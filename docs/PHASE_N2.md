# Phase N2: phone-only sign-in, real name, no password

Status: **approved by the owner on 2026-11-15; complete 2026-11-16** (implemented, compiled, tested, manually verified on the emulator; no real phone; results per unit under "As built"). Direction:
`IRL_DIRECTION.md` §2.5 and decisions N-3, N-5, N-7. New decisions of this phase: `DECISIONS.md` N-21 to N-26.

## What was there before N2
- Sign-up: name, e-mail, phone, date of birth, password; the account waited (unactivated) for a texted code (17B).
  Sign-in: e-mail + password, and a verified phone.
- The password was used by six backend parts: sign-in (`AuthService`, timing-equalised BCrypt), password change
  (`AccountService`), deletion request and cancel (`PasswordGuard`), ending an SOS from another sign-in
  (`EmergencyService` via `PasswordGuard`), the unusable hash of an erased account (`AccountEraser`), the BCrypt bean
  (`SecurityConfig`). The e-mail: sign-in key, the sign-in limits (`AuthThrottle`), the admin role change, `UserResponse`.
- `phone_challenges` (V26) was keyed by account, so a code could only go to a number that already had an account.
- Two names: `users.name` (account) and `profiles.display_name` (a second, free name).
- Development database before N2 (read-only count, 2026-11-15): 204 accounts, 31 with a verified phone, 173 without
  one; 24 profiles; 68 device sessions.

## The design (approved)
1. `POST /api/auth/phone/start {phone}`: always the same answer and a code is always sent, account or not (no probing
   which numbers are registered). Limits: per address, per number per day, cooldown, per challenge.
2. `POST /api/auth/phone/verify {challenge, code, previousRefreshToken?}`: the number's account → `SIGNED_IN` + session
   (the Phase 11 hand-over of a running SOS kept); no account → `SIGN_UP_NEEDED` + a ticket (256 bits, SHA-256 stored,
   30 min, one use, tied to the number).
3. `POST /api/auth/sign-up {ticket, name, dateOfBirth}`: 18+ (`AgeGate`, never stored, D6) and the real-name rule
   (2–60 characters, letters of any script with their marks, spaces, `. ' -`; no digits or emoji) → account with the
   number verified from the first moment, signed in.
4. Then the profile form (handle) without its own name field, then Home.
- `resend` and `change` (a typo) stay. Removed: `/register`, `/login`, `PUT /me/password`.
- Sensitive actions confirm with a fresh code (N-7): `POST /api/users/me/confirmation` texts a code to the person's own
  number; deletion request, deletion cancel, ending an SOS from another sign-in and the new "Sign out all other phones"
  send `{challenge, code}`. `CodeGuard` replaces `PasswordGuard` with the same rules (wrong = 409, never 401; 5 failures
  in 15 min = 429). Honest limit: the code goes to the same SIM as sign-in; it protects against a stolen session, not
  against someone holding the SIM.
- Admin role change by verified phone number; `UserResponse` loses `email`, gains a masked number; BCrypt leaves.

## Owner's decisions on the open questions (2026-11-15)
1. The 173 development accounts without a verified phone stay in the database and become unusable (no session, no
   sign-in); they are not erased now (N-22).
2. The separate profile display name is dropped; the real name is shown everywhere (N-23).
3. The real name can be edited in N2; it is locked after the ID check of N3 (N-24).
4. Changing the phone number is deferred to N3, where an admin re-links it after an ID check (N-25).
5. "Sign out all other phones" is included (N-26).

## Migration V33 (development only; the pilot stays on V30)
`phone_codes` (purpose SIGN_IN / CONFIRM, number, `user_id` only for CONFIRM, token / code / ticket hashes, counters,
times; unique (purpose, number)) replaces `phone_challenges`; `email_verification_tokens` dropped; `users` loses
`email`, `email_verified_at`, `password_hash` (their CHECK and indexes first); `profiles` loses `display_name` (its
CHECK first); index `idx_users_phone`. Nothing deleted otherwise. Backup, read-only pre-check, dry run on a scratch copy,
apply, real-MySQL tests.

