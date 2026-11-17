# Phase N5: Responses and plans

Status: **design APPROVED by the owner on 2026-11-17; D14–D19 LOCKED.** The owner gave full creative freedom inside the
product direction and D14–D19 ("Moment → Response → People → Plan → Real life → This happened"). Units B1–B4, A1–A3, C;
one local commit each; never pushed, never deployed. Decisions N-88 to N-99 in `DECISIONS.md`. "As built" at the end.

## 1. What N5 is

N5 lets people answer a moment and lets a Who's in? become a real plan: the four responses the owner chose (Same, Say
more, I'm in, private reply), a small plan that closes on its own, and one home for all of it: **Replies**. No public
comments, no numbers, no "seen", no endless chats.

- Say → **Same** (I feel this too), **Say more** (the author adds one follow-up), **Reply** (private, taking turns).
- Who's in? → **I'm in** → the author **adds** people → a **plan** (closes a day after its time) → **This happened**.
- **Replies** = what needs you, your plans, your conversations, who felt the same; then it ends.

## 2. Locked decisions (owner, 2026-11-17)

| # | Decision |
|---|---|
| D14 | **Same**: on a Say only. The author sees names (newest first), never a band. Everyone else sees a band counting the OTHER people who said Same: 1 "Someone feels the same", 2–5 "A few feel the same", 6+ "Many feel the same"; with one's own tap "You and someone else / a few others / many others feel the same". Always on the moment's page; on Today cards only after the viewer said Same. Constants in the policy |
| D15 | **Say more**: on a Say only; the author sees names; the author answers with ONE follow-up (text ≤ 500) added to the moment, only after at least one person asked, only while it is on Today; shown under "More" to everyone who can see the moment; askers are told in Replies; then "Said more". Checked and moderated as part of the moment; not an edit; does not extend the 24 h |
| D16 | **No explicit decline**: author Add · Not this time (silent, tidies only the author's list) · Remove after adding. The asker sees "Waiting for …" until added, then "This plan has closed" / "Called off", never "declined". Removed: "You're not in this plan any more", no line for others, cannot ask again. Withdraw any time ("… can't make it" when they were in). Asks close at the plan time (no time: when the moment leaves Today) and on Call it off. No cap (N-50) |
| D17 | **Private replies**: anyone who can see the moment while it is on Today, per the author's setting My people (default) · Anyone at MIET · Nobody, checked when the conversation starts; one conversation per moment per person; **strict turns** (nobody sends twice in a row); text only 1–500, no links for new members, the on-phone nudge; open until 24 h after the moment leaves Today, then read-only until the purge; either person can End it; no unsend, no edit; never read state |
| D18 | **Plans**: members = author + added; text ≤ 500, system lines, exact message times for members; opens at the first Add; closes 24 h after the plan time (no time: 24 h after the moment leaves Today) or at once on Call it off; read-only until the purge. **This happened**: one per plan, any member after the plan time while the plan is open, a photo taken now (+ optional words) → a Say on Today linked to the plan with the plan's audience (a My-people plan: only its author); names nobody else, no count; all moment rules apply. **No "made happen" count or list on any profile** |
| D19 | **Replies marker**: a dot (never a number) on the Replies tab when something arrived since the person last opened Replies; the "last opened" time is theirs alone, never per message. Same never lights it. Checked on coming to the front, on tab changes and every 2 minutes while IRL is on screen (push: N7) |

## 3. Rules (N5)

- **Response matrix:** Same and Say more on a Say; I'm in on a Who's in?; private reply on both. Only while the moment is
  on Today (live, visible to others, for I'm in also not called off and before the plan time), never on one's own.
- **Visibility (the one rule, extended):** respond = can see the live moment + not own. A plan: the author, or a member
  (IN) who still passes the people facts with the author (active, same community, no block either way) while the moment
  is not held or removed; also after the moment left Today, until the plan closes. A conversation: the two people
  under the same facts. A message: hidden between two people who block each other and when its writer is no longer
  visible; HELD / REMOVED messages only to their writer (marked); COLLAPSED behind a tap.
- **Blocks** end everything between two people at once and silently (their conversation; an ask / membership in the
  other's plan); inside a third person's plan they stop seeing each other's messages. Mutes stay Today-only.
- **Never:** read receipts, ticks, typing, "seen", online / last active, who opened anything, any number. Exact times only
  inside plans and conversations, to their members.
- **Pause:** "responses" (`COMMENTS_PAUSED`) stops Same, Say more, follow-ups, asks, starting replies, sending messages;
  posting pause and suspension include it. Reading, leaving, ending, calling off, blocking, reporting never stop.
- **Text check** (rules only) on every message (kind `COMMENT`) and follow-up (as the moment's words), inside the write,
  before delivery: HOLD = never delivered until a moderator decides (writer sees a notice); COLLAPSE = behind a tap
  (writer told, may ask for a review); self-harm = one support line to the writer, never stored. Service down:
  delivered and rechecked, except a new member's FIRST private reply to a non-connection, which waits at most 2 h.
- **Moderators** see a reported message + up to 10 earlier messages of that conversation (pseudonyms); a held message
  alone + whether the two are connected; never browse.
- **Limits** (in memory): Same / Say more 200 a day; asks 20 (new members 10); new conversations 10 (new members 3);
  messages 300 a day and 10 a minute; Replies reads 600 an hour.
- **Lifecycle:** responses close when the moment leaves Today; plans and conversations run to their own close; response
  data is deleted 7 days after the moment's end, also under a kept moment, unless something has an open case; deleting
  a moment deletes everything under it; account deletion erases the person's taps, asks, conversations, messages.
- **N5 adds no push, no dependency, no permission.** Push is N7; the Safety Circle offer on plans is N6.

## 4. Units

| Unit | Content |
|---|---|
| B1 | V36 written (Flyway held at 35); Same, Say more, follow-up; asks, add, set aside, remove, call off; the visibility rule; `responses` in moment answers; people list; limits; eraser; boundary guard |
| B2 | Conversations and messages: private replies (setting, turns, End, close), plan thread (system lines), `MessageSent`, purge of response data |
| B3 | Moderation: check before delivery, behind a tap, outage rule, MESSAGE reports and cases with context, review requests, the responses pause, queue, purge skips open cases |
| B4 | Replies (sections, marker, opened); This happened; V36 backup + dry run + apply on the development MySQL; curl walk |
| A1 | Responses on cards and the moment page; author tools; data layer |
| A2 | Replies tab + marker, conversation, plan, reply sheet, Private replies setting |
| A3 | Message moderation on the phone, moderator message cases, This happened, guidelines wording |
| C | Full tests, lint, security / privacy review, emulator walk, documents |

## As built

### B1 (2026-11-17): V36 written, Same, Say more and its follow-up, I'm in, Call it off — backend

Status: **implemented, compiled, tested** (backend 483 + guard updates, full clean run green). V36 is written and
applied NOWHERE: `spring.flyway.target=35` holds Flyway until B4 (N-99). **Do not run the backend against the
development MySQL before B4**: the entities already name V36 columns.

- Schema `V36__responses_and_plans.sql`: `moments` + `more_text`, `more_at`, `called_off_at`, `happened_of`;
  `moment_signals`, `plan_people`, `conversations`, `messages`, `social_settings`, `replies_opened`; moderation tables
  learn `message_id` / `MESSAGE`.
- Entities `MomentSignal`, `PlanPerson`, `Conversation`, `Message`, `SocialSettings`, `RepliesOpened`, `ResponseEnums`;
  `Moment.addMore / callOff / happenedOf`.
- Pure `ResponsePolicy` (bands 1 / 2–5 / 6+, asks window, plan and reply close times, This happened window);
  `Visibility.canRespond` and `canSeeSpace`.
- `ResponseService` (Same / Say more toggles in their own transaction, the follow-up + `MomentMoreAdded` event, ask,
  withdraw, add, set aside, remove, call off, people lists), `ResponseViews` (the `responses` block per page, batched),
  `PeopleLookup` (only people the viewer may see), `ResponseLimits` + `ResponseProperties` (`app.responses.*`),
  `SocialAccess.requireCanRespond` (the responses pause, N-95), `ResponseController`. `PlanSpaces` and `PlanEvents` are
  filled by B2 (conversations).
- Moment answers gain `responses` and `happenedOf` (B4). `AccountEraser` covers the six new user references.
- Tests: `ResponsePolicyTest`, `ResponseApiTest` (10), `ResponseBoundariesTest`; `MomentApiIntegrationTest` field
  guard extended on purpose.

### B2 (2026-11-17): private replies and plans — backend

Status: **implemented, compiled, tested** (backend 495 / 0 failures / 2 skipped, full clean run). V36 still held.

- Pure `ConversationPolicy`: the author's setting (My people by default, Anyone at MIET, Nobody), strict turns in a
  private reply (nobody sends twice in a row; a plan has no turns), CLOSED / ENDED.
- `ConversationService`: start a private reply (on Today, not one's own, one per moment and person, the setting, a new
  member's links rule, `ResponseLimits.startReply`), send (row-locked turn check), get (members only, else 404 like a
  missing one; a REPLY also needs the two people to see each other: a block ends it for both, silently), End (either
  person; read-only for both; never stopped by a pause), the private-replies setting. Members get exact times; others'
  held or removed messages are left out, collapsed ones are behind a tap; the writer sees a notice about their own.
- `PlanSpaces` (planId for the author and the people in; `canReply`; the viewer's `replyId`), `PlanEvents` (the plan
  opens at the first Add with "… is in"; leaving writes "… can't make it"; removal writes no line; Call it off writes
  its line and closes the plan; This happened writes its line, B4).
- `MessageSent` event (published inside the write before anyone else can read the message; listened to in B3).
- `ResponseData.deleteFor` (messages, conversations, plan places, taps) with a `MessageCleanup` port for the
  moderation rows (B3); used by moment delete and by `SocialMediaCleanupJob`, which now also clears response data
  7 days after a moment's end even when it is kept, never while a message or the moment is in review (N-98).
- `ConversationController`: `POST /api/moments/{id}/private-replies`, `GET /api/conversations/{id}`,
  `POST /api/conversations/{id}/messages`, `POST /api/conversations/{id}/end`, `GET|PUT /api/social-settings`.
- Tests: `ConversationApiTest` (11).

### B3 (2026-11-17): moderation of messages and of the follow-up — backend

Status: **implemented, compiled, tested** (backend 504 / 0 failures / 2 skipped, full clean run). V36 still held.

- `TargetKind.MESSAGE`; `ContentCase.aboutMessage`, `ContentReport.messageId`, `ContentAssessment.ofMessage`
  (`momentId` nullable); repositories find, clean and keep retention per message (`deleteOrphans` of assessments no
  longer depends on a moment).
- `AssessmentService`: one `Target` with `MomentTarget` and `MessageTarget`. `onMessageSent` (kind `COMMENT`):
  hold = never delivered until a person decides, collapse = behind a tap, self-harm = the writer's support line only;
  during an outage only a new member's first private reply to a non-connection waits (`AWAITING_CHECK`, released by
  `maintain` after 2 h). `onMomentMoreAdded`: the moment is checked again with all its words (text, "where",
  follow-up); a hold hides the moment with its follow-up; an outage keeps any existing state. Review requests accept
  `MESSAGE` (own message, system state only).
- `ReportService`: reports about a message the reporter can see (`ConversationService.requireVisibleMessage`),
  row-locked, one per reporter, opens or joins the message's case, keeps it from the purge; collapse at 3 independent
  reports as for moments.
- `ContentModerationService`: message cases (state, Keep / Hold / Collapse / Remove, close, overturn, appeal,
  standing `targetId` = the conversation). `ContentView.more` (the follow-up) and `ContentView.conversation`
  (N-97: up to 10 earlier visible messages + the reported one, case pseudonyms; a check-held message alone with
  `connected` for a private reply).
- `MessageModerationCleanup` implements social's `ResponseData.MessageCleanup` port; `AccountEraser` removes reports,
  cases and checks of the messages it erases (`ERASED_MESSAGES`).
- Tests: `MessageModerationApiTest` (4), `MessageAssessmentIntegrationTest` (5, stub service, own H2).

### B4 (2026-11-17): Replies, This happened, V36 on the DEVELOPMENT MySQL

Status: **implemented, compiled, tested (backend 510 / 0 failures / 2 skipped, full clean run); V36 applied on the
development MySQL 8.0.46 only; curl walk on real MySQL.** The pilot is untouched (old jar, V30).

- `RepliesService` + `RepliesController`: `GET /api/replies` (needs you: ASKS, SAY_MORE, YOUR_TURN, MORE; plans: PLAN,
  WAITING, NOT_IN, CLOSED, CALLED_OFF; conversations: REPLY; same: SAME), names (≤ 3 + "and others"), never a number;
  `fresh` per line and the tab dot against the person's own `replies_opened` time (`GET /api/replies/marker`,
  `PUT /api/replies/opened`); Same never lights it; a closed ask is quiet; a block removes the lines.
- This happened (N-92): `POST /api/moments` + `happenedOf` (a photo Say; author or someone in; after the plan time
  while the plan is open; once per plan, plan row locked + `uk_moments_happened_of`; the plan's audience; a My people
  plan by its author only); the plan's HAPPENED line (not for a held Say); `happenedOf {id, text}` in answers; deleting
  or purging the plan clears the link (`MomentRepository.clearHappenedOf`).
- V36 gained `uk_moments_happened_of` before it was applied (N-99). `spring.flyway.target=35` removed.
- **Applying V36:** backup `db-backups\safesphere-dev-before-v36-20261117.sql` (32 tables, complete; V36 touches no
  files). Pre-check: Flyway at 35; the 5 names V36 drops exist once each; the new audit CHECK is the old one + MESSAGE
  (all 9 audit rows pass); 0 moments, cases, reports, assessments. **Rehearsal of the whole migration on a `zz_` copy
  of the schema** (all tables, foreign keys and CHECKs renamed; real audit rows, MIET, 4 users copied): no error, 38
  tables. **Dry run: 83 cases (30 accepted + 53 refused), each by the intended constraint, 0 findings**; cascades: a
  deleted plan keeps its This happened Say with `happened_of` NULL; a deleted moment takes its conversations,
  messages and their cases, reports, checks; a deleted account takes its taps, asks, replies, messages, setting and
  marker. Scratch tables dropped (0 left). Applied by Flyway at start-up in 0.68 s: version 36, 38 tables, audit rows 9.
- **curl walk on the development backend (8090, real MySQL):** 46 checks: 41 as written; 5 were mistakes in the walk's
  own expectations (JSON booleans printed as `true`/`false`; the author's name lists are plain strings; "needs you"
  was correctly empty because everything had been answered). Covered: Same band, no number anywhere, names for the
  author, the follow-up (once, rough time for others), private reply with turns, a stranger 404, signed out 401,
  I'm in / Add / Not this time (the asker still "waiting"), the plan (members only, no turns), Replies sections, the
  dot and "opened", a report about a message, Call it off (closed, line), and deleting both moments left 0 signals,
  plan rows, conversations, messages, message cases and reports. Not manually verified on a phone (Android is next).
- Tests: `RepliesApiTest` (6); `ResponseBoundariesTest` checks every N5 DTO.

### A1 (2026-11-18): responses on cards and the moment page, author tools, data layer — Android

Status: **implemented, compiled, tested (Android 780 / 0, `gradlew --offline clean testDebugUnitTest assembleDebug`);
NOT manually verified.** Decision N-100.

- **Data:** `ResponseModels.kt`, `ResponseDtos.kt` (fail-closed: unknown band = no band, unknown or AUTHOR standing for
  someone else = no plan controls, names only on one's own moment, at most 3, blank dropped; follow-up exact time only for
  the author), `ResponseApi`, `RemoteResponseRepository` (non-UUID ids never reach a URL), `AppContainer.responseRepository`,
  `Moment.responses` / `happenedOf`.
- **Pure policy `ui/ResponseLabels`:** D14 bands (page always, card only after one's own Same), D16 standing (waiting /
  in / closed / called off / not in any more, never "declined"), the author's name lines (who waits first; one / many verb),
  `cardLine` (ONE line), `actions` (the response matrix: Same / Say more on someone else's live Say, Say more ends after
  the follow-up; I'm in only while asks are open; Take it back / I can't make it always while asked (open) or in; Add a
  little more once, after an ask, while others can see it on Today; People for the author and for someone in; Call it
  off once others can see it). `PeopleSections` (author: Want in = Not this time + Add while open, In = Remove with a
  confirm, Not this time = Bring back, Want to hear more, Feel the same; someone in: In only).
- **UI:** card (`item_moment_card.xml`): "More · rough time" block in the body, "This happened · …" line, one quiet
  response line, all in the spoken text. Page (`fragment_moment.xml`): Same / Say more checkable buttons (tick + checked
  state, never colour alone), I'm in, standing line, name lines, Take it back / I can't make it, Add a little more (dialog,
  500, the on-phone nudge through `DraftNudgeDialogs`, support line if the server suggests it), People bottom sheet
  (`sheet_moment_people.xml`, `MomentPeopleSheet`), Call it off (confirm). Reply / plan buttons wait for A2.
  `MomentViewModel(repository, responses)`; Today re-reads only an answered card (`RESULT_CHANGED`, `onUpdated`).
- **Tests:** `ResponsesTest` (22: parsing, words, matrix, sheet, view model, Today, guards: no digit / "declined" / "seen"
  in `response_*` words, nothing stored); `MomentUiTest` constructors; `SocialTextsTest` covers the new sheet.

### A2 (2026-11-18): Replies tab and dot, plans and private replies, the setting — Android

Status: **implemented, compiled, tested (Android 797 / 0, `gradlew --offline clean testDebugUnitTest assembleDebug`);
NOT manually verified.** Decision N-101.

- **Package `feature/replies`** (social palette; never imports safety or membership): `domain/Replies.kt`
  (`Conversation`, `Message`, `You`, `Turn`, `ReplyItem`, `RepliesPage`, `PrivateReplies`, `RepliesRepository`),
  `data/RepliesDtos.kt` (fail-closed: unknown conversation kind or unreadable moment = error; unknown turn = CLOSED;
  WAITING, CLOSED, ENDED or not open never offer sending; unknown message kind / event and unknown Replies lines
  dropped; a line in the wrong section dropped; SAME never fresh; someone else's notice and support line never kept;
  at most 3 names; unknown setting = MY_PEOPLE), `RemoteRepliesRepository` + `RepliesApi` (the one Retrofit; non-UUID ids
  never reach a URL). `AppContainer.repliesRepository`, `repliesMarker`.
- **Bar:** Today · ✚ Create · Replies · You (`repliesFragment` is a tab). **Dot (D19):** `RepliesMarker` (app scope;
  `BadgeDrawable` with `clearNumber()`, spoken "Replies, something new"): read on coming to the front and every 2 min
  while STARTED (`MainActivity`), on tab changes (at most every 15 s), one call at a time; a lost connection keeps the
  dot, a refusal clears it; cleared before sign-in. Opening Replies reads it, then `PUT /opened`, then clears the dot.
- **Replies (`RepliesFragment`, FLAG_SECURE):** Needs you · Plans · Private replies · Feel the same, empty ones left out,
  then an end line; names only (`RepliesLabels.title`), whose turn (never "seen"), exact time on plan / reply lines,
  "New" on fresh lines. A line opens its conversation, else the moment. 403 `MEMBERSHIP_NEEDED` → "Get verified".
- **Conversation (`ConversationFragment`, task screen, FLAG_SECURE):** what it is about (moment words, plan time and
  where, called off), who is in it, "Open until …", See the moment, End this conversation (a private reply, either
  person, confirm). Messages oldest first: own on the right, writer's name for others, exact time, quiet lines (in, can't
  make it, called off, happened, ended), collapsed ones behind a tap, the writer's own notice (held / behind a tap /
  taken down, amber). Writing box only when sending is allowed; otherwise one line (wait for an answer / closed / ended /
  paused). Every message passes `DraftNudgeDialogs` first; support line when the server suggests it. Read again on
  return and every 30 s while on screen (no push until N7). **Reply sheet = the same screen in start mode** (moment id
  + author name): the first message creates the private reply (`POST /private-replies`); a refusal (setting, pause) shows
  the server's words and keeps the words.
