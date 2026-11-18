# Phase N7: Notifications and the IRL signature

**Status: APPROVED by the owner on 2026-11-18 (D26–D42 = N-118 to N-134 in `DECISIONS.md`); implementation started the same day. See "As built" at the end.** Originally: design only: no code, no migration, no dependency, no
deployment. Decisions D26–D42 are N-118 onward in `DECISIONS.md` (the brand ones also IRL-C12 to
IRL-C14). Units B1–B4, A1–A3, C, one local commit each; never pushed, never deployed; the pilot stays on the old jar / V30.

Inspected for this design: `IRL_DIRECTION.md` §2, N-10 (Firebase approved), N5 (D19 Replies marker, `/api/replies` line
types, N-93, N-101), N6 (§6 "push to Circle members who use IRL", the alert queue, N-110 to N-117), `core/notifications`,
`AndroidSafeWalkAlerts`, `SafetyTrackingService` (both use `ic_shield`), `SplashFragment` / `fragment_splash.xml`, the
brand resources and colours, `docs/design/irl-icon.md` (five rounds of marks and why each was dropped), backend events
(`MessageSent`, `MomentMoreAdded`, `MomentShared`, `SosChanged`), `DeviceSession` (V19), `N6SafetyGuardsTest` (reads the
source manifest only), `ModelPlacesTest`. Facts checked: the backend already has Nimbus JOSE (through the OAuth2 resource
server), so FCM needs no new backend library; the `irl_api33` emulator is a Google APIs image (FCM works on it).

## 1. What N7 is and why

- **"Someone is in" must not wait until the app is opened (N-10).** Today Replies lights a dot only while IRL is open;
  a Circle member who uses IRL learns of an SOS only by SMS; On my way depends on an inexact alarm (A23: about 1 min 45 s
  late in light doze; Android allows far more on paper).
- **IRL is not an engagement machine.** A notification is sent only when a person did something that needs you, or for
  safety you started or agreed to. Never counts, never "you might like", never "we miss you". Quiet is the default.
- **The brand.** The ஐ mark (IRL-C10) tells nobody what IRL is. N7 replaces it with **the IRL signature**, redesigns the
  opening around it and replaces the shield in the status bar.

## 2. Decisions for approval