## Units (one local commit each)
| Unit | Content |
|---|---|
| B1 | Backend sign-in: `phone_codes`, start / verify / sign-up, register and login removed, sessions require a verified number, V33 file, `TestAccounts` behind the same signatures |
| B2 | Backend: `CodeGuard` and confirmation endpoint; deletion, cancel, SOS end, sign-out-others with a code; password change removed; role change by number; BCrypt and e-mail removed; one name (profile display name dropped); `AccountEraser` |
| B3 | V33 applied on the development MySQL |
| A1 | Android sign-in flow, session and user model |
| A2 | Android confirmation sheet, Account screen, SOS end with a code, texts |
| C | Documents, full runs on both sides, manual emulator walk |

## Not in N2
Changing the phone number (N3), the college ID check and the name lock (N3), bans by number (N3 plugs into
`phone/start`), moments and the new profile (N4), automatic SMS removal (N6), push (N7), any deploy to the pilot.

## As built

### B1 backend: phone sign-in (2026-11-15)
- New: `auth/phone/PhoneCode` + `PhoneCodeRepository` (V33 table `phone_codes`), `PhoneCodeService` (replaces
  `PhoneVerificationService`; the 17B code rules are unchanged: 6 digits, HMAC over purpose | number | code, 5 tries,
  cooldown, caps per challenge and per number per day, wrong codes per address, SMS after commit with undo; plus the
  sign-up ticket and a whole-attempt retry when two starts for one number collide), `user/service/RealNamePolicy`,
  `POST /api/auth/sign-up`, the `VerifyResponse` (`SIGNED_IN` + session or `SIGN_UP_NEEDED` + ticket).
- `AuthService`: start / verify / sign-up / refresh / sign-out only. `AuthController`: `/register` and `/login` gone.
  `AuthThrottle` keeps only new accounts per address (the password limits went with the password; `app.auth.login-*`
  removed). `app.otp.unverified-retention` replaced by `app.otp.ticket-lifetime` (30 min).
- Sessions: `ActiveAccountCheck` and the refresh require a verified number (N-22: accounts without one keep no
  session, `account_unavailable`). `User.isActivated` and `email_verified_at` gone; a new account is made only with a
  verified number (`new User(name, phone, verifiedAt)`); `email` and `password_hash` are nullable until B2 removes them.
- Removed: `RegisterRequest`, `LoginRequest`, `PhoneChallenge*`, `EmailVerificationToken*`, `InvalidCredentialsException`,
  `EmailAlreadyUsedException`; the eraser's statements for the two dropped tables (it now deletes `phone_codes` of the
  person and of their number).
- V33 written (not applied anywhere yet).
- Tests: new `PhoneSignInApiIntegrationTest` (19) and `RealNamePolicyTest` (5); `AuthApiIntegrationTest` keeps its token
  checks (8); `AuthThrottleApiIntegrationTest` now covers sign-ups per address, wrong codes per address and texts per
  number (3); the real-address tests count failed code checks; the session, continuity, age and role-claim tests sign
  in with a code (`TestAccounts.signIn`, `seedTicket`). Transitional: `TestAccounts` still writes the old e-mail and
  password columns for the account tests B2 rewrites.
- Result: backend **492 run, 0 failures, 0 errors, 2 skipped** (the opt-in real-MySQL class; 496 before N2: the
  password test cases left, the sign-in cases came in). `mvn compile` clean. Not run against MySQL (V33 is applied in B3).

### B2 backend: codes instead of the password, one name (2026-11-15)
- New: `account/service/CodeGuard` (replaces `PasswordGuard`, same rules: wrong = 409, never 401; 5 wrong codes per
  15 min per account for all confirmed actions together = 429; `app.account.code-failures` / `code-failure-window`
  replace `password-failures` / `password-failure-window`), `PhoneCodeService.startConfirmation` / `checkConfirmation`
  (purpose CONFIRM, the account's own number, one use; a challenge of another account or of a sign-in is simply
  "not right"; the check runs in its own transaction so a wrong try stays counted when the action is refused).
