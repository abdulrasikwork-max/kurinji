# Phase 17D — Location UX (2026-11-01)

**Implemented, compiled, tested, emulator-verified (API 37).** Physical phone: **not verified** (none connected, so
no real weak-GPS, indoor or approximate-vs-precise device run). No migration (next still **V28**), no backend change,
no new permission, no new dependency. Plan: `PHASE17_PLAN.md` (17D row). Written autonomously under the user's brief of
2026-11-01 (no approval step), following the plan below.

## Plan (written before the code)

Findings from reading the code (R4 and the rest of §1 of the plan):

1. **A report needs a position of 100 m or better (Phase 6), but the phone took the FIRST position that arrived and
   refused if it was rough.** Indoors the first reading is a 300 m Wi-Fi / cell one, so "too rough", with no way
   forward. The rule stays; what was missing is a short, visible search that keeps listening, then "move outside or wait
   for a better signal" + Try again. Never a looser threshold.
2. **Four screens asked for location in four ways.** Nearby had its own launcher; Safe Walk's buttons did nothing after
   a permanent denial; the place picker looped "Use precise location" → Android's dialog → still approximate → the same
   notice forever. One `LocationAsk` + one pure `LocationNeeds` now decide the single action to offer.
3. **Safe Walk dropped every location problem** (`collectLocation` ignored `Unavailable`): location switched off,
   or approximate-only (every fix is rougher than the 100 m the checks accept, so "weak GPS" for the whole walk) had
   no words.
4. **Post location** had "Places near me" only: no search, and the precise position needed for "Add this place"
   blocked the list.
5. A cached position from minutes ago, perfectly accurate and from somewhere else, would place a report in the wrong
   block. Position age must come from the platform's boot clock, never from comparing GPS time with the phone clock
   (the A23 was 74.6 s off, `CLAUDE.md` §12).

Rules kept: no new permission, no background location, coordinates never logged or sent (only 6/7-character cells; an
accuracy number such as "about 180 m" is shown, never a position), location used only while the screen is open,
`EmergencyLocationSharing` (SOS) untouched.

## What changed

| Area | Change | Key code |
|---|---|---|
| Core | `preciseFix`: listens for up to 25 s, returns `Good` (≤ 100 m, accuracy stated), `Rough(best)`, or the exact problem (`NeedsPermission`, `NeedsPrecisePermission`, `LocationOff`, `NotFound`, `Unavailable`); reports the best accuracy so far; cancellable; a rough result is never used | `core/location/PreciseFix.kt` |
| Core | `LocationProvider.freshUpdates`: drops positions older than 30 s by `elapsedRealtimeNanos` (a cached position is never used to place anything); default = `updates` | `FusedLocationProvider` |
| Core | `LocationNeeds.of(...)` → ALLOW / OPEN_APP_SETTINGS / TURN_ON / UPGRADE_PRECISE / UPGRADE_PRECISE_IN_SETTINGS (pure). The upgrade goes to settings only after the dialog **observably** came back approximate | `core/location/LocationNeed.kt`, `core/ui/LocationAsk` (`act`, `askForPrecise`, `preciseDeclined`) |
| Core | `LocationStreamPolicy` for continuous streams: searching → available → "no signal" after max(60 s, 3 × interval); off; needs permission; switch check every 5 s (`afterSwitch`) | `PreciseFix.kt` |
| Nearby | Area from `oneFix(areaOnly)` (works indoors); `approximateLocation` (permission) told apart from a rough position; "Updated 2:25 PM" / stale after 10 min; second button "Use precise location" (→ "Open settings" after a declined upgrade) | `CommunityViewModel`, `CommunityFragment` |
| Nearby | Report: checked **before** the category list (approximate / off / blocked → one dialog with its action); then a visible, cancellable "Looking for a precise position… about N m" search; rough → "Move outside or wait for a better signal" + Try again; answering/flagging a BLOCK signal = same gate, an AREA signal needs only an area position (the server judges nearness at the cell the signal is shown at); leaving the screen stops the search and says so on return | `CommunityViewModel.blockFixOrEvent`, `CommunityPolicy.needsBlockPosition` |
| Nearby | Location off → "Turn on location"; no fix → "Move outside or wait for a better signal" + Try again; no Play services → no button | `CommunityFragment.renderArea` |
| Safe Walk | `SafeWalkState.locationStatus`; hints for off / no signal / unsupported (each says "Check-ins still work"); movement lines: approximate = "need precise location, paused" (was "weak GPS" forever), off = paused; one action button during the walk; set-up panel line + button (precise / approximate / off / not allowed); start fix via `oneFix` (no 3 s wait when location is off); **a location problem never changes the phase, a concern or the escalation level** (pinned by a test) | `SafeWalkSession.collectLocation`, `WalkHints.kt`, `SafeWalkFragment` |
| Safe Walk | "Check the way" fix via `oneFix` (instant "off", recent position, Wi-Fi / cell fallback indoors) | `WayCheckViewModel` |
| Post location | "Use where I am / Search / Places you used": search by place name (debounced 300 ms, 2–40 characters with a letter or digit, mirrors the server; only the words are sent; near-private places marked; a failure or 429 is one inline line and never blocks the other ways); "Use where I am" lists the area from a quick position first, then a visible search for a precise one to **add** a place; weak signal → the list stays, adding waits | `PlacePickerViewModel/Fragment` |
| Private places | Marking uses `preciseFix` (found on the A23: indoors it gave "needs precise" with no way forward); approximate → upgrade dialog; weak → move/wait + retry | `PrivatePlacesViewModel/Fragment` |
| Shared UI | `showSessionExpiredDialog` took the NavController when a button was tapped; a detached fragment crashed (**found on the emulator, pre-existing**); now taken before the dialog is shown | `SessionDialogs.kt` |

