# Kurinji design system ("Ink & Lime", Phase 17E; structure from Phase 16)

Concept: **a social app for first-hand experiences: open it, see something, react, comment, explore, share.**
Line: **Go. See. Tell.** Results and decisions: `docs/PHASE17E.md` (17E), `docs/PHASE16.md` (structure, U1–U4).
Icon concepts: `docs/design/kurinji-16-icon.md`. (Phase 13F's "field journal" system and Phase 16's red brand are
retired; decision E17-1 superseded the colour part of U4, its emergency rule stands.)

## Colour (`res/values/colors.xml`, `values-night/colors.xml`; `ContrastTest` checks every pair)

| Role (resource) | Day | Night | Used for |
|---|---|---|---|
| Page (`k_page`) | #F3F4EE | #0B0C0A | The page |
| Surface (`k_surface`) | #FFFFFF | #161814 | Feed cards, sheets, the comment box |
| Ink / ink soft (`k_ink`, `k_ink_soft`) | #0E100C / #4A4F44 | #F1F4EA / #B7BDAB | Text |
| **Action** (`k_action` / `k_on_action`, `colorPrimary`) | ink #0E100C with lime words | lime #ADFF2F with ink words | Buttons, links, chosen pills |
| **Lime** (`k_lime` / `k_on_lime`, `colorLime`) | #ADFF2F with ink on it | same | A FILL only: a given butterfly, Saved, chosen tags, today's state. Never text or an icon on a light page (1.2:1) |
| Leaf (`k_leaf`, `colorAccentText`) | #3A6100 | #ADFF2F | Accents that must be read on a light page ("Saved this week", the Tip label) |
| Panel (`k_panel`, `colorPanel*`) | #0E100C + #F3F4EE | #1B1E17 + #F1F4EA | The ink head on You, a person's profile, place pages |
| Ochre (`colorCaution`) | unchanged | unchanged | Moderation and caution, always with an icon and words |
| Emergency (`colorError`, `emergency_surface`) | #C8102E on #0B0B0B | unchanged | Emergency UI only (see below); values pinned by `ContrastTest` |
| Avatars (`avatar_1..6`) | six strong colours | same | A person's initial when they have no photo |

Text pairs ≥ 4.5:1, control edges ≥ 3:1, day and night. Status is never colour alone: a given mark also changes its word
("Save" → "Saved") and its spoken description.

**Emergency (decision U4, unchanged by 17E).** Emergency UI never relies on hue: every emergency surface is **black with
a thick red ring** (`Widget.SafeSphere.Card.Emergency`, `Widget.SafeSphere.Button.Emergency`), carries the **siren**
(`ic_siren`) and says **SOS / Emergency** in words. Since 17E red is no longer the brand, so red again means only
emergency (an extra cue, not the rule). Safety screens kept their layouts; they only inherit the neutral tokens.

## Type (`res/font`, `values/type.xml`)

Mukta Malar throughout (Tamil and Latin drawn together, OFL, `docs/licences/`). **Bold** (`kurinji_display`) for
headlines, names, buttons and the wordmark, set large and tight; Medium for a post's words on a card; Regular for body.
Labels are sentence case (no letter-spaced capitals). The SOS badge keeps the phone's sans for lining figures.

## Components

| Component | Where | What it does |
|---|---|---|
| Feed card (`item_experience_card`, `CardBinder`) | Discover, place/category pages, profiles, Your posts | Photo first with the category tag (or a quote panel without a photo), avatar + name + time, the post's words, place / when / "Tip inside" chips, then the marks: butterfly "Inspired" · Comments (count, U1) · Why? · Save |
| Marks fit (`MarksFit`) | Feed cards, You head, Saved rows | Text budget = screen width in dp ÷ font scale. ≥ 400: every word; ≥ 320: the comment button drops its word; below: icons only (Why? = info icon), You's photo and name stack, Saved's actions stack. Spoken descriptions never change |
| `AvatarView` | Cards, comments, people rows, post page, You | The profile photo when there is one (memory cache only), else the initial in a stable colour |
| Discover (`fragment_discover`, `DiscoverFeed`) | Discover | Head (wordmark, search) and the city as the title ("Discover · Chennai ⌄", opens the city sheet) scroll away with the feed and come back on scroll-up; then shortcuts, Talking now, New in the city, theme, people you know, "You're all caught up" |
| City sheet (`CityPicker`, `sheet_city_picker`) | Discover title, composer CITY, profile editor | Searches the bundled catalog on the phone ("What you type is not sent anywhere") |
| Daily-state sheet (`DailyStates`, `sheet_daily_state`) | You | Eight drawn icons + words (moods and intents, never a place or presence), clear; the note says connections only, ends at midnight |
| Post page (`fragment_post`) | Post | Person, photos edge to edge, words, chips, Tip box, butterfly + Save, "Only you see…" note, I went / Going there?, comments with the box pinned |
| Saved (`fragment_saved`, `item_saved`) | Saved tab | "Only you can see what you save"; rows with photo, author · city, saved band; I went / Going there? / Remove |
| You (`fragment_you`) | You | Ink head: photo with lime ring and camera badge, name, handle · city, today's state chip + note, Edit profile; your experiences; compact Safety grid (SOS tile in the U4 treatment, Safe Walk, Safety Circle, Nearby, All safety tools) |
| Buttons | everywhere | `Button.Tonal` / `Button.Quiet`: icon next to its label (`iconGravity textStart`); `Button.Mark`: the marks |

## Motion

Launch: the stem rises and the chevron steps in, then "Go. See. Tell." lands word by word. Saving: the bookmark flies to
the Saved tab with one haptic and the spoken "Saved. Only you can see it." Screens fade in with a small rise. With the
system's "remove animations" every moment becomes a plain state change.

## Navigation

Discover · ✚ Share · **Saved** · You (the Saved tab's id stays `cocoonFragment`). Bar on tabs and reading screens;
toolbar + back on task screens. Safety banners on every tab and reading screen, never on the SOS screen.

## Words

Plain words a first-time user knows: **Inspired** (the butterfly), **Save / Saved**, **I went**, **Comments**, **Tip**,
**Question**, **Talking now**, **You're all caught up**. `KurinjiTextsTest` forbids the retired vocabulary (cocoon,
perspective, postmark, edition, "for the next person") in anything people read, plus emoji, slang and hype.

## Honesty rules kept

No text claims a place or person is safe; no counts except the comment count (U1) and the author's private butterfly
line (from 3); saves are counted for nobody; no ranking words; no "avoid"; moderation feedback in ochre; no endless
feed (U3).
