# IRL on a laptop behind a Cloudflare Tunnel (phase L1) — runbook

**Status: written and partly rehearsed on a development PC; NEVER run on the laptop-as-server yet.** Everything marked
**NOT VERIFIED** could not be run where this was written (no WSL, nginx, cloudflared or Docker there). Decision: `docs/DECISIONS.md`
D12-3 (supersedes D12-1 for the pilot). This chapter only lists what DIFFERS from `deploy/README.md`; everything else there applies
unchanged (users, systemd units, secrets, preflight, backups, restore check).

Purpose: let a small friend circle (5–30 people) use the app while the owner's laptop is the server, without paying for a VPS, and
move to a VPS later **without changing the app** (section 11).

```
phone ──https──> Cloudflare edge ──(tunnel, outbound from the laptop)──> cloudflared ──http──> nginx 127.0.0.1:8081
                  api.<yourdomain>                                                       └──http──> backend 127.0.0.1:8180
                                                                                                         ├─> MySQL 127.0.0.1:3307 (TLS)
all of it INSIDE WSL2 (Ubuntu + systemd) on the laptop                                                   └─> moderation service 127.0.0.1:8091
```

| Piece | Where | Port (loopback only) | Reachable from the internet? |
|---|---|---|---|
| cloudflared | WSL | metrics 20241 | no (it only connects OUT) |
| nginx | WSL | 8081 | only through the tunnel |
| backend (Spring Boot) | WSL | 8180 | no |
| MySQL 8 | WSL | 3307 | **never** |
| moderation service (Python) | WSL | 8091 | **never** |

The ports differ on purpose from the development ones on Windows (backend 8080, MySQL80 3306, Python 8091) and WSL does not forward its
ports to Windows (`localhostForwarding=false`), so the two worlds cannot collide and the dev emulator can never reach the pilot server.

## What must NEVER be exposed
MySQL (3306/3307/33060), the Python service (8091), the backend itself (8080/8180), SSH, RDP/Remote Desktop, the Windows file sharing
ports, and the **development backend** (`mvn spring-boot:run` listens on every interface by default: stop it before serving friends, or
run it with `SERVER_ADDRESS=127.0.0.1`). The tunnel's ingress routes ONLY `api.<yourdomain>` to nginx; everything else answers 404.
`windows-host-setup.ps1` lists every listening socket that is not loopback so you can see this.

## 0. What you need (the owner's list)
1. A **domain** (about 100–1,000 rupees a year) added to a free **Cloudflare** account (its name servers set at the registrar). A named
   tunnel needs a domain on Cloudflare DNS; the throw-away `trycloudflare.com` quick tunnels change their address and cannot be built into an APK.
2. **WSL2 + Ubuntu** on the laptop (`wsl --install -d Ubuntu`, administrator, reboot). Yours to decide: it is a new tool on your PC.
3. A **Fast2SMS** account + API key (new accounts need a real texted code; the `prod` profile refuses the file-based development sender).
4. A **backup destination OFF the laptop** (an external drive, another PC, cloud storage). Backups inside the same laptop do not survive its loss.
5. A **release keystore** (`docs/PHASE17J.md` §5) when you build the APK for friends.
Money: the domain, the SMS top-up (first Rs 100), later the Play fee. No server rental.

## 1. The Windows side (once)
```powershell
powershell -ExecutionPolicy Bypass -File deploy\wsl\windows-host-setup.ps1          # report + dry run, changes nothing
powershell -ExecutionPolicy Bypass -File deploy\wsl\windows-host-setup.ps1 -Apply   # power settings, .wslconfig, keep-alive task
```
By hand (your decisions, the script never touches them):
- **Windows Update:** Settings → Windows Update → Advanced options → Active hours (set the hours your friends use it). Updates still
  restart the laptop sometimes; section 5 says what comes back by itself and what does not.
- **Automatic sign-in after a restart:** the keep-alive task runs when YOU sign in, so after a restart the server stays down until
  you sign in. Either accept that or enable automatic sign-in (a security trade-off on a stolen laptop: do it only with disk encryption on).
- **Device encryption / BitLocker** (Settings → Privacy & security → Device encryption) so a stolen laptop does not expose the
  database, the evidence ciphertext and the tunnel credentials.
- Keep it **plugged in**; the battery is only a UPS for short power cuts. Keep the vents free (a closed lid on a bed overheats).
- Windows development MySQL: it listened on all interfaces by default. On the owner's PC this was fixed on 2026-11-09 (`bind-address=127.0.0.1` and
  `mysqlx-bind-address=127.0.0.1` in `C:\ProgramData\MySQL\MySQL Server 8.0\my.ini`, backup next to it as `my.ini.bak-before-bind-*`); on any other PC check
  it with `windows-host-setup.ps1`. Optional, NOT done for you: set the Windows `MySQL80` service to Manual while serving, Defender exclusions for the WSL disk.
