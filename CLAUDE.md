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

**Brand: IRL — In Real Life** (the user's decision, 2026-11-03; replaces Kurinji; trademark, domain and store checks
still to do). Line **"OUT THERE. TOGETHER."**, icon **"the Meet"** (`docs/design/irl-icon.md`), opening screen and name
rules in `docs/DESIGN_SYSTEM.md`. **Package, applicationId (`com.safesphere.app`), signing, database, API and code names
stay SafeSphere** (a rebrand, not a new app; `KurinjiTextsTest` pins it).

**Direction — FINAL (the user, 2026-10-22; design `docs/EXPERIENCE_PLATFORM_DESIGN.md`, decisions D1–D15 in its §15;
built in Phases 13–17):** a social platform for real-world experiences and exploration, with the existing safety
technology as a trust layer underneath — *people share first-hand experiences of places and activities, written for
the next person, so others feel inspired and equipped to go experience the world themselves, with quiet safety built
in.* Philosophy: **"Experience it. Share it forward. Fear less."** Do **not** treat it as a planning/outings app or return
to `AUDIT_NEXT.md` Part II (superseded). Do not keep re-opening the core concept. The project is not being restarted;
Phases 1–3 are the foundation.

- Safety is **not** the identity, the main screen, onboarding, navigation or branding; it appears when someone moves
  from reading to going ("Going there?" sheet, You → Safety, active walk/SOS banners). **Every safety guarantee is kept.**
- Structure: **Discover · ✚ Share · Saved · You** (the Saved tab keeps the nav id `cocoonFragment`). Experience + Ask
  posts (Takes in V2), Perspectives (comments), mutual Connections, visibility Everyone / Connections / Only me.
- 🦋 butterfly = a **reaction** on others' Experiences (the author sees a count from 3, never who); **Save** = a private
  bookmark (D-3). Today's state: a closed list, connections only, the IST day only (D-7).
- **Permanent product rules:** no follower/following, no public counts (except U1), no DMs, no reels/stories, no streaks,
  no popularity rankings, no endless feed, no presence/online status, notifications only from people. **Nobody can use
  the platform to monitor another person** (no family/partner/guardian mode, no location on request, no remote view;
  applies to social features too).
- Changed by the user on 2026-10-26 (Phase 16, `docs/PHASE16.md`): **U1** a post shows how many comments the viewer can
  read (only comments; computed through the comment visibility rule, so it reveals nothing the viewer could not count);
  **U2** Discover may show "Talking now", ordered by the newest visible comment, never by totals, no numbers; **U3**
  Discover auto-loads the next page but always **ends** with an "all caught up" card; **U4** emergency UI never relies on
  colour: siren icon + the words SOS/Emergency + its own black-and-red treatment.