| # | Decision |
|---|---|
| D26 | **Pointer pushes.** Every push is an FCM *data-only* message carrying a type code and opaque ids, nothing else: no name, no text, no time, no location. The phone builds the notification itself from IRL's own API (visibility-checked, so something deleted, held or blocked is never shown); if IRL cannot be reached in about 8 s it shows a generic line ("New activity in Replies"). Google sees that a message was sent, never what it is about |
| D27 | **Firebase, minimal.** Android: `firebase-messaging` through the Firebase BOM (new dependency, approved in principle by N-10); initialised by hand from `local.properties` values (no `google-services` plugin, no JSON in git); auto-init off (no token before sign-in); no Analytics, Crashlytics, Remote Config or In-App Messaging; delivery-metrics export stays off. The library adds two install-time permissions through manifest merge (`WAKE_LOCK`, `com.google.android.c2dm.permission.RECEIVE`): the permission guard moves to the merged manifest and pins them. A build without the Firebase values has no push and works as today. Backend: **no new library** (FCM HTTP v1 through the JDK `HttpClient`, the OAuth token signed with the Nimbus JOSE that is already present); a third outbound client (`ModelPlacesTest`: moderation service, SMS, FCM) |
| D28 | **Tokens belong to a sign-in.** One token per device session (`sid`): registered after sign-in and only while notifications are allowed on the phone; replaced on refresh; deleted at sign-out, "sign out all other phones", a revoked or expired session, account deletion, an FCM "unregistered" answer and after 60 days without a refresh. Every active session gets the push (several phones work); signing out wipes IRL's notifications from that phone |
| D29 | **A closed catalogue of 14 kinds** (§4). Never: Same, Say more requests, new moments on Today, the daily question, "set aside", a plan closing by itself, connection accepted, digests, reminders to come back, anything with a number. The server's type list is a closed enum pinned by a test; a new kind needs a decision |
| D30 | **Anti-noise rules.** One push per plan until you open Replies (no per-message read state: it uses your own "last opened Replies" time, N-93); private replies push once per turn (turns already exist); several "I'm in" within 2 minutes become one push ("Asha, Ravi and others are in"); social pushes at most 6 an hour and 20 a day per person (then quiet; Replies still shows them); quiet at night (11 pm–7 am phone time, social only: shown without sound); social notifications disappear by themselves after 24 hours, like the moments they are about |
| D31 | **Choices.** Settings → Notifications: Replies, Plans, Messages in plans, People (switched on the server, so "off" means IRL does not send); "Show what people wrote" (off) and "Quiet at night" (on), kept on the phone. Safety and account notices are not switched in IRL: they come only from something you started or agreed to (Android settings can still block them). A channel blocked in Android settings is synced to the server switch |
| D32 | **Private on screen.** Default text never contains what anyone wrote, only names and structure ("Asha replied", "You're in Asha's plan · Today 4:00 PM"). Lock screen: a neutral public version ("IRL · New activity in Replies"; SOS: "SOS from your Safety Circle", no name). Nothing about other people is stored on the phone (Q3); opening or dismissing a notification tells nobody anything (no read receipts) |
| D33 | **Permission in context, never at launch.** Android 13+: a short in-app card first, then the system dialog, at the moments it obviously helps: after sharing a first Who's in?, after saying I'm in, after sending a first private reply, before On my way (exists), when accepting a Safety Circle request. "Not now" waits 30 days. Denied: IRL works exactly as today (the dot) |
| D34 | **Phone behaviour.** Eight channels (§5.1). While IRL is open no social notification is shown: the dot and the visible screen update instead; safety always shows. Taps go through a new non-exported router activity (ids validated; another app cannot fake one), land on the right screen with its tab underneath. Stable notification ids per thread replace instead of stacking; grouped per channel with a summary that has names, never a count |
| D35 | **Reliable delivery.** A queue in the database (the N6 pattern: survives restarts, the provider is called outside transactions, every step its own transaction); per-kind priority and lifetime (SOS high / 30 min; On my way high / 10 min; social normal / 24 h); retries with back-off on 429 / 5xx; token deleted on `UNREGISTERED` / `INVALID_ARGUMENT` / `SENDER_ID_MISMATCH`; auth failure stops sending and says so in the log; `PUSH_PROVIDER` = `FCM`, `DEV_FILE` or `NONE` (production refuses `DEV_FILE`; `NONE` is the kill switch); logs hold counts and FCM status codes only |
| D36 | **The Safety Circle in the app (N6 §6).** When you add a number that belongs to a verified IRL account, that person (only) gets a request: "Asha added you to their Safety Circle. If they press SOS, IRL can also tell you here." Accept / Not now / Don't ask again. You learn nothing until they accept; then your contact row says "Also in the IRL app". An SOS then also pushes to them (high priority) at the same moment and under the same limits as the SMS, **never instead of it**; the push holds no location; their screen shows the same facts as the text (name, time, the map link the texts carried, status) while the SOS is active and 24 h after; Call them / Call 112. An SOS ended from another phone sends nothing (N6 rule). Either side can end the link at any time |
| D37 | **On my way: a server reminder as a second alarm.** The server already knows the due time: it sends a high-priority push at "There yet?" and at the nudge; the phone treats it exactly like its own alarm (same notification id, shown once). If the laptop is down, the alarm still works; if the alarm is late, the push is on time. Nobody else is told (timeout alerts to the Circle stay deferred, N6 §6) |
| D38 | **Account and community notices.** A new sign-in on another phone ("Not you? Sign out the other phones"); a verification decision; a human moderation decision about your moment or message, or your appeal's outcome (never an automatic collapse, never who reported). Staff: "Things are waiting" for verifiers, moderators and admins who switch it on, at most once an hour |
| D39 | **Brand: the IRL signature** (§8) replaces ஐ everywhere: launcher icon, themed icon, the in-app mark, the opening. "irl" written by hand in one unbroken line, with a round blush dot: the dot is you |
| D40 | **Notification icons.** One small icon for every IRL notification: the signature as a 2 dp line, white, alpha only (the shield goes). Anything about an active emergency (your own SOS location notice, SOS from your people) uses the siren, so an emergency never looks like a chat ping (U4). Accent: Black Cherry; emergency: `ss_emergency` |
| D41 | **The opening.** The line writes "irl", the dot arrives from out there and lands on the i, then OUT THERE. TOGETHER.; the first-run lines become **REAL PEOPLE. REAL PLANS. REAL LIFE.** and the button **I'M IN →**. Returning: the same in under a second. No motion with animations off. The system launch screen stays plain cherry (the mark is drawn once, by the opening) |
| D42 | **V38** (development MySQL first: backup, scratch dry run, apply): `push_devices`, `push_settings`, `push_messages`, `circle_links`, `circle_link_mutes` (§9). Nothing changes meaning in existing tables |

## 3. How a push works

### 3.1 Flow
1. Something happens and is committed (a message delivered after its check, a person added to a plan, an SOS round
   sent...). Its owner publishes a domain event, as today (`MessageSent`, `SosChanged`, ...); N7 adds the missing ones.
2. `push` (a new backend package, the only one that knows FCM) listens **after commit**, applies `PushPolicy` (pure:
   kind allowed, recipient's switch, membership, block, throttle, caps) and writes one `push_messages` row per active
   device, with `not_before` (now, now + 2 min for batching, or a due time).
3. `PushDispatcher` (every 2 s, and at once after a write) claims due rows, calls FCM outside any transaction, records
   the result. Expired rows are skipped (`EXPIRED`), never sent late.
4. The phone's `FirebaseMessagingService` gets `{t, s, m}`; the router hands it to the renderer of that kind (registered
   by its feature; `core/push` imports neither social nor safety). The renderer reads IRL's API (≤ 8 s) and posts the
   notification, or nothing if the thing is gone, or a generic line if IRL could not be reached.

