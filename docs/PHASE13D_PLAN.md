# Phase 13D — Moderation v2: implementation plan

**Date:** 2026-10-24 · **Status: implemented, compiled, tested and verified 2026-10-24/25 (results and deviations
13D-D1–D11: `docs/PHASE13D.md`; M10 thresholds became < 3 / < 5 / < 8, 13D-D7); plan-level choices M1–M20 decided under the user's delegation** (the user
delegated plan-level and technical decisions; core product direction, D1–D15, Q1–Q12 and C1–C15 are unchanged).
Design: `docs/EXPERIENCE_PLATFORM_DESIGN.md` §8 (layers, decision policy, reports, repeat offenders, probation,
moderators, guidelines), §4.5–§4.6, §6.1 (evidence → deterministic policy → only humans restrict people; collapse
rather than remove; no shadow bans), §9.9 (amber feedback), §11.1, §12. Builds on 13A–13C and on Phase 7 moderation
(`docs/PHASE7.md`). Deferred here by 13B: Q6 "approve people I'm not connected with first", Q7 edit history, place
merging (`places.merged_into`).

## 1. Goal and scope

After 13D:

- anyone can **report** a post, a perspective or a person with a closed reason ("Something else" needs a short note);
  the reported person is **never told who reported, nor that a report exists**; reporters see their own reports as
  *Received* → *Reviewed* (action taken / no rule broken);
- **independent** reports (Phase 7 `Independence`, new-account probation) can **collapse** a post or perspective while
  it waits for a moderator — visibly ("Collapsed while it's reviewed — tap to show"), never silently;
- moderators get a second queue for **posts, perspectives and people**: keep, hide while reviewing (hold), collapse,
  remove; warn, pause perspectives, pause posting, suspend — each a human decision with a reason code, in the existing
  audit log, appealable to a **different** moderator through the existing appeals table;
- **strikes** (only human-confirmed violations, weighted, decaying with a 90-day half-life) suggest the next step;
- the author sees what happened to their content in **amber** with the reason and one tap to ask for a review;
  restricted people see an amber notice with the end date; **SOS, Safe Walk, the Safety Circle and the vault are never
  touched**, and block, mute, report, delete and "make more private" always work;
- authors can choose **"Approve perspectives from people I'm not connected with first"** (Q6);
- an edit made while a case is open keeps the earlier text **for the moderator only** (Q7);
- moderators can **merge duplicate places** (API);
- new accounts are on **probation** (72 h): their reports do not count yet, lower posting limits, no links;
- a **community guidelines** screen.

**Not in 13D:** AI classifiers, the decision policy's automatic holds, the composer nudge, the strong-language filter,
the image hold for new accounts' public photo posts (all 13E: they need an assessment); outcome counters for social
moderation (13E, where they measure the automatic actions, §6.6); the personal word filter and "Hide a place or
category" (13F/V2); the Activity list and push (V2); a moderator screen for place merging (API only, like Phase 7's
admin functions); the Tamil guidelines text (D5: Tamil UI in V2); a "self-harm" report reason (13E routes it to
support resources, never to punishment; a report today could only reach a moderator who has no way to help).
Nothing in the safety stack changes.

## 2. Plan-level choices (decided under the user's delegation)