- Endpoints: `POST /api/users/me/confirmation` (texts the code), `POST /api/users/me/sessions/sign-out-others`
  (N-26; `{challenge, code}`; new credentials stamp, every other session ends with the new reason
  `OTHERS_SIGNED_OUT`, refused while an SOS or a Safe Walk runs, the caller keeps its sign-in), `PUT /api/users/me/name`
  (N-24, RealNamePolicy); deletion request and cancel take `{challenge, code}`; ending an SOS from another sign-in takes
  `{challenge, code, endedAt?}` (403 without, 409 wrong). `PUT /api/users/me/password` is gone (404).
- Every refusal of an action is checked before the code, so a refused action does not use the code up.
- `users`: `email` and `password_hash` gone from the entity; `erase()` keeps no number and no secret; BCrypt and the
  `PasswordEncoder` bean are gone; `MailProperties` (V25 leftover) removed. `UserResponse.email` replaced by
  `phoneHint`. Admin role change takes `{phone, role}` (any common way of typing the number; verified accounts only).
- One name (N-23): `profiles.display_name` dropped from the entity; `Profile.displayName` is read from `users.name`
  with the row (`@Formula`); `PUT /api/profiles/me` no longer takes a name (a sent one is ignored); the API field
  `displayName` stays, so the profile, people lists and the moderators' person view show the real name.
- Found and fixed on the way: opening a code inside a caller's read-only transaction did not save a rotated
  challenge token (the new token was then "not right"); opening a code now always runs in its own transaction.
- V33 extended: the session-reason CHECK gets `OTHERS_SIGNED_OUT` (`PASSWORD_CHANGED` stays for old rows).
- Tests: `AccountLifecycleApiIntegrationTest` rewritten for codes (14: sign-out-others revocation, shared limit, one-use
  and foreign codes, the confirmation endpoint, refusal during an SOS / walk, name change, the password endpoint gone,
  deletion refusals / grace / cancel / erase / postponement / anonymisation); `SessionContinuityApiIntegrationTest`
  (SOS ended elsewhere with a code), `DeviceSessionApiIntegrationTest` (sign-out-others, deletion), role changes by
  number, profile name = real name; `TestAccounts.confirmation(...)`; the transitional e-mail / password helper gone.
- Result: backend **494 run, 0 failures, 0 errors, 2 skipped** (clean build).

### B3 Migration V33 (2026-11-15, DEVELOPMENT MySQL 8.0.46 only; the pilot is NOT migrated)
- **Backup first:** `C:\Users\ABDUL RAFIQ\Desktop\SafeSphere-Phase3\db-backups\safesphere-dev-before-v33-20261115.sql`
  (`mysqldump --single-transaction`, 30 tables, 232 860 bytes; outside the repository; plain, same disk).
- **Pre-check (read-only):** schema at V32, 30 tables; exactly the CHECKs and indexes V33 drops exist
  (`ck_users_erased_email`, `ck_users_email_verified_after_created`, `ck_profiles_display_name`,
  `idx_users_email_verified_created`, `uk_users_email`); no other CHECK names a dropped column; no foreign key points
  at `phone_challenges` or `email_verification_tokens`; session reasons in use: `SIGNED_OUT` and none. Data: 204
  accounts (31 with a verified number), 24 profiles, 68 sessions, 1 phone challenge, 0 e-mail tokens.
- **Dry run on scratch copies** (`zz_v33_*`, dropped afterwards, table count back to 30): the users, profiles and
  sessions statements on copies with all their rows (204 / 24 / 68 kept; users 17 → 14 columns, profiles 15 → 14);
  every existing user row satisfies the remaining CHECKs; the new session reason accepted, a bogus one refused.
  `phone_codes` copy: 3 rows accepted (sign-in with code, confirmation with account, sign-up ticket) and 12 refused
  (duplicate number + purpose, unknown and lower-case purpose, a non-Indian number, an upper-case hash, a code without
  expiry or send time, a ticket on a confirmation row or next to a code, 21 attempts, expiry before creation, an
  unknown account); deleting an account removes its confirmation rows (cascade).
