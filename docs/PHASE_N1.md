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
