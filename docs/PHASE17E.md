# Phase 17E — Social & Community redesign (2026-11-02 / 2026-11-03)

**Status: implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37, light + dark, 320 dp at
2.0x text) 2026-11-03; one local commit, NOT pushed.** Physical phone: install + launch smoke check only (§3.5).
TalkBack by ear: not verified. Plan: `PHASE17_PLAN.md` (17E row, D-3, D-5, D-7, §4). Written autonomously under the
user's briefs of 2026-11-02 and 2026-11-03 (full autonomy; no approval step). §1 was written before the code; §2 is
what was built; §3–§8 are the verification and review results (paused once on 2026-11-02, resumed 2026-11-03).

## 1. Plan (written before the code)

### 1.1 Decisions taken under the brief

| # | Decision | Why |
|---|---|---|
| E17-1 | **New palette: ink black + Kurinji lime (#ADFF2F).** Supersedes the colour part of U4 (red brand). Light: black is the action colour (black buttons, lime words on them), lime is a FILL (chosen, reacted, saved) with black on it, never text or an icon on a light page (1.2:1). A deep leaf green (#3A6100) carries accents that must be read on light pages. Dark: lime is the action colour with black words. | The user gave full freedom and suggested black + lime; lime is the young growth on the Kurinji hills, and with red no longer the brand, red means only emergency again (an extra cue on top of U4's shape-and-words rule) |
| E17-2 | **U4's emergency rule is unchanged**: emergency UI is the black surface with a thick red ring, the siren and the words; `colorError`, `emergency_surface` and the emergency styles keep their exact values. No Safety layout changes; Safety screens only inherit the new neutral tokens and the new action colour through the theme. | The brief: keep Safety's design and emergency signals intact |
| E17-3 | **D-7 with drawn icons, not emoji.** `KurinjiTextsTest` forbids emoji in anything people read; each daily state is a vector icon + a word | Same meaning, consistent with the design system |
| E17-4 | **Daily states describe a mood or intent, never a place or presence**: no "travelling", "at home", "free now", "online". | "Away from home" is exactly what a stalker or burglar wants to know; "free now" is presence (forbidden) |
| E17-5 | **Butterfly = a reaction on Experiences** (not on Questions, not on one's own posts); **Save = a bookmark** of Experiences and Questions (not one's own). Both idempotent: PUT twice = one row, DELETE of nothing = 204. The author's private tally (from 3, C2) now counts reactions; saves are counted for nobody. | D-3; optimistic toggles on a feed must survive double taps and lost answers |
| E17-6 | **`butterflies` becomes the reaction table** (state/been columns dropped, rows moved to the new `saved_posts`); D-3: every Cocoon item becomes a saved post | The name stays honest; the account-erasure drift guard keeps working |
| E17-7 | **Cities: one catalog file** (`cities-v1.tsv`) read by the backend and bundled in the app, seeded into the `cities` table by V28 (a test compares the three); closed CHECK lists replaced by foreign keys without referential actions. City search runs on the phone: no search words leave it. | D-5; H2 tests run without Flyway, so the list must come from code; boxes must not overlap (tested) |
| E17-8 | **Profile photo**: square, re-encoded on the phone and again on the server, new random key per upload, served only through the profile visibility rule (blocked = 404), memory cache only on the phone. Moderators see it on a tap in a person case and can remove it (`PERSON_REMOVE_PHOTO`: shown to the person, not a strike, not appealable, does not close the case). | New public imagery needs a moderation path |
| E17-9 | **"I went"** (Saved, post page): opens the composer with the place filled in when the post names an exact place the viewer can see; otherwise the composer opens empty. Nothing is stored or posted by itself. **No rating**: ratings are 17G (V30, D-8). | §4 of the plan; no scope creep into 17G |
| E17-10 | Places and lists are never ordered by numbers of posts, reactions or saves; place discovery uses recency, name or nearness | Permanent no-ranking rule |

### 1.2 Backend (V28)

- `saved_posts` (user, post, created_at; unique pair; FKs cascade); `butterflies` → reaction rows only.
- `PUT|DELETE /api/posts/{id}/reaction`, `PUT|DELETE /api/posts/{id}/save`, `GET /api/saved?cursor=`;
  `/butterfly` and `/api/cocoon` removed. Post answers: `youReacted`, `saved` (the viewer's own), `butterflies`
  (author only, reactions, from 3).
- `cities` (code PK, name, state, box) from the catalog; `places.city` / `posts.city` → FK; `OTHER` stays a post-only
  value; places outside the old 12 boxes get a city at start-up (Java, idempotent).
- `profiles.photo_key`; `PUT|DELETE /api/profiles/me/photo`, `GET /api/profiles/{id}/photo/{key}`; `photo` in every
  person summary and profile; moderator `GET …/content-cases/{id}/person-photo`, action `PERSON_REMOVE_PHOTO`.
- `daily_states` (user PK, state closed list, `set_on` DATE in IST, no time); `PUT|DELETE /api/profiles/me/daily-state`;
  in the own profile, and in another person's profile only when CONNECTED; expires on read at IST midnight, rows
  deleted by the clean-up job.

### 1.3 Android

Tokens and theme → components (feed card, avatar with photo, action bar, city sheet, daily-state sheet,
loading/empty/error) → screens: Discover, post page, Saved, composer, You (profile header, daily state, Safety grid),
person profile, profile edit (photo, city picker), place and category pages, connections.

## 2. What was built (implemented)

The pause (2026-11-02, the user's usage window) left everything in the working tree; nothing was reverted or half-written.

### 2.1 Built before the pause (2026-11-02)

**Backend**
- V28 `V28__social_redesign.sql`: `cities` (86 rows: 85 catalog cities + OTHER) with code/box CHECKs; `places.city` and
  `posts.city` closed lists replaced by FKs (no referential action) + `ck_places_city` (never OTHER) + `ck_posts_city`
  (precision rule only); `saved_posts` (Cocoon rows copied in; `butterflies` emptied, `state`/`been_at`/CHECKs/index
  dropped = reactions now); `profiles.photo_key` (UUID CHECK, unique); `daily_states` (user PK, closed list, `set_on`
  DATE); `moderation_actions` lists + `PERSON_REMOVE_PHOTO` (kind PERSON only, COALESCE pattern).
- City catalog: `resources/cities/cities-v1.tsv` (the ONE file) + `social/policy/CityCatalog` (replaces `CityAreas`
  and the `City` enum; codes are Strings), `PlaceCityBackfill` (ApplicationRunner, idempotent).
- D-3: `Butterfly` = reaction, new `SavedPost`, `PostMarkService` (replaces `ButterflyService`; idempotent; Experiences
  only for reactions; Experiences + Asks for saves; never own); routes `PUT|DELETE /api/posts/{id}/reaction`,
  `PUT|DELETE /api/posts/{id}/save`, `GET /api/saved`; `/butterfly` and `/api/cocoon` removed. `PostResponse`:
  `youReacted`, `saved`, `butterflies` (author-only reaction tally from 3).
- Profile photo: `ImageSanitizer.sanitizeSquare` (720 px), `ProfilePhotoService` (new key per upload, old file after
  commit, visibility-checked GET), `PUT|DELETE /api/profiles/me/photo`, `GET /api/profiles/{id}/photo/{key}`;
  `PersonSummary.photo`, own/person profile `photo`; the clean-up job keeps profile photo files.
- D-7: `DailyState` entity, `DailyStateService` (IST day, expiry checked on read, connections/self only),
  `PUT|DELETE /api/profiles/me/daily-state`; the clean-up job deletes earlier days (still the ONE `@Scheduled` in social).
- Moderation: `PERSON_REMOVE_PHOTO` (open PERSON case with a photo; violation reason; not a strike; not appealable;
  shown to the person; the case stays open), `PersonView.hasPhoto`, `GET …/content-cases/{id}/person-photo`.
- `AccountEraser`: `saved_posts`, `daily_states`, the profile photo file.

**Android**
- Palette "Ink & Lime" (E17-1): new `colors.xml` / `values-night/colors.xml` tokens (`k_page`, `k_surface`, `k_lime`,
  `k_action`, `k_leaf`, `k_panel`…; emergency and safety values unchanged), theme roles, attrs `colorLime`,
  `colorOnLime`, `colorAccentText`, `colorPanel*`; selectors; new styles (Button.Mark, Button.Tonal, Tag, Card.Panel,
  Card.Tile, ScreenTitle, Quote); launcher/splash colours (ink + lime; the SOS shortcut ring stays red).
- `domain/Cities.kt` (bundled `src/main/resources/cities/cities-v1.tsv`, byte-identical; search/match/cityOf on the
  phone) replacing the `City` enum, `CityMatch`, `CityAreas`; `CityPicker` sheet (Discover title, composer CITY, profile).
- Marks: DTOs/models (`youReacted`, `saved`), `DiscoverRepository` react/unreact/save/unsave/saved, optimistic toggles
  in Discover, the post page, place/category pages and profiles (`PostMarks`); the Saved tab rebuilt (`SavedFragment`,
  `SavedViewModel`, `fragment_saved`, `item_saved`; Cocoon and BeenInvitation removed; nav id `cocoonFragment` kept).
- Redesigned layouts: feed card (photo first, category tag, quote panel without a photo, marks bar), Discover head
  (the city as the title), feed header and rail, post page marks + "I went" + "Going there?", You (ink profile head,
  photo, today's state, compact Safety grid with the U4 SOS tile), person profile (ink panel, photo, state for
  connections), place/category page (ink head, "I went here"), people rows and comments with photos, profile editor
  city picker.
- Profile photo on the phone (`PhotoPreparer.prepareAvatar`, Photo Picker, no permission), daily-state sheet
  (`DailyStates`, drawn icons, no emoji), "I went" (`ComposeViewModel.prefill` + `ExperienceRepository.place`), the
  moderator person-photo view + remove action, the person's "photo removed" notice on You.


### 2.2 Added on resumption (2026-11-03), from the verification in §3

| # | Found | Fix |
|---|---|---|
| 17E-V1 | Onboarding page 2 ("A butterfly says it inspired you") showed a bookmark | `OnboardingPage.butterfly`: the page draws the reaction butterfly (`ButterflyDrawable.reacted`) |
| 17E-V2 | 320 dp at 2.0x text: the card's marks row broke "Comment" mid-word and pushed **Why? and Save off the card** (Save unreachable) | Pure `MarksFit` (text budget = width dp ÷ font scale): FULL ≥ 400, SHORT ≥ 320 (comment word dropped), ICONS below (icon-only marks, Why? = info icon); spoken descriptions unchanged. Unit test `marksBar_losesWordsBeforeButtons…` |
| 17E-V3 | The same: the "when" chip was clipped ("November 2026, M") | Context chips end in "…" with a max width; the words stay in the card's spoken description |
| 17E-V4 | Discover's fixed head (wordmark + city title) took a third of a small screen | Head in an `AppBarLayout` (scroll + enterAlways): scrolls away, back on scroll-up; page-coloured (M3's lifted colour set to the page colour; measured equal) |
| 17E-V5 | You at 2.0x: the handle broke into pieces beside the photo and the state note made a near-empty black panel two screens tall | Today's state chip above its note at every size; photo and name stack at the ICONS budget (`YouFragment.stackHeadForLargeText`) |
| 17E-V6 | Saved at 2.0x: I went / Going there? / Remove overflowed | The action row stacks at the ICONS budget |
| 17E-V7 | Full-width icon buttons (Edit profile, I went, Going there?, All safety tools) had the icon at the far edge, detached from the word | `iconGravity textStart` in `Button.Tonal` and `Button.Quiet`, and on the "All safety tools" button only (the shared `Button.Secondary` used by Safety screens is unchanged) |
| 17E-V8 | Person profile: Mute/Block right-aligned, "Report this person" left-aligned | Quiet actions aligned to the start |
| 17E-V9 | The 429 for marks said "You have changed your **Cocoon**…" (a retired word, shown to people) | "You have given or taken back many butterflies and saves today…"; stale comments (`SocialProperties`, `Visibility`) |
| 17E-V10 | Lint: 7 new warnings in 17E files | RtlSymmetry ×2, DisableBaselineAlignment ×2, Overdraw (city row ripple → foreground) fixed; NestedWeights / UseCompoundDrawables left (pre-existing pattern, harmless); `%d photos` is only used for 2+ |

## 3. Verification (2026-11-03)

### 3.1 Automated

| Check | Result |
|---|---|
| Backend `mvn test` (full, final code) | **790 run, 0 failures, 0 errors, 2 skipped** (the two need the git-ignored `restricted/` set) |
| Android `testDebugUnitTest` | **808 run, 0 failures** (807 + `MarksFit`) |
| Android `lintDebug` | **0 errors / 104 warnings** (109 before 17E-V10; 144 before the unused-resource clean-up) |
| `assembleDebug` | Built, installed on the emulator and the A23 |

### 3.2 V28 on MySQL 8.0 (2026-11-02, unchanged since)

Backup first; risky DDL dry-run 8/8 refused; applied by Flyway; constraint checks 26/26 invalid refused, 11/11 valid
accepted; `saved_posts` cascades. On 2026-11-03 Flyway validated 28 migrations, "Schema up to date".

### 3.3 Live API on MySQL (`live17e.ps1`; five throwaway accounts: author, connection, stranger, blocked, saver)

**49/49 PASS** (48 + 1 script fault re-verified: the 900×600 test photo correctly became 600×600 because the sanitiser
only scales down; a 1600×1200 photo became 720×720).
- Reactions: PUT twice = 200 and one row (the author's tally counted it once), DELETE twice = 204, own post / Ask → 400,
  unknown → 404, the old `/butterfly` route is gone; the author sees "fewer than three" with 1, **3** with three
  people, back to "fewer" after a take-back; reactors and strangers see no tally; the tally names nobody.
- Saves: PUT twice + an Ask = 200, Saved lists 2 (no duplicate) newest first, own post → 400, no save count anywhere.
- Photo: upload → key; visible to a stranger who can see the profile; `no-store` + `nosniff`; square, no EXIF; wrong
  key 404; no sign-in 401; replaced → new key, old key 404; non-image 400; the new key in the connection list.
- **Blocked user**: photo 404, profile 404, reaction and save 404, the key under another profile 404; someone who
  saved and was then blocked: Saved shows 0, unsave still 204.
- **Daily-state privacy**: set 200; "TRAVELLING" 400; own profile and a connection see it; a stranger (by id and by
  handle) gets null; never in a post answer or the connection list; hidden again as soon as the connection ends;
  cleared → null.
- New-account probation stopped the seeding script's 4th post of the day (429): the limit works as designed.

**Moderator photo path (`mod17e.ps1`; role set by SQL, reset in `finally`) 9/9:** a person report opened a PERSON
case; `PERSON_REMOVE_PHOTO` is offered (a photo exists); moderator person-photo 200, a non-moderator 403; after the
removal the photo key is NULL and the **case is still OPEN** (database); the author's standing shows the decision with
`canAppeal: false` and nothing about the reporter; 0 strikes; 1 audit row; role back to USER. (The first run's blank
fields and one 401 were script faults: guessed field names, and PowerShell's case-insensitive `$B` vs `$b`.)

### 3.4 Emulator walk-through (API 37, `Medium_Phone_API_37.0`, fresh APK, backend on MySQL)

| Area | Result |
|---|---|
| Onboarding (3 pages), sign-in | PASS (17E-V1 found and fixed) |
| Discover: city title, chips, photo-first cards with avatars, Inspired + Save toggles (lime fill; words and spoken labels change), Why? | PASS |
| City sheet: "mys" → Mysuru, Karnataka; on-phone note; Discover re-titled; falls back to "From people you know" | PASS |
| Post page: photos, chips, Tip, marks, private-marks note, I went (composer opens empty for a city-level post, E17-9), Going there? sheet | PASS |
| Person profile as a connection: photo with lime ring, "Today: Inspired", Remove connection / Mute / Block / Report | PASS (17E-V8) |
| Saved: item, I went, Going there?, Remove | PASS |
| You: photo through the system Photo Picker → uploaded and shown; today's state sheet (8 drawn icons, privacy note) → set; the connection sees it, a stranger does not (API) | PASS |
| Safety grid → SOS screen unchanged; countdown → Cancel returns to idle, **no emergency recorded** (API: 0) | PASS |
| Dark theme: splash, Discover, cards, You, Safety grid (SOS tile black + red ring + siren) | PASS |
| 320 dp at 2.0x text: Discover, cards, Saved, You, Safety grid | **FAIL → fixed** (17E-V2…V6), re-checked PASS |

### 3.5 Physical phone (Galaxy A23, R5CW42E9RQW)

Non-writing smoke check only: the APK installs (`--user 0`) and launches to the sign-in screen in dark mode (the
screenshot's brown cast is the phone's Eye Comfort Shield, `blue_light_filter=1`). Nobody signed in; nothing written.

## 4. Security and privacy review

- **Identity and ownership** only from the JWT; marks, photo and state act on the caller's own rows; another person's
  post / profile goes through the one visibility rule (invisible = 404, never 403).
- **No new public number**: saves are counted for nobody; the reaction tally is the author's own, thresholded at 3,
  never who (live-checked); reactions and saves order no list.
- **Daily state** (D-7): closed list of moods/intents, no place, no presence, no time stored or shown (DATE only), no
  history, no notification; connections only (relationship from the one rule), null for everyone else, gone at IST
  midnight on read, deleted by the clean-up.
- **Profile photo**: sanitised twice (phone, server), no metadata (live-checked), square ≤ 720 px, a new random key per
  upload, served only through `canSeeProfile` + the current key, `no-store`, memory cache only on the phone (one Coil
  loader, disk cache null), file deleted after commit, covered by `AccountEraser`, moderator removal audited.
- **City search** runs on the phone; no search words are sent.
- **Idempotent writes** follow the Phase 14 rule (no unique-key error caught inside a transaction).
- **Isolation**: social never references moderation or safety (`SocialIsolationTest`); moderation calls the photo
  service, not the reverse.
- Nothing logs a photo key, state or post id; failures log exception class names only.

## 5. Accessibility and visual review

- Every mark has a spoken description that changes with its state; icon-only marks keep them (17E-V2).
- Large text: the 2.0x pass found and fixed the breaks in §2.2. Left at 2.0x: "Heads-ups" in the narrow Nearby tile
  breaks after "Heads-u" (a Safety label, left unchanged on purpose).
- Contrast: `ContrastTest` covers the new pairs (lime is a fill with ink on it, never text on a light page) and pins the
  emergency values; dark theme checked by eye.
- Not verified: TalkBack speech by ear, switch access, a physical phone's large-text settings.

## 6. Regression review

- Safety and emergency: SOS screen, countdown and cancel unchanged on the emulator; the SOS tile keeps the U4
  treatment in light, dark and at 2.0x; Safety screens' layouts untouched (only `Button.Quiet`'s icon now sits next to
  its word wherever that style is used); every safety test in both suites passes.
- Social: Cocoon / BeenInvitation removed; the Saved tab keeps the id `cocoonFragment`; `/butterfly` and `/api/cocoon`
  are gone (an older app gets 404/405 on those two only).
- Migration safety: V28 dry-run + constraint checks on MySQL; Cocoon rows became saved posts (no reaction invented);
  `PlaceCityBackfill` is idempotent.

## 7. Not verified / known limits

- Physical phone beyond install + launch; TalkBack by ear; a profile-photo upload on a weak network.
- Ratings (D-8) are 17G; "I went" only opens the composer (with the place when the post names an exact place the
  viewer can see).
- The cities' boxes are hand-drawn rectangles (tested not to overlap); a place just outside a box has no city.
- The advisor tool was switched off by the user for this session; the reviews in §4–§6 were done directly.

## 8. State left behind

- Dev MySQL: the five `p17e-*@example.com` throwaway accounts were asked for deletion through the API (the normal
  7-day erasure, due 2026-11-09, run by `AccountDeletionJob` the next time the backend is up; it also removes their
  posts, photos, report and case rows); the moderator role was reset.
- Emulator: display size, density and text scale reset; signed in as a throwaway account until its erasure.
- One local commit; nothing pushed.

### Viva notes (short)

- *Why two marks?* A reaction tells the author "this inspired someone" (private, thresholded); a save is the reader's
  own list. One mark for both (13C) turned a private list into a signal for the author.
- *Why does the state never say "travelling" or "free now"?* Those reveal absence from home and presence, which a
  stalker wants; moods and intents do not.
- *Why a new photo key per upload?* Caches can never show an old photo, and a URL someone kept stops working when the
  photo changes or a block happens.
- *Why `MarksFit` and not a wrapping row?* A wrapping row grows the card unpredictably; dropping words first keeps every
  action reachable and the card's rhythm, and the spoken descriptions keep the meaning.
