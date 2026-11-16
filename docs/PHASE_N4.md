# Phase N4: Moments and Today

Status: **decisions LOCKED by the owner on 2026-11-16; implementation NOT started.** The plan below was presented on
2026-11-16 and approved with the owner's changes (voice deferred, new-member period 7 days, no learned model). Units B1,
B2, B3, B4, A1, A2, A3, C; one local commit each; never pushed, never deployed. Each unit starts only when the owner says
so. Direction: `IRL_DIRECTION.md` §2; decisions N-42 to N-58 in `DECISIONS.md`. N5 (responses and plans) is designed
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

## 11. Units (approved order, NOT started)

| Unit | Content |
|---|---|
| B1 | V35 + moment core + Today + lifecycle + keep + timing + daily question + limits + purge |
| B2 | Photo upload + serving + sanitization + limits + new-member protection (no voice) |
| B3 | Moderation + "This is about me" + profiles + reports + old social removal + tests |
| B4 | Development DB backup + migration dry run + V35 applied + API validation |
| A1 | Today + moment UI + You + profiles + navigation + old social clean-up + screenshot protection |
| A2 | Create flow + text + audience + Who's in? + camera + photo + moderation nudge + upload |
| A3 | Reports + "This is about me" + author notices + moderator case + admin questions |
| C | Full testing + documentation + emulator walk |

## As built

Nothing yet: implementation has not started.
