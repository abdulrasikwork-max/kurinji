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

## 18A-2 detailed plan (2026-11-14): presence (A3) + disclosure (A5), migration V31 - AWAITING THE OWNER'S APPROVAL, nothing built

Gate: the pilot's backup + rehearsed restore passed on 2026-11-14 (`docs/D12_DEPLOYMENT.md` section 10). Code inspected for
this plan: `V30`, `PlaceVisit`, `PlaceVisitService`, `PlaceVisitRepository.raters`, `PlaceRatings`, `RatingPolicy`,
`PlaceController`, `PostDtos`, `GeoCell`, `SocialIsolationTest`, Android `DiscoverApi`, `PlaceVisits`, `WentSheet`, `ExperienceDtos`.

### Scope (two changes to section 18A above, both proposed, the owner decides)
- **V31 holds only what 18A-2 uses:** `place_visits.at_place` and `posts.disclosed`. The table `place_rating_holds` moves to
  18A-3 with the burst hold that reads it (then V32; 18B becomes V33). An unused table should not reach the pilot early.
- **The report reason "Undisclosed promotion" moves to 18A-3.** It is a new value in FIVE closed-list CHECKs of V23
  (`content_reports`, `posts.moderation_reason`, `comments.moderation_reason`, `social_strikes`, `social_standing`) plus the
  moderator screens: moderation work, reviewed with A4. Until then a person reports such a post as Spam or Other.

### Migration V31 (`V31__presence_and_disclosure.sql`)
```sql
ALTER TABLE place_visits ADD COLUMN at_place TINYINT(1) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_place_visits_at_place CHECK (at_place IN (0, 1));
ALTER TABLE posts ADD COLUMN disclosed TINYINT(1) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_posts_disclosed CHECK (disclosed IN (0, 1));
```
Neither column is part of a foreign key (CHECK allowed); NOT NULL, so no NULL case; existing rows become 0; no new table, so
`AccountEraserCoverageTest` is unaffected (`place_visits` and `posts` are already erased with the account).

