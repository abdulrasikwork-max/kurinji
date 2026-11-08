# Plan: targetSdk 36 for Google Play (DRAFT, awaiting the user's approval)

Written 2026-11-08. **Nothing in this plan has been started**: no SDK downloaded, no Gradle file edited. Phase name/number:
the user's decision (17K? a new Phase 18?). Reason for the work: Play requires new apps and updates to target API 36 since
2026-08-31; the app targets 35 (`docs/PHASE17J.md` §5, J-7).

Sources: [Play target SDK requirement](https://developer.android.com/google/play/requirements/target-sdk) ·
[Android 16 behavior changes, target 36 only](https://developer.android.com/about/versions/16/behavior-changes-16) ·
[Android 16 behavior changes, all apps](https://developer.android.com/about/versions/16/behavior-changes-all) ·
[AGP release table](https://developer.android.com/build/releases/about-agp).

## 1. Facts found (inspection of the repo, 2026-11-08)

| Item | Today | Needed |
|---|---|---|
| AGP | 8.7.3 | **≥ 8.9.1 for API 36**; newest 8.x is 8.13.2 (API 37 would need AGP 9.1.1+) |
| compileSdk / targetSdk | 35 / 35 | 36 / 36 |
| Gradle | 9.7.1 wrapper | AGP 8.13 documents **minimum Gradle 8.13**; Gradle 9.x with AGP 8.x is **not documented either way** (today's AGP 8.7.3 on 9.7.1 already works unofficially) |
| Kotlin / JDK | 2.0.21 / 17 | no requirement found on the pages read; unchanged unless the build refuses |
| SDK platforms installed | android-35, android-37.0 | **`platforms;android-36` is missing** (needs a download) |
| Emulator images | API 26, 29, 33, 37.0 | the API 37 image shows target-36 behaviour (it applies on Android 16 and later) |
| Galaxy A23 | not connected now | its Android version is unknown: it shows regressions only unless it runs 16+ |
| Code exposure | see §3 | no `onBackPressed`/`KEYCODE_BACK` override, no orientation/resizability limit, no `elegantTextHeight`, no `windowOptOutEdgeToEdgeEnforcement` |
| Tests pinning build files | `KurinjiTextsTest` and `Phase17JReleaseGuardsTest` read `build.gradle.kts`; `ModelPlacesTest` scans `libs.versions.toml` | re-run them; the AGP bump touches only a plugin version |

## 2. Two separately testable steps (two commits, so either can be rolled back alone)

### Step A: toolchain only, targetSdk stays 35
Files: `gradle/libs.versions.toml` (agp), `app/build.gradle.kts` (compileSdk), maybe `gradle/wrapper/gradle-wrapper.properties`.
1. Install `platforms;android-36` (**needs the user's OK**).
2. AGP 8.7.3 → **8.13.2**, compileSdk 36. Keep Gradle 9.7.1, Kotlin 2.0.21, JDK 17. **If the build refuses this Gradle or Kotlin, stop and ask** (the fallback would be a Gradle 8.14.x wrapper; nothing is bumped silently).
3. Verify: forced full `testDebugUnitTest`; `lintDebug` (a newer lint and API 36 deprecations may add warnings: triage them, do not baseline them); `assembleRelease` + `assembleReleaseCheck`; the guard tests.
4. **Repeat the releaseCheck walk on an emulator**: the R8 version changes with AGP, and the first walk found two release-only bugs. Add the screens never walked under R8: sign-up + phone code, a post with a photo, SOS countdown + **Cancel**, Safe Walk start/stop, audio recording, Nearby, Explore, a duplicate Safety Circle contact (server error text).

### Step B: targetSdk 36 only
File: `app/build.gradle.kts` (`targetSdk = 36`). Review, ranked by relevance to this app:
1. **Predictive back** (target 36: system back animations on by default, `onBackPressed` and `KEYCODE_BACK` no longer called). The code only uses `OnBackPressedCallback` (Onboarding, Discover panel, ProfileSetup), which is the supported API. Test back and the back gesture on every safety screen and sheet: SOS countdown, an active SOS, the FLAG_SECURE password prompt, the vault, the report / "I went" / city / daily-state sheets, the composer with a draft. **Do not** add `enableOnBackInvokedCallback="true"` (it would also change Android 13-15 devices). `="false"` stays only as a documented, temporary fallback if something breaks.
2. **Tamil text**: `elegantTextHeight` is ignored and the Tamil UI fonts are discontinued, so line heights change. Check Tamil/Tanglish posts, chips, buttons and rows at 1.0x and 2.0x for clipping.
3. **Foreground service, location, microphone, notifications, SMS**: the two Android 16 pages **do not cover them**. Listed as "verify on the API 37 emulator" (SOS start, Safe Walk, audio in an SOS, notification permission flow), not as "no change".
4. **Edge-to-edge**: the opt-out is unused and enforcement already applied at target 35 on Android 15+; confirm only.
5. **Large screens** (orientation/resizability limits ignored at sw ≥ 600dp): none declared, so no change expected; one tablet-size emulator check is optional. This opt-out disappears at target 37.
6. **Not applicable**: health permissions, Bluetooth, MediaStore version, Safer Intents (opt-in), photo-picker preselection (the app uses the picker, no action), 16 KB (already done, `PHASE17J.md`), local-network permission (not enforced yet; matters only to LAN-IP debug builds later).

### Android 16 changes that hit ALL apps regardless of target (already live today; NOT caused by this upgrade, out of scope)
- **JobScheduler quota**: `EvidenceUploadJobService` is affected (also when it runs next to the SOS foreground service). Test it on the API 37 emulator with the `OVERRIDE_QUOTA_ENFORCEMENT_*` compat flags. Moving evidence uploads to user-initiated jobs needs a new permission: the user's decision.
- **`announceForAccessibility`** is deprecated (4 call sites: You, Discover, Post x2; none on the SOS screen). Follow-up with the TalkBack-by-ear work.
- **Intent redirection hardening**: all outgoing intents are built locally with no nested intent: no action.

## 3. Test matrix: what each device can and cannot show

| Device | Shows |
|---|---|
| API 37 emulator (`Medium_Phone_API_37.0`) | the only target-36 behaviour (predictive back, large-screen rules, JobScheduler quota) |
| API 36 emulator image (optional download) | closest to Play's floor |
| Galaxy A23 | regressions only unless it runs Android 16+ (check when connected) |
| API 26 / 29 / 33 emulators | regression only (layout, back, permissions) |
| Backend / Python | unaffected; not re-run unless Step A touches them (it does not) |

## 4. Definition of done
Step A: tests + lint + both release builds green, releaseCheck walk repeated and recorded. Step B: the §2 B review done and recorded, tests/lint/builds green, `aapt2 dump badging` shows `targetSdkVersion:'36'`. Docs: `PHASE17J.md` §5 and J-7, `STATUS.md`, `HANDOFF.md`, `DECISIONS.md`, `CLAUDE.md` (stack line and 17J row). **Not verified until a device says so:** anything only a real Android 16 phone shows, TalkBack by ear, the real Play upload.

## 5. Risks
- AGP 8.13 + Gradle 9.7.1 may be refused (see Step A 2). - A newer R8 can break reflection again (hence the repeated walk). - Newer lint may add many warnings. - Predictive back may expose a screen where back used to be swallowed (SOS screen). - Tamil line heights change. - AGP 9.x is a separate, larger migration that API 37 will need before the next yearly target bump; it is not needed for 36.

## 6. Decisions needed from the user
1. Approve this plan (CLAUDE.md §3).
2. Allow downloading `platforms;android-36`, and optionally an API 36 emulator image (about 1-2 GB).
3. AGP **8.13.2** (recommended) or 9.x now. If Gradle 9.7.1 is refused: allow a Gradle 8.14.x wrapper.
4. `announceForAccessibility`: migrate now or leave for the TalkBack work (recommended: leave).
5. The phase name/number.

## 7. Status log

**2026-11-08, Phase 17K approved by the user** (AGP 8.13.2, first try Gradle 9.7.1, stop and ask if incompatible; API 36 download allowed; `announceForAccessibility` left for the TalkBack work; Step A then Step B; no commit, no push).

- Done: baselines saved (lint, R8 mapping, badging); `platforms/android-36` installed with `sdkmanager` (build-tools 36.0.0 was already present; no emulator image downloaded).
- **Step A BLOCKED, stopped as instructed.** AGP 8.13.2 + compileSdk 36 on Gradle 9.7.1 fails at configuration: *"Plugin 'com.android.internal.application' relies on 'org.gradle.api.problems.internal.InternalProblems', a Gradle internal API that was removed in Gradle 9.6.0. Update the plugin ... or use Gradle 9.5."* The attempt was saved as a patch (scratchpad `step-a-attempt-agp-8.13.2.patch`), reverted, and the old build was confirmed working (`Phase17JReleaseGuardsTest` 6/6, BUILD SUCCESSFUL). The tree is exactly the committed `c12edd0` plus this plan file. **Nothing was changed in Gradle.**
- Experiment (configuration only, each reverted): on Gradle 9.7.1, AGP **8.9.1 and 8.10.1 configure**, AGP **8.11.2 and 8.12.3 fail** with the same error. Full builds with 8.9.1 / 8.10.1 were not run.
- Options awaiting the user's decision: see the report. **Step B has not been started** (it depends on Step A).

**2026-11-08 (later), the user chose Option 1: Gradle 9.5.1 + AGP 8.13.2. STOPPED: Gradle 9.5.1 could not be downloaded, so Step A was NOT run.**
- Only `distributionUrl` was changed (`gradle-9.5.1-bin.zip`; no checksum line exists in the file). The wrapper then failed twice with *"Downloading from https://services.gradle.org/distributions/gradle-9.5.1-bin.zip failed: timeout (10000ms)"* (the wrapper's 10 s `networkTimeout`), and, with `networkTimeout` raised temporarily to 600000, three times with *"Server returned HTTP response code: 504 for URL: https://github.com/gradle/gradle-distributions/releases/download/v9.5.1/gradle-9.5.1-bin.zip"*.
- This is a **download / host problem, not a compatibility result**: `curl` gets `504 Gateway Time-out` for the same asset (and for 9.5.0 and, now, the 9.7.1 asset that downloaded fine earlier); `downloads.gradle.org` also 504; retried every 30 s for 4 minutes; while github.com, services.gradle.org and Maven Central answer 200. So it is GitHub's release-asset download path failing from this machine.
- AGP 8.13.2 was therefore never configured against 9.5.1: whether 9.5.1 works is still unknown. No other Gradle version was tried (as instructed).
- Restored: wrapper back to 9.7.1 (`networkTimeout=10000`), the empty failed-download files in `~/.gradle/wrapper/dists/gradle-9.5.1-bin` removed, the old build re-confirmed (BUILD SUCCESSFUL; `Phase17JReleaseGuardsTest` 6/6, `SealedAccessTokenTest` 15/15). The tree is exactly `c12edd0` plus this plan file. Patch of the attempt: scratchpad `stepA-wrapper-9.5.1-attempt.patch`.
- Side finding: Git Bash `sed -i` rewrites **CRLF files as LF** (`gradle-wrapper.properties` is CRLF); use the Edit tool for such files. The 17J commit was checked: no file was affected.

**2026-11-08 (later still), the user chose Option 2: Gradle 8.14.6 + AGP 8.13.2. STOPPED BEFORE ANY EDIT: the 8.14.6 download also fails.**
- Advisor-recommended probe first, nothing edited: `https://services.gradle.org/distributions/gradle-8.14.6-bin.zip` answers `307` to `https://github.com/gradle/gradle-distributions/releases/download/v8.14.6/gradle-8.14.6-bin.zip`, which answers **`504 Gateway Time-out`**. Five probes over about 90 seconds all gave 504; a control asset (the 9.7.1 zip, which downloaded fine earlier in this project) gave 504 too, while github.com, services.gradle.org and Maven Central answer 200. Same failure as for 9.5.1/9.5.0: GitHub's release-asset path is down from this machine.
- The published checksum was fetched for later verification: `gradle-8.14.6-bin.zip.sha256` = `7988ed071b2a07900e2ec715fca15c6ab72bce6db433af301fa7fa7e9407bd24`.
- No local Gradle 8.14.x exists (cache holds 8.9 and 9.7.1 only). Wrapper, `libs.versions.toml` and `build.gradle.kts` are untouched; the tree is `c12edd0` plus this plan file. Step A has still never been run; Step B not started.
- Notes for when the download works: 8.14.x is the last Gradle 8 line and AGP 9 needs Gradle 9.1+, so this choice pins the project to AGP 8.x until the AGP 9 migration (which API 37 will need anyway); Android Studio users need Narwhal 3 Feature Drop (2025.1.3) or newer for AGP 8.13. Use the Edit tool for the CRLF wrapper file; raise `networkTimeout` only for the download and restore 10000.

**2026-11-08 (16:17 onward), Step A PARTIALLY run on Gradle 8.14.6 + AGP 8.13.2 and PAUSED (system low on memory).**
- The 8.14.6 download worked this time: the official URL probed 200, the zip downloaded with curl (139,420,601 bytes) and its **SHA-256 matched** the published value; the wrapper's own download first failed with `java.net.ConnectException: Connection timed out` (the JVM could not connect while curl could) and succeeded with `-Djava.net.preferIPv4Stack=true` in `JAVA_TOOL_OPTIONS` (an environment option, not in the repo); the unpacked distribution's jars are byte-identical to the checksum-verified zip. `gradle --version` reports 8.14.6. Wrapper `networkTimeout` was raised only for the download and restored to 10000.
- Working-tree edits now (3 lines, patch in the scratchpad `stepA-wip-8.14.6-agp-8.13.2.patch`): `distributionUrl` -> `gradle-8.14.6-bin.zip`, `agp = "8.13.2"`, `compileSdk = 36` (targetSdk still 35).
- Results so far: configure `:app:tasks --all` **BUILD SUCCESSFUL, no warnings**; forced `testDebugUnitTest` **BUILD SUCCESSFUL, 1029 tests, 0 failures, 0 errors** (2 min 19 s).
- **NOT done:** lint, `assembleRelease` + `assembleReleaseCheck`, the Gradle guards in failure mode, the static R8/dex/16 KB/badging checks, the emulator walk. A first combined forced run (tests + lint + both release builds) stalled for ~14 minutes in `compileReleaseKotlin` / `compileReleaseCheckKotlin` (the log warned "multiple Kotlin daemon sessions"; the Kotlin daemon was using ~9 cores at ~2.4 GB and made no progress, looking like GC thrash at the 2 GB heap limit) and was stopped by me; a separate lint run was then stopped **by the harness because the system was critically low on memory**. The cause of the stall is not established (new AGP/R8, two release variants compiling in parallel, or the machine's memory). Idle Gradle/Kotlin daemons were stopped afterwards (free memory 2 GB -> 7.9 GB). Nothing was restarted on its own.

**2026-11-08 (evening), Step A COMPLETE on Gradle 8.14.6 + AGP 8.13.2 + compileSdk 36 (targetSdk still 35). Step B NOT started.**
- **Edits (3 lines; patch `step-a.patch` in the scratchpad):** `gradle/wrapper/gradle-wrapper.properties` `distributionUrl` -> `gradle-8.14.6-bin.zip`; `gradle/libs.versions.toml` `agp = "8.13.2"`; `app/build.gradle.kts` `compileSdk = 36`. Plus one machine-local, git-ignored edit: `android/local.properties` `sdk.dir=C\:/Users/...` (the colon escaped, same path), because AGP 8.13's newer lint flags the unescaped form as the one lint ERROR (`PropertyEscape`). Not in git.
- **Environment:** the 8.14.6 download works here only with `-Djava.net.preferIPv4Stack=true` in `JAVA_TOOL_OPTIONS` (the JVM could not connect over IPv6 while curl could); the zip's SHA-256 matched the published value. `platforms/android-36` was installed with `sdkmanager` (use `platforms/android-36`; the `;` form is split by the `.bat`). Git Bash `sed -i` turns CRLF files into LF: use the Edit tool for `gradle-wrapper.properties`.
- **Build stages, run one at a time** (a combined forced run stalled about 14 minutes with the two release variants compiling at once, and a lint run was stopped by the harness for low memory; neither recurred when the stages were run separately with the daemons stopped between heavy ones): configure OK, no warnings; forced `testDebugUnitTest` **1029 tests, 0 failures** (2 min 19 s); `lintDebug` **0 errors, 98 warnings** (baseline 105; by issue ID: `OldTargetApi` +1 expected, goes in Step B; `UseKtx` +12, `NewerVersionAvailable` +6, `ExifInterface` +2 are newer-lint notes on unchanged code; `GradleDependency` -26, `AndroidGradlePluginVersion` -2); `assembleRelease` OK in 3 min 7 s incl. `lintVitalRelease` and R8; `assembleReleaseCheck` OK in 2 min 5 s.
- **Guards in failure mode, still working under AGP 8.13.2:** `-Prelease.api.base.url=http://example.com` FAILS with the https message; fake `IRL_KEYSTORE_*` + the placeholder URL FAILS ("A SIGNED release must not use the placeholder server"); the defaults PASS (the `preReleaseBuild` hook is not a no-op).
- **Static R8 / packaging on the new release APK:** badging `compileSdkVersion='36'`, `targetSdkVersion:'35'`, versionName 1.0.0-rc1; the dex shows `ErrorBody` (mapped `core/network/b$b`) with fields `code` and `message`; the mapping keeps field names (`VaultItem.cloud -> cloud`) and `configuration.txt` carries the `TypeToken` and `-keep,allowobfuscation class com.safesphere.app.**` rules; `zipalign -c -P 16` passes and every ELF LOAD segment of `libdatastore_shared_counter.so` (4 ABIs) is 16384-aligned.
- **Emulator walk of the minified `releaseCheck` build on `Medium_Phone_API_37.0`** (Android 17 / API 37, gesture navigation = nav mode 2, font scale 1.0, 1080x2400 at 420 dpi: **Step B must use the same device and settings**), against the local backend, with no `AndroidRuntime` error at any step. Pre-granted by adb for repeatability (so the permission flows themselves were NOT walked): location, notifications, microphone. Mock location came from `cmd location providers add-test-provider gps` after allowing the shell's `MOCK_LOCATION` appop (the emulator console `geo fix` only gave one fix per boot); both were removed/restored afterwards.
  - PASS: opening screen, onboarding, sign-in; **a new account registered through the API (unverified), then signed in through the UI, routed to "Verify your number"** (the `PHONE_NOT_VERIFIED` code mapping), the code from the local dev file verified, profile setup saved; restart persistence (twice; the sealed session); Explore (typed phrase -> chips -> a search result from the server); Nearby (area picture, radar, one-step report, the report under "Your reports", withdrawn); private place "Work" saved and read back after a restart; the composer refused a public place near the private one; **a post with a photo** (picker, upload, post page with the image served back through the origin-checked client; audience Only me); a Safety Circle contact and the **duplicate number showing the server's own sentence**; SOS: the countdown screen, **Cancel** (scripted Start then Cancel in one command; no SOS started 6 s later), and a real SOS (location saved, audio recorded, 2 parts, **foreground service types location+microphone (0x88)**, ended with "I'm safe", service stopped); Safe Walk start, check-in, "I've arrived" (service type location, then stopped); the Evidence Vault lists both audio items "Verified in your vault".
  - Server rows (dev DB, scratch account `walk.17k@example.com`): emergency 107 RESOLVED MANUAL, 1 point, 2 AUDIO evidence items SEALED; emergency 108 RESOLVED MANUAL, 0 points (a second SOS that started because my reading of the countdown screenshot outlasted its 5 s; ended with "I'm safe"); safe journey 53 COMPLETED; 1 post (Only me, with a photo); 1 Safety Circle contact.
  - **NOT walked (still NOT VERIFIED under R8):** the sign-up form itself (date-of-birth picker), video posts, comments, saved posts, connections, moderation screens, password change, account deletion, shake SOS, smart (AI) understanding, Tamil/Tanglish content, photo/video evidence capture, the permission and notification-permission flows, TalkBack.
- **Side effects on the machine, restored/removed:** emulator app uninstalled, port reverse removed, pushed test photo deleted, mock-location appop back to deny, location_mode unchanged (3); the backend and the emulator stopped. **One mistake:** my cleanup also stopped a VS Code Java helper process (PID 26008; its command line matched `spring-boot`); it was not mine and VS Code normally restarts it. The scratch account `walk.17k@example.com` stays in the DEV database (account deletion is refused for 24 h after an emergency).
- Next: Step B (`targetSdk = 36` and the review in section 2) when the user says so.

**2026-11-08 (night), Step B COMPLETE: `targetSdk = 36` (one line, on top of Step A).** Advisor-reviewed first; method: measure on the SAME API 37 emulator (Android 17, gesture navigation, font 1.0, 1080x2400) with the SAME scripts, first on the target-35 build (baseline), then on the target-36 build.
- **Change:** `app/build.gradle.kts` `targetSdk = 35` -> `36` (the only difference between `step-a.patch` and `step-ab.patch`). No manifest attribute added (no `enableOnBackInvokedCallback`), no layout change, no code change.
- **Build stages (one at a time, daemons stopped between, emulator and backend stopped during):** forced `testDebugUnitTest` **1029 / 0**; `lintDebug` **0 errors, 98 warnings, the same issue IDs as Step A** (`OldTargetApi` is still reported: lint compares with the NEWEST platform installed here, android-37.0, and Play requires 36; informational, not suppressed); `assembleRelease` OK incl. `lintVitalRelease`; `assembleReleaseCheck` OK; badging of the release APK **`targetSdkVersion:'36'`**, compile 36, versionName 1.0.0-rc1; R8/packaging unchanged (`ErrorBody` mapped to `core/network/b$b`, field names kept, TypeToken and class-keep rules present, `zipalign -c -P 16` verified). Backend and Python untouched, not re-run.
- **Back matrix, target 35 baseline vs target 36 (three actions each: `input keyevent 4`, a full left-edge swipe, a cancelled edge gesture via `input motionevent`): ALL IDENTICAL.**
  - Onboarding page 2: key and swipe go to page 1; a cancelled gesture stays.
  - ProfileSetup (first time and re-entered): key goes to Discover / swipe to You; cancelled stays on the form. (A back at the Discover root leaves the app, as expected.)
  - Discover search panel, city sheet, Nearby report sheet: key and swipe close the panel/sheet and stay in the app; a cancelled gesture leaves it open (sheets checked by their dimming scrim as well as by text).
  - **SOS countdown (5 s, scripted in one command):** key and edge swipe CANCEL the countdown and leave the screen, no SOS starts (no foreground service afterwards); a cancelled gesture leaves the countdown running (screenshot shows it at 3) and the Cancel button then stops it. Same on both builds.
  - **Active SOS:** a cancelled gesture changes nothing (service type location stays); a full back leaves the screen with the "Emergency active. Tap to open it." banner and the service running; ended from the banner with "I'm safe".
  - **Active Safe Walk:** a cancelled gesture changes nothing; the "End this Safe Walk?" dialog closes on key and swipe and stays on a cancelled gesture; a full back leaves the screen with the "Safe Walk active. Tap to open it." banner and the service running; ended from the banner.
  - The platform logs `Setting back callback OnBackInvokedCallbackInfo ...` for the app on the target-36 run (the callback path is in use).
- **Tamil:** the same Tamil + Tanglish post (audience Only me, created through the API from a UTF-8 file) shown on the post page at font scale 1.0 and 2.0 on both builds: **identical line breaks and glyph rendering, no clipped line** (only the avatar colour differs). Screenshots: scratchpad `shots-A/t35-tamil-post-*.png` and `t36-tamil-post-*.png`. Font scale restored to 1.0.
- **Functional walk on target 36** (same device; no `AndroidRuntime` error anywhere): new account registered through the API, sign-in routed to "Verify your number", code verified, profile saved; restart persistence; city choice; Explore (typed phrase -> "Quiet Reading Cafe vgwj, matches 3 of the 3"); Nearby report submitted, listed, withdrawn; private place saved and read back after a restart; a photo post (picker, upload, post page, audience Only me); Safety Circle contact and the duplicate-number sentence "This phone number is already in your Safety Circle"; a real SOS with audio (foreground service types location+microphone 0x88, 1 part sealed and "Verified in your vault: 1"), ended with "I'm safe", service stopped; Evidence Vault lists the item. Safe Walk is covered by the matrix above.
- **NOT verified (only a real Android 16 phone, or not exercised):** anything specific to a physical Android 16 device (the API 37 emulator applies the target-36 rules because they apply on Android 16 and later, but it is a different system image); the JobScheduler quota effect on `EvidenceUploadJobService` (not target-gated; compat-flag test not run); large-screen/tablet layouts (no orientation or resizability limits are declared, no tablet emulator run); the sign-up form and its date-of-birth picker; video posts; comments, connections, moderation, account screens; shake SOS; smart (AI) understanding; permission flows (pre-granted by adb); TalkBack; `announceForAccessibility` (deprecated, left for the TalkBack work as agreed); a real Play upload.
- **Scratch data in the DEV database (not removed):** accounts `bk35@example.com` and `bk36@example.com` (each with one profile, one Tamil Only-me post, resolved SOS/walk rows; `bk36` also a photo post, a contact and one audio item), plus `walk.17k@example.com` and `releasecheck.17j@example.com` from earlier. Deleting an account is refused for 24 h after an emergency.
- **Machine:** emulator and backend stopped (the backend by its port listener only), app uninstalled, mock-location provider removed and the shell's MOCK_LOCATION appop back to deny, font scale 1.0, nav mode unchanged, pushed test photos deleted, Gradle daemons stopped.
- Patches in the scratchpad: `step-a.patch` (Step A), `step-ab.patch` (Step A + B). Next: the user's review and a local commit if wanted.
