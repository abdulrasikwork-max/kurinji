# Phase 17B — account creation, phone verification (OTP), A23 (2026-10-27)

**Implemented, compiled, tested, live-verified on MySQL, verified on the Samsung Galaxy A23 and the emulator (API 37).**
Plan (approved as written): `PHASE17B_PLAN.md`. Migration **V26** (next: **V27**). No new dependency; one removed
(`spring-boot-starter-mail`).

## 1. Problems found and root causes

| # | Problem | Root cause | Fix |
|---|---|---|---|
| A1 | A new account could never be used | V25 sign-up made an UNVERIFIED account and no session; with no `MAIL_*` configured the link was never sent; login answered `EMAIL_NOT_VERIFIED`; "resend" sent nothing. 3 such stuck accounts were in the dev DB | Phone OTP replaces e-mail links (below) |
| A2 | The e-mailed link pointed at a server address a phone could not open | V25 design (no HTTPS domain) | Gone with A1 |
| A3 | After sign-up nothing led to a handle; the first post was refused | profile setup only under You | First-run "Your profile" right after verification (save or skip → Discover) |
| A4 | An interrupted sign-up left an unusable account | no resume path | Signing in resumes the same verification (new token, the code already sent still works, no second SMS) |
| A5 | Register mapped every 409 to "email used", every 400 to one generic text | `RemoteAuthRepository` used only HTTP status | Stable server codes decide (`ApiError.code`), case-dependent words (tries left, wait) shown |
| L1 | **Found on the A23:** "Places near me" failed indoors although precise location was allowed and on | a HIGH_ACCURACY one-time request waits for GPS and returns nothing; a 300 m Wi-Fi/cell fix 48 s old was there | `currentFix(…, areaOnly)`: area readings use balanced accuracy; the fused provider falls back to the platform's last fix within the allowed age; the picker falls back from a precise to an area fix (adding a place still needs ≤ 100 m) |
| U1 | **Found on the A23:** the keyboard covered "That code is not right. 4 tries left." | message below the buttons | each new message is scrolled into view |

## 2. What was built

**Backend** (`auth/phone`): `PhoneNumbers` (India, +91 E.164, hint = last 4 digits), `OtpProperties` (`app.otp.*`),
`OtpSender` + `DevFileOtpSender` (default; writes masked number + code to a local file, never sends) +
`Fast2SmsOtpSender` (`POST /dev/bulkV2`, `route=otp`, key from `FAST2SMS_API_KEY`, never logs key/code/number, refusals
reported by Fast2SMS status code only) + `OtpSenderConfig`, `PhoneChallenge` (+ repository), `PhoneVerificationService`,
`PhoneVerificationCleanupJob`; `PhoneVerificationException` (codes PHONE_NOT_VERIFIED, PHONE_INVALID, PHONE_REQUIRED,
CHALLENGE_INVALID, OTP_INVALID, OTP_EXPIRED, OTP_LOCKED, OTP_WAIT, PHONE_IN_USE, PHONE_ALREADY_VERIFIED); `AuthService`
register / login / `startPhoneVerification` / `verifyPhone`; `AuthController` + `SecurityConfig` (public
`/api/auth/phone/start|verify|resend|change`); `User.phoneE164/phoneVerifiedAt/isActivated`; `ActiveAccountCheck` and
refresh accept accounts activated by phone OR (before V26) e-mail; clean-up deletes only accounts with neither.
**Removed:** e-mail issuing/resend/verify endpoints and page, `EmailVerificationService`, mailer, `MailConfig`,
`EmailVerificationProperties`, `EmailNotVerifiedException`, `VerificationLinkInvalidException`, the mail starter.

**Rules:** 6-digit code from `SecureRandom`, valid 5 min, stored only as HMAC-SHA256(OTP_SECRET or a key derived from
JWT_SECRET, account id | code), compared in constant time; 5 wrong codes kill the code (row-locked counting); resend
after 60 s, ≤ 5 codes per challenge, ≤ 8 per number per day, per-address limits (in memory); challenge token 256-bit,
SHA-256 stored, 30 min; the SMS is sent after the commit and a provider refusal is undone (no count, no wait); one
verified account per number (check + MySQL unique key on the generated `verified_phone`). Accounts from before 17B keep
their sessions (an SOS is never cut off) and add + verify a number at their next sign-in (D-1).

