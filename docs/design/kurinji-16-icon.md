# Kurinji launcher icon: concepts and choice (Phase 16)

Brief: bold, minimal, red/black, recognisable at tiny sizes; not a recolour of the 13F butterfly, not a generic
butterfly, not Instagram/TikTok-like, not a copy of any brand. Android adaptive icon (108 dp canvas, content inside the
66 dp safe circle) with a one-colour layer for Android 13 themed icons. **There is no iOS target in this repository,
so no iOS assets were made.**

| # | Concept | Strength | Problem | Verdict |
|---|---|---|---|---|
| 1 | **K + dot** (a bold K with a period) | Very legible | Too close to an existing fintech brand's "K." mark | Rejected |
| 2 | Speech bubble whose tail is a map pin | Says "stories about places" | The most common map/chat cliché; mush at 24 px | Rejected |
| 3 | Five-petal Kurinji flower | Ties to the name | Generic flower; reads as a wellness app; detail lost small | Rejected |
| 4 | Butterfly silhouette in red | Continuity with the save | Brief forbids a generic butterfly; many butterfly icons exist | Rejected |
| 5 | **Go-K**: a K built from a black stem and a white forward chevron, on red | Two shapes only; reads as "K" and as "go ›" at every size; uses all three brand colours; no common app uses it | The chevron alone could read as "play" | **Chosen** |

Why Go-K: the stem is someone standing still, the chevron is them going out; together they are the K of Kurinji and
the product's loop (go, see, tell). Two flat shapes survive 24 px, monochrome themed icons and dark launchers. The
"play" reading is broken by the separate stem.

Files: `res/drawable/ic_launcher_foreground.xml` (black stem, white chevron), background `@color/ic_launcher_background`
(#D0102A), `ic_launcher_monochrome.xml`, `mipmap-anydpi/ic_launcher.xml`; in-app mark `ic_brand_mark.xml` (ink stem,
red chevron); launch animation `drawable-v31/splash_mark.xml` (stem rises, chevron steps in), static
`drawable/splash_mark.xml` before Android 12. The SOS shortcut icon is separate and uses the emergency treatment
(black disc, red ring, white siren).
