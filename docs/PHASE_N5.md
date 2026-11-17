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