- In `/etc/wsl.conf` (inside Ubuntu) use `deploy/wsl/wsl.conf.example` (systemd on); `%UserProfile%\.wslconfig` is `deploy/wsl/wslconfig.example`
  (the script creates it). Then `wsl --shutdown` once.

## 2. Inside Ubuntu: install and configure (follow `deploy/README.md` §1–§5 with these changes)
- Do **not** use certbot, `irl-bootstrap.conf.template`, `irl.conf.template` or the 80/443 firewall rules: TLS ends at Cloudflare.
  Install `nginx` and use `deploy/nginx/irl-tunnel.conf.template` (section 3). `ufw` is not needed (no listener faces a network).
- MySQL: `sudo apt install mysql-server`, then `deploy/mysql/irl-mysql.cnf.example` as `/etc/mysql/mysql.conf.d/zz-irl.cnf` (port 3307,
  TLS required, small memory), and `deploy/sql/create-production-db.sql.example` for the database and user.
- `/etc/irl/backend.env` — copy `deploy/env/backend.env.example` and change only:
  `PORT=8180`, `DB_URL=jdbc:mysql://127.0.0.1:3307/safesphere?sslMode=REQUIRED&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`.
  Keep `SERVER_ADDRESS=127.0.0.1` and `FORWARD_HEADERS_STRATEGY=native` (**required**: see "Real client address" below; preflight checks it).
  Storage stays on WSL's own disk (`/var/lib/irl/...`), NOT under `/mnt/c` (slow, no Unix permissions).
- `deploy/scripts/preflight.sh /etc/irl/backend.env /etc/irl/moderation.env` must report 0 failed before the first start.
- Start order is handled by systemd: MySQL → moderation → backend → nginx → cloudflared.

## 3. nginx (real client address)
```bash
export IRL_NGINX_PORT=8081 IRL_BACKEND_PORT=8180
envsubst '${IRL_NGINX_PORT} ${IRL_BACKEND_PORT}' < deploy/nginx/irl-tunnel.conf.template | sudo tee /etc/nginx/sites-available/irl >/dev/null
sudo ln -sf /etc/nginx/sites-available/irl /etc/nginx/sites-enabled/irl && sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx          # NOT VERIFIED until you run this
curl -s http://127.0.0.1:8081/api/health               # {"status":"UP"}
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/api/admin/evaluation   # 404: the admin API is not served
```
**Real client address.** Every request reaches the backend from cloudflared on 127.0.0.1. The backend's sign-in, sign-up and phone-code
limits count by network address, so without a fix all friends would share ONE counter and one person's typos would lock everyone out.
The template trusts only the loopback peer to name the real client (`set_real_ip_from 127.0.0.1; real_ip_header CF-Connecting-IP`; Cloudflare
sets that header itself), forwards it as `X-Forwarded-For`, and the backend reads it because `FORWARD_HEADERS_STRATEGY=native`. Proven in code
by `RealClientAddressBehindProxyTest` / `RealClientAddressWithoutProxyTest` (a real embedded server: with `native` two forwarded addresses
have separate limits; with the default `none` a forged header changes nothing). That nginx really sets the header is **NOT VERIFIED** until
the manual check in section 7.

## 4. Cloudflare (L3; needs the domain)
Install `cloudflared` inside Ubuntu from Cloudflare's own package repository (follow the current instructions at
`developers.cloudflare.com/cloudflare-one/connections/connect-networks/`; NOT VERIFIED here), then:
```bash
cloudflared tunnel login                         # opens a browser link: authorise the domain; this writes cert.pem (a SECRET)
cloudflared tunnel create irl-api                # prints the tunnel id and writes <id>.json (a SECRET)
cloudflared tunnel route dns irl-api api.<yourdomain>      # creates the proxied DNS record
sudo adduser --system --group --no-create-home cloudflared
sudo install -d -o cloudflared -g cloudflared -m 750 /etc/irl/cloudflared
sudo install -o cloudflared -g cloudflared -m 600 ~/.cloudflared/<id>.json /etc/irl/cloudflared/
export IRL_TUNNEL_ID=<id> IRL_API_HOST=api.<yourdomain> IRL_NGINX_PORT=8081
envsubst '${IRL_TUNNEL_ID} ${IRL_API_HOST} ${IRL_NGINX_PORT}' < deploy/cloudflared/config.yml.template | sudo tee /etc/irl/cloudflared/config.yml >/dev/null
shred -u ~/.cloudflared/cert.pem ~/.cloudflared/<id>.json    # the service does not need cert.pem; keep a copy of <id>.json in your password manager
sudo cp deploy/systemd/irl-cloudflared.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now irl-cloudflared
```
Choose the host name ONCE: it is baked into the release APK (`-Prelease.api.base.url=https://api.<yourdomain>/`) and stays the same on a VPS.

