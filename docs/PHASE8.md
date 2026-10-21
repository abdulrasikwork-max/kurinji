# Phase 8: Safety Intelligence Engine (results)

Written 2026-10-21. Design: `docs/PHASE8_DESIGN.md` (decisions, alternatives, threat model). This file records what
was actually built, how it differs from the design, what was run, and what was NOT verified. Status vocabulary per
`CLAUDE.md` §3. Implemented autonomously under the user's brief of 2026-10-20 ("full autonomy, 8A → 8D").

| Sub-phase | Status |
|---|---|
| 8A backend core (independent freshness, one visibility path, rules-1, `picture` in `/area`) | **implemented, compiled, tested**, live-API checked (real defaults) |
| 8B Android area picture (card, radar area-cell layer, texts) | **implemented, compiled, unit-tested, emulator-verified (API 37)** |
| 8C pattern memory (V14 `area_patterns`, `reached_confirmed`, episodes, moderation reset) | **implemented, compiled, tested, V14 applied + constraint-checked on MySQL 8.0**; a real episode on MySQL (needs a confirmed signal to EXPIRE, 3+ days) **NOT VERIFIED** (covered by H2 integration tests with a virtual clock) |
| 8D region pack + "Check the way" | **implemented, compiled, tested, live-API checked, emulator-verified (API 37)** |
| Real phone (Galaxy A23) | **NOT VERIFIED — not run in this session** |
| TalkBack walkthrough | **NOT VERIFIED** (accessibility tree read with `uiautomator`: the picture card is one focusable item with a full description) |

## 1. What was built

### Backend
- `SignalPolicy.Assessment.independentFreshness`: freshness from INDEPENDENT support only, each linked group represented
  by its OLDEST member, so a sock-puppet group cannot keep an incident "today". The radar's `freshness` is unchanged.
- `CommunityService.visibleIn(cells, now)` → `VisibleSignal(signal, contributions, assessment)`: the ONE definition of
  "what anyone may see" (moderation NONE ∩ `SignalPolicy.visible`), used by `area()`, the picture and region packs.
- `intelligence` package (new):
  - `AreaIntelligencePolicy` (pure, rules-1): evidence class (LOW → UNCONFIRMED, MODERATE → CONFIRMED, HIGH → STRONG),
    relevance (incident TODAY = CURRENT else RECENT; condition/haven EARLIER = AGING), concern per area cell
    (ELEVATED: strong incident today, or ≥ 2 confirmed non-aging concerns, or a confirmed incident where incidents
    recur; SOME: ≥ 1 confirmed concern), information (known = confirmed concerns + min(confirmed havens, 4)),
    conflict (disputed confirmed concern, or confirmed WELL_LIT_BUSY in the same block as confirmed POOR_LIGHTING /
    ISOLATED_STRETCH), outlook, reasons. Neighbourhood = maximum of its cells.
  - `AreaIntelligenceService`: builds the picture; a failure of the rules omits `picture` (logs only the exception
    class), never the signals; database reads happen before the guarded part.
  - `PatternPolicy` (pure) + `PatternService`: 8C memory (§3). `AreaPattern` entity, `AreaPatternRepository`.
  - `RegionIntelligenceService`, `IntelligenceController` (`GET /api/intelligence/region`), `RegionQueryLimiter`
    (in memory; 20 downloads / 6 distinct regions per 24 h; 2 for accounts in probation).
  - `IntelligenceDtos`: allow-list records (`AreaPicture`, `AreaCellPicture`, `RegionPack`, `RegionArea`, `RegionSignal`).
- `config/IntelligenceProperties` (`app.intelligence.*`, validated at start-up), `GeoCell.children` + `REGION_PRECISION`.
- `CommunityPurgeJob`: also forgets faded patterns and region budget windows. `CommunityService.purge`: marks
  `reached_confirmed`, records episodes for signals that end naturally. `ModerationService` REMOVE: forgets the area's
  pattern for that group.
- Migration **V14** `area_patterns` (no FK, no user/signal id; `DATE` columns; CHECKs on cell, group, value 0–4,
  next ≥ decayed) + `place_signals.reached_confirmed`.

### Android
- `feature/intelligence/{domain,data,ui}`: `IntelligenceModels` (outlook/concern/information/reasons, area and region
  models; `toString()` redacts cells), `IntelligencePolicy` (night order by the phone's clock, direction + rounded
  distance of the strongest cell, radar rectangles), `CorridorBriefPolicy` (pure), `IntelligenceRepository` +
  `RemoteIntelligenceRepository` + `IntelligenceApi`, DTOs (`AreaPictureDto` fails CLOSED: unknown or inconsistent
  headline ⇒ no card; `RegionPackDto` fails SAFE: anything not understood ⇒ pack incomplete ⇒ no brief),
  `IntelligenceTexts`, `WayCheckViewModel`.