| # | Choice | Decision | Why |
|---|---|---|---|
| **M1** | Where content cases live | **Sibling tables in the `moderation` package** (`content_cases`, `content_reports`), not new columns on `moderation_cases`. Reused from Phase 7: the audit log `moderation_actions` (new action/reason codes, `target_kind`), the appeals table `moderation_appeals` (one appeal per decision, a different moderator), `CaseRefs` pseudonyms (own namespace), roles read from the DB, the moderator request limit, the case pattern (one OPEN per target via `open_marker`, `version`), `Independence` | `moderation_cases.signal_id` is NOT NULL and frozen Phase 7/9 code (coordination, S15 Option B, 9B counters, orphan clean-up) reads it; widening it would change accepted, frozen behaviour. Deviation from design §11.1 ("cases extended"), same machinery |
| **M2** | Dependency direction | `moderation` reads and writes `social` (like it reads `community`); **`social` never references `moderation`**. Social reads only its own columns (post/comment state, `social_standing`) | Content and people stay usable without the moderation code; one direction is testable (`SocialIsolationTest`) |
| **M3** | Report reasons | Closed list from §7.3: `HARASSMENT`, `SEXUAL_HARASSMENT`, `HATE`, `THREAT`, `DOXXING` (incl. "where someone is right now"), `SPAM`, `NUDITY_OR_VIOLENCE`, `IMPERSONATION` (people only), `OTHER` (note 1–200 chars, required; no note otherwise). One report per person per target | Same codes for reports, decisions and strikes (13E reuses them); free text only where the design asks for it |
| **M4** | Reporter protection | Nothing the reported person can observe changes on a report: no notice, **edit and delete are never refused** (a refusal would be an oracle). Their content changes only when ≥ 3 independent reports collapse it or a moderator acts. Reporting an invisible target = 404. Reporting twice = the same 202 | With small audiences (Connections posts), any signal of "a report exists" points at a few people; retaliation is the main harm |
| **M5** | Counting and independence | A report **counts** from `counts_from` = max(now, account created + 72 h) and only while the reporter is not paused. Counting reporters are made **independent** with Phase 7 `Independence.select`; two reporters are linked when they reported ≥ 3 of the same targets and that is ≥ half of the smaller one's reports | No device ids or IPs; the rule already explained in Phase 7; sock puppets need time and separate histories |
| **M6** | What reports do by themselves | **Collapse only**, on posts and perspectives, at **3** independent counting reports (the C2 floor of 3); never hold, remove or restrict. People reports never act by themselves. Every report opens (or joins) a case for a human | Design §8.3; content is hidden automatically only reversibly; k = 3 makes the collapse say little about who |
| **M7** | Moderation state | Posts and perspectives get `moderation` NONE / COLLAPSED / HELD / REMOVED + reason + decision ref. **The one `Visibility` rule** gains it: HELD and REMOVED are visible to their author only; COLLAPSED is visible (shown collapsed); the **Edition leaves collapsed posts out**; Cocoon, lists, photos and perspectives follow automatically. Perspective visibility moves into `Visibility.canSeeComment` | One definition (§11.2); four-user tests extended |
| **M8** | Content decisions | **KEEP** (no rule broken: back to NONE, reports resolved), **HOLD** (hidden while reviewing; case stays open), **COLLAPSE** (final: stays, behind a tap, no strike — "collapse rather than remove"), **REMOVE** (final: hidden from everyone but its author, strike). Removed content is not deleted by moderators: the author still sees it, marked, and may delete it; an overturned appeal restores it | Reversible by appeal; nothing destroyed by a mistake |
| **M9** | Person decisions | **WARN**, **PAUSE_COMMENTS** (3 days), **PAUSE_POSTING** (7 days: no posts, perspectives or new places), **SUSPEND** (30 days: also no connection requests, invites or profile changes). Each human, audited, appealable, adds a strike. Never affects reading, block, mute, report, delete, narrowing, the Cocoon — or any safety feature | Graduated steps (§8.4); safety tools must always work |
| **M10** | Strikes | Rows in `social_strikes` (person, reason, weight 1–3, decision ref, time); weight THREAT/SEXUAL_HARASSMENT/HATE/DOXXING 3, HARASSMENT/NUDITY_OR_VIOLENCE/IMPERSONATION 2, SPAM/OTHER 1; points **computed** with a 90-day half-life (nothing to decay in a job). Pure `StrikePolicy` suggests the step: < 2 warn, < 4 pause perspectives, < 8 pause posting, else suspend. An overturned appeal deletes the strike | Only human-confirmed violations count; explainable; shown to moderators only |
| **M11** | Appeals | Rows in `moderation_appeals` (one per decision; the deciding moderator cannot decide it; nobody decides their own). Appealable: REMOVE, COLLAPSE and the three pauses. Phase 7's appeal list/decision is limited to community RESTRICT decisions; content appeals have their own list and decision endpoint | Reuse without mixing two meanings of "overturn" |
| **M12** | What moderators see | The content (incl. Connections-only text: needed to judge; every decision audited), its audience, the earlier text if edited during the case, photos **hidden until tapped** (D15 "blurred by default" without a new library), the post's text as context for a perspective; people as case refs (author, reporters with reason, counting yes/no, account age band, link letter); for a person case the public profile; suggested step. Moderators who are the author, a reporter or the subject cannot act (403) | Phase 7 pattern; wellbeing |
| **M13** | Edit history (Q7) | `post_revisions` rows are written only while a case about the post is open (`posts.in_review`), shown to the moderator, deleted when the case closes | History only where moderation needs it; edits stay invisible to everyone else |
| **M14** | Deleting reported content | Always allowed (M4); the open case and its reports go with it. A person report remains possible | Deletion removes the harm; refusing would reveal the report. Residual risk: post-and-delete harassment (documented) |
| **M15** | Approve first (Q6) | `posts.approve_first` (only with "Everyone can add perspectives"); perspectives from people not connected with the author wait (`comments.awaiting_approval`): the writer sees "Waiting for the author's approval", the author sees Approve / Hide, nobody else sees it | Author control layer (§8.1 layer 2) |
| **M16** | Probation | Accounts < 72 h: reports do not count yet (M5), at most 3 posts and 10 perspectives a day, no links in posts or perspectives (a clear message) | §8.5; the photo hold needs 13E's assessment |
| **M17** | Place merging | `POST /api/moderation/places/{id}/merge {into}`: same 6-character area only, neither already merged; EXACT/AREA posts move to the kept place, the duplicate keeps `merged_into` and disappears from search and nearby; reading it follows one hop. Audited (`PLACE_MERGED`). Moderator look-up `GET /api/moderation/places?area=` | D11 promised moderators would merge duplicates; a screen can follow with 13F |
| **M18** | Retention | Closed content cases and their reports are deleted after the Phase 7 closed-case retention (90 d); revisions when the case closes; strikes stay until they decay below 0.1 point (deleted then); the audit log as Phase 7. A new hourly `ContentModerationJob` in `moderation` does only this deletion | Minimum retention; `social` keeps its one scheduled task |
| **M19** | Limits | 20 reports a day per person; moderators share the Phase 7 per-hour limit | Report bombing; in memory |
| **M20** | Where the person sees decisions | `GET /api/social-standing` (any member): current pause (level, until, reason, appeal) and decisions about their content in the last 90 days, each with its appeal state; amber notice on Connections; the post screen shows the state of own content | No Activity list yet (V2); outside `/api/moderation/**`, which is moderators only |

