# Phase N1: strip the retired features (plan approved by the owner on 2026-11-15)

**Status: approved, in progress. The "as built" section at the end records each unit with its real results.**
Direction and reasons: `IRL_DIRECTION.md`. History of everything removed here: git tag `pre-n1` and the phase docs.

## Scope rule
N1 removes only what leaves no hole. The feed, composer, posts, places and comments stay until N4 and N5 replace
them, so the app runs after every unit. No new feature is built in N1.

## Findings that shaped the plan (inspection of 2026-11-15)
- Backend content moderation (`ReportService`, `ReportPolicy`, `ContentModerationService`) uses
  `community.service.Independence`; places use `community.service.GeoCell`. Both move to a shared package first.
- The Android `feature/moderation` mixes Nearby-signal cases (retired) and content cases (kept): it is split.
- `AccountEraserCoverageTest` compares the eraser with every table that references users: the retired tables are
  dropped in the same phase (V32) so the test stays honest.
- Route and stop checks (4C) live inside `SafeWalkSession`: they are NOT touched in N1 (moved to N6).
- `RECORD_AUDIO` leaves with the vault and comes back in N4 for voice moments.

## Units (one local commit each, on branch `irl-next`; nothing is pushed by the session)
| Unit | Removes | Main touch points |
|---|---|---|
| A1 Android: Nearby and intelligence | `feature/community`, `feature/intelligence`, "Check the way" in Safe Walk set-up, the signal half of `feature/moderation`, their Safety rows and nav destinations | `SafeWalkFragment`, `SafetyFragment`, `AppContainer`, `nav_graph.xml` |
| A2 Android: vault and SOS audio | `feature/evidence`, the evidence card on the SOS and detail screens, the audio question in SOS options, the upload job service; a one-time notice deletes old vault files from the phone | `EmergencyFragment`, `EmergencyDetailFragment`, manifest (`RECORD_AUDIO`, microphone service type, `USE_BIOMETRIC`) |
| A3 Android: social add-ons | Explore tab and AI understanding, the visit and rating sheet, presence, disclosure, Save and butterfly, today's state. Bar: Home, Share, You | `feature/social`, `YouFragment`, `NavigationRules`, bottom menu |
| B1 Backend: Nearby side | Move `Independence` and `GeoCell` to a shared package; delete `intelligence`, `evaluation`, `community`, the signal half of `moderation`, the Nearby purge job | `ReportService`, `ReportPolicy`, `ModerationService`, `AssessmentService`, `AdminController` |
| B2 Backend: evidence | `evidence` package and its part of the emergency context | `EmergencyContextService`, `AccountEraser`, `AccountDeletionJob` |
| B3 Backend: social add-ons | `explore`, Explore search and tags, visits and ratings, marks, today's state, the disclosure flag; Python `understand.py` and its route | `PostService`, `DiscoverService`, `ModelPlacesTest` |
| B4 Migration V32 | Drops the retired tables and columns | Development MySQL only: backup, dry run, apply |
| C Close | `API.md`, `.claude/rules/`, status, handoff; full test runs; emulator walk | |

## Tests
- Deleted with their features: the intelligence, evaluation and coordination isolation tests, the Explore, rating and
  18A-2 tests, the Nearby text tests.
- Updated on purpose: `AccountEraserCoverageTest`, `ModelPlacesTest`, `SocialIsolationTest`, `NavigationRulesTest`,
  `IrlRedesignTest`, `HonestyTextsTest`, `Phase17FGuardsTest`.
- Both suites, `assembleDebug` and lint run after each unit; the real numbers are recorded below.

## Manual check at the end (emulator, development backend)
Sign in; Home, post a text, You; start and cancel an SOS countdown; start and end a real SOS; start and end a Safe
Walk; the SOS tile and shortcut still open.

## Not in N1
Route and stop checks and automatic SMS (N6); passwords (N2); posts, places, feed, comments (N4, N5); Firebase (N7);
any deploy to the pilot (the laptop setup and the live jar stay exactly as they are).

## Risks
- Safety regressions: A2 edits the SOS screen and the tracking service's declared type; the manual check exercises a
  real SOS and a walk.
