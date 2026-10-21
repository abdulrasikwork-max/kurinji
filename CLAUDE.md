# CLAUDE.md — SafeSphere project context

Permanent context for Claude Code. **The repository is the source of truth for code**; this file records product
intent, decisions, conventions and rules. If code and this file disagree, inspect the code and flag the conflict to
the user instead of guessing. Detailed history is in `docs/` — read only what the current task needs.

> The user's local repository may contain fixes made during Phase 3 live verification that are not in
> `SafeSphere-Phase3.zip`. Always inspect the local repo, never the zip.

---

## 1. Product

**SafeSphere — a safety, community intelligence, and empowerment platform designed with women at the center.**
Philosophy: *"Safe enough to live. Free enough to grow."*

- **SAFE** — *"You deserve to feel safe."* Emergency and personal safety, Safety Circle, emergency response,
  Safe Journey / Safe Walk, location and emergency intelligence, community safety reports, safety map, evidence
  protection, safety intelligence, AI-assisted safety.
- **RISE** — *"You deserve the freedom to grow."* Redesigned in Phase 10 as **"Proof"** (`docs/PHASE10_DESIGN.md`): a
  private, phone-only self-efficacy cycle (Doubt → Prepare → Try → Proof → Next); self-doubts are tested, safety concerns
  are respected and never "disproven"; support is never ranked; gender and stereotypes are never named; collective proof
  only as anonymous thresholded counts. Not built yet (10B). The original "Small Wins / Stories" community idea was
  rejected (no audience, no shared text, no contact).
  **Not** LinkedIn-style networking. **Not** an Instagram-style popularity/influencer system (no likes, followers, feeds).
- **Identity (Phase 10A):** *"Safety that expands your world."* **Nobody can use SafeSphere to monitor another person**
  (no family/partner/guardian mode, no location on request, no remote view): a permanent product decision.
- SAFE community features exist to produce **safety intelligence** (reports, locations, confirmations, verification), not engagement.
- UI philosophy: **"Calm when everything is safe. Powerful when something goes wrong."**

The vision has evolved; the project is **not** being restarted. Phases 1–3 are the foundation.
Final-year B.Tech IT team project: the student must be able to explain every important decision in a viva.

---

## 2. Status and roadmap

| Phase | Scope | Status |
|---|---|---|
| 0 | Architecture & planning | Done |
| 1 | Android foundation, branded design system | Done |
| 2 | Spring Boot + MySQL + JWT auth, Android session | Done, live verified |
| 3 | Safety Circle + manual SOS (countdown, events, history, recovery) | Done, **live verified** |
| **4** | **Real emergency response + Safe Walk** (sub-phases below) | **4A–4D implemented, compiled, tested** (manual verification **not verified**: 4A, 4B pending; 4C partial, `docs/PHASE4C.md` §8.1; 4D skipped by the user 2026-10-20, `docs/PHASE4D.md` §8). Phase 5 is done; see the next rows |
| 5 | Emergency Evidence Vault | **Implementation-complete 2026-10-20**: implemented, compiled, tested, emulator-verified, incl. **hardening** (V10, `docs/PHASE5.md` §10) and **restore on a new phone** (V11, recovery passphrase, §11). **Samsung Galaxy A23 partially verified** (§11.5.1: PASS tests 1–5 and 6b; PARTIAL 6; 7 step 1 only). **All other real-phone checks DEFERRED / not yet verified** by the user's decision (list in §11.5.1, incl. recovery passphrase 16–22 on a real phone) |
| **6** | **Community Safety Intelligence** ("Place Signals") | **Implemented, compiled, tested 2026-10-20** (V12 on MySQL, live API smoke test, API 37 emulator run): `docs/PHASE6.md`. **Real phone: not verified** |
| **7** | **Verification, Trust + Moderation** | **Implemented, compiled, tested 2026-10-20** (backend 261 / Android 403 tests, V13 on MySQL, live API 58/58), **emulator-verified (API 37)** incl. moderator screens: `docs/PHASE7.md` §10. TalkBack/dark mode **not verified**; **Galaxy A23 deferred** |
| **8** | **Safety Intelligence Engine** (area picture, pattern memory, "Check the way"; full route planner NOT built) | **Implemented, compiled, tested 2026-10-21** (backend 316 / Android 431 tests, V14 on MySQL, live API 22/22, **emulator-verified (API 37)**): `docs/PHASE8.md`, design `docs/PHASE8_DESIGN.md`. TalkBack **not verified**; real phone **not verified**; real pattern episode on MySQL **not verified** |
| **9** | **AI/ML** ("evidence before AI", `docs/PHASE9_DESIGN.md`) | **In progress.** 9C.0 (Phase 7 COORDINATION reopen fix) implemented, tested, **live-verified on MySQL**; **9A attack simulation implemented, compiled, tested 2026-10-21** (test code only); **9C coordination check implemented, compiled, tested 2026-10-21** (coord-1 in **SHADOW** by default, no withdrawal lock for COORDINATION cases, moderator pattern texts); **9C.1 S15 Option B, 9C.2 group members + whole-group pause, 9C.3 two guard fixes implemented, compiled, tested 2026-10-21** (backend 388 / Android 450, lint 0 errors / 87 warnings; **live ACTIVE check on MySQL 25/25**; **emulator (API 37) moderator screens PASS**; TalkBack speech, dark-mode colours and the A23 **not verified**): `docs/PHASE9.md` §9–§16. **9C complete: accepted by the user on 2026-10-21.** **9D implemented, compiled, tested 2026-10-21, awaiting the user's review** (`docs/PHASE9.md` §18–§26): neighbour join (active), havens-alone cap = active rules **rules-1.1**, rules-2 in **SHADOW only** (not promoted: no real-world evidence); backend 423 / Android 452, lint 0 errors / 87 warnings, **live MySQL 19/19**, **emulator (API 37) PASS**; TalkBack, the A23 and real-world rates **not verified**. **9B implemented, compiled, tested 2026-10-21; complete: accepted by the user on 2026-10-21, frozen** (`docs/PHASE9.md` §27–§34): V15 weekly system-wide outcome counters (after commit, best effort) + `moderation_cases.coordination_rule`, weekly snapshot, ADMIN-only `GET /api/admin/evaluation`; backend 453 / Android 454, lint 0 errors / 87 warnings, **V15 dry run 19/19 + applied on MySQL, live MySQL 21/21**; nothing user-visible; real-world rates **not verified** (no deployment). **No learned model** (gates G1–G3 not met) |
| **10** | **Foundation (10A) + RISE "Proof" (10B–10D)** (`docs/PHASE10_DESIGN.md`) | **10A implemented, compiled, tested, verified 2026-10-22** (`docs/PHASE10.md`): honesty/identity/language pass, V16 + `ActiveAccountCheck` + password change with revocation, coercion-aware account deletion; backend 466 / Android 480 tests, lint 0 errors / 85 warnings, **V16 dry run 13/13 + applied, live MySQL 23/23, emulator (API 37) PASS**; TalkBack, onboarding pages on a device and the real phone **not verified**. 10B–10D: planned, not started |
| 12 | Verified Nearby Assistance | Not started (11 not yet defined) |

**Phase 4 sub-phases — one at a time, each gated by the user's build + test:**
- **4A** — Location + permissions, notifications foundation, emergency location sharing, emergency/state recovery, battery + network readers.
- **4B** — Safe Walk, check-in timer, "I'm Safe", journey state, escalation levels 0–3.
- **4C** — Route deviation, inactivity/unexpected stop, Safety Bubble, battery policy (pure, heavily tested logic).
- **4D** — Offline SMS fallback, Emergency Context Packet, escalation communication (levels 2 and 4).

**4A is in the repository** (V4, location API, `core/location|device|notifications|tracking`, emergency location
sharing, offline recovery). Automated tests, V4 on real MySQL and an API 37 emulator run passed on 2026-10-19; what
was and was not verified is in `docs/PHASE4A.md` §4.
**4B is in the repository** (V5, V6, `/api/journeys`, `feature/safewalk`, `CheckInPolicy`, `SafeWalkSession`, Home
active-session banner). Verified on 2026-10-19 (tests, MySQL, API 37 emulator): `docs/PHASE4B.md` §5.
**4C is in the repository** (V7, destination coordinates + escalation reasons, `MovementPolicy`, `BatteryPolicy`,
`GeocoderDestinationResolver`, Safe Walk movement checks UI). Verified on 2026-10-19 (tests, V7 on MySQL, API smoke test,
API 37 emulator route-deviation run): `docs/PHASE4C.md` §6.
**4D is in the repository** (V8 `circle_alerts`, `/circle-alerts`, `/dial-prompts`, `/context`, `core/sms`,
`feature/circlealert`, Emergency details screen). Verified on 2026-10-20 (tests, V8 on MySQL, API smoke test, emulator SMS run):
`docs/PHASE4D.md` §6.