### A3 presence (backend)
- `PUT /api/places/{id}/went` accepts an OPTIONAL body `{"cell": "<7 characters>"}` (`@RequestBody(required = false)`; an
  old app sends none and behaves as today). Malformed cell: 400. A valid cell is compared with `GeoCell.neighbourhood(place.cell)`
  (the place's cell and the cells around it; the exact set is read from `GeoCell` when building); inside it, `at_place = 1`.
  **The cell is compared and dropped: never stored, never logged.**
- A row that exists with `at_place = 0` may be raised to 1 by a later call with a matching cell (the person marked it from
  home and comes back); it is never lowered. One upgrade per row, so no new limit is needed.
- `VisitResponse` does NOT change: the app is never told whether presence was confirmed (no badge, nothing to probe).
- `RaterRow` + `RatingPolicy.Rater` gain `atPlace`; `weight` = (0.5 or 1.0 as in P18-2) x 1.5 when present. Still 5
  independent groups; presence never replaces that. Proposed decision **P18-4**: x 1.5.
- `mergePlace`: the surviving row is present if either row was.

### A3 presence (Android)
- "Been here" sends `went` at once WITHOUT a cell (the tap must never wait for GPS). Only if location is ALREADY allowed
  (no prompt on this tap), `PlaceVisits` asks the existing `PreciseFix` path for one fix, at most about 10 s, accuracy
  100 m or better (the 17D rule), and then sends the same `PUT` again with the cell. Denied, rough or late: nothing more is
  sent. The search stops in `onStop`. No screen registers a launcher or reads a stream (`Phase17DLocationTest` stays green).
- Honesty: one line in the "How was it?" sheet and in About, e.g. "If location is on, we check that you are near this
  place. Nobody sees it." (wording through `SocialTextsTest` / `HonestyTextsTest`).

### A5 disclosure (backend)
- `PostRequest.disclosed` (Boolean, Experience only; ignored for a question). `PostEditRequest.disclosed`: may be turned ON
  later, never OFF (like the audience, which may only narrow). `PostResponse.disclosed` for every reader of the post.
- `raters` gains: `AND NOT EXISTS (a post by this rater at this place with disclosed = true)`. The rating is still saved
  and shown to its owner. This is said up front in the composer ("your rating for this place will not count towards its
  average"), so it is a stated rule, not a silent one.
- No change to Home, Explore or any order: a disclosed post is listed like any other.

### A5 disclosure (Android)
- Composer, under "Add details", Experience only: switch "I was paid, or got this free" + the one-line consequence.
- Card and post page: a plain text label "Paid or gifted visit" (neutral colour: not amber, which means moderation; not red).
- Edit screen: the switch can be turned on; once on it is shown locked.

### Files
- **Backend:** `V31__presence_and_disclosure.sql`; `PlaceVisit`, `Post`; `PlaceDtos` (went body), `PostDtos`;
  `PlaceController`, `PlaceVisitService`, `PostService`; `PlaceVisitRepository.raters`, `PlaceRatings`, `RatingPolicy`.
- **Android:** `DiscoverApi`, `RemoteDiscoverRepository`, `DiscoverRepository`, `PlaceVisits`, `PostFragment`,
  `PostCollectionFragment`, `WentSheet`; `ExperienceDtos`, composer draft + `ComposeFragment` / `ComposeViewModel`, card
  adapter, `item_experience_card.xml`, `fragment_post.xml`, `strings.xml`. No new permission, dependency or service.
- **Docs:** `API.md` (+ appendix), `DECISIONS.md` (P18-4 to P18-6), `STATUS.md`, `HANDOFF.md`, `.claude/rules/database.md`,
  `backend.md`, `android-social.md`, `CLAUDE.md` (next migration V32; it is at its 40k limit: move text, add one line).

### Tests
- `RatingPolicyTest`: present weighs x 1.5; five present but CONNECTED raters still show nothing; no weight is ever 0.
- `Phase17g2VisitsApiTest`: no body, near cell, far cell, malformed cell (400), upgrade 0 to 1, never 1 to 0, answer
  unchanged; a disclosed author's rating is not counted and is still returned to them.
- Post API tests: disclosed stored and returned to author / connection / stranger; edit on, not off; a question ignores it.
- `RatingIsolationTest`: no DTO field for presence; only `PlaceRatings` reads it. `SocialIsolationTest`, `EvaluationIsolationTest` untouched.
- `MySqlPlaceRatersQueryTest` (opt-in, real MySQL): the changed `raters` query.
- Android: `PlaceVisits` (first call without a cell; a second only when allowed AND precise; none when denied), composer
  draft, the label, text guard tests, `Phase17DLocationTest`.

### Verification order (each step reported with its real result)
1. Backend + Android unit tests, `assembleDebug`, lint.
2. **Development MySQL (Windows):** backup, dry run of the two CHECKs on a scratch copy (accepted and rejected values),
   apply V31 by starting the backend, `SAFESPHERE_MYSQL_TESTS=true`, a live API walk (two accounts).
3. Emulator, then the A23 (Been here at / away from a place cannot be told apart on screen: checked in the database on
   the DEVELOPMENT server only).
4. **Pilot: a separate step with its own approval.** `restore-rehearsal.sh` PASS immediately before, stop `irl-backend`,
   keep the old jar, new jar, read the journal for the Flyway line that V31 was applied, smoke test. Rollback = that
   run's dump + the old jar (Flyway only goes forward). Note: that jar also brings 18A-1 to the pilot for the first time.

### Privacy and security review points
- A social request carries a cell and no coordinate (already the rule; places are created the same way). The cell says
  where the person is at that moment: it is used for one comparison and dropped; `at_place` is one private bit on the
  person's own row, served to nobody (not even to them), erased with the account.
- Nobody can use it to watch a person: no other account, and no moderator screen, reads `at_place`.
- A mock location defeats presence: it raises the cost of a fake rating, it is not proof (documented limit).
- Disclosure is self-declared: an undeclared paid visit is caught only by a report (human decision, 18A-3 adds the reason).

### Risks
- A second request after the tap on a slow line (the pilot's hotspot): small, sent only when a precise fix exists.
- The weight table grows (0.5, 0.75, 1.0, 1.5): still one pure function with a case table.
- "Turn on, never off" for disclosure may surprise someone who switched it on by mistake: they can delete the post.

### Decisions the owner is asked for
1. V31 = the two columns only; holds table and the new report reason move to 18A-3 (recommended).
2. Presence weight x 1.5 (P18-4).
3. Presence is automatic when location is already allowed, with the sentence in the sheet and About (recommended), or an
   explicit switch in the sheet.
4. Disclosure can be turned on by an edit but not off (recommended).
5. The composer tells the author up front that a disclosed visit's rating does not count (recommended).

### 18A-2 backend as built (2026-11-14) - the owner approved all five decisions as recommended ("approved, start with the migration and backend")

- **Status: backend implemented, compiled, tested; V31 verified and applied on the DEVELOPMENT MySQL. Android: not started. Pilot: not touched (old jar, schema V30).**
- Built exactly as planned above: `V31__presence_and_disclosure.sql`; `PlaceVisit.atPlace` (`confirmAtPlace()`, no setter back);
  `Post.disclosed` (`disclose()`, on only); `PlaceDtos.WentRequest`; `PlaceController.went` (optional body);
  `PlaceVisitService.went(user, place, cell)` + merge rule; `PlaceVisitRepository.raters` (`atPlace`, NOT EXISTS disclosed post);
  `PlaceRatings`; `RatingPolicy.weight` (x `PRESENT_FACTOR` 1.5, now public for its test); `PostDtos` + `PostService`.
  One detail decided while building: a BLANK cell counts as no cell (200), only a non-blank malformed one is 400.
- **Tests:** full backend suite **928 run, 0 failures, 0 errors, 4 skipped** (the two opt-in real-MySQL classes); was 914. New:
  `Phase18a2PresenceDisclosureApiTest` (9), `RatingPolicyTest` +3 (12 to 15), `RatingIsolationTest` +2 (6 to 8). Android and
  Python not touched, not re-run.
- **Real MySQL 8.0.46 (development database on Windows):** backup `safesphere-before-v31.sql` (46 tables, session scratchpad:
  it does not survive the session); dry run on scratch copies of both tables with all 16 + 16 real rows: **6 accepted + 6
  refused** (2, -1 and NULL refused for each column; existing rows 0; default 0), scratch tables dropped; Flyway on start-up:
  "Successfully applied 1 migration ... now at version v31", health 200, backend stopped again; columns and both CHECKs read back
  from `information_schema`. `MySqlPlaceRatersQueryTest`: **2 run, 0 failures** with 6 visits temporarily marked present and one
  rater temporarily given a disclosed post at the place (the repository and plain SQL agree; the disclosed rater is left out:
  4 of 5); that temporary data was put back (0 present, 0 disclosed, the visit's owner restored).
- **NOT verified:** a live two-account API walk against MySQL (no test account with a known password on the development
  server; H2 API tests + the real-MySQL query test stand in for it); anything on Android; anything on the pilot.
- **Next:** Android (presence call + honesty line; composer switch, label, edit lock), then emulator and A23, then the pilot
  deploy as its own approved step after a fresh `restore-rehearsal.sh` PASS.

### 18A-2 Android as built (2026-11-14, evening)

- **Status: implemented, compiled, tested (unit tests). NOT seen on any screen: no emulator or phone walk yet. Not committed.**
- **Presence - one change from the plan, towards less data (P18-4, amended):** the phone decides "am I at the place?" ITSELF
  and sends its cell ONLY then. `PresenceCell.near(placeCell, lat, lng)` = the place's own cell or one of the eight around
  it (from `GeoHash.bounds`); `PresenceCell.ifAt` = the shared `preciseFix` (10 s window, 100 m rule, never a permission
  prompt), then the place's cell (already in the place page's state; on the post page one `GET /api/places/{id}`, asked only
  after a good position exists). So "Been here" tapped at home sends NOTHING about where the person is; the plan would have
  sent the home cell for the server to compare and drop. The server check stays (it does not trust the phone).
- `PlaceVisits`: `went` is sent at once as before (the sheet opens, nothing waits); then `confirmPresence` runs quietly and,
  if at the place, calls `DiscoverRepository.wentAt(placeId, cell)` (`PUT .../went {cell}`, `WentRequestDto`). Its answer and
  any failure are ignored. `stopLooking()` on `onStop` of both screens (`onScreenHidden`) and before "I haven't been here".
  `PostCollectionViewModel` and `PostViewModel` take an optional `LocationProvider` (`container.screenLocationProvider`).
- **Disclosure:** composer switch `disclosedSwitch` "I was paid, or got this free" under the approve-first switch (Experience
  only) with `disclosedNote` shown when on (label, rating not counted, cannot be switched off); `PostDraft.disclosed`,
  `PostRequestDto.disclosed` (sent only as `true`, never for a question), `PostDto.disclosed` → `Post.disclosed` (missing =
  false), `ExperienceCard.disclosed`; label `disclosedLine` "Paid or gifted visit" (neutral colour, info icon) on the feed
  card and above the words on the post page; in the card's spoken description.
- **Words:** `went_presence_note` in the "How was it?" sheet; one paragraph added to `about_body`.
- **Not built, and why:** the "edit lock" of the plan: the app has NO edit screen (editing is API only), so there is nothing
  to lock. The small rail card on Home ("Talking now") has no visible label (its spoken description has it; the post page
  shows it). Saved rows and Explore results show no label either (they open the post page, which does).
- **Tests:** Android **1086 run, 0 failures, 0 errors, 0 skipped** (was 1069); new `Phase18A2TrustTest` (17: near / not near,
  at the place, beside it, elsewhere, rough, no permission / approximate / location off with zero position requests, empty
  window, take back, screen hidden, silent failure, no provider, post page, request shape, disclosure DTOs, card, words and
  colours); `Phase17G2VisitsTest`'s fake API gained the new method. `assembleDebug` OK; lint **0 errors, 98 warnings**
  (unchanged). Backend not touched since its 928 / 0 run.
- **IMPORTANT before installing this build anywhere:** `android/local.properties` points at the PILOT, whose jar is the old
  one. Against the old jar a disclosed post would be accepted WITHOUT the disclosure (the old server ignores the unknown
  field) and shown without a label: a silent loss. Do not use this build against the pilot until the pilot runs the new jar.
- **NOT verified:** every screen (composer switch + note, label on card and post, the sheet line, About), light / dark, large
  text, TalkBack, the presence call on a real phone with real GPS (at a place and away from it), the A23, the pilot.
- **Known limit:** leaving the screen within the 10 s (for example "Share your experience" straight from the sheet) stops the
  search (17D rule: location only while the screen is open), so that visit is not confirmed unless "Been here" is opened again.

### Viva questions this step must answer
Why a boolean and not the cell? Why is presence optional? Why can a mock location not buy a rating by itself (5 independent
groups still needed)? Why does a paid post stay visible but stop counting? Why is the person not told "confirmed"?
