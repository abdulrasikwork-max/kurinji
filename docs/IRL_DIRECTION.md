# IRL direction (approved by the owner on 2026-11-15)

**Status: the direction is approved. N0 (this record) is done; N1 (strip) is approved and has its own plan in
`PHASE_N1.md`. Everything from N2 on is planned only: each phase needs its own plan and the owner's approval.**
This document replaces the place-first "experience platform" of 2026-10-22 (`EXPERIENCE_PLATFORM_DESIGN.md`, now
history). The previous `CLAUDE.md` is kept verbatim in `history/CLAUDE_2026-11-15.md`.

## 1. Why the direction changed

On 2026-11-15 the owner reviewed the product and rejected it as "a large college project with many features":

- **Ratings, "Been here", "Want to go"** asked people to do unpaid work for strangers, gave the author nothing back,
  and a claim of presence does not make an opinion trustworthy. Rejected by the owner.
- **Place-first** needed city-wide density before anyone got value, and the audit of the live pilot showed the place
  flow failing on a real phone (an empty place table forced "add a place", which needs a GPS position of 100 m or
  better; typed place names were uncontrolled).
- Concepts considered and rejected in the same review: "Proof, not reviews" (capture at a place: Instagram with a
  place name), "Together" (no posting alone: the owner wants the product to work for one person), "Home safe" (safety
  as the identity), physical proximity as the audience (worst for privacy and cold start).

What survived: self-expression to **the people who share your real place**, in a verified community, with one mechanic
that anonymous campus apps cannot copy: a post can become a real-life plan.

## 2. The product

**IRL is a verified, real-name community for self-expression. "Who's in?" turns a post into a real-life plan.
Safety is a quiet supporting layer. The first community is MIET Engineering College.**