### 3.2 Payload (everything Google sees, besides the token and the time)
`{"message":{"token":"…","data":{"t":"PLAN_ASK","s":"<moment uuid>","m":"<row id>","v":"1"},"android":{"priority":"NORMAL","ttl":"86400s","collapse_key":"plan-<uuid>"}}}`

### 3.3 Limits (configurable, counted in the database)
| Rule | Default |
|---|---|
| Plan messages | one push per plan until the person opens Replies again |
| "I'm in" batching | 2 minutes; one push names up to 2 people "and others" |
| Social pushes per person | 6 / hour, 20 / day (then SKIPPED: shown in Replies only) |
| Staff summary | 1 / hour, opt-in |
| Device registration | 10 / hour per session |
| Retention | rows deleted 7 days after creation; tokens as D28 |

## 4. The catalogue (D29)

Text is with "Show what people wrote" off. Unlocked / lock screen. All social kinds need an active MIET membership.

| # | Kind | When, to whom | Switch | Channel · priority | Text | Opens |
|---|---|---|---|---|---|---|
| 1 | `PLAN_ASK` | someone says I'm in, to the plan's author (batched 2 min) | Plans | Plans · normal | "Asha is in" / "Asha and Ravi are in" / "Asha, Ravi and others are in" · "For your plan · Today 4:00 PM" | the plan's people |
| 2 | `PLAN_ADDED` | the author adds you | Plans | Plans · normal | "You're in" · "Asha's plan · Today 4:00 PM" | the plan |
| 3 | `PLAN_MESSAGE` | a message or quiet line (incl. This happened) in a plan you are in | Messages in plans | Plans · normal | "New in Asha's plan" | the plan |
| 4 | `PLAN_CALLED_OFF` | the author calls it off, to the people in | Plans | Plans · normal | "Plan called off" · "Asha called off Today 4:00 PM" | the plan |
| 5 | `REPLY_TURN` | a private reply arrives, or it is your turn | Replies | Replies · normal | "Asha sent you a private reply" / "Asha replied" | the conversation |
| 6 | `MORE_ADDED` | the author says more on a moment you asked about | Replies | Replies · normal | "Asha said more" | the moment |
| 7 | `PEOPLE_REQUEST` | someone wants to be in your people | People | People and requests · normal | "Asha wants to connect" | requests |
| 8 | `ACCOUNT_SIGN_IN` | a new sign-in, to your other phones | — | Your account · normal | "New sign-in" · "IRL was just signed in on another phone. Not you? Sign out the other phones." | Account |
| 9 | `MEMBERSHIP_DECIDED` | your verification is decided | — | Your account · normal | "You're verified" / "Your verification needs another look" | Get verified |
| 10 | `CONTENT_DECIDED` | a moderator decides on your moment or message, or your appeal | — | Your account · normal | "An update on your moment" (no reason on screen) | the item |
| 11 | `CIRCLE_REQUEST` | someone added your number to their Safety Circle | — | People and requests · normal | "Asha added you to their Safety Circle" · "Choose if IRL can also tell you here" | the request |
| 12 | `CIRCLE_SOS` | an SOS of someone whose Circle you accepted: started, then ended | — | SOS from your people · high | "Asha pressed SOS" · "9:41 PM" [Open] [Call Asha] [Call 112]; then "Asha ended the SOS: I'm safe · 9:58 PM" (facts only) | the alert |
| 13 | `OMW_REMINDER` | your own On my way: at the time and at the nudge | — | On my way · high | the existing "There yet?" / "Still on your way?" | On my way |
| 14 | `STAFF_WAITING` | requests or cases wait, to staff who opted in | Staff | Community staff · normal | "Things are waiting" | Community staff |

## 5. On the phone

### 5.1 Channels (D34)
| Channel | Importance | Holds |
|---|---|---|
| Replies (new) | default | 5, 6 |
| Plans (new) | default | 1–4 |
| People and requests (new) | default | 7, 11 |
| Your account (new) | default | 8–10 |
| SOS from your people (new) | high, heads-up | 12 |
| On my way (existing `safety_alerts`) | high | 13 and the local alarm |
| Emergency location (existing) | low, ongoing | your own SOS |
| Community staff (new; created only for staff) | low | 14 |

### 5.2 Behaviour
- **Foreground:** social kinds update the Replies dot and the visible screen (a `PushEvents` flow); no tray
  notification. `CIRCLE_SOS` and `OMW_REMINDER` always show.
- **Ids and groups:** one id per thread (plan, conversation, request, SOS), `setOnlyAlertOnce` on updates; a group per
  channel with a summary listing names; opening the thread in IRL cancels its notification on this phone.
- **Taps (D34):** `PendingIntent` → `NotificationRouterActivity` (not exported, no UI) → `MainActivity` with the tab
  under the target. Signed out, a closed plan, an ended SOS: the normal screen for that state.
- **Settings → Notifications:** the switches of D31, "Android notification settings", and an honest footer: "Notifications
  can come late or not at all on some phones (battery savers). Replies always has everything."

## 6. Safety

