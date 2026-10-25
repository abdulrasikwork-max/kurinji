# Phase 13F — Kurinji UI/UX, safety integration and final integration: plan

**Results: `docs/PHASE13F.md`** (implemented, compiled, tested, emulator-verified 2026-10-25; Ready check F12 not
built, deviations 13F-D1–D9).

**Date:** 2026-10-25 · **Status: approved by the user 2026-10-25 with F1–F4 as recommended** (Kurinji visible now;
fonts downloaded; SOS shortcut/tile start the countdown; Heads-up = editable note, no location). F5–F21 decided under
the user's delegation.
Visual direction (mock-ups, colour, type, motion): `docs/design/kurinji-13f-direction.html` (also published as a private
artifact for review). Design basis: `docs/EXPERIENCE_PLATFORM_DESIGN.md` §2, §5, §9, §10.4, §15 (D1, D5, D9, D10, D14).
Builds on 13A–13E (`docs/PHASE13A.md` … `PHASE13E.md`). **Android only: no backend change, no migration (next stays
V25), no new library.**

## 1. Where the app stands (inspection, 2026-10-25)

- The app is still SafeSphere on the outside: tabs **Home · Safety · Nearby · Rise · Profile**, navy/teal palette,
  system fonts. Every experience feature built in 13A–13E sits **behind Profile → "Your profile and connections"**
  (Discover, Cocoon, Share, your experiences, private places, guidelines, reports, the strong-language switch).
- Posts are shown everywhere as `PersonRowAdapter` text rows ("Experience · At X · This week · Everyone"): no photo,
  no place stamp, no tip in lists. The butterfly is an emoji in a text button. Discover opens on a search field.
- Home carries safety guarantees that must survive the redesign: the red active-SOS banner, the Safe Walk banner
  (amber when a check-in is missed), the amber pending-deletion banner (Phase 10A: visible for the whole grace
  period), the calm "sign in to sync" banner (C1), the factual status card and the SOS tile.
- Guard tests that pin today's screens: `HomeTextsTest` (Home layouts), `HonestyTextsTest` (onboarding lines, Home
  files), `SocialTextsTest` (social layouts; forbids "safe", "check-in", "live", counts), `RiseTextsTest` / `RiseTest`
  (Rise isolation), `ModerationV2Test` / `AiModerationTest` (amber, no "report" to authors).

## 2. Design direction (summary; details and mock-ups in the HTML file)

**"A field journal from the hills, not a feed."** Every post is three facts, and each fact gets one object:

| Fact | Object | Rule |
|---|---|---|
| Where | **Postmark** in violet "office-stamp" ink: place name on the ring, month and time of day below | Area posts show a grid reference, city posts the city, "no place" a blank cancellation. Exactly what the author chose to reveal |
| When | Time **band** ("this week") | Never a timestamp (unchanged) |
| What the next person should know | **Ticket**: the tip, notched like a bus ticket | The hero of every card |
| Intent | **Butterfly** (Want → chrysalis in the Cocoon → Been = butterfly) | Drawn, not an emoji |
| Nothing here yet | **Kolam** line drawing (empty states only) | A kolam is drawn at the door for whoever comes next |

- **Colour ("mist, ink, bloom"):** page = cool hill mist (deliberately not cream paper); ink = indigo night;
  **Bloom** = Kurinji violet-blue (brand, actions, postmarks, Been); **Yeoman** = Tamil Nadu's state butterfly orange,
  used only for intent (the butterfly, Want); **Ochre** = moderation notices (with icon and words); **red = emergency
  UI only** (unchanged). Dark mode "Kurinji night". Every text pair ≥ 4.5:1 in both themes (tested, F21).
- **Type:** display **Young Serif** (headlines only), UI/body **Mukta Malar** (drawn for Tamil and Latin together, so
  Tamil, Tanglish and English share size and weight). On Android 10+ display text falls back to a matching Tamil face.
- **Motion:** four moments only (butterfly flight into the Cocoon tab ~600 ms, chrysalis emergence ~700 ms, stamp on
  share ~280 ms, kolam drawn once ~900 ms) plus a short fade-rise between screens; all respect the system
  "remove animations" setting (state change + spoken confirmation instead).
