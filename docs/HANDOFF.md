# CHECKPOINT 2026-11-12 (about 14:00 IST): the owner APPROVED the mark (IRL-C10) and the opening direction (IRL-C11); committed locally, NOT pushed

**This is the newest checkpoint. The owner's words: "I approve the new ஐ logo and the new opening direction." No push. V31 stays BLOCKED until the pilot's backup AND a restore are set up and verified.**

- **Committed (local, `main`, commit `3cec88b`, one ahead of `origin/main`):** only the mark and the opening: the five mark drawables + the launcher icon comment, `fragment_splash.xml`, `SplashFragment.kt`, `SplashViewModel.kt`, `Motion.kt`, `dimens.xml`, the opening lines of `strings.xml`, their assertions in `KurinjiTextsTest.kt`, `docs/design/irl-icon.md` and the sheets in `docs/design/irl/`.
- **That commit does not build by itself:** the mark and the opening use the cherry / cream colours, the Fraunces font and the styles of the identity work, which are still only in the working tree with everything else since `ff250bd` (IRL redesign R1-R7, palette, trust step 18A-1, fast-start, deploy files, docs). The working tree as a whole builds and passes (Android 1069 / 0, backend 914 / 0 with 4 skipped). The owner has not yet asked for that larger commit.
- **Not committed, on purpose:** every file that mixes this work with the rest (`DECISIONS.md`, `DESIGN_SYSTEM.md`, `STATUS.md`, `HANDOFF.md`, `CLAUDE.md`): they record the approval in the working tree and go in with the larger commit.
- **Still open:** the launcher icon on the home screen grid and the themed icon (not seen), a trademark search, the Play Store 512 px icon; the backup + restore for the pilot; V31.

---

# CHECKPOINT 2026-11-12 (about 13:30 IST): new mark (the Tamil letter ai, IRL-C10) and new opening (IRL-C11), both waiting for the owner's review - working tree, NOT committed, NOT pushed

**(Superseded by the 14:00 checkpoint above: both were approved.) No commit or push without the owner's explicit approval. V31 stays BLOCKED until the pilot's backup AND a restore are set up and verified. The 13:00 checkpoint below is still right except for its mark (the Kolam butterfly was rejected by the owner as too complicated for an icon).**

