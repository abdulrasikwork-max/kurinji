# SafeSphere

**Safety that expands your world.** *Safe enough to live. Free enough to grow.*
A privacy-focused safety platform designed with women at the centre (final-year B.Tech IT project). This repository
holds the Android app and the Spring Boot backend. Nobody can use SafeSphere to monitor another person: there is no
family, partner or guardian mode and no location on request (a permanent product decision).

```
SafeSphere/
├── android/   Kotlin Android app (XML views, MVVM, Navigation, Retrofit, DataStore)
├── backend/   Spring Boot REST API (Spring Security + JWT, JPA, Flyway, MySQL)
└── docs/      API.md, PHASE2.md … PHASE10.md, PHASE*_DESIGN.md, HANDOFF.md, DESIGN_SYSTEM.md, PLATFORM_CONSTRAINTS.md
```

The status table below covers Phases 1–5; the current, complete status of every phase (6–10) is in `CLAUDE.md` §2
and `docs/HANDOFF.md`. In short: Nearby place signals (6), trust and moderation (7), safety intelligence (8) and the
Phase 9 evaluation layer are built; **Phase 10A** (honest app texts, password change with session revocation, and
coercion-aware account deletion) is implemented and tested (`docs/PHASE10.md`); RISE (10B) is designed, not built.

## Status

| Phase | Scope | Status |
|---|---|---|
| 1 | Android foundation: navigation, onboarding, auth UI, branded design system | Done (builds and runs on the emulator) |
| 2 | Spring Boot + MySQL + JWT; real register / login / profile; persistent session | **Done: manually verified on Windows** |
| 3 | Safety Circle (trusted contacts) + manual SOS foundation (countdown, emergency events, history) | **Done: live verified** (see docs/PHASE3.md) |
| 4A | Emergency location: permissions, foreground service, offline upload queue, battery/network readers, recovery after restart | **Implemented, compiled and tested** (backend + Android unit tests, V4 on real MySQL, emulator run). Your manual verification pending: docs/PHASE4A.md |

| 4B | Safe Walk: server-computed check-in deadlines, reminders, missed check-in (L1), no response (L2), "I'm OK" (also on the notification), SOS links the walk (L3), offline-first, Home banner | **Implemented, compiled and tested** (backend + Android unit tests, V5/V6 on real MySQL, emulator run). Your manual verification pending: docs/PHASE4B.md |

| 4C / 4D | Route/stop checks, battery policy; Safety Circle SMS (opt-in), Call 112 dial prompt, Emergency Context Packet | **Implemented, compiled and tested**: docs/PHASE4C.md, docs/PHASE4D.md |
| 5 | Emergency Evidence Vault: user-started audio (30 s encrypted parts), photo / video via the camera app, on-phone encryption (Keystore), server-verified SHA-256 seal, hash-chained chain of custody, offline escrow recovery key, password-protected deletion | **Implemented, compiled, tested, emulator-verified**. Real-phone verification pending: docs/PHASE5.md |

During an active SOS the phone's location can be saved to the person's **own** emergency record, and the person can choose to record evidence, which is encrypted on the phone before upload. SafeSphere never contacts emergency services itself. Push notifications from the server, learned AI models and RISE
are not built.

## Architecture

```
Android app ──Retrofit/OkHttp (HTTP+JSON)──▶ Spring Boot ──Spring Security + JWT──▶ MySQL
 MVVM, DataStore session                      controller → service → repository       users table (Flyway)
```
Details, flows and a viva-style explanation: **docs/PHASE2.md**. API reference: **docs/API.md**.

## Technology

- **Android:** Kotlin, XML Views, Fragments, ViewBinding, single Activity, Navigation Component, MVVM, repositories, Coroutines/Flow, DataStore, Material 3, Retrofit 2.11, OkHttp 4.12 (Gson converter), minSdk 26.
- **Backend:** Java 17+, Spring Boot 3.5.16, Spring Web, Spring Data JPA, Spring Security (OAuth2 resource server for JWT), Bean Validation, Flyway, MySQL 8, BCrypt, Maven.

## Prerequisites (Windows)

1. **JDK 17 or newer** (`java -version`).
2. **Maven 3.9+** (`mvn -version`), or open `backend` in IntelliJ IDEA, which bundles Maven.
3. **MySQL Server 8.x** with the `mysql` command available (MySQL Installer from mysql.com).
4. **Android Studio** (current stable) with an emulator (API 26+).

