# Phase 4A — Emergency location, permissions, notifications, recovery

Status: **implemented · compiled · tested** (automated) · emulator end-to-end run done by Claude ·
**your manual verification pending** (section 6).

## 1. What changed

During an ACTIVE emergency the phone's location is saved to the person's **own** emergency record on the server.
Nobody is notified and the location is sent to no one (Safety Circle messaging is Phase 4D).

| Area | Change |
|---|---|
| DB | `V4__create_emergency_locations_table.sql` |
| Backend | `EmergencyLocation` entity, `LocationSource`, `EmergencyLocationRepository`, `EmergencyLocationService`, `EmergencyLocationController`, DTOs, `EmergencyLocationProperties`, `ClockConfig` (injectable `Clock`), locking lookup `findByIdAndUserIdForUpdate` |
| Android core | `core/location` (`LocationProvider`, `FusedLocationProvider`, `LocationPermissions`, `GeoDistance`, models), `core/device` (`BatteryMonitor`, `ConnectivityMonitor`), `core/notifications` (`NotificationChannels`, `NotificationPermission`), `core/tracking` (`SafetyTrackingService`, `TrackingNotice`) |
| Android emergency | `EmergencyLocationSharing` (the one shared holder), `LocationSamplingPolicy`, `RemoteEmergencyLocationRepository`, `ActiveEmergencyStore` (DataStore), location card on the SOS screen, offline recovery in `EmergencyViewModel` |
| Manifest | location (fine + coarse), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_NETWORK_STATE`; service `SafetyTrackingService` (`foregroundServiceType="location"`, not exported); `dataExtractionRules` |
| Dependency | `com.google.android.gms:play-services-location:21.3.0` (the only approved one) |
| Text | Privacy / About / Safety Center / SOS notices updated: they used to say "no location is collected", which is no longer true |

## 2. Flow

```
SOS countdown reaches 0 → POST /api/emergencies (ACTIVE)
  → EmergencyViewModel.showActive(): LocationSharing.start(id) + ActiveEmergencyStore.save(user, id, startedAt)
  → screen asks location (once automatically, in context) → then notifications (Android 13+)
  → SafetyTrackingService.start()  (foreground, type=location, persistent private notification)
FusedLocationProvider (every 10 s) → LocationSamplingPolicy (first fix / moved > max(15 m, accuracy) / 60 s)
  → in-memory queue (+ battery %) → POST /api/emergencies/{id}/locations (≤ 50 per batch)
  offline / 5xx → keep, retry with backoff 5 s → 60 s, or at once when the network returns
  400 → drop + "check date and time"   409/404 → stop uploading, show the server's message   401 → session ends, stop