- **Mark, IRL-C10 (built, NOT approved):** the Tamil letter "ai" (said exactly like the English "I"; a child's shout of delight; the old Tamil word for wonder), our own drawing as one line, one colour. Record, the dropped directions and the sheets: `docs/design/irl-icon.md`, `docs/design/irl/icon-round5-*.png`, `icon-ai-final.png`. Files: `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_brand_mark`, `ic_mark_on_ink`, `art_opening_meet`.
- **Opening, IRL-C11 (built, NOT approved):** one centred lockup every time (the mark writes itself, "IRL", "OUT THERE. TOGETHER."); a returning person moves on after 1000 ms (was 700); on the first run the lockup moves up and "YOUR WORLD. / YOUR PEOPLE. / YOUR FREEDOM." lands with "LET'S GO". Taken off the screen: the long supporting sentence and the small "IN REAL LIFE" (still spoken). Files: `fragment_splash.xml`, `SplashFragment.kt`, `SplashViewModel.kt` (the constant), `Motion.kt` (optional start delay and stagger), `dimens.xml` (2), `strings.xml` (`opening_brand_spoken` new; `opening_expansion`, `opening_supporting` removed), `KurinjiTextsTest.kt` (the pinned words changed on purpose).
- **Seen:** the returning opening on the A23 (this build is installed there); the first-run opening on the API 33 emulator (fresh data). Pictures: `docs/design/irl/opening-ai-*.png`. **Not seen:** the launcher icon on the home screen grid, the themed icon, the mark inside the app (Home head, You, empty states), dark mode, large text or a small screen with the new opening, TalkBack, the motion by eye (still frames only).
- **Tests (final tree):** Android **1069 run, 0 failures, 0 errors**; `assembleDebug` OK; lint **0 errors, 98 warnings** (2 more than before: not looked at one by one; `opening_soft` is now an unused colour). Backend not touched since the 13:00 run (914 / 0, 4 skipped).
- **Open for the owner:** (1) the verdict on the mark, above all whether a Tamil letter is right for a brand with an English name; (2) a Tamil reader's check that the drawing is a correct letter; (3) the verdict on the opening, its timing and the two removed texts; (4) a trademark search before any store release; (5) the backup + restore plan for the pilot, then V31; (6) the commit.

---

# CHECKPOINT 2026-11-12 (about 13:00 IST): Marina exact-place walk DONE, trust step 18A-1 verified on real MySQL, new mark "the Kolam butterfly" - working tree, NOT committed, NOT pushed

**This is the newest checkpoint. No commit or push without the owner's explicit approval. V31 stays BLOCKED until the pilot's backup AND a restore are set up and verified (the owner's instruction, 2026-11-12).**

- **"Been here" on an exact place: manually verified on the A23** (light mode, live pilot; `docs/IRL_REDESIGN.md` 3.3): the exact-place Marina post shows "Been here" → the "How was it?" sheet → rating 4 → Done → open again (still 4) → Take back → 4 again → the place page reads "You've been here · 4 out of 5", lists the post, shows no public average (expected from one rater). **No defect.** Noted, not changed: the POST page still says "Been here" after rating (only the place page shows the state). Left on the pilot: the owner's visit to Marina Beach, rating 4.
- **Trust step 18A-1:** its two queries (`raters`, `acceptedAmong`) are **verified on real MySQL 8.0.46** (the Windows DEVELOPMENT database, schema V30, read-only; new opt-in test `MySqlPlaceRatersQueryTest`: 2 run, 0 failures). **Found:** `RatingPolicyTest` did not compile (two methods with one name), so the "911 run" of the 05:20 checkpoint was not the final tree; fixed by renaming one method. Still NOT on the pilot (old jar); the pilot's MySQL was not touched.
- **The owner rejected the mark "Out there"** (ordinary, generic). **New mark, IRL-C9, "the Kolam butterfly"** (built, NOT yet approved): one unbroken cream line, drawn like a kolam at a doorstep, goes round four blush dots; its loops are a butterfly in flight. Meaning, the directions considered and the sheets: `docs/design/irl-icon.md`, `docs/design/irl/icon-round4-*.png`, `icon-kolam-butterfly-final.png`. Files: `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_brand_mark`, `ic_mark_on_ink`, `art_opening_meet` (the line draws itself, about 640 ms). **Seen on the A23 (light mode, this build is installed):** the Home head and the opening screen. **Not seen:** the launcher icon on the home screen grid, the themed icon, dark mode, the first-run opening.
- **Tests (final tree, all re-run this afternoon):** backend **914 run, 0 failures, 0 errors, 4 skipped** (the two opt-in real-MySQL classes); Android **1069 run, 0 failures, 0 errors**; `assembleDebug` OK; lint **0 errors, 96 warnings**. Python not touched, not re-run.
- **NOT done, on purpose:** 18A-2 / V31 (blocked, above); the pilot's backups are still paused and no restore has been rehearsed; nothing deployed to the pilot; no commit.
- **Open for the owner:** (1) the verdict on the Kolam butterfly (look at the launcher icon on the home screen too); (2) if kept: should the "Inspired" reaction icon be redrawn in the same line, and a trademark / similarity check before any store release; (3) the backup + restore plan for the pilot, then V31; (4) the commit.

---

# CHECKPOINT 2026-11-12 (about 05:20 IST): new mark "Out there", trust step 18A-1 built, Marina exact-place post created - working tree, NOT committed, NOT pushed

**(Superseded by the 13:00 checkpoint above: the reminder is done, the mark was rejected.) The 05:00 one below is still right except for its mark (replaced) and its "still blocked" line (the post now exists).**

- **REMIND THE OWNER WHEN THEY RETURN (they asked for this; the post is visible to others from 05:47 IST, 2026-11-12):**
  1. On the A23, as the main account, open the Marina Beach **exact-place** post (Home → New in Chennai, or the "Marina Beach" place page). It is a second Marina post: the older one is city-level and its button says "Share yours".
  2. Tap **Been here** (the button must say that on this post).
  3. In "How was it?" pick a number, Done; then open it again: Take back my rating / change it.
  4. Open the place page ("See this place" in the post's menu, or the place chip): the post is listed, the button reads "You've been here · N out of 5", and **no public average is expected** (it needs 5 independent groups of raters; the owner is one).
  Then tell the session what the screen showed; fixes, docs and `docs/IRL_REDESIGN.md` follow.
- **The owner's feedback (2026-11-12):** light and dark mode both checked by the owner: "work perfectly"; Black Cherry + Cream Vanilla stays; the two-path pin mark was rejected as generic.
- **New mark "Out there" (IRL-C8):** a cream ring open at one side and a blush dot that has stepped out. Files: `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_brand_mark`, `ic_mark_on_ink`, `art_opening_meet` (the ring draws around the waiting dot, the dot steps out). `docs/design/irl-icon.md` has the geometry. **Seen on the A23** (opening screen and the Home head, dark mode, display filter off). **The owner has not seen or approved this mark yet.** Not seen: the launcher icon on the home screen, light mode.
- **Trust step 18A-1 built (backend only; decisions P18-1 to P18-3; `docs/PHASE18_TRUST_PLAN.md` "as built"):** connected raters are one voice, 5 independent groups needed, a new (< 30 d) or one-place (< 3 rated places) account counts half. No schema, API or Android change. **Not on the pilot:** the live server still runs the old jar. **Not verified on real MySQL** (the new query has a subquery in its select list; H2 only).
- **Tests (final tree):** backend **911 run, 0 failures, 0 errors, 2 skipped** (the opt-in real-MySQL checks); Android **1069 run, 0 failures, 0 errors**; `assembleDebug` OK; lint **0 errors, 96 warnings**. Python not touched, not re-run.
- **NOT started, and why:** 18A-2 (presence + disclosure) needs migration **V31**. A migration must be verified on real MySQL after a backup, and this laptop hosts the live pilot database whose backups are still paused (risk noted in the 01:15 checkpoint): the owner decides where and when that runs. 18A-3 and 18B follow it.
- **Exact next step:** the four reminder points above; the owner's verdict on the new mark; then either the commit (a lot is uncommitted) or the go-ahead for V31 with a backup plan.

---

# CHECKPOINT 2026-11-12 (about 05:00 IST): new social identity built (Black Cherry + Cream Vanilla, Fraunces, new mark, 700 ms opening, "Been here", "Want to go") - working tree, NOT committed, NOT pushed

**The owner approved each item on 2026-11-12. Android + docs only: backend, database and the live server were not changed. Decisions IRL-C1 to IRL-C7 (`docs/DECISIONS.md`); spec note at the top of `docs/DESIGN_SYSTEM.md`.**

- **Built:**
  1. **Social palette, isolated:** `ThemeOverlay.IRL.Social` (`c_*` colours, day + night) worn only by the screens `core/ui/SocialPalette` names (social, profile, settings, account, auth, onboarding); `ViewBindingFragment` gives them a themed context; `MainActivity` colours the shared page and toolbar per destination; the bottom bar carries the overlay. Every `k_*`, `ss_*`, `hero_*` and emergency value is unchanged (pinned by the new `SocialPaletteTest`).
  2. **Type:** Fraunces SemiBold (`res/font/fraunces_semibold.ttf`, `irl_serif`; licence `docs/licences/Fraunces-OFL.txt`) for headlines and the wordmark, on ALL screens (safety screen titles too: type only, no colour); safety hero text and the opening capitals stay Mukta Malar.
  3. **Mark and icon ("the Meet", second drawing):** `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_launcher_background`, `ic_brand_mark` (theme attributes), `ic_mark_on_ink`. `docs/design/irl-icon.md` still describes the first drawing (NOT updated).
  4. **Opening:** plain cherry launch screen (`splash_blank`; `splash_mark` drawables deleted), `art_opening_meet` draws the mark once (about 700 ms), `SPLASH_MINIMUM_DURATION_MS` 1200 → 700, words + button only on the first run.
  5. **Words:** "Been here" / "You've been here" / "I haven't been here"; the list "Want to go" (string names unchanged).
  6. **Plan only:** `docs/PHASE18_TRUST_PLAN.md` (18A trust rules, then 18B Next-person card). Needs the owner's approval before any code; 18A-2 would be migration V31.
- **Tests / build / lint (final tree):** Android unit tests **1069 run, 0 failures, 0 errors** (+6 `SocialPaletteTest`); `assembleDebug` OK; lint **0 errors, 96 warnings**. Backend and Python not touched, not re-run.
- **Seen on the A23 (dark mode, text 0.8; this build is installed):** the opening (mark + lockup on cherry), Home, a post ("Share yours" on the city-level Marina post), You ("Want to go" row, cherry panel), and Safety (still ink, lime and red). **The phone had a warm display filter on (4:40 at night), so every screenshot is shifted towards orange: the true hue of cherry and cream was NOT judged on a screen.**
- **NOT verified:** light mode (the cream page) on any screen; the first-run opening with its words; login / onboarding / composer / Explore / place page / dialogs and sheets in the new palette; the launcher icon on the home screen; large text, 320 dp, TalkBack; Tamil text in a Fraunces headline; "Been here" on an exact place (still blocked, below).
- **Still blocked:** the exact-place Marina Beach post. The session's permission check refuses the live write; the owner can run the prepared script (it was written to the session scratchpad, which does not survive; ask for it again) or post from a second phone. Then steps 3-6 of section 9 below.
- **Exact next step:** (1) the owner looks at the build on the A23 in light AND dark mode with the display filter off and says keep / adjust; (2) the Marina exact-place post, then "Been here" → rating → place page; (3) the owner reviews `docs/PHASE18_TRUST_PLAN.md`; (4) update `docs/design/irl-icon.md`; (5) the commit (owner's approval).

---

# UPDATE 2026-11-12 (later the same day): "I went" showed no rating - cause found, button wording fixed (IRL-R14) - NOT committed, NOT pushed

- **The owner's report:** "I went" on the Marina post showed no rating. **Cause (read from the live pilot as the friend):** the only Marina post (`9728382d…`) is CITY precision (Chennai) with no place, so "I went" opened the composer, as designed since 17G-2. The exact-place post of step 2 below was **never created**; the place page of "Marina Beach" still has no post, no rating, no visit.
- **Fixed (Android only, IRL-R14):** the post page's button says "I went" only on an exact place; otherwise, and always in Saved, it says "Share yours" (it opens the composer). Files: `PostFragment.kt`, `item_saved.xml`, `strings.xml` (2 spoken labels), `IrlRedesignTest.kt` (+1), `DECISIONS.md`, `IRL_REDESIGN.md`, `.claude/rules/android-social.md`.
- **Tests / build:** Android unit tests **1063 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK. Lint NOT re-run. The new APK is **NOT installed on the A23**; the new wording is NOT seen on a screen.
- **Still NOT verified:** the rating sheet and the place page on an exact place. Step 2 below (the friend's exact-place post) was attempted from the session and **refused by the session's permission check** (a write to the live pilot); the owner creates it or allows it. Steps 2-6 below are unchanged and still the next step.
- **Waiting for the owner (nothing built):** the product positioning review and the Black Cherry / Cream Vanilla palette question (answered in the session, no code).

---

# CHECKPOINT 2026-11-12 (about 01:15 IST) - COMPLETE: IRL two-account social testing - working tree, NOT committed, NOT pushed

**STOP MARKER (the owner stopped here on purpose): resume from "9. Exact next step". No commit or push without the owner's approval. No further code change was made after the results in section 4. This section replaces the two shorter 2026-11-12 checkpoints below it (kept as history).**

## 1. Second test account (live pilot `https://api.irlnow.in`)
- **Created 2026-11-11 19:09 UTC with the owner's approval**: name "Test Friend", e-mail `test.friend@irlnow.in`, handle `test_friend`, **user id 3**, profile id `94fb9c30-f665-4210-b135-82531d3a3b98`, date of birth 2000-01-01, city Chennai, phone ending 3390 (the owner's second number), **phone verified** with ONE real SMS code (Fast2SMS) that the owner read out. No other SMS was sent.
- **Password:** only in `C:\Users\ABDUL RAFIQ\.irl-pilot-test-account.txt` (outside the repository). Never print it.
- **How it is driven:** from the laptop through the API (`POST /api/auth/login` with the file's e-mail and password gives a token); the A23 stays signed in as the owner. The access token used in the session was deleted from the scratchpad; the friend's server session was NOT signed out (it lapses by itself).
- **Connected** with the owner (`rasik`, user id 2, profile id `1f8223aa-eca5-4d42-946e-2441420ba092`): the friend asked, the owner's account accepted on the A23.
- **New-account limits seen:** the fourth post of the day was refused with 429 "You have shared a lot today. Please try again tomorrow." (probation limit; when exactly it resets was NOT checked, and the limiter is in memory, so a backend restart also resets it). Photo posts from this account will be held for a moderator for its first 72 h (rule NEW_ACCOUNT_PHOTOS; not tried).

## 2. Two-account social testing: done on the A23 (Android 14, dark mode, text 0.8)
Verified by reading the screen (UI dump + screenshots), as the owner on the A23, with the friend acting through the API:
- **Connecting:** "Requests for you" row, the person's profile ("Wants to connect with you" → Accept → "Connected", Remove connection / Mute / Block / Report shown).
- **Home:** another person's posts under "New in Chennai" (experience + question), "From people you know" (the Connections-only post), "Talking now" (after comments), the "You're all caught up" end card; re-tap refresh.
- **Marks:** butterfly and Save from a card (word, fill and spoken label change); the same marks on the post page; You → Saved lists the saved post.
- **Comments:** the owner's comment on a question is sent as ANSWER (picked by `PostViewModel.kindFor`), the friend's reply is indented under it, "This helped" shows; "Comments · 2"; a comment on an experience; the card's count ("2 comments", "1 comment").
- **Person profile with posts:** the head scrolls away, the three posts list (this was the "NOT verified for the person profile" point of the 2026-11-11 evening checkpoint; now seen).
- **"I went" on a post without an exact place:** opens the composer (as designed).
- **Earlier the same night (R6):** Home remembers the chosen city after a forced stop; one empty message; "Keep it just for me".

## 3. Defects found and fixed in this work (all Android, all re-checked on the A23 after a reinstall)
| # | Found | Fix | Decision |
|---|---|---|---|
| 1 | Home asked "Choose a city" at every start | `HomeCityChoice`: the person's own city code kept per account in the `viewer_preferences` DataStore | IRL-R6 |
| 2 | Two empty messages stacked on Home | `DiscoverFeed.build`: "find people" only under posts | IRL-R7 |
| 3 | "Share with Only me" | button "Keep it just for me" (`social_share_only_me`) | IRL-R8 |
| 4 | Dead "Why?" code; a comment claimed About explained the order | code and 12 strings removed; the sentence added to `about_body` | IRL-R9 |
| 5 | Back on Home after a comment or mark, the card was stale | `PostViewModel` calls `FeedFreshness.changed()` after a confirmed mark or comment action | IRL-R10 |
| 6 | "I went" / "Going there?" under a question | row `goingRow` in `fragment_post.xml`, experiences only | IRL-R11 |
| 7 | The bottom bar sat on top of the open keyboard | `MainActivity`: bar = wanted on this screen AND keyboard closed | IRL-R12 |
| 8 | A commenter's photo was dropped (initial shown) | `CommentDto.toDomain` keeps the photo key | IRL-R13 |
(From the first A23 walk, 2026-11-11: video label in dark mode, "Go to Home", Home tab icon, own-name label and Back loop: IRL-R5 and `docs/IRL_REDESIGN.md` section 3.)

## 4. Test / build / lint results (final tree; run after the last code change)
- Android unit tests: **1062 run, 0 failures, 0 errors, 0 skipped** (`testDebugUnitTest`). New in this work: 6 tests in `IrlRedesignTest` (11 → 17 total there: city choice, Home in the remembered city, private-note button, commenter photo, feed freshness from the post page, question row + keyboard bar); `DiscoverTest` (empty Home) and `Phase17G3SmartUnderstandingTest` (what the DataStore may hold) each had one test changed on purpose.
- `assembleDebug`: OK. The APK on the A23 is this final build.
- Lint (`lintDebug`): **0 errors, 96 warnings**, no unused resource.
- Backend and Python: NOT touched, NOT re-run. Only docs changed after these runs.

## 5. Remaining unverified
- **"I went" + rating + place page on an exact place** (the next step).
- A photo or video post from another person in Home (72 h hold for the new account); media upload in the new composer.
- Mute, block, remove connection, report, decline, invite codes, today's state (not tapped: they change the live relationship).
- The About paragraph on a screen; "find people" under posts (the owner now has a connection, so it no longer appears); a second account on the SAME phone not inheriting the Home city (unit-tested only).
- Light mode, large text (2.0x), a 320 dp screen, TalkBack by ear, log out, the emulator.
- Still open from 2026-11-11: the two fast-start checks (evening checkpoint, section 6).

## 6. Live pilot data that remains (nothing was deleted)
- **Account** user id 3 with its profile, its device session, and the connection owner ↔ friend.
- **By the friend:** post `9728382d-0a26-418d-a8c1-2cbd0155d9f6` (Marina at sunrise, Everyone, city Chennai, with a tip); post `e15e788d-1326-4c5e-86de-f584cba4f45c` (question "quiet place to read", Everyone); post `3cec58fc-6856-46e5-b792-5b87be41d6fe` (filter coffee, Connections, no place); one reply ("Thank you, going on Thursday."); "This helped" on the owner's answer; public place **"Marina Beach"** `e61232e0-55ed-4bf2-821e-cbcc97dabf7d` (cell `tf344ze`, Chennai) with NO post yet.
- **By the owner's account (taps made by the session on the A23):** the accepted request; a butterfly and a Save on the Marina post; comment `d33eca14-0f87-422a-ac9d-7e40d2438588` ("Anna Centenary Library, third floor.") on the question; a comment "Is the parking open that early?" on the Marina post.
- **On the A23:** the owner's Home city is now Chennai (chosen by the session; change it with the city name in the Home head). The composer's remembered audience is still "Only me".

## 7. Uncommitted work (HEAD is still `ff250bd`)
Everything since the D12 commit is only in the working tree: the 2026-11-11 backend fast-start + upload timeout work, the deploy files, and the whole IRL redesign R1-R7. This work added: NEW `feature/social/domain/HomeCityChoice.kt`; DELETED `feature/social/ui/DiscoverTexts.kt`; MOD `AppContainer.kt`, `MainActivity.kt`, `ViewerPreferences.kt`, `DataStoreViewerPreferences.kt`, `ExperienceDtos.kt`, `ComposeFragment.kt`, `DiscoverFeed.kt`, `DiscoverFeedAdapter.kt`, `DiscoverFragment.kt`, `DiscoverViewModel.kt`, `ExperienceCardAdapter.kt`, `PostFragment.kt`, `PostViewModel.kt`, `fragment_post.xml`, `item_experience_card.xml`, `strings.xml`; tests `IrlRedesignTest.kt`, `DiscoverTest.kt`, `Phase17G3SmartUnderstandingTest.kt`; docs `IRL_REDESIGN.md` (sections 3.1, 3.2), `DECISIONS.md` (IRL-R6 to R13), `STATUS.md`, `PHASE13C.md`, `HANDOFF.md`, `CLAUDE.md` (39.8k characters), `.claude/rules/android.md`, `.claude/rules/android-social.md`.

## 8. Risks and notes
- **A large amount of work is uncommitted** (several days, backend + Android + deploy + docs). A commit needs the owner's approval; until then the working tree is the only copy.
- **Backups are still paused** (timer not enabled, 2026-11-11 evening checkpoint): the standing gate was "no friend signs up until backups are verified". The test account was created with the owner's approval and holds only test data, but the live database now has new rows that no verified backup covers.
- **The pilot's internet is the A23's own hotspot** (2026-11-11 finding): every request from the phone crosses its mobile link twice; slow answers during a walk are the line, not necessarily the app.
- **IRL-R10 costs one extra Home read** after each mark or comment made on the post page (wanted, but it is more traffic on that slow line).
- **adb taps on the owner's phone:** the session checks the foreground window and call state before every tap since a call took two taps on 2026-11-11 (`t.sh` in the session scratchpad did this; it is not in the repository). The owner was asked to glance at the call log and sound mode after that incident; not confirmed yet.
- **The test account stays on the pilot** until the owner says otherwise; removing it later = account deletion (7-day grace) or the owner's own SQL.
- The friend's daily post limit: reset time not checked (section 1).
- Comments print "Shared this week" after the name (shared string; noted in `IRL_REDESIGN.md` section 4, not changed).

## 9. Exact next step (resume here)
1. Sign in as the friend from the laptop (`POST /api/auth/login`, credentials from the file in section 1; do not print them).
2. As the friend, `POST /api/posts` ONE experience with `precision: "EXACT"`, `placeId: "e61232e0-55ed-4bf2-821e-cbcc97dabf7d"` (Marina Beach), audience Everyone, a month and a daypart. If it answers 429 again, the daily limit has not reset: wait, do not work around it.
3. Read `visibleFrom` in the answer and **wait for the 1-3 h delay** (others cannot see an exact-place post before that).
4. On the A23 as the owner (check the foreground window and call state before every tap): open the post from Home → **"I went"** → the **"How was it?" rating** sheet (pick a number, Done; then Take back / change) → **"See this place"** → the **place page** (posts list, "I went" state, no public average is expected: it needs 5 raters).
5. Fix what the screen shows, re-run tests / build / lint, re-check on the A23, update `docs/IRL_REDESIGN.md` and this file.
6. Then: the light mode + large text + TalkBack pass; the owner's decisions in `docs/IRL_REDESIGN.md` section 4; the commit (owner's approval).

---

# CHECKPOINT 2026-11-12 (about 01:00 IST): second test account on the live pilot, social walk on the A23, 4 defects fixed (R7) - working tree, NOT committed, NOT pushed

**No commit or push without the owner's approval. Android + docs only; backend code, database schema and server configuration were not changed. Live DATA was changed on purpose (below).**

- **Live data created (the owner approved; one real SMS code, read out by the owner):** account `test.friend@irlnow.in`, user id 3, handle `test_friend`, phone ending 3390, connected with the owner (`rasik`, user id 2). Its password is in `C:\Users\ABDUL RAFIQ\.irl-pilot-test-account.txt` (outside the repository, never printed). By the friend: 3 text posts (Marina experience for Everyone, a question, a filter-coffee experience for Connections), 1 reply, 1 "This helped", 1 public place "Marina Beach" (`e61232e0-55ed-4bf2-821e-cbcc97dabf7d`). By the owner's account through the A23: the accepted request, a butterfly and a Save on the Marina post, 2 comments. Nothing was deleted.
- **Verified on the A23 (dark mode, text 0.8):** see `docs/IRL_REDESIGN.md` section 3.2: another person's posts in Home, "Talking now", butterfly, Save, Saved, comments (answer, reply, "This helped"), person profile (request → Accept → Connected, posts).
- **Found and fixed (decisions IRL-R10 to IRL-R13), each re-checked on the A23:** Home card stale after a comment or mark; "I went / Going there?" on a question; bottom bar on top of the keyboard; a commenter's photo dropped.
- **Tests / build / lint (final tree):** Android unit tests **1062 run, 0 failures, 0 errors, 0 skipped**; `assembleDebug` OK; lint **0 errors, 96 warnings**. Backend and Python not touched, not re-run.
- **NOT verified:** "I went" with a rating on an exact place; a photo/video post from another person (72 h hold for the new account); mute, block, remove connection, report, decline, invite codes, today's state; plus light mode, large text 2.0x, 320 dp, TalkBack by ear, media upload in the new composer, log out, emulator.
- **Exact next step:** once the friend's daily post limit has reset (signing in again as the friend needs the password file above): share ONE exact-place post at "Marina Beach" as the friend, wait for its 1-3 h delay, then on the A23 open it and test "I went" + the rating sheet + the place page. Then the light mode + large text + TalkBack pass, then the commit (owner's approval). Open owner decisions: `docs/IRL_REDESIGN.md` section 4; whether the test account stays; the two fast-start checks (evening checkpoint of 2026-11-11, section 6).

---

# CHECKPOINT 2026-11-12 (after midnight): IRL redesign R6 - the walk's open points resolved, docs and rules brought up to date - working tree, NOT committed, NOT pushed

**No commit or push without the owner's approval. Android + docs only: backend, database and the live server were not changed, nothing was posted. No second test account was created (the owner said not yet).**

- **Done (the owner asked for these to be resolved autonomously; record: `docs/IRL_REDESIGN.md` section 3.1, decisions IRL-R6 to IRL-R9):**
  1. **Home city:** remembered on the phone per account (`HomeCityChoice`, "account id:city code" in the `viewer_preferences` DataStore); Home uses it first, then the profile's catalog city, then asks once. This changes the 13C sentence "the chosen city is not saved"; it is the person's own setting, nothing about other people, never sent.
  2. **Empty Home:** one message only ("Choose a city", or the empty week with "Share yours"); "find people" appears only under posts.
  3. **Private note:** the composer's button says "Keep it just for me" for Only me.
  4. **Clean-up:** the dead "Why?" code (card button, dialog, `DiscoverTexts.kt`, `FeedActions.why`, `CardRow.onWhy`, 12 strings) removed; the sentence about the feed order is now a paragraph of About.
- **Tests / build / lint (final tree):** Android unit tests **1059 run, 0 failures, 0 errors, 0 skipped** (1056 + 3 in `IrlRedesignTest`; `DiscoverTest` and `Phase17G3SmartUnderstandingTest` each had one test changed on purpose: the empty Home, and what the DataStore may hold); `assembleDebug` OK; lint **0 errors, 96 warnings**, no unused resource. Backend and Python not touched, not re-run.
- **Seen on the A23 (dark mode, text 0.8, live pilot):** Home with no city shows one message; after choosing Chennai and a forced stop Home opens on Chennai without asking, with only the empty-week message; the composer's button reads "Keep it just for me". **The owner's Home city on the A23 is now Chennai.** The foreground window and call state were checked before the taps; nothing else was touched.
- **NOT verified:** the new About paragraph on a screen; "find people" under posts; a second account on the same phone not inheriting the city (unit-tested only); and everything still open from the checkpoint below (light mode, large text 2.0x, 320 dp, TalkBack by ear, others' posts, comments, media upload in the new composer, log out, emulator).
- **Docs and rules:** `docs/IRL_REDESIGN.md`, `docs/DECISIONS.md` (IRL-R6 to R9), `docs/STATUS.md`, `docs/PHASE13C.md` (one note), `.claude/rules/android-social.md` (current entry points + an IRL paragraph; old notes corrected), `.claude/rules/android.md`, `CLAUDE.md` (**39.8k characters, under its 40k limit again**: the "Real-phone testing over USB" and "Emulator" gotchas moved verbatim to the end of `.claude/rules/android.md`).
- **Still open, the owner decides** (`docs/IRL_REDESIGN.md` section 4): no comment box on an only-me/held post; Explore keeps its starting city; the backend-dependent items; onboarding and sign-up shortening.
- **Exact next step:** create the **second test account** on the live pilot (the owner's go-ahead is needed; a new account needs a phone OTP - no SMS is sent unless the owner types SEND, so decide first how the code is obtained) and connect it with the owner's account; then walk on the A23: Home with another person's post, "find people" under posts, a comment, a person profile, the marks. After that: the light mode + large text + TalkBack pass, then the commit (owner's approval). The two fast-start checks (evening checkpoint of 2026-11-11, section 6) are still open.

---

# CHECKPOINT 2026-11-11 (late night): IRL redesign walked on the A23, 4 defects fixed, lint clean-up, docs written - working tree, NOT committed, NOT pushed

**No commit or push without the owner's approval. Android + docs only in this session: backend, database and the live server were not changed** (one private "Only me" text post was created through the app for the walk and deleted again).

- **Done:** `assembleDebug` OK; installed on the A23 (R5CW42E9RQW, `--user 0`, points at `https://api.irlnow.in/`); walked Home, Explore, composer, post, Your posts, You, Saved, Safety, Settings and more in dark mode at text size 0.8. Full record: **`docs/IRL_REDESIGN.md`** (section 3 = what the screen showed and the fixes, section 4 = seen but left for the owner).
- **Fixed after the walk:** the video label "▶ 0:03" was unreadable in dark mode (new colour `k_on_video_badge`); "Explore Discover" → "Go to Home" on the empty Saved screen; new Home tab icon `ic_nav_home`; your own name on your post now says "Opens your profile" and goes back to You instead of stacking a second one; 29 unused strings, `item_safety_tile.xml` and style `Widget.Kurinji.Card.Tile` removed (`SocialTextsTest` file list updated). All four fixes were seen on the A23 after a reinstall.
- **Tests:** Android unit tests **1056 run, 0 failures, 0 errors, 0 skipped**; lint **0 errors, 96 warnings**, no unused resource. Backend and Python not touched, not re-run.
- **Docs:** `docs/IRL_REDESIGN.md` (new), `docs/DECISIONS.md` rows IRL-R1 to IRL-R5, `docs/STATUS.md`, `docs/README.md`, `CLAUDE.md` (sections 1, 2, 5), `.claude/rules/android.md` (navigation line). **`CLAUDE.md` is now about 40.6k characters, over its own 40k limit (it was 40.2k before this session): something has to move out.** `.claude/rules/android-social.md` still describes 13C/17E entry points (Explore "entry: Discover head", switch "on Connections"); not rewritten.
- **NOT verified:** light mode, large text (2.0x), a 320 dp screen, TalkBack by ear, a feed with other people's posts, writing a comment (the only public post is held), photo/video upload in the new composer, log out, the emulator.
- **During the walk a phone call arrived on the A23** and two automated taps landed on the call screen instead of the app (the call had already ended; the phone showed "Call ended", later a missed-call mark and the mute icon). Nothing was dialled by the session; the owner should glance at the call log and the sound mode.
- **Exact next step (the owner decides each):** (1) the points in `docs/IRL_REDESIGN.md` section 4: remember the Home city on the phone or set a catalog city in the profile; one empty state on Home; "Share with Only me" wording; (2) a second account so the feed, comments, a person profile and marks can be seen with real posts; (3) light mode + large text + TalkBack pass; (4) trim `CLAUDE.md` below 40k; (5) commit; (6) then onboarding and sign-up shortening and the backend-dependent items (section B below). The two pending fast-start checks (evening checkpoint, section 6) are still open.

---

# CHECKPOINT 2026-11-11 (night): IRL social redesign, units R1-R5 in the working tree - NOT committed, NOT pushed (steps 1-3 of its section F were done later that night: see the checkpoint above)

**STOP MARKER (the owner stopped here because the usage window ended): continue from "Exact next step" below. No commit or push without the owner's approval. Android only: the backend, the database and the live server were NOT touched in this work.**

## A. What was inspected (read-only, before any change)
`docs/HANDOFF.md`, `CLAUDE.md`, `.claude/rules/android*.md`; the navigation graph, bottom bar and `MainActivity`; every main social screen and its layout (Discover, composer, post, feed card, You, Saved, Explore, place picker, profile setup, register, connections, person profile, video player); `strings.xml` (social parts); the view models for Discover, composer and post; backend rules that shape the feed (`DiscoverService` weekly window, `DecisionPolicy` NEW_ACCOUNT_PHOTOS hold, exact-place delay, comment kinds, `IN_CITY` query). The app was NOT run on a device in this session.

## B. Decisions approved by the owner (2026-11-11)
The owner approved the written product/UX audit and "the new IRL direction", with freedom to implement: preserve the working backend and infrastructure, simplify rather than rewrite, fix bugs, no commit or push without approval. The direction: **a post is proof you went outside; success is someone else going because of you.** Camera-first short composer, friends-first feed that ends, Home · Explore · ✚ · You, You as a profile with settings one tap away, safety kept intact but in the background (one quiet row), a dry and human voice (one light line per screen, never in safety, moderation, permission or costly-failure texts). Safety features are NOT to be expanded or redesigned in this phase. Earlier decisions the audit challenged and the approval covers: the fixed tab structure (Saved is no longer a tab), the comment "kind" chooser, "Why?" on every card. **Still the owner's call, NOT implemented:** merging butterfly and Save into one "Want to go", naming connections who reacted, a "Today" time band, a caption-less photo post, the new-account photo hold for the pilot, Discover falling back to older posts in a thin week (all need backend changes or a rule change). These rows are not yet in `docs/DECISIONS.md` (to do).

## C. What was changed (all Android, all uncommitted, on top of the earlier uncommitted work below)
- **R1 bugs:** (1) the feed reads again: `DiscoverViewModel.refresh()` (keeps the posts on screen until the new ones arrive), `onShown()` (when the person's own posts changed, via the new in-memory `FeedFreshness`, or after 5 min), tab re-tap (`TabReselect`, `setUpWithTabs(onReselect)`) goes to the top and refreshes; (2) a failed butterfly/Save is a one-shot `markFailure` shown as a short message, no longer a feed-level failure; (3) the composer asks "Throw this away?" on Back and on the toolbar arrow when the draft has words or media (`ComposeUiState.hasDraft`, `onDiscard()` removes uploads; `MainActivity` routes the arrow through the back dispatcher only on the composer); (4) comments have no kind chooser and print no kind except Answer; the kind sent to the server is picked from the words (`PostViewModel.kindFor`: a question mark = QUESTION, else the first kind that fits), server contract unchanged.
- **R2 composer:** `fragment_compose.xml` rewritten: media button first, words, "This is a question" chip (replaces the type chooser), one optional place row, audience (asked once, then remembered, as before), "Add details" (closed: tip, when, good for, category, who can comment, approve first), Share. The place needs no decision: it starts as NONE and becomes CITY with the profile's city when that names a catalog city (`homeCity`, never over a choice the person made), so posts still appear in the city list.
- **R3 navigation:** bottom bar = Home (`discoverFragment`, label "Home") · Explore (`exploreFragment`, now a tab with its own title, starts in the Home feed's city) · ✚ Share · You. Saved (`cocoonFragment`) is a reading screen under You (toolbar + bottom bar). Home head is ONE row (mark, name, city, search); the Explore button, "Why?" on cards and the header small print are gone.
- **R4 You:** `fragment_you.xml` is a profile head plus five rows (Your posts, Saved, Connections, Safety, Settings and more). New `MoreFragment` + `fragment_more.xml` + nav id `moreFragment` hold everything that was the lower half of You (private places, reading switch, community, moderation, account, log out). Safety screens untouched; the SOS shortcut, tile and shake are unchanged; SOS from inside the app is now You → Safety → SOS (one tap more than before).
- **R5 small:** a tap on the author's photo/name on a feed card opens their profile (Home and place/category pages); your own name on your post opens You; the "Only you see your butterflies..." line under the marks is removed; about 19 strings reworded in the new voice (empty states, loading, posted, hints). Strings pinned by `KurinjiTextsTest` / `HonestyTextsTest` were left as they are.
- **New files:** `feature/social/domain/FeedFreshness.kt`, `navigation/TabReselect.kt`, `feature/profile/ui/MoreFragment.kt`, `res/layout/fragment_more.xml`, test `feature/social/IrlRedesignTest.kt` (11 tests). **Modified:** `AppContainer.kt`, `MainActivity.kt`, `NavigationRules.kt`, `YouFragment.kt`, social `ComposeFragment/ViewModel`, `DiscoverFragment/ViewModel/FeedAdapter`, `ExperienceCardAdapter`, `ExploreFragment`, `PostFragment/ViewModel`, `PostCollectionFragment`, layouts `fragment_compose/discover/explore/post/saved/you`, `menu/bottom_navigation.xml`, `nav_graph.xml`, `strings.xml`, test `NavigationRulesTest.kt`.

## D. Tests and build status
Android unit tests after the last change: **1056 run, 0 failures, 0 errors, 0 skipped** (1045 before + 11 in `IrlRedesignTest`); `NavigationRulesTest` was updated for the new bar. The debug sources compile (the test task builds them). **NOT done:** `assembleDebug`, lint, install, any emulator or phone run, TalkBack, dark mode, large text. Status: implemented, compiled, tested (unit); **not manually verified**. Backend and Python untouched and not re-run.

## E. Known bugs and risks
- Nothing of the redesign has been seen on a screen. Most likely to need fixing on a device: the one-row Home head on a 320 dp screen at 2.0x text (city name next to the wordmark), the composer's city row when the default city applies, the Explore tab's back/tab state, the Saved screen's toolbar.
- The Explore tab keeps the city it started with; changing the city on Home later does not change it.
- Comment kinds: "nice!" is still stored as BEEN_HERE on the server (only no longer shown); a real plain kind needs a backend change.
- `IrlRedesignTest` and code comments name `docs/IRL_REDESIGN.md`, which does not exist yet (write it, or point them at this section).
- Unused now but still present: strings `you_tile_*`, `compose_heading`, `compose_subheading`, `social_type_*`, `marks_private_note`, `discover_why*`, `feed_header_*_sub`, `explore_entry_description`; layout `item_safety_tile.xml`; `FeedActions.why`. Lint will report them.
- `CLAUDE.md` sections 1, 5 and 10 and `.claude/rules/android*.md` still describe "Discover · Share · Saved · You"; `docs/DECISIONS.md`, `docs/STATUS.md` and `docs/API.md` are not updated (no API change).
- Not started from the audit: onboarding cut to one screen (pinned by `HonestyTextsTest`), shorter sign-up (confirm-password, pinned by `RegisterViewModelTest`), an in-app activity list, invite by link, own posts shown on the You tab, everything that needs the backend (section B).

## F. Exact next step
1. `cd android`, `.\gradlew.bat assembleDebug` (with `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\Public`), install on the emulator or the A23, and walk: Home (re-tap, come back after posting), composer (photo first, Back with a draft, Share with no place), post (comment, own name), Explore tab, You → Saved / Safety / Settings and more → log out. Fix what the screen shows.
2. Run lint; remove the unused strings/layout listed above.
3. Write `docs/IRL_REDESIGN.md` (audit summary + R1-R5 + viva notes), add the decision rows to `docs/DECISIONS.md`, one line to `docs/STATUS.md` and `CLAUDE.md` section 2, and correct the navigation lines in `CLAUDE.md` and `.claude/rules/android*.md`.
4. Then, with the owner: onboarding and sign-up shortening (test changes needed), and the backend-dependent items in section B (each needs a plan, tests and a deployment the owner runs).

---

# CHECKPOINT 2026-11-11 (evening): video fast-start + upload timeout + post-list layouts, live-path testing on the A23 - working tree, NOT committed, NOT pushed

**STOP MARKER (the owner stopped here on purpose to switch models): inspect the live state first (read-only), then wait for the owner's go-ahead. No commit, no push, no further code changes, no backup/keystore/other deployment step until told.**

## 1. State right now
- **Live:** `https://api.irlnow.in` (Cloudflare Tunnel -> nginx -> Spring Boot `irl-backend` -> MySQL, all in WSL `Ubuntu-24.04` on the owner's laptop). Public `/api/health` 200. Only the owner uses it (account user id 2; password was reset by hand on 2026-11-11 by the owner via a bcrypt hash update); no friends have signed up. The owner's posts are HELD for a human moderator ("Photo posts for everyone from new accounts are looked at by a moderator first"; rule NEW_ACCOUNT_PHOTOS, probation 72 h from account creation, about 2026-11-09); the author can always see and play their own posts.
- **KEY FINDING (changes earlier assumptions):** the laptop is connected to the **A23's own hotspot** (laptop Wi-Fi 192.168.161.116, gateway 192.168.161.129 = the phone's `swlan0`). So the pilot's whole internet line is the phone's mobile data, and the phone under test uploads over the same radio that also carries the tunnel (a video crosses the phone's mobile link twice). The earlier "laptop line 7 Mbit/s down, 2.4 Mbit/s up" and 0.7-2.2 s edge latency were this hotspot, not a home line. The owner has no other internet connection available. A friend's phone would not share that radio (inference, NOT tested).
- **Backend deployed:** `/opt/irl/backend/app.jar` sha256 `485f627ce8e04d1e20cdf418c62c4f18c5b772d45a5f5b5803304382cc2f319b` (built from this working tree with `mvn -DskipTests package`), installed and `irl-backend` restarted 2026-11-11 14:43:52 UTC by the owner. Previous jar kept as `/opt/irl/backend/app.jar.bak-20261111T144351Z` (rollback = copy back + restart; earlier backup `app.jar.bak-20261109T115412Z` also there). Compared entry by entry with the previous jar: only `Mp4FastStart` (+ inner class) added and `MediaService` (+ 2 inner classes) changed; libraries identical. Journal after restart: Flyway validated 30 migrations, schema at **V30, "up to date, no migration necessary"**, "Production guard passed", started in 10.9 s; only the old WARN that `EVIDENCE_VAULT_PUBLIC_KEY` is empty. No warnings or errors since (checked 15:02 UTC).
- **Backups:** UNCHANGED and still paused: timer NOT enabled; the existing valid backup untouched; second laptop (GPG private key) unavailable. Standing gates: no friend signs up until backups are verified; hand-run a root backup before any V31+ migration. `deploy/scripts/backup.sh` covers the database dump + media + evidence folders.
- **Android debug build on the A23 (R5CW42E9RQW, Android 14):** installed 2026-11-11 20:27 IST from the current working tree (`assembleDebug`, `adb install -r --user 0`). `android/local.properties` (git-ignored) now has `api.base.url=https://api.irlnow.in/` (was `http://127.0.0.1:8080/`; old copy in the session scratchpad `local.properties.bak`). The A23 uses Jio mobile data (Wi-Fi off) and is the laptop's hotspot.

## 2. What was done in this work (all uncommitted)
1. **Video fast-start (backend):** `Mp4FastStart` moves `moov` in front of `mdat` and shifts `stco`/`co64`; run by `MediaService.withMovieFirst` after `Mp4Sanitizer`; the result is re-sanitized (same size/duration, every offset inside the media data), any doubt/error keeps the original layout (never refuses a video). Decision row **17C-F1**. The phone's MediaMuxer writes `moov` LAST (probe showed `moov` at the end of a 2.13 MB, 3.13 s, 5.69 Mbit/s clip).
2. **"Your posts" shows both posts (Android layout):** the probe proved the server returned both posts; the list was a wrap_content non-scrolling RecyclerView in a ScrollView, which lays out only about one screen of items (a photo card is 1776 px on the A23). `fragment_my_posts.xml`: the list scrolls itself (0dp, weight 1). Decision row **17H-F2**. Verified on the A23 (second post appears on scrolling).
3. **Same fix for person profile and place/category pages:** header (everything above the posts) is an `AppBarLayout` in a `CoordinatorLayout` (as Discover) scrolling away with a self-scrolling list; progress, Show more and the end line pinned; view ids unchanged. Verified on the A23 for the category page (header collapses, card scrolls, end line pinned); **NOT verified on a device for the person profile** (no profile with posts reachable: own author name does not open a profile).
4. **Upload timeouts (Android):** `UploadTimeoutInterceptor` raises read AND write timeouts to **120 s** only for calls marked `@Headers(UploadTimeoutInterceptor.HEADER_LINE)` (photo/video upload `api/media`, poster, profile photo); the marker header is removed before sending; connect timeout and every other request keep the shared 15 s; still ONE Retrofit/OkHttp client. `ApiResult.Failure` gained `timedOut`; a read/write timeout sets it; the composer shows "Too slow on this connection. Tap to try again" (`social_media_timed_out`); a lost connection and a server refusal keep their own words; Retry resumes from the failed step. Decision row **17H-F3**. Bitrate/transcoding NOT touched.

## 3. Exact files changed (uncommitted; `git status` on 2026-11-11)
- **Backend (this work):** NEW `backend/src/main/java/com/safesphere/backend/social/media/Mp4FastStart.java`; MOD `backend/src/main/java/com/safesphere/backend/social/service/MediaService.java`; NEW `backend/src/test/java/com/safesphere/backend/social/Mp4FastStartTest.java`; MOD `backend/src/test/java/com/safesphere/backend/social/VideoApiIntegrationTest.java` (one new test).
- **Android main (this work):** NEW `core/network/UploadTimeoutInterceptor.kt`; MOD `core/network/ApiResult.kt`, `core/network/ApiCallHandler.kt`, `AppContainer.kt`, `feature/social/data/remote/ExperienceApi.kt`, `feature/social/data/remote/SocialApi.kt`, `feature/social/ui/ComposeViewModel.kt`, `feature/social/ui/ComposeMediaTexts.kt`; MOD resources `res/layout/fragment_my_posts.xml`, `fragment_person_profile.xml`, `fragment_post_collection.xml`, `res/values/strings.xml` (one string).
- **Android tests (this work, all NEW):** `core/network/UploadTimeoutTest.kt`, `feature/social/MyPostsPageTest.kt`, `feature/social/PostListLayoutTest.kt`, `feature/social/UploadTimedOutComposerTest.kt`.
- **Docs (this work):** `docs/DECISIONS.md` (rows 17C-F1, 17H-F2, 17H-F3), `.claude/rules/backend.md` (Media bullet), this section of `docs/HANDOFF.md`.
- **Also uncommitted from EARLIER sessions (not this work):** `backend/.env.example`, `Fast2SmsOtpSender.java` + its test, `backend/src/main/resources/application.properties`, `deploy/` (LAPTOP.md, README.md, cloudflared template, env examples, nginx tunnel template, smoke-test.sh, backup/cloudflared systemd units, NEW `backup.env.example`, `backup.my.cnf.example`, `backup-db-account.sh`), `docs/D12_DEPLOYMENT.md`, `docs/PHASE17B.md`, `docs/PHASE17J.md`. 32 modified + 10 untracked files in total. HEAD is still `ff250bd`.

## 4. Tests and results
- **Backend:** targeted `Mp4FastStartTest` 4/4, `Mp4SanitizerTest` 8/8, `VideoApiIntegrationTest` 11/11. Full suite (run once): **905 run, 0 errors, 2 skipped, 1 failure** = `UnderstandingApiTest.theStatus_isTrueOnlyWhileTheServiceHoldsAProviderKey` (Explore status; looks timing-flaky under load; the class passed 11/11 when re-run alone; NOT shown to fail without my change).
- **Android unit tests (full run after the last change): 1045 run, 0 failures, 0 skipped** (1029 at the start of the session + MyPostsPageTest 2 + PostListLayoutTest 3 + UploadTimeoutTest 7 + UploadTimedOutComposerTest 4). `assembleDebug` OK. Lint NOT run. The new tests were not mutation-checked.
- Python moderation service: not touched, not re-run.

## 5. Measurements and comparison data
- **Edge latency from this PC through Cloudflare** (trivial `/api/health`): TTFB 0.7-2.2 s, connect 0.1-0.74 s, one 520, one 21 s outlier; origin (nginx on loopback) 5 ms. Speed test from WSL to Cloudflare: ~0.9 MB/s down, ~0.3 MB/s up (the hotspot).
- **Old video (moov last):** one playback = ~15-17 range requests over ~44-60 s, one every 2-3 s, server time 16-104 ms each (the player cancels most: cloudflared "stream canceled by remote"); phone log shows MediaHTTPConnection opening a NEW HTTPS connection per seek incl. a failed TLS handshake (platform VideoView = MediaPlayer; the phone's own pre-player first-byte check stays because it renews a lapsed sign-in).
- **Upload timings (`POST /api/media`):** 13:13:47 400 after 17.9 s (old 15 s timeout, phone aborted); 13:30:57 201 11.2 s; after the backend restart (old Android timeouts) 14:46:21 400 35.4 s, 14:47:20 201 33.9 s, 14:47:58 400 36.9 s (cloudflared logged "Incoming request ended abruptly: context canceled" for the 400s); **with the new 120 s build: 15:01:17 201 after 97.5 s** (poster 200 in 0.9 s, post 201 in 0.6 s). 97.5 s is close to the 120 s ceiling.
- **New video's playback requests (15:02:05-15:02:53):** 13 requests over ~48 s, each now 0.8-5.5 s (data really transferred); count NOT clearly lower than before, so **fast-start's benefit is NOT yet proven** (box order of the stored file not yet seen).

## 6. Two pending verification checks (the owner runs them; nothing else is pending to "finish" the fast-start test)
1. **Box order of the newest stored MP4** (WSL `wsl -d Ubuntu-24.04`, needs sudo; prints sizes only, no content). Expect `ftyp@0 moov@... mdat@...`:
```
sudo python3 -I -c "
import glob,os,struct
f=max(glob.glob('/var/lib/irl/media/*.mp4'),key=os.path.getmtime);d=open(f,'rb').read();p=0;out=[]
while p+8<=len(d):
    s=struct.unpack('>I',d[p:p+4])[0];t=d[p+4:p+8].decode('latin1');out.append(f'{t}@{p}({s})')
    if s<8:break
    p+=s
print(os.path.getsize(f),'bytes ',' '.join(out))"
```
2. **Probe** (Windows PowerShell; asks the password hidden, one sign-in + GET requests only, never prints password/token, deletes its temp files). It lists every video in the owner's posts, so the OLD (moov last) and the NEW video appear in one run: posts returned, first-byte times, response headers, file size/bitrate, first 1 MB, last 64 KB, full download time, MP4 box order:
`powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\Users\ABDULR~1\AppData\Local\Temp\claude\12a449e1fb3f74b2\4252d7e8-a702-4225-9770-58ad8a9fe8eb\scratchpad\irl-probe.ps1"` (the session scratchpad; if it is gone, `irl-probe.ps1` must be re-created: it signs in via `POST /api/auth/login`, calls `GET /api/posts/mine` and for each VIDEO `GET /api/media/{id}/video` with `Range` headers through `curl.exe -H @file`).

## 7. Next steps (in order, each needs the owner's go-ahead)
1. Run the two checks above; compare old vs new video (box order, first-byte time, request count/duration) and say plainly whether fast-start helped. If the new file is NOT `ftyp, moov, mdat`, investigate `Mp4FastStart` / the sanitizer self-check (a journal line "Fast start skipped (...)" or "Fast-start copy differed..." would show it; none seen).
2. Decide on the upload timeout margin (97.5 s of 120 s): raise to 180 s? (not changed; owner decides).
3. Verify the person-profile layout on a device (needs a reachable profile with posts, e.g. a second test account) and, if wanted, the other short-list ScrollView + RecyclerView screens (Safety Circle, vault, settings rows: not changed; only long photo-card lists show the defect).
4. Owner decisions pending: commit (nothing committed; HEAD `ff250bd`), bitrate/transcoding (a transcoder on the phone = new dependency or MediaCodec pipeline, or media hosted off the laptop), a separate internet connection for the laptop (none available today).
5. Later, unchanged from the 2026-11-10 plan: backups (needs the second laptop), reboot/sleep tests, release keystore + signed APK (a debug-signed app cannot be upgraded in place to a release-signed one: uninstall wipes the phone's local data), privacy/deletion pages off the laptop, monitor, Cloudflare 2FA, rebuild drill, friend pilot.

## 8. Known risks and limitations
- The laptop's connection is the A23's hotspot: uploads from the same phone cross its mobile link twice and compete; a slower moment can exceed even 120 s (then the new "Too slow..." message appears). Each failed upload attempt still counts against the 10 videos a day; unattached uploads are cleaned after 24 h. The numbers above come from one phone, one network, a few clips.
- Fast-start applies only to NEW uploads; the old video keeps `moov` last. Bitrate is the camera's (5.7 Mbit/s for the test clip, above the link's uplink): longer clips will still buffer.
- Person-profile layout: built and structure-tested only. Backend full suite has one flaky-looking failure (above). Android lint not run.
- The session scratchpad holds screenshots of the owner's own posts/photos (`mp1.png`, `mp2.png`, `mp3.png`, `pc1.png`, `phone*.png`, `pp1.png`) and helper scripts (`q*.sh`, `ui.sh`, `cmp.py`, `irl-probe.ps1`); delete the screenshots when done. Never paste passwords, tokens or the password hash.
- Rules still in force: every sudo step is the owner's (`wsl -d Ubuntu-24.04`, never `wsl -u root`); no SMS unless the owner types SEND; each Cloudflare dashboard change confirmed first; never print secrets; no commit/push unless told.

---

# Handoff: L1 tunnel LIVE at https://api.irlnow.in, hardened non-root connector (2026-11-10) - working tree, NOT committed, NOT pushed

**STOP MARKER (2026-11-10, the owner stopped here on purpose): inspect the live state first, then wait for the owner's go-ahead. Do not continue with the backup setup, recovery tests, the release keystore or any other deployment step until told.** Nothing is committed or pushed; the backup timer is NOT enabled.
- **Backup setup: repo side prepared, nothing applied.** Decisions (owner): daily 09:00 IST with `Persistent=true` (catch-up after the laptop was off; NOT verified), independent of the IRL services; destination = a 29 GB USB pendrive; encrypted with GnuPG, the PRIVATE key lives only on the owner's second laptop, this laptop gets the public key only; database login via `LoadCredential=`; a scratch-restore test is approved; the timer is enabled only after the manual run, the encryption round trip and the restore test pass. Prepared in the working tree: `deploy/systemd/irl-backup.service` (LoadCredential for `/etc/irl/backup.my.cnf`, `GNUPGHOME=/var/backups/irl/.gnupg`), `deploy/systemd/irl-backup.timer` (`*-*-* 09:00:00 Asia/Kolkata`, Persistent), `deploy/env/backup.env.example`, `deploy/env/backup.my.cnf.example`, `deploy/scripts/backup-db-account.sh` (creates the least-privilege account `irl_backup`, never prints the password). Validated here only by `systemd-analyze verify`, `bash -n` and a `%d` specifier test. **On the laptop:** the old `irl-backup.service` (installed copy, matches the OLD repo version) and the disabled timer are still the old files; no `backup.env` / `backup.my.cnf`, no public key imported, no `irl_backup` account created (not checked as root); the existing valid backup in `/var/backups/irl` is untouched and must not be modified or deleted.
- **To resume:** the owner makes the key pair on the second laptop (`gpg --quick-generate-key "IRL backup key" default default 2y`, exports the public key and a revocation certificate, notes the fingerprint) and runs the read-only "Step 0" listing (`/etc/irl` modes, `/var/backups/irl` listing, MySQL account names and plugins, storage paths, database name); then: import the public key for `irl`, run `backup-db-account.sh`, create `/etc/irl/backup.env`, install the two units (not enabled), run `irl-backup.service` by hand, checksum `restore-check`, decrypt on the second laptop, one temporary plain backup + scratch restore, only then enable the timer, then delete the plain run and the old root-owned folder.
- **Priority list after the backups (agreed, not started):** (1) reboot / sleep / power-off tests (does the WSL keep-alive start with nobody signed in; does the catch-up backup fire); (2) release keystore (back it up) + signed release APK for `https://api.irlnow.in/` + a full real-phone walk on mobile data; (3) privacy policy and account-deletion page hosted off the laptop (the draft in `RELEASE_PRIVACY_PLAY.md` still says "backups are disabled" and must mention the 14-day backup copies); (4) uptime monitor (B8) and SMS balance watch; (5) Cloudflare authenticator-app / key 2FA; (6) pilot readiness + a rebuild drill; (7) Google Play only if needed (registration fee and testing rules NOT verified). Also open: a separate off-laptop copy of the tunnel credential (the only copy today).

Full record: **`docs/D12_DEPLOYMENT.md` section 9**; decisions **D12-7** (domain `irlnow.in`, host `api.irlnow.in` permanent) and **D12-8** (hardened connector, `LoadCredential=`, old unit masked). **The API is public now.** No SMS was sent and no database row written during the tunnel work.
- **Verified (2026-11-10):** zone Active (name servers `*.ns.cloudflare.com`); Step A (local nginx, forged `CF-Connecting-IP` from loopback) and Step B (through Cloudflare, laptop IPv4 vs IPv6) both PASS: the 31st wrong sign-in from one address is 429, another address is not blocked, forged `X-Forwarded-For` is ignored, a forged `CF-Connecting-IP` is refused (403) by Cloudflare; the privacy log holds no address or e-mail. `irl-cloudflared.service` runs as user `cloudflared` (hardened), 4 connections, exactly one connector, `/api/health` 200 publicly, `/api/admin/` `/v1/` `/` 404, only loopback listeners, Windows cannot reach any pilot port.
- **State on the laptop:** old root `cloudflared.service` masked (unit kept as `cloudflared.service.pre-irl-hardening`); `cloudflared-update.timer` still active (decision open); credential `/etc/cloudflared/<id>.json` root 0400 + symlink `tunnel.json` (no copy); home `cert.pem` shredded; **the home tunnel JSON is still there** (needs `sudo cmp` equality, then removal; the owner decides about a password-manager copy).
- **Repo changes (uncommitted):** `deploy/systemd/irl-cloudflared.service` (`LoadCredential=`, config path), `deploy/cloudflared/config.yml.template` (credentials path), `deploy/LAPTOP.md` section 4, `docs/D12_DEPLOYMENT.md` section 9, `docs/DECISIONS.md` D12-7/D12-8. Earlier uncommitted work (Fast2Sms sender, deploy docs) is still in the tree.
- **Next, in the recommended order (each dashboard change is confirmed with the owner first):** (1) DONE 2026-11-10 with a residual risk: GoDaddy phone 2FA on, mailbox 2-step on, recovery codes offline; **Cloudflare stays on e-mail 2FA only** (the owner's choice; authenticator app / key still recommended, revisit before real friends use it); (2) DONE 2026-11-10, verified from outside, logged in `D12_DEPLOYMENT.md` section 9: Always Use HTTPS On, cache-bypass rule, `/api/admin` WAF block, minimum TLS 1.2, SSL mode Full (was already), Bot Fight Mode Off + Browser Integrity Check On (unchanged), `/api/auth/` rate limit (20 per 10 s per IP; real burst 31 passed, 29 refused by Cloudflare) in `deploy/LAPTOP.md` section 4 (cache bypass for `api.irlnow.in`, WAF rule blocking `/api/admin/`, Bot Fight Mode / Browser Integrity off if the app is challenged, Always Use HTTPS + min TLS 1.2, SSL mode Full, optional relaxed rate limit on `/api/auth/`); (3) DONE 2026-11-10 from the laptop through the public Cloudflare edge: `smoke-test.sh https://api.irlnow.in --tunnel --check-redirect` **19 PASS / 0 FAIL** (first run unchanged: 17 PASS + 1 expected FAIL because the admin path now answers 403 from Cloudflare; the script's admin check was updated, uncommitted; record in `D12_DEPLOYMENT.md` section 9); a second network is still to do: open `/api/health` and `/api/explore/understanding` in a phone browser on mobile data; (4) DONE 2026-11-10: `sudo cmp` IDENTICAL, home JSON shredded (best effort), protected copy unchanged and now the ONLY copy (no off-machine backup; recoverable by creating a new tunnel); (5) DONE 2026-11-10 (D12-9): apt-only, manual updates; the package's update timer and service are disabled, moved aside and masked, `irl-cloudflared` untouched (same process, same checksums); procedure in `deploy/LAPTOP.md` section 4; the owner must restart `irl-cloudflared` after any apt update; (6) backups (timer still off; off-laptop destination undecided); (7) a phone on mobile data: client-address proof + a real sign-in with the release APK (`-Prelease.api.base.url=https://api.irlnow.in/`); (8) hosting the privacy and account-deletion pages off the laptop; (9) reboot and sleep tests. No SMS unless the owner types SEND.
- **NOT verified:** everything in the list in section 9 of `D12_DEPLOYMENT.md` (dashboard settings, phone on mobile data, reboot with nobody signed in, laptop-off on a real phone, backups, release APK against the public host).

---

# Handoff: L1 laptop pilot running locally in WSL; STOPPED before the domain / Cloudflare Tunnel (2026-11-09 night) - history; superseded by the section above (the tunnel is live)

**STOP MARKER: wait for the owner to return with the domain decision. Inspect the live state first (nothing is assumed to be unchanged overnight).** No purchase, no Cloudflare change, no VPS (budget is about Rs 0: do not recommend paid services), no commit or push unless the owner says so, no new product features, no automatic SMS, no secrets in chat (no Cloudflare token, password, OTP, Fast2SMS key, JWT secret).

**Live now (WSL `Ubuntu-24.04`, outside the repo; local only, nothing reachable from the internet):**
- `irl-backend`, `irl-moderation`, `nginx`, `mysql` are active AND enabled. Backend 127.0.0.1:8180 (jar sha256 `f369bcc5ffd3f802fbf9945722f71cf075ebffcdca9223ef90c13e9089ff8c9c`, `prod` profile, "Production guard passed", Flyway V30, JDBC `sslMode=REQUIRED`), nginx 127.0.0.1:8081, Python 127.0.0.1:8091, MySQL 127.0.0.1:3307. From Windows `localhost` 8180/8081/8091/3307/22/3389 are all unreachable (checked). `IRL-WSL-KeepAlive` scheduled task: enabled, Ready.
- **Lesson (found 2026-11-09):** when the last `wsl.exe` session closes, WSL powers the whole distro off (`systemd-logind: The system will power off now`), which stops the backend even though it started fine. The keep-alive task (one `sleep infinity` in the distro) is what prevents it; it was Disabled until the owner enabled it. Also needed: `systemctl enable irl-backend` (done by the owner).
- **Real OTP end to end PASSED** (Fast2SMS Quick SMS: register, typed SEND, SMS received, verify HTTP 200, session issued, `GET /api/users/me` 200, the owner's real account is user id 2, logcheck found no code/token/key values). The older test account `rasikabd123@gmail.com` (UNVERIFIED, code expired) was not touched; never verify any account by hand.
- Fast2SMS Quick API key is configured in `/etc/irl/backend.env` (root-only); never print it.
- Harness (WSL home `/home/abdul_rafiq/`): `irl-otp-test.sh` (the `db` step now uses `s.created_at`), `irl-audit.sh` (read-only: `/etc/irl` permissions, backup state, nginx directives, log counts, `db` + `logcheck`; run `sudo bash`).

**nginx error_log review (read-only): intentional and safe.** Global `error_log` (nginx.conf, level `error`) = process faults only (1 line, from before the site existed); the site's `error_log ... crit` stops the per-request upstream lines that carry client address and query string (`irl.error.log` is 0 bytes after several backend outages). Notes: site logs are 644 root until the first logrotate (0640); the global `access_log` uses the combined format (IP, query) and is overridden only because the site has its own `access_log` (a future server block without one would leak it); `deploy/nginx/irl.conf.template:47` (VPS) still says `warn` and needs the same `crit` fix later (needs approval).

**Backup: NOT automated.** `irl-backup.timer` is installed but disabled. The one passing backup + restore-check + tamper test (`/var/backups/irl/irl-backup-20261108T211433Z`) was run by hand as root. Untested as the `irl` user under `ProtectSystem=strict`: `irl` reading `backup.my.cnf` (`/etc/irl` is root-only), retention cannot delete root-owned folders (errors go to /dev/null), GnuPG needs the public key in `irl`'s keyring and a writable home. Owner decisions: the GPG private key stays OFF the laptop; the off-laptop destination (B7; `/var/backups/irl` and `/mnt/c` are the same physical disk); 03:30 on the UTC distro = 09:00 IST. Proposed order (each needs approval): fix permissions + `backup.env`/`backup.my.cnf`; import the key for `irl`; one manual `systemctl start irl-backup.service` + journal + `restore-check`; then `enable --now irl-backup.timer`; then a scratch-database restore.

**Tomorrow (owner's plan):** (1) check availability of the shortlisted `.in` domains for IRL; (2) compare registration, renewal price and checkout conditions; (3) a 1-year registration if practical; (4) registrar auto-renew OFF; (5) add the domain to Cloudflare (zone Active); (6) Cloudflare 2FA on; (7) resume at the tunnel (`deploy/LAPTOP.md` section 4: install `cloudflared`, `tunnel login/create/route dns` run by the owner, only the tunnel ID is shared, `cert.pem` and `<id>.json` never pasted); (8) keep the API hostname stable (it is baked into the release APK). Before the tunnel goes live decide how to prove "the real client address reaches the backend" without an SMS and without adding an IP to the privacy log (`irl_privacy` records no IP). Then: public HTTPS checks, smoke tests (`smoke-test.sh https://api.<domain> --tunnel`), backups, restore, final security/config review.

**NOT verified:** cloudflared, the domain, public HTTPS, Cloudflare dashboard settings, the backup timer as `irl`, GnuPG encryption, restore into a scratch database, `require_secure_transport` on the MySQL server (not read), the Android app against this backend, TalkBack, a real phone off the laptop.

---

# Handoff: L1 laptop + Cloudflare Tunnel pilot preparation, and project cleanup (2026-11-09) - working tree, NOT committed, NOT pushed, NOTHING DEPLOYED

Runbook: **`deploy/LAPTOP.md`**; record and blockers: **`docs/D12_DEPLOYMENT.md`** section 8; decisions D12-3 (laptop now, VPS later; supersedes D12-1 for the pilot), D12-4 (real client address), D12-5 (admin API not served, pages off the laptop, separate ports).
- **Defect found and closed in the design:** behind a tunnel every request comes from 127.0.0.1, so `AuthThrottle` would have shared ONE sign-in / sign-up / code limit between all friends. `irl-tunnel.conf.template` takes the client from `CF-Connecting-IP` (loopback peer only) and `FORWARD_HEADERS_STRATEGY=native` is mandatory (preflight fails without it). New real-server tests: `RealClientAddressBehindProxyTest`, `RealClientAddressWithoutProxyTest` (MockMvc cannot show this). Code change besides tests: a comment in `AuthThrottle`.
- **New in `deploy/`:** `LAPTOP.md`, `nginx/irl-tunnel.conf.template`, `cloudflared/config.yml.template`, `systemd/irl-cloudflared.service`, `mysql/irl-mysql.cnf.example`, `wsl/` (`wsl.conf.example`, `wslconfig.example`, `windows-host-setup.ps1` = report + dry run, `-Apply` for power plan / .wslconfig / keep-alive task), `smoke-test.sh --tunnel`; `.gitignore` patterns for tunnel credentials and filled env files; root `README.md` now points to the current docs.
- **Verified here:** backend 897 / 0 (2 skipped), Python 83 OK, Android 1029 / 0 from a clean tree, `bash -n` all scripts, PowerShell parse + report mode, packaged jar on the laptop ports with the real MySQL (smoke test 0 failed; per-address limits separate with `native`), preflight on a laptop-style env pair. Android lint was not run (Android source untouched).
- **NOT verified:** WSL itself (not installed), `nginx -t`, that nginx forwards `CF-Connecting-IP`, the systemd units, cloudflared, every Cloudflare dashboard setting, the `.wslconfig` keys on this WSL version, laptop-off behaviour on a real phone, real SMS.
- **Cleanup (explicit paths only, nothing tracked touched):** deleted `android/app/build`, `android/build`, `android/.kotlin`, `android/.gradle`, `backend/target`, three `__pycache__` folders, throwaway rehearsal secrets. 544 MB to 78 MB. Kept: `backend/evidence-store` (42 MB) and `backend/social-media-store` (2 MB) = development data that the development database rows point at (the owner decides), `backend/sql/backups` (24 KB, the only copy from before the one-time UTC fix), `moderation-service/restricted/*.tsv` (needed to run it, git-ignored), `backend/.env`, `android/local.properties`, IDE settings, all docs and design files. Outside the repo, reported only: the Desktop Phase1/2/3 folders and zips, screenshots, the parent `backend-run.log`; `~/.android` (about 29 GB, mostly emulators), the Android SDK (20 GB), `~/.gradle` (5.5 GB), `~/.m2` (0.1 GB).
- **Owner's next steps:** install WSL2 + Ubuntu (admin, reboot), run `windows-host-setup.ps1` (dry run, then `-Apply`), choose and buy the domain and put it on Cloudflare, Fast2SMS account + key, the off-laptop backup destination, the release keystore; then follow `deploy/LAPTOP.md` section by section. The Windows development MySQL is now bound to localhost (done 2026-11-09, verified; backup of `my.ini` kept beside it).

---

# Handoff: D12 production deployment preparation (2026-11-08) - working tree, NOT committed, NOT pushed, NOTHING DEPLOYED

Full record, blocker table and the exact asks: **`docs/D12_DEPLOYMENT.md`**; runbook: **`deploy/README.md`**. No server, domain, SMS account or production database exists, so D12 ended in artifacts, a local rehearsal and a blocker list.
- **Code (only change):** `GET /api/health` (public, UP / 503 DOWN, `HealthApiTest` 4 tests), `server.address=127.0.0.1` in the `prod` profile (`ProductionGuardTest` extended), `API.md`. Backend **895 / 0** (2 skipped), Python **83 OK**; Android untouched.
- **`deploy/`:** env templates (no values), systemd units (backend, moderation, backup + timer), nginx bootstrap + production templates, `preflight.sh`, `backup.sh`, `restore-check.sh`, `smoke-test.sh`, DB setup example, `.gitattributes` (LF for scripts).
- **Rehearsed on this PC (real MySQL 8 + TLS 1.3, real jar, real Python service):** the `prod` guard refuses with exactly one problem (the SMS provider) when everything else is production-valid; Flyway validates 30 migrations; backend listens on 127.0.0.1 only; backend -> Python assessment works (PUBLISH/CLEAN); smoke test 12 PASS; backup + restore-check pass and reject tampering; preflight 0 failed on a valid and 19 on a broken synthetic pair, never printing a value; Android `release.api.base.url` reaches BuildConfig.
- **NOT verified:** nginx (`nginx -t`), systemd units, certbot, the Linux permission checks, GnuPG encryption, the backup timer, monitoring, restore into a scratch DB, a from-scratch V1-V30 migration (needs an empty DB: B10), real SMS.
- **Blockers (user):** B1 server, B2 domain + e-mail, B3 production MySQL with TLS, B4 Fast2SMS account + key, B5 release keystore, B6 privacy / deletion page text, B7 backup destination (+ optional GnuPG key), B8 uptime monitor, B9 Play declarations, B10 MySQL admin access for the scratch-DB rehearsal.
- **Phone:** nothing deployed to test yet; the debug build over `adb reverse` against a local backend still works (`CLAUDE.md` section 12).
- **State left:** rehearsal backend (8085) and Python service (8091) stopped, phone-session backend (8080) stopped and its USB reverse removed, backup and options file deleted, Gradle daemons stopped. Scratch posts made for the rehearsal were deleted; scratch accounts remain in the dev DB.
- **Next:** the user's choices for B1-B10; then run `deploy/README.md` on the server.

---

# Handoff: Phase 17K Step B, targetSdk 36 (2026-11-08) - working tree, NOT committed, NOT pushed

Full record: **`docs/TARGETSDK36_PLAN.md`** (status log at the end; decisions 17K A-1 and B-1 in `DECISIONS.md`). **Steps A and B are complete.** The app now builds with AGP 8.13.2, Gradle 8.14.6, compileSdk 36 and **targetSdk 36** (the Play requirement).
- **Step B = one line** (`targetSdk = 36`). Unit tests **1029 / 0**, lint **0 errors / 98 warnings** (same issue IDs as Step A; `OldTargetApi` stays, informational), `assembleRelease` and `assembleReleaseCheck` built, release badging `targetSdkVersion:'36'`, R8/packaging checks unchanged. Backend and Python untouched, not re-run.
- **Measured, not assumed:** on the same API 37 emulator, target 35 and target 36 behave **identically** for back handling (key, edge swipe, cancelled edge gesture) on onboarding, ProfileSetup, the Discover panel, the city and report sheets, the **SOS countdown** (back cancels it, a cancelled gesture does not), an **active SOS** and an **active Safe Walk** (a full back leaves the screen, the banner and the service stay), and for the Tamil + Tanglish post at font 1.0 and 2.0 (no clipping). No predictive-back opt-out was added.
- **NOT verified:** a real Android 16 phone, the JobScheduler quota effect on evidence uploads, large-screen layouts, the sign-up form/DOB picker, video, comments/connections/moderation/account screens, shake SOS, AI understanding, permission flows (pre-granted), TalkBack, an actual Play upload.
- **State left:** emulator and backend stopped, app uninstalled, mock location removed, font 1.0; scratch accounts `bk35`, `bk36`, `walk.17k`, `releasecheck.17j` are in the dev DB. Local-only edit outside git: `android/local.properties` `sdk.dir` colon escaped.
- **Still open for Play (user decisions, `PHASE17J.md` §5):** production domain + TLS, release keystore, real SMS, privacy policy + Data safety forms, account-deletion web link, SEND_SMS / foreground-service declarations, Spring Boot 4.x, the AGP 9 migration before the next yearly target bump (API 37).
- **Next:** the user's review; a local commit if wanted.

---

# Handoff: Phase 17K Step A, toolchain for targetSdk 36 (2026-11-08) - working tree, NOT committed, NOT pushed

Full record: **`docs/TARGETSDK36_PLAN.md`** (status log at the end; decision 17K A-1 in `DECISIONS.md`). **Step A is complete; Step B (`targetSdk = 36`) is not started.**
- **Changed (3 lines):** wrapper `distributionUrl` -> Gradle **8.14.6**, `agp = "8.13.2"`, `compileSdk = 36` (targetSdk 35). Also, outside git: `android/local.properties` `sdk.dir` colon escaped (AGP 8.13 lint error `PropertyEscape`).
- **Results:** unit tests **1029 / 0**, lint **0 errors / 98 warnings** (was 105), `assembleRelease` + `assembleReleaseCheck` built, Gradle guards fail where they should, R8/dex/16 KB/badging checks pass, emulator walk of the minified build on the API 37 AVD passed on the steps listed in the plan (sign-in routing to the phone-code screen, verify, profile, Explore, Nearby report, private place, photo post, duplicate-contact text, SOS countdown + Cancel, a real SOS with 2 sealed audio items, Safe Walk, vault). **Backend and Python not touched, not re-run.**
- **NOT verified:** the sign-up form and DOB picker, video, comments, connections, moderation, account screens, shake SOS, AI understanding, Tamil text, permission flows (pre-granted), TalkBack, any real Android 16 phone.
- **Environment notes:** use `-Djava.net.preferIPv4Stack=true` for Gradle downloads here; run Gradle stages one at a time with the daemon stopped between heavy ones (a combined forced run stalled and a low-memory reap happened); Git Bash `sed -i` rewrites CRLF files.
- **State left:** emulator and backend stopped, app uninstalled, mock location removed; scratch account `walk.17k@example.com` (2 resolved emergencies, 1 post, 1 contact) is in the dev DB. I also stopped a VS Code Java helper process by mistake (it normally restarts on its own).
- **Next:** Step B when the user says so (same API 37 AVD and settings, same walk, compare screenshots in the scratchpad `shots-A`).

---

# Handoff: 17J follow-up J-8, no plaintext token ever (2026-11-08) — working tree, NOT committed, NOT pushed

Supersedes the plain-text fallback (J-2) from the first 17J pass. Details and reasons: `docs/PHASE17J.md` §2 (J-8) and `DECISIONS.md` J-8 (advisor-reviewed first). Android **1029 / 0** (forced re-run), lint **0 / 105**, `assembleRelease` + `assembleReleaseCheck` built; backend and Python untouched, not re-run.
- **Changed:** `DataStoreSessionStorage` only (plus tests and docs). No token or refresh secret is ever written in plain text: sealing is tried twice; if the Keystore still refuses, the session is **memory-only** (secrets in this process, a random generation id on disk, every sealed/plain secret removed in the same edit); after a restart it is the safe state: same person, empty token, "sign in to sync", nothing deleted, SOS works locally. The plain `access_token` key is never read as a token or assigned; a pre-17J plain token is sealed or moved to memory at first read and removed in the same edit. No Keystore key is deleted or regenerated.
- **Tests:** `SealedAccessTokenTest` rewritten (15; raw DataStore file bytes scanned for secrets; account-switch, retry, restart, emission, migration, source guard; a deliberate plaintext write made 3 tests fail) and `EmptyTokenRestartStateTest` (4: refresher/renewer quiet, no sign-out).
- **NOT verified:** the memory-only path on a device (a real Keystore failure cannot be produced on demand). One earlier forced build run reported BUILD FAILED with its output lost; cause not identified; two later runs passed.
- **Next:** the user's review; commit when they say so; the 17J decisions (targetSdk 36, domain, keystore, SMS, policy).

---

# Handoff: Phase 17J release readiness (2026-11-08) — changes are in the working tree, NOT committed, NOT pushed, nothing deployed

Full record: **`docs/PHASE17J.md`** (decisions J-1…J-7 in `DECISIONS.md`; privacy / Play draft in `docs/RELEASE_PRIVACY_PLAY.md`). Android **1017 / 0**, lint 0 / 105, backend **891 / 0**, Python 83 OK.
- **Done (implemented, compiled, tested):** access token sealed with the Keystore (A14 closed); `AuthInterceptor` + video header only to the backend origin; release signing only from `keystore.properties` / `IRL_KEYSTORE_*` (else unsigned); R8 + resource shrinking with Gson keep rules; versionCode 1 / `1.0.0-rc1`; separate `release.api.base.url` (placeholder under `.invalid`, https enforced, a signed release may not carry the placeholder); strict main network config; backend profile `prod` + `ProductionGuard`; `.env.example` complete; proxy / rate-limit notes.
- **Found by running the minified build on the emulator (two release-only bugs, both fixed):** (1) the app crashed at launch (R8 stripped `TypeToken` generic signatures); (2) server error texts and codes were silently dropped (R8 merged away `ErrorBody`; a duplicate Safety Circle number showed a generic text). Re-verified on screen after the fixes; partial walk (sign-in, restart persistence, Discover, Safety Center, Safe Walk, Evidence Vault, duplicate contact) passed. Not walked under R8: sign-up/OTP, post with media, SOS, Nearby, Explore.
- **Blocks a Play upload / needs the user:** **Play now requires targetSdk 36 (app: 35)** = AGP + compileSdk upgrade (own phase); production domain + TLS; release keystore (generate + back up); real OTP (Fast2SMS KYC, top-up, India DLT); production secrets; privacy policy + Data safety + account-deletion web link; `SEND_SMS` / foreground-service declarations; Spring Boot 4.x upgrade (3.5.16 is the last 3.5.x; newest stable 4.1.2); confirm version numbers.
- **State left:** emulator and backend stopped, check app uninstalled; the A23 was not touched; scratch account `releasecheck.17j@example.com` is in the dev DB; the build still points at `127.0.0.1`.
- **Next:** the user's decisions above; then a repeat of the minified walk with the real release configuration; 17I (brand, regression).

---

# Handoff: real-device sprint CLOSED PARTIAL (2026-11-07) — changes are in the working tree, NOT committed (the user decides), never pushed by Claude

Full record: **`docs/REAL_DEVICE_SPRINT.md`** (§17 = close-out, open decisions, device state). Android **998 / 0**, lint 0 / 105, backend 883 / 0, Python 83 / 0.
- **Done:** Tracks A-H (see the record). Eleven defects found on the A23 and the emulators were fixed; the last one (F-H1c, the foreground-service notification after Home + a late "Allow") was found and verified on the A23 in the close-out.
- **NOT verified / deferred by the user:** D1b real 25 h idle and D2 overnight battery (never started; their old reference times are void), SOS countdown speech under TalkBack, screen-off shake reliability, a second phone.
- **Open decisions for the user (nothing built):** haptic tick for the SOS countdown; a general Tanglish spelling rule (+ "chilling"); screen-off shake; a proximity rule against jolts; restarting a walk's service from any screen; the 17F signal-card buttons at 2.0x text.
- **State left:** the A23 is back at its settings (battery Optimized, automatic SMS OFF, `SEND_SMS` revoked, TalkBack off, `location_mode` 3 - it was found at 0, no SOS or walk running); the backend started for the close-out may still be running; AVDs `irl_api26/29/33` (about 47 GB) are still on disk by the user's instruction; dev SQL backups in the session scratchpad contain dev data.
- **Next:** the user's decisions above; the sprint commit (local) when they say so; then 17I/17J.

---

# Handoff: real-device sprint, Tracks A and B done; Track C paused (2026-11-04) — committed locally, NOT pushed (history)

Full record and the Track C handoff: **`docs/REAL_DEVICE_SPRINT.md`** (§4 = how to resume). The user paused the sprint after Track B ("not ready for the
physical-device Track C tests"); **no further device testing was run and Track C has not started.**
- **Done on the Galaxy A23:** Track A (setup) and Track B (display matrix, permission flows, server-unreachable SOS with sync, process death, foreground-service
  types, media). Everything passed except one defect and one label nit, both **fixed and re-verified on the phone**: Nearby after a permanent location refusal
  (`CommunityUiState.permissionBlocked`) and the notification button label (`core/ui/RuntimePermissionAsk`).
- **Results:** Android **974 / 0** (+8 `Phase17HPermissionDenialTest`), lint 0 / 104 (unchanged), backend unchanged (883 / 0).
- **NOT VERIFIED (Tracks C-H):** shake SOS, TalkBack by ear, Safe Walk and Nearby report outdoors, Explore with the Samsung keyboard / Tamil / Tanglish, the
  Phase 5 vault viewing (needs the user's unlock), Samsung/OEM behaviour, SMS and Call 112 (nothing sent, nothing dialled so far), 25 h idle / overnight runs,
  the emulator API 26 / 29 / 33 matrix.
- **State left:** backend (started for the sprint with the dev OTP sink, no real SMS) and the `adb reverse tcp:8080` tunnel may still be up; the phone is
  back at its original settings (`stay_on_while_plugged_in 0`); location, microphone and notifications granted, `SEND_SMS` not.
- **Next:** resume with Track C when the user is available and holding the phone; then D, E, F, G, H as in the sprint plan.

---

# Handoff: Phase 17G-2 "I went" + guarded rating (2026-11-03) — committed locally (2f1e09b), NOT pushed

**Implemented, compiled, tested, live-verified on real MySQL, emulator-verified incl. 2.0x** (`docs/PHASE17G2.md`; rule **U5** added to CLAUDE.md §1 by the
user's approval of all defaults). **V30 `place_visits` is applied to the dev database** (backup `safesphere-before-v30.sql` first; dry run 15/15); next
migration **V31**. "I went" is a private record; an optional 1-5 rating lives on the same row; a place shows ONE guarded average (≥ 5 counted raters past
probation, Bayesian, once per IST day in memory, no count/name/time, place page only, never an ordering input). Android: "How was it?" sheet from the place page and
the post page.
- **Results:** backend **883 / 0** (+25), Android **966 / 0** (+16), Python 83 / 0, lint **0 / 104** (unchanged).
- **NOT VERIFIED:** a physical phone, TalkBack by ear, the daily memo across a real IST midnight (moved clock only), several backend instances.
- **Found by the live run (fixed):** the place buttons side by side clipped the long "You went here · 4 out of 5" label (now stacked full-width); a stale
  post-page button description. **My test method, not the product:** MySQL's own CHECK refused moving accounts back in time without `phone_verified_at`.
- **State left:** backend stopped; the emulator was cold-booted (its old snapshot hung), signed in as scratch account `live17g2.other.stqa@example.com`
  (has a visit); many scratch accounts `live17g2.*` / `live17g3.*` and their places/posts remain in the dev DB. Backups in the session scratchpad
  contain dev data (`safesphere-before-v29.sql`, `-v30.sql`): delete when no longer needed.
- **Next:** the user's review and a local commit if wanted; then 17H (brand) and 17I/J (regression, release audit).

---

# Handoff: Phase 17G-3 optional AI understanding (2026-11-03) — committed locally (edd63f9, f997b73), NOT pushed

**Implemented, compiled, tested and MANUALLY VERIFIED LIVE with Groq on the emulator** (`docs/PHASE17G3.md` section 8). OFF by default at every layer; Explore (17G-1) is unchanged
when off. Python service `/v1/understand` (`understand.py`, stdlib only, provider adapter: **Groq free tier first**, Anthropic second; key only in `IRL_UNDERSTANDING_API_KEY`; smoke test `tools/smoke_understand.py`), backend package `explore`
(`UnderstandingClient/Gate/Service/Controller`, caps 20 / 300 per UTC day, 10-minute breaker, mode `EXPLORE_UNDERSTANDING_MODE`), Android
switch + question on the Explore screen (per-account consent, fallback "Standard understanding was used."). No migration (next stays **V30**).
- **Results:** Python **83** OK, backend **858 / 0**, Android **950 / 0**, lint **0 / 104**. Live: the real model answered English/Tanglish/Tamil in about 1 s and
  was stable after the prompt + `temperature: 0` change; the app's consent, smart search, hand edit, repeat and cap fallback were walked through on the emulator.
- **NOT VERIFIED:** the REAL provider (no funded key; the request shape follows the API reference but was never sent), the emulator and the A23 for
  this sub-phase, TalkBack. `PHASE17G3.md` §6 is the checklist; item 1 (one real request) is the important one.
- **Deliberate changes to guard tests:** `ModelPlacesTest` (third pinned client, `urllib`/`ssl`, provider only in `understand.py`),
  `Phase17GExploreTest` (Explore API endpoints 1 → 3).
- **Next:** the user's review and a local commit if wanted; 17G-2 (ratings, U5 rule change); the 17G-1 A23 run and Tamil word-list review stay open.

---

# Handoff: Phase 17G-1 deterministic Explore (2026-11-03) — one local commit, NOT pushed

**Implemented, compiled, tested, live-verified on MySQL, emulator-verified** (`docs/PHASE17G.md`; plan + decisions G-1…G-11 in
`PHASE17G_PLAN.md`, approved by the user). **V29 `post_facets` is applied to the dev database** (backup `safesphere-before-v29.sql` was
taken first; dry run on scratch tables 14/14 accepted + 9/9 refused); next migration **V30**. A person types what they want; the
**phone** understands the words (word list EN/Tamil/Tanglish) and shows chips; Search sends ONLY the structured query; the server's
deterministic engine lists places from EXACT-place Everyone experiences the viewer may read. Experiences carry up to 4 optional
"Good for" tags. No AI, no key, no cost.
- **Results:** backend **818 / 0** (+26), Android **927 / 0** (+31), lint **0 / 104** (unchanged), live API **31/31** on MySQL
  (real sign-up + dev-file OTP), emulator walk-through PASS incl. 2.0x text. Scratch accounts/places/tags were deleted afterwards.
- **NOT VERIFIED:** the Galaxy A23 (not connected this time); TalkBack by ear; **the Tamil/Tanglish word list is a draft that needs a
  Tamil reader (decision G-6)**; Explore with real data.
- **Next:** the user's review; 17G-3 (optional Claude understanding behind a mode switch, default OFF, stub-verified unless a funded
  API key is provided) is recommended before 17G-2 (ratings need the U5 rule change). Nothing for 17G-2/3 is started.
- **State left:** backend stopped; emulator at font 1.0, signed in as the 17E throwaway account; the build still points at `127.0.0.1`.

---

# Handoff: Phase 17F device validation + four 2.0x fixes (2026-11-03) — committed locally (15db253), NOT pushed

**Implemented, compiled, tested (Android 896 / 0, lint 0 / 104), verified on the Galaxy A23** (`docs/PHASE17F.md` §6.1 and §6.2).
The A23 was reached without touching authentication: the backend's existing `DEV_FILE` OTP sink wrote the code to a file and a
test account (`qa.phase17f@example.com`, handle `qa_phase17f`, fake number 9000017017) was created through the real sign-up
screen. It still exists on the A23 and in the dev DB (account deletion is refused for 24 h after an emergency; one manual SOS
was ended with "I'm safe"; no Safety Circle contacts, no SMS). Executed on the phone: audio consent, SOS countdown/Cancel, auto
audio + Stop finality, last known position, Safe Walk line, Nearby radar and report sheet, 2.0x text. **Fixed (V5-V8, drawing and
layout only):** report dialog → bottom sheet with a fixed Share/Cancel footer (Cancel had been clipped at 2.0x), Safe Walk line labels
no longer printed over their marks, radar number/"You"/ring labels keep apart, signal-row badge under the title.
- **Still open (needs the user's decision):** the signal card's two button rows break words at 2.0x ("Rep / ort / a problem");
  proposal in `PHASE17F.md` §6.2.
- **Manual only, still the user's:** all shake tests (screen on, screen off during a Safe Walk, false positives), swipe-away from
  recents, TalkBack by ear, battery, Safety Circle SMS, a Nearby report outdoors (≤ 100 m), the Safe Walk deviation notification.
- **State left:** A23 restored (font 0.8, dark, no reverse, stay-awake off; its location was found ON, left as found); backend
  stopped; the APK on both devices is the new build pointing at `127.0.0.1`.

---

# Handoff: Phase 17F emergency (2026-11-03) — one local commit, NOT pushed

**Implemented, compiled, tested, emulator-verified (API 37)** (`docs/PHASE17F.md`, decisions F17-1…F17-7). **No migration**
(next stays **V29**: `emergency_events.trigger_type` has no CHECK, confirmed on MySQL), no new permission, service or dependency.
Opt-in **shake SOS** (3 firm back-and-forth shakes → the same cancellable 5 s countdown; server trigger `SHAKE`; voice stays
unavailable and the app says so), **audio in an SOS after advance consent** (one question on the first visit to You → Safety,
default No, microphone asked then; once per SOS, Stop is final), **"last known position"** with its age on the SOS screen,
**one-step Nearby report** (no text, no photo) + "about 24 hours" instead of "1465 minutes", **readable radar** (sp labels,
numbered marks matching the list, marks pushed apart), **Safe Walk line** (app-drawn straight start→destination line + band + you).
- **Results:** backend **792 / 0** (2 skipped; +2); Android **877 / 0** (+68); lint **0 / 104** (unchanged); debug build OK.
- **Emulator walk-through PASS** (list in `PHASE17F.md` §6) after 4 fixes found on the way: the shake never armed because
  `AppVisibility` was created too late (17F-V1), Safe Walk line collisions / cut-off band (V2), radar halos over numbers (V3),
  an outdated "audio only when you tap" line (V4).
- **Galaxy A23 (connected this time):** debug APK installed and cold-started to the sign-in screen (nobody signed in). **Finding:**
  its only accelerometer is non-wake-up, so shaking with the screen off is unreliable on such phones: stale samples are dropped
  and the app tells the person not to rely on it.
- **NOT VERIFIED:** shake tuning / false positives / battery on a real phone (nobody shook it); TalkBack by ear; dark mode of the
  new screens; the radar at 2.0x text; the Safety Circle SMS after a background-started SOS (needs a SIM).
- **State left:** backend stopped (it had been started for the walk-through); emulator running, signed in as the old 17E throwaway
  account (queued for deletion 2026-11-10; it now has shake on, audio on, SOS rows 51–53 `SHAKE`/`SHAKE`/`MANUAL`, all ended);
  scratch Nearby signals and my extra test account deleted; the build still points at `127.0.0.1` (`adb reverse tcp:8080`).
- **Next:** the user's review and push; then 17G AI Explore + ratings (V29 is the next free migration number; the plan said V30 for
  ratings because V29 was reserved for 17F - it was not needed, so ratings can take **V29**).

---

# Handoff: IRL rebrand + opening screen (2026-11-03) — one local commit, NOT pushed

**Implemented, compiled, tested, emulator-verified (API 37)**, a focused branding update before 17F (no phase, no
migration, no new dependency). Visible name **IRL** (In Real Life) everywhere a person reads it, incl. the Safety
Circle SMS and the one server message that named the app; package / `applicationId` / signing / code names unchanged
(the app updates in place). New launcher icon **"the Meet"** (adaptive + themed + animated launch mark; concepts and
choice in `docs/design/irl-icon.md`). New opening screen: **OUT THERE. TOGETHER. / YOUR WORLD. YOUR PEOPLE. YOUR
FREEDOM. / LET'S GO →** (`docs/DESIGN_SYSTEM.md` "Opening").
- **Results:** Android **809 / 0**; lint **0 / 104**; backend: the 5 affected classes **45 / 0** (one message reworded).
- **Emulator:** launcher label + icon (circle mask) and long-press SOS shortcut; opening screen light and dark (always
  ink, edge to edge), 320 dp at 1.0x and 2.0x (headline never wraps, hero hides, button pinned); CTA → onboarding;
  a returning person is routed on with no tap; sign-in tile, Discover head mark, You sign-off.
- **NOT VERIFIED:** physical phone; TalkBack by ear; other launchers' mask shapes beyond circle/squircle previews.
- **Known limit:** on a 640 dp-tall screen at 1.0x the supporting line needs one short scroll (the button stays in view).
- **State left:** backend stopped; emulator signed in as a throwaway account (queued for deletion), display reset.
- **Next:** the user's review and push; 17F.

---

# Handoff: Phase 17E social redesign (2026-11-03) — one local commit, NOT pushed

**Implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37, light + dark, 320 dp at 2.0x
text)** (`docs/PHASE17E.md`). V28 (next **V29**): "Ink & Lime" palette, butterfly = reaction + Save = private
bookmark (Saved tab), 85-city catalog searched on the phone, profile photo, today's state for connections only, "I went",
compact Safety grid on You, `MarksFit` for narrow screens / large text.
- **Results:** backend **790 / 0** (2 skipped); Android **808 / 0**; lint **0 / 104**; V28 dry run + constraint checks on
  MySQL (applied 2026-11-02); live API **49/49** + moderator photo path **9/9**; emulator walk-through PASS after 10
  fixes found on the way (17E-V1…V10: overflowing marks at 2.0x, You head at 2.0x, onboarding icon, a "Cocoon" 429 text…).
- **NOT VERIFIED:** the A23 beyond install + launch (nobody signed in); TalkBack by ear; a photo upload on a weak network.
- **State left:** backend stopped; emulator running, display reset, signed in as a throwaway account; five
  `p17e-*@example.com` accounts queued for deletion (due 2026-11-09, erased when the backend next runs after that);
  the build still points at `127.0.0.1`; the A23 has the 17E debug APK.
- **Next:** the user's review and push; 17F (SOS location handling, shake SOS per D-4), 17G ratings (V30, D-8).

---

# Handoff: Phase 17D location UX (2026-11-01) — one local commit, NOT pushed

**Implemented, compiled, tested, emulator-verified (API 37)** (`docs/PHASE17D.md`). Android only: no migration (next
**V28**), no backend change, no new permission or dependency. Nearby report needs a precise position (visible,
cancellable search; "move outside or wait for a better signal"; the 100 m rule is unchanged), one permission /
precise-upgrade path for every screen, Safe Walk says why no position arrives (never touches check-ins), place picker
"Use where I am / Search / Recent", private places use the same precise search, `showSessionExpiredDialog` crash fixed.
- **Results:** Android **792 / 0**; lint **0 / 102**; backend **773 / 0** (2 skipped, unchanged code); emulator walk-through PASS (list in
  `PHASE17D.md`); test account, its report and the test place deleted.
- **NOT VERIFIED:** physical phone (none connected); the "Rough" weak-signal path, the accuracy progress number and the
  cached-position filter are **unit-tested only** (the emulator's GPS always says 5 m); TalkBack; dark mode.
- **State left:** backend stopped; emulator running with app data cleared, location switched on, `adb reverse tcp:8080` set; test users 353/354 deleted (users back to 174); the build still points
  at `127.0.0.1`.
- **Next:** the user's review and push; 17E social UX. Later: unify the SOS screen's location handling (17F), the
  "about 1465 minutes" wording.

---
# Handoff: Phase 17C media (2026-11-01) — one local commit, NOT pushed

**Implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37)** (`docs/PHASE17C.md`). Photos at
3072 px / q92; one video per post (<= 60 s, <= 50 MB) re-packed on the phone with MediaMuxer and stripped again by the
server (`Mp4Sanitizer`); poster frame; upload progress, retry from the failed step, Remove deletes the server copy; no
partial post; `VideoView` player on tap only. Migration **V27** (next **V28**). No new dependency.
- **Results:** backend **773 / 0** (2 skipped); Android **749 / 0**; lint **0 / 102**; V27 dry run **15/15 + 5/5** +
  applied (backup `safesphere-before-V27.sql` in the session scratchpad); live MySQL **21/21**; emulator walk-through PASS
  (real H.264/AAC clips given a GPS atom, square and 90° portrait: the phone's and the server's copies carry no position or metadata box; rotation kept); 49 MiB upload 201 in 0.5 s.
- **NOT VERIFIED:** physical phone; TalkBack by ear; rotation while playing; the phone's 60 s refusal on a device (unit + server only); videos over 50 MB are refused (no
  transcoding yet).
- **State left:** 17C test accounts and their files deleted; backend and emulator left running, `adb reverse tcp:8080`
  active (the build still points at `127.0.0.1` from the A23 sprint). `gradle-wrapper.properties` (Gradle 9.7.1) was
  already modified before 17C and is NOT part of the commit; the build and tests pass with it.
- **Next:** the user's review and push; 17D location UX. Later recommendations: transcoding to fit 50 MB, resumable
  uploads.

---
# Handoff: Phase 17B account creation + phone OTP (2026-10-27) — one local commit, NOT pushed

**Implemented, compiled, tested, verified on MySQL, the Galaxy A23 and the emulator** (`docs/PHASE17B.md`). Root cause
of "cannot create an account": V25 waited for an e-mail that was never sent (no mail server). Phone OTP (V26) replaces
it; interrupted sign-ups resume at sign-in; "Your profile" follows the first verification; a location bug found on the
A23 (indoors, area lookups) is fixed in the shared layer.
- **Results:** backend **755 / 0** (2 skipped; fresh reports: the V25 test classes were removed, 25 tests added);
  Android **732 / 0**; lint **0 / 101**; V26 dry run **15/15 + 7/7** + applied;
  live MySQL **20/20**; A23 + emulator walk-throughs PASS.
- **NOT VERIFIED:** any real SMS (Fast2SMS not configured, no key); TalkBack by ear.
- **To send real SMS (updated 2026-11-09: Quick SMS `route=q`, about Rs 7 per code, the old OTP route is closed):** first Rs 100 top-up + balance, Dev API key → `backend/.env`: `OTP_PROVIDER=FAST2SMS`,
  `FAST2SMS_API_KEY=...` (template in `.env.example`); restart; the start-up log names the provider.
- **Until then (DEV_FILE):** codes are written to `OTP_DEV_FILE` (default `%TEMP%\kurinji-dev-otp.txt`) on the PC.
- **State left:** test accounts deleted (users 174 as before); the owner's account on the A23 was signed out through
  the app (its age was confirmed by the owner); signing in again asks for a mobile number + code (dev file).
  Backend and emulator stopped; `adb reverse` removed.
- **Next:** the user's review and push; 17C media (not started).

---
# Handoff: Phase 17A stabilise (2026-10-27) — one local commit, NOT pushed

**Implemented, compiled, tested, live-verified on MySQL, emulator-verified (API 37)** (`docs/PHASE17A.md`; audit and
the whole Phase 17 plan with the user's decisions: `docs/PHASE17_PLAN.md`). One age state + banner, `PROFILE_NEEDED`
next to Share, failed-photo retry, SOS location watchdog (60 s), debug cleartext list from `api.base.url`.
- **Results:** backend **756 / 0** (2 skipped); Android **719 / 0**; lint **0 / 99**; live MySQL **6/6** (+ age stored
  after the app's screen); emulator walk-through PASS (2 issues found there and fixed). No migration (next **V26**).
- **NOT VERIFIED:** physical phone (none connected); TalkBack by ear; the SOS watchdog on a real GPS.
- **For a real phone:** set `api.base.url=http://<PC LAN IP>:8080/` in `android/local.properties`, rebuild, allow TCP
  8080 in Windows Firewall; or keep `127.0.0.1` with `adb reverse tcp:8080 tcp:8080` while on USB.
- **State left:** test account deleted; app data cleared; `adb reverse` removed; emulator and backend stopped.
- **Next:** 17B phone OTP (needs an SMS provider account: MSG91 or Twilio, plus DLT registration for India).

---
# Handoff: Phase 16 Kurinji UX reset + stabilization (2026-10-26) — one local commit, NOT pushed

**Implemented, compiled, tested, emulator-verified (API 37)** (`docs/PHASE16.md`). The user decided U1–U4 on
2026-10-26 (comment count only; "Talking now" by newest comment; auto-load that ends; red brand with emergency UI by
siren + words + black/red ring). New red/black/white identity, Go-K icon, "Go. See. Tell." splash, social feed cards,
one-feed Discover, rebuilt post page, Saved tab (was Cocoon), plain vocabulary, shared one-shot location path.
- **Results:** backend **756 / 0** (2 skipped); Android **712 / 0**; lint **0 errors / 99 warnings**; Python not
  re-run (unchanged); live MySQL check of `commentCount` and `TALKING` PASS; emulator W1–W13 PASS with 4 findings fixed.
- **NOT VERIFIED:** physical phone (none connected), TalkBack by ear, real e-mail delivery (`docs/EMAIL_SETUP.md`
  explains Gmail app password / provider setup in `backend/.env`).
- **State left:** `emu16a/b@example.com` deletion requested (due date moved to now; erased by `AccountDeletionJob`);
  backend and emulator stopped; `adb reverse` removed; `android/local.properties` unchanged (127.0.0.1).
- **Next:** the user's review and push; real-phone pass of the new UI (A23 sprint, D10); real e-mail provider.

---# Handoff: V25 e-mail ownership verification (2026-10-26) — one local commit, NOT pushed

**Implemented, compiled, tested, verified on MySQL, live API and emulator** (`docs/EMAIL_VERIFICATION.md`, EV1–EV16).
A new account is UNVERIFIED and gets no session until its owner opens the e-mailed link (60 min, single use, only
SHA-256 stored); login of an unverified account = 403 `EMAIL_NOT_VERIFIED`; resend needs e-mail + password and always
answers 202; existing 171 accounts grandfathered as verified. New dependency: `spring-boot-starter-mail`.
- **Results:** backend **752 / 0** (2 skipped; +24); Android **701 / 0** (+9); lint **0 errors / 95 warnings** (+1: the
  new heading's `accessibilityHeading`, like the existing ones; 0 unused); Python **38 / 38**; V25 dry run **11/11 rejected + 6/6 accepted** on scratch copies, backup `safesphere-before-V25.sql`
  (scratchpad, outside the repo), **applied** (Flyway v25); live API **16/16** through a local SMTP sink (real SMTP
  path, real e-mail text, links extracted from delivered messages); browser page confirm + replay; emulator sign-up →
  "Check your email" → resend → refused sign-in with resend → confirm in browser → replaced link 400 → sign-in OK.
- **NOT VERIFIED:** delivery to a real external mailbox (no provider credentials configured; the user's personal
  Gmail was deliberately not used); a physical phone (none connected); TalkBack speech.
- **To enable real e-mail:** set `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` (provider API key),
  `MAIL_FROM` and an HTTPS `PUBLIC_BASE_URL` in `backend/.env` (template in `.env.example`); restart; the start-up log
  says "Verification e-mails go through SMTP …".
- **State left:** backend, SMTP sink and emulator stopped; all `liveev.*` / `emuev.*` accounts erased (users 171 = the
  171 before, incl. `abdul@…` created by the user today); no token, key or password left in files.
- **Next:** the user's review and push; Phase 16.

---
# Handoff: Phase 15 final integration and acceptance testing (2026-10-26) — one local commit, NOT pushed

**PARTIAL** (`docs/PHASE15.md`): everything runnable here passed; real phone (none connected), TalkBack by ear and a
production HTTPS deployment were not possible. No feature, no migration (next still **V25**), no backend change.
- **Results:** backend **728 / 0** (2 skipped); Android **692 / 0**; lint **0 / 94**; Python **38 / 38**; live API
  **119 checks** on MySQL + the real Python service (118 PASS + 1 script fault re-verified by hand); emulator (API 37)
  walk-through PASS; acceptance matrix **31/36 PASS, 0 FAIL, 5 NOT VERIFIED**.
- **Fixed (Android, `Phase15IntegrationFixesTest`):** 15-1 onboarding dots; 15-2 post failures off-screen; 15-3 Cocoon
  "Add a perspective"; 15-4 report rows "Sent …" + `SpokenText` (no "list.."); **15-5 session banners (deletion, walk,
  SOS) not re-shown on returning to a tab** (`MainActivity.anyBannerDue`); 15-6 deletion text names social content.
- **For the user (not changed):** dev DB holds 168 old `phase6–9.*@example.com` test accounts and an **ACTIVE SOS (id 24)
  since 2026-10-20 on the owner's personal account** (blocks its deletion / password change).
- **State left:** backend, service and emulator stopped, `adb reverse` removed, app data and test photo removed, font
  scale / night mode reset; all `live15.*` / `emu15.*` accounts erased (users 170), test place and this week's counters
  deleted; temporary service key deleted.
- **Next:** the user's review and push; then V25, A14, `AuthInterceptor` host scope, the A23 sprint (D10).

---
# Handoff: Phase 14 security, privacy and abuse-resistance audit (2026-10-26) — one local commit, NOT pushed

**Implemented, compiled, tested, live-verified on MySQL** (`docs/PHASE14.md`). Audit only: no feature, no migration
(next still **V25**), no new dependency, no Android change.
- **Findings (all Low, all fixed, regression tests in `backend/src/test/.../audit/Phase14SecurityRegressionTest`):**
  P14-1 others' profile post list ordered by `created_at` revealed the time the Q1 delay hides (now `visible_from`);
  P14-2 a moderator could decide a case (and appeal) about a perspective on their own post (now "took part");
  P14-3 simultaneous blocks/mutes answered 500, and on MySQL simultaneous reports deadlocked → 500 / lost report
  (now: block row on its own; reports lock the target row in a READ COMMITTED transaction + up to 3 attempts).
  No Critical / High / Medium.
- **Results:** backend **728 / 0** (2 skipped); Android **686 / 0**; lint **0 / 94**; Python **38 / 38**; live MySQL
  **7/7** (earlier failing runs disclosed in PHASE14 §4).
- **Recommendations (not changed):** scope the token to the API host in `AuthInterceptor`; body-size and upload
  concurrency limits on the HTTPS proxy (D12); token encryption on the phone (A14); V13 NULL CHECK (V25).
- **State left:** backend stopped; all `live14.*` accounts deleted (users 170 as before); no key or password in files.
- **Next:** the user's review and push (GitHub Desktop); then the A23 sprint (D10) / 13R.

---# Handoff: Phase 13F complete (2026-10-25) — two local commits, NOT pushed

**Second step (same day, the user's request):** the **Ready check** "Before you need it" in You → Safety (Safety Circle
people from the phone's offline copy, refreshed when the hub opens; automatic SMS on / off / permission / not
supported; location; notifications; the quick-SOS line; buttons to the Safety Circle or app settings; never a verdict)
and the **clean-up of all 49 unused resources** (40 strings, 3 dimens, 2 styles, 4 drawables). Android **686 / 0**,
lint **0 errors / 94 warnings, 0 unused**; emulator **E14–E16 PASS** (fresh account facts, Circle row refreshed after a
contact was added, "Open app settings" lands on Kurinji's page). Test data deleted again (users 170), emulator and
backend stopped. The first-step notes below still apply, except that the Ready check and the clean-up are now done.

**13F implemented, compiled, tested, emulator-verified** (`docs/PHASE13F.md`; plan `PHASE13F_PLAN.md`, approved by the
user with F1–F4 as recommended; visual direction `docs/design/kurinji-13f-direction.html`). Android only; no backend
change; next migration still **V25**.
- **What changed:** the Kurinji identity (mist/ink/bloom/yeoman colours, Young Serif + Mukta Malar bundled, postmark /
  ticket / butterfly / kolam, four motion moments), visible name Kurinji (package unchanged), Discover · Share · Cocoon
  · You, safety banners on every tab and reading screen, You → Safety hub, "Going there?" (save, let someone know,
  walk, check the way), SOS launcher shortcut + Quick Settings tile (countdown; non-exported entry), Rise retired
  with a one-time notice (D9), new onboarding/splash/icon, Safety Circle SMS say "Kurinji".
- **Results:** Android **682 / 0**; lint **0 errors / 144 warnings** (49 unused leftovers of removed screens);
  emulator (API 37) **E1–E13 PASS**, 4 bugs found there and fixed (theme font overriding headlines, "5" read as "s"
  on the SOS badge, tabs breaking mid-word at large text, SOS tile ignored on task screens). **Disclosed:** during the
  tile test a real SOS started on a throwaway account (my scripted cancel was late); ended as false alarm, server
  CANCELLED, 0 Safety Circle alerts.
- **NOT VERIFIED:** real phone, TalkBack speech by finger, animations frame by frame / low-end phones, live composer
  postmark, launcher shortcut (tile verified), locked-device tile, Android 13 add-tile dialog, Tamil headlines at 2.0×.
- **Not built (follow-ups):** Ready check in the hub (13F-D1); unused-resource clean-up (lint 49); Safe Walk local start
  (its own safety plan, D10); Tamil UI (V2).
- **State left:** emulator and backend stopped, `adb reverse` removed, app data cleared, test accounts deleted (users
  170 as before), test place and photo files removed; emulator display settings restored.
- **Next:** the user's review of 13F (and pushing via GitHub Desktop); then the A23 sprint (D10), Ready check, 13R.

---
# Handoff: Phase 13E AI v1 done (2026-10-25) — committed locally, NOT pushed

**13E implemented, compiled, tested, verified** (`docs/PHASE13E.md`; plan `PHASE13E_PLAN.md`, choices E1–E30 decided
under the user's delegation): a Python moderation service (`moderation-service/`, standard library only, internal, never
logs text) gives evidence from the norm-1 normaliser, a hashed Tamil / Tanglish / English lexicon (lex-1) and context
rules; the backend's pure `DecisionPolicy` (policy-1) decides publish / publish collapsed / hold for a moderator; the
machine never removes anything or restricts anyone; an outage publishes (new accounts wait at most 2 h); self-harm only
ever shows a support line (Tele-MANAS 14416) and is never stored; the phone runs the same rules in plain Kotlin for a
private nudge before sharing. The rung-2 linear model is built in Python and Kotlin but OFF: **no model ships** (no D3
dataset). `NoMlRuntimeTest` / the ML check of `EvaluationIsolationTest` are replaced by `ModelPlacesTest`.
- **Results:** backend **725 run / 0 failures** (2 skipped; 13D 694); Android **677 / 0** (13D 657); lint **0 / 54**
  (fresh run); Python **37 / 37**; Kotlin = Python on every shared vector; V24 dry run **59/59 + 19/19**, applied
  (dev DB **v24**, next **V25**); live MySQL with the real service **28/28** (first run 22/28: script faults, disclosed);
  emulator (API 37) **E1–E8 PASS**.
- **Rule evaluation (developer challenge set, not a D3 dataset):** held-out part B before fixes: collapse/hold precision
  1.00, recall 0.88, holds 1.00 / 0.79, 0 false actions on 31 banter / counter-speech / identity / safety / profanity
  items; the normaliser cuts obfuscation attack success from 0.91–1.00 to 0.00–0.09 (spacing 0.24).
- **Found and fixed:** the author's answer said `source: REPORTS` (would reveal reports, 13D M4); list rows showed a
  collapsed post's words (since 13D); a system case was headed "A guideline"; several Tanglish/English key collisions
  (dei/the, un/on, vaada/vada, koothi/kutty, baadu/paattu, Niger).
- **`moderation-service/restricted/` is git-ignored** (user's decision before pushing): the readable slur lexicon and
  the abusive test sentences stay on this machine (back them up privately); only the folder's README is in git. A clone
  without them works and its tests skip what needs them. They were never in any commit of the branch.
- **To run the check locally:** Python 3.10+ (none on PATH here), `MODERATION_SERVICE_KEY` (16+ chars) for both the
  service (`python -E -s -m kurinji_moderation.server`) and the backend (`backend/.env`); without a key the backend
  publishes unchecked and says so.
- **NOT VERIFIED:** real phone, TalkBack speech, dark mode, 320 dp / large fonts; any real-world precision (needs a
  pilot and `AI_REVIEWED` counts); the lexicon's review by ≥ 2 native speakers (required before any pilot).
- **State left:** backend, service and emulator stopped, `adb reverse` removed, app data cleared, all `live13e.*` /
  `emu13e.*` accounts deleted (170 users as before), test counters removed; no key or password left in files.
- **Next:** the user's review of 13E (and the restricted-folder decision); then 13F (new UI and navigation) or 13R
  (dataset: annotation guideline, licence checks, ≥ 2 annotators) to unlock rung 2.

---# Handoff: Phase 13D moderation v2 done (2026-10-24/25)

**13D implemented, compiled, tested, verified** (`docs/PHASE13D.md`; plan `PHASE13D_PLAN.md`, choices M1–M20 decided
under the user's delegation): reports on posts, perspectives and people (closed reasons; the reported person observes
nothing until 3 independent counting reports collapse the content, and is never told about reports); moderator
decisions (keep, hide while reviewing, collapse, remove; remind, pause perspectives / posting, suspend), strikes that
halve every 90 days, appeals to a different moderator on the shared appeals table; approve-first (Q6), edit history
only during a case (Q7), place merging (API), 72 h probation; pauses never touch reading, blocking, reporting or safety.
- **Results:** backend **694 run / 0 failures** (2 skipped; 13C 663); Android **657 / 0** (13C 641); lint **0 / 54**
  (fresh run; 13C recorded 94, not compared like-for-like); V23 dry run **51/51 + 18/18**, applied (dev DB **v23**,
  next **V24**); live MySQL **30/30**; emulator (API 37) **E1–E8 PASS**.
- **Found and fixed:** two V23 CHECK gaps (MySQL lets an UNKNOWN CHECK pass) before applying; a reporting moderator
  could decide the appeal; three emulator wording bugs; the 13C intermittent test failure (C-D7: random handle suffix
  of digits made "mod" reserved).
- **Follow-up (not done):** the same NULL weakness in V13 `ck_moderation_cases_state` (Phase 7, frozen).
- **NOT VERIFIED:** real phone, TalkBack speech, dark mode, 320 dp / large fonts, the hourly job on a live server.
- **State left:** backend and emulator stopped, `adb reverse` removed, all `live13d.*`/`emu13d.*` accounts and places
  deleted (170 users as before); app data cleared on the emulator.
- **Next:** the user's review of 13D; then the 13E plan (AI v1: normaliser, lexicon, linear model, Python service,
  DecisionPolicy, image classifier, outcome counters).

---
# Handoff: Phase 13C intent and discovery done (2026-10-23)

**13C implemented, compiled, tested, verified** (`docs/PHASE13C.md`; plan `PHASE13C_PLAN.md`, choices C1–C15 decided
under the user's delegation of 2026-10-23): the butterfly 🦋 saves someone else's Experience to the private Cocoon
(Want → Been → invitation to add a Been-here-too perspective or an own Experience); the author alone sees a count, and
only from 3 people up; Discover = a finite weekly Edition per launch city (In the city / Asks you could answer / theme
of the week, "Why am I seeing this?", ends with "Go make your own"), From your connections (30 days, ends), place pages
(exact-place Everyone posts only), category pages, place-name search (no people search); mute applies to every list.
- **Results:** backend **663 run / 0 failures** (2 skipped; 13B 635); Android **641 / 0** (13B 627); lint **0 / 94**
  (13B 91); V22 dry run **14/14**, applied (dev DB **v22**, next **V23**); live MySQL **29/29**; emulator (API 37)
  **E1–E17 PASS** — one wording bug found there and fixed with a test ("Saved shared this week").
- **Disclosed:** one intermittent backend failure in the first full run (`SocialAccountDeletionTest`, a 409 creating a
  test profile), not reproduced in two more runs; the helper now prints the server's message (C-D7).
- **NOT VERIFIED:** real phone, TalkBack speech, dark mode, 320 dp / large fonts.
- **State left:** backend and emulator stopped, `adb reverse` removed, all `live13c.*`/`emu13c.*` accounts, places,
  posts and butterflies deleted (170 users as before); app data cleared on the emulator.
- **Git:** git found only inside GitHub Desktop (`%LOCALAPPDATA%\GitHubDesktop\app-3.6.7\resources\app\git\cmd\git.exe`);
  13A+13B committed as c735022, 13C as 2e61d88. Remote `origin` = https://github.com/abdulrasikwork-max/kurinji.git
  (2026-10-24); its starter commit (README + Android .gitignore) merged in f20e3a0 (our README kept, .gitignore
  combined). Pushing needs the user's GitHub sign-in (GitHub Desktop "Push origin" or `git push -u origin main`).
- **Next:** the user's review of 13C; then the 13D plan (moderation v2: reports, holds, collapse, strikes, appeals).

---
# Handoff: Phase 13B experiences done (2026-10-22/23)

**13B implemented, compiled, tested, verified** (`docs/PHASE13B.md`; plan `PHASE13B_PLAN.md`, approved with Q1–Q12):
places (no creator, cells only), Experiences and Asks with precision EXACT/AREA/CITY/NONE, the 1–3 h delay for exact
places, bands instead of times, up to 6 photos stripped on the phone and re-encoded on the server, Perspectives (hide,
"This helped"), encrypted private places on the phone, Coil with memory cache only.
- **Results:** backend **635/0** (2 skipped); Android **627/0**; lint **0 / 91**; V21 dry run **79/79**, applied (dev DB
  **v21**, next **V22**); live MySQL **33/33**; emulator (API 37) **E1–E12 PASS** — two bugs found there and fixed
  (photo preparation always failed; a false delay line on a city post).
- **NOT VERIFIED:** real phone, TalkBack speech, dark mode, 320 dp / large fonts. No Android edit screen yet (API only).
- **State left:** backend and emulator stopped, `adb reverse` removed, all `live13b.*`/`emu13b.*` accounts, places,
  posts, photos, perspectives and files deleted (170 users as before); app data cleared on the emulator; new git-ignored
  folder `backend/social-media-store/` (empty). Nothing committed (git not on PATH here).
- **Next:** the user's review of 13B; then the 13C plan (butterflies, Cocoon, Discover/Edition, place and category
  pages, mute in lists).

---

# Handoff: Phase 13A identity foundation done (2026-10-22); new product direction = experience platform (Phase 13)

**Product direction changed (the user's final decision, 2026-10-22):** a social platform for real-world experiences with
the safety system as a trust layer (`docs/EXPERIENCE_PLATFORM_DESIGN.md`; decisions D1–D15 all taken; brand Kurinji,
trademark/domain/store checks still to do). **13A implemented, compiled, tested, verified** (`docs/PHASE13A.md`):
- **Results:** backend **569/0** (2 skipped); Android **592/0**; lint **0 / 89**; V20 dry run **30/30**, applied (dev DB
  **v20**, next **V21**); live MySQL **12/12** (a first attempt failed 5 checks on a script fault, disclosed);
  emulator (API 37) **E1–E7 PASS** — a navigation bug (Block closed two screens) found there and fixed with a test.
- **NOT VERIFIED:** real phone, TalkBack speech, dark mode, 320 dp / large fonts.
- **State left:** backend and emulator stopped, `adb reverse` removed, all `live13a.*`/`emu13a.*` accounts deleted (no
  profile/connection/invite/block/mute rows left), the app's data cleared on the emulator. `android/local.properties`
  still points at `127.0.0.1:8080` (unchanged; real-phone setting). Nothing committed (git not on PATH here).
- **Next:** the user's review of 13A; then the 13B plan (experiences, places, photos with Coil, comments, privacy
  precision and time bands) for approval. The earlier safety "Next" list below is folded into Phase 13 (D10).

---

# Handoff: C1 session continuity done (2026-10-22); Phase 11 (SOS without internet) + 10B (Rise) done; Phase 9 frozen

**C1 "session continuity" implemented, compiled, tested and verified 2026-10-22** (`docs/PHASE11.md` §8; the critical
finding C1 of `docs/AUDIT_NEXT.md`): an expired access token is no longer a sign-out. V19 `device_sessions` + rotating
refresh secrets (`POST /api/auth/refresh`, `/sign-out`, reuse detection, 60 s lost-answer grace, 90-day sliding idle
limit); the phone refreshes after idle time, keeps every local safety record when a refresh is refused ("Sign in to
sync"), and only a changed password / deleted / disabled account signs it out.
- **Results:** backend **501/0** (2 skipped); Android **561/0**; lint **0 / 85**; V19 dry run **3/3 + 14/14**, applied
  (dev DB **v19**, next **V20**); live MySQL **10/10** (L4 first failed on a script fault, disclosed); emulator (API 37,
  2-minute tokens) **E1–E7 PASS**. Four bugs found on the emulator and fixed with regression tests (§8.4).
- **NOT VERIFIED:** real phone (Galaxy A23), a 25-hour idle test on a device, TalkBack on the new banner/dialog.
- **State left:** backend and emulator stopped, `adb reverse` removed, all C1 test accounts deleted (no device session
  left in the dev DB); the C1 debug build is installed on the emulator (signed out). Nothing committed.
- **Next (the user's plan):** (2) Ready Check + Test my SOS + launcher shortcut + Quick Settings tile; (3) automatic SMS
  as a first-run choice; (4) Safe Walk starting locally; (5) SOS location retention; (6) product clean-up.

---

**Phase 11 + 10B implemented, compiled, tested and verified 2026-10-22** (full-autonomy brief; `docs/PHASE11.md`):
- **SOS without internet:** every SOS starts on the phone (Safety Circle SMS and location work offline);
  `SosRecorder` records it on the server later with a client reference (V18: `client_ref`, `started_offline`,
  end ≥ start CHECK). "I'm safe" works offline and is recorded later with its real time. Before this, an SOS without a
  server answer only showed "could not record" and texted nobody.
- **Rise "Proof" (10B):** tab "Rise", phone-only, encrypted per account; Doubt → Prepare → Try → Proof → Next; replay of
  the person's own record for the same self-doubt; safety worry opens SAFE preparation; quiet mode, optional screen
  lock, FLAG_SECURE, delete; no Home card, no notifications, no incident note (deviations R1–R10).
- **Results:** backend **482/0** (2 skipped); Android **527/0**; lint **0 / 85**; V18 dry run **10/10**, applied (dev DB
  **v18**, next **V19**); live MySQL **12/12** (after a first attempt stopped on two script faults, disclosed); emulator
  PASS with the backend really stopped (start offline, process death, recorded later, offline end) and for Rise. One
  bug found on the emulator and fixed with a regression test (screen stayed "not recorded" if the server answered
  while contacts loaded).
- **NOT VERIFIED:** real phone (incl. an automatic SMS sent offline on a real SIM), TalkBack, dark mode, Rise's
  screen-lock gate on a device with a lock.
- **State left:** backend and emulator stopped, `adb reverse` removed, all test accounts deleted; the new debug build is
  installed on the emulator (its account was deleted, so it will show the session-expired dialog).
- **Next:** the user's review; 10D (real phone + TalkBack for SOS offline and Rise); still deferred: token encryption
  (A14), password reset (A6), 10C collective proof (needs users and moderators).

---

**Phase 10A.4 "session continuity" implemented, compiled, tested and verified 2026-10-22** (the user's brief;
`docs/PHASE10.md` §11): token renewal (`POST /api/users/me/token`, always granted during an SOS or Safe Walk; the phone's
`SessionRenewer` renews below 12 h left) and the rule that another sign-in needs the account password to end an
ACTIVE SOS (V17 `started_session` only while ACTIVE, `ended_elsewhere`; password prompt on the SOS screen; the starting
phone re-checks when its SOS screen is visible). Backend **473/0** (2 skipped), Android **487/0**, lint **0 / 85**, V17
dry run **7/7** and applied (dev DB **v17**, next **V18**), live MySQL **9/9**, emulator PASS. Not verified: a renewal
on a device, TalkBack, real phone. All test accounts deleted; backend and emulator stopped; `adb reverse` removed.
**Next:** the user's review of 10A/10A.4; still deferred: token encryption (A14), password reset (A6).

**Phase 10A implemented, compiled, tested and verified 2026-10-22** (`docs/PHASE10.md`, design `docs/PHASE10_DESIGN.md`):
- 10A.1 honesty / identity / language: onboarding rewritten ("Safety that expands your world"), Settings, Profile,
  Safety Center and Home without placeholders (timeline and duplicate tile removed, Evidence Vault reachable without an
  SOS), tab "Nearby", summary leads with safe places, Circle and Safe Walk copy, accurate privacy notice, `feature/map`
  removed.
- 10A.2 account security: V16, `ActiveAccountCheck` (per-request account check + credentials-stamp revocation), password
  change (refused during an ACTIVE SOS/walk), Android `SessionEnder` (a stale token's 401 never signs out a newer session).
- 10A.3 coercion-aware deletion: password, refused during / 24 h after an emergency, 7-day grace with Home banner,
  cancel needs the password, postponed by an emergency, evidence files before rows, audit-referenced accounts
  anonymised, phone wipe on `account_deleted`.
- **Results:** backend **466 tests, 0 failures, 2 skipped**; Android **480/0**; lint **0 errors / 85 warnings**; V16 dry run
  **13/13**, applied (dev DB **v16**, next **V17**); live MySQL **23/23** (after a first attempt stopped by a script
  fault, disclosed); emulator (API 37) PASS for the changed screens. **NOT VERIFIED:** TalkBack, dark mode, onboarding
  pages on a device, real phone.
- **State left:** backend and emulator stopped, `adb reverse` removed, the 10A debug build installed on the emulator
  (signed out). Every account the checks created was deleted. A backup of `users` taken before V16 is in the session
  scratchpad only (not in the repo).
- **Next:** (1) the user's review of 10A; (2) recommended small "session continuity" step (`PHASE10.md` §9: token
  renewal for long SOS, a policy for a second session ending an SOS, token encryption); (3) 10B RISE "Proof" only when
  briefed. Phase 9 stays frozen.

---

**Phase 9B complete: implemented 2026-10-21, reviewed and accepted by the user on 2026-10-21, frozen**
(`docs/PHASE9.md` §27–§34): weekly, system-wide
outcome counters (V15 `evaluation_counters`, after commit, best effort, no place/person/fine time; CHECKs make a cell
unstorable), `moderation_cases.coordination_rule` (so coord-1 decisions are credited to the rule that asked), a weekly
snapshot (incl. the rules-2 shadow comparison, durable instead of the hourly log line), and the ADMIN-only
`GET /api/admin/evaluation` (rates with Wilson intervals, gate G1). Backend **453 tests, 0 failures, 2 skipped**,
Android **454/0**, lint 0 errors / 87 warnings, V15 dry run **19/19** and applied (dev DB **v15**, next **V16**), live
MySQL **21/21** (after a first attempt that failed on two script faults, disclosed in §32). Nothing user-visible; no
emulator check. Every rate is `NOT_ENOUGH_DATA` today (no real data; correct). Left in the dev DB: 29
`phase9.verify9b.*` USER accounts, 6 audit rows; counter rows from the check deleted. Backend stopped. **Next:** the
user's review of 9D.

**Phase 9D implemented 2026-10-21, awaiting the user's review** (`docs/PHASE9.md` §18–§26): neighbour join (active),
havens-alone cap = active rules **rules-1.1**, rules-2 in **SHADOW** (not promoted: no evidence). Backend **423 tests,
0 failures, 2 skipped** (final full run on the final code), Android **452/0**, lint 0 errors / 87 warnings, live MySQL
**19/19**, emulator E1–E4 PASS. Left in the dev DB: 16 USER accounts `phase9.verify9d.*` / `phase9.emu9d.*`, no test
signal. Backend and emulator stopped, `adb reverse` removed. `CLAUDE.md`, `docs/API.md` and `PHASE9_DESIGN.md` are
updated for 9D. **Next:** the user's review of 9D; 9B only when briefed.

**Phase 9C is complete: reviewed and accepted by the user on 2026-10-21** (`docs/PHASE9.md` §9–§16; the bridge file
`docs/PHASE9C_CONTINUATION.md` only records how 9C was closed). **Do not start 9D (or 9B) until the user briefs it
separately.**
Read `CLAUDE.md` (permanent rules and architecture), then `docs/PHASE9.md` (Phase 9 so far: 9C.0 fix, 9A attack
simulation and its findings, 9C) and `docs/PHASE9_DESIGN.md` (the Phase 9 design), then `docs/PHASE8.md` and
`docs/PHASE8_DESIGN.md` (Safety Intelligence as built). Per-phase details are in
`docs/PHASE4A–4D.md`, `docs/PHASE5.md`, `docs/PHASE6.md`, `docs/PHASE7.md`, `docs/TIMESTAMPS.md` and `docs/API.md`.
The folder became a git repository on 2026-10-21 (`fbe530b`); Phase 10A is committed on `main` (not pushed).

## 1. Status

| Phase | Implemented · compiled · tested | Manual verification |
|---|---|---|
| 4A–4D | yes | 4A, 4B not verified; 4C partial; 4D skipped by the user (see the phase docs) |
| 5 Evidence Vault | yes | Emulator verified; Galaxy A23 partial; rest deferred (`PHASE5.md` §11.5.1) |
| 6 Community Safety Intelligence | yes | Emulator (API 37) main flows; real phone not verified |
| 7 Verification, Trust, Moderation | yes | Emulator (API 37) PASS; TalkBack/dark mode not verified; Galaxy A23 deferred |
| **8 Safety Intelligence Engine** | **yes** (8A–8D) | **Emulator (API 37) PASS** for the picture card, radar cells, dark mode, "Check the way", walk unaffected (`PHASE8.md` §5); **TalkBack NOT VERIFIED; real phone NOT VERIFIED; a real pattern episode on MySQL NOT VERIFIED** |
| **9 AI/ML (in progress)** | **9C.0 reopen fix, 9A attack simulation (test code only), 9C coordination check (SHADOW by default), 9C.1 S15 Option B, 9C.2 group members + whole-group pause, 9C.3 guard fixes: yes. 9D (neighbour join, rules-1.1 havens cap, rules-2 SHADOW): yes, awaiting review. 9B (outcome counters, V15, ADMIN report): yes, complete (accepted 2026-10-21, frozen).** No learned model (gates not met; G1 `NOT_MET` per the 9B report) | 9C.0 **live 10/10**; 9C **live SHADOW 11/11**; 9C.1–9C.3 **live ACTIVE 25/25 on MySQL**; **emulator (API 37) moderator screens PASS** (`PHASE9.md` §14); TalkBack speech, dark-mode colours, Galaxy A23: **NOT VERIFIED** |

## 2. Phase 8 in brief

rules-1 Safety Intelligence on two axes (confirmed concern / how much is known; nothing ever says "safe"), computed on
read from exactly the visible signals; independent freshness; pattern memory (V14: one decaying number per ~1 km area
and group, from finished corroborated episodes, k = 3 episodes, reset on moderator removal); app-drawn radar area
cells (no heat map, no tiles); "Check the way" on the Safe Walk set-up screen, computed on the phone from ~5 km region
packs (the server never sees a route). Decision support only: isolated by source-scan tests from SOS, Safe Walk's
session and policies, the Safety Circle, evidence and moderation of people.

## 3. Last actual results (2026-10-21)

| Check | Result |
|---|---|
| `mvn test` | **388 tests, 0 failures**, 2 skipped (opt-in MySQL), after 9C.1–9C.3 (366 after 9C; 316 at the end of Phase 8) |
| `gradlew testDebugUnitTest` | **450 tests, 0 failures** after 9C.1–9C.3 (446 after 9C; 431 at the end of Phase 8) |
| `assembleDebug` + `lintDebug` | OK; **0 lint errors, 87 warnings** (unchanged) |
| V14 on MySQL 8.0 | dry run: 8 violations rejected; dev DB at **v14** |
| Live API (real defaults) | **22/22 PASS** |
| Emulator (Medium_Phone_API_37.0, demo settings) | `PHASE8.md` §5 items 1–8 PASS |

## 4. Environment state left behind

- **Backend: stopped.** Real defaults in `application.properties` (demo values were command-line arguments only).
  Database `safesphere` at **v15** since 2026-10-21 (9B); `evaluation_counters` empty (the live check's rows were
  deleted; the next start takes a fresh weekly snapshot); `area_patterns` empty; 6 signals have `reached_confirmed = 1`.
- Phase 9B live check (2026-10-21): 29 `phase9.verify9b.*@example.com` accounts (all USER), audit rows 2 ROLE_GRANTED +
  2 ROLE_REVOKED + 2 DISMISS; no test signal, case or flag left; the 4 pre-existing FLAGS cases unchanged.
- Test accounts: `phase8.smoke.*@example.com` (6), `phase8.demo.*@example.com` (10), random passwords never stored.
  Demo signals near `tdr1v9` (Bangalore) expire on their own. One CANCELLED demo Safe Walk of the Phase 7 emulator
  account. No account has MODERATOR or ADMIN.
- **Emulator: stopped** (Phase 8 build installed, the emulator account still signed in); `adb reverse` removed;
  notification permission granted on the emulator during the walk test.
- Phase 9 live check of 9C (2026-10-21, SHADOW): 7 `phase9.verify9c.*@example.com` accounts (all USER), 4 DISMISS
  audit rows; no test signal, case or flag left; backend stopped again. Shipped config: coordination mode SHADOW.
- Phase 9C.1–9C.3 live ACTIVE check and emulator check (2026-10-21): 56 `phase9.verify9c1.*` + 12 `phase9.emu9c1.*`
  accounts (all USER; 8 carry a 7-day pause + 1 strike from the checks), audit rows 8 RESTRICT + 3 DISMISS; no test
  signal, case or flag left; the 4 pre-existing FLAGS cases unchanged; the emulator account
  `phase7.smoke.emu.85cc36a3@example.com` was a temporary MODERATOR and is USER again. Backend and emulator stopped,
  `adb reverse` removed, night mode off; the emulator has the 9C.1–9C.3 debug build installed.
- Phase 9 live check of 9C.0 (2026-10-21): 6 `phase9.verify.*@example.com` accounts (all USER), 7 DISMISS rows in the
  audit log (append-only, 365 days); no test signal, case or standing row left; backend stopped again.
- `android/local.properties` unchanged (`api.base.url=http://127.0.0.1:8080/`).
- One pre-existing flaky backend test fixed (`EvidenceApiIntegrationTest`, `PHASE8.md` §2).

## 5. Deferred verification (exact)

1. Real phone (Galaxy A23): `PHASE8.md` §5 checklist items 1–8.
2. TalkBack walkthrough of the picture card, the radar summary and the way brief.
3. A real pattern episode on MySQL (a confirmed incident left to expire 3+ days, then three weekly ones).
4. Earlier phases' deferred items (unchanged): 4A/4B manual checks, 4C partial, Phase 5 A23 items, Phase 6 real phone,
   Phase 7 A23 + TalkBack.

## 6. Recommended next steps

- The user's manual checks above.
- ~~Home "Safety status: SAFE" sample card~~ **Done 2026-10-21** (the user chose the factual card over removing it or
  pointing to the area picture): the card states only what the phone knows. `HomeStatus` (in `HomeViewModel`) is
  *Checking* until the stored emergency record has been read and the walk recovery has finished, then *Nothing
  running* ("No SOS or Safe Walk is running on this phone. Home does not check your surroundings."), or *Safe Walk on*,
  or *Check-in missed* (MISSED / NO_RESPONSE: amber + warning icon). During an SOS the card is hidden, so the red banner
  is the one emergency element. A running SOS or walk shows at once; "nothing" only after the check. Teal or amber
  only, never green or red (`StatusTone`); the Preview badge, `SafetyState` (SAFE/CAUTION) and `hero_safe` are gone.
  Tests: `HomeViewModelTest` +5, new `HomeTextsTest` (6: scans **every** string Home's layouts and code reference
  for safety claims, the Safe badge is never used on Home, status words/icons unique, amber ⇔ warning icon).
  Results: `testDebugUnitTest` **442 tests, 0 failures**; `assembleDebug` OK; `lintDebug` **0 errors, 87 warnings**
  (unchanged). **Emulator: only "Nothing running" seen** (during the SOS tile checks below, incl. 320/360 dp and large
  text); the other states, TalkBack on the card and dark mode NOT VERIFIED; **real phone NOT VERIFIED.** Manual check: (1) cold start shows *Checking* briefly,
  then *Nothing running*; (2) start a Safe Walk → *Safe Walk on*; let a check-in lapse → *Check-in missed* (amber,
  warning icon); (3) start SOS → card hidden, red banner shown; (4) TalkBack reads "Right now: …" as one sentence;
  (5) dark mode contrast.
- **Home SOS cleanup: DONE 2026-10-21** (implemented, compiled, tested, emulator-verified; the user's requests).
  - Texts: badge `sos_badge_no_alerts` "No alerts yet" → `sos_badge_countdown` "5 s to cancel"; `cd_sos_tile` says the
    countdown can be cancelled and that SafeSphere texts the Safety Circle only if automatic SMS is switched on (4D).
    `HomeTextsTest` +1 (requires the opt-in condition, forbids "no alerts" / "not active yet" / "nobody is").
  - Layout: the badge moved from beside the title to **under "Emergency help"** in the tile's text column. Beside it,
    the badge kept its full width and squeezed the title (320 dp: "Emerg/ency"; 320 dp at 1.3×: "SO/S"; 320 dp at 2.0×:
    "SOS" and "Emergency help" disappeared). Probably the same with the old "No alerts yet" (same length; not tested).
    SOS behaviour, the tile's description and every other view are unchanged.
  - Results: `testDebugUnitTest --rerun` **443 tests, 0 failures**; `assembleDebug` OK; `lintDebug` **0 errors,
    87 warnings** (unchanged). Note: `HomeTextsTest` reads layouts from disk, which Gradle does not track as test
    inputs: after a layout-only change, run the tests with `--rerun`.
  - **Emulator (Medium_Phone_API_37.0), PASS:** TalkBack (focus via keyboard, "Display speech output") reads the tile as
    ONE item, exactly the new description, then "Double-tap to activate"; the badge is not a separate stop (all children
    non-focusable, re-checked after the layout change). Layout at 411 dp ×1.0, 360 dp ×1.0/1.3/2.0, 320 dp ×1.0/1.3/2.0:
    "SOS" always one line, "Emergency help" one line except 320 dp ×2.0 (wraps at the space), badge one line and
    unclipped. Emulator restored afterwards (size, density, font 1.0, TalkBack off, its notification permission denied).
  - **Not verified:** a real phone (Galaxy A23) and TalkBack swipe navigation by finger (adb cannot drive
    explore-by-touch; the keyboard "next item" shortcut did not fire over adb).
- **Phase 9 (AI/ML): design in `docs/PHASE9_DESIGN.md`; results in `docs/PHASE9.md`.** Verdict: no ML for anything a
  user sees (no real data, no ground truth, and retention deletes history by design). Phase 9 = an evidence layer: 9A
  adversarial simulator → 9B outcome counters (V15) → 9C coordination triage `coord-1` → 9D adjacent merge of concern
  reports + a `rules-2` candidate. A learned moderator-triage model is allowed only behind gates G1–G3 (§4.4 there).
  **Done 2026-10-21:** 9C.0 (reopen fix, live-verified) and **9A** (attack simulation through the real API, 37 tests,
  test code only). **Measured, SIMULATED:**
  - two accounts reach TAKE_EXTRA_CARE in ~1 h;
  - a ring of n accounts marks n(n−1)/2 areas, unseen by Phase 7;
  - two fake safe places turn "no information" into "no confirmed concerns";
  - one pair builds a "recurring" history in ~17 days;
  - a real warning is hidden or erased by as many accounts as back it;
  - honest groups backing 3+ of the same places count as one person;
  - 60 % of honest pairs on a 300 m street never confirm each other (grid boundary);
  - one account can scrape ~60 regions a day via `/area`.

  The coord-1, rules-2 and havens-alone prototypes fix the cases they target without false positives in the honest
  scenarios. **Decisions waiting for the user** are in `PHASE9.md` §4.

  **9C done 2026-10-21** (`PHASE9.md` §8): coord-1 R1/R2 in production, **SHADOW by default** (logs a count, opens
  nothing); no withdrawal lock for COORDINATION cases without flags (closes the detection oracle); moderators see the
  pattern in bands plus the guidance "not proof".

  **9C.1–9C.3 done 2026-10-21** (`PHASE9.md` §9–§16):
  1. **Option B** (S15): a moderator's DISMISS of a COORDINATION case lets the people counting behind THAT signal at
     that moment count separately on it (no migration; the closed case row is the mark; later joiners are not freed).
  2. **Group members as case participants** (`groupMembers`, pseudonyms + bands) and **RESTRICT `wholeGroup`**: one
     audited, appealable decision pauses every member (one by one was measured to be fragile).
  3. **Two coord-1 fixes found by the new tests:** the "dismissed group is not asked again" guard now looks at every
     signal backed only by the group's members; `Group.members()` (≥ 2 of the group's places) keeps an honest pair a
     ring confirmed once out of the list, the pause and the guard.
  4. Live ACTIVE check on MySQL **25/25**, emulator moderator screens **PASS** (TalkBack speech, dark-mode colours and
     the A23 not verified).

  **9C accepted by the user on 2026-10-21. Next: 9D, only when the user briefs it separately (9B likewise).** The shipped mode stays
  SHADOW; switching to ACTIVE needs an active moderator (still none in the dev setup).

  **9B complete: accepted by the user on 2026-10-21, frozen** (`PHASE9.md` §27–§34). What to do with it once real use starts: read
  `GET /api/admin/evaluation` (ADMIN) every few weeks; promote coord-1 to ACTIVE only with an active moderator team;
  promote rules-2 only on weeks of `SHADOW_PICTURE` counts plus moderator outcomes for the areas it changes; follow
  `UNREVIEWED_SHARE` (moderator capacity) and `RETIRED_AFTER_CONFIRMATION` (finding 6). Publication rule: system-wide
  only, values below 10 as "fewer than 10".

  Correction to an earlier note: Phase 8's
  `AreaIntelligenceModel` interface and shadow mode were **never built**; only `modelVersion` and the codes-only
  contract exist. A real route planner still needs an on-device routing graph and a new privacy review
  (`PHASE8_DESIGN.md` §16.4).

## 7. Open items / known limitations

- Phase 8 (`PHASE8.md` §7): thresholds are judgment calls; straight-line corridor; coarse Geocoder results; sparse areas
  read as "no information"; `reached_confirmed` checked every 15 min; pattern memory up to ~240 days; in-memory,
  per-instance region budget; operator-level correlation of a region download with a walk.
- Phase 7 (`PHASE7.md` §8), Phase 6 (`PHASE6.md` §6), Phase 5 (`PHASE5.md` §7, §10.4, §11.3): unchanged.
- **Phase 7 bug, found and FIXED 2026-10-21 (9C.0, `PHASE9_DESIGN.md` §1.3 F9):** after a moderator dismissed a
  COORDINATION case, the next `ModerationService.maintain()` run (≤ 15 min) opened a new one for the same signal,
  because only *open* cases were checked; each reopen also blocked withdrawal again. FLAGS and REREPORTED cases never
  looped. Fix: `maintain()` skips a signal that already has a DISMISSED COORDINATION case
  (`ModerationCaseRepository.existsBySignalIdAndOriginAndOutcome`). Closed cases are deleted after 90 days, so a
  dismissed signal can be looked at once more after that. Test `ModerationApiIntegrationTest.aDismissedCoordinationCase_
  isNotReopenedByTheNextRun_andItsReporterCanWithdrawAgain` **failed before the fix** (2 cases for the signal instead of
  1) and passes after it. `mvn test`: **317 tests, 0 failures, 2 skipped**. No migration and no API change. **Live
  verification on MySQL: 10/10 PASS** (real scheduled job, positive control; `PHASE9.md` §1). **Not verified:** the
  moderator screens on the emulator (unchanged by the fix).
- Google Play declarations needed for SMS (4D) and the microphone FGS (Phase 5). Release minification off (needs Gson
  keep rules, incl. Phase 6/7/8 DTOs). Spring Boot 3.5.16 past OSS support.
