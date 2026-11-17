# Phase N4: Moments and Today

Status: **decisions LOCKED by the owner on 2026-11-16; units B1, B2, B3 and B4 DONE (implemented, compiled, tested;
"As built" at the end); A1 PARTIAL (2026-11-17); A2, A3, C NOT started: see "As built" and the top of `HANDOFF.md`.** The plan below was presented on
2026-11-16 and approved with the owner's changes (voice deferred, new-member period 7 days, no learned model). Units B1,
B2, B3, B4, A1, A2, A3, C; one local commit each; never pushed, never deployed. Each unit starts only when the owner says
so. Android UI and UX: the owner gave full creative freedom on 2026-11-16 (simple and clean, not bland; a polished,
branded product, not a generic Material look); the design is made in A1 / A2. Direction: `IRL_DIRECTION.md` §2; decisions N-42 to N-58 in `DECISIONS.md`. N5 (responses and plans) is designed
here (§9) and needs its own go-ahead (decisions D14–D19 still open).

## 1. Product direction (locked)

IRL is **a verified, real-name community social wall designed to move people from online to real life**. The core
interaction is **"Who's in?"**. IRL is community-first, never GPS- or proximity-first.

**Never reintroduce:** exact location, distance, nearby users, online status, "last seen", viewer lists, read receipts,
ranking, engagement counts, a generic Explore, cities, canonical places, place ratings or trust, proximity feeds,
generic comments, social video.

**Boundary:** N4 builds on the N3 membership gate and does not break or redesign N2 phone sign-in, N3 verified MIET
membership, SOS, the Safety Circle, Safe Walk or any emergency feature.

## 2. Locked decisions (owner, 2026-11-16)

