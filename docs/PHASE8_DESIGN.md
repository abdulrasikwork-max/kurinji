# Phase 8: Safety Intelligence Engine: architecture and design

Written 2026-10-20. **Status update 2026-10-21: implemented (8A–8D), compiled, tested; see `docs/PHASE8.md` for
what was built, the deviations from this design (§2 there) and what was and was not verified.** This document is the
design as written in the design-only session; no code, migration, API, dependency or database state was changed then. Every design decision
here was made on the architect's own judgment under the user's brief of 2026-10-20 ("full autonomy over the design").
As `CLAUDE.md` §3 requires, implementation starts only when the user gives the go-ahead. It then runs in gated
sub-phases 8A–8D (§23), with a build and test between each.

---

## 0. Decisions at a glance

| Question | Decision (one line) |
|---|---|
| What is the engine? | A **deterministic, rule-based evidence model** with **two separate axes**: *how much concern is confirmed* and *how much is known*. It is computed **on read** from exactly the signals people can already see. There is no numeric "danger score" |
| What does it output? | An **Area Picture**: an outlook (5 values, none of which means "safe"), plain-language **reason codes**, the confirmed concern categories, and conflict and freshness markers. The server sends codes; the phone writes the words |
| ML? | **No ML in Phase 8.** There is no ground truth, the data is small and sparse, and the output must be explainable. A clean extension point (`modelVersion`, a policy interface, shadow mode) is defined for Phase 9 |
| Q1: history without surveillance | Everything is derived on read. The only new stored data is **one decayed number per (~1 km area, group)**. It is fed only by **finished, independently corroborated, unmoderated episodes**, at most one per week, with a 60-day half-life. It is forgotten below a threshold and reset by any moderator removal. No times, people or events are stored (**8C**, can be switched off) |
| Q2: heat map vs radar | **Keep the app-drawn Safety Radar** and add coarse area-cell shading that draws *how much is known*, not "danger". **No heat map** (it implies false precision and makes "no colour" look like "safe"). **No third-party tiles** (they leak where people look). Any future basemap must be offline and downloaded by region |
| Q3: weak evidence and routes | **Two channels.** CONFIRMED evidence (≥ 2 independent people) may influence ranking, within a bounded detour. LOW, unconfirmed or contested evidence goes only to the **uncertainty channel** ("unconfirmed reports along this way"). Unknown is neither safe nor dangerous: a route is recommended over another only when **similar amounts are known about both**. Positive signals never cancel concerns |
| Route data path | Phase 8 does **not** build a route planner. It defines the contract (§16) and one small consumer: an optional, user-tapped **"Check the way"** on the Safe Walk start screen. The phone computes it from a coarse **region pack** (~5 km cell). The server never receives a route, an origin or a destination for this (**8D**) |
| Safety rule | The engine is decision support. It has **no code path** to SOS, Safety Circle, emergency services, journeys, escalation or moderation of people. This is enforced by an isolation test (§18) |

---

## 1. Current-system understanding (verified from code, not only docs)

Inspected: `CLAUDE.md`, `docs/HANDOFF.md`, `docs/PHASE6.md`, `docs/PHASE7.md`, migrations V12 and V13,
`community/*` (all services, entities, DTOs, the job), `ModerationService.maintain`, `application.properties`,
`pom.xml`, the Android `feature/community` (ViewModel, models, policy), `core/location/GeoPoint.kt` (`RouteGeometry`),
`feature/safewalk/domain/MovementPolicy.kt` and the backend test inventory.

**What exists and what Phase 8 builds on:**

| Fact (verified in code) | Where |
|---|---|
| A signal = one row per (7-char geohash cell, category). There are 10 place-only categories in 3 groups: INCIDENT (lifetime 3 d), CONDITION (30 d), HAVEN (60 d) | `SignalCategory`, V12 |
| Only `counts_from` (server time + a random 10–30 min delay + new-account probation) is stored per contribution. No coordinates, no real report time | `CommunityService.countsFrom`, V12 |
| `SignalPolicy.assess` is pure. It returns visible, retired, expired, `supporters` (independent), precision AREA/BLOCK (k = 2 independent), support band, freshness band, disputed, **confidence LOW/MODERATE/HIGH**, reasons, and weighted support/dispute | `SignalPolicy` |
| Confidence: HIGH ⇔ I ≥ 3 and W − WD ≥ 7. MODERATE ⇔ I ≥ 2 and W − WD ≥ 4. Otherwise LOW. A single person is always LOW, even when TRUSTED | `SignalPolicy` |
| Independence: two accounts are linked when they share ≥ 3 live signals on one side **and** that is ≥ half of the smaller account's activity. Linked accounts count once (greedy, strongest first) | `Independence` |
| Private tiers weigh contributions (0/1/2/3 half-points). They are never returned. Counters halve every 90 days | `TrustPolicy`, `TrustService` |
| `/api/community/area?cell=<6>` returns the 3×3 neighbourhood of area cells. It drops anything not `moderation = NONE`, keeps only `visible` signals, sorts nearest first and **truncates to 100** | `CommunityService.area` |
| Look-ups are limited in memory: 120 requests/h and 15 distinct areas/h per account. They are never stored | `AreaQueryLimiter` |
| One scheduled job runs every 15 min: credit corroborated reports, delete expired, retired and empty signals, run moderation upkeep, decay standings. It logs counts only | `CommunityPurgeJob` |
| Moderation hides content (QUARANTINED, REMOVED tombstone 30 d). Hidden signals answer 404 | `CommunityService.visibleSignal`, V13 |
| The phone already has `GeoHash.encode`, `RouteGeometry.distanceToSegmentMeters` and the Safe Walk corridor (`MovementPolicy.corridorHalfWidthMeters`: 40 % of length, clamped to 250 m–2 km) | Android `core/location`, `feature/safewalk` |
| Backend dependencies: web, JPA, security, resource server, validation, Flyway, MySQL, test, H2. **No actuator or Micrometer**, so observability must use logs | `pom.xml` |

**Findings that affect the design (code behaviour the docs do not spell out):**

1. **Freshness counts linked support.** `SignalPolicy` computes freshness from the newest of *all* counted support
   (`countedSupport`), before the independence selection. So linked accounts can keep a signal's freshness at
   "TODAY". This is harmless for the radar, but intelligence uses "confirmed incident **today**" as its strongest
   rule. **Design response:** add `independentFreshness` (newest support among the *independent* selection) to
   `Assessment`. This is additive, so existing fields do not change. Intelligence uses only `independentFreshness` (§7.2).
2. **Truncation.** `area()` truncates to 100 signals *after* sorting by distance. The picture must be computed from
   the **full visible set**, before truncation. Otherwise a crowded neighbourhood would silently lose its farthest
   concerns.
3. **Visibility logic lives in one method body.** Intelligence must use *exactly* the same "what may anyone see"
   set. If it did not, aggregates could reveal pending, quarantined or retired signals (a timing or moderation
   oracle). **Design response:** extract one internal read path, `CommunityService.visibleSignals(areaCells, now)`.
   Both `area()` and the engine use it (§3).

No conflict was found between `CLAUDE.md` and the code for the parts Phase 8 touches.

---

## 2. Goals and non-goals

**Goals**
- G1. Turn the visible, trusted, moderated place signals into an **explainable area picture**: concern, how much is
  known, freshness, conflicts, and safe places.
- G2. **Represent uncertainty honestly.** "No data" is its own state and never means "safe". A single person never
  produces a concern level.
- G3. Learn **recurring** conditions over time **without** storing event history, times or people (Q1).
- G4. Define the **input contract** for a future Safe Route engine, plus one minimal, privacy-preserving consumer (Q3).
- G5. Keep every Phase 6 and Phase 7 privacy guarantee. Add no new personal data.
- G6. Keep the engine **pure, deterministic and fully unit-testable**, so every rule can be explained in a viva.

**Non-goals (Phase 8 will not)**
- Build a route planner, use real street geometry, or add a routing, maps or tiles dependency.
- Use ML, external datasets (crime statistics, population), or any data other than place signals.
- Use emergencies, SOS locations, Safe Walk tracks, deviations, check-ins, evidence or Safety Circle data as input.
- Trigger, suggest or change anything in SOS, Safe Walk escalation, Safety Circle or moderation of people.
- Send notifications, geofence, or use background location.
- Publish rankings ("most dangerous areas"), city-wide maps, or any bulk export.
- Show any number that looks like a probability or a score.

---

## 3. Architecture