- `core/location/GeoHash`: `bounds`, `centre`, `isValid`, `REGION`.
- Community: `AreaView(signals, picture)` from the repository; `AreaState.Ready.picture`; "Area picture" card above
  the radar (NoticeView: amber for TAKE_CARE / TAKE_EXTRA_CARE, teal info otherwise, never green, never red; bold
  headline, details, fixed footer); `SafetyRadarView.setAreaCells` (hatched = nothing known, dotted = little known,
  amber outline = confirmed concerns, clipped to the radar).
- Safe Walk set-up: "Check the way" button + intro + result card + footer, in its own `WayCheckViewModel` (the walk's
  `SafeWalkViewModel` / `SafeWalkSession` are untouched). `AppContainer.destinationResolver` is now shared.
- No new permission, no new dependency, nothing stored on the phone.

## 2. Deviations from the design (and why)

| Design said | Built | Why |
|---|---|---|
| Android `core/intelligence` | `feature/intelligence` | Its models use `feature/community` categories; `core` must not depend on a feature. Cross-feature imports already exist (safewalk ↔ circlealert) |
| `CorridorBriefPolicy` in `feature/safewalk/domain` | `feature/intelligence/domain` | Keeps all intelligence together, and lets the isolation test forbid it in Safe Walk's session/policies |
| Information LITTLE when a pattern row has value ≥ 1 | Only when the area is RECURRING | Exposing "≥ 1 past episode" would break the "patterns only from 3 episodes" k-gate (found during implementation) |
| Brief corridor = the Safe Walk bubble | Bubble half-width **capped at 500 m**; ≤ 4 regions; ≤ 20 km | 2 km to the side is not "along the way", and the cap keeps the region budget usable |
| `complete=false` only when too many signals | Also when the phone does not understand a value | Silently dropping an unknown signal could hide a concern |
| "Switch" for patterns | Turning it off also DELETES remembered rows at the next clean-up | Data minimisation: an unused memory should not be kept |
| Reset on REMOVE | Any REMOVE (incl. DUPLICATE) | Conservative and simplest to explain ("do less") |
| — | V14 adds `ck_area_patterns_dates` (next ≥ decayed) | One more impossible state rejected by the database |
| — | "Check the way" text also says the place you type goes to the phone's map service | Honest: the platform Geocoder (Google on most phones) receives the destination text, as at walk start (4C) |
| `EvidenceApiIntegrationTest` untouched | Its "file names carry no ids" check now asserts a random v4 UUID | Pre-existing flaky test: a small id such as 3 appeared inside the random UUID by chance (failed once in this session) |

## 3. Pattern memory (8C) as built
Episode = a signal deleted at the end of its natural lifetime (job path) or replaced after expiry (report path) that
had been marked `reached_confirmed` (MODERATE/HIGH while visible, checked every job run), moderation NONE, no OPEN case.
Retired, removed, quarantined, under-review, withdrawn and single-person signals are never episodes. Value:
`min(4, value·2^(−days/60) + 1)`, at most one per (area, group) per 7 days; RECURRING from 2.5 (three weekly episodes:
2.773); forgotten below 0.25 (one episode ≈ 120 days, a full row ≈ 240 days). Havens have no memory.

## 4. Tests (actually run, 2026-10-21)

| Suite | Result |
|---|---|
| Backend `mvn test` | **316 tests, 0 failures, 2 skipped** (the opt-in MySQL tests), was 261 |
| New backend | `AreaIntelligencePolicyTest` 20, `AreaPictureApiIntegrationTest` 8, `IntelligenceIsolationTest` 4, `PatternPolicyTest` 8, `PatternIntegrationTest` 5, `RegionPackApiIntegrationTest` 6, `RegionPackNewAccountApiIntegrationTest` 1, `SignalPolicyTest` +2, `GeoCellTest` +1 |
| Android `testDebugUnitTest` | **431 tests, 0 failures** (was 403): `AreaPictureTest` 12, `IntelligenceTextsTest` 3, `IntelligenceIsolationTest` 2, `CheckTheWayTest` 11 |
| Android `assembleDebug` + `lintDebug` | OK; **0 lint errors, 87 warnings** (unchanged from Phase 7; two new concatenation warnings were fixed) |

