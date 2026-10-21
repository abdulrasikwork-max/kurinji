# Phase 6: Community Safety Intelligence ("Place Signals")

Written 2026-10-20. Status vocabulary per `CLAUDE.md` §3. Summary of status: **implemented, compiled, automated-tested,
V12 verified on MySQL 8.0, live API smoke-tested, emulator-run (API 37)**. **Real phone: not verified.**

## 1. What it is, and why it is different

A "place signal" is a short-lived, structured observation about a **place**: what it is like here (poor lighting,
an isolated stretch), something that happened here (harassment, someone followed), or a **safe place** to head for
(open late with staff, a help point). It is not a post. There are no authors anyone can see, no text, no photos, no
likes, followers or feed, no exact counts and no timestamps of other people.

- Reports **merge**: one signal per (~150 m cell, category). A second report of the same thing is a confirmation.
- Signals **fade**: each contribution keeps a signal alive for its category's lifetime (incident 3 days, condition 30,
  safe place 60). Confirmations keep a true signal alive; "No longer true" answers retire it.
- **Precision is earned by corroboration**: one person's report is shown only to ~1 km; it reaches its ~150 m block
  only once a second person stands behind it.
- **Safe places as well as risks**: the screen answers "where can I go?" (nearest safe place), not only "what is bad?".
- **Safety Radar** instead of a map: drawn by the app, so no map-tile provider learns where the person looks.
- **Calm summary instead of a feed**: "Around you: 1 recent concern, 2 places to take care, 1 safe place. Nearest safe
  place: 300 m." Sorted by distance, never by recency or popularity.

## 2. Threat model (location privacy and abuse)

| # | Threat | Main defence(s) | Residual risk |
|---|---|---|---|
| 1 | Identify who submitted a report | No reporter field in any response; random UUID ids (no order/volume leak); identical 202 answer for new / merged / re-sent; random 10–30 min publication delay so a report cannot be linked to the person seen using their phone | A server or database breach reveals `user_id` per contribution (kept for limits and Phase 7 accountability) |
| 2 | Use reports to track a person | Only the CELL is stored (never coordinates); the exact report time is never stored (only `counts_from` = server time + random delay); contributions are deleted when their lifetime ends; no history view, no "reports by this person" | Breach: a person's live contributions (≤ 10 reports + answers per day, cells only) for up to 60 days |
| 3 | Repeated reports to manipulate the map | One signal per cell+category; one answer per person per signal (DB unique key); 10 reports / 24 h, 3 per area / 24 h, 30 answers / 24 h (429) | Withdraw-and-re-report reuses the daily budget (does not add visible signals) |
| 4 | Fake reports around a home, workplace, college | A single report is only shown to ~1 km (AREA), so one account cannot pin a block; 3 reports per area per day; place-only categories (no "suspicious person"), no text to accuse anyone; labelled "not verified" | Several aged accounts can still raise a fake signal to BLOCK precision: needs Phase 7 verification/moderation |
| 5 | Confirming own report with multiple accounts | Own answers impossible (unique key); a new account counts only after 24 h probation; precision and support need DISTINCT accounts; support is a band (SINGLE / SEVERAL / MANY), so a few sock puppets gain little | Patient sybils (accounts older than 24 h). No device or phone verification exists: Phase 7 |
| 6 | Exact coordinates exposing a reporter | The phone computes the geohash; the server never receives coordinates (unknown JSON fields ignored, never stored); responses give cell centres only | — |
| 7 | Historical reports reveal regular travel | No exact times; freshness only as TODAY / THIS_WEEK / EARLIER; rows deleted at the end of their lifetime (purge every 15 min + read-time filtering); no past-reports endpoint | — |
| 8 | Scraping the API for all locations | Area look-ups only for ONE 6-char cell + its neighbours (~3.6 × 1.8 km), at most 100 signals; 120 look-ups and 15 DIFFERENT areas per hour per account (429); no bbox, no radius, no paging, no global list | Many accounts × 15 areas/h. The limiter is per instance and in memory (by design, see §4) |
| 9 | Sparse areas where ~1 km still identifies | Single reports only at AREA precision; random delay; banded freshness | In a very sparse area, an AREA cell may hold few homes. Documented; k-anonymity on population needs data we do not have (Phase 8) |
| 10 | Combining with other SafeSphere data | Community tables have no link to emergencies, journeys, evidence or contacts; nothing is reported automatically by SOS / Safe Walk; the Emergency Context Packet and Safety Circle never include community data | Server-side join by `user_id` after a breach |
| 11 | Photos / descriptions exposing personal data | **None accepted** in Phase 6 (no free text, no photos, no EXIF to strip) | — |
| 12 | Spam, harassment, false accusations, report bombing | All of the above + "No longer true" retirement (2 people, ≥ supporters); disputes shown ("Some people say this has changed") | No reporting-abuse flow or moderator tools yet: Phase 7 |
| 13 | Probing a signal's hidden block | Nearness for answers is checked at the precision the signal is SHOWN at (area for single reports), so "near" cannot be used as an oracle; one answer per person per signal | — |
| 14 | Map as a reconnaissance tool | Area = where the phone is (no free browsing; 15 areas/h); no people, no times, no counts; incidents fade in 3 days | A determined local stalker can still learn "harassment was reported in this ~1 km area this week" (that is the purpose) |