- **Moment page:** "Reply privately" when the server allows (`canReply`), "Your private reply" when one exists, "Open the
  plan" for its author and the people in it; the page re-reads itself quietly when it comes back.
- **Setting:** "Private replies" row in Settings and more (read first, then My people / Anyone at MIET / Nobody, saved
  only when changed).
- **Tests:** `RepliesTest` (17); `NavigationRulesTest` (three tabs, conversation is a task screen); `SocialTextsTest`
  scans `feature/replies` and the four new layouts; `SocialPalette` covers `replies`.

### A3 (2026-11-18): message moderation on the phone, moderator message cases, This happened, guidelines — Android

Status: **implemented, compiled, tested (Android 803 / 0, `gradlew --offline clean testDebugUnitTest assembleDebug`);
NOT manually verified.** Decision N-102.

- **Members:** `ReportTarget.MESSAGE` (the moment reasons; impersonation stays for people). On a conversation, touch and
  hold someone else's message → "Report this message" (the reporter is told only that a moderator will look; the writer
  is never told). The writer's own held / covered message shows its state in the moment words (`MomentCardBinder.
  noticeText`: the reason when there is one, the automatic check speaking as "our system") and a tap offers "Ask a
  moderator to look" (`POST /api/content-checks/review {MESSAGE}`) and, when the decision can still be appealed (read
  from "Your standing"), "Ask for a review". "Your reports" names "A message".
- **Moderators:** `ContentTarget.MESSAGE`; the case shows the message, then where it was written ("Written in a plan /
  private reply. Earlier messages, oldest first:" with case pseudonyms, the case's own line marked) and, for a held
  private reply, whether the two are connected (N-97). Moment cases show the follow-up ("More (the author's one
  addition)"). DTOs fail closed (lines without a pseudonym or words dropped; unknown kinds none).
- **This happened (N-92):** "This happened" on a plan's conversation when it is open, not called off, after its time
  (or without one) and nobody shared it yet (`RepliesLabels.canShareHappened`, the server's rule mirrored). It opens
  Create in its own mode (`ARG_HAPPENED_OF`): a Say, the plan's words shown, no kind or audience chooser, a photo taken
  now required, words optional; the request carries `happenedOf` and NO audience (the server uses the plan's), no
  question, nothing remembered as the next audience. The server's refusals (a My people plan by someone else, once per
  plan) show its words.
- **Guidelines:** a section "Private replies and plans" (turns, no read state, messages checked before delivery, how
  reporting works and what a moderator reads, nobody told they were turned down).
- **Tests:** `MessageModerationTest` (5), one This happened test in `CreateTest`.
