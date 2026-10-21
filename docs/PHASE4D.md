# Phase 4D — Escalation communication: Safety Circle SMS, level 4, Emergency Context Packet

Status: **implemented · compiled · tested** (automated) · V8 on real MySQL · live API smoke test · emulator end-to-end
run (real SMS path) by Claude · **manual verification: NOT VERIFIED** — skipped by the user's decision on 2026-10-20;
every check in section 8 is unverified until run.
Earlier phases: 4A/4B manual verification still open; 4C manual verification **not fully verified** (docs/PHASE4C.md §8.1:
check 5 part C and check 11 partial, checks 6/8/9 not fully reported). 4D does not change any 4C detection logic.

## 1. What changed

| Area | Change |
|---|---|
| DB | `V8__create_circle_alerts_and_dial_prompt_reason.sql`: `circle_alerts` (log of what the phone told each contact: kind, channel, honest status, client reference UUID; **no phone number, no text**), escalation reason `DIAL_PROMPT_OPENED` |
| Backend | packages `circlealert` (entity, repository, service, controller, DTOs) and `emergencycontext` (`ContextAudience`, projections, service, controller); `EmergencyService.recordDialPrompt` (level 4); `EscalationEvent.forEmergency`; journey level-4 message |
| API | `POST /api/emergencies/{id}/circle-alerts`, `POST /api/journeys/{id}/circle-alerts`, `POST /api/emergencies/{id}/dial-prompts`, `GET /api/emergencies/{id}/context?audience=OWNER\|SAFETY_CIRCLE` |
| Android core | `core/sms`: `SmsSender`, `AndroidSmsSender` (SmsManager, multipart, sent/delivery results), `SmsResultReceiver` (not exported) |
| Android feature | `feature/circlealert`: `CircleMessages` (pure, the Safety Circle allow-list text), `CircleAlertPolicy` (pure: when to tell what), `CircleAlertCoordinator` (one holder), `DataStoreCircleAlertStore`, `RemoteCircleAlertRepository`, UI text mappings; `RecipientSyncingSafetyCircleRepository` (offline copy of enabled contacts) |
| UI | Safety Circle: "Text my Safety Circle automatically" card (off by default, rationale + SEND_SMS in context, example message). SOS: **Call 112** button (level 4), per-contact status + **Text** (SMS app). Safe Walk: honest "was / will / was not told" line + **Text my Safety Circle**. SOS failed panel: Call 112. New **Emergency details** screen (history → tap) from the context packet |
| Manifest | `SEND_SMS` (asked only on opt-in), `uses-feature telephony required=false`, `SmsResultReceiver` |
| Text | All "nobody is contacted / not sent to anyone" texts corrected (SOS, Safe Walk, location card, tracking notification, Safety Center, About, Privacy policy) |

## 2. Flow

```
Opt-in (Safety Circle screen): switch on → rationale → SEND_SMS → "On. N contact(s) will receive an SMS".
Enabled contacts are copied to the phone (DataStore) every time the list loads, so SMS works without internet.

Safe Walk reaches level 2 (no answer)          SOS ACTIVE (level 3, from the SOS screen)
  │ CircleAlertPolicy (pure, memory on phone)     │ waits ≤ 30 s for a first fix (not at all if none can come)
  ▼                                               ▼
"did not answer… not a confirmed emergency"     "started an emergency alert… <map link>"  (+1 location follow-up ≤ 10 min)
  │ person answers (I'm OK / arrived / cancel)    │ person ends it
  ▼                                               ▼
"responded … and is OK" (all clear)             "…marked themselves safe" / "…false alarm"
Automatic only if: opted in AND SEND_SMS AND SMS supported AND ≥1 enabled contact. Otherwise nothing is sent and
the screens say "not notified" and offer Text (SMS app, the person sends it).

Per contact: SENDING → SENT (left the phone) → DELIVERED (network report) | FAILED | COMPOSER_OPENED
  → persisted queue → POST …/circle-alerts (UUID reference: re-send harmless, status only moves forward)
Call 112 (SOS screen): ACTION_DIAL tel:112 (person presses call) → POST …/dial-prompts → escalation level 4 once
Emergency details: GET …/context (OWNER) → started/ended, highest level, walk destination, last location + map,
  escalations and every message with its honest status
```

