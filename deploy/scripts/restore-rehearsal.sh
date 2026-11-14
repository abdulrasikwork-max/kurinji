#!/usr/bin/env bash
# Pilot gate before a migration: ONE fresh backup of the live database + files, then a REHEARSED RESTORE of that backup into a
# scratch database, compared with the live database table by table. Run by the owner, as root, on the pilot host:
#
#   sudo bash deploy/scripts/restore-rehearsal.sh
#
# What it does to the LIVE database: reads it (mysqldump --single-transaction, SELECT COUNT(*), CHECKSUM TABLE). It never
# writes to it, never stops a service, never deletes an existing backup. It creates ONE scratch database named
# irl_restorecheck_<time> (the name can never be the live one), loads the dump into it, compares, and drops it again.
#
# What it leaves: the backup folder in IRL_REHEARSAL_DIR (default /var/backups/irl-rehearsal, root only, mode 700).
# That backup is PLAIN (not encrypted) and on the SAME disk as the database: it protects against a bad migration, NOT against
# losing the laptop. It contains personal data: do not copy it anywhere unencrypted.
#
# It prints table names and row counts only: no row content, no account names, no secrets.
#
# Environment (all optional):
#   IRL_REHEARSAL_DIR           where the backup folder goes               (default /var/backups/irl-rehearsal)
#   IRL_DB_NAME                 the live database name                     (default: read from DB_URL in /etc/irl/backend.env, else safesphere)
#   IRL_DB_ADMIN_DEFAULTS_FILE  a MySQL options file (mode 600) of an admin account; without it root signs in through the socket
#   EVIDENCE_STORAGE_DIR        (default /var/lib/irl/evidence)
#   SOCIAL_MEDIA_DIR            (default /var/lib/irl/media)
set -u -o pipefail
umask 077

die() { echo "rehearsal: FAIL: $*" >&2; exit 1; }
say() { echo "rehearsal: $*"; }

[ "$(id -u)" = "0" ] || die "run it with sudo (it reads the database and the storage folders as root)"
here="$(cd "$(dirname "$0")" && pwd)"
[ -f "$here/backup.sh" ] && [ -f "$here/restore-check.sh" ] || die "backup.sh and restore-check.sh must be next to this script"
for c in mysql mysqldump gzip gunzip tar sha256sum; do command -v "$c" >/dev/null || die "$c not found"; done

BK="${IRL_REHEARSAL_DIR:-/var/backups/irl-rehearsal}"
EVID="${EVIDENCE_STORAGE_DIR:-/var/lib/irl/evidence}"
MEDIA="${SOCIAL_MEDIA_DIR:-/var/lib/irl/media}"

work="$(mktemp -d /root/irl-rehearsal.XXXXXX)" || die "cannot create a work folder"
scratch=""
cnf="${IRL_DB_ADMIN_DEFAULTS_FILE:-}"
if [ -z "$cnf" ]; then
  cnf="$work/root.my.cnf"
  printf '[client]\nuser=root\n' > "$cnf"
  chmod 600 "$cnf"
fi
[ -f "$cnf" ] || die "IRL_DB_ADMIN_DEFAULTS_FILE not found"

q() { mysql --defaults-extra-file="$cnf" -N -B -e "$1"; }

cleanup() {
  if [ -n "$scratch" ]; then
    q "DROP DATABASE IF EXISTS \`$scratch\`" >/dev/null 2>&1 || echo "rehearsal: WARN: could not drop $scratch: drop it by hand" >&2
  fi
  rm -rf "$work"
}
trap cleanup EXIT

q "SELECT 1" >/dev/null 2>&1 || die "cannot sign in to MySQL as an admin (root through the socket did not work: set IRL_DB_ADMIN_DEFAULTS_FILE)"

# ---- which database
DB="${IRL_DB_NAME:-}"
if [ -z "$DB" ] && [ -f /etc/irl/backend.env ]; then
  # Only the name between the last "/" and the "?" of DB_URL is taken; the line itself is never printed.
  DB="$(sed -n 's/^DB_URL=.*\/\([A-Za-z0-9_]*\)\(?.*\)\{0,1\}$/\1/p' /etc/irl/backend.env | head -n 1)"
