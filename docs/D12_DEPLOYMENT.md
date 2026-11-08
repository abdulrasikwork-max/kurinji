# D12: production deployment (closed pilot) — plan, artifacts, rehearsal, blockers

Written 2026-11-08. **Decision D12** (`EXPERIENCE_PLATFORM_DESIGN.md` §15, decided 2026-10-22): before a closed pilot, deploy an
HTTPS server with image storage, backups and a privacy policy. This file is the plan, what was built, what was rehearsed, and
what is blocked. Nothing here was deployed: **no server, domain, SMS account or production database exists yet**, so D12 can end
today only in (1) production artifacts, (2) a production-like rehearsal on this machine, (3) an exact list of blockers.
Status vocabulary as in CLAUDE.md §3. Nothing was committed or pushed.

## 1. Blockers (what stops each stage; what the user provides)

| # | Blocker | Stops | What the user provides |
|---|---|---|---|
| B1 | **A Linux server** (single VPS, Ubuntu LTS 22.04/24.04) | everything that runs in production | Provider and plan (suggest ≥ 2 vCPU, 4 GB RAM, ≥ 60 GB disk: media + evidence grow), root/SSH access (or install the artifacts yourself from `deploy/README.md`) |
| B2 | **A domain + DNS A record** to the server's IP | TLS, the app's API address | A domain you control, the A record, an e-mail address for the certificate (Let's Encrypt expiry notices) |
| B3 | **Production MySQL 8 with TLS** | the backend's start (the guard requires `sslMode`) | Either MySQL on the same server with `require_secure_transport=ON`, or a managed instance plus its CA file (`VERIFY_CA`) |
| B4 | **SMS/OTP provider account** (Fast2SMS is the one adapter) | sign-up of new accounts (`prod` profile refuses DEV_FILE) | Fast2SMS account with KYC + the first Rs 100 top-up, then its API key (set on the server only); DLT only if you want a branded sender |
| B5 | **Release signing key** | a signed APK for the phone | Generate and back up a release keystore (`PHASE17J.md` §5) |
| B6 | **Privacy policy + account-deletion page text** | the pilot opening; Play forms | The final text (draft: `RELEASE_PRIVACY_PLAY.md`); nginx serves static pages from `deploy/` |
| B7 | **Backup destination (+ optional encryption key)** | off-server backups | Where backups go (second disk, object storage, another host) and, if encrypted, a recipient public key |
| B8 | **Uptime monitor choice** | alerts | A monitor (any external HTTPS checker) pointed at `https://<domain>/api/health` |
| B9 | Play Console declarations | Play upload | `PHASE17J.md` §5 / `RELEASE_PRIVACY_PLAY.md` §3 |
| B10 | Empty scratch MySQL database (rehearsal only) | from-scratch migration V1-V30 + restore rehearsal | Permission or admin credentials (the dev `safesphere` user cannot create a database) |

## 2. Design choice (the user confirms; decision D12-1)

**One Linux VPS: systemd services + nginx (TLS via certbot) + MySQL 8 + the Python moderation service on 127.0.0.1.**
The code decides this: rate limits are in memory per instance, and evidence and photos live on a local disk, so multi-instance
platforms and PaaS with ephemeral disks would break both. No Docker (not installed here, nothing could be tested, and a new tool
nobody approved). Spring Boot 3.5.16 stays (its upgrade is separate).

## 3. Stages and status

| Stage | What | Status |
|---|---|---|
| 0 | This plan | written |
| 1 | Backend: `GET /api/health` (no new dependency), prod bind address `127.0.0.1`, tests, `API.md` | **implemented, compiled, tested** (backend 895 / 0) |
| 2 | `deploy/`: env templates, systemd units, nginx templates, preflight / backup / restore-check / smoke-test scripts, `.gitattributes` | **written; scripts tested locally; nginx and systemd NOT VERIFIED** |
| 3 | Local rehearsal on real MySQL (prod guard, functional run with the Python service, smoke test, backup) | **done on this PC; from-scratch migration and restore NOT done (B10)** |
| 4 | Docs and report | this file, `HANDOFF`, `STATUS`, `DECISIONS`, `CLAUDE.md` |

## 4. What was built (all uncommitted)

**Backend (the only code change):** `common/health/HealthController` = `GET /api/health`, public, GET only: `200 {"status":"UP"}` while the
database answers a 2-second ping, else `503 {"status":"DOWN"}`; no version, no exception text, no time, `no-store`, no CORS (`SecurityConfig`
`permitAll` for that one GET; `/api/health/details` and POST stay `401`). `application-prod.properties`: `server.address=${SERVER_ADDRESS:127.0.0.1}`
(the development default listens on every interface: the phone-test session showed port 8080 open to the whole network). `API.md` + its index.
Tests: `HealthApiTest` (4: public and exact body, GET only and sub-path protected, DB failure -> 503 DOWN without a reason, invalid connection -> DOWN),
`ProductionGuardTest` extended (bind address).

**`deploy/` (nothing in it is a secret, a domain or a credential):** `README.md` (runbook), `env/backend.env.example` and `env/moderation.env.example`
(every variable, no values, generation commands to run ON THE SERVER), `systemd/` (backend, moderation service, backup service + daily timer; dedicated
users, hardening, no secrets), `nginx/` (bootstrap port-80 site + the production site: TLS 1.2+, HSTS, 60 MB uploads, streaming, `X-Forwarded-For` SET
not passed, `/v1/` not proxied, privacy-friendly access log without client address), `scripts/` (`preflight.sh`, `backup.sh`, `restore-check.sh`,
`smoke-test.sh`), `sql/create-production-db.sql.example`. `.gitattributes` (LF for `*.sh` and `deploy/**`).

## 5. Verification

**Tests:** backend `mvn test` **895 run, 0 failures, 0 errors, 2 skipped** (was 891; +4); Python **83 OK**; Android not touched by D12.
**Local rehearsal on this PC (real MySQL 8.0 with TLS 1.3, the real jar, the real Python service; nothing external contacted):**
- MySQL: `have_ssl=YES`; a `sslMode=REQUIRED` connection negotiated TLSv1.3; the dev database has all 30 migrations applied.
- **Production guard:** the jar with `SPRING_PROFILES_ACTIVE=prod`, a TLS database URL, a generated `OTP_SECRET`, absolute storage folders and
  `OTP_PROVIDER=DEV_FILE` refused to start with **exactly one problem** (the SMS provider): every other production setting passes on the real start-up path.
  No secret value appeared in the log.
- **Functional run** (same settings, default profile, Python service on 127.0.0.1:8091 with a throwaway key): Flyway validated 30 migrations ("up to date"),
  the backend listened on **127.0.0.1 only**, no "automatic check is OFF" warning, the Python service answered `UP` and `401` without the key; a public post made
  through the backend was assessed by the Python service (`/v1/assess` 200, assessment DONE / PUBLISH / CLEAN, the service log has no text) and then deleted.
- **`smoke-test.sh`** against that backend: 12 PASS, 2 SKIP (TLS and HSTS need https), exit 0; against the wrong service: 7 FAIL, exit 1; `http://` without
  `--allow-http`: refused; nothing listening: a clean failure; no URL: usage (no default is invented).
- **`backup.sh`** against the dev database (options file, no password on a command line): `db.sql.gz` (46 tables, 121,763 bytes), the evidence and photo archives,
  `SHA256SUMS`; **`restore-check.sh`** passes on it, rejects a tampered archive, and refuses a scratch database that is not named `irl_restorecheck_...` or
  contains anything but letters, digits and `_`. The backup and the options file were deleted afterwards.
- **`preflight.sh`**: a valid synthetic pair -> 0 failed (warnings: Linux-only permission check skipped here, no vault key); a deliberately broken pair -> 19
  failed; neither run printed any value.
- **Android:** `-Prelease.api.base.url=https://rehearsal.invalid/` reaches `BuildConfig.API_BASE_URL`; an `http://` value still fails the build.
- Two script bugs found and fixed by this testing (a `grep -q` under `pipefail` made restore-check fail on a good dump; a size listing).
- `bash -n` passes for all four scripts.

**NOT VERIFIED (no Linux host, nginx, certbot, Docker or `shellcheck` on this PC):** `nginx -t` of the templates, the systemd units, certbot, the Linux-only
permission checks in `preflight.sh` and `backup.sh`, the GnuPG encryption path and the backup timer, the monitoring, a restore into a scratch database, and
**a from-scratch run of migrations V1-V30** (they have only ever been applied incrementally; it needs an empty database, B10). Real SMS/OTP was not run and no
provider was contacted.

## 6. Exactly what to do next, per blocker

| Blocker | Your action |
|---|---|
| B1 server | Rent a Linux server (Ubuntu 22.04/24.04 LTS, at least 2 vCPU, 4 GB RAM, 60 GB disk) and tell me the provider and how I may reach it (SSH user + key), or follow `deploy/README.md` §1-§8 yourself |
| B2 domain + TLS | Own a domain; create an A record to the server's IP; give me the domain and an e-mail for Let's Encrypt (the certificate itself is requested ON the server, `deploy/README.md` §6) |
| B3 production MySQL | On the server: MySQL 8 with `require_secure_transport=ON`, run `deploy/sql/create-production-db.sql.example` (placeholders only) |
| B4 real SMS | Create a Fast2SMS account, complete KYC and the first Rs 100 top-up, copy the API key ONLY into `/etc/irl/backend.env` on the server. Nothing else sends sign-up codes |
| B5 signing | Generate a release keystore, back it up (losing it means no updates), put its paths/passwords in `android/keystore.properties` on your PC |
| B6 policy pages | Write/approve the privacy policy and the account-deletion page (draft: `docs/RELEASE_PRIVACY_PLAY.md`); put them at `/var/www/irl/privacy.html` and `/var/www/irl/delete-account.html` |
| B7 backups | Choose the destination for off-server copies, and (optional) a GnuPG public key for encryption |
| B8 monitoring | Choose an external uptime monitor and point it at `https://<domain>/api/health` |
| B9 Play | Play Console declarations (`PHASE17J.md` §5, `RELEASE_PRIVACY_PLAY.md` §3) |
| B10 rehearsal | Say if you want the from-scratch migration + restore rehearsal here: it needs MySQL admin rights (tell me the admin account to use, or run `CREATE DATABASE` yourself) |

## 7. Testing from the phone

**There is no deployed version, so nothing can be tested from the phone against production yet.** What works today: the DEBUG build over USB against a
local backend (`adb reverse tcp:8080 tcp:8080`, `CLAUDE.md` §12), as in the last session. After B1-B6 are done: (1) `deploy/scripts/smoke-test.sh
https://<domain> --check-redirect`; (2) on your PC `./gradlew assembleRelease -Prelease.api.base.url=https://<domain>/` with the keystore; (3) install that
signed APK; (4) sign up with a real number (the code arrives by SMS) and walk through sign-in, a post with a photo, Nearby, a Safe Walk and an SOS you
cancel.

## 8. L1 update (2026-11-09): the pilot runs on the owner's laptop (decisions D12-3 to D12-5)

The user approved running the friend-circle pilot from their own Windows laptop instead of renting a VPS first: **WSL2 (Ubuntu, systemd) + nginx + a named
Cloudflare Tunnel to a permanent `api.<domain>`**; the same artifacts move to a VPS later and the APK never changes. Full runbook: **`deploy/LAPTOP.md`**.
This changes the blockers: B1 becomes "install WSL2 on the laptop" (the owner's decision: admin + reboot), B2 becomes "a domain on Cloudflare DNS",
B3 is MySQL inside WSL, B10 disappears (the owner is admin of a fresh MySQL in WSL, so the from-scratch V1-V30 migration and the restore rehearsal can
run there), and B7 matters more (the laptop is a single point of failure; backups must leave it). B4 (Fast2SMS), B5 (keystore), B6 (privacy pages,
now hosted OFF the laptop), B8, B9 are unchanged.

**Built in L1 (nothing deployed; no domain, tunnel, WSL, nginx or cloudflared exists on the machine this was written on):**
- **A real defect fixed in the design:** behind a tunnel every request arrives from 127.0.0.1, so `AuthThrottle` (which counts by network address)
  would have shared ONE sign-in / sign-up / code limit between all friends. `nginx/irl-tunnel.conf.template` trusts only the loopback peer to name the
  client (`set_real_ip_from 127.0.0.1; real_ip_header CF-Connecting-IP`), and `FORWARD_HEADERS_STRATEGY=native` is mandatory (preflight fails without it).
  Proven on a real embedded server (MockMvc cannot): `RealClientAddressBehindProxyTest` (two forwarded addresses have separate limits) and
  `RealClientAddressWithoutProxyTest` (with the default `none` a forged header changes nothing).
- `deploy/nginx/irl-tunnel.conf.template` (loopback-only site, `/api/admin/` = 404, `/v1/` = 404, HSTS, literal `X-Forwarded-Proto https`),
  `deploy/cloudflared/config.yml.template` (one hostname → nginx, catch-all 404, loopback metrics), `deploy/systemd/irl-cloudflared.service`
  (`Restart=always`, hardened), `deploy/mysql/irl-mysql.cnf.example` (port 3307, TLS required, 256 MB buffer pool, performance schema off),
  `deploy/wsl/` (`wsl.conf.example`, `wslconfig.example`: memory cap, no localhost forwarding; `windows-host-setup.ps1`: report + dry run by default,
  `-Apply` sets the power plan, writes `.wslconfig` if absent, registers the keep-alive task; never installs WSL or touches updates/firewall/Defender),
  `smoke-test.sh --tunnel` (came through Cloudflare, `/api/admin` not served, API not cached), `.gitignore` patterns for tunnel credentials and filled env files.
- **Checked in the app (no code change):** Cloudflare's 530 and nginx's 502 both map to `ApiError.SERVER`, which the SOS recorder, the location queue and
  Safe Walk treat as retry (not a final failure); an HTML error body is never parsed; the Circle SMS location link is a Google Maps link (alive with the
  laptop off); media responses already send `private, no-store`.
- **Findings on this machine:** the Windows development `mysqld` (MySQL80) listened on all interfaces (3306, 33060); **fixed 2026-11-09 at the owner's request**: `bind-address=127.0.0.1` and `mysqlx-bind-address=127.0.0.1` added to `[mysqld]` in `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini` (backup `my.ini.bak-before-bind-*` beside it; to undo, copy the backup over `my.ini` and restart MySQL80). Verified: both ports listen on 127.0.0.1 only, a LAN-address connection to 3306/33060 fails, the real-MySQL test `MySqlTimestampStorageTest` passes (2/0), the development backend started with its default `localhost:3306` URL (Flyway: 30 migrations validated, up to date) and `/api/health` answered UP. WSL is not installed.

**Verification (L1):** backend `mvn test` **897 run, 0 failures, 0 errors, 2 skipped** (was 895; +2 real-server tests); Python **83 OK**; Android `testDebugUnitTest` **1029 run, 0 failures** from a clean tree (build output deleted first: BUILD SUCCESSFUL in 2m 11s, so the build is reproducible); `bash -n` passes for every script; `windows-host-setup.ps1` parses with no errors, is ASCII only and its report mode ran on this PC (it found WSL missing and the Windows development MySQL listening on all interfaces; nothing was changed). Rehearsal on this PC with the packaged jar on the laptop ports (backend 8180, Python service 8091, real MySQL over TLS, scratch storage folders): Flyway validated, backend and Python listened on 127.0.0.1 only, `smoke-test.sh` 0 failed (11 PASS, 2 SKIP for TLS/HSTS), and with `server.forward-headers-strategy=native` 30 failed sign-ins from forwarded address A made A refused (429) while forwarded address B was still answered normally (401); with the default strategy B shared A's limit. `preflight.sh` on a synthetic laptop env pair: 0 failed, no value printed; with the proxy mode wrong: 1 failed. Measured on that run: backend working set about 494 MB after start-up and light use, Python service about 27 MB.
**NOT VERIFIED:** `nginx -t` of the tunnel template, that nginx really forwards `CF-Connecting-IP` as the client address (needs nginx + Cloudflare; manual
check in `deploy/LAPTOP.md` §7), the systemd units, `cloudflared` itself, every Cloudflare dashboard setting (names change), WSL behaviour (keep-alive,
`.wslconfig` keys depend on the WSL version), the laptop-off behaviour on a real phone, real SMS.