## 3. Decisions

| Decision | Why |
|---|---|
| The **phone** sends the SMS; the server only records | Approved design (no server SMS gateway / delivery channel in Phase 4); works offline; no credentials or cost on the server |
| Automatic SMS **opt-in**, off by default; otherwise the SMS app opens and the person sends | Approved design. SEND_SMS is sensitive and Play-restricted |
| Google Play: SEND_SMS allowed under the **"Physical safety/emergency alerts to send SMS"** exception, needs a Permissions Declaration and review | Checked against the Play policy page (2026-10-20); recorded in the manifest and PLATFORM_CONSTRAINTS |
| Message text = **allow-list**: name, time, what happened, map link (+ its time) | Approved "Safety Circle sees only name, time, type, location link". `CircleMessages.compose` cannot receive other fields; the server's SAFETY_CIRCLE projection is the same allow-list (tested for exact field set) |
| A walk alert says "**not a confirmed emergency**"; an all-clear follows an answer | Proportional ladder; no panic longer than needed |
| SOS waits ≤ 30 s for a location; follow-up once; an SOS cancelled before the first message tells nobody | Useful first message without a long delay; accidental SOS stays private |
| No automatic alert for an emergency first seen > 30 min after it started; at most 3 walk alerts | A late or repeated SMS would mislead or flood |
| Alert memory persisted (no numbers/locations) | No duplicate SMS after a restart |
| Server log stores **contact id, never number or text**; another user's contact id is stored as unknown; contact deleted → `SET NULL` | Minimum data; ownership never revealed |
| Statuses: SENT ≠ DELIVERED ≠ COMPOSER_OPENED; SENDING is never reported to the server | CLAUDE.md §9 "SMS sent ≠ delivered" |
| Level 4 = user-tapped dialer (`ACTION_DIAL`), recorded as "dialer opened", only during an ACTIVE emergency, once | Approved "no claimed integration"; no CALL_PHONE permission |
| Emergency Context Packet = **read model on demand**, projections OWNER / SAFETY_CIRCLE; OFFICIAL_PATHWAY and VERIFIED_NEARBY_ASSISTANCE refused | Approved design; no context table; owner-only endpoint |
| Contacts cached on the phone (name + number), cleared on logout, excluded from backup | Needed for offline SMS; documented in the privacy text |

## 4. Creative additions (evaluated before building)

| Addition | Why | Trade-off |
|---|---|---|
| **All-clear SMS** after an answered walk alert, **resolved / false-alarm SMS** after an SOS | Stops contacts worrying (or rushing) needlessly; makes automatic alerts acceptable to turn on | One extra SMS per episode |
| **Location follow-up** when the first SOS message had no fix | The first message is fast, the second is useful | Max one extra SMS, only within 10 min |
| **Emergency details screen** (context packet) | Post-incident review: who was told what, when, with honest statuses; viva-friendly view of the packet | One read-only screen |
| **Example message** on the opt-in card | The person sees exactly what is shared before allowing SEND_SMS | — |
| **Call 112** also on the "could not record" panel | When recording fails (offline) the fastest real help is one tap away; no level is recorded (no emergency exists) | — |
| **"Delivered" wording** only when every message has a network delivery report | Honest, and reassuring when true | — |

Not added (recorded): server SMS gateway / push (needs provider, cost, consent: later phase), live location link page (needs a
public HTTPS deployment and revocable tokens), auto-retry of failed SMS (could spam; the Text button is the fallback),
contact-side acknowledgement ("I'm on my way" replies need READ/RECEIVE_SMS: not acceptable).

## 5. Required Phase 4 test cases (4D part)

