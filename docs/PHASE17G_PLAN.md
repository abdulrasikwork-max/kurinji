# Phase 17G plan — Explore (deterministic first), "I went" + ratings, optional Claude understanding

Status: **17G-1 (deterministic Explore) IMPLEMENTED 2026-11-03: see `PHASE17G.md`** (approved by the user; V29 applied; backend 818 /
Android 927 tests, live MySQL 31/31, emulator PASS; the A23 was not run). **17G-3 IMPLEMENTED 2026-11-03 (live with Groq): see `PHASE17G3.md`. 17G-2 IMPLEMENTED 2026-11-03: see `PHASE17G2.md`** (the text below keeps the plan's wording)
below. Two corrections found while building 17G-1 (the text below keeps the original wording): Explore reads **EXACT-place** posts only
(an AREA post promises not to reveal its place), and the categories asked for count as **ONE** requirement
(`requested` = (categories ? 1 : 0) + tags + (time of day ? 1 : 0)).

The plan was written 2026-11-03 after a read-only inspection of `PHASE17_PLAN.md` (§2–§4), `DECISIONS.md` (D-2, D-8/U5), the backend
`social` package (`DiscoverService`, `PostRepository`, `Visibility`, `SocialLimits`), `SocialIsolationTest`, `ModelPlacesTest`, the
Python `moderation-service`, and the Android Discover/Saved/"I went" code. Every statement about what exists comes from
that reading; every statement about what to build was a **proposal** until the user approved it (CLAUDE.md §3).

Decided already (2026-10-27, `PHASE17_PLAN.md` §3.1): **D-2** (Claude API, `claude-haiku-5-5`, request text + candidate place
facts only, the deterministic engine must work without it, amends D2 for this one use) and **D-8 = U5** (ratings only from
people who went, from 5 ratings, Bayesian, never sorted by rating or activity alone, "% match" explained in words).
Everything else below is for you to confirm in §6.

---

## 0. One page

| Sub-phase | What | Cost to run | Migration | Changes a permanent rule? |
|---|---|---|---|---|
| **17G-1 Deterministic Explore** | Ask for what you want; IRL understands it **on the phone** (word lists, English / Tamil / Tanglish), shows what it understood as editable chips, and the server returns matching **places** from experiences you may read, explained in words | **free**, no AI, no external call | **V29** `post_facets` (optional tags on experiences) | **no** |
| **17G-2 "I went" + ratings (U5)** | "I went" becomes a private record; an optional 1–5 rating from it; a place shows a rating only from 5 raters | free | **V30** `place_visits` | **yes — U5** (CLAUDE.md §1, design doc §2.7/§3.5) |
| **17G-3 Optional Claude understanding** | Claude turns a typed request into the same chips when available; if it is off, unreachable, out of credit or over a limit, IRL silently uses 17G-1 | a few cents per 1,000 requests, only if you switch it on and fund an API account | none | no (D-2 already allows it; `ModelPlacesTest` is amended) |

**Order:** 17G-1 first and **complete on its own**. 17G-2 and 17G-3 are independent of each other once 17G-1 is accepted. If the
Claude demo matters more than ratings, do **17G-1 → 17G-3 → 17G-2** (recommended, §6 G-11): ratings change a permanent
rule and deserve their own review, Claude does not.

**The one design idea:** everything meets in one **structured query** — `{city, categories[], facets[], daypart?}` — built
only from closed lists. Whoever produces it (the phone's word lists, or later Claude) is only an *interpreter*; one
deterministic server engine decides what is shown. This is D2 applied to Explore: *models produce evidence → a tested
deterministic policy decides.* It also means the typed sentence never has to leave the phone unless the person switches
Claude on.

---

## 1. Rules this plan must keep (and how)

| Rule (source) | How Explore keeps it |
|---|---|
| No coordinates in any social request; cells only (CLAUDE.md §9) | 17G-1 is **city-scoped** (no "near me", no location permission, no cell). A later "near me" would send the 6-character area cell only |
| No public counts except U1; no popularity rankings; no endless feed (§1) | Results are **places**, not posts; no post/comment/rater counts anywhere; at most 20 results then an end line; ordering never uses volume (§3.5) |
| Every list of others' posts is ordered by `visible_from`, never `created_at` (§13) | The candidate window is read by `visible_from`; the only time-like tie-break is the newest `visible_from`, never `created_at` |
| One visibility definition per area; tested with author / connection / stranger / blocked (§6) | Explore reads only what `Visibility`/`Relationships.forLists` already allows (see §3.4) and gets the four-user tests |
| Nothing about other people is stored on the phone (Q3) | The query is not saved, no history; results live in memory only |
| No text claims a place or person is safe; no ranking words (§10) | No "safe/well-lit/women" facets; wording guarded by a test; no "best", "top", "for you" |
| Social and safety never import each other; intelligence stays out of safety (§5, §6) | Explore code lives in `social` (17G-1/2) or its own `explore` package (17G-3) and may reference no safety, community-signal or intelligence package — new isolation tests |
| Models only for moderation + the D-2 exception; AI mistakes must not decide (§2) | 17G-3 only returns closed-list codes, validated; the engine decides; any failure falls back |
| Time is UTC; `Clock` injected; no `LocalDateTime.now()` (§6) | As elsewhere |
| 18+, brand IRL, Chennai first, Tamil/Tanglish from V1 (§1) | `SocialAccess.requireMember` on every endpoint; strings say IRL; the word list includes Tamil and Tanglish |

---

## 2. What exists today (so we build on it, not around it)

| Exists | Where | Use in 17G |
|---|---|---|
| Experiences with `category` (10 closed codes), `daypart` (4), `tip`, `experienced_month`, place link, city | `posts`, `places` (V21, V28) | the data Explore matches on |
| Place search by **name only**, place page, category page, "Going there?" sheet | `PlaceService.search`, `DiscoverService.place/category`, `feature/going` | results open the existing place page |
| Discover "edition" with **reason codes** the phone turns into words | `EditionPolicy`, `DiscoverText` | same pattern for Explore reasons |
| One visibility rule, block/mute/moderation filtering for lists | `Visibility`, `Relationships.forLists` | reused unchanged |
| In-memory per-user limits | `SocialLimits` (`discover`, `search`, …) | add `explore` |
| City catalog bundled and searched on the phone | `cities-v1.tsv`, `Cities`, `CityPicker` | city step of the query |
| "I went" opens the composer, **stores nothing**, no rating (E17-9) | `ComposeViewModel.prefill` | 17G-2 builds the record |
| Python moderation service (stdlib only, internal, key header, fail-closed), one backend client pinned by `ModelPlacesTest` | `moderation-service/`, `ModerationServiceClient` | pattern for 17G-3 |
| **Nothing** for facets, Explore, ratings, "% match", a Claude key or call | grep of `main/` | all new |

Two constraints found in the tests that shape the design:
- `SocialIsolationTest.social_callsNothingOutside` forbids `HttpClient`/`RestTemplate`/`WebClient`/`@Async` inside
  `social` → **the Claude client cannot live in `social`**; it gets its own `explore` package (17G-3).
- `ModelPlacesTest` pins exactly two backend outbound clients, bans the word "anthropic" from the pom, and limits the
  Python service to a short standard-library list (no `urllib`/`ssl`) → it **must be amended deliberately** in 17G-3.

---

## 3. 17G-1 — Deterministic Explore (free, no AI, no external call)

### 3.1 What the person sees
1. **Explore** opens from Discover (entry point: G-7). A box asks "What are you looking for?", a city button (defaults to the
   city chosen in Discover) and a Search button.
2. Typing is understood **on the phone**. A row **"We understood:"** shows chips — a category ("Food and cafés"), tags
   ("Quiet", "Budget friendly"), a time of day ("Evening") and the city. Chips can be removed, and tags/categories can be added
   from a list, so the person always sees and controls what is searched.
3. Words the lists did not know are shown honestly: **"We did not use: …"**. No negation in v1 ("not crowded" is not understood
   and is said so).
4. Search returns **places**. Each card: name, category, city, **"Matches 3 of the 4 things you asked for"**, the matching
   tags as chips, and reasons in words ("shared by people in Chennai"). Tap → the existing place page.
5. At most 20 results, then **"That is all we found. Try fewer words or another city."** An empty result says so plainly.
6. Nothing is saved: no history, no recent searches.

### 3.2 The structured query and its closed lists
```
ExploreQuery { city: CityCode (required), categories: [≤ 2 of the 10], facets: [≤ 4 of the list], daypart?: MORNING|AFTERNOON|EVENING|NIGHT }
```
Needs at least one of categories / facets / daypart. Unknown codes → 400 (server) / dropped (phone DTO, fail-closed).

**Facet list v1 — proposal, for you to edit (G-1)**, 13 codes, each with English / Tamil / Tanglish labels:
`QUIET`, `LIVELY`, `BUDGET_FRIENDLY`, `GOOD_WITH_FRIENDS`, `GOOD_ALONE`, `FAMILY_FRIENDLY`, `OUTDOOR_SEATING`,
`VEGETARIAN_OPTIONS`, `EASY_TO_REACH`, `STEP_FREE_ACCESS`, `OPEN_LATE`, `SCENIC`, `GOOD_FOR_WORK`.
Deliberately **absent**: anything about safety, lighting, women, crowds-as-danger (Nearby covers conditions; a test forbids
`safe|safety|danger|women|well lit` in facet codes and labels). The list lives in one file, `explore-facets-v1.tsv`
(code, English, Tamil, Tanglish), **byte-identical** in the backend resources and the Android resources with a parity test
(the `cities-v1.tsv` pattern), so adding a facet later is one file + one migration CHECK.

### 3.3 Understanding on the phone (pure Kotlin, offline, no network)
- `ExploreQueryParser` (pure): lower-case, strip punctuation, longest-phrase match against a bundled word list
  `explore-lexicon-v1.tsv` (`phrase → kind(category|facet|daypart) → code`), English + Tamil script + Tanglish spellings
  ("amaithiya", "kaapi kadai", "beach"), the city via the existing `Cities.search`. Returns the query plus the unused words.
- I draft ~200 entries; **a Tamil reader must review them** (G-6) — a wrong word list is a silent wrong search.
- Pure tests with a table of sentences (English, Tamil, Tanglish, mixed, unknown words, empty).

### 3.4 The engine on the server (`social`, pure policy + service)
- **Candidates:** Everyone-audience **Experience** posts that have a place, in the chosen city, `visible_from ≤ now`, newest
  400 by `visible_from` (a bounded window), through the existing EVERYONE predicate and `Relationships.forLists` (blocks,
  mutes, hold/removal; collapsed posts left out as in the Edition). Connections-only posts are **excluded in v1**
  (consistent with place and category pages; fewer ways to leak — G-4).
- **Per place** (from those visible posts only): its category, the **union** of facet tags on its visible experiences, the
  dayparts of its visible experiences. No counts are kept or returned.
- **Match** (`ExplorePolicy`, pure): `requested = categories + facets + (daypart ? 1 : 0)`; a place matches a category if its
  category is in the query, a tag if any visible experience carries it, a daypart likewise; `matched ≥ 1` to be listed.
- **Order** (G-5): (1) matched/requested descending, (2) newest visible experience `visible_from` descending, (3) normalised
  place name. Count-free, no rating, no activity volume. Reason codes (`MATCHED_CATEGORY`, `MATCHED_FACET`,
  `MATCHED_DAYPART`, `IN_CITY`) — the phone writes the words (EditionPolicy pattern).
- **Endpoint:** `POST /api/explore/search` (body = the structured query; no free text ever reaches the server). Limits via
  `SocialLimits.explore` (in memory, our 429 message). 400 for invalid codes, 409 age (existing code path), no stack text.
- **Response:** `hits[]` = `{place:{id,name,category,city}, matchedCategories[], matchedFacets[], matchedDaypart, requested,
  matched, reasons[]}`. No coordinates, no times, no counts of posts or people.

### 3.5 Database (V29, additive)
```
post_facets ( post_id BIGINT NOT NULL → posts(id) ON DELETE CASCADE,
              facet VARCHAR(20) COLLATE utf8mb4_bin NOT NULL CHECK (facet IN (…13 codes…)),
              PRIMARY KEY (post_id, facet), INDEX idx_post_facets_facet (facet, post_id) )
```
≤ 4 per post and Experience-only are enforced in code (a CHECK cannot count rows). The CHECK is on a non-FK column, so
MySQL accepts it. **Process (CLAUDE.md §6):** backup → dry run of the DDL on a scratch copy → apply; H2 cannot prove
MySQL DDL, so a real-MySQL check is part of done. Existing posts simply have no tags; the engine still matches on
category and daypart for them.

### 3.6 Tagging experiences (so there is something to match)
Composer: an optional row **"Good for…"** of up to 4 tag chips (Experience only, never for Ask). `PostRequest`/`PostResponse`
gain `facets[]` (create, edit API, read); the post page shows them read-only. No moderation text pipeline is needed (closed
list, no text). Moderator "remove tags" is **not** in scope.

### 3.7 Safety / privacy / abuse for 17G-1
- The server never sees the typed sentence; it logs nothing about the query (counts only, if anything).
- Tag stuffing (one author tags every post with all four): bounded by ≤ 4 per post and the Everyone/visibility rules; a place
  appearing for one tag from one author is accepted (no popularity logic, by design). Documented limit.
- Muted/blocked authors, held/removed posts, other people's connections-only posts never contribute (four-user tests).
- Place merges (`merged_into`) are followed when grouping.
- Explore never reads place signals, intelligence, evaluation or safety data (isolation test), and shows no safety claim.

### 3.8 Tests (targets, not promises)
- Backend: `ExplorePolicyTest` (match fraction, ordering invariants including "adding more posts to a place never changes its
  rank", ties, empty), `ExploreFacetsParityTest`, `Phase17gExploreApiTest` (H2, real JWT: author / connection / stranger /
  blocked / muted / held / other city / merged place / invalid codes / limits / age), `ExploreIsolationTest`
  (no safety/community/intelligence/evaluation references, no outbound call, no `count(` over posts/comments in the explore
  query), `AccountEraser` unaffected (cascade through posts) but verified.
- Android: `ExploreQueryParserTest` (English/Tamil/Tanglish table), `ExploreViewModelTest`, `ExploreTextsTest` (no ranking
  words, no safe claims, no `%d` outside the match sentence), DTO fail-closed tests, `ExploreFacetsParityTest`, layout/2.0x
  guard like `Phase17FGuardsTest`.
- Live: PowerShell API script on real MySQL (method of `PHASE9.md` §13): sign up two accounts, post tagged experiences,
  search as stranger/connection/blocked, confirm ordering and "nothing leaks".
- Devices: emulator (light/dark, 320 dp, **2.0× text**), then the A23 by the dev-file OTP route used in 17F.

### 3.9 Done when
Backend and Android tests green, lint not worse than 104, V29 applied on MySQL after a dry run, live script passes, emulator
and A23 walked at 2.0×, docs (`PHASE17G.md` §1, `API.md`, `STATUS.md`, `HANDOFF.md`, `DECISIONS.md`) updated, one local
commit. **Not verified** items stated plainly (e.g. TalkBack by ear).

---

## 4. 17G-2 — "I went" + ratings (U5) — after 17G-1, and after you approve the rule change

- **Rule change first.** Add **U5** to CLAUDE.md §1 and amend `EXPERIENCE_PLATFORM_DESIGN.md` §2.7 / §3.5 ("no stars, no
  ratings, no rankings of places") and the three code comments that repeat it (`PostCollectionFragment`,
  `PostCollectionViewModel`), with the guards below. Nothing is built before you approve that wording (G-8).
- **V30 `place_visits`:** `(id, user_id → users ON DELETE CASCADE, place_id → places ON DELETE CASCADE, rating TINYINT NULL
  CHECK (rating IS NULL OR rating BETWEEN 1 AND 5), created_at, rated_at NULL, UNIQUE(user_id, place_id))`. One row = "I went"
  (private, owner-only, like Saved); the optional rating lives on the same row, so a rating can only exist after "I went" **by
  construction**. `AccountEraserCoverageTest` gets the new table; place merges re-point visits (keep the rated row).
- **API:** `PUT/DELETE /api/places/{id}/went` (delete also removes the rating), `PUT/DELETE /api/places/{id}/rating`
  (409 without a visit). A person may mark "went" only for a place they can **see** (a visible Everyone experience), so the
  endpoint cannot probe hidden places. Own state returned as `yourVisit`; the public line is `rating: "4.6"` or null.
- **Policy (pure `RatingPolicy`):** shown only when ≥ 5 raters; Bayesian `(3.5·5 + Σ)/(5 + n)`, one decimal; **no count and no
  timestamps in any response**; recomputed at most once per IST day (an in-memory per-place value keyed by the IST date — no
  new `@Scheduled`, which `SocialIsolationTest` allows only for the photo clean-up) to blunt "average before/after one
  rating" inference. Never used to sort Explore or any list.
- **Abuse:** one visit per person per place, a daily cap (new `SocialLimits.visit`), paused people cannot rate, ratings follow
  merges, deletion removes everything. **Honest limit:** "I went" is self-declared — there is no proof of a visit.
- **Android:** "I went" marks the visit, then offers **"How was it? (optional)"** — 1 to 5 as numbered buttons ≥ 48 dp with
  spoken "3 out of 5" (not colour or stars alone), then the existing composer option. Place page and Explore cards show
  **"⭐ 4.6 IRL · from people who went"** only when present (brand: IRL, not the plan's "Kurinji").
- **Tests:** `RatingPolicyTest` (threshold, Bayesian values, rounding, daily recompute), API tests (no visit → 409; hidden place
  → 404; merge; erase), differencing test (counts never exposed), `SocialTextsTest` amended (one allowed rating string, ranking
  words still banned), Android rating UI at 2.0×.

---

## 5. 17G-3 — Optional Claude understanding (never required)

**Principle:** Claude only fills the **same chips**. It never sees places, never ranks, never decides. If it is unavailable for
*any* reason, the phone's word lists (17G-1) are used automatically.

- **Where it lives.** Backend package `explore` (not `social`): `UnderstandingController`
  `POST /api/explore/understand {text ≤ 200 chars}` → `{categories, facets, daypart}` or a code (`UNDERSTANDING_OFF`,
  `UNDERSTANDING_UNAVAILABLE`); `UnderstandingClient` (java.net.http) talks only to our **internal Python service**, like
  `ModerationServiceClient`. The **Anthropic key exists only in the Python service's environment** — the Java backend, the app
  and the repository never hold it.
- **Python service:** new module `understand.py`, route `/v1/understand`, standard library only (`urllib` + `ssl`):
  one POST to the Anthropic Messages API with model `claude-haiku-5-5`, a system prompt that contains **only the closed
  vocabulary** and asks for JSON, `max_tokens` small, 6 s timeout, **no retries**, no tools, no logging of the text or key.
  The reply is **validated against the closed lists**: anything else is dropped, so prompt injection can at worst return
  vocabulary codes. Data sent to the provider: the typed text and the vocabulary — **no place facts** (narrower than D-2
  allows; place facts could be added later only with a new decision).
- **Mode and budget (all enforced by us, independent of Anthropic):** `app.explore.understanding.mode = OFF` (default) | `ON`;
  per-user daily cap (proposed 20), global daily cap (proposed 300), a **circuit breaker** (10 minutes) after any provider
  error, credit-exhausted, spend-limit or timeout, because the spend-cap response carries no `retry-after` and retrying fails
  until the next month. Without a key the service answers `UNDERSTANDING_OFF` and the app never shows a Claude control.
- **Consent (Android):** Settings → "Smart understanding (Preview)" switch, **default off**, one honest question the first time:
  the typed words go to IRL's server and then to an external AI provider to be turned into tags; they are not stored or used
  for anything else; you can turn it off any time. When on and a search is started: try smart → on any failure use the phone
  parser and say once "Standard understanding was used" (not an error). The chips are shown either way.
- **Tests:** Python tests with a local stub HTTP server (valid reply, invalid codes, junk, timeout, 4xx/429/5xx, no key);
  Java client tests with a stub; the breaker and caps with a fake `Clock`; `ExploreIsolationTest` (the package references no
  `social` data and no safety code); **`ModelPlacesTest` amended on purpose**: a third pinned client
  (`UnderstandingClient.java`), `urllib`/`ssl` added to the Python allow-list, the string `api.anthropic.com` allowed in
  `understand.py` only, the pom still free of any AI SDK, still no model file. Android: consent flow, fallback path, no
  `ANTHROPIC` string or key anywhere in the app (guard test).
- **Live check:** only if you provide an API key (or fund one). Without it this sub-phase is **stub-verified only** and is
  reported that way. Billing is separate from your Claude subscription: see the explanation given with this plan
  (prepaid credits, auto-reload off, a low spend limit; ≈ $0.14 per 1,000 requests for Haiku 5.5 at ~600 input / 150 output
  tokens — my estimate from Anthropic's published price of $0.10 / $0.50 per million tokens).

---

## 6. Decisions needed from you (each with my recommendation)

| # | Decision | Recommendation |
|---|---|---|
| G-1 | The facet list (13 proposed in §3.2) and its Tamil/Tanglish labels | Edit freely; keep safety words out |
| G-2 | Three sub-phases, each its own plan check-in, tests and local commit | Yes |
| G-3 | Where the typed sentence is understood in 17G-1 | **On the phone**; the server only receives the structured query |
| G-4 | What Explore searches | Everyone-audience experiences with a place, one city, no "near me" and no connections-only posts in v1 |
| G-5 | Result order | match fraction → newest visible experience → name; no rating, no volume; max 20 |
| G-6 | Who reviews the Tamil/Tanglish word list | **You** (or someone you trust) before 17G-1 is called done |
| G-7 | Where Explore opens from | A quiet "Explore" row in Discover's head beside the search icon (the search icon keeps place/category names) |
| G-8 | U5 wording and details: per place; once-a-day recompute; ≥ 5 raters; only for places you can see; "⭐ 4.6 IRL" | Approve the CLAUDE.md/design-doc amendment text before 17G-2 starts |
| G-9 | Claude: who owns the API account and key; caps (20/user/day, 300/day); default OFF; Preview label; consent copy | As written in §5 |
| G-10 | Migration numbers | **V29** facets, **V30** visits/ratings (the plan's V30 for ratings stands; HANDOFF's "ratings can take V29" is superseded because facets need V29 first) |
| G-11 | Order after 17G-1 | **17G-3 before 17G-2** (Claude demo is lower-risk than a permanent-rule change) |

## 7. Risks and how they are handled

| Risk | Handling |
|---|---|
| Empty Explore on a new database (no tagged experiences) | The engine also matches category/daypart; the empty state is honest; seed with tagged test posts for demos |
| Wrong or thin Tamil/Tanglish list → silent bad searches | "We did not use: …" line; chips are editable; G-6 review; table tests |
| Ratings undermine "no popularity" principles | Separate sub-phase, explicit U5, never used for ordering, no counts, daily recompute, limits |
| Cost surprises with Claude | Default off, caps, breaker, prepaid credits with auto-reload off and a low spend limit; fallback means no outage |
| Time pressure (8 days of subscription) | Sub-phases are independent after 17G-1; 17G-1 alone is a complete feature |
| H2 does not exercise MySQL DDL | Real-MySQL dry run + apply is part of "done" |

## 8. Not in scope
Verified Nearby Assistance (Phase 12), route planning, "near me" Explore, ranking by popularity, Claude seeing place facts or
posts, storing request text, voice input, Explore history, moderator tools for tags, anything on the safety side.

## 9. What I need from you to start 17G-1
Approval of this plan, your answers to G-1…G-11 (defaults above are fine), and a person to review the Tamil word list.
For 17G-3 only: an Anthropic API key funded as you choose — or agree it stays stub-verified.