```
                       ┌──────────────────────── backend (com.safesphere.backend) ────────────────────────┐
 phone: fix → geohash  │                                                                                   │
  GET /community/area ─┼─► CommunityController ─► CommunityService.area()                                  │
     ?cell=<6>         │                              │  AreaQueryLimiter (unchanged)                       │
                       │                              ▼                                                     │
                       │                 CommunityService.visibleSignals(cells, now)  ◄── ONE visibility path│
                       │                   (moderation NONE ∩ SignalPolicy.visible; same trust snapshot)    │
                       │                              │ List<VisibleSignal(signal, assessment)>             │
                       │              ┌───────────────┴────────────────┐                                    │
                       │              ▼                                ▼                                    │
                       │   signals[] (unchanged)        intelligence.AreaIntelligenceService                │
                       │                                   │  AreaIntelligencePolicy (PURE, rules-1)        │
                       │                                   │  + PatternReader (8C, area_patterns)           │
                       │                                   ▼                                                │
                       │                         picture {outlook, concern, information, reasons…}         │
                       │                                                                                   │
  GET /intelligence/   │   IntelligenceController ─► RegionQueryLimiter ─► AreaIntelligenceService.region()│
     region?cell=<5> ──┼─►  (8D)                       (in memory)          areas[] + confirmed signals[]   │
                       │                                                                                   │
                       │   CommunityPurgeJob (existing, every 15 min) ─► PatternService.maintain() (8C)     │
                       │        marks reached_confirmed; adds finished episodes; decays/forgets            │
                       │   ModerationService REMOVE ─► PatternService.forget(area, group) (8C)             │
                       └───────────────────────────────────────────────────────────────────────────────────┘
        NO arrows to or from: emergency, journey (Safe Walk), safetycontact, circle alerts, evidence.
```

**Backend components (new package `com.safesphere.backend.intelligence`)**

| Component | Kind | Responsibility |
|---|---|---|
| `AreaIntelligencePolicy` | pure, static | rules-1: visible signals (+ patterns) → `AreaPicture` per area cell and for a neighbourhood or region. No I/O, no clock reads (takes `now`) |
| `PatternPolicy` (8C) | pure | Decay, episode admission (weekly cap), RECURRING threshold, forget threshold |
| `AreaIntelligenceService` | `@Service`, read-only | Calls `CommunityService.visibleSignals`, loads pattern rows (8C), runs the policy, builds DTOs |
| `PatternService` (8C) | `@Service` | Called by the existing job and by `ModerationService`. Marks `reached_confirmed`, admits episodes, forgets |
| `IntelligenceController` (8D) | `@RestController` | `GET /api/intelligence/region` |
| `RegionQueryLimiter` (8D) | `@Component`, in memory | Region-pack budget per account (§19) |
| `IntelligenceProperties` | config record | `app.intelligence.*` (§14.4) |
| `IntelligenceDtos` | records | Field allow-lists (§13) |

**Changed (small, additive):** `SignalPolicy.Assessment` gains `independentFreshness`. `CommunityService` gains the
extracted `visibleSignals(...)`, used by `area()` with unchanged behaviour. `CommunityDtos.AreaResponse` gains
`picture`. `CommunityPurgeJob` calls `PatternService.maintain()` (8C). `ModerationService` REMOVE calls
`PatternService.forget` (8C).

**No background computation and no cache in 8A–8C.** The picture is a pure function of rows the area query already
loads, so it adds O(n) work to an existing request. A cache is added only if measurement shows a need (§19.6). If one
is added, it is keyed by **cell, never by user**, so it cannot become a viewer log.

---

## 4. Data flow

**A. "Around me" (8A/8B), the existing request, enriched**
1. The Community screen opens. The phone takes one fresh fix (unchanged), computes the 6-char cell, and sends
   `GET /api/community/area?cell=tdr1v9`.
2. `AreaQueryLimiter.check` runs (unchanged).
3. `visibleSignals(neighbourhood(cell), now)`: the same moderation filter, trust snapshot and `SignalPolicy` as today.
4. `signals[]` is built exactly as today (sorted, truncated to 100).
5. `picture`: `AreaIntelligencePolicy.neighbourhood(visibleAll, patterns, now)` runs on the full set, before truncation.
6. The phone maps the codes to words, then shows the Area Picture card and the area-cell shading on the radar. All
   of this is kept in memory only.

**B. Pattern upkeep (8C), inside the existing 15-minute job**
1. For each unmoderated signal with `reached_confirmed = false`: assess it. If confidence ≥ MODERATE, set it to true.
2. Before deleting a signal that **ended by expiry**: if `reached_confirmed`, moderation is NONE, there is no OPEN
   case, and the signal was not retired, then `PatternPolicy.admit(area_cell, group, today)`.
3. Decay every row lazily. Delete rows whose value is below the forget threshold.
4. Log one line with counts only ("n episodes added, m patterns forgotten").

**C. "Check the way" (8D)**
1. On the Safe Walk start screen the person types a destination. It is resolved on the phone (unchanged
   `DestinationResolver`). The person taps **Check the way**: nothing is automatic.
2. The phone samples the straight start→destination line every 100 m and collects the 5-char region cells
   (≤ 3, else "too long to check").
3. For each region: `GET /api/intelligence/region?cell=<5>`. The server returns area summaries and **confirmed**
   signals only.
4. The phone runs `CorridorBriefPolicy` (pure Kotlin) on the same corridor that Safe Walk watches and shows a brief.
5. The packs and the brief live in the ViewModel only. They are never stored and never sent with the journey, and
   they never reach `SafeWalkSession`, `CheckInPolicy`, `MovementPolicy` or `CircleAlertCoordinator`.

---

## 5. Intelligence model

### 5.1 Inputs (and only these)
Per visible signal: `category`, `group`, `cell` (7), `area_cell` (6), `precision`, `confidence`, `supporters` (I),
`independentFreshness`, `disputed`. Per area (8C): the decayed pattern value per group.

**Never used as input:** individual contributions, user ids, tiers (they act only *through* confidence, as today),
flags (flags are about abuse, not about the place), pending, quarantined, removed or retired signals, emergencies,
journeys, check-ins, evidence, or the identity, history or location of the viewer.

### 5.2 Evidence class per signal (an ordinal, not a number)

| Class | Rule | Meaning for people |
|---|---|---|
| `UNCONFIRMED` | confidence LOW | "One report / not yet confirmed" |
| `CONFIRMED` | confidence MODERATE | "Confirmed by people nearby" (≥ 2 independent people) |
| `STRONG` | confidence HIGH | "Strongly confirmed" (≥ 3 independent people, clear net support) |

Disputes are already netted inside `SignalPolicy` confidence (W − WD). A disputed signal keeps its class but also
adds `CONFLICTING_REPORTS` (§6.3).

### 5.3 Relevance (from `independentFreshness` and the group)

| Group | TODAY | THIS_WEEK | EARLIER |
|---|---|---|---|
| INCIDENT | `CURRENT` | `RECENT` | (cannot occur, lifetime 3 d; treated as `RECENT` defensively) |
| CONDITION | `CURRENT` | `CURRENT` | `AGING` ("may have changed") |
| HAVEN | `CURRENT` | `CURRENT` | `AGING` ("check it is still open") |

### 5.4 Concern level (per ~1 km area cell)
Let *C* = CONFIRMED or STRONG signals in the cell whose group is INCIDENT or CONDITION.

| Level | Rule (first match wins) |
|---|---|
| `ELEVATED` | (a) a STRONG incident that is CURRENT; or (b) ≥ 2 signals in *C* that are not AGING; or (c) (8C) ≥ 1 CURRENT or RECENT incident in *C* **and** the area's INCIDENT pattern is RECURRING |
| `SOME` | ≥ 1 signal in *C* (AGING included) |
| `NONE_CONFIRMED` | otherwise (unconfirmed reports may exist) |

**Invariant:** every non-`NONE_CONFIRMED` level needs ≥ 2 independent people *on at least one signal*. No single
account, and no linked group, can raise a concern level. Rule (c) also needs ≥ 3 earlier corroborated episodes (§7.4).

**Neighbourhood or region level = the maximum over its area cells.** It is never a sum. Summing would rate larger or
busier areas as worse just for having more people reporting. The picture names *where* the highest cell is
(direction from the viewer, computed on the phone).

### 5.5 Information level (how much is known), a separate axis

`n_concern` = CONFIRMED+ concern signals, `n_haven` = CONFIRMED+ havens, `n_unconfirmed` = UNCONFIRMED signals,
`p` = the area is RECURRING (8C). (Implementation note: the first draft said "a row with value ≥ 1"; that would reveal a
single past episode and break the 3-episode k-gate, so only RECURRING counts.)
`known = n_concern + min(n_haven, 4)`. Havens count at most 4. This is the **lure guard**: a flood of "safe place"
signals cannot make an area look well understood.

| Level | Rule |
|---|---|
| `NONE` | `known = 0`, `n_unconfirmed = 0`, no `p` |
| `LITTLE` | `known ≤ 1` (any unconfirmed reports or a pattern still leave it LITTLE) |
| `SOME` | `2 ≤ known ≤ 4` |
| `MORE` | `known ≥ 5` |

### 5.6 Outlook (the headline): 5 values, none of them "safe"

