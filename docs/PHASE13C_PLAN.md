# Phase 13C — Intent and discovery: implementation plan

**Date:** 2026-10-23 · **Status: implemented, compiled, tested and verified 2026-10-23 (results and deviations C-D1–C-D8: `docs/PHASE13C.md`); plan-level choices C1–C15 decided under the user's delegation of 2026-10-23**
(the user delegated plan-level and technical decisions to the session; core product direction, D1–D15 and Q1–Q12 are
unchanged). Design: `docs/EXPERIENCE_PLATFORM_DESIGN.md` §3.4 (butterfly + Cocoon), §3.5 (places), §3.7 (discovery),
§3.8, §4 (privacy, invariant 6), §9.2–§9.3, §9.6, §11, §12. Builds on 13B (`docs/PHASE13B.md`).

## 1. Goal and scope

After 13C a person can:

- tap **🦋 "I want to experience this"** on someone's Experience: it is saved privately to their **Cocoon** as *Want to
  experience*; later they mark it **Been**, and are invited to add a *Been here too* perspective or their own
  Experience. The author privately sees how many people want to experience their post (§2 C2); nobody else sees any
  number, and nobody can list who butterflied what;
- open **Discover**: a finite **weekly Edition** for a city (experiences in the city, Asks they could answer, one theme
  of the week) that ends with *"That's this week's edition. Go make your own."*, and **From your connections**
  (chronological, ends, more only on "Show more"); every Edition card answers **"Why am I seeing this?"**;
- open **place pages** and **category pages**, and **search** by place name or category (no people search);
- rely on **mute** taking effect in all these lists (13B Q8).

