# Phase N6: Safety trim and automatic SOS alerts

**Status: APPROVED / DESIGN LOCKED / IMPLEMENTATION DEFERRED (owner, 2026-11-18).**
Reason for deferring: the Claude usage limit was already at about 90 % when the design was approved. Nothing of N6 is
implemented: no code, no migration, no test changed. Decisions D20–D25 are locked here and recorded as N-104 to N-109 in
`DECISIONS.md`. Units B1–B3, A1–A3, C, one local commit each; never pushed, never deployed; the pilot stays on the old
jar / V30.

## 1. What N6 is and why

IRL keeps a small, reliable, privacy-conscious safety layer (direction §2.7): SOS, the Safety Circle, a light walk
reminder. N-8 removes the phone's automatic `SEND_SMS`. Without a replacement, nothing would reach another person
unless the person taps Send, which defeats an SOS when the person is escaping or cannot use the phone (the owner's
objection to the first D20, 2026-11-18). N6 therefore:

- moves automatic SOS alerts to the **server** (provider SMS, no phone permission), keeping manual SMS as a fallback;
- removes what only made sense with automatic phone SMS or with tracking: route / stop / arrival checks, the walk's
  location upload and foreground service, shake SOS;
- turns Safe Walk into **On my way** (a reminder, no tracking);
- adds the plan offer ("Let someone know · On my way") inside Plans.

Inspected for this design (2026-11-18): `IRL_DIRECTION.md` §2.7, N-8, the N1 / N2 notes for N6, Android
`feature/emergency|safewalk|circlealert|safetycircle|safety|going`, `core/location|sms|tracking|device`, backend
`journey`, `emergency`, `emergencycontext`, `circlealert`, `safetycontact`, `auth/phone` (Fast2SMS), V2, V5–V8,
`REAL_DEVICE_SPRINT.md` (shake: screen off only "works sometimes", jogging false starts fixed).

## 2. Locked decisions (owner, 2026-11-18)