| Case | Test |
|---|---|
| **SMS unavailable** | `CircleAlertCoordinatorTest` › SMS unavailable - no SMS support or a revoked permission…; › a message the phone could not hand over is FAILED…; `CommunicationScreenTest` › the opt-in card status…, turning the switch on asks for SEND_SMS first… |
| Escalation (communication) | `CircleAlertCoordinatorTest` › escalation communication - level 2 texts each enabled contact…; `CircleAlertPolicyTest` › escalation communication… |
| "I'm Safe" | `CircleAlertCoordinatorTest` › I'm Safe after the alert…, arriving after an alert…; `CircleAlertPolicyTest` › I'm Safe after an alert sends one all clear… |
| Network unavailable | `CircleAlertCoordinatorTest` › network unavailable - SMS still goes out, the server is told later… |
| Recoverable after restart | `CircleAlertCoordinatorTest` › alerts are recoverable after restart - never texted twice |
| Location unavailable | `CircleAlertPolicyTest` › no waiting when no location can come…; `CircleMessagesTest` › without a location the message says so… |
| Another user cannot access | API › userB_cannotReportOnUserAsEmergencyOrWalk…, anotherUsersContactId_isStoredAsUnknown…, unavailableAndUnknownAudiences…_andOtherUsersGet404, dialPrompt…andOnlyForTheOwner |
| Allow-list | `CircleMessagesTest` › allow-list…; API › safetyCircleContext_isAStrictAllowList |

## 6. What was run (2026-10-20, this machine)

| Check | Result |
|---|---|
| `mvn test` (H2) | **106 tests, 0 failures** (12 new in `CommunicationApiIntegrationTest`; 1 journey test updated for the new level-4 message) |
| Flyway on real MySQL 8.0.46 | `Successfully applied 1 migration … now at version v8` (your running backend was restarted to apply it) |
| V8 constraints, direct SQL, rolled back | kind / channel / status CHECKs, duplicate `client_ref`, unknown emergency (FK), unknown escalation reason: all rejected; a valid row accepted; rollback clean |
| Live API smoke test on MySQL | **17/17 pass** (report → DELIVERED update, other user 404, wrong kind 400, dial prompt 204 ×2 then 409 after end, owner context L4 + alert, SAFETY_CIRCLE exact field set, official pathway 400, **deleted contact → name gone, alert kept (MySQL SET NULL)**, walk composer report, walk L4 refused). Test users deleted |
| `gradlew testDebugUnitTest` | **261 tests, 0 failures** (40 new: `CircleAlertCoordinatorTest` 14, `CircleAlertPolicyTest` 12, `CircleMessagesTest` 7, `CommunicationScreenTest` 7). Writing `CircleAlertPolicyTest` found a real bug (an SOS cancelled before its first message could still send one later) — fixed |
| `assembleDebug` + `lintDebug` | OK, **0 lint errors**; lint first caught a format-argument misuse in two status lines — fixed |
| Emulator (separate API 37 AVD, throwaway account, real backend) | Opt-in: rationale → system SEND_SMS dialog → "On. 1 contact(s)…". SOS → **one real SMS via SmsManager** with map link; contact row "SMS delivered"; server `SOS_STARTED / SMS_AUTO / DELIVERED`. **Call 112** → dialer with 112 (no call), server level 4 `DIAL_PROMPT_OPENED`. I'm safe → "marked themselves safe" SMS. Emergency details showed the full timeline with honest statuses. 2-min Safe Walk → level 2 → "did not answer… not a confirmed emergency" SMS, red card + "was texted" line; I'm OK → all-clear SMS; server `WALK_NO_RESPONSE` + `ALL_CLEAR` DELIVERED. Found and fixed during the run: location card / tracking notification still said "not sent to anyone"; "1 points" plural; "delivered" wording. Test account deleted, test emulator cleared and shut down; **your Pixel 7 emulator was not touched** |