- Colours (E17-1, 2026-11-02): **"Ink & Lime"** (ink black + lime #ADFF2F, `docs/DESIGN_SYSTEM.md`); red again means only
  emergency (`ContrastTest` pins the emergency values).
- Women's realities are an internal design principle only (D4). Chennai first; Tamil/Tanglish content from V1, Tamil UI
  in V2 (D5). 18+, self-declared date of birth, never stored (D6).
- Final-year B.Tech IT team project: the student must be able to explain every important decision in a viva.
- History (SafeSphere "as built to Phase 11": SAFE / RISE; Rise retired in 13F, D9): appendix of
  `docs/EXPERIENCE_PLATFORM_DESIGN.md`.

---

## 2. Status (full record: `docs/STATUS.md`; per phase: `docs/PHASE*.md`; latest: `docs/HANDOFF.md`)

| Phase | Scope | Status | Not verified (never claim otherwise) |
|---|---|---|---|
| 0–3 | Planning; Android foundation; Spring Boot + MySQL + JWT; Safety Circle + manual SOS | Done; 2 and 3 live verified | — |
| 4A–4D | Location + notifications; Safe Walk; route/stop/battery checks; SMS fallback, Context Packet, Call 112 | implemented, compiled, tested | manual: 4A, 4B pending; 4C partial; 4D skipped by the user |
| 5 | Evidence Vault + hardening (V10) + restore on a new phone (V11) | implementation-complete, emulator-verified | A23 partial; other real-phone checks deferred (`PHASE5.md` §11.5.1) |
| 6 | Community place signals ("Nearby") | implemented, compiled, tested, emulator | real phone |
| 7 | Verification, trust, moderation | implemented, compiled, tested, emulator | TalkBack, dark mode, A23 |
| 8 | Safety Intelligence (area picture, patterns, "Check the way"; no route planner) | implemented, compiled, tested, emulator | TalkBack, real phone, real pattern episode |
| 9 | "Evidence before AI": 9A attack simulation, 9B counters, 9C coordination, 9D neighbour join | 9B and 9C accepted, frozen; 9D awaiting the user's review; **no learned model** | real-world rates, TalkBack, A23 |
| 10 | 10A foundation + 10A.4 session continuity; 10B Rise (retired in 13F) | verified (MySQL, emulator) | TalkBack, real phone; 10C not built |
| 11 | SOS without internet (V18) + C1 device sessions (V19) | verified (emulator incl. server down) | real phone, TalkBack, 25 h idle |
| 12 | Verified Nearby Assistance | not started | — |
| 13A–13F | Identity, experiences, discovery, moderation v2, AI v1, new UI/navigation | each implemented, compiled, tested, live MySQL, emulator | real phone, TalkBack speech, dark mode; 13R research track planned |
| 14 | Security, privacy + abuse-resistance audit (P14-1…3 fixed) | implemented, compiled, tested, live MySQL | real phone |
| 15 | Final integration + acceptance testing | PARTIAL: 31/36 PASS, 0 FAIL | 5 NOT VERIFIED (real phone, TalkBack…) |
| V25 | E-mail verification | superseded by 17B (history) | real mailbox |
| 16 | UX reset (U1–U4) | implemented, compiled, tested, emulator | physical phone, TalkBack, real e-mail |
| 17A–17E | Stabilise; phone OTP (V26); media (V27); location UX; social redesign (V28) | each implemented, compiled, tested, verified (MySQL/emulator; 17B also on the A23) | no real SMS sent; physical phone; TalkBack by ear; 17E on the A23 install + launch only |
| IRL | Rebrand, icon "the Meet", opening screen | implemented, compiled, tested, emulator; local commit | physical phone, TalkBack |
| 17F–17J | Rest of `docs/PHASE17_PLAN.md` | planned | — |

Next migration **V29**. Each phase or sub-phase needs its own plan and the user's approval first.

**Never implement early:** Verified Nearby Assistance (Phase 12; "Circle Response" in `PHASE10_DESIGN.md` §32 stays a
proposal); advanced route intelligence (a real route planner needs an on-device routing graph + a new privacy review,
`PHASE8_DESIGN.md` §16.4). Rise is retired.

**AI/ML rules:** everything users see from Phase 8/9 safety intelligence is decided by rules; no learned model there
without gates G1–G3 and the user's decision. **D2 (2026-10-22):** learned models are allowed for content moderation only
(Tamil/Tanglish/English text, images), under "models produce evidence → a tested deterministic policy decides → only
humans restrict people"; they run only in the Python moderation service and as a plain-Kotlin linear model on the phone
(`ModelPlacesTest` pins exactly those places). No learned model acts until gates L1–L3 (`PHASE13E_PLAN.md` E2) and the
user's decision (`app.moderation.ai.model-mode`). Promoting rules-2 (`app.intelligence.rules-2-mode=ACTIVE`) is the
user's decision, never a default. Phase 9 is frozen; nothing that decides what people see may read a 9B counter
(`EvaluationIsolationTest`). **D-2:** AI Explore may use the Claude API with request text + candidate place facts only;
the deterministic engine must work without it.

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

- **Stack:** Kotlin 2.0.21, AGP 8.7.3, XML Views, Fragments, ViewBinding, single Activity (`MainActivity`), one Navigation
  graph, MVVM, repositories, Coroutines/Flow, DataStore, Retrofit 2.11 + OkHttp 4.12 + Gson, Material 3, Coil (13B, D7),
  `play-services-location`. minSdk 26, compile/target 35. **No Compose. No Hilt** unless explicitly approved. No new
  dependency without the user's approval.
- **Packages:** feature-first `feature/<name>/{domain,data,ui}`; shared code in `core/`, `navigation/`. Features: splash,
  onboarding, auth, safety (You → Safety hub + session status), community (Nearby), profile (You), settings,
  safetycircle, emergency (+ `entry`: SOS shortcut/tile), safewalk, circlealert, evidence, moderation, intelligence,
  account, social, going (navigation bridge only). `feature/map`, `feature/home` and `feature/rise` no longer exist.
  No screen shows "Coming soon".
- **Navigation:** Discover · ✚ Share (action → composer) · Saved · You; start = Discover. Bar on tabs + reading screens
  (post, place/category page, person profile); task/safety screens have toolbar + back. Safety banners (SOS, walk,
  deletion, sign in to sync) on every tab and reading screen via `MainActivity` + `SessionStatusViewModel`, never on the
  SOS screen. Nearby (`communityFragment`) is under You → Safety.
- **Isolation:** social and safety never import each other; `feature/going` imports neither (`GoingThereTest`);
  intelligence stays out of SOS, tracking, Safe Walk, circle alerts and evidence (`IntelligenceIsolationTest`).
- **DI:** manual `AppContainer` (composition root) via `Fragment.appContainer`; each ViewModel has `factory(container)`.
- **Networking:** exactly **one** Retrofit instance; all APIs are created from it in `AppContainer`. `AuthInterceptor`
  adds the Bearer token to everything except `/api/auth/*`. **No HTTP logging interceptor** (bodies contain passwords).
- **Errors:** repositories use `core/network/ApiCallHandler` → `ApiResult<T>` (`Success` / `Failure(ApiError, message?)`).
  Only our own 409 messages (and our 403/429 messages) reach users; other errors use app strings. A 401 goes through
  `core/network/SessionEnder` (clears the session only if the rejected token is still the saved one).
- **Session (C1): expired ≠ revoked.** A 401 without a reason → `SessionRefresher` → one retry; a refused refresh or
  `session_revoked` → "sign in to sync" and **nothing is deleted** (SOS, unsent endings, Circle copy and walk stay; SOS
  works); `credentials_changed` / `account_deleted` / `account_unavailable` → sign-out. Session changes use the atomic
  `SessionStorage.update`. The 24 h access token is **not encrypted** yet (known limitation); `allowBackup=false`.
- **One holder per concern, in application scope** (`SosRecorder`, `EmergencyLocationSharing`, `SafeWalkSession`,
  `CircleAlertCoordinator`, `EvidenceVault`, …); screens and the ONE `SafetyTrackingService` read the same holder.
  Detection and decision logic are **pure, unit-tested policies** (`CheckInPolicy`, `MovementPolicy`, `BatteryPolicy`,
  `CircleAlertPolicy`, `CommunityPolicy`, …). Every SOS starts on the phone (Phase 11).
- **Location (17D):** one permission path (`LocationAsk` + pure `LocationNeeds`); screens other than the SOS screen must
  not register their own location launcher or read a stream (`Phase17DLocationTest`); a rough position never places
  anything (≤ 100 m for reports and places); screens stop location searches in `onStop`.
- **Nothing about other people is stored on the phone (Q3):** one Coil `ImageLoader` on the shared OkHttp client, memory
  cache only; videos stream with the sign-in header, play only after a tap; social data is never persisted.
- **Wording is guarded by tests** (keep them green; extend them for new strings): `HonestyTextsTest`, `KurinjiTextsTest`,
  `SocialTextsTest`, `ModerationV2Test`, `AiModerationTest`, `CommunityTextsTest`, `ContrastTest`,
  `EmergencyTreatmentTest`, `ResourceEncodingTest`.
- **Permissions today:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_NETWORK_STATE`, `SEND_SMS` (asked only on opt-in; Play
  "physical safety" declaration needed for release), `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` (asked on the
  Record tap during an ACTIVE SOS), `USE_BIOMETRIC`. Photo/video via the Photo Picker and camera-app intents — **never
  declare `CAMERA`**. Telephony/microphone/camera features not required. `dataExtractionRules` exclude all data.
- **Tests:** JUnit4 + kotlinx-coroutines-test, `MainDispatcherRule`, fakes in `src/test/.../testing/`. Background holders
  need `runCurrent()`/`advanceTimeBy()`, NOT `advanceUntilIdle()` (it ignores `backgroundScope`); real-recorder tests use
  `sosRecorderForTest` + `settle()`.

---

## 6. Backend architecture (`com.safesphere.backend`) — detail in `.claude/rules/backend.md`

- **Stack:** Java 17, Spring Boot **3.5.16** (OSS support ended 30 Jun 2026 — upgrading to 4.x is a separate, planned step),
  Spring Web, Data JPA, Security (OAuth2 resource server for JWT), Bean Validation, Flyway, MySQL 8.
- **Packages:** `auth` (+ `auth/phone`), `user`, `account`, `safetycontact`, `emergency`, `emergencycontext`,
  `escalation`, `journey`, `evidence`, `community`, `moderation` (+ `moderation.assessment`), `intelligence`,
  `evaluation`, `social`, `security`, `config`, `common` (each `controller/service/repository/entity/dto`).
- **Conventions:**
  - Thin controllers; rules in services; DTOs are Java records; entities are never serialized.
  - Caller identity **only** from the JWT: `CurrentUser.id(jwt)` (`sub` claim). No `userId` in any request DTO.
  - Repositories returning owned data always filter by user (`findByIdAndUserId`, …). Another user's record → **404** (same as missing).
  - Throw `ApiException` subclasses (`InvalidRequestException` 400, `UnauthorizedException`/`InvalidCredentialsException`
    401, `ResourceNotFoundException` 404, `ConflictException` 409); `GlobalExceptionHandler` renders one JSON shape:
    `timestamp, status, error, message, path, fieldErrors?, code?`. No stack traces or exception text to clients.
    Stable `code`s for refusals the app must act on (`AGE_NOT_CONFIRMED`, `PROFILE_NEEDED`, `PHONE_NOT_VERIFIED`).
  - Wrong passwords on account endpoints answer **409**, not 401 (a 401 means "signed out" on the phone).
  - `spring.jpa.hibernate.ddl-auto=none`, `open-in-view=false`. **Flyway owns the schema.**
  - Never catch a unique-key error inside a transaction and carry on: write the row in its own transaction or retry the
    whole attempt (Phase 14).
  - Time is UTC everywhere; inject `Clock`; no `LocalDateTime.now()` without `ZoneOffset.UTC`/`Clock` (`TimeConventionsTest`).
- **One visibility definition per area:** social `Visibility` (every read path, tested with author / connection /
  stranger / blocked), community `SignalVisibility`. **Isolation tests are invariants:** `SocialIsolationTest`,
  `IntelligenceIsolationTest`, `EvaluationIsolationTest`, `CoordinationIsolationTest`, `ModelPlacesTest`;
  `AccountEraserCoverageTest` (the eraser must cover every `REFERENCES users`). `SocialMediaCleanupJob` is the ONLY
  `@Scheduled` in `social`.
- **Auth/JWT:** HS256, secret from env (≥ 32 chars), issuer `https://safesphere.example`, `sub` = user id, 24 h, claims
  `sid`, `auth_time`, `cca`. Device sessions with rotating refresh secrets (V19); `security/ActiveAccountCheck` refuses
  missing/erased/disabled accounts, changed credentials and ended sessions (401 + RFC 6750 `error_description`). Public:
  `POST /api/auth/register|login|refresh|sign-out` and `/api/auth/phone/start|verify|resend|change`; everything else
  authenticated; `/api/moderation/**` MODERATOR/ADMIN, `/api/admin/**` ADMIN (roles read from the DB per request; ADMIN
  only via SQL). CSRF off (no cookies); CORS no origins by default; BCrypt (72-byte limit). Login errors generic and
  timing-equalised. New accounts need a verified phone (17B).
- **Tests:** `@SpringBootTest` + MockMvc, real JWT flow, H2 in MySQL mode with **Flyway disabled** and Hibernate
  `create-drop` (`src/test/resources/application.properties`). Helper `support/TestAccounts`. Two-user ownership tests
  are the norm. ⚠ H2 does not exercise MySQL-only DDL (generated columns, CHECKs): **verify every new migration on real
  MySQL** (backup, dry run of the CHECKs on a scratch copy, then apply). Real-MySQL check: `SAFESPHERE_MYSQL_TESTS=true`.

---

## 7. Database (MySQL `safesphere`, Flyway) — per-migration rules in `.claude/rules/database.md`

Conventions: never edit an applied migration; next file is **V29**; snake_case; `DATETIME(6)` stored in UTC; constraint
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

---

## 8. API — every endpoint in `docs/API.md` (per-phase index in its appendix)

- One error JSON shape (§6); a foreign or invisible record answers **404**; refused writes by a paused person **403**;
  limits **429** (our message shown).
- Every authenticated endpoint can answer 401 with `WWW-Authenticate: Bearer error="invalid_token",
  error_description="credentials_changed|account_deleted|account_unavailable|session_revoked"`.
- Social answers never carry coordinates (cells only) or exact times for others (bands only); no counts except U1 and
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
- Never claim emergency-service integration, SMS delivery, or that contacts were notified unless it happened and was
  confirmed. SMS "sent" ≠ delivered.
- Document platform limitations honestly (`docs/PLATFORM_CONSTRAINTS.md`).
- **Social features (Phase 13, built; design doc §4):** no coordinates in any social request or response (cells
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
  from before 17B keep their sessions and verify a number at their next sign-in.
- **Moderation (Phase 13D, built):** the reported person is never told who reported or that a report exists (no
  notice, no refused edit/delete, no "keep" in their standing); reports only collapse (visibly, at 3 independent
  counting reports); hold, removal and pauses are human decisions with a reason, audited and appealable to another
  moderator (never the decider or a reporting moderator); no shadow bans: every state is shown to its author.
- **E-mail ownership (V25, superseded by 17B):** the full rule is in `docs/EMAIL_VERIFICATION.md` (appendix). A future
  password reset must use its own token table and refuse unverified accounts.

---

## 10. Design system and UX (full spec: `docs/DESIGN_SYSTEM.md`; earlier identities in its History section)

- **"Ink & Lime" (E17-1):** ink black is the action colour on light pages (lime words on it), lime #ADFF2F the action
  colour in dark mode; lime is otherwise a FILL (a given mark, chosen tags, today's state) with ink on it, never text or
  an icon on a light page; deep leaf green #3A6100 for accents read on light pages. Red means only emergency.
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
- **Real-phone testing over USB:** `android/local.properties` → `api.base.url=http://127.0.0.1:8080/`, rebuild, then
  `adb -s <serial> reverse tcp:8080 tcp:8080` (lost on every reconnect). Remove the line to go back to the emulator
  default `10.0.2.2`. Install for `--user 0` on Samsung (Secure Folder user blocks some adb commands). Phone clocks may
  be off (the A23 was 74.6 s behind): capture times are phone time, `received_at`/`sealed_at` server time — compare
  them only after measuring the offset.
- **Time = UTC, always** (`docs/TIMESTAMPS.md`): `DATETIME(6)` holds UTC wall clock; `main()` calls `UtcTimeZone.install()`;
  JDBC URL keeps `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true`; `TimestampStorageVerifier` refuses to start on
  a shifted value; entities truncate to micros; no `LocalDateTime.now()` without `ZoneOffset.UTC`/`Clock`
  (`TimeConventionsTest`); surefire runs in Asia/Kolkata on purpose. Real-MySQL check: `SAFESPHERE_MYSQL_TESTS=true`.
  Rows written before the fix were corrected on 2026-10-20 (`backend/sql/fix_utc_offset_ist.sql`, one-time, verified; never rerun).
- **Encoding:** Windows PowerShell 5.1 `Get-Content`/`Set-Content` read BOM-less UTF-8 as Windows-1252 and add a BOM —
  never round-trip source files through them (it turned "…" into "â€¦" in `strings.xml`). `ResourceEncodingTest` guards res/.
- **Live-check scripts (PowerShell 5.1):** `$home` is read-only (assigning it aborts the script); a one-element array
  returned from a function unrolls to its element (`return , @(...)`); wrap the scenario in try/finally so the
  clean-up (withdrawals, role reset) always runs. Method and results: `docs/PHASE9.md` §13. Save a script containing
  Tamil text with a UTF-8 **BOM** (5.1 reads BOM-less UTF-8 as Windows-1252); `R` is an alias of Invoke-History and
  `Type` of Get-Content (don't name helpers so); native-exe arguments lose embedded double quotes.
- **Python (13E):** none on PATH on this PC; how to run the service and its tools: `.claude/rules/moderation-service.md`.
- **Emulator:** starting it with `-no-snapshot-save` can come back from an older snapshot with an older APK installed;
  check `dumpsys package com.safesphere.app | lastUpdateTime` and reinstall before a walk-through.

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
| Another sign-in ends an ACTIVE SOS only with the password; no automatic "resolved" SMS from another phone | Nobody can silently end someone's SOS; only the person on a phone can say they are OK |
| Evidence encrypted on the phone (per-item key); server stores ciphertext only; deletion needs the password and is refused during / 24 h after an emergency | A breach reveals no evidence; coerced deletion is blocked |
| Password change and account deletion refused during an ACTIVE SOS/walk; deletion has a 7-day grace | Revocation must not cut a running safety session; coerced deletion is recoverable |
| Community = place signals (cells only, no authors, no text, bands not counts); trust private, never shown | Safety intelligence, not engagement; no accusations of people |
| Content can be hidden automatically (reversible); people are restricted only by a human, with a reason, audited, appealable to another moderator; no shadow bans | Automatic punishment fails unsafely; moderator abuse is auditable |
| AI produces evidence → a deterministic policy decides → only humans restrict (D2); an outage publishes; self-harm never stored | An AI mistake must not silence anyone; infrastructure failure must not silence people |
| Safety intelligence = deterministic rules, no score, "unknown" is never "safe"; no text claims a place or person is safe | Explainable, attack-resistant, honest |
| Social: no followers, mutual connections only, no DMs, no presence, no counts except U1; cells and time bands only; private places stay on the phone | Following someone's real-world posts is monitoring; public posts must not reveal routines |
| Every list of others' posts is ordered by `visible_from`, never `created_at` | The order must not reveal the time the delay hides |
| Phone OTP for new accounts (provider-neutral `OtpSender`); e-mail + password stays the sign-in | Sign-ups were stuck without a mail server; codes and limits stay in tested code |
| Media: metadata stripped on the phone and again on the server; videos tap to play, never stored on the phone | Location privacy; Q3 (nothing about other people on the phone) |
| Visible brand IRL; package, applicationId, signing and code names stay SafeSphere | A rebrand must update the installed app in place |
| Time = UTC everywhere (`docs/TIMESTAMPS.md`) | Shifted timestamps were found and fixed once; `TimestampStorageVerifier` refuses to start on a shift |
