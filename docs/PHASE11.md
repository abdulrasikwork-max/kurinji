# Phase 11: SOS without internet + Phase 10B: Rise "Proof"

**Implemented 2026-10-22** autonomously under the brief "take full ownership of the next stage; product freedom"
(audit first, one valuable capability, a critical re-evaluation of RISE, then implement, test, verify, document).
Status vocabulary (`CLAUDE.md` §3): IMPLEMENTED · COMPILED · TESTED · VERIFIED (where it was run) · NOT VERIFIED.

| Part | Status |
|---|---|
| Phase 11 SOS without internet (V18, backend, Android) | IMPLEMENTED, compiled, tested; **V18 dry run 10/10 on MySQL, applied (v18)**; **live API 12/12**; **emulator (API 37) VERIFIED** with the server really down (§3.6) |
| Phase 10B Rise "Proof" (Android only, phone-only) | IMPLEMENTED, compiled, tested; **emulator (API 37) VERIFIED** for intro, goal, prepare/try/outcome, replay, proof, quiet mode, "wasn't OK", delete, FLAG_SECURE (§4.6) |
| Real phone (Galaxy A23), TalkBack, dark mode, screen-lock gate on a device with a lock | NOT VERIFIED |
| 10C collective proof | Not built (stoppable by design; needs real users and moderators, §4.4) |

---

## 1. Audit (before any code)

Read: `CLAUDE.md`, `HANDOFF.md`, `PHASE10.md`, `PHASE10_DESIGN.md`, `API.md`, the phase docs, then the code itself
(the SOS path end to end, Safety Circle alerts, location sharing, Safe Walk hooks, evidence hooks, account code).

| Area | Classification (2026-10-22, before this work) |
|---|---|
| SOS lifecycle (countdown, record, end, recovery, 10A.4 password rule) | IMPLEMENTED; emulator VERIFIED; real phone NOT VERIFIED. **Gap found: needed the server to start (§2)** |
| Safety Circle (contacts, opt-in automatic SMS, composer, statuses) | IMPLEMENTED; emulator SMS run (4D); real phone NOT VERIFIED; 4D manual checklist skipped by the user |
| Safe Walk / check-ins / movement checks | IMPLEMENTED; 4A/4B manual checks NOT VERIFIED; 4C PARTIALLY VERIFIED |
| Location sharing (4A) | IMPLEMENTED; emulator VERIFIED; real phone NOT VERIFIED |
| SMS / 112 prompt | IMPLEMENTED (`ACTION_DIAL`, no CALL_PHONE); emulator VERIFIED |
| Evidence Vault (Phase 5) | IMPLEMENTED; emulator VERIFIED; Galaxy A23 PARTIALLY VERIFIED; rest DEFERRED |
| Nearby (6), trust/moderation (7), Safety Intelligence (8), Phase 9 | IMPLEMENTED; emulator VERIFIED; TalkBack/real phone NOT VERIFIED; Phase 9 frozen |
| Accounts (10A, 10A.4) | IMPLEMENTED; MySQL + emulator VERIFIED; token encryption (A14) and password reset (A6) DEFERRED |
| RISE "Proof" | **PLANNED only.** Confirmed in code: no `feature/rise`, no Rise tab, no Rise strings (`PHASE10.md` D11/D12) |

**Docs vs. code gaps found**
1. **SOS needed the server to exist.** `EmergencyViewModel.startEmergency()` called `POST /api/emergencies` first; any
   failure (no data balance, basement, rural road, an overloaded network, server down) showed "Could not record the
   emergency" and only "Call 112". The Safety Circle was **not** texted even with automatic SMS on, although SMS needs no
   internet and the contacts are cached for exactly that; no location was taken. `PHASE3.md` said "no offline queue";
   `PLATFORM_CONSTRAINTS.md` ("SMS needs no internet") and the 4D "offline SMS fallback" read as if it worked.
2. Ending an SOS without a connection failed ("Unable to connect"); the person's "I'm safe" was lost.
3. The `CLAUDE.md` Rise line "Not built yet (10B)" was accurate.