- **Voice:** plain, warm, specific; humour only in a few fixed places (empty states, the Edition's end). No slang
  campaign, no emoji in UI text (the butterfly is drawn), no engagement words (existing tests plus F21).

## 3. Decisions

**Need the user (asked separately):** F1 brand on screen, F2 font download, F3 SOS quick-entry behaviour, F4 the
"Let someone know" design (D10 asked for a short Heads-up design first). Recommendations are marked.

| # | Decision | Recommendation / decided under the delegation | Why |
|---|---|---|---|
| **F1** | Visible brand | **Recommended:** "Kurinji" as the launcher label, wordmark and in every visible text; package and applicationId stay `com.safesphere.app`; code names unchanged (CLAUDE.md §1) | The phase is the Kurinji identity; D1 checks (trademark, domain, store) stay open and are noted in the docs |
| **F2** | Fonts | **Recommended:** download Young Serif (Regular) and Mukta Malar (Regular, Medium, Bold) TTFs from `github.com/google/fonts` (OFL), ~0.7 MB, into `res/font`; licence text in `docs/licences/`. Fallback if declined: system serif + sans | Typography is the strongest identity lever; no network font loading (design §9.11) |
| **F3** | SOS launcher shortcut + Quick Settings tile | **Recommended:** open the SOS screen with the cancellable 5 s countdown **already running** | These entries exist for speed; the countdown is the protection, as today |
| **F4** | "Let someone know" (Heads-up) | **Recommended:** the person's own share sheet with an editable note ("I'm going to {place} this {daypart}. I'll message you when I'm back."), **no location, no link**; Kurinji sends and stores nothing | Care without monitoring; works offline; nothing new on the server |
| F5 | Navigation | Bottom bar **Discover · ✚ Share · Cocoon · You**; Share is an action (opens the composer), not a tab; the app opens on Discover. The bar also shows on reading screens (post, place and category pages, a person's profile) so the butterfly can fly into the Cocoon tab; task screens (composer, place picker, safety flows, settings) keep toolbar + back | Design §9.2; reading flows stay one tap from the Cocoon |
| F6 | Safety banners | Moved from Home into a **banner host in `MainActivity`** shown on every tab and reading screen (never on the SOS screen): active SOS (red), Safe Walk (amber when overdue), pending deletion (amber), sign in to sync (calm). Logic moves unchanged from `HomeViewModel` into an activity-scoped `SessionBannersViewModel` | Keeps the 4B / 10A.3 / C1 guarantees on every screen instead of one tab |
| F7 | Home, Safety and Nearby tabs | Retired as tabs. **You → Safety** hub = status card (facts only) + SOS tile + Safe Walk / Check the way + Safety Circle + Ready check + Evidence Vault + emergency history + Nearby heads-ups (Phase 6/8 radar) + safety settings + the honest line "Kurinji never contacts emergency services for you" (moved from onboarding) | Design §5: safety appears when someone moves from reading to going, and lives in You → Safety |
| F8 | Rise (D9, decided) | `feature/rise`, its layouts, strings and tests are removed; a small `RiseRetirement` deletes `files/rise/*` once and shows a one-time notice ("Rise has been retired. Its notes on this phone were deleted. Nothing from Rise was ever on a server."); the account-deletion wiper no longer calls Rise | D9: retire when 13F ships, with a clear notice |
| F9 | "Going there?" | A bottom sheet in a new bridge package `feature/going` (navigation only: imports neither social nor safety code): **In your Cocoon / Save to Cocoon** (result returned to the opener), **Let someone know** (F4), **Walk with check-ins** (opens Safe Walk with the place name filled in; the person still presses Start), **Check the way** (same screen, way check filled in). Shown on others' Experiences, place pages and Cocoon "Want" items | Design §5 bridge; social and safety stay separate (tested) |
| F10 | Safe Walk / Check the way changes | Only an optional `destinationLabel` argument that pre-fills the existing text field. No change to `SafeWalkSession`, policies or the server | Smallest safe change |
| F11 | SOS entries (D10) | Static launcher shortcut + `SosTileService`, both start a **non-exported** `SosEntryActivity` that records an in-memory `SosLaunchRequest` (valid 15 s, used once) and opens `MainActivity`; only that request starts the countdown, so another app cannot trigger it through the exported launcher activity. Signed out → Login as today. Locked phone → the tile asks to unlock first (`unlockAndRun`). Android 13+: "Add SOS to Quick Settings" button in the hub | Fast access without a spoofable trigger |
| F12 | Ready check (D10) | A facts list in the hub: Safety Circle people, automatic SMS on/off (+ permission), location and notification access, SOS shortcut/tile. Each row = fact + action. Never "you are ready/safe". Pure `ReadyCheck` from existing stores and permission checks | D10 places it in You → Safety |
| F13 | Cards | One `ExperienceCardAdapter` replaces the post rows in Discover, place/category pages, profiles and "Your experiences": first photo (Coil, aspect ratio known before load), postmark, type + category, headline = first line of the body, ticket tip, author + band, own Cocoon state. Ask = dashed "question slip". Collapsed = the collapsed line only, no words, photo or tip (13E-D2 rule kept, tested on the pure card model) | Field journal, not text rows |
| F14 | Discover | Masthead "This week in **Chennai**" (city opens a chooser sheet), Edition / Connections text tabs, search behind a search action, sections of cards, Asks as slips, end card with the hills illustration ("That's this week's edition. Go make your own." + Share / Open your Cocoon), categories last. `DiscoverViewModel` unchanged | Editorial, finite, not search-first |
| F15 | Post | Photo header (swipe), postmark, headline, body, ticket, by-line + band, butterfly button + "Going there?", perspectives as journal notes ("Been here too" first, with a small stamp), report and author tools in the ⋯ menu. `PostViewModel` unchanged | Design §9.4, §9.8 |
| F16 | Cocoon | Want to go / Been text tabs; chrysalis rows with "I've been" and "Going there?"; emergence animation, then the invitation as a bottom sheet; kolam empty state | Design §3.4, §9.6 |
| F17 | Share | Type cards (Experience / Ask), sections, live postmark preview of exactly what others will see, sticky "Share with …" button, stamp animation on success. `ComposeViewModel`, D14 and the 13E nudge unchanged | Design §9.5 |
| F18 | You | Journal header (name, handle, bio, city, interests; no counts), then: Your experiences, Connections, Private places, Safety, Community guidelines, Your reports, Blocked and muted, "Collapse strong language", Moderation (moderators only), Account and security, Privacy, Settings, About, Log out; the social pause notice. `ConnectionsFragment` keeps only connections, requests, invites and find-by-handle | One place for the person; no hub hidden behind a row |
| F19 | Onboarding and splash | Three pages: what Kurinji is (motto), the butterfly and the private Cocoon, privacy (no followers, no counts, no messages, nobody can use Kurinji to track you; "Trusted people and SOS live in You → Safety"). New adaptive icon (butterfly with violet fore-wings and orange hind-wings) and splash wordmark | Design §5 "no safety pitch in onboarding" |
| F20 | Accessibility and quality | One spoken sentence per card (type, headline, where, when, by); postmark and butterfly drawings hidden from TalkBack; ≥ 48 dp targets; sp text; body line spacing for Tamil; headings marked; 320 dp and 2.0× font layouts; dark mode tokens; image size from `MediaRef` before load; custom views allocate nothing in `onDraw` | CLAUDE.md §10, design §9.14 |
| F21 | Guards | See §5 | |

**Not in 13F:** Safe Walk starting without the server (a safety-logic change: its own plan next, D10), SOS location
retention, Tamil UI strings (V2, D5), a map of places, Activity (no endpoint exists), push, "Your butterfly landed",
"My experience differed", place "Good to know" signals (13C kept social away from safety data), the Galaxy A23 sprint,
token encryption on the phone (A14).

## 4. Work order (each step builds and passes tests)

1. **Foundation:** fonts (F2), colour tokens light/dark, type scale, shapes, `Motion` + haptics helper, components:
   `PostmarkView`, ticket background, `ButterflyView` (drawn, animatable), `KolamView`, card styles, bottom-sheet style.
   All existing screens re-skin through the theme.
2. **Shell:** four-item bar + Share action, Discover as start, `SessionBannersViewModel` + banner host, `YouFragment`,
   Safety hub (from `SafetyFragment` + Home's tile/status card), Home/Nearby tabs retired, Rise retirement (F8).
3. **Discover and lists:** `ExperienceCardAdapter`, Discover masthead/sections/end card/search, place and category
   pages, a person's profile ("Journal"), Your experiences.
4. **Post and Cocoon:** post screen, butterfly flight, perspectives, Cocoon + emergence + invitation sheet.
5. **Share:** composer layout, live postmark preview, stamp animation.
6. **Going there and SOS entries:** `feature/going` sheet, Safe Walk pre-fill, shortcut + tile + `SosEntryActivity`,
   Ready check.
7. **Onboarding, splash, icon, brand strings (F1, F19).**
8. **Verification:** unit tests, lint (fresh), emulator walk-through (§6), security/privacy review, docs
   (`PHASE13F.md`, `DESIGN_SYSTEM.md` rewritten, `CLAUDE.md`, `HANDOFF.md`), local commit. No push.

13F is large: if the context runs low, work stops at a finished step with a detailed handoff in `HANDOFF.md`.

## 5. Tests

| Test | What it pins |
|---|---|
| `ContrastTest` (new) | Parses `colors.xml` (light + night) and checks every text/background role pair ≥ 4.5:1 |
| `KurinjiTextsTest` (new) | No emoji in UI strings; no slang/hype list ("vibe", "slay", "bestie", "OMG", "epic", "insane", "crushing it" …); no "SafeSphere" in visible strings (if F1); motto and empty-state lines present |
| `NavigationRulesTest` (new) | Four bar items, Share is not a tab, Discover is the start, reading vs task screens, banners never on the SOS screen |
| `SessionBannersViewModelTest` (from `HomeViewModelTest`) | Same cases: SOS first, walk, overdue, deletion, sign-in, checking → nothing running |
| `SafetyHubTextsTest` (from `HomeTextsTest`) | No safety claim in any hub/banner string, status card rules, SOS tile wording, the "never contacts emergency services" line |
| `ExperienceCardTest` (new) | Pure card model: collapsed shows no words/photo/tip; where/when per precision; spoken sentence; Ask vs Experience |
| `GoingThereTest` (new) | Heads-up text has no coordinates, link or "location"; `feature/going` imports no social or safety code; social never imports safety packages (Android twin of the backend `SocialIsolationTest`) |
| `SosLaunchRequestTest` (new) | Request is used once, expires after 15 s, nothing else starts the countdown |
| `ReadyCheckTest` (new) | Facts per input; no "ready"/"safe" verdict words |
| `RiseRetirementTest` (replaces `RiseTest`/`RiseTextsTest`) | Deletes only `files/rise`, once; notice once; no Rise code or string left |
| `HonestyTextsTest`, `SocialTextsTest`, `ModerationV2Test`, `AiModerationTest` | Updated to the new files and lines; every existing ban kept |

## 6. Emulator walk-through (API 37) — planned

E1 cold start → onboarding (3 pages) → sign-in → Discover masthead with the profile's city · E2 Edition cards (photo,
postmark, ticket), Ask slip, "Why?", end card, categories · E3 post: butterfly flight into the Cocoon tab; Cocoon shows
it · E4 "Going there?": Let someone know (share sheet, editable note), Walk with check-ins (place pre-filled, not
started), Check the way · E5 Cocoon: I've been → emergence → invitation sheet · E6 Share: live postmark changes with
precision, nudge still appears, stamp, post opens · E7 collapsed post in lists shows no words · E8 You: journal header,
all rows open their screens; strong-language switch · E9 You → Safety: status card, SOS tile → countdown → cancel;
Ready check rows · E10 launcher shortcut and QS tile start the countdown (F3) and can be cancelled · E11 Safe Walk
running → banner on Discover, Cocoon, You and the post screen · E12 pending deletion banner on every tab · E13 Rise
retirement notice once, files gone · E14 dark mode, 320 dp, 2.0× font, "remove animations", TalkBack labels via the
accessibility tree.

**Will stay NOT VERIFIED:** real phone (A23 sprint), TalkBack speech by finger, OEM font rendering, the tile on a
locked device of a real OEM.

## 7. Risks

| Risk | Mitigation |
|---|---|
| A redesign breaks a safety guarantee (banners, SOS reachability) | Banner host on every screen; SOS one tap from the hub, plus shortcut and tile; tests + E9–E12 |
| Spoofed SOS start through the exported launcher activity | In-memory request from a non-exported activity only (F11) |
| Social and safety code mixing through the new bridge | `feature/going` is navigation only; isolation tests both ways |
| Animation cost on low-end phones | Four short moments, hardware layers during animation, nothing continuous, off with system setting |
| Custom fonts and Tamil at large sizes | Tamil fallback on Android 10+, line spacing, 2.0× checks; system fallback below |
| Re-skin hurts contrast somewhere | `ContrastTest` + dark-mode emulator pass |
| Scope | Eight buildable steps; handoff at a finished step if context runs low |
