# Phase 16: Kurinji UX reset and stabilization

**Date:** 2026-10-26 · **Status: implemented, compiled, tested, emulator-verified (API 37) for the flows in §6.
Physical phone, TalkBack speech by ear and real external e-mail delivery are NOT VERIFIED (§8).** No migration (next
stays V26). Backend: one read-only field and one Edition section (no new endpoint, no new table).

## 1. Decisions (asked once, answered by the user on 2026-10-26; recorded in CLAUDE.md §1, §10, §13)

| # | Question (conflict with a permanent rule) | Answer |
|---|---|---|
| U1 | Public reaction/comment counts? | **Comment count only**: the number of comments the viewer can read. No like/save/view/follower numbers |
| U2 | A "trending" surface? | **"Talking now"**, ordered by the newest readable comment, never by totals |
| U3 | Infinite scroll? | **Auto-load the next page, but the feed always ends** ("You're all caught up") |
| U4 | Red brand vs "red = emergency only"? | **Red brand; emergency UI recognised by shape and words**: black surface, thick red ring, siren icon, the word SOS/Emergency |

## 2. What was wrong (inspection)

- The UI read like a printed journal: masthead, serif headlines, "postmarks", "tickets", kolam drawings, editorial
  tabs; cards were "headline → paragraph → metadata → For the next person". A first-time user had to learn invented
  words (Cocoon, perspectives, Edition, butterfly it, postmark, grid TF31FW).
- The post page showed photos as **120 dp thumbnails**; report/delete buttons sat in the content; the comment box was
  at the very bottom of a long page; a comment could not be sent until a "kind" chip was chosen (no default).
- Discover was split into tabs with three nested lists inside a scroll view and a manual "Show more".
- Saving required opening each post; nothing could be done from a card.
- Location (social screens): four copies of the same "wait for a fresh fix" loop with a 20 s timeout; location
  switched off looked like a generic "unavailable" after waiting; a permanent "Don't allow" left the person with a button
  that did nothing; no fallback without Google Play services; no use of a recent cached position.

## 3. What was built (Android, `docs/DESIGN_SYSTEM.md`)

- **Identity:** red #D0102A / near-black / off-white, dark mode; Mukta Malar Bold headlines (Young Serif removed);
  pill buttons; sentence-case labels. **Icon** "Go-K" (concepts and choice: `docs/design/kurinji-16-icon.md`; no iOS
  target exists). **Launch:** red system splash with the K drawing itself (Android 12+), then "Go. See. Tell." word by
  word, inside the existing minimum time; nothing moves with animations off.
- **Feed card** (`CardBinder`): avatar (`AvatarView`), name, kind · time, photo, the post's own words, place and
  "Tip inside" chips, **Save** (butterfly, from the feed), **Comments** (count, U1), **Why?** on Discover picks.
- **Discover** (`DiscoverFeed`, `DiscoverFeedAdapter`, one RecyclerView): Near me + category pills → Talking now
  (sideways) → New in the city with a question after every third post → theme of the week → From people you know
  (next page loads near the end) → "You're all caught up" + Share yours. Search and "Near me" open a panel; back closes it.
- **Post page:** person first with a ⋮ menu (report / delete / place), photos edge to edge in their own shape (16:9
  to 4:5, swipe), every word, place/when chips (the place opens its page), a Tip box, Save / Going there? / I've been,
  "Comments · N", comment rows with avatars, and the comment box pinned at the bottom with a default kind.
- **Saved** (the Cocoon, same API): Want to go / Been there pills, rows with photo or initial, I've been (a bounce),
  Going there?, Remove. **You:** avatar header. **Words:** Save, Saved, Comments, Tip, Question, Talking now.
- **Location** (`core/location/OneFix`, `LocationProvider.currentFix`, `core/ui/LocationHelp`): one shared one-shot
  read; the fused provider's `getCurrentLocation` (a cached fix ≤ 2 min returns at once for listing an area; ≤ 30 s when
  a place may be added), 15 s timeout, platform `LocationManager` fallback without Play services; distinct outcomes
  (needs permission / location off / not found / unavailable) each with one button that fixes it (allow, open app
  settings after a permanent refusal, turn location on, try again, use precise location); coming back from settings
  retries. Discover "Near me" sends only the 6-character area. SOS, Safe Walk and Community tracking were not changed.
- **Emergency treatment (U4):** `Card.Emergency` / `Button.Emergency` black with a 3 dp red ring and the siren; the
  SOS tile, active-SOS banner, SOS screen and Safe Walk "no response" card carry the siren; the SOS shortcut icon is
  a black disc with a red ring. The calm status ("Nothing running") is sky blue, never red (§6 finding).

## 4. Backend

