# Real-device sprint (Galaxy A23) — record so far: Track A and Track B (2026-11-03/04)

Status: **Track A (setup) and Track B (automated checks on the phone) done; Track C IN PROGRESS (§5); Tracks D–H NOT STARTED.** The user paused the sprint after
Track B ("I'm not ready for the physical-device Track C tests"); it resumes in a later session. Vocabulary as in `CLAUDE.md` §3: nothing here
is called verified that was not run.

Plan (approved by the user, all defaults): **A** setup → **B** automated on the A23 → **C/G** human-in-the-loop + 17G on the phone → **D** long
runs (25 h idle, overnight battery, long Safe Walk) → **E** Samsung/OEM behaviour → **F** SMS and Call 112 → **H** emulator version matrix
(API 26, 29, 33) → this record completed, `STATUS.md` / `CLAUDE.md` "real phone" cells updated, one commit.

## 1. The phone and the build

Samsung SM-A236E, Android 14 (SDK 34), One UI build A236EXXSGEZH4, security patch 2026-08-05, 1080x2408, density 450 (1 dp = 2.8125 px, so 48 dp =
135 px), 3-button navigation, pattern lock (**only the user can unlock it**), two users (Owner 0 and Secure Folder 150; the app is installed for user
0 only). Dual SIM, one Jio SIM active (IN_SERVICE, 5G); Samsung Messages is the SMS app. Automatic time is OFF: the phone clock ran about 48.6 s behind
(measured in Track A), so compare phone-time and server-time values only after allowing for that. The debug build calls `127.0.0.1:8080`, so it needs
`adb reverse tcp:8080 tcp:8080` while the USB cable is attached (lost on every reconnect). Test account: `qa.phase17f@example.com` (dev data only).
Installed build: the working tree of the Track B commit (the commit that adds this file), installed in place so data and session were kept.

## 2. Track B results

| # | Check | Result | Evidence |
|---|---|---|---|
| B1 | Screens at dark/light × 1.0/2.0 text (15 screens) | **PASS** | 59 of 60 reached first pass, the last on retry; heuristics: 0 off-screen, 0 targets under 48 dp, 0 ellipses; six 2.0x screenshots read by eye, nothing clipped |
| B2 | Location refused: Discover "Near me", Safe Walk set-up, SOS | **PASS** | blocked wording, "Open settings" → IRL's App info; granting in Settings recovers; an SOS with location and notifications off was recorded on the server |
| B2 | **Location refused: Nearby** | **FAIL, fixed** (see §3, F-B1) | the button stayed "Allow location" and did nothing |
| B2 | Notifications and microphone refused | **PASS** (one nit, fixed, §3) | honest lines, system dialogs, recovery through Settings; refusing the microphone sets the audio consent to OFF by design |
| B3 | Server unreachable (route removed) | **PASS** | Discover says "Unable to connect to server…" with "Try again"; an SOS started and ended with no server synced about 30 s after the route came back: `started_offline = 1`, ending and 1 location point arrived |
| B4 | Process death (`kill -9`) in an SOS and in a Safe Walk | **PASS** | SOS and walk come back with their banner, screen and foreground service; the walk deadline carried on (09:16 → 08:41); both ended and reached the server |
| B5 | Foreground-service types and channels | **PASS** | `0x8` location, `0x80` microphone only (when only the microphone was usable), `0x88` both; no notification left after stop; channels seen `safety_tracking`, `sos_countdown` |
| B6 | Media (17C) on the phone | **PASS** | system Photo Picker; the server-stored photo has no Exif/GPS/XMP, the stored video has no meta/udta/location boxes and zeroed times; plays only after a tap, paused when the app was left and did not resume; deleting the post removed rows and all stored files; the upload staging cache was empty |
| B7 | Phase 5 vault viewing | **NOT RUN** | needs the user's unlock: moved to Track C |

Limits of what was shown: a **true first refusal where Android would still show its dialog again** could not be produced without clearing app
data (`pm revoke` already counts as a refusal), so that branch rests on the pure policy tests; the test video had **no audio track** (a screen
recording), so only the "no location" half of the video check ran; one phone, one OS version, USB-attached, debug build.

## 3. Defects found in Track B and fixed

- **F-B1 (defect):** on Nearby, after Android stopped showing its location dialog, the button stayed "Allow location" and did nothing; the blocked
  wording and "Open settings" never appeared. Cause: a second refusal left the view model's state EQUAL to the one before, a `StateFlow` does not emit
  an equal value, and the fragment's own plain field (`locationAsk.permanentlyDenied`) was never read again. **Fix:** the refusal is part of the state
  (`CommunityUiState.permissionBlocked`, set by `CommunityViewModel.onPermissionResult(permanentlyDenied)`, cleared when a position can be read);
  `CommunityFragment` draws from the state. Verified on the A23: one refusal → blocked text + "Open settings"; allowing in Settings → the same visit
  loads the radar.
- **O-B2 (nit):** on SOS options the notification button kept the label "Allow notifications" after a refusal while its action had become "open
  settings". **Fix:** `core/ui/RuntimePermissionAsk.needsSettings(asked, shouldShowRationale)` is used for label AND action of both the microphone and the
  notification button. Verified on the A23.
- Tests: `Phase17HPermissionDenialTest` (+8). Android **974 / 0**, lint **0 errors / 104 warnings** (unchanged), `assembleDebug` OK.

## 4. Handoff: continuing with Track C

- **Before starting:** the user unlocks the phone and stays with it. Keep it awake while a script drives it (`settings put global
  stay_on_while_plugged_in 3`) and put it **back to 0** afterwards. Original device settings to restore: `font_scale 0.8`, `ui_night_mode 2`,
  `screen_off_timeout 600000`, `stay_on_while_plugged_in 0`, animation scales 1.0, `location_mode 3`, `auto_time 0`.
