# Real-device sprint (Galaxy A23) — final record, Tracks A-H and close-out (2026-11-03 to 2026-11-07)

**Final status (2026-11-07, §17): CLOSED AS PARTIAL, by the user's decision to keep D1b (25 h idle) and D2 (overnight battery) optional/deferred.** Everything that could be run was run; what was not run is listed in §17 and stays NOT VERIFIED. The text below is the record as written track by track.

Status: **Track A, Track B and Track C done as far as stated in §5 and §7-§12 (2026-11-06, two sessions); Track D PARTIAL (§13: D1a PASS, D3 PARTIAL, D1b + D2 NOT VERIFIED, deferred by the user as impractical); Track E PARTIAL (§14: E1b, E3, E5 PASS; E1/E2/E4 findings, disclosed in the app by F-E1); Track F PASS (§15: Call 112 dial prompt, automatic SMS on and off, failure under airplane mode, 5 real SMS to a number the user owns); Track H PASS on API 26/29/33 after one defect fixed (§16) (G = the 17G checks, done inside Track C).** Track C is PARTIAL on C1b (screen-off shake), C2 flow 4 (what TalkBack speaks at the SOS countdown) and the Tanglish word list; the others PASS or PASS-by-the-user's-report as marked. (History: the user paused the sprint after Track B on 2026-11-04; Track C ran on 2026-11-06.)
Vocabulary as in `CLAUDE.md` §3: nothing here is called verified that was not run.

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
| C1b | Shake SOS, screen OFF | **PARTIAL (session 2, §11): works sometimes, not reliably; best-effort as documented** | the user chose to skip; needs the phone off the cable (a powered, adb-attached phone may keep the CPU awake and give a false pass) |
| C1c | Shake false positives (walk, jog, hard run, pocket) | **FAIL, fixed, retest PASS** (§6, F-C1) | see §6 |
| C2 | TalkBack by ear (4 flows) | **PARTIAL (session 2: flows 1 and 3 now PASS by the user; flow 4 speech still open)**; session-1 detail: | Flow 2 Nearby (radar, list, report sheet): **PASS** by the user. Flow 4 SOS countdown: **FAIL, partly fixed** (§6 F-C2). Flow 1 SOS options: **FAIL, not investigated** (user: "a switch had no state spoken", "a label was long/confusing"; the accessibility tree shows two real Switch nodes with text + checkable + checked, so the cause is not visible in the tree: needs a second by-ear pass; this TalkBack reads "State, name, type" by Samsung default). Flow 3 Explore / I went: **FAIL, not investigated** (user: "could not type/enter the request", "chips unreachable or not spoken"; the star rating and the I went sheet were not reported on) |
| C3 | Safe Walk outdoors | **PASS (core flow); destination/route check, pocket/screen-off alerting and battery NOT tested** (§12) | |
| C4 | Nearby report outdoors | **PASS** (§12) | |
| C5 | Explore: Samsung keyboard, Tamil, Tanglish | **PARTIAL: English, Tanglish (4 phrases) and the keyboard fix PASS by the user; Tamil script, Tanglish spelling rule and "chilling" open (§7)** | user (2026-11-06, Samsung keyboard): English requests work; some Tanglish phrases did not; the blue search key did not close the keyboard. Tamil script and the user's own phrase (4) were not reported separately |
| C6 | Smart understanding (Groq) | **PASS** (Tamil by the user's report only; service-window log not seen by me) | see §9 |
| C7 | "I went" + rating | **PASS, 1 defect found and fixed (§8 F-C5)** | driven on the A23 by adb with database checks (evidence in §8); TalkBack by ear on the sheet NOT done (belongs to C2) |
| C8 | Phase 5 vault viewing | **PASS** (one item) | user unlocked the vault with the screen lock (prompt appeared at "Open your Evidence Vault"); 3 audio items listed as "Verified in your vault · also on this phone"; the first (0:29) opened in the player and the user heard it play. Other items, "More", recovery passphrase: not run |

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
- **Retest on the A23:** brisk walk, jog, hard run / stairs: no countdown; 5 deliberate shakes (2 light, 2 medium, 1 hard): all 5 opened the countdown. One run by
  one person on one phone. (Correction: server rows 65-67 are `SHAKE` rows created after the fix; the user confirmed they shook the phone on purpose to open the
  SOS screen during the TalkBack flows, so they are not false triggers. An earlier version of this record said "no new SHAKE row"; that was wrong.)
- **Not solved (stated limit):** a single isolated violent jolt (one trace showed 24, 69, 29 m/s² at a jog) looks exactly like a hard shake; it did not repeat in the
  retest, but the policy cannot tell the two apart. A proximity-sensor "phone is covered" rule would, but it is a design change (a person may want to shake a
  pocket) and is the user's decision.
- **F-C2 (defect, safety-relevant, PARTLY fixed):** with TalkBack on, the SOS countdown was **silent** (user, clean TalkBack session: "Nothing spoken during the
  count"), and a person feeling for CANCEL by ear could not reach it within 5 s: two countdowns ran to zero and started real SOS rows (68 and 70, both closed as
  false alarm; no contacts, nobody notified). The number had a polite live region and a per-second content description; TalkBack 14.5 on the A23 did not speak them.
  **Measured:** TalkBack's verbose log shows a 6.45 s utterance (the new Cancel label) cut off about 0.5 s later by `SamsungTTS onStop()` and a 2.0 s utterance; the
  text is hashed in the log, so what interrupts it is **not identified**. Per-second `announceForAccessibility` made it worse (only a fragment, or "5" and "4").
  **Fix (kept):** when the countdown starts, accessibility focus moves to CANCEL (`performAccessibilityAction(ACTION_ACCESSIBILITY_FOCUS)`, once per countdown), its
  label is "Cancel. The emergency is starting. Double-tap anywhere to stop it." (no number: it would go stale), the number is no longer a live region and nothing is
  announced per second. **Verified on the A23: one double-tap anywhere cancels the countdown** (several remote runs, no SOS row created).
  **NOT fixed / not verified:** what TalkBack SPEAKS at the start. The user heard only the fragment "Cancel" in three attempts; the countdown numbers are not spoken.
  A haptic tick per second (`performHapticFeedback`, no permission) would not depend on TalkBack: proposed, not built (the user's decision).
  Tests: `Phase17HTalkBackCountdownTest` (+3). Android **982 / 0**, lint **0 errors / 105 warnings** (104 before).
- **Device changes made and undone:** TalkBack was enabled through `secure` settings for the tests and is OFF again (original null/0); TalkBack's own permission
  READ_PHONE_STATE was answered "Don't allow" and is now fixed as denied for TalkBack (it kept asking "Allow phone access?" over the SOS screen, which also muddled the
  first by-ear run: **this stays changed**, say if you want it back); TalkBack developer "Display speech output" was turned on and is OFF again (log level was already
  VERBOSE); the logcat buffer was enlarged to 5 MiB (resets on reboot); `location_mode` had been left at 0 and was put back to 3; `stay_on_while_plugged_in` is 0.
- **Left behind:** QA emergencies 59-70 (RESOLVED or CANCELLED, trigger SHAKE or MANUAL). The backend started by this session (`spring-boot:run`, dev OTP file in the
  session scratchpad) is still running.
- *(Earlier note, session 1)* **Track C stopped here** (C1b screen-off shake skipped by the user's choice; C3-C8 not started; C2 open items above). Outdoor tests C3/C4 need the PC carried along
  (user's choice), so they are best done in one separate outing.

## 7. Track C, session 2 (2026-11-06): defects found in Explore on the A23

Setup: backend restarted (`spring-boot:run`, `MODERATION_AI_ENABLED=false`, dev OTP file), `adb reverse tcp:8080`, QA account as before.

- **F-C3 (defect, usability):** on Explore the Samsung keyboard's blue search key ran the search but left the keyboard open, so the answer appeared
  out of sight below it. **Fix (`ExploreFragment.startSearch`):** the search key and the Search button both close the keyboard (also when nothing was
  understood, so "We did not use ..." stays visible) and, once an answer or failure is drawn, scroll it into view. Installed on the A23; **user retest
  1:** "quiet cafe to study" closed the keyboard; "nice place" did not (my first version returned early when nothing could be searched), now fixed the
  same way. **Not yet retested by the user:** the "nice place" case and the scroll to the results.
- **F-C4 (word list gap, draft by design):** "amaidhiyana place" (Tanglish spelled with *dh*) was not understood; the list held only *amaithi* and the tag
  name *amaithiyana*. Added `amaidhi|amaidhiyana|amaithiyaana|amaidhiyaana` to QUIET (`explore-lexicon-v1.tsv`); `Phase17GExploreTest` +1. Tanglish has
  no fixed spelling, so more gaps will exist: a general rule (treat *th/dh*, *ai/ei*, doubled vowels alike) would be a design change and the user's
  decision; the human review of the Tamil/Tanglish list stays open. A second phrase then failed, "sapdra place" (a verb form of "eat"): added
  sapdra, saapdra, sapidra, saapidra, sapdalam, saapdalam, saapidalam to FOOD_CAFES (`Phase17GExploreTest` +1). The user confirmed the keyboard fix for
  "nice place" and "amaidhiyana place"; whether the Quiet chip lit for "amaidhiyana place" and the new "sapdra place" form were **not yet retested**.
  The pattern (nouns only, no verb forms, free spelling) means one-by-one additions will keep missing phrases.
- Tests: Android **984 / 0** (982 before); `assembleDebug` OK; lint not re-run; installed in place. The keyboard behaviour itself has no unit test (it
  needs a real IME); it is covered only by the by-hand retest.
- **Retest result (user, same day):** the keyboard closes for "quiet cafe to study" and "nice place" (F-C3 fixed by the user's report); "amaidhiyana place" and
  "sapdra place" now work ("Tanglish works properly"). The scroll-to-results part of F-C3 was not specifically reported.
- **Open (found by the retest, not a defect of the code as designed):** "best place for chilling" selects no chips and Search stays disabled: "best" is a
  ranking word the product refuses on purpose (shown as unused) and "chilling" has no term. A person can still choose chips by hand. Whether "chill / chilling /
  hangout" should map to a tag (and to which) is a product decision for the user.
- **Decision taken (user, same day):** build a general Tanglish spelling rule plus a reviewed phrase list **after a plan is approved**. Nothing built yet.

## 8. C7 "I went" + rating on the A23 (2026-11-06, session 2)

Place: "Counted Lake stqa" (id 24, Chennai), QA account 362 (73 h old at the time, so past the 72 h probation). Method: I drove the phone with adb taps and read the
UI tree and the MySQL row after every step (a read-only option file in the session scratchpad, deleted afterwards; credentials never printed). Backend restarted
at 09:31 UTC with `EXPLORE_UNDERSTANDING_MODE=ON` for C6.

| Step | Result | Evidence |
|---|---|---|
| Before: place page | shows "4.3 out of 5 · from people who went", no count, no names; button "I went here" | UI tree; 6 other visits on the place, all rated (five 5s from accounts 305 h old, one 4 from an account 65 h old = under probation) |
| "I went here" | sheet "How was it?": five buttons 1-5 at 48 dp (135 px), spoken "Rate N out of 5", the line "Optional. Your rating is private to you. A place shows an average only once enough people who went have rated it.", "You went here. You have not rated it.", Share your experience / I did not go here / Done | UI tree, screenshot |
| Rate 1 | button 1 lime fill AND the words "Your rating: 1 out of 5", spoken "1 out of 5, your rating", "Take back my rating" appears; row `rating=1`, `rated_at` set | screenshot, DB |
| Done | place page button "You went here · 1 out of 5"; **the public line stayed 4.3** (a counted 1 would give about 4.0). Consistent with the once-per-IST-day memo (first read of the day was before my rating); also survived an app relaunch | UI tree. Limit: I cannot read the memo itself, and a day boundary was not crossed |
| Take back my rating | visit kept, `rating = NULL`, `rated_at = NULL`; sheet says "You have not rated it" | DB, UI |
| Rate 3 | `rating = 3` | DB |
| I did not go here | **row deleted** (`NO ROW`); button back to "I went here" | DB, UI |
| Post page: "I went" → rate 5 → Share your experience | visit row, then `rating = 5`; the composer opened (nothing shared: 0 new posts); "I did not go here" from the post page deleted the row | DB, UI |

- **F-C5 (defect, accessibility):** after a visit, the place-page button's text became "You went here · 1 out of 5" but its spoken label stayed "I went here: mark that
  you went to … and say how it was", so a TalkBack user was told the first-visit prompt and never their state (the visible and spoken words also disagreed).
  **Fix:** `PostCollectionFragment` picks the spoken label by the same three states as the text (`place_went_description_state`, `place_went_description_rated`:
  "You went to X and rated it 1 out of 5. Open to change it, share your experience, or take it back."). Tests: `Phase17HWentLabelTest` (+3). Android **987 / 0**.
  Verified in the A23's accessibility tree after reinstall; **not heard by ear**. The post-page button keeps the same word "I went" in every state, so it has no such
  mismatch.
- **Not run on the phone (rest on the 17G-2 backend tests and its earlier live API run):** a place the caller cannot see answers 404, rating before "I went" 409, a
  paused person 403, the rating limits, and the five-raters threshold crossing from "no line" to "line" (the place already had it).
- **Dev data:** the QA account has no visit left on place 24 and no new post.
- Lint after the C5/C7 changes: **0 errors / 105 warnings** (unchanged from the end of session 1).

## 9. C6 Smart understanding (Groq free tier) on the A23 (2026-11-06, session 2)

Setup: the user ran the Python service in their own PowerShell window with their Groq key (I never saw it; `IRL_UNDERSTANDING_API_KEY` was not set in any scope of
this PC and is in no file); `/v1/health` answered `"understanding": "UP"`. Backend restarted with `EXPLORE_UNDERSTANDING_MODE=ON`, the README's local
`MODERATION_SERVICE_KEY` (loopback; not the Groq key) and a per-person cap of 3 (`APP_EXPLORE_UNDERSTANDING_USER_DAILY_CAP=3`, in memory, so a restart resets it).
QA account 362; the app was the installed debug build. I drove the phone with adb and read the UI tree; the user typed the Tamil request and one more.

| Check | Result | Evidence |
|---|---|---|
| Switch offered only because the server offers it; starts OFF; honest summary | **PASS** | "Smart understanding (Preview)", unchecked; "Off unless you turn it on. When on, the words you type are sent to IRL's server and then to an external AI service ... Searching works without it." |
| Consent question | **PASS** | "Use smart understanding?": words go to IRL's server and an external AI service, "no places, no location and no account details", "IRL does not save them", "you can turn it off at any time"; **Not now** left the switch off; **Turn on** switched it on and the helper said "Smart understanding is on, so your words are sent when you search" |
| English (a 11-word sentence about a calm place to sit with a book and a coffee after work) | **PASS** | chips Food and cafés + Quiet + Good alone + Evening replaced the typed ones, note "Understood with smart understanding. You can change the choices.", a place came back ("Matches 3 of the 4 things you asked for") |
| Tanglish ("amaidhiyana edathula kaapi kudikka evening position irukkum") | **PASS** | Food and cafés + Quiet + Evening, same smart note, place found. Limit: most of those words are also in the phone's list, so this does not show the AI was needed |
| Tamil (typed by the user on the Samsung keyboard) | **PASS by the user's report** ("chips filled") | I could not read it back: the user typed one more request afterwards |
| Same words searched again (twice) | **PASS** | no new "Standard understanding" note, chips unchanged; had a repeat used a call, the Tamil request (3rd distinct) would have fallen back |
| Cap of 3 per person: the next requests | **PASS** | the 4th ("Quite place", typed by the user) and my 5th ("quiet cafe to read in the evening") showed "Standard understanding was used" once, the phone's chips were searched and places returned; hand-typed misspelling "quite" shown under "We did not use: quite". I did not separate "cap" from "breaker"; both end in the same fallback |
| Logs: typed words and keys | **PASS for the backend and the phone; the Python window NOT seen** | backend logs (2 runs, 18 KB): 0 hits for any typed phrase and 0 for key patterns (`gsk_`, `sk-ant`, `Bearer ey`, the service key); phone logcat (7 MB): the typed words appear 7 times but ALL from `AccessibilityNodeInfoDumper`, i.e. my own `uiautomator dump`, none from the app; no `Authorization` / `Bearer`; two `refresh_token` lines are Samsung Account's own. The service window output was not available to me: the user should confirm it shows only categories, no text or key |

- Tests (no fixes were needed in C6): Python service **83 / 0** (`python -I -m unittest discover -s tests`, no key in that process); Android 987 / 0 and backend unchanged since session 1.
- Not tested: the service stopped mid-session / provider outage (covered by the 2026-11-03 live run, `PHASE17G3.md` §8), the 400 prompt-injection case, a provider refusal on the phone.
- Dev data: Groq calls spent on the user's free tier: 3 smart answers plus any attempts the user made; the backend cap state resets on restart.

## 10. C2 TalkBack, remaining flows (2026-11-06, session 2)

Method: TalkBack 14.5 (Samsung) enabled through `secure` settings, the user listened and answered; I could not read the speech myself (TalkBack's "Display speech output"
overlay did not appear in screenshots, so what was SPOKEN rests entirely on the user's words). Under TalkBack a one-finger adb swipe explores instead of scrolling, so I
placed the screens with TalkBack briefly off.

| Flow | Result | Evidence (user's report) |
|---|---|---|
| 1 SOS options (shake + audio switches, their long paragraphs) | **PASS by ear** | "All spoken clearly": state, name and type heard for both switches, the new state spoken after a double-tap, nothing confusing. The session-1 FAIL ("a switch had no state spoken", "a label long/confusing") is **not reproduced and its cause is not identified**; session 1's first run was also disturbed by TalkBack's own "Allow phone access?" prompt over the screens (since denied for TalkBack). No code was changed for this flow |
| 3 Explore (typing, chips, Search, answer) | **PASS by ear** | typing works the normal way; every chip says chosen or not chosen; Search reachable "in a few swipes"; the answer or the "We did not use ..." message is spoken by itself. The session-1 FAIL ("could not type", "chips not spoken") is not reproduced; the keyboard fix of F-C3 and Samsung's typing setting are possible but UNPROVEN causes. A typo ("quite place") correctly gave "We did not use: quite place" |
| 2 Nearby | PASS (session 1) | |
| 4 SOS countdown | **still PARTIAL** | one double-tap anywhere cancels (verified, session 1); what TalkBack SPEAKS at the start is still not fixed or verified; the haptic tick per second remains proposed, not built (the user's decision) |

- Settings seen on this TalkBack (not changed): "Element description order: State, name, type"; "Typing preference: Hold finger to select letter key, then lift"; "Single-tap to activate: Tap once on a focused item to activate" (a single tap on Explore opened it).
- **F-C5** (the "I went here" spoken label) was found in C7 and is verified in the tree only; it was not part of the user's by-ear pass.
- **Device changes made and undone:** TalkBack enabled for the tests and OFF again (`enabled_accessibility_services` null, `accessibility_enabled` 0); "Display speech output" toggled ON then OFF (its switch reads OFF); both SOS switches ended ON as before. **`location_mode` was found at 0 (not the original 3) and was put back to 3** (needed for C3/C4); I do not know what set it to 0.

## 11. C1b Shake SOS with the screen OFF, off USB (2026-11-06, session 2)

Conditions: battery 50%; a Safe Walk (30 min check-ins, journey 32) was started by me with the screen on, then the user unplugged the cable, turned the screen off,
shook the phone firmly three times, waited, and repeated for three rounds, then unlocked. The QA account has no Safety Circle contacts, so nothing could be sent.
Before: foreground service `SafetyTrackingService` (type location) running, accelerometer LSM6DSO registered at 20 ms by the app (`dumpsys sensorservice`).

| Observation | Evidence |
|---|---|
| A shake with the screen off **did start an SOS** (the user's words: "it started the emergency SOS while I shake my phone when the phone is off") | server row 72 (it reached the server about 6 minutes later, when the cable and `adb reverse` came back): `trigger_type = SHAKE`, `journey_id = 32`, `started_offline = 1`, started 10:22:56 UTC, `CANCELLED` 11 s later; journey 32 went to `EMERGENCY`; `dumpsys sensorservice` shows the app's `AndroidShakeSensor` released at 15:52:56 phone time (the start) and registered again at 15:53:07 (the end) |
| **Not every round triggered**: one SOS row for about three rounds | the user: "it not turn on the SOS every time" |
| The user saw **small shakes trigger it at times and harsh shakes not** | user's observation only; **not explained and not reproduced under measurement** |

Result: **PARTIAL.** Screen-off shaking is not dead, but it is unreliable, which is exactly what the SOS options text already says ("many phones pause their motion sensor when the
screen is off, so do not rely on shaking then"). The A23's only accelerometer is a non-wake-up sensor (`PHASE17F.md` §6.1): samples queue in a FIFO and arrive in batches when
something wakes the CPU, which fits a result that depends on what else woke the phone, but **that is a hypothesis, not a finding**. No countdown-notification count, no Cancel test
on the lock screen and no lock-screen privacy check were recorded. No code was changed. A follow-up would need temporary logging of batch arrivals (when each batch arrives, how old its
samples are, what `ShakePolicy` decided) in a repeated off-USB run; not built, the user's decision. Not claimed: any false positive while walking with the screen off (not tested here).

State afterwards: no ACTIVE emergency, no ACTIVE walk, no service running. Dev data: QA emergency 72 (CANCELLED, SHAKE), journey 32 (EMERGENCY, ended).

## 12. C3 Safe Walk and C4 Nearby report outdoors (2026-11-06, session 2)

Setup: the user carried the phone and the PC (USB, `adb reverse`) outdoors with open sky; QA account 362 (no Safety Circle contacts, automatic SMS off, so nothing could be sent);
the phone clock was 49 s behind the server. I started the walk by adb (2-minute check-ins, **no destination typed**), the user walked about 10 minutes and did the taps. Positions are
not recorded here (private; the rows hold them only as the app's last-known fix).

**C3 Safe Walk (journey 33):**

| Check | Result | Evidence |
|---|---|---|
| Start, foreground service, location fixes | **PASS** | journey `ACTIVE`; `SafetyTrackingService` (type location) running; `last_location_at` updated about every 2 minutes with accuracy 10.7 / 11.4 / 12.6 / 16.8 / 25.8 m (two outliers 75.5 and 82.5 m); the status-bar location icon was on |
| A due check-in the user did not answer | **PASS** (and a finding) | the prompt reached the user ("I noticed it", did not tap in time); level 1 at the due time, level 2 (no response) 2 minutes after; the screen read "No response to your check-in ... Your Safety Circle is not texted automatically (automatic SMS is off). If you need help, tap SOS or call 112. If you are fine, tap I'm OK", `+06:08 since your check-in was due`, notification `safety_alerts` importance 4; no emergency row was created (0 new) |
| "I'm OK" recovers | **PASS** | `CHECKED_IN`, level back to 0, next deadline moved 2 minutes ahead; a second check-in on time at 10:59:42 likewise |
| "I've arrived" ends the walk | **PASS** | `COMPLETED` at 11:00:56 UTC; `last_latitude/longitude` cleared (only the last-known fix is ever stored; no track), service stopped, no notification left |
| Level 1 once appeared about 47 s before the deadline | **not a defect, cause unattributed** | by design a movement concern ("unusual stop") opens the prompt early (`CheckInPolicy`); `DUE_SOON` starts 60 s before the deadline at level 0. I did not read a concern record, so I cannot say which it was |
| One person's rapid taps recorded 16 `CHECKED_IN` events in about 20 s | **observation** | harmless to safety; not changed |
| Not tested | | a typed destination, geocoder, route deviation (4C); the walk with the phone in a pocket and the screen off; alert audibility in a bag; battery cost; the Safety Circle SMS path (Track F); the "Are you OK?" notification on the lock screen |

**C4 Nearby report (one step):**

| Check | Result | Evidence |
|---|---|---|
| Nearby opens with location allowed; radar, area picture, "Updated 4:30 PM" | **PASS** | UI tree; area picture says "Little community information here" and "Based on community reports only. It cannot tell you that a place is safe" |
| Report sheet: "Choose what it is like here. Nothing else is asked: no text, no photo, no names." three groups, **Share anonymously disabled until a choice is made**, Cancel | **PASS** | UI tree (enabled=false before the choice) |
| The user picked the option that was true where they stood and tapped Share | **PASS by the user's report** | "Shared, saw a confirmation" |
| Server row | **PASS** | `place_signals` 9 -> 10: id 186, `POOR_LIGHTING`, `cell` 7 characters and `area_cell` 6 (a cell, never coordinates: the table has no latitude/longitude column), moderation NONE; `signal_contributions`: kind REPORT, `credited = 0`, **`counts_from` about 24 minutes after the report** (the delay that keeps a report from revealing the moment); the signal row carries no author |
| Not tested | | the rough-position refusal outdoors (a rough position never places anything), withdrawing a report, "I saw this too" on someone else's signal |

Dev data left: QA journey 33 (COMPLETED), signal 186 and its contribution; emergencies 72 (C1b).

## 13. Track D (long runs), session 3 (2026-11-06)

Track D = D1 25 h idle (PHASE11 acceptance check "an SOS after a day unopened reaches the server without the password"), D2 overnight battery, D3 long Safe Walk under Doze/OEM
restrictions. A real 25 h or overnight run cannot finish inside one working session, so each is split into what was done now and what is RUNNING, with the exact time marks.

### D1a. Accelerated session-expiry check on the A23 (done, USB attached) - PASS

Method (no code change): the backend was restarted with a different `JWT_SECRET` for that process only (an environment value held in memory, never written to a file or log), so
the access token the phone holds fails its signature check while its refresh secret (V19 `device_sessions`) stays valid. This is the same app path as an expired token (a 401 with no
`credentials_changed` / `session_revoked` reason). It does NOT reproduce what a real day adds: Doze, App Standby, a killed process or a cold Keystore after a long idle.

| Step | Result | Evidence |
|---|---|---|
| Secret changed, app started, Discover (Chennai) opened | **PASS** | content loaded; no "Sign in to sync" dialog; `device_sessions` row 227 refresh secret rotated 08:55:19 -> 11:21:31 UTC |
| Secret changed again while the app stayed open | **PASS** | the session rotated by itself 11:22:43 (17 s later), still `revoked = no` |
| SOS 73 started (MANUAL, online, ACTIVE), then the secret changed DURING the SOS | **PASS** | refresh at 11:24:37, a location point arrived 11:24:38; points kept arriving about every minute (4 by 11:26:30); status stayed ACTIVE; no dialog on the SOS screen (SOS never gets one) |
| "I'm safe" with the refreshed session | **PASS** | 73 `RESOLVED` 11:28:15; audio recording had started by itself (the QA consent is ON) |
| Normal secret restored | **PASS** | on the next app start the phone refreshed again at 11:29:08 = the start of the real idle clock (D1b below) |

Not claimed: that the 24 h expiry itself, the Keystore after a day, or an idle process behave the same; that is D1b.

### D3. Long Safe Walk under Doze, phone idle and unplugged (done: 94 minutes) - PARTIAL (core PASS; limits below)

Setup: Safe Walk journey 34 started 11:31:19 UTC (10-minute check-ins, "unusual stops" watch OFF, no destination), baseline taken first (standby bucket ACTIVE = 10, IRL on no battery
allowlist, no background restriction, `dumpsys batterystats --reset`). The user unplugged the cable at about 11:34:47 UTC (phone clock), turned the screen off, laid the phone flat and did not touch it,
and plugged it back in at about 13:03 (94 minutes in all). **Confounds, stated up front:** (1) the phone's Wi-Fi hotspot was ON (`+wifi_ap` in the battery history); (2) the only
route to the server was the USB cable, so while unplugged the phone could NOT reach the server (a test artefact, not a Doze result); (3) the phone was on sound-off/silent (status-bar icon),
so alert audibility was not tested; (4) one phone, one run, a stationary phone (the worst case for Doze).

| Check | Result | Evidence |
|---|---|---|
| Doze reached | **observed** | `device_idle=light` at +7m26s after the screen went off; maintenance windows (`off`) at +13m41, +26m, +41m, +57m; **`device_idle=full` (deep Doze) at +1h08m04s** until the cable went back in at +1h33m03s |
| Foreground service survived light AND deep Doze | **PASS** | `+fg=u0a550` (IRL) at the start, never a `-fg` afterwards; `SafetyTrackingService` still running at 13:04; 159 IRL wake-lock acquisitions in the light phase and 118 during deep Doze |
| Check-in logic ran on time with the screen off | **PASS** (light Doze only) | notification 4101 created 17:09:32 IST and updated to "No response to your check-in" at 17:12:31 IST on the PHONE clock; adding the measured 49 s the phone was behind, that is 11:40:21 (the 60 s `DUE_SOON` warning before the 11:41:19 deadline) and 11:43:20 = due + 2 min exactly (`CheckInPolicy` response window). Both happened in light Doze; **nothing time-critical was due during deep Doze, so deep Doze was not exercised for timing** |
| The app's own view afterwards | **PASS** | the Safe Walk screen showed "No response to your check-in ... Your Safety Circle is not texted automatically (automatic SMS is off)" and "+88:57 since your check-in was due", which matches 11:41:19 + 88:57 = 13:10:16 |
| Server during and after | **observation (limit, expected)** | during the run the server saw nothing (`last_fix` stuck at 11:33:40, level 0, no escalation rows). About a minute after `adb reverse` came back the phone reported level 2 and the position resumed: escalation rows 58 (`CHECK_IN_MISSED`) and 59 (`NO_RESPONSE`) were both stored at 13:05:47 UTC, i.e. **stamped with the delivery time, about 84 minutes after they happened**. With a real network the delay would be the normal one; the stamp is "when the server heard", which the docs already say (check-ins run on the phone; no server-side alert channel). No emergency was created (0 new) |
| GPS in deep Doze | **finding (platform limit, documented)** | `-gps` at +1h08m04s136ms, the same instant as `device_idle=full`; `+gps` only at +1h33m03s when the cable went back; fixes in that stretch were network-level (accuracy 100 m to 900 m, e.g. 900.0 m for the last stored position before the unplug). Added to `PLATFORM_CONSTRAINTS.md` |
| Battery | **measured, not clean** | 56% to 46% over the unplugged 88 minutes; `dumpsys batterystats`: computed drain 428 mAh (actual 405-450) of 4500 mAh; **IRL (uid u0a550) 67.5 mAh = about 16%**, of which GNSS 63.0 mAh (1 h 02 min of GPS, only until Doze switched it off), wake locks 2.5 mAh, cpu 1.7 mAh; the rest was the kernel/idle/other apps and the hotspot. This is a short run; **no 8-hour or overnight extrapolation is claimed** |
| The walk ended cleanly | **PASS** | "End this Safe Walk?" -> "I've arrived": journey 34 `COMPLETED` 13:11:05, position cleared, service stopped |

Not tested in D3: a moving phone in a pocket (it would not reach deep Doze), the phone's own network under Doze (the server was unreachable by design), the Safety Circle SMS path,
sleeping-apps / Adaptive-battery behaviour after days (E), alert audibility, and any run longer than 94 minutes.

### D1b + D2. The real 25-hour idle run and the overnight battery measurement: **NOT VERIFIED, DEFERRED (the user, 2026-11-06: "impractical for me"; to be done later)**

**(2026-11-07: the reference times below are VOID.** Tracks E, F and H, the reboot in E5 and the close-out in §17 all opened and re-installed the app after 13:11 UTC on 2026-11-06, so the idle clock and the token's age no longer match them. A later D1b run must read the times afresh at its start.)

Reference points (server UTC): the last app interaction was **13:11 UTC on 2026-11-06** (the walk ended); the refresh secret was last rotated **11:29:08**, so the current access token (24 h)
expires about **11:29 UTC on 2026-11-07**; 25 hours after the last interaction is **14:11 UTC = 19:41 IST on 2026-11-07**. D1b and D2 share one run: an idle, unplugged phone, which also measures the idle drain.

**What the user does (physical):** (1) turn the phone's hotspot OFF; (2) charge to at least 90% and leave IRL untouched; (3) unplug (a charge to 90% or more makes Android reset its battery
statistics at the unplug, so "since last charge" covers exactly the idle run); (4) leave the phone alone, screen off, normal place, nothing opened, until after **8:00 pm IST on 2026-11-07**;
(5) then plug it in, unlock it, and tell Claude, **without opening IRL**.

**What Claude then does (nothing here has been run yet):** `adb reverse`, start the backend with the normal secret; read `dumpsys batterystats` (level at the unplug, level now, per-app mAh for IRL,
Doze share); read `device_sessions` (was the session refreshed while idle, and when; `revoked_at` must be NULL); then open IRL (an expired access token gets a 401 and a refresh), start an
SOS, and check that the SOS reaches the server without a password, with `started_session` and its location points; end it. PASS = no sign-in prompt, session not revoked, SOS recorded.
Anything else is recorded as FAIL with the evidence. If the app refreshed in the background overnight (the renewer runs hourly while the process lives) that is recorded too: it is the normal
behaviour and it means the token never expired.

**Status of D1b and D2 (2026-11-06, the user's decision):** the user cannot leave the phone idle for 25 hours now, so **D1b (real 25 h idle) is NOT VERIFIED and D2 (overnight battery) is NOT VERIFIED**; Track D as a whole is **PARTIAL** (D1a PASS, D3 PARTIAL). Nothing was run for D1b/D2, so nothing is claimed. The plan above stays valid for a later session, with one adjustment: the reference times quoted there (last interaction 13:11 UTC, last rotation 11:29:08) are only valid if nothing touches the app before the run, so they must be re-read from `device_sessions` and the last app interaction at the start of the later run. The `PHASE11.md` line "a 25-hour idle test on the Galaxy A23 is the remaining acceptance check" therefore still stands. D1a covers only the refresh logic (with an invalid token), not the real expiry.

## 14. Track E: Samsung / OEM behaviour on the A23 (2026-11-06, session 3)

Scope pinned from the docs (`CLAUDE.md` §11-12, `PLATFORM_CONSTRAINTS.md`, `PHASE4B/4C.md`): OEM battery killers may stop foreground services and the documented design is **"recovery on next open"** (`START_NOT_STICKY`, no boot receiver);
uploads resume after a plain kill through JobScheduler but "Force stop" cancels jobs by design and nothing survives a reboot; a prompt that was open survives a force stop with no duplicate escalations. A plain `kill -9` was already
PASS in B4, so it is not repeated. Judging rule: FAIL = data lost that should survive, a wrong state or banner after recovery, an SOS or walk that cannot be ended, or a crash; "did not restart by itself" is NOT a failure.
Method: I drove the phone with adb and read `dumpsys`, the system event log (`logcat -b events`), the database and screenshots; QA account 362 (no contacts, so nothing could be sent). One phone, one OS build (One UI, Android 14, TalkBack 14.5).

**E0 baseline (read only):** battery mode for IRL **Optimized** (the default); standby bucket ACTIVE (10); `RUN_ANY_IN_BACKGROUND` default allow; not on the Doze allowlist; Samsung's "Sleeping apps / Deep sleeping apps" lists were **NOT read** (not reachable by adb).

| # | Check | Result | Evidence |
|---|---|---|---|
| E1 | Swipe IRL away from Recents during an ACTIVE **Safe Walk**, battery "Optimized" | **FINDING (platform kill, silent); recovery PASS** | right after the swipe the process and service were alive; **45 s later Samsung's own process manager killed IRL** (`am_kill ... Chimera #0`, adj 200) while it was running the walk's foreground service; location points stopped; the 10-minute deadline passed with **no notification and no escalation**; the server still showed the walk ACTIVE at level 0. On reopen the banner read "Safe Walk: check-in missed. Tap to check in.", the server got level 2 with the escalation rows stamped at the reopen time, the service restarted only when the walk screen was opened (by design: `TrackingServiceStarter` starts it from a visible walk/SOS screen), and location resumed. **With "Unrestricted"** the same swipe (verified in the event log) left the process and service alive for 90+ s, points still arriving |
| E1b | Same swipe during an **SOS** (Optimized) | **PASS** | process, service and location points alive for 8 minutes (audio recording on), and again for 2.5 minutes with the automatic audio OFF (location-only service). Why the SOS is spared and the walk is not is **not established** (a high-importance notification or the microphone are guesses) |
| E2 | Samsung "Check background activity" -> **Stop** during a walk | **FINDING (same as E1); recovery PASS** | the panel lists IRL with a Stop button; Stop = `fully stop com.safesphere.app/0 by user request`, process dead, no notification left in the shade, app NOT in the stopped state (unlike Force stop), server walk still ACTIVE; reopening restored the walk and its state (level 2 by then) |
| E3 | **Force stop** during an ACTIVE **SOS** (audio recording, 21 sealed parts), then reopen | **PASS** (against the documented design) | process dead and `stopped=true`, nothing arrived for 2 minutes (21 parts, 11 points), SOS stayed ACTIVE on the server; on reopen the banner "Emergency active. Tap to open it." appeared, **one pending audio part (#119) uploaded by itself**, opening the screen restored the SOS with its original start time and the service, points resumed (11 to 13); the automatic audio did NOT restart (once per SOS, by design); "I'm safe" ended it and `RESOLVED` reached the server; 22 parts, all sealed |
| E4 | Samsung's real **Restricted** battery mode, simulated unplugged (`dumpsys battery unplug`, reset afterwards) | **FINDING** | system state: bucket 45 (RESTRICTED), `RUN_ANY_IN_BACKGROUND = ignore`. **Walk:** the foreground service was gone 6 s after IRL left the screen, location stopped (the process lingered). **SOS:** location stopped at once (1 point), audio parts kept arriving for about 4.5 minutes, then the system killed the cached process (`cached idle & background restricted`); the SOS stayed ACTIVE on the server and was ended normally afterwards |
| E5 | Reboot with an ACTIVE walk (walk 39, due 15:45:02), the user unlocked with the pattern only | **PASS** (against the documented design; see below) | |
| - | Days-long "put unused apps to sleep" / auto-optimisation | **NOT VERIFIED** | cannot be waited for; E4 only simulates its strongest form |

**F-E1 (genuine gap, fixed): the app said nothing about any of this.** On the default setting a person who swipes IRL away believes the walk is still checking on them; on "Restricted" both the walk and the SOS lose their location the moment IRL leaves the screen. The app cannot change the phone's policy, but it can say so.
**Fix (small, no new permission, no new button):** `core/device/BackgroundLimit` (pure `BackgroundLimits.of(restricted, ignoringOptimizations)` over `ActivityManager.isBackgroundRestricted` and `PowerManager.isIgnoringBatteryOptimizations`; a failed read says nothing);
`WalkHint.BATTERY_OPTIMIZED` / `BATTERY_RESTRICTED` on the active Safe Walk screen ("On some phones, including Samsung, closing IRL from your recent apps ends this walk's check-ins until you open IRL again. Leave IRL open, or in the phone's App info set Battery to Unrestricted."; "Battery use is restricted for IRL, so this walk stops checking on you as soon as you leave IRL ...");
and one line on the SOS screen **only for Restricted** (an SOS survived Optimized): "Battery use is restricted for IRL, so your location may stop being saved when you leave this screen ...". Nothing is shown when the phone is Unrestricted. The app does not request the "ignore battery optimisation" permission and does not open any dialog (`Phase17HBackgroundLimitTest` guards both).
Tests: `Phase17HBackgroundLimitTest` (+6). Android **993 / 0**.
**Verified on the A23 by screenshot:** Optimized -> the walk note; Restricted -> the stronger walk note and the SOS note (above "Low accuracy right now..."); Unrestricted -> no note on the walk. Not measured: that people read it, TalkBack by ear.
Incidental, correct: starting a walk while an SOS is active is refused with "An emergency is active. End it before starting a Safe Walk".

**Device changes made and undone:** IRL's battery mode was changed through App info to Unrestricted, Restricted and back to **Optimized (final, as at E0)**; `dumpsys battery unplug` then `reset` (the system reports USB powered again); standby bucket back to 10, appops back to allow, not allowlisted; the SOS options switch "Record audio in an SOS" was turned OFF for one test and back **ON**; no ACTIVE SOS left (74, 75, 76, 77 all RESOLVED).
Dev data left: QA emergencies 74-77 (RESOLVED), journeys 35-39 (walk 39 still ACTIVE for E5), signals as before.

**E5 detail (reboot with an ACTIVE walk):** after the restart IRL was **not running**, no IRL notification was posted and **no boot receiver is declared** (`BOOT_COMPLETED` absent), exactly as designed; the walk stayed ACTIVE on the server at level 0 while its deadline (15:45:02) passed with the app dead and no escalation. On the first open (15:48:28) the banner read "Safe Walk: check-in missed. Tap to check in.", the server received level 2 (rows `CHECK_IN_MISSED` and `NO_RESPONSE`, both stamped at the reopen time 15:48:27), no sign-in dialog appeared, and "I've arrived" ended it (`COMPLETED`).
**Partial evidence for D1b (does NOT change D1b's status, still NOT VERIFIED):** the first open used the saved access token (it was still valid), so I changed the backend's signing secret once more (process only) and the app refreshed within seconds with the Keystore-sealed refresh secret after the cold boot (rotation at 15:50:34, previously 11:29:08, no revoke, no dialog); after the normal secret was restored it refreshed again (15:55:51). What is still unproven is the real 24 h expiry after a day of idling.

**Track E overall: PARTIAL.** PASS: E1b, E3, E5 and the recovery half of E1/E2. FINDINGS (platform behaviour the app cannot change; now disclosed in the app, F-E1): E1/E2 (Optimized: the walk is ended silently by the phone), E4 (Restricted: walk and SOS lose location). NOT VERIFIED: Samsung's Sleeping/Deep-sleeping lists, the days-long auto "put unused apps to sleep", a second Samsung model or OS version, TalkBack by ear on the new notes.
Open question for the user (not decided here): the walk is only restarted when the walk screen is opened, so opening IRL on another screen leaves a "missed" walk unmonitored until the banner is tapped. This matches the documented `TrackingServiceStarter` design, so it was left unchanged.

## 15. Track F: SMS and Call 112 on the A23 (2026-11-06, session 3)

Scope (`PHASE4D.md` §2 and §8, 12 checks, all NOT VERIFIED until now): the Safety Circle is texted **automatically only if** the person opted in AND `SEND_SMS` is granted AND SMS is supported AND at least one contact is enabled; otherwise nothing is sent and the screens say so and offer
"Text" (the SMS app opens, the person sends it); statuses SENDING (never reported) -> SENT (left the phone) -> DELIVERED (network report) | FAILED | COMPOSER_OPENED; "Call 112" = `ACTION_DIAL` (the person presses call) recorded once as level 4 "dialer opened", no `CALL_PHONE` permission.
Rules for this track: SMS only to a number the user owns and agreed to; the digits are never written to the repo or these docs; I never press the dialer's call button.

**F0 baseline:** SIM 1 loaded and IN_SERVICE (Jio), SIM 2 absent; call state idle; `SEND_SMS` NOT granted (appop `ignore`); `CALL_PHONE` not declared; QA account 362 has 0 Safety Circle contacts; Samsung Messages / Samsung Dialer are the apps.

| # | Check | Result | Evidence |
|---|---|---|---|
| F1 | **Call 112** during an ACTIVE SOS (78, no contacts) | **PASS** | the Samsung dialer opened with **112 typed and the call button untouched**; `mCallState` stayed 0 (idle) before, during and after; the call log has no entry for 112; Back (twice) returned to IRL's SOS screen; server `escalation_events` for SOS 78: `4 / DIAL_PROMPT_OPENED` once; "I'm safe" ended it (`RESOLVED`) |
| F1b | Emergency details screen | **PASS** | "Level 4 · Dialer opened for 112", last saved location with "Open in maps", evidence line, and the honest note: "'Sent' means an SMS left the phone, not that it was received." |
| F2 | Opt-in card (checklist 2) | **PASS** | "Text my Safety Circle automatically": switch OFF, "Off. Nobody is texted automatically. You can still text your contacts yourself from the SOS and Safe Walk screens.", and an example of exactly what they would receive (name, time, last known location link, "call 112", "(Sent automatically by IRL.)") |
| F3a | Turn the switch on: rationale, then refuse (checklist 3) | **PASS** | the app's own rationale comes first ("It only texts your enabled contacts and never reads your messages."), then Android's dialog ("Allow IRL to send and view SMS messages?", it names the whole SMS group, as `PHASE4D.md` §7 already says); **Don't allow** left the switch OFF and `SEND_SMS` ungranted. On this install the permission dialog appears directly: `SEND_SMS` carries `RESTRICTION_INSTALLER_EXEMPT`, so Android's "Allow restricted settings" step was NOT needed (it may differ for another installer) |
| F4 | Contact added in the app (name "Track F test", relationship required, enabled) | **PASS** | 1 contact on the server; the number is one the user typed in chat and is NOT recorded here |
| F5 | SOS with automatic SMS OFF and one contact (SOS 79) | **PASS** | the contact row said **"Not notified"** with a **Text** button; the system's sent-SMS count stayed 194 and the outbox stayed empty (nothing was sent by the app); Text opened Samsung Messages with the message pre-filled (name, time, last-known location link, "call 112") and the recipient chosen; server `circle_alerts`: `SOS_STARTED / SMS_COMPOSER / COMPOSER_OPENED` (status only: no number and no text are stored there). **I did NOT press send**: see the note below |

**Stop, 2026-11-06 ~22:25 IST:** the Messages thread that opened for the typed number is saved on this phone under a **person's name** (it looked like a family member's), with older personal messages and a carrier notice that the number is "now available to take calls". That does not match "a second number of mine", and the rule for this track is SMS only to a number the user owns. I closed the composer with Back, sent nothing, and asked the user to confirm. **Every check that needs a real SMS (F6-F10) is on hold until the user confirms a number they own.**

**Ownership confirmed (user, ~22:30 IST):** after the stop above the user answered "It's mine, send the tests" for the number, and agreed to up to about 6 test messages. The user later reported what arrived on the receiving phone.

| # | Check | Result | Evidence |
|---|---|---|---|
| F6 | Turn the switch on, rationale, **Allow** (checklist 3, second half) | **PASS** | "On. 1 contact(s) will receive an SMS from this phone. 'Sent' does not mean received."; `SEND_SMS` granted |
| F7 | SOS start with automatic SMS ON (SOS 80, one contact) | **PASS** (sent: yes, delivered: not confirmed by the network) | exactly **one** SMS left the phone within 8 s (system sent count 194 to 195); `circle_alerts`: `SOS_STARTED / SMS_AUTO / SENT` and it never moved to `DELIVERED` in 70 s; the contact row read **"SMS sent 10:33 PM · delivery not confirmed"**. **User: it arrived** on the other phone, so a missing delivery report is not a failed delivery, which is exactly why the app says "not confirmed" |
| F8 | "I'm safe" (checklist 6) | **PASS** | sent count 196; `SOS_RESOLVED / SMS_AUTO / DELIVERED` (the network's delivery report); text "IRL: <name> ended the SOS at 23:02 and marked themselves safe. (Sent automatically by IRL.)"; user: arrived |
| F9 | Safe Walk, 2 min, **no answer** (checklist 7, first half; journey 40) | **PASS** | level 1 at the deadline texted nobody; **the SMS went out at level 2** (~2 min after, 17:21): `WALK_NO_RESPONSE / SMS_AUTO / SENT`; the red card said "Your Safety Circle is being texted that you did not answer", then "Your Safety Circle was texted (1). Delivery is not confirmed."; user: arrived |
| F9b | **I'm OK** (checklist 7, second half) | **PASS** | `ALL_CLEAR / SMS_AUTO / DELIVERED`, sent count 198; text "responded at 22:54 and is OK. You can ignore the earlier Safe Walk alert."; screen "Your Safety Circle was told that you are OK."; ending the walk afterwards sent nothing more |
| F10 | **Airplane mode** (radios off, checklist 9; SOS 81) | **PASS** | the SMS could not leave: `SOS_STARTED / SMS_AUTO / FAILED` and `SOS_LOCATION / SMS_AUTO / FAILED`, the sent count stayed at 198, the screen read **"SMS failed 10:58 PM · tap Text to send it yourself"**, and Samsung Messages marks both with its red "!". **Not silently retried**; after the radios returned, the "ended" message went out normally (`SOS_RESOLVED / SMS_AUTO / DELIVERED`, count 199). User: the failed ones did NOT arrive, the others did |
| F11 | Automatic SMS **OFF**, walk unanswered (checklist 8; journey 41) | **PASS** | level 2 reached, **nothing sent** (count 199, no alert row), card: "Your Safety Circle is not texted automatically (automatic SMS is off)" and "Your Safety Circle will not be told automatically"; **Text my Safety Circle** opened "Who should get the message?", then Samsung Messages with the pre-filled text ("IRL alert: <name> did not answer a Safe Walk safety check at 23:14 ... This is not a confirmed emergency. ... If you cannot reach them and are worried, call 112." and no "(Sent automatically by IRL.)" tag); I did not send it; `WALK_NO_RESPONSE / SMS_COMPOSER / COMPOSER_OPENED`; the screen said "The SMS app was opened for your Safety Circle. Check that the message was sent." |
| F12 | Content of what the contact received | **PASS by the user's report** | all 5 sent messages arrived; none for the failed airplane-mode ones; each held only name, time, what happened and a map link (plus "(Sent automatically by IRL.)"); "call 112" advice present |

Totals: **5 real SMS** left the phone (sent count 194 to 199), all to the one number the user confirmed; 2 more were attempted and failed (airplane mode); 0 were sent by the composer paths (I never pressed send there).
Also checked on the way: no `CALL_PHONE` permission is declared; the app never reads messages; `circle_alerts` stores only kind / channel / status (no number, no text).
**Not tested:** more than one contact; a second SIM or SIM selection; a message long enough to need several parts; revoking `SEND_SMS` while the switch is ON (covered by `CircleAlertCoordinatorTest` only); the 30-minute "stale emergency sends nothing" rule; the 10-minute location follow-up with a real fix (the follow-up attempted during the airplane test failed, as it should); another phone or carrier; whether a person reads the delivery wording correctly.
**Device and data changes made and undone:** the test contact was removed through the app (0 on the server, so the phone's offline copy is gone too); `SEND_SMS` was granted for the tests and **revoked again** (`pm revoke`, so Android now records it as user-denied rather than never asked); the automatic SMS switch is OFF (as at the start); airplane mode is OFF; no ACTIVE walk or SOS (SOS 78-81, journeys 40-41 ended). Dev data left: QA emergencies 78-81 and journeys 40-41 and their status-only `circle_alerts` rows. **The Samsung Messages thread for the number now contains the test messages on the user's phone: delete them if unwanted.** No code was changed in Track F, so tests and lint are as at the end of Track E.

**Track F overall: PASS** for every check that was run (F1-F12), with the limits above.

## 16. Track H: emulator version matrix (API 26, 29, 33), session 3 (2026-11-07)

Purpose: the debug build (`minSdk 26`, `targetSdk 35`) had only ever been run on the API 37 emulator and on the Galaxy A23 (Android 14). H runs the same scripted checks on Android 8.0 (API 26, the lowest supported), 10 (API 29) and 13 (API 33, where `POST_NOTIFICATIONS` and the system Photo Picker arrive).
**Setup (nothing in the repo changed for it):** the SDK had only an API 37 image and no command-line tools, so I downloaded Google's `cmdline-tools` (the zip's SHA-1 was checked against Google's own repository manifest before use; the SDK licences were already accepted) and the three `system-images;android-N;google_apis;x86_64` images (935 MB, 1254 MB, 1629 MB); AVDs `irl_api26`, `irl_api29`, `irl_api33` (Pixel 5 profile, 3 GB RAM, headless, `swiftshader_indirect`, hardware acceleration on). The same debug APK (built 2026-11-06 20:47, 993 unit tests green) is installed on each; `adb reverse tcp:8080` reaches the dev backend; a **brand-new scratch account per API** is created through the real register + dev-OTP-file flow (fake numbers `90000100NN`, no SMS sent), so every run starts from a fresh install, onboarding and login. The harness is session scripts (not in the repo), written first against the API 37 AVD where every step passed.
**Method and its limits:** each step drives the UI by `uiautomator` and checks the server database and `dumpsys` (service, notifications, permissions), plus a crash scan (`logcat -b crash`, ANR, foreground-service errors). Emulators have no real Doze/OEM killer, no real GPS (mock fixes via `emu geo fix`), no SIM/SMS tested here (that was Track F on the phone), no real TalkBack. The animated Safe Walk countdown makes `uiautomator` dumps fail intermittently; the script retries. **API 37** results below (SOS, walk, kill, offline) are the harness check, not part of the requested matrix.

**Defect found by the matrix and fixed (F-H1, Android 13):** with the notification permission not yet allowed, the **first SOS** showed the system dialog "Allow IRL to send you notifications?" over the SOS screen and, **while it stayed unanswered, the tracking foreground service was not running** (sampled at 8, 14 and 22 s after Start: `running=False`; the SOS was ACTIVE on the server and saved location only while the screen was visible; the screen even said "Keep IRL open: with the screen off or another app in front, location may pause"). A person who pressed Home instead of answering would have lost the location. The code comment already said "granted or not, start now", but `TrackingServiceStarter` waited for the answer. Android lets a foreground service run without that permission (it only hides the notification).
**Fix (`core/tracking/TrackingServiceStarter`, one function):** the service is started first and the question is asked next to it; no state waits for the answer. The Safe Walk path is unchanged (it asks before starting and never asks from the starter). `Phase17HTrackingStarterTest` (+3 source guards). Android **996 / 0** (993 before).
**Verified on the Android 13 emulator, same timed reproduction on the fixed build:** dialog open and unanswered, `service running=True` from 8 s on, the "keep IRL open" line gone; **Home pressed with the dialog still unanswered: the service kept running and location points kept arriving (1 to 2)**; answering later gave `POST_NOTIFICATIONS=true`. Not tested: the SOS notification text appearing after a late "Allow" on a service that was already running (not needed for the fix; the platform decides).

**Second finding of the same kind (F-H1b) and its fix:** with F-H1 in place, **after a late "Allow" the service's ongoing notification never appeared** (0 IRL notification records on Android 13, still 0 fifty seconds after allowing, the shade empty): a service that entered the foreground while the permission was missing is not re-posted by the platform. (Before F-H1 the notification did appear after "Allow", because the service only started then; F-H1 had traded that for keeping the location alive.)
**Fix:** when the permission answer is "allowed" and a service was wanted, the starter starts the service once more; `onStartCommand` re-enters the foreground, which posts the notification. `Phase17HTrackingStarterTest` +1 (4 tests now). Android **997 / 0**, lint **0 errors / 105 warnings** (unchanged, none in the changed files).
**Verified on the Android 13 emulator:** permission revoked, SOS started, question left open: service running, **0** notifications; after "Allow": **1 IRL notification** within 8 s ("Emergency active - Your location is being saved to your emergency record." in the shade, screenshot). The Galaxy A23 (Android 14) was not re-run for F-H1/F-H1b: the A23 already had the permission allowed in every Track B-F run, so it never showed the question.

### H results (final)

Every API ran the same scripted list on a **brand-new account and a clean install** (login, 11-screen sweep, the real location dialog, SOS with its foreground service / location points / ending, Safe Walk with its missed check-in / alert / "I'm OK" / arrival, `kill -9` during an SOS with recovery, an SOS started with the server unreachable and synced afterwards, light 1.0x and dark 2.0x text on 3 screens, a whole-run crash scan). The server database was read at each step. A first pass failed some steps because of **script faults, not the app** (a hardware Enter key instead of the keyboard's search key, `run-as` not seeing the process id on API 29, `uiautomator` unable to dump the animated Safe Walk countdown on API 29, too few scrolls on the longer Safety hub on API 33, a quoting error): each such step was repeated by hand or with the corrected script, as listed.

| Check | API 26 (Android 8.0) | API 29 (Android 10) | API 33 (Android 13) |
|---|---|---|---|
| Install, first launch, login | PASS | PASS | PASS |
| Explore typed search with the real soft keyboard (search key closes the keyboard, answer scrolled into view: F-C3) | **PASS** by hand (screenshot) | **PASS** by hand (screenshot) | PASS in the scripted run |
| Screen sweep (Discover, Saved, You, Safety hub, Safety Circle, Safe Walk, SOS options, Nearby, Emergency History, Settings) | PASS (11/11) | PASS (11/11) | PASS (3 screens by hand: the hub is longer on 13, the scripted run scrolled too little) |
| Location permission dialog and grant | PASS (old single DENY/ALLOW dialog) | PASS ("Allow only while using the app" / "Deny") | PASS (Precise / Approximate, "While using the app", "Only this time", "Don't allow") |
| SOS: start, foreground service, location points reach the server, ends RESOLVED | PASS | PASS | PASS |
| SOS foreground service **type** read from the system | not applicable (types start with Android 10) | `types` not printed by that `dumpsys` (service **running** confirmed); not verified | `0x8` (location), seen on the API 37 harness and the A23 |
| Notification permission (new in 13) | not applicable | not applicable | **F-H1, F-H1b found and fixed** (above) |
| Safe Walk: start, service, missed check-in escalates and posts an alert, "I'm OK" resets, arrival ends | PASS | PASS (the scripted "I'm OK" and arrival failed 3 times; **by hand on the final build both PASS**: level 2 to 0 with a `CHECKED_IN` event, then `COMPLETED`; the notification action also reset it) | PASS |
| `kill -9` during an SOS: banner, screen and service come back, SOS can be ended | PASS | PASS (scripted run failed because `run-as` could not see the process; a real kill: PASS, repeated in the recheck: PASS) | PASS |
| SOS started with the server unreachable, synced once back (`started_offline = 1`), ends | PASS | PASS | PASS |
| Display: light 1.0x and dark 2.0x on Safety hub, Explore, SOS start: no off-screen text, no target under 48 dp, no crash | PASS / PASS | PASS / PASS | PASS / PASS |
| Whole-run crash scan (FATAL, ANR, foreground-service start errors) | 0 / 0 / 0 | 0 / 0 / 0 | 0 / 0 / 0 |

The API 37 emulator ran the same list as the harness check (all PASS). **Overall: PASS on API 26, 29 and 33 for everything run, after one defect (two findings) found on API 33 and fixed.**
**A defect found by the first API 29 pass (the keyboard), already known:** none new; the F-C3 fix was confirmed on 26 and 29.
**Not covered by H (not claimed):** the Photo Picker and media on 13 (the system picker only exists from 13), the vault, Tamil input, smart understanding, SMS and Call 112 (a phone-only check, done in Track F), Doze and OEM behaviour (emulators have none), TalkBack, a Google Play image vs this `google_apis` image (no Play Store), real GPS (mock fixes), arm64 devices (the images are x86_64), Android 11, 12 and 14, tablets, and whether the "SOS foreground type" is right on 10 and 12 (not read).
**Dev data and state left:** 5 scratch accounts (`h.m26/29/33...@example.com`, ids 386-391, fake numbers) and their SOS/walk rows; **one SOS (id 96, scratch account 390, from an aborted API 33 run) is still ACTIVE** on the server and harmless (nobody has contacts); three AVDs `irl_api26/29/33` and their three images, measured at the end: images 3.1 + 3.2 + 8.2 GB, AVD data 14.2 + 13.7 + 4.7 GB, **about 47 GB in all** under `...Sdksystem-images` and `~/.android/avd` (C: still had 238 GB free); delete the AVDs and images from the SDK manager when no longer needed (an earlier line of this record that said "about 8 GB" was wrong); Google's command-line tools in `...\Sdk\cmdline-tools\latest`. All emulators are stopped; the A23 was not touched in Track H.
**State of the Galaxy A23 at the end of Track H:** it was **not attached** (`adb devices` empty after restarting the adb server, and Windows lists no Samsung or ADB device), most likely because the USB cable was unplugged or the phone was switched off during the long emulator work; I did not touch it in Track H and cannot confirm its settings until it is reconnected (they were as listed in §14/§15 when last seen: battery mode Optimized, automatic SMS OFF, `SEND_SMS` revoked, TalkBack off, no ACTIVE SOS or walk).

## 17. Close-out on the A23 (2026-11-07, session 4)

The user reconnected the phone and asked for the sprint to be closed, with **D1b (25 h idle) and D2 (overnight battery) kept OPTIONAL / DEFERRED**: they were not started and do not block closing. The phone was awake and unlocked by the user (nothing was scripted around the lock). Backend started again (`spring-boot:run`, `MODERATION_AI_ENABLED=false`, dev OTP file), `adb reverse tcp:8080`, QA account 362.

**Phone state found (read only, before any change):** Android 14, battery **16 % and charging**, `low_power` 0, battery mode Optimized (no allowlist, `RUN_ANY_IN_BACKGROUND` allow, standby bucket RARE = 40 after a day unused), automatic SMS OFF (the SOS screen said so), `SEND_SMS` revoked, TalkBack off, `stay_on_while_plugged_in` 0, `font_scale` 0.8, night mode 2, auto time OFF, **`location_mode` 0** (the second time it was found at 0, see §10; I changed it to 3, its original value in §4, because every SOS and walk test needs it; I do not know what sets it to 0, most likely the user turning location off between sessions). The installed build was the Track H APK (2026-11-06 20:48), which did **not** contain the F-H1/F-H1b fix.

**F-H1c (defect, found by re-running the F-H1 test on the real phone, fixed):** with the notification permission reset to a true first ask (`pm revoke` + `pm clear-permission-flags ... user-set user-fixed`, otherwise Android treats a revoked permission as permanently refused and never shows the dialog), the first SOS showed "Allow IRL to send you notifications?" and the tracking service **was running while the question stayed open** (F-H1 holds on Android 14: `isForeground=true`, `types=0x88`, still true after pressing Home, a HIGH_ACCURACY location request kept active). But after returning to IRL and tapping **Allow**, **no notification was ever posted** (service start count unchanged, shade empty). Cause: the answer callback only restarted the service while `requestedFor != null`, and `reset()` clears that in `onStop`, which pressing Home triggers. The emulator test of F-H1b had answered the dialog without leaving the app, so it never took this path.
**Fix (`TrackingServiceStarter`):** a separate flag `questionOpen` (set when the question is launched, cleared by the answer, NOT cleared by `reset()`); the callback restarts the service only when the question was open and the answer was "allowed" (the service stops itself if nothing is being tracked, so a late answer cannot leave a stray service). `Phase17HTrackingStarterTest` +1 (5 tests): `reset()` must not clear the open question; the callback must not depend on `requestedFor`.
**Verified on the A23 (final build, same sequence):** dialog open and unanswered: service running; Home: still running; back, **Allow**: `POST_NOTIFICATIONS` granted, notification **4001** posted and the service start count 2 to 3. "I'm safe" ended the SOS: service stopped, 0 IRL notifications. Not tested: a refusal ("Don't allow") on this path, other Android versions after F-H1c (the emulators were not re-run; the change is one flag in the callback).

**Regression smoke of the final APK on the A23 (all PASS):** SOS start and end (RESOLVED); Safe Walk 30 min start (service running, the Samsung "Optimized" note from F-E1 and the low-battery line shown, location precise) and "I've arrived" (service stopped, no notification left); Explore "quiet cafe to study" typed by adb: chips chosen from the words, the keyboard closed on the search key, and **the results were in view** (screenshot), which settles the F-C3 scroll-to-results item the user had not specifically reported.
**Tests and build (current tree):** Android **998 / 0**, lint **0 errors / 105 warnings** (unchanged), `assembleDebug` OK; backend and Python not touched this session (883 / 83 as before).

**Device state at the end:** SOS and walk ended, no service running, no IRL notification, TalkBack off (`enabled_accessibility_services` null), `stay_on_while_plugged_in` 0, `SEND_SMS` revoked, automatic SMS OFF, battery mode Optimized, `location_mode` **3** (found 0), `POST_NOTIFICATIONS` granted (originally granted and user-fixed; now granted, user-set only). The phone is at about 16 % and charging. Dev data: QA emergencies and journeys from these tests (RESOLVED / COMPLETED); an old SOS (id 96, scratch account 390, from Track H) is still ACTIVE on the server and harmless (no contacts).

**Not run, stay NOT VERIFIED (for the record):** D1b real 25 h idle and D2 overnight battery (deferred by the user; reference times void, see §13); Tamil-script Explore request typed by the user (C6 rests on the user's report of "chips filled"); TalkBack by ear on the F-C5 "I went here" label and on the F-E1 notes; what TalkBack **speaks** at the SOS countdown start; a second Samsung model / OS version; Samsung's Sleeping / Deep-sleeping lists and the days-long auto optimisation; the Safety Circle with more than one contact; a typed destination and route deviation; the walk with the phone in a pocket and the screen off; alert audibility in a bag.

**Open decisions for the user (nothing here was built; each is the user's call):**
1. **SOS countdown under TalkBack:** a haptic tick per second (`performHapticFeedback`, no permission) so the count does not depend on what TalkBack speaks.
2. **A general Tanglish spelling rule** (treat th/dh, ai/ei, doubled vowels alike) plus a reviewed phrase list; plan approved in principle, not built; and whether "chill / chilling / hangout" maps to a tag.
3. **Screen-off shake** (it triggered roughly one round in three on the A23): more work (batch-arrival logging, a wake-up path) or leave it best-effort as the SOS options text already says.
4. **A pocketed or covered phone:** a proximity-sensor rule against an isolated violent jolt opening a countdown (a design change: a person may want to shake a pocket).
5. **The walk is restarted only when the walk screen is opened** (E-track open question): keep, or restart the service when IRL opens on another screen with a walk running.
6. The 17F signal-card button rows that break words at 2.0x text (still open from before the sprint).

**Sprint verdict: closed PARTIAL.** Tracks A, B, F, H: PASS (with the defects fixed as listed); C, E: PARTIAL as stated; D: PARTIAL (D1a PASS, D3 PARTIAL, D1b and D2 deferred). Defects found and fixed during the sprint: F-B1, O-B2, F-C1, F-C2 (partly: focus and Cancel fixed, speech not), F-C3, F-C4, F-C5, F-E1 (disclosure), F-H1, F-H1b, F-H1c. Nothing is committed or pushed.