- The moderation split (B1): the content-report tests stay and must pass unchanged.
- V32 is irreversible where applied: development database only, after a backup. The pilot is wiped later, as its own
  approved step.
- Old vault files on a phone are deleted by the one-time notice (owner's decision; only test phones hold any).

## As built
(filled in per unit)

### A1 Android: Nearby and intelligence (2026-11-15)
- Removed: `feature/community` (15 files), `feature/intelligence` (9), the signal half of `feature/moderation` (case screen,
  case / appeal adapters, policy, texts, queue view model), 8 layouts, the Nearby row of the Safety hub, "Check the way"
  on the Safe Walk set-up screen and in the "Going there?" sheet, their nav destinations and `AppContainer` wiring.
- Kept and reduced: `ModerationRepository` / `ModerationApi` = only `access()`; `ModerationFragment` shows the content
  queue only and now says "not allowed" or the failure from `ContentQueueUiState.answered`.
- Tests: the Nearby, intelligence and signal-moderation test classes deleted; `Phase17FGuardsTest` lost its three
  Nearby-only cases; `GoingThereTest` and `HonestyTextsTest` updated; the two permission-rule cases of
  `Phase17HPermissionDenialTest` live on in the new `RuntimePermissionAskTest`.
- Result: **971 run, 0 failures, 0 errors, 0 skipped** (1086 before); `assembleDebug` OK; lint **0 errors, 365 warnings**
  (98 before: the rest are resources left without a user, removed in unit C). Not seen on a screen yet.

### A2 Android: Evidence Vault and SOS audio (2026-11-15)
- Removed: `feature/evidence` (25 files), `core/crypto/EvidenceCipher` and `RecoveryKeyCrypto`, `EscrowKey` (from
  `KeyWrapping.kt`), `core/ui/ScreenLockGate`, 4 layouts, the FileProvider and its paths file, `EvidenceUploadJobService`,
  the two passphrase word lists and their notice file in assets, the build setting `evidence.vault.key.id`.
- Screens: the evidence card on the SOS screen, the evidence line and "Open Evidence Vault" on the emergency detail
  screen, the vault rows of the Safety hub and Settings, the audio switch in SOS options and the one-time audio question
  on the Safety hub, the vault destination in the nav graph. `EmergencyContext` no longer carries evidence counts (a
  server that still sends them is fine: the field is ignored).
- Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `USE_BIOMETRIC` and the microphone feature are gone; the
  one tracking service has the type `location` only (`ForegroundTypes`).
- Kept: `LocalKeyWrapper` / `AndroidKeystoreKeyWrapper` (the session and the private places file use them; the Keystore
  alias keeps its old name so existing files still open); `AccountDataWiper` (now only forgets the deleted account's SOS
  options and clears the marker); `SosOptionsStore` (shake only; `forget` still removes the old audio keys).
- New: `core/retired/VaultRetirement` deletes `files/evidence`, `files/evidence-capture` and `cache/evidence-view` once
  at start; `MainActivity` shows a one-time notice, only when the vault held an item or an unsealed capture (the old
  build created the empty folders on every phone).
- Texts: the visible strings that promised recording or the vault were reworded (SOS limits notice, About, privacy
  policy, sign-out, account deletion, the Login notice, SOS options). The now unused vault strings stay until unit C.
  Known left-over for unit C: About and the privacy policy still mention Nearby and "Check the way" (from A1).
- Tests: the evidence, cipher, recovery and `AutoAudioPolicy` test classes deleted; `FakeEvidenceParts` reduced to
  `FakeKeyWrapper`; `Phase17FGuardsTest` (permission and service lists; a new case: nothing asks about, offers or can
  record audio), `GoingThereTest`, `HonestyTextsTest`, `Phase15IntegrationFixesTest`, `AccountTest` updated; new
  `VaultRetirementTest` (5).
- Result: **872 run, 0 failures, 0 errors, 0 skipped** (971 before); `assembleDebug` OK; lint **0 errors, 512 warnings**
  (365 before: more resources without a user, removed in unit C). Not seen on a screen; the SOS screen and the tracking
  service's type changed, so the real SOS and Safe Walk of the manual check at the end matter.
