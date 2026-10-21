# SafeSphere — Phase 1: Android foundation (with branded UI redesign)

SafeSphere is a privacy-focused women's safety platform (final-year B.Tech IT project).
**Phase 1 contains the Android app foundation only.** There is no backend, no emergency feature,
no location, microphone, camera, SMS or AI in this build, and the app requests **no permissions**.

## UI redesign
The app has a branded design system ("Calm when everything is safe. Powerful when something goes wrong."): see `docs/DESIGN_SYSTEM.md`.
It is a visual change only: ViewModels, repositories, validation, DataStore, navigation destinations and all Phase 1 behaviour are unchanged.

## What works in Phase 1
- Splash, then 3-page onboarding (Skip / Back / Next / Get started). Completion is saved with DataStore, so onboarding is shown once.
- Login, Register and Forgot Password forms with local validation only. Valid input continues in **preview mode** and the UI says so ("Preview mode — Backend authentication will be connected in Phase 2."). Nothing is verified, created, sent or stored.
- Home (Safety Command Center), Safety Center, Community Safety and Safety Profile screens. Their features are labelled Available / Preview / Coming soon; only Settings, About, Privacy and Terms pages, and Log out, actually work.
- Settings and About / Terms / Privacy Policy placeholder pages.
- Bottom navigation: Home, Safety, Community, Profile.

## Package structure
```
com.safesphere.app
├── SafeSphereApp, AppContainer, MainActivity
├── core/        validation, session (in-memory preview), shared UI (base fragment, menu list, helpers)
├── navigation/  which screens show the toolbar / bottom bar, tab switching
└── feature/
    ├── splash  onboarding  auth  home  profile  settings   (implemented)
    ├── safety  community                                    (placeholder screens)
    └── emergency  evidence  map                             (empty, README only)
        each feature: ui/ (+ data/ and domain/ where it already needs them)
```

## Key architecture decisions (viva notes)
- **MVVM**: Fragments only draw state and forward clicks; ViewModels hold UI state as `StateFlow` and contain the logic; repositories hide where data comes from.
- **Feature-first packages**: each future feature gets its own `data/domain/ui`, so Phase 2+ adds code without moving existing files, and features can later become Gradle modules.
- **Single Activity + Navigation Component**: one back stack, one place for the toolbar and bottom bar.
- **Manual `AppContainer`** instead of Hilt: few objects exist, so a dependency-injection library would add complexity without benefit. Hilt can replace it in Phase 2.
- **DataStore** for the onboarding flag: asynchronous, crash-safe replacement for SharedPreferences. Only one boolean is stored.
- **Auth is a UI preview**: `AuthRepository` is an interface with no implementation and no users. Validation logic lives in `FormValidator`, which is unit tested. The in-memory `PreviewSession` only lets Home and Profile show a name and email; it is never persisted.
- **Security**: no permissions, `allowBackup=false`, no secrets in code (`local.properties` is git-ignored), passwords are checked and then discarded. This is **not** production-grade security; real authentication and secure storage arrive with the backend.

## Dependencies (all official or Kotlin team)
Core KTX, AppCompat, Activity/Fragment KTX, Material Components, RecyclerView, ViewPager2, Navigation, Lifecycle (ViewModel, runtime), DataStore Preferences, SplashScreen, Kotlin Coroutines. Tests: JUnit, kotlinx-coroutines-test. Versions: `gradle/libs.versions.toml`.

## Run it
1. Install the current stable **Android Studio**. Use its bundled JDK 17 or newer.
2. **File > Open** and select the `SafeSphere` folder. Accept the Gradle sync; Android Studio downloads Gradle 8.9 and the Android SDK 35 platform if missing (SDK Manager).
3. Create an emulator (API 26 or newer) or enable USB debugging on a phone, then press **Run**.
4. Unit tests: `./gradlew testDebugUnitTest` (or right-click `app/src/test` > Run Tests).
5. If Android Studio suggests newer AGP / Kotlin versions, update `gradle/libs.versions.toml` only; use the *Upgrade Assistant* so the Gradle version stays compatible.

## Known limitations
- Not compiled by the author's tooling: only static checks and the validator tests were run outside Android Studio. Do a first build before relying on it.
- No real authentication, accounts, password reset or profile editing.
- Greeting, name and email exist only for the current app session (in memory).
- Light and dark themes exist; no tablet-specific layouts; English only.
- Terms and Privacy Policy are placeholders, not legal text.
- No instrumented (on-device UI) tests yet.
