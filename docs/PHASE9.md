# Phase 9: AI/ML Intelligence (results)

Written 2026-10-21. Design: `docs/PHASE9_DESIGN.md`. This file records what was actually built, how it differs from the
design, what was run and what was NOT verified. Status vocabulary per `CLAUDE.md` §3. Built under the user's briefs of
2026-10-21 ("fix 9C.0 first", then "full engineering freedom for 9A", then "continue Phase 9C only, full autonomy":
9C.1–9C.3 in §9–§16, then "Phase 9D, full autonomy": §18–§26, then "Phase 9B only, full autonomy": §27–§34).

| Step | Status |
|---|---|
| **9C.0** reopen bug (Phase 7) | **implemented, compiled, tested, live-verified on MySQL (10/10 PASS)**. Complete; not to be changed further |
| **9A** adversarial simulation | **implemented, compiled, tested** (37 new tests, stable over 3 runs). Test code only: **no production code, migration, API, dependency or configuration changed** |
| **9C** coordination check (coord-1 R1/R2, **SHADOW by default**), no withdrawal lock for COORDINATION cases, moderator pattern texts | **implemented, compiled, tested** (backend 366, Android 446, lint 0 errors / 87 warnings), **live-checked on MySQL in SHADOW (11/11 PASS)**. §8 |
| **9C.1 S15 Option B** (a moderator's DISMISS of a COORDINATION case lets that signal's supporters count separately) | **implemented, compiled, tested, live-verified on MySQL (ACTIVE run), emulator-verified (API 37)**. §9 |
| **9C.2 group members as case participants** + "pause the whole group" in one decision | **implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37)**. §10 |
| **9C.3 two coord-1 defects found and fixed** (dismissed-group guard; honest pair pulled into a ring's core) | **implemented, tested** (both reproduced against the old code first), live-verified as part of the ACTIVE run. §11 |
| **9C as a whole** | **Complete: reviewed and accepted by the user on 2026-10-21.** Shipped mode stays **SHADOW**. TalkBack speech, dark-mode colours of the moderator screen and the Galaxy A23: **NOT VERIFIED** (§14, §16) |
| **9D-1 neighbour join** (a lone concern report joins the same signal in a block sharing a SIDE with its own, in the clean-up job, once it counts) | **implemented, compiled, tested, live-verified on MySQL (19/19 with 9D-2/3), emulator-verified (API 37)**. §18 |
| **9D-2 rules-2** ("take extra care" from two confirmed concerns needs 3 independent people) | **implemented, compiled, tested; SHADOW only** (counted in the log, never shown). **Not promoted: no real-world evidence justifies it** (§19) |
| **9D-3 havens-alone cap** (active rules are now **rules-1.1**: safe places alone never make an area look known) | **implemented, compiled, tested, live-verified, emulator-verified; ACTIVE** (§20) |
| **9D as a whole** | **Implementation complete, awaiting the user's review** (2026-10-21). TalkBack speech, the Galaxy A23 and real-world rates: **NOT VERIFIED** (§25) |
| **9B outcome counters** (V15, weekly system-wide counts, ADMIN-only report) | **implemented, compiled, tested, V15 applied + constraint dry run on MySQL, live-verified. Complete: reviewed and accepted by the user on 2026-10-21; frozen** (§27–§34). Nothing user-visible; no emulator check needed. Real-world rates: **NOT VERIFIED** (no deployment, no real data) |
| Learned model (M1) | **not built**, gates G1–G3 not met (no real data exists) |

---

## 1. 9C.0: dismissed COORDINATION cases are no longer reopened

- **Bug** (found by reading the code, then reproduced by a test): after a moderator dismissed a COORDINATION case, the
  next clean-up run opened a new one for the same signal, because only open cases were checked. Each reopen also blocked
  withdrawal again.
- **Fix:** `ModerationService.maintain()` skips a signal that already has a DISMISSED COORDINATION case
  (`ModerationCaseRepository.existsBySignalIdAndOriginAndOutcome`). Closed cases are deleted after 90 days, so a dismissed
  signal can be looked at once more after that. FLAGS and REREPORTED cases never looped and are unchanged.
- **Test:** `ModerationApiIntegrationTest.aDismissedCoordinationCase_isNotReopenedByTheNextRun_andItsReporterCanWithdrawAgain`
  failed before the fix (2 cases for the signal instead of 1) and passes after it.
- **Live verification on MySQL 8.0 (2026-10-21):** backend started against the dev database with demo timing as
  command-line arguments only (`probation=0s`, `delay=0s`, `purge-interval=PT20S`; `application.properties` unchanged),
  driven through the API, rows checked in MySQL. Two accounts backed the same three places (an open-ocean test cell), the
  real scheduled job opened 3 COORDINATION cases, a temporary moderator dismissed one. Then a **positive control** (a new
  linked signal got its own case, which proves the job ran after the dismissal), two more job runs, and the checks.
  **10/10 PASS:** the dismissed signal kept exactly one case (CLOSED/DISMISSED), the other signals one open case each, and
  the reporter could withdraw (204). The first attempt scored 6/8 because my control script hit the real limit of 3
  reports per area per day (the control signal never existed). The fix was not at fault; the script was corrected and
  re-run. Backend log: 0 ERROR/WARN, no cell, no e-mail. Backend stopped.
- **Left in the dev database:** 6 `phase9.verify.*@example.com` accounts (all USER; random passwords never stored) and 7
  DISMISS rows in the append-only audit log (expire after 365 days). No signal, case or standing row is left.

## 2. 9A: what was built (all in `backend/src/test/java/com/safesphere/backend/evaluation/simulation/`)

| File | What it is |
|---|---|
| `AttackHarness` | Drives synthetic attackers and honest people through the **real API** (MockMvc, JWT, every limit) on H2, with the virtual clock and the real clean-up job steps; counts attacker effort (accounts, accepted/refused writes, burst waits). Corrects each new account's `created_at` to the virtual clock, because the server takes it from the system clock (otherwise accounts made after the clock moved would skip probation) |
| `AttackSimulationTest` | 18 scenarios (26 runs) with production timing switched back on: 10–30 min publication delay, 24 h probation, 10 writes per 10 minutes |
| `AccountFarmingSimulationTest` | Sign-up limit (10 per address per hour, real) with per-request network addresses (RFC 5737 documentation addresses), plus probation |
| `GridBoundaryMeasurementTest` | Monte Carlo (fixed seed, real `GeoCell`) of two honest reports of one street |
| `PictureInvariantPropertyTest` | Property tests of rules-1: invariants I1–I8 on 5,000 random evidence sets |
| `CandidateRules` + `CandidateRulesTest` | **Prototypes** of the candidate rules (coord-1 R1 support core, R2 exclusive pair, rules-2, havens-alone cap), used only to measure them. The server does not run them |
| `SupportSnapshot`, `AttackReport` | In-memory view of who backs what (for the prototypes); report writer → `backend/target/attack-simulation/*.md` (regenerated each run, banner "SIMULATED — not real-world data") |

### 2.1 Deviations from the design (and why)

| Design said | Built | Why |
|---|---|---|
| An in-memory simulator of the rules plus a fidelity test against the real API | Scenarios drive the **real API** directly | No second copy of the rules that could drift, so no fidelity test is needed, and limits, delays, probation, merging and moderation are all exercised. Cost: speed (~25 s), acceptable |
| `target/simulation-report.md` | `target/attack-simulation/{community-attacks, account-farming, grid-boundary, picture-invariants}.md` | One file per test class (no cross-class hook needed) |
| Candidates measured "in simulation" | Test-scope prototypes in `CandidateRules` | Measuring needs code. Production code stays untouched until 9C/9D, where a prototype moves in with its own tests |
| Scenarios S1–S11 | S1–S18 + F1–F2 | Added from the brief: withdrawals, flag bombing, "no longer true" erasure, pattern poisoning, scraping, write limits, account farming |
| — | **New candidate: havens-alone cap** | Found by S6 (not in the design) |

## 3. Results

> **SIMULATED — not real-world data.** Synthetic accounts and places (open ocean, around 12.5° S 135.5° W), in-memory
> database, virtual clock. The API, rules (rules-1, Phase 6/7 trust and moderation) and limits are the real ones. These
> numbers say what the rules **allow**, not how often anyone does it.

"Prepared" accounts are past the 24 h probation (an attacker makes them a day ahead). Times are virtual time.

### 3.1 Fake concerns

| # | Attack | Attacker cost | What the server did | What a viewer sees |
|---|---|---|---|---|
| S1 | One account reports a lot | 1 account; 9 accepted, 1 refused over 2 days | 4th report in one area on one day: 429. 9 single-person signals, all LOW, area precision | LITTLE_INFORMATION. **Invariant held:** one account never raises a concern |
| S2 | Two accounts, two conditions in one area (F5) | 2 prepared accounts, 4 writes, ~1 h | Both MODERATE; not linked (2 shared < 3); no case | **TAKE_EXTRA_CARE.** rules-2 prototype: TAKE_CARE |
| S2b | Two accounts, one condition | 2 accounts, 2 writes, ~1 h | One MODERATE signal | TAKE_CARE (the accepted k = 2 floor) |
| S3 | Owner + puppet | 2 accounts, 6 writes | 2 shared signals: MODERATE. From the **3rd** shared signal: linked, all LOW, 3 COORDINATION cases | The attacker's optimum is ≤ 2 shared signals per pair |
| S4 | Rotating ring, n = 3…6 (F6) | n accounts, 2(n−1) writes each, ~1 h | **n(n−1)/2 areas** (3, 6, 10, 15); Phase 7: **0 links, 0 cases** | That many areas read TAKE_EXTRA_CARE. Prototype coord-1 R1: **1 group of n, every size** |
| S5 | Padded pair (4 shared fakes in 2 far areas + confirming popular real places) | 2 accounts, 16–18 writes | Padding 4: linked, 4 cases. **Padding 5 = shared + 1: not linked, no case** | 2 areas TAKE_EXTRA_CARE. Prototype coord-1 R2: flagged |
| S13 | One pair builds a "recurring" history | 2 accounts, 8 writes, **~17 days** (3 weekly confirmed incidents) | RECURRING; the pair is never linked (incidents expire). Then **one** confirmed incident → rule (c) | TAKE_EXTRA_CARE. rules-2 does not change this |
| S12 | Withdraw-and-redo keeps a fake incident alive | 2 accounts, 4 writes a day | 4 days later still MODERATE and TODAY; a pair that does not refresh is gone after 3 days | TAKE_CARE + "confirmed incident today", indefinitely |

### 3.2 Lures and silencing

| # | Attack | Attacker cost | What the server did | What a viewer sees |
|---|---|---|---|---|
| S6 | Two fake safe places in an unknown area | 2 accounts, 4 writes, ~1 h | 2 MODERATE havens → information SOME (the cap of 4 does not stop 2) | NO_INFORMATION becomes **NO_CONFIRMED_CONCERNS** + "safe places nearby". Havens-alone prototype: LITTLE_INFORMATION |
| S7 | Fake safe place on a real concern | 2 accounts, 4 writes | Concern unchanged; conflict added | TAKE_CARE + "people disagree". **Invariant held** |
| S8 | Flag a real warning with k supporters (k = 2, 3, 5) | **max(2, k) accounts**, one flag each, minutes | Quarantined (hidden, reversible); a FLAGS case waits for a moderator | The warning disappears until a human restores it. With no active moderator, until the signal expires |
| S9 | Same with brand-new accounts | 2 new accounts | Nothing for 24 h (probation), then quarantined by the job | Visible 1 day, then hidden |
| S10 | "No longer true" on a real warning with k supporters (k = 2, 3) | **max(2, k) accounts**, ~31 min each | Retired and **deleted**; no case, no human review (Phase 7 design) | "Some say this has changed", then gone for good |
| S11 | Withdrawal as a detection oracle (F8) | 1 request after one job run | **409 "This signal is being reviewed"**: the ring learns it was detected | — |

### 3.3 Honest communities, the grid, scraping, limits

| # | Situation | Result |
|---|---|---|
| S14 | Two honest people report one street from neighbouring blocks (F7) | Two single LOW signals → LITTLE_INFORMATION. Same block → one MODERATE signal → TAKE_CARE |
| Grid | How often two honest reports of one street share a block (20,000 trials per length) | 100 m: **74.5 %** (neighbouring blocks: 100 %); 200 m: **53.1 %** (99.7 %); 300 m: **39.9 %** (95.0 %); 500 m: **25.2 %** (73.4 %). Today **60 % of honest pairs on a 300 m street never confirm each other** |
| S15 | An honest group (4 residents) backing the same places | 2 shared places: all HIGH → TAKE_EXTRA_CARE. **3 shared places: all 6 pairs linked → every real warning LOW, 3 COORDINATION cases → LITTLE_INFORMATION**. A moderator's DISMISS does not restore counting (linkage is automatic) |
| S16 | Sparse honest launch (synthetic model: 30 people, 32 areas, 1 report each, 30 % confirm) | 30 signals, 5 confirmed; 0 links; 0 cases; **prototype coord-1: 0 findings**. Areas: 11 NONE, 20 LITTLE, 1 SOME (the model decides these) |
| S17 | Scraping with one account | **6 area look-ups cover a whole ~5 km region** (32 area cells); 15 different areas per hour, then 429. Derived (not run for 24 h): **~60 regions a day** by area look-ups vs **6** by region packs |
| S18 | Scripted writes | 11th write within 10 min: 429 (accepted 10 min later); 11th report in a day: 429 |
| F1 | Two brand-new accounts confirm a fake | Nothing visible for a day; MODERATE after about 25 h |
| F2 | Sign-up limit | 10 per network address per hour, then 429; a second address has its own 10. Derived: 240 a day per address. It costs addresses, it does not stop a farm |
| Invariants | rules-1 on 5,000 random evidence sets (45,000 cell pictures) | **0 violations** of I1–I8 (concern only from confirmed evidence; outlook mapping; "no information" means nothing; exact categories; neighbourhood = maximum; havens change no level; unconfirmed signals change no level; a pattern alone raises nothing) |

## 4. What the measurements mean (findings and recommendations)

Ordered by harm. **Nothing below has been changed**; each change is for the user to approve (9C/9D).

1. **Honest groups lose their voice (S15).** Four people who report and confirm the same three places (a hostel, one
   street) are linked by Phase 7 and count as one person. Their real warnings stay "not yet confirmed", and moderator
   dismissal does not undo it. For a women's safety app this is probably the most important measured cost. There is no
   cheap fix: relaxing the rule inside one neighbourhood would also free the commonest puppet case (owner + puppet next
   door). **Recommendation:** decide in 9C whether a moderator's DISMISS of a COORDINATION case should lift the linkage for
   that signal ("a human confirmed these are separate people"). That needs a stored per-signal mark and its own review.
2. **Rotating rings scale undetected (S4) and padding escapes linking (S5).** The coord-1 prototypes catch every ring
   size tested and the padded pair, and stay quiet on the hostel (S15) and the sparse launch (S16). **Recommendation:
   9C** (triage only, no automatic effect, SHADOW first) plus the F8 fix (S11). **Done 2026-10-21, §8** (SHADOW by
   default; ACTIVE preconditions in §8.5).
3. **Two accounts reach "take extra care" in about an hour (S2).** **Recommendation:** `rules-2` (9D). The prototype
   turns S2 into TAKE_CARE and leaves strong incidents and patterns alone.
4. **Fake safe places make an unknown area read "no confirmed concerns" (S6).** This is a new finding. Phase 8 example 7
   accepted it for 9 havens; the measurement shows 2 accounts suffice. **Recommendation:** add the havens-alone cap to 9D
   (havens alone → LITTLE_INFORMATION + "safe places nearby").
5. **One pair can manufacture a "recurring" history in ~17 days (S13).** Pattern memory deliberately stores no people,
   so it cannot tell one pair repeating from real recurrence. rules-2 does not help. **Option** (not prototyped: it needs
   a production change): an episode only counts when it reached HIGH confidence (≥ 3 independent people), which raises
   the cost from 2 to 3 accounts without storing anyone.
6. **Real warnings can be hidden by max(2, supporters) flags (S8) or erased by as many "no longer true" answers (S10).**
   Hiding is reversible but needs a working moderator. **No moderator exists in the dev setup.** Erasure is permanent
   and never reviewed (a Phase 7 decision). **Recommendation:** an operational one (moderators must work the queue), plus
   a decision on whether retiring a *confirmed* signal should need more disputers than supporters or a human look.
7. **Area look-ups give a scraper ~10× the region-pack budget (S17).** Signals are public by design, but city-wide
   collection by one account in a day contradicts the Phase 6 intent ("no free browsing"). **Option:** a daily
   distinct-area cap on `/area` (for example 40). Most phone users would never notice it. It is the user's decision,
   because it changes a Phase 6 limit.
8. **Honest corroboration is wasted at block boundaries (grid, S14).** 60 % of honest pairs on a 300 m street never
   confirm each other today. The adjacent-merge candidate would merge ~95 % of them. **Recommendation: 9D** with its
   privacy conditions (design §7.5.2).
9. **Defences that held:** one account never raises a concern (S1); safe places never cancel a concern (S7); new
   accounts wait 24 h (S9, F1); the burst and daily limits work (S18); rules-1 keeps all eight invariants (0 violations);
   the owner + puppet case is linked from the 3rd shared signal (S3).

**What could NOT be measured honestly:**
- How often any attack happens in reality. There is no real data.
- Location spoofing, which is undetectable by design (no movement history). Every scenario already assumes the phone
  can claim any cell.
- Real network diversity for farming (one per-address mechanism shown, not a botnet).
- Whether a human moderator would recognise a ring.
- Timing attacks on the 10–30 min publication delay (needs real-world observation).
- Multi-instance limiter bypass (single instance).
- Load and performance.

## 5. Tests (actually run, 2026-10-21)

| Suite | Result |
|---|---|
| `mvn test` (whole backend) | **354 tests, 0 failures, 2 skipped** (the opt-in MySQL tests); was 316 before Phase 9 (+1 for 9C.0, +37 for 9A) |
| Phase 9A package alone | 37/37, **three separate runs, all green** (the publication delay is random, so stability was checked) |
| Android | **not run**: no Android file changed in 9C.0 or 9A |
| Lint | The backend has no lint step; no Android change, so `lintDebug` was not re-run |
| MySQL | 9C.0 live-verified (§1); 9A needs no migration |

## 6. Security and privacy review (2026-10-21)

- **9A is test code only.** No production class, migration, endpoint, permission, dependency or configuration changed.
  The prototypes cannot run in the server (test scope).
- **Synthetic data only:** open-ocean cells, RFC 5737 network addresses, random `@example.com` accounts, in-memory H2.
  The reports contain counts, outlooks and statuses, and no cell, coordinate or account.
- **Reports are build output** (`backend/target/attack-simulation/`), regenerated on every run and labelled SIMULATED.
  Quote them only with that label.
- **Live verification touched the dev database** (§1). Leftovers are listed there; no test signal remains, and the
  temporary moderators are back to USER.
- **Findings** are in §4. Two of them (F8 oracle, S15 linkage after dismissal) concern moderation privacy and fairness,
  and are inputs to the 9C design.

## 7. Not verified

- The candidate rules in production (they are prototypes; 9C/9D).
- Any real-world rate of the attacks above.
- 9C.0 on the emulator's moderator screens (the API and MySQL behaviour were verified; the screens are unchanged).
- Earlier phases' deferred checks (`HANDOFF.md` §5) are unchanged.

## 8. 9C: coordination check for moderators (2026-10-21)

### 8.1 What was built
- **`CoordinationPolicy`** (`community/service`, pure): coord-1 R1 SUPPORT_CORE and R2 EXCLUSIVE_PAIR, moved from the 9A
  prototype with the same thresholds (≤ 3 backers = low support; core ≥ 3 people and ≥ 4 confirmed signals; pair ≥ 3
  exclusive signals; never inside one neighbourhood). The case target is "most harm": the area where the group backs the
  most signals, then concerns before safe places, incidents first, then oldest.
- **`CoordinationCheck`** (`moderation/service`): builds the support graph in memory from every visible signal, from
  the clean-up job, at most once per `app.moderation.coordination.interval` (1 h).
  - **SHADOW** (the shipped default): opens nothing and logs only "Coordination check coord-1 (SHADOW): N group(s) for
    review, 0 case(s) opened".
  - **ACTIVE**: opens one COORDINATION case per group, within `max-cases-per-day` (5; it counts every COORDINATION case
    of the last 24 h), on the group's most harmful signal.
  - **OFF**: Phase 7 exactly.
  - A group is skipped while any of its signals has an open case, and for good (90 days) once a COORDINATION case on
    any of its signals was dismissed (the 9C.0 guard, extended to groups).
  - It is its only write, enforced by `CoordinationIsolationTest`.
- **`SignalVisibility`** (`community/service`): the one visibility definition, moved out of `CommunityService` so that
  moderation can read the same set without a dependency cycle. `CommunityService.visibleIn` delegates to it unchanged.
- **Withdrawal (a Phase 7 behaviour change):** `ModerationService.blocksWithdrawal` locks withdrawal for FLAGS and
  REREPORTED cases, and for flags on any open case, but **not** for a COORDINATION case without flags (F8: the 409
  told a ring it was detected; withdrawing removes the fake support anyway). `isUnderReview` is unchanged: any open case
  still stops a signal from becoming a pattern episode.
- **Moderator view:** `CaseDetail.coordination` gives `reasons` (LINKED_GROUP from Phase 7, SUPPORT_CORE, EXCLUSIVE_PAIR),
  `groupSize` (TWO / THREE_TO_FIVE / SIX_OR_MORE) and `groupPlaces` (TWO_TO_FOUR / FIVE_TO_NINE / TEN_OR_MORE). It is
  recomputed each time the case is viewed, for **any** case (a FLAGS case on a ring signal shows the pattern too), and
  is null when nothing holds. There are no cells of other signals and no number of areas.
- **Android** (`feature/moderation`): `CoordinationDto` → `CoordinationPattern` (unknown reasons dropped, none → no
  pattern). The case screen adds "Pattern behind this signal:", the reason in plain words, the bands, and always the
  guidance *"Backing each other is not proof that a report is false: friends, a household or a hostel can look like
  this. Look at the place and the reasons."* A COORDINATION case whose pattern has gone says so. The origin text is now
  "Backed by accounts that act together" (was "…only…", which is wrong for coord-1 groups).
- **Fix found by the tests:** `CoordinationCheck` keeps its last run in memory. A clock set back (NTP correction, or a
  test resetting the virtual clock) made it skip for hours. A last run "in the future" now no longer blocks a run.

### 8.2 Tests (actually run)
| Suite | Result |
|---|---|
| Backend `mvn test` | **366 tests, 0 failures, 2 skipped** (was 354). New: `CoordinationPolicyTest` 9, `CoordinationApiIntegrationTest` 6 (ACTIVE), `CoordinationIsolationTest` 3; the 6 coord prototype tests left `CandidateRulesTest` |
| 9A simulation | Now measures the **production** rules. S4: production R1 finds every ring of 3–6, and SHADOW returns `Run(1 candidate, 0 opened)` with 0 cases. S5: production R2 finds the padded pair. S15/S16: quiet. **S11 now asserts 204** (oracle closed, a deliberate change of a pinned measurement) |
| Android `testDebugUnitTest` | **446 tests, 0 failures** (was 443; `ModerationTest` +3: DTO reading, guidance always shown and per-code words, no accusatory word in any coordination string) |
| `assembleDebug` + `lintDebug` | OK; **0 errors, 87 warnings** (unchanged) |

### 8.3 Live check on MySQL 8.0 (SHADOW, the shipped mode): 11/11 PASS
The backend ran on the dev database with demo timing as command-line arguments only (probation and delay 0,
`purge-interval=PT20S`, `coordination.interval=0s`; mode left at its default SHADOW):
- **The coordination check:** a ring of 3 across three ocean areas made the real job log `Coordination check coord-1
  (SHADOW): 1 group(s) for review, 0 case(s) opened`, and **no case** was opened for the ring.
- **Phase 7 still works:** an owner + puppet got their 3 R0 COORDINATION cases.
- **Withdrawal:** during a COORDINATION review it answered **204**. On a ring signal an outsider then flagged it
  answered **409**.
- **The moderator view:** the moderator's view of that FLAGS case showed `SUPPORT_CORE / THREE_TO_FIVE / FIVE_TO_NINE`,
  and no other area of the ring.
- **Clean-up and logs:** clean-up left no test signal; the log had no ERROR/WARN and no test cell or e-mail.

Left in the dev DB: 7 `phase9.verify9c.*` accounts (all USER) and 4 DISMISS audit rows. **ACTIVE mode was not run on
MySQL** (only in the H2 integration tests).

### 8.4 Measured in ACTIVE (H2, `CoordinationApiIntegrationTest`) — the limitation below is fixed in §10
- **What a case is, and what it is not:** a ring of 4 gets **one** case, on its most harmful signal, over repeated
  runs. Nothing is hidden, viewers get byte-identical answers, and the reporter can withdraw (204).
- **Repeats:** after a DISMISS, two days later the group is not asked about again (no other signal of it is picked
  instead).
- **Budget:** 1 a day holds a second ring back until the next day.
- **Flags:** an outsider's flag re-locks withdrawal (409).
- **Measured limitation:** a case lists only the 2 people behind **its** signal. Pausing both quiets 5 of a ring of
  4's 6 areas; **the 6th (the pair not in the case) still reads TAKE_EXTRA_CARE.** The design's claim that "one
  decision neutralises a whole ring" was wrong for rings of 4 or more and is corrected in `PHASE9_DESIGN.md` §7.5.1.

### 8.5 Before switching to ACTIVE (preconditions)
1. **An active moderator.** None exists in the dev setup; cases nobody reviews only block pattern episodes. **Still
   open** (an operational precondition, not code).
2. ~~Group members as case participants~~ **Done (9C.2, §10).**
3. ~~The S15 decision~~ **Done (9C.1, Option B, §9).**
4. Ideally a few weeks of SHADOW log lines from a real deployment, to see the workload. **Still open.**

### 8.6 The honest-group question (S15): options and recommendation
**Problem (measured, S15):** 4 residents who back the same 3 places are linked by Phase 7 and count as **one person**.
Their real warnings stay "not yet confirmed" (LITTLE_INFORMATION instead of TAKE_EXTRA_CARE), and Phase 7 opens a
COORDINATION case per signal. Since 9C.0 a moderator's DISMISS stops the re-review, but it **does not restore
counting**: linkage is computed automatically every time.

| Option | What it does | Privacy | Abuse | Verdict |
|---|---|---|---|---|
| A. Keep as is | Nothing | — | — | Honest tight groups (the app's core users: hostels, colleges, a street of neighbours) keep losing their voice |
| **B. Human-confirmed independence, per signal** | When a moderator DISMISSES a COORDINATION case with NO_VIOLATION, **that signal's** supporters count as separate people for that signal. The mark is the closed DISMISSED case row that already exists (kept 90 days, deleted with the signal): **no migration, no new data**. Other signals of the group still need their own review | Nothing new stored; content-level, not people-level | A careless or malicious moderator could free a puppet group's signal. Mitigated: audited; a moderator who took part cannot decide; per signal only; expires with the case row (90 d) | **Recommended** |
| C. Group-level exemption ("these accounts are separate people") | Remember the people relationship, so all their signals count | **Stores a social fact about people at rest** | One dismissal frees a whole puppet group | Rejected |
| D. Loosen the independence rule (e.g. ignore co-support inside one neighbourhood, or link from 4 shared) | Fewer honest groups linked | — | The commonest puppet case (owner + puppet next door) passes; S3 shows linking at 3 is what stops it | Rejected |
| E. People declare "we are a group" | Self-service | Collects relationships | Trivially abused | Rejected |

**Recommendation: B, as a small follow-up (9C.1) once you approve it.** (Chosen by the user and **built: §9.**)
- `TrustService.Snapshot` would carry the set of signals with a DISMISSED COORDINATION case, and `assess` would use no
  support linkage for those signals. That applies on every path (`visibleIn`, retirement, crediting, confirmation
  marking, moderation).
- The DISMISS confirmation on a COORDINATION case would say: "These people will count separately for this place."
- Tests: S15 after a dismissal reaches TAKE_EXTRA_CARE again; the owner + puppet case after a (mistaken) dismissal
  counts too (documented risk); no effect on other signals of the group; the mark ends with the case row.
- **It changes how signals count, which is a user-visible safety rule, so it needs your decision.**

### 8.7 Security and privacy review (9C)
- **Its only effect:** coord-1 can only open a review case, and only in ACTIVE (`CoordinationIsolationTest` scans the
  source: one `saveAndFlush(new ModerationCase(…))`, no quarantine/standing/signal/delete/HTTP). It never changes a
  confidence or a weight. SHADOW is the default.
- **The detection oracle (F8) is closed;** flags still lock withdrawal. Accepted cost: a ring member can withdraw
  before a moderator acts, and so escape a strike. Removing the fake matters more.
- **Moderators see bands only:** no other cell, no number of areas (tested on H2 and live). Pseudonyms are unchanged.
- **Logs** hold counts only (verified live). The graph is in memory for one run; nothing is stored.
- **One visibility definition is preserved** (`SignalVisibility`); Safety Intelligence is unchanged (all Phase 8
  tests green; `IntelligenceIsolationTest` passes).
- **Cost:** reads every visible signal at most hourly, plus once per moderator case view (bounded by 600 moderator
  requests per hour). Fine at project scale; a city-scale deployment would need a cached last run. Documented.
- **No new dependency, migration, permission or endpoint;** one new response field (`coordination`), which older apps
  ignore.

### 8.8 Not verified (9C) — superseded
- ~~The new lines on the emulator's moderator screen~~ verified on the emulator in §14; TalkBack speech and dark-mode
  colours still not verified.
- ~~ACTIVE mode on MySQL~~ verified in §13.
- A real phone: not verified.

## 9. 9C.1: S15 Option B (a moderator's dismissal restores counting, per signal)

**Decision:** Option B of §8.6, named by the user on 2026-10-21. **Problem (S15):** four residents backing the same
three places are linked by Phase 7 and count as one person; their real warnings stay "not yet confirmed", and a
moderator who finds them fine could not change that.

**Behaviour as built:**
- When a moderator **DISMISSES** (NO_VIOLATION) a **COORDINATION** case, the people who were **counting** behind that
  signal at that moment count as separate people **on that signal only**: confidence, precision, "corroborated"
  crediting, "refuted" bookkeeping, flag quarantine threshold, pattern-episode marking, coordination input. Every path
  goes through `TrustService.Snapshot.supportOn(signalId, contributions)`, which `Snapshot.assess` uses; the two
  direct linkage uses in `CommunityService` (crediting, `recordUnsupportedReports`) call it too.
- **Phase 7 still links them** on every other signal (the decision is about one place, not about people).
- **Freeze (a refinement found while designing the tests):** the decision covers only the supporters counting at the
  dismissal (`counts_from <= closed_at`). Anyone who joins later is counted by the normal rule, so a mistaken
  dismissal of an owner + puppet signal cannot be topped up with more linked puppets to reach HIGH.
- **Only COORDINATION cases.** Dismissing a FLAGS or REREPORTED case says "the flags were wrong", not "these are
  separate people", and changes nothing about counting.
- **No new data, no migration:** the mark is the closed DISMISSED COORDINATION case row (`ModerationCaseRepository.
  lastClosedBySignal`, read with every snapshot). It ends when the case row is deleted (90 days) or with the signal
  (FK cascade); in practice the freed contributions expire first (category lifetime 3–60 days).
- **R2 is unchanged:** `CoordinationCheck` still passes Phase 7's people-level linkage to R2 (a pair linked on its
  other signals is still R0's job).
- **Android:** "Keep it" on a COORDINATION case confirms with *"The signal stays visible. The people backing it now
  will count as separate people for this place. Their other places are not affected."*
- **Dependency:** `TrustService` (community) now reads `ModerationCaseRepository` (a community → moderation repository
  read; `CommunityService` → `ModerationService` already existed). No cycle.

**Risk (documented):** a careless or malicious moderator can free a puppet signal. Limits: one signal, the people the
moderator saw only, audited (DISMISS row), a moderator behind the signal (or in its coordination group) cannot decide,
and it expires.

**Observable side effect:** a place that turns from "not yet confirmed (linked support counted once)" to "confirmed"
without new support tells a careful viewer that a moderator looked at it. No person, account or decision text is
revealed; accepted.

## 10. 9C.2: group members as case participants, and pausing a whole group in one decision

**Problem (measured in §8.4):** a coord-1 case listed only the people behind ITS signal, so pausing them quieted 5 of a
ring of 4's 6 areas. **Design and privacy note (written before building):**

| Question | Decision |
|---|---|
| Who is listed? | The **members** of the coord-1 group this signal belongs to right now who are not behind this signal, in a new `CaseDetail.groupMembers` list (a separate list, so older apps ignore it instead of showing them as flaggers). Recomputed at every view and every action |
| What does a moderator see about them? | Exactly what Phase 7 shows for participants: a **case-scoped pseudonym** (HMAC, per case, per server start), tier band, account-age band, "counting", link-group letter. Sorted by pseudonym (ids would leak creation order). No cell, signal, count of places or other case |
| Privacy trade | A moderator now sees pseudonyms and bands of people **not behind this signal**, and the list length reveals the exact group size (previously a band). Accepted: it is the minimum needed to act on a ring, adds no place information, and stays within one case |
| Who is a member? | `CoordinationPolicy.Group.members()`: people who back **at least two** of the group's places. See §11.2: the 2-core alone can pull in an honest pair through one shared place |
| How is a whole group paused? | `RESTRICT` with `"wholeGroup": true` (instead of `contributorRef`): every member not paused yet, **in one transaction from one consistent computation**, one audit entry + strike + appealable pause per person |
| Why not one by one? | Measured: pausing someone makes their support stop counting, so the group (and the list of the remaining members) can disappear from the case before the last member is paused (`pausingOnlyTheCasesTwoParticipants_…`). Individual pauses of listed members still work |
| Conflict of interest | Anyone in the group's core (members or not) counts as "took part": the queue labels the case and detail/actions answer 403 |

**Android:** "Others in this group" section (same participant rows, "In the same group, not behind this place"), a
"Pause everyone in this group" button only on an open coord-1 case the moderator may act on (`ModerationPolicy.
canRestrictGroup`), reason → length → confirmation *"Every account in this group will not be able to report, confirm
or flag for 7 days. People who only flagged this place are not included. They can all still see signals and use SOS
and every safety feature, and each of them can ask another moderator to review it. Backing each other is not proof on
its own."* Each "Pause" button now has its own TalkBack label ("Pause Person 70a0a6").

## 11. 9C.3: two coord-1 defects found while testing (fixed)

### 11.1 The "dismissed group is not asked again" guard was too narrow
- **Found by** the new legitimate-group test (friends who live and study together across town, the documented false
  positive). The 9C guard looked for a dismissed case only among the signals that make up the finding *today*. If a
  member withdrew from the dismissed place (one backer left), it dropped out of the finding, and the next run opened
  a new case on another place of the same group.
- **Fix:** look on every visible signal backed **only by the group's members**. **Reproduced first:** the test failed
  against the old guard (a second case opened) and passes with the fix.
- **Not changed (documented):** the mark still dies with the dismissed signal (FK cascade). Once that place has ended
  (3–60 days), a pattern that still holds can be put in front of a moderator once more.

### 11.2 A ring could pull honest people into its group
- **Found while designing** the guard's attack test. A ring member who confirms an honest pair's place (a place with two
  backers) gives each of them two partners, so the R1 2-core takes them in. Consequences in the old code: (a) the ring
  could **shelter behind an honest place a moderator had dismissed** (the guard saw a dismissed place inside the
  group) — reproduced: the ring got no case; (b) with 9C.2, "pause everyone" would have **paused the honest pair**.
- **Fix:** `Group.members()` (people who back at least two of the group's places) is used for the list, the pause and
  the guard; detection (which groups exist, the case target) is unchanged. Tests: `CoordinationPolicyTest.
  aPairPulledIntoARingThroughOnePlace_isInTheCore_butNotAMember`, `CoordinationApiIntegrationTest.
  aRingMemberWhoConfirmsAnHonestPairsPlace_…`, `…aRingCannotShelterBehindAnHonestPlaceAModeratorFoundFine` (failed
  against the old guard, passes now).
- **Residual:** an attacker who confirms TWO places of the same honest pair makes them members. Mitigations: the
  moderator sees each member's bands and link letters, the guidance text, the confirmation; every pause is individual
  in the audit log and appealable.

## 12. Tests (actually run, 2026-10-21)

| Suite | Result |
|---|---|
| Backend `mvn test` | **388 tests, 0 failures, 2 skipped** (the opt-in MySQL tests); was 366 |
| New backend | `DismissedCoordinationApiIntegrationTest` 5 (S15 restored one signal at a time to TAKE_EXTRA_CARE + crediting; mistaken owner+puppet dismissal frees one signal but later linked accounts count once; effect ends with the case row; FLAGS dismissal frees nothing; a moderator behind the signal gets 403), `CoordinationFalsePositiveApiIntegrationTest` 6 (ACTIVE: hostel, friends across town = documented FP with one case only, city-wide genuine problem, power user, couple, colleagues), `CoordinationApiIntegrationTest` +9 (now 15: whole-ring pause quiets all 6 areas, ring of 6 quiets all 15, pausing one by one is fragile, group member pausable by pseudonym, insider moderator 403, `wholeGroup` validation, padded pair (R2) paused without touching honest places, absorbed honest pair neither listed nor paused, ring cannot shelter behind a dismissed honest place, documented one-neighbourhood evasion), `CoordinationPolicyTest` +2 |
| 9A simulation | S15 re-measured with production timing: 3 shared places → LITTLE_INFORMATION; after a moderator dismisses the 3 cases → **TAKE_EXTRA_CARE**; Phase 7 still links all 6 pairs; no case reopened (SIMULATED) |
| Android `testDebugUnitTest` | **450 tests, 0 failures** (was 446; `ModerationTest` +4: group members read only as such, older server sends none; whole-group pause only on an open coord-1 case; Option B confirmation text; the ViewModel sends one `wholeGroup` decision and nothing impossible) |
| `assembleDebug` + `lintDebug` | OK; **0 errors, 87 warnings** (unchanged) |

## 13. Live check on MySQL 8.0, coord-1 in ACTIVE (2026-10-21): 25/25 PASS

Backend on the dev database with **command-line arguments only** (`application.properties` unchanged, shipped mode
SHADOW): probation and delay 0, `purge-interval=PT30S`, `coordination.mode=ACTIVE`, `coordination.interval=0s`,
`max-cases-per-day=3`, `registrations-per-address=1000` (the script registers ~36 synthetic accounts). Driven through
the API by a scratchpad script; rows checked with `mysql.exe` (password read from `backend/.env`, never printed).
Open-ocean cells, `phase9.verify9c1.*@example.com` accounts.

| # | Check | Result |
|---|---|---|
| A0–A4 | Ring of 4 marks 6 areas TAKE_EXTRA_CARE; four rings with budget 3: exactly 3 cases, one ring waits; the largest ring has one; its case is on the most harmful signal; nothing hidden | PASS |
| A5–A7 | Moderator view: 2 participants + 2 group members, `SUPPORT_CORE / THREE_TO_FIVE / TEN_OR_MORE`, no other area of the ring, no e-mail; members carry pseudonym + bands only | PASS |
| A8–A11 | One `wholeGroup` decision: all 4 paused, 1 strike each, 4 audit entries; all 6 areas back to NO_INFORMATION; the case says the pattern is gone | PASS |
| A12–A14 | DISMISS of a ring's case: not asked about again over two more job runs; the budget (dismissed cases included) still holds the fourth ring back | PASS |
| B1–B5 | Honest street (4 residents, 3 places): 3 R0 cases, LOW, LITTLE_INFORMATION; after one DISMISS that place HIGH, the others LOW, TAKE_CARE; withdrawal during an open COORDINATION case 204; no reopen; Option B persists across job runs | PASS |
| C1–C3 | Every test contribution withdrawn (cases went with the signals); no other case opened or changed; no MODERATOR/ADMIN left | PASS |
| D1–D2 | Backend log: no test cell or e-mail; no ERROR/WARN. Log lines are counts only, e.g. `Coordination check coord-1 (ACTIVE): 4 group(s) for review, 3 case(s) opened` | PASS |

**Honest note:** the first attempt crashed after check A6 because of a bug in my PowerShell script (a one-element array
returned from a function becomes a string, so the case id was one character). The backend was not at fault. The crashed
run's 30 synthetic signals (no decision had been made) were deleted by SQL, the script fixed (and given a try/finally
clean-up), and the whole check re-run from a clean state.

**Left in the dev database:** 56 `phase9.verify9c1.*` and 12 `phase9.emu9c1.*` accounts (all USER, random passwords
never stored; 8 of them carry a 7-day pause and 1 strike from the checks), audit rows 8 RESTRICT + 3 DISMISS
(append-only, 365 days). No test signal, case or flag. The 4 pre-existing FLAGS cases are unchanged.

## 14. Emulator check (Medium_Phone_API_37.0, 2026-10-21)

New debug build installed; backend ACTIVE as in §13 (budget default 5); `adb reverse tcp:8080`; the emulator's own
Phase 7 test account was a temporary MODERATOR (reset afterwards). Synthetic ring of 4 + a linked pair (3 places).
Screens read from the accessibility tree (`uiautomator`).

| # | Check | Result |
|---|---|---|
| E1 | Profile shows Moderation for the moderator; the queue lists the ring's case and the pair's 3 Phase 7 cases ("Backed by accounts that act together") | PASS |
| E2 | Ring case: "Pattern behind this signal:", the SUPPORT_CORE sentence, "Group: 3 to 5 accounts.", "Places only this group backs: 10 or more.", the guidance; 2 participants; **"Others in this group"** with 2 rows "In the same group, not behind this place · standard · new account"; **"Pause everyone in this group"** | PASS |
| E3 | FLAG_SECURE: a device screenshot of the case screen is completely black (0 non-black sampled pixels) | PASS |
| E4 | Pause everyone: reason list → 7 / 30 days → the new confirmation text → Record; afterwards "not shown at the moment", **"The pattern that opened this case is no longer present."**, both participants "paused · not counting", no group section, no group button | PASS |
| E5 | Pair case: LINKED_GROUP sentence, "Group: 2 accounts.", no group section, **no** group button | PASS |
| E6 | "Keep it" on the pair's case shows the **Option B text**; after Record: "This case is closed (dismissed).", the place "Confirmed by people nearby", 2 independent people, the pair still "acts with group A"; the pair's other cases stay "Not yet confirmed" | PASS |
| E7 | Night mode: the case screen re-renders with all its content (accessibility tree) | PASS (structure); **colours/contrast NOT VERIFIED** (FLAG_SECURE blocks every screenshot) |
| E8 | Accessibility tree: every row and button labelled; after the fix each "Pause" button reads "Pause Person xxxxxx" | PASS |
| E9 | TalkBack speech walkthrough | **NOT VERIFIED** (TalkBack is installed, but its navigation could not be driven reliably over adb in earlier sessions; not attempted) |
| E10 | Galaxy A23 | **NOT VERIFIED** |

Restored afterwards: night mode off, `adb reverse` removed, emulator stopped, backend stopped, test data withdrawn.

## 15. Security and privacy review (9C.1, 9C.2, 9C.3; 2026-10-21)

- **coord-1 still only opens a case.** `CoordinationIsolationTest` passes unchanged (one write, no quarantine,
  standing, signal, contribution or flag access, no HTTP, no scheduling). The guard and `members()` changes are reads.
- **Every effect on people is a human decision:** Option B needs a moderator's DISMISS; a group pause needs a
  moderator's RESTRICT. Both are audited per decision (per person for pauses), both respect the conflict-of-interest
  rule (now also for anyone in the group's core), pauses are appealable to a different moderator, and a pause never
  touches SOS, Safe Walk, the Safety Circle or evidence (unchanged Phase 7 tests pass).
- **No new data at rest, no migration, no new endpoint, permission or dependency.** Option B's mark is an existing
  row; group membership is recomputed in memory per request. No social graph or "these accounts are separate" fact is
  stored (Option C stays rejected).
- **Moderators:** pseudonyms (case-scoped HMAC) and bands only; no other cell or place count; the exact group size is
  now visible through the list length (accepted, §10). Member order is by pseudonym, not by id.
- **Ordinary users:** client DTOs unchanged (`CommunityApiIntegrationTest` allow-lists pass). Option B can make a
  linked place visibly "confirmed" without new support (§9, accepted).
- **Abuse reviewed:** mistaken dismissal (frozen to the people seen; one signal; audited; expires); piggybacking on a
  dismissed honest place (closed by `members()`, tested); pulling honest people into a ring's pause (closed for one
  shared place, residual for two, §11.2); a moderator inside a ring (403, tested); probing (no client-visible change from
  a coordination case; withdrawal still 204, tested live).
- **Logs:** counts only (verified live twice: no cell, no e-mail, no ERROR/WARN).
- **Cost:** each trust snapshot runs one more indexed query (`lastClosedBySignal`, by signal ids); the moderator queue
  now computes coord-1 once per page view (as the case view already did). Fine at project scale; a city-scale
  deployment would cache the last run (unchanged recommendation).

## 16. Remaining limitations and not verified (9C)

- **Not verified:** TalkBack speech and dark-mode colours of the moderator screens (FLAG_SECURE blocks screenshots);
  the Galaxy A23; a real moderator team's workload (no real deployment).
- coord-1 can be evaded by rings with ≥ 4 backers per signal or inside one neighbourhood (measured, by design).
- The shared budget: `max-cases-per-day` counts Phase 7's R0 COORDINATION cases too (seen live: 3 R0 cases used a
  budget of 3).
- The "found fine" mark dies with the dismissed place (3–60 days); a pattern that still holds can then come back once.
- §11.2 residual (two shared places).
- Option B's greedy counting order: a later, higher-weight linked newcomer can make a freed signal count FEWER people
  (never more). Conservative, documented.

## 17. Viva notes

- **What / why:** before adding any AI, measure what the existing rules cost an attacker, with the real API and a
  virtual clock. The result is concrete numbers (two accounts, one hour, "take extra care") instead of opinions.
- **Key classes:** `AttackHarness` (real API, attacker's bill), `CandidateRules` (prototypes; removed in 9D once every
  prototype was production code), `PictureInvariantPropertyTest` (property testing), `GridBoundaryMeasurementTest`
  (Monte Carlo with a fixed seed).
- **Likely questions:** *Why the real API and not a model of it?* (a model can drift; the real code cannot). *Why
  assert weaknesses?* (to pin today's cost, so a change that makes an attack cheaper fails a test). *What is the most
  important finding?* (honest groups can lose their voice; rings and padding evade Phase 7). *Is this ML?* (no; it is
  the evaluation any ML would need first, and it showed that rules fix the measured problems).
- **9C.1 / 9C.2 in one minute:** a moderator who finds an honest group fine can now make THAT place count them as
  separate people (no stored exemption; the closed case row is the mark; later joiners are not freed), and a
  moderator who finds a ring can pause all of its members in one audited, appealable decision. *Why not remember
  "these people are separate"?* (it would store a social fact about people and free a puppet group everywhere). *Why
  one decision for a whole group?* (pausing members one by one makes the pattern vanish before the last of them, which
  was measured). *How do you avoid pausing honest people a ring touched?* (a member must back at least two of the
  group's places; one shared place is not membership — an attack found and fixed in testing).

## 18. 9D-1: the neighbour join (2026-10-21)

**Problem (F7, measured):** two honest people reporting one 300 m street from neighbouring ~150 m blocks shared a block
only 39.6 % of the time; otherwise they stayed two "not yet confirmed" signals for ever.

**Changed from the design (§7.5.2 merged at REPORT time into a visible neighbour).** Built instead: the clean-up job
(`CommunityService.joinLoneReports`, first step of `purge`) moves a **lone** report (the signal's only contribution, a
REPORT that already **counts**, never confirmed before, no flag, no open case, a concern category — never a safe place)
as a **CONFIRM** (same `counts_from`) onto an **older**, visible, same-category signal in a block sharing a **side**
with its own, that the reporter has not answered or flagged (best target: most independent people, then oldest).
Why: (1) a write-time merge is a **silent probe**: the reporter's own view (`/mine`, `yourContribution`, a 409 on
confirm) would reveal at once, before anything counts, that a single report sits next door, and a pending probe can be
withdrawn without trace; joining only after the report counts makes the only effect the k = 2 reveal a CONFIRM already
gives. (2) It also joins honest pairs who were **both still pending** (reports minutes apart, or new accounts in
probation), which a write-time merge misses. (3) "Older only" means a newer (fake) signal can never capture an honest
report. A re-sent report (outbox) next to the caller's own confirmation adds nothing (`confirmsNextDoor`).
**Side, not corner (measured, `GridBoundaryMeasurementTest`, 20 000 trials, SIMULATED geometry):**

| | same block (before) | + side blocks (9D) | + all 8 neighbours |
|---|---|---|---|
| honest pair, 200 m street | 53.5 % | **90.3 %** | 99.7 % |
| honest pair, 300 m street | 39.6 % | **78.6 %** | 94.6 % |
| two DIFFERENT places 300 m apart joined | 0 % | **2.0 %** | 17.9 % |
| two different places 200 m apart joined | 0 % | 37.7 % | 80.6 % |

**Attacks (`NeighbourJoinSimulationTest`, production timing, SIMULATED):** J1 piggyback = exactly a CONFIRM; J2 capture
impossible; J3 side-block probes reveal nothing before counting (also live, L1b/L1c); J4 withdraw/re-report never
counts one account twice; J5 other categories / safe places never join; J6 one account never confirms itself; J7 a ring
using joins is still found by coord-1. **F11 (new finding, PRE-EXISTING since Phase 6, not fixed):** a report on the
EXACT hidden block of a single report merges at once and the reporter's own view shows "your report" on it; withdrawn,
it leaves no trace. Cost: up to 32 reports per ~1 km area (3 per account per day). A CONFIRM reveals the same block
publicly within 10–30 min, so the extra harm is silence. Fix needs per-contribution withdrawal handles (API change):
future improvement.
**Honest cases:** S14 side blocks now TAKE_CARE (corner blocks still LITTLE_INFORMATION, known limitation); J8 hostel:
4 residents reporting one lane from their own blocks become one HIGH signal; three shared places link them as in S15
(3 COORDINATION cases; Option B frees them) — the join makes that reachable from neighbouring blocks (moderator
workload); J9 two new accounts in probation join once both count; J10 an expired incident never confirms a new one.

## 19. 9D-2: rules-2 in SHADOW — not promoted

`AreaIntelligencePolicy.Model.RULES_2` = rules-1.1 + rule (b) needs ≥ 3 independent people across the confirmed,
non-aging concerns (people re-counted across signals with Phase 7 linkage; pairs a signal already counted separately —
Option B — stay separate). `app.intelligence.rules-2-mode=OFF|SHADOW|ACTIVE` (default **SHADOW**):
`AreaModelShadow` compares both rule sets over every area with a visible signal at most once per
`rules-2-shadow-interval` (1 h) and logs counts only (live: "8 area(s) compared, 1 would read less cautiously").
ACTIVE is tested (`RulesTwoActiveApiIntegrationTest`) but is the user's decision.
**Measured (SIMULATED):** S2 pair EXTRA → CARE; S4 rings of 3–6: 3/6/10/15 EXTRA areas → 0; S5 padded pair → CARE;
S13 pattern poisoning unchanged (rule c); **J11 an honest pair also loses EXTRA (→ CARE); an honest trio keeps it**.
Property test: 45 000 cell pictures, 0 violations (never more cautious; only the concern level differs).
**Why it stays SHADOW:** it downgrades every pair-backed "extra care", honest or fake; the simulation shows both sides
but not their real-world proportion; there is no deployment, so no data. Phase 8's asymmetry (false reassurance is
worse than false caution) argues against weakening warnings without evidence; the cost of not promoting is limited
(attacker pairs get "extra care" instead of "care", both amber). Evidence needed: weeks of shadow counts from real use
plus 9B moderator outcomes for rule-(b) areas.

## 20. 9D-3: havens-alone cap — ACTIVE (rules-1.1)

**Finding:** in rules-1 the outlook NO_CONFIRMED_CONCERNS ("No confirmed concerns have been reported here recently")
could ONLY be produced by safe-place reports (any confirmed concern makes it TAKE_CARE), and two accounts sufficed
(S6). Safe places say where help is, not whether there are concerns. **rules-1.1:** without a confirmed concern, havens
count at most once → information ≤ LITTLE → LITTLE_INFORMATION + "Places to go for help are known nearby". The server
therefore never sends NO_CONFIRMED_CONCERNS any more (the phone keeps its text for compatibility). It only removes
reassurance, never hides a concern, so it ships. Measured: S6 with 2 accounts (2 havens) and 4 accounts (12 havens):
LITTLE_INFORMATION; live L3; emulator E3. `modelVersion` is now `rules-1.1` (the phone ignores it).

## 21. Tests (actually run, 2026-10-21)

| Suite | Result |
|---|---|
| Backend `mvn test` | **423 tests, 0 failures, 2 skipped** (the opt-in MySQL tests), final full run on the final code; was 388 after 9C |
| New backend | `AdjacentJoinApiIntegrationTest` 9 (incl. positive controls for every exclusion), `AdjacentJoinTimingApiIntegrationTest` 3, `NeighbourJoinSimulationTest` 11, `RulesTwoTest` 6, `RulesTwoActiveApiIntegrationTest` 2, `GeoCellTest` +1, `GridBoundaryMeasurementTest` +1, `PictureInvariantPropertyTest` +1 (I9–I11), `IntelligenceIsolationTest` +1, `AreaIntelligencePolicyTest` +1; `CandidateRules(Test)` removed (prototypes are production code) |
| Android | `testDebugUnitTest --rerun` **452, 0 failures** (+2 `CommunityTextsTest`); `assembleDebug` OK; `lintDebug` **0 errors, 87 warnings** (unchanged) |

## 22. Live check on MySQL 8.0 (dev DB v14, no migration): 19/19 PASS
Command-line demo timing only (delay 60 s fixed, probation 0, purge 20 s, shadow interval 0 s; rules-2 mode default
SHADOW). Join not before counting (L1a–d), joined after (L1e–h, MySQL rows REPORT + CONFIRM), re-send adds nothing (L1i),
corners stay apart (L2), havens-only LITTLE_INFORMATION (L3), viewers still get TAKE_EXTRA_CARE while the shadow logs
counts (L4a–c), join counted in the log (L5), clean-up (C1), log has no cell/e-mail and no ERROR/WARN (D1–D2).

## 23. Emulator (Medium_Phone_API_37.0): PASS
E1 report dialog shows the new sentence; E2 the phone's report joined the older side-block signal: "Take care … Confirmed:
Poor lighting", radar "1 place to take care", "You said you saw this too"; E3 havens-only area "Little community
information here." + "Places to go for help are known nearby."; E4 Withdraw on the joined report works.
**Not verified:** TalkBack speech, dark mode (no layout or colour change in 9D), Galaxy A23.

## 24. Security and privacy review (9D)
No migration, endpoint, permission, dependency or new stored field; the join moves one row of the reporter's own
(time unchanged) and deletes an empty signal (FKs cascade). No silent probe added (§18); F11 is pre-existing. Report-time
guard reads only the caller's rows. Shadow: read-only, counts only (source scan `theRulesTwoShadow_onlyReadsAndCounts`).
Isolation tests pass (community/intelligence never reach SOS, Safe Walk, Safety Circle, evidence). Client DTO allow-lists
unchanged. Logs counts only (live). Residual: a CONFIRM racing the job's insert hits the unique key and rolls back that
clean-up run (retried 15 min later).

## 25. Known limitations, not verified, future improvements
Corner blocks never join; places 300–335 m apart can join (2 %); a joined report reads "you saw this too"; two signals
that each already have 2+ people in neighbouring blocks stay separate (pre-existing double count); joins add Phase 7
co-support (close honest groups linked after 3 places, as S15); a joined report frees one per-area report slot; F11;
rules-2 has no real-world evidence; havens-only busy areas read "little information"; in-memory per-instance shadow
cadence; the lone-report query scans all signals every 15 min (fine at project scale). **Not verified:** TalkBack,
Galaxy A23, real-world rates, multi-instance. Future: fix F11; 9B counters to evaluate rules-2.

## 26. Viva notes (9D)
*Why join in the job, not at report time?* (before a report counts, nothing may depend on what is next door: no silent
probe; and pending honest pairs still meet). *Why side blocks only?* (measured: doubles honest pairs, 2 % false joins
at 300 m vs 18 % with corners). *Why is rules-2 not on?* (no evidence; it weakens honest pairs' warnings too). *Why
ship the havens cap without evidence?* (it only removes reassurance that two accounts could fake).

## 27. 9B: outcome counters (2026-10-21)

> **Complete: reviewed and accepted by the user on 2026-10-21; frozen.** Do not change without a new brief: V15 (never
> edit an applied migration), the metric/dimension vocabulary, after-commit + best-effort recording, the 104-week
> retention, the ADMIN-only report, and the rule that nothing deciding what people see may read a counter
> (`EvaluationIsolationTest`). Still NOT VERIFIED: real-world rates (no deployment), multi-instance behaviour, a real
> week change and long-run retention on MySQL (§34).

**Why it exists (design §7.5.4, F4, P1, P6).** Nothing outlived a signal: after 3–60 days every row is gone by design, so
nobody could ever say how often confirmed warnings later turned out false, how precise each automatic case trigger is,
how many cases expire unreviewed, or whether rules-2 should be promoted. 9B keeps **weekly, system-wide counts** of
those outcomes, and nothing else, so the rules can be evaluated and the ML gates (G1–G3) can be checked with numbers.

**Does it still fit after 9C/9D?** Yes, and it is now more needed than at design time: 9D left rules-2 in SHADOW with
"evidence needed: weeks of shadow counts from real use plus 9B moderator outcomes" (§19), but its only record was an
hourly log line; 9C left coord-1 in SHADOW with "ACTIVE needs real SHADOW counts" (§8.5). Four parts of the design no
longer matched the code and were adjusted (§28).

**What was built** (backend only; no Android, client-API or permission change):
- **V15:** `evaluation_counters` (`week_start` Monday UTC, `metric` closed list, `dimension` upper case + underscores,
  `model_version`, `occurrences`; CHECKs make a cell, an id or a non-Monday date unstorable) and
  `moderation_cases.coordination_rule` (`LINKED_GROUP` | `SUPPORT_CORE` | `EXCLUSIVE_PAIR`, COORDINATION cases only).
- **`evaluation` package:** `OutcomeRecorder` (typed methods taking only enums; counts are buffered per transaction
  and written **after commit** in a transaction of their own; **best effort**: any failure is logged as an exception
  class name and swallowed; never reads), `WeeklySnapshot` (first clean-up run of each ISO week, one transaction, reads
  `SignalVisibility.setEverywhere` = the one visibility definition, plus the retention of 104 weeks), `EvaluationReport`
  (pure: rates, Wilson 95 % intervals, minimum counts, gate G1), `EvaluationService` (the only reader), DTOs,
  `config/EvaluationProperties` (`app.evaluation.enabled`, `retention-weeks`).
- **Hooks (one line each):** `CommunityService` (signal started, joined, first confirmed, every deletion: expired /
  retired / withdrawn, plus any case still open on a deleted signal), `ModerationService` (case opened with its rule,
  closed REMOVED/DISMISSED, signal removed with its reason; `evaluationReport` for ADMIN), `CoordinationCheck` (SHADOW
  finding once per group and week; ACTIVE case opened with its rule), `CommunityPurgeJob` (snapshot + retention count
  in its log line).
- **`GET /api/admin/evaluation?weeks=N`** (1–104) in the existing `AdminController`: ADMIN only (SecurityConfig + a
  database role check per request), counters, rates, G1 status (`docs/API.md`).

## 28. Deviations from the design (and why)

| Design (§7.5.4, §11) | Built | Why |
|---|---|---|
| Version CHECK `^(-\|[a-z]+-[0-9]+)$` | `^(-\|[a-z]+-[0-9]+([.][0-9]+)?)$` | The active model since 9D is `rules-1.1`; the design's CHECK rejects it |
| Recompute a COORDINATION case's rule at decision time ("no schema change needed") | V15 stores `coordination_rule` on the case when it is opened | After a 9C.2 whole-group pause the pattern is gone before the case closes (`PHASE9.md` §14 E4), so the decision would count against nothing. Reproduced: `CoordinationCountersApiIntegrationTest` (fails when the rule is not stored, mutation-checked). A code on an existing row, deleted with the case |
| `SIGNAL_MERGED_ADJACENT`, yield = merged ÷ (started + merged) | `SIGNAL_JOINED_NEIGHBOUR`, yield = joined ÷ started | 9D built a clean-up-time join: a joined report first started its own signal |
| `INVARIANT_VIOLATION` | not built | 9D built no runtime invariant guard (property tests instead), so nothing can produce it |
| `COORDINATION_SHADOW` "when SHADOW would have opened a case" | once per group per ISO week (in-memory set of the group's signal ids, cleared each week) | coord-1 runs hourly and SHADOW never opens a case, so a literal count is group-hours (168 a week for one ring) |
| — | `SNAPSHOT_TAKEN` (1 per snapshot week) | Tells an empty week apart from a week without a snapshot; also the "taken this week" mark that survives restarts |
| Column `value` | `occurrences` | `VALUE` is a keyword in H2, the test database |
| `metric IN (…)` under the table collation | `metric` and `coordination_rule` binary-collated | The MySQL dry run showed `'support_core'` passing an `IN` CHECK under `utf8mb4_unicode_ci` (fixed before V15 was applied anywhere) |
| `AdminEvaluationController` | an endpoint in `AdminController` | Same ADMIN surface as the audit log; no second controller for one method |
| Shares from summed weekly snapshots | snapshot shares from the **latest** snapshot only | Summing weeks counts a 60-day safe place up to 8 times and would make Wilson intervals falsely narrow |
| Publication rule "values below 10 shown as fewer than 10" | applies to publication; the ADMIN report shows exact counts and carries the rule in `notice` | The operator holds the raw rows anyway (design §9 residual) |

## 29. What is counted

| Metric | Dimension | Counted when (after commit) |
|---|---|---|
| `SIGNAL_STARTED` | group | a report creates a new signal row (a merge or re-send does not) |
| `SIGNAL_JOINED_NEIGHBOUR` | group | the 9D neighbour join moves a lone report |
| `SIGNAL_CONFIRMED` | group | `reached_confirmed` is first set (once per signal row) |
| `SIGNAL_ENDED` | `{group}.{EXPIRED\|RETIRED\|WITHDRAWN}_{CONFIRMED\|UNCONFIRMED}` | a signal row is deleted (expiry in the clean-up or on a new report, "no longer true" retirement, last withdrawal); a joined lone report is not an end; a removed signal's tombstone is not counted again |
| `SIGNAL_REMOVED` | `{group}.{CONFIRMED\|UNCONFIRMED}_{reason}` | moderator REMOVE; confirmed = the Phase 8 mark or MODERATE/HIGH at that moment |
| `CASE_OPENED` / `CASE_UNREVIEWED` | `FLAGS`, `REREPORTED`, `LINKED_GROUP` (Phase 7 R0), `SUPPORT_CORE`, `EXCLUSIVE_PAIR` (version `coord-1`), `COORDINATION` (older case, rule unknown) | a case is created / its signal is deleted while it is still open |
| `CASE_CLOSED` | `{trigger}.{REMOVED\|DISMISSED}` | moderator decision |
| `COORDINATION_SHADOW` | coord-1 rule | SHADOW finds a group none of whose signals was counted this week |
| `SNAPSHOT_TAKEN`, `VISIBLE_SIGNALS`, `AREAS_BY_OUTLOOK`, `AREAS_BY_INFORMATION`, `SHADOW_PICTURE` | see `docs/API.md` | first clean-up run of the week |

Rates in the report: confirmation yield, false-confirmation rate (**lower bound**: only what moderators caught),
retirement after confirmation, join yield (per group); trigger precision (≥ 10 decided cases) and unreviewed share (per
trigger); confirmed share of visible signals and rules-2 shadow disagreement (latest snapshot). Below the minimum:
`NOT_ENOUGH_DATA`, never a number. G1: `NOT_MET` or `COUNTS_MET_PRIVACY_REVIEW_REQUIRED` (never "met").

## 30. Tests (actually run, 2026-10-21)

| Suite | Result |
|---|---|
| Backend `mvn test` | **453 tests, 0 failures, 2 skipped** (the opt-in MySQL tests), final full run on the final code (one test file was re-formatted during that run and re-run alone: 7/7); was 423 after 9D. Every 9C/9D suite unchanged and green (`Coordination*`, `DismissedCoordination*`, `AdjacentJoin*`, `RulesTwo*`, `NeighbourJoinSimulationTest`, `AttackSimulationTest`) |
| New backend (30) | `EvaluationReportTest` 8 (Wilson values, minimums, inconsistent windows, dimension parsing incl. `_UNCONFIRMED` not counted as confirmed, latest-snapshot shares, G1 blocks/current week/moderators, vocabulary-only), `EvaluationCountersIntegrationTest` 13 (lifecycle once each, withdraw/retire, join ≠ end, moderator decisions per trigger, unreviewed on expiry and on withdrawal during a COORDINATION case with the rule stored, coord-1 SHADOW once per group per week and opens nothing, commit/rollback, refused request counts nothing, switched off touches nothing, snapshot = what viewers see + rules-2 shadow + once per week, retention 104 weeks, ADMIN-only 401/403/400, report shape/allow-list/no cell, e-mail or id), `EvaluationCounterFailureTest` 1 (counter store throwing on every call: report, confirm, flag, DISMISS, withdraw, clean-up, snapshot all succeed), `EvaluationIsolationTest` 7 (no safety/moderation reference from evaluation; safety and intelligence never read it; outside evaluation only the recorder, the report only on the ADMIN path; the counter table only in evaluation and the recorder never reads; no scheduling/HTTP; V15 columns and CHECKs; no ML runtime or AI API in `pom.xml`), `CoordinationCountersApiIntegrationTest` 1 (ACTIVE: coord-1 case counted against SUPPORT_CORE at opening and at REMOVE after a whole-group pause; **mutation-checked**: fails when the rule is not stored) |
| Changed backend | `CoordinationIsolationTest` (constructor argument; the recorder may only be used for `coordinationShadow`/`caseOpened`, still one write) |
| Android `testDebugUnitTest --rerun` | **454 tests, 0 failures** (was 452; +2 `NoMlRuntimeTest`, the design's Android twin, never built before: no ML/AI library in the version catalog or `build.gradle.kts`, no model file in `src/main`). No main Android source changed |
| `assembleDebug` + `lintDebug` | OK; **0 errors, 87 warnings** (unchanged; no main Android source changed) |

## 31. V15 on MySQL 8.0

- **Constraint dry run** (scratch `p9_` tables with the same definitions, dropped afterwards; MySQL DDL cannot be rolled
  back, so this is the established method of V13/V14): valid rows accepted (5 counter rows incl. `rules-1.1` and
  `coord-1`, an increment, 5 case rows). **First run: 14/14 counter violations rejected but 3/4 case-rule violations**:
  `'support_core'` passed the `IN` CHECK (case-insensitive collation). V15 was changed (binary collation for `metric`
  and `coordination_rule`) **before it was applied anywhere**, and the re-run rejected **19/19**: geohash `tdr1v9q`,
  `TDR1V9Q` (digits), lower case, three parts, a space, empty, unknown metric, lower-case metric, versions `rules` /
  `Rules-2` / `tdr1v9q`, negative value (insert and update), a Tuesday, a duplicate key; rule on a FLAGS and a
  REREPORTED case, unknown rule, lower-case rule. No scratch table left.
- **Applied by Flyway** at backend start: "Successfully applied 1 migration … now at version v15" (0.117 s). The 4
  pre-existing FLAGS cases keep `coordination_rule` NULL. **Dev DB at v15; next migration V16.**

## 32. Live check on MySQL 8.0 (dev DB v15): 21/21 PASS

Backend on the dev database, **demo timing as command-line arguments only** (`application.properties` unchanged:
delay and probation 0, `purge-interval=PT20S`, `coordination.interval=0s`, `rules-2-shadow-interval=0s`,
`registrations-per-address=1000`; coord-1 and rules-2 in their shipped SHADOW mode). Driven through the API by a
scratchpad script; rows read with `mysql.exe` (password from `backend/.env` via `MYSQL_PWD`, never printed);
open-ocean cells; `phase9.verify9b.*@example.com` accounts; clean-up in `finally`.

| # | Check | Result |
|---|---|---|
| S0a–c | V15 applied (`15:1`); exactly the 5 columns, `metric` binary; `moderation_cases.coordination_rule` binary, no pre-existing case has a rule | PASS |
| S1 | `/api/admin/evaluation`: no token 401, USER 403, MODERATOR 403, ADMIN 200, `weeks=0` 400 (ADMIN set by SQL, moderator by the admin API) | PASS |
| S2a–b | report + confirm + a merging re-report: `SIGNAL_STARTED` +1 right after commit; `SIGNAL_CONFIRMED` +1 from the job, once over several runs | PASS |
| S3 | withdrawal: `SIGNAL_ENDED INCIDENT.WITHDRAWN_UNCONFIRMED` +1 | PASS |
| S4a–b | flag: `CASE_OPENED FLAGS` +1; moderator DISMISS: `CASE_CLOSED FLAGS.DISMISSED` +1 | PASS |
| S5 | 9D join still works (one signal left, REPORT + CONFIRM) and `SIGNAL_JOINED_NEIGHBOUR` +1 | PASS |
| S6a–b | owner + puppet: 3 COORDINATION cases stored with `coordination_rule = LINKED_GROUP` on MySQL, `CASE_OPENED LINKED_GROUP` +3; withdrawal still 204 during the case (9C), `CASE_UNREVIEWED LINKED_GROUP` +1 | PASS |
| S7 | ring of 4 (coord-1 SHADOW): `COORDINATION_SHADOW SUPPORT_CORE coord-1` +1 while the log said "1 group(s) for review, 0 case(s) opened" on 7 runs; no case | PASS |
| S8a–c | viewers still get `rules-1.1` / TAKE_EXTRA_CARE for a ring area; snapshot re-taken (this week's snapshot rows deleted on purpose): taken once, TAKE_EXTRA_CARE 7, rules-2 LESS_CAUTIOUS 6, confirmed conditions 19; **same numbers as 9D's shadow log line** ("14 area(s) compared, 6 would read less cautiously"); not re-taken by the next run | PASS |
| S9 | ADMIN report: 26 rates, G1 `NOT_MET`, `activeModerators` 10 (moderators of earlier live checks within 30 days), no test cell, e-mail or signal id | PASS |
| S10 | all 25 stored rows: upper-case dimension, Monday week | PASS |
| C1 | clean-up: no test signal left, no test account above USER | PASS |
| Log | 0 WARN/ERROR after start-up; no test cell (prefix scan) and no e-mail in the log | PASS |

**Honest note:** the first attempt scored 13 PASS / 1 FAIL and then stopped. Both were faults of my script, not of the
backend: (1) S2b looked before the job's first run (the job starts 60 s after boot, `initialDelay`); the counters showed
the signal counted at that first run; (2) the ring loop used PowerShell's `4..3`, which counts **down**, so a member
tried to confirm its own report and the server correctly answered 409. The `finally` clean-up ran (no signal left). The
script was fixed (poll for the first run; `i` in `0..2`) and the whole check re-run from scratch: 21/21.

**Left in the dev database:** 29 `phase9.verify9b.*` accounts (all USER, random passwords never stored); audit rows
2 ROLE_GRANTED + 2 ROLE_REVOKED + 2 DISMISS (append-only, 365 days). No test signal, case or flag. **The 25 counter rows
written by the check were deleted after the backend was stopped** (they were test data; the next start takes a fresh
snapshot). The 4 pre-existing FLAGS cases are unchanged (`coordination_rule` NULL). Backend stopped; no emulator or
`adb reverse` was used.

## 33. Security and privacy review (9B)

- **PR1 (no new personal data at rest):** V15 has five columns, none for a person, place, signal, case, address or
  time finer than a week; the CHECKs reject cells, digits, ids, free text and non-Mondays (dry run). The recorder's
  methods take enums only and re-check the same patterns before writing; the snapshot's dimensions are enum names.
  `coordination_rule` is a rule code on an existing row and is deleted with it.
- **PR2 (nothing new reaches clients):** no client DTO changed (the Phase 6/7/8 allow-list tests pass unchanged); no
  new response field, header or timing difference on any client endpoint (counts are written after the response's
  transaction commits, in the same request thread, and failures are swallowed). No Android change except a test.
- **PR4 (no viewer data):** no counter comes from a request that only reads; the snapshot reads data, not views.
- **PR5 (same set):** the snapshot reads `SignalVisibility.setEverywhere`, the one visibility definition, and the
  active model (tested: the snapshot's outlook equals what `/area` returns).
- **PR6 (isolation):** `evaluation` references no safety package and no moderation; no safety package, and not
  `intelligence`, references `evaluation`; outside `evaluation` only the recorder (and, on the ADMIN path only, the
  report) is used; the counter table is touched only inside `evaluation`, and the recorder never reads it, so **no
  rule, picture, confidence or threshold can depend on a counter** (no feedback loop, no automatic tuning). Source
  scans: `EvaluationIsolationTest`; coord-1 still writes only a case (`CoordinationIsolationTest`).
- **PR7 / logs:** log lines hold counts and exception class names only (live: no cell, no e-mail, no WARN/ERROR). The
  coord-1 SHADOW de-duplication holds signal ids **in memory** for at most one ISO week (never written or logged); a
  restart forgets it (a group may then be counted twice that week: over-count, documented).
- **PR9 (purpose):** counts of the system's own rules and decisions only; the endpoint is ADMIN-only (401/403 tested
  and live), has no export and no per-area view; config can switch it off, which also deletes the rows.
- **Abuse:** clients cannot write counters (server events only). An attacker can inflate counts only by doing the
  counted things, which every existing limit already governs. Counter failures (missing table, deadlock, full disk)
  cannot block a report, an answer, a flag, a withdrawal, a moderator decision or the clean-up
  (`EvaluationCounterFailureTest`); SOS, Safe Walk, Safety Circle and evidence code is untouched.
- **Residual (honest):** the operator can read small weekly counts (they already hold the raw rows); a week with one
  event in a sparse deployment is identifiable to someone who knows that week's events (publication rule: system-wide,
  "fewer than 10"). `activeModerators` is a number of people, computed from the audit log the ADMIN can already read.

## 34. What 9B is worth, limitations, not verified

**Frank value assessment.** The mechanism is sound and cheap, but **today it measures nothing real**: the dev DB holds
only test and demo data, there is no deployment and no active moderator, so every rate in the report is
`NOT_ENOUGH_DATA` and G1 is `NOT_MET` (correct). Its value is that, from the first real week, the questions 9C and 9D
left open can be answered with numbers instead of opinion: is coord-1 precise enough for ACTIVE (`TRIGGER_PRECISION`
for `SUPPORT_CORE`/`EXCLUSIVE_PAIR` vs `FLAGS`), would rules-2 change much (`SHADOW_PICTURE`), does the join help
(`JOIN_YIELD`), do moderators keep up (`UNREVIEWED_SHARE`), are confirmed warnings being erased by "no longer true"
(`RETIRED_AFTER_CONFIRMATION`). Parts with little value at project scale and therefore NOT built: per-area density
bands, a counter of coord-1 candidates held back by the ACTIVE budget, quarantine counts, a dashboard.

**Limitations:** counts are best effort and after commit (a lost counter is a lower bound; a crash between commit and
counter write loses that count); rates over a window ignore lag (events straddling the edges → `INCONSISTENT`, never a
number above 100 %); `SIGNAL_CONFIRMED` is noticed by the 15-minute clean-up, so a signal confirmed for less than one
run is never counted (same as the Phase 8 mark); "confirmed" for an ended signal is the Phase 8 mark only; the coord-1
SHADOW set is per instance and in memory; the snapshot is one sample per week (state at the first run of the week);
G1's "month" is 4 weeks (stricter); the written privacy review G1 needs is not checkable by software.

**Not verified:** any real-world rate (no deployment); multi-instance behaviour; behaviour across a real week boundary
on MySQL (the week change is tested with the virtual clock on H2 only); long-run retention on MySQL (tested on H2).
No emulator or phone check: nothing user-visible changed.

**Viva notes.** *Why counters and not raw history?* (raw history is exactly what the privacy design deletes; a weekly
system-wide number cannot say where or who). *Why after commit and best effort?* (a rolled-back report must count
nothing, and measurement must never break a safety app). *Why can't a counter hold a cell?* (CHECK: upper case and
underscores only; a geohash is lower case and digits; tested on MySQL). *Why store the rule on the case?* (a
whole-group pause removes the pattern before the decision; recomputing would credit the decision to nothing). *Is this
ML?* (no: it is the measurement any ML would need first; it shows G1 is not met).
