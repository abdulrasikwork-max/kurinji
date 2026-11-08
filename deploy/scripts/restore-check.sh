#!/usr/bin/env bash
# Phase D12 restore check: proves a backup can be read back. It NEVER touches the live database.
#
#   restore-check.sh BACKUP_FOLDER                                  checksums, gzip/tar integrity, SQL sanity (no database needed)
#   restore-check.sh BACKUP_FOLDER --scratch-db irl_restorecheck_N  ALSO restores the dump into that scratch database and counts
#                                                                   tables + Flyway history rows, then drops the scratch database
#
# The scratch database name MUST start with irl_restorecheck_ (so it can never be the live one) and must not exist yet. The
# MySQL user needs the right to create and drop it (an admin option file: IRL_DB_ADMIN_DEFAULTS_FILE, mode 600).
# An encrypted backup (*.gpg) must be decrypted first with the owner's private key (not done here: that key is never on the server).
set -u -o pipefail

die() { echo "restore-check: $*" >&2; exit 1; }

[ $# -ge 1 ] || die "usage: $0 BACKUP_FOLDER [--scratch-db irl_restorecheck_NAME]"
dir="$1"; shift
scratch=""
if [ "${1:-}" = "--scratch-db" ]; then scratch="${2:-}"; [ -n "$scratch" ] || die "--scratch-db needs a name"; fi
[ -d "$dir" ] || die "folder not found"
ls "$dir"/*.gpg >/dev/null 2>&1 && die "this backup is encrypted: decrypt it first (restore-check does not hold your key)"
for f in db.sql.gz evidence.tar.gz media.tar.gz SHA256SUMS; do [ -f "$dir/$f" ] || die "missing $f"; done

echo "== checksums"
(cd "$dir" && sha256sum -c SHA256SUMS) || die "a checksum does not match"
echo "== archives readable"
gzip -t "$dir/db.sql.gz" || die "db.sql.gz is damaged"
tar -tzf "$dir/evidence.tar.gz" >/dev/null || die "evidence.tar.gz is damaged"
tar -tzf "$dir/media.tar.gz" >/dev/null || die "media.tar.gz is damaged"
echo "evidence entries: $(tar -tzf "$dir/evidence.tar.gz" | wc -l | tr -d ' ')   media entries: $(tar -tzf "$dir/media.tar.gz" | wc -l | tr -d ' ')"
echo "== dump sanity"
tables="$(gunzip -c "$dir/db.sql.gz" | grep -c '^CREATE TABLE')"
echo "CREATE TABLE statements in the dump: $tables"
# NOT `grep -q` after a pipe: with pipefail an early-closing grep makes gunzip fail with SIGPIPE and the check would
# wrongly fail. grep -c reads the whole stream.
flyway_lines="$(gunzip -c "$dir/db.sql.gz" | grep -c 'flyway_schema_history')"
[ "$flyway_lines" -gt 0 ] || die "the dump has no flyway_schema_history table"
[ "$tables" -ge 30 ] || die "the dump has fewer than 30 tables ($tables): incomplete?"
trailer="$(gunzip -c "$dir/db.sql.gz" | tail -n 3 | grep -c 'Dump completed')"
[ "$trailer" -gt 0 ] || echo "WARN: no 'Dump completed' trailer (mysqldump without --comments?)"

if [ -n "$scratch" ]; then
  case "$scratch" in irl_restorecheck_*) ;; *) die "scratch database name must start with irl_restorecheck_" ;; esac
  case "$scratch" in *[!A-Za-z0-9_]*) die "scratch database name may contain only letters, digits and _" ;; esac
  : "${IRL_DB_ADMIN_DEFAULTS_FILE:?set IRL_DB_ADMIN_DEFAULTS_FILE (mode 600, an admin MySQL account)}"
  command -v mysql >/dev/null || die "mysql client not found"
  exists="$(mysql --defaults-extra-file="$IRL_DB_ADMIN_DEFAULTS_FILE" -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$scratch'")" || die "cannot query MySQL"
  [ "$exists" = "0" ] || die "database $scratch already exists: choose another name (this script never reuses or drops an existing one)"
  echo "== restore into scratch database $scratch"
  mysql --defaults-extra-file="$IRL_DB_ADMIN_DEFAULTS_FILE" -e "CREATE DATABASE \`$scratch\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" || die "cannot create the scratch database"
  trap 'mysql --defaults-extra-file="$IRL_DB_ADMIN_DEFAULTS_FILE" -e "DROP DATABASE IF EXISTS \`$scratch\`" >/dev/null 2>&1' EXIT
  gunzip -c "$dir/db.sql.gz" | mysql --defaults-extra-file="$IRL_DB_ADMIN_DEFAULTS_FILE" "$scratch" || die "the restore failed"
  n="$(mysql --defaults-extra-file="$IRL_DB_ADMIN_DEFAULTS_FILE" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$scratch'")"
  v="$(mysql --defaults-extra-file="$IRL_DB_ADMIN_DEFAULTS_FILE" -N -e "SELECT COUNT(*), MAX(CAST(version AS UNSIGNED)) FROM \`$scratch\`.flyway_schema_history WHERE success=1")"
  echo "restored tables: $n   Flyway migrations (count, newest version): $v"
  echo "(the scratch database $scratch is dropped now)"
fi
echo "restore-check: OK"
