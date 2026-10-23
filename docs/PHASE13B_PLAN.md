# Phase 13B — Experiences: implementation plan

**Date:** 2026-10-22 · **Status: approved by the user on 2026-10-22 (Q1–Q12 as recommended); implemented, compiled,
tested and emulator-verified 2026-10-22/23.** Results and the deviations B1–B14: `docs/PHASE13B.md`.
Design: `docs/EXPERIENCE_PLATFORM_DESIGN.md` §3.2–§3.5, §4, §9.4–§9.8, §11. Builds on 13A (`docs/PHASE13A.md`).
Decisions used: D2 (no AI here; that is 13E), D7 (Coil), D8 (Takes in V2), D11 (places user-created), D14 (no preselected
audience on the first post).

## 1. Goal and scope

After 13B, a person can **share an Experience or an Ask**, with up to 6 photos, attached to a **place** at the
**precision they choose** (exact place, area, city, none), with **when** it happened as a month and a time of day, for
**Everyone / Connections / Only me**. Others who may see it can read it on the author's profile and in a post view, and
add **Perspectives** (Been here too / Question / Reply; on an Ask: answers the asker can mark "This helped"). The author
controls who may comment and can hide comments. **Private places** on the phone stop posts from revealing home, hostel
or work.

**Not in 13B:** butterflies, Cocoon, Discover/Edition, place and category pages, map, search (13C); reports, holds,
strikes, edit history, "approve first" for comments (13D); text and image classifiers, the "post this as an anonymous
heads-up instead?" suggestion (13E); the new design system and navigation (13F); Takes (V2). Nothing in the safety
stack changes.

**Where people see posts in 13B** (until Discover in 13C): the author's profile ("Experiences"), the post view, and the
caller's own list. That is enough to build and verify everything above.

## 2. Plan-level choices (recommended defaults; the user may change any)

| # | Choice | Recommendation | Why |
|---|---|---|---|
| **Q1** | Exact-place posts appear to others **after a random 1–3 hour delay** (the author sees theirs at once) | **Yes**, for every audience (connections included) | Turns "posted from here" into "was here earlier": the main stalking risk of experience posts. Area / city / no place: no delay |
| **Q2** | City | **Closed list** (Chennai, Coimbatore, Madurai, Tiruchirappalli, Salem, Tirunelveli, Vellore, Erode, Thoothukudi, Thanjavur, Puducherry, Bengaluru, Other) | Free text splits one city into "Chennai / chennai / Madras"; the list grows by migration |
| **Q3** | Coil image cache | **Memory only, no disk cache** | Nothing about other people stays on the phone (13A rule); photos reload when a screen opens |
| **Q4** | Photos | ≤ 6 per post; phone resizes to ≤ 2048 px and re-encodes JPEG (drops all metadata); **server decodes and re-encodes again** (defence in depth), ≤ 5 MB in, JPEG or PNG in, JPEG out | Design §4.4 "EXIF stripped twice" |
| **Q5** | A comment the post author hides | **Transparent**: the commenter sees "Hidden by the author"; others see nothing | Design: no secret reach reduction |
| **Q6** | "Approve people I'm not connected with first" (design V1 list) | **Deferred to 13D** (with the moderation queue) | It needs a pending state and a review screen; author can already limit comments to connections or nobody, and hide |
| **Q7** | Edit history of posts | **Deferred to 13D**; 13B marks a post "edited" | Only moderation needs revisions; there is no pilot before 13D (D12) |
| **Q8** | Mute | Takes effect where lists of others' posts appear (13C). In 13B a deliberate visit to a profile still shows its posts | 13B has no feed; a mute is "stop showing me", not a block |
| **Q9** | Private places and recently used places on the phone | **Encrypted on the phone** (Keystore key, the Rise/Evidence pattern), per account, removed on sign-out and account switch; never uploaded | They are the most sensitive locations a person has |
| **Q10** | One scheduled clean-up job in `social` (unattached photos older than 24 h; delayed posts need none) | **Yes**; `SocialIsolationTest` allows exactly that class | Without it, photos uploaded and never posted would stay on disk |
| **Q11** | Entry points until 13F | "Share an experience" on the Connections screen and on one's own profile; "Experiences" list on every profile | Smallest change to today's navigation; 13F rebuilds it |
| **Q12** | Places record who created them | **No creator, only the day** (`created_on DATE`) | Creating a place must not be a record of who was where; moderators can still merge duplicates (13D) |

## 3. Database: migration V21 (`V21__experiences.sql`)

InnoDB, utf8mb4_unicode_ci, `DATETIME(6)` UTC, FKs cascade. **MySQL dry run before applying.** CHECKs only on columns
that are not part of a cascading FK (the V6/V20 rule); the rest in code.

