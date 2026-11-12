---
paths:
  - "android/**"
---
# Android architecture (moved verbatim from CLAUDE.md §5 on 2026-11-03)

Loaded when working on files under `android/`. Safety features: `android-safety.md`; social features:
`android-social.md`. The short version stays in `CLAUDE.md` §5.

> **Read with care (checked against the code on 2026-11-03):** this text was written phase by phase. Since 13F
> there is no `feature/home` (no Home screen, no `HomeStatus`): "Home banner" now means the session banners in
> `MainActivity` (`SessionStatusViewModel`), "Profile" means the You tab. Since the IRL redesign (2026-11-11) the bar is Home · Explore · ✚ Share · You and **Saved** is a row of You
> (`SavedFragment`, nav id still `cocoonFragment`); Cocoon / Want / Been are gone. `feature/rise` was removed in 13F.
> When this text and the code disagree, the code wins: flag it to the user.

- **Stack:** Kotlin 2.0.21, AGP 8.13.2 on Gradle 8.14.6 (AGP 8.11+ needs Gradle <= 9.5; AGP 9 needs Gradle 9.1+), XML Views, Fragments, ViewBinding, single Activity (`MainActivity`), one Navigation
  graph, MVVM, repositories, Coroutines/Flow, DataStore, Retrofit 2.11 + OkHttp 4.12 + Gson, Material 3.
  minSdk 26, compile/target 36 (Play requirement, 17K; the target-36 behaviour changes were measured on the API 37 emulator, `docs/TARGETSDK36_PLAN.md`). **No Compose. No Hilt** unless explicitly approved.
- **Packages:** feature-first `feature/<name>/{domain,data,ui}`; shared code in `core/`, `navigation/`.
  Features: splash, onboarding, auth, safety (You → Safety hub + session status), community (Nearby heads-ups), profile
  (You), settings, safetycircle, emergency (+ `entry`: SOS shortcut/tile), safewalk, circlealert, evidence, moderation
  (Phase 7), intelligence (Phase 8), account (Phase 10A), social (Phase 13), going (Phase 13F bridge, navigation only).
  `feature/map` (10A), `feature/home` and `feature/rise` (13F, D9) were removed. No screen shows "Coming soon".
- **Navigation (13F, changed by the IRL redesign 2026-11-11, `docs/IRL_REDESIGN.md`):** Home (`discoverFragment`) · Explore (`exploreFragment`) · ✚ Share (action → composer) · You; start = Home; Saved (`cocoonFragment`) and "Settings and more" (`moreFragment`, `MoreFragment`) are reached from You; a tab re-tap goes to the top and refreshes (`TabReselect`). Bar on tabs + reading
  screens (post, place/category page, person profile); task/safety screens toolbar + back. Safety banners (SOS, walk,
  deletion, sign in to sync) on every tab and reading screen via `MainActivity` + `SessionStatusViewModel`, never on the
  SOS screen. Social and safety never import each other; `feature/going` imports neither (`GoingThereTest`).
- **DI:** manual `AppContainer` (composition root) via `Fragment.appContainer`; each ViewModel has `factory(container)`.
- **Two palettes (2026-11-12, IRL-C1):** `core/ui/SocialPalette.appliesTo(className)` (feature packages social, profile, settings, account, auth, onboarding) decides who wears `ThemeOverlay.IRL.Social` (`c_*` colours). `ViewBindingFragment` overrides `getContext()` and `onGetLayoutInflater` for those fragments, so `requireContext()` there is already themed: open sheets and dialogs with it, never with `requireActivity()`. `MainActivity.chromeContext` colours the shared page and toolbar per destination; the bottom bar has the overlay in `activity_main.xml`. Safety features are not in the list and must never be (`SocialPaletteTest`). Headlines use `@font/irl_serif` (Fraunces); `TextAppearance.SafeSphere.Hero.*` stay Mukta Malar.
- **Networking:** exactly **one** Retrofit instance; all APIs are created from it in `AppContainer`. `AuthInterceptor` adds the
  Bearer token only to the backend origin (scheme+host+port, 17J) and never to `/api/auth/*`. **No HTTP logging interceptor** (bodies contain passwords).