**Android:** `PhoneChallenge`, `PendingVerification` (memory only), `PhonePolicy`, `AuthResult.Failure.message`,
`AuthError` phone codes; `RemoteAuthRepository` (start / verify / resend / change, code-based mapping);
`RegisterViewModel/Fragment` (+ mobile number, "+91" prefix, no "check your email"); `LoginViewModel/Fragment`
(PHONE_NOT_VERIFIED → start → code screen; V25 resend removed); new `PhoneVerifyFragment` + `PhoneVerifyViewModel`
(auto-send at 6 digits, countdown, resend, "Use another number", add a number, lost/ended state → sign in); first-run
`ProfileSetupFragment` (`firstRun`: save or skip → Discover); nav actions; `ResendStatus` and V25 strings removed;
location: `LocationProvider.currentFix(areaOnly)`, `FusedLocationProvider` fallback, `PlacePickerViewModel` and
`DiscoverViewModel` area readings.

## 3. Fast2SMS (researched 2026-10-27)

Suitable. The OTP route needs, on the account: **KYC** (else status 996) and **one top-up of at least Rs 100** (else
999); Indian numbers only; fixed text "Your OTP: 123456" from Fast2SMS's sender (no "Kurinji" without DLT); repeated
SMS to one number is blocked as spam (995) — our cooldown and caps stay below it. Per-SMS price not stated in text on
their site (check the dashboard). **Not enabled:** set `OTP_PROVIDER=FAST2SMS` and `FAST2SMS_API_KEY` in `backend/.env`
when ready. The adapter was tested only against a local stub; **no real SMS was sent**.

## 4. Results

- **Backend:** 755 / 0 (2 skipped), see §6. New: `PhoneVerificationApiIntegrationTest` (16),
  `PhoneNumbersTest` (3), `Fast2SmsOtpSenderTest` (5); V25 tests removed (`EmailVerificationApiIntegrationTest`,
  `MailConfigTest`); `ModelPlacesTest` now pins exactly two outbound HTTP clients (moderation service, SMS adapter).
- **Android:** 732 tests, 0 failures (17A: 719). New `PhoneVerifyViewModelTest` (11), rewritten Register/Login/Remote
  auth tests, picker area fallback test. Lint **0 errors / 101 warnings** (+2: `accessibilityHeading` on the new
  screen's headings, as on every screen).
- **V26 on MySQL:** backup `safesphere-before-V26.sql` (scratchpad, outside the repo); dry run on scratch copies of
  `users` (all 174 rows) **15/15 rejected + 7/7 accepted + cascade** (no erased account exists, so "a tombstone keeps
  no number" had no row to test); **applied** (Flyway v26, 0.24 s).
- **Live API on MySQL (dev-file sink) 20/20 PASS:** new account (no token, hint, hashed code, code not stored), sign-in
  before code 403, wrong code (tries left), resend at once 429 with seconds, right code → session, `/me`, MySQL verified,
  sign-out → sign-in, interrupted sign-up resumed without a second text, lost token refused, expired code (made old in
  MySQL), resend → new code works, older account keeps its session and adds a number, number in use 409, V25 endpoint
  gone, test accounts removed.
- **Samsung Galaxy A23 (Android 14, USB + `adb reverse`) PASS:** update over the owner's signed-in account kept the
  session; the 17A age screen opened by itself (the owner's account had no confirmed age; **the owner confirmed it
  themself**; Discover then loaded without banner); sign-out; **new account** through the real form → code screen with
  countdown → **wrong code** ("4 tries left") → app force-stopped → **sign-in resumed** the verification (no second text)
  → **Send a new code** → right code → **"Your profile"** → handle saved → Discover; **reinstall/restart** kept the
  session; **post** created and opened; **Places near me** (after the L1 fix) listed the area indoors in ~21 s;
  **log out → log in** without a code. Expired code and an older account were tested through the API, not on the phone.
- **Emulator (API 37) PASS:** new account → code → first-run profile → Back → Discover.

## 5. Not verified / limitations

- **No real SMS** (Fast2SMS not configured; no credentials); delivery time, DLT-free sender behaviour, and spam blocking
  995 are untested.
- The owner's own account on the A23 is signed out; signing in will ask for a mobile number and a code, which (with
  DEV_FILE) is written on the PC, not texted.
- "Places near me" indoors takes up to ~25 s (precise attempt first, then the area fallback).
- No SMS Retriever / autofill API (it would need Play services auth, a new dependency); the keyboard may still offer
  the code from the SMS (autofill hint `smsOTPCode`).
- India-only numbers; one verified account per number.
- TalkBack by ear not tested.

## 6. Backend full suite

**755 tests, 0 failures, 2 skipped** (fresh run after the last change; 17A had 756: the V25 e-mail tests were removed
and 25 phone-verification tests added).