**Cloudflare dashboard checklist** (names change over time; NOT VERIFIED):
| Setting | Value | Why |
|---|---|---|
| Cloudflare Access on the API host | **do not use** | the app cannot do a browser login |
| Bot Fight Mode / JS challenges / "I'm Under Attack" | **off** for this domain | they answer API clients with an HTML challenge page; use a domain for the API only |
| Browser Integrity Check | off if the app's requests are refused with an HTML page | it judges browser headers |
| Cache Rule | host equals `api.<yourdomain>` → **Bypass cache** | media of other people must never sit in an edge cache (nothing should be cached); the backend also sends `private, no-store` |
| WAF custom rule | block path starts with `/api/admin/` | the nginx 404 is the first wall, this is the second |
| Rate limiting rule (the free plan allows one) | path starts with `/api/auth/`, about 20 requests per 10 s per address | extra protection in front of the app's own limits |
| Always Use HTTPS, minimum TLS 1.2 | on | the app only speaks https |
| SSL/TLS mode | Full | the tunnel itself is encrypted; the origin hop is local |
Cloudflare's free-plan terms limit serving video or large amounts of non-HTML files through its proxy, and a request body is capped
(about 100 MB; verify): fine for a friend circle (videos are ≤ 50 MB), not for a public video service.

## 5. Startup, sleep and recovery (what returns by itself)
| Event | What happens | You do |
|---|---|---|
| Laptop on, signed in | the keep-alive task starts WSL; systemd starts MySQL, moderation, backend, nginx, cloudflared | nothing |
| Network drops / Wi-Fi changes | cloudflared reconnects by itself (`Restart=always`); requests fail meanwhile | nothing |
| Sleep / lid closed | the server is DOWN while asleep (the power settings prevent this plugged in) | keep it plugged in |
| Windows Update restart | everything is down until Windows is back **and you are signed in** | sign in (or enable auto sign-in, section 1) |
| Power cut | battery carries it; a hard shutdown is recovered by MySQL's crash recovery | check `journalctl -u irl-backend` after |
| Laptop off | friends keep the app installed and their local data; the app shows "not connected"; SOS still works from the phone (SMS from the SIM, 112 dial prompt) | turn the laptop on |
After any restart: `deploy/scripts/smoke-test.sh https://api.<yourdomain> --tunnel` from another machine or your phone's browser data.
Useful checks inside Ubuntu: `systemctl --failed`, `journalctl -u irl-cloudflared -n 50`, `free -m`, `df -h /`.

## 6. Rules for running the pilot from your development laptop
- **Prod and dev are separate**: the pilot database (`safesphere` on 3307 in WSL) is not the development one (Windows MySQL80, 3306). Never
  put pilot credentials in `backend/.env` or `android/local.properties`; never run the dev profile against the pilot database.
- Run the pilot from a **copied jar** in `/opt/irl/backend/app.jar`, not from `backend/target` (rebuilding or `mvn clean` must not take the server down).
- `mvn test` uses an in-memory database and never touches the pilot. The Android debug build must keep pointing at `10.0.2.2`/`127.0.0.1`
  and must not be installed to friends.
- Say it to friends in plain words: the pilot is up only when the owner's laptop is on; **IRL does not replace calling 112**.

## 7. Testing the tunnel (L6)
1. From a machine that is not the laptop: `deploy/scripts/smoke-test.sh https://api.<yourdomain> --tunnel`.
2. Real-address check (the one thing the unit tests cannot prove, because nginx is in the way): on your phone over MOBILE DATA make 4 wrong
   sign-in attempts for a made-up e-mail; a friend on another network must still be able to sign in. Then, inside Ubuntu, look at the backend
   WARN/INFO log: no address is logged, so the proof is behavioural.
3. Turn cloudflared off (`sudo systemctl stop irl-cloudflared`): the app must say it cannot connect and must not lose a pending SOS ending.
4. A post with a photo, Nearby, a Safe Walk and an SOS you cancel (the checklist in `docs/D12_DEPLOYMENT.md` §7).

