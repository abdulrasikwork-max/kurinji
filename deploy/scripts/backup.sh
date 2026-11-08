#!/usr/bin/env bash
# Phase D12 backup: ONE run = the MySQL dump, then the evidence and photo folders, into one time-stamped folder.
#
# Order matters: the database is dumped FIRST, the files AFTER. Files are write-once (random names), so a file saved between
# the two steps is at worst an orphan (the backend's clean-up removes those); the other order could leave database rows that
# point at files the archive does not contain.
#
# Environment (normally from /etc/irl/backup.env, mode 600; see deploy/README.md). NO secret is ever passed on a command line:
#   IRL_BACKUP_DIR           where backups go (must exist, be writable, ideally another disk)      REQUIRED
#   IRL_DB_DEFAULTS_FILE     a MySQL client options file (mode 600) with [client] user= and password=   REQUIRED
#   IRL_DB_NAME              database name (default: safesphere)
#   EVIDENCE_STORAGE_DIR     the backend's evidence folder (absolute)                              REQUIRED
#   SOCIAL_MEDIA_DIR         the backend's photo/video folder (absolute)                           REQUIRED
#   IRL_BACKUP_GPG_RECIPIENT optional: encrypt with this GnuPG public key (the key is the owner's decision); then the
#                            plain files are removed. Without it the backup is NOT encrypted: protect the destination.
#   IRL_BACKUP_KEEP_DAYS     delete irl-backup-* folders older than this many days (default 14; 0 = keep all)
set -u -o pipefail
umask 077

die() { echo "backup: $*" >&2; exit 1; }

: "${IRL_BACKUP_DIR:?set IRL_BACKUP_DIR}"
: "${IRL_DB_DEFAULTS_FILE:?set IRL_DB_DEFAULTS_FILE}"
: "${EVIDENCE_STORAGE_DIR:?set EVIDENCE_STORAGE_DIR}"
: "${SOCIAL_MEDIA_DIR:?set SOCIAL_MEDIA_DIR}"
DB="${IRL_DB_NAME:-safesphere}"
KEEP="${IRL_BACKUP_KEEP_DAYS:-14}"

[ -d "$IRL_BACKUP_DIR" ] || die "IRL_BACKUP_DIR does not exist"
[ -w "$IRL_BACKUP_DIR" ] || die "IRL_BACKUP_DIR is not writable"
[ -f "$IRL_DB_DEFAULTS_FILE" ] || die "IRL_DB_DEFAULTS_FILE not found"
case "$EVIDENCE_STORAGE_DIR" in /*) ;; *) die "EVIDENCE_STORAGE_DIR must be absolute" ;; esac
case "$SOCIAL_MEDIA_DIR" in /*) ;; *) die "SOCIAL_MEDIA_DIR must be absolute" ;; esac
[ -d "$EVIDENCE_STORAGE_DIR" ] || die "EVIDENCE_STORAGE_DIR does not exist"
[ -d "$SOCIAL_MEDIA_DIR" ] || die "SOCIAL_MEDIA_DIR does not exist"
case "$KEEP" in ''|*[!0-9]*) die "IRL_BACKUP_KEEP_DAYS must be a number" ;; esac
command -v mysqldump >/dev/null || die "mysqldump not found"
if [ -n "${IRL_BACKUP_GPG_RECIPIENT:-}" ]; then command -v gpg >/dev/null || die "gpg not found (IRL_BACKUP_GPG_RECIPIENT is set)"; fi

# The defaults file holds a password: refuse a world-readable one (Linux only; elsewhere the check is skipped).
if [ "$(uname -s)" = "Linux" ]; then
  m="$(stat -c %a "$IRL_DB_DEFAULTS_FILE")"
  case "$m" in 400|600) ;; *) die "IRL_DB_DEFAULTS_FILE must have mode 600 (it is $m)" ;; esac
fi

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
dest="$IRL_BACKUP_DIR/irl-backup-$stamp"
mkdir "$dest" || die "cannot create $dest"
trap 'rc=$?; if [ $rc -ne 0 ]; then echo "backup: FAILED (exit $rc), removing the incomplete folder" >&2; rm -rf "$dest"; fi' EXIT

echo "backup: database dump (single transaction, utf8mb4)"
# --defaults-extra-file MUST be the first option. No password on the command line.
mysqldump --defaults-extra-file="$IRL_DB_DEFAULTS_FILE" --single-transaction --quick --routines --triggers \
  --default-character-set=utf8mb4 --no-tablespaces "$DB" | gzip -9 > "$dest/db.sql.gz" || die "mysqldump failed"
gzip -t "$dest/db.sql.gz" || die "the dump is not a valid gzip file"

echo "backup: evidence folder"
tar -czf "$dest/evidence.tar.gz" -C "$(dirname "$EVIDENCE_STORAGE_DIR")" "$(basename "$EVIDENCE_STORAGE_DIR")" || die "tar (evidence) failed"
echo "backup: media folder"
tar -czf "$dest/media.tar.gz" -C "$(dirname "$SOCIAL_MEDIA_DIR")" "$(basename "$SOCIAL_MEDIA_DIR")" || die "tar (media) failed"

# Checksums are written BEFORE any encryption so restore-check can verify the plain files; with encryption they are of the
# encrypted files (what is stored).
if [ -n "${IRL_BACKUP_GPG_RECIPIENT:-}" ]; then
  for f in db.sql.gz evidence.tar.gz media.tar.gz; do
    gpg --batch --yes --trust-model always --encrypt --recipient "$IRL_BACKUP_GPG_RECIPIENT" --output "$dest/$f.gpg" "$dest/$f" || die "gpg failed for $f"
    rm -f "$dest/$f"
  done
  (cd "$dest" && sha256sum ./*.gpg > SHA256SUMS)
  echo "backup: encrypted for the configured recipient (the plain files were removed)"
else
  (cd "$dest" && sha256sum ./*.gz > SHA256SUMS)
  echo "backup: NOT encrypted (IRL_BACKUP_GPG_RECIPIENT unset): protect the destination"
fi

if [ "$KEEP" -gt 0 ]; then
  find "$IRL_BACKUP_DIR" -mindepth 1 -maxdepth 1 -type d -name 'irl-backup-*' -mtime +"$KEEP" -exec rm -rf {} + 2>/dev/null
fi

trap - EXIT
echo "backup: done -> $dest"
for f in "$dest"/*; do printf 'backup:   %s bytes  %s\n' "$(wc -c < "$f" | tr -d ' ')" "$(basename "$f")"; done