| # | Decision | Record |
|---|---|---|
| D1 | N4 (moments) and N5 (responses and plans) are two phases on one design | N-42 |
| D2 | Photos come from the phone's camera app through the FileProvider; NO `CAMERA` permission, no new library, no gallery | N-43 |
| D3 | **Voice is DEFERRED from N4.** No voice moments, no `RECORD_AUDIO`, no audio sanitizer in N4. **Video is not part of N4** | N-44 |
| D4 | A moment = text up to 500 characters and at most one photo; text may accompany the photo (text optional when a photo is attached); kinds `SAY` and `WHOS_IN` only | N-45 |
| D5 | Audience MIET (`COMMUNITY`) or My people (`MY_PEOPLE`); no default the first time, then the last choice remembered on that phone; both need active membership of the same community | N-46 |
| D6 | 24 h of visibility, counted from when others can first see it (a held moment's clock starts at release); after expiry it leaves Today; its data may remain 7 days and is then purged unless an open moderation case needs it; retention of moderator-removed content is set in N8 after the legal check | N-47 |
| D7 | Keep: at most 6 kept moments, chosen while live, un-keep anytime; kept moments show on the profile (never back on Today) | N-48 |
| D8 | Daily question: admin-controlled, one per India-time calendar day, pinned on Today; answers are ordinary moments marked with the question; no ranking or counts | N-49 |
| D9 | Who's in?: optional time within the next 24 h (shown exactly), optional `where` of at most 60 characters of plain text; never a place record, never coordinates, no location inference, no participant-cap architecture | N-50 |
| D10 | Profile: city, languages and interests removed; programme and graduation year from the verified membership shown to other members; photo, bio, handle kept; sharing a moment needs no profile | N-51 |
| D11 | "This is about me" is built in N4: hides the moment at once, opens a priority case, a human decides, audited | N-52 |
| D12 | Screenshots blocked on Today, moment pages and other people's profiles (N5: Replies and conversations); not on Create or your own You | N-53 |
| D13 | No editing of moments (delete and share again) | N-54 |
| M1 | **No learned AI model** in the pilot; the existing rule-based Python text check (Tamil / Tanglish / English) stays; outage behaviour below | N-55 |
| M2 | **New-member period = 7 days from `memberships.verified_at`** (§5) | N-56 |
| M3 | Held new-member photos are never released or deleted automatically, and the protection works without the Python service | N-57 |
| — | The "never reintroduce" list (§1) | N-58 |

**Still open (not locked):** M4 tiered queue priority (§7; planned for B3, confirm before B3), M5 moderator coverage
promise (N8), whether the pilot must refuse to start without the text-check service (N8 deploy configuration), N5
decisions D14–D19 (§9).

## 3. Rules (N4)

| Topic | Rule |
|---|---|
| Today | The main wall: the last 24 hours of the community, newest first, ending with an end card. Pinned daily question. MIET / My people switch. No counts, no ranking, no viewer lists, no read receipts. Others see a rough time (today / yesterday × morning / afternoon / evening / night, India time; kept moments older than a day: this week / this month / earlier); the author sees exact times |
| Moment | `SAY` or `WHOS_IN`; text ≤ 500 characters; at most one photo; no editing |
| Photo | Taken with the camera app into the app's cache (FileProvider, no `CAMERA`); input ≤ 5 MB; re-encoded on the phone (≤ 3072 px, no metadata) and again on the server (`ImageSanitizer`) |
| Who's in? | Optional time within the next 24 h; optional `where` ≤ 60 characters of plain text |
| Audience | MIET or My people (mutual connections); both people active members of the same community; blocks hide both ways; mutes hide from Today only |
| Life | 24 h from first visibility (release time for a held moment); then gone from Today; data purged 7 days after expiry unless an open case needs it |
| Keep | ≤ 6 kept, chosen while live, shown on the profile |
| Daily question | One per India-time day, written and scheduled by admins |
| Limits | New members 3 moments a day, established members 10; 20 photo uploads a day |
| Account deletion | Removes every moment, photo file and keep of the person (eraser coverage test) |

## 4. Backend plan

- Moments live in `social` (one `Visibility` rule): entities `Moment`, `MomentMedia`, `DailyQuestion`; pure
  `MomentPolicy`, `MomentTimes`; services `MomentService`, `MomentMediaService`, `DailyQuestionService`;
  `Visibility.canSeeMoment` / `showOnToday`; `SocialMediaCleanupJob` stays the ONLY scheduled job in `social` and also
  purges expired moments; the `MembershipGate` port gains the approval time and programme / year (social still never
  imports community).
- API: `POST /api/moments/media` (photo) → `{mediaId, width, height}`, `DELETE` an unattached one;
  `POST /api/moments {kind, audience, text?, mediaId?, planAt?, planWhere?, questionId?}`;
  `GET /api/moments/today?people=ALL|MINE&cursor=` → `{question, moments, nextCursor}`; `GET|DELETE /api/moments/{id}`;
  `PUT|DELETE /api/moments/{id}/kept` (409 at 6); `GET /api/moments/mine`; `GET /api/profiles/{id}/moments`;
  `GET /api/moments/{id}/media` (private, no-store); `GET|POST|DELETE /api/admin/questions`; `POST /api/reports` with
  target `MOMENT`; `POST /api/moments/{id}/about-me`. The old `/api/posts`, `/api/places`, `/api/discover` and post-media
  endpoints are removed.
- Old social code removed: posts, comments (perspectives), places, the city catalog, the city feed, delays, week/month
  bands for posts, video; tests rewritten for moments.

## 5. New-member period (M2, LOCKED)

- **Starts** at the exact moment the MIET membership is approved: `memberships.verified_at`. Not account creation,
  phone verification or sign-up.
- **Lasts 7 days. Established at exactly `verified_at + 7 days`**, computed live on every request (no stored flag, no
  job, no new column).
- **During the 7 days:** photos shared with all of MIET are held for human review (My people: not held), independent
  of the Python service; such held media is never released or deleted automatically, also when no moderator is
  available; up to 3 moments a day. Carried over from 13D unless changed later: no links in text; their reports reach
  moderators but count toward the automatic 3-report collapse only once the period ends.
- **After:** established. Photos publish normally; humans look only when needed (holds, reports, about-me, review
  requests); up to 10 moments a day. No "established" badge, no visible trust label; established is not trusted.
- **Edge cases:** re-verification starts a new 7 days; moving to a new number does not restart it; warnings and pauses do
  not extend it; moderators, verifiers and admins get no exemption.
- The person is told (no shadow rules): Create shows "As a new member, your photo goes to a moderator before MIET sees
  it, until <date, time>"; You shows the end; moderators see "new member until …".
- Tests: one microsecond before, exactly at, and just after `verified_at + 7 days`.
- Launch (N8 checklist): verify the founding cohort at least 7 days before opening.

## 6. Moderation (locked parts)

- **No learned model** (M1). The rule-based text check runs on every moment's text and `where`.
- **Outage behaviour:** established members' text publishes when the check is down and is rechecked for 24 h; new
  members' text waits at most 2 h, then publishes; new-member photos to all of MIET stay held until a human decides (M3).
  The new-member photo hold is its own rule and does not depend on the Python service.
- **Humans:** moderators are appointed by admins (role read from the database). A moderator can never decide about
  their own content or content they reported (server-side, existing `tookPart`). Actions: **Keep it** (release; a held
  moment's 24 h starts then), **Hide while reviewing**, **Collapse**, **Remove**, plus the person steps. Every action
  needs a reason and a confirmation and is written to the audit log. No shadow bans: the author always sees the state.
- **"This is about me"** (D11): hides at once, opens a priority case, a human decides, audited; the author sees "Hidden
  while a moderator looks", never who asked.

## 7. Moderation (planned, to confirm before B3)

- M4 tiered queue priority (fixes: cases the automatic check opened sort at priority 0 today): 1. about-me and held
  severe content; 2. reported content by severity × independent reporters; 3. new-member photo holds and authors'
  review requests; oldest first within a tier; over 12 h waiting moves up a tier; the queue shows waiting time.
- M5 moderator coverage promise and rota (N8); push alerts for moderators with N7.

## 8. Database: V35 (planned)

Creates `daily_questions`, `moments` (kind, audience, body, plan time and `where` only for `WHOS_IN`, question,
`visible_at`, `expires_at`, `kept_at`, moderation columns), `moment_media` (photo only in N4). Retargets
`content_reports`, `content_cases`, `content_assessments` to `moment_id` (post / comment rows deleted, target kinds
`MOMENT`, `PERSON`); `moderation_actions` keeps its history rows. Drops `post_revisions`, `comments`, `post_media`,
`posts`, `places`, `cities`, `profiles.city / languages / interests`. Safety, auth and community tables untouched.
Applied B4-style (backup, dry run of every CHECK, apply on the development MySQL, curl walk, real-MySQL test). Data
impact on development: about 12 posts with their photos, 6 places, the city catalog, 1 perspective; old post files are
removed by the cleanup job as orphans (folder copied first). The pilot stays on V30 (its wipe: N-12, own step).

## 9. N5 (designed, not approved to start)

Same, Say more, I'm in (author accepts; list visible to the author and the accepted only, N-11), private replies (text
only, default from My people), plan threads, "This happened", Replies tab; V36. Open decisions D14 (Same wording
thresholds), D15 (Say more), D16 (no explicit decline), D17 (private reply rules and lifetime), D18 (plan threads,
"made happen" visibility), D19 (Replies marker). The Safety Circle offer on plans is N6; push is N7.

## 10. Android plan

- New `feature/moments`: Today (new start screen), moment card and page, Create, capture files, the admin questions
  screen. Bottom bar Today · ✚ · You (Replies arrives in N5; no placeholder).
- Create: opens ready to type; camera button (camera app, FileProvider second cache folder; without a camera app the
  button is hidden: no gallery); audience; "Who's in?" switch with time and `where`; the on-phone text check nudge; the
  new-member line; upload as soon as a photo is attached.
- Old social screens removed (city feed, composer, post, place, private places, cities, Home city, video, experience
  cards) with a one-time deletion of the private-places file and key and the saved Home city; `feature/going` stays for
  N6.
- Guards: provider paths, permission list unchanged (no `RECORD_AUDIO`, no `CAMERA`), FLAG_SECURE per D12, a moments
  wording guard (no counts, ranking, location or safety claims; Create never touches the gallery), nothing about other
  people stored on the phone.

## 11. Units (approved order; B1 done, the rest NOT started)

| Unit | Content |
|---|---|
| B1 | **DONE 2026-11-16.** V35 + moment core + Today + lifecycle + keep + timing + daily question + limits + purge |
| B2 | Photo upload + serving + sanitization + limits + new-member protection (no voice) |
| B3 | Moderation + "This is about me" + profiles + reports + old social removal + tests |
| B4 | Development DB backup + migration dry run + V35 applied + API validation |
| A1 | Today + moment UI + You + profiles + navigation + old social clean-up + screenshot protection |
| A2 | Create flow + text + audience + Who's in? + camera + photo + moderation nudge + upload |
| A3 | Reports + "This is about me" + author notices + moderator case + admin questions |
| C | Full testing + documentation + emulator walk |

## As built

### B1 (2026-11-16): V35, moment core, Today, life, keep, daily question, limits, purge — backend only

Status: **implemented, compiled, tested** (backend **584 run, 0 failures, 0 errors, 2 skipped**: the 532 of N3 + 52 new; clean build, `mvn -o clean test`). **V35 is written and applied NOWHERE.** Not manually
verified (no server run, no emulator: there is no Android side yet). One local commit, not pushed, not deployed.
Decisions fixed while building, all inside the locked plan: N-59 to N-68 in `DECISIONS.md`.

**⚠ Do not start the backend against the development MySQL expecting moments to work before B4.**
`application.properties` holds Flyway at V34 (`spring.flyway.target=34`, N-59), because V35 drops the tables the old
social code still reads until B3. With that line the jar starts on V34 exactly as before (old social works, the moment
endpoints cannot work: their tables do not exist). B4 removes the line after the backup and the dry run.

**What exists now**

| Part | Classes |
|---|---|
| Schema | `V35__moments.sql`: creates `daily_questions`, `moments`, `moment_media`; retargets `content_reports` / `content_cases` / `content_assessments` to `moment_id` (post and perspective rows deleted; origin `ABOUT_ME` added); `moderation_actions` learns `MOMENT` and keeps its rows; drops `post_revisions`, `comments`, `post_media`, `posts`, `places`, `cities`, `profiles.city / languages / interests` |
| Entities | `social/entity/Moment`, `MomentMedia` (row only; upload and serving are B2), `DailyQuestion`, `MomentEnums` (`Kind` SAY / WHOS_IN, `Audience` COMMUNITY / MY_PEOPLE) |
| Pure rules | `social/policy/MomentPolicy` (the locked numbers as constants, the draft check, `isNewMember` / `establishedAt`, cleaning, the link rule, `purgeDue`), `MomentTimes` (rough times in India time, `indiaDay`), `Visibility.canSeeMoment` / `showOnToday` / `todayStates` + `MomentFacts` |
| Services | `MomentService` (create, today, get, mine, delete, keep, unkeep, `respond`, `requireVisible`), `DailyQuestionService`, `MomentLimits`; `Relationships.momentFacts` / `canSeeMoment` / `forToday`; `SocialAccess.requireMembership` / `communityOf`; port `MembershipGate.activeMember` → `Member(communityId, verifiedAt)` (implemented by `MembershipLookup`; social still never imports community) |
| Job | `SocialMediaCleanupJob` (still the ONLY `@Scheduled` in social) also purges moments, deletes moment photos never attached after 24 h, and knows moment photo files (so they are never swept as orphans); `Outcome` gained `moments` |
| Account deletion | `AccountEraser`: `moments.author_id`, `moment_media.owner_id` (files first), `daily_questions.created_by` (cleared); `posts` / `post_media` / `comments` left the reference list (V35 drops them) but their DELETE statements stay until B3 |
| Config | `config/MomentProperties` (`app.moments.per-day` 10, `new-member-per-day` 3, `today-reads-per-hour` 600, `questions-ahead` 60) |
| API | `MomentController`, `DailyQuestionAdminController` (`API.md`, "Phase N4") |

**Rules as built** (each has a test)

- **Sharing:** needs an active membership and no posting pause; no profile needed (the author is the account's real
  name; profile id, handle and photo are null without a profile). Kind and audience are required (no default audience).
  Text is cleaned (NFC, invisible characters out, at most one empty line) and counted in code points (Tamil and emoji
  count as people count them; V35's `CHAR_LENGTH` agrees). In B1 a text is always required (photos arrive in B2; the
  rule "text optional next to a photo" is already in `MomentPolicy` and tested).
- **Who's in?:** `planAt` is an ISO instant, cut to the minute, after now and at most 24 h ahead; `planWhere` is one
  line of plain text of at most 60 characters. A Say with either is refused. The plan time is returned exactly to
  everyone who may see the moment (D9).
- **Life (N-60):** `visible_at` / `expires_at` are NULL while a moment has never been visible; `Moment.publish` sets
  them once (`expires_at = visible_at + 24 h`); a later hold does not move them. In B1 every moment is published the
  instant it is shared (nothing holds one yet: B2 photo hold, B3 text check).
- **Visibility (one rule):** the author always; anyone else needs the author's profile to be visible (active account,
  active member of the same community, no block either way), the moment started, a state others may see (not HELD /
  REMOVED), live OR kept, and the audience (`MY_PEOPLE` = an accepted connection). Not visible = 404.