### 6.1 Safety Circle in the app (D36)
- **Linking:** on adding or editing a contact, the server compares the number with verified numbers (`users.verified_phone`).
  A match that is not you and has not chosen "Don't ask again" for you gets a `circle_links` row (PENDING) and a request.
  The owner sees nothing until ACCEPTED. A changed number, a removed contact, "Leave" (member) or the member's number
  change ends the link. Requests are limited (10 a day per owner) and never reveal that a number has IRL.
- **SOS:** the N6 round planner, when it plans the texts, also queues `CIRCLE_SOS` for accepted members (same timing:
  first location or 20 s; false alarm before = nothing; same 5-rounds-a-day cap). End: one update push. Ended from
  another phone: nothing.
- **The member's view** (`GET /api/circle/alerts/{emergencyId}`, 404 for anyone else): name, the owner's number (to
  call), start time, status, the map link the texts carried, "sent late". Available while active and 24 h after.
  No history, no live tracking, no location in any push.

### 6.2 On my way (D37)
Walk start and every More time queue two rows (`not_before` = due, due + 5 min); I'm there / Stop / SOS delete them. The
phone's handler is `SafeWalkSession.onAlarm`, so a push and an alarm together still show one notification.

## 7. Privacy and security
- What leaves IRL: to Google, an install token, a type code, opaque ids and timing; nothing else (D26). The privacy
  notice (N8) names Google as the push processor and says exactly this.
- Tokens are never logged or returned; stored only to send; one row per session; deleted as D28.
- Every route takes the caller from the JWT; a device row can only be written for the caller's own session; settings
  are the caller's own; circle routes never need a community membership (safety is never gated).
- No push precedes visibility: a message pushes only after its check delivered it; a held or removed thing renders as
  nothing.
- Blocks: producers skip blocked pairs; renderers read through the same visibility as Replies.
- Isolation stays: social and safety publish their own events; the backend `push` package and the phone's `core/push`
  are the only places that join them (guards extended).

## 8. Brand: the IRL signature (D39–D41)

### 8.1 The idea
**"irl" written by hand in one unbroken line, and one round dot.**
- **Handwriting is the most real thing a screen can show.** Nobody types a signature. IRL is a real-name community;
  a signature is your real name in your own hand.
- **"Who's in?" is the old sign-up sheet on the notice board:** you put your name down. The mark is that gesture.
- **The letters hold hands.** Joined-up ("running") writing, the way every Indian school copybook teaches it: one line,
  people connected.
- **The dot is you.** The i is only an i when the dot arrives: in the opening it comes in from out there and lands.
  OUT THERE. TOGETHER. The line leans forward and its last stroke leaves to the right.
- It keeps what the owner approved in ஐ (one line, one weight, round ends, cream on Black Cherry, a mark that writes
  itself) and says IRL to everyone, Tamil reader or not.

### 8.2 Explored and dropped (sheets `docs/design/irl/n7-*.png`)
| Direction | Why dropped |
|---|---|
| An open ring with a dot, "there's room for you" | tried twice before (round 1 D, IRL-C8): a button, "ordinary and generic" |
| Confluence: two strokes becoming one ("sangam") | reads as a Y or a wishbone |
| Interrobang, "Who's in‽" | reads as a P |
| A name tag ("Hello, my name is") | a calendar at 24 dp |
| A raised hand, high five, "hands in" | a hand is a party sign in Tamil Nadu (rule of round 5) |
| Chai glass, bench, ticket, tin-can phone | a food, furniture or events brand; unreadable at 24 dp |
| The same letters typed, separate | clean but a font: the 2026-11-03 brief ruled out a text-only IRL; the signature is drawn, not typed |
| A swash under the signature | busy at 48 px; with the dot it starts to look like a face |

### 8.3 Geometry (concept; a drawing pass with a review sheet refines the curves in A1)
108 × 108 adaptive canvas, monoline, stroke 5.4, round caps and joins, written in stroke order (the order the hand writes):
`M28 65 C31 60 34 55 37 52.5 C36 58 35 63.5 35 67.5 C35 72.5 40 73.5 43 69.5 C45 65 47 57 49 50.5 C50 52.5 51 54.5 52.5 54.5 C54 54.5 56 53 58.5 51 C57.5 57 56 63 56 67.5 C56 72.5 61 73.5 64 69.5 C67 63.5 71 48 71.5 39 C72 32 67.5 30 65.8 34.5 C64 41 63.5 57 64.5 66.5 C65.5 72.5 71 73.5 77 68.5`;
dot: circle (40.5, 41.5) r 4.7. Every point, the stroke's half-width included, lies within 31 units of the centre
(safe zone 33).

### 8.4 Where it appears
- **Launcher:** cream line, blush dot on the existing Black Cherry ground (`ic_launcher_background` unchanged).
  Themed icon (Android 13+): line and dot in one colour. The SOS shortcut keeps its emergency icon.
- **In the app** (Today head, You, Replies empty states, onboarding): the line in the text colour, the dot in the accent
  (cherry by day, blush at night).
- **Notification small icon** (D40): `ic_stat_irl`, the same path scaled to 24 dp (line 2 dp, dot 3.5 dp); `ic_stat_sos`
  = the siren for emergencies.
- **Play Store 512 px, feature graphic:** N8.