## MySQL setup (once)

1. Open `backend/sql/setup.sql` and replace `<choose-a-password>` with a password of your choice (keep it; you need it below).
2. From the `backend` folder run (works in PowerShell and cmd):
   ```
   mysql -u root -p -e "source sql/setup.sql"
   ```
   This creates the database `safesphere` and a user `safesphere` that can only touch that database.
   **Do not commit the edited file** if it now contains your password; revert it after running.
3. Tables are created automatically by Flyway the first time the backend starts.

## Environment variables

| Variable | Required | Meaning |
|---|---|---|
| `DB_PASSWORD` | **yes** | MySQL password of the `safesphere` user |
| `JWT_SECRET` | **yes** | Signs login tokens. At least 32 characters; the app refuses to start otherwise |
| `DB_USERNAME` | no | Default `safesphere` |
| `DB_URL` | no | Default `jdbc:mysql://localhost:3306/safesphere?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` (keep the time-zone settings, docs/TIMESTAMPS.md) |
| `JWT_EXPIRATION` | no | Default `24h` |
| `PORT` | no | Default `8080` |
| `CORS_ALLOWED_ORIGINS` | no | Default empty (the Android app needs none) |

Easiest on Windows: copy `backend/.env.example` to `backend/.env` and fill it in. `.env` is git-ignored and is read automatically when you start the backend from the `backend` folder.
Generate a secret in PowerShell:
```powershell
[Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }) -as [byte[]])
```
Or use real environment variables for the current window:
```powershell
$env:DB_PASSWORD = "your-mysql-password"
$env:JWT_SECRET  = "a-long-random-string-of-at-least-32-characters"
```

## Run the backend

```
cd backend
mvn spring-boot:run
```
Success looks like: Flyway `Successfully applied 1 migration`, then `Started SafeSphereBackendApplication` on port 8080.
Common failures: `Could not resolve placeholder 'DB_PASSWORD'` or `'JWT_SECRET'` means the variable/`.env` is missing; `Access denied for user` means a wrong DB password; `Communications link failure` means MySQL is not running.

## Run the Android app

1. Open the **`android`** folder in Android Studio (not the repository root). If you used the Phase 1 folder before, open the new one.
2. Let Gradle sync. (Optional) set the server URL in `android/local.properties`: `api.base.url=http://10.0.2.2:8080/`; this is already the default.
3. Start an emulator and press Run. Keep the backend running.

### Emulator networking

Inside the emulator `localhost` is the emulated phone, **not your PC**. The emulator's alias for your PC is **`10.0.2.2`**, so the app calls `http://10.0.2.2:8080/`. Debug builds allow plain `http://` to `10.0.2.2`, `localhost` and `127.0.0.1` only; release builds require HTTPS. For a real phone on the same Wi-Fi use `http://<PC-IP>:8080/` in `local.properties`, add that IP to `android/app/src/debug/res/xml/network_security_config.xml`, and allow port 8080 in Windows Firewall.

## API endpoints

| Method | URL | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | none | Create account, returns token + user |
| POST | `/api/auth/login` | none | Log in, returns token + user |
| GET | `/api/users/me` | Bearer token | Profile of the signed-in user |
| GET, POST | `/api/safety-contacts` | Bearer token | List / add your trusted contacts |
| GET, PUT, DELETE | `/api/safety-contacts/{id}` | Bearer token | Read / edit / remove one of your contacts |
| POST, GET | `/api/emergencies` | Bearer token | Record a manual emergency / your history |
| GET | `/api/emergencies/{id}` | Bearer token | One of your emergencies |
| PUT | `/api/emergencies/{id}/cancel`, `/resolve` | Bearer token | End your active emergency ("false alarm" / "I'm safe") |

Full examples and error formats: **docs/API.md**.

## Testing

**Backend** (needs no MySQL; uses an in-memory H2 database):
```
cd backend
mvn test
```
Covers registration, BCrypt hashing, duplicate email, validation errors, login success/failure, JWT contents and expiry, expired / forged / garbage tokens, protected `/api/users/me`, and the JWT-secret safety check.

