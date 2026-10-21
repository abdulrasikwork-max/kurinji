# Phase 2 explained: real accounts

Phase 1 gave SafeSphere its screens. Phase 2 gives it a **memory**: a server that can create accounts and
recognise people. Nothing else was added (no SOS, location, maps, AI, reports or evidence).

```
Android app --(Retrofit/OkHttp, HTTP + JSON)--> Spring Boot --(Spring Security + JWT)--> MySQL
```

## 1. The pieces and why each exists

| Piece | Job (one sentence) |
|---|---|
| **Spring Boot** | Runs a web server and wires the classes together. |
| **Controller** | Receives an HTTP request, validates the JSON, calls a service. No business rules. |
| **Service** | The rules: "is this email taken?", "does this password match?". |
| **Repository (Spring Data JPA)** | Reads and writes the `users` table without hand-written SQL. |
| **Entity (`User`)** | One row of the `users` table as a Java object. |
| **DTO (`RegisterRequest`, `UserResponse`...)** | The exact JSON shape that goes in and out. The entity is never sent directly, so the password hash cannot leak by accident. |
| **BCrypt** | One-way, salted, deliberately slow hashing for passwords. |
| **JWT** | A signed "pass" the server gives after login; the app shows it on later requests. |
| **Spring Security** | The guard at the door: public URLs vs URLs that need a valid JWT. |
| **Flyway** | Creates and versions the database tables from `db/migration/V1__...sql`. |
| **Retrofit + OkHttp** | Turn a Kotlin interface into HTTP calls on the Android side. |
| **DataStore** | Keeps the token on the phone so the user stays signed in. |

## 2. Backend structure (`backend/src/main/java/com/safesphere/backend`)

```
config/      SecurityConfig (who may call what, BCrypt, CORS), JwtConfig (encoder+decoder from one secret), JwtProperties
security/    JwtService (the only place tokens are created), RestAuthenticationEntryPoint (401 JSON), RestAccessDeniedHandler (403 JSON)
auth/        controller/AuthController, service/AuthService, dto/ (RegisterRequest, LoginRequest, AuthResponse)
user/        controller/UserController, service/UserService, repository/UserRepository, entity/User, dto/UserResponse
common/      exception/ (GlobalExceptionHandler + small exception classes), response/ApiError
```

## 3. Database

One table, created by Flyway from `V1__create_users_table.sql`:

| Column | Type | Notes |
|---|---|---|
| id | BIGINT AUTO_INCREMENT | primary key |
| name | VARCHAR(100) | required |
| email | VARCHAR(255) | required, **UNIQUE** (case-insensitive collation; we also lower-case it) |
| password_hash | VARCHAR(100) | BCrypt hash (60 characters), never the password |
| enabled | BOOLEAN | default true; a disabled user cannot log in |
| created_at, updated_at | DATETIME(6) | stored in UTC |

**Why Flyway and not "let Hibernate create the tables"?** Hibernate's `ddl-auto=update` is convenient but changes the
database silently and cannot be reviewed. Flyway keeps each change as a numbered SQL file that is reviewed and
version-controlled, which is how real projects evolve a schema (Phase 3 will add `V2__...`). The cost is one extra
dependency and having to write the SQL. `ddl-auto` is set to `none` so Hibernate never touches the schema.
Trade-off for tests: they use an in-memory H2 database built by Hibernate, so the MySQL migration itself is
checked separately (see "What was tested").

## 4. The authentication flow in plain words (for your viva)

**Register**
1. The user fills the form. The app checks it locally (email format, 8+ characters, passwords match).
2. Retrofit sends `POST /api/auth/register` with name, email, password over HTTP.
3. The server validates again (never trust the client), lower-cases the email, and rejects duplicates (409).
4. BCrypt turns the password into a salted hash. Only the hash is saved in MySQL.
5. The server creates a JWT and returns it with the safe user info. Design choice: registration signs the user in
   immediately, which saves a screen and a request compared with "register, then log in".
6. The app saves the token and basic profile in DataStore and opens Home.

**Login**: same, but the server looks the user up by email and asks BCrypt whether the typed password matches the stored
hash. Wrong email, wrong password and disabled account all give the **same** 401 message, so nobody can use the
response to discover which emails have accounts. The server even performs one BCrypt comparison for unknown emails so
that timing does not give the answer away either.

**Using the token**: every later request carries `Authorization: Bearer <token>`. OkHttp's `AuthInterceptor` adds it in one
place. Spring Security checks the signature (was it signed with our secret?), the expiry and the issuer. If anything is
wrong the answer is 401 and the controller never runs. `GET /api/users/me` then reads the user id from the token's `sub`
claim and returns the profile.

**Staying signed in**: on the next app start the splash screen finds a saved session and goes straight to Home. Home asks
`/api/users/me` which also proves the token is still valid; if the server says 401 the app clears the session and shows
"Session expired".

**Logout**: the app deletes the saved session and returns to Login. The JWT is stateless, so the server has nothing to
delete; the old token simply stays valid until it expires (24 hours). Fixing that needs refresh tokens or a deny-list (later).

