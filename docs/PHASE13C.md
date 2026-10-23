# Phase 13C — Intent and discovery: results

**Date:** 2026-10-23 · **Status: implemented, compiled, tested, verified (MySQL, live API, emulator API 37).**
Plan: `docs/PHASE13C_PLAN.md` (choices C1–C15, decided under the user's delegation of 2026-10-23). Design:
`docs/EXPERIENCE_PLATFORM_DESIGN.md` §3.4, §3.5, §3.7, §4.10, §9.3, §9.6. Builds on 13B (`docs/PHASE13B.md`).

## 1. What was built

- **Butterfly 🦋 = Cocoon item** (C1): `PUT|DELETE /api/posts/{id}/butterfly`, `GET /api/cocoon`. Only other
  people's Experiences the viewer can see (C3). Want → Been → back; removing works even when the post is no longer
  visible (it is the person's own row). The Cocoon follows the one Visibility rule: a saved post that became invisible
  (block, narrowed audience, author gone) is hidden and comes back if it becomes visible again (C4).
- **The author's private line** (C2): every post answer carries `yourButterfly` (the viewer's OWN state) and, only for
  the author of an Experience shared with others, `butterflies {count, fewerThanThree}` — a number only from 3 people
  up. Nobody else ever gets a number or anyone's butterfly.
- **Discover** (`DiscoverService`): the weekly **Edition** for a launch city (In the city / Asks you could answer /
  theme of the week; caps 12 / 5 / 6; ends), **From your connections** (30 days, newest first, 20 per page, ends),
  **place pages** (exact-place Everyone posts only), **category pages** (Everyone posts, optionally one city), **place
  search** (names only). Every Edition item carries reason codes for "Why am I seeing this?" (C8).
- **City of a place** (C6): `CityAreas` bounding boxes → `places.city` (V22), set by the server from the cell.
- **Mute in lists** (C13): `Visibility.showInLists` = `canSeePost` + "author not muted"; used by every Discover list.
- **Android:** `DiscoverFragment` (city chips preselected by `CityMatch` from the profile's city text, Edition with
  "Why?" dialogs and the end card, From your connections with "Show more" and an end line, search for places and
  categories, category chips), `CocoonFragment` (Want / Been, I've been, Want again, Take out, the invitation),
  `PostCollectionFragment` (place page / category page), `PostFragment` (🦋 button with the TalkBack label "Want to
  experience this. Saves to Cocoon.", "I've been", the author's private line, "See this place"); entry points
  "Discover" and "Your Cocoon" on the Connections screen until 13F. Nothing is stored on the phone.

## 2. Database: V22 (`V22__intent_discovery.sql`)

`places.city` (closed list without OTHER, binary, index), `butterflies` (user FK cascade, post FK cascade, state
WANT/BEEN binary CHECK, `(state = BEEN) = (been_at IS NOT NULL)` and `been_at >= created_at` CHECK, UNIQUE (user, post),
indexes), index `posts (audience, visible_from)`. "Not your own post" and "Experiences only" are in code (the V6 rule:
no CHECK on cascading-FK columns).

## 3. Results

| Check | Result |
|---|---|
| Backend `mvn test` | **663 run, 0 failures, 0 errors, 2 skipped** (13B: 635). New: `DiscoverPoliciesTest` (11), `ButterflyApiIntegrationTest` (8), `DiscoverApiIntegrationTest` (10), `SocialFourUserTest` +1; `PlaceApiIntegrationTest`, `SocialAccountDeletionTest` extended |
| Android `testDebugUnitTest` | **641, 0 failures** (13B: 627; rerun after the emulator fix: 641 / 0). New: `DiscoverTest` (14); `SocialTextsTest`, `ExperienceViewModelsTest` adjusted |
| Android `lintDebug` | **0 errors / 94 warnings** (13B: 91; the 3 new: `accessibilityHeading` on API < 28 ×2, the same as 13B's post screen; `PluralsCandidate` on the author's line, which is always ≥ 3) |
| V22 dry run (MySQL 8.0.46, scratch `dry_*` tables, dropped after) | **14/14** negative inserts/updates rejected (city OTHER / lower case / unknown / update to OTHER; state unknown / lower case / NULL; BEEN without time; WANT with time; Been before saved; duplicate pair; unknown user; unknown post; update to BEEN without time); positives accepted; post delete cascades |
| V22 applied | Flyway "Successfully applied 1 migration … now at version v22" (dev DB **v22**, next **V23**) |
| Live API (backend on MySQL, 4 throwaway `live13c.*` accounts, try/finally) | **29/29 PASS** (L1–L29); users **170 before = 170 after**; no account, place or butterfly left |
| Emulator (AVD `Medium_Phone_API_37.0`) | **E1–E17 PASS** after one fix (§4) |

Emulator walk-through (throwaway `emu13c.*` accounts, deleted afterwards; app data cleared; `adb reverse` removed;
emulator and backend stopped; users 170):
E1 Edition opened for Chennai preselected from the profile city "Madras", theme line, both Chennai posts · E2 "Why?"
lists the reasons and says the order never uses counts · E3 Asks section, end card "That's this week's edition. Go
make your own.", category chips · E4 From your connections newest first (a Connections-only post included), then
"You have seen everything…" · E5 search "elliots" finds the place with its city · E6 place page: name, category, city,
its exact-place post, "That's all for now." · E7 post: 🦋 button with the right TalkBack description, private note,
"See this place" · E8 saved: "In your Cocoon: want to go" + "I've been" · E9 Been → invitation dialog · E10 "Add a
perspective" → Been-here-too added, button "In your Cocoon: been" · E11 Cocoon Want empty · E12 Been list (bug, §4) ·
E12b fixed · E13 Want again · E14 Take out → empty · E15 category page "Hidden gems" in Chennai · E16 the writer's own
post: no 🦋, "Fewer than 3 people want to experience this so far. Only you can see this line." (1 person) · E17 after
3 people: "3 people want to experience this. Only you can see this line."

**NOT VERIFIED:** real phone, TalkBack speech (labels checked in the accessibility tree only), dark mode, 320 dp / large
fonts, a week boundary passing on a live server (tested with fixed instants only).

## 4. Deviations and findings

| # | What | Why |
|---|---|---|
| C-D1 | Reason code `IN_THIS_CITY` (plan draft said `IN_YOUR_CITY`) | The Edition city is chosen; it need not be the viewer's |
| C-D2 | City section titled "New in {city}" | Short, true ("since the start of last week") |
| C-D3 | `AccountEraser` also deletes everyone's butterflies on the erased person's posts (before the posts) | The posts go; rows pointing at them must go first (no FKs in the H2 tests) |
| C-D4 | "Been here too" reason ignores the viewer's own blocks | It is a yes/no per post, never who or how many |
| C-D5 | Place pages show only the place itself, not places merged into it | Merging is 13D |
| C-D6 | **Emulator bug, fixed:** the Cocoon row read "Saved shared this week" (a band sentence glued after "Saved") | Own strings `cocoon_saved_this_week/_month/_earlier`; `SocialTextsTest` now pins the wording |
| C-D7 | **Intermittent test failure seen once:** in the first full backend run `SocialAccountDeletionTest.anAnonymisedAccount…` got 409 from `PUT /api/profiles/me` while creating its person; not reproduced in two more full runs nor in a subset run | Cause not found; `SocialClient.person` now fails with the server's message so a repeat can be diagnosed. Reported, not hidden |
| C-D8 | Places created before V22 keep `city` NULL | None existed (dev DB had 0 places) |

## 5. Security and privacy review

- **Who butterflied what is never exposed:** rows are read only by their owner (`/api/cocoon`, `yourButterfly`); the
  author gets a count, thresholded at 3 (C2), never a list; no endpoint lists butterflies of a post; nothing orders by
  them. Remaining honest limit: an author whose post moves from "fewer than 3" to "3" right after telling three friends
  can guess; documented, inherent to any count (invariant 6 allows the author's count).
- **Counts:** the only number in any answer is the author's own tally; `SocialTextsTest` allows `%d` only in
  `butterfly_author_count`, which must say "Only you can see this line".
- **Location:** no coordinate anywhere; place cities come from the server's boxes; AREA posts never appear on place
  pages (they promise not to reveal the place); the Edition never sends a 7-character cell of an AREA post (live L6).
- **Time:** bands only (`savedPeriod`, `postedPeriod`); `weekOf` is a public calendar date; others' posts are ordered by
  `visible_from` (the Q1 delay holds in every list: live L3/L5, test).
- **Monitoring:** no people search (live L13); From your connections is limited to 30 days and to posts already visible
  on the profile; nothing records what was seen; mute and block apply to every list (live L25/L26, four-user test).
- **Scraping:** in-memory limits (butterflies 200/day, Discover 240/h, searches 120/h), Everyone posts only on public
  lists, ≤ 20 search results.
- **Isolation:** `SocialIsolationTest` unchanged and green: social reads no safety data (place signals are NOT shown on
  place pages for that reason); the only `@Scheduled` in social is still the photo clean-up.
- **Phone:** nothing about other people stored; the chosen city is not saved; DTOs fail closed (unknown state/kind/
  section/reason dropped; a tally on someone else's post or under 3 dropped).

## 6. Viva notes

- **What / why:** intent ("I want to experience this") instead of approval; discovery that ends.
- **Key classes:** `Butterfly`, `ButterflyService`, `ButterflyTally`, `CityAreas`, `EditionPolicy`, `DiscoverService`,
  `Visibility.showInLists`, `Relationships.viewer/forLists`; Android `DiscoverViewModel`, `CocoonViewModel`,
  `PostCollectionViewModel`, `CityMatch`.
- **Flow:** list query (pre-filter in SQL) → every post through `Visibility` + mute → `PostService.respond` (bands, own
  state, author-only tally) → reason codes → the phone writes the words.
- **Likely questions:** Why one table for butterflies and Cocoon? (one fact, cannot disagree.) Why hide counts below 3?
  (a "1" in a small audience names a person.) Why no ML ranking? (explainable, no engagement signal, design §3.7.) Why
  are AREA posts not on place pages? (the author chose not to reveal the place.) Why IST for the week? (launch city's
  calendar; storage stays UTC.)
