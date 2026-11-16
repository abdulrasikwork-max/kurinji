# CLAUDE.md — SafeSphere (brand: IRL) project context

Permanent context for Claude Code. **The repository is the source of truth for code**; this file holds the product
intent, rules and conventions that apply to every task. If code and this file (or a rules file) disagree, inspect the
code and flag the conflict to the user instead of guessing.

> The user's local repository may contain fixes made during Phase 3 live verification that are not in
> `SafeSphere-Phase3.zip`. Always inspect the local repo, never the zip.

**Where the rest of the project knowledge lives (read only what the task needs):**
- `.claude/rules/` — architecture detail, loaded automatically when you open matching files: `android.md` (all of
  `android/`), `android-safety.md` (SOS, Safe Walk, Circle, vault, Nearby, trust, intelligence), `android-social.md`
  (social, profile, moderation screens, text check, media), `backend.md`, `database.md` (migrations, per-migration
  constraints), `moderation-service.md`. When planning across modules, read the relevant ones yourself.
- `docs/STATUS.md` — the full status record per phase (test counts, verification results, code map per phase).
- `docs/DECISIONS.md` — the full decision log with reasons (viva material). New decisions are added there.
- `docs/API.md` — every endpoint; its appendix is the per-phase endpoint and config index.
- `docs/README.md` — index of every doc. Latest state and next steps: top of `docs/HANDOFF.md`.
- `C:\Users\ABDUL RAFIQ\Desktop\CLAUDE.md` (outside the repo) is an old copy; this repo copy is the current one.

**Keeping this file small:** it must stay below 40k characters. New phase results go to the phase doc,
`docs/STATUS.md` (one compact line here) and `docs/HANDOFF.md`; new decisions to `docs/DECISIONS.md` (here only if
they are permanent rules); new class maps to the matching `.claude/rules/` file. Move text, never delete it.

---

## 1. Product

**Brand: IRL — In Real Life.** Line **"OUT THERE. TOGETHER."**, icon **the Tamil letter ai** (`docs/design/irl-icon.md`).
**Package, applicationId (`com.safesphere.app`), signing, database, API and code names stay SafeSphere** (`KurinjiTextsTest`).

**Direction (the owner, 2026-11-15; full record and reasons: `docs/IRL_DIRECTION.md`; it REPLACES the place-first
"experience platform" of 2026-10-22, whose text is in `docs/history/CLAUDE_2026-11-15.md`):** IRL is a **verified,
real-name community for self-expression**; the first community is **MIET Engineering College**. People say what is on
their mind to the people who share their real place, and **"Who's in?"** turns a post into a real-life plan. Safety is a
quiet supporting layer, never the identity.

- **Community, not proximity:** a community is a real institution that IRL recognises (nobody owns it; no channels, no
  roles beyond verifier / moderator / platform admin). One community per person at launch. No location is used anywhere
  in the social product.
- **Verification:** no college e-mail. A trusted person sees the college ID once: in person (a verifier approves) or by
  an uploaded photo (an admin approves). Kept: name, department, graduation year, the roll number only in keyed
  (unreadable) form; the image is deleted at the decision. Bans attach to roll number + phone number. 18+ only. The owner
  and the project teammates are the root admins.
- **Moments:** text, one photo or up to 30 s of voice, **made now inside the app (no gallery)**, 24 h life (a few can be
  kept on the profile). Two kinds: **Say** and **Who's in?** (optional time and a plain-text "where", never a place
  record). Audience: the community, or "my people" (mutual connections).
- **Responses:** **Same** (the author sees who; others see "a few / many", never a number) · **Say more** · **I'm in**
  (only on a "Who's in?"; the author accepts each person; the list is visible to the author and the accepted only) ·
  **private reply** to the author (one per person per post until answered; default: from my people only). No public
  comment threads, no upvotes, no ranking, no followers.
- **Structure:** **Today** (the community wall, 24 h, ends) · **✚ Create** · **Replies** · **You** (real name,
  department, year, live and kept moments; rows My people, Safety, Settings).
- **Sign-in:** phone number + code only (no e-mail, no password). **Push:** Firebase messaging (approved 2026-11-15).
- **Safety (kept):** SOS, Safety Circle, basic Safe Walk, under You plus the app shortcut, tile and opt-in shake; banners
  only while one runs. The Safety Circle message opens the SMS app (no automatic `SEND_SMS`). **Retired from the app
  (kept in git tag `pre-n1` and in `docs/` for the viva):** Nearby signals + trust, safety intelligence, route/stop
  checks, the Evidence Vault and SOS audio, the Phase 9 evaluation work, places, ratings, Explore.