Do not implement 4B/4C/4D during 4A unless a small dependency is unavoidable (say so explicitly).
**6 is in the repository** (V12 `place_signals` + `signal_contributions`, `/api/community/*`, `feature/community`,
`core/location/GeoHash`, Safety Radar). Threat model, decisions and verification: `docs/PHASE6.md`.

**7 is in the repository** (V13, `TrustPolicy`, `Independence`, `FlagPolicy`, `TrustService`, `moderation`
package, `AuthThrottle`; Android confidence/flags/pause banner and `feature/moderation`). Implemented autonomously under
the user's briefs of 2026-10-20 (full autonomy). Real-phone checks deferred (`docs/HANDOFF.md` §5).

**8 is in the repository** (V14, `intelligence` package: `AreaIntelligencePolicy` rules-1, `PatternPolicy`/`PatternService`,
`RegionIntelligenceService`, `RegionQueryLimiter`; `/area` `picture`, `/api/intelligence/region`; Android
`feature/intelligence`, Area picture card, radar area cells, Safe Walk "Check the way"). Implemented autonomously under
the user's brief of 2026-10-20. Results and deviations from the design: `docs/PHASE8.md`.

Never implement early: RISE, Verified Nearby Assistance, advanced route intelligence (a real route planner needs
an on-device routing graph + a new privacy review, `docs/PHASE8_DESIGN.md` §16.4). AI/ML only as `docs/PHASE9_DESIGN.md`
plans it: rules decide everything users see; no learned model without gates G1–G3 and the user's decision.
Phase 9A attack scenarios live in `backend/src/test/.../evaluation/simulation` (real API, virtual clock, SIMULATED
reports in `backend/target/attack-simulation/`); since 9D they measure only PRODUCTION rules (`CandidateRules` was
removed; 9D scenarios in `NeighbourJoinSimulationTest`). Promoting rules-2 (`app.intelligence.rules-2-mode=ACTIVE`) is
the user's decision, never a default. Phase 9B (`evaluation` package, V15) only COUNTS: nothing that decides what
people see may read a counter (`EvaluationIsolationTest`). Next: see `docs/HANDOFF.md` §6.

**10A is in the repository** (V16, `account` package, `security/ActiveAccountCheck`, Android `feature/account`,
`core/network/SessionEnder`, rebuilt Settings/Profile/Safety Center/Home/onboarding). Deviations from the design
(D1–D12), the SAFE audit (A1–A16) and the deferred "session continuity" step (token expiry during an SOS, a second
session ending an SOS): `docs/PHASE10.md`. Phase 9 stays frozen. 10B (RISE "Proof", Android only) only when briefed.

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
├── docs/      API.md, PHASE2.md, PHASE3.md, PHASE4A.md, PHASE4B.md, PHASE4C.md, PHASE4D.md, PHASE5.md, PHASE6.md, PHASE7.md, PHASE8_DESIGN.md, PHASE8.md, PHASE9_DESIGN.md, PHASE9.md (9A, 9B, 9C, 9D results), PHASE9C_CONTINUATION.md (closed), PHASE10_DESIGN.md, PHASE10.md (10A results), TIMESTAMPS.md, HANDOFF.md, DESIGN_SYSTEM.md, PLATFORM_CONSTRAINTS.md, PHASE1_README.md
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

---

## 5. Android architecture (`com.safesphere.app`)

- **Stack:** Kotlin 2.0.21, AGP 8.7.3, XML Views, Fragments, ViewBinding, single Activity (`MainActivity`), one Navigation
  graph, MVVM, repositories, Coroutines/Flow, DataStore, Retrofit 2.11 + OkHttp 4.12 + Gson, Material 3.
  minSdk 26, compile/target 35. **No Compose. No Hilt** unless explicitly approved.
- **Packages:** feature-first `feature/<name>/{domain,data,ui}`; shared code in `core/`, `navigation/`.
  Features: splash, onboarding, auth, home, safety, community, profile, settings, safetycircle, emergency, safewalk,
  circlealert, evidence, moderation (Phase 7), intelligence (Phase 8), account (Phase 10A).
  (`feature/map` was removed in Phase 10A; it was an empty placeholder. No screen shows "Coming soon" any more.)
- **DI:** manual `AppContainer` (composition root) via `Fragment.appContainer`; each ViewModel has `factory(container)`.
- **Networking:** exactly **one** Retrofit instance; all APIs are created from it in `AppContainer`. `AuthInterceptor` adds the
  Bearer token to everything except `/api/auth/*`. **No HTTP logging interceptor** (bodies contain passwords).
- **Errors:** Phase 3+ repositories use `core/network/ApiCallHandler` → `ApiResult<T>` (`Success` / `Failure(ApiError, message?)`).
  A 401 goes through `core/network/SessionEnder` (Phase 10A): the session is cleared only if the rejected token is still
  the saved one (`SessionStorage.clearIfToken`, atomic in DataStore; a late 401 for an old token never signs out a newer
  session), the old token's 401 is ignored while a password change swaps tokens (`swapping`), and the reason from
  `WWW-Authenticate` (`credentials_changed` / `account_deleted` / `account_unavailable`) is published (`ended`,
  `lastReason`); screens then call `showSessionExpiredDialog()` (words follow the reason) (→ Login). Only our own 409
  messages reach users; other errors use app strings (`ApiResult.Failure.toMessage()`). Auth keeps its own
  `RemoteAuthRepository` mapping (its `refreshProfile` 401 also uses `SessionEnder`).