- **Today:** live moments of the viewer's community, ordered by `visible_at` then id, newest first, pages of 20, cursor =
  the id of the last moment shown (N-61); `people=ALL` (default) includes the viewer's own live moments exactly as
  others get them; `people=MINE` = moments by the viewer's connections only (N-62). A mute hides from Today only. No
  count, no ranking, no viewer record. Per-person read limit.
- **Times for others (N-63):** `when {day, part}`; the exact `createdAt` / `visibleAt` / `expiresAt` only for the author.
- **Keep:** the author only, only while live and visible to others, at most 6 (409 otherwise; the account row is locked
  so two requests cannot both take the sixth place), un-keep anytime. A kept moment stays readable by id after its 24 h
  (the profile list is B3) and never returns to Today.
- **Delete:** the author, always (also under review); no edit endpoint exists (405).
- **Daily question (N-66):** ADMIN only (path rule + service check) and an active member; one per community per India
  day; today to 60 days ahead; 3 to 140 characters, one line; no editing; deleting keeps the answers as plain moments.
  Today pins the question of the India day; a moment may carry `questionId` only for the question pinned right now.
- **New-member period:** `MomentPolicy.isNewMember(verifiedAt, now)`, computed on every request from the membership
  (tests: one microsecond before, exactly at, just after `verified_at + 7 days`; an old account with a fresh approval; a
  new verification restarts it). In B1 it drives the daily limit (3, then 10) and "no links".
