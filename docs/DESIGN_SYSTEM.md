# Kurinji design system (Phase 16)

Concept: **a social app for first-hand experiences: open it, see something, react, comment, explore, share.**
Line: **Go. See. Tell.** Results and decisions: `docs/PHASE16.md`. Icon concepts: `docs/design/kurinji-16-icon.md`.
(Phase 13F's "field journal" system, with mist/bloom colours, Young Serif, postmarks, tickets and kolams, is retired.)

## Colour (`res/values/colors.xml`, `values-night/colors.xml`; `ContrastTest` checks every pair)

The resource names stay from 13F so every layout keeps working; their roles changed.

| Role (resource) | Day | Night | Used for |
|---|---|---|---|
| Page (`k_mist`) | #F5F4F2 | #0D0D0E | The page: off-white, not beige |
| Card (`k_card`) | #FFFFFF | #19191B | Feed cards, sheets, the comment box |
| Ink / ink soft | #121212 / #4A4A4A | #F4F4F4 / #BEBEBE | Text |
| **Brand red** (`k_bloom`, `colorPrimary`) | #D0102A | #FF4F5E | Buttons, the save, links, chosen chips, the Tip bar |
| Save (`k_yeoman*`, `colorIntent*`) | the brand red | the brand red | The butterfly (saved = solid red with the word "Saved") |
| Hero (`hero_*`) | #121212 + white | #1E1E21 + white | Status card, dark panels |
| Ochre (`colorCaution`) | #8A5A00 | #E8B54A | Moderation and caution, always with an icon and words |
| Emergency (`colorError`, `emergency_surface`) | #C8102E on #0B0B0B | #D42A3D on #000000 | Emergency UI only (see below) |
| Avatars (`avatar_1..6`) | six strong colours | same | A person's initial; white on each reaches 4.5:1 |

Text pairs ≥ 4.5:1, control edges ≥ 3:1, day and night. Status is never colour alone.

**Emergency (decision U4).** Red is the brand now, so emergency UI never relies on hue: every emergency surface is
**black with a thick red ring** (`Widget.SafeSphere.Card.Emergency`, `Widget.SafeSphere.Button.Emergency`), carries the
**siren** (`ic_siren`) and says **SOS / Emergency** in words. Brand buttons are solid red without a ring or siren.

## Type (`res/font`, `values/type.xml`)

Mukta Malar throughout (Tamil and Latin drawn together, OFL, `docs/licences/`). **Bold** (`kurinji_display`) for
headlines, names, buttons and the wordmark, set large and tight; Medium for a post's words on a card; Regular for body.
Labels are sentence case (no letter-spaced capitals). The SOS badge keeps the phone's sans for lining figures.

## Components

| Component | Where | What it does |
|---|---|---|
| Feed card (`item_experience_card`, `CardBinder`) | Discover, place/category pages, profiles, Your posts | Avatar + name + kind/time, the photo, the post's own words (4 lines), place and "Tip inside" chips, then Save (butterfly) · Comments (count, U1) · Why? |
| `AvatarView` | Cards, comments, post page, You | A person's initial in a stable colour (no profile photos exist) |
| Discover feed (`DiscoverFeed`, `DiscoverFeedAdapter`) | Discover | Shortcuts (Near me + categories) → Talking now (sideways) → New in the city (a question after every third post) → theme → people you know → loading or "You're all caught up" |
| Post page (`fragment_post`) | Post | Person, photos edge to edge (swipe), every word, chips (place opens its page, when), Tip box, Save / Going there? / I've been, Comments with the box pinned at the bottom |
| Comment row (`item_comment`, `CommentAdapter`) | Post | Avatar, name · kind · time, words, notes, Reply / More |
| Saved (`fragment_cocoon`) | Saved tab | Want to go / Been there pills; rows with photo or initial; I've been / Going there? / Remove |
| Pills (`pillChipStyle`), fact chips (`contextChipStyle`) | Discover, cards, post | Chosen pill = solid red with white words and a check |

## Motion

Launch: the stem rises and the chevron steps in (~600 ms, Android 12+ system splash), then "Go. See. Tell." lands word
by word (~1 s, inside the existing minimum). Saving: the butterfly flies to the Saved tab (13F flight kept) with one
haptic. "I've been": the row bounces once. Screens fade in with a small rise. With the system's "remove animations"
every moment becomes a plain state change.

## Navigation

Discover · ✚ Share · **Saved** · You (the Saved tab's id stays `cocoonFragment`). Bar on tabs and reading screens;
toolbar + back on task screens. Safety banners on every tab and reading screen, never on the SOS screen.

## Words

Plain words a first-time user knows: **Save / Saved**, **Comments**, **Tip**, **Question**, **Want to go / Been
there**, **Talking now**, **You're all caught up**. `KurinjiTextsTest` forbids the retired vocabulary (cocoon,
perspective, postmark, edition, "for the next person") in anything people read, plus emoji, slang and hype.

## Honesty rules kept

No text claims a place or person is safe; no counts except the comment count (U1) and the author's private save line;
no ranking words; no "avoid"; moderation feedback in ochre; no endless feed (U3).