Design §18 → tests: no-data-is-not-safe → `example1_…`, `anEmptyNeighbourhood_isNoInformation_neverSafe`; single
person → `unconfirmedReports_neverRaiseConcern_howeverMany`, `example2_…`; linked freshness →
`independentFreshness_ignoresLinkedSupport`, `example5_…`; havens never cancel → `example6_…`, `places to go for help
never cancel a concern…`; lure guard → `example7_…`; max-not-sum → `neighbourhood_isTheMaximumOfItsCells_neverASum`;
timing oracle → `somethingStillInItsPublicationDelay_changesNothing`; moderation excluded →
`aQuarantinedSignal_isAsIfItNeverExisted`, `quarantinedSignals_areNotInPacks`; truncation →
`thePictureUsesTheFullVisibleSet_beforeThePhonesListIsCut`; paused viewer → `aPausedViewer_stillSeesThePicture`;
pattern rules → `PatternPolicyTest`, `confirmedIncidentsThatEndNaturally_areEpisodes_…`, `aBurstWithinOneWeek_…`,
`unconfirmedRetiredOrReviewedSignals_areNeverEpisodes`, `aModeratorRemoval_forgetsTheArea`,
`fadedRowsAreDeleted_andSwitchingOffForgetsEverything`; region pack allow-list/budget →
`RegionPackApiIntegrationTest`, `RegionPackNewAccountApiIntegrationTest`; isolation (both sides) →
`IntelligenceIsolationTest` (backend + Android); no "safe" claims → `no intelligence text claims safety or danger`;
only region cells sent → `only 5-character region cells are sent, and the brief is computed on the phone`.

## 5. Verification actually performed

- **V14 dry run on MySQL 8.0** (scratch `p8_area_patterns`, dropped afterwards): valid row accepted; **8 violations
  rejected** (bad character, upper case, 7-character cell, HAVEN group, value 4.5, value −1, next before decayed,
  duplicate area+group); no scratch table left.
- **V14 on the dev database**: Flyway "Successfully applied 1 migration … now at version v14"; `reached_confirmed`
  column present; `flyway_schema_history` v14 success.
- **Live API, real defaults: 22/22 PASS** (one run had 21/22 because of a Windows PowerShell 5.1 JSON quirk in the
  test script itself; the database showed the checked contribution was gone; the script was fixed and re-run):
  picture present, allow-list, 9 cells, no weight/tier/user/pattern/time, outlook consistent with its axes; a new
  account's report is pending (≥ 1440 min) and does not move the picture; region pack fields, no ids, confirmed only;
  400 for 4- and 6-character cells; 401; new-account budget (2 regions, 3rd 429, repeat allowed); emergencies and
  journeys still 200; the smoke report withdrawn.
- **Emulator (Medium_Phone_API_37.0), backend with `probation=0s, delay=0s` FOR THE DEMO ONLY** (command-line
  arguments; `application.properties` unchanged; backend stopped afterwards), demo signals seeded near Bangalore:
  1. Community: "Take extra care: recent confirmed concerns nearby." with how much is known, confirmed categories
     (night order: the emulator clock read 00:19), today's incident, unconfirmed reports, help nearby, "Strongest
     concern: in your area", footer — **PASS**.
  2. Radar area cells: hatched / dotted / plain, amber outlines on concern cells, clipped, legend — **PASS**.
  3. Dark mode: card and radar readable, no red — **PASS (visual)**.
  4. Safe Walk "Check the way" with an empty destination → "Type where you are going first." — **PASS**.
  5. "Bangalore Palace" → brief: confirmed categories, today's incident, "Little is known about most of the way.",
     unconfirmed reports, help near the way, "Checked towards: Bengaluru, Karnataka, India" (the Geocoder returned
     the city: exactly why the found place is shown), footer — **PASS**.
  6. The walk then started normally (10 min interval), was cancelled; MySQL: CANCELLED, escalation 0, no escalation
     events, no circle alerts, destination + last location deleted — **PASS**.
  7. The scheduled clean-up on MySQL marked 6 confirmed signals `reached_confirmed = 1`; `area_patterns` stayed empty
     (nothing expired yet) — **PASS (partial 8C on MySQL)**.
  8. Backend log: no ERROR/WARN, no cell or coordinate — **PASS**.
- The emulator ran the build from BEFORE the final wording change of the "Check the way" intro (the map-service
  sentence, §2); that string change is covered by the unit tests and the final `assembleDebug`, not by an emulator run.
- **Not verified**: TalkBack itself; a real pattern episode on MySQL (needs 3+ days); a real phone; the 429 text for
  regions on a device (API-tested only); "Check the way" with location permission denied on a device (unit-tested).

