# IRL production deployment runbook (D12)

**Status: written and rehearsed on a development PC; NEVER run on a real server** (none exists yet). Parts marked
**NOT VERIFIED** were written without being able to run them here (no Linux host, nginx, certbot or Docker on that machine).
Plan, blockers and results: `docs/D12_DEPLOYMENT.md`. Nothing in this folder contains a secret, a domain or a credential:
every such value is a placeholder you generate or obtain, and secrets are generated **on the server**.

**Pilot on the owner's laptop (decision D12-3, phase L1): read `deploy/LAPTOP.md` first** — WSL2 + a Cloudflare Tunnel instead of a VPS
with certbot; it lists only the differences from this runbook (ports, `irl-tunnel.conf.template`, `irl-cloudflared.service`, Windows setup).
Everything below still applies to it unless LAPTOP.md says otherwise.

Target (the owner confirms, decision D12-1): **one Linux VPS (Ubuntu 22.04/24.04 LTS)**: systemd + nginx (TLS by certbot) +
MySQL 8 + the Python moderation service on `127.0.0.1`. One instance only: the backend's rate limits are in memory and its
evidence/photo folders are local disk.

```
internet -> nginx :443 (TLS)  -> /api/*  -> backend 127.0.0.1:8080 -> MySQL 127.0.0.1:3306 (TLS)
                              -> /v1/*   -> 404 (the moderation service is internal)
                              -> /privacy, /delete-account -> static pages (your text)
backend -> moderation service 127.0.0.1:8091 (X-Moderation-Key)      backend -> Fast2SMS (HTTPS, only for sign-up codes)
```

## 0. What you need first (blockers, `docs/D12_DEPLOYMENT.md` §1)
A server with SSH, a domain with an A record to it, an e-mail for the certificate, a Fast2SMS account (first top-up + balance for
Quick SMS at about Rs 7 per code + API key), a release keystore, the privacy-policy and account-deletion page text, and a backup destination.

## 1. Server preparation (NOT VERIFIED)
```bash
sudo apt update && sudo apt install -y openjdk-17-jre-headless python3 mysql-server nginx certbot gnupg
sudo adduser --system --group --no-create-home irl        # the backend runs as irl
sudo adduser --system --group --no-create-home irlmod     # the moderation service runs as irlmod
sudo install -d -o irl -g irl -m 700 /var/lib/irl/evidence /var/lib/irl/media
sudo install -d -o root -g root -m 755 /opt/irl/backend /opt/irl/moderation-service /opt/irl/deploy/scripts /var/www/irl /var/www/certbot
sudo install -d -o irl -g irl -m 700 /var/backups/irl
sudo install -d -m 750 /etc/irl
# firewall: only SSH, 80, 443 (3306, 8080 and 8091 stay closed: they listen on 127.0.0.1 anyway)
sudo ufw allow OpenSSH && sudo ufw allow 80,443/tcp && sudo ufw enable
```

## 2. MySQL with TLS (NOT VERIFIED)
Edit `deploy/sql/create-production-db.sql.example` (placeholders only), run it as admin, delete your copy. In the MySQL
config set `require_secure_transport=ON` and `bind-address=127.0.0.1`; restart MySQL. Test: the `safesphere` user must NOT
be able to connect without TLS.