"I'm safe" / "False alarm" → LocationSharing.stop() (one last upload attempt) → service stops itself, store cleared
```

**Recovery after restart:** the SOS screen asks the server for history; an ACTIVE emergency resumes location saving.
If the server cannot be reached, the record on the phone (ids + start time only, same user only) is shown with an
"offline" notice and saving resumes; the server state wins as soon as it answers.

## 3. Decisions (and why)

| Decision | Why |
|---|---|
| No `user_id` in `emergency_locations`; ownership via the emergency | One source of ownership; a point can never belong to someone else than its emergency |
| `UNIQUE (emergency_id, recorded_at)` instead of a plain index (refinement of the plan) | Re-sending a batch after a lost response is normal for an offline phone; duplicates are skipped and `stored` < `received` |
| All-or-nothing batch, validated before any write | Simple to reason about; the phone never has to guess which points were stored |
| `SELECT … FOR UPDATE` on the emergency row during upload | Limit and duplicate checks stay correct with concurrent uploads |
| Server clock (`Clock` bean), 5-min tolerance, 15-min grace | Phone clocks drift; offline phones upload late |
| Points recorded after `endedAt` (+tolerance) rejected | Minimum data: no location after the person said they are safe |
| Upload queue **in memory only** (newest 500) | Precise locations are never written to the phone's storage; trade-off: lost if the process dies |
| Phone remembers only `{userId, emergencyId, startedAt}` | Enough to recover offline; no location on disk; ignored for another user; cleared on logout |
| No `ACCESS_BACKGROUND_LOCATION`; foreground service instead | Approved design; avoids Play review; the person always sees a notification |
| `START_NOT_STICKY` | A restarted service would have no while-in-use access; recovery on next app open instead |
| Notification never contains coordinates; `VISIBILITY_PRIVATE` | Lock screen privacy |
| Connectivity is a hint, uploads are still attempted when "offline" | Captive/flaky networks are misreported both ways |
| Sampling policy (15 m / accuracy / 60 s) | Battery, data and the 1000-point limit (~16 h stationary, ~4 h moving) |
| `dataExtractionRules` exclude everything | Android 12+ device transfer ignores `allowBackup=false`; token and record must not move to another phone |

## 4. What was run (2026-10-19, this machine)

| Check | Result |
|---|---|
| `mvn test` | **72 tests, 0 failures** (17 new in `EmergencyLocationApiIntegrationTest`) |
| V4 on real MySQL 8.0 (Flyway) | **Applied** (`Successfully applied 1 migration … now at version v4`) |
| V4 constraints, direct SQL in a rolled-back transaction | latitude/longitude/battery/accuracy/source CHECKs, UNIQUE, FK all rejected bad rows; µs precision kept; deleting the user cascaded to 0 rows |
| Live API on MySQL (curl-style smoke test) | 201 upload, re-send stored 0, latest, list, other user 404, no token 401, lat 91 → 400, future → 400, upload in grace → 201. Test accounts deleted afterwards |
| `gradlew testDebugUnitTest` | **137 tests, 0 failures** (60 new/updated: sharing holder, sampling, card, core helpers, view model) |
| `gradlew assembleDebug lintDebug` | Build OK; **lint 0 errors** |
| Emulator (API 37, Google Play image) | Auto location prompt after countdown; deny → "Allow location"; allow → notification prompt → foreground service `types=0x8`, private notification; points saved; app in background → new points saved; network off → "2 points waiting" → uploaded on reconnect; force-stop + offline relaunch → restored from phone record with offline notice, saving resumed; "I'm safe" → service and notification gone, no points after end; location switched off → "Turn on location", resumed when on. Test account deleted afterwards |

**Not verified:** a real phone; Android 8–13 devices (only API 37 emulator); OEM battery killers; permanent-denial path
and "Open settings" on a device (unit-tested only); approximate-only on a device (unit-tested only); Play services missing.

## 5. Required Phase 4 test cases covered in 4A

| Case | Test |
|---|---|
| Location unavailable | `EmergencyLocationSharingTest` › location temporarily unavailable…; LocationCardTest › signed out and unavailable… |
| Location permission denied | `EmergencyLocationSharingTest` › location permission denied…; `EmergencyViewModelTest` › location permission denied permanently…; `LocationCoreTest` › permission results… |
| Network unavailable | `EmergencyLocationSharingTest` › network unavailable - points are kept…; server problems are retried with backoff |
| Emergency recoverable after restart | `EmergencyViewModelTest` › emergency recoverable after restart (server) / while offline |
| Another user cannot access emergency | `EmergencyLocationApiIntegrationTest` › userB_cannotUploadToOrReadUserAsEmergencyLocations; `EmergencyViewModelTest` › another user's record… |
| Low battery handled (reader only; policy is 4C) | `EmergencyLocationSharingTest` › low or unknown battery level is reported as-is… |

Safe Walk, check-in, warning, escalation, "I'm Safe" for journeys and SMS cases belong to 4B–4D.

## 6. Manual verification checklist (your machine)

Backend: `mvn test`, then `mvn spring-boot:run` (Flyway should say schema is at version 4). Android: install the debug build.

1. Start SOS, let the countdown finish → the location dialog appears by itself. Choose **Don't allow** → card says
   location is *not* being saved, with **Allow location**.
2. Tap **Allow location** → **While using the app** → notification dialog (Android 13+) → **Allow**.
   Card: "Your location is being saved…", coordinates, "Last saved". Notification "Emergency active" is shown.
3. Lock the phone → the lock-screen notification shows **no coordinates**.
4. Press Home, walk (or change the emulator location) for a minute → `GET /api/emergencies/{id}/locations` grows.
5. Airplane mode → card "No connection… N points waiting"; airplane off → points upload, "Last saved" updates.
6. Swipe the app away / force-stop, airplane on, reopen, open SOS → emergency shown with the **offline** notice.
   Turn airplane mode off: the points upload.
7. Tap **I'm safe** → notification disappears; no new points after that.
8. Start SOS with phone location **off** → "Turn on location" opens settings; turning it on resumes saving.
9. Deny location twice → **Open settings** appears and opens SafeSphere's app settings.
10. Grant **Approximate** only → approximate hint shown, points still saved.
11. Second account on another phone/emulator: cannot read the first account's locations (404).
12. Settings → Privacy policy / About texts mention the location honestly.

## 7. Known limitations

- Tracking needs the foreground service; after the process dies it resumes only when the SOS screen is opened again
  (since 4B, Home shows an "Emergency active" banner that leads back to it).
- Points queued while offline are lost if the process dies (deliberately not stored on disk).
- No Safety Circle notification, no SMS, no 112 integration (4D, and 112 stays a user-tapped dial prompt).
- Precise location requires Google Play services; no fallback to the platform provider yet.
- Token in DataStore is still not encrypted (Phase 2 limitation).

## 8. Viva notes

- **Why a foreground service and not background location?** Android only lets apps without `ACCESS_BACKGROUND_LOCATION`
  receive location while visible *or* while a foreground service of type `location`, started from the foreground, runs.
  It is honest (persistent notification) and avoids Play's background-location review.
- **What happens offline?** Points queue in memory and upload in batches; the server de-duplicates and accepts late
  uploads for 15 minutes after the end.
- **Why not trust the phone's timestamps?** The server checks them against its own clock with a 5-minute tolerance
  and rejects points outside the emergency.
- **How is ownership enforced?** Every endpoint first loads the emergency with `findByIdAndUserId(id, jwt.sub)`; another
  user's id gives the same 404 as a missing one. `emergency_locations` has no user column to get wrong.
- **What if two uploads arrive at once?** The emergency row is locked (`FOR UPDATE`), and the unique key is a backstop.
- **Why is location not stored on the phone?** Minimum retention: a stolen/inspected phone does not reveal a track.
- **Lifecycle:** work runs in an application-scoped coroutine (survives leaving the screen); the service only keeps the
  process foreground; the ViewModel survives rotation, so the permission dialog is not shown twice.

---

## Appendix: approved Phase 4 design (moved verbatim from CLAUDE.md §11 on 2026-11-03)

Agreed with the user before 4A; 4A–4D are now built. The invariants are summarised in CLAUDE.md §11.

**Cross-cutting decisions**
- **Detection logic runs on the phone in pure Kotlin** (check-in deadlines, deviation, inactivity, Safety Bubble, battery
  policy, escalation policy): works offline and is unit-testable. The **server** stores journeys, validates state
  transitions, enforces ownership, and computes check-in deadlines (phone clocks drift).
- **One foreground service** (`SafetyTrackingService`, type `location`) during Safe Walk or an active emergency; persistent
  notification; started only while the app is visible. UI and service share state through one holder.
- **Location:** `play-services-location` (the only approved new dependency) behind a `LocationProvider` interface.
- **Emergency Context Packet** = a **read model assembled on demand** (emergency + latest location + journey + escalation
  events). **No `emergency_context` table.** Recipient-specific **projections are field allow-lists** (owner, Safety Circle,
  future official pathway, future Verified Nearby Assistance). Safety Circle sees only name, time, type, location link.
- **Escalation Ladder** = enum + pure policy, proportional, never jumps straight to the top:
  L0 normal · L1 missed check-in/minor concern → prompt user · L2 no response/anomaly → Safety Circle ·
  L3 confirmed SOS → emergency ACTIVE · L4 emergency pathway = **user-tapped dial prompt (112)**, no claimed integration ·
  L5 Verified Nearby Assistance = reserved name, **rejected by the server**.
- **Duress PIN** (silent trigger) remains a reserved trigger type; not part of Phase 4 unless re-planned.
- **SMS fallback:** auto-send only if the user opts in (then `SEND_SMS`); otherwise open the SMS composer for the user to send.
- **Safety Bubble / deviation baseline:** corridor around a straight start→destination line with generous tolerance;
  requires sustained deviation with acceptable GPS accuracy; **warns only**. Real route geometry belongs to Phase 8.
- **Privacy:** journeys store only the last-known location (no full GPS track).
- **Not in Phase 4:** server scheduler / push / dead-man alerts (no delivery channel yet — documented limitation),
  background location, `CALL_PHONE`, exact alarms, boot receiver, microphone, camera.

**Planned migrations (re-inspect the schema before writing)**
- **V4 `emergency_locations` (4A):** `emergency_id` FK cascade (**no user_id** — ownership via the emergency), latitude/longitude
  `DECIMAL(9,6)` with range CHECKs, `accuracy_meters`, `source` (FUSED, LAST_KNOWN, GPS, NETWORK, UNKNOWN), `battery_percent`,
  `recorded_at`, `received_at`, index `(emergency_id, recorded_at)`.
- V5 `safe_journeys` + `journey_events` (4B; check-ins are journey events; one non-final journey per user via generated marker).
- V6 `escalation_events` + nullable `journey_id` on `emergency_events` (4B/4C).

**Planned 4A API**
- `POST /api/emergencies/{id}/locations` — batch of 1–50 points (lets an offline phone upload later), all-or-nothing.
  Accepted while ACTIVE plus a 15-minute grace after the emergency ends; `recordedAt` not in the future (5-min clock tolerance)
  and not before the emergency began; per-emergency cap (configurable, e.g. 1000). Use an injectable `Clock` bean.
- `GET /api/emergencies/{id}/locations/latest` (404 if none), `GET /api/emergencies/{id}/locations` (newest 100).
- Owner only; other users → 404; JWT required.

**Planned 4A Android**
- `core/location` (provider interface + fused implementation; permission, availability and accuracy states; GPS off / denied /
  revoked / low accuracy / unavailable / temporary failure all handled), `core/device` (`ConnectivityMonitor`, `BatteryMonitor`),
  `core/notifications` (channels + notifier), emergency location association and upload, recovery not dependent on memory alone.
- New 4A permissions: `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` (at the moment of need, with rationale),
  `POST_NOTIFICATIONS` (Android 13+, before the first notification), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`,
  `ACCESS_NETWORK_STATE`.

**Phase 4 required test cases (map each to a named test):** Safe Walk start/complete/cancel; check-in success/missed;
warning; escalation; "I'm Safe" prevents escalation; location unavailable; location permission denied; network unavailable;
SMS unavailable; emergency recoverable after restart; another user cannot access emergency/journey; low battery handled.
