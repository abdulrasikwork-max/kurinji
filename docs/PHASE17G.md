# Phase 17G-1 — Deterministic Explore (2026-11-03)

Status: **17G-1 implemented, compiled, tested, live-verified on MySQL, emulator-verified.** Not verified on a physical phone (the
Galaxy A23 was not connected). 17G-2 ("I went" + ratings, U5) and 17G-3 (optional Claude understanding) are **not started**; the plan
and the approved decisions are `PHASE17G_PLAN.md`. Nothing here calls an AI service, needs a key, or costs anything to run.

## 1. What was built

**A person says what they are looking for; the phone understands the words with its own word list (English, Tamil, Tanglish) and
shows the result as chips they can change; Search sends ONLY those chips; the server's deterministic engine lists matching places.**

| Part | What |
|---|---|
| Tags ("Good for…") | 13 closed tags on **experiences** (not Asks), up to 4, chosen in the composer; shown on the post page. `explore-facets-v1.tsv` (code, English, Tamil, Tanglish), byte-identical in the backend and the app; V29's CHECK list is the same codes |
| Understanding on the phone | `ExploreLexicon` (word list `explore-lexicon-v1.tsv` + the tag names) and pure `ExploreQueryParser`: longest phrase first, a city by name or other name ("Trichy"), limits (2 categories, 4 tags, 1 time of day, 1 city), unknown words shown as **"We did not use: …"**, negation **not understood** (the next word is shown as unused, never inverted), nothing stored or sent |
| The structured query | `{city, categories[≤2], facets[≤4], daypart?}`. Categories are alternatives, so they count as **one** requirement; `requested` = (categories ? 1 : 0) + tags + (time of day ? 1 : 0) |
| The engine | pure `ExplorePolicy` + `ExploreService` (package `social`): candidates are Everyone-audience **EXACT-place Experiences** in the city, not the viewer's, already visible, newest 400 by `visible_from`, through `Relationships.forEdition` (blocks, mutes, hold/removal, collapse); tags and times are read only from those posts; order = matches, then newest readable experience, then name; at most 20 places, the list always ends |
| API | `POST /api/explore/search` (structured body, never free text; `SocialLimits.explore`, 120/hour); `facets` on post create / edit / read (edit: `null` keeps, `[]` clears, a list replaces) |
| Android | Explore screen (Discover → "Explore"), results open the place page; composer tag chips (disabled at four); post-page chip; no history, nothing stored (Q3) |
| Database | **V29** `post_facets` (id, post_id → posts ON DELETE CASCADE, facet binary CHECK list, UNIQUE (post, facet)) |

## 2. Findings made while building (they changed the plan's wording, not its scope)

- **EXACT only.** An AREA post promises not to reveal its place (C10); Explore therefore reads only EXACT posts. (The plan said "posts that have a place".)
- **One category requirement.** A place has one category, so counting two asked categories separately could never give a full match.
- **No cascade on the test database.** H2 builds the schema from the entities, so `ON DELETE CASCADE` exists only on MySQL: `PostService.delete` and `AccountEraser` delete the tags explicitly (new eraser step; `SocialAccountDeletionTest` seeds a tagged post).
- **Probation limits posts to 3 a day** on a new account: the live script's 4th and 5th posts got a correct 429 and were re-run with a fresh account.

## 3. Security, privacy and rules review

- **The typed words never leave the phone.** `ExploreRequestDto` has exactly four fields (a test pins them); the app source names no AI service and holds no key (a test scans it).
- **No coordinates, no times, no counts** in the answer: the answer's field names are pinned; tags are a property of a post and are read only for posts the viewer may read (four-user API tests: stranger, connection, author, blocked, muted, held, collapsed, ONLY_ME, connections-only, AREA-only, still inside the exact-place delay, merged place, other city).
- **No popularity input:** `ExploreIsolationTest` scans the Explore code (comments stripped) for counts of posts, people, reactions, ratings and `createdAt`; `ExplorePolicyTest` shows extra tags and extra experiences never lift a place.
- **No safety claims:** no tag or word-list entry is about safety, lighting, women or danger (tests on both files and on the wording); Explore reads no place-signal, intelligence, evaluation or safety data and calls nothing outside (`SocialIsolationTest`, `ExploreIsolationTest`).
- **Honest limits:** the Tamil and Tanglish word list is a **draft not yet reviewed by a Tamil reader** (file header, tests, here); Tamil endings are recognised only where a form is written out; "I did not use" tells the person what was ignored. A tag by one author is enough for a place to match (no popularity logic, by design).