- **Session:** `DataStoreSessionStorage` (token + id/name/email + a pending deletion's dates; never the password). Splash
  routes Onboarding / Login / Home. Token is **not encrypted** yet (known limitation). `allowBackup=false`.
- **Account (Phase 10A, `feature/account`, `docs/PHASE10.md`):** `AccountFragment` (FLAG_SECURE): change password
  (`AccountPolicy` checks 8+ chars, ≤ 72 bytes, confirm, different; `RemoteAccountRepository` saves the NEW token inside
  `SessionEnder.swapping`), request / cancel deletion (password dialogs). `AccountPolicy.deletionNotice` drives the amber
  banner on Account and Home (opens Account). `PendingDeletionStore` (DataStore, account id + date only, survives
  sign-out). On a 401 `account_deleted`, `AppContainer` runs `AccountDataWiper` (= `EvidenceVault.forgetAccount` +
  `RecoveryKeyStore.remove`; a sign-out clears the rest anyway); after the date with no session, Login offers "Remove its
  data from this phone". `HonestyTextsTest` guards onboarding/settings honesty, identity lines, "no avoid", summary order.
- **UI helpers:** `ViewBindingFragment`, `collectWhenStarted`, `Motion.revealInSequence`; components in `core/ui/components`
  (`StatusBadgeView` + `BadgeStatus`, `NoticeView`, `SectionHeaderView`, `EmptyStateView`, `SafetyStatusCardView`, `FeatureListAdapter`).
- **Emergency (Phase 3):** `EmergencyUiState` = Loading → Idle → Countdown(5..1) → Starting → Active → Ended(RESOLVED/CANCELLED),
  plus StartFailed. Countdown is **client-side only** (cancel records nothing). Recovery: fetch history, show any ACTIVE event.
  The active screen lists enabled Safety Circle contacts **for display only — nobody is notified** (Phase 3; since 4D see
  "Safety Circle communication" below). Home SOS tile badge: "5 s to cancel", under the subtitle (not beside the title: it
  squeezed "SOS" off 320 dp screens); its description says SafeSphere texts the
  Safety Circle only with automatic SMS switched on (was "No alerts yet" until 2026-10-21; `HomeTextsTest` guards it).
- **Emergency location (4A):** `EmergencyLocationSharing` (application scope, single-threaded main) is the one holder read by
  the SOS screen (`state`) and `SafetyTrackingService` (`notice`; null ⇒ service stops). Queue in memory only (newest 500),
  batches ≤ 50, backoff 5→60 s. `ActiveEmergencyStore` keeps `{userId, emergencyId, startedAt}` for offline recovery
  (cleared on end/logout). Location asked once automatically when the emergency is ACTIVE, then via the card.
- **Safe Walk (4B):** `SafeWalkSession` (application scope) is the one holder for the screen, Home, the notification
  "I'm OK" action (`CheckInActionReceiver`, not exported) and the service. `CheckInPolicy` is pure: ON_TRACK → DUE_SOON
  (1 min before) → MISSED = L1 → NO_RESPONSE = L2 (+2 min); never L3 by itself. Local deadline = phoneNow + (due − serverTime).
  Ordered offline command queue; `ActiveJourneyStore` keeps ids/interval/start/local deadline/pending end (no destination,
  no location). The ONE `SafetyTrackingService` reads `CompositeTrackingNoticeSource` (emergency > walk); screens start it
  via `TrackingServiceStarter`. `EmergencyViewModel.onEmergencyStarted` ends the local walk.
- **Movement checks (4C):** `MovementPolicy` is pure (`onFix(state, fix, destination)` → next state): Safety Bubble
  (corridor around start→destination, half-width 40 % of length, 250 m–2 km, outside = distance − accuracy > width,
  sustained ≥ 3 fixes + 90 s), unexpected stop (10 min within 60–200 m), arrival hint (never auto-ends), fixes > 100 m
  accuracy ignored (GPS "weak" = paused). A NEW concern opens the same prompt early: `CheckInPolicy.evaluate(due, now,
  concern)` → L1 with reason, L2 after 2 min, never higher; "I'm OK" answers and suppresses the same condition until it
  clears. `BatteryPolicy` (pure): LOW ≤ 20 %, CRITICAL ≤ 10 %, +5 hysteresis, charging = NORMAL; low → location every
  60 s; critical → one notification + latest fix sent once; SOS location is never throttled. Destination looked up via
  `DestinationResolver` (platform Geocoder) at start; the phone stores only the switch + open concern (kind, time).
- **Safety Circle communication (4D):** `CircleAlertCoordinator` (application scope, the one holder) watches
  `SafeWalkSession.state` and gets SOS active/ended hooks from `EmergencyViewModel`; `CircleAlertPolicy` (pure) decides:
  walk L2 → "did not answer" (≤ 3 per walk) and an all-clear after an answer; SOS → one message (waits ≤ 30 s for a fix,
  one location follow-up ≤ 10 min), resolved/false-alarm at the end; nothing for stale (> 30 min) emergencies.
  Automatic ONLY if opted in + `SEND_SMS` + SMS supported + enabled contacts; otherwise the SMS app (`ACTION_SENDTO`).
  `CircleMessages` (pure) = Safety Circle allow-list text (name, time, what happened, map link). `AndroidSmsSender`
  (multipart, sent/delivery PendingIntents → non-exported `SmsResultReceiver`). Statuses SENDING/SENT/DELIVERED/FAILED/
  COMPOSER_OPENED; SENDING never reported. `DataStoreCircleAlertStore` keeps opt-in, enabled contacts (name+number, for
  offline SMS), alert memory, recent deliveries, pending server reports (cleared on logout). "Call 112" = `ACTION_DIAL` +
  `POST /dial-prompts` (L4). Emergency details screen reads the context packet (OWNER).
- **Evidence Vault (Phase 5, `docs/PHASE5.md`):** `EvidenceVault` (application scope, the one holder) seals each
  capture at once: per-item 256-bit key, `core/crypto/EvidenceCipher` = **SSEV1** (AES-256-GCM, 64 KiB segments, STREAM
  nonce; byte-identical to backend `SsevFormat`, shared test vector), both SHA-256 hashes, key wrapped by
  `AndroidKeystoreKeyWrapper` (not auth-bound) and, when the server has a vault key, by `EscrowKey` (RSA-OAEP-SHA256;
  optional pin `evidence.vault.key.id` in local.properties). Files: `files/evidence` (ciphertext + `index.json`),
  `files/evidence-capture` (plaintext staging + note; `recover()` seals after a crash), `cache/evidence-view` (playback
  only). Upload: declare → resumable pieces → server seal; refusals keep the item. `EvidenceRecorder`: audio only on tap
  during ACTIVE SOS, 30 s AAC/ADTS parts, stops at 60 min / low storage / emergency end / logout / mic loss.
  Photo/video via camera-app intents + FileProvider (no CAMERA permission — never declare it). Vault screen
  FLAG_SECURE; delete everywhere = server password check, refused while ACTIVE. Items belong to the capturing account.
  Tests: background holders need `runCurrent()`/`advanceTimeBy()`, NOT `advanceUntilIdle()` (it ignores backgroundScope).
  Hardening (§10): every vault action checks the item belongs to the signed-in person; `VaultLock` (app scope) +
  `ScreenLockGate` (platform BiometricPrompt / Keyguard, `USE_BIOMETRIC`) gate the vault SCREEN, re-lock on leaving or
  account change (app gate, not auth-bound keys — deliberate); index written fsync'd with `index.json.bak` fallback;
  `PlaintextWipe` for staging/view files; `CustodyAnchoring` (pure) = phone-held `(sequenceNo, entryHash)` per
  emergency in `custody-anchors.json`; `EvidenceUploadJobService` (JobScheduler, job id 5001, network constraint, not
  persisted) resumes uploads after process death; "Remove from this phone" first asks the server to re-check
  (`/verify`); refused uploads can be retried. `ApiError.TOO_MANY_ATTEMPTS` = 429 (our message shown).
  Restore on a new phone (§11): `RecoveryKeyCrypto` (own PBKDF2-HMAC-SHA256 600k over NFC UTF-8; GCM wraps with AADs
  `SSRK1|rkVersion`, `SSRW1|clientRef|sha`), `RecoveryKeyStore` (RK per account, Keystore-sealed,
  `recovery-keys.json`; a NEW RK is stored only after the server accepted it), `EvidenceRecovery` (set up / restore /
  change / forgot), `PassphrasePolicy` (≥ 15 chars, not name/email/account password, not in `common_passwords_10k.txt`)
  + `PassphraseGenerator` (5 EFF words, `eff_large_wordlist.txt`); vault section + `EvidenceRecoveryViewModel`
  (behind `VaultLock`); `EvidenceVault` adds the copy at declaration (re-declares without it on a "recovery" 409),
  back-fills when idle, `importRestored` (content on Bring back), `syncDeletions` on vault refresh.
- **Community (Phase 6, `docs/PHASE6.md`):** place signals, not posts. The phone turns one fresh fix into a geohash
  (`core/location/GeoHash`, shared vectors with backend `GeoCell`): 6 chars (~1 km) to look at the area, 7 chars
  (~150 m) to report/answer; **no coordinate is ever sent**. `CommunityPolicy` (pure): reporting needs PRECISE +
  fix ≤ 100 m (viewing works with approximate), outbox ≤ 10 / ≤ 24 h, placement (distance, bearing), brief, 50/100 m
  rounding. `CommunityViewModel` (location only while the screen is open, 20 s timeout), `DataStoreCommunityOutbox`
  (cell + category only, cleared on logout), `SafetyRadarView` (no map tiles; shape + colour; halo = ~1 km precision),
  Home "Nearby Safety" opens Community. No new permission; location asked from the Community screen only.
  Phase 9D: the report confirmation (`report_confirm_message`) says a report may be added to the same report in the
  block right next to yours and then shows as "you saw this too" (`CommunityTextsTest`); no other phone change.
- **Trust UI (Phase 7, `docs/PHASE7.md`):** `AreaSignal.confidence/reasons/youFlagged` (unknown → LOW, never more
  certainty than told); card shows "Not yet confirmed / Confirmed by people nearby / Strongly confirmed", "How sure is
  this?" (reasons) and "Report a problem" (flag reason + confirmation, current 7-char cell only); `canFlag`/`canAnswer`
  exclude own and flagged signals; `CommunityStanding` from `/standing` drives a warning banner + "Ask for a review"
  and blocks writes; a 403 reloads it. `ApiError.FORBIDDEN` (403, our message shown); `AuthError.TOO_MANY_ATTEMPTS` (429).
  Trust tiers are NEVER shown in the app. "Withdraw" hidden for UNDER_REVIEW items.
- **Moderation (Phase 7, `feature/moderation`):** Profile shows "Moderation" only when `GET /api/moderation/access`
  says `canModerate` (hidden on any failure; re-asked on every Profile start). `ModerationPolicy` (pure) mirrors the
  server: reason codes per decision, no decision on a case you took part in / a closed case, no appeal decision on your
  own restriction. `ModerationViewModel` (queue; 403 ⇒ NotAllowed, data cleared) and `ModerationCaseViewModel` (only
  possible decisions sent; 409/403 reload). DTO unknowns fail closed (`youTookPart`/`youDecidedTheRestriction` default
  true); pseudonyms/cells redacted in `toString()`. Both screens FLAG_SECURE. Nothing stored on the phone. Closed cases
  and admin functions are API-only. The server is the authority for every check. Phase 9C: the case screen adds the
  coordination pattern as text lines (`ModerationTexts.coordinationLines`: reason, size/places bands, and ALWAYS the
  guidance "Backing each other is not proof…"); `CoordinationDto` drops unknown reasons; `ModerationTest` scans the
  `coordination_*` strings for accusatory words. 9C.1/9C.2: "Others in this group" (`CaseDetail.groupMembers`, read
  only when role and flag are both null), "Pause everyone in this group" (`ModerationPolicy.canRestrictGroup`: open
  coord-1 case only; `onRestrictGroup` sends RESTRICT + `wholeGroup`), "Keep it" on a COORDINATION case confirms with
  `coordination_confirm_dismiss` (Option B); each "Pause" button is labelled "Pause Person xxxxxx" for TalkBack.
- **Safety Intelligence (Phase 8, `docs/PHASE8.md`, `feature/intelligence`):** decision support only. The server sends
  CODES (rules-1; since 9D rules-1.1, which never sends NO_CONFIRMED_CONCERNS; the phone keeps its text and ignores
  `modelVersion`), the phone writes the words (`IntelligenceTexts`; a test forbids "safe/safest/dangerous/verified"
  claims). Two axes: concern (confirmed only) and information (how much is known); no outlook means "safe".
  `AreaPictureDto` fails CLOSED (unknown/inconsistent ⇒ no card); `RegionPackDto` fails SAFE (anything unknown ⇒ pack
  incomplete ⇒ no brief). Community: `AreaView(signals, picture)`, card above the radar (amber for care, teal info
  otherwise, never green/red), `SafetyRadarView.setAreaCells` (hatched = nothing known, dotted = little, amber outline =
  confirmed concerns). Night order from the phone's own clock (`IntelligencePolicy.isNight`). Safe Walk set-up:
  "Check the way" in its own `WayCheckViewModel` (one fix, destination via the shared `destinationResolver`,
  `CorridorBriefPolicy` pure: straight-line corridor = bubble half-width capped at 500 m, ≤ 4 regions, ≤ 20 km; only
  5-character region cells are sent). Nothing stored; `IntelligenceIsolationTest` keeps it out of SOS, tracking,
  Safe Walk session/policies, circle alerts and evidence.
- **Permissions today:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_NETWORK_STATE`, `SEND_SMS` (4D, asked only on opt-in;
  Play "physical safety" declaration needed for release), `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` (Phase 5, asked
  on the Record tap; the one service's types = location ∪ microphone), `USE_BIOMETRIC` (Phase 5 hardening, normal
  install-time permission for the vault's screen-lock prompt). Telephony/microphone/camera features not required.
  `dataExtractionRules` exclude all data.
- **Build gotcha (this Windows sandbox):** Gradle/Tomcat fail with "Unable to establish loopback connection"; run with
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Public` (backend: `-Dspring-boot.run.jvmArguments=...`).
- **Tests:** JUnit4 + kotlinx-coroutines-test, `MainDispatcherRule`, fakes in `src/test/.../testing/` (FakeAuthRepository,
  FakeSafetyCircleRepository, FakeEmergencyRepository, FakeAuthApi, FakeSessionStorage…). Countdown tested with virtual time.

---

## 6. Backend architecture (`com.safesphere.backend`)

- **Stack:** Java 17, Spring Boot **3.5.16** (OSS support ended 30 Jun 2026 — upgrading to 4.x is a separate, planned step),
  Spring Web, Data JPA, Security (OAuth2 resource server for JWT), Bean Validation, Flyway, MySQL 8.
- **Packages:** `auth`, `user`, `safetycontact`, `emergency`, `evidence` (Phase 5; + `tools/VaultKeyTool`, offline only),
  `intelligence` (Phase 8: pure `AreaIntelligencePolicy` + `PatternPolicy`, `AreaIntelligenceService`, `PatternService`,
  `RegionIntelligenceService`, in-memory `RegionQueryLimiter`, `IntelligenceController`; `config/IntelligenceProperties`;
  reads signals only through `CommunityService.visibleIn` = the ONE visibility definition; must not reference
  emergency/journey/escalation/safetycontact/circlealert/evidence/moderation — `IntelligenceIsolationTest`;
  Phase 9D: `AreaIntelligencePolicy.Model` RULES_1_1 (active, `modelVersion` "rules-1.1": havens alone count once) |
  RULES_2 (rule (b) needs ≥ 3 independent people across signals, `Evidence.people` from
  `Assessment.independentSupporters`, people linkage from `SignalVisibility.VisibleSet`); `AreaModelShadow` (from the
  job, at most hourly, read-only, logs counts only); `app.intelligence.rules-2-mode` OFF|SHADOW|ACTIVE, default SHADOW),
  `community` (Phase 6: `GeoCell`, pure `SignalPolicy`, `CommunityService`, in-memory `AreaQueryLimiter`,
  `CommunityPurgeJob` = a `@Scheduled` task, deletion only (since Phase 10A the other one is `AccountDeletionJob`); `config/CommunityProperties`, `SchedulingConfig`;
  Phase 7: pure `TrustPolicy`, `Independence`, `FlagPolicy`, `TrustService`, private `ContributorStanding`, `SignalFlag`;
  Phase 9C: `SignalVisibility` = the ONE visibility definition (`CommunityService.visibleIn` delegates), pure
  `CoordinationPolicy` = coord-1 R1/R2; Phase 9D: `CommunityService.joinLoneReports` (first step of `purge`: a lone,
  counting, never-confirmed concern REPORT moves as a CONFIRM onto an OLDER visible same-category signal in a block
  sharing a SIDE, `GeoCell.adjacency`; never havens/corners/flagged/under review/self) and `confirmsNextDoor`
  (re-send guard)),
  `moderation` (Phase 7: `ModerationService`, `CaseRefs`, `ModerationController`, `AdminController`,
  `config/ModerationProperties`; Phase 9C: `CoordinationCheck` (from the job, mode OFF|SHADOW|ACTIVE, default SHADOW,
  only write = open a COORDINATION case; `CoordinationIsolationTest`), `config/CoordinationProperties`,
  `blocksWithdrawal` (COORDINATION cases without flags do not lock withdrawal); Phase 9C.1–9C.3: S15 Option B in
  `TrustService.Snapshot.supportOn` (people counting at a DISMISSED COORDINATION case count separately on THAT signal;
  read via `ModerationCaseRepository.lastClosedBySignal`, nothing stored), `CoordinationPolicy.Group.members()` (≥ 2
  of the group's places) for `CaseDetail.groupMembers`, RESTRICT `wholeGroup` and the dismissed-group guard (any
  signal backed only by members); Phase 9B: `coordination_rule` stored on every new COORDINATION case),
  `evaluation` (Phase 9B: `OutcomeRecorder` = the only writer, typed enum-only methods, counts buffered per transaction
  and written AFTER COMMIT in their own transaction, best effort (failure = WARN with the exception class only), never
  reads; `WeeklySnapshot` from the job, first run of each ISO week, one transaction, reads `SignalVisibility`, also the
  104-week retention; pure `EvaluationReport` (rates, Wilson 95 %, minimums, gate G1); `EvaluationService` = the only
  reader, called by `ModerationService.evaluationReport` (ADMIN); `config/EvaluationProperties`; must not reference
  safety packages or moderation, and safety packages / intelligence must not reference it — `EvaluationIsolationTest`),
  `security/RoleAuthorization` +
  `AttemptLimiter`, `auth/service/AuthThrottle`
  (`config/AuthProperties`) (each `controller/service/repository/entity/dto`),
  `config` (SecurityConfig, JwtConfig, JwtProperties), `security` (JwtService, CurrentUser, 401/403 JSON handlers),
  `common/exception` + `common/response/ApiError`.
- **Conventions:**
  - Thin controllers; rules in services; DTOs are Java records; entities are never serialized.
  - Caller identity **only** from the JWT: `CurrentUser.id(jwt)` (`sub` claim). No `userId` in any request DTO.
  - Repositories returning owned data always filter by user (`findByIdAndUserId`, …). Another user's record → **404** (same as missing).
  - Throw `ApiException` subclasses: `InvalidRequestException` 400, `UnauthorizedException`/`InvalidCredentialsException` 401,
    `ResourceNotFoundException` 404, `ConflictException`/`EmailAlreadyUsedException` 409. `GlobalExceptionHandler` renders one
    JSON shape: `timestamp, status, error, message, path, fieldErrors?`. No stack traces or exception text to clients.
  - `spring.jpa.hibernate.ddl-auto=none`, `open-in-view=false`. **Flyway owns the schema.**
- **JWT:** HS256, secret from env, issuer `https://safesphere.example`, `sub` = user id, **24 h, no refresh tokens**;
  logout = client clears its session. **Phase 10A:** after a password change tokens carry `cca` (credentials stamp = µs of
  `users.credentials_changed_at`); `security/ActiveAccountCheck` (inside the decoder, after exp/iss) refuses a token whose
  account is missing/erased/disabled or whose stamp differs (exact equality) → 401 with an RFC 6750 `error_description`.
- **Account (Phase 10A, `account` package):** `AccountService` (password change refused during an ACTIVE SOS or Safe
  Walk; deletion request refused while ACTIVE / < 24 h after an emergency, 7-day grace; cancel with password; 5 wrong
  passwords / 15 min shared → 429, in memory; wrong password = 409), `AccountDeletionJob` (`@Scheduled`, 15 min) →
  `AccountEraser` (postpone if an emergency is recent; evidence files first; owned rows children-first + 
  `CommunityService.eraseContributor`; delete the row, or anonymise it if the audit log names it as decider);
  `AccountEraserCoverageTest` compares the eraser with every `REFERENCES users` in the migrations.
- **Security:** stateless; `POST /api/auth/register|login` public, everything else authenticated. CSRF off (no cookies).
  CORS: no origins by default. BCrypt passwords (72-byte limit enforced). Login errors generic and timing-equalised.
- **Tests:** `@SpringBootTest` + MockMvc, real JWT flow, H2 in MySQL mode with **Flyway disabled** and Hibernate `create-drop`
  (`src/test/resources/application.properties`). Helper `support/TestAccounts`. Two-user ownership tests are the norm.
  ⚠ H2 does not exercise MySQL-only DDL (generated columns, CHECKs): **verify every new migration on real MySQL.**

---

## 7. Database (MySQL `safesphere`, Flyway)

| Migration | Table | Key rules |
|---|---|---|
| V1 | `users` | unique email (case-insensitive collation, stored lower-case), `password_hash` (BCrypt), `enabled` |
| V2 | `safety_contacts` | FK user (cascade); unique `(user_id, phone_number)` with normalized phone; generated `primary_marker` + unique ⇒ max one primary per user; priority CHECK 1–5; max 10 contacts (service rule); disabled contact never primary |
| V3 | `emergency_events` | FK user (cascade); status ACTIVE/CANCELLED/RESOLVED; trigger MANUAL (SHAKE, VOICE, DURESS_PIN reserved and **rejected**); generated `active_marker` + unique ⇒ max one ACTIVE per user; `started_at`/`ended_at` |
| V4 | `emergency_locations` | FK emergency (cascade), **no user_id**; lat/lng `DECIMAL(9,6)` + range CHECKs; `accuracy_meters DECIMAL(7,1)`, `source` CHECK, `battery_percent` 0–100; `recorded_at`/`received_at`; **UNIQUE (emergency_id, recorded_at)** (idempotent re-send; also the read index) |

| V5 | `safe_journeys`, `journey_events` | FK user cascade; status ACTIVE/COMPLETED/CANCELLED/EMERGENCY; generated `active_marker` + unique ⇒ one ACTIVE walk per user; interval CHECK 2–60; escalation 0–3; deadline/ended CHECKs tied to status; last location only (all-or-nothing CHECK) |
| V6 | `escalation_events` + `emergency_events.journey_id` | level 0–4 (5 never stored), reason CHECK; journey/emergency FKs cascade ("at least one" enforced in code: MySQL forbids CHECKs on cascading-FK columns); `journey_id` on emergencies `ON DELETE SET NULL` |

| V7 | `safe_journeys.destination_latitude/longitude` + reasons | `DECIMAL(9,6)`, range CHECKs, both-or-none CHECK; reason CHECK replaced (+`ROUTE_DEVIATION`, `UNEXPECTED_STOP`); coordinates + last location deleted when a walk ends COMPLETED/CANCELLED (kept for EMERGENCY) |

| V8 | `circle_alerts` + reason `DIAL_PROMPT_OPENED` | emergency/journey FKs cascade (exactly one, enforced in code), `contact_id` FK `ON DELETE SET NULL`; kind/channel/status CHECKs; `UNIQUE client_ref` (UUID from the phone); no number, no text |

| V9 | `evidence_items`, `evidence_custody_events` | items: FK emergency cascade, **no user_id**; kind/status/size/lower-case-hex-hash/capture-order CHECKs; SEALED ⇒ sealed_at + all bytes; DELETED ⇔ deleted_at ⇔ no storage_key, no escrow key; `UNIQUE client_ref`, sequence_no NOT unique. Custody: per-user hash chain, `UNIQUE (user_id, sequence_no)`, action CHECK, append-only. Ciphertext lives in files (`app.evidence.storage-dir`), never in MySQL |

| V10 | `evidence_custody_events` | action CHECK replaced (+`VERIFIED`, `DELETE_REFUSED`); index `(user_id, action, occurred_at)` for the deletion lockout count |

| V11 | `evidence_recovery_envelopes`, `evidence_recovery_failures`, `evidence_items.recovery_rk_version/recovery_wrapped_key` | envelope: one per user, kdf CHECK `PBKDF2-SHA256`, iterations 600 000–10 000 000, salt 16–32 B, wrap = 60 B; item copy both-or-none + 60 B; shredded CHECK now also requires no recovery copy; failures cascade from users |

| V12 | `place_signals`, `signal_contributions` | signal: random `public_id`, category CHECK (10 place-only categories), `cell` CHAR(7) geohash (REGEXP CHECK), `area_cell` = LEFT(cell,6) CHECK, **UNIQUE (cell, category)** (reports merge); **no coordinates, no author, no time**. Contribution: FK signal + user cascade, kind REPORT/CONFIRM/NO_LONGER, **UNIQUE (signal_id, user_id)**, `counts_from` = server time + random delay (+ probation), the only time stored. Rows deleted at the end of their lifetime |

| V13 | `users.role`, `contributor_standing`, `signal_flags`, `moderation_cases`, `moderation_actions`, `moderation_appeals`; `place_signals.moderation/moderation_until`; `signal_contributions.credited` | role CHECK (ADMIN only via SQL); standing = counters only (no places/times), halved every 90 days; one flag per person per signal; generated `open_marker` + unique ⇒ one OPEN case per signal; REMOVED ⇔ moderation_until; audit append-only, actor FK without cascade; one appeal per decision; `version` on cases/appeals. Applied + constraint-checked on MySQL 8.0 |

| V14 | `area_patterns`, `place_signals.reached_confirmed` | one decayed value per (6-char area, INCIDENT/CONDITION), PK (area_cell, signal_group), **no FK, no user/signal id**, `DATE` columns (day precision, never returned), CHECKs cell/group/value 0–4/next ≥ decayed; fed only by finished, independently confirmed, unmoderated episodes (≤ 1/week), forgotten < 0.25, reset by any REMOVE. Applied + constraint-checked on MySQL 8.0 |

| V15 | `evaluation_counters`, `moderation_cases.coordination_rule` | counters: PK (week_start, metric, dimension, model_version), `occurrences` ≥ 0; **no user/cell/signal/case column**; metric closed list (binary collation), dimension `^(-\|[A-Z_]+([.][A-Z_]+)?)$` case-sensitive (a geohash cannot be stored), version `^(-\|[a-z]+-[0-9]+([.][0-9]+)?)$`, week_start a Monday; 104-week retention in code. `coordination_rule` LINKED_GROUP/SUPPORT_CORE/EXCLUSIVE_PAIR, COORDINATION cases only, binary. Dry run 19/19 rejected, applied on MySQL 8.0 |

| V16 | `users.credentials_changed_at`, `deletion_requested_at`, `deletion_due_at`, `erased_at` | CHECKs: deletion pair both-or-none, due > requested, erased ⇒ disabled, ⇒ no pending deletion, ⇒ role USER, ⇒ email `erased-%@erased.invalid`; index on `deletion_due_at`. Dry run 13/13, applied on MySQL 8.0 (2026-10-22) |

Conventions: never edit an applied migration; next file is **V17**; snake_case; `DATETIME(6)` stored in UTC; constraint
prefixes `uk_`, `fk_`, `ck_`, `idx_`; FKs `ON DELETE CASCADE` (no orphans); InnoDB, utf8mb4_unicode_ci.

---

## 8. API (details and examples in `docs/API.md`)

- `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/users/me`
- `GET|POST /api/safety-contacts`, `GET|PUT|DELETE /api/safety-contacts/{id}`
- `POST|GET /api/emergencies`, `GET /api/emergencies/{id}`, `PUT /api/emergencies/{id}/cancel`, `PUT /api/emergencies/{id}/resolve`
- `POST|GET /api/emergencies/{id}/locations`, `GET /api/emergencies/{id}/locations/latest` (4A; config
  `app.emergency.locations.max-per-emergency|upload-grace|clock-tolerance`, defaults 1000 / 15m / 5m)
- `POST|GET /api/journeys`, `GET /api/journeys/{id}`, `POST /api/journeys/{id}/check-ins`, `PUT …/complete|cancel`,
  `POST …/escalations` (1–2 only, no skipping, idempotent; 3 = start SOS; 4/5 rejected), `PUT …/location` (last known) (4B).
  `POST /api/emergencies` links the caller's ACTIVE walk (L3) and returns `journeyId`.
- 4C: `POST /api/journeys` optional `destinationLatitude/Longitude` (both or neither), response `destination`;
  `POST …/escalations` optional `reason` (L1: `CHECK_IN_MISSED` default | `ROUTE_DEVIATION` (needs destination) |
  `UNEXPECTED_STOP`, may precede the deadline; L2: `NO_RESPONSE` only).
- 4D: `POST /api/emergencies/{id}/circle-alerts`, `POST /api/journeys/{id}/circle-alerts` (1–20 reports, idempotent by
  `clientRef`, status only forward, ≤ 200 per target), `POST /api/emergencies/{id}/dial-prompts` (L4 once, ACTIVE only),
  `GET /api/emergencies/{id}/context?audience=OWNER|SAFETY_CIRCLE` (others 400). Journey L4 → 400.
- Phase 5: `GET /api/evidence/vault-key`, `POST|GET /api/emergencies/{id}/evidence`, `GET …/evidence/custody`,
  `GET /api/evidence/{id}`, `PUT /api/evidence/{id}/content?offset=N` (octet-stream ≤ 1 MiB), `POST …/seal`,
  `GET …/content`, `POST …/delete {password}`. Config `app.evidence.*` (`EVIDENCE_STORAGE_DIR`, `EVIDENCE_VAULT_PUBLIC_KEY`).
  Context OWNER gains `evidence` counts; SAFETY_CIRCLE none.
- Phase 5 hardening (V10): `POST /api/evidence/{id}/verify` (server re-hashes its stored copy; damaged → UPLOADING
  again). Delete: refused while ACTIVE and for `app.evidence.delete-protection` (24h) after the end; 5 wrong passwords
  in 15 min → 429 (`delete-max-failures`, `delete-failure-window`); wrong passwords logged `DELETE_REFUSED`.
  `VaultKeyTool generate` (passphrase-encrypted PEM) | `check` | `decrypt`.
- Phase 5 restore (V11, `docs/PHASE5.md` §11): `GET|PUT /api/evidence/recovery`, `POST /api/evidence/recovery/envelope
  {password}`, `GET /api/evidence/recovery/items?page=N`, `PUT /api/evidence/{id}/recovery-key`; declaration accepts
  `recoveryRkVersion` + `recoveryWrappedKey`; `EvidenceResponse.recoverable`. Wrong passwords counted (5/15 min → 429).
- Phase 6 (V12, `docs/PHASE6.md`): `POST /api/community/reports {category, cell(7)}` → 202 (same answer new / merged /
  re-sent), `GET /api/community/area?cell=(6)` (cell + 8 neighbours, ≤ 100, nearest first; precision AREA/BLOCK,
  support/freshness BANDS, `yourContribution`), `POST /api/community/signals/{id}/contributions {kind, near(7)}`,
  `GET /api/community/mine`, `DELETE /api/community/mine/{id}`. Config `app.community.*`: delay 10–30 min, probation
  24 h, 10 reports / 3 per area / 30 answers per 24 h, 120 look-ups + 15 distinct areas per hour, purge 15 min.
- Phase 7 (V13, `docs/PHASE7.md`): area signals add `confidence`, `reasons`, `youFlagged`; mine adds `UNDER_REVIEW`;
  `POST /api/community/signals/{id}/flags {reason, near(7)}` → 202 `{status:RECEIVED}` (10/day);
  `GET /api/community/standing` (own pause only, no score); `POST /api/community/appeals` → 202;
  `GET /api/moderation/access` (any JWT); MODERATOR/ADMIN (role from DB per request): `GET /api/moderation/cases?status=`
  (summaries carry `youTookPart`),
  `GET /api/moderation/cases/{id}`, `POST /api/moderation/cases/{id}/actions {action QUARANTINE|DISMISS|REMOVE|RESTRICT,
  reason, contributorRef?, days?}`, `GET /api/moderation/appeals`, `POST /api/moderation/appeals/{id}/decision
  {decision UPHELD|OVERTURNED}`; ADMIN: `PUT /api/admin/roles {email, role USER|MODERATOR}`, `GET /api/admin/moderation-log?page=`.
  Paused contributor → 403 on report/answer/flag (viewing and safety features unaffected). Sign-in 429 after 10 failures
  per account / 30 per address per 15 min; sign-up 429 after 10 per address per hour; community writes 10 per 10 min.
  Config `app.auth.*`, `app.moderation.*`, `app.community.writes-per-10-minutes` (tests lift the per-address limits).
- Phase 8 (V14, `docs/PHASE8.md`): `GET /api/community/area` adds `picture {modelVersion "rules-1", outlook, concern,
  information, reasons, concernCategories, conflicting, highestConcernCell (6-char or null), areas[9] {cell, outlook,
  information}}` (null if the rules fail; computed before the 100-signal cut); `GET /api/intelligence/region?cell=(5)` →
  `{region, modelVersion, complete, areas[] (only known cells; absent = NO_INFORMATION), signals[] (MODERATE/HIGH only,
  no ids, independent freshness)}`; 429 after 20 downloads / 6 distinct regions per 24 h (2 in probation), in memory.
  Config `app.intelligence.*` (pattern half-life 60d, cap 4, recurring 2.5, forget 0.25, min gap 7d, region budgets,
  `region-max-signals` 500).
- Phase 9C (`docs/PHASE9.md` §8): `GET /api/moderation/cases/{id}` adds `coordination: null | {reasons
  [LINKED_GROUP|SUPPORT_CORE|EXCLUSIVE_PAIR], groupSize TWO|THREE_TO_FIVE|SIX_OR_MORE, groupPlaces null|TWO_TO_FOUR|
  FIVE_TO_NINE|TEN_OR_MORE}` (bands only, recomputed per view). `DELETE /api/community/mine/{id}` is no longer 409 for a
  COORDINATION case without flags. Config `app.moderation.coordination.mode` (SHADOW), `.interval` (1h; tests 0s),
  `.max-cases-per-day` (5).
- Phase 9C.1–9C.2 (`docs/PHASE9.md` §9–§10): case detail adds `groupMembers [{ref, role null, flag null, tier,
  accountAge, counting, linkGroup}]`; `POST …/actions` RESTRICT accepts `"wholeGroup": true` instead of `contributorRef`
  (400 with both or on another action; 409 no group / everyone paused); a moderator in the group's core gets 403. DISMISS
  of a COORDINATION case = Option B (that signal counts the people seen then separately).
- Phase 9D (`docs/PHASE9.md` §18–§20): **no new endpoint or field.** `picture.modelVersion` / region pack `modelVersion`
  = `"rules-1.1"` (was `"rules-1"`; `"rules-2"` only if `app.intelligence.rules-2-mode=ACTIVE`); the outlook
  `NO_CONFIRMED_CONCERNS` is no longer sent.
- Phase 9B (V15, `docs/PHASE9.md` §27–§34): ADMIN only `GET /api/admin/evaluation?weeks=1..104` (default 12) →
  `{notice, fromWeek, toWeek, currentWeekIncomplete, countersEnabled, counters[{weekStart, metric, dimension,
  modelVersion, value}], rates[{name, dimension, numerator, denominator, minimum, status OK|NOT_ENOUGH_DATA|INCONSISTENT,
  value, low, high, lowerBound}], g1{status NOT_MET|COUNTS_MET_PRIVACY_REVIEW_REQUIRED, decidedCasesPerFourWeeks,
  activeModerators, note}}`; 400 / 401 / 403. No client endpoint or field changed. Config `app.evaluation.enabled`
  (true; false deletes the rows at the next clean-up), `app.evaluation.retention-weeks` (104, max 104). `POST /api/community/reports` answers as before; a lone report may later
  appear in `GET /api/community/mine` as `kind: CONFIRM` on the neighbouring signal's id. Config
  `app.intelligence.rules-2-mode` (SHADOW) and `.rules-2-shadow-interval` (1h; tests 0s).
- Phase 10A (V16, `docs/API.md` "Phase 10A endpoints"): `PUT /api/users/me/password {currentPassword, newPassword}` → 200
  fresh token (others revoked; 409 during ACTIVE SOS/walk; 409 wrong password; 429), `POST /api/users/me/deletion
  {password}` → 202 `{deletionRequestedAt, deletionDueAt}` (409 ACTIVE / < 24 h), `POST /api/users/me/deletion/cancel
  {password}` → 204; `/me`, login, register and password-change `user` add `deletionRequestedAt`, `deletionDueAt`; every
  authenticated endpoint can answer 401 with `WWW-Authenticate: Bearer error="invalid_token",
  error_description="credentials_changed|account_deleted|account_unavailable"`. Config `app.account.*`.

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

---

## 10. Design system and UX (full spec: `docs/DESIGN_SYSTEM.md`)

- Navy hero `#0B1B2E`, teal primary `#0E7C86` (dark `#3DD6C6`), green = safe, amber = caution.
  **Red only for emergency UI.** All text pairs ≥ 4.5:1 contrast. Status never by colour alone (text + icon).
- Use theme attributes and existing styles/components; no hard-coded colours in layouts.
- Honesty labels: **Available** (works), **Preview** (design only), **Coming soon** (not built), **Sample** (placeholder data).
- No text claims a place or person is safe (Phase 8). The Home status card states facts only (`HomeStatus`: checking /
  nothing running / Safe Walk on / check-in missed; hidden during an SOS); `HomeTextsTest` scans every Home string.
- Emergency UI: clear, fast, high contrast, low cognitive load, large touch targets, `keepScreenOn` while active.
- Bottom nav: Home, Safety, Nearby (the `communityFragment`; renamed in Phase 10A), Profile. Phase 3+ screens are secondary destinations (toolbar + back, no bottom bar).

---

## 11. Approved Phase 4 design (already agreed with the user)

**Cross-cutting decisions**
- **Detection logic runs on the phone in pure Kotlin** (check-in deadlines, deviation, inactivity, Safety Bubble, battery
  policy, escalation policy): works offline and is unit-testable. The **server** stores journeys, validates state
  transitions, enforces ownership, and computes check-in deadlines (phone clocks drift).
- **One foreground service** (`SafetyTrackingService`, type `location`) during Safe Walk or an active emergency; persistent
  notification; started only while the app is visible. UI and service share state through one holder.
- **Location:** `play-services-location` (the only approved new dependency) behind a `LocationProvider` interface.
- **Emergency Context Packet** = a **read model assembled on demand** (emergency + latest location + journey + escalation
  events). **No `emergency_context` table.** Recipient-specific **projections are field allow-lists** (owner, Safety Circle,
  future official pathway, future Verified Nearby Assistance). Safety Circle sees only name, time, type, location link.
- **Escalation Ladder** = enum + pure policy, proportional, never jumps straight to the top:
  L0 normal · L1 missed check-in/minor concern → prompt user · L2 no response/anomaly → Safety Circle ·
  L3 confirmed SOS → emergency ACTIVE · L4 emergency pathway = **user-tapped dial prompt (112)**, no claimed integration ·
  L5 Verified Nearby Assistance = reserved name, **rejected by the server**.
- **Duress PIN** (silent trigger) remains a reserved trigger type; not part of Phase 4 unless re-planned.
- **SMS fallback:** auto-send only if the user opts in (then `SEND_SMS`); otherwise open the SMS composer for the user to send.
- **Safety Bubble / deviation baseline:** corridor around a straight start→destination line with generous tolerance;
  requires sustained deviation with acceptable GPS accuracy; **warns only**. Real route geometry belongs to Phase 8.
- **Privacy:** journeys store only the last-known location (no full GPS track).
- **Not in Phase 4:** server scheduler / push / dead-man alerts (no delivery channel yet — documented limitation),
  background location, `CALL_PHONE`, exact alarms, boot receiver, microphone, camera.

**Planned migrations (re-inspect the schema before writing)**
- **V4 `emergency_locations` (4A):** `emergency_id` FK cascade (**no user_id** — ownership via the emergency), latitude/longitude
  `DECIMAL(9,6)` with range CHECKs, `accuracy_meters`, `source` (FUSED, LAST_KNOWN, GPS, NETWORK, UNKNOWN), `battery_percent`,
  `recorded_at`, `received_at`, index `(emergency_id, recorded_at)`.
- V5 `safe_journeys` + `journey_events` (4B; check-ins are journey events; one non-final journey per user via generated marker).
- V6 `escalation_events` + nullable `journey_id` on `emergency_events` (4B/4C).

**Planned 4A API**
- `POST /api/emergencies/{id}/locations` — batch of 1–50 points (lets an offline phone upload later), all-or-nothing.
  Accepted while ACTIVE plus a 15-minute grace after the emergency ends; `recordedAt` not in the future (5-min clock tolerance)
  and not before the emergency began; per-emergency cap (configurable, e.g. 1000). Use an injectable `Clock` bean.
- `GET /api/emergencies/{id}/locations/latest` (404 if none), `GET /api/emergencies/{id}/locations` (newest 100).
- Owner only; other users → 404; JWT required.

**Planned 4A Android**
- `core/location` (provider interface + fused implementation; permission, availability and accuracy states; GPS off / denied /
  revoked / low accuracy / unavailable / temporary failure all handled), `core/device` (`ConnectivityMonitor`, `BatteryMonitor`),
  `core/notifications` (channels + notifier), emergency location association and upload, recovery not dependent on memory alone.
- New 4A permissions: `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` (at the moment of need, with rationale),
  `POST_NOTIFICATIONS` (Android 13+, before the first notification), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`,
  `ACCESS_NETWORK_STATE`.

**Phase 4 required test cases (map each to a named test):** Safe Walk start/complete/cancel; check-in success/missed;
warning; escalation; "I'm Safe" prevents escalation; location unavailable; location permission denied; network unavailable;
SMS unavailable; emergency recoverable after restart; another user cannot access emergency/journey; low battery handled.

---

## 12. Known limitations and gotchas

- Safe Walk can only escalate while the phone is alive and the process runs; no server-side alert channel exists yet.
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
  clean-up (withdrawals, role reset) always runs. Method and results: `docs/PHASE9.md` §13.

---

## 13. Decision log

| Decision | Reason |
|---|---|
| XML Views + Fragments, no Compose | Approved in Phase 1; viva-friendly; consistency |
| Manual `AppContainer`, no Hilt | Small object graph; less magic to explain |
| Feature-first packages | Features grow independently; future modularisation |
| Flyway, `ddl-auto=none` | Reviewed, versioned schema changes |
| JWT 24 h, no refresh tokens | Kept simple in Phase 2; revisit later |
| Spring resource-server JWT (not a custom filter) | Less custom security code |
| Foreign record → 404 | Does not reveal that IDs exist |
| "One primary" / "one active" via generated columns + unique index | Correct even under concurrent requests |
| Countdown client-side; event recorded at zero | Cancelling records nothing |
| Phase 4 in gated sub-phases 4A–4D | Size and risk; build + test between each |
| Location upload queue in memory only; phone stores ids + start time only | Minimum location retention on the device |
| `UNIQUE (emergency_id, recorded_at)` + all-or-nothing batches | Offline re-sends are safe and simple |
| `START_NOT_STICKY` tracking service, recovery on next open | A restarted service would lack while-in-use location access |
| Phone counts down `due − serverTime`; server checks "missed" against its own deadline (30 s) | Clock drift irrelevant; reports stay plausible |
| SOS links the active walk server-side by JWT user | No client-supplied journey ownership |
| Home active-session banner | Tracking can only resume from a visible screen after a restart |
| Movement concern = the same "Are you OK?" prompt opened early (L1 + reason), L2 on no answer | Ladder "L1 minor concern → prompt; L2 no response/anomaly"; bubble only warns, never L3. Confirmed by the user 2026-10-19 |
| Destination coordinates on the server, deleted at a normal end | Phone keeps no destination/location; minimum retention |
| Arrival is a hint, never auto-complete | Ending monitoring near the door is unsafe |
| Phone sends Safety Circle SMS (opt-in), server only records status | No server gateway in Phase 4; SMS works offline; server keeps no number or text |
| All-clear / resolved follow-up messages; SOS cancelled within the location wait tells nobody | Calm, proportional communication; no needless alarm |
| Emergency Context Packet: read model + explicit allow-list projections, owner-only endpoint | Approved design; nothing copied; no accidental leak |
| Level 4 = `ACTION_DIAL` 112, recorded as "dialer opened" | No claimed emergency-service integration; no CALL_PHONE |
| Evidence encrypted on the phone, per-item key in the Keystore; server stores ciphertext only | Server/DB breach reveals no evidence; delete key = crypto-shred |
| Escrow to an OFFLINE RSA recovery key (server has the public half only) | Recoverable after phone loss without making the server able to read evidence |
| Server re-hashes every byte before SEALED; hash-chained custody ledger incl. downloads | "Verified" is a server fact; tampering and access are auditable |
| Audio in 30 s AAC/ADTS parts; photo/video via camera app | Loss limited to one part; crash-recoverable; no CAMERA permission, no new library |
| Deletion needs the password and is refused during an ACTIVE emergency | An unlocked phone in the wrong hands cannot destroy evidence |
| Deletion also refused 24 h after the end; 5 wrong passwords → 429; attempts logged in custody | Coerced "I'm safe" + immediate deletion; online guessing; attempts are evidence |
| Vault viewing gated by the phone's screen lock; Keystore key still NOT auth-bound | Capture while locked; removing the screen lock would otherwise destroy all keys |
| Upload resumption via platform JobScheduler (not WorkManager) | No new dependency; same OS mechanism |
| Phone re-checks the vault copy on the server before deleting its own | "Verified once" ≠ "intact now"; never delete the last good copy |
| Restore on a new phone: user passphrase → PBKDF2 → wraps a per-account recovery key that wraps item keys | Self-service recovery without server readability; passphrase change = one re-wrap; approved 2026-10-20 (option D) |
| Recovery envelope only with the account password; RK reset deferred | Stolen token gives no guessing material; one RK keeps every copy openable |
| Community = place signals: merge per (cell, category), one answer per person, bands not counts, no text/photos/authors | Safety intelligence, not engagement; report bombing pointless; no accusations of people (Phase 6, autonomous decision under the user's brief 2026-10-20) |
| Phone sends geohash cells only; precision earned by a 2nd person (k = 2) | Server never has coordinates; one account cannot pin a home |
| Random publication delay; only `counts_from` stored; 24 h new-account probation | Reporter cannot be linked on the spot; real time never recorded; sock puppets slower |
| Area look-ups = own area only, 15 distinct areas/h, limiter in memory | Anti-scraping without a DB log of where viewers look |
| Safety Radar drawn by the app, no map tiles | No new dependency / API key; tile servers would learn viewed locations |
| Trust private (tiers only weigh contributions; never shown, not even to the person) | Trust > popularity; no status system, nothing to track or game (Phase 7, autonomous under the user's brief 2026-10-20) |
| Independence rule: accounts sharing ≥ 3 live signals and ≥ half of their activity count once | Sock-puppet resistance without device ids, phone numbers or IPs; fades with retention |
| Content hidden automatically (reversible quarantine), people restricted only by a human | Automatic punishment fails unsafely; flags cannot silence a well-corroborated warning |
| Case-scoped HMAC pseudonyms, reason codes only, audit log, appeal to a different moderator | Moderators act without knowing or tracking people; moderator abuse is auditable |
| Community pause never touches SOS / Safe Walk / Safety Circle / evidence | A safety app must fail safely |
| Phase 9: no learned model for anything users see; measure first (9A attack simulation through the real API) | No real data, no ground truth, deliberate forgetting; a model would learn our test scripts (`PHASE9_DESIGN.md`, 2026-10-21) |
| coord-1 (k-core + exclusive pairs) only opens a case for a human; SHADOW by default | Catches the rings and padded pairs 9A measured; honest communities misread as rings must cost moderator time, never their voice |
| COORDINATION cases do not lock withdrawal (flags still do) | The 409 told a ring it was detected; withdrawing removes the fake anyway (9C, changes a Phase 7 decision) |
| S15 Option B: a moderator's DISMISS of a COORDINATION case frees THAT signal's counting people (frozen to those seen), via the closed case row | Honest groups regain their voice without storing a social fact; a mistaken dismissal cannot be topped up with puppets (9C.1, the user's choice of option B) |
| A case lists the coord-1 group's members; one RESTRICT can pause them all | One by one was measured to be fragile (the pattern vanishes before the last member); each pause is still audited and appealable per person (9C.2) |
| Group membership = backs ≥ 2 of the group's places | A ring member confirming an honest pair's place pulled them into the 2-core (found in testing); detection unchanged (9C.3) |
| Neighbour join in the clean-up job, only once the report counts; lone reports only, into OLDER signals, stored as CONFIRM | A write-time merge (the design) is a silent probe of a single report's hidden block and misses pairs that were both pending; "older only" stops capture; the effect equals "I saw this too" (9D-1) |
| Side neighbours only, never corners | Measured: 300 m street 39.6 % → 78.6 % honest pairs joined, 2.0 % of places 300 m apart joined (corners: 94.6 % / 17.9 %) (9D-1) |
| rules-1.1: safe places alone count once (information ≤ LITTLE) | "No confirmed concerns" could only come from havens, and two accounts faked it; a change that only removes reassurance ships (9D-3) |
| rules-2 stays SHADOW (counted, never shown) | It also downgrades honest pairs' "extra care"; no real-world evidence of the honest/fake proportion; weakening a warning needs evidence (9D-2) |
| Outcome counters: weekly, system-wide, after commit, best effort, ADMIN-only; nothing that decides reads them (9B) | Rules can be evaluated without keeping history, places or people; measurement can never break or steer a safety app |
| The rule that opened a COORDINATION case is stored on the case (V15) | A whole-group pause removes the pattern before the decision; recomputing would credit the decision to nothing (9B, deviation from the design) |
| Counter CHECKs: upper-case dimension, versions with one dot, binary-collated closed lists | A cell, id or free text cannot be stored; `rules-1.1` must fit; an `IN` CHECK under a case-insensitive collation let `support_core` through (dry run) |
| Roles read from the DB per request; ADMIN only via SQL | Instant revocation; no API path to the top privilege |
| Sign-in/sign-up limits in memory, failures only, per account + per address | No IP/e-mail log at rest; short account window limits victim lock-out |
| Safety Intelligence = deterministic rules on two axes (confirmed concern, information), no score, no ML | Explainable, attack-resistant (counts independent people), honest about "unknown"; no ground truth for ML (Phase 8, autonomous under the user's brief 2026-10-20) |
| Picture computed from the ONE visible set; neighbourhood = max of cells; havens capped and never cancel concerns | No timing/moderation oracle; busy areas not penalised; lure resistance |
| Independent freshness (linked group = its oldest member) for intelligence | Sock puppets cannot keep an incident "today" |
| Pattern memory = one decaying number per ~1 km area and group, k-gated (3 episodes), reset on removal | Recurrence without history, people or times; forgets within ~8 months |
| App-drawn radar cells, no heat map, no tiles | Unknown is visible (hatched), real precision, no third-party location leak |
| "Check the way" computed on the phone from ~5 km region packs | Server never learns a route; only confirmed signals rank; unknown ≠ safe |
| **No monitoring of a person by anyone else, permanently** (no family/partner/guardian mode, no location on request) | Safety the person controls expands their world; a monitoring feature would turn a safety tool into a control tool (Phase 10) |
| Revocation by a credentials stamp in the token, exact equality, checked on every request (Phase 10A) | Ends stolen / old sessions and disabled accounts at once without refresh tokens; no clock or second-rounding issue (deviation from `iat`) |
| Password change refused during an ACTIVE SOS or Safe Walk (Phase 10A) | Revocation would otherwise let anyone who knows the password cut a running safety session off the server |
| Account deletion: password, refused during / 24 h after an emergency, 7-day grace with a Home banner, cancel needs the password, sign-in does not cancel, postponed by an emergency, files before rows (Phase 10A) | Coerced deletion is recoverable and visible; fresh evidence is protected; no ciphertext left without an owner |
| Accounts named in the moderation audit log are anonymised, not deleted (CHECK-enforced tombstone) (Phase 10A) | The audit trail survives; the person's data does not |
| Wrong passwords on account endpoints answer 409, not 401 (Phase 10A) | A 401 means "signed out" on the phone |
| The phone removes a deleted account's evidence only on the server's `account_deleted` answer or the person's tap | A sign-out must never destroy evidence; a cancelled or postponed deletion must never wipe a living account |
| Only working rows on every screen; no "Coming soon" anywhere; tab "Nearby"; summary leads with safe places; no "avoid" (Phase 10A) | Honesty is the product's claim; places, not people; going out, not staying away (`HonestyTextsTest`) |