## 3. Database: migration V23 (`V23__moderation_v2.sql`)

InnoDB, utf8mb4_unicode_ci, `DATETIME(6)` UTC, FKs cascade, binary collation for codes. **MySQL dry run before
applying.** CHECKs only on columns that are not part of a cascading FK (the V6/V20 rule).

| Change | Columns and constraints |
|---|---|
| `posts` | `moderation` NONE/COLLAPSED/HELD/REMOVED (default NONE), `moderation_reason` (closed list) NULL, `moderation_decision` CHAR(36) NULL (UUID CHECK), `in_review` BOOLEAN, `approve_first` BOOLEAN; CHECK NONE ⇒ no reason/decision; COLLAPSED ⇒ reason and decision both or neither (automatic collapse has neither); HELD/REMOVED ⇒ both; `approve_first` ⇒ comment policy EVERYONE |
| `comments` | the same three moderation columns + CHECK, `awaiting_approval` BOOLEAN |
| `post_revisions` | `post_id` FK cascade, `body` TEXT, `tip`, `replaced_at`; index |
| `content_cases` | `public_id` (UUID CHECK, unique), `target_kind` POST/COMMENT/PERSON, `post_id` / `comment_id` / `person_id` FKs cascade (exactly one, in code), `status` OPEN/CLOSED, `outcome` NO_VIOLATION/ACTION_TAKEN, times, `version`, generated `open_marker`; UNIQUE (post_id, open_marker), (comment_id, open_marker), (person_id, open_marker); state CHECK as V13 |
| `content_reports` | `public_id`, `case_id` FK cascade, `reporter_id` FK users cascade, target FKs (cascade) for UNIQUE (reporter, post) / (reporter, comment) / (reporter, person), `reason` CHECK, `note` CHECK (1–200 iff OTHER), `created_at`, `counts_from ≥ created_at`, `resolved` |
| `social_strikes` | `user_id` FK cascade, `reason`, `weight` 1–3, `decision` CHAR(36) unique, `created_at` |
| `social_standing` | `user_id` PK FK cascade, `restriction` COMMENTS_PAUSED/POSTING_PAUSED/SUSPENDED, `restricted_until`, `reason`, `decision` |
| `moderation_actions` | new `target_kind` POST/COMMENT/PERSON/PLACE NULL (NULL = Phase 7 signal decisions); action CHECK + `CONTENT_KEEP`, `CONTENT_HOLD`, `CONTENT_COLLAPSE`, `CONTENT_REMOVE`, `PERSON_KEEP`, `PERSON_WARN`, `PERSON_PAUSE_COMMENTS`, `PERSON_PAUSE_POSTING`, `PERSON_SUSPEND`, `PLACE_MERGED`; reason CHECK + the M3 codes |