**Not verified:** a real phone with a SIM and a real recipient (the emulator's modem fakes sending and delivery reports);
dual-SIM / no default SMS subscription; carriers without delivery reports; the "I'm OK" **notification** action sending the
all-clear (my tap missed the heads-up; the in-app button was used; the shared code path is unit-tested); SMS app (composer)
on a device; process death between sending and the result (documented below); Android 8–13.

## 7. Known limitations

- SMS is sent by the phone: if the phone is off, has no signal or the process was killed, nothing is sent (no server
  gateway or dead-man alert yet). Level 2 on a walk only happens while the app runs (4B limitation).
- Results arriving after the process died are lost: the attempt stays "sending" on the phone and is **not** reported as
  sent. Emulator delivery reports are simulated; real networks may never send one ("delivery not confirmed").
- The Android permission dialog says "send **and view** SMS" (it names the whole SMS group); the app requests only
  SEND_SMS and never reads messages.
- A Play release needs the SMS Permissions Declaration (physical safety exception) and its review.
- Phone numbers are not country-aware (existing limitation): the SMS goes exactly to the stored number.
- Raw DB timestamps were 5 h 30 m behind UTC (pre-existing; root cause and fix in docs/TIMESTAMPS.md, code fixed 2026-10-20;
  existing rows corrected and verified the same day). The API and app were unaffected; V8 rows follow the same convention as the rest.
- If minification is enabled for a release, keep rules are needed for Gson DTOs and the stored snapshot classes.

## 8. Manual verification checklist (use a real phone with a SIM if possible, and a contact who agreed to receive tests)

**Result: all 12 checks NOT VERIFIED** (skipped by the user's decision, 2026-10-20). Only Claude's automated tests, MySQL
checks and emulator run in section 6 have been performed.

1. `mvn spring-boot:run` → Flyway **v8**. Install the debug build.
2. Safety Circle → card "Text my Safety Circle automatically": status Off, example message visible.
3. Switch on → rationale → **Don't allow** → stays Off, toast. Switch on again → **Allow** → "On. N contact(s)…".
4. Start SOS (location allowed) → within ~30 s each enabled contact gets **one** SMS with your name, time and a map link;
   rows say "SMS sent … delivery not confirmed" or "SMS delivered".
5. Tap **Call 112** → the dialer opens with 112 (do not call) → back. Tap **Text** next to a contact → SMS app opens
   with the message (send or discard).
6. **I'm safe** → contacts get "marked themselves safe". History → tap the emergency → details show started/ended,
   "Highest level reached: 4", the messages and "Dialer opened for 112".
7. Safe Walk, 2 min, no answer → after ~4 min contacts get "did not answer … not a confirmed emergency"; the red card says
   they are being texted. Tap **I'm OK** (try the notification button) → contacts get "responded … and is OK".
8. Switch automatic SMS **off** → repeat step 7 → nobody is texted; the card says "not told automatically" and
   **Text my Safety Circle** opens the SMS app for the contact you pick.
9. Airplane mode (SMS off too) during step 4 → rows show "SMS failed"; the server log shows FAILED after reconnecting.
10. Revoke SMS permission in Settings → card shows "SMS permission is off…"; an SOS sends nothing automatically.
11. Second account: cannot read the first account's context (404).
12. Log out → log in → the switch is Off again (opt-in and cached numbers were deleted).

## 9. Recorded for later phases (not built)

| Idea | Phase |
|---|---|
| Server-side SMS / push gateway and a dead-man alert when the phone goes silent | after 4D (needs provider, consent, cost) |
| Live location page for contacts (revocable HTTPS link) instead of a static map link | 6/8 (needs public deployment) |
| Contact acknowledgements ("on my way") | later (must not need READ_SMS) |
| Official pathway projection (e.g. police/ERSS integration) | only with a real, verified integration |
| Verified Nearby Assistance projection | 12 |

## 10. Viva notes

- **Why does the phone send the SMS?** There is no server gateway in Phase 4; SMS works without internet; the server
  validates and records (ownership, kinds, times, status order) but sends nothing.
- **How is privacy protected?** Opt-in; allow-list message; the server stores no number or text; contacts' numbers on the
  phone are app-private, not backed up, deleted at logout; the context packet's Safety Circle projection is an exact field set.
- **What does "sent" mean?** Only that the SMS left the phone. "Delivered" only with a network delivery report. The SMS app
  path is "SMS app opened" because the app cannot know whether the person pressed send.
- **Why is level 4 a dial prompt?** No real emergency-service integration exists; claiming one would be dangerous. The
  person presses call; the app records only that the dialer was opened.
- **What stops spam or false alarms?** One message per episode, max 3 per walk, all-clear/resolved follow-ups, an SOS
  cancelled within the location wait tells nobody, no late alerts for stale emergencies, memory survives restarts.
- **Emergency Context Packet?** A read model built on request from existing tables (emergency, latest location, walk,
  escalations, alerts) with audience projections as explicit allow-lists — nothing copied, nothing to go stale.
