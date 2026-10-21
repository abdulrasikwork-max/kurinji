# Phase 9: AI/ML Intelligence: design ("evidence before AI")

Written 2026-10-21 in a **design-only session**. No code, migration, API, dependency, configuration or database state was
changed. Every decision here was made on the architect's own judgment under the user's brief of 2026-10-21 ("full
autonomy / engineering freedom; design only"). As `CLAUDE.md` §3 requires, implementation starts only when the user gives
the go-ahead. It then runs in gated sub-phases 9A–9D (§21), with a build and test between each.

Status of everything in this document: **planned**. Findings in §1.3 come from reading the code. Where a finding was not
reproduced by running a test, it says so.

**Status update 2026-10-21:** 9C.0 (F9) is fixed and live-verified, and **9A is implemented and tested** (test code
only). Results, deviations from this design, the measured attack costs and new findings (havens-alone lure, honest groups
counted once) are in `docs/PHASE9.md`. **9C is implemented and tested** too (coord-1 in SHADOW by default, no withdrawal
lock for COORDINATION cases, moderator pattern texts; `PHASE9.md` §8), and since the second 9C session also S15 Option B,
group members as case participants with a whole-group pause, and two guard fixes (`PHASE9.md` §9–§16; implementation
complete, accepted by the user on 2026-10-21).

**Status update 2026-10-21 (9D):** **implemented, compiled, tested, live-verified on MySQL, emulator-verified; awaiting
the user's review** (`PHASE9.md` §18–§26). Deviations from this design, each with its reason there:
1. **Adjacent merge (§7.5.2) became a job-time "neighbour join":** a lone report joins only once it COUNTS (a
   write-time merge was a silent probe of a single report's hidden block and missed pairs that were both pending),
   only into an OLDER signal (no capture), and only across a block SIDE, never a corner (measured).
2. **The active rules changed too:** `rules-1.1` = rules-1 + the havens-alone cap (§24's "not in the design", found by
   9A S6), shipped because it only removes reassurance. `rules-2` (§7.5.3) was built on top of it and runs in
   **SHADOW only** (an hourly count in the log, not the weekly 9B snapshot, which does not exist); **not promoted**.
3. **No runtime `PictureInvariants` guard (§13):** I1–I5 (and the new I9–I11) are property tests over 5 000 random
   worlds for both rule sets; a runtime re-check of deterministic, tested code adds no protection.
4. **`AreaIntelligenceModel` interface (§7.2):** not built as an interface; a `Model` enum selects the rule set inside
   the pure policy (two rule sets differing in one rule do not need a plug-in seam).
**Status update 2026-10-21 (9B):** **implemented, compiled, tested, V15 applied and constraint-checked on MySQL,
live-verified; complete: accepted by the user on 2026-10-21, frozen** (`PHASE9.md` §27–§34). Deviations from §7.5.4 (each with its reason there):
1. **V15 version CHECK** accepts one dot (`rules-1.1`, active since 9D); the design's `^(-|[a-z]+-[0-9]+)$` would have
   rejected the active model.
2. **V15 also adds `moderation_cases.coordination_rule`**: the design planned to recompute a COORDINATION case's rule at
   decision time, but a 9C.2 whole-group pause removes the pattern before the case is closed, so the decision would be
   counted against nothing (reproduced in a test).
3. **Metrics:** `SIGNAL_MERGED_ADJACENT` became `SIGNAL_JOINED_NEIGHBOUR` (9D built a job-time join, so the yield is
   joined ÷ started); `INVARIANT_VIOLATION` dropped (9D built no runtime guard: nothing to count); `SNAPSHOT_TAKEN` added
   (an empty week must be told apart from a missing snapshot); `COORDINATION_SHADOW` counts each group once per week
   (coord-1 runs hourly: the design's wording would count group-hours).
4. **Column `value` is `occurrences`** (VALUE is an H2 keyword); `metric` and `coordination_rule` are binary-collated
   (the MySQL dry run showed an `IN (...)` CHECK under the case-insensitive default accepts `support_core`).
5. **The weekly snapshot uses the active model** (rules-1.1) and the shadow comparison only while rules-2 is SHADOW;
   snapshot shares in the report use the latest snapshot only (summing weeks would count long-lived signals repeatedly).

---

## 0. Decisions at a glance

| Question | Decision |
|---|---|
| Is ML justified **now**? | **No, not for anything a user sees.** There is no real data: every row in the dev database was created by us for tests and demos. There is no ground truth about places, and the privacy design deletes history after 3–60 days on purpose. A model trained on this would be fabricated evidence |
| Is ML **ever** justified here? | **In one place only:** ordering review work for human moderators (possible coordinated fake support), and only after numeric data-readiness gates (§4.4). Place pictures, routes, Safe Walk, SOS and the Safety Circle stay deterministic **permanently** |
| What does Phase 9 build? | An **evidence layer** that any future ML needs, and that the current rules need today: **9A** an adversarial simulator that measures what each rule costs an attacker; **9B** privacy-safe outcome counters (system-wide, weekly, no cells, no people), so the rules can be evaluated at all; **9C** deterministic coordination triage `coord-1` for moderators, plus a fix for a Phase 7 re-open bug; **9D** evidence-quality fixes found during inspection (merging adjacent concern reports; a stricter "take extra care" rule evaluated in shadow) |
| Approach | Hybrid, ordered by authority: **hard invariants → deterministic rules (the only source of user-facing output) → measurement and shadow evaluation → (future, gated) learned models that may only advise humans** (§7.1, the "authority ladder") |
| Where it runs | Backend Java only. **Nothing ML on the phone. No Python, no ML library and no new dependency in Phase 9.** Future training, if a gate is ever met, runs offline. Its output is a small, reviewed points table that pure Java scores |
| Uncertainty | The Phase 8 two axes are unchanged. Every measured rate comes with an interval, or "not enough data" below a minimum count. Any future model abstains when unsure, and then the rules decide |
| Hard boundaries | No Phase 9 component has a code path to SOS, Safe Walk, the Safety Circle, evidence or a person's standing. Coordination triage can do exactly one thing: open a case for a human. Enforced by source-scan tests (Appendix A) |
| Not built, ever or for now | Danger scores, hotspot prediction, heat maps, learned routes, behaviour models of users, chatbots/LLMs, voice/scream/shake ML, on-device models, third-party AI APIs (§23) |

---

## 1. What was inspected, and what the code actually does

### 1.1 Inspected
`CLAUDE.md`; `docs/HANDOFF.md`, `PHASE6.md`, `PHASE7.md`, `PHASE8.md`, `PHASE8_DESIGN.md`, `PLATFORM_CONSTRAINTS.md`;
migrations V12, V13, V14; backend `community/*` (`SignalPolicy`, `Independence`, `TrustPolicy`, `FlagPolicy`,
`TrustService`, `CommunityService`, `CommunityPurgeJob`, `GeoCell`, `SignalCategory`), `intelligence/*`
(`AreaIntelligencePolicy`, `AreaIntelligenceService`, `PatternPolicy`, `RegionIntelligenceService`, DTOs),
`moderation/*` (`ModerationService` automatic part and decisions, enums, DTOs), `application.properties`, `pom.xml`;
backend tests (`IntelligenceIsolationTest`, `CommunityTrustApiIntegrationTest`, `ModerationApiIntegrationTest`
inventory); Android `feature/intelligence/*` (models, `CorridorBriefPolicy`, DTOs, tests), `feature/community`
(`CommunityViewModel.onReport`), `feature/safewalk/domain/MovementPolicy`, `gradle/libs.versions.toml`.

### 1.2 Facts Phase 9 builds on (verified in code)

| Fact | Where |
|---|---|
| A signal is one row per (7-char cell, category). Reports merge only on that **exact** pair | `CommunityService.report` → `findByCellAndCategory` |
| Only `counts_from` (server time + random 10–30 min delay + 24 h probation for new accounts) is stored per contribution. Rows are deleted at the end of the category lifetime (incident 3 d, condition 30 d, haven 60 d) | V12, `SignalCategory`, `CommunityService.purge` |
| Confidence: MODERATE ⇔ ≥ 2 independent supporters and net weight ≥ 4. HIGH ⇔ ≥ 3 and ≥ 7 | `SignalPolicy` |
| Independence links two accounts only when they share ≥ 3 live signals **and** that is ≥ half of the smaller account's activity. Pairs sharing fewer than 3 are not even loaded | `Independence`, `SignalContributionRepository.sharedSupportPairs(... MIN_SHARED)` |
| rules-1 ELEVATED: a STRONG current incident, **or** ≥ 2 confirmed non-aging concerns in one area cell, **or** a confirmed incident where incidents recur | `AreaIntelligencePolicy.cell` |
| Automatic COORDINATION case: a visible signal whose only support is one linked group | `ModerationService.maintain` |
| Any open case blocks withdrawal (409 "This signal is being reviewed") and stops the signal from becoming a pattern episode | `CommunityService.withdraw`, `endedNaturally`, `ModerationService.isUnderReview` |
| Closed cases are kept 90 days, the audit log 365 days. The audit log has no cell and no signal id | V13, `application.properties` |
| `modelVersion = "rules-1"` is a constant sent in pictures and packs. The phone reads the field and ignores it | `AreaIntelligencePolicy.MODEL_VERSION`, Android `IntelligenceDtos` |
| Backend dependencies: web, JPA, security, resource server, validation, Flyway, MySQL, H2/test. Android: AndroidX, Material, Retrofit/OkHttp/Gson, DataStore, coroutines, play-services-location. **No ML library anywhere** | `pom.xml`, `libs.versions.toml` |

### 1.3 Findings that shape Phase 9

| # | Finding | Evidence | Consequence |
|---|---|---|---|
| F1 | The Phase 8 "extension point" was only partly built: `AreaIntelligenceModel` and shadow mode **do not exist**. The extension point is the codes-only contract plus the `modelVersion` string | grep finds no interface or shadow code | Do not build the interface speculatively. Build it in 9D, when a second implementation (`rules-2`) exists |
| F2 | **The dataset is effectively empty.** The dev DB holds only `phase6/7/8.*@example.com` test and demo accounts, a few demo signals near one Bangalore cell, an empty `area_patterns`, and no MODERATOR or ADMIN | `HANDOFF.md` §4, `PHASE7.md` §8 | Nothing can be trained or validated on real data. Any "accuracy" would describe our own test scripts |
| F3 | **History is structurally absent, by design.** Signals, contributions, flags and cases die within 3–60 days (cases 90 d after closing). Only decayed numbers outlive them (`area_patterns`, halving standing counters) | V12–V14, purge code | No historical training set can exist unless new derived data is stored. Phase 9 stores only counters (§7.5.4) |
| F4 | **No outcome is ever measured.** Nothing records how often confirmed signals were later removed as false, how precise automatic case triggers are, or how often cases expire unreviewed. Phase 8's thresholds are "judgment calls" (`PHASE8.md` §7) and cannot be evaluated today | code inspection | 9B counters |
| F5 | **The cheapest concern attack costs two accounts.** Two accounts past probation each back the same two condition signals in one area cell. They share 2 signals, which is below 3, so they are not linked. Both signals become MODERATE, and rule (b) gives TAKE_EXTRA_CARE | `Independence`, `SignalPolicy`, `AreaIntelligencePolicy` (derived from the rules, not run) | This is the accepted k = 2 floor for "take care". For "take **extra** care" it is cheap. Candidate `rules-2` (§7.5.3) |
| F6 | **Rotating rings scale undetected.** With n accounts where each pair backs at most 2 signals in its own area, no pair is ever linked. Each pair marks one area TAKE_EXTRA_CARE: n = 6 gives 15 areas, about 10 contributions per account, within every daily limit. The COORDINATION rule never fires (each signal has 2 independent supporters). Only locals flagging each signal (2 independent flaggers per signal) stop it | same, derived, not run | 9C `coord-1` |
| F7 | **Honest corroboration is lost to cell boundaries.** The phone reports its own 7-char block. The server merges only on the exact block. Two honest people reporting one dark street from neighbouring blocks create two single-person signals that each stay UNCONFIRMED forever: no concern level, no region-pack entry, no pattern episode | `CommunityViewModel.onReport`, `CommunityService.report` | Sparse data is made sparser by the system itself. 9D adjacent merge (§7.5.2) |
| F8 | **The withdrawal lock is a detection oracle for coordination cases.** A ring operator can create a ring, wait one job run, try to withdraw, and read 409 = "detected" | `withdraw` → `isUnderReview` | coord-1 cases (and Phase 7 COORDINATION cases) should not lock withdrawal (§7.5.1, a change to a Phase 7 decision) |
| F9 | **Phase 7 bug: a dismissed COORDINATION case is reopened on the next job run.** (Update 2026-10-21: **reproduced by a test and fixed as 9C.0**, see `HANDOFF.md` §7.) `maintain()` checks only `!hasOpenCase`. After DISMISS the case is CLOSED, the condition still holds, and a new OPEN case is created about every 15 minutes until the signal expires. Each reopen also re-locks withdrawal. No test covers it (COORDINATION appears only in an "opened" assertion in `CommunityTrustApiIntegrationTest`). FLAGS and REREPORTED cases do not loop (flags are resolved; the tombstone is cleared) | `ModerationService.maintain` lines 176–191, `openCase` | Fix first (9C.0). The same guard applies to coord-1 |
| F10 | Isolation is enforced for `intelligence` ↔ safety packages, but a future `evaluation` package would be unguarded | `IntelligenceIsolationTest` | Extend the scans (§18) |

No conflict was found between `CLAUDE.md` and the code. `HANDOFF.md` §6 described the Phase 8 extension point as
including shadow mode. F1 corrects that.

---

## 2. Problem definition

What would make Safety Intelligence genuinely better for a woman checking her area or her way home? After Phase 8 there
are six concrete problems:

| # | Problem | Who is harmed | Today |
|---|---|---|---|
| P1 | Thresholds are unvalidated | Everyone: a rule may be too strict (hides warnings) or too loose (lets fakes through), and nobody can tell | No outcome data (F4) |
| P2 | Attack costs are unknown in numbers | Viewers misled by fake concerns or fake safe places; reporters' trust | Prose risk statements only |
| P3 | Coordination detection is pairwise only | Areas stigmatised by rings; people lured by fake havens | F6 |
| P4 | Honest corroboration is lost to cell boundaries | Viewers see "little information" where two people actually warned | F7 |
| P5 | Most areas will show "no or little information" | Viewers, especially in low-adoption areas | Inherent, honestly labelled by the information axis |
| P6 | There is no way to ever evaluate a learned model | Future maintainers | F3, F4 |

P5 has **no honest technical fix**. Filling empty areas with predictions (smoothing, interpolation, "similar
neighbourhoods") would invent evidence where nobody observed anything. Phase 8 §6.4 rule 4 forbids that, and Phase 9
keeps the rule. The only real fixes are more people reporting, and not throwing away what honest people already report
(P4).

---

## 3. Is ML justified? (per problem)

| Problem | What ML would need | Do we have it? | Verdict |
|---|---|---|---|
| "Is this place dangerous?" (risk prediction) | Ground truth about places (independent incident records) | **No, and we should not collect it** (police data encodes bias and stigma; a victim's emergency must never become a place label, `PHASE6.md` §7) | **Not an ML problem for SafeSphere. Rejected permanently** |
| P1 thresholds | Outcomes that outlive the 3–60 day rows | No | **Statistics** over new counters (9B), with intervals. A human changes thresholds through code review. No automatic tuning |
| P2 attack costs | Adversarial evaluation | No | **Simulation** (9A). Not ML |
| P3 coordination beyond pairs | Graph structure (we have it, live) and labels (we do not) | Structure yes, labels no | **Deterministic graph rules** now (9C). A learned ranking (M1) only after gates G1–G3 |
| P4 fragmentation | Exact cells (we have them) | Yes | **Deterministic merge rule** (9D). Not ML |
| P5 sparse areas | More people | — | Nothing. The information axis states it honestly |
| P6 future evaluation | Retained labels | No | Counters now. De-identified examples only after collection gate G1 |

**Honest summary for the viva.** Machine learning is a way to estimate parameters from data. SafeSphere has almost no
data, no ground truth for its main question, and a privacy design that deliberately forgets. Under those conditions
the strongest engineering is to measure the rules we already have, attack them in simulation, fix the weaknesses found
with explainable rules, and define exactly what a future model must prove before it may advise a human. That is what
Phase 9 does.

**Why there is no toy model for the demo.** A model trained on our few dozen test accounts would learn only our own test
scripts. Showing it as "AI" would be the pretending the brief forbids. The viva demonstration is the simulator: with
clearly labelled synthetic data, it shows what each rule costs an attacker and what each change buys.

---

## 4. Data availability and limitations

### 4.1 Inventory

| Data | What it is | Real data today | Retention | Usable as a label? |
|---|---|---|---|---|
| `place_signals`, `signal_contributions` | Place observations and who backs them | None (demo/test only) | 3/30/60 days after the last contribution | No (they are the input, not an outcome) |
| `contributor_standing` | Private counters | Test only | Halves every 90 d | No (using it as a label would make the model learn "punished people") |
| `signal_flags`, `moderation_cases` | Abuse flags; review cases | Phase 7 smoke/demo rows | With the signal; closed cases 90 d | **Case outcomes (REMOVE vs DISMISS) are the only labels in the system.** Rare, biased (only flagged or triggered signals get reviewed) |
| `moderation_actions` (audit) | Who decided what, when | Demo rows | 365 d | Outcome counts only. Never per person |
| `area_patterns` | One decayed number per area and group | **Empty** | ≤ ~240 d | No |
| Emergencies, locations, journeys, escalations, circle alerts, evidence | Personal safety data | Test rows | Various | **Forbidden as input or label, permanently** (isolation, §9) |
| Area/region look-ups | Viewer requests | Never stored | — | **Never** (it would be a viewer location log) |

### 4.2 Structural limitations (true even with many users)
1. **No ground truth.** A confirmation is itself the signal. We never learn whether something "really" happened.
2. **Deliberate forgetting.** At any moment at most ~60 days of raw data exist. Longer-lived knowledge exists only as
   decayed or aggregate numbers.
3. **Reporting bias.** Data says where *SafeSphere users* report, not where things happen. A model would learn "areas
   with active users are dangerous".
4. **Selection bias of labels.** Only signals that were flagged or triggered a rule ever get a moderator outcome.
5. **Phone-asserted location.** It can be spoofed and cannot be verified (`PHASE6.md` §6).
6. **Tiny per-cell counts.** Most ~1 km areas will hold zero or one signal at a time.
7. **No demographics** (correct for privacy). Fairness can only be checked by *area density band*, not by groups of people.
8. **Rules are public.** Detection works by cost, not secrecy (Phase 8 §12).

### 4.3 Rough volume estimate (an estimate, not a measurement)
At 1,000 active users in one city, each sharing about one report a week, a city of several thousand ~1 km cells sees well
under one new signal per cell per week. Most areas stay NO_INFORMATION or LITTLE_INFORMATION. Coordination cases would
be a handful per month. Per-cell prediction is hopeless at this scale. Even a moderator-triage model would take over a
year to reach gate G2.

### 4.4 Data-readiness gates (all must be met, and the user must decide; nothing is automatic)

| Gate | Unlocks | Conditions | Why these numbers |
|---|---|---|---|
| **G1 collection** | Start storing de-identified triage examples (§8.4) | 9B counters show ≥ 20 decided COORDINATION+FLAGS cases per month for 3 consecutive months; ≥ 3 active moderators; a written privacy review | Below this, storage would never reach G2 and would be data kept "in case" |
| **G2 training** | Train candidate M1 offline | ≥ 150 positive and ≥ 150 negative labelled examples, from ≥ 3 moderators, over ≥ 6 months; a held-out final 3 months with ≥ 40 of each | About 10 ordinal features → ≥ 10–15 events per parameter for a stable regularised logistic regression; ≥ 40 per class keeps a 95 % interval on precision within about ±0.15 |
| **G3 promotion** | M1 may order the coord-1 queue | Beats coord-1 precision@budget by ≥ 0.10 absolute, with a bootstrap 95 % CI (by month) that excludes 0; no simulation scenario gets cheaper for attackers; honest-community scenarios flagged no more often than by coord-1; 8 weeks in shadow within the moderator budget; written decision in `PHASE9.md` | Promotion must be evidence, not enthusiasm |

**Expected outcome, stated now:** a single-city student deployment will probably never reach G2. In that case M1 is
never built, and that is the correct result.

---

## 5. Threat model (Phase 9 additions to `PHASE8_DESIGN.md` §11, T1–T18)

| # | Threat | Defence | Residual |
|---|---|---|---|
| T19 | **Label poisoning by a moderator** (biased REMOVE/DISMISS) | Labels used only after G1/G2; ≥ 3 moderators required; content outcomes only (never restrictions or appeals); audit log reviewed by ADMIN | A colluding moderator majority |
| T20 | **Feedback loop**: a model picks what gets reviewed, so labels exist only for its picks | coord-1 and Phase 7 triggers keep opening cases independently; M1 trains only on rule-opened cases, or weights its own picks (§10.2) | Rules have blind spots too |
| T21 | **Probing the detector** | coord-1 cases have **no client-visible effect**: no quarantine, no confidence change, no withdrawal lock (F8). Responses stay identical | Final REMOVE is visible (after a human decision, slow) |
| T22 | **Moderator flooding** (triggering many cases to bury real ones) | Daily budget for coord-1 (default 5), worst-harm first; FLAGS cases unaffected; budget use counted | Budget can be filled by an attacker. Real flags still open their own cases |
| T23 | **Honest community misread as coordination** (hostel, friends walking together) | Groups within one neighbourhood excluded; reason codes plus guidance "backing each other is not proof a report is false"; nothing hidden meanwhile; DISMISS rate per rule counted; a rule with poor precision is switched off | Moderator automation bias (T29) |
| T24 | **Counters as a side channel** | System-wide weekly counts; no cell, user, signal id or time finer than a week; the schema rejects cells (§7.5.4); operator-only; publication rule | The operator already has the raw data |
| T25 | **Simulation results misrepresented as real performance** | Every simulator output starts "SIMULATED — not real-world data"; never used to promote a model on its own (G3 needs real data) | Human misquotation |
| T26 | **Model artefact tampering / supply chain** | No ML runtime; artefacts are small JSON in the repo with SHA-256 in the model card; code review; no remote model download | Repository compromise |
| T27 | **Leaks through shadow outputs** | Shadow results are only counted, never returned or logged with cells | — |
| T28 | **Function creep** (policing, profiling, "risky neighbourhood" lists) | No export endpoint; purpose stated per stored item; isolation; no per-area output beyond what Phase 8 already shows | Future operators' choices (documented) |
| T29 | **Automation bias** ("the system flagged it, so remove it") | Moderators see reasons, never scores; DISMISS restores nothing because nothing was hidden; guidance text; counters show DISMISS rates | Human judgment |
| T30 | **Abuse of the adjacent merge** (9D) | Merges only into a VISIBLE signal (no timing oracle on pending reports); stored as CONFIRM; havens never merged (lure guard); the same limits as answers; independence unchanged | Equal to today's "I saw this too" |
| T31 | **Ring evades coord-1 thresholds** (bigger rings, 4+ supporters per signal) | Thresholds raise cost, they do not prevent. Larger rings are more likely to be flagged by locals, and are linked by Phase 7 once they overlap ≥ 3. Simulator measures the new cost | Patient, well-separated sybil farms (the root residual since Phase 6) |

---

## 6. Candidate approaches compared

| Approach | Ground truth needed? | Data needed | Privacy cost | Attack surface | Explainable? | Safety risk | Verdict |
|---|---|---|---|---|---|---|---|
| Supervised place-risk model (e.g. boosted trees per cell) | Yes, none exists | Large, per cell | Needs history we delete | Optimisable target | Low | Stigma; "AI says dangerous" | **Rejected permanently** |
| Hotspot forecasting (Poisson/Hawkes, ARIMA, LSTM) | Yes | Long time series | Requires event history (Phase 8 option C, rejected) | Feedback loops | Low | Predictive-policing harms | **Rejected** |
| Spatial smoothing (KDE, kriging, empirical-Bayes shrinkage) | No | Moderate | Low | Spreads one fake over neighbours | Medium | Invents evidence in empty cells | **Rejected** (Phase 8 §6.4 rule 4) |
| Unsupervised anomaly detection on contributors (Isolation Forest, LOF, autoencoder) | No | Moderate | In-memory possible | Adversarial | Low for moderators | **Honest small communities are the anomalies** (base-rate problem at launch) | **Rejected for production.** Research only after G2 |
| Graph ML for sybils (SybilRank, node2vec, GNN) | Partly / social graph | Large graphs | Needs a social graph we do not have | High | Very low | — | **Rejected** |
| **Deterministic graph analytics** (k-core, exclusive pairs) | No | Live rows | In memory, nothing stored | Thresholds can be learned; cost-based | **High** ("they keep backing each other and nobody else backs it") | Only opens a human case | **Chosen for 9C** |
| **Statistics over outcome counters** (rates with Wilson intervals) | Uses moderator outcomes | Small | Counters only | Low | High | None (informs humans) | **Chosen for 9B** |
| **Adversarial simulation** | No | Synthetic, labelled | None (no real data) | — | High | None | **Chosen for 9A** |
| Supervised, interpretable triage (logistic regression → points scorecard) | Moderator outcomes | ≥ G2 | De-identified examples after G1 | Medium | High (points per band) | Orders human work only | **Future M1, gated** |
| On-device personal models (walking pattern, adaptive deviation or stop thresholds) | No | Continuous own tracks | **Continuous location history on the phone** | Extractable model | Low | Changes when Safe Walk asks "Are you OK?" and therefore when the Safety Circle is texted (L2) | **Rejected** |
| On-device sensor ML (shake, fall, scream, voice) | Yes | Sensor data, microphone | High | — | Low | Automatic SOS triggers | **Rejected** (SHAKE/VOICE are reserved and rejected by the server; no automatic SOS) |
| LLM features (chatbot, generated explanations, summaries) | — | Sends context to a third party | Location and safety context leave the system | Prompt injection | Low; untestable wording | Hallucinated safety advice | **Rejected** |
| Federated learning / DP-SGD | Labels | Many devices | Gradient leakage risk | Poisoning | Low | — | **Rejected** (nothing to learn on the device) |
| External crime datasets | — | Licences | Stigma | — | — | Reporting bias of policing | **Rejected**. Open map *facts* (e.g. street-lighting tags) only for a future router, deterministically (§20) |

---

## 7. Chosen architecture

### 7.1 The authority ladder (the core idea)

```
 Level 0  HARD INVARIANTS          executable checks + source-scan tests; nothing below may override them
          (k = 2 independent people; one person never raises concern; "never safe"; havens never cancel;
           no path to SOS / Safe Walk / Safety Circle / evidence / a person's standing)
              ▲ wraps every output
 Level 1  DETERMINISTIC RULES      the ONLY source of anything a user sees, and of every automatic action
          rules-1 (Phase 8), coord-1 (9C), adjacent merge (9D), rules-2 candidate (9D)
              ▲ measured by
 Level 2  EVIDENCE LAYER           never user-facing; produces numbers for humans
          simulator (9A, test-only) · outcome counters (9B) · weekly shadow comparison (9D)
              ▲ may later inform
 Level 3  LEARNED MODELS (future, gated G1–G3)   advise humans only: may ORDER the moderator queue, never act
          M1 coordination triage ranker (not built in Phase 9)
```

Authority flows downward: a lower level can only *inform* the level above it through a human decision (a code change
with evidence). A learned model can never sit in Levels 0–1. This mirrors the Phase 4 escalation ladder: proportional,
never jumping to the top.

### 7.2 Components (planned)

| Component | Package | Kind | Responsibility |
|---|---|---|---|
| `AttackSimulator` + scenarios | backend **test** scope `evaluation/simulation` | deterministic, seeded | Runs synthetic communities through the real pure policies; writes `target/simulation-report.md` |
| `OutcomeRecorder` | `evaluation` (new) | `@Component` | Best-effort counter increments, after commit |
| `EvaluationCounter` entity + repository | `evaluation` | JPA | V15 table (§7.5.4) |
| `WeeklySnapshot` | `evaluation` | called by the existing job | Once per ISO week: coverage counts and shadow comparison over all areas with visible signals |
| `EvaluationReport` | `evaluation` | pure | Derived rates, Wilson intervals, "not enough data" |
| `AdminEvaluationController` | `moderation` (next to `AdminController`) | ADMIN only | `GET /api/admin/evaluation?weeks=N` |
| `CoordinationPolicy` | `community/service` (next to `Independence`) | pure | coord-1 rules R1, R2 (§7.5.1) |
| `ModerationService.maintain` | existing | changed | Runs coord-1 (mode, budget, cadence); reopen guard (F9) |
| `CommunityService.report` | existing | changed (9D) | Adjacent merge (§7.5.2) |
| `AreaIntelligenceModel` + `RulesOne`, `RulesTwo`, `PictureInvariants` | `intelligence/service` | pure | The seam, built only with rules-2 (F1); invariant guard on every picture |
| Android `ModerationTexts`, case screen | `feature/moderation` | UI | coord-1 reasons and guidance |

**Not built:** no ML library, no Python, no on-device model, no new endpoint for clients, no new permission.

### 7.3 Data flow

```
 every 15 min (existing CommunityPurgeJob)
   CommunityService.purge ──────────────► OutcomeRecorder (afterCommit): SIGNAL_ENDED / SIGNAL_CONFIRMED …
   ModerationService.maintain
      ├─ Phase 7 R0 (unchanged, + reopen guard)
      └─ coord-1 (at most hourly): live rows → CoordinationPolicy (in memory) → OFF | SHADOW: count | ACTIVE: open case
   WeeklySnapshot (first run of each ISO week)
      visibleIn(all areas with signals) → rules-1 (+ shadow rules-2) → counts by outlook / information / agreement
 on events
   report / merge / moderator decision ──► OutcomeRecorder
 ADMIN
   GET /api/admin/evaluation ──► EvaluationReport (rates + intervals)    (no cells, no people, no export)

 NO arrows to or from: emergency, journey, escalation, safetycontact, circlealert, evidence, the phone's Safe Walk/SOS code.
```

### 7.4 What stays deterministic permanently
- **Safety-critical logic:** SOS countdown, `CheckInPolicy`, `MovementPolicy`, `BatteryPolicy`, `CircleAlertPolicy`, the
  escalation ladder, evidence handling. ML is never allowed here: a wrong output can stop a real alert or send a false one.
- **What people see and how people count:** `SignalPolicy`, `Independence`, `TrustPolicy`, `FlagPolicy`. A model here would
  be automated moderation of people.
- **Area picture and corridor brief** (rules-1/rules-2), pattern memory, region packs.
- **The only place a learned component may ever enter:** the *order and selection of review work for humans* (M1).

### 7.5 Component designs

#### 7.5.1 coord-1: coordination triage (9C)

**Input** (rebuilt in memory on each run from live rows only; nothing stored): counted support edges (participant key,
signal), where kind is REPORT or CONFIRM, `counts_from` has passed and is within the lifetime, weight > 0, and the signal
is unmoderated and visible. Per signal: raw supporters R(s), confidence, area cell.

**Rules** (thresholds are provisional and are set from the 9A simulation before 9C, recorded in the coord-1 model card):

| Rule | Definition | Catches | Does not catch / false positives |
|---|---|---|---|
| R0 `LINKED_GROUP` | Phase 7, unchanged: all counted support of a signal is one linked group | Owner + puppets backing the same things | — |
| R1 `SUPPORT_CORE` | (1) L = signals with \|R(s)\| ≤ 3 (popular signals are ignored, so padding with them changes nothing). (2) Graph H: an edge between two participants who co-back a signal in L. (3) Take the **2-core** of H: repeatedly remove anyone with fewer than 2 partners. (4) For each connected component K with ≥ 3 people: count its signals s ∈ L with R(s) ⊆ K that are MODERATE+ (havens included: lure rings). Fire if there are ≥ 4 **and they do not all fit inside one neighbourhood** (3 × 3 area cells, ~3.6 × 1.8 km) | Rotating rings (F6) of any size that mark areas across the city; padded rings | Rings that give every signal ≥ 4 backers (they cost more and tend to become linked); rings that stay in one neighbourhood. FP: friends who walk together across town and report places nobody else confirms |
| R2 `EXCLUSIVE_PAIR` | Two participants, not linked by Phase 7, co-back ≥ 3 live signals in L that **only they** back (R(s) ⊆ {p, q}), not all inside one neighbourhood | Padded puppet pairs that dodge the "half of activity" ratio | The 2-signal floor of F5 (handled by rules-2). FP: two friends reporting unbacked places in several parts of town |

A group whose signals all lie inside one neighbourhood is never flagged (a hostel, a street's residents, neighbours who
walk around their own area). Local damage is bounded to that neighbourhood, and rules-2 limits a pair there to TAKE_CARE.
The scaling attack (many areas from few accounts, F6) is the one coord-1 is built for.

**Effects (the complete list):** open ONE case per group or pair (origin COORDINATION), on the group's signal that does
the most harm: one in the area with the highest concern, or, for a group that backs only safe places, one of those
(lures). That is all. No quarantine, no confidence or weight change, no
withdrawal lock (F8), no message to anyone. Pattern episodes of a signal under an open case stay suppressed (the
existing conservative rule). A moderator can then DISMISS, REMOVE, or RESTRICT participants by pseudonym. A restriction
sets their weight to 0 at once, so **one decision neutralises a whole ring**. *(Corrected 2026-10-21, measured in
`PHASE9.md` §8.4: a case lists only the people behind ITS signal, so this holds for a ring of 3 but not for 4 or more.
Listing the group's members as case participants is a precondition for ACTIVE mode, `PHASE9.md` §8.5.)* *(Built
2026-10-21, `PHASE9.md` §10: the case lists the group's members — people backing at least two of its places — and one
RESTRICT with `wholeGroup` pauses all of them; one by one turned out to be fragile.)*

**Guards:** mode `app.moderation.coordination.mode = OFF | SHADOW | ACTIVE` (default **SHADOW** after implementation;
OFF = Phase 7 behaviour exactly); at most hourly; budget `max-cases-per-day = 5`, worst harm first (number of
TAKE_EXTRA_CARE / TAKE_CARE areas affected); **never reopened after a DISMISS**: R0 skips a signal that has a dismissed
COORDINATION case (the F9 fix), and coord-1 skips a whole group or pair if **any** of its signals has one (otherwise
the next run would simply pick another signal of the same group). Closed cases are kept 90 days, so a dismissed group is
looked at again after 90 days at the earliest; flags still work at any time.

**Moderator view** (recomputed each time the case is viewed, so it is always current): reason codes, a banded group size
(2 / 3–5 / 6+), a banded count of signals only this group backs (2–4 / 5–9 / 10+), the existing pseudonyms and
link-group labels, and the guidance line. **No other cells and no number of areas** (that would sketch where pseudonymous
people go). If no rule holds any more: "The pattern that opened this case is no longer present."

**Why a k-core:** it is the simplest standard graph algorithm that separates "a group that keeps backing each other" from
"one active person whose reports strangers confirm". In a star, the leaves have one partner, so they are removed, and then
the centre is removed too. In a ring everyone has several partners. It can be explained in one sentence and checked by
hand on a whiteboard.

#### 7.5.2 Adjacent merge for concern reports (9D, fixes F7)

> **As built (2026-10-21), different from the text below:** a job-time join after the report counts, older targets
> only, side blocks only, lone never-confirmed reports only. Reasons and measurements: `PHASE9.md` §18.

In `CommunityService.report`, when no signal exists for the exact (block, category): look in the 8 neighbouring blocks
(`GeoCell.neighbourhood` works at any precision) for a signal of the **same category** that is

- in the INCIDENT or CONDITION group (**never HAVEN**: safe places are points, and merging would make fake havens easier
  to confirm);
- **VISIBLE** now (never pending: merging into a report still in its delay would be a timing oracle on a fresh reporter);
- unmoderated, with no open case;
- not already backed, disputed or flagged by the caller.

If one exists (edge neighbours before corner ones, then higher support), the caller's contribution is added to it **as a
CONFIRM**, under the answer limits. Otherwise a new signal is created exactly as today. The response is identical either way.

- **Stored as CONFIRM on purpose:** "your reports" shows a REPORT at its own block. A merged REPORT would show the caller
  the neighbour's exact block while that signal is still a single at area precision. That would leak a single reporter's
  block.
- **Privacy is equal to or tighter than today:** anyone can already tap "I saw this too" on a single report from anywhere
  in the ~1 km area (nearness at shown precision). The merge requires ≤ one block (~150–210 m).
- **Independence, probation, weights and k = 2 are unchanged.** One person with two accounts gains exactly what "I saw
  this too" already gives them.
- The phone needs only a text change in the report confirmation: "If the same thing was already reported right next to
  you, your report is added to it."
- **Not done:** merging two singles that were both pending when reported (a periodic consolidation). It is more complex
  (moving rows). Build it only if 9A shows the loss is large, for example at launch when everyone is in probation.

#### 7.5.3 rules-2 candidate: "take extra care" needs breadth (9D, fixes F5)

> **As built (2026-10-21):** built on top of the active `rules-1.1`; SHADOW only (hourly log count); not promoted,
> because it also weakens honest pairs' warnings and no real-world evidence exists (`PHASE9.md` §19).

`rules-2` = `rules-1` with one change. ELEVATED by rule (b) ("≥ 2 confirmed non-aging concerns") also requires that
those signals are backed, **together, by ≥ 3 independent people**: the union of their independent supporters, with
independence re-applied across the signals. Rules (a) (a STRONG current incident, already ≥ 3 people) and (c) (recurring
history) are unchanged.

- **Effect:** two accounts can produce at most TAKE_CARE (amber, still a warning). "Take extra care", the strongest claim
  the app makes about a place, then always needs a third independent person, a strongly confirmed incident, or a
  recurring history. An honest pair of walkers is downgraded from "extra care" to "care". That is never to "fine", so it
  stays on the safe side of Phase 8's asymmetry.
- **Needs:** `Evidence` gains the opaque keys of each signal's independent supporters (in memory, never output), and the
  policy receives the support linkage.
- **Path:** simulated (9A) → shadow (weekly snapshot, 9D) → promoted only by the user's decision → `modelVersion
  "rules-2"`. The phone needs no change (same codes).

#### 7.5.4 Outcome counters (9B): V15 (planned, not written)

> **As built (2026-10-21):** see the 9B status update at the top and `PHASE9.md` §27–§34 for the deviations (version
> CHECK with one dot, `moderation_cases.coordination_rule`, `occurrences`, binary collation, metric list after 9D).

```sql
CREATE TABLE evaluation_counters (
    week_start    DATE        NOT NULL,               -- Monday (UTC) of the ISO week; nothing finer
    metric        VARCHAR(40) NOT NULL,
    dimension     VARCHAR(48) NOT NULL DEFAULT '-',   -- e.g. 'CONDITION.EXPIRED_CONFIRMED'
    model_version VARCHAR(20) NOT NULL DEFAULT '-',   -- e.g. 'rules-1', 'coord-1'
    value         INT         NOT NULL,
    PRIMARY KEY (week_start, metric, dimension, model_version),
    CONSTRAINT ck_evaluation_counters_metric    CHECK (metric IN ( /* closed list below */ )),
    -- Upper-case letters and underscores only: a geohash (lower case + digits) or an id CANNOT be stored here.
    CONSTRAINT ck_evaluation_counters_dimension CHECK (REGEXP_LIKE(dimension, '^(-|[A-Z_]+([.][A-Z_]+)?)$', 'c')),
    CONSTRAINT ck_evaluation_counters_version   CHECK (REGEXP_LIKE(model_version, '^(-|[a-z]+-[0-9]+)$', 'c')),
    CONSTRAINT ck_evaluation_counters_value     CHECK (value >= 0),
    CONSTRAINT ck_evaluation_counters_monday    CHECK (DAYOFWEEK(week_start) = 2)   -- verify on MySQL 8 in the dry run
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
```

| Metric | Dimension | Counted when |
|---|---|---|
| `SIGNAL_STARTED` | group | A new signal row is created |
| `SIGNAL_MERGED_ADJACENT` | group | 9D merge |
| `SIGNAL_CONFIRMED` | group | `reached_confirmed` is first set |
| `SIGNAL_ENDED` | `{group}.{EXPIRED\|RETIRED\|WITHDRAWN}_{CONFIRMED\|UNCONFIRMED}` | Deletion |
| `SIGNAL_REMOVED` | `{group}.{CONFIRMED\|UNCONFIRMED}_{reason}` | Moderator REMOVE |
| `CASE_OPENED` | `{origin}` or `COORDINATION.{rule}` | Case created |
| `CASE_CLOSED` | `{origin or rule}.{REMOVED\|DISMISSED}` | Decision (the coord-1 rule is recomputed at decision time, so no schema change is needed) |
| `CASE_UNREVIEWED` | `{origin or rule}` | Signal deleted while its case was open |
| `COORDINATION_SHADOW` | `{rule}` | coord-1 in SHADOW would have opened a case |
| `VISIBLE_SIGNALS` | `{group}.{UNCONFIRMED\|CONFIRMED\|STRONG}` | Weekly snapshot |
| `AREAS_BY_INFORMATION` / `AREAS_BY_OUTLOOK` | level / outlook | Weekly snapshot, areas with ≥ 1 visible signal |
| `SHADOW_PICTURE` | `SAME\|MORE_CAUTIOUS\|LESS_CAUTIOUS` (version = candidate) | Weekly snapshot |
| `INVARIANT_VIOLATION` | `-` (version = model) | The guard rejected a picture (§13) |

- Recorded **after commit** (a rolled-back operation counts nothing) and **best-effort**: a counter failure never fails
  a report, a decision or the job. It logs only the exception class.
- Retention: 104 weeks, deleted by the existing job. The `CommunityPurgeJob` log line gains a count.
- Read only by ADMIN through `GET /api/admin/evaluation?weeks=N` (N ≤ 104). There is no export and no client view.
- **Publication rule** (thesis, slides, any report): system-wide only; values below 10 shown as "fewer than 10"; state
  "weekly, system-wide, lower bound where marked". There is no public dashboard in Phase 9. Differential privacy is
  unnecessary internally (the operator holds the raw rows anyway) and is required for any future public, repeated release.

---

## 8. Feature and data design

### 8.1 Data register (what may be used for what)

| Data item | Rules (Level 1) | Evidence layer (Level 2) | Future model (Level 3) | Never |
|---|---|---|---|---|
| Category, group | yes | yes (dimension) | yes (group as a band) | — |
| 7-char cell / 6-char area cell | yes | **no** (counts only) | **no**: a model must not learn places (stigma, geographic bias) | stored in counters |
| `counts_from` | yes | weekly bucket only | banded (e.g. supporters' account-age bands) | time of day |
| Participant (user id) | in memory, as an opaque key for independence and graphs | no | in memory at feature time only | stored, logged, returned |
| Trust weight | yes (existing) | no | "share of supporters below standard weight", banded | tier names, standing counters as labels |
| Flags | yes (existing) | case counts | counts by reason, banded | as evidence about the place |
| Moderator outcomes | — | counters | labels (content outcomes only) | restrictions or appeals as labels |
| `area_patterns` | yes (existing) | no | no | — |
| Safety data (emergencies, locations, journeys, escalations, circle alerts, evidence, contacts) | **no** | **no** | **no** | **always** |
| Viewer requests (area/region look-ups) | no | no (the snapshot is data-side) | no | **always** |
| Device ids, IP, phone number, e-mail, name, sensors, microphone, camera, free text | no | no | no | **always** |

### 8.2 coord-1 features
Graph structure only (§7.5.1). No location beyond each signal's own area cell, used only for the "≥ 2 areas" test and
never shown. No time features (`counts_from` carries a random delay, so timing is noise). No account ages in coord-1
(see §24: launch base-rate problem).

### 8.3 Simulator data
Fully synthetic. Geography is a synthetic grid around a **geohash in open ocean**, so no scenario ever says anything
about a real neighbourhood. Actors are scripted (§10.1). It is never mixed with real rows and never written to a database
other than the H2 used by the fidelity test.

### 8.4 Future M1 example (specified now, stored only after G1)
One row per decided COORDINATION/FLAGS case: about 10 **bands** computed when the case was opened (independent
supporters; raw-to-independent ratio; core size; exclusive-signal count; areas spanned; share of supporters < 30 days old;
share below standard weight; flag count by reason; group), the **label** (1 = REMOVE with SPAM_OR_COORDINATED or
FALSE_INFORMATION; 0 = DISMISS NO_VIOLATION; others excluded) and the **month**. No case reference (it would join to the
audit log and, through a RESTRICT in the same case, to a person), no signal id, no cell, no user, no moderator. 24-month
retention. The features are held on the open case row while it is open (they go with the signal) and copied at close.

---

## 9. Privacy design (invariants, each tested in §18)

1. **PR1 No new personal data at rest.** V15 holds no user, cell, coordinate, signal id, free text or time finer than a
   week, and its CHECKs make storing a cell or an id impossible.
2. **PR2 Nothing new reaches clients.** Every client DTO is unchanged (the existing allow-list tests keep passing). The
   merge answers identically.
3. **PR3 Moderators see only reasons and bands beyond today:** never other cells, areas or counts that sketch movement.
4. **PR4 No viewer data.** No counter comes from requests. The snapshot reads data, not views.
5. **PR5 Same-set rule for every picture model** (`visibleIn`). rules-2 and any shadow candidate see exactly what rules-1
   sees.
6. **PR6 Isolation in both directions** for `evaluation` as for `intelligence`: no safety package is read, and none reads
   these.
7. **PR7 Graph analysis in memory only.** Logs carry counts only.
8. **PR8 The merge never reveals a single reporter's block** (visible targets only, stored as CONFIRM, havens excluded,
   precision rule unchanged).
9. **PR9 Purpose limitation.** Counters exist to evaluate SafeSphere's own rules, and for nothing else.
10. **PR10 Future examples** only after G1, de-identified as in §8.4, with their own approval.

**Residual (honest):** the operator can read small weekly counts (they already hold the raw rows). A merged reporter
can infer that a same-category signal exists within about one block (this equals what "I saw this too" already reveals).
Operator-level correlation with live request logging (`PHASE8.md` §6) is unchanged.

---

## 10. Training and validation strategy

### 10.1 For rules (Phase 9)
- **Simulation suite (9A)**, deterministic seeds. Scenarios:

| # | Scenario | Measures |
|---|---|---|
| S1 | One account, many reports | Invariant: never raises concern |
| S2 | Puppet pair, 2 signals in one area (F5) | rules-1 → TAKE_EXTRA_CARE; rules-2 → TAKE_CARE |
| S3 | Rotating ring, n = 4…10 (F6) | Areas marked per account; coord-1 time to a case |
| S4 | Padded puppet pair (≥ 3 exclusive signals across town + popular padding) | Phase 7 misses it; R2 catches it |
| S5 | Fake-haven lure in a concern-free area; and next to a real concern | Havens never cancel (invariant); lure cost |
| S6 | Flag bombing a well-corroborated real signal | Stays visible (invariant) |
| S7 | "No longer true" attack on a real signal | Retirement cost |
| S8 | Two honest reporters at random points along a 300 m street (F7) | P(confirmed) with exact-cell merge vs adjacent merge |
| S9 | Honest hostel (5 people, one area, many mutual confirmations); neighbours walking around their own neighbourhood | coord-1 must NOT open a case |
| S10 | Three friends walking across town | coord-1 opens (documented false positive); moderator workload |
| S11 | Sparse launch: 20 new honest users | Information distribution; coord-1 workload at launch (base rate) |

- **Fidelity test:** one small scenario runs both through the simulator and through the real API on H2, and every
  assessment must match. This catches drift between the simulator's in-memory linkage and the SQL pair query.
- **Property tests over simulator output:** invariants I6/I7 (§13) hold for every simulated area.
- **Promotion of a rule change:** all invariant scenarios pass; no attack gets cheaper; deltas documented; shadow counts
  if a real deployment exists (otherwise this is written down honestly); a written user decision; a version bump.

### 10.2 For M1 (future, only after G1/G2)
- **Task:** at case-open time, estimate P(moderator REMOVE as coordinated or false). Used **only** to order and choose
  coord-1 candidates under the daily budget.
- **Splits:** time-based (train on older months, validate, test on the last 3 months). **Never random**: one campaign
  spans many cases, so a random split would leak.
- **Algorithm:** L2-regularised logistic regression on ordinal bands, with monotonic sign checks, rounded to an integer
  **points table** (a scorecard) that a moderator could read. Baseline: coord-1's own ordering.
- **Selection bias:** train on rule-opened cases. If M1-chosen cases are later included, weight them by inverse
  selection probability. Never train on a model's own picks alone.
- **No online learning** (a poisoning channel). Retraining is manual, at most quarterly, and each retrain is a new
  version with its own card.
- **Where:** offline Python/scikit-learn on the operator's machine, reading the de-identified examples. The output is a
  JSON points table plus SHA-256, reviewed in code review, scored by pure Java. No Python or ML runtime in production.

### 10.3 Never
Random splits on campaign data; tuning on the test set; promoting on simulated data alone; training on safety data;
treating synthetic results as accuracy; automatic threshold changes.

---

## 11. Evaluation metrics

| Metric | Definition | Reported when |
|---|---|---|
| Confirmation yield (per group) | `SIGNAL_CONFIRMED / SIGNAL_STARTED` (lagged by one lifetime) | Denominator ≥ 20 |
| False-confirmation rate (FCR), **a lower bound** | Confirmed signals removed as FALSE_INFORMATION or SPAM_OR_COORDINATED ÷ all confirmed signals that ended in any way (expired, retired, withdrawn or removed) | ≥ 20; havens separately (lure indicator) |
| Retirement after confirmation | Confirmed signals ended RETIRED ÷ confirmed ended | ≥ 20 |
| Trigger precision (per origin and rule) | `CASE_CLOSED.*.REMOVED ÷ CASE_CLOSED.*` | ≥ 10 decided cases |
| Unreviewed share | `CASE_UNREVIEWED ÷ CASE_OPENED` (moderator capacity) | Always |
| Coverage | Distribution of `AREAS_BY_INFORMATION`; share CONFIRMED+ among `VISIBLE_SIGNALS` | Always |
| Merge yield (9D) | `SIGNAL_MERGED_ADJACENT ÷ (SIGNAL_STARTED + merged)` | ≥ 20 |
| Shadow disagreement | `SHADOW_PICTURE` LESS/MORE ÷ total | Always (counts) |
| **Simulation:** attack cost | Minimum accounts, days and contributions to reach an outcome; areas affected per account | Every run |
| **Simulation:** detection latency, honest false-positive cases, honest confirmation probability | Per scenario | Every run |
| M1 (future): precision@budget, recall proxy, Brier score + reliability curve, workload, DISMISS rate by area-density band, attack-cost non-regression | Per G2/G3 | Gate reviews |

All rates come with **Wilson 95 % intervals**. Below the minimum the report says "not enough data", never a number.

---

## 12. Confidence and uncertainty handling

- **User-facing:** unchanged. Two axes (concern, information), ordinal levels and reason codes. No probability, score or
  model confidence is ever shown. "No information" still means exactly that.
- **Measurement:** intervals and minimum counts (§11). The admin report states gate status ("G1 not met: 0 decided
  cases this month").
- **coord-1:** no score. A rule holds or it does not, with its reasons. If the graph cannot be built (job error, budget
  exhausted) it **abstains**: nothing is opened, and Phase 7 R0 still runs.
- **M1 (future):** abstains when a feature is missing, when the band combination was never seen in training (out of
  range), or when the support set has < 2 people. Abstention means coord-1 ordering. Scores only order work; they are
  never shown to moderators (automation bias, T29).
- **System level:** gates not met means no model at all. That is stated, not hidden.

---

## 13. Integration with Phase 8

- **rules-1 remains the active model** until the user promotes rules-2. The `AreaIntelligenceModel` seam (`picture(cells,
  evidence, patterns) → Picture`) is introduced in 9D with exactly two implementations. Config:
  `app.intelligence.area-model = rules-1 | rules-2`, `app.intelligence.area-shadow = none | rules-2`. Shadow runs only
  in the weekly snapshot, never per request.
- **Codes-only contract:** any model outputs the same enums. A new reason code needs phone text. Older phones drop
  unknown reasons individually (existing fail-safe). A new outlook value would hide the card on old phones (existing
  fail-closed), so outlooks must not be added lightly.
- **Invariant guard (`PictureInvariants`)**, applied to every picture of every model before it leaves the server:
  I1 concern > NONE_CONFIRMED ⇒ at least one confirmed concern signal in that cell; I2 outlook = the mapping of
  (concern, information); I3 information NONE ⇒ no evidence and no recurrence; I4 concern categories ⊆ confirmed concern
  evidence; I5 `highestConcernCell` non-null ⇔ concern > NONE_CONFIRMED and ∈ cells. On violation: rules-1's picture
  is used and `INVARIANT_VIOLATION` is counted. If rules-1 itself fails, the picture is omitted (as today). The costlier
  I6 (havens never change concern) and I7 (removing all unconfirmed evidence never changes concern) are property tests
  over the simulator, not runtime checks.
- **Region packs and "Check the way"** use the active model's cell pictures. The phone-side `CorridorBriefPolicy` is
  unchanged.
- **The adjacent merge** happens upstream in community, so rules-1 benefits with no change to intelligence code.
- **Pattern memory** is unchanged and is never fed by a model.

---

## 14. Backend / on-device / offline split

| Where | Phase 9 | Why |
|---|---|---|
| **Phone** | No ML. Words from codes (unchanged); one report-confirmation text (9D); moderator reason texts (9C) | Nothing to learn without tracking the person; on-device models can be extracted and probed offline; APK size and battery; Safe Walk/SOS must stay deterministic |
| **Backend (Java)** | Counters, weekly snapshot, coord-1, merge, rules-2 seam, invariant guard, admin report | The data lives here; pure policies are already the project's tested idiom; no new runtime |
| **Backend test scope** | Simulator, fidelity and property tests | Runs in `mvn test`; no production footprint |
| **Offline (future, after G2)** | Python/scikit-learn training on de-identified examples; output = JSON points table + SHA-256 | Training tools without a production dependency; artefacts reviewed like code |
| **Never** | Third-party AI APIs, remote model downloads, an ML runtime in production, an on-device model | Data would leave the system; supply-chain risk; no need |

---

## 15. Failure and fallback behaviour

| Component | Failure | Behaviour |
|---|---|---|
| Counter write | Exception | Main operation unaffected (after-commit, caught); class name logged |
| Weekly snapshot | Exception or timeout | Retried on the next job run; a partial week is never written |
| coord-1 | Exception | That run abstains; Phase 7 R0 still runs; class name logged |
| coord-1 | False positive | A moderator case only; nothing hidden or changed; DISMISS stops reopening |
| coord-1 | False negative | Status quo: flags, quarantine and moderators still apply |
| coord-1 | Budget exhausted | Remaining candidates wait for the next day, worst harm first |
| Adjacent merge | Exception in the lookup | Falls back to today's path (a new signal at the caller's block) |
| rules-2 (shadow) | Exception | Shadow count skipped; rules-1 unaffected |
| rules-2 (active) | Exception or invariant violation | rules-1 picture used; counted |
| rules-1 | Exception | Picture omitted, signals still shown (unchanged) |
| Phone | Unknown codes | Unchanged: unknown outlook ⇒ no card; unknown reason dropped; incomplete pack ⇒ no brief |
| Future M1 artefact | Missing, bad checksum, invalid | M1 disabled, coord-1 ordering, WARN logged. **The server still starts** (SOS depends on it). Invalid *configuration values* still refuse start, as for `app.intelligence.*` |
| Admin report | Gates not met | Says so; shows counts and "not enough data" |

---

## 16. Model versioning and governance

- **Versions:** `rules-1` (Phase 8), `rules-2` (candidate), `coord-1` (9C), `triage-1` (future M1; letters-only prefix,
  so it fits the V15 version CHECK). Every picture and pack carries
  `modelVersion`; every counter row carries the version that produced it; case reasons name their rule.
- **Model card per version** (`docs/models/<version>.md`): purpose; the authority level it may hold (§7.1); inputs and
  forbidden inputs; rules or features; thresholds and where they came from (simulation run, date); evaluation results
  labelled SIMULATED or REAL; known failure modes and false positives; promotion evidence and the user's decision;
  rollback criteria; artefact SHA-256 (M1 only).
- **Rollback** = change one configuration value (`area-model`, `coordination.mode`). Old versions stay in code until
  their removal is itself decided.
- **Phone compatibility:** the phone does not depend on `modelVersion`. Compatibility is the code contract (§13).
- **No automatic updates** of any model, rule or threshold. Every change is a code change with evidence.

---

## 17. Security against poisoned and manipulated data

| Vector | Defence |
|---|---|
| Fake reports and confirmations | Only *counted* contributions enter anything (after probation, weight > 0, independence applied); same-set rule; coord-1; rules-2 breadth |
| Sock puppets | Phase 7 linkage; R1 core; R2 exclusive pairs; one RESTRICT neutralises a group |
| Account farming | Registration limits and probation (Phase 7); coord-1 ignores account age on purpose (§24), so farms are caught by structure, not age |
| Location spoofing | Unchanged and undetectable (no movement history by design). Rules count people, not places visited |
| Lures (fake havens) | Havens never cancel concerns, are capped in information, and are **never merged** (9D). FCR for havens measured separately |
| Label poisoning | §10.2, T19: labels only after gates, from ≥ 3 moderators, content-only |
| Feedback loops | T20 |
| Probing | T21: no client-visible effect of triage |
| Flooding moderators | T22: budget, worst harm first |
| Poisoning the counters | Counters are increments by the server itself on its own events. No client writes them |
| Gaming thresholds | Thresholds are public. Defence is cost (more independent people, more days), measured by the simulator |
| Sparse-area bias | Counters and simulation report by area-density band. Groups within one neighbourhood are never flagged |

---

## 18. Testing strategy (planned test names)

**Backend, pure**
- `CoordinationPolicyTest`: `rotatingRing_isOneCore_anySize`, `paddingWithPopularSignals_changesNothing`,
  `star_ofOnePowerUserAndStrangers_isNotACore`, `groupWithinOneNeighbourhood_neverFires` (hostel), `friendsAcrossTown_fire`
  (documented false positive), `exclusivePair_firesBelowPhase7Ratio`, `linkedPair_isLeftToR0`,
  `dismissedGroup_isSkippedOnEverySignal`, `outputHasNoPeopleOrCells`.
- `RulesTwoTest`: `pairOfPeople_canReachTakeCare_notExtraCare`, `thirdIndependentPerson_reachesExtraCare`,
  `linkedThird_doesNotCount`, `strongIncident_unchanged`, `recurring_unchanged`, plus every `AreaIntelligencePolicyTest`
  example re-run (identical except rule (b)).
- `PictureInvariantsTest`: each of I1–I5 rejects a crafted bad picture; rules-1 and rules-2 outputs always pass.
- `EvaluationReportTest`: Wilson intervals, "not enough data" thresholds, gate status texts.
- `AttackSimulationTest` (S1–S11), `SimulatorFidelityTest`, `PictureInvariantPropertyTest` (I6, I7 over simulator output).

**Backend, integration (MockMvc, real JWT, two users)**
- `CoordinationApiIntegrationTest`: SHADOW counts and opens nothing; ACTIVE opens one case per group; no quarantine,
  confidence unchanged, **withdrawal allowed**; identical client responses before and after; budget; moderator view shows
  reasons and bands, no other cells; RESTRICT of the core neutralises every ring signal.
- `CoordinationReopenTest`: `aDismissedCoordinationCase_isNotReopenedByTheNextRun` (F9, for R0 and coord-1).
- `AdjacentMergeApiIntegrationTest`: merges into a visible neighbour as CONFIRM; never into a pending one (timing
  oracle); never havens; never into something the caller disputed or flagged; "your reports" never shows the neighbour's
  block; the response is identical; answer limits apply; independence still applies.
- `EvaluationCountersIntegrationTest`: each lifecycle event counts once, after commit; rollback counts nothing; a counter
  failure never fails the operation; retention; ADMIN-only endpoint (USER and MODERATOR 403, no token 401).
- `EvaluationIsolationTest` (source scan, like `IntelligenceIsolationTest`): `evaluation` references no safety package and
  none references it; no `RestTemplate|WebClient|HttpClient`; V15 file has no column named like `user|cell|lat|lng|ip|email`.
- `NoMlRuntimeTest`: `pom.xml` contains no ML runtime (tensorflow, onnx, djl, tribuo, smile, weka, pytorch). An Android
  twin checks `libs.versions.toml` (also tflite, mlkit). It guards against black boxes slipping in.
- `EvaluationIsolationTest` also checks that `CoordinationPolicy` is pure (no repository, service or entity imports), so
  coord-1's only effect is the `openCase` call in `ModerationService`, which `CoordinationApiIntegrationTest` checks.
- Regression: all existing suites (Community, Trust, Moderation, Intelligence, Emergency, Journey, Evidence) stay green.

**Database:** V15 on MySQL 8.0 plus a rolled-back dry run. Must reject: a lower-case dimension (a geohash), a digit in a
dimension, an unknown metric, a bad version string, a negative value, a non-Monday week.

**Android (JUnit4, fakes):** `ModerationTextsTest` (+ coord reasons, guidance; forbidden phrases extended: no "dangerous",
"suspicious person", "fraud" about people); `CommunityTextsTest` (merge sentence present, no safety claim). The existing
`IntelligenceIsolationTest` is unchanged and must pass.

---

## 19. Android / UX implications

- **Nothing labelled "AI" or "smart" anywhere.** That would be a false claim (`CLAUDE.md` §10 honesty labels).
- **People see no new screen, card, permission or dependency.** Pictures look the same (rules-2 uses the same codes).
- **Community (9D):** one sentence in the report confirmation. A merged report appears in "your reports" with the
  existing confirmation wording ("You said you saw this too", `your_kind_confirm`) at the precision everyone sees.
- **Moderators (9C):** COORDINATION cases show the rule's reasons in plain words ("These accounts keep backing the same
  places, and nobody else backs those places"), the banded group size, and the guidance: *"Backing each other is not
  proof that a report is false. Look at the place and the reasons."* FLAG_SECURE, TalkBack and dark mode follow the
  existing moderation screens.
- **Admin metrics:** API-only (like every Phase 7 admin function). No Android screen.

---

## 20. Future route intelligence (how it can improve without becoming an emergency decision-maker)

1. **The contract stays** (`PHASE8_DESIGN.md` §16.2): CellEvidence, AreaContext, RouteEvidenceSummary, the two-channel
   rule, "recommend only between comparably known routes", and the bounded detour (min(25 %, 800 m)).
2. **Preconditions unchanged** (§16.4 there): an on-device routing graph from an offline map extract downloaded by
   region; the server never sees a route.
3. **Where computation helps a router, deterministically:** *map-matching* the corridor to walkable streets (a hidden
   Markov model with Viterbi decoding over the local graph: an exact algorithm, not learned from users) and *open map facts*
   such as street-lighting tags, always labelled "map data says…", in their own channel, never "safe".
4. **Never:** learning from people's walks, check-ins, deviations, escalations or SOS (it would need tracks, and would
   correlate safety data with community data); automatic re-routing mid-walk; changing check-in intervals; escalating;
   blocking or delaying Start; "safe route" wording.
5. **Evaluation carries over:** a simulator scenario "diversion attack" measures the detour fake concerns can force,
   against the detour bound.
6. **Isolation extends** to a future `routing` package: Safe Walk session and policies must never read it.

---

## 21. Implementation phases (each gated by build + tests + the user's check, as in Phase 4/8)

| Step | Scope | Migration | Behaviour change | Main files (planned) |
|---|---|---|---|---|
| **9A** Adversarial simulator | Scenarios S1–S11, fidelity and property tests, `target/simulation-report.md`; sets coord-1 and rules-2 thresholds; results into `PHASE9.md` (SIMULATED) | none | **none** (test code only) | `backend/src/test/.../evaluation/simulation/*` |
| **9B** Outcome counters | V15, `OutcomeRecorder`, `WeeklySnapshot`, `EvaluationReport`, ADMIN endpoint, isolation tests, API docs | **V15** | none visible to users | `evaluation/*`, `CommunityService`, `CommunityPurgeJob`, `ModerationService`, `AdminEvaluationController`, `docs/API.md`. **Built 2026-10-21** (`PHASE9.md` §27–§34): the endpoint lives in the existing `AdminController`, the report in `EvaluationService` + pure `EvaluationReport` |
| **9C** Coordination | **9C.0 reopen fix (F9)**; `CoordinationPolicy` R1/R2; mode/budget/cadence; no withdrawal lock for COORDINATION; moderator reasons (backend DTO + Android texts); coord-1 model card | none (origin COORDINATION reused) | Moderators: new cases and reasons. Contributors: may withdraw during a COORDINATION review (**changes a Phase 7 decision, §24**) | `community/service/CoordinationPolicy`, `ModerationService`, `ModerationDtos`, `ModerationCaseRepository`, Android `feature/moderation` |
| **9D** Evidence quality | Adjacent merge (+ text); `AreaIntelligenceModel` seam, `RulesTwo`, `PictureInvariants`, shadow in the snapshot; rules-1/rules-2 cards; promotion **only by the user's decision** | none | Merge changes how reports combine (**changes a Phase 6 behaviour**); rules-2 only if promoted | `CommunityService`, `intelligence/service/*`, Android community strings. **Built 2026-10-21 with the deviations in the status note at the top** (neighbour join, rules-1.1 active, rules-2 SHADOW, no runtime guard, `Model` enum) |
| *9E* M1 learned triage | **Not part of Phase 9.** Specified (§8.4, §10.2); started only when G1, then G2, are met and the user approves | (future table) | — | — |

Order: 9A first (zero risk; its numbers set the thresholds of 9C/9D). 9C.0 can be done at any time, even before 9A.
9B, 9C and 9D are otherwise independent. Model per `CLAUDE.md` §3: Opus for 9A design, coord-1, the merge's privacy
details and the security reviews; Sonnet for the counters plumbing, DTOs and texts.

---

## 22. Definition of done (Phase 9)

Per `CLAUDE.md` §3, for **each** sub-phase:
1. `mvn test` and `gradlew testDebugUnitTest` all green; `assembleDebug` + `lintDebug` with no new errors.
2. Every §18 test exists and passes; the regression suites are unchanged.
3. (9B) V15 applied on MySQL 8.0 with the constraint dry run recorded (incl. the geohash-in-dimension rejection).
4. (9A) Simulation report recorded in `PHASE9.md`, **labelled SIMULATED**, with the attack-cost table before/after each change.
5. Live API smoke test: admin report (ADMIN only); coord-1 in SHADOW counts, in ACTIVE opens a case with no client-visible
   effect; merge into a visible neighbour, not into a pending one.
6. Emulator check (then Galaxy A23, or explicitly DEFERRED by the user): moderator sees coord-1 reasons and guidance; the
   case is not reopened after DISMISS; a contributor can withdraw during a COORDINATION review; merged report appears as
   "You said you saw this too"; no visible change to the area picture under rules-1.
7. Security/privacy review against §9 and §17 written into `PHASE9.md` (results file, created at implementation time).
8. Model cards for every active or candidate version; `CLAUDE.md`, `HANDOFF.md`, `API.md` updated with status in the
   project's vocabulary.
9. **ML status recorded honestly:** gate status (G1–G3) stated. No learned model deployed (the expected outcome).

---

## 23. Explicitly rejected approaches

| Rejected | Why |
|---|---|
| A learned danger/risk score per place or route | No ground truth; encodes reporting bias as "danger"; stigma; a gaming target; contradicts "never safe / never dangerous" |
| Hotspot or incident forecasting | Needs event history we delete on purpose; feedback loops; predictive-policing harms |
| Heat maps, KDE, kriging, empirical-Bayes smoothing, "similar neighbourhood" imputation | Invents evidence in empty cells; makes unknown look known |
| Learning from SOS, Safe Walk, check-ins, deviations, escalations, circle alerts or evidence | Correlates a victim's emergency with public data; violates isolation (`PHASE6.md` §7, Phase 8 §10) |
| On-device behaviour models (normal routes, walking speed, adaptive deviation or stop thresholds) | Continuous location history; an ML change to when "Are you OK?" fires changes when the Safety Circle is texted |
| Shake/fall/scream/voice ML | Automatic SOS triggers are forbidden; microphone use outside the Evidence rules |
| Chatbot, "safety assistant", LLM explanations or summaries | Hallucinated safety advice; untestable wording; context sent to a third party; a generic chatbot is out of scope |
| Isolation Forest / LOF / autoencoders in production | Honest small communities are the anomalies; no labels; opaque to moderators |
| GNN / node2vec / SybilRank | Too little data; no social graph; opaque |
| A person-level trust or risk score from ML; any automatic restriction | Moderation of people stays human (Phase 7); "AI must never punish" |
| Using account age in coord-1 | At launch everyone is new: the rule would fire on the whole community (base-rate problem); probation already handles age |
| Storing training examples now | No use until G1; data kept "in case" is exactly what the brief forbids |
| Python, an ML library or a model runtime in Phase 9 | Not necessary; a new dependency and attack surface for no current value |
| A generic per-request shadow framework | Shadow runs weekly over data; per-request shadow would add latency and view-shaped work for no benefit |
| Building `AreaIntelligenceModel` before a second implementation exists | YAGNI (F1). It is built with rules-2 |
| A toy model trained on test accounts for the viva | It would learn our own scripts; presenting it would be pretending |
| Federated learning, DP-SGD, on-device training | Nothing to learn on the device; complexity and leakage risk |
| External crime statistics | Policing bias, stigma, licensing; changes the product's nature |
| Third-party AI/ML APIs; remote model updates | Location and safety data would leave the system; supply-chain risk |
| Showing model scores or probabilities to anyone | False precision for users; automation bias for moderators |

---

## 24. Critical review of this design (done before finishing; what changed because of it)

| Lens | Question asked | Finding | Change made |
|---|---|---|---|
| Unnecessary AI | Is any component "AI" for its own sake? | An early draft ran an Isolation Forest "in shadow" in the backend | Removed. No ML library or Python in Phase 9. The only learned component (M1) is gated and probably never built |
| Fake or unsupported claims | Could anything be read as measured performance? | F5/F6 attack costs are derived from the rules, not run; F9 is from reading the code | Labelled as such. All simulator output is labelled SIMULATED. FCR is a lower bound. No accuracy number appears anywhere |
| Privacy leakage | Does any new artefact reveal places or people? | (a) A merged report stored as REPORT would show the merger a stranger's block in "your reports". (b) Merging into a pending report is a timing oracle. (c) A free-text dimension could hold a cell. (d) Showing moderators "areas spanned" sketches movement. (e) Counting pictures served would be a view log | (a) Stored as CONFIRM. (b) Visible targets only. (c) Upper-case-only CHECK. (d) Only banded signal counts are shown. (e) The snapshot is data-side |
| Overengineering | What can go? | Generic shadow framework; training-example table; new-account burst rule; a group-case schema | All removed. Shadow = weekly snapshot; examples deferred to G1; R3 dropped; one case per group, and RESTRICT neutralises the group |
| Lack of training data | Is the plan honest about it? | Yes: F2, F3, §4.3; gates G1–G3 with numbers; expected "never reached" | — |
| Unsafe automation | Can any Phase 9 output act on its own? | coord-1 originally also inherited the withdrawal lock (a detection oracle). An invalid model file could stop the server | coord-1's only effect is a case; no withdrawal lock; budget; an invalid artefact disables the model instead of blocking start |
| Weak explainability | Can a moderator or examiner follow every output? | Graph rules could be opaque | k-core explained in one sentence; named reason codes; no scores |
| Viva difficulty | Can each part be explained in two minutes? | Yes: ladder, simulator, counters, k-core, merge, rules-2 | Appendix B |
| Production risks | What breaks first in a real deployment? | coord-1 false positives at launch (sparse, isolated groups); moderator capacity; per-instance in-memory cadence. A first draft only excluded single-*area* groups, but neighbours walking around their own ~1 km area easily touch two area cells | SHADOW default; the exclusion widened to one *neighbourhood* (3 × 3 areas); group-level dismissal guard; budget; unreviewed share measured; graph rebuilt on each run, so a restart loses nothing |
| Previous decisions challenged | Do the new facts make any earlier choice weak? | (1) Phase 7: "withdrawal refused while a case is open" turns COORDINATION cases into an oracle (F8). (2) Phase 7 COORDINATION reopens after DISMISS (F9). (3) Phase 8 rule (b) gives "extra care" to two people (F5). (4) Phase 8's "extension point" was not built (F1). (5) Phase 6 exact-cell merging wastes honest corroboration (F7) | (1) Proposed change for COORDINATION only (FLAGS/REREPORTED keep the lock). (2) Fix 9C.0. (3) rules-2 candidate, the user decides. (4) Corrected in `HANDOFF.md`. (5) Adjacent merge, concerns only |

---

## 25. Risks and limitations (honest)

1. **The gates may never be met.** Then Phase 9 ends with no learned model. That is the intended honest result, and the
   evidence layer still pays for itself by validating the rules.
2. **coord-1 can be evaded** by rings that pay more (≥ 4 backers per signal) or that stay inside one neighbourhood
   (local damage, capped at TAKE_CARE for a pair by rules-2). Patient, independent-looking sybils remain the root
   residual since Phase 6.
3. **coord-1 false positives** (friends who walk together) cost moderator time. Nothing is hidden meanwhile.
4. **The adjacent merge shifts meaning slightly:** a signal shown at block x may be backed by someone ≤ ~210 m away. That
   is tighter than today's "I saw this too" (≤ ~1 km).
5. **rules-2 downgrades honest pairs** from "extra care" to "care". Both are amber; never "fine".
6. **Counters are approximate** (best-effort, after commit) and FCR is a lower bound (only what moderators caught).
7. **Simulator fidelity:** the scenarios encode our assumptions about attackers. The fidelity test guards the mechanics,
   not the realism.
8. **Two Phase 6/7 behaviours change** (merge; COORDINATION withdrawal). Both are explained here and both need the user's
   approval at implementation time.
9. **No moderators exist in the dev setup.** Every triage improvement depends on people actually working the queue
   (`PHASE7.md` §8).
10. **In-memory, per-instance cadence and budgets** (like every limiter since Phase 6). A cluster needs a shared store
    and its own privacy review.

---

## Appendix A: hard safety boundaries → how each is enforced

| Boundary (from the brief) | Enforcement | Test |
|---|---|---|
| AI never triggers SOS | No Phase 9 package references `emergency`/`journey`/`escalation`; no scheduling, push or outbound HTTP in `evaluation`/`intelligence` | `EvaluationIsolationTest`, `IntelligenceIsolationTest` |
| AI never contacts the Safety Circle | No reference to `safetycontact`/`circlealert`; Android safety code never reads intelligence | same + Android `IntelligenceIsolationTest` |
| Never accuses or identifies a person as dangerous | Place-only categories (V12 CHECK); coord-1 speaks about support patterns, to moderators, by case pseudonym; forbidden phrases in moderation texts | `ModerationTextsTest`, `CoordinationPolicyTest.outputHasNoPeopleOrCells` |
| Never punishes or moderates by itself | coord-1's only write is `openCase`; no quarantine, weight or standing change | `CoordinationApiIntegrationTest`, isolation scan of the coord-1 path |
| No reporter identity or movement history exposed | Client DTOs unchanged; moderators get bands only; counters cannot hold cells or ids | allow-list tests, V15 dry run, `AdjacentMergeApiIntegrationTest` |
| No continuous location tracking | Phase 9 adds no location use on the phone | Android changes limited to texts and moderation DTO fields (review) |
| No unnecessary personal data | V15 has no personal column; examples deferred to G1 | `EvaluationIsolationTest` (migration scan) |
| No black box where a rule is better | No ML runtime dependency; every output = rule + reason codes | `NoMlRuntimeTest` (backend + Android) |
| No chatbot or social features | None designed | — |
| No data collected to look impressive | Purpose per stored item (§8.1, §9 PR9); gates | review |

## Appendix B: viva notes

- **What / why:** we asked whether ML is justified and showed it is not, yet: no real data, no ground truth, deliberate
  forgetting. So Phase 9 builds what any ML would need first: measurement, adversarial evaluation and explainable fixes.
- **Key ideas:** the authority ladder (rules decide, evidence measures, models may only advise humans); attack cost as
  the security metric; k-core for coordination; adjacent merge so honest people are not split by a grid line;
  "extra care needs three people"; gates with numbers.
- **Key classes (planned):** `CoordinationPolicy`, `PictureInvariants`, `RulesTwo`, `OutcomeRecorder`, `WeeklySnapshot`,
  `EvaluationReport`, `AttackSimulator`.
- **API + DB flow:** events → after-commit counters (V15, weekly, no cells) → ADMIN report with intervals; job → live
  rows → in-memory graph → case for a human; report → same block or a visible neighbour → one signal.
- **Likely questions:** *Why no ML?* (no ground truth, no data, privacy deletes history; a model would learn our test
  scripts). *Then what is "AI" in Phase 9?* (the methodology an ML system needs, and one gated, human-advising use).
  *How do you know a rule works?* (simulated attack costs now; real outcome rates once people use it). *Why a k-core?*
  (it separates a group backing each other from a popular reporter). *Why does a coordination case not hide anything?*
  (an honest hostel looks the same; humans decide). *Why may contributors withdraw during a coordination review?* (a 409
  would tell a ring it was detected, and withdrawing removes the fake anyway). *Why can two people no longer make "extra
  care"?* (it is the strongest claim; three independent people is proportionate). *What if the gates are never met?*
  (then no model, and that is the honest outcome).
