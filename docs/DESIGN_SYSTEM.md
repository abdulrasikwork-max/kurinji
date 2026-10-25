# Kurinji design system (Phase 13F)

Concept: **"A field journal from the hills, not a feed."** Motto: *Written for the next one who goes.*
Visual reference with mock-ups: `docs/design/kurinji-13f-direction.html`. Results and decisions: `docs/PHASE13F.md`.
(Until Phase 13F this file described the SafeSphere navy/teal system; that is superseded.)

## Colour (`res/values/colors.xml`, `values-night/colors.xml`; `ContrastTest` checks every pair)

| Role | Day | Night ("Kurinji night") | Used for |
|---|---|---|---|
| Mist (`k_mist`) | #EEF1EF | #12142A | The page |
| Card (`k_card`, `?attr/colorCard`) | #FBFCFA | #1B1E36 | Raised surfaces, sheets |
| Ink / ink soft | #1A1D35 / #4A4F66 | #E8EAF2 / #B4B8CC | Text |
| Bloom (`colorPrimary`) | #4A46B0 | #ABA8F7 | Brand, actions, postmarks, chosen states |
| Yeoman (`colorIntent`, `colorIntentInk`) | #E58A2E / #9A4A0B | #F2A65A / #F6B97A | Intent only: the butterfly, "Want to go" (fill; edges and text use the ink shade) |
| Ochre (`colorCaution`) | #8A5A00 | #E8B54A | Moderation and caution, always with an icon and words |
| Red (`colorError`) | #C62828 | #FF6B6B | **Emergency UI only** (SOS tile, SOS screen, active-SOS banner, shortcut icon) |

Text pairs ≥ 4.5:1, control edges ≥ 3:1, day and night. Status is never colour alone.

## Type (`res/font`, `values/type.xml`)

- **Young Serif** (`@font/kurinji_display`): headlines only (`TextAppearance.Kurinji.Masthead`, `Headline.*`,
  `CardHeadline`; `TextAppearance.SafeSphere.ScreenTitle/SectionTitle` point at them).
- **Mukta Malar** (`kurinji_sans`, `kurinji_sans_medium`): everything else; Tamil and Latin drawn together.
- Plain TextViews get Mukta through `android:textViewStyle` (never `android:fontFamily` on the theme: it overrides
  every text appearance). The SOS badge uses the phone's sans (lining figures; "5" must never read as "s").
- OFL licences: `docs/licences/`. No network font loading.

## The four Kurinji objects (`core/ui/components`)

| Object | Component | Meaning |
|---|---|---|
| Postmark | `PostmarkView` (`CardTexts.postmark`) | Where and when, only what the author revealed: place name, "Grid …" for an area, a city, or a blank cancellation |
| Ticket | `TicketLayout` | "For the next person": the tip |
| Butterfly / chrysalis | `ButterflyDrawable` (flap), `ic_chrysalis` | Intent: Want (chrysalis) → Been (butterfly) |
| Kolam | `KolamView` | Empty states only; draws itself once |

Other components: `TextTabsView` (editorial tabs; stack when they do not fit), `AspectRatioImageView`,
`ExperienceCardAdapter` (postcards and Ask slips), `FeatureListAdapter` (rows, no badge by default),
`SafetyStatusCardView` (facts-only status card), `NoticeView`, `Widget.Kurinji.SheetRow`.

## Motion (`core/ui/ButterflyFlight`, `BeenInvitation.emerge`, `Motion`, `Haptics`)

Four moments only: butterfly flight into the Cocoon tab (~620 ms), chrysalis emergence (~700 ms), stamp on share
(~280 ms), kolam drawn once (~900 ms); screens fade in with a small rise (~220 ms); cards settle under the finger.
With the system's "remove animations" each moment becomes a plain state change (plus haptic and a spoken line).

## Navigation and chrome (`navigation/NavigationRules`)

Discover · ✚ Share (action) · Cocoon · You. Bar on tabs and reading screens; toolbar + back on task screens. Safety
banners on every tab and reading screen, never on the SOS screen. SOS shortcut and tile from any signed-in screen.

## Honesty rules kept

No text claims a place or person is safe (`SafetyHubTextsTest`, `SocialTextsTest`); no counts except the author's
private butterfly line; no ranking or engagement words; no emoji, slang or hype (`KurinjiTextsTest`); no "avoid"
(`HonestyTextsTest`); moderation feedback in ochre, never red; red only for emergency UI.
