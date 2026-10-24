# Phase 13D — Moderation v2: results

**Date:** 2026-10-24/25 · **Status: implemented, compiled, tested, verified (MySQL, live API, emulator API 37).**
Plan: `docs/PHASE13D_PLAN.md` (choices M1–M20, decided under the user's delegation). Design:
`docs/EXPERIENCE_PLATFORM_DESIGN.md` §8, §4.5–§4.6, §6.1, §9.9, §11.1. Builds on 13A–13C and on Phase 7 moderation
(`docs/PHASE7.md`).

## 1. What was built

- **Reports** (`ReportService`, `POST /api/reports`): posts, perspectives and people; closed reasons (§7.3:
  harassment, sexual harassment, hate, threats, doxxing, spam, nudity/violence, impersonation for people, "something
  else" with a 1–200 character note). Only what the reporter can see can be reported (else 404); one report per person
  per target, and a repeat gives the same 202 (no oracle); 20 per day; reporting is never paused.
- **The reported person observes nothing** (M4): no notice, edits and deletions never refused. Their content changes
  only when **3 independent counting reports** collapse it (M6) or a moderator acts. A report counts once the reporter
  is past the 72 h probation and not paused; reporters who keep reporting the same things count once (Phase 7
  `Independence`, reused). People reports never act by themselves.
- **Moderation states** on posts and perspectives (V23): NONE / COLLAPSED / HELD / REMOVED + reason + decision. The one
  `Visibility` rule now decides them: held and removed content is its author's alone (always shown to them, marked);
  collapsed content stays visible behind a tap; the Edition leaves collapsed posts out (`showInEdition`); perspectives
  are decided by `Visibility.canSeeComment` (hidden Q5, waiting Q6, moderation).
- **Moderators** (`ContentModerationService`, `/api/moderation/content-cases`): a queue ordered by severity × independent
  reports; case detail with the content (Connections-only text too), the post as context for a perspective, the public
  profile for a person case, earlier text if edited during the case, photos on request, people as case pseudonyms
  (`CaseRefs.contentRef`, own namespace), the author's decayed strike points and a suggested step. Decisions: keep,
  hide while reviewing, collapse (no strike), remove (strike); warn, pause perspectives 3 d, pause posting 7 d, suspend
  30 d (each a strike, at most one strike per case). A moderator who wrote, reported or is the subject gets 403.
- **Strikes** (`StrikePolicy`): weight by reason (3/2/1), halved every 90 days when read, never written back.
- **Pauses** (`social_standing`, read by `SocialAccess.requireCanComment/Post/Connect`): stop exactly their writes.
  Reading, blocking, muting, reporting, deleting, narrowing, the Cocoon and every safety feature are never affected.
- **Appeals** reuse `moderation_appeals` (one per decision, a different moderator, never the decider and never a
  moderator who reported it); an overturn restores the content (if it still carries that decision), lifts the pause (if
  still the one in force) and deletes the strike. Phase 7's appeal queue now holds RESTRICT appeals only.
- **The person's own view** (`GET /api/social-standing`): the pause in force and decisions about them or their content
  in the last 90 days (never a "keep": they were not told anything was reported), each with its review state.
- **Approve first** (Q6), **edit history** only during a case (Q7), **place merging** (API, same 6-character area only),
  **probation** (72 h: reports wait, 3 posts / 10 perspectives a day, no links), hourly `ContentModerationJob`
  (collapse when a probation report starts counting; retention).
- **Android:** report dialogs (post, perspective, person) with the guidelines link; collapsed posts and perspectives
  behind "Show it"; the author's amber notice with the reason and "Ask for a review"; "Waiting for approval" + Approve;
  the approve-first switch; "Your reports"; "Community guidelines"; the pause notice on Connections; moderators' queue
  sections and the case screen (FLAG_SECURE, photos only on tap, reason dialog, confirmation).

## 2. Database: V23 (`V23__moderation_v2.sql`)

`posts`/`comments` moderation columns (+ `in_review`, `approve_first`, `awaiting_approval`) with state CHECKs;
`post_revisions`; `content_cases` (one OPEN per post / perspective / person via `open_marker` + unique keys);
`content_reports` (one per reporter and target, note only for OTHER, `counts_from ≥ created_at`); `social_strikes`;
`social_standing`; `moderation_actions.target_kind` + extended action/reason CHECKs + a kind-matches CHECK (13D codes
always name a target, Phase 7 codes never). "Exactly one target" is in code (no CHECK on cascading-FK columns).

## 3. Results

| Check | Result |
|---|---|
| Backend `mvn test` | **694 run, 0 failures, 0 errors, 2 skipped** (13C: 663). New: `ReportAndStrikePolicyTest` (5), `ReportApiIntegrationTest` (7), `ContentModerationIntegrationTest` (13), `ProbationApiIntegrationTest` (1); `VisibilityTest` +2, `SocialIsolationTest` +2, `SocialFourUserTest` +1 |
| Android `testDebugUnitTest` | **657, 0 failures** (13C: 641). New: `ModerationV2Test` (16); `SocialTextsTest` layouts extended |
| Android `lintDebug` | **0 errors / 54 warnings** on a fresh `--rerun-tasks` run. 13C recorded 94; the old count could not be reproduced like-for-like (a HEAD worktree could not be created here), so no comparison is claimed |
| V23 dry run (MySQL 8.0, scratch `dry_*` copies built from the V23 text, dropped after) | **51/51** negative inserts rejected, **18/18** valid inserts accepted; deleting a post cascades its cases, reports and revisions (4 → 0 rows). The first run found **2 real gaps** (13D-D1) |
| V23 applied | Flyway "Successfully applied 1 migration … now at version v23" (dev DB **v23**, next **V24**) |
| Live API (backend on MySQL, 6 throwaway `live13d.*` accounts, try/finally) | **30/30 PASS** first run; users **170 before = 170 after**; no account or place left |
| Emulator (AVD `Medium_Phone_API_37.0`) | **E1–E8 PASS**; 3 wording issues found there and fixed (13D-D4) |

Emulator walk-through (throwaway `emu13d.*` accounts, deleted afterwards; app data cleared; `adb reverse` removed;
emulator and backend stopped; users 170): E1 report dialog from a post (closed reasons; no "pretends to be someone else"
on a post; guidelines link), sent · E2 the post then shows "Collapsed while a moderator looks at it." + "Show it"; text,
tip and photos hidden until tapped · E3 "Your reports": "A post · Insults or bullying — Received", nothing about others
· E4 guidelines screen · E5 moderator: queue section "Posts, perspectives and people", case screen (content, guidance
"Reports are not proof", people as pseudonyms only, suggested step), Remove → reason preselected → confirmation →
closed · E6 author: amber "A moderator removed this for others. Only you can see it. Why: Insults or bullying." + "Ask
for a review" → "A review is waiting for another moderator." · E7 after a posting pause: amber notice with the end date
and reason, "Reading, blocking, muting, reporting and your safety tools are not affected.", review button · E8 composer:
approve-first switch with "Anyone who can see it", hidden with "Your connections".

**NOT VERIFIED:** real phone, TalkBack speech, dark mode, 320 dp / large fonts; the moderator's photo view on a device
(server path tested); the hourly job on a live server (called directly in tests).

## 4. Deviations and findings

| # | What | Why |
|---|---|---|
| 13D-D1 | **Dry-run finding, fixed before applying:** MySQL lets a CHECK pass when it evaluates to UNKNOWN, so `outcome IN (…)` with a NULL outcome and `target_kind IN (…)` with a NULL kind slipped through. V23 now says `outcome IS NOT NULL` and `COALESCE(target_kind, '-')` | Found by negative inserts; H2 never runs these CHECKs |
| 13D-D2 | **Same weakness in V13** `ck_moderation_cases_state` (Phase 7): a CLOSED case with a NULL outcome would pass | Not changed (frozen Phase 7/9 table; the code never writes it). Follow-up |
| 13D-D3 | Appeals: a moderator who **reported** the content can neither see nor decide its appeal (403) | Found while writing the live script; conflict of interest, now tested |
| 13D-D4 | **Emulator findings, fixed:** the Phase 7 moderation intro said "Cases about places, never about people"; a decided case's headline fell back to "A guideline" (top reason now from all its reports); decided reports read "does not count yet" | Tests pin the intro and the top reason |
| 13D-D5 | **13C C-D7 explained and fixed:** `SocialClient.person` built handles from a name + 8 random hex characters; when all 8 were digits (~2 %), `HandlePolicy` saw only the name's letters, and "Mod"/"Me" are reserved → 409 | Suffix now always starts with a letter |
| 13D-D6 | Content cases are a **sibling** of the Phase 7 signal cases (design §11.1 said "extended") | Frozen Phase 7/9 code reads `moderation_cases.signal_id` (M1) |
| 13D-D7 | `StrikePolicy` thresholds are < 3 warn, < 5 pause perspectives, < 8 pause posting, else suspend (plan draft < 2/< 4/< 8), counting this case's reason | A first threat suggests a pause, a first insult a reminder |
| 13D-D8 | Self-harm is not a report reason; the strong-language filter, AI holds and the new-account photo hold are 13E | They need the assessment (§6) |
| 13D-D9 | Android shows the app's generic text for a 400 (e.g. "new accounts can share links after three days"); only 403/409/429 messages are shown verbatim (existing `ApiResult` rule) | The guidelines screen states the link rule; a later change can map VALIDATION messages |
| 13D-D10 | Tests: the probation test runs on its own H2 database | A second Spring context on the shared one re-creates the tables under the cached main context (ids restart; in-memory limiters still remember old ids) |
| 13D-D11 | The emulator seed hit the Phase 7 sign-up limit (10 per address per hour) | Expected behaviour; the backend was restarted to reset the in-memory limiter |

## 5. Security and privacy review

- **Reporters are never revealed to the reported person:** no answer to them names a reporter, says a report exists or
  carries a count; edit/delete are never refused; KEEP decisions are not listed in their standing; the author-facing
  strings are scanned for "report(s)/reported" (`ModerationV2Test`). Inherent residual: a collapse after 3 independent
  reports is visible (no shadow bans); in a very small audience the author may guess who. k = 3 and independence keep
  this as small as transparency allows.
- **No shadow ban:** every state is shown to the author (`moderation`), and others see a reasoned collapse; held and
  removed content is never hidden from its author.
- **Content hidden automatically only reversibly** (collapse); hold, remove and every pause are a human's, with a
  reason code, in the append-only audit log, appealable to a different moderator.
- **Safety untouched:** `SocialIsolationTest` adds "social never references moderation" and "safety packages never read
  moderation"; live: a suspended account still reads SOS and the Safety Circle and can mute, block and report.
- **Moderators:** roles from the DB on every request; shared per-hour limit; pseudonyms per case (own HMAC namespace);
  no names, e-mails or ids except a person case's public profile; FLAG_SECURE; photos only on request (`no-store`).
  Moderators do read Connections-only text of a reported post (necessary; decisions audited).
- **Data minimisation:** edit history only during a case and deleted at its close; strikes decay by computation and are
  deleted after the audit retention; closed cases and their reports after 90 days; ended pauses at once; everything
  cascades with its target; `AccountEraser` + `AccountEraserCoverageTest` cover the 5 new user references.
- **Report abuse:** probation, independence, paused reporters excluded, 20 a day, reports only collapse.
- **Post-and-delete harassment** (M14) remains possible; person reports and strikes are the mitigation. Documented.

## 6. Viva notes

- **What / why:** distributed moderation (viewer, author, community, humans, appeal) without silencing anyone by mistake.
- **Key classes:** `ReportPolicy`, `StrikePolicy`, `ReportService`, `ContentModerationService`, `ContentModerationJob`,
  `Visibility` (moderation, `canSeeComment`, `showInEdition`), `SocialAccess` (pauses, probation); Android
  `ReportViewModel`, `ContentCaseViewModel`, `ContentModerationPolicy`.
- **Flow:** report → case (open or joined) → independence tally → maybe collapse → moderator decision (audit row id =
  `moderation_decision` on the content) → strike / pause → the person's standing → appeal → another moderator.
- **Likely questions:** Why not tell the author about reports? (retaliation in small audiences.) Why can reports only
  collapse? (automatic punishment fails unsafely; collapse is visible and reversible.) Why sibling tables? (frozen Phase
  7/9 queries.) Why compute strike decay? (nothing to forget to update; explainable.) Why does a pause never touch SOS?
  (a safety app must fail safely; isolation tests.) What did the dry run catch? (UNKNOWN CHECKs pass in MySQL.)