| Outlook | Condition | Example of what the phone says |
|---|---|---|
| `NO_INFORMATION` | information NONE | "No community information here yet. That does not mean it is safe or unsafe." |
| `LITTLE_INFORMATION` | concern NONE_CONFIRMED, information LITTLE | "Little community information here. There is an unconfirmed report of poor lighting." |
| `NO_CONFIRMED_CONCERNS` | concern NONE_CONFIRMED, information SOME/MORE | "No confirmed concerns have been reported here recently. Community reports only cover what people share." |
| `TAKE_CARE` | concern SOME | "Take care: confirmed reports of poor lighting nearby." |
| `TAKE_EXTRA_CARE` | concern ELEVATED | "Take extra care: recent confirmed reports of poor lighting and an isolated stretch nearby." |

Colours follow `CLAUDE.md` §10. NO_INFORMATION, LITTLE_INFORMATION and NO_CONFIRMED_CONCERNS use **neutral/teal
info styling, never green**, because green means "safe" in the design system. TAKE_CARE and TAKE_EXTRA_CARE use
amber with an icon and text. **Red is never used**, because red is reserved for emergencies. Status is never shown
by colour alone.

### 5.7 Reason codes (ordered, aggregate, nothing about people)

`CONFIRMED_INCIDENT_TODAY`, `CONFIRMED_INCIDENT_THIS_WEEK`, `CONFIRMED_CONDITION`, `CONDITIONS_MAY_HAVE_CHANGED`,
`CONFLICTING_REPORTS`, `UNCONFIRMED_REPORTS`, `RECURRING_CONCERNS` (8C), `SAFE_PLACES_NEARBY`, `LITTLE_INFORMATION`.
Plus `concernCategories` (the CONFIRMED+ concern categories present, e.g. `[POOR_LIGHTING, ISOLATED_STRETCH]`).
These are the words in the prompt's example sentence, and each is already public on the individual signals.

### 5.8 Worked examples (these become test cases)

| # | Visible signals in the viewer's area cell | Picture |
|---|---|---|
| 1 | none, no pattern | NO_INFORMATION |
| 2 | 1× POOR_LIGHTING, LOW | LITTLE_INFORMATION + UNCONFIRMED_REPORTS |
| 3 | POOR_LIGHTING MODERATE + ISOLATED_STRETCH MODERATE, both THIS_WEEK | TAKE_EXTRA_CARE (rule b), categories [POOR_LIGHTING, ISOLATED_STRETCH] |
| 4 | HARASSMENT HIGH, TODAY | TAKE_EXTRA_CARE (rule a) + CONFIRMED_INCIDENT_TODAY |
| 5 | HARASSMENT HIGH, TODAY **only by linked accounts' freshness** (independent support 3 days old) | TAKE_CARE (independent freshness = THIS_WEEK → RECENT; rule a not met). This is finding 1, closed |
| 6 | POOR_LIGHTING MODERATE + WELL_LIT_BUSY MODERATE in the **same block** | TAKE_CARE + CONFLICTING_REPORTS. The haven does **not** cancel the concern |
| 7 | 9× havens CONFIRMED, nothing else | NO_CONFIRMED_CONCERNS (information SOME, capped by the lure guard) + SAFE_PLACES_NEARBY |
| 8 | 1× BROKEN_PATH MODERATE, EARLIER | TAKE_CARE + CONDITIONS_MAY_HAVE_CHANGED |
| 9 | HARASSMENT HIGH but QUARANTINED | the same as if absent |
| 10 | a report still in its publication delay | the same as if absent (no timing oracle) |
| 11 | THEFT MODERATE RECENT + INCIDENT pattern RECURRING (8C) | TAKE_EXTRA_CARE (rule c) + RECURRING_CONCERNS |
| 12 | no live signals, INCIDENT pattern RECURRING | LITTLE_INFORMATION + RECURRING_CONCERNS ("concerns were confirmed here repeatedly in recent months; none right now"). A pattern alone **never** raises the concern level |

---

## 6. Confidence and uncertainty model

### 6.1 Principle: two axes, never collapsed
Many systems compute a single score, so "unknown" and "fine" both come out as a low number. SafeSphere keeps
**concern** (§5.4) and **information** (§5.5) as separate fields in every response, every UI string and the route
contract. That is the whole uncertainty model, and it is enough. It is the subjective-logic idea of keeping belief
and uncertainty separate, without inventing priors and probabilities we cannot calibrate (§20).

### 6.2 Uncertainty sources and how each is represented

| Source | Representation |
|---|---|
| Nobody reported anything | information NONE → NO_INFORMATION ("not the same as safe") |
| Only one person | UNCONFIRMED class → never affects the concern level; `UNCONFIRMED_REPORTS` reason |
| Old conditions | AGING → still a concern, cannot trigger ELEVATED (b); `CONDITIONS_MAY_HAVE_CHANGED` |
| Disagreement | `CONFLICTING_REPORTS` (§6.3) |
| Phone-asserted location | Inherent; precision rules unchanged; disclosed in the footer text |
| Reporting bias (who uses the app) | Information axis + wording "only covers what people share"; never city-wide comparisons |
| Moderation in progress | Excluded entirely (hidden content does not exist for others) |

### 6.3 Conflicting evidence
Conflict exists when (i) a CONFIRMED+ concern is `disputed` ("some say this has changed"), or (ii) contradictory
categories are both CONFIRMED+ **in the same 7-char block**: `WELL_LIT_BUSY` vs `POOR_LIGHTING`, and `WELL_LIT_BUSY`
vs `ISOLATED_STRETCH`. AREA-precision signals cannot be placed in a block, so they never form a contradiction.

Handling: **show both and net nothing.** The concern keeps its level. The picture adds `CONFLICTING_REPORTS`
("People disagree about this place"). Why asymmetric: a false "it's fine" can lure someone into danger, while a
false concern only costs a detour. The opposite attack (inventing concerns to divert people) needs ≥ 2 independent,
non-linked, non-probation accounts plus surviving flags and moderation. That is accepted and documented (§22).

### 6.4 "When uncertain, do less": the explicit rules
1. One person never produces a concern level, a route influence, or a pattern episode.
2. A pattern alone never raises the concern level.
3. Positive signals never cancel or lower a concern.
4. Missing data is never filled in from neighbouring cells (no smoothing or interpolation).
5. Unknown values in any DTO make the phone **hide the card**. It never guesses.
6. Nothing in the engine can start, suggest or change an emergency, a walk's escalation, an alert or a moderation
   action against a person.

---

## 7. Temporal model (and Open Question 1)

### 7.1 What already handles time (unchanged)
Per-category lifetimes (3/30/60 d), confirmations extend life, `counts_from` is the only stored time, freshness is
banded, and rows are deleted at expiry. Phase 8 does not touch this.

### 7.2 Independent freshness (closes finding 1)
`Assessment.independentFreshness` = the band of the newest `countsFrom` among the **independent selection**. The
radar keeps using `freshness` (no visible change). Intelligence uses only `independentFreshness`, so a sock-puppet
group cannot keep "confirmed incident today" alive.

### 7.3 Time of day / day of week: decided **against** collection
- **Collecting** time-of-day would mean storing when incidents happen. Phase 6 deliberately never stores the real
  time, because it is the most identifying fact. Exact times are also sparse noise at ~1 km and weeks of data.
- **Instead, the relevance lives in the category's meaning.** `POOR_LIGHTING` and `ISOLATED_STRETCH` are
  *night-relevant* by definition. The **phone** uses its own local clock (never sent) to put night-relevant
  conditions first after dark, and the route contract carries `nightRelevant` per category (§16). This gives most
  of the value at zero privacy cost.
- Evolution path, if ever justified: a 3-band "part of day" dimension **inside the decayed pattern value only**
  (never per contribution), behind the same k-gate. It would need its own privacy review.

### 7.4 Open Question 1: privacy-preserving aggregation over time

**Problem.** Incidents live 3 days. Without memory, an area where harassment is confirmed every week looks the same
as an area where it happened once. With memory, we risk building a long-lived surveillance record of "what happened
where and when", and stigmatising neighbourhoods.

**Alternatives considered**

| Option | Privacy | Value | Attack resistance | Verdict |
|---|---|---|---|---|
| A. No history at all (status quo) | best | no recurrence | n/a | loses the one thing users cannot see themselves |
| B. Keep expired signals and contributions (soft delete) | **breaks** Phase 6 retention promise; keeps user ids | high | poor (old data mined) | rejected |
| C. Event log per area (category + date per episode) | dated history of incidents per ~1 km | high | medium | rejected: a timeline is exactly the surveillance artefact to avoid |
| D. Time-series snapshots of computed outlooks | a periodic record of every area | medium | poor | rejected: data that has no use except history |
| E. **One decayed value per (area, group), fed only by finished, corroborated, unmoderated episodes, with k-gate, cap, weekly admission, decay and forget threshold, reset on removal** | no people, no events, no times (one internal date for the decay and the weekly cap) | enough for "recurring" | good (§12) | **chosen** |
| F. Differential-privacy noisy counters | strong formally | poor (noise swamps tiny counts; explanations would contradict what people see) | good | rejected for per-area use; noted for any future bulk statistics (Phase 9) |