`AccountEraser`: new user references `content_reports.reporter_id`, `content_reports.person_id`,
`content_cases.person_id`, `social_strikes.user_id`, `social_standing.user_id`, plus cases/reports/revisions of the
person's posts and perspectives before those. `AccountEraserCoverageTest` forces it.

## 4. Backend

| Area | Files |
|---|---|
| `social` (content side) | `ContentState` + `Post`/`Comment` moderation fields, `PostRevision`, `SocialStanding` + repositories; `Visibility` (moderation fact, `canSeeComment`, `showInEdition`); `SocialAccess.requireCanPost/Comment/Connect` (pause → 403 with the end date); `LinkPolicy` + probation limits in `SocialLimits`; `PostService` (approveFirst, revisions while `in_review`, `moderation` in answers), `CommentService` (approve, collapsed/awaiting in answers), `DiscoverService` (Edition without collapsed), `PlaceService` (follow `merged_into`, merged places out of search/nearby) |
| `moderation` (pure) | `ReportPolicy` (counting, independence over co-reports, collapse threshold, queue priority), `StrikePolicy` (weights, decay, suggested step) |
| `moderation` (services) | `ReportService` (file, own list), `ContentModerationService` (queue, case detail, decisions, content appeals, my standing, place merge, retention), `ContentModerationJob` (hourly retention only), `CaseRefs.ref(namespace, …)` |
| `moderation` (API) | `ReportController` (`/api/reports`), `SocialStandingController` (`/api/social-standing`), `ContentModerationController` (`/api/moderation/content-cases`, `/content-appeals`, `/places`) |
| changed Phase 7 | `ModerationEnums` (codes), `ModerationAction` (`target_kind`, content/person factories), `ModerationService.listAppeals/decideAppeal` limited to community RESTRICT decisions |

## 5. API (all authenticated; invisible = 404; no counts to anyone but moderators)

| Method and path | Body / result | Errors |
|---|---|---|
| `POST /api/reports` | `{targetKind POST\|COMMENT\|PERSON, targetId, reason, note?}` → 202 `{status: RECEIVED}` (also when already reported) | 400 own/unknown reason/note rules; 404 not visible; 429 |
| `GET /api/reports/mine` | `[{reportId, targetKind, reason, status RECEIVED\|REVIEWED, outcome null\|ACTION_TAKEN\|NO_ACTION, period}]` (newest 50) | — |
| `GET /api/social-standing` | `{restriction null\|{level, until, reason, decisionId, appeal NONE\|OPEN\|UPHELD\|OVERTURNED}, decisions[{decisionId, action, reason, targetKind, targetId, period, appeal, canAppeal}]}` | — |
| `POST /api/social-standing/decisions/{id}/appeal` | → 202 `{status: OPEN}` | 404; 409 not appealable / already asked |
| Post answers | add `approveFirst`; for the author `moderation {state, reason, decisionId}` (null when NONE); for others `collapsed` (null \| UNDER_REVIEW \| BY_MODERATOR) | — |
| Perspective answers | add `collapsed` (as posts), `awaitingApproval` (writer and post author), `moderation` (writer only) | — |
| `POST /api/posts`, `PUT /api/posts/{id}` | accept `approveFirst` | 400 without "Everyone can add perspectives" |
| `POST /api/comments/{id}/approve` | post author → 204 | 404 |
| Writes while paused | posts, perspectives, places (and, when suspended, connection requests, invites, profile changes) → 403 with the end date | — |
| `GET /api/moderation/content-cases?status=` | summaries `{caseId, targetKind, status, outcome, opened (date), topReason, reasons{code: n}, counting, independent, contentState, youTookPart}` ordered by priority | 403 |
| `GET /api/moderation/content-cases/{id}` | summary + `content` (post / perspective with post context / person profile), `revisions`, `participants[{ref, role AUTHOR\|REPORTER\|SUBJECT, reason, counting, accountAge, linkGroup}]`, `author {ref, suggestedStep, strikePoints, restriction}`, `possibleActions` | 403 took part; 404 |
| `POST /api/moderation/content-cases/{id}/actions` | `{action, reason}` → detail | 400 reason/action; 409 closed / version |
| `GET /api/moderation/content-cases/{id}/media/{mediaId}` | the photo (no-store) | 404 |
| `GET /api/moderation/content-appeals`, `POST …/{id}/decision {decision UPHELD\|OVERTURNED}` | appeal views (decision, reason, target kind, date) | 403 own decision; 409 decided |
| `GET /api/moderation/places?area=` | places in a 6-char area incl. merged state | 400 |
| `POST /api/moderation/places/{id}/merge` | `{into}` → 204 | 400 other area / self; 409 already merged |

