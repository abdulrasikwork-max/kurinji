# Phase 4C — Safety Bubble, unexpected stop, arrival hint, battery policy

Status: **implemented · compiled · tested** (automated) · V7 on real MySQL · emulator end-to-end run (route deviation path)
done by Claude · **manual verification by the user run 2026-10-19/20 (§8.1): not fully verified**. Pass: 1, 2, 3, 4, 7,
10, 12. Partial: 5 (re-prompt after returning not verified), 11 (locked-screen text not verified). Server side
confirmed but phone side not reported: 6, 8. Not reported: 9.
4A and 4B manual verification (docs/PHASE4A.md §6, docs/PHASE4B.md §7) are also still open.

## 1. What changed

| Area | Change |
|---|---|
| DB | `V7__add_journey_destination_and_anomaly_reasons.sql`: `safe_journeys.destination_latitude/longitude` (`DECIMAL(9,6)`, range CHECKs, both-or-none CHECK); `escalation_events` reason CHECK replaced to add `ROUTE_DEVIATION`, `UNEXPECTED_STOP` |
| Backend | `StartJourneyRequest` optional `destinationLatitude/Longitude`; `JourneyResponse.destination`; `EscalationRequest.reason` (optional); `JourneyService.escalate` reason rules; `SafeJourney.end()` deletes coordinates on COMPLETED/CANCELLED; `EscalationReason` +2 |
| Android pure logic | `feature/safewalk/domain/MovementPolicy` (Safety Bubble, unexpected stop, arrival, GPS quality), `CheckInPolicy` now takes an open `Concern`, `core/device/BatteryPolicy`, `core/location/RouteGeometry` + `GeoPoint` |
| Android platform | `core/location/DestinationResolver` + `GeocoderDestinationResolver` (platform Geocoder, API 33 async / older blocking on IO, 6 s timeout, 50 km search box); `BatteryMonitor.isCharging()` |
| Android session | `SafeWalkSession`: destination lookup at start, movement checks on every fix, concern → early prompt, battery loop (60 s), last-gasp location; `ActiveJourneyRecord` + `movementChecks`, `concern` |
| UI | Setup switch "Watch for unusual stops and route changes" (on by default); active screen: concern texts, arrival hint, movement/battery lines (`movementLinesFor`, pure), on/off toggle; new notifications "Are you OK?" and "Battery very low" |
| Text | Privacy policy explains the map lookup, destination coordinates and deletion; Safe Walk feature description |
| Manifest / dependencies | **No new permission, no new dependency.** |

## 2. Flow

```
Start (destination text, interval, switch "Watch for unusual stops and route changes" = on)
  → location asked (4B) → one fix (≤ 3 s) → Geocoder("Station", 50 km box around the fix) (≤ 6 s)
      found      → POST /api/journeys {…, destinationLatitude, destinationLongitude}
      not found / unavailable → walk starts anyway; only stops are watched; the screen says why
Every fix (30 s; 60 s when the battery is low) → MovementPolicy.onFix(state, fix, destination)   [pure]
  Safety Bubble: corridor around the straight start→destination line, half-width 40 % of the route
                 (min 250 m, max 2 km); "outside" only if (distance − accuracy) > half-width;
                 sustained = ≥ 3 fixes and ≥ 90 s → ROUTE_DEVIATION
  Unexpected stop: within max(60 m, accA + accB) (≤ 200 m) of one spot for 10 min → UNEXPECTED_STOP
                 (not at the destination; a 3-min gap without fixes restarts the timer)
  Arrival: 2 fixes within max(80 m, accuracy) of the destination → hint "Tap I've arrived" (never auto-ends)
  Fixes worse than 100 m (or without accuracy) are ignored → "GPS weak: checks paused"
New concern (only if no prompt is already open) → Concern(kind, now) → CheckInPolicy:
  MISSED = L1 at once (POST escalations {level 1, reason}) → no answer for 2 min → NO_RESPONSE = L2
  never higher by itself; "I'm OK" answers it (check-in) and the same condition is not asked again
  until it clears (back inside the bubble for 2 fixes / moved away from the spot)
Battery (every 60 s) → BatteryPolicy: LOW ≤ 20 %, CRITICAL ≤ 10 % (leave +5 points), charging = NORMAL
  LOW/CRITICAL → location every 60 s; entering CRITICAL → one notification + the latest fix is sent once
```

## 3. Decisions