### 8.5 The opening (D41)
| Time | First run | Returning |
|---|---|---|
| 0 | plain cherry (the system launch screen hands over invisibly) | same |
| 120–820 ms | the line writes "irl" (one trim-path animation) | 120–640 ms |
| 820–1 100 ms | the dot comes in from the top right and lands on the i | 640–820 ms, then on to Today / Login |
| 1 100 ms | OUT THERE. TOGETHER. (blush, letter-spaced) | — |
| 1 500 ms → | REAL PEOPLE. / REAL PLANS. / REAL LIFE. (last line blush), then I'M IN → | — |
Animations off: the finished frame at once. TalkBack: "IRL, in real life. Out there. Together.", then the lines and the
button ("I'm in: see how IRL works"). The existing text-size fitting stays.

### 8.6 Resources that change (A1)
New: the signature in `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_brand_mark`, `ic_mark_on_ink`,
`art_opening_meet` (animated: writes itself), `ic_stat_irl`, `ic_stat_sos`. Retired: the ஐ drawings (kept in git),
`ic_shield` from notifications. Strings: `opening_line_*`, `opening_cta`, the art description. `irl-icon.md` gets the
new mark on top and keeps ஐ as history.

## 9. Data (V38, D42)
| Table | Columns (all FKs `ON DELETE CASCADE`) |
|---|---|
| `push_devices` | id, user_id → users, session_id → device_sessions (unique), token, token_hash (SHA-256, unique), app_version, created_at, updated_at |
| `push_settings` | user_id (PK) → users, replies, plans, plan_messages, people, staff, updated_at (no row = defaults) |
| `push_messages` | id, device_id → push_devices, user_id → users, type (closed list, binary), subject, collapse_key, high_priority, ttl_seconds, not_before, expires_at, status QUEUED/SENT/SKIPPED/FAILED/EXPIRED, attempts, next_attempt_at, sent_at, skip_reason, created_at |
| `circle_links` | id, contact_id → safety_contacts (unique), owner_user_id → users, member_user_id → users, status PENDING/ACCEPTED, created_at, decided_at |
| `circle_link_mutes` | member_user_id → users, owner_user_id → users (PK of both), created_at |
`AccountEraser` and its coverage test take every new reference to users.

