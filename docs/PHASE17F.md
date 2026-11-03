# Phase 17F — Emergency (shake SOS, audio consent, last-known position, Nearby, radar, Safe Walk line)

Status: **implemented, compiled, tested** (results §6). Scope taken from `docs/PHASE17_PLAN.md` §2 row "17F Emergency" and the
user's decisions of 2026-10-27 (§3.1: D-4 shake only, voice deferred; D-9 Nearby without text or photo; D-10 physical
phone). Written 2026-11-03. **No migration** (next stays **V29**), **no new permission, service or dependency**.

## 1. Scope (from the repository)

| Plan item | Built |
|---|---|
| auto-audio consent (first visit to You → Safety, stored per account on the phone) | §2.2 |
| shake trigger (D-4: opt-in, app open **or** a Safe Walk running, 3 strong shakes in 2 s, then the normal 5 s countdown) | §2.1 |
| voice (D-4: **defer, say so in the app**) | §2.1: the SOS options screen says it plainly; nothing is built |
| Nearby simplified flow (D-9: Report → type → confirm, no text, no photo) | §2.4 |
| radar readability | §2.5 |
| Safe Walk start→destination line on the app-drawn view | §2.6 |
| SOS last-known marked as such | §2.3 |
| V29 "if SHAKE/VOICE triggers become accepted" | **not needed**: `emergency_events.trigger_type` has no CHECK (`SHOW CREATE TABLE` on MySQL 8.0, 2026-11-03) |

Not touched: the SOS screen's own permission launcher (`Phase17DLocationTest` exempts it; SOS is verified and
safety-critical), the Safety Circle messages (they always said "Last known location"), Safe Walk check-ins and concerns.

## 2. What was built

### 2.1 Shake to start SOS (D-4, F17-1…F17-3)
- **Backend:** `EmergencyService` accepts `triggerType` `SHAKE` besides `MANUAL`; `VOICE` and `DURESS_PIN` still 400.
  `EmergencyApiIntegrationTest` (SHAKE accepted and returned in the history; VOICE/unknown refused) and
  `OfflineSosApiIntegrationTest` (a late-recorded SHAKE keeps its trigger, one reference = one emergency).
- **Phone, pure:** `ShakePolicy` (a gravity-removing filter; a *peak* is ≥ 17 m/s² of what is left; a counted shake must push
  the OPPOSITE way of the previous peak; 3 within 2 s; peaks < 90 ms apart are one movement; 10 s quiet after a trigger),
  `ShakeArming` (when to listen at all).
- **Phone, holder:** `ShakeSos` (application scope, the one holder) + `ShakeSensor`/`AndroidShakeSensor` (platform
  accelerometer, the WAKE-UP variant when the phone has one, `SENSOR_DELAY_GAME`) + `AppVisibility` (activity lifecycle
  callbacks, no dependency).
  - It listens **only** for a person who switched it on (per account, default off), signed in, with no SOS running, and
    - while IRL is on the screen: a shake emits `foregroundShakes`; `MainActivity` opens the SOS screen with
      `ARG_AUTO_START` + `ARG_TRIGGER=SHAKE` (the same path as the tile and shortcut, `NavigationRules.opensSosFrom`), or, on the
      SOS screen, `EmergencyViewModel` starts its own countdown. **Nothing starts without that cancellable 5 s countdown**,
      with the normal SOS treatment (siren, words, black and red, Cancel);
    - or while a Safe Walk runs (the existing foreground service keeps the process alive) **and notifications are allowed**:
      the countdown is a high-importance notification "SOS starts in N s" with a **Cancel** button (non-exported
      `ShakeCountdownCancelReceiver`, `VISIBILITY_PUBLIC`, own channel `sos_countdown`). At zero the SOS is started on the
      phone through the same steps the SOS screen takes (`SosRecorder.startLocal` → the Safe Walk ends → location sharing →
      Safety Circle), then the notification becomes "SOS started - tap to open".
    - with notifications off it does **not listen in the background at all** (a countdown nobody could cancel is not offered).
- **Trigger plumbing:** `ActiveEmergencyRecord.trigger` → `DataStoreActiveEmergencyStore` (`trigger` key; unsent endings get a
  ninth field as a one-digit code so the line stays digits/flags; older records and lines are MANUAL) → `SosRecordRequest` →
  `RemoteEmergencyRepository` → the server. The trigger survives offline recording and retries.
