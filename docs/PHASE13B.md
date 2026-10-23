# Phase 13B — Experiences: results

**Date:** 2026-10-22/23 · **Plan:** `docs/PHASE13B_PLAN.md` (approved by the user 2026-10-22 with Q1–Q12 as recommended)
· **Design:** `docs/EXPERIENCE_PLATFORM_DESIGN.md` §3.2–§3.5, §4, §9.4–§9.8, §11 · builds on `docs/PHASE13A.md`.

| Part | Status |
|---|---|
| V21 (`places`, `posts`, `post_media`, `comments`) + `AccountEraser` (rows **and** photo files, files first) | **Implemented, compiled, tested; V21 dry run 79/79 on MySQL 8.0; applied (dev DB v21)** |
| Backend: places, posts (Q1 delay, bands, narrow-only edits), photos (sanitiser, storage, clean-up job), perspectives | **Implemented, compiled, tested; live MySQL 33/33** |
| Android: Coil (memory cache only), data layer, encrypted places store, composer, place picker, post view, lists, private places | **Implemented, compiled, tested; emulator (API 37) E1–E12 PASS** — two bugs found there and fixed (§5) |
| Real phone, TalkBack speech, dark mode, 320 dp / large fonts | **Not verified** (A23 sprint before any pilot, D10/D12) |

Totals: backend **635 tests, 0 failures** (2 skipped, as before; 13A: 569); Android **627, 0 failures** (13A: 592);
lint **0 errors / 91 warnings** (13A: 89; the two new ones are listed in §6). Nothing committed (git is not on PATH).

## 1. What was built

**Database (V21).** `places` (name, normalised name, category, 7-char cell + 6-char area, `created_on DATE`,
`merged_into`; **no creator, no coordinates**; unique per cell + normalised name), `posts` (type, body, tip, category,
place, precision, area cell for AREA only, city from a closed list, month (Experience only), daypart, audience, comment
policy, `created_at`, `visible_from`, `edited_at`), `post_media` (owner, post or none yet, position 0–5, random storage
key, size, SHA-256 of the stored file), `comments` (kind, one level of replies, hidden by the author, "This helped").
Every rule that does not touch a cascading foreign key is a CHECK; the others are in code (the V6/V20 rule).

**Backend (`social`).** Pure policies: `Visibility.canSeePost / canComment / visibleAudiences` (THE one rule, now for
posts, photos and perspectives too), `PostPolicy`, `TimeBands`, `PostDelay` (Q1), `PlaceNamePolicy` (refuses "my home",
"hostel room", "en veedu", door numbers…). `media/ImageSanitizer` (magic bytes; **dimensions read from the header before
decoding**, ≤ 4096 px a side; metadata never parsed; any decoder warning = refused; re-encoded as a plain RGB JPEG with no
metadata), `media/MediaStorage` (random UUID names, temp file + atomic move, never served statically). Services
`PlaceService`, `PostService`, `MediaService`, `CommentService`, and `SocialMediaCleanupJob` (Q10, the only `@Scheduled`
in `social`; deletes unattached uploads after 24 h and orphan files). Limits in memory: places 10/day, place look-ups
60/h, posts 20/day, photos 30/day, perspectives 100/day.

**Android (`feature/social`).** `ExperienceApi` / `ExperienceRepository` / `RemoteExperienceRepository`, fail-closed DTOs,
`ComposerPolicy` (mirror of `PostPolicy`), `PrivatePlacePolicy` (cell + 8 neighbours), `PhotoPreparer` (Photo Picker
URI → upright, ≤ 2048 px, new JPEG q85, no metadata), `EncryptedPlacesStore` + `PlaceMemory` (Q9: AES-GCM per save,
Keystore-wrapped key, AAD `SSPLACES1|accountId`; private places, 20 recent places, last audience; cleared at sign-out and
account switch; an unreadable file is never overwritten and makes every place count as private), ONE Coil `ImageLoader`
on the shared OkHttp client (JWT attached) with **memory cache only, disk cache off** (Q3). Screens: `ComposeFragment`,
`PlacePickerFragment`, `PostFragment`, `MyPostsFragment`, `PrivatePlacesFragment`; Connections gets "Share an
experience", "Your experiences", "Private places" (Q11); every profile gets an "Experiences" list (Q8: a deliberate
visit shows a muted person's posts). Location only on "Places near me" / "Mark where I am now": one fix, then off;
only a 6-char area (list) or 7-char cell (add a place) leaves the phone.

