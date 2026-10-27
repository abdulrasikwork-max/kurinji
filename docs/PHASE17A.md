# Phase 17A — Stabilise (2026-10-27)

**Implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37).** Physical phone: **not
verified** (none connected). Plan and findings: `PHASE17_PLAN.md` (R1–R4). No migration (next still **V26**), no new
dependency.

## What changed

| # | Problem (root cause) | Fix | Key code |
|---|---|---|---|
| R2 | "Confirm your age to use this" on every social screen, posting refused: accounts created before V20 have no `age_confirmed_at`; each screen showed the raw 409 and only You → Edit profile could fix it; the phone read a missing `ageConfirmed` as "confirmed" | Server: the 409 carries code `AGE_NOT_CONFIRMED`. Phone: ONE `AgeGate` (fed by `ApiCallHandler`, a check at each sign-in, the age screen); the age screen opens by itself **once per sign-in/app start**, only from a tab or reading screen (never SOS); after "back" a calm banner "Confirm your age once…" stays until confirmed; confirming closes the screen and Discover reloads; fail-closed read | `AgeNotConfirmedException`, `core/network/AgeGate`, `MainActivity.openAgeScreenIfDue`, `ProfileSetupFragment.ARG_AGE_ONLY`, `view_session_banners.ageBanner` |
| new | **"Cannot post" found on the emulator:** the server refused the first post with "Make your profile first", shown at the TOP of the long composer while the person was at the Share button, so nothing seemed to happen | Server: code `PROFILE_NEEDED` (posts, perspectives, connections). Composer: the error sits right above Share, with a "Make your profile" button; the draft is kept while the profile is made | `ProfileNeededException`, `ApiResult.Failure.code`, `fragment_compose.xml` |
| — | A photo that failed to upload was silently left out of the post | Share is blocked while a photo failed; tapping it tries again (or Remove) | `ComposeViewModel.onRetryPhoto`, `ComposeUiState.photosFailed` |
| R3 | SOS screen said "Location is temporarily unavailable" during a normal GPS warm-up: every Play-services "no position right now" became UNAVAILABLE, even after a good fix | A watchdog: UNAVAILABLE only after 60 s without any position; location switched back on → SEARCHING; the last position stays shown with its time; new wording says what helps | `EmergencyLocationSharing.reportUnavailableAfterGrace`, `NO_FIX_GRACE_MILLIS` |
| R1 | A phone build pointing at `127.0.0.1` works only with USB + `adb reverse`; a LAN address was blocked by the static cleartext list | The debug cleartext allow-list is generated from `api.base.url`'s host (debug only; release still HTTPS-only) | `app/build.gradle.kts` (`devNetworkResDir`); static `src/debug/res/xml/network_security_config.xml` removed |

Safety features never read the age state (`SocialAccess` is social-only; checked live: adding a Safety Circle contact is
not refused).

## Results

- **Backend:** 756 tests, 0 failures, 2 skipped (two tests gained the `code` assertion).
- **Android:** 719 tests, 0 failures (Phase 16: 712; +4 `AgeGateTest`, +2 SOS location, +1 composer).
- **Lint:** 0 errors / 99 warnings (unchanged; none in new code).
- **Python:** not re-run (moderation service unchanged).
- **Live API on MySQL 6/6 PASS:** register; login; `/me ageConfirmed=false`; profile read 409 + `AGE_NOT_CONFIRMED`; post
  409 + code; Safety Circle not refused. (First run 3 FAIL: my script read `token` instead of `accessToken` — script
  fault, disclosed.) After the app's age screen: `age_confirmed_at` set in MySQL — PASS.
- **Emulator (API 37) PASS:** sign-in → age screen opens by itself → back → banner, not reopened across tabs → app
  restart asks once → banner → confirm → back to Discover, reloaded, no refusal, no banner → a confirmed account is not
  asked → composer: "Make your profile first" next to Share + "Make your profile" → profile saved → back → Share →
  post page opens; row in MySQL (EVERYONE / NONE / NONE); "Your posts" on You.
- **Found on the emulator and fixed:** Discover kept the old refusal after confirming (now reloads); the composer's
  refusal off-screen (above).
- **NOT VERIFIED:** physical phone (incl. the LAN-address build and GPS warm-up on a real device); TalkBack by ear;
  SOS location watchdog only by unit tests (no real SOS was started).
- **State left:** test account deleted (cascade), app data cleared, `adb reverse` removed, emulator and backend stopped.
