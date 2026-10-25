# Phase 13F — Kurinji UI/UX, safety integration and final integration: results

**Date:** 2026-10-25 · **Status: implemented, compiled, tested, emulator-verified (API 37) for the flows in §4;
real phone, TalkBack speech by finger and the remaining items in §6 NOT VERIFIED.** Plan: `docs/PHASE13F_PLAN.md`
(approved by the user 2026-10-25 with F1–F4 as recommended; F5–F21 decided under the delegation). Visual direction:
`docs/design/kurinji-13f-direction.html`. **Android only: no backend change, no migration (next stays V25).**

## 1. What was built

**Identity ("a field journal from the hills").** Colour = hill mist (page), indigo-night ink, Neelakurinji violet-blue
"bloom" (brand, actions, postmarks), Tamil Yeoman orange (intent only: the butterfly, "Want to go"), ochre for
moderation, red for emergency UI only; dark mode "Kurinji night". Type = Young Serif (headlines) + Mukta Malar (UI and
body, Tamil and Latin drawn together), bundled OFL fonts (`res/font`, licences in `docs/licences/`). Four objects carry
the meaning: the **postmark** (where and when, only what the author revealed), the **ticket** (the tip for the next
person), the **butterfly / chrysalis** (intent; drawn, not an emoji), the **kolam** (empty states). New launcher icon
and splash: the butterfly with violet fore-wings and orange hind-wings; visible name **Kurinji** (F1).