- **Hosting:** the owner's laptop (WSL2, nginx, Cloudflare Tunnel, Spring Boot, MySQL) for development, demo and the
  controlled MIET pilot. No paid cloud without the owner's decision.
- **Still permanent:** nobody can use IRL to monitor another person (no presence, no "last seen", no viewer lists, no
  location on request); a social restriction never touches SOS, Safe Walk or the Safety Circle; bright red means only
  emergency; women's realities are an internal design principle; Tamil/Tanglish content from V1.
- Final-year B.Tech IT team project: the student must be able to explain every important decision in a viva.

---

## 2. Status (full record: `docs/STATUS.md`; per phase: `docs/PHASE*.md`; latest: `docs/HANDOFF.md`)

**Until N4/N5 replace them, the old social screens and rules (posts, places, comments, cells, time bands, delays) are
still in the code and its tests; sections 5-13 below describe that code. Where they contradict section 1, section 1
is the product; flag the conflict instead of "fixing" the new work to fit an old rule.**

| Phase | Scope | Status |
|---|---|---|
| 0–4D, 11, 17F | Foundation, auth, Safety Circle, SOS (also without internet), location, Safe Walk, SMS, shake SOS | Kept. Done and verified as recorded in `docs/STATUS.md` (A23: SOS, offline SOS, 4A/4B core, 4D) |
| 4C | Safe Walk route / stop / battery checks | To be removed in N6 (never verified on a real phone) |
| 5–9, 12 | Evidence Vault, Nearby, trust, intelligence, evaluation; Verified Nearby Assistance | **Retired from the app in N1** (history: tag `pre-n1`, phase docs); 12 never built |
| 10, 14, 15, 17A–17D, 17H–17K, D12 + L1 | Account, security audit, acceptance, OTP, media, location UX, device sprint, release readiness, targetSdk 36, laptop pilot | Kept as foundations; status as recorded in `docs/STATUS.md` |
| 13A–13F, 16, 17E, IRL redesign | Profiles, connections, posts, discovery, moderation v2, text check, navigation | Profiles, connections, blocks, moderation, text check, photo pipeline KEPT; posts / places / feed / comments stay until N4/N5 replace them |
| 17G-1/2/3, 18A-1/2 | Explore, AI understanding, "I went" + ratings, rating trust, presence, disclosure | **Removed in N1** (18A-2 was never verified on a device); 18A-3, 18B, 17I cancelled |
| **N0** | Record the new direction (docs, this file) | done 2026-11-15 (documents only) |
| **N1** | Strip: remove the retired features on both sides, migration V32 | implemented, compiled, tested 2026-11-15 (Android 766, backend 496, Python 38; V32 applied on the DEVELOPMENT MySQL only); results per unit in `docs/PHASE_N1.md`; manually verified on the emulator 2026-11-15 (SOS, Safe Walk, tile, shortcut, post; no real phone; one wording fix) |
| **N2** | Phone-only sign-in, real name, no password, migration V33 | implemented, compiled, tested 2026-11-16 (Android 766, backend 494; V33 applied on the DEVELOPMENT MySQL only); decisions N-21 to N-26; results per unit in `docs/PHASE_N2.md`; manually verified on the emulator 2026-11-16 (no real phone) |
| N3 | Community, membership, verification, bans, admin screens | planned |
| N4 | Moments, capture-only Create, the wall, daily question | planned |
| N5 | Same / Say more / I'm in, private replies, plan threads, Replies tab | planned |
| N6 | Safety trim (route checks out, SMS-app fallback only, Safety Circle offer on plans) | planned |
| N7 | Firebase push | planned |
| N8 | Pilot launch at MIET (backups, SMS provider, policy texts, seeding, moderators) | planned |

Next migration **V34**. Each phase or sub-phase needs its own plan and the owner's approval first. The pilot still
runs the old jar on schema V30 (its move to V32 deletes data: its own approved step).

**Never implement early:** other communities / the community request system, multiple memberships, video, iPhone,
Tamil UI, companies, sub-communities, ads, any new AI feature.

**AI/ML rules (D2, unchanged):** learned models are allowed for content moderation only (Tamil/Tanglish/English text),
under "models produce evidence → a tested deterministic policy decides → only humans restrict people"; they run only
in the Python moderation service and as a plain-Kotlin linear model on the phone (`ModelPlacesTest`). No learned model
acts until gates L1–L3 (`PHASE13E_PLAN.md` E2) and the owner's decision (`app.moderation.ai.model-mode`).

---

## 3. Working rules (permanent)

**Process for every phase or significant feature**
1. Inspect the relevant code first. Never assume class names, structure or schema.
2. Present a concise plan (files, migrations, APIs, permissions, tests, risks) and **wait for approval** before large changes.
3. Implement in small validated units; keep the project buildable after each.
4. Build and run tests; fix failures.
5. Security/privacy review where relevant; update docs.
6. Give the user a manual verification checklist.