- **Limits (N-65):** in memory; a refused request does not count; a deleted moment still counts; a new member's moment
  counts against both limits.
- **Purge (N-64):** hourly, `expires_at` more than 7 days ago AND not kept AND `in_review = false` AND not REMOVED;
  never a moment that was never visible. Rows first, then the photo file.
- **Guard:** `MomentBoundariesTest` scans the moment code, the three V35 tables and the answer fields for the "never
  reintroduce" list (location, presence, viewer / read tracking, counts, ranking, voice, video).

**Tests added (52):** `MomentPolicyTest` 12, `MomentTimesTest` 5, `MomentVisibilityTest` 7, `MomentBoundariesTest` 3,
`MomentApiIntegrationTest` 17, `DailyQuestionApiTest` 3, `MomentLimitsApiTest` 3 (its own H2 database, the real 3 / 10),
`MomentPurgeAndDeletionTest` 2. Helper `support/MomentClient`. Test properties lift the moment limits (every test
account is a new member). `AccountEraserCoverageTest`, `SocialIsolationTest`, `CommunityIsolationTest`,
`TimeConventionsTest` pass unchanged.

**Not in B1 (by the plan):** photo upload, serving and the new-member photo hold (B2); the text check on moments,
reports, "This is about me", author notices, `GET /api/profiles/{id}/moments`, programme and year on profiles, removal
of the old social code (B3); V35 on MySQL (B4); everything on the phone (A1 to A3).

**Open points handed to B2 / B3 (V35 may still change until B4 applies it)**

1. B2: the photo size CHECK in `moment_media` is V21's (≤ 4096 px, ≤ 5 MiB); tighten it if the server limit becomes
   3072 px. `MomentService.create` passes `photo = false`; B2 adds `mediaId`.
2. B3: the moderation lists in V35 are V24's plus origin `ABOUT_ME` and target kind `MOMENT`. If "This is about me" or
   the new-member photo hold need their own reason / rule / action codes, add them to V35 before B4.
3. B3: `Moment` uses the moderation enums of `PostEnums`; when the old social code goes they move with it.
4. B3: the test database has no foreign keys: deleting or purging a moment must also remove its reports, cases and
   assessments in code (MySQL cascades them), and the purge must never take a moment with an open case (`in_review` is
   the flag the purge reads; the moderation code sets and clears it).
5. B3: `SocialAccess.POSTING_PAUSED` still says "Connections shows until when" (old wording).
6. B4: remove `spring.flyway.target=34`; dry-run every new CHECK; confirm the index and foreign key names V35 drops
   (`uk_content_reports_post`, `fk_content_cases_post`, …) against `SHOW CREATE TABLE` on the development database.

**Honest limits:** the daily limits and the Today read limit are in memory (per instance, reset by a restart). Today's
candidate reading is bounded (5 batches of 40 per page); a viewer who can see fewer than 21 of 200 consecutive
candidates gets a shorter page and a cursor to carry on. A cursor names a moment the viewer was shown; if it is deleted
before the next page is asked for, the app gets 400 "Invalid cursor" and reloads.

### B2 (2026-11-16): the photo of a moment, the new-member photo hold — backend only

Status: **implemented, compiled, tested** (backend **593 run, 0 failures, 0 errors, 2 skipped**: 584 + 9 new,
`MomentPhotoApiTest`). V35 still applied nowhere (one list in it grew: `NEW_MEMBER_PHOTO`). Not manually verified.

- **Upload:** `POST /api/moments/media` (body = the image, `image/jpeg` or `image/png`, at most 5 MB; anything else 415)
  → `{mediaId, width, height}`. `MomentMediaService` decodes and re-encodes with `ImageSanitizer` before anything is
  stored (no EXIF, no position); the uploaded bytes never reach the disk. 20 uploads a day (`app.moments.photos-per-day`,
  in memory; a refused upload does not count). A posting pause refuses it (403). `DELETE /api/moments/media/{mediaId}`
  removes the caller's own upload that no moment uses (204; anything else 404).
- **Attach:** `POST /api/moments` takes `mediaId`: the caller's own, unattached upload (locked until the commit, so two
  moments never share one); anything else is 400 "That photo is no longer available. Please take it again." The text is
  optional next to a photo (N-45). The answer carries `photo {width, height}` and nothing else about it.
- **Serving:** `GET /api/moments/{id}/media` by the MOMENT's id, through the one visibility rule (`requireVisible`):
  exactly who may see the moment gets its photo, `image/jpeg`, `private, no-store`, `nosniff`. A photo has no address of
  its own; an unattached upload cannot be fetched by anyone (the phone shows its own copy).
- **New-member photo hold (N-56, N-57):** pure `MomentPolicy.holdsPhoto(newMember, audience, photo)` = new member AND
  photo AND audience `COMMUNITY`. Such a moment is saved WITHOUT `publish` (no `visible_at`, status `WAITING`) in state
  `HELD` / `SYSTEM` / reason `NEW_MEMBER_PHOTO` (new code in `ModerationReason` and in V35's `ck_moments_moderation_reason`;
  never a report reason). The rule is social's own: it does not call the Python service or any listener. Nothing releases
  it or deletes it automatically (test: 30 days later, after the clean-up job, it still waits and its file exists); the
  author can always delete it. My people photos, words alone and established members are not held; the boundary is
  exactly `verified_at + 7 days` (test: one minute before and at the instant). The moderator's case for it is opened in B3.
- **Not tightened:** the server accepts up to 4096 px a side (the phone sends at most 3072 px), so V35's
  `ck_moment_media_size` stays as written.