**Chosen solution: `area_patterns` (8C)**

- One row per (`area_cell` 6, `signal_group` ∈ {INCIDENT, CONDITION}). Havens need no memory: they are long-lived.
- **Episode** = a signal that **ended by expiry** after having **reached MODERATE confidence** (≥ 2 independent
  people, marked by the job in `place_signals.reached_confirmed`), with moderation NONE and no OPEN case. Retired
  ("no longer true") or removed signals are **never** episodes. That is conservative: disputes suppress memory.
- **Admission:** at most **one episode per (area, group) per 7 days** (`next_episode_on`). A burst of signals is one
  episode, so a farm needs ≥ 3 separate weeks of corroborated, unflagged activity to create a pattern.
- **Value:** `v ← min(4, v·2^(−days/60) + 1)` on admission. Decay is applied lazily (half-life **60 days**).
- **Exposure:** `RECURRING` when `v ≥ 2.5` (needs ≥ 3 episodes, so at least 3 corroborated signals and 6 independent
  supports). Only the band is exposed, never `v`, never episode counts, never dates.
- **Forgetting:** the row is deleted when `v < 0.25`. A single episode is forgotten after 2 half-lives (~120 days).
  The maximum is 4 → 0.25 = 4 half-lives, ~**240 days after the last episode**. No row can live longer.
- **Reset:** any moderator `REMOVE` of a signal in that area deletes that (area, group) row. A coordinated campaign
  that was caught leaves no lingering stigma.
- **Switch:** `app.intelligence.patterns-enabled` (default true after 8C verification). Turning it off simply makes
  `RECURRING_CONCERNS` disappear. Nothing else depends on it.

**Stored / derived / expiring / never stored**

| Stored | Derived on read | Expires | Never stored |
|---|---|---|---|
| `area_patterns(area_cell, signal_group, value, decayed_on DATE, next_episode_on DATE)`; `place_signals.reached_confirmed` (deleted with the signal) | outlook, concern, information, reasons, conflicts, relevance, region packs, corridor briefs | signals and contributions (unchanged lifetimes); pattern rows below 0.25 | episode lists or dates, which signals formed a pattern, who contributed, computed pictures, area/region/route requests, viewer locations, time of day, any link to journeys or emergencies |