## 8. Backups (the laptop is a single point of failure)
`deploy/scripts/backup.sh` as in `deploy/README.md` §7, but `IRL_BACKUP_DIR` must be an **off-laptop** place: an external drive mounted in WSL
(`/mnt/<letter>/...`) or a folder you copy elsewhere. A folder on `C:` is on the same disk. Windows drives carry no Unix permissions, so use
`IRL_BACKUP_GPG_RECIPIENT` (GnuPG) for anything that leaves the laptop. Run `restore-check.sh` on the newest backup once a week while the pilot runs.
The systemd timer is daily 03:30 **while the laptop is on**; a laptop that is off at 03:30 simply skips that night.

## 9. What the app does when the laptop is off
Works on the phone alone: starting an SOS and its countdown, the Circle SMS (from the SIM, only if the person opted in) with a Google Maps link,
the 112 dial prompt, an unsent ending that is kept and sent later. Needs the server: new accounts (the code is sent by the server), sign-in
and the refresh of a session (a failed refresh says "sign in to sync" and deletes nothing), server records of an SOS or walk, evidence recording
(it waits for the server record), check-in deadlines, Nearby, Explore, posts and photos. Cloudflare answers 530 and nginx 502 when nothing is
behind them; the app treats both as "try again", never as a final failure. Not verified on a real phone with the laptop off.

## 10. What the laptop will use (expected, NOT measured on the laptop)
Backend ≈ 0.9–1.1 GB (heap capped at 768 MB), MySQL ≈ 0.4 GB (buffer pool 256 MB, performance schema off), Python service ≤ 0.5 GB (hard cap)
and normally far less, nginx ≈ 10 MB, cloudflared ≈ 50 MB, WSL overhead ≈ 0.2 GB: about 2–2.5 GB, capped at 3 GB by `.wslconfig`. CPU is near zero when
idle. Measure with `free -m` and `systemd-cgtop` on the first day and correct this paragraph. The heavy things on a development laptop are on
the Windows side: the Android emulator, Gradle/Kotlin daemons, Android Studio / VS Code language servers and the Windows MySQL80 service. Close
them (or run `gradlew --stop`) when you serve friends.

## 11. Moving to a VPS later (the app does not change)
1. On the laptop: `backup.sh` (database first, then files), copy the folder to the VPS.
2. On the VPS follow `deploy/README.md` §1–§5 with its own `backend.env` (**copy the same `JWT_SECRET`** so signed-in friends stay signed in; new
   `DB_PASSWORD`). Restore with the procedure in `restore-check.sh`'s header and start the services.
3. Either keep the tunnel (copy `<id>.json` and `config.yml`, install `irl-cloudflared.service`) — **stop cloudflared on the laptop first or at the same
   time: two connectors for one tunnel both receive traffic** — or switch to `irl.conf.template` + certbot and change the DNS record of
   `api.<yourdomain>` from the tunnel to the VPS address.
4. `smoke-test.sh https://api.<yourdomain>` (add `--tunnel` if you kept Cloudflare). The host name, the APK and the friends' phones are untouched.

## 12. Troubleshooting
| Symptom | Likely cause → check |
|---|---|
| Phone says it cannot connect, browser shows Cloudflare error 1033 / 530 | the tunnel is not connected: `systemctl status irl-cloudflared`, laptop awake and online, WSL running (is someone signed in?) |
| 502 Bad Gateway | nginx is up but the backend is not: `systemctl status irl-backend`, `journalctl -u irl-backend -n 80` (the production guard prints exactly what is wrong) |
| Everyone gets "too many attempts" | the real address is not reaching the backend: `FORWARD_HEADERS_STRATEGY=native`? nginx `real_ip_header` lines present and loaded? section 7 step 2 |
| App requests answered with an HTML page | a Cloudflare challenge: turn off Bot Fight Mode / JS challenge / Browser Integrity Check for the domain, see Security events |
| `/api/health` answers 503 DOWN | MySQL: `systemctl status mysql`, port 3307, TLS settings in `DB_URL` |
| Server stops when you close the terminal | the keep-alive task is not running (`Get-ScheduledTask IRL-WSL-KeepAlive`) or WSL was shut down (`wsl --shutdown`) |
| Backend refuses to start with a list of problems | that list is the production guard; fix each item in `backend.env` |
| Sign-up does not send a code | `FAST2SMS_API_KEY`, the account balance/KYC, `journalctl -u irl-backend` (a provider refusal is logged by class only) |

## 13. Never commit
`cert.pem`, `deploy/cloudflared/*.json`, the filled `config.yml`, any filled `*.env` (`backend.env`, `moderation.env`, `backup.env`), `backup.my.cnf`,
the keystore and `keystore.properties`, backups, the real domain name inside any file you do not intend to publish. `.gitignore` covers the
credential patterns; the `*.template` / `*.example` files contain placeholders only.
