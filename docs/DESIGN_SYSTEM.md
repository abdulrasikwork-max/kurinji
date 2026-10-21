# SafeSphere design system

Concept: **"Calm when everything is safe. Powerful when something goes wrong."**
This redesign changes how the app looks. It does not change what the app does (see README, "Phase 1 behaviour").

## Colour (res/values/colors.xml, values-night/colors.xml)
| Role | Light | Dark | Used for |
|---|---|---|---|
| Hero / midnight | #0B1B2E | #12293F | Status card, profile header, brand tile, splash |
| Accent (primary) | #0E7C86 | #3DD6C6 | Primary buttons, selected states, links |
| Safe | #187A4B | #5BD694 | Safe / Available badges |
| Caution | #A35F00 | #F5B544 | Caution badges and warning notices |
| Emergency | #C62828 | #FF6B6B | **Only** the SOS tile/icon and "Concern" badge |
Every text/background pair is at least 4.5:1 (checked for light and dark).
Status is never colour-only: each badge has a word, and most have an icon. Map markers also differ in shape.

## Where styling lives (so layouts do not repeat it)
- `themes.xml`: colour roles, and which style every default Material button/card/text field/bottom bar uses.
- `type.xml`: the type scale (ScreenTitle, SectionTitle, CardTitle, Body, Caption, Overline, StatusLabel, Button, Hero.*).
- `styles.xml`: buttons, text field, cards (normal / hero / emergency), bottom navigation, toolbar, small decorations.
- `attrs.xml`: theme attributes such as `?attr/colorSafe`, `?attr/colorHero`, and the XML attributes of the custom views.

## Reusable components (core/ui/components)
| Component | What it is | Used on |
|---|---|---|
| `StatusBadgeView` + `BadgeStatus` | Pill: Available / Preview / Coming soon / Sample / Safe / Caution / Concern | everywhere |
| `SectionHeaderView` | Section title with optional badge | Home, Community, Profile |
| `NoticeView` | Info (teal) or warning (amber) message box | Login, Register, Home, Safety Center, Forgot Password |
| `SafetyStatusCardView` + `StatusCardContent` / `StatusTone` | Navy status card: label, headline, detail, icon; tone CALM (teal) or CAUTION (amber + warning icon), deliberately no green or red. Home maps `HomeStatus` to it (`HomeStatusCard`) | Home |
| `FeatureListAdapter` + `FeatureItem` | Icon + title + description + badge card row | Safety Center, Profile |
| `EmptyStateView` | Icon + title + message | Community ("Your reports") |
| `SafetyRadarView` (Phase 6) | Signals by direction/distance around the person; shape + colour (▲ concern, ■ take care, ● safe place, amber/green, never red); soft halo = placed only to ~1 km; one content description, details in the list | Community |
| `Motion` | Short fade + slide-up, off when system animations are off | splash, Home, Safety, Community, Profile |

## Honesty rules built into the UI
1. Anything not built is labelled **Coming soon**; anything that shows a design only is labelled **Preview** or **Sample**.
2. The SOS tile is red (emergency UI). Its badge says "5 s to cancel" (the countdown) and sits under "Emergency help",
   never beside the title (beside it, it squeezed "SOS" off the tile on 320 dp phones with large text); its
   description says SafeSphere texts the Safety Circle only if automatic SMS is switched on (Phase 4D). Until
   2026-10-21 it said "No alerts yet".
3. The Home status card never says "safe" (Phase 8 rule, `PHASE8_DESIGN.md` §6.4). It states only what the phone
   knows: *Checking* (until the phone has looked), *Nothing running* ("No SOS or Safe Walk is running on this phone"),
   *Safe Walk on*, or *Check-in missed* (amber, warning icon). Until 2026-10-21 it was a Phase 1 "SAFE" sample.
4. During an SOS the status card is hidden: the red banner is the one emergency element. `HomeTextsTest` scans every
   string Home shows for safety claims.