### What is inside a JWT?
Three Base64 parts: header (algorithm HS256), payload (`iss`, `sub` = user id, `iat`, `exp`) and signature
(HMAC-SHA256 of the first two parts using `JWT_SECRET`). It is **signed, not encrypted**: anyone can read the payload, nobody
can change it without the secret. So the token contains no password and no hash.

## 5. How the emulator reaches your server

Inside the Android emulator, `localhost` / `127.0.0.1` means **the emulated phone itself**, not your PC. The emulator
provides the special address **`10.0.2.2`**, which is routed to your computer's loopback. So the app uses
`http://10.0.2.2:8080/`. The URL comes from `BuildConfig.API_BASE_URL`, taken from `android/local.properties`
(`api.base.url=...`), so it can be changed without editing code. A real phone needs your PC's LAN IP.

Android blocks plain `http://` by default. **Debug builds only** have a network-security config (in `app/src/debug`) that
allows cleartext to `10.0.2.2`, `localhost` and `127.0.0.1`. Release builds do not, so they require HTTPS.

**CORS** is a browser rule. A native Android app is not a browser and never sends an `Origin` header, so no CORS
configuration is needed. The backend allows no browser origins by default and has one setting
(`CORS_ALLOWED_ORIGINS`) ready for a future web client.

## 6. Security: what is done, and what is NOT

Done: BCrypt hashing; JWT with expiry, issuer check and a secret from the environment (the app refuses to start if it is
missing or shorter than 32 characters); no secrets in source control; generic login errors; input validation on the
server; JPA parameterised queries; DTOs so entities never leave the server; no exception messages or stack traces in
responses; the Android request objects redact passwords in `toString()`; no logging interceptor (so bodies are never
logged); backups disabled so the token is not copied to cloud backups; cleartext HTTP only in debug builds.

**This is not production-grade.** Remaining work for later phases:
- Use HTTPS/TLS everywhere (the local server uses plain HTTP).
- Encrypt the token on the phone with the Android Keystore (DataStore is private but not encrypted).
- Short-lived access tokens plus refresh tokens, and server-side logout / token revocation.
- Rate limiting and lockout against password guessing; email verification; password reset.
- Stronger password rules (breached-password checks); audit logging.
- Secret management (a vault or cloud secret store) instead of environment files; database TLS and least-privilege users.
- Dependency scanning and updates (see "Spring Boot version" below).

## 7. Spring Boot version

The backend uses **Spring Boot 3.5.16** (Java 17+). OSS support for the 3.5 line ended on 30 June 2026, and 3.5.16 is its
last free release, so it still works but will not get further security fixes. Spring Boot 4.x is the supported line, but it
changes several things (Jackson 3, split starters, moved test annotations, Spring Security 7). I could not compile or run
anything in my build environment, so I chose the version whose APIs I could write exactly. Upgrading to 4.x is a
planned, separate step.

## 8. What was tested, and what was not

Be honest in your report:

| Check | Result |
|---|---|
| MySQL 8.0 setup script, Flyway migration SQL, unique/case-insensitive email, NOT NULL, least-privilege user | **Run for real against MySQL 8.0.46 here: passed** |
| Backend Java syntax (all 26 files) and consistency between my own classes | Checked with `javac`: no syntax errors; no unresolved symbols other than Spring/Jakarta/JUnit types |
| Backend unit + integration tests (`mvn test`) | **Not run**: Maven Central is not reachable from my environment. Written, not executed |
| Backend actually starting against MySQL | **Not run** |
| Android Kotlin syntax (all files, main + test) | Checked with `kotlinc`: no syntax errors (it found and I fixed one) |
| Android resource/ID/import cross-checks, colour contrast | Run: pass |
| Pure Kotlin tests (`FormValidatorTest`, `GreetingProviderTest`) | Run for real: 8 of 8 passed |
| Android ViewModel / repository tests, Gradle build, emulator run | **Not run** (no Android SDK or Maven repositories here) |
| End-to-end: app against the server | **Not run** |

## 9. Manual test procedure (do this on your PC)

1. Start MySQL, create the database and user (README, "MySQL setup").
2. Start the backend (README) and confirm the log says `Started SafeSphereBackendApplication` and that Flyway
   `Successfully applied 1 migration`.
3. Run `mvn test` in `backend`: all tests should pass.
4. Test the API without the app (commands in README, "Test the API from PowerShell"): register, duplicate (409),
   bad input (400), login ok, login wrong password (401), `/api/users/me` with and without token.
5. In MySQL: `SELECT id, name, email, LEFT(password_hash, 7) FROM users;` The hash column must start with `$2a$` or `$2b$`
   and the password must appear nowhere.
6. Run the Android app on the emulator and walk through the checklist in README, "Android manual test checklist".

## 10. Phase 3 boundary

Phase 2 ends here. Not started, by design: the Safety Circle (trusted contacts), emergency/SOS, location, Safe Walk,
reports, evidence, AI, notifications. Phase 3 will most likely add the Safety Circle (a second table and the first real
feature on top of this login), which is the natural next step because every later feature needs "who is this user" and
"who are their trusted contacts".