## 2. Problem chosen

*If SafeSphere were released tomorrow, what important problem would still be insufficiently solved?*
**The SOS fails exactly where help is hardest to get: without a working internet connection.** In India prepaid data
runs out, metros and basements have no signal, rural highways and night trains have weak coverage, crowds overload the
network, and servers have outages. In all of these SMS still works. A safety app whose emergency button depends on a
server round trip fails silently at the worst moment, and the person learns it only then.

Rejected alternatives (each looked impressive, solved less): a duress PIN (accidental and coerced activation, needs its
own threat model), a server dead-man alert (no delivery channel exists; would need an SMS gateway and a public deployment),
a "Circle Response" link (needs HTTPS deployment), password reset (needs e-mail), an AI risk score (no labels; Phase 9
verdict). A readiness check-up was considered; its most important line ("your SOS will not text anyone offline") would
have described this very gap, so fixing the gap came first.

It fits **"Safety that expands your world"**: people can go further (treks, late trains, rural trips, a city with poor
coverage) and the SOS still reaches the people they chose.

## 3. Phase 11: SOS without internet

### 3.1 Behaviour
- At the end of the 5-second countdown the SOS **starts on the phone at once**: location sharing and the Safety Circle
  (automatic SMS if switched on, otherwise "Text" per contact with the cached list) start immediately, with a **local id**
  (negative, from a random reference). The screen shows *"Your SOS is running on this phone. SafeSphere's server has not
  been reached yet…"* until the server has it; the location card says points are *collected*, never "saved".
- `SosRecorder` (application scope) records it on the server **now or as soon as it can**: backoff 5→60 s, at once when
  the connection returns, after a restart too. The same **client reference** is sent every time; the server answers it
  with the same emergency, so a lost answer never makes a second SOS.
- When the server's id arrives, the holders that keep per-emergency state (Safety Circle memory/deliveries/pending
  reports, location sharing incl. points collected meanwhile) are **moved to it** (`EmergencyRebinder`). Nothing is sent
  twice; reports and points reach the right record.
- **"I'm safe" / "False alarm" work offline.** Before the server has the SOS, or when the end call cannot reach it, the
  end is kept on the phone (with its time) and told to the server later (as an already-ended record, or as an end with
  `endedAt`). The resolved/false-alarm SMS still goes out. The ended screen says it will be saved once the server is
  reached, and changes when it is.
- "Call 112" before the server has the SOS is remembered and recorded (level 4) once it has.
- If the server already has a running SOS of this person (another phone), the phone joins that one.
- Evidence capture opens only once the server has the SOS (evidence is bound to a server emergency; a short wait online,
  a stated limitation offline).
- History shows *"Started without a connection (times from your phone)"*.

### 3.2 Backend (V18)
`emergency_events.client_ref` (lower-case UUID, binary collation, `UNIQUE (user_id, client_ref)`, format CHECK),
`started_offline` (CHECK: needs a reference), and `ck_emergency_events_ended_order` (`ended_at >= started_at`, now that
phone times can enter). `POST /api/emergencies` accepts `clientRef`, `startedAt`, `endedAt` + `outcome`;
`PUT …/resolve|cancel` accepts `endedAt`. Phone times are clamped (start: `[now − 24 h, now]`; end: `[start, now]`), never
trusted for anyone but the owner. Online starts keep the server's own time (no behaviour change). The location upload
grace for an offline-started SOS counts from when the server first stored it if that is later than its end. A late
record links only a walk that was already running when the SOS began. Details: `API.md` "Phase 11".

### 3.3 Android
`feature/emergency/session/SosRecorder` (the one holder; `EmergencyRebinder` interface), `ActiveEmergencyStore` extended
(reference, on-server flag, 112 flag; a separate "unsent endings" list, ≤ 5, encoded as numbers/UUID/flags only by
`UnsentEndingsCodec`), `EmergencyLocationSharing` (upload target may be local → collect but do not send; park points of
an SOS that ended before the server had it, in memory; `rebindEmergency`), `CircleAlertCoordinator`
(`rebindEmergency`; reports for a local id wait), `EmergencyViewModel` (local-first start, offline end, restore of a
local SOS before asking the server, re-check of the recorder after the screen state is built), `EmergencyFragment`
(notice, evidence card hidden while local, honest location text), history line. `AppContainer` creates the recorder
eagerly and clears its records on sign-out.