- **Errors:** Phase 3+ repositories use `core/network/ApiCallHandler` → `ApiResult<T>` (`Success` / `Failure(ApiError, message?)`).
  A 401 goes through `core/network/SessionEnder` (Phase 10A): the session is cleared only if the rejected token is still
  the saved one (`SessionStorage.clearIfToken`, atomic in DataStore; a late 401 for an old token never signs out a newer
  session), the old token's 401 is ignored while a password change swaps tokens (`swapping`), and the reason from
  `WWW-Authenticate` (`credentials_changed` / `account_deleted` / `account_unavailable`) is published (`ended`,
  `lastReason`); screens then call `showSessionExpiredDialog()` (words follow the reason) (→ Login). Only our own 409
  messages reach users; other errors use app strings (`ApiResult.Failure.toMessage()`). Auth keeps its own
  `RemoteAuthRepository` mapping (its `refreshProfile` 401 also uses `SessionEnder`).
- **Session:** `DataStoreSessionStorage` (token + id/name/email + a pending deletion's dates; never the password). Splash
  routes Onboarding / Login / Home. The 24 h access token is sealed with the Keystore like the refresh secret and NEVER written in plain text (17J, `docs/PHASE17J.md` §2 / J-8: sealing is tried twice, then the session is memory-only with a generation id on disk; an unopenable or lost token = same person, empty token, "sign in to sync", nothing deleted). `allowBackup=false`.
  **C1 (`docs/PHASE11.md` §8):** `AuthSession.refreshToken` (sealed with Keystore alias `safesphere_session_v1`) and
  `needsSignIn` ("sign in to sync"). **Expired ≠ revoked:** a 401 without a reason → `SessionRefresher` (single-flight,
  NonCancellable, conditional atomic save) → `ApiCallHandler` retries ONCE; offline = NO_CONNECTION; refused or
  `session_revoked` → `markNeedsSignIn` (NOTHING deleted: SOS, unsent endings, Circle copy, walk stay; SOS works; Home
  banner + closable dialog, never on the SOS screen); `credentials_changed`/`account_deleted`/`account_unavailable` →
  Phase 10A sign-out. Every session change that keeps the rest uses the atomic `SessionStorage.update`. Login to the
  same account sends `previousRefreshToken` (hand-over incl. its ACTIVE SOS); another account → `onAccountSwitch`
  clean-up first, never its secret. Logout ends the server session (best effort). `SessionRenewer` refreshes at start
  after idle time and when due (last 12 h AND second half of the token's life).
- **Account (Phase 10A, `feature/account`, `docs/PHASE10.md`):** `AccountFragment` (FLAG_SECURE): change password
  (`AccountPolicy` checks 8+ chars, ≤ 72 bytes, confirm, different; `RemoteAccountRepository` saves the NEW token inside
  `SessionEnder.swapping`), request / cancel deletion (password dialogs). `AccountPolicy.deletionNotice` drives the amber
  banner on Account and Home (opens Account). `PendingDeletionStore` (DataStore, account id + date only, survives
  sign-out). On a 401 `account_deleted`, `AppContainer` runs `AccountDataWiper` (= `EvidenceVault.forgetAccount` +
  `RecoveryKeyStore.remove`; a sign-out clears the rest anyway); after the date with no session, Login offers "Remove its
  data from this phone". `HonestyTextsTest` guards onboarding/settings honesty, identity lines, "no avoid", summary order.
  **10A.4:** `SessionRenewer` (app scope; every 10 min during an SOS/walk, hourly otherwise, at once when one starts)
  renews when < 12 h are left (`TokenRenewalPolicy`, reads `exp` only) via `RemoteAccountRepository.renewToken`, saved
  with `SessionStorage.replaceToken` (atomic, only over the same old token). SOS screen: a 403 while ending ⇒
  `EmergencyUiState.Active.passwordNeededFor` ⇒ FLAG_SECURE password prompt ⇒ `onEndWithPassword` (never re-asks by
  itself); `onScreenVisible` re-checks the SOS and, if it was ended elsewhere, stops location sharing and shows
  `emergency_ended_elsewhere_message` WITHOUT sending the Safety Circle a "resolved" SMS.
- **UI helpers:** `ViewBindingFragment`, `collectWhenStarted`, `Motion.revealInSequence`; components in `core/ui/components`
  (`StatusBadgeView` + `BadgeStatus`, `NoticeView`, `SectionHeaderView`, `EmptyStateView`, `SafetyStatusCardView`, `FeatureListAdapter`).
- **Location UX (Phase 17D, `docs/PHASE17D.md`):** one-shot readers use `oneFix` (a recent / Wi-Fi position, area level) or `preciseFix` (a short visible, cancellable search for <= 100 m: `Good` / `Rough(best)` / the exact problem; a rough position NEVER places anything; `LocationProvider.freshUpdates` drops cached positions by the platform boot clock). Streams: `LocationStreamPolicy` (searching / available / no signal after max(60 s, 3 x interval) / off, switch looked at every 5 s) in `SafeWalkState.locationStatus`; it explains and never changes a check-in phase, concern or level. One permission path: `LocationAsk` (+ `act`, `askForPrecise`, `preciseDeclined`) and pure `LocationNeeds` (the ONE action to offer); screens (except the SOS screen) must not register their own location launcher or read a stream (`Phase17DLocationTest` scans). Nearby: gate before the form, "Updated" time, answers on AREA signals need only an area position. Place picker: Use where I am / Search (names only) / Recent. Screens stop their location search in `onStop`.
- **Permission refusals (Phase 17H, found in the real-device sprint, `docs/REAL_DEVICE_SPRINT.md` §3):** a refusal that changes what a screen must say has to be part of the screen's STATE, never only a field of the fragment (a `StateFlow` does not emit an equal value, so the screen would not redraw): Nearby keeps `CommunityUiState.permissionBlocked`. For a non-location runtime permission, label AND action of the button come from the pure `core/ui/RuntimePermissionAsk.needsSettings(asked, shouldShowRationale)` (used by both buttons on SOS options). `Phase17HPermissionDenialTest`.
- **Rise is retired (13F, D9):** code, layouts, strings and tests removed; `core/retired/RiseRetirement` deletes
  `files/rise/` once and MainActivity shows a one-time notice. The description below is history.
- **Rise "Proof" (Phase 10B, `feature/rise`, `docs/PHASE11.md` §4):** phone-only; `RiseJournalHolder` (one encrypted
  file per account, `EncryptedFileRiseStore`: AES-GCM per save, key wrapped by the Keystore `LocalKeyWrapper`, AAD
  `SSRISE1|accountId`, `.bak`, Unreadable never silently empty), pure `GoalPolicy` / `ProofPolicy` (replay only for the
  same self-doubt, never "wasn't OK"; a safety worry never gets a replay), `RiseLock` (optional screen-lock gate,
  1 s grace across Rise screens), quiet mode, FLAG_SECURE on all Rise screens, tab "Rise". SAFE bridge = navigation
  only; "Tell someone" = SMS app without a number. No network/notification/service/alarm (`RiseTextsTest` scans language
  rules and isolation). `ScreenLockGate` lives in `core/ui`. Account deletion wipes Rise; sign-out keeps it.
- **Permissions today:** `INTERNET`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `ACCESS_NETWORK_STATE`, `SEND_SMS` (4D, asked only on opt-in;
  Play "physical safety" declaration needed for release), `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` (Phase 5, asked
  on the Record tap; the one service's types = location ∪ microphone), `USE_BIOMETRIC` (Phase 5 hardening, normal
  install-time permission for the vault's screen-lock prompt). Telephony/microphone/camera features not required.
  `dataExtractionRules` exclude all data.
- **Build gotcha (this Windows sandbox):** Gradle/Tomcat fail with "Unable to establish loopback connection"; run with
  `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Public` (backend: `-Dspring-boot.run.jvmArguments=...`).
- **Tests:** JUnit4 + kotlinx-coroutines-test, `MainDispatcherRule`, fakes in `src/test/.../testing/` (FakeAuthRepository,
  FakeSafetyCircleRepository, FakeEmergencyRepository, FakeAuthApi, FakeSessionStorage…). Countdown tested with virtual time.
- **Device and emulator gotchas (moved verbatim from CLAUDE.md §12 on 2026-11-12):**
  - **Real-phone testing over USB:** `android/local.properties` → `api.base.url=http://127.0.0.1:8080/`, rebuild, then
  `adb -s <serial> reverse tcp:8080 tcp:8080` (lost on every reconnect). Remove the line to go back to the emulator
  default `10.0.2.2`. Install for `--user 0` on Samsung (Secure Folder user blocks some adb commands). Phone clocks may
  be off (the A23 was 74.6 s behind): capture times are phone time, `received_at`/`sealed_at` server time — compare
  them only after measuring the offset.
  - **Emulator:** starting it with `-no-snapshot-save` can come back from an older snapshot with an older APK installed;
  check `dumpsys package com.safesphere.app | lastUpdateTime` and reinstall before a walk-through.