- **UI:** You → Safety → **SOS options** (also in Settings → Safety): Shake switch (disabled with a reason when the phone has no
  accelerometer; a line + "Allow notifications" when notifications are off), Audio switch, and a notice that **voice SOS is not
  available** and why (no "coming soon", no promise).

### 2.2 Audio in an SOS, with prior consent (F17-4)
- A one-time question the first time an account opens You → Safety ("Record audio in an SOS?", default look **No**). It never
  stands between a person and SOS: it is **not shown while an SOS or a Safe Walk is running**, and Back or a tap outside dismisses
  it as "ask next time" (nothing stored, the choice stays NOT_ASKED). **Yes** asks for the microphone permission *then*; a refusal leaves audio off. Changeable any time in SOS options
  (the switch asks for the permission too). Stored per account in `sos_options` (DataStore, app-private), wiped by
  `AccountDataWiper` when an account is deleted, kept across sign-out like other preferences.
- `AutoAudioPolicy` (pure) allows an automatic start only when: the choice is ON, the server has the SOS, nothing is recording,
  it has not been started for this SOS before, and the microphone is **already** allowed (it never asks during an SOS).
  `EmergencyFragment` calls `EvidenceCardController.autoStartIfDue()` from the visible SOS screen (Android 14 refuses a
  microphone service from the background). Recording is exactly the Phase 5 recorder: encrypted on the phone, the foreground
  notification, **Stop** works as before. `EvidenceCaptureViewModel.startRecording` remembers the SOS id in the store, so a
  **Stop is final** (leaving and returning, or a restart, does not start it again); a refusal is not retried either.
- This **amends** the Phase 5 rule "audio only on tap" (CLAUDE.md §9, `android-safety.md`): the recording is still
  person-chosen (in advance), visible and stoppable.

### 2.3 SOS last known position (F17-5)
`EmergencyLocationUi.nowMillis` (a 15 s clock from the view model; null in tests) + pure `lastKnownOf`: a position ≥ 60 s old
is shown as "Last known position (HH:mm, N minutes ago). Not necessarily where you are now." followed by the coordinates,
whatever the status says. No fix → nothing is invented; signed out → no position. The status wording and the 17A 60 s grace are
unchanged; the Safety Circle SMS already said "Last known location (time)".

### 2.4 Nearby: one step (F17-6, D-9)
"Report something here" → (17D location gate, unchanged) → **one sheet**: the ten place conditions grouped as on the radar
(`ReportChoices`), the existing privacy text (`report_confirm_message`, 9D wording guarded by `CommunityTextsTest`), and
"Share anonymously", enabled only after a choice. The separate confirm dialog is gone. No text field, no photo
(`Phase17FGuardsTest` scans the layout). The thank-you now says "about 24 hours" instead of "about 1465 minutes" (the 17D
follow-up; `CommunityTexts.recordedDelay`).