## 4. Results (2026-11-03)

| Check | Result |
|---|---|
| Backend `mvn test` | **818 tests, 0 failures, 2 skipped** (792 before; +26: `ExplorePolicyTest` 7, `ExploreFacetsParityTest` 5, `ExploreIsolationTest` 4, `Phase17gExploreApiTest` 10) |
| Android `testDebugUnitTest` | **927 tests, 0 failures** (896 before; +31 `Phase17GExploreTest`) |
| Android lint | **0 errors, 104 warnings** (unchanged; 8 new ones found and fixed: API-28 attribute, unused strings, a plural) |
| V29 on MySQL 8.0.46 | backup first (`mysqldump`, 44 tables); **dry run on scratch tables with the real DDL: 14/14 valid rows accepted, 9/9 invalid refused** (duplicate, unknown code, lower-case, empty, NULL, 21 characters, a safety word, missing post, NULL post), cascade confirmed; applied by Flyway (`now at version v29`); `SHOW CREATE TABLE` matches the dry run |
| Live API on MySQL | **31/31 PASS** (three real accounts through register + the dev-file OTP; tagged experiences; stranger / connection / author / block; refusals; edit semantics; rows in `post_facets`; delete removes tags). Two checks were re-run with a fresh account because the first hit the new-account post limit. Scratch accounts, places and tags were deleted afterwards (0 left, no orphans, `posts` back to 6) |
| Emulator (API 37) | PASS: Discover → Explore; "quiet scenic spot in the evening xyzzy" gave the chips Quiet · Scenic · Evening and "We did not use: xyzzy"; Search listed the place with "Matches 3 of the 3 things you asked for", its tags and the end line; the card opens the place page; the post page shows "Good for: Quiet · Scenic"; the composer row disables the other 9 tags at four; 2.0x text: chips wrap, buttons and card readable |
| Galaxy A23 | **not run** (not connected) |

## 5. Not verified / to do

- **A physical phone** (A23): the Explore screen, composer tags and 2.0x/dark mode on the real device; TalkBack by ear (the card has one spoken description, checked in the accessibility tree only).
- **A Tamil reader must review `explore-lexicon-v1.tsv`** (decision G-6) and the Tamil/Tanglish tag names; until then a Tamil request may use a word the list lacks.
- Explore on real data: only scratch data was used; a demo needs a few tagged experiences by two accounts.
- Not built (17G-2, 17G-3): "I went" record, ratings, "% match" beyond "x of y", Claude understanding.

## 6. Manual checklist for you

1. Install the build, sign in, open Discover → **Explore**; type "quiet cafe to study in the evening" and see the chips; change a chip; Search.
2. Share an experience at an exact place with 2 tags (composer → "Good for"); from a second account (not connected) search for that tag: the place appears; from a connection: the same; a connections-only post does not.
3. Type Tamil/Tanglish requests ("amaithiyana kaapi maalai") and send the words that are not understood so they can be added to the list.
4. Large text (2.0x) and dark mode on the phone; TalkBack on the Explore screen.

## 7. Viva notes

*What / why:* a free-text request is turned into a closed-list query **on the phone**, so the server never sees the words and the same engine can later serve any interpreter (D2: evidence from an interpreter, a deterministic policy decides). *Key classes:* `ExploreQueryParser`, `ExploreLexicon`, `ExploreFacets`, `ExplorePolicy`, `ExploreService`, `ExploreViewModel`, `PostFacet`. *DB / API flow:* phone parses → `POST /api/explore/search {city, categories, facets, daypart}` → candidates by SQL pre-filter → the one visibility rule → tags/times from visible posts → `ExplorePolicy.rank` → places. *Lifecycle:* nothing is stored or cached; leaving the screen forgets it. *Privacy:* no coordinates, no counts, no times, no authors; AREA posts never reveal their place; ordering cannot be bought by posting more. *Likely questions:* why EXACT only? why are categories one requirement? why no negation? why is the word list on the phone? how would Claude plug in (17G-3: it would only fill the same chips; the engine and every rule stay)?