| # | Decision |
|---|---|
| D20 | **Automatic server-side SOS SMS** to the person's enabled Safety Circle contacts when an SOS reaches the server, through the provider SMS port (no `SEND_SMS`); the person chooses once, explicitly, with a preview; **manual SMS (the SMS app per contact), "Tell them you're OK" and Call 112 stay** as the fallback and extra. Details §3 |
| D21 | **Remove shake SOS** and the SOS options screen (reverses the direction's "opt-in shake"): screen-off shake was unreliable on the A23, it had jogging false starts, and with automatic alerts a false shake would text the family. SOS stays one tap away (tile, launcher shortcut, You) |
| D22 | **Replace Safe Walk with "On my way"**: "remind me in 10 / 20 / 30 / 45 / 60 min" + optional label; at the time a notification "There yet?" [I'm there] [More time]; one more nudge after 5 min with SOS / Call 112; then quiet. No location, no foreground service, no route / stop / arrival / battery checks, no destination lookup (it sent the typed destination to Google), no level 1 / 2 reports to the server. Reminders by an alarm that needs no permission (`AlarmManager.setAndAllowWhileIdle`), so they can be a few minutes late (the screen says so). "Let someone know" (share sheet, editable note, no location, no link) at start and at "I'm there". The server keeps the existing walk model (start, label, interval, check-ins = More time, end) without coordinates |
| D23 | **V37 (revised)**: drop `safe_journeys.destination_latitude/longitude` (+ their CHECKs); clear old values of the walk's last-location columns but KEEP the columns (for a possible later walk-alert phase); extend `circle_alerts` into the server alert queue (server channel, statuses QUEUED / SENT / DELIVERED / FAILED / SKIPPED, attempts, next attempt, provider message id, skip reason; no message text); the person's alert choice; the recipient stop list (numbers only as HMAC). Development MySQL first (backup, scratch dry run, apply); the pilot only in its own approved move |
| D24 | **"Let someone know · On my way" inside Plans**: one quiet row in a plan conversation (members only, while the plan is open, not called off), through the existing `feature/going` bridge (navigation only; social and safety never import each other). The note carries only the plan's "where" and time (never the author's words, never other people's names, no location, no link). On my way opens with the "where" prefilled (editable) |
| D25 | **The name "On my way"** in the app (code names stay `SafeWalk*`; no "safe" claim) |

Approved defaults inside D20 (the owner approved the design as proposed): recipients are told in advance **by the
person** (a free share-sheet note), not by an IRL notice SMS; "Test my alerts" exists; push for Circle members who use
IRL is N7; **automatic On my way timeout alerts are DEFERRED** to a later phase (§6).

## 3. Automatic SOS alerts (D20)

### 3.1 Flow
1. The SOS starts on the phone as today (5 s countdown, works offline, Phase 11). `SosRecorder` records it on the server
   at once when online; location points follow about every 10 s.
2. The server queues one alert per enabled contact (at most 5, by priority) and sends **at the first location or after
   20 s**, whichever comes first. The 20 s are also a last false-alarm window: "False alarm" before sending = nothing
   is sent.
3. No location by then: sent without one, then **one** follow-up with the location if it arrives within 10 min.
4. "I'm safe" / "False alarm" after an alert went out: one closing text (facts only, never "all is well"). An SOS
   **ended from another phone sends nothing** (existing rule: only the person on the phone can say they are OK).
5. An SOS that reaches the server more than **30 min** after it started (offline start) is not texted automatically;
   within 30 min it is, marked "sent late".
6. A dispatcher job (every 5 s, plus an immediate try at the first location) works a queue in the database, so it
   survives restarts; the provider is called outside any transaction (Phase 14 rule); refusals retry briefly, then
   FAILED.

### 3.2 Content (allow-list, plain ASCII, one 160-character part for names up to about 30 characters)
Name, the person's own number, time, what happened, a map link, "sent late". Nothing else: no free text from anyone,
so IRL cannot be used as an SMS gateway. Example (131 characters):
`IRL SOS: Asha Devi pressed SOS at 9:41 PM. Location: maps.google.com/?q=10.81234,78.69012 Call 9876543210 now. No answer? Call 112.`
Kinds: SOS_STARTED, SOS_LOCATION (follow-up), SOS_RESOLVED ("ended the SOS at … and marked it I'm safe. Call them if
you are worried."), SOS_CANCELLED ("marked the SOS as a false alarm …"). Wording final in B2; no pronouns guessed.

### 3.3 Privacy
- The person chooses once (no preselection) when adding the first contact, seeing the exact text; can switch off.
- Only enabled contacts; only Indian mobile numbers (the provider's limit); other numbers show "text them yourself".
- Location leaves the phone only in an SOS, never in On my way or plan notes.
- Alert rows keep kind, contact, status, times, provider message id: never the text. They go with the emergency or the
  account (`AccountEraserCoverageTest` must cover any new table).
- The SMS provider sees the text (name, number, map link); Fast2SMS says it reviews Quick SMS traffic. The privacy
  notice names the provider and says exactly what is sent, when and to whom.
- Recipients: the app asks the person to tell their contacts in advance (free share-sheet note). An admin can put a
  number on the stop list (HMAC only) to stop all IRL alerts to it; the public request path comes with N8's grievance
  contact.
- Nobody can monitor anyone: alerts only on the person's own SOS; no location on request. Nothing logs a number, a
  text or a location.

### 3.4 Limits (counted in the database, not in memory; configurable)
| Limit | Default |
|---|---|
| Contacts texted per SOS | 5 (by priority; the circle holds up to 10) |
| SOS alert rounds per person per day | 5 |
| Alert SMS to one number per day, across all senders | 10 |
| Server-wide alert SMS per day | 100 (about Rs 700) |
| "Test my alerts" | once per person per day |
| One real SOS with 3 contacts | 6–9 SMS, about Rs 42–63 |

Over a limit: not sent, and the phone says "text them yourself".

### 3.5 Failure handling (what the SOS screen says)
| Situation | Automatic text | Screen |
|---|---|---|
| No mobile data (SMS signal only) | impossible | "No internet: IRL can't text your Circle. Text them yourself (works without internet) or call 112." |
| Server unreachable (laptop off, tunnel down) | impossible | the same, after about 15 s without an answer |
| Provider refuses / wallet empty / spam rule | FAILED | "IRL couldn't text <name>. Text <name> yourself." |
| Provider accepted | SENT | "Texted by IRL at 9:41 PM · delivery not confirmed" |
| Delivery report (only if Fast2SMS offers one; to check in B2) | DELIVERED | "Delivered 9:42 PM"; never claimed otherwise |

Manual Text stays on every contact row. The screen reads alert status while open (new
`GET /api/emergencies/{id}/alerts`; unknown statuses read as "not confirmed").

### 3.6 Manual messaging (kept)
Text per contact through the SMS app (one at a time: no group MMS, which would show everyone's numbers), "Tell them
you're OK" after an SOS for contacts texted by hand, Call 112 (dial prompt), and **"Test my alerts"** in "Before you
need it": the server sends the Circle message to the person's own number (proves the whole chain without bothering
anyone).

### 3.7 Fast2SMS (as recorded in the repo)
- One provider adapter for codes and alerts (refactor `Fast2SmsOtpSender` into a shared client) so the
  outbound-connection guard (`ModelPlacesTest`) stays at two; a development file sink (production refuses it, as for
  codes); a stub provider for tests (like `StubModerationService`).
- Quick SMS: `POST https://www.fast2sms.com/dev/bulkV2`, header `authorization`, JSON `{route: "q", message, numbers}`;
  no DLT, numeric / random sender, about Rs 7 per SMS, first Rs 100 top-up and wallet balance needed (status 999 / 416),
  Indian numbers only, plain ASCII 160 characters = one billed part. Real codes passed on the pilot 2026-11-09/10; the
  key lives only in the server environment.
- To measure before relying on it: seconds from SOS to SMS, the provider's review of Quick SMS, its repeated-SMS spam
  rule (status 995) on three texts to one number within minutes, whether delivery reports exist. Provider rules change
  (the OTP route was closed); the port allows switching (DLT route, MSG91, Twilio).

## 4. Other changes

- **Remove (Android):** the phone's automatic SMS and `SEND_SMS` (`AndroidSmsSender`, `SmsResultReceiver`,
  `AutoSmsViewModel`, the automatic parts of `CircleAlertPolicy` / `CircleAlertCoordinator`), `MovementPolicy`,
  `WalkLine*`, the walk's battery rules (`BatteryPolicy`; `BatteryMonitor` stays for SOS), `DestinationResolver` /
  `GeocoderDestinationResolver`, the walk's location stream and its use of `SafetyTrackingService` (the service stays
  for SOS), shake (`feature/emergency/trigger`), `SosOptionsFragment` / store, dead helpers (`GeoHash`, `PreciseFix`).
  One-time clean-up of stored opt-in / shake / alert-memory values (`core/retired` pattern). About 2,500 lines (estimate).
- **Remove (backend):** walk location, walk escalation and walk circle-alert endpoints; destination in the walk start and
  answers. Kept: walk start / get / check-ins / complete / cancel, SOS link, the account rules.
- **Fixes from earlier notes:** no permission dialog may open during the SOS countdown (N1 note; confirm which dialog
  first); "Before you need it" asks for location and notifications in context ahead of time; the tile / shortcut on the
  idle SOS screen starts the countdown (N2 note).
- **Wording:** SOS screen states (§3.5); About ("a privacy-focused safety app" → the IRL line); privacy notice (walk,
  route, SMS paragraphs; names the provider); Settings "Safety Circle and automatic SMS" → "Safety Circle"; the tile's
  description; the `going_*` strings for plans.
- **Guard gap:** `GoingThereTest`'s social↔safety isolation scans only `feature/social`; widen it to `moments`,
  `replies` and the `safety` package.
- **Permissions after N6:** INTERNET, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION (SOS only), POST_NOTIFICATIONS,
  FOREGROUND_SERVICE, FOREGROUND_SERVICE_LOCATION (SOS only), ACCESS_NETWORK_STATE. No SEND_SMS, CAMERA, RECORD_AUDIO,
  exact-alarm or boot permission.

## 5. Units (one local commit each; Gradle, Maven and the emulator ONE AT A TIME: the PC runs out of memory)

| Unit | Content |
|---|---|
| B1 | Backend walk trim: start without destination; remove walk location / escalation / circle-alert endpoints; answers without coordinates; tests; `API.md` |
| B2 | Backend alerts: pure `AlertPolicy` (timing, follow-up, stale, closing, ended elsewhere, caps, Indian mobile, length), dispatcher job + queue, the shared Fast2SMS adapter, dev file sink (prod refuses), stub provider, `GET /api/emergencies/{id}/alerts`, the choice and test-to-self endpoints, admin stop list; V37 written (held with `spring.flyway.target=36` until B3) |
| B3 | V37 on the development MySQL (backup, dry run on a scratch copy, apply; real-MySQL tests); live run with the dev sink |
| A1 | Android SOS side: remove phone automatic SMS and shake + SOS options; the choice with preview; alert status per contact; offline / server-down fallback; "Tell them you're OK"; "Test my alerts"; readiness asks in context; countdown and tile fixes; one-time clean-up; guards |
| A2 | Android On my way (D22): alarm reminders and notification actions that work after process death, share-sheet notes, texts, privacy-notice paragraphs |
| A3 | Android plans row via `GoingThere` (D24), widened isolation guard, About / Settings / tile wording |
| C | Full runs, lint, security / privacy review, emulator walk incl. upgrade from the N5 build, live SMS tests, real-phone checks, documents (`STATUS.md`, `CLAUDE.md`, HANDOFF, rules files) |

## 6. Deferred (not N6)

- **Automatic On my way timeout alerts** (dead-man: no answer by due + 10 min → the server texts the Circle, also when
  the phone is dead): designed as opt-in per walk; needs the walk's latest location on the server again (only when
  chosen) and has a false-alarm cost (forgotten walks, check-ins with no signal). Build only after SOS alerts pass on a
  real phone, with its own approval.
- **Push to Circle members who use IRL (N7):** in-app request "Asha wants you in her Safety Circle"; pushes only after
  acceptance; data-only push with an alert id, details fetched over the API, no location in the push; the adder never
  learns whether a number has IRL unless that person accepts.
- Voice call to the primary contact, WhatsApp, an expiring live-location link, the exact-alarm permission, a boot
  receiver, a phone-only Safety Circle.

## 7. Validation plan

- **Policy (pure):** send at first location or 20 s; nothing after a false alarm before sending; one follow-up; nothing
  for an SOS older than 30 min; closing only after an alert; nothing when ended elsewhere; every cap; Indian-mobile check;
  one part for names up to 30 characters; only allow-listed fields reach the text.
- **Backend:** stub provider; the queue survives a restart; refusal → retry → FAILED; two-user ownership of alert status;
  the eraser removes queue rows; production refuses the dev sink; logs carry no number, text or location; walk API
  without coordinates; V37 on real MySQL (CHECK changes with binary collation).
- **Android:** reminder schedule (pure, reusing `CheckInPolicy` without concerns), alarm receiver, status mapping fails
  closed, fallback after about 15 s without the server, manual Text always present, countdown never asks a permission,
  tile on the idle SOS screen; guards: exact permission list; no `SmsManager`, accelerometer or `Geocoder`; location only
  in SOS; notes carry no location / link / names; honesty texts; isolation across all social packages.
- **Counts:** about 90 Android tests belong to removed features, so the total (804) drops even with new tests; say so.
- **Full runs, one at a time:** `mvn -o clean test`; `gradlew --offline clean testDebugUnitTest assembleDebug`;
  `lintDebug`.
- **Emulator:** fresh-install first SOS; tile / shortcut; Text per contact; alert states with the dev sink; On my way with
  the app killed (adb) — the reminder still arrives; plan row → share sheet / On my way; upgrade from the N5 build.
- **Live (needs the owner, about Rs 50–100):** "Test my alerts" to oneself; one full SOS to a second own number (start,
  location, I'm safe) with the seconds from SOS to SMS measured; airplane mode; backend stopped; the spam rule.
- **Real phone (A23):** reminder lateness with the screen off for 30 min; first SOS on a clean install; TalkBack on the
  countdown.

## 8. Limits that remain (say them in the app and the viva)

- No data connection = no automatic text; only `SEND_SMS` could close that gap (reopening N-8).
- The laptop is the server: alerts are only as available as the laptop and the tunnel; the phone notices and falls back.
- The provider is unproven for emergencies until §3.7 is measured.
- Legal: texting third parties' numbers (DPDP Act) and SMS rules (TRAI) must be checked before the pilot (N8); this
  design is not legal advice.

## As built

### B1 (2026-11-18): backend walk trim. Implemented, compiled, tested.
- `journey`: `StartJourneyRequest` = label + interval only (old coordinate fields ignored by Jackson, never stored);
  `JourneyResponse` without `destination` / `lastLocation`; `SafeJourney` no longer maps the destination or last-location
  columns (V37 drops the first and clears the second); `JourneyService` = start, history, get, check-in, complete,
  cancel, `linkActiveJourney` (`escalate`, `updateLocation` and their DTOs `EscalationRequest`,
  `JourneyLocationRequest` removed). A check-in still brings a walk an older phone left on level 1 / 2 back to 0.
- `circlealert`: `POST /api/journeys/{id}/circle-alerts`, `uploadForJourney`, `CircleAlert.forJourney`,
  `countByJourneyId` removed; walk kinds and the 4C escalation reasons stay as enum values for old rows (the owner's
  context packet still lists them).
- Tests: `JourneyApiIntegrationTest` 22 → 13 (route / stop / location / ladder tests removed; new: the removed
  endpoints answer 404, a walk answer has exactly the allowed fields, old coordinates are ignored, an old level is
  reset by a check-in); `CommunicationApiIntegrationTest` 12 → 11 (walk report paths removed; an old walk alert row is
  inserted with SQL to keep the context-packet coverage). `mvn -o clean test`: **501 run / 0 failures / 0 errors / 2
  skipped** (511 before; the difference is the removed walk tests).
- `API.md`: section "Phase N6", a note on Phase 4B, appendix line.

### B2 (2026-11-18): server-side Safety Circle texts. Implemented, compiled, tested. V37 written, NOT applied.
- **SMS port:** `common/sms/SmsGateway` (send → provider reference), `Fast2SmsClient` = the ONE Fast2SMS HTTP client
  (moved out of `Fast2SmsOtpSender`, which now only formats the code; `ModelPlacesTest` pins `ModerationServiceClient` +
  `Fast2SmsClient`, still two), `DevFileSmsGateway` (texts to a local file with the last 4 digits only; default);
  `circlealert/service/AlertGatewayConfig` picks it from `app.alerts.provider`. Tests: `support/StubSmsGatewayConfig`.
- **Rules:** pure `AlertPolicy` (timing, follow-up, closing, too late / late, retries, who is texted, limits order, the
  allow-listed texts, ASCII names, one 160-character part, the preview). Texts as built:
  `IRL SOS[ (sent late)]: <name> pressed SOS at 9:41 PM. Location: maps.google.com/?q=<lat>,<lng> | Location not known yet. Call <number> now. No answer? Call 112.`;
  `IRL SOS update: <name> was here at <time>: <link> Call <number> now.`;
  `IRL: <name> ended the SOS at <time> and marked it I'm safe. Call <number> if you are worried.`;
  `IRL: <name> marked the SOS at <time> as a false alarm. Call <number> if you are worried.`;
  `IRL test, not an emergency: your Safety Circle alerts work. In an SOS each contact gets your name, the time, a map link and your number.`
- **Queue:** `AlertQueue` (plan / dueIds / claim / result, each its own transaction), `AlertDispatcher` (scheduled every
  5 s, `kick()` after `SosChanged` = recorded / first location / ended, `@TransactionalEventListener` AFTER_COMMIT in
  `SosChangedListener`; one run at a time; logs counts and the provider status only), `AlertKeys` (HMAC of numbers),
  `CircleAlert` factories `queued` / `skipped` / `test` + `claim` / `sent` / `refused` / `skip`; enums `SkipReason`,
  channel `SERVER_SMS`, statuses `QUEUED` / `SKIPPED`, kind `TEST`. `emergency/event/SosChanged` (emergency never
  imports circlealert). New repository reads in `CircleAlertRepository`, `EmergencyEventRepository.findByStatusAndCreatedAtAfter`.
- **Read / choose:** `SafetyAlertService`, `SafetyAlertController` (`/api/safety-alerts`, `/test`,
  `/api/emergencies/{id}/alerts`), `AlertStopAdminController` (`/api/admin/alert-stops`, `/lift`, `/check`),
  `AlertDtos`; entities `AlertSetting`, `AlertStop`. Phone reports may no longer use the server's channel / statuses / kind.
- **V37** (`V37__n6_walk_trim_and_server_alerts.sql`): §1 walk destination columns dropped (CHECKs first), last-location
  values cleared; §2 `circle_alerts` closed lists to binary + new columns (`user_id` FK cascade, `recipient_key`,
  `included_location`, `attempts`, `next_attempt_at`, `sent_at`, `provider_ref`, `skip_reason`) and CHECKs (phone row vs
  server row, QUEUED ⇔ next attempt, SKIPPED ⇔ reason with `COALESCE`, sent time only on SENT / DELIVERED, hex key);
  §3 `alert_settings`; §4 `alert_stops`. Held with `spring.flyway.target=36` until B3.
- **Elsewhere:** `AccountEraser` (+ `circle_alerts.user_id`, `alert_settings.user_id`, `alert_stops.added_by` SET NULL),
  `ProductionGuard.alertProblems` (FAST2SMS + own `ALERT_SECRET`), `config/AlertProperties` (`app.alerts.*`),
  `SchedulingConfig` note (the one scheduled task that sends messages). Decisions N-110 to N-113.
- **Not built (honest limits):** delivery reports (SENT = accepted by the provider); the phone side (A1).
- Tests: `AlertPolicyTest` 12, `ServerAlertApiTest` 16 (choice unset sends nothing; first location / 20 s; foreign
  number skipped; false alarm before sending; follow-up once; closing once; ended elsewhere; too late / sent late; first
  five + duplicate number; refusal → retry → FAILED; a claim before a crash is retried from the database; per-number
  limit across senders; 404 for another person; phone cannot report server states; Test my alerts once a day; stop list
  (admin only, HMAC only, shown as NOT_AVAILABLE); erasure), `AlertLogsTest` 2, `ProductionGuardTest` +1,
  `Fast2SmsOtpSenderTest` +1. `mvn -o clean test`: **533 run / 0 failures / 0 errors / 2 skipped**.

### B3 (2026-11-18): V37 on the development MySQL; live run with the development sink. Applied, verified.
- `spring.flyway.target=36` removed. Backup `db-backups/safesphere-dev-before-v37-20261118.sql` (38 tables).
- Pre-check: Flyway at 36; every name V37 drops exists once; the 14 alert rows are all phone rows (no row changes meaning).
- Rehearsal of the whole migration on `zz_` copies of `safe_journeys` / `circle_alerts` with the real rows (constraint
  names renamed, the walk FK pointed at the copy): no error; 46 walks and 14 alerts kept; destination columns gone; 9
  old walk locations cleared. Dry run: 44 cases (13 accepted + 31 refused), 0 findings (2 values were refused by an
  earlier CHECK than the one aimed at: lower-case `sent` by `ck_circle_alerts_server_row` / `ck_circle_alerts_sent`);
  cascades: deleting a walk takes its alert rows; the FK rules read back as designed (user CASCADE, stop list SET NULL).
  Scratch tables dropped (0 left).
- Applied by Flyway at start-up (0.32 s), schema v37, **40 tables**. The two "integer display width is deprecated"
  warnings come from `TINYINT(1)` (as in V31), harmless.
- **Live run (backend 8090, real MySQL, `ALERT_PROVIDER=DEV_FILE`), account 401, contacts "Live Test" (an own test
  number) and a US number:** settings UNSET with the exact preview and "NOT_INDIAN_MOBILE" for the US number; choice
  ON; SOS; no text for 4 s without a location; the first location → the text written to the dev file at once
  (`kick` after commit), map link `maps.google.com/?q=10.81234,78.69012`; `GET …/alerts` SENT + SKIPPED; I'm safe → the
  closing text within 1 s; Test my alerts → the own number (`******5555`), a second one 429; a phone report with
  `SERVER_SMS` 400; another account reading the alerts 404; no token 401. The backend log holds no number, text, name
  or coordinate (searched), only "Safety Circle texts: 1 accepted, 0 not accepted".
- Real-MySQL test `MySqlTimestampStorageTest` (`SAFESPHERE_MYSQL_TESTS=true`): 2 / 0.
- Database afterwards restored from `db-backups/safesphere-dev-after-v37-before-b3-walk-20261118.sql` (V37, 40 tables,
  nothing from the walk left: 0 SERVER_SMS rows, 0 alert settings, no contacts on 401). Backend stopped.
- Not measured here (needs the owner and real SMS, step C): seconds to a real phone, the provider's review and spam rule.

### A1 (2026-11-18): Android SOS side. Implemented, compiled, tested. NOT manually verified.
- **Removed:** the phone's automatic SMS (`core/sms` = `AndroidSmsSender`, `SmsResultReceiver`, `SmsSender`;
  `CircleAlertPolicy`; `AutoSmsViewModel`; the automatic parts of `CircleAlertCoordinator`: walk watching, the alert
  memory, the SMS results), `SEND_SMS` and both receivers in the manifest; shake SOS (`feature/emergency/trigger`, 5
  files), the SOS options screen (`SosOptionsFragment` / view model / layout / nav destination / Settings and hub rows),
  `SosOptions` + `DataStoreSosOptionsStore`; `EmergencyViewModel.shakeEvents`, `EmergencyFragment.ARG_TRIGGER`, the
  MainActivity shake collector. `EmergencyTrigger.SHAKE` stays only as a value of older records.
- **One-time clean-up:** `core/retired/SafetyTrimRetirement` deletes `files/datastore/sos_options.preferences_pb`; the
  circle-alert record no longer reads its old opt-in and memory fields and is saved again without them at the first
  sign-in after the update (`CircleAlertCoordinator.onSession`).
- **Server alerts:** `feature/circlealert/domain/ServerAlerts.kt` (choice, plans, statuses, kinds, reasons; every unknown
  value fails closed: UNSET, "not confirmed", OTHER; pure `ServerAlertLines.lineFor` / `closingTexted` /
  `unreachable` (15 s)), `data/remote/SafetyAlertApi`, `data/RemoteSafetyAlertRepository`, `AppContainer.safetyAlertRepository`.
- **Safety Circle screen:** the switch card became "Automatic texts from IRL" (`SafetyAlertsViewModel`): the exact first
  text from the server, the status (not chosen / on / off / could not check), the contacts IRL will not text and why
  (stop-list numbers only as "IRL can't text this number"), "Yes, text them for me" / "No, I'll text them myself"
  (nothing preselected), "Tell them in advance" (share sheet, plain note, no location, no link), "Test my alerts" (once a
  day, its state; the 429 shown as "once a day").
- **SOS screen:** polls `GET /api/emergencies/{id}/alerts` every 5 s while visible (and 2 min after the end for the closing
  text); per-contact lines "IRL is about to text them" / "Texted by IRL at … · delivery not confirmed" (+ "sent late") /
  "Delivered …" / "IRL couldn't text them. Text them yourself." / "Not confirmed"; what the person sent by hand stays
  "SMS app opened · check that you sent it". The notice says on / off, or after about 15 s without an answer from IRL
  (local SOS not yet on the server, or the alert call failing): "No connection to IRL: it can't text your Circle. Text
  them yourself (works without internet) or call 112." "Tell them you're OK" after the end, for the people texted by
  hand that IRL did not send a closing text to. Manual Text stays on every contact row; Call 112 unchanged.
- **Fixes from the N1 / N2 notes:** the SOS screen asks for location and starts the tracking service only once the SOS
  is ACTIVE, never during the 5 s countdown; "Before you need it" asks for location (`LocationAsk`) and notifications
  (with `RuntimePermissionAsk.needsSettings`) ahead of time; the shortcut / tile used while IRL was left on the idle SOS
  screen now starts the countdown there (`MainActivity.onNewIntent` → `EmergencyFragment.onLaunchRequested`).
- **Ready check:** the SMS row became the alert choice row (on / off / not chosen / unknown; only with at least one
  contact); location and notification rows ask instead of opening Settings.
- **Details screen:** `AlertChannel.SERVER_SMS` → "texted by IRL (not confirmed)".
- **Guards:** `N6SafetyGuardsTest` (the exact N6 permission list; no `SmsManager` / `SEND_SMS` / sensor code; the countdown
  asks nothing; the SMS app is the only phone path); `Phase17FGuardsTest`, `RuntimePermissionAskTest`, `HonestyTextsTest`
  trimmed to what still exists. Tests rewritten: `CircleAlertCoordinatorTest` (11), `CommunicationScreenTest` (server lines
  fail-closed, the choice card, SOS polling and the 15 s fallback), `ReadyCheckTest`; new `SafetyTrimRetirementTest`.
- `gradlew --offline testDebugUnitTest`: **757 run / 0 failures** (804 before A1: about 50 tests of the removed shake,
  phone SMS and policy went, new ones came); `assembleDebug` OK. Not yet on a device or emulator (step C).