### 2.5 Radar readability
Labels in **sp** (they follow the phone's text size) with a halo in the card colour; larger marks (9 dp) with a separating
outline; **numbers on the marks that match the numbered list below**; `RadarLayout.spread` moves overlapping marks apart
(drawing only: list, order and distances unchanged); the radar is up to 320 dp; the legend says the numbers match.

### 2.6 Safe Walk line (F17-7)
`WalkLineView` + pure `WalkLineGeometry`: the app-drawn straight line from where the walk started to the place named, the
band the route checks watch (the Safety Bubble's own half-width, `MovementPolicy.corridorHalfWidthMeters`), and "You". North
up, uniform scale, shapes + words (never colour alone), caption "a straight line … not a route". Computed each time from
in-memory state: no trail, no stored destination (it is hidden after an offline restore until the server sends it back), no
location stream read by the view (`Phase17FGuardsTest`). "You" is left out while the GPS has had no usable fix for 2 minutes.
TalkBack gets one description (distance, inside/outside the band). It never changes a check-in, concern or level.

## 3. Files (new / changed)

Backend: `EmergencyService`, `TriggerType`; tests `EmergencyApiIntegrationTest`, `OfflineSosApiIntegrationTest`.
Android new: `feature/emergency/trigger/{ShakePolicy,ShakeArming,ShakeSensor,ShakeSos,AndroidShakeCountdownNotifier}`,
`feature/emergency/domain/SosOptions`, `feature/emergency/data/DataStoreSosOptionsStore`, `core/lifecycle/AppVisibility`,
`feature/evidence/domain/AutoAudioPolicy`, `feature/safety/ui/{SosOptionsFragment,SosOptionsViewModel}`,
`feature/community/domain/ReportChoices`, `feature/community/ui/RadarLayout`, `feature/safewalk/domain/WalkLineGeometry`,
`feature/safewalk/ui/WalkLineView`; layouts `fragment_sos_options`, `dialog_report_sheet`.
Changed: `AppContainer`, `MainActivity`, `NotificationChannels`, `AndroidManifest` (one non-exported receiver), nav graph,
`EmergencyViewModel/Fragment/LocationUi`, `SosRecorder`, `DataStoreActiveEmergencyStore`, `RemoteEmergencyRepository`,
`EvidenceCaptureViewModel`, `EvidenceCardController`, `SafetyFragment`, `SettingsFragment`, `CommunityFragment`,
`CommunityTexts`, `SafetyRadarView`, `AreaSignalAdapter`, `SafeWalkFragment/ViewModel`, `fragment_safe_walk`, `strings.xml`.
Tests new: `ShakePolicyTest`, `ShakeArmingTest`, `ShakeSosTest`, `AutoAudioPolicyTest`, `Phase17FEmergencyTest`,
`Phase17FNearbyTest`, `WalkLineGeometryTest`, `Phase17FGuardsTest`.

## 4. Security, privacy and accessibility review

- **Nobody monitors anybody:** everything is the person's own phone and own choice; nothing is sent to the Safety Circle or
  the server by the shake except through the normal SOS path; the trigger is a label the person sees in their own history.
- **No new permission, service or boot receiver** (`Phase17FGuardsTest` pins the permission list and the three services). The
  notification channel needs no permission; `POST_NOTIFICATIONS` is asked only from the SOS options button the person taps.
- **Microphone:** never at launch, never during an SOS, never in the background; asked once, in context, with the question on
  screen; recording is visible (the foreground notification) and stoppable; evidence stays encrypted on the phone.
- **Location:** the Safe Walk line stores nothing, reads no stream, draws no trail; "last known" never claims "now".
- **Lock screen:** the shake countdown notification is deliberately PUBLIC (Cancel must work from the lock screen; it reveals only
  that a ~5 s countdown runs); the "SOS started" notification is PRIVATE with a neutral public version ("IRL is running"), the
  same pattern as the tracking notification, so nobody holding a locked phone reads that an SOS is running (`Phase17FGuardsTest`).
- **Honesty:** voice is "not available", the line "is not a route", no text says a place or person is safe (guard test).
- **Accessibility:** all new controls ≥ 48 dp; switches have text labels; status lines are polite live regions; the radar,
  the sheet and the walk line never rely on colour (shape + number/word); labels use sp.

## 5. Known limitations (read before relying on it)

- **Shake thresholds are untuned on real hardware.** The emulator can only replay values; a person must shake a physical phone
  (D-10) to tune the 17 m/s² peak and to measure false positives (a bag swinging, a bumpy ride) and battery cost during a long
  walk. **Not verified** on a real phone.
- **Screen-off shaking is best-effort and the app says so.** `dumpsys sensorservice` on the Galaxy A23 (SM-A236E, Android 14,
  2026-11-03) shows its only accelerometer (LSM6DSO) is a **non-wake-up** sensor: with the screen off its samples wait in a
  FIFO and arrive in a batch when something wakes the CPU. Two consequences, both built in: (1) a sample older than 1.5 s when
  it reaches the policy is dropped (`ShakePolicy.isFresh`), so a late batch can never open a countdown for an old shake;
  (2) the SOS options text tells the person not to rely on shaking with the screen off. The wake-up variant is used when a
  phone has one. On such a phone the visible-app shake and a shake while the screen is on during a walk are the reliable cases.
- Off the screen the shake exists only during a Safe Walk, needs notifications, and depends on the OEM not freezing the
  service (Xiaomi/Oppo/Vivo/Samsung battery killers - `PLATFORM_CONSTRAINTS.md`).
- If the SOS is started from a notification countdown with the app closed to the screen, the **Safety Circle SMS app composer
  cannot open** (Android blocks activities from the background): automatic SMS (opt-in) sends; otherwise the person opens IRL
  from the notification and texts from the SOS screen.
- Auto audio starts only while the SOS screen is in front; an SOS started by a background shake records no audio until the
  screen is opened (Android 14 microphone rule).
- A background-started SOS cannot open the SMS composer (Android blocks activities from the background); the "SOS started"
  notice says the Safety Circle is texted only if automatic SMS is on. Platform notes: `PLATFORM_CONSTRAINTS.md` "Phase 17F".
- Voice SOS: not built (deferred, D-4).

## 6. Results (2026-11-03)

| Check | Result |
|---|---|
| Backend `mvn test` (whole suite) | **792 tests, 0 failures, 2 skipped** (790 before; +SHAKE accepted / VOICE refused, +late SHAKE on the offline path) |
| Android `testDebugUnitTest` | **877 tests, 0 failures** (809 before; +68: shake policy and arming, the shake holder, auto-audio, trigger storage and recording, last-known, the one-step report, radar layout, Safe Walk line, wording and isolation guards) |
| Android lint | **0 errors, 104 warnings** (the same 104 as before 17F; three new ones found on the way were fixed) |
| MySQL | `SHOW CREATE TABLE emergency_events`: no CHECK on `trigger_type`, so **no V29**; the emulator walk-through wrote `trigger_type = SHAKE` rows 51, 52 and `MANUAL` 53 into the live table |
| Emulator (API 37) | PASS after 4 fixes (below) |
| Galaxy A23 | 2026-11-03: sensor inventory read; then a device-validation run through the app's own sign-up and the dev-file OTP sink (§6.1), and a re-check of fixes V5-V8 (§6.2) |

**Emulator walk-through (what was done and seen):** first visit to You → Safety showed the audio question, "Yes" showed Android's
microphone dialog, the choice was stored (`auto_audio_<id> = ON`); SOS options (shake off by default, audio on, voice notice);
shake on → the accelerometer appeared in `dumpsys sensorservice` (50 Hz, 300 ms batching) only then; three alternating
accelerations sent over the emulator console at 120 ms spacing opened the SOS screen with the normal 5 s countdown; letting it
finish gave `SHAKE` in MySQL and the Evidence card said "Audio is being recorded" with no tap (an evidence item existed);
**Stop recording**, leaving and coming back did **not** restart it; with the app in the background during a Safe Walk the same
shake posted "SOS starts in N s" with **Cancel**, Cancel removed it and created nothing, letting it run started the SOS on the
phone (location saved, Safe Walk ended as EMERGENCY) and audio started when the screen was opened; Nearby: the new one-sheet
report (three groups, Share disabled until a choice), seven numbered marks (scratch rows, deleted afterwards) all separable;
Safe Walk line with a ~4.5 km destination (Start / Destination / band / You moving along the line), hidden for a walk shorter
than 100 m (the arrival hint takes over); SOS with location switched off for 100 s: "Last known position (12:16 PM, 1 minute
ago). Not necessarily where you are now."

**Found on the way (17F-V1…V4):**
- **V1** `AppVisibility` was created after the first screen had started, so the app never counted as visible and the shake
  never listened (no unit test can see this): now created in `Application.onCreate`.
- **V2** Safe Walk line: with start and destination almost the same place the labels printed over each other and the band
  filled the picture; the band was also cut off at the edges. Now hidden under 100 m, end labels on opposite sides, the picture
  includes the whole band.
- **V3** Radar: a later soft circle washed out earlier numbers: halos are drawn first.
- **V4** The idle SOS screen still said audio is recorded "only when you tap": reworded (and the Evidence card intro).
Also: the console-based sensor replay is the only reliable way to shake the emulator (`adb emu` calls are too slow and uneven).

### 6.1 Galaxy A23 device validation (2026-11-03, Android 14, SM-A236E, by adb + uiautomator + screenshots)

How the app was reached without touching authentication: the backend's existing development sink (`OTP_PROVIDER=DEV_FILE`, the
default) writes each code to a local file; a new account was created through the real sign-up screen and the code typed in.
No production auth, OTP or safety code was changed. **Executed on the phone:**