- `PostResponse.commentCount` (U1): `CommentService.readable` (the list's own filter) with `Visibility.canSeeComment`,
  batch-loaded per list; equals the comment list's length for the same viewer.
- Edition section `TALKING` (U2): `PostRepository.talkedAbout` pre-filter, then the newest readable comment per post
  (`DiscoverService.talking`) and the pure `EditionPolicy.pickTalking` (newest first, cap 8, reasons IN_THIS_CITY +
  NEW_PERSPECTIVES). Docs: `docs/API.md` "Phase 16".

## 5. Tests

| Check | Result |
|---|---|
| Backend `mvn test` | **756 run, 0 failures, 2 skipped** (was 752; new `Phase16SocialApiTest`: count = readable list incl. hidden and blocked, Talking now order/window/hidden, pure ordering never by totals) |
| Android `testDebugUnitTest` | **712 run, 0 failures** (was 701) |
| Android `lintDebug` | **0 errors / 99 warnings** (was 95; +4 are pre-existing kinds, no unused resources) |
| Python moderation tests | not re-run: no Python change |
| Live API on MySQL (dev DB) | commentCount = list length (2/2); edition TALKING ordered by newest comment (the Ask with the later comment first) |

New or rewritten guards: `ExperienceCardTest` (feed card, CityAreas, avatar), `DiscoverTest` (feed order, auto-load
ends, save from feed and double tap, Near me sends only the area, connect prompt), `EmergencyTreatmentTest` (U4),
`ContrastTest` (+ emergency surface, avatars, splash, calm accent not red), `KurinjiTextsTest` (new line, no invented
vocabulary), `SocialTextsTest` (comment count allowed and named), `HonestyTextsTest`, `Phase15IntegrationFixesTest`,
`ExperienceViewModelsTest` (location off is its own notice).

## 6. Emulator walk-through (AVD `Medium_Phone_API_37.0`, backend on the dev MySQL, throwaway `emu16a/b@example.com`)

| # | Check | Result |
|---|---|---|
| W1 | Launch: red splash with the Go-K, then onboarding "See it first-hand" | PASS |
| W2 | Login: app tile + "Go. See. Tell." | PASS |
| W3 | Discover: pills, Talking now with "1 comment / 2 comments", New in Chennai cards with photo, chips, Save/Comments/Why | PASS |
| W4 | Save from the feed → "Saved" with the red butterfly | PASS |
| W5 | Comment button opens the post at "Comments · 2"; comment rows; pinned box with "Been here too" preselected | PASS |
| W6 | Post top: author row, ⋮, photo; **finding: fixed 4:5 cut the sides of a landscape photo** → photos keep their shape | FIXED |
| W7 | Feed end; **finding: "Find people you know" shown to a connected person** whose posts were already in the city list → prompt only when they shared nothing (test added) | FIXED |
| W8 | Saved tab: item with photo, I've been / Going there? / Remove | PASS |
| W9 | You: avatar header | PASS |
| W10 | Safety hub: SOS tile black with red ring + siren; **finding: "Nothing running" drawn in red** → calm sky accent (test added) | FIXED |
| W11 | Dark mode Discover; **finding: the Near me pin black on dark** → pills tint their icon | FIXED |
| W12 | Near me without permission → Android's dialog at once → "Only this time" → result within ~5 s (honest empty state) | PASS |
| W13 | Composer: "Share an experience", Experience/Question | PASS |

Not exercised on the emulator: the fix screenshots after W6/W10/W11 (rebuilt and unit-tested, not re-captured), the
Android 12 splash animation frame by frame, a running SOS, TalkBack by ear, 320 dp / 2.0x text, the platform location
fallback (the emulator has Play services).

## 7. Security and privacy review

- `commentCount` reveals nothing new: it is the size of a list the viewer can already fetch (same rule, tested with
  hidden and blocked writers). No other public number was added.
- "Talking now" uses comment times only to order, never returns them; Everyone posts only; mute/block via `forEdition`.
- Near me sends one 6-character area (~1 km) once per tap; no coordinates leave the phone; nothing is stored.
- Area posts now say "Around Chennai", computed on the phone from the area the post already shows.
- Emergency UI no longer relies on colour; social/safety isolation tests unchanged and passing.
- V25 e-mail verification unchanged; `docs/EMAIL_SETUP.md` explains provider setup (credentials only in `backend/.env`).

## 8. Not verified / limits

Physical phone (none was connected to adb); TalkBack speech by ear; real external e-mail delivery (needs the owner's
provider credentials; see `docs/EMAIL_SETUP.md`); the Play-services-free location path on a real device; low-end
performance of the feed. Comments still need a kind (Been here too / Question / Answer): the backend has no plain
"comment" kind; a default is now chosen so it never blocks sending.