## 2. Deviations from the plan (recorded as decided during implementation)

| # | Plan | Built | Why |
|---|---|---|---|
| B1 | columns `precision`, `position`, `type` | `location_precision`, `sort_order`, `post_type` | `PRECISION`/`POSITION` are MySQL keywords; clearer names |
| B2 | place answer `{publicId, name, category, areaCell}`; create → 201 | adds `cell` (7 chars); create → **200** (a new or the existing place) | The phone needs the cell to keep places near private places out of posts; a place is a public point with no person attached. 200 because "create" may return an existing place |
| B3 | extend `SocialApi` / `RemoteSocialRepository` | new `ExperienceApi` / `ExperienceRepository` beside them | 13A fakes and tests stay untouched; same Retrofit instance and `ApiCallHandler` |
| B4 | hidden comment shown to its writer only | writer **and the post's author** (so the author can show it again); replies under a hidden perspective are hidden for everyone else | Without it "Show again" is impossible; replies to an invisible parent would be orphans |
| B5 | kinds | Experience: BEEN_HERE / QUESTION; Ask: ANSWER / QUESTION; REPLY only under a top-level one. The author may always add a perspective to their own shared post, also with "No one" | An asker must be able to answer questions on their Ask |
| B6 | — | ONLY_ME silently forces comment policy NOBODY (server and phone) | One less error for the person; the CHECK still enforces it |
| B7 | `PUT /api/posts/{id}` | API built and tested; **no edit screen on Android in 13B**; an edit never changes the place or photos; moving a CITY post to another city = 409 | Kept the 13B UI to the plan's screen list; a sideways move reveals a new location like a widening |
| B8 | design §4.9 "soft delete → purge job" | hard delete: rows, then photo files after commit (the clean-up job removes any file left) | Simpler and immediate; nothing to keep until 13D's moderation needs revisions (Q7) |
| B9 | month rules | Experience **requires** a month; allowed up to the month of UTC+14 h (no "future" refusal just after midnight in India), not before 1950 | Phone and server calendars differ by the time zone |
| B10 | upload errors 400 | other content types → **415** (Spring `consumes`) | Standard answer for a wrong media type |
| B11 | — | choosing a place preselects **AREA** (design §4.2 "public posts default to Area"); near a private place, CITY | Precision default is a privacy default; D14 (no preselection) concerns the audience only, which is not preselected |
| B12 | — | "Places you used" is remembered only after a post is shared | A place picked and abandoned is not "used" |
| B13 | ViewModel tests incl. photos | photo picking is not unit-tested (needs `android.net.Uri`); covered on the emulator (E4) | No Robolectric/mocking library in the project |
| B14 | permission handling | denial handled (notice + "Allow location"; "Only the city" always works); **no "Open settings" for a permanent denial** in the picker | Small; follow-up for 13F |

## 3. Tests (plan §7)