**Definition of done:** plan → implement → build → tests → manual verification (where required) → security/privacy
review → important decisions documented and explainable → only then "complete".

**Status vocabulary — always use it, never blur it:** planned · implemented · compiled · tested · manually verified · not verified.
Never claim success because code was generated. Never invent test results. If something could not be run, say so.

**If Android/platform rules make a feature unsafe or impossible:** stop and explain. No fake implementations,
no placeholders presented as working, no TODOs for core functionality.

**Token efficiency — never at the cost of quality**
- Read only files relevant to the task; reuse this file and `docs/` instead of restating history.
- Concise plans and reports; no repeated explanations.
- `/clear` between genuinely separate tasks; `/compact` when history grows large.
- Sonnet for routine implementation; Opus for architecture, security, complex debugging, cross-cutting decisions.
- Do **not** save tokens by skipping inspection, tests, edge cases, security review, or necessary reasoning.

**Viva explanations:** for each major feature give a short *what changed / why / key classes / API + DB flow /
Android lifecycle implications / security + privacy / likely viva questions*. No essays for trivial changes.

---

## 4. Repository and commands

```
SafeSphere/
├── android/   Kotlin app — open THIS folder in Android Studio (has gradlew)
├── backend/   Spring Boot API — Maven (no mvnw wrapper; Maven is installed)
├── docs/      one doc per phase + references; index in docs/README.md (STATUS.md, DECISIONS.md, API.md, HANDOFF.md …)
├── moderation-service/  Phase 13E Python service (stdlib only): README.md, NORMALISER.md, restricted/ (sensitive, git-ignored)
└── README.md
```

Windows:
```
cd backend
mvn test
mvn spring-boot:run      # expect Flyway "Successfully applied ..." then "Started SafeSphereBackendApplication"

cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```
- Backend secrets via env vars or git-ignored `backend/.env` (`spring.config.import=optional:file:./.env[.properties]`):
  `DB_PASSWORD`, `JWT_SECRET` (≥32 chars — the app refuses to start otherwise), optional `DB_USERNAME` (safesphere),
  `DB_URL`, `JWT_EXPIRATION` (24h), `PORT` (8080), `CORS_ALLOWED_ORIGINS` (empty).
- One-time DB setup: `backend/sql/setup.sql` (database `safesphere`, least-privilege user `safesphere`).
- Android backend URL: `android/local.properties` → `api.base.url=http://10.0.2.2:8080/` (default; `10.0.2.2` is the
  host PC from the emulator). Cleartext HTTP allowed **only in debug builds** (`app/src/debug/res/xml/network_security_config.xml`).