**Android unit tests:** in Android Studio, right-click `android/app/src/test` and choose Run Tests, or from `android`: `gradlew testDebugUnitTest`.
They cover validators, ViewModels (login, register, splash routing, home, profile) and the repository against a fake server.

**Test the API from PowerShell** (backend running):
```powershell
$base = "http://localhost:8080"
$body = @{ name = "Asha Devi"; email = "asha@example.com"; password = "correct-horse-battery" } | ConvertTo-Json
$r = Invoke-RestMethod -Method Post -Uri "$base/api/auth/register" -ContentType "application/json" -Body $body
$r                                                    # expect accessToken + user (no password fields)

# Profile with the token
Invoke-RestMethod -Uri "$base/api/users/me" -Headers @{ Authorization = "Bearer $($r.accessToken)" }

# Login
$login = @{ email = "asha@example.com"; password = "correct-horse-battery" } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType "application/json" -Body $login

# Error cases: each should print a status code and the JSON error
function Try-Call($method, $path, $json, $headers = @{}) {
  try { Invoke-RestMethod -Method $method -Uri "$base$path" -ContentType "application/json" -Body $json -Headers $headers }
  catch { "$($_.Exception.Response.StatusCode.value__): $($_.ErrorDetails.Message)" }
}
Try-Call Post "/api/auth/register" $body                                   # 409 duplicate email
Try-Call Post "/api/auth/register" '{"name":"","email":"x","password":"1"}' # 400 validation
Try-Call Post "/api/auth/login" '{"email":"asha@example.com","password":"wrong"}'  # 401
Try-Call Get  "/api/users/me" $null                                         # 401 no token
Try-Call Get  "/api/users/me" $null @{ Authorization = "Bearer not.a.jwt" } # 401 bad token
```
Check the database: `mysql -u safesphere -p safesphere -e "SELECT id, name, email, LEFT(password_hash,7) FROM users;"` (hash starts with `$2a$`/`$2b$`).

### Android manual test checklist
1. Fresh install: splash, onboarding, Login. Login screen has no "Preview mode" notice.
2. Register with a bad email, short password, mismatched passwords: local errors, **no** network call.
3. Register with valid data: progress bar, then Home greets you by name. The row exists in MySQL.
4. Register the same email again (after logging out): "An account with this email already exists."
5. Log out (Profile > Log out > confirm): back on Login, cannot go back.
6. Log in with a wrong password: "Invalid email or password." Correct password: Home.
7. Close the app completely and reopen: lands on Home (signed in), no login screen.
8. Stop the backend and try to log in: "Unable to connect to server."
9. With the backend stopped, reopen the app while signed in: still opens Home (cached name), no error.
10. Restart the backend with a **different** `JWT_SECRET`, reopen the app: "Session expired" dialog, then Login.
11. Profile shows your real name/email and "Signed in"; Settings, Privacy, About still work.
12. Rotate the device and switch dark mode on Login/Register: nothing is lost or broken.

## Known limitations

- Phase 4A limitations (no background-location permission, OEM battery killers, in-memory upload queue, nobody is notified): see docs/PHASE4A.md and docs/PLATFORM_CONSTRAINTS.md.
- Plain HTTP and an unencrypted token in DataStore (development only); no refresh tokens, no server-side logout, no rate limiting, no email verification, no password reset (the Forgot Password screen is still a labelled preview).
- Spring Boot 3.5.16 is the last free 3.5 release (support ended 30 June 2026); a migration to 4.x is a separate step. See docs/PHASE2.md.
- Maven wrapper (`mvnw`) is not included; install Maven or use IntelliJ.
- Phase 1 limitations still apply (English only, no tablet layouts, no on-device UI tests).

## Phase 3 (Safety Circle + manual SOS): see docs/PHASE3.md

The full Phase 3 manual test (including the two-user ownership check) is in **docs/PHASE3.md**, section 6.
Run `mvn test` in `backend` and `gradlew testDebugUnitTest` in `android` first.

## Phase 4A (emergency location): see docs/PHASE4A.md

Design, decisions, what was run, and the manual checklist are in **docs/PHASE4A.md**; Safe Walk (4B) in
**docs/PHASE4B.md**. Next: 4C (deviation, inactivity, Safety Bubble, battery policy), then 4D. Community, evidence and AI come later.
