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
| B4 | **SMS/OTP provider account** (Fast2SMS is the one adapter) | sign-up of new accounts (`prod` profile refuses DEV_FILE) | Fast2SMS account with the first Rs 100 top-up and balance (Quick SMS, route=q, about Rs 7 per SMS, since 2026-11-09: the old OTP route is closed), then its API key (set on the server only); DLT only if you want a branded sender |
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
| B4 real SMS | Create a Fast2SMS account, make the first Rs 100 top-up (Quick SMS, route=q; one real SMS arrived on 2026-11-09), copy the API key ONLY into `/etc/irl/backend.env` on the server. Nothing else sends sign-up codes |
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

## 9. L1 tunnel live (2026-11-10): domain, Cloudflare Tunnel, client-address proof, hardened connector (decisions D12-7, D12-8)

**Status vocabulary:** implemented and manually verified on the laptop (see below); NOT committed, NOT pushed. **The API is now PUBLIC at `https://api.irlnow.in`** (Cloudflare edge, named tunnel `irl-api`, cloudflared 2026.10.7).
- **Domain:** `irlnow.in` bought by the owner (GoDaddy India, Rs 99, auto-renew OFF); name servers moved to Cloudflare (free plan, zone Active); Account security (the owner's report, 2026-11-10; not independently checkable from here): **Cloudflare 2FA is e-mail based only** (the owner kept it; an authenticator app / security key was recommended and is NOT enabled, so the Cloudflare account is exactly as strong as the mailbox behind it: a residual risk, accepted by the owner, since this account controls where `api.irlnow.in` points); **GoDaddy 2FA is on with a verified phone number** (a text-message factor: weaker than an authenticator app, exposed to SIM swapping; the domain only holds the name servers); the **e-mail account has 2-step verification on**; recovery codes are stored offline by the owner.
- **Step A (before the tunnel, local nginx on 127.0.0.1:8081, `CF-Connecting-IP` forged from loopback with documentation addresses, no SMS, no DB write): PASS.** 30 wrong sign-ins from `203.0.113.7` answered 401, the 31st 429; the same address again 429; `203.0.113.8` 401; an outside `X-Forwarded-For` ignored; no header at all 401. The access log grew by exactly the requests sent and contains no address or e-mail.
- **Step B (through Cloudflare, real addresses): PASS.** From the laptop's IPv4: 30 wrong sign-ins 401, the 31st 429 with the app's own message; forged `X-Forwarded-For` still 429; the laptop's IPv6 (a different real client address) 401. A forged `CF-Connecting-IP` sent from outside is answered **403 by Cloudflare itself** (`Server: cloudflare`, never reaches the app). `/api/admin/*` and `/v1/*` and `/` answer 404 publicly. The privacy access log still holds only time, status, method, path and duration (0 addresses, 0 test e-mails; `irl.error.log` 0 bytes). Method: one machine with two address families, not a phone on mobile data (still to do).
- **Hardened connector (the stop-and-ask in this step):** the first tunnel ran under the root `cloudflared.service` written by `cloudflared service install`. The repository unit could not work as written: `/etc/irl` is `750 root:irl`, so a dedicated `cloudflared` user cannot read `/etc/irl/cloudflared/`. Fixed with `LoadCredential=tunnel.json:/etc/cloudflared/tunnel.json` (unit and `config.yml.template` changed, hardening directives byte-identical, `--no-autoupdate` kept), reviewed by Opus before any sudo step; the config is `/etc/cloudflared/irl-tunnel.yml` (non-secret); the old unit file is kept as `/etc/systemd/system/cloudflared.service.pre-irl-hardening`, `cloudflared.service` is **masked**, `irl-cloudflared.service` is **enabled**. The owner ran the sudo steps.
- **Verified after the switch (re-run by Claude from a read-only session):** main process user `cloudflared`; `User`, `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `MemoryMax=256M` applied; `/ready` 200 with 4 connections; journal shows 4 "Registered tunnel connection"; `tunnel info irl-api` shows exactly ONE connector; public `/api/health` 200; backend and nginx 200 locally; the full `ss -ltn` list equals the pre-switch list (only loopback; 20241 is now the new connector's metrics page); Windows cannot reach 20241, 8180, 8081, 8091, 3307, 22, 3389; credential `0400 root:root` (not copied), config 644, `tunnel.json` a symlink. Expected non-root warnings in the journal: the ICMP proxy is disabled (group not in `ping_group_range`); harmless.
- **Secrets:** the home `cert.pem` was removed with `shred -u -z` (best effort: a virtual disk with a journalling file system gives no guarantee that old blocks are gone; real revocation would be in the Cloudflare account). The service never read it (it runs with the home folder hidden and reconnected as another user). **The home copy of the tunnel JSON is still there**; it is removed only after `sudo cmp -s ~/.cloudflared/<id>.json /etc/cloudflared/<id>.json` reports identical, and the owner decides about a password-manager copy (without one, losing the WSL disk means creating a new tunnel).
- **Update timer:** `cloudflared-update.timer` (daily + up to 6 h) runs `cloudflared update` as root, which downloads a binary from Cloudflare's server over the apt-owned `/usr/bin/cloudflared`; on exit 11 it runs `systemctl restart cloudflared` (now masked, so the hardened unit keeps running the OLD binary in memory until its next restart; the root connector cannot come back). It has never run (no journal entries). Kept for now; the decision between "updates only through apt" and the timer is open. The repository design says updates come from the package manager.
- **Runbook correction:** `deploy/LAPTOP.md` section 4 now uses `/etc/cloudflared` + `LoadCredential=`, and tells the reader to stop, back up and MASK the package unit.
- **Cloudflare dashboard checklist log (2026-11-10, each setting set by the owner, verified by Claude with `curl` from outside; dashboard names as the owner reported them):**
  1. **Always Use HTTPS: On.** Before: a plain-HTTP request reached the app (401). After: `http://api.irlnow.in/api/health` answers 301 to the https URL. (Minor, left alone: HTTPS answers carry two `strict-transport-security` headers, from Spring and from nginx, same max-age; Cloudflare's own HSTS is deliberately NOT enabled.)
  2. **Cache Rule `irl-api-bypass-cache`: Hostname equals `api.irlnow.in` -> Bypass cache** (no other rule existed). Before: a static-looking path (`/x.js`, nginx's bare 404) was stored (`MISS`, `max-age=14400`); no API route has a file extension and the controllers that serve media, profile, evidence, recovery and moderation data send `no-store`, so this is defence in depth. After: every path answers `cf-cache-status: DYNAMIC`, no `Age`, no TTL override; `/api/admin/` and `/v1/` still 404.
  3. **Minimum TLS Version: TLS 1.0 -> TLS 1.2** (the owner's report of the previous value; TLS 1.3 left on). Before (`openssl s_client`): TLS 1.0, 1.1, 1.2 and 1.3 all accepted. After: TLS 1.0 and 1.1 refused (`tlsv1 alert protocol version`), 1.2 and 1.3 accepted; health 200 over IPv4 and IPv6; HTTP still 301. The app's minSdk is 26 (Android 8), which negotiates TLS 1.2.
  4. **SSL/TLS encryption mode: already Full, left unchanged** (the owner's report). With a tunnel the Cloudflare-to-origin hop does not use this mode (the connector's own encrypted channel; the last hop to nginx is local plain HTTP); how the mode interacts with tunnels was not verified. Health 200, edge certificate unchanged (Let's Encrypt for `irlnow.in`, valid until 2027-02-08).
  - **Finding before rule 5 (minor, nothing exposed):** nginx's `location /api/admin/` has a trailing slash, so `GET /api/admin` (no slash) is NOT answered by nginx; it reaches the backend and answers 401 (an authenticated ADMIN token would still be needed and no endpoint exists at exactly that path). The Cloudflare rule below uses a prefix without the slash, so it covers it; a regex location in `irl-tunnel.conf.template` (`^/api/admin(/|$)`) is a possible later fix (needs the owner's sudo reload; not done). The Android app never calls `/api/admin` (0 matches).
  5. **WAF custom rule `irl-block-admin-api`: Block** when `http.host eq "api.irlnow.in" and starts_with(lower(http.request.uri.path), "/api/admin")` (second wall after nginx; closes the no-slash gap). After: `GET /api/admin/x`, `/api/admin`, `/API/ADMIN/x`, `/api/admin/evaluation` and the URL-encoded `/api/%61dmin/x` all answer **403 from Cloudflare** ("Sorry, you have been blocked" page; that page carries the visitor's own address in a hidden element, so bodies are not stored); `/api/health` 200, `/v1/x` 404 (nginx), `POST /api/auth/login {}` reaches the app (400). The owner's admin use stays on the laptop at `127.0.0.1:8180`, which does not pass Cloudflare.
  6. **Bot settings: nothing changed** (the owner's report: Bot Fight Mode already Off, Browser Integrity Check On). Baseline from this laptop (status only): `GET /api/health` answers 200 for the app's OkHttp User-Agent, curl's default, python-requests, a headless-browser User-Agent and no User-Agent at all, and a sign-in-style POST reaches the app (400), so no challenge is shown with these settings. **NOT verified:** shared mobile addresses (CGNAT), which cannot be tested from here (a phone on mobile data is still to do). If a friend's phone is ever refused with an HTML page, check Security events first and then Browser Integrity Check.
  7. **Rate limiting rule `irl-auth-rate-limit`** (the owner's report: deployed and Active; intended as `http.host eq "api.irlnow.in" and starts_with(http.request.uri.path, "/api/auth/")`, 20 requests per 10 seconds per IP, Block for 10 s; the exact values the plan accepted were NOT read back by Claude). Verified from outside: 10 sequential `curl.exe` requests ran at about 16 per 10 s, below the threshold, so the first test (30 sequential) was correctly NOT limited; a real burst of 60 requests (30 in parallel from WSL, empty bodies, which fail validation and do not count as sign-in failures) finished in 3 s with **31 answered by the app (400, `application/json`) and 29 refused by Cloudflare (429, `text/plain`, not the app's JSON 429)**; the limit is approximate (more than 20 got through, as Cloudflare counts per edge location with some lag), which is acceptable for a coarse extra layer in front of the app's own per-address limits. Right after the burst `/api/health` 200, `/api/admin/*` 403 (rule 5), `/v1/x` 404; 20 s later the auth path answers 400 from the app again, so the block is short and path-scoped. IPv6 is counted as a separate address.
- **Cloudflare dashboard checklist: COMPLETE (2026-11-10)** for the items in `LAPTOP.md` section 4 (Always Use HTTPS, cache bypass, `/api/admin` block, bot settings checked, minimum TLS 1.2, SSL mode Full, auth rate limit). Cloudflare Access is deliberately not used (the app cannot do a browser login). Dashboard names were as the owner reported them and are not verified against current Cloudflare documentation. A real shared-address (CGNAT) phone case is still untested.
- **Production smoke test (2026-11-10, after an Opus review of the procedure): `deploy/scripts/smoke-test.sh https://api.irlnow.in --tunnel --check-redirect`.** Network honesty: run from the laptop through the PUBLIC Cloudflare edge, NOT from a second network (switching the laptop to a hotspot would move the tunnel too). It is anonymous and read-only: no `/api/auth/*`, no sign-in, no SMS, no write.
  - **Run 1, script unchanged: 17 PASS, 1 FAIL.** The FAIL was expected: `/api/admin/evaluation` answered 403 (Cloudflare rule 5) where the script wanted 404 (nginx). Recorded as "design changed", not as a regression.
  - **Test fix (a test change, not architecture; uncommitted):** the `--tunnel` admin check now accepts 404 (nginx) or 403 (Cloudflare) and requires that the body is not the app's JSON (`"status"` field; the body is never printed because Cloudflare's block page carries the visitor's address), and it also checks the no-slash `/api/admin`. `bash -n` OK, LF endings kept; the negative control (the app's own 401 JSON matches the pattern the check rejects) was confirmed. `LAPTOP.md` section 4 (WAF row) updated to the exact expression and the 403.
  - **Run 2, edited script: 19 PASS, 0 FAIL** (HTTPS with a trusted certificate; health 200, exact body, `no-store`; protected endpoint 401 with `WWW-Authenticate: Bearer` and the app's JSON, no stack trace; unknown `/api` path 401; `/v1/health` 404; no CORS header for a foreign Origin; `Server` reveals no software; CF-Ray present; both admin paths 403 not by the app; no cache hit; HSTS; http redirects to https).
  - **Extra read-only checks:** only `api.irlnow.in` has DNS records (A and AAAA); `irlnow.in`, `www`, `mail` have none. A CORS preflight with a foreign Origin answered 403 with no `access-control-*` header (who answered the 403, Cloudflare or the app, was not identified). After the run: all five units active, `cloudflared.service` masked, update timer still active, connector user `cloudflared`, `/ready` 200 with 4 connections, listeners unchanged (loopback only), Windows cannot reach any pilot port, `irl.error.log` 0 bytes, the access log (247 lines) has no address-like token, no query string and no e-mail, the backend journal of the last hour has no address-like token. `cloudflared tunnel info` was not used (the home `cert.pem` is shredded, so it would fail by design).
  - **Credential cleanup (2026-11-10, after the smoke test):** the owner ran `sudo cmp -s` between the home tunnel JSON and `/etc/cloudflared/<id>.json` and reported **IDENTICAL** (175 bytes each); Claude then removed ONLY the home copy with `shred -u -z -n 2` (best effort on a virtual disk, no guarantee that old blocks are gone). The first attempt failed safely with "Permission denied" because the file was mode 0400 and `shred` opens for writing; the home copy (only) was made owner-writable and the second attempt succeeded. Verified: `~/.cloudflared` is empty; the protected credential is unchanged (`-r-------- root:root`, 175 bytes, same mtime and inode as before) and `tunnel.json` is still the symlink; the connector runs as `cloudflared` with 4 connections; journal has no credential/permission lines; public `/api/health` 200 over IPv4 and IPv6, `/api/admin` 403, `/v1/x` 404. **The protected file is now the ONLY copy of the tunnel credential on this machine** (no password-manager backup was made; losing the WSL disk would mean creating a new tunnel and re-running `tunnel route dns` for `api.irlnow.in`).
  - **Update strategy (2026-11-10, decision D12-9; the owner ran the sudo steps):** apt-only, by hand. Inspection: `cloudflared` 2026.10.7 is a dpkg package from `pkg.cloudflare.com/cloudflared` (no newer candidate); the timer had never run; `unattended-upgrades` allows only Ubuntu origins (no Cloudflare entry); the package ships no unit and its scripts do not restart anything (they only link the binary and write a "installed from package manager" marker). Done: `cloudflared-update.timer` disabled and stopped, both update unit files moved to `*.disabled-apt-only`, both units **masked**. Verified afterwards: both masked, no cloudflared timer scheduled, no dangling wants link, `cloudflared.service` still masked; `irl-cloudflared` still enabled and active as `cloudflared` with the SAME process (MainPID 3254, same start time, `NRestarts=0`, no restart) and 4 connections; tunnel config sha256 and unit sha256 identical to before, credential unchanged (mode, owner, size, mtime, inode), `tunnel.json` still the symlink; version still 2026.10.7; backend, moderation, nginx, mysql active; listeners unchanged; public `/api/health` 200 over IPv4 and IPv6, `/api/admin` 403, `/v1/x` 404; Windows reaches no pilot port. One unrelated failed unit: `getty@tty1.service` (WSL has no console; start-limit hit at 11:58 UTC; not connected to `cloudflared`; whether it predates the change was not established). Manual update procedure and rollback note: `deploy/LAPTOP.md` section 4. NOT verified: that an apt upgrade of this package works end to end here (no newer version existed), that `cloudflared update` would have declined on this install, and rollback to an older version.
  - **Not covered by this test (by design):** sign-in, sign-up, OTP, uploads, SOS; a second network. **Still to do from outside:** open `https://api.irlnow.in/api/health` (expect `{"status":"UP"}`) and `https://api.irlnow.in/api/explore/understanding` (expect the app's 401 JSON) in a phone browser on mobile data, which is also a first shared-address data point for Browser Integrity Check.
- **NOT verified:** a phone on mobile data (client-address proof, a real sign-in, a shared-address bot or rate-limit case); `smoke-test.sh https://api.irlnow.in --tunnel`; the behaviour after a Windows reboot with nobody signed in; the laptop-off behaviour on a real phone; sleep/wake; backups; privacy and deletion pages; the release APK against the public host.