- **Applied** by starting the new jar once (its V33 compared byte for byte with the source): "Successfully validated
  33 migrations", "now at version v33", "Started". Afterwards 29 tables, `phone_codes` empty, `users` without
  `email` / `email_verified_at` / `password_hash`, `profiles` without `display_name`, data counts unchanged.
- **Entities against the migrated schema:** all 198 explicitly named entity columns exist in MySQL.
- **API on real MySQL (curl):** start → code from the development code file → `SIGN_UP_NEEDED` → sign-up 201 → `/me`
  (name, masked number, age confirmed) → a wrong confirmation code 409 → sign-out-others 200 → the old token 401, the
  new one 200 → a Tamil name change 200 (stored exactly, 11 characters) → `/api/auth/login` 401. Two development test
  accounts ("Mysql Check") were made on the way.
- **Real-MySQL test:** `MySqlTimestampStorageTest` with `SAFESPHERE_MYSQL_TESTS=true`: 2 run, 0 failures.
- An older jar (V32 or before) now refuses to start against the development database. The pilot stays on V30.

### A1 Android: the sign-in flow (2026-11-15)
- Screens: the first screen (`loginFragment`, kept as the destination id the rest of the app opens) asks for the
  mobile number only ("Send code"; checked by `PhonePolicy` first); the code screen (`phoneVerifyFragment`) is kept
  and now has three outcomes: a profile to make, Home, or (a number without an account) "About you"
  (`registerFragment`: the real name, `RealNamePolicy.kt`, the server's rule, and the date of birth, 18+, stored
  nowhere), which creates the account and opens the profile form. Back from "About you" goes to the number screen
  (the code is used up); a used or ended ticket says so and offers "Enter your number again".
- Removed: the e-mail and password fields, "Forgot password?" (fragment, view model, layout, its nav destination),
  "Create an account" (sign-in and sign-up are one flow), the password and confirmation checks in `FormValidator`.
- Domain / data: `AuthRepository` = `startSignIn`, `verifyCode` (`SignInResult.SignedIn` / `SignUpNeeded`),
  `signUp`, `resendCode`, `changePhone`, `refreshProfile`, `logout`; `PendingVerification` also holds the sign-up
  ticket (memory only); `AuthUser.email` became `phoneHint`; `AuthError` follows the server's codes (`TICKET_INVALID`,
  `ACCOUNT_UNAVAILABLE` new; the password and e-mail ones gone). The DTOs fail closed (no token, no ticket, an unknown
  status = an unreadable answer).
- "Sign in to sync" (Phase 11 C1): the phone cannot know the account before the code is checked, so it sends its old
  refresh token when the number's last 4 digits match its session; the server ignores another account's token.
- Session storage: the masked number replaces the e-mail; a session saved before N2 (e-mail, no number) stays
  readable and signed in (an SOS may run), and its e-mail is removed at the next write.
- One name (N-23): the profile form no longer has "Name on your profile"; the request no longer sends one.
  "Settings and more" shows the name and the masked number instead of the e-mail.
- Tests: `LoginViewModelTest`, `RegisterViewModelTest`, `RemoteAuthRepositoryTest` rewritten; a sign-up case in
  `PhoneVerifyViewModelTest`; new `RealNamePolicyTest` (same cases as the server); session refresh, sealed-token,
  navigation, form-validator and social tests adjusted; the e-mail literals in test users became masked numbers.
- Result: Android **760 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK; lint **0 errors, 91 warnings**.
  Not seen on a screen yet (unit C).

