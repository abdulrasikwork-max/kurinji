# Phase 17B plan — account creation, phone OTP, A23 connectivity (awaiting approval)

Status: **approved as written and implemented 2026-10-27** (results: `PHASE17B.md`). Written 2026-10-27 after inspecting the Android auth screens, `AuthService`,
`EmailVerificationService`, `SecurityConfig`, the nav graph and the dev database.

## 1. Authentication audit — what is wrong today

| # | Problem | Root cause | Evidence |
|---|---|---|---|
| A1 | **A new account cannot be used** | V25: sign-up creates an UNVERIFIED account and no session; the e-mail is only sent when `MAIL_HOST`/`MAIL_FROM` are set, and they are not. Login then answers 403 `EMAIL_NOT_VERIFIED`; "resend" sends nothing. The person is stuck with no way forward | `backend/.env` has no `MAIL_*`; 3 accounts created 2026-10-26/27 are still unverified |
| A2 | Even with mail working, the link opens `PUBLIC_BASE_URL/verify-email`, which a phone cannot reach on a local server | design of V25 (HTTPS domain not available) | `EmailVerificationService.send` |
| A3 | After sign-up nothing leads to a profile/handle; the first post is refused | profile setup only reachable from You | fixed in 17A as a recovery path; not part of onboarding |
| A4 | Interrupted sign-up = unusable account until a clean-up job deletes it after 7 days | no way to resume | `EmailVerificationService.cleanUp` |
| A5 | Register maps every 409 to "email already used" and every 400 to one generic message | `RemoteAuthRepository.errorForHttpCode` | fine-grained server words lost |
| A6 | A physical phone needs `adb reverse` or a LAN URL | 17A added the LAN option; not tested on the A23 | — |

Working and kept: login rate limits, timing-equalised login, device sessions + refresh (C1), logout ends the server
session, splash routing by saved session, account switch clean-up, revocation on password change.

## 2. Fast2SMS — suitable, with conditions

Researched 2026-10-27 (docs.fast2sms.com, error code list, OTP page):
- **OTP route** `POST https://www.fast2sms.com/dev/bulkV2` with header `authorization: <API key>`, `route=otp`,
  `variables_values=<numeric code>`, `numbers=<10-digit Indian number>`. Fixed text "Your OTP: 123456". Our server
  generates and checks the code; Fast2SMS only delivers it (no state at the provider, provider-neutral).
- **Conditions (the user's account, not code):** a single top-up of at least ₹100 before any API use (error 999); KYC
  completed before the OTP route works (error 996; some developers report "website verification" as the wording);
  Indian numbers only; sending many SMS to the same number is blocked as spam (995) — our 60 s resend cooldown and send
  caps stay below that. The exact per-SMS price is not stated in text on their site (₹100 ≈ a few hundred OTPs at
  typical rates — to be confirmed in the dashboard).
- **Weakness:** the message cannot say "Kurinji" (fixed template) without DLT registration; acceptable for a pilot.
- Verdict: **suitable**. Implemented behind `OtpSender`; **off until you add `FAST2SMS_API_KEY` and set
  `OTP_PROVIDER=FAST2SMS`**. Default = a local development sink (a file on the PC, never sent anywhere).

## 3. New flow (decision D-1: e-mail + password stays the sign-in; the phone is verified)

```
Sign up (name, e-mail, password, date of birth 18+, phone) ─► account PENDING, OTP sent
   └► "Enter the code" (6 digits, 5 min, 5 tries, resend after 60 s, change number)
        └► verified ─► signed in at once ─► "Choose your handle" (profile) ─► Discover
Log in ─► phone verified? ─ yes ─► signed in
                         └─ no (interrupted sign-up, or an account from before 17B) ─► same code screen
                              (an account with no phone first enters one)
```

### Backend (migration **V26**)
- `users.phone_e164` (`+91XXXXXXXXXX`, binary, CHECK pattern), `users.phone_verified_at`; generated
  `verified_phone` + UNIQUE ⇒ one verified account per number (pending sign-ups may race; the loser gets 409 after
  proving the code).
- `phone_challenges`: one per account; `token_hash` (SHA-256 of a 256-bit challenge token held by the app), the number
  being verified, `code_hash` = HMAC-SHA256(server key, challenge|code) (never the code), `code_expires_at` (5 min),
  `attempts` (max 5, then the code is dead: resend), `sends` (max 5 per challenge), `last_sent_at`, `expires_at`
  (30 min). No IP, no message text.
- Endpoints (public, limited per address and per number in memory): `POST /api/auth/register` → 201
  `{status: PHONE_VERIFICATION_REQUIRED, challenge, phoneHint "••••••3210", resendInSeconds, codeExpiresInSeconds}`;
  `POST /api/auth/phone/start {email, password, phone?}` (resume / older accounts; wrong password = the generic 401,
  counted like a sign-in); `POST /api/auth/phone/verify {challenge, code}` → 200 session (same shape as login), or 400
  `OTP_INVALID` (with tries left) / `OTP_EXPIRED`, 409 `PHONE_IN_USE`, 429; `POST /api/auth/phone/resend {challenge}`;
  `POST /api/auth/phone/change {challenge, phone}` (only before verification). Login of an unverified phone → 403
  `PHONE_NOT_VERIFIED` (only after the right password).
- `OtpSender` interface; `DevFileOtpSender` (default; writes masked number + code to a git-ignored file, WARN at start);
  `Fast2SmsOtpSender` (java.net.http, key from env, never logs the code or the number, 5 s timeout, provider failures
  logged by status code only). Codes from `SecureRandom`, compared in constant time.
- **E-mail verification leaves the active flow:** register no longer issues links; the resend endpoint is removed;
  `/verify-email` stays only for links already sent (harmless). "Unverified" clean-up now means "no verified phone and
  no verified e-mail" (otherwise every new phone-verified account would be deleted after 7 days).
- Existing accounts: sessions keep working (an SOS is never cut off); **at their next sign-in** they add and verify a
  phone (D-1). The 3 stuck accounts can finish by signing in.

### Android
- Register: phone field (+91), clearer per-field server errors; on success → new `PhoneVerifyFragment`.
- `PhoneVerifyFragment` + ViewModel: code entry (SMS autofill hint), countdown, resend, change number, wrong/expired/
  too-many states, back = back to sign-in with a note that the account waits; nothing stored on the phone (the challenge
  lives in memory; an interrupted sign-up resumes by signing in).
- Login: `PHONE_NOT_VERIFIED` → phone/start → the same screen; e-mail-verification UI and strings removed.
- After the first verification: profile/handle screen (`firstRun`), then Discover.

### Tests and checks
Backend: challenge lifecycle (new, wrong ×5, expired, resend cooldown/caps, change number, reuse, race on the same
number, older account at sign-in, clean-up rule), `Fast2SmsOtpSender` against a local stub HTTP server, no code in
logs. Android: ViewModels for register/verify/login states. Live API on MySQL (V26 dry run first, backup). Emulator and
**A23**: new account, existing account, wrong code, expired code (short expiry via config), resend, interrupted sign-up,
logout → login, restart during/after sign-up; plus posting and one location check (Places near me) on the A23.

## 4. Needed from you
- **Connect the Samsung A23 by USB with USB debugging on** (Settings → Developer options). Same Wi-Fi as the PC if we
  test without the cable.
- No Fast2SMS credentials now (dev sink only).