| Table | Columns and constraints |
|---|---|
| `places` | `id`, `public_id` (UUID CHECK, unique), `name VARCHAR(60)` (trimmed, 2–60 chars CHECK), `normalized_name` (lower-case, for de-duplication), `category` (closed list = the 10 interest codes, binary CHECK), `cell CHAR(7)` + `area_cell CHAR(6)` (Phase 6 geohash CHECKs, `area_cell = LEFT(cell, 6)`), `created_on DATE`, `merged_into` (self FK, `SET NULL`), **UNIQUE (cell, normalized_name)**, index `area_cell`. **No creator, no coordinates.** |
| `posts` | `id`, `public_id`, `author_id` FK, `type` EXPERIENCE/ASK (CHECK; TAKE reserved, rejected), `body TEXT`, `tip VARCHAR(300) NULL` (Experience only), `category` (closed list) NULL, `place_id` FK `SET NULL` NULL, `precision` EXACT/AREA/CITY/NONE (CHECK), `area_cell CHAR(6) NULL` (copied from the place for AREA; never a 7-char cell), `city` (closed list, CHECK) NULL, `experienced_month DATE` (first day of a month, CHECK `DAY() = 1`) NULL for Ask, `daypart` MORNING/AFTERNOON/EVENING/NIGHT NULL, `audience` EVERYONE/CONNECTIONS/ONLY_ME (CHECK), `comment_policy` EVERYONE/CONNECTIONS/NOBODY (CHECK), `created_at`, `visible_from` (≥ `created_at`; = `created_at` unless Q1 delay), `edited_at` NULL, index `(author_id, created_at)`, index `(area_cell, visible_from)` for 13C |
| `post_media` | `id`, `public_id`, `owner_id` FK, `post_id` FK cascade NULL (NULL = uploaded, not yet attached), `position` 0–5 (CHECK), `storage_key CHAR(36)` (unique, UUID CHECK), `width`, `height` (1–4096 CHECK), `bytes` (≤ 5 MiB CHECK), `sha256` (lower hex CHECK), `created_at`; **UNIQUE (post_id, position)** |
| `comments` | `id`, `public_id`, `post_id` FK cascade, `author_id` FK, `parent_id` (self FK cascade, NULL = top level; one level, enforced in code), `kind` BEEN_HERE/QUESTION/REPLY/ANSWER (CHECK), `body VARCHAR(1000)` (1–1000 chars CHECK), `hidden_by_author_at` NULL, `helped_at` NULL (asker's "This helped", ANSWER only — code), `created_at`, index `(post_id, created_at)` |

Precision rules (code + tests; some also CHECKs): EXACT and AREA need a place; CITY needs a city and no place; NONE
needs neither; AREA stores only the 6-character area; ONLY_ME posts take no comments.

`AccountEraser`: new references `posts.author_id`, `post_media.owner_id`, `comments.author_id` (deleted for deleted and
anonymised accounts); **photo files are deleted before the rows** (the Evidence pattern: collect storage keys, delete
files outside the transaction, a failure stops and retries next run). `AccountEraserCoverageTest` forces this.

## 4. Backend (`com.safesphere.backend.social`, extended)

| Area | Files (planned) |
|---|---|
| `entity` | `Place`, `Post`, `PostType`, `Precision`, `Audience`, `CommentPolicy`, `Daypart`, `PostMedia`, `Comment`, `CommentKind` |
| `policy` (pure) | `Visibility` extended with **`canSeePost`** (author always; others: not blocked either way, author active, `visible_from` reached, and EVERYONE, or CONNECTIONS + connected; ONLY_ME never) and **`canComment`** (can see + policy + not blocked + not ONLY_ME); `PostPolicy` (type/precision/place/city/month/daypart combinations, lengths: Experience body 1–5000, tip ≤ 300, Ask body 10–1000); `TimeBands` (posted period for non-authors: THIS_WEEK / THIS_MONTH / EARLIER; experienced month never in the future); `PlaceNamePolicy` (cleaning as profiles, no links, refuses obvious private-place names such as "my home", "hostel room"); `PostDelay` (Q1: random 1–3 h for EXACT) |
| `media` | `MediaStorage` (folder `app.social.media-dir`, random UUID file names, never served statically — the `EvidenceStorage` pattern), `ImageSanitizer` (reads dimensions BEFORE decoding to refuse decompression bombs; JPEG/PNG magic bytes; decode with ImageIO; re-encode JPEG without metadata; ≤ 4096 px) |
| `service` | `PlaceService` (create with de-duplication per cell + name, nearby by area cell + 8 neighbours), `PostService` (create, read, edit, delete, lists; photos attached atomically; files deleted after the rows on delete), `CommentService`, `MediaService`, `SocialMediaCleanupJob` (Q10) |
| `controller` | `PlaceController`, `PostController`, `CommentController`, `MediaController` |

Every read path — post, profile list, own list, comments, photo bytes — goes through `Visibility`; the four-user test is
extended to all of them. Answers carry **bands, not timestamps**, for everyone but the author, and never a 7-character
cell for AREA posts.

## 5. API

All authenticated; invisible = 404; random public ids; no counts; `app.social.*` limits in memory.

| Method and path | Body / result | Errors |
|---|---|---|
| `POST /api/places` | `{name, category, cell(7)}` → place `{publicId, name, category, areaCell}`; an existing place with the same name in that cell is returned instead | 400 rules; 429 (10/day) |
| `GET /api/places/nearby?area=` (6) | places in the area and its 8 neighbours, ≤ 100 | 400; 429 (60/h) |
| `GET /api/places/{id}` | one place | 404 |
| `POST /api/media` (`image/jpeg` or `image/png`, ≤ 5 MB) | `201 {mediaId, width, height}` — sanitised and stored, owner-only until attached | 400 not an image / too large / too many pixels; 429 (30/day) |
| `GET /api/media/{mediaId}` | `image/jpeg` bytes, `Cache-Control: private, no-store` | 404 (not visible) |
| `POST /api/posts` | `{type, body, tip?, category?, placeId?, precision, city?, experiencedMonth?, daypart?, audience, commentPolicy, mediaIds[≤6]}` → `201` post (own view) | 400 rules; 404 place/media not usable; 409 profile needed; 429 (20/day) |
| `GET /api/posts/{id}` | post as the viewer may see it: `{publicId, type, author{publicId, handle, displayName}, body, tip, category, place{publicId, name}? (EXACT only), areaCell? (AREA), city?, experiencedMonth, daypart, postedPeriod, edited, media[{mediaId, width, height}], audience (author only), commentPolicy, canComment}` | 404 |
| `PUT /api/posts/{id}` | author edits body, tip, category, daypart, month, comment policy; may **narrow** audience or precision only | 400; 404; 409 widening |
| `DELETE /api/posts/{id}` | author → 204 (rows, then photo files) | 404 |
| `GET /api/posts/mine?cursor=` | own posts, newest first, 20 per page, explicit next cursor | — |
| `GET /api/profiles/{publicId}/posts?cursor=` | that person's posts the viewer may see | 404 profile not visible |
| `GET /api/posts/{id}/comments` | visible comments: BEEN_HERE first, then oldest first; on an Ask, answers marked helpful first; replies under their parent; each `{publicId, kind, author, body, postedPeriod, hidden (only to its writer), helped, parentId?}` | 404 |
| `POST /api/posts/{id}/comments` | `{kind, body, parentId?}` → 201 | 400 kind/nesting; 403-as-404 not allowed by policy; 429 (100/day) |
| `DELETE /api/comments/{id}` | its writer → 204 | 404 |
| `POST /api/comments/{id}/hide` · `/unhide` | the post's author → 204 | 404 |
| `POST /api/comments/{id}/helped` · `DELETE …/helped` | the asker, on an ANSWER → 204 | 404 |

## 6. Android (`feature/social`, extended)

| Area | Files (planned) |
|---|---|
| Dependency | **Coil 2.7.0** (`io.coil-kt:coil`), one `ImageLoader` in `AppContainer` built on the shared OkHttp client (so the JWT is attached), **memory cache only** (Q3) |
| `domain` | `Post`, `Place`, `Comment`, enums, `ComposerPolicy` (mirrors `PostPolicy` + `PostDelay` wording), `PrivatePlacePolicy` (a place is private when its cell is a private cell or one of its 8 neighbours → precision limited to City / None, and a new public place there is refused), `TimeBandTexts` |
| `data` | `SocialApi` extended, `RemoteSocialRepository` extended, `PhotoPreparer` (decode with sampling from the Photo Picker URI, rotate by orientation, scale to ≤ 2048 px, re-encode JPEG 85 — no metadata), `EncryptedPlacesStore` (private places + up to 20 recent places, Q9) |
| `ui` | `ComposeFragment` (type, photos with the system Photo Picker — no permission, text, tip, place step, precision chips, when, daypart, category, audience chips with their sentences and no preselection the first time, comment policy, "Share with …" button), `PlacePickerFragment` ("Near me" = one fresh fix like Community, with the location permission asked in context; "Places I used"; "Add this place"; "Only the city"; "No place"), `PostFragment` (photos, place line, bands, tip box, Perspectives with kinds, reply, hide/unhide for the author, helped for the asker), `PostListAdapter` (on profiles and "Your experiences"), `PrivatePlacesFragment` (mark "a private place" from a fix; list; remove) |
| Changed | `PersonProfileFragment` (Experiences list), `ConnectionsFragment` ("Share an experience", "Your experiences", "Private places"), `nav_graph.xml`, `AppContainer`, strings, `AccountDataWiper`/sign-out clean-up (encrypted places file) |

Location: only when the person taps "Near me"; one fix, then off. Only cells leave the phone (7 characters to create a
place, 6 to list nearby places). No coordinates are stored.

## 7. Tests

**Backend**

| Test | Covers |
|---|---|
| `PostPolicyTest`, `TimeBandsTest`, `PlaceNamePolicyTest`, `PostDelayTest`, `VisibilityTest` (extended) | Every type × precision × place/city combination; month rules; bands at their edges; delay range; the visibility and comment matrix |
| `ImageSanitizerTest` | A JPEG with an EXIF GPS block comes out with **no APP1/Exif segment**; PNG → JPEG; oversized pixel counts refused before decoding; non-images, truncated files, and a too-large body refused |
| `PlaceApiIntegrationTest` | Create, de-duplicate, nearby (area + neighbours only), invalid cells, limits; no creator column |
| `MediaApiIntegrationTest` | Upload → sanitised bytes stored; owner-only before attach; visible only with its post afterwards; `no-store`; orphan clean-up job |
| `PostApiIntegrationTest` | Create each kind; precision outputs (EXACT name, AREA 6-char only, CITY, NONE); bands not timestamps for others; Q1 delay (author sees at once, others after `visible_from`); edit + narrow-only; delete removes rows and files; lists with cursors; limits |
| `CommentApiIntegrationTest` | Kinds, one-level nesting, policy EVERYONE/CONNECTIONS/NOBODY, ONLY_ME takes none, hide is transparent to its writer only, helped by the asker only, ordering |
| `SocialFourUserTest` (extended) | Owner / connection / stranger / blocked on every new read path, photos and comments included, for each audience |
| `SocialIsolationTest` (adjusted) | Still no safety references; the only `@Scheduled` is `SocialMediaCleanupJob` |
| `AccountEraserCoverageTest` + `SocialAccountDeletionTest` (extended) | Posts, comments, media rows **and files** gone for deleted and anonymised accounts; other people's posts keep their comments minus the erased person's |

**Android:** `ComposerPolicyTest`, `PrivatePlacePolicyTest` (same cell, neighbour, far away), `TimeBandTextsTest`,
`EncryptedPlacesStoreTest` (round trip, wrong account cannot read, cleared on sign-out), ViewModel tests for compose,
place picker, post view, private places with a `FakeSocialRepository` and a fake location provider, DTOs fail closed,
`SocialTextsTest` extended (no counts, no "live"/"now here" wording, the delay explained honestly).

**Verification after tests:** lint; **V21 dry run** on MySQL with negative inserts for every CHECK and unique rule, then
applied; live API script with three accounts (try/finally clean-up, including the photo folder); emulator (API 37):
compose with photos, each precision and audience, private place refusal, post visible to a connection and not to a
stranger, delay shown honestly, perspectives and hide, delete; **the uploaded file pulled from the server folder and
checked for no EXIF**. Real phone, TalkBack speech and dark mode: not in 13B (A23 sprint before the pilot).

## 8. Risks

| Risk | Mitigation |
|---|---|
| Size: the biggest sub-phase so far (4 tables, photos, a new dependency, 5 screens) | Work in the order of §9 with the build green after each step; photos and comments are separate steps |
| Photos without an image classifier until 13E (nudity, other people's faces, documents) | No pilot before 13D/13E (D12); `no-store` serving; reports arrive in 13D; documented |
| Text without moderation until 13D/13E | Same; lengths, no links in names; no pilot |
| Stalking through "where and when" | Precision chosen per post (AREA/CITY/NONE), private places on the phone, Q1 delay, bands instead of timestamps, no live wording |
| Malicious images (decompression bombs, parser bugs) | Dimensions read before decoding, size cap, re-encode, never served as uploaded |
| Disk growth | 5 MB cap, 6 per post, 30 uploads/day, orphan clean-up, deletion removes files |
| Place duplicates and junk names | Unique per cell + normalised name; name rules; merging tools in 13D |
| Exif removal on the phone failing silently | The server re-encodes regardless; a server test proves it; the emulator check inspects the stored file |
| MySQL-only DDL | Mandatory dry run |
| Coil caching other people's photos on disk | Disk cache off (Q3) |

## 9. Order of work (each step buildable)

1. V21 + entities + `AccountEraser` (rows and files) → MySQL dry run.
2. Places (policy, service, API, tests).
3. Posts without photos (policy, bands, delay, visibility, API, four-user tests).
4. Photos (sanitiser, storage, media API, attach, clean-up job, tests).
5. Comments (policy, API, tests).
6. Android: Coil + data layer + encrypted places store, then composer and place picker, then post view and lists, then
   private places; tests.
7. Lint, MySQL apply, live API script, emulator walk-through, security/privacy review, `docs/PHASE13B.md`,
   `docs/API.md`, CLAUDE.md, HANDOFF.
