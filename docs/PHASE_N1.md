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

### A3 Android: social add-ons (2026-11-15)
- Removed: Explore (tab, screen, view model, phone-side word understanding, the two bundled word files, the optional
  smart understanding and its stored account id), the "Good for" tags in the composer and on the post page, "Been
  here" with the rating sheet and the quiet presence check (`PlaceVisits`, `WentSheet`, `PresenceCell`), the place
  page's average, the disclosure switch and its label, the butterfly and Save on every card and on the post page
  (`PostMarks`, `ButterflyFlight`), the author's butterfly line, Saved (screen, view model, the row of You, the Save row
  of the "Going there?" sheet), today's state (You, the person profile, the sheet). 7 layouts.
- Bar: Home, Share, You (`NavigationRules.tabDestinations` = Home and You; Saved is no longer a reading screen).
- Kept: the post page's and the place page's button now always says "Share yours" and opens the composer with the
  place filled in; "Going there?" (tell someone, walk with check-ins); comments and their count; `MarksFit` (the comment
  mark, You's head, the opening); `ButterflyDrawable` is now unused code (unit C).
- Data layer: `DiscoverRepository` / `DiscoverApi` lost react, save, saved, went, rate; `SocialRepository` lost the
  daily state calls; `Post` lost `youReacted`, `saved`, `butterflies`, `disclosed`, `facets`; `PlacePage` lost `rating`
  and `yourVisit`. A server that still sends those fields is fine (ignored). `PostViewModel` and `PostListViewModel` no
  longer take the Discover repository; `PostViewModel` and `PostCollectionViewModel` no longer take a location provider.
- Texts: onboarding page two ("Share it, ask about it"), About (the sentences on "Been here" and paid visits removed),
  the place page button. Unused strings stay until unit C.
- Tests: `Phase17GExploreTest`, `Phase17G2VisitsTest`, `Phase17G3SmartUnderstandingTest`, `Phase17HWentLabelTest`,
  `Phase18A2TrustTest` and `FakeExploreRepository` deleted; the mark, Saved and daily state cases cut from `DiscoverTest`,
  `Phase17ETest`, `ExperienceCardTest`, `IrlRedesignTest`; `NavigationRulesTest`, `SocialTextsTest`, `HonestyTextsTest`,
  `Phase15IntegrationFixesTest` updated; `IrlRedesignTest` gained a guard that no social code names the removed calls.
- Result: **766 run, 0 failures, 0 errors, 0 skipped** (872 before); `assembleDebug` OK; lint **0 errors, 622 warnings**
  (512 before; unused resources, unit C). Not seen on a screen.

### B1 Backend: Nearby side (2026-11-15)
- Moved: `GeoCell` to `common/geo`, `Independence` to `common/independence` (with their tests); content reports and
  places use them from there.
- Removed: packages `community`, `intelligence`, `evaluation` (main and test), `ModerationCase` and its repository,
  `CoordinationCheck`, `config/CommunityProperties`, `CoordinationProperties`, `EvaluationProperties`,
  `IntelligenceProperties`, their `app.*` settings, the Nearby clean-up job, `GET /api/admin/evaluation`, every
  `/api/community/**`, `/api/intelligence/**`, `/api/moderation/cases/**` and `/api/moderation/appeals/**` endpoint.
- Kept and reduced: `ModerationService` (access, `requireModerator`, role changes, audit log, audit retention, now
  called from `ContentModerationService.maintain()`); `ModerationController` = `GET /api/moderation/access` only;
  `ModerationDtos.AuditEntry` carries `targetKind` instead of `category` (API change); `ModerationAction` lost
  `signalCategory`, `onCase`, `restriction`. The Java enums in `ModerationEnums` stay as they are; V32 deletes the old
  Phase 7 audit rows instead.
- `AssessmentService` no longer writes evaluation counters: the weekly AI counters that gates L1 to L3 were going to
  read are gone with `evaluation` (to be re-decided before any learned model acts; recorded in `DECISIONS.md` in unit C).
- `AccountEraser`: no Nearby statements or references; `AccountEraserCoverageTest` ignores tables that a later
  migration drops.
- V32 (file only, applied nowhere): the B1 part (seven tables, the Phase 7 audit rows, `moderation_actions.signal_category`).
- Tests: `ModerationApiIntegrationTest` rewritten (6 cases: who may moderate, access, role claims, the retired endpoints
  answer 404, role changes with the audit log's field list, a disabled moderator); the test helper `CommunityClient` is
  now `ApiTestClient` without the Nearby calls; the Nearby deletion case left `AccountLifecycleApiIntegrationTest`; the
  two counter assertions left `AssessmentIntegrationTest`; `ExploreFacetsParityTest` lost the comparison with the app's
  copy (the app's file was removed in A3).
- Result: full run **665 run, 0 failures, 1 error, 4 skipped** (928 before N1); the one error was that parity case
  reading the app file A3 deleted. After removing it the three changed classes were re-run (0 failures); the full
  suite was NOT run again in B1 (it is run again in B2). Main code compiled. No database touched.

### B2 Backend: evidence (2026-11-15)
- Removed: package `evidence` (controllers, services, entities, repositories, `tools/VaultKeyTool`, `SsevFormat`) with
  its tests and the test-only vault key file, `config/EvidenceProperties`, every `app.evidence.*` setting, every
  `/api/emergencies/{id}/evidence/**` and recovery endpoint.
- `EmergencyContextService` / `OwnerContext`: no `evidence` summary any more (API change; the N1 app already ignores
  the field). `AccountEraser`: no evidence statements, references or file step; `AccountDeletionJob`: no evidence
  folder sweep. `ProductionGuard` no longer checks `EVIDENCE_STORAGE_DIR` or warns about the vault key.
- V32 (file only): drops `evidence_custody_events`, `evidence_recovery_failures`, `evidence_recovery_envelopes`,
  `evidence_items`. The encrypted files are not in the database: the evidence folder is removed by hand where one exists.
- NOT changed on purpose: `deploy/` (env examples, `backup.sh`, `preflight.sh`, `restore-*.sh`, the backup unit) still
  names `EVIDENCE_STORAGE_DIR`. The pilot runs the old jar; those scripts change together with the pilot's own deploy.
- Tests: `AccountLifecycleApiIntegrationTest` (deletion case without evidence), `ProductionGuardTest`,
  `SocialIsolationTest` (package list). **Found and fixed:** `AccountEraserCoverageTest` read the migrations in file-name
  order, where `V32` sorts before `V9`, so a drop of a V9 table was seen before its creation; it now reads them in
  version order.
- Result: full run **621 run, 1 failure, 0 errors, 4 skipped**; the failure was that ordering fault in the test. After
  the fix the three changed classes were re-run (20 run, 0 failures); the full suite is run again in B3. No database
  touched.

### B3 Backend: social add-ons (2026-11-15)
- Removed: package `explore` (the optional AI "understanding": client, gate, service, controller, settings) and
  `ExploreController` / `ExploreService` / `ExplorePolicy` / `ExploreFacets` / `PostFacet*` with the tag file;
  `PlaceVisit*`, `RatingPolicy`, `PlaceRatings`; `PostMarkService`, `Butterfly*`, `ButterflyTally`, `SavedPost*`;
  `DailyState*`; `Post.disclosed`. Endpoints gone: `/api/explore/**`, `PUT|DELETE /api/places/{id}/went` and `/rating`,
  `PUT|DELETE /api/posts/{id}/reaction` and `/save`, `GET /api/saved`, `PUT|DELETE /api/profiles/me/daily-state`.
- Answers changed (API change; the N1 app already ignores these fields): `PostResponse` lost `youReacted`, `saved`,
  `butterflies`, `facets`, `disclosed`; post create / edit no longer read `facets` or `disclosed`; `PlacePage` lost
  `rating` and `yourVisit`; both profile answers lost `dailyState`.
- Kept and reduced: `SocialLimits` / `SocialProperties` (no mark, visit, rating or Explore limits),
  `SocialMediaCleanupJob` (photos only; still the one scheduled task of `social`), `ContentModerationService` place
  merge (posts only), `AccountEraser` (five references and their statements gone).
- Python `moderation-service`: `understand.py`, its route `/v1/understand`, `data/explore-vocabulary-v1.tsv`,
  `tests/test_understand.py`, `tools/smoke_understand.py` and the README section removed; `/v1/health` no longer
  carries `understanding`. **No file of the product names an AI provider or opens a provider connection any more.**
- V32 (file only): drops `butterflies`, `saved_posts`, `daily_states`, `post_facets`, `place_visits`, the CHECK
  `ck_posts_disclosed` and the column `posts.disclosed`; also deletes the log entries of decided Phase 7 appeals
  (`APPEAL_*` with no `target_kind`; a content appeal always has one).
- Tests: the Explore, understanding, rating, visit, reaction and 18A-2 test classes deleted; the daily state cases cut
  from `Phase17eSocialApiTest`; marks cut from `SocialFourUserTest`, `ContentModerationIntegrationTest`,
  `SocialAccountDeletionTest`, `DiscoverPoliciesTest`; `ModelPlacesTest` now pins two outbound clients (moderation
  service, SMS) and asserts that NO Python file opens a connection or names a provider; `SocialIsolationTest` unchanged
  and green.
- Result: backend, clean build: **496 run, 0 failures, 0 errors, 2 skipped** (the opt-in real-MySQL class). A first,
  incremental run showed 1 error (`PostPolicyTest`: a compiled inner class missing from `target`, a stale build, gone
  after `mvn clean`). Python service: **38 run, OK**. No database touched.
- Not changed on purpose: `deploy/env/*.example` still mention `EXPLORE_UNDERSTANDING_MODE` (pilot files, see B2).

### B4 Migration V32 (2026-11-15, DEVELOPMENT MySQL 8.0.46 only; the pilot is NOT migrated)
- **Backup first:** `C:\Users\ABDUL RAFIQ\Desktop\SafeSphere-Phase3\db-backups\safesphere-dev-before-v32-20261115.sql`
  (`mysqldump --single-transaction`, 46 tables, 440 088 bytes; outside the repository; plain, same disk).
- **Pre-check (read-only):** all 16 tables to drop were present; the only foreign keys pointing at them come from
  tables dropped earlier in the file (cases / flags / contributions before `place_signals`, the custody ledger before
  `evidence_items`); no index and no other CHECK names `signal_category` or `disclosed`.
- **Dry run on scratch copies** (`zz_v32_*`, dropped afterwards, table count back to 46): the two audit-log deletes
  on a copy of `moderation_actions` with a copy of `moderation_appeals` cascading from it: 37 rows became 7 (3 role
  grants, 3 role removals, 1 `PERSON_REMOVE_PHOTO`), 2 appeals became 0; `DROP COLUMN signal_category` accepted; on a
  copy of `posts` with its 16 rows: `DROP CHECK` then `DROP COLUMN disclosed` accepted, 16 rows kept, 27 columns
  became 26. **Found:** MySQL also accepts dropping the column without dropping its one-column CHECK first (it removes
  the CHECK itself); the file keeps the explicit `DROP CHECK` and its comment was corrected before the apply.
- **Applied** by starting the new jar once: Flyway "Successfully validated 32 migrations", "Successfully applied 1
  migration to schema `safesphere`, now at version v32", then "Started SafeSphereBackendApplication". Afterwards: 30
  tables, `moderation_actions` 7 rows, `moderation_appeals` 0, `posts` 16, no `disclosed` / `signal_category` column.
- **Entities against the migrated schema:** every `@Column` / `@JoinColumn` name of the 27 entity tables (193
  columns) exists in MySQL. (`ddl-auto=validate` cannot be used for this: it stops at `circle_alerts.client_ref`,
  CHAR in the schema and VARCHAR for Hibernate, an old and harmless difference.)
- **Real-MySQL test:** `MySqlTimestampStorageTest` with `SAFESPHERE_MYSQL_TESTS=true`: 2 run, 0 failures (the
  credentials must be exported from `backend/.env` first; without them it fails with "Access denied").
- **Not done:** no API walk against the migrated database (the emulator check of unit C does that). An older jar
  (schema V31 or before) now refuses to start against the development database.
- **For the pilot later (its own approved step):** V32 deletes data for good; `restore-rehearsal.sh` PASS right before,
  and the evidence folder is removed by hand afterwards.

### C Close (2026-11-15)
- **Android resources:** the 530 resources lint reported as unused were removed with a script that reads the lint
  report (517 value entries: 498 strings, 17 plurals, one colour, one dimension; 12 drawables: the bookmark, the
  Nearby tab icon, the eight daily-state icons, `ic_mark_on_ink`), then 22 comment lines left without a resource.
  `strings.xml` 1 912 lines became 1 306; still UTF-8 without a byte-order mark, well-formed (1 115 strings,
  7 plurals). One style (`TextAppearance.Kurinji.ScreenTitle`) is still reported and was left. `ButterflyDrawable`
  stays: the onboarding pages draw with it.
- **Words:** About no longer names Nearby or "Check the way"; the privacy notice lost its Nearby, "Check the way" and
  weekly-counts paragraphs and the Nearby parts of its deletion and retention paragraphs; the account deletion text
  and the Rise notice no longer name a saved list. Known and left for N2 / N8 (the notice is rewritten there): it
  still describes e-mail and password accounts, route and stop checks, and has a paragraph about Rise.
- **Tests updated on purpose** (they pinned removed strings): `ContrastTest` (`opening_soft`), `KurinjiTextsTest`
  (reaction, Saved, "Been here", "Want to go"), `Phase17FGuardsTest` (two Nearby strings), `Phase15IntegrationFixesTest`
  ("saved list"), `SocialTextsTest` (the author's butterfly count).
- **Documents:** `API.md` (a "Removed in N1" block at the top and a note on the appendix; the per-phase sections stay
  as history), `.claude/rules/*.md` (an N1 notice at the top of each; `database.md` has the V32 row and "next V33"),
  `CLAUDE.md` (status row, packages, navigation, permissions, guard tests, V32, next migration V33),
  `DECISIONS.md` N-17 to N-20, `STATUS.md`, `HANDOFF.md`.
- **Final results (all on the final tree):** Android **766 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK;
  lint **0 errors, 91 warnings** (622 before unit C; 1 unused resource left). Backend **496 run, 0 failures, 0 errors,
  2 skipped** (clean build, run in B3; no backend source changed after it except one comment in V32, and the jar
  built after that change applied V32 and started). Python **38 run, OK**.

### Manual check (2026-11-15): STARTED, NOT finished
- Set-up that worked: the development backend from the jar on port 8080 (health 200), the `irl_api33` emulator without
  a window, a debug build whose only backend address is `http://10.0.2.2:8080/` (`local.properties` put back at once).
- Seen (as screen text): install over the older build, start without a crash, the first-run opening, onboarding page 1.
- Not seen: everything else of the list above (sign-in, Home, a post, You, SOS countdown and real SOS, Safe Walk, the
  tile and shortcut, the vault notice). The session ended here; the steps to continue are at the top of `HANDOFF.md`.
- Noted: onboarding page 1 still describes the old place-first product ("places, food, walks and things to try").