## 3. Build and install the artifacts
On your PC (this part works; the commands are the project's own):
```bash
cd backend && mvn -DskipTests package                    # target/safesphere-backend-<version>.jar  (the version in the name is irrelevant)
```
Copy to the server and install:
```bash
sudo install -o root -g root -m 644 safesphere-backend-*.jar /opt/irl/backend/app.jar
# the moderation service: the folder WITHOUT restricted/, tests/ and tools/ (restricted/ is git-ignored and not needed at run time)
sudo rsync -a --exclude restricted --exclude tests --exclude tools --exclude __pycache__ moderation-service/ /opt/irl/moderation-service/
sudo install -o root -g root -m 755 deploy/scripts/*.sh /opt/irl/deploy/scripts/
sudo install -m 644 deploy/systemd/*.service deploy/systemd/*.timer /etc/systemd/system/
```

## 4. Secrets and configuration (ON THE SERVER)
```bash
sudo install -m 600 -o root -g root deploy/env/backend.env.example    /etc/irl/backend.env
sudo install -m 600 -o root -g root deploy/env/moderation.env.example /etc/irl/moderation.env
# generate each secret here, paste it into the file (never into chat, git or a ticket):
openssl rand -base64 48   # JWT_SECRET        openssl rand -base64 48   # OTP_SECRET (a DIFFERENT value)
openssl rand -base64 36   # DB_PASSWORD       openssl rand -base64 32   # MODERATION_SERVICE_KEY: the SAME value in both env files
sudoedit /etc/irl/backend.env /etc/irl/moderation.env
sudo /opt/irl/deploy/scripts/preflight.sh /etc/irl/backend.env /etc/irl/moderation.env    # must print RESULT: 0 failed
```
`preflight.sh` prints names and lengths only, never a value. The backend ALSO refuses to start in the `prod` profile if SMS
is DEV_FILE, `OTP_SECRET` is empty, `DB_URL` has no TLS, or a storage folder is a relative path (`ProductionGuard`, tested).

## 5. Start the services (NOT VERIFIED)
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now irl-moderation irl-backend
journalctl -u irl-backend -n 80 --no-pager      # expect: Flyway "Successfully applied" (first start: V1..V30), "Production guard passed",
                                                # no "Automatic content check is OFF" warning, then "Started SafeSphereBackendApplication"
curl -s http://127.0.0.1:8080/api/health        # {"status":"UP"}
```
**Migrations** run by themselves at every start (Flyway; never edit an applied migration). First start on the empty database
applies all of them. This has only ever been rehearsed on a database that already existed (see `docs/D12_DEPLOYMENT.md` §5).

## 6. TLS and nginx (NOT VERIFIED)
Follow the header of `deploy/nginx/irl-bootstrap.conf.template` (port-80 site, `certbot certonly --webroot`), then
`deploy/nginx/irl.conf.template` (`envsubst '${IRL_DOMAIN}'`, `nginx -t`, reload). Put your two pages at
`/var/www/irl/privacy.html` and `/var/www/irl/delete-account.html`. Check `systemctl list-timers | grep certbot` (renewal).
Then, from any machine:
```bash
deploy/scripts/smoke-test.sh https://<your domain> --check-redirect
```

## 7. Backups and restore check (backup run rehearsed locally; the timer and encryption are NOT VERIFIED)
```bash
# /etc/irl/backup.env (mode 600): IRL_BACKUP_DIR=/var/backups/irl  IRL_DB_DEFAULTS_FILE=/etc/irl/backup.my.cnf
#   EVIDENCE_STORAGE_DIR=/var/lib/irl/evidence  SOCIAL_MEDIA_DIR=/var/lib/irl/media  [IRL_BACKUP_GPG_RECIPIENT=<key id>]
# /etc/irl/backup.my.cnf (mode 600):  [client]  user=<backup account>  password=<its password>
sudo systemctl enable --now irl-backup.timer           # daily 03:30
sudo /opt/irl/deploy/scripts/backup.sh                 # a run now; then:
/opt/irl/deploy/scripts/restore-check.sh /var/backups/irl/irl-backup-<stamp>
```
Copy backups OFF the server (another disk, host or storage: your choice). Without `IRL_BACKUP_GPG_RECIPIENT` they are
**not encrypted** and contain personal data and ciphertext evidence: protect the destination. A restore into a scratch
database needs admin rights: `restore-check.sh ... --scratch-db irl_restorecheck_1`.

**Deploying a new version:** `backup.sh`, stop `irl-backend`, replace `app.jar`, start, read the log for Flyway, run the smoke
test. Rolling back = restore the database backup AND the previous jar (Flyway only goes forward).

## 8. Monitoring (NOT VERIFIED)
Point an external HTTPS monitor at `https://<domain>/api/health` (expects 200 `{"status":"UP"}`; 503 `DOWN` when MySQL does not
answer). Also watch: free disk (media and evidence grow), the certificate expiry date, `journalctl -u irl-backend -p warning`,
and that the last backup is less than 25 hours old.

## 9. The Android app against production
```bash
cd android
./gradlew assembleRelease -Prelease.api.base.url=https://<your domain>/      # + keystore.properties or IRL_KEYSTORE_*
```
The build fails if the URL is not https, and a SIGNED build refuses the placeholder. Install the signed APK on the phone and
sign up with a real number. Until the server exists, the phone can only use the debug build over `adb reverse` against a
local backend (`CLAUDE.md` §12).
