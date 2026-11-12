# IRL social redesign (2026-11-11 and 12): units R1-R7

**Status: implemented, compiled, tested (1062 Android unit tests, 0 failures), lint 0 errors, manually verified on the
Samsung A23 (Android 14, dark mode, text size 0.8) against the live pilot server. NOT verified: light mode, large text
(2.0x), a 320 dp screen, TalkBack by ear, a feed with other people's posts (the pilot has one account), comments on a
post (the only public post is held for a moderator), photo/video upload in the new composer, log out. Nothing is
committed or pushed.** Android only: no backend, database, API or migration change.

## 1. Why (the audit, approved by the owner on 2026-11-11)

The app felt complex and safety-heavy and gave no everyday reason to open it. The approved direction: **a post is proof
you went outside; success is someone else going because of you.** Simple and social on top, the safety technology
intact underneath and one tap deeper. Limits set by the owner: keep the working backend and infrastructure, simplify
instead of rewriting, do not expand or redesign safety, no commit or push without approval.

## 2. What changed

| Unit | Change | Key classes / files |
|---|---|---|
| R1 bugs | The feed reads again when you come back (own posts changed, or after 5 min) and on a tab re-tap; a failed butterfly/Save is a short message, not a broken feed; the composer asks "Throw this away?" on Back and on the toolbar arrow when there is a draft; comments have no kind chooser (the kind sent is picked from the words) | `DiscoverViewModel.refresh/onShown`, `FeedFreshness`, `TabReselect`, `ComposeUiState.hasDraft`, `ComposeViewModel.onDiscard`, `PostViewModel.kindFor` |
| R2 composer | Media first, then words, "This is a question" chip, one optional place row, audience, "Add details" (closed), Share. With no place chosen the post takes the profile's city when that is a catalog city | `fragment_compose.xml`, `ComposeFragment`, `ComposeViewModel` (`homeCity`) |
| R3 navigation | Bottom bar **Home · Explore · ✚ Share · You**. Saved is a reading screen under You (nav id still `cocoonFragment`). Home head is one row: mark, name, city, search | `menu/bottom_navigation.xml`, `nav_graph.xml`, `NavigationRules`, `MainActivity`, `ExploreFragment` |
| R4 You | A profile head and five rows: Your posts, Saved, Connections, Safety, Settings and more. The old lower half of You lives in `MoreFragment` | `YouFragment`, `MoreFragment`, `fragment_you.xml`, `fragment_more.xml` |
| R5 small | Author photo/name on a card opens their profile; your own name opens You; about 19 strings reworded in a dry, human voice (never in safety, moderation, permission or costly-failure texts) | `DiscoverFeedAdapter`, `ExperienceCardAdapter`, `PostFragment`, `strings.xml` |
| R6 after the walk (2026-11-12) | The Home city is remembered on the phone per account; an empty Home says one thing; the private-note button says "Keep it just for me"; the dead "Why?" code is removed and About explains the order (section 3.1) | `HomeCityChoice`, `ViewerPreferences.homeCity`, `DiscoverViewModel.start/onCity`, `DiscoverFeed.build`, `ComposeFragment`, `about_body` |

## 3. Device walk on the A23 (2026-11-11 night) and what it found

Walked: Home (no city, choose a city, empty week), Explore tab (chips, search, empty answer), composer (draft guard on
Back and on the arrow, Only me, Add details, Share with no place), post page, Your posts, You, Saved, Safety, Settings
and more. One private "Only me" text post was created for the walk and deleted again.

