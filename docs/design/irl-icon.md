# IRL launcher icon: concepts and choice (2026-11-03)

Brief (the user's, for the rename to **IRL — In Real Life**): an original, instantly recognisable symbol for real-world
connection, people coming together, being present and exploring beyond a screen. **Not** a shield, a security symbol,
a location pin, a female silhouette, a text-only "IRL" or anything stock-looking. Ink & Lime palette; good on light and
dark home screens; a proper Android adaptive icon. (Replaces the Phase 16 "Go-K", `kurinji-16-icon.md`, kept as history.)

Every concept was drawn in the adaptive 108-unit space and reviewed at 192 px and 48 px, in a circle and a squircle
mask, on a light and a dark wallpaper (sheets in `docs/design/irl/`).

## Round 1 (`irl/icon-round1-concepts.png`)

| # | Concept | Problem | Verdict |
|---|---|---|---|
| A | Step out: two dots leaving a phone frame | Reads as a "U" or a cup; the frame dominates at 48 px | Rejected |
| B | Together: two overlapping discs | Generic two-circle mark (card-network look) | Rejected |
| C | Horizon: lime sun with two heads in front | Reads as a snowman / a face | Rejected |
| D | Loop: an open ring of two halves around a dot | Reads as a record or power button | Rejected |
| E | **Meet: two paths bending toward each other under a lime point** | Distinctive silhouette at 48 px; carries the idea | **Developed** |

## Round 2 (`irl/icon-round2-paths.png`) and round 3 (`irl/icon-round3-no-figure.png`)

Tapered paths ("roads") were clearly strongest. But with the lime point **centred above** them the mark could read as a
head over a flared body, a restroom-style "woman" pictogram, which the brief rules out. Round 3 tested ways to break
that reading: the point merged into the meeting point (a keyhole), winding paths (arms), a half sun on a horizon (a
lamp), and **the point moved off-axis**. Off-axis it is unmistakably a sun, not a head.

## Final: "the Meet" (`irl/icon-final.png`)

Two tapered paths, one lime and one white, rise from different places beyond the edge of the icon and meet at one
point, under a lime sun out in the open.

- **Two people coming together** in the real world (two colours, two journeys, one meeting point).
- **Exploring**: two roads converging on the horizon, the sun up ahead.
- **Being present**: the sun is the world out there, not a screen.
- The peak echoes the old forward chevron, so the brand keeps a little continuity.

Geometry (108 units): lime path `M20,108 L20,94 C24,66 38,56 49.5,49.5 L51.5,52.5 C44,59 36,70 36,94 L36,108 Z`,
white path mirrored to the right, a 1-unit gap where they meet; sun r 9 at (67, 35). The meeting point and the sun lie
inside the 66-unit safe zone; the paths run past every mask, so whatever shape a launcher uses, they come up from its
edge. Background: ink #0E100C fading to deep leaf #1A2A05 at the bottom (the ground). Themed icon (Android 13+): the
same three shapes in one colour.

## Resources

| File | What |
|---|---|
| `mipmap-anydpi/ic_launcher.xml` | Adaptive icon: background + foreground + monochrome (manifest `icon` and `roundIcon`) |
| `drawable/ic_launcher_background.xml` | The ink-to-leaf ground (vector gradient) |
| `drawable/ic_launcher_foreground.xml` | The Meet, launcher colours |
| `drawable/ic_launcher_monochrome.xml` | Themed-icon layer |
| `drawable/splash_mark.xml`, `drawable-v31/splash_mark.xml` | System launch screen (static; animated on Android 12+: the paths rise, the sun comes up) |
| `drawable/ic_brand_mark.xml` | In-app mark, cropped to the visible 72 units, day/night colours (leaf + ink by day: lime is never drawn on a light page) |
| `drawable/ic_mark_on_ink.xml` | The mark in launcher colours for always-ink surfaces (the opening screen) |
| `drawable/art_opening_meet.xml` | The opening screen's hero: the Meet x 4.4, with two faint leaf rings around the sun |

**No density PNGs** (`mipmap-mdpi` … `xxxhdpi`): `minSdk` is 26, every supported device uses the adaptive icon, so
bitmap fallbacks would never be loaded. A 512 px Play Store image is a store asset, not an app resource, and is not
part of this change. The SOS launcher shortcut keeps its own emergency icon (black disc, red ring).