| Check | Result |
|---|---|
| Audio question: Back closes it with no choice, it returns next visit; not shown during a Safe Walk; Yes -> Android's own microphone dialog; not shown again | PASS |
| SOS options (shake off by default, audio on, voice notice), accessibility tree (labelled switches, live regions, radar description) | PASS (tree check, not TalkBack by ear) |
| SOS countdown + Cancel (manual button; countdown at "4" when cancelled, no SOS started) | PASS |
| SOS run: audio starts with no tap; OS shows an active MIC capture, foreground service type location+microphone, 7 vault items verified | PASS |
| Stop recording is final: leaving and re-entering did not restart the microphone | PASS |
| Screen off during the SOS: recording continued | PASS |
| Last known position (location off 95 s): "Last known position (1:59 PM, 1 minute ago). Not necessarily where you are now." | PASS |
| Safe Walk straight line, dark mode, 2.0x text | renders; findings below |
| Nearby radar and report sheet, dark mode, 2.0x text | renders; findings below |
| Nearby report filed | **not done**: indoors the position was 156-254 m and the app refused ("Move outside..."), sending nothing (the designed gate) |

**Manual only (not done, never simulated):** every shake test (screen on, screen off, false positives), TalkBack by ear, battery cost,
the Safety Circle SMS path, a Safe Walk "Are you OK?" notification with the screen off. The lock screen of this phone shows
icons only, so the private lock-screen text could not be read (inconclusive). The phone is pattern-locked: a screen-off during
the run locked it and the owner had to unlock it.

