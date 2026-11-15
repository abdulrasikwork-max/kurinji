# Phase N2: phone-only sign-in, real name, no password

Status: **approved by the owner on 2026-11-15; in progress** (results per unit under "As built"). Direction:
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
