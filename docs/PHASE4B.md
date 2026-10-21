# Phase 4B — Safe Walk, check-ins, "I'm Safe", escalation levels 0–3

Status: **implemented · compiled · tested** (automated) · emulator end-to-end run done by Claude ·
**your manual verification pending** (section 7). Note: 4A's own manual verification (docs/PHASE4A.md §6) is also still open.

## 1. What changed

| Area | Change |
|---|---|
| DB | `V5__create_safe_journeys_tables.sql` (`safe_journeys`, `journey_events`), `V6__create_escalation_events_and_link_emergencies.sql` (`escalation_events`, `emergency_events.journey_id`) |
| Backend | packages `journey` (entity/repository/service/controller/dto) and `escalation` (`EscalationLevel`, `EscalationReason`, `EscalationEvent`); `EmergencyService.start` links an active walk; `EmergencyResponse.journeyId` |
| Android | `core/escalation/EscalationLevel`; `feature/safewalk/{domain,data,session,ui}`: `CheckInPolicy` (pure), `SafeWalkSession` (the one holder), `AndroidSafeWalkAlerts` + `CheckInActionReceiver`, `SafeWalkFragment/ViewModel`; `core/tracking`: `CompositeTrackingNoticeSource`, `TrackingServiceStarter` (shared by SOS and Safe Walk) |
| UI | Safe Walk screen; Home + Safety tab: Safe Walk / Check-in are now **Available**; Home active-session banner |
| Manifest | non-exported `CheckInActionReceiver`. **No new permission.** New notification channel "Safe Walk check-ins" (high importance) |
| Text | Privacy policy, About and Safety Center describe Safe Walk data honestly |

## 2. Flow

```
Start (destination text optional, interval 2/5/10/15/30 min)
  → location asked (moment of need) → notifications asked → POST /api/journeys
  → server: ACTIVE, nextCheckInDueAt = now + interval, returns serverTime
  → phone: localDue = phoneNow + (due − serverTime)   ← a wrong phone clock does not matter
CheckInPolicy(localDue, now):
  ON_TRACK ─(1 min before)→ DUE_SOON [reminder] ─(deadline)→ MISSED = L1 [alert + "I'm OK"; POST escalation 1]
  ─(+2 min)→ NO_RESPONSE = L2 [red card + alert; POST escalation 2]   — never higher by itself
"I'm OK" (screen or notification) → local timer restarts at once → POST check-ins (server: level 0, new deadline)
SOS (button on the walk, or any SOS) → server ends walk as EMERGENCY, records L3 SOS_CONFIRMED, emergency.journeyId set
"I've arrived" / "Cancel" → ended on the phone at once → PUT complete|cancel (kept as "pending end" until confirmed)
Location: last known only, every ≥2 min or ≥25 m (PUT …/location, server overwrites)
```

## 3. Decisions

| Decision | Why |
|---|---|
| Server computes deadlines, returns `serverTime` | Approved design ("phone clocks drift"); counting down *time left* makes the phone clock irrelevant |
| Policy is a pure, stateless function of (deadline, now) | Works offline, survives restarts (recomputed from the stored deadline), easy to unit-test and explain |
| Automatic ladder stops at L2; L3 only via a person-confirmed SOS; L4 rejected (4D); L5 reserved | "Proportional, never jumps to the top" |
| Server checks "missed" against its **own** deadline (30 s allowance) | Plausibility; found in the live test that reusing the 5-min clock tolerance would let a 2-min walk escalate at once — fixed + regression test |
| Escalation reports idempotent (≤ current → 200), no skipping (400) | Offline phones resend safely |
| Any SOS during a walk links it **server-side by the JWT user** | No client-supplied journey id ("never trust client ownership") |
| Offline-first commands (ordered queue, backoff) + "pending end" persisted | "I'm OK" and "I've arrived" must work without network; nothing lost on restart |
| Phone stores ids, interval, start, local deadline, pending end only | No destination, no location on disk |
| One foreground service, composite notice (emergency > walk) | Approved "one service"; no duplication |
| Escalation events in their own table (V6), check-ins as journey events (V5) | As planned; ready for the 4D Emergency Context Packet |
| "At least one of journey/emergency" enforced in code, not a CHECK | MySQL forbids CHECKs on columns with cascading FKs (verified) |