**Not in 13C:** reports, holds, moderation of posts (13D); AI (13E); the new navigation, Discover tab and visual
identity (13F: until then Discover and the Cocoon are reached from the Connections screen, like 13B's entry points);
"Your butterfly landed" and the Activity list (V2); push notifications (V2); the map of places (later); "Hide a place or
category from my Discover" (design §4.5; 13F with the Discover tab); "Good to know" heads-ups from place signals on place
pages (would make the social package read community safety data, which `SocialIsolationTest` forbids; needs its own
decision); saving a place without a post to the Cocoon (one tap = one meaning). Nothing in the safety stack changes.

## 2. Plan-level choices (decided under the user's delegation of 2026-10-23)

| # | Choice | Decision | Why |
|---|---|---|---|
| **C1** | Butterfly and Cocoon storage | **One table `butterflies`** = the Cocoon: (person, post, state WANT/BEEN). The butterfly *is* the Cocoon item (design: "one tap, one meaning, no separate save") | Two tables would let them disagree; one row per (person, post) is the whole fact |
| **C2** | The author's private count (design §3.4.4, invariant 6) | Shown **only to the author**, and **only from 3 people up**; below that the author sees "fewer than 3 people so far" (0, 1 and 2 look the same). Never on Only-me posts. Never used for ordering | With a small audience (a Connections post seen by two friends) an exact "1" tells the author *who* butterflied it. A k = 3 floor (the Phase 6 k-anonymity idea) keeps the count private in practice; it only removes information |
| **C3** | What can be butterflied | **Experiences of other people that the viewer can see.** Not Asks (nothing to experience), not one's own posts | "I want to experience this" needs an experience by someone else |
| **C4** | A saved post the viewer can no longer see (deleted, narrowed, block, author gone) | Deleted post → its butterflies are deleted with it. Otherwise the row stays but the Cocoon **does not show it** (design §11.2); it comes back if the post becomes visible again; the owner can still remove it by the post id | The Cocoon must never become a back door around visibility |
| **C5** | Edition unit | A **city from the closed list** (Q2), not "near you": Discover never asks for location. The phone preselects the city when the profile's city text names one of the list; otherwise the person picks a city (kept only on the screen) | Design §3.7 says "for your city"; asking for location to read would be a location habit the design avoids |
| **C6** | Which posts belong to a city | CITY posts by their city; EXACT and AREA posts by their **place's city**, computed on the server from the place's cell with a fixed table of the launch cities' bounding boxes (`CityAreas`, stored as `places.city` in V22; NULL outside every box). NONE posts are in no city | No new input, no places API (D11); boxes change by code + migration |
| **C7** | Edition contents | Built when asked, from **Everyone** posts only (the Everyone promise: "It appears on the place page"): **In {city}** = Experiences that became visible since the start of last week (≤ 12); **Asks you could answer** = open Asks (no answer marked helpful) from the last 4 weeks, not your own (≤ 5); **This week's theme** = one category, rotating weekly through the closed list, Experiences from the last 8 weeks not already shown (≤ 6). Your own posts, muted and blocked people are left out. Week = Monday 00:00 Asia/Kolkata (launch city; stored times stay UTC) | Finite by construction; a new pilot is not empty for a week; no stored "edition" table |
| **C8** | Ordering and "Why am I seeing this?" | Deterministic score from **content and the viewer's own choices only**: has a place, has when (month + time of day), has a tip, matches your interests, someone has been here too (a visible *Been here too* exists; a yes/no, never a number); ties → newer first. Each item carries its **reason codes**; the phone writes the words. **Never** butterflies, views, comment counts or any engagement signal | Design §3.7: explainable, never engagement prediction; butterflies are private and must not become popularity |
| **C9** | From your connections | Accepted connections' posts the viewer may see (Everyone + Connections), **last 30 days**, newest visible first, 20 per page, more only on "Show more", then an end card. Nothing records what was seen | "Ends when you have seen it" without a read-receipt-like record |
| **C10** | Place pages | **Only EXACT posts with audience Everyone** at that place, newest first, paged | An AREA post promises not to reveal its place; a Connections post was not promised to the place page |
| **C11** | Category pages | Everyone posts of that category, optionally in one city, newest first, paged | Same promise as place pages |
| **C12** | Search | **Place names only** on the server (normalised "contains", 2–40 characters, optional city, ≤ 20, rate-limited); categories are matched on the phone against the closed list. No people search, no full-text post search | Design §3.7 (no people search in V1); names are already public |
| **C13** | Mute in lists (Q8) | Muted people are left out of the Edition, From your connections, place pages and category pages. A deliberate profile visit, a post link and your own Cocoon still show them | Mute = "stop showing me", not a block |
| **C14** | Times in Cocoon answers | Bands (`savedPeriod`), even to the owner | One way of showing time everywhere; nothing needs more |
| **C15** | Limits (in memory) | Butterfly changes 200/day; discover reads 240/h; place searches 120/h | Anti-scraping without storing who looked at what |

## 3. Database: migration V22 (`V22__intent_discovery.sql`)

InnoDB, utf8mb4_unicode_ci, `DATETIME(6)` UTC, FKs cascade. **MySQL dry run before applying.** CHECKs only on columns
that are not part of a cascading FK (the V6/V20 rule).

| Change | Columns and constraints |
|---|---|
| `places.city` | `VARCHAR(20) COLLATE utf8mb4_bin NULL`, CHECK closed list **without OTHER**, index `(city)`. Set by the server from the cell (C6); never sent by the phone |
| `butterflies` | `id`, `user_id` FK users cascade, `post_id` FK posts cascade, `state` WANT/BEEN (binary CHECK), `created_at`, `been_at` NULL, CHECK `(state = 'BEEN') = (been_at IS NOT NULL)`, CHECK `been_at >= created_at`, **UNIQUE (user_id, post_id)**, index `(user_id, state, created_at)`, index `(post_id)` |
| `posts` | index `(audience, visible_from)` for the Edition, category and connections lists |

`AccountEraser`: new reference `butterflies.user_id` (the person's Cocoon) and the butterflies on their posts (before the
posts). `AccountEraserCoverageTest` forces it.

## 4. Backend (`social`, extended)

| Area | Files |
|---|---|
| `entity` | `Butterfly`, `PostEnums.ButterflyState`; `Place.city` |
| `policy` (pure) | `CityAreas` (bounding boxes → city), `EditionPolicy` (week start, theme of the week, score + reason codes, section caps), `ButterflyTally` (C2) |
| `service` | `ButterflyService` (save / mark Been / back to Want / remove / Cocoon list), `DiscoverService` (Edition, connections list, place page, category page, place search); `Relationships.viewer(...)` batches blocks/mutes/connections for lists — every item still passes `Visibility.canSeePost` |
| `controller` | `ButterflyController` (`/api/posts/{id}/butterfly`, `/api/cocoon`), `DiscoverController` (`/api/discover/*`, `/api/places/{id}/posts`, `/api/places/search`) |
| changed | `PostService.respond` adds `yourButterfly` (viewer's own state) and `butterflies` (author only, C2); `PostService.delete` deletes the post's butterflies; `PlaceService.create` sets the city; `SocialLimits`, `SocialProperties` |

## 5. API (all authenticated; invisible = 404; no counts except C2 to the author)

| Method and path | Body / result | Errors |
|---|---|---|
| `PUT /api/posts/{id}/butterfly` | `{state?: WANT\|BEEN}` (default WANT) → 200 `{state}` | 400 own post / Ask / unknown state; 404 not visible; 429 |
| `DELETE /api/posts/{id}/butterfly` | → 204 (also when the post is no longer visible to you) | 404 none |
| `GET /api/cocoon?state=WANT\|BEEN&cursor=` | `{items[{post, state, savedPeriod}], nextCursor}` (newest saved first, 20 per page, invisible posts skipped) | 400 |
| `GET /api/posts/{id}` (and every post answer) | adds `yourButterfly` (null/WANT/BEEN, the viewer's own) and `butterflies` (author only: `{count, fewerThanThree}`; null for others and on Only-me posts) | — |
| `GET /api/discover/edition?city=` | `{city, weekOf, theme, sections[{kind CITY\|ASKS\|THEME, items[{post, reasons[]}]}]}`; empty sections left out | 400 city; 429 |
| `GET /api/discover/connections?cursor=` | `{items[{post, reasons[FROM_A_CONNECTION]}], nextCursor}` | 400; 429 |
| `GET /api/discover/categories/{category}?city=&cursor=` | post page | 400; 429 |
| `GET /api/places/{id}/posts?cursor=` | `{place, items, nextCursor}` (EXACT + Everyone only) | 404; 429 |
| `GET /api/places/search?q=&city=` | places `[{publicId, name, category, cell, areaCell, city}]` ≤ 20 | 400; 429 |

Reason codes: `IN_THIS_CITY`, `RECENT`, `HAS_PLACE`, `HAS_WHEN`, `HAS_TIP`, `MATCHES_YOUR_INTERESTS`, `BEEN_HERE_TOO`,
`OPEN_ASK`, `THEME_OF_THE_WEEK`, `FROM_A_CONNECTION`.

## 6. Android (`feature/social`, extended)

| Area | Files |
|---|---|
| `domain` | `DiscoverModels` (Edition, section, item, reasons, Cocoon item, butterfly state, tally; unknown codes dropped / fail closed), `CityMatch` (profile city text → closed list, "Madras" → Chennai), `ExperienceRepository` extended |
| `data` | `ExperienceApi` + DTOs + `RemoteExperienceRepository` extended |
| `ui` | `DiscoverFragment` + `DiscoverViewModel` (city chips, Edition sections, "Why am I seeing this?", end card, From your connections with Show more and end card, search: places + categories), `PostCollectionFragment` + `PostCollectionViewModel` (place page / category page), `CocoonFragment` + `CocoonViewModel` (Want / Been, mark Been → invitation, remove), `PostFragment` (🦋 button with TalkBack label "Want to experience this. Saves to Cocoon.", author's private line, Been invitation), `DiscoverTexts` (reason words) |
| changed | `ConnectionsFragment` ("Discover", "Your Cocoon"), `nav_graph.xml`, strings |

Nothing about other people is stored on the phone (the Edition and the Cocoon are loaded when the screen opens).
Reduce-motion: no flight animation is built in 13C (13F); the button changes state with text and icon.

## 7. Tests

**Backend:** `CityAreasTest` (each city centre, outside, edges), `EditionPolicyTest` (week start in IST, theme rotation,
scores, reasons, caps), `ButterflyTallyTest`, `ButterflyApiIntegrationTest` (save, Been, back, remove, Ask/own refused,
invisible 404, tally author-only with the floor, Cocoon hides invisible posts and shows them again, paging, deletion of
a post removes its butterflies), `DiscoverApiIntegrationTest` (Edition sections and caps, city by place and by CITY post,
Everyone only, delay respected, mute and block, own posts out, connections list window/paging/end, place page EXACT
only, category page, search, limits), `SocialFourUserTest` extended (owner / connection / stranger / blocked / muted on
every new path), `SocialAccountDeletionTest` + `AccountEraserCoverageTest` (butterflies), `SocialIsolationTest`
(unchanged rules), privacy invariants (no coordinate fields, bands only, no tally for non-authors).

**Android:** `CityMatchTest`, DTO fail-closed tests, `DiscoverViewModelTest`, `CocoonViewModelTest`,
`PostCollectionViewModelTest`, `PostViewModel` butterfly tests, `SocialTextsTest` extended (no counts outside the
author's line, no "trending/popular/for you/top/most …" ranking words, the floor sentence present).

**Verification:** lint; V22 dry run on MySQL with negative inserts for every CHECK and unique rule, then applied; live
API script (throwaway accounts, try/finally clean-up, user count before = after); emulator (API 37) walk-through.

## 8. Risks

| Risk | Mitigation |
|---|---|
| The author's count reveals who | C2 floor of 3; never shown to anyone else; never on Only-me |
| Butterflies become popularity | Never ordered by, never public, no "most wanted" anywhere (test on words) |
| Discover as a scraping tool for where people go | Everyone posts only, bands only, AREA posts never on place pages, delay respected, in-memory limits |
| City boxes wrong at the edges | Approximate by design, documented; a place outside every box is simply in no Edition |
| Empty Edition in a small pilot | Rolling window since the start of last week, theme from 8 weeks; honest empty state |
| Feed creep (infinite scroll) | Caps, explicit "Show more", end cards, `SocialTextsTest` |
| MySQL-only DDL | Mandatory dry run |

## 9. Order of work (each step buildable)

1. V22 + `Butterfly` + `places.city` + `AccountEraser` → tests.
2. `CityAreas`, butterflies + Cocoon (service, API, tally) → tests.
3. Discover (Edition, connections, place/category pages, search) → tests, four-user tests.
4. Android data layer, then post view butterfly, Cocoon, Discover, place/category pages → tests.
5. Lint, MySQL dry run + apply, live API script, emulator, security/privacy review, docs, commit.