### B3 (2026-11-16 and 2026-11-17, DONE; the first session stopped at the usage limit, see "B3 close"): moderation of moments, "This is about me", profiles, old social removal — backend only

Status at the interruption (superseded by "B3 close" below: B3 is implemented, compiled, tested, 467): partly implemented (backend **456 run, 0 failures, 0 errors, 2 skipped**,
`mvn -o clean test`). **Not finished:** the tests of the automatic text check on moments, V35 in step with the code,
`API.md` / `DECISIONS.md` / rules files, the security review (list: top of `HANDOFF.md`). Not manually verified. One
local checkpoint commit, not pushed.

**What is in**

| Part | Classes |
|---|---|
| Removed | Controllers `Post`, `Comment`, `Place`, `Discover`, `Media`; services `PostService`, `CommentService`, `PlaceService`, `DiscoverService`, `MediaService`, `PlaceCityBackfill`; entities `Post`, `PostMedia`, `PostRevision`, `Comment`, `Place`, `MediaKind`; policies `PostPolicy`, `PostDelay`, `EditionPolicy`, `PlaceNamePolicy`, `CityCatalog`, `ProfileLists`, `TimeBands`; `Mp4Sanitizer`, `Mp4FastStart`; `event/ContentWritten`; `cities-v1.tsv`; their repositories, DTOs and tests. `Visibility` and `Relationships` keep profiles and moments only |
| Renamed | `social/entity/PostEnums` → `ContentModeration` (`ModerationState`, `ModerationReason` + `NEW_MEMBER_PHOTO`, `ABOUT_ME`, `reportable()`, `ModerationSource`) |
| Event | `social/event/MomentShared` (moment id, author, `newMember`, `photoHeld`, the words = body + "where"): published by `MomentService.create` inside the transaction BEFORE the moment is published; a moment a listener holds is saved unpublished (its 24 hours start at release) |
| Moments | `MomentResponse` + `collapsed` (others), `notice {state, reason, by, reviewable, decisionId}` (the author only), `supportSuggested` (the author's own create answer only); `MomentService.ofProfile` → `GET /api/profiles/{id}/moments` `{live, kept}`; `MomentRepository.lockById` |
| Profiles (N-51) | `Profile` without city / languages / interests; `ProfileRequest {handle, bio}`; `PersonProfileResponse` + `programme`, `graduationYear`; `OwnProfileResponse` + those and `newMemberUntil`; the port `MembershipGate.Member` carries programme and year; `SocialAccess.memberOf`, `isNewMember`, `countsFrom` (replaces the 72-hour account probation) |
| Moderation | `ContentCase` / `ContentReport` / `ContentAssessment` use `moment_id`; `TargetKind` + `MOMENT` (POST, COMMENT, PLACE stay for old audit rows); `CaseOpenedBy` + `ABOUT_ME`; `ReportService` (targets MOMENT and PERSON, `aboutMe`), `ContentModerationService` (moment cases, `media(caseId)`, Keep / an overturned appeal publish a never-visible moment, `in_review` set and cleared), `AssessmentService` (listens to `MomentShared`; opens the case for a held photo even with the check off; never changes a `NEW_MEMBER_PHOTO` or `ABOUT_ME` hold), new pure `QueuePolicy`, `Periods` |
| API | `POST /api/moments/{id}/about-me` (202); `GET /api/moderation/content-cases/{id}/media`; gone: `/api/moderation/places`, the case video route |
| Other | `AccountEraser` without post statements (cases and checks of the person's moments added); `SocialMediaCleanupJob` without post media (old post files are swept as files without a row); `SocialAccess` pause wording now says "sharing" and "You" |

**Rules as built (each has a test unless marked)**

- **Reports:** only a visible moment or profile; never one's own; the same 202 twice, also after one's own request hid
  the moment; `IMPERSONATION` only on a person; the codes `NEEDS_REVIEW`, `AWAITING_CHECK`, `NEW_MEMBER_PHOTO`,
  `ABOUT_ME` cannot be given as a report reason; kinds POST / COMMENT / PLACE are refused. One report changes nothing
  the author can see. Three independent counting reports collapse (author: `notice {state COLLAPSED}` with no reason and
  nobody named; others: `collapsed: true`, still on Today).
- **New member's report:** stored and shown to moderators at once; counts toward the collapse from
  `verified_at + 7 days` (test: 5 minutes before and after, through `ContentModerationService.maintain`).
- **"This is about me" (N-52):** `ReportService.aboutMe`: a report row with reason `ABOUT_ME` (so the person who asked
  is a party and can never decide), the daily report limit applies; the moment becomes `HELD` / `SYSTEM` / `ABOUT_ME` at
  once unless a moderator already decided or it is already held; the case is opened with origin `ABOUT_ME` (or joined).
  The author sees `notice {HELD, NEEDS_REVIEW, SYSTEM}`: never the code `ABOUT_ME`, never who. A hold does not move
  `expires_at` (N-60). Keep brings it back; asking again by the same person changes nothing.
- **Held photo (N-57):** `AssessmentService.onMomentShared` opens a `SYSTEM` case whether or not the text check is on;
  Keep publishes it (24 hours from the release); Remove is appealable; an overturned appeal publishes it and deletes
  the strike. The purge skips a moment while `in_review`.
- **Queue (M4, built as planned):** `QueuePolicy`: tier 1 = about-me and content a rule HELD for a severe finding;
  tier 2 = reported content (inside it `ReportPolicy.priority`); tier 3 = new-member photo holds and authors' review
  requests; more than 12 hours waiting moves a case up one tier, once; `CaseSummary.waitingHours`.
- **Who may not decide:** the author, a reporter, the person who said "about me" (403 on the case, its actions and its
  photo); the decider never decides the appeal.
- **Automatic text check on moments** (tested in the B3 close, `MomentAssessmentIntegrationTest`). Behaviour (PHASE_N4 §6): established
  members publish when the service is down and are rechecked for 24 h; a new member's words wait (`AWAITING_CHECK`) at
  most 2 hours, then publish with the 24 hours starting then; a `PUBLISH` on a fresh write leaves publishing to
  `MomentService`; a retry publishes itself.

**Decisions to record in `DECISIONS.md` (not written yet):** N-69 the three-tier queue (M4) built as planned; N-70
"This is about me" as a report row with its own reason, hides at once, shown to the author as an ordinary review; N-71
a new member's report counts from the end of their 7 days; N-72 the author sees a report collapse as "under review"
without a reason; N-73 a moment is published AFTER the listeners ran, so a held one never had a visible time; N-74 the
old 72-hour account probation is replaced everywhere by the 7-day new-member period.

**Open points for the end of B3 and for B4**

1. V35: `ABOUT_ME` in `ck_moments_moderation_reason`; `ck_content_reports_reason` replaced to accept `ABOUT_ME`;
   then the B1 list (dropped index / key names, every CHECK dry-run).
2. `MomentAssessmentIntegrationTest` (own H2 URL, `StubModerationService`).
3. `API.md`, `DECISIONS.md`, `.claude/rules/backend.md`, `database.md`, `CLAUDE.md`.
4. Trim `SocialProperties` / `application.properties` (dead post, place, comment, video, probation settings).
5. The author's notice carries no flagged phrases yet (posts had `flagged` spans); decide in A3 whether the app needs them.
6. `KIND` sent to the text service is `"POST"` (the service knows POST and COMMENT only).

**B3 close (2026-11-17, the session after the usage limit).** The interrupted session had left the B3 work
uncommitted (110 files, nothing staged consistently); it was inspected, rebuilt from clean and found green (456), then
committed as a recovery checkpoint before anything else was changed. Finished afterwards:

- **Tests of the automatic text check on moments:** `MomentAssessmentIntegrationTest` (10, stub service, its own H2
  database): clean words publish and the assessment keeps no text (the service is sent `kind` and `text` only); a
  targeted insult collapses (author: `notice {COLLAPSED, HARASSMENT, SYSTEM, reviewable}`, others: `collapsed`, no case
  until the author asks; the review request is the author's only, the same 202 twice, one case with origin
  `AUTHOR_REQUEST`); a severe threat is held (`WAITING`, never visible, case `SYSTEM`, the moderator sees the evidence,
  Keep starts its 24 hours, no strike); the `where` of a plan is checked with the text; during an outage an established
  member is published and rechecked later (a later hold does not move `expires_at`, N-60); a new member's words wait
  (`AWAITING_CHECK`, no case), are still waiting at 119 minutes, are published after 2 hours with the service still
  down, and their 24 hours start at that release; a new member's held photo is never released by a clean answer, by an
  outage, by the 2-hour rule, by the check giving up after a day or by the clean-up job (N-57); "This is about me" is
  not released by a later clean check (N-75); self-harm sets `supportSuggested` in the author's own create answer only
  and nothing about it is stored; quoted words and strong language publish. **The path is now implemented, compiled,
  tested.**
- **V35 in step with the code:** `ABOUT_ME` added to `ck_moments_moderation_reason`; `ck_content_reports_reason`
  dropped and re-created with `ABOUT_ME` (`ck_content_reports_note` unchanged: a note only and always for `OTHER`).
  `MOMENT` fits `target_kind VARCHAR(7)`, `ABOUT_ME` fits `origin VARCHAR(14)`, `NEW_MEMBER_PHOTO` fits
  `moderation_reason VARCHAR(20)`. Moderators' decisions use `ActionReason` (unchanged), so the audit and strike lists
  need no new code.
- **Dead settings removed (N-74):** `SocialProperties` keeps profiles, connections and the photo folder; the post,
  place, perspective, Discover, video and `probation` settings left `application.properties` and the test properties.
- **Guard:** `MomentBoundariesTest.theOldSocialSurface_isGone` (no `Post*` / `Comment*` / `Place*` / `Discover*` /
  `City*` / `Mp4*` source file, no mapping of `/api/posts|places|discover|media|comments|explore`). `MomentShared` is
  inside the scans of `SocialIsolationTest` and `MomentBoundariesTest` by its package and name.
- **Documents:** `API.md` ("Phase N4" rewritten, index line), `DECISIONS.md` N-69 to N-75, `.claude/rules/backend.md`.
- **Result:** backend **467 run, 0 failures, 0 errors, 2 skipped** (`mvn -o clean test`; 456 + 10 + 1).

**Security and privacy review of B2 + B3 (2026-11-17)**

| Question | Finding |
|---|---|
| Can a photo be read by someone who may not see the moment? | No: a photo has no address of its own; `GET /api/moments/{id}/media` goes through `requireVisible` (the one rule), 404 otherwise; `private, no-store`, `nosniff`; an unattached upload is served to nobody. Moderators read a case photo only through the case, and not when they took part |
| Does an upload keep metadata or reach the disk as sent? | No: decoded and re-encoded by `ImageSanitizer` first; only the re-encoded JPEG is stored under a random name |
| Can the reported person learn of a report or of who said "about me"? | No: one or two reports change nothing they can observe; a collapse by reports has no reason and no source; "about me" reads as `NEEDS_REVIEW`; delete is never refused; `GET /api/social-standing` lists only decisions that found a violation |
| Can a moderator decide their own matter? | No: the author, every reporter and the person who said "about me" are refused on the case, its actions and its photo (403); the decider never decides the appeal |
| Can an automatic rule silence or punish a person? | No: rules only collapse or hold content (reversible), never remove, never strike or pause; an outage publishes (new members wait at most 2 hours); only humans restrict |
| Can "This is about me" be used to hide other people's moments at will? | **Residual risk, accepted with the locked decision D11:** any member who can see a moment can hide it until a moderator looks. Bounds: the daily report limit per person, one request per person per moment, a tier-1 case (looked at first), Keep restores it, the request is a report row so the audit shows who asked (to moderators as a pseudonym). Moderator coverage (M5, N8) decides how long such a hide lasts in practice: to watch in the pilot |
| What does the text service receive? | `{kind, text}` only: no person, no moment id (tested). Nothing about self-harm is stored |
| Does anything new touch safety? | No: `SocialIsolationTest`, `CommunityIsolationTest` unchanged and green; a posting pause refuses sharing and uploads only |
| Does anything reintroduce a retired concept? | No: `MomentBoundariesTest` (source, V35 tables, answer fields, old routes) |
| Limits | In memory (reset by a restart): documented, unchanged |

### B4 (2026-11-17): V35 on the DEVELOPMENT MySQL 8.0.46 only (the pilot is NOT migrated: old jar, V30)

Status: **applied and checked on the development database; implemented, tested (curl walk on real MySQL; not an
emulator walk: there is no Android side yet).**

- **Backup first:** `C:\Users\ABDUL RAFIQ\Desktop\SafeSphere-Phase3\db-backups\safesphere-dev-before-v35-20261117.sql`
  (`mysqldump --single-transaction`, 35 tables, 232 332 bytes; outside the repository; plain, same disk) and the media
  folder copied to `db-backups\social-media-store-before-v35-20261117` (5 files).
- **Pre-check (read-only):** Flyway at V34, 35 tables. Every name V35 drops exists exactly once (6 foreign keys, 6
  unique indexes, 9 CHECKs, 9 columns); no table outside the dropped set has a foreign key into `posts`, `comments`,
  `post_media`, `post_revisions`, `places` or `cities` except the three moderation tables whose keys V35 drops first.
  Data: 209 accounts, 25 profiles (23 with a city, languages or interests), 12 posts, 4 post media, 1 perspective, 8
  places, 86 cities, 0 reports, 0 cases, 2 assessments, 6 audit rows (all role changes), 4 memberships, 89 sessions, 73
  SOS records, 5 Safety Circle contacts.
- **Rehearsal on scratch copies** (`zz_v35_*`: the five existing tables V35 alters, created from `SHOW CREATE TABLE`
  with renamed tables and constraint names and filled with their real rows plus old-style rows (a case and a report
  about a post and about a perspective, an open and a closed case about a person with two reports, audit rows with kinds
  POST and PERSON); then V35 itself, sections 1, 2, 3 and 5, rewritten to the scratch names; section 4, the `DROP
  TABLE`s, cannot be rehearsed and was covered by the pre-check). It ran without an error. Afterwards: the post and
  perspective cases and reports were gone, both person cases and their two reports stayed, the assessments were empty,
  all 8 audit rows stayed, 25 profiles without `city` / `languages` / `interests`, the unique and foreign-key indexes
  on `moment_id` present and none left on `post_id` / `comment_id`.
- **Dry run of the CHECKs, keys and cascades: 104 cases, 35 accepted and 69 refused, each by the intended constraint,
  0 findings.** (Two cases of the first run were wrong in the test data, not in the schema: the "upper-case id" had no
  letter in it. Repeated with real upper-case letters: refused by `ck_daily_questions_public_id` and
  `ck_moments_public_id`.) Accepted: a question with and without an author, 140 Tamil characters; a Say, My people, a
  photo-only moment, 500 Tamil characters, a Who's in? with and without time and where and exactly 24 h ahead, a moment
  never visible, kept, collapsed by reports, held `ABOUT_ME` / `AWAITING_CHECK` / `NEW_MEMBER_PHOTO`, collapsed by the
  check with spans, removed by a moderator; an attached photo and unattached uploads; a case with origin `ABOUT_ME`, a
  person case; reports `ABOUT_ME` (no note), `OTHER` with a note, `SPAM`; an assessment of a moment; audit rows on a
  MOMENT and a PERSON. Refused: a second question on a day, 2 / 141 characters, padding, an unknown community; 501
  characters, empty or padded text, unknown or lower-case kind and audience, an unknown author, a duplicate id; a plan
  24 h + 1 s ahead, at or before the creation time, a time or a where on a Say, a where of 61 or 0 characters; visible
  without an end and the reverse, visible before created, end = visible, kept before or without visibility; every
  inconsistent moderation state (reports with a reason or HELD, system without a reason / REMOVED / with a decision,
  moderator without a decision or with spans, a state without a source, NONE with a reason or a source, unknown state,
  reason, source, malformed spans or decision); a second photo on a moment, 4097 px, 0 px, 5 MiB + 1, a bad hash, a path
  as the file name; a second open case per moment, kinds POST / COMMENT, unknown origins; the same reporter twice,
  `ABOUT_ME` with a note, `OTHER` without, reasons `NEEDS_REVIEW` / `NEW_MEMBER_PHOTO` / lower case; an assessment of
  kind POST, without a moment, a second per moment; `CONTENT_REMOVE` on a PERSON or without a kind,
  `PERSON_REMOVE_PHOTO` on a MOMENT, kind STORY, a role change with a kind. Cascades: deleting a moment removed its
  photo row, case, reports and assessment; deleting a question kept its answers. Scratch tables dropped: 35 tables, no
  `zz` name left.
- **Applied** by removing `spring.flyway.target=34` and starting the backend once (`mvn -o spring-boot:run`, port 8099):
  "Successfully validated 35 migrations", "Migrating schema `safesphere` to version "35 - moments"", "now at version
  v35", "Started". Afterwards **32 tables** (35 + 3 − 6), the 19 CHECKs of the three new tables present, data counts
  unchanged (209 accounts, 25 profiles, 6 audit rows, 4 memberships, 89 sessions, 73 SOS records, 5 contacts), 0
  assessments. Every column the changed entities map (`Moment`, `MomentMedia`, `DailyQuestion`, `Profile`,
  `ContentCase`, `ContentReport`, `ContentAssessment`) exists in MySQL. (Hibernate's own `validate` mode is not usable
  here: it stops at an old CHAR / VARCHAR difference in `bans.phone_key`, V34, which is harmless.)
- **API on real MySQL (curl, two existing members, both new members, not connected):** a Say for MIET → 201 LIVE; the
  other member finds it on Today with `when` and `createdAt: null`; a photo upload → 201 (1153 × 2048), a text file as
  JPEG → 400; the new member's photo for MIET → `WAITING`, `notice {HELD, NEW_MEMBER_PHOTO, SYSTEM}`, the other member
  gets 404 for the moment and for its photo, the author gets the photo (`image/jpeg`, `no-store, private`, `nosniff`); a
  Who's in? for My people with a time and a where → 201, 404 for a member who is not connected; a Say with a time →
  400; a link as a new member → 400; the 4th moment of the day → 429; keep → 200; no edit route → 405; "This is about
  me" → 202 twice, the author sees `notice {HELD, NEEDS_REVIEW, SYSTEM}`, the rows are report `ABOUT_ME`, case origin
  `ABOUT_ME`, moment `HELD / SYSTEM / ABOUT_ME`, `in_review = 1` (the real CHECKs accept what the code writes); a report
  of one's own moment, reason `ABOUT_ME` through `/api/reports` and kind `POST` → 400; `/api/posts`, `/api/discover`,
  `/api/places`, `/api/media` → 404; the moderators' queue and the admin questions as a plain member → 403; the Safety
  Circle list → 200. The three moments were deleted at the end: no moment, photo row, case or report left (the real
  cascades). `MySqlTimestampStorageTest` 2 / 2 (`SAFESPHERE_MYSQL_TESTS=true`).
- **Not exercised on real MySQL:** the daily question (no admin account is a member on the development database: root
  admins verify each other first, N-36), a moderator's decision and an appeal (no moderator is a member there), the
  automatic text check (the Python service was not started). All three are covered by the H2 tests; the emulator walk in
  unit C repeats them on MySQL.
- **Left as it is:** the 5 files of the old posts are still in `backend/social-media-store`; the hourly clean-up job
  removes them as files without a row the next time the backend runs for an hour (they are in the backup).

### A1 (2026-11-17, PARTIAL: stopped on purpose before the usage limit) — Android

Status: **partly implemented; what is in is compiled and tested at the data layer only** (Android **805 run, 0
failures, 0 errors**: 795 + `MomentDtosTest` 10). **Not reachable in the app, not manually verified.** Two local
commits (`e9d1b84` data layer; then the checkpoint commit with the screens).

**What is in**

| Part | Classes / files |
|---|---|
| Domain | `feature/moments/domain/MomentModels.kt` (`Moment`, `MomentAuthor`, `MomentPhoto` with a clamped ratio, `MomentPlan`, `DailyQuestion`, `MomentNotice`, `RoughTime`, `TodayPage`, `MyMoments`, `ProfileMoments`, `People`), `MomentRepository` |
| Data | `data/remote/MomentApi`, `MomentDtos` (a moment with an unknown id / kind / audience / status, no author name or neither words nor photo is DROPPED; an unknown notice state reads as HELD; ids and cursors are lower-case UUIDs or nothing, so nothing else can reach a URL), `data/RemoteMomentRepository` (`photoUrl`, `personPhotoUrl`); `AppContainer.momentRepository` |
| Today | `ui/TodayViewModel` + `TodayUiState`, `TodayFragment` (FLAG_SECURE while shown, D12; tab re-tap = top + read again; no pull-to-refresh library was added), `MomentCardAdapter` (`TodayRow`, pure `TodayWall.rows`: question, cards, then exactly one of loading / empty / end), `MomentCardBinder` (covered moments behind a tap; the author's notice in amber; one TalkBack sentence), pure `MomentLabels` |
| A moment's page | `ui/MomentViewModel` (404 = "This moment isn't here" for gone, ended and not-for-you alike; keep / un-keep / delete; a delete of an already deleted moment counts as done), `MomentFragment` (the author's exact end time, keep hint "up to 6", delete with a confirmation; others get a button to the author's profile) |
| Resources | `fragment_today`, `fragment_moment`, `item_moment_card`, `item_today_question`, `item_today_message`; strings `nav_today` … `moment_open_author`; `nav_graph.xml` destinations `todayFragment`, `momentFragment` (nothing navigates to them yet) |

**Design choices made (UI freedom, inside the direction):** Today's head is the mark and the word "Today" in the serif
wordmark style with one two-part switch "MIET | My people"; a card is the person first (photo or initial, real name,
rough time, "My people" when it is the smaller audience), a "Who's in?" tag, the words in the large body style, the
photo rounded and laid out from its size before it loads, the plan on a quiet panel with the accent rule (time exactly
as written, in the reader's zone and 12 / 24-hour style; "where" in the author's words); the wall ends with "That's
today." and the line "The rest is out there." No count, no reaction row, no viewer line anywhere.

**Not done in A1** (order and details: top of `HANDOFF.md`): the navigation switch to Today; tests of the new view
models, `TodayWall` and `MomentLabels`; You and people's profiles on the new API (programme, year, own and kept
moments; city / languages / interests out); removal of the old social screens with their layouts, strings, tests and
fakes (`DiscoverFragment`, `ComposeFragment`, `PostFragment`, `PlacePickerFragment`, `PrivatePlacesFragment`,
`PostCollectionFragment`, `MyPostsFragment`, `VideoPlayerFragment`, `JournalViewModel`'s post parts, `Experience*`,
`Discover*`, `Cities`, `HomeCityChoice`, `EncryptedPlacesStore`, `PlaceMemory`, `VideoPreparer`, `Mp4Scrubber`,
`VideoRules`, `FeedFreshness`, `cities-v1.tsv`); the one-time deletion of the private-places file and key and the
saved Home city; the guards.

**The navigation switch, exactly** (prepared, not applied): `nav_graph.xml`: the three
`app:destination="@id/discoverFragment"` → `@id/todayFragment`. `menu/bottom_navigation.xml`: two items,
`@+id/todayFragment` (`ic_nav_home`, `nav_today`) and `@+id/youFragment`. `navigation/NavigationRules.kt`:
`tabDestinations = setOf(R.id.todayFragment, R.id.youFragment)`, remove `SHARE_ITEM`, `START = R.id.todayFragment`,
add `R.id.momentFragment` to `readingDestinations`, and in `setUpWithTabs` remove the `Untinted` line for the Share
item and the `if (item.itemId == NavigationRules.SHARE_ITEM)` branch (A2 brings the ✚ back as Create). Then fix
`NavigationRulesTest` and whatever else names `discoverFragment` / `navShare`.

**Found while stopping:** `Phase17ETest` compared the app's city catalog with the server's file, which B3 deleted; the
"795 / 0" measured after B3 was a cached Gradle result. The comparison was removed (the app's catalog goes in A1).