### Manual verification checklist (for you, emulator or Galaxy A23)
1. Empty area → "No community information here yet. That does not mean it is safe or unsafe."; hatched cells.
2. One report → "Little community information here." + "There are also reports nobody has confirmed yet."; no amber.
3. Two independent confirmations of two conditions in one cell → "Take extra care…", amber outline on that cell.
4. Haven + poor lighting confirmed in the same block → "People disagree about some of these places."; still amber.
5. Two flags on a confirmed signal (demo settings) → the picture changes as if it never existed.
6. Airplane mode → refresh → no card (the radar's failure state), never an old picture.
7. Safe Walk → "Check the way" → brief + footer; a 30 km destination → "This walk is too long to check here.";
   Start works whatever the brief says; nothing reaches the Safety Circle, check-ins or escalation.
8. TalkBack reads the card as one item; dark mode; no red anywhere in intelligence UI.

## 6. Security and privacy review (2026-10-21, code as built)

- **Same-set rule holds**: every output is computed from `visibleIn`; tests prove pending, quarantined, removed and
  retired signals have no effect (no timing or moderation oracle).
- **No new personal data**: V14 has no user id, signal id, coordinate or time-of-day; dates are DAY precision and never
  returned; `reached_confirmed` dies with its signal. The pattern table cannot be joined back to signals or people.
- **No viewer trail**: region budget windows in memory only (≤ 6 regions per account for 24 h), never logged; no request
  logging was added; the live log contained no cell or coordinate.
- **Allow-lists**: picture and pack fields asserted in tests and live; no ids in packs; single reports never listed;
  block cells only for CONFIRMED signals (public already).
- **Isolation**: source-scan tests on both sides (backend: intelligence ↛ emergency / emergencycontext / escalation /
  journey / safetycontact / circlealert / evidence / moderation, and the reverse; Android: emergency, circlealert,
  evidence, tracking, Safe Walk session and its policies never reference intelligence).
- **Fail closed / safe**: unknown picture values ⇒ no card; unknown pack values ⇒ no brief; rules failure ⇒ no picture
  but signals still shown.
- **Abuse**: concern levels need ≥ 2 independent people per signal; linked freshness neutralised; havens capped and
  never offset concerns; patterns need 3 corroborated, unmoderated, finished episodes in separate weeks and reset on
  removal; region budget below the existing area budget.
- **Wording**: forbidden-phrase test over all Phase 8 strings; footers say it cannot tell you a place/way is safe.
- **Residual (documented, unchanged from the design)**: patient independent-looking sybils; phone-asserted location;
  in-memory per-instance limiters; an operator with live request logging could correlate a region download with a walk
  started minutes later; the destination text goes to the platform Geocoder (as since 4C); pattern memory keeps derived
  place information up to ~240 days.

## 7. Known limitations

- Thresholds (2 confirmed ⇒ extra care, 2.5 ⇒ recurring, havens cap 4) are judgment calls; no ground truth exists.
- The brief uses a straight line, not the real route; Geocoder results can be coarse (seen: city instead of the palace).
- Areas with few users show "no information" more often (honest, but people may still over-read it).
- `reached_confirmed` is checked every 15 min: a signal confirmed for less than one run is missed (conservative).
- ~~The Home "Safety status: SAFE" sample card conflicts with "never say safe".~~ **Resolved 2026-10-21** (the user
  chose a factual status card): the card now states only what the phone knows, see `HANDOFF.md` §6.
- Dev database leftovers: test accounts `phase8.smoke.*@example.com` (6) and `phase8.demo.*@example.com` (10), demo
  signals near `tdr1v9` (they expire on their own: 3 / 30 / 60 days).

## 8. Viva notes

- **What / why**: decision support that turns trusted signals into an honest area picture; concern and knowledge kept
  apart so "no data" never reads as "safe"; recurring patterns remembered without history; a way brief computed on
  the phone so the server never learns a route.
- **Key classes**: `AreaIntelligencePolicy`, `PatternPolicy`, `CorridorBriefPolicy` (pure); `AreaIntelligenceService`,
  `PatternService`, `RegionIntelligenceService`, `RegionQueryLimiter`; Android `IntelligencePolicy`, `IntelligenceTexts`,
  `WayCheckViewModel`, `SafetyRadarView.setAreaCells`.
- **API + DB flow**: `/area` → `visibleIn` → rules-1 → codes → phone words; job → `reached_confirmed` → natural end →
  `area_patterns` → "recurring"; phone → region cells → `/region` → pack → `CorridorBriefPolicy` on the phone.
- **Android lifecycle**: everything in ViewModels (memory only); a picture exists only in a fresh Ready state; the way
  check has its own ViewModel and cannot touch the walk.
- **Likely questions**: Why no score? Why max not sum? Why can a haven not cancel a concern? Why independent freshness?
  Why only 3+ episodes and a weekly cap? Why region packs, not a route API? Why does an unknown value hide the card but
  make the pack incomplete? (Answers: `PHASE8_DESIGN.md` appendix and §2 above.)
