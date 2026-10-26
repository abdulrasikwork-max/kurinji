# Setting up real e-mail for verification links (V25)

V25 (`docs/EMAIL_VERIFICATION.md`) sends one e-mail per sign-up (and per "Send the link again"). Without mail settings
the backend logs `Verification e-mail not sent: e-mail delivery is not configured` and **nobody can confirm a new
account**. That warning is the designed behaviour, not a bug. Nothing in this file weakens V25: the link is still
single-use, 60 minutes, stored only as a SHA-256, and no account gets a session before it is opened.

**Credentials never go into git.** Put them only in the git-ignored `backend/.env` (template: `backend/.env.example`),
then restart the backend.

**Status (Phase 16, 2026-10-26): delivery to a real external mailbox is NOT VERIFIED.** It needs the owner's own
provider account; it was not set up by the agent.

## 1. Choose a sender

| Option | Good for | Settings |
|---|---|---|
| **Gmail + app password** | the team's own tests, a few mails a day | `MAIL_HOST=smtp.gmail.com`, `MAIL_PORT=587`, `MAIL_STARTTLS=true`, `MAIL_USERNAME=<the Gmail address>`, `MAIL_PASSWORD=<16-letter app password>`, `MAIL_FROM=Kurinji <the same Gmail address>` |
| **A transactional provider** (Brevo, Mailgun, SendGrid, Amazon SES, Postmark...) | a pilot with real people | the provider's SMTP relay host, port 587, STARTTLS, the SMTP user and SMTP key it gives you, and a sender address on a domain you verified with it |

Gmail app password (once): turn on 2-Step Verification for the Google account, then Google Account → Security →
"App passwords" → create one for "Mail". It is a separate 16-letter password just for this app (not the account
password) and can be revoked at any time. Gmail rewrites the From address to the signed-in account, so `MAIL_FROM` must
be that Gmail address. Gmail's daily sending limits make it unsuitable for a public launch: use a provider then (D12).

## 2. Where the link points (`PUBLIC_BASE_URL`)

The e-mail contains `PUBLIC_BASE_URL/verify-email#t=...`; the person opens it in a browser, which must reach the backend.

| How you test | `PUBLIC_BASE_URL` |
|---|---|
| Emulator, mail read on the PC | `http://localhost:8080` (the default) |
| **Physical phone over USB** with `adb reverse tcp:8080 tcp:8080`, mail opened **on the phone** | `http://127.0.0.1:8080` (the phone's own 127.0.0.1:8080 is forwarded to the PC by adb reverse) |
| Physical phone on the same Wi-Fi | `http://<the PC's LAN address>:8080` (Windows Firewall must allow port 8080 on the private network) |
| A real deployment | `https://your-domain` (HTTPS is required before a pilot, D12) |

## 3. Example `backend/.env` lines (Gmail, physical phone over USB)

```
PUBLIC_BASE_URL=http://127.0.0.1:8080
MAIL_HOST=smtp.gmail.com
MAIL_PORT=587
MAIL_USERNAME=your.name@gmail.com
MAIL_PASSWORD=abcdabcdabcdabcd
MAIL_FROM=Kurinji <your.name@gmail.com>
MAIL_STARTTLS=true
```

## 4. Check it

1. Restart the backend; the start-up WARN about e-mail must be gone.
2. Sign up on the phone with an address you can read; the app shows "Check your email".
3. The mail arrives (look in Spam the first time); open the link on the phone, tap the button, then sign in.
4. A mail that never arrives: the backend logs only the exception class (never the address or the token); a wrong app
   password shows as an authentication exception. Then "Send the link again" on the sign-in screen.
5. Record the result as **verified** only after a real mail was received and the link worked.