### 6.2 Four 2.0x-text findings from that run, fixed (17F-V5...V8, all drawing / layout; no safety logic touched)

| Id | Found on the A23 | Fix |
|---|---|---|
| V5 | The Nearby report dialog gave its choices nearly all the height; at 2.0x the button area was ~295 px and **Cancel was clipped** | `showReportSheet()` is now a Material `BottomSheetDialog` (`dialog_report_sheet.xml`): title, the scrolling choices, and a footer with "Share anonymously" and "Cancel" OUTSIDE the scroll area. Share stays off until a choice; Back, Cancel and a swipe down all close it with nothing sent |
| V6 | Safe Walk line: "Destination" printed over its own diamond (the old code pushed a word back inside the view, onto its mark) | pure `WalkLineLabels.place` (preferred side, other side, above, below; never onto a mark or another word); "You" prefers above. The earlier absent "You" was the weak-GPS state (no usable fix), by design |
| V7 | Radar: the number "1" half under the "You" ring; "500 m" and "1 km" labels collided | `RadarLayout.spread(keepClear)` keeps marks out from under the person's ring (drawn position only; the list keeps the true distance); `numberSpot` puts a number on the OUTSIDE of its mark; `ringLabelBox` puts the 500 m label below-right and the others above-right, at any text size |
| V8 | A signal row's text column was squeezed by the badge beside it ("confir / med") | the badge moved under the title in `item_area_signal.xml` (one layout for every size, no scale-dependent code) |

Tests: `testDebugUnitTest` **896 tests, 0 failures** (877 before; +8 `WalkLineLabelsTest`, +8 `RadarReadabilityTest`, +3 `Phase17FGuardsTest`);
lint **0 errors, 104 warnings** (the same 104; one new `UnusedAttribute` was found and fixed). Re-checked **on the A23 at 2.0x text in
dark mode**: V5 (Share and Cancel fully on screen, Share off until a choice, Cancel closes it, nothing sent), V6, V7, V8 (walk line,
radar, signal row). On the **emulator at 2.0x** (light mode): V5 also (Cancel and Back both close it, nothing shared). **Not
re-checked:** light mode of V6-V8 on the A23.

**Still open (found while re-checking V8, not fixed - outside the approved four):** in the same signal card the two button rows
("I saw this too / No longer true", "How sure is this? / Report a problem") are horizontal rows of wrap-content buttons; at 2.0x
"No longer true" wraps to three lines and "Report a problem" breaks inside a word ("Rep / ort / a"). Proposed: stack the buttons
vertically when the font scale is large (the pure `MarksFit` pattern) or always; needs the user's decision. Smaller nit: with
start and "You" almost on the same spot the "Start" word touches the You ring (no overlap).

## 7. Not verified / to do before 17G

- A physical phone: shake tuning (every shake test), screen-off delivery on phones whose sensor does not wake the CPU, whether the
  Safe Walk service and shake survive a swipe-away from recents on Samsung, battery cost, TalkBack by ear for the new screens,
  light mode of the new screens at 2.0x, a real Nearby report outdoors, the Safe Walk deviation notification on the phone.