## Bugs found on the emulator and fixed

1. **Location switched off during a Safe Walk showed nothing** — the fused provider did not report the switch. The walk
   now looks at the switch every 5 s (`afterSwitch`), tested without any platform callback.
2. `showSessionExpiredDialog` crash (above).
3. (Test-only, found while writing tests) a punctuation-only search is a server 400; the phone no longer sends it, and
   a server 400 reads as "no place has that name", not "search failed".

## Results

- **Android:** 792 tests, 0 failures (17C: 749; +43). **Backend:** 773 tests, 0 failures, 2 skipped (unchanged code, re-run 2026-11-01). **Lint:** 0 errors / 102 warnings (unchanged; one unused string
  found and removed). **Python:** not affected.
- **Live API / MySQL:** no backend code changed, so no dedicated live-API script was run; place search 400 cases are pinned by `DiscoverApiIntegrationTest`. The emulator run went through the REAL backend and MySQL (area, report, nearby places, create place, search) and is the live check; the
  test accounts (users 353, 354), the report and the test place were deleted afterwards. **Disclosure:** the clean-up ran `DELETE FROM place_signals WHERE id NOT IN (SELECT signal_id FROM signal_contributions)` without a prior SELECT and the number of rows it removed was not recorded (it can only have removed signals with no contribution at all; users stayed at 174, no orphans remain).
- **Emulator (API 37) PASS:** Nearby with no permission → Allow → Approximate → area view with "Updated …" and
  "Use precise location" → Report shows the upgrade dialog **before** any form → Android's upgrade dialog → declined →
  the button became "Open settings" and the next report tap says to switch on "Use precise location" and opens the
  app's settings (recorded: Android API 37 shows its upgrade dialog EVERY time it is asked, also after a "Keep approximate" earlier — checked by declining, leaving Nearby and returning: the dialog came again. So the app never relies on Android remembering: within one screen visit a declined upgrade turns the button into "Open settings" (the settings toggle is the way); after the screen is recreated the flag is new and Android is asked again, which is harmless); precise →
  report: visible "Looking for a precise position…" with Cancel, then "Thank you"; location off → "Turn on location";
  Safe Walk set-up line "approximate" + button; active walk with approximate: "route checks need precise location …
  check-ins still work" + button; location off during the walk: hint + "Turn on location"; place picker with approximate:
  area list + upgrade notice; upgrade → precise → "Add this place" appeared; place added; search "mari" finds it, "zzzq"
  says no place has that name, one letter shows nothing.

## NOT verified / limits

- **Physical phone** (D-10): real weak GPS, indoor Wi-Fi positions, cached-position behaviour, OEM permission screens.
- **Rough-position path on the emulator:** its GPS always reports 5 m (an NMEA HDOP had no effect), so the "Rough" /
  "move outside" result, the best-accuracy progress number and the cached-position filter are **unit-tested only**
  (`Phase17DLocationTest`, `CommunityViewModelTest`, `ExperienceViewModelsTest`).
- Leaving a screen mid-search is unit-tested; not exercised by hand (the emulator answers instantly).
- TalkBack by ear; dark mode.
- After `cmd location set-location-enabled false` the emulator's GPS stopped delivering until the emulator was
  restarted (its GPS provider showed no last location): an emulator behaviour, not an app finding.

## For later phases (not done here)

- `EmergencyFragment` keeps its own permission launcher and `EmergencyLocationSharing` its own status handling (SOS is
  verified and safety-critical; 17F: "SOS last-known marked as such" is the place to unify it). `Phase17DLocationTest`
  pins that no other screen registers a location launcher or reads a position stream directly.
- The report confirmation says "It counts within about 1465 minutes" (server delay in minutes): wording, 17E/17H.
- The Discover "Near me" chip and `DiscoverViewModel` already used `oneFix` and were left as they were.
- Private places shows a progress bar during its search, without Cancel or the accuracy number Nearby has; on an
  answer or flag of a block signal the "too rough" dialog still says "a report needs about 100 m" (slightly off for an
  answer). Neither changes behaviour.