### 3.4 Decisions and reasons
| # | Decision | Reason |
|---|---|---|
| E1 | Local-first for EVERY SOS, not a fallback after a timeout | One path; the Safety Circle SMS no longer waits for a server round trip (up to 30 s of OkHttp timeouts on a captive network); the mechanism is exercised by every SOS, not only by rare offline ones |
| E2 | Idempotency by a phone reference, unique per person | Retries after lost answers are normal offline; per-person uniqueness gives no cross-account oracle |
| E3 | First attempt sends no phone time; later ones do; server clamps | Online SOS keep exact server times (phone clocks drift: the A23 was 74.6 s behind); offline ones keep their real start, bounded |
| E4 | An SOS that ended offline is created already ended (no session id) | The record is true (start and end as they happened) and cannot block a new SOS |
| E5 | Points of a local SOS kept in memory only | Same rule as 4A: no precise location on the phone's disk; a process death loses them (stated) |
| E6 | Evidence only once the server has the SOS | Evidence items, custody and escrow are bound to a server emergency; re-binding sealed items is a separate design |
| E7 | Sign-out clears unsent endings | They belong to the account; a sign-out must not leave another person's records |

### 3.5 Security and privacy
- **Authorization:** owner from the JWT only; references are per person; another person's reference creates their own
  row and reveals nothing (tested); foreign ids stay 404.
- **Abuse / coercion:** the 5 s countdown and its cancel are unchanged (accidental activation); the 10A.4 rule (another
  sign-in needs the password to end an SOS) is unchanged; an offline end from the starting phone is the person's own
  "I'm safe", exactly as online.
- **Data minimisation:** phone stores ids, a random UUID, times, a 112 flag; never a location, number or text (format
  test). Server stores the reference and the offline flag only.
- **Clock manipulation:** only the person's own record is affected; bounded by clamps and the ordering CHECK.
- **False reassurance:** no text says "saved" or "recorded" before the server has it (location card fixed after the
  emulator run, §3.6).