## 6. Android

| Area | Files |
|---|---|
| `feature/social` | DTO/model fields (fail closed: unknown state → treated as not visible / collapsed, unknown reason dropped); `ReportSheet` (reasons, note for "Something else", guidelines link, "The person is not told who reported"); report entries in `PostFragment` (post + each perspective) and `PersonProfileFragment`; collapsed post/perspective ("Tap to show"); author's amber notices (collapsed / held / removed + reason + "Ask for a review"); `approveFirst` switch in `ComposeFragment`; "Waiting for approval" + Approve in the perspective menu; `MyReportsFragment`; `GuidelinesFragment`; standing notice on `ConnectionsFragment` |
| `feature/moderation` | `ModerationFragment` third and fourth lists (posts, perspectives and people; requests to look again at them); `ContentCaseFragment` + `ContentCaseViewModel` (content, photos behind "Show photo", reporters as refs, suggested step, actions with confirmation; FLAG_SECURE); `ContentModerationPolicy` (pure mirror: which actions fit, reasons per action) |

Amber for all moderation feedback (never red); calm words ("Our system…" is 13E; here: "A moderator…", "People
reported…" is never said to the author). Nothing about other people stored on the phone.

## 7. Tests

**Backend:** `ReportPolicyTest`, `StrikePolicyTest`, `VisibilityTest` (moderation states, comments, Edition);
`ReportApiIntegrationTest` (targets, reasons, note rules, once only, invisible 404, own 400, limits, probation,
reporter's list, nothing visible to the reported person); `ContentModerationIntegrationTest` (collapse at 3 independent,
linked reporters count once, decisions, strikes, suggested step, restrictions block exactly their writes and nothing
else, appeals by a different moderator, overturn restores and removes the strike, took-part 403, version 409,
revisions only during a case, deletion allowed silently, retention, place merge); `SocialFourUserTest` extended
(held/removed/collapsed per role; muted), `SocialAccountDeletionTest` + `AccountEraserCoverageTest`,
`SocialIsolationTest` (+ social never references moderation), `ModerationApiIntegrationTest` (Phase 7 appeals
unaffected), privacy invariants (no reporter id/ref/count in any non-moderator answer).

**Android:** DTO fail-closed tests, `ContentModerationPolicyTest`, `ContentCaseViewModelTest`, report view-model tests,
`PostViewModel` (collapsed, approve), `SocialTextsTest` (no reporter wording to authors, no red, no counts),
`ModerationTest` (no accusatory words in the new strings).

**Verification:** lint; V23 dry run with negative inserts for every CHECK and unique rule, then applied; live API script
(throwaway accounts, a temporary moderator role, try/finally clean-up, user count before = after); emulator (API 37).

## 8. Risks

| Risk | Mitigation |
|---|---|
| Reporters identified by the reported person | M4: no oracle, no notice; collapse needs 3 independent reports; never "people reported this" in author-facing words |
| Report bombing to silence someone | Independence + probation + pause excludes; reports only collapse (visible, tap to view); humans decide; appeals |
| Shadow banning by accident | Every state is shown to the author; `Visibility` tests per role; the Edition rule is the only list difference and is documented |
| Moderation touching safety | `social`/`moderation` never reference safety packages (isolation tests); pauses are checked only on social writes |
| Phase 7 regressions | Sibling tables; Phase 7 tests unchanged and green; appeal lists filtered by action type |
| Harassment by post-and-delete | Person reports; strikes on removal; documented residual risk |
| MySQL-only DDL | Mandatory dry run |
| Moderators reading private (Connections) posts | Only when reported; audited decisions; FLAG_SECURE; pilot team (D15) |

## 9. Order of work (each step buildable)

1. V23 + entities + `AccountEraser` → tests green.
2. `Visibility` + moderation state in posts/perspectives/lists, approve-first, revisions, probation, pauses → tests.
3. Reports + `ReportPolicy` + collapse → tests.
4. Content cases, decisions, strikes, standing, appeals, place merge, retention job → tests.
5. Android data layer, report sheet, states, approve-first, my reports, guidelines, standing → tests.
6. Moderator screens → tests; lint.
7. MySQL dry run + apply, live API script, emulator, security/privacy review, docs, commit, push.
