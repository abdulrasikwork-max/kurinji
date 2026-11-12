# Phase 18 plan: place trust (18A) and the Next-person card (18B) (2026-11-12)

Status: **18A-1 (rules A1 and A2) implemented and tested on 2026-11-12 (H2, and since the afternoon of 2026-11-12 its two queries on real MySQL 8.0.46, the development database, read-only; not deployed to the pilot). 18A-2, 18A-3 and 18B: planned, approved by the owner on 2026-11-12, not built.** ("plan the trust system first,
then the Next-person card"); each part still needs the owner's approval of this plan before code (CLAUDE.md section 3).

The problem to answer in a viva: *"a café pays ten people to write 5/5: why is IRL's place page still worth reading?"*

## 0. What already defends a place (built in 17G-2, unchanged)

- A rating **never sorts or ranks** anything, shows **no count**, appears on the place page only (U5). Stars buy no reach.
- No business accounts, no pages, no follower counts, no public popularity.
- One account per verified phone number (17B): a fake rater costs a SIM.
- Only enabled accounts past the 72 h probation count; at least 5 raters; Bayesian prior (3.5 worth 5); one value per IST day.
- Limits: 20 "Been here" marks and 40 rating writes per person per day. Paused people cannot rate.

Honest gap today: **five old accounts that know each other can still create or move a small place's average**, and every
counted rating weighs the same.

## 18A. Trust rules (deterministic; no learned model)

Principle (same as moderation, D2): rules produce the number; content may be held automatically; **only a human restricts a
person**. Nobody is ever told "your rating was not counted" as a punishment, and nothing here creates a shadow ban: a rating
is always saved and always shown to its owner; what changes is only how much it adds to the ONE public average.

| # | Rule | How |
|---|---|---|
| A1 | **Independent raters** | Raters who are connected to each other (accepted `connections`) form one group. The public average needs **5 groups**, and a group adds ONE value (the mean of its members). Five friends rating their friend's café = one voice |
| A2 | **Rater weight** | A pure policy gives each group member a weight from facts the server already has: account age band (72 h to 30 d = 0.5, older = 1.0) and breadth (rated only this one place = 0.5, three or more different places = 1.0). The group value is the weighted mean |
| A3 | **Presence, optional** | "Been here" tapped at the place may send the phone's **7-character cell** (already the only location a social request may carry). The server compares it with the place's cell and its 8 neighbours and stores one boolean `at_place` on the visit row. A confirmed visit weighs x 1.5. Never required (the posting delay invites people to write later), never shown to anyone, spoofable with a mock location: it raises cost, it is not proof |
| A4 | **Burst hold** | If more than N new ratings for one place arrive within 48 h and most come from accounts younger than 30 d or from one connected group, the place's average **keeps yesterday's value** and one case opens in the existing moderation queue ("unusual rating activity", no names of raters shown beyond what a moderator already sees). A moderator releases or keeps the hold. No account is touched automatically |
| A5 | **Disclosure** | Composer switch "I was paid, or got this free". The post carries a visible label "Paid or gifted visit"; the author's rating for that place is not counted. New report reason "Undisclosed promotion" (existing 13D flow: a human decides) and one paragraph in the guidelines |

Not planned, with reasons: GPS-only ratings (excludes honest late posts, still spoofable); business account types (creates
the incentive this design removes); a visible "trusted reviewer" badge (a public rank of people: forbidden); any AI verdict.

**Files (backend):** `RatingPolicy` (weights, groups: pure, unit-tested with the attack cases below), `PlaceRatings`
(reads rows + connections instead of two totals; still text only, still once per IST day), `PlaceVisitRepository` (one
query returning rater id, rating, account age, breadth, `at_place` for ONE place; never exposed), `PlaceVisitService`
(optional `cell` on `PUT /went`), `PostService` + DTOs (`disclosed`), report reasons, moderation case kind.
**Migration V31:** `place_visits.at_place TINYINT(1) NOT NULL DEFAULT 0`; `posts.disclosed TINYINT(1) NOT NULL DEFAULT 0`;
`place_rating_holds (place_id PK → places, held_at, held_value, case_id)`. Verify on real MySQL (backup, scratch dry run).
**API:** `PUT /api/places/{id}/went {cell?}`; `POST /api/posts {disclosed?}`; post answers add `disclosed`. No new read
endpoint, no count, no weight and no group ever leaves the server.
**Android:** `PlaceVisits.went(placeId, cell?)` using the existing `LocationAsk` + `preciseFix` path only when the person
already allowed location (no new prompt on this tap); composer switch under "Add details"; label on the card and post page.
**Tests:** policy cases (5 connected 5-star raters show nothing; 5 unrelated show a value; one-place accounts weigh half;
a gifted visit never counts; a held place keeps its value), isolation (`RatingIsolationTest` extended: no DTO field for
weight, group, presence or hold; nothing sorts by them), `EvaluationIsolationTest` untouched (no 9B counter is read).
**Privacy review points:** `at_place` is a private fact about the person on their own row (erased with the account, never
served, not even to them as a badge); the cell is compared and dropped, never stored; a connection group is computed in
memory and never returned.
**Risks:** more complex average = harder to explain (mitigation: the policy is one pure class with a table of cases);
groups by connection miss strangers hired online (A2 and A4 cover part of it; the honest limit stays documented).

**Viva line:** "We do not detect fake reviews with AI. We remove the reason to write them, count independent people rather
than accounts, and let a human look when a place suddenly gets attention."

## 18B. The Next-person card (after 18A)

After "Been here" the sheet asks up to three one-tap questions from closed lists, all optional:
**Best time** (morning / afternoon / evening / night) · **How busy** (quiet / lively / crowded) · **Went** (alone / with
friends / with family). The place page shows, in words and without numbers, what **independent** visitors (18A groups)
mostly said, per question, only from 5 groups: "Mostly quiet · Best in the morning · People went with friends".

- Same guards as the rating: no count, once per IST day, never sorts, the Explore engine does not read it.
- **Rule check needed before building (the owner decides):** no line may claim a place is safe (Phase 8 rule). "Went alone"
  is a fact about visitors, not a safety claim; a question such as "comfortable alone?" is NOT in this plan for that reason.
- V32: three nullable closed-list columns on `place_visits` (binary collation, CHECKs written with COALESCE).
- Files: `PlaceCardPolicy` (pure), place page DTO gains `card` (three optional words), `WentSheet` (three chip rows),
  `PostCollectionFragment` (one line under the rating).

## Order

18A-1 (A1, A2: no schema change, pure policy + one query) → 18A-2 (V31: A3, A5) → 18A-3 (A4 with the moderation queue)
→ 18B. Each step: plan review, build, tests, real MySQL where a migration is involved, manual check, docs.

## 18A-1 as built (2026-11-12)

- `RatingPolicy.display(List<Rater>, Collection<Link>)` (pure): groups by union-find over the links, a group's voice = the
  weighted mean of its members, its weight = its strongest member's; fewer than 5 groups show nothing; then the same
  Bayesian average. `weight` = 1 for an account at least 30 days old that has rated 3 or more places, otherwise 0.5
  (decision P18-2: half, not a quarter; this is simpler than the two separate factors of the table above).
- `PlaceVisitRepository.raters(placeId, cutoff)` replaces `totals`; `ConnectionRepository.acceptedAmong(ids)`;
  `PlaceRatings.compute` (still text only, still once per IST day). No schema change, no API change, no Android change.
- Tests: `RatingPolicyTest` 5 → 11, `Phase17g2VisitsApiTest` +1 (five connected raters show nothing; with four independent
  people the place shows 3.5), `RatingIsolationTest` (only `PlaceRatings` reads the raters; no DTO names them). The older
  expectations changed on purpose: five 10-day-old one-place raters with 5 now show **4.0** (was 4.3).
- **Verified on real MySQL (2026-11-12 afternoon, development database on Windows, 8.0.46, schema V30, read-only):**
  `MySqlPlaceRatersQueryTest` (opt-in, `SAFESPHERE_MYSQL_TESTS=true`) runs `raters` for every rated place and `acceptedAmong`
  through the repositories and compares each row with the same question in plain SQL: 2 tests, 0 failures. It only reads.
  It also showed that such a test must call `UtcTimeZone.install()` first (surefire runs in Asia/Kolkata; without it the
  account dates come back 5 h 30 m apart: a test set-up matter, the server always installs UTC).
- **Found on the way:** `RatingPolicyTest` had two methods with the same name and did not compile, so the "911 run" of the
  05:20 checkpoint cannot have been the final tree (most likely an old compiled class was reused). Renamed the second one
  (`impossibleRaters_showNothing`); the full suite on the corrected tree: **914 run, 0 failures, 0 errors, 4 skipped**
  (the two opt-in real-MySQL classes).
- **Not verified:** the live pilot still runs the old jar; the pilot's own MySQL (8 in WSL) was not touched. **Known limit:** `acceptedAmong` uses two `IN` lists of all raters of the place (fine for the
  pilot; a place with thousands of raters needs a join instead).