## 10. API
`PUT /api/push/device {token, appVersion}` 204 (the caller's session) · `DELETE /api/push/device` 204 ·
`GET|PUT /api/push/settings` · `GET /api/circle/requests` · `POST /api/circle/requests/{id}/accept` ·
`POST /api/circle/requests/{id}/decline {dontAskAgain}` · `GET /api/circle/in` · `DELETE /api/circle/in/{id}` ·
`GET /api/circle/alerts/{emergencyId}` · contact answers gain `inApp` (accepted links only). Config `app.push.*`
(`PUSH_PROVIDER`, `FCM_CREDENTIALS_FILE`: the service-account JSON, outside git, readable only by the service user).

## 11. Units (one local commit each; Gradle, Maven and the emulator one at a time)
| Unit | Content |
|---|---|
| A1 | **Brand first** (no backend needed): the signature drawings + review sheet, launcher / themed / in-app marks, `ic_stat_irl`, `ic_stat_sos`, the opening (animation, copy), tests (strings, contrast, a11y), emulator and A23 look |
| B1 | Push core: V38 written (Flyway held at 37), `FcmClient` (HTTP v1 + OAuth via Nimbus), dev file sink, stub, `PushPolicy`, queue + dispatcher, device and settings APIs, clean-up job, eraser, prod guard, guards |
| B2 | Producers: plans, replies, more, people, account, membership, moderation, staff; throttles and caps; per-kind tests (author / member / stranger / blocked / membership ended / paused) |
| B3 | Safety: circle links (requests, accept, decline, don't ask again, leave, `inApp`), `CIRCLE_SOS` in the N6 round, the member's view, On my way reminders |
| B4 | V38 on the development MySQL (backup, scratch dry run, apply); live run against a real Firebase project with the emulator |
| A2 | Android push: manual Firebase init, token lifecycle, service + router + renderers per feature, channels, router activity, foreground handling, Settings → Notifications, permission cards, sign-out clean-up, merged-manifest guard |
| A3 | Android safety: Circle requests, "Circles you're in" (leave), "Also in the IRL app" on contacts, the SOS-from-your-people screen and notification, On my way push handling |
| C | Full runs, lint, security / privacy review, emulator walk with real FCM, A23 (doze, Samsung sleeping apps, lock screen, two phones, sign-out), upgrade N6 → N7, documents |

**The owner's part:** create the Firebase project (free plan) under the team's Google account with 2-step sign-in; add
the Android app `com.safesphere.app`; create one service account for the pilot server and one for development; put
their JSON files outside the repo; give the Android config values for `local.properties`.

## 12. Deferred (not N7)
- Clearing a notification on your other phones when it is opened on one.
- Actions that write from a notification (accept someone, reply inline); a "mute this plan" switch.
- A reminder before a plan's time; plan time changes (moments are not edited).
- Live location for Circle members during an SOS; their SOS history; On my way timeout alerts to the Circle (N6 §6).
- iPhone / APNs, web push; photos inside notifications; any delivery analytics.
- Tamil text for notifications (Tamil UI is on the "never early" list).
- Sending from a cloud server instead of the laptop; the Play Store icon and graphics; a trademark / similarity search
  for the signature and the name (before any store release, N8).

## 13. Validation plan
- **Pure:** `PushPolicy` (every kind, switch, throttle, cap, batch, never-list, priority and lifetime); the phone's
  router and renderers fail closed (unknown kind, missing item, 403 → nothing; unreachable → generic line).
- **Backend:** stub FCM (each error answer → retry / delete token / stop), the queue survives a restart, expired rows
  never sent, one row per active device, a revoked session gets nothing, two-user ownership of devices and settings,
  blocked pairs, membership ended, erasure, prod guard, payload pinned to `{t, s, m, v}`, logs without tokens, names or
  text, V38 on real MySQL.
- **Android:** channels and importances, lock-screen public versions, previews off by default, quiet at night,
  foreground suppression, router activity not exported and ids validated, sign-out wipes notifications and the token,
  no permission ask at launch, merged-manifest permission pin, isolation (`core/push` imports neither side), the
  signature resources (safe zone, monochrome alpha-only small icon), opening timings and strings.
- **Emulator (real FCM):** every kind once, foreground vs background vs killed, two signed-in phones (emulator + A23),
  sign-out on one, a Circle request → accept → SOS → end, On my way with the alarm and the push together.
- **A23:** doze (screen off 30 min, unplugged), Samsung "sleeping apps", lock screen, TalkBack on the opening.

## 14. Risks and conflicts (for the owner)
1. **New dependency** (Firebase; N-10 approved push, the details are D27): needs Google Play services; phones without it
   get no push and lose nothing else. Two library permissions arrive by manifest merge (D27).
2. **The laptop is the sender:** pushes only while it and its internet are up (sending is outbound: the tunnel is not
   needed for it; the phone's look-up is). The phone shows a generic line if the look-up fails.
3. **Android delivery is best effort:** normal-priority pushes can wait for a doze window; Android may lower high
   priority for rarely used apps; Samsung and other battery savers can stop delivery until IRL is opened. Hence: SMS
   stays the SOS channel, the alarm stays for On my way, Replies stays the full record. Said in Settings.
4. **Metadata:** Google still sees who receives how many pushes and when. Accepted for N-10; said in the privacy notice.
5. **Linking reveals a relationship to the member** (that you saved their number): it is the adder's own choice;
   the member can decline or block future requests; the adder learns nothing unless they accept.
6. **The signature is made of letters.** The 2026-11-03 brief ruled out a "text-only IRL"; this is a drawn mark with a
   story, not a font, but it is the owner's call (D39). Similarity with the defunct US app "IRL" (2023) and other "irl"
   marks is unchecked (§12).
7. **Rules that need updating, not breaking:** `ModelPlacesTest` (2 → 3 outbound clients), `SchedulingConfig` (a second
   scheduled sender), `N6SafetyGuardsTest` (merged manifest), `SocialIsolationTest` / `GoingThereTest` (`push`),
   `HonestyTextsTest` (opening lines), Q3 (names in the tray, never stored by IRL, gone after 24 h or sign-out).
8. **`CLAUDE.md` is at 39.3k characters:** N7's summary must move text out (as N6-C did).

## 15. Limits that remain (say them in the app and the viva)
- No push without Google Play services, with notifications blocked, or when a battery saver has put IRL to sleep.
- A push is not a delivery receipt; IRL never claims anyone saw anything.
- The Circle push is an extra: the SMS is the alert; both need the phone's internet when the SOS starts (N6 §8).

## As built

### A1 (2026-11-18): brand. Implemented, compiled, tested; seen on the emulator.
- **Drawings:** the IRL signature (one path, stroke 5.4, round ends, written in hand order) + the blush dot in
  `ic_launcher_foreground` (cream / blush), `ic_launcher_monochrome`, `ic_brand_mark` (line = `colorOnSurface`, dot =
  new `brand_mark_dot`: cherry by day, blush at night), `art_opening_signature` (animated: `trimPathEnd` 120-820 ms; the
  dot group flies in from (+26, -22) and lands with an overshoot at 820-1100 ms; `art_opening_meet` deleted),
  `ic_stat_irl` (24 dp, white, alpha only), `ic_stat_sos` (the siren, white, no tint). The ஐ path is gone from every
  resource. Brand decisions recorded as IRL-C12 (mark), IRL-C13 (notification icons), IRL-C14 (opening): IRL-C11 was
  already the 2026-11-12 opening.
- **Opening:** the typed "IRL" wordmark is gone (the signature is the name); the motto at 900 ms; the first-run lines
  `opening_line_people|plans|life` = "Real people. / Real plans. / Real life.", the button "I'm in →" (spoken "I'm in:
  see how IRL works"); `SPLASH_MINIMUM_DURATION_MS` 1000 → 1100 so the dot has landed before a returning person moves on.
- **Icons outside the app:** On my way notifications `ic_stat_irl` + Black Cherry accent; the SOS location notice
  `ic_stat_sos` + `ss_emergency` accent; the SOS / Call 112 actions, the Quick Settings tile and its in-app row: the
  siren; `ic_shield_alert` deleted. Kept on purpose: the in-app shield glyphs of the Safety hub status card and the
  "Safety" / "Moderation" rows (functional icons inside the app, not the brand).
- **Docs:** `design/irl-icon.md` (the signature on top, ஐ as history), `DESIGN_SYSTEM.md` brand block.
- **Tests:** new `BrandSignatureTest` (one geometry in all five files, safe zone ≤ 33 incl. half the stroke, no ஐ / Meet
  remnant, small icons alpha-only and only `ic_stat_irl` / `ic_stat_sos`, the siren on SOS surfaces, the opening
  resources and timing); `KurinjiTextsTest` (new lines and button). `testDebugUnitTest` **660 / 0**, `assembleDebug` OK.
- **Seen on the emulator (fresh install):** the line written, the dot landed, the motto; first run settles on REAL
  PEOPLE. / REAL PLANS. / REAL LIFE. and I'M IN →; the launcher icon in the app drawer.

### B1 (2026-11-18): push core. Implemented, compiled, tested. V38 written, NOT applied (Flyway held at 37 until B4).
- **V38** (`V38__n7_push_and_circle_links.sql`): `push_devices` (one token per `device_sessions` row, `token_hash`
  SHA-256 unique, CHECKs), `push_settings`, `push_messages` (closed type list = `PushType`, status QUEUED / SENT /
  SKIPPED / FAILED / EXPIRED with the queued / sent / reason pairs as CHECKs, subject `^[A-Za-z0-9-]{1,64}$`, `event_id`
  shared by every phone of one event; no text, name or place), `circle_links` (public UUID, unique contact, PENDING /
  ACCEPTED, decided ⇔ ACCEPTED), `circle_link_mutes` (PK member + owner). All FKs cascade. The planned `collapse_key`
  column is derived instead (`PushPolicy.collapseKey`); `event_id` added for de-duplication and the caps.
- **`push` package:** `PushType` (the 14 kinds with group, switch, priority, lifetime, thread), entities and
  repositories, pure `PushPolicy` (switches with defaults, batching, caps, collapse key, retry delay ≤ 10 min, TTL left,
  subject / token patterns), `PushQueue` (enqueue one row per running sign-in, due ids, claim with a 2-minute lease and
  the session re-checked, result: an invalid token deletes the phone and its rows), `PushDispatcher` (every 2 s + kick
  after commit, one run at a time, logs counts and FCM status codes only), `PushService` (THE entry for producers:
  `notify`, `notifyAt`, `forget`, `toldSince`; NONE = nothing queued), `PushDeviceService` + `PushController`
  (`/api/push/device`, `/api/push/settings`; the caller and the sign-in only from the JWT; a token moves to the newest
  sign-in; 10 registrations an hour per sign-in), `PushCleanupJob` (hourly: phones of ended sign-ins, tokens older than
  60 days, rows older than 7 days).
- **Sender:** `PushSender` port; `FcmClient` = FCM HTTP v1, data-only, Android priority / TTL / collapse key; the OAuth
  service-account assertion signed RS256 with Nimbus (no Firebase library on the server); access token cached to near
  expiry; answers mapped (404 / UNREGISTERED, SENDER_ID_MISMATCH, a 400 about the registration token → delete the phone;
  any other 400 → FAILED, never deletes tokens; 401/403 → AUTH, token fetched again; 429 / 5xx → retry with Retry-After);
  https only (a local stub only in tests). `DevFilePushSender` (default; no token in the file). `PushSenderConfig`.
- **Elsewhere:** `config/PushProperties` (`app.push.*`), `ProductionGuard.pushProblems` (prod refuses DEV_FILE; FCM needs
  an absolute readable credentials file; NONE allowed), `SchedulingConfig` (two scheduled senders), `AccountEraser` (+ 7
  references, statements before the sessions and contacts), `CircleLink` / `CircleLinkMute` entities (used from B3),
  `ModelPlacesTest` (three pinned outbound clients; FCM never logs and sends no notification block).
- **Tests:** `PushPolicyTest` 5 (the 14 kinds = the V38 CHECK, priorities, switches, anti-noise, the pointer payload),
  `PushApiTest` 9 (registration bound to the sign-in, settings own, every running sign-in gets the same event, a token
  moves, batching, invalid token / retries / expiry, signing out stops even queued pushes, On my way scheduling and
  forgetting, erasure, no token in the logs), `PushCapsTest`, `PushOffTest`, `FcmClientTest` 3 (a local stub of Google's
  token endpoint and FCM: the RS256 assertion verifies, data-only body, every answer's mapping, token reuse), the guard
  tests. `mvn -o clean test` **553 run / 0 failures / 0 errors / 2 skipped** (533 before).

### B2 (2026-11-18): producers. Implemented, compiled, tested.
- **One neutral event:** `common/event/Tell` `{kind, recipients, subject}` (kinds = `PushType` names, pinned by a test),
  published INSIDE the owner's write; `push/service/PushProducer` listens AFTER COMMIT (a rolled-back write tells nobody),
  per recipient, never throws back. No publisher imports `push`.
- **Where:** `ConversationService` (a private reply → REPLY_TURN to the other person; a plan message → PLAN_MESSAGE to the
  author and the people in, minus the writer and anyone blocked either way; only when the text check let the words
  through), `ResponseService` (I'm in → PLAN_ASK to the author, batched 2 min; Add → PLAN_ADDED with the plan's
  conversation id; Call it off → PLAN_CALLED_OFF to the people in; the follow-up to Say more → MORE_ADDED to the people who
  asked, only when visible), `PlanEvents` (This happened → PLAN_MESSAGE), `ConnectionService` (a new request →
  PEOPLE_REQUEST, subject `people`), `DeviceSessionService` (a new sign-in → ACCOUNT_SIGN_IN to the person's OTHER running
  phones, subject `account`), `DecisionService` (MEMBERSHIP_DECIDED, `membership`), `ContentModerationService` (hold /
  collapse / remove → CONTENT_DECIDED `m-<moment>` or `c-<conversation>`; Keep only when the author had already seen a
  notice, so a push never reveals a report; an appeal's outcome → `decisions`). Person decisions are not pushed.
- **"Once per plan until you open Replies":** `PushProducer` skips a plan message when a push about that plan reached the
  person since their own `replies_opened` time (no per-message read state).
- **Staff:** `StaffWaitingJob` (every 10 min): moderators and admins with the staff switch on, an open content case (and,
  for admins, an ID photo waiting); at most once an hour and only when something new arrived since the last one.
- **Tests:** `PushProducersTest` 5 (Tell kinds = push kinds; a plan end to end incl. batching, the once-per-plan rule
  and Call it off; private replies, Say more and the replies switch; a connection request and a new sign-in; a
  moderator's Keep says nothing, a Remove tells the author, the staff summary). `mvn -o clean test` **558 / 0 / 0 / 2**.
- **Not pushed (as designed):** Same, Say more requests, "set aside", a plan closing by itself, a connection accepted, a
  message released later by a moderator's Keep (it shows in Replies).

### B3 (2026-11-18): the Safety Circle in the app and On my way's second alarm. Implemented, compiled, tested.
- **Links:** `safetycontact/service/CircleLinkService` (+ `CircleLinkRepository`, `CircleLinkMuteRepository`), called by
  `SafetyContactService` on create / update / delete; `SafetyContactResponse.inApp`. A link counts only while the
  member's verified number is still the contact's number and the account is usable (checked on every read: a member
  moving to another number, or an owner editing the contact, ends it with no hook in `account` or `auth`). Requests are
  limited to 10 a day per owner, counted when made and in memory (from the rows, deleting and re-adding a contact reset
  the limit: found by the test and fixed); the owner's answer never changes.
- **Routes:** `circlealert/controller/CircleController` (`/api/circle/requests|in|alerts`).
- **The SOS:** `AlertQueue.claim` publishes `CIRCLE_SOS` for the accepted member behind a contact on the FIRST claim of
  that contact's first text: the text's moment (first location or 20 s), after "still active, contact still there" and
  the SMS limits, so a false alarm before then and a used-up day of rounds tell nobody, and a provider outage does not
  stop it (the push does not wait for the provider). **Deviation from §6.1 wording (planner time instead of claim
  time), same intent:** queuing at planning time would have pushed at 20 s even when the first location made the text
  go earlier, and a false alarm would have needed a later recall. `AlertQueue.membersTold` (the contact's first text
  claimed at least once) is the one answer for "who got it in the app", used by the end update and the member's view.
  `SosChangedListener` → `AlertQueue.sosEnded` (its own transaction: it runs after a commit, where a joined
  transaction would never fire the hooks of what it publishes; found by the test) sends ONE update when the SOS ends,
  nothing when it was ended from another sign-in.
- **The member's view:** `circlealert/service/CircleSosViewService`: 404 unless the caller is in `membersTold`; while
  active and 24 h after the end; the text's facts plus status; the map link of the last location a text actually carried.
- **On my way:** `JourneyService.remind` / `stopReminders`: start and More time replace the planned `OMW_REMINDER` rows
  (due, due + 5 min, `Tell.at`); I'm there, Stop and an SOS during the walk remove them (`Tell.forget`). `Tell` gained
  `notBefore` and `forget`; `PushProducer` maps them to `notifyAt` / `forget`.
- **Limits (documented):** a contact IRL cannot text at all (not an Indian mobile, a duplicate, beyond the first five,
  on the stop list at planning time) is not told in the app either; only people who chose automatic texts (N6) give
  their members the in-app SOS (the same consent).
- **Tests:** `CircleInAppApiTest` 6 (request → accept → `inApp`, a stranger 404, leave; no request for a number without
  IRL, your own number, a changed number, after Don't ask again; the silent daily limit; an SOS tells the member with
  its first text, the view and its 404s, once per SOS, one end update, gone after 24 h; a false alarm tells nobody,
  ended elsewhere sends no update; On my way rows at due and due + 5, More time replaces, I'm there and an SOS remove).
  `mvn -o clean test` **564 / 0 / 0 / 2**.