### What the server stores / shows / never returns

- **Stored**: `place_signals(public_id, category, cell(7), area_cell(6))`, `signal_contributions(signal_id, user_id,
  kind, counts_from)`. Nothing else: no coordinates, accuracy, device, IP, text, or the real report time.
- **Shown to others**: category, group, precision, the shown cell (6 or 7 characters) and its centre, support band,
  freshness band, disputed flag. The caller additionally sees `yourContribution` (only their own).
- **Never returned**: user ids, names, emails, exact supporter counts, any timestamp, the block cell of a
  single-supporter signal, other people's answer kinds, pending / retired / expired signals.
- **Exact coordinates are never exposed and never sent.** The phone keeps its fix in memory while the screen is open
  (to draw the radar) and stores nothing but unsent reports (cell + category, ≤ 10, ≤ 24 h, cleared on logout).

## 3. Design

### Backend (`com.safesphere.backend.community`)
- `GeoCell` (pure geohash encode / bounds / centre / 3×3 neighbourhood with antimeridian wrap). Shared test vectors with
  the phone (`geohash-vectors.csv`, anchored to published values `u4pruyd`, `ezs42`, `dr5reg`, `gcpuv`).
- `SignalPolicy` (pure): from the counted contributions → visible, retired, expired, precision, support, freshness,
  disputed. Rules are in its KDoc; every rule has a test (`SignalPolicyTest`, 12 tests).
- `CommunityService`: report (merge, idempotent, fresh signal after retirement/expiry), contribute (visible only,
  one per person, nearness at shown precision), withdraw, area, mine, purge; daily limits via DB counts.
- `AreaQueryLimiter`: in-memory 1-hour window per account (requests + distinct areas). Deliberately not in the DB:
  storing looked-up areas would itself be a location trail of the person looking.
- `CommunityPurgeJob` (`@Scheduled`, 15 min; `SchedulingConfig`): deletes expired contributions per category,
  retired signals and empty signals; logs only a count.
- `CommunityProperties` (`app.community.*`): delays, probation, limits; safe defaults.
- Migration **V12** (`place_signals`, `signal_contributions`): unique (cell, category), unique (signal, user),
  category/kind CHECKs, `REGEXP_LIKE` cell CHECK, `area_cell = LEFT(cell, 6)` CHECK, FKs cascade (users, signals).

### Android (`feature/community`, `core/location/GeoHash`)
- `GeoHash` (encode only), `CommunityPolicy` (pure: report readiness, outbox rules, placement by distance/bearing,
  brief, rounding, canAnswer), `CommunityRepository` + `RemoteCommunityRepository` (`ApiCallHandler`), DTOs that skip
  unknown values and redact cells in `toString()`, `DataStoreCommunityOutbox`.
- `CommunityViewModel`: one fresh fix when the screen opens / before each report or answer (20 s timeout), area by
  6-char cell, report by 7-char cell; offline report → outbox → sent on the next visit (refused ones dropped).
