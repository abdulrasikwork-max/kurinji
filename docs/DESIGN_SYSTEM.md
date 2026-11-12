# IRL design system ("Ink & Lime", Phase 17E; structure from Phase 16; renamed IRL 2026-11-03)

Concept: **a social app for first-hand experiences: open it, see something, react, comment, explore, share.**
Line: **OUT THERE. TOGETHER.** Results and decisions: `docs/PHASE17E.md` (17E), `docs/PHASE16.md` (structure, U1–U4).
Icon: `docs/design/irl-icon.md` (the Phase 16 Go-K, `kurinji-16-icon.md`, is history). (Phase 13F's "field journal"
system and Phase 16's red brand are retired; decision E17-1 superseded the colour part of U4, its emergency rule stands.)

> **2026-11-12 (decisions IRL-C1 to IRL-C6): two palettes.** The sections below describe "Ink & Lime", which is now the
> palette of the SAFETY screens only (unchanged). The social side (posts, people, places, You, settings, account, sign-in,
> onboarding) wears **Black Cherry `#5D0703` and Cream Vanilla `#EEDCC8`** through `ThemeOverlay.IRL.Social`:
> - **Where:** `core/ui/SocialPalette` (by feature package) → `ViewBindingFragment` gives those fragments a themed context
>   (views, sheets, dialogs, list rows); `MainActivity` colours the shared page and toolbar per destination; the bottom bar
>   carries the overlay. The overlay names only `c_*` colours and no emergency, safe, caution or hero role.
> - **Day:** page `c_page` cream, cards `c_surface` #FAF3EA, text `c_ink` #1F0A08 / `c_ink_soft` #5A413A, actions and the
>   fill of a given mark = cherry with cream words (10.5:1), tonal `c_soft` #E8C9B5.
> - **Night:** page #140707, cards #221010, text and actions cream; cherry is only a surface (panel, tonal fill).
> - **Brand surfaces** (launcher icon, opening screen): `brand_cherry`, `brand_cream`, `brand_blush` #E8A48C, fixed.
> - **Icon and mark (IRL-C10, approved by the owner on 2026-11-12; the Kolam butterfly, IRL-C9, was rejected the same day):** the
>   Tamil letter ஐ (said like the English "I"; a shout of delight; the old word for wonder), our own one-line drawing,
>   one colour (`docs/design/irl-icon.md`). In-app mark `ic_brand_mark` uses the text colour (theme attribute).
> - **Opening (IRL-C11, direction approved by the owner on 2026-11-12):** plain cherry launch screen, then ONE centred lockup every time: the mark writes
>   itself (`art_opening_meet`, 560 ms), "IRL" (Fraunces, 60sp) from 300 ms, "OUT THERE. TOGETHER." from 520 ms; a returning
>   person is taken on after 1000 ms. First run only: the lockup moves up (380 ms) and "YOUR WORLD. / YOUR PEOPLE. / YOUR
>   FREEDOM." lands line by line with "LET'S GO". No other words. Sections further down that describe the earlier opening are history.
> - **Type:** Fraunces SemiBold (`irl_serif`, OFL, `docs/licences/Fraunces-OFL.txt`) for headlines and the wordmark; the
>   rest Mukta Malar. Safety hero text and the opening capitals stay Mukta Malar.
> - **Tests:** `SocialPaletteTest` (pairs day and night, safety values pinned, no safety screen can wear the overlay),
>   `ContrastTest` (brand pairs; the k_* brand is still not red).

## Name (2026-11-03)

The visible name is **IRL** (In Real Life), everywhere a person reads the app's name: launcher label, strings, the
Safety Circle SMS ("IRL SOS: …"), the server's one user-facing message that named the app. The **package,
`applicationId` (`com.safesphere.app`), signing, database, API and code names stay SafeSphere**: this is a rebrand,
not a new app (the installed app updates in place). `KurinjiTextsTest` (class name kept) fails if any string value
still says Kurinji or SafeSphere, or if the `applicationId` changes. IRL takes "an": "an IRL account".

## Icon (2026-11-03, `docs/design/irl-icon.md`)

**"The Meet"**: a lime path and a white path rise from different places beyond the icon's edge and meet at one point,
under a lime sun placed off-axis (a sun, never a head: the mark is not a figure). Adaptive icon on an ink-to-deep-leaf
ground, themed (monochrome) layer, animated system launch mark. In the app: `ic_brand_mark` (leaf + ink by day, lime +
light at night), `ic_mark_on_ink` on the always-ink opening screen; the sign-in screen shows the adaptive icon itself.

## Opening (2026-11-03, `fragment_splash`, `SplashFragment`)

Always ink, edge to edge (`NavigationRules.destinationsOnInk`: ink behind the system bars, light bar icons), in light
and dark mode alike: the IRL lockup ("IN REAL LIFE"), the hero (`art_opening_meet`: the paths rise, the sun comes up,
two rings open), **OUT THERE. TOGETHER.** (lime eyebrow), **YOUR WORLD. / YOUR PEOPLE. / YOUR FREEDOM.** (white, the
last line lime), the supporting line, and **LET'S GO →** (the lime pill with ink words, pinned to the bottom).

- The button appears **only on the first run** (it opens the onboarding). A returning person is taken on by
  themselves after the usual short launch time (`SplashViewModel.waitsForStart`), so the opening never adds a tap on
  the way to SOS.
- Capitals come from `textAllCaps`; the strings are sentence case, so TalkBack reads words, not letters. The three
  lines are one spoken heading; the button says "Let's go: see how IRL works".
- Fit: the headline lines never wrap (all three shrink to one size that fits); the hero takes at most 30 % of the
  screen height and hides with large text on a narrow screen (the `MarksFit` ICONS budget); the words scroll if they
  must, the button never does.
- Motion: words rise in sequence (`Motion.revealInSequence`), the hero is an animated vector; with animations off,
  everything is simply shown.

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

## History: identity notes from CLAUDE.md §10 (moved verbatim on 2026-11-03)

Superseded identities (13F mist/bloom, Phase 16 red/black/white) and older UX bullets as CLAUDE.md held them.
The current rules are above and in CLAUDE.md §10.

> **Since 13F the Kurinji system governs every screen** (`docs/DESIGN_SYSTEM.md`): mist page, indigo ink, bloom
> violet-blue primary, yeoman orange for intent only, ochre for moderation/caution, red for emergency UI only; Young
> Serif headlines + Mukta Malar (Tamil + Latin) bundled; postmark / ticket / butterfly / kolam objects; four motion
> moments. `ContrastTest` and `KurinjiTextsTest` guard contrast and voice. (The navy/teal identity is superseded.)

> **Since Phase 16 (2026-10-26, decision U4) the red / black / white Kurinji system replaces the 13F mist/bloom look**
> (`docs/DESIGN_SYSTEM.md`, `docs/PHASE16.md`): brand red for actions and the save; black hero surfaces; Mukta Malar
> Bold headlines; social feed cards (person, photo, short thought, context chips, save + comments). Emergency UI is
> recognised by the siren icon + the words SOS/Emergency + its black-and-red treatment, never by colour alone. The
> bullet below about red is superseded.

> **Since Phase 17E (E17-1) the palette is "Ink & Lime"** (`docs/DESIGN_SYSTEM.md`): ink black is the action colour on
> light pages (lime words on it), lime #ADFF2F the action colour in dark mode; lime is otherwise a FILL (a given mark,
> chosen tags, today's state) with ink on it, never text or an icon on a light page; deep leaf green #3A6100 for
> accents read on light pages. The U4 emergency treatment and values are unchanged; red now means only emergency.

- ~~**Red only for emergency UI.**~~ (superseded by U4) All text pairs ≥ 4.5:1 contrast, control edges ≥ 3:1. Status never by colour alone.
  Never set `android:fontFamily` on the theme (it overrides text appearances); emergency digits use the phone's sans.
- Use theme attributes and existing styles/components; no hard-coded colours in layouts.
- Honesty labels: **Available** (works), **Preview** (design only), **Coming soon** (not built), **Sample** (placeholder data).
- No text claims a place or person is safe (Phase 8). The Home status card states facts only (`HomeStatus`: checking /
  nothing running / Safe Walk on / check-in missed; hidden during an SOS); `HomeTextsTest` scans every Home string.
- Emergency UI: clear, fast, high contrast, low cognitive load, large touch targets, `keepScreenOn` while active.
- Bottom nav (13F): Discover, ✚ Share (action), Cocoon, You. Reading screens keep the bar; everything else is a
  secondary destination (toolbar + back, no bottom bar). Nearby (`communityFragment`) is under You → Safety.