| Decision | Why |
|---|---|
| A movement concern opens the **same** "Are you OK?" prompt early (L1), and L2 still needs no answer | Approved ladder: "L1 missed check-in/minor concern → prompt user; L2 no response/anomaly". The bubble only **warns** (it never starts an emergency, never contacts anyone, never goes above L2). Without this, an abduction off-route on a 30-min walk would wait up to 32 min. **Confirmed by the user on 2026-10-19.** |
| Concerns are reported with a reason (`ROUTE_DEVIATION`, `UNEXPECTED_STOP`) and may come before the deadline | The server cannot see the movement; it records what the phone saw. `ROUTE_DEVIATION` needs a walk with a destination; reasons must match their level; missed check-ins still wait for the server's own deadline |
| Corridor around a straight line, generous, accuracy-aware, sustained | Approved baseline; streets are not straight; real route geometry is Phase 8. False alarms erode trust, so every rule leans against them |
| Weak / missing GPS pauses the checks and says so | "Never guess": a phone in a bag or a tunnel must not raise an alarm, and the person must know the checks are paused |
| "I'm OK" suppresses only the condition that is true now | A known detour or a long coffee stop is asked about once; a new departure or stop is asked about again |
| Destination coordinates stored on the server, not the phone | The phone stores no destination or location (4B rule). After a restart they come back with the walk; offline-restored walks get them with the next server answer |
| Coordinates deleted when a walk ends normally; kept for an EMERGENCY walk | Minimum retention (privacy rule): once the person is safe nobody needs where they were going; the emergency may need it |
| Route start = first usable fix (after a restart: where the walk resumed) | No start position on disk; a resumed corridor is still a valid baseline |
| Movement state in memory only (persisted: the switch + an open concern's kind and time) | No track on the phone. An open prompt must survive a restart |
| Arrival is a hint, never an automatic "I've arrived" | Ending monitoring automatically near the door is exactly when someone could be in danger |
| Battery: no change to the check-in interval; emergency (SOS) location is not throttled | A shorter interval does not help after the phone dies; during SOS the location matters more |
| Geocoder behind an interface, failures are values, bounded search 50 km around the person | CLAUDE.md gotcha: Geocoder is unreliable; it also avoids a far-away namesake |

## 4. Creative additions (evaluated before building)

| Addition | Why it is worth it | Trade-off |
|---|---|---|
| **"Last-gasp" location** when the battery becomes critical | If the phone dies, the server's last known location is as fresh as possible (ready for the 4D Emergency Context Packet) | One extra upload |
| **Arrival hint** ("You seem to be at your destination") + suppressing stop alarms there | Reminds the person to end the walk, and avoids a false "you have stopped" at home | Hint only (deliberately) |
| **Shown destination address** ("Destination found: …") + on/off toggle during the walk | The person can see if the map lookup picked the wrong place and switch the checks off instead of getting false alarms | One more line on the screen |
| **Deleting coordinates at the end of a normal walk** (also the 4B last location) | Privacy rule "minimise location retention" — applied to existing data too | History of a finished walk no longer shows where it ended (the app never showed it) |
| **Honest GPS-quality line** | The person knows when the checks are paused | — |

Considered and **not** added: motion-sensor inactivity (sensors are throttled in the background; belongs with Phase 9),
destination picker on a map (needs a map dependency; Phase 8), lowering the check-in interval on low battery (no benefit
after the phone dies), automatic "I've arrived" (unsafe).

## 5. Required Phase 4 test cases (4C part)

| Case | Test |
|---|---|
| Warning (movement) | `SafeWalkMovementTest` › route deviation - level 1 with its reason…; `MovementPolicyTest` › route deviation… |
| Escalation | `SafeWalkMovementTest` › route deviation… then level 2…, never higher; `CheckInPolicyTest` › an unanswered concern reaches level 2…; API › anUnexpectedStop_isLevel1BeforeTheDeadline… |
| "I'm Safe" prevents escalation | `SafeWalkMovementTest` › I'm OK answers the prompt…; `MovementPolicyTest` › I'm OK answers whatever is true right now |
| Location unavailable | `SafeWalkMovementTest` › location unavailable or weak GPS never raises a concern; `MovementPolicyTest` › inaccurate fixes never count… |
| Low battery handled | `BatteryPolicyTest` (4 tests); `SafeWalkMovementTest` › low battery handled…, ending the walk clears the battery warning |
| Recoverable after restart | `SafeWalkMovementTest` › an open concern and the destination are recovered…, the destination comes back from the server… |
| Another user cannot access | API › userB_cannotReportAConcernOnUserAsWalk_orSeeItsDestination |

## 6. What was run (2026-10-19, this machine)

| Check | Result |
|---|---|
| `mvn test` (H2) | **94 tests, 0 failures** (6 new in `JourneyApiIntegrationTest`) |
| Flyway on real MySQL 8.0.46 | `Successfully applied 1 migration … now at version v7` |
| V7 constraints, direct SQL in a rolled-back transaction | latitude 91, longitude 181, one coordinate only, reason `BOGUS` → all rejected by their CHECKs; `ROUTE_DEVIATION` / `UNEXPECTED_STOP` accepted; rollback left 0 rows |
| Live API smoke test on MySQL | **16/16 pass**: start with destination (rounded to 6 dp), half destination 400, other user 404 (read + concern), missed-before-deadline 409, `ROUTE_DEVIATION` L1, L2 wrong reason 400, L2 200, L3 400, check-in → 0, complete deletes coordinates + last location (label kept), route deviation without destination 400, `UNEXPECTED_STOP` L1, SOS walk keeps destination. Test users deleted |
| `gradlew testDebugUnitTest` | **221 tests, 0 failures** (41 new: `MovementPolicyTest` 14, `SafeWalkMovementTest` 14, `BatteryPolicyTest` 4, `CheckInPolicyTest` +3, `SafeWalkScreenTest` +5, 4B tests unchanged and passing) |
| `assembleDebug` + `lintDebug` | OK, **0 lint errors**, no new warnings in 4C files |
| Emulator (API 37, Google Play image), real backend + MySQL | Walk to "Bangalore City railway station" from central Bengaluru: **platform Geocoder found it** ("Destination found: Bengaluru City Railway Station…", "about 2.8 km to go"); emulator moved ~1.2 km sideways → after ~2 min "You seem to have left the area around your route" (server: level 1 `ROUTE_DEVIATION`); no answer → red "No answer to “Are you OK?”" (server: level 2 `NO_RESPONSE`); I'm OK → normal (level 0) and **no repeat prompt** while still off-route; I've arrived → server walk COMPLETED with destination coordinates and last location **deleted**, label kept. Test account deleted, emulator app data cleared |

**Not verified:** a real phone; Android 8–13; unexpected stop, arrival hint, battery policy and restart-with-open-concern
**on a device** (unit-tested only); Geocoder on a phone without Google services; long walks under Doze/OEM killers.
Runtime permissions were granted with `adb` for the emulator run (the in-app prompts are unchanged from 4A/4B).

## 7. Known limitations

- The bubble is a straight-line corridor: a long legitimate detour (river, closed road) can still prompt once.
  Real route geometry is Phase 8.
- The platform Geocoder may be missing (some phones / emulators without Google services) or pick a wrong place;
  the walk then runs with stop checks only, and the screen says so. The typed text goes to the phone's map service.
- Everything still runs on the phone: if the phone dies or the process is killed, nothing escalates until the app is
  opened again (no server scheduler / push in Phase 4).
- A route check resumed after a restart uses the resume point as the corridor start.
- An unexpected stop of less than 10 minutes, or movement inside a 60–200 m circle (e.g. a large building), is not detected.
- Level 2 only alerts the person; telling the Safety Circle is 4D.

## 8. Manual verification checklist

1. `mvn spring-boot:run` → Flyway reports **v7**. Install the debug build.
2. Safe Walk → type a real nearby place (e.g. a station), keep the switch on, 30 min, Start →
   "Destination found: …" line with the address and "about N km to go".
3. Emulator: Extended controls → Location → move the point ~1 km sideways and keep it there (or play a route):
   after ~2 min "Are you OK? You seem to have left your route" (screen + heads-up with I'm OK).
4. Don't answer: 2 min later the red "No answer to “Are you OK?”" card; `GET /api/journeys/{id}` → `escalationLevel` 2.
5. Tap I'm OK → normal; stay off-route → no new prompt; move back to the route, then off again → prompted again.
6. Keep the location fixed for 10 min (switch on, no destination) → "You have not moved for a while".
7. Move to the destination → "You seem to be at your destination" hint; the walk keeps running until you tap I've arrived.
   Then `GET /api/journeys/{id}` → no `destination`, no `lastLocation`.
8. Type nonsense as destination → "could not be found on the map, so only stops are watched".
9. `adb shell dumpsys battery set level 15` → "Battery low" line; `level 8` → "Battery very low" notification once;
   `adb shell dumpsys battery reset` afterwards.
10. Turn the switch off at start → "Stop and route checks are off for this walk"; no prompts except check-ins.
11. Lock screen: the alerts show only "SafeSphere needs your attention" (no route, no place).
12. Force-stop during an open "Are you OK?" prompt, reopen → Home banner → the prompt is still open.

### 8.1 Manual verification results (by the user, started 2026-10-20)

| # | Check | Result | Notes |
|---|---|---|---|
| 1 | Flyway v7, debug build installed | **pass** | Backend on V7; debug build installed; Pixel 7 emulator, Android 17; signed in with the user's own account |
| 2 | Destination found, distance shown | **pass** | "Trichy Junction" from ~16 km away (Tiruchirappalli): found as "Tiruchirappalli Junction Bus Stop", route check on, "about 16.0 km", Safe Walk notification visible. Finding: the Geocoder matched a bus stop rather than the railway station. This is the documented "may pick a wrong place" limitation, and showing the address let the user notice it. Harmless here (the 2 km bubble covers it) |
| 3 | Off-route prompt (screen + heads-up) | **pass** (screen) | Walk 14 (10-min interval). Emulator moved about 4 km off the route; the amber in-app warning appeared. Server: level 1 `ROUTE_DEVIATION`. Heads-up notification and delay not reported |
| 4 | No answer → red card, server level 2 | **pass** | Red card "No answer to “Are you OK?”" shown; no SOS started. Server: level 2 `NO_RESPONSE` exactly 2:00 after level 1 (13:47:09 → 13:49:09 UTC); no emergency for this user |
| 5 | I'm OK; no repeat while off-route; re-prompt after returning | **PARTIAL**: A pass · B pass · C **failed / inconclusive** | Times below in real UTC (raw DB values are 5 h 30 m behind, see note). **A** (walk 14): I'm OK → `CHECKED_IN`, level 0 at 19:21:34. **B**: no repeat warning while off-route. **C, first attempt** (walk 14): the 10-min check-in ran out (`CHECK_IN_MISSED` 19:31:34, `NO_RESPONSE` 19:33:34) with no second `ROUTE_DEVIATION`; while a check-in prompt is open, no route prompt opens (by design). At 19:36:39 the phone was still at ≈10.6932, 78.8946, not the suggested route/off-route points. An earlier report that C passed was **corrected by the user: C did not work**. **C, re-test** (walk 15, "Mannarpuram, trichy" → 10.7861, 78.6904, 30 min): `ROUTE_DEVIATION` 19:45:21 → `NO_RESPONSE` 19:47:21 → I'm OK `CHECKED_IN` 19:47:50; **no second `ROUTE_DEVIATION`** up to 20:02:45. Last saved fix 20:01:21 at ≈10.6846, 79.0510, about 40 km from the destination, far outside the 2 km bubble. **Assessment:** no re-prompt is expected unless the phone first came back *inside* walk 15's bubble. The suggested "route midpoint" (10.7698, 78.6162) was computed for **walk 14's route** and is outside walk 15's corridor. Only the last location is kept (privacy design), so the server cannot show whether the phone was ever inside. A code defect is **not demonstrated**, but re-arming is **not manually verified**; it remains covered by unit tests only (`MovementPolicyTest` › after the person is asked…; `SafeWalkMovementTest` › I'm OK answers the prompt…). Retest needs points computed for the walk actually running | A: I'm OK → server `CHECKED_IN` (level 0) at 13:51:34 UTC. B: no repeat while off-route. C: the check-in deadline passed during the test (`CHECK_IN_MISSED` 14:01:34, `NO_RESPONSE` 14:03:34), and by design no route prompt opens while a check-in prompt is open. No second `ROUTE_DEVIATION` reached the server before the deadline. The last saved location (≈10.6932, 78.8946) was not one of the suggested test points, so whether the bubble re-armed cannot be told |
| 6 | Unexpected stop after 10 min | server side confirmed; phone side not yet reported | Walk 16 (no destination, 30 min), started 20:05:03 UTC; level 1 `UNEXPECTED_STOP` 20:15:07 (10 min 4 s), level 2 `NO_RESPONSE` 20:17:07 (2:00 later), no emergency. Location ≈10.684620, 79.050988, accuracy 5 m (20:17:06). The suggested `geo fix` was not applied, but the emulator stayed in one place, so the test is valid. I'm OK `CHECKED_IN` 20:19:30; cancelled 20:22:53 |
| 7 | Arrival hint; coordinates deleted after I've arrived | **pass** | User: Check 7 complete (phone side). Server: walk 17 "Mannarpuram, trichy", started 20:28:41 UTC, **COMPLETED** 20:34:26; destination coordinates and last location **NULL** (deleted); label kept; no escalations (no false stop or route warning at the destination). Arrival hint and "You have arrived" screen are phone-only, confirmed by the user |
| 8 | Nonsense destination → only stops watched | server side confirmed; phone side not yet reported | Walk 18 "zzqxv qqwxz 98765", ACTIVE at 20:46 UTC with destination coordinates **NULL** (never sent). Stops still watched: `UNEXPECTED_STOP` 20:57:33, then I'm OK 20:58:21; cancelled 21:10:21. On-screen fallback line not yet reported |
| 9 | Battery low / very low | not yet reported | Ran on walk 18. The "last location" upload cannot be checked on the server any more: walk 18 was cancelled, which deletes its last location by design |
| 10 | Switch off → no movement prompts | **pass** | User: phone side passed. Server: walk 19 "Mannarpuram, trichy" (switch off, 30 min), started 21:12:41 UTC, ACTIVE at 21:24:55; destination coordinates **NULL** (not looked up); level 0, **no escalation events** although the phone sat ≈9.7 km from the destination at 10.803498, 78.603498 (last fix 21:24:16, 11+ min after the start) |
| 11 | Lock screen shows no route/place | **PARTIAL**: notification content pass; lock-screen public version **not verified** | Walk 19 (switch turned back on): server level 1 `UNEXPECTED_STOP` 21:36:48 UTC, then `NO_RESPONSE` 21:38:48. User: the warning appeared, and the notification contains no destination, address or coordinates. The emulator has no secure lock screen (screen lock "None"), so the redacted public version ("SafeSphere needs your attention") shown on a locked device could not be observed. A screenshot was mentioned but none reached this session. In the code, the alerts use `VISIBILITY_PRIVATE` with that public version, but this is not manually verified |
| 12 | Open prompt survives force-stop | **pass** | User: phone side passed (force-stop → reopen → prompt restored → I'm OK). Server, walk 19: `UNEXPECTED_STOP` 21:36:48 → `NO_RESPONSE` 21:38:48 → **no duplicate escalation events** from the restart, and none when the original deadline (21:42:41) passed while the prompt was open → I'm OK `CHECKED_IN` 21:48:47, level 0, new deadline 22:18:47 (30 min). Still ACTIVE, phone still sending its location (21:50:26), no active emergency |

**Finding during manual verification (not a 4C regression, not fixed yet):** raw `DATETIME(6)` values in MySQL are
**5 h 30 m behind real UTC** (e.g. an event at 19:21:34 UTC is stored as 13:51:34). The backend's UTC `LocalDateTime` is
treated as JVM-local (IST) time and converted to UTC again by the JDBC driver (`serverTimezone=UTC`, no
`hibernate.jdbc.time_zone`). The API round-trip is consistent, so the app's behaviour is correct. But the stored data
breaks the "stored in UTC" convention, and anything reading the DB directly (reports, future server jobs) would be off.
The fix needs a decision about shifting existing rows.
**Update 2026-10-20:** root cause reproduced, code fixed and existing rows corrected and verified; see `docs/TIMESTAMPS.md`. (The UTC times quoted in this document were already true UTC.)

## 9. Recorded for later phases (not built)

| Idea | Phase |
|---|---|
| L2 from a concern tells the Safety Circle *why* ("left the route" / "stopped"); Context Packet includes destination + last-gasp location | 4D |
| Battery-critical message to the Safety Circle (opt-in) before the phone dies | 4D |
| Real route geometry / safe-route corridor instead of a straight line; destination picked on a map | 8 |
| Motion-sensor inactivity (fall / phone dropped) | 9 (needs re-plan: sensors, `ACTIVITY_RECOGNITION`) |
| Learning the person's usual routes to widen/narrow the bubble | 8/9 (privacy review needed) |

## 10. Viva notes

- **Why is detection on the phone?** It must work offline and without push; the server validates the reported level
  and reason (e.g. no route deviation without a destination) but cannot see the movement.
- **How do you avoid false alarms?** Accuracy filter (≤ 100 m), accuracy-aware "clearly outside", sustained over
  ≥ 3 fixes and 90 s, generous corridor, weak GPS pauses the checks, one prompt per condition, arrival suppresses stops.
- **Why never higher than level 2 automatically?** Proportional ladder: an emergency needs a confirmed SOS.
- **What is a Safety Bubble?** A corridor (capsule) around the segment start→destination; distance from a point to
  a segment, computed on a local flat projection (error < 1 % at walking distances).
- **Privacy:** the phone keeps no track or destination; the server keeps the destination and the last location only,
  deleted at a normal end; the Geocoder sends the typed text to the phone's map service (disclosed).
- **Battery:** half the location rate when low, one warning and a last location when critical; SOS unaffected.