| Found on the screen | Fix |
|---|---|
| The "▶ 0:03" label on a video was invisible in dark mode: its words used `k_surface`, which turns dark at night, on a dark label | New colour `k_on_video_badge` (#FFFFFF in both modes) in `Widget.Kurinji.VideoBadge` |
| The empty Saved screen's button still said "Explore Discover" | `saved_empty_action` = "Go to Home" |
| The Home tab showed the old folded-page icon | New `ic_nav_home` (a house, same 1.8 stroke); the page icon stays on the "Your posts" row |
| Your own name on your post said "Opens their profile" to TalkBack, and opened a second You on top of the first (Back looped) | `post_author_own_description`; `PostFragment` pops back to You when it is already below, else navigates |
| Lint: 31 unused resources left by the redesign | 29 strings, `item_safety_tile.xml` and style `Widget.Kurinji.Card.Tile` removed |

Lint: 0 errors, 127 warnings before the clean-up (31 of them UnusedResources); **0 errors, 96 warnings, no unused
resource** after it (re-run on the final tree).

### 3.1 R6 (2026-11-12): the walk's open points, resolved on the owner's instruction

| Point | What was done | Why this way |
|---|---|---|
| Home asked "Choose a city" at every start | `HomeCityChoice` remembers the city chosen on Home as "account id:city code" in the existing `viewer_preferences` DataStore. At start Home uses the remembered city, else the profile's catalog city, else asks once. A new choice on Home replaces it | The person's own choice, a code from the bundled list, never typed words, nothing about anyone else (Q3 untouched), never sent. Stored with the account, so another account on the phone starts without it. The profile was not changed for the person |
| Two empty messages on Home | `DiscoverFeed.build`: the "find people" prompt only appears under posts. No city → only "Choose a city"; an empty week → only the empty-week message with "Share yours" | One message, one action |
| "Share with Only me" | New string `social_share_only_me` = "Keep it just for me"; the other audiences keep "Share with …" | It is a note, not a share |
| Dead "Why?" code | Button, dialog, `DiscoverTexts.kt`, `FeedActions.why`, `CardRow.onWhy` and 12 strings removed; the order sentence is now a paragraph of About | The code could not run since R3, and a comment claimed About explained the order when it did not |

Seen on the A23 (dark mode, text 0.8) after installing this build: Home with no city shows one message; after choosing
Chennai and a forced stop, Home opens on Chennai without asking and shows only the empty week; the composer's button
reads "Keep it just for me" with Only me. **NOT seen on a screen:** the new About paragraph, the "find people" prompt
under posts (needs other people's posts), a second account on the same phone. The owner's Home city on the A23 is now
Chennai (change it with the city name in the Home head).

### 3.2 R7 (2026-11-12): the walk with a second person

The owner approved a second account on the live pilot. `test_friend` (user id 3, phone verified with one real SMS code
the owner read out) was driven from the laptop through the API; the A23 stayed signed in as the owner. The friend made a
profile, asked to connect, shared three text posts (an experience for Everyone in Chennai, a question, an experience for
Connections), replied to the owner's answer and marked it "This helped". One public place ("Marina Beach") was created.

**Seen working on the A23:** the request under "Requests for you", the person's profile ("Wants to connect with you" →
Accept → "Connected"), Home with "New in Chennai", "From people you know", "Talking now" and the end card; butterfly and
Save from a card (word, fill and spoken label change); Saved; the post page; a comment sent as the right kind (ANSWER on
a question, chosen by `PostViewModel.kindFor`), the reply indented under it, "This helped"; the profile's post list with
the head scrolling away; "I went" on a post without an exact place opens the composer.

| Found on the screen | Fix |
|---|---|
| Back on Home after commenting, the card still said "Comment" and showed the old marks | `PostViewModel`: a confirmed mark and a successful comment action call `FeedFreshness.changed()` (IRL-R10) |
| "I went" and "Going there?" under a question | `fragment_post.xml` row `goingRow`, shown for experiences only (IRL-R11) |
| With the keyboard open the bottom bar sat on top of it | `MainActivity`: bar visible = wanted on this screen AND keyboard closed (IRL-R12) |
| The owner's comment showed an initial although they have a photo | `CommentDto.toDomain` keeps the photo key (IRL-R13) |
| 2026-11-12, the owner on the A23: "I went" on the Marina post showed no rating. Checked on the pilot: that post is CITY precision (Chennai) with no place, so the composer opened as designed; the exact-place post of the next step had not been created yet | The button says "Share yours" where it opens the composer (post without an exact place, and Saved), "I went" only on an exact place (IRL-R14). Unit-tested; NOT yet seen on the A23 |

All four were seen fixed on the A23 after a reinstall. **Verified later (3.3):** "Been here" with a rating on an exact place. **NOT verified:** a photo or video post from another person (held for a moderator for the account's first 72 h); mute, block, remove
connection, report (not tapped: they change the live relationship); today's state; a decline; invite codes.
The test account stays on the pilot; its password is in a file outside the repository on the owner's laptop.

### 3.3 "Been here" on an exact place (2026-11-12 afternoon, A23, light mode, text 0.8, live pilot)

The friend's second Marina post (EXACT precision, place "Marina Beach") became visible; walked as the owner, every tap
guarded (IRL in front, no call):

| Step | Seen |
|---|---|
| Home, "New in Chennai" | the exact-place post, chip "Marina Beach" |
| Post page | the button reads **"Been here"** (the city-level Marina post reads "Share yours") |
| Tap "Been here" | sheet "How was it?": 1-5, "You've been here. You have not rated it.", Share your experience, I haven't been here, Done |
| Tap 4 | "Your rating: 4 out of 5", the chip speaks "4 out of 5, your rating", "Take back my rating" appears |
| Done, open again | the rating is still 4 |
| Take back my rating | back to "You have not rated it", five plain numbers |
| Tap 4 again, Done, place chip | place page "Marina Beach": **"You've been here · 4 out of 5"**, the post listed, "That's all for now.", **no public average** (expected: one rater, five independent groups are needed) |

No defect found. One thing noted, not changed (the owner decides): after rating, the POST page still says "Been here"
(the post answer does not carry the visit; only the place page shows "You've been here · 4 out of 5"); the toolbar of a
place page says "Posts". Left on the pilot: the owner's visit to Marina Beach with rating 4.

## 4. Seen on the walk, NOT changed (the owner decides)

- Comments print "Shared this week" after the name; "this week" alone would read better (shared string).
- A post that only you can see shows "No comments." and no way to write one (existing behaviour).
- The Explore tab keeps the city it started with; changing the city on Home later does not change it.
- Comment kinds: "nice!" is still stored as BEEN_HERE on the server, only no longer shown.
- Needs the backend or a rule change, not started: one "Want to go" instead of butterfly + Save, naming connections
  who reacted, a "Today" time band, a photo post without words, the new-account photo hold for the pilot, older posts
  in a thin week. Also not started: one-screen onboarding, shorter sign-up, an activity list, invite by link.

## 5. Security and privacy

No new permission, request or dependency. One new stored value: the person's own Home city code with their account id (R6), on the phone only. Saved, connections and blocks stay visible only to their owner.
Safety screens, the SOS shortcut, tile and shake are unchanged; SOS inside the app is You → Safety → SOS (one tap more
than before). `FeedFreshness` is memory only. Isolation and wording tests are green (`SocialTextsTest`,
`Phase17G3SmartUnderstandingTest` (now: one account id and the own city code are the only things kept), `HonestyTextsTest`, `KurinjiTextsTest`, `ContrastTest`, `GoingThereTest`, `NavigationRulesTest`, `IrlRedesignTest`).

## 6. Viva notes

- **R6 city:** *Why is the city stored when "nothing is stored"?* The rule (Q3) is about OTHER people; this is the person's own setting, like the strong-language switch in the same DataStore. *Why with the account id?* So a second person signing in on the phone does not inherit it. *Lifecycle:* read once in `DiscoverViewModel.start()`, written in `viewModelScope` when a city is chosen; it survives process death and sign-out, and is ignored for any other account.
- **What / why:** fewer decisions before a post, a feed that is current, safety one tap deeper. The identity is
  going outside, not safety (direction of 2026-10-22, sharpened on 2026-11-11).
- **Flow:** no API or database change. The composer still sends the same post request; a comment still carries a kind,
  now chosen by `PostViewModel.kindFor` instead of the person.
- **Lifecycle:** `DiscoverViewModel.onShown()` runs when Home becomes visible; `FeedFreshness` lives in `AppContainer`
  (application scope, memory only), so it is lost with the process, which only costs one extra refresh.
- **Likely questions:** Why is Saved not a tab? (It is a list you return to rarely; Explore is used more.) Why is the
  city remembered now? (See R6: it is the person's own setting, kept per account.) Why did a white label turn dark? (A colour that
  changes with night mode was used on a surface that does not change.) Does hiding Safety one level down weaken it? (No:
  the shortcut, tile, shake and banners do not pass through You.)