### A2 Android: codes instead of the password (2026-11-16)
- New, shared by the account and the SOS screens without either importing the other: `core/confirm/ConfirmationCodes`
  (`request()` = `POST /api/users/me/confirmation`), `CodeRequest` (challenge in memory only, masked number, sent or
  not), `Confirmation` (exactly 6 digits or nothing is sent; `toString` hides both), `NoConfirmationCodes`; and
  `core/ui/CodeConfirmDialog` (`showCodeConfirmDialog`: FLAG_SECURE, "We texted a code to +91 ••••••3210",
  confirm / "Send a new code" / cancel; the typed code is passed on once and the field cleared).
- Account and security: the password section is gone; new "Your name" (the real name, `RealNamePolicy` on the phone
  first, `PUT /api/users/me/name`, the session shows it at once; N-24) and "Your other phones" ("Sign out all other
  phones", N-26: the new token is saved inside `SessionEnder.swapping`, so the old token's 401 is not a sign-out; this
  phone keeps its refresh secret unless the answer brings one). Deletion and its cancel: first a code is texted
  (`AccountViewModel.onAskCode` → `codeNeeded`), then the typed code goes with the action (`onConfirm`).
  `AccountPolicy`'s password checks and `PasswordProblem` are gone.
- SOS ended on a phone that did not start it: a 403 now texts a code (`EmergencyViewModel.askForCode`, the account
  repository as `ConfirmationCodes`) and the screen shows the same dialog (`codeNeededFor` + `codeRequest` replace
  `passwordNeededFor`); `onEndWithCode`, `onEndCodeDismissed` (the SOS stays active), `onEndCodeResend`. A wrong code is
  shown and never asked again by itself; a code that cannot be sent is shown and the SOS stays active.
  `EndEmergencyRequestDto` = `{challenge, code, endedAt}` (nulls left out: the starting phone sends none).
- Texts: every password word left the visible strings (session-ended, "sign in to sync", deletion, SOS elsewhere,
  About, the privacy notice); the deletion explainer keeps its list of what is deleted. Comments that still described
  a password change now describe signing out the other phones.
- Fixed while compiling: `View.AUTOFILL_HINT_SMS_OTP` does not exist (the hint lives in `androidx.autofill`, not a
  dependency here); the dialog uses the literal `"smsOTPCode"`, the same as the sign-in code field.
- Tests: `AccountTest` rewritten (the confirmation rule and its hidden `toString`; asking for a code fails closed
  without a challenge; sign-out-others saves the new token (R8) and sends the code; a wrong code keeps the session, a
  401 asks for a sign-in without deleting anything; the name change; deletion and cancel send their codes; the view
  model texts a code first, then confirms each of the three actions; a code that cannot be sent opens no dialog);
  `SessionContinuityTest` (SOS ended elsewhere: code asked, dismissed, resent, wrong, right; a code that cannot be
  texted); `FakeEmergencyRepository` records the `Confirmation`. New guard in `HonestyTextsTest`: no visible text offers
  a password or an e-mail sign-in (the words appear only as "no password, no e-mail"; the Safety Circle contact's
  optional e-mail and the social nudge are allowed) and no source has a password field.
- Result: Android **766 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK; lint **0 errors, 89 warnings**
  (91 before; none in the A2 files). Not seen on a screen yet (unit C).

### C close: documents, full runs, manual emulator walk (2026-11-16)
- Documents: `API.md` ("Changed in N2" box, "Phase N2" section, appendix row), `DECISIONS.md` N-21 to N-26,
  `.claude/rules` (backend and android N2 notes, database V33 row, next V34), `CLAUDE.md` (status row, next V34, auth
  wording in sections 5, 6, 7, 9, 13; 38 087 characters), `STATUS.md`, `README.md` (commit `b7eb32c`).
- Full runs: backend **494 run, 0 failures, 0 errors, 2 skipped** (clean build; one stale surefire report of the
  removed `PhoneVerificationApiIntegrationTest` from 2026-11-15 was in `target/` and was deleted, it was not part of
  this run); Android **766 run, 0 failures**, `assembleDebug` OK, lint **0 errors, 89 warnings**. Python not touched.
