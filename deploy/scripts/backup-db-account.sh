#!/usr/bin/env bash
# Phase D12 backup setup, ONE-TIME: creates the least-privilege MySQL account `irl_backup` and writes its login file
# /etc/irl/backup.my.cnf (mode 0400, root:root; irl-backup.service loads it with LoadCredential=).
#
#   sudo bash deploy/scripts/backup-db-account.sh
#
# The password is generated here (openssl), goes to MySQL on STDIN (never as an argument, so it is not in `ps`) and into the file.
# It is NEVER printed, logged or asked for. The script refuses to run twice (an existing account or file stops it) and changes
# nothing else. MySQL root must be reachable over the local socket without a password (auth_socket); if it is not, the script stops
# and you decide how to proceed: do not type a password into it.
#
# Privileges (what mysqldump --single-transaction --routines --triggers --no-tablespaces needs, nothing that writes):
#   SELECT, SHOW VIEW, TRIGGER on the one database. The account REQUIRES TLS and is reachable from this machine only.
set -eu -o pipefail
umask 077

die() { echo "backup-db-account: $*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || die "run with sudo"
command -v mysql >/dev/null || die "mysql client not found"
command -v mysqldump >/dev/null || die "mysqldump not found"
command -v openssl >/dev/null || die "openssl not found"

DB="${IRL_DB_NAME:-safesphere}"
PORT="${IRL_DB_PORT:-3307}"
ACCOUNT="irl_backup"
CNF="/etc/irl/backup.my.cnf"
case "$DB" in ''|*[!A-Za-z0-9_]*) die "IRL_DB_NAME may contain only letters, digits and _" ;; esac
case "$PORT" in ''|*[!0-9]*) die "IRL_DB_PORT must be a number" ;; esac

[ -d /etc/irl ] || die "/etc/irl does not exist"
[ ! -e "$CNF" ] || die "$CNF already exists: refusing to overwrite it (move it away on purpose first)"
mysql -N -e "SELECT 1" >/dev/null 2>&1 || die "cannot log in as MySQL root over the local socket without a password: stopped (no password is ever typed into this script)"
[ "$(mysql -N -e "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$DB'")" = "1" ] || die "database '$DB' not found (set IRL_DB_NAME)"
[ "$(mysql -N -e "SELECT COUNT(*) FROM mysql.user WHERE user='$ACCOUNT'")" = "0" ] || die "a MySQL account named $ACCOUNT already exists: refusing"

# 32 letters and digits only: nothing to escape in SQL or in the options file.
pw="$(openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-32)"
[ "${#pw}" -eq 32 ] || die "could not generate a password"

# The statements go in on stdin. Both host spellings are created because a TCP connection to 127.0.0.1 is matched as 'localhost' or
# as '127.0.0.1' depending on the server's name-resolution setting; both are loopback only.
mysql <<SQL
CREATE USER '$ACCOUNT'@'localhost'  IDENTIFIED BY '$pw' REQUIRE SSL;
CREATE USER '$ACCOUNT'@'127.0.0.1'  IDENTIFIED BY '$pw' REQUIRE SSL;
GRANT SELECT, SHOW VIEW, TRIGGER ON \`$DB\`.* TO '$ACCOUNT'@'localhost';
GRANT SELECT, SHOW VIEW, TRIGGER ON \`$DB\`.* TO '$ACCOUNT'@'127.0.0.1';
SQL

# 0400: umask 277 so the file never exists with wider rights.
( umask 277; printf '[client]\nuser=%s\npassword=%s\nhost=127.0.0.1\nport=%s\nssl-mode=REQUIRED\n' "$ACCOUNT" "$pw" "$PORT" > "$CNF" )
unset pw
chown root:root "$CNF"; chmod 0400 "$CNF"
echo "wrote $CNF: $(stat -c '%a %U:%G' "$CNF")"

echo "== check 1: the account can log in over TCP with TLS"
mysql --defaults-extra-file="$CNF" -N -e "SELECT CURRENT_USER()" || die "the new account cannot log in (a MySQL error is shown above; nothing else was changed)"
cipher="$(mysql --defaults-extra-file="$CNF" -N -e "SHOW SESSION STATUS LIKE 'Ssl_cipher'" | awk '{print $2}')"
[ -n "$cipher" ] && echo "TLS in use: yes ($cipher)" || die "TLS is NOT in use"
echo "== check 2: its grants (no secret in this output)"
mysql --defaults-extra-file="$CNF" -N -e "SHOW GRANTS FOR CURRENT_USER()"
echo "== check 3: a schema-only dump with exactly the flags backup.sh uses"
if mysqldump --defaults-extra-file="$CNF" --single-transaction --quick --routines --triggers --default-character-set=utf8mb4 --no-tablespaces --no-data "$DB" > /dev/null; then
  echo "PASS: mysqldump (schema only) works with this account"
else
  die "mysqldump failed with this account (the error is shown above)"
fi
echo "backup-db-account: OK (the password was not printed anywhere)"
