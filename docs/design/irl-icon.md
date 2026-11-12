# IRL launcher icon: concepts and choice

> **CURRENT MARK (2026-11-12 afternoon, decision IRL-C10): the Tamil letter ஐ, drawn as one line. APPROVED by the owner on
> 2026-11-12 ("I approve the new ஐ logo and the new opening direction").** The Kolam butterfly (IRL-C9) was rejected by the owner the same day: "the concept/story is interesting,
> but the actual logo feels too complicated for an app icon". Earlier drawings are history, below.
>
> **The idea.** ஐ is the Tamil vowel that is said exactly like the English letter **I**.
> - It is the **I of IRL** (in Tamil script the name is written ஐ.ஆர்.எல்), and it is "I": me, in person.
> - **"ஐ!"** is what a child shouts in delight, for instance when let outside.
> - In old Tamil the word ஐ means **wonder** (Tolkappiyam: "ஐ வியப்பாகும்"). It also begins ஐக்கியம், unity.
> - For someone who does not read Tamil it is an abstract ribbon: it starts curled up, sweeps out wide, and ends open.
>
> So the visual itself carries the idea (I, in real life, with wonder); it does not illustrate "people + place".
>
> **The drawing.** Our own drawing of the standard letterform as ONE line of one weight with round ends (not a font
> outline). One colour. 108 x 108 adaptive, the letter is about 44 units wide, stroke 5, all inside the 66-unit safe
> zone. Path data: `ic_launcher_foreground.xml`.
> - **Colours:** `brand_cream` #EEDCC8 on `brand_cherry` #5D0703 fading to `brand_cherry_deep`. Themed icon: one colour.
>   In the app (`ic_brand_mark`): the text colour of the screen.
> - **Motion (`art_opening_meet`, 560 ms):** the letter writes itself in one stroke, in the order it is written by hand.
> - **Files:** `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_brand_mark`, `ic_mark_on_ink`, `art_opening_meet`,
>   `mipmap-anydpi/ic_launcher.xml` (comment only); `ic_launcher_background` unchanged.
>
> **How it was chosen (round 5; sheets `docs/design/irl/icon-round5-*.png`).** Rendered and dropped:
>
> | Direction | Idea | Why dropped |
> |---|---|---|
> | The letter I casting a shadow | only real things cast a shadow | reads as a pushpin, or as a 2013 "long shadow" icon |
> | Two linked hooks | URL is a link to a page, IRL a link to a person | it is the paperclip / hyperlink icon |
> | Pause bars, one stepping | press pause, step out; a trail blaze | a pause or equaliser icon; too plain |
> | Two soles | the same, as footprints | a step-counter app |
> | An open hand ("first-hand") | the oldest post is a handprint | the Congress party symbol; "stop" |
> | A hand that is a rising sun | first-hand + out there | a weather icon; the rising sun is the DMK symbol |
> | Four pebbles, phone face down, three ticks | | generic |
>
> Thought through and not drawn: a door (reads as "log out"), an ampersand (says "together" but not "real life"), a crow
> (the Kural's bird that calls others to share: a bird is a worn social-media sign), a kite, a paper boat and a pinwheel
> (each is a well-known brand's sign), the letter இ (richer in meaning: இடம் place, இங்கே here, இணை connect, but a more
> complicated drawing).
> **A rule learned for any later round:** in Tamil Nadu a mark must not look like a party symbol (rising sun, open hand,
> two leaves, lotus, and others).
>
> **Seen:** the final sheet (`icon-ai-final.png`: 300 to 32 px, circle and squircle, themed, day, night, and the
> drawing laid over the reference letter); on the A23 the returning opening (`opening-ai-returning-a23.png`); on the
> API 33 emulator the first-run opening (`opening-ai-first-run-emulator.png`).
> **Not seen:** the launcher icon on the home screen grid, the themed icon, the Home head and other in-app places on
> a phone, dark mode, the motion itself by eye (only still frames).
> **Still open (the approval covers the mark and the direction, not these checks):** (1) a second Tamil reader's look at
> the drawing; (2) a trademark and similarity search
> (the 2015 film "ஐ" used the letter as its title; other uses are not checked); (3) the Play Store 512 px icon.

---

# History: the Kolam butterfly (IRL-C9, 2026-11-12, rejected the same day)

> **(History) Decision IRL-C9: "the Kolam butterfly". Rejected by the owner on 2026-11-12 as too complicated for an icon.**
> The earlier drawings ("Out there" of the same morning, rejected by the owner as ordinary and generic; "the Meet" of
> 2026-11-03) are history, below.
>
> **The idea.** A kolam is drawn every morning at the doorstep of a Chennai home: the exact line between inside and
> out there. It is ONE unbroken line that goes round every dot, touches none, and comes home; it is made by hand, in
> rice flour on red earth, for whoever walks past. IRL is the same thing: a line through the places of your city, drawn
> by people, left for the next person. In the mark that one line leaves the middle four times, goes round a dot each
> time, and its four loops are the wings of a **butterfly in flight**.
> - **The line** = a person's way through the city; one stroke, never lifted (people, going out, coming home).
> - **The dots** = places. The line goes round them without touching: close, never intrusive (the no-monitoring rule).
> - **The butterfly** = what the app is for. It is already the reaction on a post ("this inspires you"); the saved list
>   was once the Cocoon; and "someone else goes because you went" is the butterfly effect. Cocoon → butterfly = staying
>   in → out there. "Fear less."
> - **The colours were already right:** cream on Black Cherry is rice flour on kaavi (the red-ochre border of a kolam).
> - **A visual language, not just a logo:** one cream line with round loops and small blush dots. It can draw empty
>   states, the "all caught up" card, dividers and loading (the line drawing itself) later. Not done yet.
>
> **Geometry (108 x 108 adaptive, all inside the 66-unit safe zone; farthest point 31.4 units from the centre):** one
> closed path, stroke 4.2, round joins, tilted 16 degrees as if rising to the right. Two upper wings (loop radius about
> 8.7, stretched 1.25 along their axis), two lower wings (radius about 5), a small four-cornered body where the line
> crosses itself. Dots: radius 2.75 in the upper wings, 1.58 in the lower. The path data is in `ic_launcher_foreground.xml`.
> - **Colours:** line `brand_cream` #EEDCC8, dots `brand_blush` #E8A48C, ground `brand_cherry` #5D0703 fading to
>   `brand_cherry_deep` #2A0302. Themed icon: one colour. In the app (`ic_brand_mark`): line = text colour, dots = accent.
> - **Motion (`art_opening_meet`, about 640 ms):** the line draws itself in one stroke (520 ms), the way a kolam is drawn;
>   the dots appear as it closes (from 420 ms).
> - **Files:** `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_launcher_background` (unchanged), `ic_brand_mark`,
>   `ic_mark_on_ink`, `art_opening_meet`, `mipmap-anydpi/ic_launcher.xml` (comment only).
>
> **How it was chosen (round 4; sheets in `docs/design/irl/`).** Directions thought through from the brief (real life,
> people, places, connection, going out, discovering, community, people and places): a doorway (reads as "log out"),
> a cairn (a marker left for the next walker: reads as a spa), a handprint ("I was here": reads as "stop"), contour
> lines that are also a fingerprint (a map and a security sign), a shadow (only real things cast one: too subtle), a
> sandal at the door, a postmark, and the kolam. The kolam won on meaning and because nobody else in the category can
> own it. Then the drawing (`icon-round4-concepts.png`): the classic four-loop kolam and every uneven version of it turned
> out to be the ⌘ key (`icon-round4-loops-rejected.png`); three loops are a fidget spinner; a kolam hand is a peacock; two
> woven strands are an X. The butterfly (`icon-round4-butterfly.png`) is the one that is a THING people can name
> ("the IRL butterfly") and still plainly one kolam line. Antennae were tried and dropped: they break the one line and
> clutter 48 px. Final: `icon-kolam-butterfly-final.png`; on the phone: `opening-kolam-butterfly-a23.png`.
>
> **Seen:** rendered at 192 / 96 / 48 px in a circle and a squircle, themed, by day and at night (the final sheet); on the
> A23 (light mode): the Home head and the opening screen. **Not seen:** the launcher icon on the home screen grid, the
> themed icon, dark mode on the phone, the first-run opening with its words.
> **Not done:** trademark / similarity search (butterflies are common in logos: this needs a real check before a store
> release); the Play Store 512 px icon; redrawing the "Inspired" reaction icon in the same line (today it is a different
> butterfly drawing: a decision for the owner).

---

# History: the first drawings (2026-11-03 and 2026-11-12 morning)

> **2026-11-12: this document describes the FIRST drawing ("the Meet": two paths rising to one point under a sun, ink and
> lime), kept as history.** The mark of the morning of 2026-11-12 was **"Out there"** (decision IRL-C8, rejected by the owner the same day): a Cream Vanilla ring, open at one side,
> and a blush dot that has stepped out through the opening, on Black Cherry.
> - **Meaning:** the ring is a place, a circle of people, or the screen you were looking at; the dot is you, out there.
> - **Geometry (108 x 108 adaptive, inside the 66-unit safe zone):** ring centre (52, 56), radius 20, stroke 8, round
>   ends, open for 56 degrees towards the upper right; dot radius 7 at (73.9, 34.1), on the axis of the opening.
> - **Colours:** `brand_cream` #EEDCC8 ring, `brand_blush` #E8A48C dot, background `brand_cherry` #5D0703 fading to
>   `brand_cherry_deep` #2A0302. Themed icon: one colour. In-app (`ic_brand_mark`): ring in the text colour, dot in the accent.
> - **Motion (`art_opening_meet`, about 700 ms):** the dot waits in the middle, the ring draws around it, the dot steps out.
> - **Files:** `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_launcher_background`, `ic_brand_mark`,
>   `ic_mark_on_ink`, `art_opening_meet`. Concepts considered and dropped: `docs/DECISIONS.md` IRL-C8.
> - **Not done:** trademark / similarity search for the mark; the Play Store 512 px icon; a look on the home screen grid.

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