**Why this fits SafeSphere.** It answers the one question users cannot answer themselves ("does this keep
happening?"). It stores less than the existing trust standings (which already keep decayed counters). It inherits
k = 2 independence for every episode. It cannot be linked to any person, and it forgets by design.

**Risks.** A patient, well-separated sybil farm could build a pattern over weeks (mitigated by weekly admission,
corroboration, flags and moderation reset; residual risk documented). The neighbourhood-stigma risk is bounded by
decay, by "never raises the level alone", and by place-only wording. The `decayed_on` / `next_episode_on` dates reveal
roughly when an area last had an episode, internal only, at day precision. **Evolution:** the part-of-day dimension
(§7.3) or Phase 9 analytics could read this table, never anything finer.

---

## 8. Spatial model (and Open Question 2)

### 8.1 Cells (all existing geohash sizes, plus one coarser)

| Size | Chars | Approx. (India) | Phase 8 use |
|---|---|---|---|
| Block | 7 | ~150 m | contradiction checks; corridor matching (confirmed signals only) |
| Area | 6 | ~1.2 × 0.6 km | **unit of the outlook**, information, patterns |
| Neighbourhood | 3×3 of 6 | ~3.6 × 1.8 km | "around me" picture (= the existing area query) |
| Region | 5 | ~4.9 × 4.9 km | **region pack** for "Check the way" (8D) |

- **Proximity** = cell membership. No radius queries and no distance-weighted kernels: a kernel would spread one
  confirmed report over neighbouring blocks, inventing evidence where nobody observed anything.
- **Aggregation upward is max, not sum** (§5.4).
- **Sparse areas:** the information axis makes sparseness visible instead of interpreting it. Population-aware cell
  sizes remain impossible without population data (Phase 6 §7). The k-gates on precision (k = 2) and patterns
  (≥ 3 episodes) protect reporters in sparse areas.
- **Geohash rectangles** shrink in longitude away from the equator. This is fine for India and documented (Phase 6).

### 8.2 Open Question 2: heat maps vs the app-drawn Safety Radar

**Problem.** People want to *see* the area. Common practice (Google Maps SDK + heat-map layer) has privacy, honesty
and dependency costs.

**Alternatives**

| Option | 3rd-party leak | False precision | "No colour = safe"? | Offline | Cost/keys | Anti-scraping | Route-ready | Verdict |
|---|---|---|---|---|---|---|---|---|
| A. Online tile map + heat-map layer | **every viewed location goes to the tile provider** | high (smooth gradients from a few cells) | **yes** | no | API key, billing | weak (map invites browsing) | yes | rejected |
| B. Online tiles + markers only | yes | medium | partly | no | key | weak | yes | rejected |
| C. **App-drawn radar (existing) + coarse area-cell shading of the 3×3 neighbourhood by information level and outline by concern** | none | none (cells are drawn as cells) | **no**: unknown is drawn as hatched "unknown", not as empty | yes (draws whatever is in memory) | none | strong (shows only "around me") | partial (schematic corridor) | **chosen** |
| D. Offline vector basemap (self-hosted OSM extract, downloaded per region) + app overlay | none if downloaded by region | low | no | yes | storage, new dependency, licence attribution | medium | yes | **deferred** to the route-planner phase (§16.4) |

**Chosen: C.** Keep `SafetyRadarView` and add one layer: the 9 area cells drawn as rectangles in the radar's
coordinate frame. Each gets a fill pattern by **information** (none = light hatching labelled "not known", little =
dotted, some/more = plain) and an amber outline when its concern is TAKE_CARE or TAKE_EXTRA_CARE (with the text
label in the card). This is an extension of one view, not a UI redesign.

**Why it is best for SafeSphere.** No new dependency or key. No third party learns where anyone looks (the Phase 6
decision stands). It works offline with what is in memory. Drawing *cells* states the real precision. Hatching makes
"unknown" a visible state instead of an empty, reassuring space. The radar is centred on the person, so it does not
invite remote browsing.

**Risks and limitations.** No street context: people must relate "north-east, ~800 m" to the real world. Cell shading
is coarse (~1 km) by design. **Evolution:** if a route planner (Phase 8+) needs streets, use option D: a basemap
downloaded **by region**, with the same privacy as region packs, never streamed per view.

---

## 9. Trust and moderation integration

| Phase 7 element | How Phase 8 uses it |
|---|---|
| Private tiers / weights | **Only indirectly**, through `confidence` (already trust-weighted). The engine never reads tiers. A TRUSTED single person stays UNCONFIRMED |
| Independence | Through `supporters` and confidence; plus `independentFreshness` (§7.2); pattern episodes need MODERATE (≥ 2 independent) |
| Moderation state | Only `NONE` signals are visible, so only they feed anything. A signal under an OPEN case at expiry is **not** an episode |
| REMOVE decision | Resets that (area, group) pattern (8C) |
| Flags | **Not evidence about the place.** They feed moderation, which in turn hides content. Flags never move a concern level |
| Retirement ("no longer true") | Retired signals disappear (unchanged) and are never pattern episodes |
| Community pause (RESTRICTED) | Unchanged: weight 0, so the person's contributions stop counting immediately. Viewing the picture stays allowed (safety information is never withheld) |
| COORDINATION cases | Unchanged. The engine adds no automatic actions on people |

The engine **never** writes to standings, flags, cases or the audit log. Its only write path (8C) is pattern
bookkeeping on content-level rows.

---

## 10. Privacy model

**Invariants (each one is tested, §18)**
1. **Same-set rule:** every aggregate is computed from exactly the set `visibleSignals` returns. Pending, quarantined,
   removed and retired signals cannot influence any output, so there is no timing or moderation oracle.
2. **No new personal data:** no new column holds a user id, coordinate, timestamp of a person, device, IP or free text.
3. **No viewer trail:** area, region and route requests are never stored or logged with cells. Limiters are in memory
   (the Phase 6/7 decision).
4. **Region packs never contain single reports individually.** Only CONFIRMED+ signals (already public at block
   precision) and per-area summaries are included. No signal ids, so no link to `/area` interactions.
5. **No correlation by design:** the `intelligence` package does not import, and is not imported by, `emergency`,
   `journey`, `safetycontact`, circle-alert or `evidence` (isolation test). On the phone, `core/intelligence` is not
   imported by `SafeWalkSession`, `CheckInPolicy`, `MovementPolicy`, `CircleAlertCoordinator`, `feature/emergency`
   or `core/tracking`.
6. **Phone stores nothing new.** Pictures, packs and briefs live in ViewModels. They are not written to DataStore or
   disk. A cached picture would itself reveal where the person was.
7. **Server sends codes; the phone writes words.** No server-side free text, and localisation stays on the phone.

**Residual correlation risk (honest):** when a person taps "Check the way" and then starts a walk with movement
checks, the server receives a region request and later (in another endpoint) the destination coordinates, with the
same JWT, minutes apart. The server does not log region requests, so it cannot join them later. A malicious operator
with live request logging could. Mitigations: the request is user-initiated, at ~5 km granularity, and is
**strictly less** than what the journey endpoint already receives. Documented in §22.

---

## 11. Threat model (additions to `PHASE6.md` §2 and `PHASE7.md` §2)

| # | Threat | Defence | Residual |
|---|---|---|---|
| T1 | Fake reports to mark an area "extra care" | Concern levels need ≥ 2 **independent**, post-probation, unrestricted people per signal; ELEVATED needs STRONG (≥ 3) or 2 confirmed signals; flags + quarantine | Patient, well-separated sybils (Phase 7 residual) |
| T2 | Coordinated ring keeps an incident "today" | `independentFreshness` (§7.2) | Truly independent colluders |
| T3 | Lure attack: fake havens / "well lit" over a dangerous place | Positives never cancel concerns (§6.3); havens capped in information (§5.5); havens never improve route rank (§16) | A place with **no** concern reports can still be advertised as a haven by ≥ 2 independent people (as in Phase 6/7) |
| T4 | Malicious "no longer true" to erase a real concern | Unchanged Phase 7 retirement rule (≥ 2 independent, weight ≥ support); disputes add CONFLICTING instead of silently lowering | Large independent groups |
| T5 | Malicious withdrawal | Only one's own contribution; refused under review; others' support stays | — |
| T6 | Report bombing to build a long-term stigma (pattern) | Episodes only from finished, MODERATE, unmoderated, unretired signals; one per week; decay; reset on REMOVE; pattern never raises the level alone | Months-long campaigns that survive moderation |
| T7 | Geographic probing for hidden or pending signals | Same-set rule: outputs are a function of already-visible signals; no pending or quarantined influence | — |
| T8 | Differencing two pictures to locate a single report's block | Single reports are UNCONFIRMED and only counted per area cell (already public at area precision); the outlook never uses block cells of UNCONFIRMED signals | — |
| T9 | Scraping the whole city | Area: unchanged limits. Region: 6 distinct regions/24 h, 20 requests/24 h, new accounts 2 regions; packs contain no single reports, no ids | Many accounts × budget (as Phase 6/7) |
| T10 | Inferring reporter identity | No new identity-bearing fields; no times; aggregates follow the already-delayed visibility | Server/DB breach (unchanged) |
| T11 | Targeted location attack (fake concern at a home or business) | Place-only categories, k = 2 precision, TARGETS_SOMEONE flags, REMOVE resets the pattern, max-not-sum | As Phase 7 |
| T12 | Algorithm gaming (learning thresholds) | Thresholds are public by design (explainability). Gaming them still costs independent people; rules count *people*, not volume | — |
| T13 | Stale data → dangerous false confidence | Signals expire; AGING; never "safe"; NO_CONFIRMED_CONCERNS always with an information level and the "only what people share" footer; phone never shows a stored picture | People over-trusting any app (wording + footer) |
| T14 | Sparse data misread as safe | Information axis; NO_INFORMATION text says so explicitly; route rule "compare like with like" | — |
| T15 | Correlating community ↔ Safe Walk/SOS | Isolation tests; no shared tables; no logs; phone-side brief | Live-logging operator (§10) |
| T16 | Engine used as an emergency authority | No code path; isolation test; texts reviewed against a forbidden-phrase list | — |
| T17 | Region pack as a cheap scraping unit | Lower effective budget than `/area` (6 regions ≈ 22 neighbourhoods/day vs 15 areas/h today); confirmed-only content | — |
| T18 | Denial by inventing concerns to divert people | Needs ≥ 2 independent people + survives flags; diversion is bounded by the detour budget (§16) | Accepted: a false detour is less harmful than a false "fine" |

---

## 12. Anti-gaming strategy (summary)

1. **Count people, not volume.** Every rule that raises concern is expressed in independent people (I) or confirmed
   signals, never raw report counts.
2. **Reuse, do not duplicate, Phase 7 defences.** Probation, independence, weights, flags, quarantine and limits all
   act *before* the engine sees anything (same-set rule).
3. **Asymmetry against lures.** Positives inform, never cancel.
4. **Rate of change limits.** Weekly episode admission; patterns need weeks; incidents need TODAY by
   *independent* support.
5. **Max, not sum.** Busy areas are not penalised for reporting volume.
6. **Human moderation resets memory.** REMOVE clears the pattern.
7. **Transparent thresholds.** Gaming is not prevented by secrecy but by cost. That is honest and explainable in a viva.

---

## 13. API design

### 13.1 8A: `GET /api/community/area?cell=<6>` gains `picture` (additive, same limiter, no new endpoint)

```json
{
  "area": "tdr1v9",
  "signals": [ /* unchanged */ ],
  "picture": {
    "modelVersion": "rules-1",
    "outlook": "TAKE_EXTRA_CARE",
    "concern": "ELEVATED",
    "information": "SOME",
    "reasons": ["CONFIRMED_CONDITION", "CONFLICTING_REPORTS", "SAFE_PLACES_NEARBY"],
    "concernCategories": ["POOR_LIGHTING", "ISOLATED_STRETCH"],
    "highestConcernCell": "tdr1vc",
    "areas": [
      { "cell": "tdr1v9", "outlook": "TAKE_CARE", "information": "LITTLE" },
      { "cell": "tdr1vc", "outlook": "TAKE_EXTRA_CARE", "information": "SOME" }
      /* … all 9 cells, NO_INFORMATION included */
    ]
  }
}
```

- An **allow-list record**: no ids, counts, times, weights, tiers or pattern values.
- `highestConcernCell` is a 6-char area cell already in the response, so it adds no precision. The phone turns it into
  "north-east, about 1 km".
- An old app ignores `picture` (Gson ignores unknown fields). An old server omits it, and the app hides the card.

### 13.2 8D: `GET /api/intelligence/region?cell=<5>`

```json
{
  "region": "tdr1v",
  "modelVersion": "rules-1",
  "complete": true,
  "areas": [ { "cell": "tdr1v9", "outlook": "TAKE_CARE", "concern": "SOME", "information": "LITTLE",
               "reasons": ["CONFIRMED_CONDITION"], "concernCategories": ["POOR_LIGHTING"], "conflicting": false } ],
  "signals": [ { "category": "POOR_LIGHTING", "group": "CONDITION", "cell": "tdr1v9k",
                 "confidence": "MODERATE", "freshness": "THIS_WEEK", "disputed": false } ]
}
```

- `areas` lists only cells whose information ≠ NONE. **Absent means NO_INFORMATION** (documented and tested; the
  phone fills the gaps explicitly).
- `signals` = CONFIRMED+ only, at block precision (already public), **no public id**. `freshness` here =
  `independentFreshness`.
- `complete = false` if more than `app.intelligence.region-max-signals` (500) qualify. The phone then says "Too much
  to check here" and shows **no brief**, because a partial brief could miss a concern.
- Validation: exactly 5 geohash characters (`^[0-9b-hjkmnp-z]{5}$`), else 400. 401 without JWT. 429 from
  `RegionQueryLimiter`.

### 13.3 What does not exist (deliberately)
No bbox or radius queries, no "worst areas", no history endpoint, no pattern values, no per-signal intelligence
beyond `/area`, no route or polyline endpoint, no admin or bulk export. No write endpoints are added: Phase 8 is
read-only for clients.

---

## 14. Database design

### 14.1 Reused, unchanged
`place_signals`, `signal_contributions`, `contributor_standing`, `signal_flags`, `moderation_cases` (the OPEN-case
check). **8A and 8B need no migration.**

### 14.2 V14 (8C only): `V14__area_patterns.sql` (planned)

```sql
CREATE TABLE area_patterns (
    area_cell       CHAR(6)      NOT NULL,          -- ~1 km geohash; never finer
    signal_group    VARCHAR(12)  NOT NULL,          -- INCIDENT | CONDITION
    pattern_value   DECIMAL(5,3) NOT NULL,          -- decayed episode weight; never returned
    decayed_on      DATE         NOT NULL,          -- UTC day the value was last decayed; internal only
    next_episode_on DATE         NOT NULL,          -- weekly admission cap; internal only
    PRIMARY KEY (area_cell, signal_group),
    CONSTRAINT ck_area_patterns_cell  CHECK (REGEXP_LIKE(area_cell, '^[0-9b-hjkmnp-z]{6}$', 'c')),
    CONSTRAINT ck_area_patterns_group CHECK (signal_group IN ('INCIDENT', 'CONDITION')),
    CONSTRAINT ck_area_patterns_value CHECK (pattern_value >= 0 AND pattern_value <= 4)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

ALTER TABLE place_signals
    ADD COLUMN reached_confirmed BOOLEAN NOT NULL DEFAULT FALSE;  -- set by the job; deleted with the row
```

- **No FK, no user id, no signal id.** It is deliberately unlinkable to the rows that produced it.
- The PK doubles as the read index (`area_cell IN (…)` covers the neighbourhood and region, 9 or 32 cells).
- Dates are `DATE` (day precision) on purpose: `DATETIME(6)` would record more than the cap needs.
- Verification plan: apply on MySQL 8.0 and run a rolled-back constraint dry run (bad cell, upper case, unknown
  group, value 4.5, value −1), the same practice as V12/V13. H2 does not check these (CLAUDE.md §6).

### 14.3 Retention
Signals and contributions are unchanged. `reached_confirmed` goes with its signal. A pattern row is deleted when its
decayed value is < 0.25 (≤ ~240 days after the last episode) or on REMOVE in that area and group. Purge runs in the
existing job; reads apply decay lazily, so correctness never depends on the job.

### 14.4 Configuration (`app.intelligence.*`, planned)
`patterns-enabled=true`, `pattern-half-life=60d`, `pattern-cap=4`, `pattern-recurring=2.5`, `pattern-forget=0.25`,
`pattern-min-gap=7d`, `region-requests-per-day=20`, `region-distinct-per-day=6`, `region-distinct-new-account=2`,
`region-max-signals=500`. They are validated at start-up (for example forget < 1 ≤ recurring ≤ cap). Invalid values
make start-up fail, the same as the JWT secret rule.

### 14.5 Must NOT be stored
Computed pictures, region or area request logs, corridor briefs, routes, viewer cells, time of day, episode lists,
per-person intelligence, and any join to journeys or emergencies.

---

## 15. Android integration

**8B: Community screen**
- `core/intelligence` (shared by Community and Safe Walk): enums `AreaOutlook`, `ConcernLevel`, `InformationLevel`,
  `PictureReason`; `AreaPicture`, `AreaCellPicture`; DTOs with nullable fields. Mapping rule: any unknown enum value
  ⇒ **picture = null (card hidden)**. Unknown reason codes are dropped individually. `toString()` redacts cells.
- `RemoteCommunityRepository.area` maps `picture`. `AreaState.Ready` gains `picture: AreaPicture?`. Everything else
  in `CommunityViewModel` is unchanged.
- `IntelligenceTexts` (pure, unit-tested) turns codes into strings. It also orders night-relevant categories first
  when the phone's local time is between 19:00 and 06:00. That is a local display choice; the time is never sent.
- UI: one **Area picture** card above the radar (reuses existing components): headline, "How much is known here:
  little", up to 3 reason lines, the direction of the highest-concern cell, and the fixed footer: *"Based on community
  reports. It cannot tell you that a place is safe."* `SafetyRadarView` gains the area-cell layer (§8.2). TalkBack
  reads the card as one item (headline + information level).
- States: Locating / Loading / NeedsPermission / LocationProblem / Failed are unchanged. **Ready without picture**
  shows the radar as today. **Offline** keeps the existing failure state: no stored picture is ever shown as current.

**8D: Safe Walk "Check the way"**
- `core/intelligence/IntelligenceApi.region(cell)`, `IntelligenceRepository` (`ApiCallHandler`), `RegionPack`.
- `feature/safewalk/domain/CorridorBriefPolicy` (pure). Inputs: start, destination, packs, `localNight`. Corridor =
  `MovementPolicy.corridorHalfWidthMeters` (the same area the walk watches). Output `CorridorBrief`: confirmed
  concerns along the way (categories, strongest relevance), unconfirmed-reports note, conflicts, havens near the line
  (band + nearest), **coverage** (share of line cells with NO_INFORMATION → `MOSTLY_UNKNOWN` / `PARTLY_KNOWN` /
  `MOSTLY_KNOWN`), and night emphasis.
- `SafeWalkViewModel` gains `onCheckTheWayClicked()` and a `CheckState` (Idle / Checking / Ready(brief) /
  TooLong / TooMuch / Failed / NoDestination). The **Start** button is never blocked or delayed by it.
- Texts: "Along the way: confirmed reports of poor lighting. Little is known about most of the route. 2 safe places
  near the way." Never "safe route".

**Permissions: none new. Dependencies: none new.**

---

## 16. Future route-engine integration (and Open Question 3)

### 16.1 Open Question 3: low-confidence signals and route recommendations

**Problem.** A route engine must rank alternatives. Weak, sparse or conflicting evidence can mislead in both
directions: treating it as danger diverts people on the basis of one account's claim, and ignoring it entirely hides
useful warnings. Treating unknown as safe is the worst error of all.

**Alternatives**

| Option | Effect | Problem |
|---|---|---|
| A. All signals weighted by confidence (LOW small but > 0) | always influences | one account can nudge thousands of routes; gaming target; "low confidence" silently becomes "a bit dangerous" |
| B. Ignore LOW entirely | clean | throws away honest early warnings people would want to know |
| C. LOW influences only when routes otherwise tie | subtle | still lets one account decide ties at scale; hard to explain |
| D. **Two channels: CONFIRMED+ → ranking (bounded); LOW / unknown / conflict → uncertainty channel (shown, never ranked); recommend only between comparably-known routes** | principled | needs careful wording (solved by texts + tests) |

**Chosen: D**, with these rules (the contract for any future engine):
1. **Ranking evidence** = CONFIRMED and STRONG concerns only. UNCONFIRMED has **zero ranking weight**. It appears
   as "Unconfirmed reports along this way" (the uncertainty channel).
2. **Unknown is neutral, not good.** A route is *recommended* over another only if its coverage band is the same as
   or better than the other's. Otherwise both are presented as a trade-off ("Route B: fewer confirmed concerns, but
   little is known about it"), and the person chooses.
3. **Positives never offset concerns.** Havens are shown as "help along the way" and may only break ties between
   routes with equal concern cost (lure guard).
4. **Bounded influence:** never prefer a route that is longer by more than **min(25 %, 800 m)**. Longer exposure is
   a risk too. **Hysteresis:** switch the recommendation only if the concern cost differs by at least one CONFIRMED
   concern.
5. **Conflict:** use the post-dispute confidence (already netted). Contradictory categories are shown and never netted.
6. **Relevance weights (internal, ordinal guidance):** CONFIRMED 2, STRONG 3; incident CURRENT ×1, RECENT ×0.5;
   condition CURRENT ×1, AGING ×0.5; night-relevant categories ×1 after dark, ×0.5 by day. A RECURRING pattern
   counts as a tie-breaker only.
7. **Wording:** "fewer confirmed concerns reported", "more is known about this way". **Never** "safe route",
   "safest" or "dangerous".
8. **No automatic action:** no mid-walk rerouting, no change to check-in intervals, no escalation. A brief is shown
   only when the person asks.

**Why it is the best fit.** It is the same "count independent people" principle as Phases 6/7, applied to routing.
It cannot be steered by one account. It is honest about ignorance, and each recommendation can be explained in one
sentence. **Risk:** in sparse areas most routes are "mostly unknown", so the engine often declines to recommend.
That is the correct, honest behaviour. **Evolution:** more data (Phase 8+ adoption) raises coverage, and the same
rules then recommend more often without being changed.

### 16.2 What Phase 8 provides to a future engine (the contract)

```
CellEvidence (per 7-char block on/near the route; from region packs, phone-side)
  concernClass: NONE_CONFIRMED | CONFIRMED | STRONG       // UNCONFIRMED never present at block level
  categories: [SignalCategory]  nightRelevant: Boolean     // static category property
  relevance: CURRENT | RECENT | AGING   conflicting: Boolean   havenNearby: Boolean
AreaContext (per 6-char area crossed)
  outlook, information (NONE..MORE), unconfirmedReports: Boolean, recurring: Boolean, conflicting: Boolean
RouteEvidenceSummary (computed by the engine with the rules above)
  concernCost (ordinal), coverage band, explanations: [code], uncertainty notes: [code]
```

### 16.3 Phase 8's own consumer
"Check the way" (8D) uses this contract on the **straight corridor**. It produces a brief, not a ranking, because
there is only one "route".

### 16.4 Pre-conditions for a real route planner (later phase, separate approval)
- Street geometry from an **on-device** routing graph (an offline OSM extract downloaded by region). **Never** send
  origin and destination to a third-party directions API (it would learn where vulnerable people go). That needs a
  new dependency and a licence and privacy review.
- The basemap per §8.2 option D.
- The same region-pack data path. The server still never sees the route.

---

## 17. Future ML extension (Phase 9, not now)

**Why not ML in Phase 8.** There is no ground truth (no labelled "an incident really happened here"). Volumes are
tiny and sparse per cell. Outputs must be explainable to the person *and* in a viva. Learned risk scores would encode
reporting bias (who uses the app) as "danger". And ML adds an attack surface (adversarial reports) without a
measurable gain. The deterministic rules are the correct tool for this data.

**Extension point (designed now, built later)**
- `AreaIntelligenceModel` interface: `picture(visibleSignals, patterns, now) → AreaPicture`. rules-1 is the only
  implementation. Every response carries `modelVersion`.
- Any future model must output **the same contract**: ordinal levels plus reason codes. No probabilities to users.
- **Shadow mode:** a candidate runs alongside rules-1, results are compared offline in aggregate only (counts of
  disagreements, no cells), and it is promoted only by an explicit decision.
- **Where ML plausibly helps first:** (1) **abuse detection** (contribution-pattern anomalies → *opens a moderation
  case* for a human, never acts on people); (2) **threshold calibration** using moderation outcomes (aggregate
  statistics). Both need a separate privacy design (data minimisation, possibly differential privacy for any training
  extract). Phase 8 deliberately stores **no training data**.

---

## 18. Testing strategy (planned test names)

**Backend, pure**
- `AreaIntelligencePolicyTest`: one test per §5.8 example; `noDataIsNotSafe`, `singlePersonNeverRaisesConcern`
  (incl. TRUSTED), `linkedFreshnessDoesNotMakeIncidentToday`, `havensNeverCancelConcern`,
  `havensCapInformation`, `contradictionNeedsSameBlock`, `agingConditionCannotElevate`,
  `patternAloneNeverRaisesLevel`, `neighbourhoodIsMaxNotSum`, `reasonsContainNoPeopleOrCounts`.
- `SignalPolicyTest` (+): `independentFreshness_ignoresLinkedSupport`, `existingFreshnessUnchanged`.
- `PatternPolicyTest` (8C): decay math (half-life), cap 4, weekly admission, RECURRING at 2.5 needs 3 episodes,
  forget at 0.25, forget horizon ≤ 240 d, one episode forgotten ≤ 120 d.

**Backend, integration (MockMvc, real JWT, two users)**
- `AreaPictureApiIntegrationTest`: allow-list (no id, count, time or weight fields; JSON field scan),
  `pendingReportChangesNothing` (timing oracle), `quarantinedAndRemovedExcluded`, `retiredExcluded`,
  `pictureUsesFullSetBeforeTruncation`, `pausedViewerStillSeesPicture`, `401WithoutToken`, `limiterUnchanged`.
- `PatternIntegrationTest` (8C): episode only after expiry of a MODERATE signal; not for retired, quarantined,
  open-case or removed signals; REMOVE resets; job idempotent.
- `RegionPackApiIntegrationTest` (8D): only CONFIRMED+ signals; no public ids; single reports never listed;
  absent area = NONE documented; `complete=false` over cap; 5-char validation 400; distinct-region and new-account
  budgets 429; another user's activity changes nothing for the caller.
- `IntelligenceIsolationTest`: source scan (the same technique as `TimeConventionsTest`). `intelligence` imports
  none of `emergency|journey|safetycontact|circlealert|evidence`, and none of those import `intelligence`. No
  `@Scheduled` in `intelligence`.
- Regression: the full existing suites (Community, Trust, Moderation, Emergency, Journey, Evidence) must stay green.

**Android (JUnit4, fakes)**
- `AreaPictureMappingTest`: unknown outlook ⇒ null; unknown reason dropped; redaction.
- `CommunityViewModelTest` (+): picture present / absent / failure; offline shows no picture.
- `IntelligenceTextsTest`: every outlook has text; **forbidden-phrase test**. No string resource or template
  contains "is safe", "safe route", "safest", "dangerous area" or "verified" (scanned in `res/values*/strings.xml`).
- `CorridorBriefPolicyTest` (8D): signals inside/outside the corridor, coverage bands, unconfirmed note,
  >3 regions ⇒ TooLong, incomplete pack ⇒ no brief, night emphasis, havens never reduce concerns.
- `IntelligenceIsolationTest` (Android): `core/intelligence` is not referenced by `SafeWalkSession`,
  `CheckInPolicy`, `MovementPolicy`, `CircleAlertCoordinator`, `feature/emergency` or `core/tracking`.

**Database:** V14 on MySQL 8.0 + rolled-back constraint dry run (§14.2).
**Manual (emulator, then Galaxy A23):** checklist in §24.

---

## 19. Security considerations

1. **Authorization:** both endpoints need a JWT (default `authenticated()`), and there is no role logic. The caller
   identity is used only for limiters and the existing `yourContribution`. No user-supplied ids.
2. **Input validation:** cells by strict regex (6 for area, 5 for region). Nothing else is accepted.
3. **Rate limits:** `/area` unchanged (120 requests/h, 15 areas/h). `RegionQueryLimiter`: 20 requests and 6
   distinct regions per rolling 24 h per account; accounts younger than the probation get 2 regions. In memory and
   per instance (the same documented limitation as Phase 6/7). It holds at most 6 region cells per account for 24 h,
   never logged.
4. **Information leakage:** allow-list DTOs; the same-set rule; no pattern values; absent ≠ hidden (absent means
   NONE); identical error bodies for invalid and out-of-budget requests where possible.
5. **Logging:** counts only, never cells, categories with places, or accounts (as `CommunityPurgeJob` does today).
6. **Performance/DoS:** the picture is O(n) over rows already loaded. A region pack loads ≤ 32 area cells (PK/index
   lookups) and one trust snapshot. It is capped by `region-max-signals`, and its duration is logged as a count-free
   timing line if > 500 ms. **Cache** only if measured: per region cell, ≤ 60 s, never per user.
7. **Fail closed on the phone, fail safe on the server:** unknown values hide the card. A server error in the
   picture must not break `/area`. The picture is computed in a guarded block that, on an internal error, omits
   `picture` and logs a count. Safety information already shown (signals) is never withheld because intelligence failed.
8. **No new secrets, keys or dependencies.**

---

## 20. Alternatives considered (architecture level)

| Criterion | **1. Rules on two axes (chosen)** | 2. Weighted numeric risk score + decay ("heat") | 3. Bayesian / subjective-logic opinion per cell | 4. Supervised ML risk model | 5. Materialised server-side aggregates (precomputed tables per cell, refreshed by a job) |
|---|---|---|---|---|---|
| Correctness | follows existing, tested assessments | false precision; sums penalise busy areas | sound in theory; priors arbitrary without data | no ground truth → cannot be validated | same as its formula, but stale between refreshes |
| Explainability | every output = rule + codes | "why 63?" has no good answer | "belief 0.4, uncertainty 0.5" needs teaching | poor | as its formula |
| Privacy | derived on read; nothing new stored (8A/B) | often stored per cell | stored opinions per cell | needs training data | **stores a city-wide derived map** |
| Security / attack resistance | counts people; thresholds cost real people | volume-gameable; optimisable target | moderate | adversarially fragile | same as formula; plus a stale window |
| Complexity | low (one pure class) | low–medium | medium | high | medium (job, invalidation, consistency) |
| Maintainability | table-driven tests | tuning wars | parameter tuning | model ops | cache invalidation vs moderation (hidden content must vanish at once) |
| Performance | O(n) on existing reads | same | same | inference cost | fastest reads |
| Future ML compatibility | contract + shadow mode | ML would replace it | natural prior for ML | — | feature store for ML |
| Viva explainability | high | medium (but misleading) | low–medium | low | medium |

**Chosen: 1.** It is the only option that is simultaneously explainable, honest about uncertainty, people-counting
(attack-resistant), storage-free for the core, and built on assessments that are already tested. From option 3 we
keep the idea of separating belief from uncertainty. From option 5 we keep only the tiny, k-gated pattern memory, not
a derived map.

---

## 21. Rejected approaches (with reasons)

| Rejected | Reason |
|---|---|
| A "safety score" (0–100) or percentage | False precision; "AI says dangerous"; unknown looks like safe; a gaming target |
| Green "safe zones" / "safe" labels | No negative evidence exists. The app cannot know a place is safe |
| Heat maps (density surfaces, kernels, interpolation) | Invents evidence between cells; implies precision; empty = safe; usually needs tiles |
| Online map tiles (Google Maps SDK etc.) | Every viewed place leaks to a third party; key and billing; contradicts the Phase 6 decision |
| "Felt safe here" / "all clear" check-ins as counter-evidence | A location check-in trail of everyday life; trivially farmable |
| Using SOS or emergency locations, Safe Walk deviations, stops or check-ins as signals | Correlates a victim's emergency with a public signal; can identify them; violates the isolation rule (also rejected in `PHASE6.md` §7) |
| Storing time-of-day / day-of-week of incidents | Most identifying fact; sparse noise; category semantics + phone clock give the useful part |
| Event history, soft deletes, outlook snapshots | Surveillance artefacts with no use except history |
| Public rankings, "most dangerous areas", city-wide view | Stigma, scraping, reconnaissance |
| Push / geofence alerts on entering an area | Background location + a server or OS trail; alarm fatigue; outside Phase 8 |
| Changing Safe Walk check-in intervals or escalating from intelligence | The engine must never act as an emergency authority |
| Flags as negative place evidence | Flags concern abuse, not the place; they already act through moderation |
| Using trust tiers directly in the engine | Tiers would become an exposed lever; confidence already contains them |
| Differential-privacy noise on pictures | Inputs are already public; noise contradicts what people see; kept for future bulk statistics only |
| Server-side route evaluation (send a polyline or cell list) | Reveals origin, destination and path; region packs reveal ~5 km instead |
| ML in Phase 8 | §17 |

---

## 22. Risks and limitations (honest)

1. **Thresholds are judgment calls.** There is no ground truth for calibration. They are chosen to require
   independent people at every step and are public, test-pinned and configurable only in code review.
2. **Reporting bias.** Areas with fewer app users show NO_INFORMATION more often. That is honest, but people may
   still read "no information" as "fine". The texts say otherwise explicitly.
3. **Sybils remain the root residual** (Phase 6/7): patient, independent-looking accounts can create CONFIRMED
   signals. Phase 8 does not worsen this, and the max-not-sum and weekly-admission rules limit its leverage.
4. **Pattern memory extends retention** of derived place information to ≤ ~240 days after the last episode (no people).
5. **Straight-line corridor ≠ the real route** (the 4C limitation). The brief says "around the way", not "on your route".
6. **Phone-asserted location** (unchanged).
7. **In-memory, per-instance limiters** reset on restart. A cluster needs a shared store, which needs its own
   privacy design.
8. **Correlation by a malicious operator** with live request logging (§10).
9. **Region budget** may frustrate heavy users (6 regions/day). The value is configurable after observing real use.
10. **Lures where no concern exists**: two independent people can still advertise a fake haven in an area with no
    reports (as today). Havens never improve ranking, and flags plus moderation apply.
11. **People may over-trust any app.** Wording, footer and the forbidden-phrase test reduce this but cannot remove it.

---

## 23. Implementation roadmap (each step gated by build + tests + the user's check, as in Phase 4)

| Step | Scope | Migration | Main files (planned) |
|---|---|---|---|
| **8A** Backend core | `independentFreshness`; extract `visibleSignals`; `AreaIntelligencePolicy` (rules-1) + `AreaIntelligenceService`; `picture` in `/area`; isolation test; API docs | none | `community/service/SignalPolicy`, `CommunityService`, `community/dto/CommunityDtos`; new `intelligence/service/*`, `intelligence/dto/IntelligenceDtos`; tests §18 |
| **8B** Android picture | `core/intelligence` models/DTOs/mapping; `IntelligenceTexts`; Area picture card; radar area-cell layer; forbidden-phrase test; emulator run | none | `feature/community/*`, `SafetyRadarView`, `strings.xml` |
| **8C** Pattern memory | V14; `PatternPolicy`, `PatternService`; job hook; REMOVE reset; `RECURRING_CONCERNS`; config switch; MySQL verification | **V14** | `intelligence/*`, `CommunityPurgeJob`, `ModerationService`, `IntelligenceProperties` |
| **8D** Region pack + "Check the way" | `/api/intelligence/region`, `RegionQueryLimiter`; Android `IntelligenceApi`, `CorridorBriefPolicy`, Safe Walk start-screen button; route contract types | none | `intelligence/controller/*`; `core/intelligence/*`, `feature/safewalk/*` |

The order is chosen so that each step is useful alone. 8C and 8D are independent of each other, and either can be
deferred without harming 8A/8B. Recommended model per `CLAUDE.md` §3: Sonnet for 8B and the mechanical parts of 8D,
Opus for 8A/8C policy code and the security review.

---

## 24. Definition of done (Phase 8)

Per `CLAUDE.md` §3 (plan → implement → build → tests → manual verification → security/privacy review → decisions
documented → "complete"), for **each** sub-phase:

1. `mvn test` and `gradlew testDebugUnitTest` all green; `assembleDebug` + `lintDebug` with no new errors.
2. Every §5.8 example and every §18 test exists and passes; regression suites unchanged.
3. (8C) V14 applied on MySQL 8.0 with the constraint dry run recorded.
4. Live API smoke test: allow-list scan of `/area` and `/region`; pending/quarantine invisibility; 429 budgets.
5. Emulator manual checklist (then Galaxy A23, or explicitly DEFERRED by the user):
   1. Empty area → "No community information here yet… not the same as safe"; hatched cells on the radar.
   2. One report → "Little community information… unconfirmed report"; no amber.
   3. Two independent confirmations of two conditions in one cell → "Take extra care: … poor lighting and an
      isolated stretch"; amber outline on that cell; direction shown.
   4. Haven + poor lighting in the same block → "People disagree about this place"; concern unchanged.
   5. Quarantine a signal (two flags, demo settings) → the picture changes as if it never existed.
   6. Airplane mode → no picture shown; radar failure state as today.
   7. (8D) "Check the way" → brief with coverage; >3 regions → "too long to check"; the walk starts normally
      whatever the brief says; nothing reaches the Safety Circle, check-ins or escalation.
   8. TalkBack reads the card; dark mode contrast ≥ 4.5:1; no red anywhere in intelligence UI.
6. Security/privacy review written into a `docs/PHASE8.md` (results file, created at implementation time),
   including a re-run of §25's self-review against the real code.
7. `CLAUDE.md` and `HANDOFF.md` updated with status in the project's vocabulary (implemented / compiled / tested /
   manually verified / not verified).