fi
DB="${DB:-safesphere}"
case "$DB" in ''|*[!A-Za-z0-9_]*) die "unexpected database name" ;; esac
case "$DB" in irl_restorecheck_*) die "the live database cannot be a scratch name" ;; esac
[ "$(q "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB'")" = "1" ] || die "database $DB not found"

say "MySQL $(q 'SELECT VERSION()')   live database: $DB"
live_flyway="$(q "SELECT CONCAT(COUNT(*), ' applied, newest V', MAX(CAST(version AS UNSIGNED))) FROM \`$DB\`.flyway_schema_history WHERE success=1")" \
  || die "cannot read flyway_schema_history"
say "live Flyway history: $live_flyway"

q "SELECT table_name FROM information_schema.tables WHERE table_schema='$DB' AND table_type='BASE TABLE' ORDER BY table_name" > "$work/tables" \
  || die "cannot list the tables"
ntables="$(wc -l < "$work/tables" | tr -d ' ')"
[ "$ntables" -ge 30 ] || die "only $ntables tables in $DB: is this the right database?"

# counts SCHEMA OUTFILE: one "table rows" line per live table.
counts() {
  local schema="$1" out="$2" t n
  : > "$out"
  while read -r t; do
    case "$t" in ''|*[!A-Za-z0-9_]*) die "unexpected table name in the list" ;; esac
    n="$(q "SELECT COUNT(*) FROM \`$schema\`.\`$t\`")" || die "cannot count $schema.$t"
    printf '%s %s\n' "$t" "$n" >> "$out"
  done < "$work/tables"
}
# sums SCHEMA OUTFILE: one "table checksum" line per live table.
sums() {
  local schema="$1" out="$2" t n
  : > "$out"
  while read -r t; do
    n="$(q "CHECKSUM TABLE \`$schema\`.\`$t\`" | awk '{print $2}')" || die "cannot checksum $schema.$t"
    printf '%s %s\n' "$t" "$n" >> "$out"
  done < "$work/tables"
}

say "counting the live rows (before the dump)"
counts "$DB" "$work/before"
media_before="$(find "$MEDIA" -type f | wc -l | tr -d ' ')"
evid_before="$(find "$EVID" -type f | wc -l | tr -d ' ')"

# ---- 1. the backup (plain, kept)
install -d -m 700 -o root -g root "$BK" || die "cannot create $BK"
say "step 1: backup into $BK"
IRL_BACKUP_DIR="$BK" IRL_DB_DEFAULTS_FILE="$cnf" IRL_DB_NAME="$DB" EVIDENCE_STORAGE_DIR="$EVID" SOCIAL_MEDIA_DIR="$MEDIA" \
  IRL_BACKUP_KEEP_DAYS=0 IRL_BACKUP_GPG_RECIPIENT="" bash "$here/backup.sh" 2>&1 | tee "$work/backup.log" || die "backup.sh failed"
dest="$(sed -n 's/^backup: done -> //p' "$work/backup.log" | tail -n 1)"
[ -n "$dest" ] && [ -d "$dest" ] || die "backup.sh did not report a finished folder"

say "counting the live rows (after the dump)"
counts "$DB" "$work/after"
sums "$DB" "$work/livesum"

# ---- 2. the backup can be read
say "step 2: checksums and archives"
bash "$here/restore-check.sh" "$dest" || die "restore-check.sh failed"
media_arch="$(tar -tzf "$dest/media.tar.gz" | grep -vc '/$')"
evid_arch="$(tar -tzf "$dest/evidence.tar.gz" | grep -vc '/$')"

# ---- 3. the rehearsed restore
scratch_name="irl_restorecheck_$(date -u +%Y%m%d_%H%M%S)"
[ "$(q "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$scratch_name'")" = "0" ] || die "$scratch_name already exists"
say "step 3: restore into the scratch database $scratch_name"
q "CREATE DATABASE \`$scratch_name\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" || die "cannot create the scratch database"
scratch="$scratch_name"
t0="$(date +%s)"
gunzip -c "$dest/db.sql.gz" | mysql --defaults-extra-file="$cnf" "$scratch" || die "the restore failed"
say "the dump loaded in $(( $(date +%s) - t0 )) s"

# ---- 4. compare
fail=0; warn=0
sn="$(q "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$scratch' AND table_type='BASE TABLE'")"
if [ "$sn" = "$ntables" ]; then say "tables: $sn restored, $ntables live  OK"; else say "tables: $sn restored, $ntables live  MISMATCH"; fail=1; fi
scratch_flyway="$(q "SELECT CONCAT(COUNT(*), ' applied, newest V', MAX(CAST(version AS UNSIGNED))) FROM \`$scratch\`.flyway_schema_history WHERE success=1")"
if [ "$scratch_flyway" = "$live_flyway" ]; then say "Flyway history: $scratch_flyway  OK"; else say "Flyway history: restored '$scratch_flyway', live '$live_flyway'  MISMATCH"; fail=1; fi

counts "$scratch" "$work/restored"
sums "$scratch" "$work/restoredsum"
declare -A B A S LS RS
while read -r t n; do B[$t]="$n"; done < "$work/before"
while read -r t n; do A[$t]="$n"; done < "$work/after"
while read -r t n; do S[$t]="$n"; done < "$work/restored"
while read -r t n; do LS[$t]="$n"; done < "$work/livesum"
while read -r t n; do RS[$t]="$n"; done < "$work/restoredsum"
same=0; moved=0; rows=0; sumdiff=0
printf '%-36s %10s %10s  %s\n' "table" "live" "restored" "result"
while read -r t; do
  if [ "${B[$t]}" != "${A[$t]}" ]; then
    # Someone used the app while the script ran: the dump is one consistent moment between the two counts, so an exact match
    # cannot be demanded for this table. Reported, not failed; run again at a quiet moment for a clean result.
    res="CHANGED DURING THE RUN (live ${B[$t]} -> ${A[$t]})"; moved=$((moved + 1))
  elif [ "${S[$t]}" != "${B[$t]}" ]; then
    res="ROW COUNT MISMATCH"; fail=1
  elif [ "${LS[$t]}" != "${RS[$t]}" ]; then
    res="same rows, CONTENT CHECKSUM DIFFERS"; sumdiff=$((sumdiff + 1))
  else
    res="OK"; same=$((same + 1))
  fi
  rows=$((rows + ${S[$t]}))
  printf '%-36s %10s %10s  %s\n' "$t" "${B[$t]}" "${S[$t]}" "$res"
done < "$work/tables"
say "tables identical (rows + checksum): $same of $ntables; changed during the run: $moved; checksum differences: $sumdiff; rows restored: $rows"
if [ "$sumdiff" -gt 0 ]; then
  # Row counts equal but CHECKSUM TABLE differs: either a row was edited during the run or the restore changed a value.
  say "WARN: a checksum difference needs a look (was the app used during the run? run again at a quiet moment)"; warn=1
fi
[ "$moved" -eq 0 ] || warn=1

if [ "$media_arch" = "$media_before" ]; then say "media files: $media_arch in the archive, $media_before in the folder  OK"
else say "WARN: media files: $media_arch in the archive, $media_before in the folder before the run"; warn=1; fi
if [ "$evid_arch" = "$evid_before" ]; then say "evidence files: $evid_arch in the archive, $evid_before in the folder  OK"
else say "WARN: evidence files: $evid_arch in the archive, $evid_before in the folder before the run"; warn=1; fi

say "the scratch database $scratch is dropped now; the live database was only read"
say "backup kept (PLAIN, same disk, root only): $dest"
if [ "$fail" -ne 0 ]; then
  echo "REHEARSAL: FAIL  (do not migrate)"
  exit 1
elif [ "$warn" -ne 0 ]; then
  echo "REHEARSAL: PASS WITH WARNINGS  (read the WARN / CHANGED lines above; run again at a quiet moment for a clean PASS)"
else
  echo "REHEARSAL: PASS  ($ntables tables, $rows rows, Flyway $live_flyway)"
fi