- **Manual walk on the `irl_api33` emulator (API 33), development backend jar built from HEAD on the DEVELOPMENT MySQL
  (V33, "Successfully validated 33 migrations"), codes from the development code file, a `10.0.2.2` debug build
  (`local.properties` changed for one build and restored at once, compared byte for byte). Made-up numbers only. No
  real phone (the A23 was attached by USB and was not touched; every command named the emulator).**

| Check | Result |
|---|---|
| Upgrade over the N1 build with its saved N1 session (e-mail, no number) | still signed in, Home; "Settings and more" shows the name and `+91 ••••••1111` |
| Account and security | sections Your name / Your other phones / Delete account; no password field |
| Name with a digit ("Asha 2") | refused on the phone: "Use letters only (spaces and . ' - are fine)"; nothing sent |
| Valid name ("Nila Check") | saved; the server row shows it |
| Sign out all other phones: wrong code | nothing changed on the server (one session, no credentials stamp) |
| … right code (a second sign-in made with curl first) | the curl session ended `OTHERS_SIGNED_OUT`, its token 401 `credentials_changed`; the emulator stayed signed in |
| Asking for a code while one is still alive | the same code is re-used with a new challenge, not texted again (17B rule, as designed) |
| Deletion on the N1 test account (its last SOS ended 12 h before) | refused: 409 "Your account can be deleted 24 hours after your last emergency ended." (seen with curl; the toast is not readable on a FLAG_SECURE screen) |
| Log out → new number `…2222` → code | "About you" |
| "About you": name "Meera 7" + date of birth 2010 | both refused on the phone ("Use letters only…", "You must be 18 or older to use this app"); no account made |
| "About you": "Meera Test" + 2000 | account 399, number verified, age confirmed → profile form without a name field → Home |
| Log out → same number → code | straight to Home (no "About you") |
| Delete account (code) | banner "will be deleted on Nov 23, 2026 (asked on Nov 16, 2026)"; server dates set |
| Cancel deletion (code) | banner gone; server dates cleared |
| SOS started on another sign-in (curl), opened on the emulator | the screen shows the active SOS; "I'm safe" → "Started on another phone" with the code dialog |
| … Cancel / Send a new code / wrong code | SOS stays ACTIVE / the dialog opens again / "That code is not right, or it has ended. Ask for a new code." on the screen, not asked again |
| … right code | "Marked as safe", ended-elsewhere text; server: RESOLVED, `endedElsewhere: true` |
| SOS started on this phone, "False alarm" | ended with no code (no code texted), `endedElsewhere: false` |
| SOS countdown → CANCEL | idle again, nothing recorded |
| Quick Settings tile, shortcut (long-press the icon → "Start SOS (5 s to cancel)") | each opened the running countdown; CANCEL at 3 → nothing recorded |
| Backend log | only the two expected start-up notices; no error; no code or password logged |

- Development database afterwards: new test account 399 ("Meera Test", made-up number ending 2222) with 4 SOS
  records (117 ended elsewhere; 118, 119, 120 false alarms: 119 and 120 started because the countdown ran out while
  the screen was being read); the N1 test account 396 renamed "Nila Check" with one more ended session. Backend and
  emulator stopped.
- **Observed, not changed (no N2 regression):**
  1. The tile or shortcut opens the SOS screen WITHOUT the countdown when IRL was left on the idle SOS screen
     (`MainActivity.onNewIntent` starts it only from screens where `NavigationRules.opensSosFrom` is true; Phase 13F
     logic). One more tap ("Start SOS") is needed then. A note for N6.
  2. On "About you" a name error stays visible after the name is corrected until the next "Create account" tap (the
     date error clears on change). Cosmetic.
  3. "About you" says "You can correct it later in Account" while the screen is called "Account and security".
- Not seen: the toasts on the Account screen (FLAG_SECURE blocks screenshots; the outcomes were checked on the server),
  dark mode, large text, TalkBack, a real phone.
