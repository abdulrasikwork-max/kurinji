# Phase 10A: foundation (honesty, account security, coercion-aware deletion): results

**Implemented 2026-10-21/22** autonomously under the user's full-autonomy brief of 2026-10-21. Design:
`docs/PHASE10_DESIGN.md` (§30 10A.1–10A.3); every deviation from it is in §4 with its reason. Phase 9 is frozen and
was not reopened (one Phase 7 test expectation changed, §5.1). RISE "Proof" (10B) is **not** started.

Status vocabulary (`CLAUDE.md` §3): IMPLEMENTED · VERIFIED · PARTIALLY VERIFIED · NOT VERIFIED · DEFERRED · FUTURE.

| Part | Status |
|---|---|
| 10A.1 honesty, identity and language pass (Android) | IMPLEMENTED, tested, **emulator VERIFIED** (API 37) for Login, Home, Safety Center, Profile, Settings; onboarding pages, TalkBack, dark mode NOT VERIFIED |
| 10A.2 account security (V16, `ActiveAccountCheck`, password change, revocation) | IMPLEMENTED, tested, **VERIFIED on MySQL** (live 23/23) and **on the emulator** (change from the phone revoked another session) |
| 10A.3 coercion-aware deletion (request, grace, cancel, job, files first, anonymisation, local wipe) | IMPLEMENTED, tested, **VERIFIED on MySQL** (real job, real cascades, real evidence file) and **on the emulator** (banner, cancel, "Account deleted" sign-out) |
| Real phone (Galaxy A23), TalkBack | NOT VERIFIED (10D) |

---

## 1. Part 1: SAFE audit before RISE

The question asked: *which specific weaknesses could undermine RISE?* RISE asks people to try things and promises
"SAFE makes trying possible", so anything that makes SAFE fail silently, mislead, or be used against its owner
undermines RISE. Checked: SOS, Safety Circle, duress PIN, Safe Walk / check-ins, location, escalation ladder, offline
SMS, context packet, 112 prompt, Evidence Vault, Nearby, safety intelligence, recovery after incidents, permissions,
offline / app-killed / battery, auth and account security, notifications, accessibility, wording.