### 2.1 Screens
| Screen | What it is |
|---|---|
| **Today** | The community wall: the last 24 hours, newest first, with an end. One pinned daily question. A "My people" filter. Screenshots blocked. |
| **✚ Create** | Opens ready to type; camera and microphone buttons; **no gallery**. Two choices only: audience (the community, or my people) and the "Who's in?" switch. |
| **Replies** | Everything sent to you (Same, Say more, I'm in, private replies) and your plan threads. |
| **You** | Real name, department, year; live moments and the few you kept; "made happen". Rows: My people, Safety, Settings. |

### 2.2 Moments
- **Say:** text, one photo, or up to 30 seconds of voice. Video is deferred.
- **Who's in?:** the same, plus an optional time and an optional plain-text "where" (a label, never a place record).
- Made now, inside the app. 24-hour life; a person can keep a few on their profile.

### 2.3 Responses (the whole interaction system)
| Response | Rule |
|---|---|
| **Same** | One tap. The author sees who; everyone else sees "a few" or "many", never a number. |
| **Say more** | One tap asking the author for a follow-up. |
| **I'm in** | Only on a "Who's in?". The author accepts each person. The list is visible to the author and the accepted people only. |
| **Private reply** | Goes only to the author. One per person per post until answered. Default: from my people only. |

No public comment threads, no upvotes, no ranking, no followers.

### 2.4 How a "Who's in?" plays out
1. "Chai at the canteen, 4 pm. Who's in?"
2. People tap I'm in; the author sees names and accepts.
3. Accepted people get one small thread for that plan; it closes a day after.
4. Afterwards anyone in it can add one photo; the wall shows "This happened" and it counts on each person's profile.
5. On acceptance, a quiet line offers "Tell your Safety Circle you're going".

### 2.5 Community and verification
- **Community over physical proximity** (owner, 2026-11-15): a post says only "MIET". No location is stored or shown in
  the social product; no presence, "last seen", viewer list, "nearby" or read receipt.
- **Communities are recognised, not created:** nobody owns one; no channels; roles are verifier, moderator and platform
  admin only. One community per person at launch. The request system for other institutions is deferred (design:
  a phone-verified person with that institution's ID requests it; a platform admin checks a public registry; it opens
  when about ten founding members are verified; a second requester joins the pending request; duplicates are merged).
- **Verification = a trusted person sees the college ID once** (no college e-mail exists at MIET):
  in person (a verifier taps approve) or by an uploaded photo (an admin approves; uploaders cover everything except
  name, photo, roll number and college name).
- **From zero:** the owner and the project teammates are root admins and the first verified members; they verify the
  first ~30 people in person and choose five to ten verifiers across departments, years and hostels, including women.
- **Kept:** verified status, who approved, the date, department, graduation year, and the roll number only in keyed
  form. The ID image is deleted at the decision. Membership ends at the graduation year.
- **Bans** attach to the roll number and the phone number; one account per roll number, one per phone number.
- **Real names** (owner's decision). **18+ only** (owner's decision; some first-years will have to wait).
- **Sign-in:** phone number and code only; no e-mail, no password (owner's decision).

### 2.6 Protection against stalking and targeting
- Nothing reveals where a person is except what they choose to post; times are rough ("this morning").
- Block makes two people invisible to each other.
- "This is about me" hides a post at once, before a moderator looks.
- Written rules, human moderators with a response-time promise, the Tamil / Tanglish text check on posts and replies.
- What cannot be prevented: the content itself (a photo of the library posted now tells the campus where you are).

### 2.7 Safety
- **Why a social app has safety:** IRL's purpose is to move people from the screen into real life. A product that
  sends people out to meet others owes them a quiet way to tell friends they got home, and an SOS that works without
  internet. Safety is the responsible half of "Who's in?", not a second product.
- **Kept:** SOS (countdown, starts without internet, Call 112), Safety Circle, basic Safe Walk. Reached from You, the
  app shortcut, the quick-settings tile and opt-in shake. Banners only while one runs.
- **Changed:** the Safety Circle message opens the SMS app; automatic `SEND_SMS` is removed (N6).
- **Retired from the app** (kept in git tag `pre-n1` and the phase docs for the viva): Nearby signals and their trust
  and moderation system (Phases 6, 7, 9), safety intelligence (Phase 8), route and stop checks (4C, in N6), the
  Evidence Vault and SOS audio (Phase 5, 17F audio), the Phase 9 evaluation work.

## 3. Decisions (all by the owner on 2026-11-15; reasons in `DECISIONS.md` rows N-1 to N-16)
Community over proximity · real names · ID-seen verification (no college e-mail) · 18+ · owner + teammates as root
admins · phone-only sign-in · SMS-app fallback · "Who's in?" as the hero mechanic · Firebase push · I'm-in list private
to author + accepted · wipe pilot data when the new schema lands · retire Phases 5 to 9 from the app · replace the
direction in `CLAUDE.md` · hosting stays the owner's laptop for the pilot · drop the retired tables (V32) · delete old
vault files from phones with a one-time notice · one local commit per unit, never pushed by the session.

## 4. Phases
| Phase | What |
|---|---|
| N0 | This record; `CLAUDE.md` sections 1 and 2 |
| N1 | Strip the retired features on both sides; migration V32 (`PHASE_N1.md`) |
| N2 | Phone-only sign-in, real name; the password leaves all six backend services that use it |
| N3 | Community, membership, verifier role, both verification doors, ban list, admin screens |
| N4 | Moments (Say, Who's in?), capture-only Create, the wall, 24-hour life and "keep", daily question |
| N5 | Same, Say more, I'm in, private replies, plan threads, Replies tab, "This is about me", moderation wiring |
| N6 | Safety trim: route / stop checks out, SMS-app fallback only, the Safety Circle offer on plans |
| N7 | Firebase push |
| N8 | Pilot launch at MIET: encrypted backups, SMS provider, privacy policy and grievance contact, seeding, moderators |

## 5. Known risks (not solved by design)
- **Real names and college authority:** a screenshot of a complaint can get a student disciplined; content will skew safe.
- **Women leaving** if private replies are abused; the defaults above exist for this.
- **One serious incident** (caste, religion, a photo of a classmate) can end the project.
- **Legal duties** as an intermediary and as a holder of ID data: to be checked before the pilot (N8).
- **Hosting:** a laptop on a phone hotspot serves a campus poorly; accepted by the owner for the controlled pilot.
- **Empty wall:** the first week decides it (one department, seeded posters, the daily question).
- **Category:** IRL is a campus social network like Fizz, Sidechat, CampusLoop, Spyll. What differs: verification
  without college e-mail, real names, no ranking, 24-hour life, private replies, and "Who's in?".
- **No test before building:** the owner chose to skip a manual two-week test; the first month at MIET is the test.
  Measures: people who post in a week without being asked; "Who's in?" posts that get at least one "I'm in".

## 6. Viva notes
- *Why did you change direction so late?* The pilot audit showed the place flow failing on a real phone and the core
  action (rating) rewarding nobody; evidence, not taste.
- *Why real names when every competitor is anonymous?* Anonymous walls cannot make anything happen in real life;
  "Who's in?" needs to know who.
- *Why not GPS proximity?* It tells every viewer you are within range; a membership tells them nothing.
- *How does the first student join with no inviter?* Root admins set on the server verify the first people in person.
- *Why keep safety in a social app?* Section 2.7.
- *What happened to Phases 5 to 9?* Retired from the app on 2026-11-15; the code is in tag `pre-n1`, the designs,
  tests and results are in their phase docs.
