# Email ownership verification (V25, 2026-10-26)

**Why:** until V25 anyone could sign up with any e-mail address and use the account at once: the address was never
proven to belong to the person. Now a new account starts with an **unverified** e-mail and gets **no session at all**
until its owner opens a link sent to that address. This is an authentication fix, not a feature: no UI redesign, no
other behaviour changed.

## 1. Flow

```
Sign-up (app)  ──POST /api/auth/register──▶  account created, email_verified_at = NULL
                                              token = 256 random bits; only SHA-256(token) stored (one per account)
                                              e-mail queued AFTER commit → background send thread
Answer 201 {status: VERIFICATION_REQUIRED, email}   (no access token, no refresh token, no session row)
App shows "Check your email" + "Send the email again" + "Go to sign in"

E-mail: https://<PUBLIC_BASE_URL>/verify-email#t=<token>
Browser opens GET /verify-email (static page, strict CSP)
   page reads the token from the URL FRAGMENT (never sent to any server), removes it from the address bar
   person taps "Confirm my email" ──POST /api/auth/verify-email {token}──▶
        server: format → SHA-256 → find row → live? → DELETE row (1 row for exactly one caller) → email_verified_at = now
        200 {status: VERIFIED}  |  400 code VERIFICATION_LINK_INVALID (unknown/used/replaced/expired, all the same)
Page: "Your email is confirmed. Go back to the Kurinji app and sign in."

Sign-in (app) ──POST /api/auth/login──▶ verified: 200 tokens as before
                                       not verified + right password: 403 code EMAIL_NOT_VERIFIED, no session
                                       wrong password / unknown e-mail: the generic 401 as before
```

## 2. Decisions