### 3.6 Verification (actual results)
| Check | Result |
|---|---|
| `mvn test` | **482 tests, 0 failures, 2 skipped** (473 before; +9 `OfflineSosApiIntegrationTest`) |
| V18 dry run on MySQL 8.0 (scratch copy of the real rows, dropped) | **10/10**: existing rows satisfy the new CHECKs; 3 valid rows accepted; 7 violations each rejected by the intended constraint (upper-case ref, non-UUID, offline without ref, end before start, duplicate ref, UPDATE end before start, UPDATE dropping the ref) |
| V18 applied | Flyway "now at version v18"; next migration **V19** |
| Live API on MySQL | **12/12** (L1–L11: idempotent reference, one row, phone end time stored exactly, ended-offline record, its late points accepted, online grace unchanged, invalid ref 400, another person's ref, history flag, MySQL CHECK, future time clamped). A first attempt stopped at 8/9 on two script faults (an end time before the start, which the server correctly clamped; a GET with a body); its accounts were removed in `finally` |
| Emulator (Medium_Phone_API_37.0), server really stopped | **PASS:** SOS started → "running on this phone", location "collected", 1 point waiting; force-stop + reopen offline → Home "Emergency active", same SOS restored; backend started → recorded within seconds with `started_offline=1`, the phone's start time, 3 points; screen switched (notice gone, evidence card back); backend stopped → "I'm safe" → "saves it to your history once its server can be reached"; backend started → `RESOLVED` with the phone's end time, screen updated |
| Bug found on the emulator and fixed | When the server answered while the screen was still loading the contact list, the screen kept showing "not recorded yet" (holders were already moved). Fix: re-check the recorder after the Active state is built; regression test `EmergencyViewModelTest` "if the server records the SOS while the contact list is still loading…" (fails without the fix by construction: slow contact list) |

**NOT VERIFIED:** an automatic SMS actually sent while offline on a real SIM (the emulator account had no contacts; the
SMS path is unchanged 4D code, unit-tested here with a local id); real phone; TalkBack on the new notices.

### 3.7 Limitations
- The SOS needs a signed-in session on the phone; an expired token (> 24 h offline without renewal) stops the server
  recording (`UNAUTHORIZED` waits; a 401 ends the session and clears the record). SMS from that moment still work only if
  the session is valid when the SOS starts.
- Points collected before a process death are lost (memory only, by design); the SOS itself is recovered.
- Evidence recording waits for the server record.
- A phone time older than 24 h is clamped; an SOS kept offline for more than 24 h is recorded with a shifted start.
- Unsent endings are cleared by a sign-out.

## 4. Phase 10B: Rise "Proof"

### 4.1 Critical re-evaluation of the design (what changed and why)
| # | Design (`PHASE10_DESIGN.md`) | Built | Reason |
|---|---|---|---|
| R1 | Home card "Rise: you decide what's for you" | **Not built** | A Rise tab exists; a Home card is a nudge and shows "Rise" to anyone glancing at Home (coercion persona) |
| R2 | "Keep a private note" after something wasn't OK | **Dropped** | The most dangerous text a controlling person could find, with no evidential value (not sealed, no custody). SOS, the Safety Circle and Nearby are offered instead |
| R3 | "What helped?" after the outcome | Dropped | The supports chosen in Prepare are already recorded and shown in proof; the extra question only served 10C |
| R4 | Safe Walk pre-filled with a destination typed in Rise | **Navigation only** | Rise stores no destination text; Rise and SAFE pass nothing to each other (isolation tested) |
| R5 | "Tell someone in your Circle" | SMS app **with no number** | Rise never reads the Safety Circle; the person picks the recipient; text names no goal, place or date (tested) |
| R6 | Quiet mode / lock as settings | Lock offered **in the intro** (default off), quiet mode in settings; lock re-arms after Rise is left (1 s grace across Rise's own screens) | Coercion persona: the choice is made at first use, not buried |
| R7 | Replay "See them" link | Not shown | "them" is on the forbidden list; the replay is complete as one sentence |
| R8 | A "first" for any outcome | Only for a step that was done | A first not taken is not a first |
| R9 | Unknown outcome from a newer app | Read as "wasn't OK" | Never turns into false proof |
| R10 | 10C collective proof | Not built | Stoppable by design; needs users, a 7-day account age and moderators; no real data exists |
Kept as designed: nine domains, self-doubt vs. safety-concern split (a safety worry opens SAFE preparation and is never
part of a replay), unranked supports, "That was your call", "That wasn't your fault", no streaks/points/badges/
followers/quotes/AI/notifications, gender and stereotypes never named.

### 4.2 Architecture
`feature/rise/{domain: RiseModels, GoalPolicy, ProofPolicy; data: EncryptedFileRiseStore; session: RiseJournalHolder,
RiseLock; ui: RiseTexts, RiseViewModel, RiseScreenFragment, RiseFragment, RiseGoalEditorFragment, RiseGoalFragment,
RiseStepFragment, RiseProofFragment}`; bottom tab "Rise" (5 tabs). `ScreenLockGate` moved to `core/ui` (no behaviour
change). No new dependency, no network, no service, notification, alarm or job.

### 4.3 Privacy and security
- **Storage:** one file per account, AES-256-GCM with a fresh key per save, wrapped by the phone's Keystore key, AAD
  `SSRISE1|accountId` (a renamed file of another account is unreadable, tested); atomic write + `.bak`; damaged →
  backup, both damaged → "could not open" with an explicit "Delete it and start again" (never silently empty).
- **Nothing leaves the phone.** Source scans: no Retrofit/OkHttp/notification/service/alarm/job/location/SMS manager in
  Rise; no SAFE package imports Rise and Rise imports no SAFE package.
- **Coercion:** FLAG_SECURE on every Rise screen (screenshot verified black), optional screen-lock gate, quiet mode,
  one-action delete, no Home card, no notifications. Signing out keeps Rise (stated in the intro); account deletion
  removes it (`AccountDataWiper`).
- **Honest limits:** someone who knows the phone's PIN can open Rise; a lost phone loses Rise (no sync).

### 4.4 Language rules (tested)
`RiseTextsTest` scans every Rise string and plural for gender words, opponent framing ("them", "prove"…), pressure
("brave", "streak", "challenge", "score"…), obligation ("should", "must"…) and safety claims ("safe", "guaranteed");
templates never remove support ("alone", "on your own", "without"…). The test caught the author's own "Change them".

### 4.5 Tests
`RiseTest` (11: goal rules, limits, one try at a time, firsts, replay only for the same doubt and never "wasn't OK",
safety worry gets no replay, honest mostly-harder, proof and summaries, encrypted round trip without readable words,
AAD binding, backup fallback, lost key, account switching, failed save, delete, intro/lock gating with the grace),
`RiseTextsTest` (6).

### 4.6 Verification
Emulator (API 37) PASS: intro texts; Start; editor with domain suggestions (Learn replaces Go's); goal screen; Prepare
with expectation + doubt (checked); safety worry → "worth listening to" + Plan the way; support; "I'm going to try it" →
"Rise does nothing while you try"; "Better than I expected" + first → "That's in your proof" + four equal next choices;
"Do it again" + same doubt → *"You've felt this once before. 1 went better than you expected."*; list and proof;
quiet mode → "Goal 1 · Learn" / "A step in Learn"; "Something happened that wasn't OK" → "That wasn't your fault", no
"first" option, SOS/SMS/Nearby/put aside, proof unchanged, history without blame; "Delete all Rise data" → both files
gone, intro again; screenshot black (FLAG_SECURE).
**NOT VERIFIED:** the screen-lock gate on a device with a screen lock (the emulator has none; unit-tested), TalkBack,
dark mode, 320 dp / 2.0× text, real phone.

## 5. How the two relate (only where real)
- A Rise step about **going somewhere** (domain "Go places") or any step with the **safety worry** shows SAFE preparation:
  "Plan the way" (Safe Walk set-up with Check the way) and "Your SOS and Safety Circle" with the line that an SOS texts
  the Circle **without internet** only if automatic SMS is on. That line is true only because of Phase 11.
- "Something happened that wasn't OK" opens SOS, which now works without a connection.
- Nothing else: SAFE never reads Rise; Rise never reads SAFE.

## 6. Files
Backend: `V18__emergency_offline_start.sql`; `emergency/{dto/CreateEmergencyRequest, dto/EndEmergencyRequest,
dto/EmergencyResponse, entity/EmergencyEvent, repository/EmergencyEventRepository, service/EmergencyService,
service/EmergencyLocationService, controller/EmergencyController}`; `journey/service/JourneyService`; test
`emergency/OfflineSosApiIntegrationTest`.
Android: `feature/emergency/{session/SosRecorder, domain/ActiveEmergencyStore, domain/EmergencyModels,
domain/EmergencyRepository, data/DataStoreActiveEmergencyStore, data/RemoteEmergencyRepository, data/remote/EmergencyDtos,
location/EmergencyLocationSharing, ui/EmergencyViewModel, ui/EmergencyUiState, ui/EmergencyFragment,
ui/EmergencyEventAdapter}`, `feature/circlealert/session/CircleAlertCoordinator`, `feature/rise/**`,
`core/ui/ScreenLockGate` (moved), `AppContainer`, `NavigationRules`, `nav_graph.xml`, `bottom_navigation.xml`, Rise
layouts and icons, `strings.xml` (SOS, Rise, privacy notice). Tests: `SosRecorderTest` (14), `EmergencyViewModelTest`
(rewritten for the local-first path, 30), `EmergencyLocationSharingTest` +2, `CircleAlertCoordinatorTest` +2,
`RiseTest` (11), `RiseTextsTest` (6), fakes `FakeSosParts`.

**Totals (final run on the final code):** `mvn test` **482 / 0 failures / 2 skipped**; `gradlew testDebugUnitTest`
**527 / 0 failures** (487 before); `assembleDebug` OK; `lintDebug` **0 errors / 85 warnings** (unchanged).

## 7. Viva notes
- *Why does the SOS start before the server answers?* Because SMS and GPS work without internet and the server does not;
  an emergency button must not depend on a round trip. The server is told later and is still the record.
- *How do you avoid two emergencies when the phone retries?* A random reference per SOS; the server answers the same
  reference with the same row (unique per person).
- *Can a phone fake old emergencies?* Only in its own history, at most 24 h back, never ending before it started (CHECK).
- *What does Rise send to the server?* Nothing. Encrypted file per account, bound to the account by AAD.
- *Why no Home card or reminders for Rise?* Pressure and exposure: someone looking at the phone sees nothing, and
  nobody is nudged towards risk.
- *Why is a safety worry not answered with past proof?* Treating "is it safe?" as a confidence problem would teach people
  to ignore real warnings; worries lead to preparation, doubts lead to proof.

---

## 8. C1: session continuity (EXPIRED is not REVOKED), V19 — 2026-10-22

**Status:** IMPLEMENTED, compiled, tested; **V19 dry run 17/17 on MySQL + applied (v19)**; **live API 10/10**;
**emulator (API 37) VERIFIED** (E1–E7 below). **Real phone NOT VERIFIED**; a 25-hour idle test on a device NOT DONE.

### 8.1 The problem (found in `docs/AUDIT_NEXT.md`, C1)
The 24 h access token was renewed only while the app process ran. After a day without the app the first request got a
401, `SessionEnder` signed the phone out, and the sign-out clean-up wiped the running SOS record, unsent endings, the
Safety Circle copy for offline SMS and the Safe Walk record, then showed Login. An offline SOS started with such a token
was wiped as soon as the connection returned. No test or emulator run could see it (every check ran within hours of
signing in).

### 8.2 What changed
- **Backend (V19 `device_sessions`):** one row per sign-in (`session_id` = the tokens' `sid`), SHA-256 of the refresh
  secret only, rotation on every refresh, previous hash + `rotated_at` for the lost-answer grace and reuse detection,
  `authenticated_at` (= `auth_time`), sliding `expires_at`, `revoked_at`/`revoked_reason` (CHECK-ed closed list).
  `POST /api/auth/refresh`, `POST /api/auth/sign-out`; login/register/refresh return `refreshToken`; renewal and password
  change adopt pre-V19 sign-ins once; `ActiveAccountCheck` refuses tokens of ended sessions (`session_revoked`); password
  change ends the other sessions; erasure deletes them; the deletion job purges ended ones; login with
  `previousRefreshToken` hands the same phone's old sign-in (and its ACTIVE SOS) to the new one. `API.md` "Phase 11 C1".
- **Android:** `AuthSession.refreshToken` (sealed with a separate Keystore key, `safesphere_session_v1`) and
  `needsSignIn`; `SessionRefresher` (single-flight Mutex; NonCancellable call + conditional atomic save
  `replaceAfterRefresh`); `SessionEnder`: no reason = refresh, refused = "sign in to sync" (`markNeedsSignIn`, nothing
  deleted), `session_revoked` = "sign in to sync", `credentials_changed`/`account_deleted`/`account_unavailable` = the
  Phase 10A sign-out; `ApiCallHandler` retries ONCE after a refresh and never refreshes twice for one call;
  `SessionRenewer` refreshes at start after idle time and before expiry, adopts pre-C1 sign-ins; the holders
  (`SosRecorder`, `EmergencyLocationSharing`, `CircleAlertCoordinator`, `SafeWalkSession`) keep their data on 401 and
  resume on the next token, and forget everything only on a real sign-out or an account switch;
  `RemoteAuthRepository`: same account = keep + hand over, another account = clean-up first (`onAccountSwitch`) and
  never send the previous account's secret, sign-out ends the server session (best effort, 3 s); Home banner and a
  closable "Sign in to sync" dialog; the SOS screen never gets a dialog for it and keeps a running SOS; "I'm safe" while
  sign-in is needed is kept and recorded after signing in again.

### 8.3 Decisions
| # | Decision | Reason |
|---|---|---|
| C1-1 | Refresh secret per device session, rotated on every use, only its SHA-256 stored | Standard rotation; a DB leak gives nothing usable; 256 random bits need no slow hash |
| C1-2 | The just-replaced secret is accepted once more for 60 s (lost answer), later it is reuse and ends the session | Lost answers are common on poor mobile networks; without a grace a network glitch would cost the person their sign-in. Reuse after the grace still ends a copied session (deviation from a strict "any replay revokes", stated) |
| C1-3 | Wrong secret for a real session id: refused, session untouched | Someone who only knows a `sid` cannot sign another person out |
| C1-4 | Idle timeout 90 days, sliding; the 30-day `session-max-age` rule stays for `/token` only | An SOS after weeks unopened must reach the server without the password; password change, sign-out, reuse and deletion still end a session at once |
| C1-5 | Expired ≠ revoked on the phone: a refused refresh keeps every local safety record ("sign in to sync") | Authentication failing for sync must never destroy an SOS, its ending or the offline Circle copy |
| C1-6 | Password change, deleted or disabled account still sign the phone out (Phase 10A) | Deliberate security events; unchanged rules |
| C1-7 | Another account signing in: the previous account's local data is cleared before the new session is saved, and its secret is never sent with the new login | No cross-account leakage; the server never learns that two accounts share a phone |
| C1-8 | Same-phone re-login hands over the old sign-in and its ACTIVE SOS (proof: the old refresh secret + the password just typed) | Otherwise "I'm safe" after re-signing would need the password (10A.4 "another sign-in" rule) and could be dropped |
| C1-9 | Every session change that keeps the rest is ONE atomic storage update | Found on the emulator: a read-then-save wrote a replaced secret back over the rotated one (seen by the server as reuse) |
| C1-10 | Renewal is due in the last 12 h AND in the second half of the token's life | Found on the emulator with 2-minute test tokens: every new token was "due" at once (tight refresh loop). 24 h tokens: unchanged |
| C1-11 | The refresh secret is Keystore-encrypted; the 24 h access token stays plain in DataStore | A14 closed for the long-lived credential; the short-lived one is app-private and excluded from backups (stated) |

### 8.4 Verification (actual results)
| Check | Result |
|---|---|
| `mvn test` | **501 / 0 / 2 skipped**; `DeviceSessionApiIntegrationTest` 19 (refresh, rotation, lost-answer grace, reuse → revoke, older/guessed secrets, malformed, concurrent refreshes = one consistent row, sign-out, password change, disabled, deletion, two accounts, same-phone hand-over incl. SOS, another account's token ignored, pre-V19 adoption, idle expiry, purge, SHA-256 + `MessageDigest.isEqual`, raw secret never in the row); `AccountEraserCoverageTest` covers `device_sessions` |
| Android unit tests | `SessionRefreshTest` (refresh + retry, storm = one refresh, no loop, offline, revoked, deleted, credentials changed, stale 401s, late answers, other account's answer, pre-C1 sign-in, same/other account login, sign-out, start-up refresh, toString, dialog rule, lost-update race); `SessionContinuityTest` (+5: refresh at start, adoption, nothing while sign-in needed, half-life rule, no loop with 2-minute tokens); holder tests (`SosRecorderTest` +2, `EmergencyLocationSharingTest` +2, `CircleAlertCoordinatorTest` +2, `SafeWalkSessionTest` +2, `EmergencyViewModelTest` +2); 6 older tests updated from "401 = sign-out" to the C1 rule |
| V19 dry run (MySQL 8.0, scratch tables, dropped) | 3/3 valid rows accepted; 14/14 violations rejected by the intended constraint (format, hex, upper-case hex, rotation pair, previous hash, revoked pair, unknown and lower-case reason, time order ×2, duplicate id, unknown user, 2 UPDATEs); cascade removes only the deleted user's rows |
| V19 applied | Flyway "now at version v19"; 7 CHECKs + `ON DELETE CASCADE` FK present |
| Live API on MySQL (backend with 2-minute tokens, 5 s grace) | **10/10**: L1 hash only; L2 rotation + lost answer; L3 reuse → `REUSE_DETECTED`, newest secret and access token refused; L5 sign-out; L6 password change; L7 disabled; L8 same-phone hand-over, SOS ended without password, not "elsewhere"; L9 other account's token ignored; L10 constraints; L4 real expiry → plain 401 → refresh → SOS recorded. L4 first failed on a script fault (waited 130 s; Spring accepts 60 s clock skew), passed with 190 s |
| Emulator (Medium_Phone_API_37.0, backend with 2-minute tokens) | **E1** sign-in, refresh secret stored sealed (raw token not in the file). **E2** app killed, token expired (192 s) → reopened to Home, refreshed (rotated). **E3** token expired + backend stopped → SOS started on the phone, force-stop + reopen kept it, backend back → recorded **once** (`started_offline=1`) after a refresh. **E4** session revoked on the server → no dialog on the SOS screen, "I'm safe" kept, Home banner "Sign in to sync", signing in through it → banner gone, SOS `RESOLVED`, `ended_elsewhere=0`. **E5** account switch → none of A's SOS on the phone. **E6** Log out → server session `SIGNED_OUT`. **E7** password changed on another sign-in → forced "Password changed" dialog, phone signed out |
| Bugs found on the emulator and fixed (each with a regression test) | (1) a signed-out phone got the "Sign in to sync" wording (dialog now decides by whether a session exists); (2) lost update of the rotated secret by a read-then-save (atomic `SessionStorage.update`); (3) refresh loop with short-lived tokens (half-life rule); (4) SOS screen said "You are offline" when it needed a sign-in (honest wording). After the fixes the emulator ran several 2-minute token cycles with kills/relaunches: one session, rotated each time, never reuse-detected |

### 8.5 Limitations (honest)
- Real phone: NOT VERIFIED; a 25-hour idle test on the Galaxy A23 is the remaining acceptance check.
- The 24 h access token itself is still unencrypted in DataStore (refresh secret is encrypted).
- An SOS started by a sign-in that was later signed out (not handed over) still needs the password to end from a new
  sign-in (10A.4 rule); an "I'm safe" kept on the phone for it is dropped on 403, and the SOS screen then shows the SOS
  from the server so it can be ended with the password.
- Home shows an SOS only from what the phone holds; an SOS the server has but the phone forgot (for example after an
  account switch) appears when the SOS screen is opened.
- Points collected before a process death are still lost (memory only, unchanged).
- Refresh failures are not rate-limited (256-bit secrets cannot be guessed); a single-instance server.

### 8.6 Totals (final run on the final code)
Backend `mvn test` **501 / 0 failures / 2 skipped** (482 before C1); Android `testDebugUnitTest` **561 / 0 failures**
(527 before); `assembleDebug` OK;
`lintDebug` **0 errors / 85 warnings** (unchanged). Next migration **V20**.

### 8.7 Viva notes
- *Why not just make the access token last 90 days?* A bearer token that long cannot be rotated or noticed when copied;
  a refresh secret is used rarely, rotated every time, and a copy shows up as reuse.
- *What happens if the phone loses the answer of a refresh?* Within 60 s the old secret still works once; later it counts
  as reuse and the session ends ("sign in to sync"), never deleting local safety data.
- *Why does an expired token not sign the person out any more?* Expiry is a fact about a credential, not a decision to end
  access; signing out destroyed the SOS that the person might need right then.