**Navigation.** Discover · ✚ Share · Cocoon · You. Share is an action (opens the composer). The app opens on Discover.
Reading screens (post, place/category pages, a person's profile) keep the bar; task and safety screens do not.
Home, the Safety/Nearby/Profile tabs and Rise are gone as tabs.

**Safety integration.** The old Home banners (SOS running, Safe Walk / check-in missed, pending deletion, sign in to
sync) now show on **every tab and reading screen** (`MainActivity` banner host + activity-scoped
`SessionStatusViewModel`, the old `HomeViewModel` logic unchanged). **You → Safety** hub: factual status card, the red
SOS tile, Safe Walk, Safety Circle, Evidence Vault, Nearby heads-ups, history, settings, "Add SOS to Quick Settings"
(Android 13+), and the honest "never contacts emergency services" notice (moved from onboarding). **"Going there?"**
(`feature/going`, navigation only): Save to Cocoon · Let someone know (share sheet, editable note, no location, no
link) · Walk with check-ins · Check the way (Safe Walk opens with the place filled in; nothing starts by itself).
**SOS launcher shortcut + Quick Settings tile** open the SOS screen with the cancellable 5 s countdown running (F3),
through a non-exported `SosEntryActivity` and an in-memory `SosLaunchRequest` (another app cannot start it).

**Screens redesigned.** Discover (masthead "This week in Chennai", editorial tabs, postcards and Ask slips, "Why?",
illustrated end card, categories, search behind an icon), place/category pages, profiles and "Your experiences" (the
card adapter), the post page (postmark, serif headline, ticket, butterfly flight into the Cocoon tab, "Going there?",
"You went" bottom sheet), the Cocoon (chrysalis rows, emergence animation, kolam empty state), You (journal header,
everything that is the person's), the composer (serif header, live postmark preview, stamp on share), onboarding
(three pages, no safety pitch), splash, login motto. Every existing screen (SOS, Safe Walk, vault, moderation …)
re-skins through the theme with unchanged behaviour.

**Rise retired (D9).** `feature/rise`, its layouts, strings and tests removed; `RiseRetirement` deletes `files/rise/`
once and shows a one-time notice.

## 2. Deviations from the plan

| # | Plan | Built | Why |
|---|---|---|---|
| 13F-D1 | F12 Ready check in the hub | **Not built** (follow-up) | Scope/context; the hub lists every tool and the tile/shortcut. Nothing half-built is shown |
| 13F-D2 | Display text with a Tamil fallback face on Android 10+ | System fallback only | Young Serif has no Tamil; per-view typeface code everywhere was not worth it now |
| 13F-D3 | Composer "type cards" | The existing chips, restyled | The live postmark carried the value; the ViewModel is untouched |
| 13F-D4 | — | Theme `android:fontFamily` replaced by a default `textViewStyle` | **Emulator finding:** a theme font overrode every text appearance (headlines lost the serif) |
| 13F-D5 | — | SOS badge uses the phone's sans | **Emulator finding:** Mukta's old-style "5" read as "s" in "5 s to cancel" |
| 13F-D6 | — | `TextTabsView` stacks its tabs when they do not fit | **Emulator finding:** at 320 dp and 1.3× text a tab broke mid-word |
| 13F-D7 | SOS entries honoured on tab/reading screens | On **any signed-in screen** except the SOS screen (`NavigationRules.opensSosFrom`) | **Emulator finding:** from the Safe Walk screen the tile did nothing |
| 13F-D8 | — | Butterfly button edge in the intent **ink** shade | `ContrastTest` finding: the bright orange edge was 2.55:1 (< 3:1) |
| 13F-D9 | — | Safety Circle SMS texts say "Kurinji" (F1) | Visible brand everywhere; `CircleMessagesTest` updated |

## 3. Tests

| Check | Result |
|---|---|
| Android `testDebugUnitTest` (final code) | **682 run, 0 failures** (13E: 677; Rise's own tests removed with Rise) |
| Android `lintDebug` | **0 errors / 144 warnings** (13E: 54): 49 `UnusedResources` (leftovers of the retired Home/Profile/Rise screens: follow-up clean-up), 31 `UnusedAttribute` (`accessibilityHeading` below API 28), 39 `GradleDependency` notices |
| Backend, Python | Not changed, not re-run |

New or rewritten tests: `ContrastTest` (every text pair ≥ 4.5:1, control edges ≥ 3:1, day and night), `KurinjiTextsTest`
(no emoji, no slang/hype, no old brand, identity lines), `NavigationRulesTest` (tabs, Share is an action, bar on
reading screens only, banners never on the SOS screen, SOS entries from any signed-in screen), `ExperienceCardTest`
(collapsed cards show no words/photo/tip, stamp = revealed precision only, headline split, the post page drops no
word), `GoingThereTest` (bridge imports neither side; social ↔ safety never import each other; Heads-up has no
location/link; SOS entry not exported; only MainActivity sets auto-start; request used once, expires, distrusts a
clock going back), `RiseRetirementTest`, `SessionStatusViewModelTest` + `SafetyHubTextsTest` (moved from Home),
`HonestyTextsTest` and `CircleMessagesTest` updated.

## 4. Emulator walk-through (AVD `Medium_Phone_API_37.0`, backend on MySQL v24, throwaway `emu13f.*` accounts)

| # | Check | Result |
|---|---|---|
| E1 | Onboarding page 1: butterfly, serif title, motto copy | PASS after 13F-D4 |
| E2 | Sign-in → Discover: "This week in / Chennai" from the profile city "Madras", theme line, editorial tabs | PASS |
| E3 | Cards: postmark, headline, excerpt, ticket, by-line, "Why?"; photo card with the postmark pressed onto the photo; Edition end card; categories | PASS |
| E4 | Area post: spoken card says "grid TF31FW", never the place name | PASS |
| E5 | Post page: postmark, serif headline, ticket, where/when, bar kept; butterfly → "In your Cocoon: want to go", "I've been" appears (the flight itself was not caught in a screenshot) | PASS |
| E6 | "Going there?": saved row disabled, footer honest; Walk with check-ins → Safe Walk with "Elliot's Beach Tea Stall" filled in, nothing started | PASS |
| E7 | Spoofing: shell start of `SosEntryActivity` → "Permission Denial … not exported"; exported `MainActivity` with an `autoStart` extra → no countdown | PASS |
| E8 | Quick Settings tile → SOS screen with the countdown running | PASS after 13F-D7. **Disclosed:** my scripted Cancel arrived after 5 s, so a real SOS started on the test account; ended at once as "False alarm" (server: CANCELLED, 0 Safety Circle alerts; the account had no contacts and automatic SMS off) |
| E9 | Cocoon: chrysalis row, I've been → emergence → "You went" sheet; Want list empty → kolam | PASS |
| E10 | You: journal header (name, handle, city, bio, interests, no counts), rows; You → Safety hub: status card "Nothing running", red SOS tile, tools | PASS after 13F-D5 |
| E11 | Dark mode (Kurinji night) on Discover | PASS |
| E12 | 320 dp with 1.3× / 2.0× text: Discover and You readable | PASS after 13F-D6 |
| E13 | Composer opened from the Share disc (no bottom bar), new header | PASS (the live postmark and the stamp animation were not exercised on the emulator) |

**State left:** test accounts deleted (users 174 → 170, as before the walk-through), the test place and photo files
removed, the app's data cleared, the tile removed, `adb reverse` removed, emulator and backend stopped, emulator size,
density, font scale and night mode restored.

## 5. Security and privacy review

- Social and safety code still never import each other (`GoingThereTest`); the bridge only navigates and passes a
  place name the post already shows. Safe Walk receives only a pre-filled text field.
- The SOS shortcut/tile cannot be triggered by another app: the entry activity is not exported, the request lives
  only in process memory, is single-use and expires after 15 s (tested and checked on the emulator).
- The Heads-up note carries at most the place name (no location, no link) and goes through the person's own share
  sheet; nothing is stored or sent by Kurinji.
- Banners now appear on more screens, never fewer; the SOS screen behaviour is unchanged.
- Cards never show a collapsed post's words, photo or tip; area posts never show a place name.
- Rise's encrypted notes are deleted from the phone once (D9); no other folder is touched (tested).
- Fonts are bundled (OFL) and never fetched at run time.

## 6. Not verified / limitations

Real phone (A23 sprint, D10); TalkBack speech by finger (labels checked through the accessibility tree only); the
butterfly flight, emergence and stamp animations frame by frame and on low-end hardware; the live composer postmark;
the launcher shortcut (the tile path was verified); the tile on a locked device; Android 13 "Add SOS to Quick
Settings" dialog; Tamil text at 2.0× in headlines. **Not built:** Ready check (13F-D1), unused-resource clean-up,
Safe Walk local start (its own plan, D10), Tamil UI (V2). D1 trademark, domain and store checks are still open.

## 7. Viva notes

- **Why a postmark?** Every post has three facts; the postmark makes where/when an object and shows exactly the
  precision the author chose, so privacy is visible, not hidden in settings.
- **Why are the banners in the activity?** Safety guarantees must not depend on one tab being open.
- **Why a non-exported trampoline for SOS?** The launcher activity must be exported; an in-memory request written only
  by our own shortcut/tile is the one signal another app cannot forge.
- **Why bundle fonts?** No network font loading (privacy, offline); Mukta Malar draws Tamil and Latin together.
- **What did the emulator catch?** A theme font overriding headlines, an unreadable "5" on the SOS badge, tabs
  breaking mid-word at large text, and SOS entries ignored on task screens.