- **Backend:** Maven `spring-boot:run` from `backend/` with `MODERATION_AI_ENABLED=false` and `OTP_DEV_FILE=<a local file>` (dev OTP sink, no real SMS
  provider); `adb reverse tcp:8080 tcp:8080`. Permissions on the phone: location, microphone and notifications are granted; **`SEND_SMS` is not**
  (Track F; it is flagged as restricted for sideloaded apps, so check "Allow restricted settings" there). The audio consent for the QA account is ON.
- **Track C scope:** shake SOS (screen on, screen off, false positives while walking/running), TalkBack by ear on the safety screens and the 17G
  sheets, Safe Walk outdoors, one-step Nearby report outdoors, Explore with the Samsung keyboard / Tamil / Tanglish, smart understanding on the phone,
  the "I went" flow, and the **Phase 5 vault viewing** (needs the user's unlock). Never send an SMS or open the dialer for 112 outside Track F, and
  use only phone numbers the user owns there.
- **Dev data left behind:** QA account emergencies 55-58 (RESOLVED) and journey 31 (COMPLETED); many scratch accounts, places and posts from the 17G
  runs; SQL backups made for V29 / V30 live in the session scratchpad (outside the repo) and contain dev data: delete them when no longer needed.
  The scratch scripts used here (adb helpers, the display-matrix script) were session files and are **not** in the repo.
- **Still open from earlier work, not part of Track B:** the 17F signal-card button rows break words at 2.0x text (the user's decision is pending);
  the Tamil/Tanglish word list needs a human review; physical-phone and TalkBack verification for everything marked "not verified" in `STATUS.md`.

## 5. Track C (2026-11-06) — IN PROGRESS: results so far

Method: the user does the physical act, I set up the phone, collect evidence (server rows, logcat, UI dump) and write nothing the user did not report.
Environment: backend `spring-boot:run` (`MODERATION_AI_ENABLED=false`, dev OTP file), `adb reverse tcp:8080`, QA account `qa.phase17f@example.com` (id 362) which
has **no Safety Circle contacts** (checked in MySQL), so no SMS composer can open and nothing can be sent. Phone clock was about 2m50s behind the server at the end.
User decisions this session: outdoor tests with the PC carried along (USB), screen-off shake skipped for now.

| # | Check | Result | Evidence |
|---|---|---|---|
| C1a | Shake SOS, screen ON, IRL open | **PASS** | user: SOS screen + 5 s countdown opened; server row 59 `trigger_type = SHAKE`, ended RESOLVED |
| C1b | Shake SOS, screen OFF | **NOT RUN** | the user chose to skip; needs the phone off the cable (a powered, adb-attached phone may keep the CPU awake and give a false pass) |
| C1c | Shake false positives (walk, jog, hard run, pocket) | **FAIL, fixed, retest PASS** (§6, F-C1) | see §6 |
| C2 | TalkBack by ear | not run yet | |
| C3 | Safe Walk outdoors | not run yet | |
| C4 | Nearby report outdoors | not run yet | |
| C5 | Explore: Samsung keyboard, Tamil, Tanglish | not run yet | |
| C6 | Smart understanding (Groq) | not run yet | |
| C7 | "I went" + rating | not run yet | |
| C8 | Phase 5 vault viewing | not run yet | |

## 6. Defect found in Track C and fixed

- **F-C1 (defect, safety-relevant):** shake SOS opened a countdown while **jogging / running with the phone in a pocket**. Normal walking was clean. With the
  phone in a pocket the person cannot press Cancel, so each such countdown ran to zero and a real SOS started on the phone (server rows 60-64, `SHAKE`,
  `started_offline = 1`, all marked False alarm; nobody was notified because the QA account has no contacts).
- **Evidence (temporary logging of the residual strength and vector, removed again):** walking peaks 17-30 m/s²; jogging 17-29 in a steady train every ~270 ms;
  hard running up to 36-58 m/s²; deliberate shakes (light to hard) 35-85, as 3 peaks in 190-250 ms, each starting from rest. A higher count threshold alone would
  have lost the light shakes (they decay: 35-75, then 18-28), and a hard run is as strong as a light shake, so strength cannot separate them; a run never goes quiet.
- **Fix (`ShakePolicy`):** the count stays "3 alternating peaks ≥ 17 m/s² in 2 s", and a shake now also needs (a) one sample ≥ 32 m/s² inside that window and
  (b) no other counted peak in the 800 ms before its first peak. The recorded trace was replayed through the real direction logic to choose the values (lead-in
  stable from 300 to 1000 ms; strong threshold between the jog maximum 29 and the weakest shake 35). Tests: `ShakePolicyTest` (+5: stride train, hard run with a
  strong jerk, burst right after an impact vs after quiet, weakening shake still triggers, jerk outside the window), `ShakeSosTest` helper strokes raised to 45.
  Android **979 / 0** (974 before).
- **Retest on the A23:** brisk walk, jog, hard run / stairs: no countdown; 5 deliberate shakes (2 light, 2 medium, 1 hard): all 5 opened the countdown; no new
  `SHAKE` row on the server. One run by one person on one phone.
- **Not solved (stated limit):** a single isolated violent jolt (one trace showed 24, 69, 29 m/s² at a jog) looks exactly like a hard shake; it did not repeat in the
  retest, but the policy cannot tell the two apart. A proximity-sensor "phone is covered" rule would, but it is a design change (a person may want to shake a
  pocket) and is the user's decision.