## 4. Creative additions (evaluated before building)

| Addition | Why it is worth it | Trade-off |
|---|---|---|
| **Home active-session banner** (emergency first, then walk; overdue walk shown in amber) | After the app is killed, tracking can only resume from a visible screen (Android 12+). Without this the person would not know a walk/emergency is still running. Closes the gap listed in PHASE4A §7. | Home reads one small local record; a stale record shows until the SOS screen reconciles it with the server. |
| **"I'm OK" on the check-in notification** | The most frequent safety action becomes one tap without unlocking/opening the app — fewer false escalations. Works even if the process died (recovers first, `goAsync`). | Anyone holding the unlocked phone can tap it — same as the in-app button. A duress PIN (reserved) is the real answer later. |
| **Any SOS links the walk server-side** | One SOS flow (with its countdown) everywhere; the walk ends and L3 is recorded automatically; no client-side journey id to trust. | An SOS started from Home also ends the walk — intended (the emergency takes over). |

Considered and **not** added: a "timer-only, no location" walk (needs a non-location foreground service type — not in the
approved design); fake/decoy call; ETA sharing (4D); arrival auto-detect (4C/8). See section 8.

## 5. What was run (2026-10-19, this machine)

| Check | Result |
|---|---|
| V5/V6 dry-run on scratch tables (real MySQL 8.0) | all CHECK/UNIQUE/FK rules rejected bad rows; cascade OK; scratch tables dropped |
| Flyway on real MySQL | `Successfully applied 2 migrations … now at version v6` |
| `mvn test` | **88 tests, 0 failures** (16 new in `JourneyApiIntegrationTest`) |
| Live API smoke test on MySQL | start, 409 second walk, check-in, location 204, other user 404, level 4 → 400, SOS links (`journeyId`), walk → EMERGENCY level 3. Found + fixed the 30 s tolerance bug. Test users deleted |
| `gradlew testDebugUnitTest` | **180 tests, 0 failures** (43 new: `CheckInPolicyTest` 6, `SafeWalkSessionTest` 23, `SafeWalkScreenTest` 9, Home 4, emergency hook 1). One real race found and fixed (pending end written after the server had already confirmed) |
| `assembleDebug` + `lintDebug` | OK, 0 lint errors |
| Emulator (API 37, Play image) | location asked on Start; notification asked next; walk ACTIVE on server (2 min, label, last location); foreground service on; app in background → "You missed a check-in" heads-up with "I'm OK" (server level 1); tapping it → level 0, alert gone; no answer → "No response" (level 2), red card; force-stop + relaunch → Home recovered the overdue walk and re-alerted; SOS from the walk → emergency `journeyId` set, walk EMERGENCY level 3, the one service switched to the emergency notice. Full ladder in DB: 1,0,1,2,0,3. Test user deleted |

**Not verified:** real phone; Android 8–13; Doze/OEM killers over long walks; offline paths on a device (unit-tested only);
denied-location walk on a device (unit-tested only).

## 6. Required Phase 4 test cases (4B part)