| # | Finding (from reading the code) | Severity for RISE | Decision |
|---|---|---|---|
| A1 | A disabled or deleted account's token worked for up to 24 h on every endpoint except `/api/users/me` (only `UserService.getProfile` and `RoleAuthorization` looked at the account) | High: no way to cut off a stolen session | **Fixed in 10A** (`ActiveAccountCheck`) |
| A2 | No password change and no session revocation: someone who learnt the password (a coercive partner) kept access for ever | High (coercive control is the main threat model) | **Fixed in 10A** (password change + credentials stamp) |
| A3 | ANY 401 cleared the newest session, even a late 401 for an old token; `EmergencyLocationSharing` and `SafeWalkSession` stop on a cleared session. After a token swap a stale answer could sign the phone out in the middle of a walk | High once tokens can change | **Fixed in 10A** (`SessionStorage.clearIfToken`, `SessionEnder`) |
| A4 | A token that expires during an SOS signs the phone out and stops location sharing (24 h tokens, no refresh). An SOS started 23 h after sign-in loses its server sync after 1 h; SMS and the 112 prompt keep working on the phone | **High** | **DEFERRED** to a dedicated hardening step (§9): needs token renewal, which is a design change (renewal is acceptable now that revocation exists). Documented, not hidden |
| A5 | Anyone with the password can sign in elsewhere and cancel or resolve a person's ACTIVE SOS | Medium (coercion) | **DEFERRED** (multi-device policy; same step as A4). 10A at least makes the password changeable and refuses password change and deletion during an SOS |
| A6 | No password reset ("Forgot password" honestly says it is not available): whoever changes the password first owns the account; there is no e-mail channel | Medium | **DEFERRED** (needs an e-mail channel). The sign-out dialog now tells the person that someone else may know the password |
| A7 | No account deletion (Play policy, DPDP Act 2023) | High for trust | **Fixed in 10A** (coercion-aware) |
| A8 | Onboarding said "not available in this version yet"; 7 of 11 Settings rows "Coming soon" behind an "Available" badge; Profile "Coming soon" rows for screens that exist; Home timeline placeholder and a duplicate tile | High for trust ("honest app" is the product's claim) | **Fixed in 10A** |
| A9 | Evidence Vault and the recovery passphrase reachable only from SOS screens: people could not set a recovery passphrase before they needed it | Medium | **Fixed in 10A** (Safety Center, Profile, Settings) |
| A10 | The privacy text was a placeholder that missed Nearby, trust/moderation, intelligence, evaluation counts and deletion | Medium | **Fixed in 10A** (accurate notice, labelled not legal advice) |
| A11 | Duress PIN is a reserved trigger the server rejects; nothing in the app claims it | — | Unchanged, correct |
| A12 | Escalation ladder, offline SMS ("sent ≠ delivered"), context packet allow-lists, 112 dial prompt: wording and behaviour honest | — | Unchanged |
| A13 | OEM battery killers, START_NOT_STICKY service, recovery on next open (documented in `PLATFORM_CONSTRAINTS.md`) | Known | Unchanged |
| A14 | Session token not encrypted on the phone (W12) | Medium | DEFERRED (release hardening) |
| A15 | No text tells people to avoid places (now a test) | — | Guarded by `HonestyTextsTest` |
| A16 | TalkBack never verified on most screens | Medium | DEFERRED to 10D |

## 2. What changed

### 2.1 10A.1 Android honesty, identity and language
- **Identity:** "Safety that expands your world." on Login, Home, About, README (replaces "Safety, connected.").
- **Onboarding** rewritten around what the build does: going further prepared (Check the way, Safe Walk, "using support
  is a choice, never a weakness"); your people, your choice (cancellable SOS, SMS only if switched on, never contacts
  emergency services); yours to control (nobody can track you, coordinates never go to Nearby, evidence encrypted,
  password change and deletion).
- **Settings rebuilt:** only working rows: Account and security; Safety Circle and automatic SMS; Evidence Vault and
  recovery passphrase; Emergency History; Privacy notice; App permissions and Notifications (the phone's own pages,
  no new permission); About; Terms. No "Coming soon" row.
- **Safety Center:** new Evidence Vault entry; the warning-style "preview" notice became an info notice that states
  what SafeSphere does and does not do; "Safety Settings" became "Settings".
- **Home:** the Phase 1 "Your Safety Journey" timeline removed (`TimelineAdapter`, its layout and strings deleted);
  the duplicate "Check-in" tile removed (two tiles: Safe Walk, Safety Circle); a deletion banner (§2.3).
- **Profile:** Emergency History, Evidence Vault, "Your Nearby reports" (opens Nearby) and Account and security replace
  the three "Coming soon" rows; `core/ui/PreviewDialogs.kt` (the "coming later" dialog) deleted.
- **Language:** tab "Community" → "Nearby" (it shows places, not people); the area summary leads with safe places
  (`CommunityTexts.briefParts`, order only, every count still shown); Safety Circle = "people you choose to keep in
  the loop"; Safe Walk set-up adds "Using support is a choice, not a weakness."; logout dialog no longer says
  "preview session" and states what a sign-out keeps and removes.
- **Removed:** dead `feature/map` (README-only). **Privacy notice** rewritten (§7). **Terms** state plainly that this is a
  prototype and not an emergency service.

### 2.2 10A.2 Account security (backend + Android)
- **V16** `users.credentials_changed_at`, `deletion_requested_at`, `deletion_due_at`, `erased_at` + 6 CHECKs + index.
- **Credentials stamp:** once the password changes, tokens carry `cca` = the stamp (µs since the epoch of
  `credentials_changed_at`); `ActiveAccountCheck` (a validator inside the JWT decoder, after the standard checks so an
  expired token never reaches the database) requires exact equality, plus: account exists, not erased, enabled.
- **401 reason** in `WWW-Authenticate` (`credentials_changed` / `account_deleted` / `account_unavailable`) for such
  otherwise valid tokens only.
- **`PUT /api/users/me/password`**: current password, 8–72 chars / ≤ 72 bytes, different; returns a fresh token;
  refused during an ACTIVE SOS or Safe Walk; 5 wrong passwords / 15 min (shared with deletion) → 429.
- **Android:** `SessionEnder` (the one place that ends a session: only if the rejected token is still the saved one;
  ignores the old token's 401 while a password change is swapping tokens; publishes who and why),
  `SessionStorage.clearIfToken` (atomic in DataStore), `ApiCallHandler` and `RemoteAuthRepository.refreshProfile`
  use it; the session-expired dialog words itself after the reason; `feature/account` (API, `RemoteAccountRepository`
  that saves the new token inside the swap, `AccountPolicy`, `AccountViewModel`, `AccountFragment` FLAG_SECURE).

### 2.3 10A.3 Coercion-aware account deletion
- `POST /api/users/me/deletion` (password; refused during an emergency and for 24 h after) → 7-day grace;
  `POST /api/users/me/deletion/cancel` (password). Signing in does not cancel. `/me` and login carry the dates.
- **Banner** on Home (amber, warning icon, opens Account) and on the Account screen for the whole grace period, with
  the date it was asked: a request made by someone else who knows the password, or one that was forced, is visible.
- **`AccountDeletionJob`** (every 15 min) → `AccountEraser`: (1) locked check, postpone if an emergency is ACTIVE or
  ended < 24 h ago; (2) delete the evidence ciphertext files (a failure stops and retries next run, so no row goes while
  its file is still on disk); (3) one transaction: re-check, `CommunityService.eraseContributor` (withdraws every
  contribution like a withdrawal, deletes signals left empty), owned rows children-first, then delete the account or
  anonymise it if `moderation_actions.actor_id` / `moderation_appeals.decided_by` refer to it; then the orphan sweep.
- **Drift guard:** `AccountEraserCoverageTest` reads every migration and fails if a new foreign key to `users` is not
  handled by the eraser.
- **Phone wipe:** a sign-out already removes the session, the active emergency/walk records, the Safety Circle copy and
  the Nearby outbox. On a 401 `account_deleted` the phone also removes what a sign-out deliberately keeps: the
  account's Evidence Vault items (files, keys in the index and its backup, custody anchors) and its recovery key
  (`AccountDataWiper`, `EvidenceVault.forgetAccount`, `RecoveryKeyStore.remove`). If the token had expired before the
  deletion, the Login screen offers "Remove its data from this phone" after the date (`PendingDeletionStore`, account id
  + date only).

## 3. Files

Backend: `db/migration/V16__account_lifecycle.sql`; `account/{controller/AccountController, dto/AccountDtos,
service/AccountService, AccountEraser, AccountDeletionJob}`; `config/AccountProperties`; `security/ActiveAccountCheck`;
changed `config/JwtConfig`, `security/JwtService`, `security/RestAuthenticationEntryPoint`, `user/entity/User`,
`user/dto/UserResponse`, `user/repository/UserRepository`, `emergency/repository/EmergencyEventRepository`,
`evidence/repository/EvidenceItemRepository`, `community/service/CommunityService` (`eraseContributor`),
`config/SchedulingConfig`, `application.properties`. Tests: `account/AccountLifecycleApiIntegrationTest` (12),
`account/AccountEraserCoverageTest` (1); `ModerationApiIntegrationTest` one expectation (§5.1).

Android: `core/network/SessionEnder`; `feature/account/{data, domain, session, ui}`; `fragment_account.xml`; changed
`ApiCallHandler`, `SessionStorage`, `DataStoreSessionStorage`, `AuthModels`, `AuthDtos`, `RemoteAuthRepository`,
`LoginViewModel/Fragment`, `SessionDialogs`, `AppContainer`, Home, Profile, Settings, Safety Center, Community texts,
`EvidenceVault`, `RecoveryKeyStore`, `nav_graph.xml`, `strings.xml`; deleted `TimelineAdapter.kt`,
`item_timeline_step.xml`, `PreviewDialogs.kt`, `feature/map`. Tests: `SessionEnderTest` (6), `AccountTest` (12),
`HonestyTextsTest` (7), `EvidenceVaultHardeningTest` +1.

## 4. Deviations from `PHASE10_DESIGN.md` (each deliberate)

| # | Design | Built | Reason |
|---|---|---|---|
| D1 | Revocation by `iat ≥ credentials_changed_at` | Token carries the stamp (`cca`); exact equality | `iat` has 1-second precision: a token issued in the same second as the change (normal in tests and on fast phones) would be refused, and an old one from that second accepted. Equality is exact and clock-independent |
| D2 | Wrong password → 401 | 409 `Password is incorrect` | The phone treats every 401 as "signed out"; a typo would log the person out. Same convention as evidence deletion |
| D3 | Password change during an SOS allowed; phone swaps tokens (R8) | **Refused (409) during an ACTIVE SOS or Safe Walk**; the token swap is still implemented and tested for the general case | Revocation creates a new attack: someone else who knows the password could change it and cut the person's running SOS / walk off the server. Refusal costs the person nothing in that moment |
| D4 | `DELETE /api/users/me/deletion` with a body, 404 when nothing pending | `POST /api/users/me/deletion/cancel`, idempotent 204 | DELETE bodies are dropped by some proxies/clients; idempotent is safe for offline re-sends |
| D5 | Moderators/admins anonymised | Anonymised **when the audit log names them as decider**, by data not by role | A moderator who decided nothing has no audit trail to keep; a demoted former moderator still has one |
| D6 | Deletion refused within 24 h of an emergency | Also: the deletion itself is **postponed** when an emergency happens during the grace period | Otherwise a deletion due during or just after an SOS would destroy its evidence on the spot |
| D7 | V16 = 3 columns, 2 CHECKs | + `deletion_requested_at`; CHECKs pair, order, erased⇒disabled, ⇒no pending, ⇒role USER, ⇒`erased-…@erased.invalid` | The banner shows when it was asked (a forced request is recognisable); the tombstone's anonymity is enforced by the database, not only by code |
| D8 | "The phone's local data is wiped" | Wiped on the server's `account_deleted` signal, else offered on Login after the date | The account may be cancelled or postponed; wiping at request time would destroy evidence of a living account; tokens often expire before day 7 |
| D9 | — | 401 `error_description` reasons | Needed for D8 and to tell the person "your password was changed" honestly |
| D10 | Merge the duplicate tile | Two tiles (Safe Walk, Safety Circle), grid of 2 | A third tile would be a placeholder |
| D11 | Bottom nav `Home · Safety · Nearby · Rise · Profile` | `Rise` not added | 10B; no empty tab |
| D12 | RISE line "You decide what's for you" | Not shown anywhere yet | No RISE feature exists; a line for a missing feature would be the dishonesty 10A removes |

## 5. Verification (actual results)

| Check | Result |
|---|---|
| `mvn test` | **466 tests, 0 failures, 0 errors, 2 skipped** (opt-in MySQL) (453 before) |
| `gradlew testDebugUnitTest` | **480 tests, 0 failures** (454 before) |
| `assembleDebug` | OK |
| `lintDebug` | **0 errors, 85 warnings** (87 before; none new: the removed placeholders took 2 away) |
| V16 dry run on MySQL 8.0 (scratch copy `p10_users`, dropped) | **13/13**: 3 valid rows accepted; 10 violations each rejected by the intended CHECK (pair ×2, order ×2, erased⇒disabled, ⇒no deletion, ⇒role ×2, ⇒e-mail, and an UPDATE) |
| V16 applied | Flyway "Successfully applied 1 migration … now at version v16"; `users` backed up first to the session scratchpad |
| Live API on MySQL (real defaults; deletion job every 10 s) | **23/23 PASS** (L1–L23: revocation, SOS refusals, 429, disabled account, request/sign-in/cancel, real job deleting rows + the evidence file, 401 `account_deleted`, e-mail reusable, moderator anonymised with the audit row kept). A first attempt stopped at 10/15 on a script fault (PowerShell sent a form content type on a bodyless POST → 415; nothing was wrong on the server); all its accounts were removed in `finally` |
| Emulator (Medium_Phone_API_37.0) | **PASS:** Login and Home show the new identity; Home: 2 tiles, no timeline, "Nearby" tab; Safety Center with Evidence Vault; Profile rows; Settings rows (both phone-settings rows open the system pages); Account screen is FLAG_SECURE (screenshots black); password change from the phone → another session got 401 `credentials_changed`, phone stayed signed in; deletion request → dated banner on Account and Home (amber + icon) → banner opens Account → cancel with password → banner gone; after the server deleted the account the next request showed "Account deleted" and returned to Login |

**NOT VERIFIED:** onboarding pages on a device (strings tested only: showing them needs clearing app data, which
would have destroyed earlier phases' emulator test data); the Login "Remove its data" notice on a device (unit-tested);
`forgetAccount` with real evidence on a device (unit-tested with a real file index); TalkBack; dark mode; 320 dp / 2.0×
text on the new screens; real phone.

### 5.1 Changed expectation in a Phase 7 test
`ModerationApiIntegrationTest.aDisabledModerator_hasNoAccess` expected 403; the account check now refuses the disabled
account's token earlier, with 401, on every endpoint. Stricter; not a Phase 9 change.

### 5.2 State left behind
Dev DB at **v16**. All accounts created by the checks were deleted (live script: `finally`; the emulator account by the
deletion job itself). Backend stopped; emulator stopped; `adb reverse` removed; the 10A debug build is installed on the
emulator, signed out. `android/local.properties` unchanged.

## 6. Tests added (what each one proves)

- `AccountLifecycleApiIntegrationTest`: revocation of every older token incl. the one that made the change; 5×409 then
  429 shared by password and deletion, ends after the window; refusal during SOS and Safe Walk while the SOS keeps
  working; new-password validation; disabled token refused on 4 endpoints; a wrong stamp refused, missing token keeps
  plain `Bearer`; deletion refusals (ACTIVE, 1 h and 24 h after), 7-day date, idempotent request, sign-in does not
  cancel, SOS works during grace; cancel only with the password; job: not before the date, files + rows deleted, other
  person untouched, e-mail free; postponement by an emergency during grace; moderator anonymised with audit kept;
  community contribution withdrawn and its empty signal deleted.
- `AccountEraserCoverageTest`: every `REFERENCES users` in V1–V16 is deleted or deliberately kept.
- `SessionEnderTest`: stale-token 401 keeps the newer session; swap ignores the old token; reasons parsed; Phase 2
  behaviour without an Authorization header; same rule through `ApiCallHandler`.
- `AccountTest`: password rules (incl. 72 bytes), banner states, DTO, R8 token swap, 401 during the change ends the
  session, request/cancel update the session, ViewModel outcomes, wiper runs every part and keeps the marker on failure,
  never clears another account's marker, Login notice only after the date.
- `HonestyTextsTest`: onboarding words, no placeholders in Settings/Profile/Safety Center/Home, every Settings row has
  an action, the identity/language lines, **no string tells people to avoid places**, summary order, sign-out texts.
- `EvidenceVaultHardeningTest`: `forgetAccount` removes only that account's items, also from `index.json.bak`.

## 7. Security and privacy review

**Threats addressed**
- *Stolen token / lost phone:* a password change ends every other session on its next request (T10). *Disabled or
  deleted account:* refused at once (T11).
- *Coercive partner who knows the password:* can no longer keep silent access (the person can change the password);
  cannot cut a running SOS/walk off with a password change (D3); a forced or covert deletion request is visible for 7
  days on Home with the date asked, needs the password to cancel, is refused during and for 24 h after an emergency,
  and is postponed by any emergency in the grace period (T9).
- *Online guessing through the new endpoints:* 5 per 15 min per account, in memory (T17).
- *Oracle:* the 401 reason is only given to a holder of a valid, unexpired token signed by this server, i.e. the
  account's own session; it says nothing about any other account. Registration and login are unchanged (no new
  "account exists" signal).
- *Audit integrity:* moderator decisions survive erasure (anonymous tombstone, CHECK-enforced), so erasure cannot be used
  to escape accountability for moderation decisions.
- *Data minimisation:* the phone's deletion marker holds an account id and a date only; nothing is logged about the
  person (the job logs counts; exceptions by class name only).

**Residual risks (honest)**
- A4/A5/A6 above (token expiry during SOS, second session ending an SOS, no password reset) remain: DEFERRED, §9.
- Someone with the password and patience can request deletion and, if the person never opens the app for 7 days, the
  account goes. The banner cannot help a person who does not open the app. 7 days is a judgement (design §29.7).
- The tombstone keeps `created_at` and the row id; audit rows about decisions stay up to their 365-day retention.
- Files are deleted before rows; if an SOS starts in the seconds between, the account stays but the OLD evidence files
  are already gone (rows reported by the reconciler as missing files). Narrow window, documented.
- The phone cannot wipe a deleted account's data without either a still-valid token or the person's tap on Login.
- The limiter is per instance and resets on restart (as Phase 7).
- The session token is still unencrypted on the phone (W12).

**Privacy notice** (Settings → Privacy notice) now describes: who can see data, no tracking by others, account and
token, location, Safe Walk, movement checks, Safety Circle SMS, evidence, Nearby (cells, delay, no authors, private
counters, moderation codes and pseudonyms, 365-day log), Check the way, weekly counts, deletion and anonymisation, and
retention. It is labelled "not legal advice and not a final privacy policy".

## 8. A future "understanding / perspectives" layer: should it exist?

Asked: whether a layer where people share how they understood or approached something (perspectives, not winners)
should exist, in what form and when. **Decision: not in Phase 10; design-only; revisit after 10C.**
- **Why not now:** any shared text needs moderators (none exist; Phase 9 measured that the machinery is idle without
  them), invites harassment and identification, and the RISE design already gives the "people like me" effect through
  anonymous collective proof without text (design §8.5).
- **Only acceptable form, if ever:** structured, not free text (closed lists of "what helped", "what I would tell
  someone before their first time" chosen from vetted options); one-directional (no replies, likes, follower counts,
  DMs, profiles, popularity ordering; order random or by recency band); about the activity, never about a person or a
  group (no gender, no "them"); pre-moderated; aggregated above a minimum like collective proof; deletable by its author.
- **Gate (design §21):** ≥ 3 active moderators, pre-moderation, a written privacy review and the user's decision.
  Expected for a student deployment: not met, and that is acceptable. Recorded as FUTURE.

## 9. Recommended next step for SAFE (deferred findings)

A small dedicated **"session continuity" step before or alongside 10B**: (1) a token renewal endpoint used at SOS start
and whenever less than 12 h remain (A4; acceptable now that revocation exists); (2) decide what a second session may
do to an ACTIVE SOS (A5; e.g. only the phone that started it ends it, or ending from elsewhere needs the password and
is shown on the first phone); (3) token encryption on the phone (A14). Password reset (A6) waits for an e-mail channel.

## 10. Is SafeSphere becoming a coherent product, or accumulating features?

Honest answer: **it is becoming coherent, with one over-built middle that must not grow further.**
- *Coherent:* one promise now runs through every screen: safety the person controls that lets them go further. Every
  SAFE feature is something the person starts and can stop; nobody can monitor anyone; texts say only what is true; the
  account itself is now under the person's control (change, revoke, delete) with coercion in mind. 10A removed
  placeholders rather than adding features, which is what a coherent product needs at this point.
- *Accumulation risk:* Phases 6–9 (Nearby, trust, moderation, intelligence, evaluation) are a deep stack built for a
  community that does not exist yet; moderation and evaluation are idle without moderators and users. That is defensible
  as adversarial engineering for the viva, but adding more SAFE intelligence now would be accumulation.
- *Direction check before 10B:* RISE must stay a thin, phone-only layer that reuses SAFE as its "Prepare" step (Check
  the way, Safe Walk, Circle) and adds one mechanism (prediction vs. outcome). If 10B starts needing new server
  machinery beyond 10C's counters, stop and re-scope. No change of direction is needed today; §9 should come first.

## 11. Viva notes (10A)

- *What is the credentials stamp?* A value stored with the account and copied into each token; changing the password
  changes it, so every older token stops matching. Revocation without refresh tokens or a token list; exact equality
  avoids clock and rounding problems.
- *Why refuse a password change during an SOS?* Revocation lets a password change cut other sessions; someone who knows
  the password must not be able to cut the person's running emergency off the server.
- *Why 7 days and a banner?* Deletion is the one action an abuser most wants to force; the delay and the visible banner
  make a forced request recoverable, and the 24 h rule protects fresh evidence (same rule as evidence deletion).
- *Why anonymise moderators?* The audit log must keep who decided what; the V13 foreign keys deliberately have no
  cascade, and V16 CHECKs guarantee the kept row is anonymous and unusable.
- *How do you make sure deletion forgets nothing?* A test reads every migration and fails if a table that refers to a
  person is not handled.
- *What does the phone delete?* A sign-out keeps evidence (so an accidental log-out loses nothing); only the server's
  "account deleted" answer, or the person's tap after the date, removes it.