- The radar with several marks at 2.0x (only one signal existed; the separation is covered by unit tests).
- The Safety Circle's own SMS path after a background-started SOS with automatic SMS on (needs a real SIM; unchanged code).
- Voice SOS (deferred by decision D-4).

## 8. Manual checklist for the user (Galaxy A23 or any real phone)

1. Install the debug build, sign in, open You → Safety: the audio question appears once; try Back (it must close without a
   choice and come back next visit) and then answer. Start a Safe Walk first and confirm the question does NOT appear during it.
2. You → Safety → SOS options → switch **Shake to start SOS** on. With the screen on and IRL open, shake three times firmly:
   the SOS screen opens with the 5-second countdown; Cancel it. Shake again and let it run: the Evidence card says audio is being
   recorded; **Stop recording**, leave the screen and come back: it must not restart. End the SOS ("I'm safe").
3. Start a Safe Walk, press the power button (screen off), shake: note whether the "SOS starts in N s" notification appears
   (expected to be unreliable on this phone: its accelerometer is non-wake-up). If it appears, test Cancel; check that "SOS
   started" shows only "IRL is running" on the lock screen.
4. False positives: carry the phone in a bag swung by hand, walk and jog with it in a pocket, ride a bumpy road: no countdown
   should open (note any that does; the threshold is `ShakePolicy.peakThreshold`).
5. TalkBack: SOS options (switches and the voice notice), the Nearby report sheet (choices read as a group, Share enabled only after a
   choice), the Safe Walk line (one spoken description), the radar and its numbered list.
6. Dark mode and font size 2.0x: the radar labels, the report sheet, the walk line and the SOS options screen.
7. SOS screen with location switched off for over a minute: "Last known position (time, N minutes ago)".

## 9. Viva notes (what / why / key classes / lifecycle / privacy)

**Shake SOS** - *What:* three firm back-and-forth shakes open the same 5-second cancellable SOS countdown. *Why:* a frightened
person may not be able to find a button, but nothing may start an SOS without a chance to say no. *Key classes:* `ShakePolicy`
(pure), `ShakeArming` (pure), `ShakeSos` (holder), `ShakeSensor`, `AndroidShakeCountdownNotifier`, `ActiveEmergencyRecord.trigger`.
*Lifecycle:* the accelerometer is registered only while IRL is on screen or a Safe Walk runs (and notifications allow a Cancel);
no new service, the one tracking service keeps the process alive. *Privacy:* opt-in per account, stored on the phone only; the
server only receives a label (`SHAKE`) in the person's own history.

**Audio consent** - *What:* audio can start by itself in an SOS, but only after a one-time question. *Why:* tapping Record in
an emergency is the step a person cannot do; consent in advance keeps it chosen, visible and stoppable. *Key classes:*
`SosOptionsStore`, `AutoAudioPolicy`, `EvidenceCardController.autoStartIfDue`, `SafetyFragment`. *Lifecycle:* starts only from
the visible SOS screen (Android 14 microphone rule), once per SOS (the id is stored), never retried after Stop. *Privacy:* default
No, microphone permission asked at the question not during an SOS, recording encrypted on the phone as in Phase 5, a notification
is always shown.

**Last known position** - *What:* a position older than a minute is labelled with its time and age. *Why:* an old position
shown as current could send help to the wrong place. *Key classes:* `lastKnownOf`, `locationCardFor`. *Lifecycle:* the screen's
view model ticks a clock every 15 s. *Privacy:* nothing new stored.

**Nearby one-step report** - *What:* one sheet to choose the place condition and share anonymously. *Why:* fewer taps when it
matters; still no text and no photo, so a place signal can never accuse a person. *Key classes:* `ReportChoices`,
`dialog_report_sheet`, `CommunityTexts.recordedDelay`, `RadarLayout`. *Privacy:* unchanged: cells only, no coordinates sent.

**Safe Walk line** - *What:* a drawn straight line from start to destination with the route-check band and "You". *Why:* it
makes the "Are you OK?" route prompts understandable without claiming a route. *Key classes:* `WalkLineGeometry`, `WalkLineView`.
*Lifecycle:* recomputed from the walk's in-memory state; hidden when the destination is unknown (after a restart) or under 100 m.
*Privacy:* no map tiles, no trail, nothing stored.