---

## 25. Final self-review (design challenged before finishing)

| Question | Answer | Changed in the design because of it |
|---|---|---|
| Can attackers manipulate it? | Only by recruiting independent, aged, unflagged accounts, the Phase 7 residual. Linked accounts can no longer fake "today" | Added `independentFreshness`; max-not-sum; weekly episode admission |
| Can reporter identity be inferred? | No new identity data. Aggregates follow already-delayed visibility. Single reports never appear individually in packs | Same-set rule; packs exclude ids and single reports |
| Can geographic probing leak? | Outputs are functions of visible signals only. Region packs reveal no more than `/area` at a lower budget | Region budget set below the `/area` effective budget |
| Can stale data create dangerous confidence? | Signals expire; AGING; nothing is cached on the phone; no "safe" output exists | Phone never stores pictures; AGING cannot elevate |
| Can sparse data be misinterpreted? | Information is a separate, always-shown axis; NO_INFORMATION says so in words | Havens capped so they cannot fake "well known" |
| Conflicting evidence handled safely? | Shown, never netted; positives never cancel | Contradiction limited to the same block (AREA signals cannot contradict) |
| Is uncertainty honest? | Two axes everywhere, including the route contract (coverage band) | Route rule "recommend only between comparably known routes" |
| Unnecessary storage? | 8A/8B/8D store nothing. 8C stores one decayed number per ~1 km and group, with no FK and day-precision dates | Changed from a considered DATETIME to DATE; removed any signal reference |
| Did we weaken Phase 6/7 privacy? | No: no coordinates, times, people, viewer logs or new correlations. Pattern retention is the one extension, bounded and switchable | Patterns isolated in 8C behind a switch |
| Over-engineered? | Core = one pure class + one response field. Patterns and region packs are optional, independent steps | Split into 8A–8D |
| Is ML justified? | No (§17) | Extension point only |
| Realistically implementable and testable? | Yes: pure policies, table tests, existing test patterns (MockMvc, source scans, fakes) | Test names fixed in §18 |
| Explainable in a viva? | Every output = one rule row + reason codes (§5.4–5.8) | Worked examples table |
| Supports future Safe Route? | Yes, through the CellEvidence/AreaContext contract and the two-channel ranking rules, without the server seeing routes | Region-pack data path chosen over server-side route queries |

---

## Appendix: viva notes (short)

- **What / why:** turn trusted community signals into an honest area picture. Concern and knowledge are kept
  separate, so "no data" never means "safe".
- **Key classes (planned):** `AreaIntelligencePolicy`, `PatternPolicy`, `AreaIntelligenceService`,
  `RegionQueryLimiter`; Android `IntelligenceTexts`, `CorridorBriefPolicy`.
- **API + DB flow:** `/community/area` → `visibleSignals` (same as the radar) → rules-1 → codes → phone words.
  Patterns come from finished, corroborated episodes in the existing job. Region packs → the phone computes the brief.
- **Likely questions.** *Why no score?* (a number hides uncertainty and invites gaming). *Why max, not sum?* (busy
  areas would look dangerous for being busy). *Why can a haven not cancel a concern?* (lure attacks; a false "fine"
  is the worse error). *Why not ML?* (no ground truth; explainability). *Why region packs?* (the server learns ~5 km,
  not your route). *How do you remember without surveillance?* (one decaying number per ~1 km, fed only by
  corroborated finished episodes, forgotten within ~8 months, reset by moderators).