| # | Decision | Reason |
|---|---|---|
| EV1 | No session (not even a restricted one) before verification | The existing session model has no "restricted" tokens; resend needs only e-mail + password, so nothing needs a session. `ActiveAccountCheck` and refresh also refuse an unverified account (defence in depth; such tokens are never issued). |
| EV2 | `users.email_verified_at` (NULL = unverified) | One fact, separate from `enabled`, deletion, moderation standing and sessions. |
| EV3 | Existing accounts grandfathered as verified (migration time) | No production user base: all 171 rows are development/test accounts made before e-mail could be sent, including the owner's. Erased tombstones stay NULL (they can never sign in). |
| EV4 | Token = 32 random bytes base64url (43 chars); store SHA-256 hex; one row per account (PK user_id) | Same reasoning as V19 refresh secrets: random secrets need no slow hash; a new e-mail replaces the token so the old link dies. Shares only the random/hash utilities with refresh secrets; the tables and endpoints are separate. |
| EV5 | Lifetime **60 minutes** | Within the requested 15–60 min; e-mail can be delayed; single use + one live token + hashed storage keep the window low-risk; resend is always available. |
| EV6 | Token in the URL **fragment**, confirm by **tap**, POST to the API | A fragment never reaches a server, proxy, access log or Referer; a link scanner that only opens the page cannot use the link up. |
| EV7 | Browser page served by the API (`/verify-email`), not an Android App Link | No HTTPS domain exists yet (App Links need a verified HTTPS host); same origin as the API; the backend stays the only authority. The page then tells the person to return to the app. |
| EV8 | Consume = one conditional `DELETE … WHERE user_id = ? AND token_hash = ? AND expires_at > now`, then mark verified in the same transaction | Two simultaneous clicks: exactly one DELETE returns 1 (row lock), the other gets the generic 400 (tested with 8 threads). |
| EV9 | Resend = e-mail **and password**, always `202 {status: RECEIVED}` | Only the password holder can trigger a message; the answer is identical for unknown, verified, wrong-password and real requests; sending is asynchronous, so timing does not tell either. |
| EV10 | Limits (in memory, like Phase 7): resend 3 per account and 20 per network address per hour; failed verification attempts 30 per address per 15 min; sign-up keeps its 10 per address per hour | Stops the endpoint from being a spam cannon and slows link guessing (which 256 bits already make hopeless). The per-account limit also counts unknown addresses, so a 429 reveals nothing. |
| EV11 | Squatting: a sign-up with an address that has an UNVERIFIED account whose last link expired replaces that account; verified accounts still answer 409 | Nobody can hold someone else's address for longer than one link lifetime (+ the attacker's own resends, at most 3/h). The 409 for verified addresses is the existing accepted oracle (Phase 14 item 5). |
| EV12 | Accounts never verified are removed after **7 days** (hourly clean-up; expired tokens too) | Data minimisation; such an account has no other data (it never had a session). |
| EV13 | Mail via a small `VerificationMailer` abstraction; SMTP (`spring-boot-starter-mail`, the one new backend dependency) when `MAIL_HOST` + `MAIL_FROM` are set; otherwise a mailer that sends nothing and says so at start-up | Provider-neutral (any transactional e-mail service's SMTP relay); fail-safe: e-mail being unavailable never verifies anyone. |
| EV14 | Plain-text e-mail, no tracking, says "never asks for your password", explains what to do if it wasn't you | Honest, phishing-resistant, no HTML to spoof. |
| EV15 | E-mail normalisation unchanged: trim + lower-case (Phase 2) | Verification is tied to the account id, i.e. to the same canonical address sign-in uses. No Gmail dot/plus folding (never done before). |
| EV16 | Password reset: none exists (A6 deferred) | Nothing to merge; a future reset token must be its own table/endpoint and must refuse unverified accounts. |

## 3. API (details in `docs/API.md` "V25")

- `POST /api/auth/register` → **201** `{status: "VERIFICATION_REQUIRED", email}` (was: tokens). 409/400/429 as before.
- `POST /api/auth/verify-email` `{token}` → 200 `{status: "VERIFIED"}` · 400 `{code: "VERIFICATION_LINK_INVALID"}` · 429.
- `POST /api/auth/verification/resend` `{email, password}` → **202** `{status: "RECEIVED"}` always · 429 · 400 (missing fields).
- `POST /api/auth/login` → new **403** `{code: "EMAIL_NOT_VERIFIED", message: "Please verify your email before signing in"}`.
- `GET /verify-email`, `/verify-email.js`, `/verify-email.css`: the page (CSP `default-src 'none'`, `no-store`,
  `no-referrer`, `frame-ancestors 'none'`).
- `ApiError` gains an optional `code` (present only where an app must react; absent everywhere else).

## 4. Configuration

| Variable | Default | Meaning |
|---|---|---|
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Where the link points (`/verify-email` is appended). **HTTPS in any real deployment** (start-up WARN otherwise, except localhost / 127.0.0.1 / 10.0.2.2). |
| `MAIL_HOST` | empty | SMTP host. Empty = nothing is sent (start-up WARN); accounts stay unverified. |
| `MAIL_PORT` | 587 | SMTP port (submission). |
| `MAIL_USERNAME`, `MAIL_PASSWORD` | empty | SMTP credentials / provider API key. Never logged (`MailProperties.toString` hides them). |
| `MAIL_FROM` | empty | From address, e.g. `Kurinji <no-reply@your-domain>`. Required. |
| `MAIL_STARTTLS` | true | Require STARTTLS. Only a local test sink should set false. |

Put them in the environment or the git-ignored `backend/.env` (never in `application.properties`). Other settings:
`app.email-verification.*` (`token-lifetime` 60m, `resends-per-account` 3, `resends-per-address` 20, `resend-window`
1h, `verify-failures-per-address` 30, `verify-window` 15m, `unverified-retention` 7d, `cleanup-interval` PT1H).

### Local development
- Without SMTP the app still runs; a new account cannot be confirmed (existing accounts are verified).
- To test the whole flow without an external account, run a local SMTP sink on 127.0.0.1 (any dev mail catcher) and
  start the backend with `MAIL_HOST=127.0.0.1 MAIL_PORT=<port> MAIL_STARTTLS=false MAIL_FROM="Kurinji <dev@localhost>"`
  and `PUBLIC_BASE_URL=http://localhost:8080` (open the link on the PC) or `http://10.0.2.2:8080` (open it in the
  emulator's browser).

### Production
- A transactional e-mail provider (SMTP relay with an API key), an SPF/DKIM/DMARC-aligned From domain, an HTTPS
  `PUBLIC_BASE_URL` behind the HTTPS reverse proxy (D12), and monitoring of the "not sent" warnings.
- Limits are per instance (in memory); several instances multiply them (Phase 14 item 4).

## 5. Android

- `RegisterViewModel`: success = "Check your email" (`verificationSentTo`), "Send the email again", "Go to sign in";
  no session saved. The password is kept only in the ViewModel's memory for the resend and cleared with it.
- `LoginViewModel`: `AuthError.EMAIL_NOT_VERIFIED` (403 + code) → "Please verify your email before signing in…" and
  "Send the email again" (uses the e-mail and password in the form). `ResendStatus` Idle/Sending/Sent/Failed; "Sent"
  is worded honestly ("If this account is waiting for confirmation, a new email is on its way").
- `RemoteAuthRepository.register` no longer saves a session; `resendVerification`; the error body's `code` is read
  (never shown). No token ever reaches the app; no deep link.

## 6. Known limitations

- Real delivery through an external provider is only as good as its configuration; see `docs/HANDOFF.md` for what was
  verified (a local SMTP sink, not an external mailbox).
- No App Link: after confirming in the browser, the person switches back to the app themselves.
- In-memory limits reset on restart and are per instance.
- Changing the e-mail address of an account is not possible (there is no such feature); when it is added it must
  re-verify the new address.
- The 409 for an already verified address remains (accepted V1 oracle).