- `CommunityFragment` + `SafetyRadarView` (rings 500 m / 1 km / 2 km; triangle = concern, square = take care,
  circle = safe place; halo = AREA precision; amber/green, red stays for emergencies) + list + "your reports" with
  Withdraw. Home "Nearby Safety" card now opens Community (badge Available; the sample map image was removed).
- **Permissions: none new.** Location (fine/coarse) is asked from the Community screen only, after the rationale; a
  permanent denial offers Settings. Viewing works with approximate location; reporting needs precise location and a
  fix ≤ 100 m (a report in the wrong block would mislead everyone).

### Decisions (with reasons)
| Decision | Reason |
|---|---|
| Phone sends geohash cells, never coordinates | Data minimisation at the source; the server cannot leak what it never had |
| Precision earned by corroboration (k = 2) | One account cannot pin a home; one reporter's position is never revealed to a block |
| Random publication delay; only `counts_from` stored | Protects the reporter on the spot; the real moment is never recorded |
| 24 h new-account probation | Raises the cost of sock-puppet confirmations; simple to explain |
| Reports merge; one answer per person | Report bombing and pile-ons become pointless; DB-enforced |
| Bands, never counts; no timestamps | No popularity, no inference about who/when |
| Place-only categories, no free text/photos | No accusations of people, no PII, no moderation needed before Phase 7 |
| Area look-ups only for "where I am" + hourly distinct-area limit, in memory | Anti-scraping without creating a location log of viewers |
| Delete on expiry (purge) + read-time filtering | Minimum retention that does not depend on the job running |
| Safety Radar instead of map tiles | No new dependency, no API key, no tile-server location leak |
| Nearness checked at shown precision | Prevents using "near" as an oracle for a hidden block |

## 4. Tests (all automated, all passing on 2026-10-20)

| Suite | Count | Covers |
|---|---|---|
| `GeoCellTest` | 6 | shared vectors, prefixes, centre, cell sizes, neighbourhood, antimeridian, validation |
| `SignalPolicyTest` | 12 | AREA→BLOCK, bands, pending, lifetimes, confirmation keeps alive, disputed, retirement, freshness |
| `CommunityApiIntegrationTest` | 20 | privacy allow-list, precision, silent merge, idempotency, neighbourhood only, own/second answer refused, far away refused, no block oracle, retirement + purge, expiry + purge, owner view, withdraw (other user 404), area/day limits, area-scraping limit, validation, 401 |
| `CommunityDelayApiIntegrationTest` | 2 | real defaults: probation + random delay, stored time ≠ report time, pending merge answers identically |
| Android `GeoHashTest` | 3 | same vectors as the backend |
| Android `CommunityPolicyTest` | 7 | readiness, outbox, placement/bearing, brief, rounding, canAnswer, DTO mapping + redaction |
| Android `CommunityViewModelTest` | 12 | no permission ⇒ nothing sent, cell-only requests, location off / no fix, network failure, approximate / rough fix refused, outbox offline → sent, refused/stale dropped, answers, limit message, withdraw, session expiry |

Totals: backend `mvn test` **204 tests, 0 failures, 2 skipped** (opt-in MySQL); Android `testDebugUnitTest`
**379 tests, 0 failures**; `assembleDebug` OK; `lintDebug` **0 errors, 85 warnings** (was 87).

## 5. Verification actually performed

- **V12 on MySQL 8.0** (dev database `safesphere`): Flyway "Successfully applied 1 migration … now at version v12".
  Constraint checks in a ROLLED-BACK transaction: duplicate cell+category, unknown category, invalid cell character,
  upper-case cell, area mismatch, second answer by the same user, unknown kind → all rejected; cascade delete of
  contributions → verified; rollback left no rows.
- **Live API smoke test** (real defaults): report → 202 `appearsWithinMinutes = 1470` (new account), area empty for
  the reporter's own pending report, mine `PENDING`, invalid area 400, no token 401, withdraw 204 → mine empty.
  The scheduled purge ran against MySQL with no errors in the log.
- **Emulator (Medium_Phone_API_37.0)**, backend started with `probation=0s, delay=0s` **for this demo only**:
  in-context permission rationale → system dialog → radar + summary + nearest safe place; single reports drawn with a
  halo at the area centre, a corroborated one at its block; report flow (category list → privacy confirmation →
  "shown to people nearby"); "I saw this too" on a single report moved it to BLOCK precision (radar and list);
  Withdraw removed the person's own signal from MySQL. Screens are not saved in the repo.