| Case | Test |
|---|---|
| Safe Walk start / complete / cancel | `SafeWalkSessionTest` › Safe Walk start…, complete…, cancel…; `JourneyApiIntegrationTest` › start_…, complete_…, cancel_… |
| Check-in success / missed | `SafeWalkSessionTest` › check-in success…, check-in missed…; `CheckInPolicyTest`; API › checkIn_success…, missedCheckIn_… |
| Warning | `CheckInPolicyTest` › warning…; `SafeWalkSessionTest` › warning… |
| Escalation | `SafeWalkSessionTest` › escalation…; `CheckInPolicyTest` › escalation…, never above 2; API › escalation_goesOneStepAtATime…, levels3To5… |
| "I'm Safe" prevents escalation | `SafeWalkSessionTest` › I'm Safe prevents escalation…, I'm Safe after a missed check-in…; API › imSafe_afterAnEscalation… |
| Location permission denied | `SafeWalkSessionTest` › location permission denied… |
| Network unavailable | `SafeWalkSessionTest` › network unavailable…, ended while offline… |
| Recoverable after restart | `SafeWalkSessionTest` › walk recoverable after restart (server / offline / unsent check-in) |
| Another user cannot access | API › userB_cannotSeeOrChangeUserAsWalk; `SafeWalkSessionTest` › another user's walk record… |

## 7. Manual verification checklist

1. `mvn spring-boot:run` → Flyway reports v6. Install the debug build.
2. Home → Safe Walk. Type a destination, pick **2 min**, Start → location dialog → notifications dialog.
3. Countdown runs; "Last location saved" appears; status bar shows "Safe Walk active".
4. Lock the phone: lock-screen notifications show **no destination or location**.
5. Wait for "Check-in due soon" (1 min before) and "You missed a check-in"; tap **I'm OK** on the notification → countdown restarts.
6. Don't answer: after 2 more minutes, red "No response" card + alert. Check the text says nobody is contacted.
7. Swipe SafeSphere away, reopen → Home banner (amber) → opens the walk.
8. Airplane mode, tap I'm OK → no alerts at the old deadline; airplane off → server shows the check-in.
9. Tap SOS on the walk → countdown → emergency; `GET /api/emergencies` shows `journeyId`.
10. New walk → I've arrived (confirm) → "You have arrived". New walk → Cancel walk (confirm).
11. Deny location on Start → walk still runs; hints say location is not shared and to keep the app open.
12. Second account: cannot read the first account's walk (404).

## 8. Future recommendations (not built; belong to later phases)

| Idea | Phase |
|---|---|
| Notify Safety Circle at L2, SMS fallback, Emergency Context Packet, L4 dial prompt (112) | 4D |
| Route deviation, unexpected stop, Safety Bubble, battery policy | **done in 4C** (docs/PHASE4C.md) |
| Destination coordinates + arrival hint (4C, never auto-complete); real route geometry | 4C done / 8 |
| Server-side dead-man alert when the phone goes silent (needs a delivery channel) | after 4D (documented limitation) |
| Timer-only walk without location (needs a non-location foreground service type, re-plan) | re-plan |
| Duress PIN that "checks in" while silently escalating | re-plan (reserved trigger) |
| Decoy/fake call, discreet mode | product decision, not in roadmap |
| Walk history screen (data already stored: `GET /api/journeys`) | small follow-up |

## 9. Known limitations

- Check-ins and escalation run on the phone: if the phone dies or the process is killed (OEM battery killers), nothing
  escalates until the app is opened again. No server scheduler/push exists yet (documented Phase 4 limitation).
- Without location permission there is no foreground service, so reminders are reliable only while the app is open.
- If the phone's clock is changed during a walk, the local countdown shifts by the same amount until the next server answer.
- Level 2 only alerts the person; nobody else is contacted until 4D.

## 10. Viva notes

- **Why does the phone, not the server, decide escalation?** It must work offline and without push; the server
  validates and records (and refuses impossible reports such as "missed" before its own deadline).
- **How does a wrong phone clock not break deadlines?** The phone counts down `due − serverTime` from its own clock.
- **Why never automatically to level 3?** Starting an emergency is a strong action; the ladder is proportional, so
  level 3 needs a confirmed SOS.
- **What if the person is offline?** "I'm OK" and "I've arrived" act immediately; an ordered queue sends them later;
  a pending end survives restarts.
- **Privacy:** only the last location (overwritten), no track; destination text is optional and never on the device's
  disk or in notifications.