- Build gotcha (this Windows sandbox): Gradle/Tomcat fail with "Unable to establish loopback connection"; run with
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Public` (backend: `-Dspring-boot.run.jvmArguments=...`).

---

## 5. Android architecture (`com.safesphere.app`) — detail in `.claude/rules/android*.md`

- **Stack:** Kotlin 2.0.21, AGP 8.13.2 (Gradle 8.14.6), XML Views, Fragments, ViewBinding, single Activity (`MainActivity`), one Navigation
  graph, MVVM, repositories, Coroutines/Flow, DataStore, Retrofit 2.11 + OkHttp 4.12 + Gson, Material 3, Coil (13B, D7),
  `play-services-location`. minSdk 26, compile/target 36 (Play, 17K). **No Compose. No Hilt** unless explicitly approved. No new
  dependency without the user's approval.
- **Packages:** feature-first `feature/<name>/{domain,data,ui}`; shared code in `core/`, `navigation/`. Features: splash,
  onboarding, auth, safety (You → Safety hub + session status), profile (You), settings, safetycircle, emergency
  (+ `entry`: SOS shortcut/tile), safewalk, circlealert, moderation (content queue only), account, social, going
  (navigation bridge only). Removed in N1: `feature/community`, `evidence`, `intelligence` (`core/retired` holds the
  one-time clean-ups). No screen shows "Coming soon".
- **Navigation (since N1):** Home · ✚ Share (action → composer) · You; start = Home (`discoverFragment`); "Settings and
  more" (`moreFragment`) under You. Bar on tabs + reading screens (post, place/category page, person profile);
  task/safety screens have toolbar + back. Safety banners (SOS, walk, deletion, sign in to sync) on every tab and
  reading screen via `MainActivity` + `SessionStatusViewModel`, never on the SOS screen.
- **Isolation:** social and safety never import each other; `feature/going` imports neither (`GoingThereTest`).
- **DI:** manual `AppContainer` (composition root) via `Fragment.appContainer`; each ViewModel has `factory(container)`.
- **Networking:** exactly **one** Retrofit instance; all APIs are created from it in `AppContainer`. `AuthInterceptor`
  adds the Bearer token only to the backend origin (17J), never to `/api/auth/*`. **No HTTP logging interceptor** (codes, tokens).
- **Errors:** repositories use `core/network/ApiCallHandler` → `ApiResult<T>` (`Success` / `Failure(ApiError, message?)`).
  Only our own 409 messages (and our 403/429 messages) reach users; other errors use app strings. A 401 goes through
  `core/network/SessionEnder` (clears the session only if the rejected token is still the saved one).
- **Session (C1): expired ≠ revoked.** A 401 without a reason → `SessionRefresher` → one retry; a refused refresh or
  `session_revoked` → "sign in to sync" and **nothing is deleted** (SOS, unsent endings, Circle copy and walk stay; SOS
  works); `credentials_changed` / `account_deleted` / `account_unavailable` → sign-out. Session changes use the atomic
  `SessionStorage.update`. The 24 h access token is Keystore-sealed, never plain text (17J, J-8); `allowBackup=false`.
- **One holder per concern, in application scope** (`SosRecorder`, `EmergencyLocationSharing`, `SafeWalkSession`,
  `CircleAlertCoordinator`, …); screens and the ONE `SafetyTrackingService` read the same holder.
  Detection and decision logic are **pure, unit-tested policies** (`CheckInPolicy`, `MovementPolicy`, `BatteryPolicy`,
  `CircleAlertPolicy`, …). Every SOS starts on the phone (Phase 11).
- **Location (17D):** one permission path (`LocationAsk` + pure `LocationNeeds`); screens other than the SOS screen must
  not register their own location launcher or read a stream (`Phase17DLocationTest`); a rough position never places
  anything (≤ 100 m for reports and places); screens stop location searches in `onStop`.
- **Nothing about other people is stored on the phone (Q3):** one Coil `ImageLoader` on the shared OkHttp client, memory
  cache only; videos stream with the sign-in header, play only after a tap; social data is never persisted (the person's OWN Home city is, per account: IRL-R6).
- **Wording is guarded by tests** (keep them green; extend them for new strings): `HonestyTextsTest`, `KurinjiTextsTest`,
  `SocialTextsTest`, `ModerationV2Test`, `AiModerationTest`, `ContrastTest`, `EmergencyTreatmentTest`,
  `ResourceEncodingTest`, `Phase17FGuardsTest`.
- **Permissions today (since N1):** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
  `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_NETWORK_STATE`, `SEND_SMS` (asked
  only on opt-in; leaves in N6). `RECORD_AUDIO` left with the vault and comes back in N4 for voice moments, asked in
  context. Photo/video via the Photo Picker and camera-app intents — **never declare `CAMERA`**.
  Telephony/microphone/camera features not required. `dataExtractionRules` exclude all data.
- **Tests:** JUnit4 + kotlinx-coroutines-test, `MainDispatcherRule`, fakes in `src/test/.../testing/`. Background holders
  need `runCurrent()`/`advanceTimeBy()`, NOT `advanceUntilIdle()` (it ignores `backgroundScope`); real-recorder tests use
  `sosRecorderForTest` + `settle()`.

---

## 6. Backend architecture (`com.safesphere.backend`) — detail in `.claude/rules/backend.md`

- **Stack:** Java 17, Spring Boot **3.5.16** (OSS support ended 30 Jun 2026 — upgrading to 4.x is a separate, planned step),
  Spring Web, Data JPA, Security (OAuth2 resource server for JWT), Bean Validation, Flyway, MySQL 8.
- **Packages (since N1):** `auth` (+ `auth/phone`), `user`, `account`, `safetycontact`, `emergency`,
  `emergencycontext`, `escalation`, `journey`, `circlealert`, `moderation` (+ `moderation.assessment`), `social`,
  `security`, `config`, `common` (+ `common/geo` `GeoCell`, `common/independence`) (each
  `controller/service/repository/entity/dto`). Removed in N1: `evidence`, `community`, `intelligence`, `evaluation`,
  `explore`.
- **Conventions:**
  - Thin controllers; rules in services; DTOs are Java records; entities are never serialized.
  - Caller identity **only** from the JWT: `CurrentUser.id(jwt)` (`sub` claim). No `userId` in any request DTO.
  - Repositories returning owned data always filter by user (`findByIdAndUserId`, …). Another user's record → **404** (same as missing).
  - Throw `ApiException` subclasses (`InvalidRequestException` 400, `UnauthorizedException`
    401, `ResourceNotFoundException` 404, `ConflictException` 409); `GlobalExceptionHandler` renders one JSON shape:
    `timestamp, status, error, message, path, fieldErrors?, code?`. No stack traces or exception text to clients.
    Stable `code`s for refusals the app must act on (`AGE_NOT_CONFIRMED`, `PROFILE_NEEDED`, `TICKET_INVALID`, `OTP_*`).
  - Wrong confirmation codes on account endpoints answer **409**, not 401 (a 401 means "signed out" on the phone).
  - `spring.jpa.hibernate.ddl-auto=none`, `open-in-view=false`. **Flyway owns the schema.**
  - Never catch a unique-key error inside a transaction and carry on: write the row in its own transaction or retry the
    whole attempt (Phase 14).
  - Time is UTC everywhere; inject `Clock`; no `LocalDateTime.now()` without `ZoneOffset.UTC`/`Clock` (`TimeConventionsTest`).
- **One visibility definition per area:** social `Visibility` (every read path, tested with author / connection /
  stranger / blocked). **Isolation tests are invariants:** `SocialIsolationTest`, `ModelPlacesTest` (two outbound
  clients: moderation service, SMS; nothing names an AI provider); `AccountEraserCoverageTest` (the eraser must cover every `REFERENCES users`). `SocialMediaCleanupJob` is the ONLY
  `@Scheduled` in `social`.
- **Auth/JWT:** HS256, secret from env (≥ 32 chars), issuer `https://safesphere.example`, `sub` = user id, 24 h, claims
  `sid`, `auth_time`, `cca`. Device sessions with rotating refresh secrets (V19); `security/ActiveAccountCheck` refuses
  missing/erased/disabled accounts, changed credentials and ended sessions (401 + RFC 6750 `error_description`). Public:
  `POST /api/auth/phone/start|verify|resend|change`, `/api/auth/sign-up|refresh|sign-out` (N2: no register/login); everything else
  authenticated; `/api/moderation/**` MODERATOR/ADMIN, `/api/admin/**` ADMIN (roles read from the DB per request; ADMIN
  only via SQL; role changes name a verified number). CSRF off (no cookies); CORS no origins by default. No password,
  no BCrypt, no e-mail (N2); a session needs a verified number (N-22). Sensitive actions: a fresh code (`CodeGuard`).
- **Tests:** `@SpringBootTest` + MockMvc, real JWT flow, H2 in MySQL mode with **Flyway disabled** and Hibernate
  `create-drop` (`src/test/resources/application.properties`). Helper `support/TestAccounts`. Two-user ownership tests
  are the norm. ⚠ H2 does not exercise MySQL-only DDL (generated columns, CHECKs): **verify every new migration on real
  MySQL** (backup, dry run of the CHECKs on a scratch copy, then apply). Real-MySQL check: `SAFESPHERE_MYSQL_TESTS=true`.

---

## 7. Database (MySQL `safesphere`, Flyway) — per-migration rules in `.claude/rules/database.md`

Conventions: never edit an applied migration; next file is **V34**; snake_case; `DATETIME(6)` stored in UTC; constraint
prefixes `uk_`, `fk_`, `ck_`, `idx_`; FKs `ON DELETE CASCADE` (no orphans); InnoDB, utf8mb4_unicode_ci. "One active /
one primary / one open" via a generated marker column + unique index. MySQL forbids CHECKs on cascading-FK columns
(enforce those rules in code); write NULL cases out with `COALESCE` (a NULL makes `IN` UNKNOWN, which a CHECK lets pass);
closed lists use binary collation.

| Migration | Tables / columns |
|---|---|
| V1–V3 | `users`; `safety_contacts`; `emergency_events` |
| V4–V8 | `emergency_locations`; `safe_journeys`, `journey_events`; `escalation_events` + `emergency_events.journey_id`; journey destination + reasons; `circle_alerts` |
| V9–V11 | `evidence_items`, `evidence_custody_events`; custody actions; `evidence_recovery_envelopes`, `evidence_recovery_failures` |
| V12–V15 | `place_signals`, `signal_contributions`; roles, standing, flags, `moderation_cases/actions/appeals`; `area_patterns`; `evaluation_counters` |
| V16–V19 | account deletion columns; `emergency_events.started_session/ended_elsewhere`; `client_ref/started_offline`; `device_sessions` |
| V20–V24 | `profiles`, `connections`, `connection_invites`, `blocks`, `mutes`; `places`, `posts`, `post_media`, `comments`; `butterflies`; content moderation tables; `content_assessments` |
| V25–V28 | e-mail verification (superseded); phone verification `phone_challenges`; `post_media.kind` (video); `cities`, `saved_posts`, `daily_states`, `profiles.photo_key` |
| V29 | `post_facets` (Explore tags on experiences, 13 closed codes, ≤ 4 per post in code) |
| V30 | `place_visits` ("I went" + optional rating 1–5 on the same row, private to its owner) |
| V31 | `place_visits.at_place`, `posts.disclosed` (18A-2) |
| **V32** | **N1: DROPS** the V9–V11 evidence tables, `place_signals`, `signal_contributions`, `signal_flags`, `contributor_standing`, `moderation_cases`, `area_patterns`, `evaluation_counters`, `butterflies`, `saved_posts`, `daily_states`, `post_facets`, `place_visits`, `posts.disclosed`, `moderation_actions.signal_category` (30 tables remain) |
| **V33** | **N2:** `phone_codes` (SIGN_IN / CONFIRM, sign-up ticket) replaces `phone_challenges`; DROPS `email_verification_tokens`, `users.email` / `email_verified_at` / `password_hash`, `profiles.display_name`; session reason `OTHERS_SIGNED_OUT` |

---

## 8. API — every endpoint in `docs/API.md` (per-phase index in its appendix)

- One error JSON shape (§6); a foreign or invisible record answers **404**; refused writes by a paused person **403**;
  limits **429** (our message shown).
- Every authenticated endpoint can answer 401 with `WWW-Authenticate: Bearer error="invalid_token",
  error_description="credentials_changed|account_deleted|account_unavailable|session_revoked"`.
- (OLD social code, until N4/N5:) social answers never carry coordinates (cells only) or exact times for others (bands only); no counts except U1 and
  the author-only butterfly count (from 3).
- Update `docs/API.md` (and its appendix index) with every endpoint change.

---

## 9. Security and privacy rules

- No secrets in code or git; no production credentials; `.env` and `local.properties` stay git-ignored.
- Ownership always from the authenticated principal; never trust client-supplied user/owner/emergency/journey ownership.
- Never log passwords, tokens, or precise locations; Android DTO `toString()` redacts secrets.
- Collect the minimum data; minimise location retention.
- Request permissions **in context** with a rationale; handle denial, permanent denial and revocation; never at launch.
- No microphone or camera before the Evidence/AI phases. Phase 5: only user-started, visible, during an ACTIVE emergency.
  **17F (F17-4):** audio may also start by itself in an SOS, but ONLY after the person agreed in advance (one question,
  default No, changeable in SOS options; the permission is asked then, never during an SOS), from the visible SOS screen,
  once per SOS, visible and stoppable. Shake SOS (opt-in) only opens the same cancellable 5 s countdown.
- Never claim emergency-service integration, SMS delivery, or that contacts were notified unless it happened and was
  confirmed. SMS "sent" ≠ delivered.
- Document platform limitations honestly (`docs/PLATFORM_CONSTRAINTS.md`).
- **Social features (Phase 13, built; design doc §4; OLD social code, replaced in N4/N5: the new product uses no location and no delay, see section 1):** no coordinates in any social request or response (cells
  only); public times as bands, never timestamps; private places stay on the phone; EXIF stripped on the phone and
  re-encoded on the server; one visibility definition (social `Visibility`) for every read path, tested with
  author / connection / stranger / blocked users; connection lists, block lists and saves visible only to their
  owner (butterfly reactions: the author sees a count from 3, never who); social packages never read safety data and safety never depends on social features; a posting restriction
  never touches SOS, Safe Walk, the Safety Circle or the vault. 18+ at launch, self-declared date of birth at sign-up (D6, decided 2026-10-22).
- **Media metadata (Phase 17C, built):** photos are re-encoded on the phone and again on the server; videos are
  re-packed on the phone (first picture + sound track only, no location, muxer `meta` zeroed) and checked + stripped of
  every metadata box and timestamp by the server; data inside the coded stream is not inspected (documented limit);
  videos stream to the player with the sign-in header and are never stored on the phone.
- **Phone ownership (Phase 17B, V26, built; replaces V25 e-mail links):** no new account gets a session before a code
  texted to its number is entered; codes are 6 random digits, 5 min, stored only as an HMAC, never logged or returned;
  5 wrong codes kill a code; cooldown + per-challenge, per-number and per-address limits; the challenge token is
  256-bit, SHA-256 stored; one verified account per number; SMS only after commit, provider refusals undone; provider
  credentials only from the environment; the dev sink writes codes to a local file only (never in production). Accounts
  from before 17B keep their sessions and verify a number at their next sign-in. **N2 (V33):** the number + code is
  the ONLY way in (N-21: same answer for every number; a new number gets a one-use 30-min sign-up ticket); deletion,
  its cancel, "sign out all other phones" and ending an SOS from another sign-in need a fresh code to the own number.
- **Moderation (Phase 13D, built):** the reported person is never told who reported or that a report exists (no
  notice, no refused edit/delete, no "keep" in their standing); reports only collapse (visibly, at 3 independent
  counting reports); hold, removal and pauses are human decisions with a reason, audited and appealable to another
  moderator (never the decider or a reporting moderator); no shadow bans: every state is shown to its author.
- **E-mail ownership (V25, superseded by 17B; e-mail and password gone in N2):** history in `docs/EMAIL_VERIFICATION.md`. A future
  password reset must use its own token table and refuse unverified accounts.

---

## 10. Design system and UX (full spec: `docs/DESIGN_SYSTEM.md`; earlier identities in its History section)

- **Two palettes (IRL-C1):** social screens wear `ThemeOverlay.IRL.Social` (cream page / cherry actions by day; cherry-black
  page, cream actions, cherry only as a surface at night); safety screens keep "Ink & Lime" (E17-1) untouched. Bright red
  means only emergency. Headlines + wordmark: Fraunces (`irl_serif`); body, buttons, emergency text: Mukta Malar.
- **Emergency UI (U4):** siren icon + the words SOS/Emergency + its black-and-red treatment, never colour alone; clear,
  fast, high contrast, low cognitive load, large touch targets, `keepScreenOn` while active. Emergency digits use the
  phone's sans.
- All text pairs ≥ 4.5:1 contrast, control edges ≥ 3:1 (`ContrastTest`). Status never by colour alone. Moderation words
  amber, never red.
- Use theme attributes and existing styles/components; no hard-coded colours in layouts. Never set
  `android:fontFamily` on the theme (it overrides text appearances).
- Narrow screens / large text: marks lose their words before any button (`MarksFit`); spoken labels unchanged.
- Honesty labels: **Available** (works), **Preview** (design only), **Coming soon** (not built), **Sample** (placeholder
  data). No text claims a place or person is safe (Phase 8). No ranking words (for you, top, trending, popular…).

---

## 11. Safety design invariants (approved Phase 4 design: appendix of `docs/PHASE4A.md`)

- Detection logic runs **on the phone in pure Kotlin** (works offline, unit-tested); the **server** stores records,
  validates state transitions, enforces ownership and computes check-in deadlines (phone clocks drift).
- **One foreground service** (`SafetyTrackingService`) during Safe Walk or an active emergency; persistent notification;
  started only while the app is visible (`START_NOT_STICKY`, recovery on next open).
- **Escalation ladder** (enum + pure policy, never jumps to the top): L0 normal · L1 missed check-in/minor concern →
  prompt the user · L2 no response/anomaly → Safety Circle · L3 confirmed SOS → emergency ACTIVE · L4 **user-tapped dial
  prompt (112)**, no claimed integration, no `CALL_PHONE` · L5 Verified Nearby Assistance = reserved, **rejected by the
  server**. Movement concerns only warn; never L3 by themselves.
- **Emergency Context Packet** = a read model assembled on demand; recipient projections are field **allow-lists**; the
  Safety Circle sees only name, time, type, location link.
- Safety Circle SMS: automatic only if the person opted in (`SEND_SMS`), otherwise the SMS app opens; SMS "sent" ≠
  delivered. Journeys store only the last-known location (no track); destination deleted at a normal end.
- A posting/community restriction **never** touches SOS, Safe Walk, the Safety Circle or the vault. Sign-out and
  authentication failures never destroy a running SOS or evidence.
- Not built (documented limitations): server-side dead-man alerts/push, background location, exact alarms, boot receiver.

---

## 12. Known limitations and gotchas

- Safe Walk can only escalate while the phone is alive and the process runs; no server-side alert channel exists yet.
- SOS without internet (Phase 11) needs a signed-in session on the phone; points collected before a process death are
  lost (memory only); evidence recording waits for the server record; sign-out clears endings not yet sent.
- OEM battery killers (Xiaomi, Oppo, Vivo, Samsung) may stop foreground services; recover on next app open; document it.
- Platform `Geocoder` is unreliable — if a destination cannot be resolved, run the walk without deviation detection and say so.
- Phone numbers are normalized but not country-aware (`9876543210` ≠ `+919876543210`).
- Spring Boot 3.5.16 is past end of OSS support.
- **Kotlin nests block comments:** a `/*` inside a KDoc (e.g. writing `/api/auth/*`) breaks compilation.
- Retrofit `DELETE` endpoints that return 204 are declared with no return type (`suspend fun deleteX(...)`).
- H2 tests do not validate MySQL-specific DDL (see §6).
- **Time = UTC, always** (`docs/TIMESTAMPS.md`): `DATETIME(6)` holds UTC wall clock; `main()` calls `UtcTimeZone.install()`;
  JDBC URL keeps `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`; `TimestampStorageVerifier` refuses to start on
  a shifted value; entities truncate to micros; no `LocalDateTime.now()` without `ZoneOffset.UTC`/`Clock`
  (`TimeConventionsTest`); surefire runs in Asia/Kolkata on purpose. Real-MySQL check: `SAFESPHERE_MYSQL_TESTS=true`.
  Rows written before the fix were corrected on 2026-10-20 (`backend/sql/fix_utc_offset_ist.sql`, one-time, verified; never rerun).
- **Encoding:** Windows PowerShell 5.1 `Get-Content`/`Set-Content` read BOM-less UTF-8 as Windows-1252 and add a BOM —
  never round-trip source files through them (it turned "…" into "â€¦" in `strings.xml`). `ResourceEncodingTest` guards res/.
- **Live-check scripts (PowerShell 5.1):** `$home` is read-only; a one-element array returned from a function unrolls;
  Tamil text needs a UTF-8 **BOM**; wrap in try/finally. Full list: `docs/PLATFORM_CONSTRAINTS.md` (end).
- **Python (13E):** none on PATH on this PC; how to run the service and its tools: `.claude/rules/moderation-service.md`.
- **Real-phone testing over USB and the emulator snapshot gotcha:** end of `.claude/rules/android.md`.

---

## 13. Permanent decisions (all 130+ decisions with reasons: `docs/DECISIONS.md`)

| Decision | Reason |
|---|---|
| XML Views + Fragments, manual `AppContainer`, feature-first packages; no Compose, no Hilt | Approved in Phase 1; viva-friendly; less magic to explain |
| Flyway owns the schema (`ddl-auto=none`); never edit an applied migration | Reviewed, versioned schema changes |
| Caller identity only from the JWT; a foreign record → 404 | No client-supplied ownership; does not reveal that ids exist |
| "One active / one primary / one open" via generated columns + unique index | Correct under concurrent requests |
| **Nobody can monitor another person, permanently** (no family/partner/guardian mode, no location on request) | A monitoring feature turns a safety tool into a control tool |
| Every SOS starts on the phone; the server is told later with a client reference | SMS and GPS work without internet; the button must not depend on a round trip |
| Expired ≠ revoked: a failed refresh is "sign in to sync" and deletes nothing | Authentication failing must never destroy a running SOS, its ending or the offline Circle copy |
| Another sign-in ends an ACTIVE SOS only with a fresh texted code (N2; was the password); no automatic "resolved" SMS from another phone | Nobody can silently end someone's SOS; only the person on a phone can say they are OK |
| Evidence encrypted on the phone (per-item key); server stores ciphertext only; deletion needs the password and is refused during / 24 h after an emergency | A breach reveals no evidence; coerced deletion is blocked |
| "Sign out all other phones" (N2; was the password change) and account deletion refused during an ACTIVE SOS/walk; deletion has a 7-day grace | Revocation must not cut a running safety session; coerced deletion is recoverable |
| Community = place signals (cells only, no authors, no text, bands not counts); trust private, never shown | Safety intelligence, not engagement; no accusations of people |
| Content can be hidden automatically (reversible); people are restricted only by a human, with a reason, audited, appealable to another moderator; no shadow bans | Automatic punishment fails unsafely; moderator abuse is auditable |
| AI produces evidence → a deterministic policy decides → only humans restrict (D2); an outage publishes; self-harm never stored | An AI mistake must not silence anyone; infrastructure failure must not silence people |
| Safety intelligence = deterministic rules, no score, "unknown" is never "safe"; no text claims a place or person is safe | Explainable, attack-resistant, honest |
| OLD social code only (until N4/N5; section 1 replaces it: private replies and plan threads exist, no places): no followers, mutual connections only, no DMs, no presence, no counts except U1; cells and time bands only; private places stay on the phone | Following someone's real-world posts is monitoring; public posts must not reveal routines |
| OLD social code only (until N4): every list of others' posts is ordered by `visible_from`, never `created_at` | The order must not reveal the time the delay hides |
| Phone OTP (provider-neutral `OtpSender`); since N2 the number + code is the ONLY sign-in, no password, no e-mail (N-21) | Sign-ups were stuck without a mail server; codes and limits stay in tested code |
| Media: metadata stripped on the phone and again on the server; videos tap to play, never stored on the phone | Location privacy; Q3 (nothing about other people on the phone) |
| Visible brand IRL; package, applicationId, signing and code names stay SafeSphere | A rebrand must update the installed app in place |
| Time = UTC everywhere (`docs/TIMESTAMPS.md`) | Shifted timestamps were found and fixed once; `TimestampStorageVerifier` refuses to start on a shift |