- **Not verified**: a real phone; TalkBack walkthrough; dark mode on a device; offline outbox on a device (unit-tested
  only); the 429 messages on a device (API-tested only).

### Manual verification checklist (for you)
1. Fresh account → Community: rationale shown, nothing asked at launch; deny twice → "Open settings".
2. Allow approximate only → area shown with the "approximate" hint; "Report" says precise location is needed.
3. Report with precise location → "counts within about 1470 minutes" for a new account (24 h probation + delay).
4. With a second, older account nearby: the report appears after ≤ 30 min at ~1 km; "I saw this too" → block.
5. Airplane mode → report → "saved on this phone"; back online → reopen Community → "1 saved report was sent".
6. Two other accounts answer "No longer true" → the signal disappears; a new report starts a fresh one.
7. Look at > 15 different areas in an hour (e.g. moving the emulator location) → limit message.
8. Withdraw → gone from "your reports"; TalkBack reads each card as one item and the radar summary.

## 6. Known limitations (honest)

- Location is asserted by the phone (spoofable); nearness and precision rules raise the cost, they prove nothing.
- Sybil resistance is limited to probation + distinct-account rules + limits; no phone/ID verification (Phase 7).
- `AreaQueryLimiter` is in memory and per instance (resets on restart; a cluster would need a shared store).
- Concurrent first reports of the same place can return 409 for one of them (DB unique key); the phone retries once
  via the outbox only if the error was "no connection", otherwise the person sees the generic conflict text.
- Withdraw + re-report reuses the daily report budget (no extra visible signals result).
- Very sparse areas: AREA precision may still narrow down a reporter. No population data exists to adapt cell size.
- Geohash cells are rectangles, smaller in longitude away from the equator (fine for India; documented).
- Demo data: the emulator run left a few demo signals and `phase6.*@example.com` test accounts in the dev database;
  they expire on their own (3 / 30 / 60 days) or can be removed with their accounts.

## 7. Deferred (deliberately)

- **Phase 7**: verification of reporters (trust levels), moderation, reporting abuse, appeals, free text/photos
  (needs moderation + EXIF stripping), account/phone verification against sybils, login rate limit (Phase 2 gap).
- **Phase 8**: aggregation over time, heat maps, safe-route use of signals, population-aware cell sizes, real map
  tiles (if ever) — `place_signals` + `SignalPolicy` are the inputs it will read.
- **Phase 9**: ML (e.g. anomaly detection on contribution patterns); **Phase 12**: Verified Nearby Assistance can reuse
  the HAVEN group and the precision rule.
- Considered and rejected for now: "turn my emergency into a report" (links an emergency's time/place to a public
  signal — can identify a victim) and "nearest safe place on the SOS screen" (unverified data on an emergency
  screen could mislead). Both need their own privacy design.

## 8. Viva notes

- **What changed / why**: community reporting that produces safety intelligence without becoming a social network.
- **Key classes**: `GeoCell`, `SignalPolicy`, `CommunityService`, `AreaQueryLimiter`, `CommunityPurgeJob`;
  `GeoHash`, `CommunityPolicy`, `CommunityViewModel`, `SafetyRadarView`.
- **API + DB flow**: phone fix → geohash (phone) → `POST /reports {category, cell}` → merge into `(cell, category)` →
  contribution with `counts_from = now + delay` → `GET /area?cell=` → `SignalPolicy` → allow-listed projection.
- **Android lifecycle**: location only in the foreground screen (one fix, 20 s timeout), nothing in the background,
  ViewModel survives rotation; outbox in DataStore, cleared on logout.
- **Likely questions**: Why geohash? (prefix = parent cell, simple, standard, same on phone and server.) Why k = 2?
  (smallest number that stops one account pinning a block.) Why not store exact time? (it is the most identifying
  fact; a delayed time is enough for lifetimes and limits.) Why in-memory limiter? (a DB log of viewed areas is a
  tracking log.) What stops two fake accounts? (probation, limits, bands — honestly, only Phase 7 verification.)