**Backend** (all new or extended, all passing): `PostPolicyTest` (every type × precision × place/city, lengths, months,
bands at their edges, delay range), `VisibilityTest` (whole matrix), `PlaceNamePolicyTest`, `ImageSanitizerTest` (JPEG
with an EXIF GPS block → no APP1/Exif; PNG → JPEG; 50 000² and 4097 px headers refused before decoding; non-images,
truncated JPEG/PNG, empty, too large), `PlaceApiIntegrationTest` (create, de-duplicate, nearby = area + 8 neighbours,
invalid cells, private names, limit, no creator column), `MediaApiIntegrationTest` (sanitised bytes stored, owner-only
before attach, visible exactly where the post is, `private, no-store` + `nosniff`, own unattached uploads only, ≤ 6 in
the chosen order, 415/400 refusals, delete removes rows and files, clean-up job: old unattached + orphan files only),
`PostApiIntegrationTest` (each precision's output, bands not timestamps, Q1 delay incl. connections, Asks and
validation, profile needed, narrow-only edits, delete, cursor lists, limit), `CommentApiIntegrationTest` (kinds,
one-level nesting, EVERYONE/CONNECTIONS/NOBODY, ONLY_ME, hide transparent to the writer, helped by the asker only,
order, bands, delete with replies), `SocialFourUserTest` (+2: posts/lists/photos/perspectives for each audience from the
four points of view; a blocked pair never sees each other's perspectives on a third person's post),
`SocialIsolationTest` (exactly one `@Scheduled`: `SocialMediaCleanupJob`), `AccountEraserCoverageTest` (3 new
references), `SocialAccountDeletionTest` (posts, photos incl. an unattached one, perspectives and **files** gone for a
deleted and an anonymised account; the friend's own perspective survives).

**Android:** `ExperiencePolicyTest` (composer rules, private places same cell / neighbour / far, band texts, photo
sizes), `EncryptedPlacesStoreTest` (round trip, ciphertext only, another account cannot read even renamed, damaged or
lost key = unreadable, clearAll, memory cap and "never overwrite unreadable"), `ExperienceDtosTest` (fail closed,
request fields per precision, no coordinate field, cells redacted in `toString`), `ExperienceViewModelsTest` (15:
no preselected audience, share + remember, private place refusal, unreadable fails closed, delay flag, picker sends only
the area / cell, at a private place no add, permission/off/rough fix, post kinds/reply/hide/helped, not-found, delete,
explicit "Show more", hidden profile = empty list, private places add/remove, rough fix refused, reset),
`SocialTextsTest` (+1: no "live / here now / check-in / who is here" wording; the delay, photo and private-place texts
say what they must; the 6 new layouts scanned for counts, popularity, presence and "safe").

## 4. MySQL and live API

- **V21 dry run (MySQL 8.0.46, scratch `dry_*` tables incl. a scratch `dry_users`, all dropped): 79/79** — 11 valid
  rows accepted (incl. a Tamil place name), 63 refused by the intended CHECK / unique / foreign key (every CHECK of every
  table, both unique rules of places and media, the position rule, FKs to unknown rows), 5 cascade checks (parent
  perspective → reply; place deleted → post's place NULL; post → photos; user → posts, photos, perspectives, their
  answer on someone else's post). Script: session scratchpad `v21_dryrun.ps1` (not in the repo).
- **Applied:** Flyway "Successfully applied 1 migration … now at version v21" when the backend started.
- **Live API (three throwaway `live13b.*` accounts): 33/33** — places (create, same name = same place, nearby, private
  name refused, no person column); a JPEG with an EXIF GPS block uploaded and **the stored file read from
  `backend/social-media-store`: no APP1 segment, no GPS text**; unattached photo 404 to others; non-image 400; exact
  post delay 135 min (in 60–180) and invisible (post and photo) to others until `visible_from` (moved in SQL), then
  visible with the place name, a band and no times; AREA shows only the 6-char area and never the cell; connections
  audience; city; only-me forces NOBODY; TAKE 400; no coordinates in any answer; profile lists per viewer; own list with
  times; widening 409, narrowing 200 + "edited"; perspectives hidden transparently; helped by the asker only; a block
  hides post, perspectives and photo; delete removes rows and the file. Clean-up in `finally`: 0 users, 0 places,
  0 files left. Script: scratchpad `live13b.ps1`.

## 5. Emulator walk-through (Medium_Phone_API_37.0, backend on MySQL v21, `adb reverse`)

| # | Check | Result |
|---|---|---|
| E1 | Connections shows "Share an experience", "Your experiences", "Private places" | PASS |
| E2 | Private places → Mark where I am now → Home (one fix) → listed with Remove; text says encrypted, never sent | PASS |
| E3 | Composer → Choose a place → Places near me **at home**: "You are near one of your private places, so a public place cannot be added here", no "Add this place" | PASS |
| E4 | Moved ~3.6 km: add "Emu13b Book Stall" (category chip), back in the composer; Photo Picker (no permission) → photo prepared, uploaded, thumbnail loaded through Coil | **PASS after a fix** (bug 1) |
| E5 | "The place" chosen → "Others will see this 1 to 3 hours after you share it…" | PASS |
| E6 | No audience preselected (button "Choose who can see this"); Everyone → its sentence and "Share with Everyone"; summary "Location shown as: Emu13b Book Stall" | PASS |
| E7 | Shared → post view: photo, "At Emu13b Book Stall", "October 2026, Morning", "Shared this week", tip box, "Who can see it: Everyone", "Others can see it from about 1:30 AM" | PASS |
| E8 | **Server copy of that photo pulled from `social-media-store`: 1600×1200, no APP1/Exif, no marker** (the original had a well-formed EXIF block with a GPS IFD and the marker); connection and stranger 404 during the delay; afterwards the stranger sees a band, no times, no audience | PASS |
| E9 | A stranger's "Been here too" appears; author: More → Hide → "Hidden by the author. Only you and the author see it."; API: writer `hidden: true`, connection sees nothing | PASS |
| E10 | Delete this post → confirmation → "Your experiences" empty; file and rows gone on the server | PASS |
| E11 | Ask, Only the city (Chennai), Connections → connection 200, stranger 404; connection answers; author "Mark as helpful" → "This helped" | PASS **after a fix** (bug 2) |
| E12 | Own profile shows "Experiences" with the Ask (type, city, band, audience) | PASS |

**Bug 1 (found on the emulator, fixed):** every picked photo showed "This photo could not be added". `PhotoPreparer`
treated the result of a bounds-only `BitmapFactory.decodeStream` (always null by design) as "unreadable". Fixed (only a
missing stream means unreadable) and the orientation read made independent (a damaged EXIF block loses only the
orientation). Not catchable by the JVM unit tests (B13). **Bug 2:** a city-only post showed "Others can see it from
about 12:19 AM": the emulator clock was a little behind the server, so `visible_from = created_at` looked like the
future. Fixed: the delay line is shown only for exact-place posts. Both re-verified on the emulator.

**Side observation, not a 13B path:** one script-held access token for a test account got a bare `401 Bearer` during
E9; a fresh sign-in worked at once. Not investigated further (auth/C1 territory, unchanged by 13B).

**State left:** all `emu13b.*` / `live13b.*` accounts, their places, posts, photos, perspectives and files deleted (dev
DB back to 170 users, 0 places/posts/photos/comments, empty `social-media-store`); test photo removed from the emulator;
app data cleared; `adb reverse` removed; backend and emulator stopped. `android/local.properties` unchanged.

## 6. Security and privacy review

- **No coordinates anywhere** in requests, answers, tables or logs (tests scan answers; cells redacted in
  `toString`). A place has no creator; `created_on` is a day and is never returned.
- **One visibility rule** for posts, lists, photos and perspectives; "not allowed" is always 404; four-user tests.
- **Time privacy:** others get THIS_WEEK / THIS_MONTH / EARLIER counted from `visible_from`; the author alone gets times.
  **Q1:** exact-place posts wait 1–3 h for every audience; area/city/none need none.
- **Photos:** stripped on the phone (re-encode) and again on the server (decode → new JPEG, metadata never parsed);
  decompression bombs refused from the header; ≤ 5 MB, ≤ 4096 px, JPEG/PNG only by magic bytes; served `private,
  no-store`, `nosniff`, never as uploaded and never as static files; unattached uploads owner-only and deleted after
  24 h; account deletion removes files **before** rows (a failure retries next run).
- **Phone:** private places, recent places and the last audience only in the encrypted per-account file, cleared at
  sign-out/account switch, never uploaded; unreadable = fail closed. Coil caches other people's photos in memory only.
- **Residual risks (documented, accepted for 13B; no pilot before 13D/13E, D12):** no text or image moderation yet
  (13D/13E) — post bodies may contain links (only place names refuse them); limits are in memory per instance and reset
  on restart; the 6-character area code is shown as text on AREA posts (it is exactly what AREA reveals); the ImageIO
  decoder is still an attack surface (mitigated by the header check, size cap and warnings-as-errors); a person who
  signs out loses their private-place list (Q9 as approved) and must mark them again; the platform `ExifInterface`
  (lint warning, the androidx one would be a new dependency) is used only to read the orientation.
- Lint: 2 new warnings — `ExifInterface` (above) and `UnusedAttribute` (`accessibilityHeading`, API 28+, ignored below).

## 7. Viva notes

- *Why a delay only for exact places?* "Posted from here" is the stalking risk; an area, a city or nothing does not
  pin a person, so delaying them would only cost usefulness.
- *Why strip photos twice?* The phone can be old, patched or replaced by a script; the server re-encodes regardless,
  and a test proves the stored file has no EXIF. The phone strip means the GPS never even leaves the phone.
- *Why read dimensions before decoding?* A 20 KB PNG can claim 50 000 × 50 000 pixels = 10 GB of memory.
- *Why do places have no creator?* Creating a place would otherwise be a record of who was where, and when.
- *Why encrypted private places on the phone?* They are the most sensitive locations a person has; the server never
  needs them, so it never gets them.
