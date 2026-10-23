# SafeSphere: whole-project audit and proposed next design

**Date:** 2026-10-22 · **Commit audited:** `1d40cd8` (`main`, not pushed) · **Type:** design audit only.
No product code, migration, navigation or dependency was changed. Everything below is a **proposal waiting for the
user's approval.**
**Revision 2 (same day):** reworked after the user's direction that safety is the *foundation*, not the whole identity
(new Part II "SafeSphere Beyond Safety"; verdicts A, C, H, I, K, L, M, N, O, P revised accordingly).

Status vocabulary (`CLAUDE.md` §3): planned · implemented · compiled · tested · emulator-verified ·
real-device-verified · partially verified · deferred · not verified.

Decision hierarchy used from Revision 2 on (the user's): **1 human usefulness · 2 differentiation · 3 safety ·
4 privacy and trust · 5 reliability · 6 simplicity · 7 technical sophistication.** A critical safety failure (C1) still
comes first in the build order, because nothing else is trustworthy while the SOS can silently fail.

## 0. Method and what was actually checked

- Read: `CLAUDE.md`, `HANDOFF.md`, `PHASE11.md`, `PHASE10.md`, the RISE part of `PHASE10_DESIGN.md`,
  `PLATFORM_CONSTRAINTS.md`, and the parts of the other phase docs the findings depend on.
- Read in code: the whole SOS path (`EmergencyViewModel`, `SosRecorder`, `SafetyTrackingService`, `AppContainer`
  sign-out wiring, `SessionEnder`, `SessionRenewer`), Safety Circle alerts (`CircleAlertPolicy`, `CircleMessages`),
  `SafeWalkSession` start/recover, Home/Safety screens, the manifest, all of `feature/rise/domain`, backend
  `SecurityConfig`, `JwtService`, `AttemptLimiter`, `RegisterRequest`, `application.properties`, V12 categories, the
  migration list, every `@Scheduled`/retention path, the SOS/Home/onboarding/Rise strings.
- **Not** re-read line by line: Phase 5 crypto internals, Phase 6–9 community/trust/intelligence/coordination
  internals, moderation UI. Findings about them come from their docs plus the code structure and are marked so.
- Tests run in this session: Android `testDebugUnitTest --rerun` → **527 tests, 0 failures, 0 errors**;
  backend `mvn test` → **482 tests, 0 failures, 0 errors, 2 skipped**. No emulator, MySQL or real phone was used.
- Market comparisons in Part II are **conceptual** (categories and well-known examples); no app was installed or
  benchmarked, and research citations are marked *(verify)*.

---

# Part I — The audit

## A. Executive verdict

SafeSphere today is an **unusually careful, honest and privacy-conscious personal-safety app**: an SOS that works
without internet, Safety Circle SMS from the phone, Safe Walk check-ins, an encrypted evidence vault. Around that core
sit (a) community/trust/coordination machinery that needs a user base it does not have, and (b) an abstract self-help
module (Rise) that does not belong in its current form.

Two conclusions drive everything else:

1. **A critical safety failure (C1).** The SOS stops working for most real users in normal life. The session token
   lives 24 h and is renewed only while the app process is alive; Android kills idle processes within hours. A person
   who has not opened SafeSphere for a day opens it in an emergency, Home's first server call returns 401, the phone
   signs out, wipes the cached Safety Circle and the running-SOS record, and shows Login. Offline, the SOS starts and
   texts the Circle, but when the connection returns the same 401 wipes it mid-emergency. No test or emulator run
   caught it because every check ran within hours of signing in. **Fix first.**

2. **A product-identity problem.** Even with every safety gap closed, SafeSphere would be "a better women-safety
   app": excellent and replaceable, opened only when something is wrong. The real, frequent, unsolved problem is not
   emergencies (rare) but the **opportunities people decline because the unfamiliar feels unsafe** — the class not
   taken, the late shift refused, the trip not made, the event skipped. SafeSphere's purpose should be to make
   unfamiliar things doable: *find out what it is really like → get ready → go with people quietly expecting you and
   real safety on standby → come back and turn the experience into know-how for your future self and, anonymously,
   for the next person.* (Part II.)

The recommended next phase does both: **fix the safety foundation (C1, Safe Walk that survives losing the phone,
SOS readiness) and build the first, phone-only version of the "go further" loop (Plans + Heads-up + Home "Today")**,
while the community knowledge layer is designed and validated with real people before any code.

---

## B. Biggest strengths (keep and protect)

1. **Honesty as an engineering rule.** No text claims "safe"; "sent ≠ delivered"; "dialer opened ≠ call made";
   string-scanning tests (`HomeTextsTest`, `HonestyTextsTest`, `RiseTextsTest`). This is the real brand.
2. **Phone-first SOS (Phase 11).** Local start, idempotent recording by client reference, offline "I'm safe",
   local→server id rebinding; emulator-verified with the server down.
3. **Safety Circle by SMS from the phone, opt-in, calm and proportional** (`CircleAlertPolicy`).
4. **Backend ownership discipline:** JWT-only identity, foreign record → 404, two-user tests, generated-column
   uniqueness, Flyway with MySQL dry runs.
5. **Coercion-aware rules:** password + 24 h guards for evidence and account deletion, another sign-in needs the
   password to end an SOS, credential-stamp revocation, FLAG_SECURE.
6. **Evidence integrity:** encrypt on the phone, server re-hash before "sealed", custody chain, resumable uploads.
7. **"Nobody can use SafeSphere to monitor another person"** — permanent and differentiating.
8. **A privacy-preserving community design** (cells not coordinates, bands not counts, delays, independence, human
   moderation, appeals). Over-built for today, but it is exactly the backbone a useful knowledge layer needs (Part II §5).
9. **Testability:** pure policies with virtual-time tests; ~1 000 automated tests.

## C. Biggest flaws

| # | Flaw | Evidence |
|---|---|---|
| **C1** | **SOS unavailable after an idle day (session expiry).** Token 24 h; `SessionRenewer` runs only while the process lives; an expired JWT cannot be renewed. First 401 → `SessionEnder.end` → `AppContainer` clears the active-SOS store, walk store, Circle cache → Login. `SosRecorder` resets when the session becomes null. | `SessionRenewer.kt`, `SessionEnder.kt`, `AppContainer.kt:425-431`, `SosRecorder.kt:93-97`; `PHASE11.md` §3.7 calls it an edge case — it is the common case |
| **C2** | **No reason to open SafeSphere when nothing is wrong.** Home is a safety dashboard; Nearby is an (empty) hazard map; Rise has no trigger. The app is designed for the 0.1 % moment and absent from the 99.9 %. | Home/Safety/Nearby/Rise screens |
| C3 | **SOS too slow to reach; permission dialogs mid-emergency.** Unlock → app → Home → SOS tile → Start → 5 s. No shortcut, Quick Settings tile or widget. First SOS asks location + notifications during the emergency. | `HomeFragment.kt:32`, `consumeAutoLocationRequest`, manifest |
| C4 | **By default an SOS tells nobody.** Automatic SMS is opt-in on the Safety Circle screen; otherwise one SMS app session per contact during the emergency. No set-up step asks. | `emergency_circle_manual_notice`, onboarding strings |
| C5 | **The Circle gets one location, then silence.** One SOS message + ≤ 1 follow-up within 10 min; the server's track has no live recipient (`SAFETY_CIRCLE` projection is called by no client). | `CircleAlertPolicy`, grep |
| C6 | **Safe Walk's dead-man switch dies with the phone**, and a walk **cannot start offline** (`repository.start` first). | `SafeWalkSession.start` l.161 |
| C7 | **Community is both too narrow and too empty.** Ten categories, seven of them hazards: a fear map. It gives nobody a reason to contribute except alarm, and with few users every area reads "no information". Meanwhile Phase 9 built coordination detection for a scale that does not exist. | V12 CHECK, `PHASE6–9`, `HANDOFF` §6 |
| C8 | **Rise is an abstract goal tracker** (nine life domains incl. money, leading, "be me") with no moment of use. | `RiseModels.kt`, §I |
| C9 | **Almost nothing is verified on a real phone** (Samsung = aggressive background killing; real SIM SMS; Android 8–13 paths; TalkBack; dark mode). | `HANDOFF` §1/§5 |

## D. Top real-world safety gaps (ranked)

Ranking = usefulness × safety impact × frequency × severity × feasibility. (Gaps "beyond safety" are in Part II.)

| Priority | Problem | Real-world impact | Current coverage | Risk if left | Recommended action |
|---|---|---|---|---|---|
| **1** | Session expiry disables SOS (C1) | Anyone who has not opened the app in ~24 h | None | **Critical** | 11B: expired ≠ signed out; SOS fully local-capable; device refresh credential with rotation (V19); Keystore-encrypted token (A14) |
| **2** | Safe Walk fails if the phone is lost/off/killed; no offline start (C6) | Exactly when a check-in matters | In-process timer | High | 11C: **Heads-up** SMS expectation to chosen people + phone-first walk |
| **3** | SOS slow to reach; dialogs mid-emergency (C3) | Every SOS | Home tile | High | 11F: Ready check grants permissions beforehand; launcher shortcut + Quick Settings tile |
| **4** | Default SOS texts nobody (C4) | Everyone who never found the switch | Buried opt-in | High | 11F: Ready check + SOS-tile summary ("Your SOS texts 2 people" / "texts nobody") |
| **5** | Circle gets one location (C5) | Being moved | 1 + 1 messages | High | 11F: periodic location SMS while the SOS runs (with auto-SMS, only when moved, capped) + one battery-critical message |
| 6 | No safe way to test the SOS | No SIM/balance/permission/dual-SIM issues found too late | None | Medium-high | 11F: "Test my SOS" (TEST SMS, nothing recorded) |
| 7 | Real-device verification debt (C9) | Unknown failure modes | Emulator | High (unknown) | 11H: Galaxy A23 sprint |
| 8 | SOS location trails kept forever, false alarms too | Breach/operator sees every SOS place | Account deletion only | Medium | 11G: outcome-based retention, password-gated delete (not < 24 h) |
| 9 | Evidence impossible offline or outside an SOS | No data = no recording | Server-recorded SOS only (E6) | Medium | Later: capture during a local SOS, bound on rebind |
| 10 | Nothing after an incident | Next steps, helplines, a record to show the police | Ended screen | Medium | Later: "After" screen + owner export |

## E. Security and privacy weaknesses

**Security**

| # | Weakness | Severity | Recommendation |
|---|---|---|---|
| S1 | C1 (availability is a security property here) | Critical | Refresh secret per sign-in (`sid`), stored hashed (`device_sessions`, V19), rotated on use, reuse → revoke, revoked by the existing credential stamp and account deletion, sliding 60–90 days; access token stays 24 h |
| S2 | Token stored unencrypted (A14) | Medium | Keystore AES-GCM wrap in 11B (same pattern as Rise's `LocalKeyWrapper`) |
| S3 | No password reset (A6), no e-mail verification | Medium | Needs an e-mail channel (deployment). Say so in onboarding; with S1 the phone stays usable |
| S4 | Abuse limiters in memory per instance | Low | Fine for one instance; document |
| S5 | Registration accepts any e-mail | Low | A victim's address can be pre-registered; low impact today |
| S6 | Debug cleartext, local MySQL without TLS | Info | Correctly scoped; a release needs HTTPS + DB TLS |
| S7 | The starting phone can end its own SOS without a check | Info (deliberate) | Coerced "I'm safe" unsolved; duress PIN stays deferred |

**Privacy**

| # | Weakness | Recommendation |
|---|---|---|
| P1 | `emergency_locations` never expire, false alarms included | Contradicts `CLAUDE.md` §9; 11G |
| P2 | Server holds Circle members' names, numbers, e-mails, relationships though SMS is sent by the phone | Keep for now (sync, `circle_alerts.contact_id`); drop e-mail/relationship later; long-term phone-only Circle |
| P3 | One account links community cells with SOS locations and walk destinations in one database | Acceptable while delays/bands hold; never add cross-reads; any knowledge layer must strip plan/journey links and publish with long delays (Part II §5) |
| P4 | Lock-screen public text "SafeSphere is running" during SOS/walk | Neutral public title (11G) |
| P5 | `Geocoder` sees the typed destination | Disclosed; Heads-up must not require geocoding |
| P6 | Region downloads reveal an approximate corridor | Disclosed in `PHASE8.md` |
| P7 | Rise/Plans data survives sign-out on a shared phone | Deliberate; add "delete on sign-out" option |

Not found: logging of tokens/passwords/coordinates, serialized entities, client-supplied ownership, open CORS (in the
files read; Phase 5–9 internals not re-audited line by line).

## F. Reliability weaknesses

| # | Weakness | Today | Proposal |
|---|---|---|---|
| R1 | A 401 wipes a running SOS (C1) | Wipe + Login | Only `ACCOUNT_DELETED` wipes; `EXPIRED` keeps safety state; password asked without blocking SOS |
| R2 | SOS/walk only while the process lives; no boot receiver; START_NOT_STICKY | Recovery on next open | Keep (Android rules); Heads-up makes a human the backstop; battery-optimisation guidance in the Ready check |
| R3 | Points before a process death lost (memory only) | Deliberate | Keep |
| R4 | SMS results after process death lost | Documented | Keep |
| R5 | Safe Walk needs the server to start | Fails offline | Phone-first start (SosRecorder pattern) |
| R6 | Evidence waits for the server SOS | No offline capture | Later |
| R7 | > 24 h offline stops server recording | Documented | Solved by S1 |
| R8 | Unsent SOS endings cleared by sign-out | Documented | Rarer after R1; keep |
| R9 | Android 8–13 code paths unverified | API 37 emulator + partial A23 | One API 26/29 emulator run |

## G. UX and accessibility weaknesses

- **Emergency usability:** C3/C4. The SOS screen itself is good; its long notices should lead with one actionable
  sentence ("Texting 2 people · sharing location · Call 112") and collapse the rest.
- **Navigation:** Home and the Safety tab are the same list twice; five tabs (Home, Safety, Nearby, Rise, Profile) for
  an app whose ordinary-life purpose is invisible. Proposal in §M.4.
- **First-time experience:** onboarding explains philosophy, not readiness, and never shows what SafeSphere is for
  on an ordinary day.
- **Accessibility:** TalkBack verified only for the Home SOS tile (keyboard, emulator); dark mode only for Phase 8
  screens; 320 dp / 2.0× only for the SOS tile. Everything else **not verified**.
- **Dangerous actions:** well guarded.

## H. Feature-bloat candidates

| Candidate | Verdict | Reason |
|---|---|---|
| Phase 9 coordination detection (coord-1, group pause, option B), evaluation counters, rules-2 shadow | **Freeze; no further work; consider removal after the viva** | Built for a scale that does not exist; cannot be validated |
| Community hazard map as the *whole* community product | **Repurpose, don't extend** | Keep signals as one layer; the trust/independence/moderation backbone becomes the base of the knowledge layer (Part II §5) if validated |
| Pattern memory, region packs, "Check the way" | **Freeze** | Value only at scale; keep optional and isolated |
| Safety tab | **Remove (merge into Home/You)** | Duplicate |
| Rise as a tab with nine life domains | **Redesign as Plans** | §I |
| `GET /context?audience=SAFETY_CIRCLE` | Leave, document as unused | No recipient |
| Evidence escrow to an offline RSA key | Keep, document honestly | Cheap, explainable, operationally fictional for a student project |

Not bloat: SOS, Safety Circle SMS, Safe Walk, location sharing, Evidence Vault core, account security.

## I. RISE verdict: **REDESIGN as "Plans" (decide → prepare → go → look back → playbook)**

Re-evaluated against human usefulness first, not safety only.

**(A) Does Rise help people live more capable lives as built?** Not much. The mechanism (predict → try → compare,
CBT-style behavioural experiments; mastery experience as the strongest source of self-efficacy, Bandura 1977
*(verify)*) is sound. The product around it is not: nine abstract life domains, free-text goals and steps, a replay
counter, no moment of use (deliberately no reminders and no Home card), payoff only after many logged tries with the
same self-doubt. Beyond a notes app it adds structure and one sentence. Nobody opens a safety app to journal about
money or leadership.

**(B) Why it is too abstract:** psychology-model vocabulary ("Proof", "Doubt", "Next"); life areas instead of
moments; delayed statistical payoff; the only concrete help is a link out to SAFE; ethical refusal of engagement
mechanics without an intrinsic, immediate use to replace them.

**(C/D) The real problem it can solve — and it is a capability problem, not only a safety one:** *"There is
something unfamiliar I want or need to do — an interview in an unknown part of town, a first day at an internship, an
evening class, a trip alone, an event, joining a new group, moving to a new city — and the unknowns (getting there,
being there, getting back, being on my own) make me hesitate or say no."* Frequent in ordinary life, central to
"Safety that expands your world", and nothing covers the **before** and **after**.

**Plans, the redesign:**
1. **Decide** — what (own words, optional), when (date), what kind (getting somewhere new · a new place/class/work ·
   meeting people · an evening/night out · a trip · something else). The boundary: things that involve *going
   somewhere or being with people* — where capability and safety meet. (Money, leadership, "be me" are out of scope.)
2. **Prepare** — worries, split as today (self-doubt = a prediction to test; safety concern = respected, never
   disproven → preparation). A short checklist from the chosen kind and supports: how I get there and back, who knows
   and when they expect me (Heads-up), what I'll do if it feels wrong, phone charged/money for a ride, what I already
   know about the place (your own playbook; later Explore know-how).
3. **Go** — one tap into Heads-up / Safe Walk / Check the way (the person taps; Plans passes nothing).
4. **Look back** — prompted naturally when the Heads-up "arrived" is sent or the walk ends: how it went vs. what you
   expected (the old replay lives here, where it is natural), what helped, next time I'll…, and **"anything you'd tell
   the next person going there?"** (kept privately in the playbook until a knowledge layer exists and the person
   explicitly passes it on).
5. **Playbook** — your own know-how: plans that worked, reusable as templates ("late shift home", "first trip
   alone"), and "the last time you weren't sure, it went better than you expected" when the same doubt returns.

Data: reuse the encrypted per-account store (AES-GCM, Keystore-wrapped key, AAD, `.bak`); `RiseDomain` → `PlanKind`;
`Attempt` → `Outing` (date only, expectation, worries, checklist ticks, outcome, what helped, next time, pass-it-on
draft). No location, no time of day, no coordinates, no contact names. FLAG_SECURE, quiet mode, optional screen lock,
no notifications, no server.

**(E) "SAFE makes trying possible. RISE makes trying count."** True, and now it is one loop instead of two sections:
*prepare → go → look back*. Keep the line for the viva as the history of the idea.

**(F)** Removing Rise remains acceptable if the user prefers fewer moving parts; keeping it as-is is not recommended.

## J. Emergency Evidence Mode verdict: **REJECT automatic capture · KEEP manual · DEFER offline capture**

| Option | Phone already does | SafeSphere adds | Problems |
|---|---|---|---|
| Phone camera/recorder | Records locally, gallery | — | Visible to an abuser in the gallery; lost with the phone; editable; no off-device copy |
| SafeSphere manual capture (today) | — | Encrypted at once, off the phone in ~30 s parts, server-verified hashes, custody log, hidden from gallery, guarded deletion | Only during a server-recorded SOS |
| Evidence Vault | — | Integrity, off-device survival, recovery | Complex; passphrase friction |
| Automatic capture at SOS start | — | Hands-free audio | Accidental SOS records private moments; a coercive partner sees the mic indicator and may escalate; battery/storage; legal exposure varies; breaks the "never automatic" promise; not covert anyway (Android indicator) |
| Better alternative | — | Make **Record** the second-largest SOS button; capture during a **local** SOS, sealed locally, bound when the server id arrives | Needs a rebinding design for sealed items + custody |

What SafeSphere provides that the phone does not: an encrypted copy that leaves the phone within ~30 s, that the
gallery and a casual searcher cannot find or edit, with server-verified hashes and an access log — for *manual*
capture. Hashes help show integrity; admissibility is for courts (India: certificate under the Bharatiya Sakshya
Adhiniyam 2023, s. 63 *(verify)*). Never claim "court-ready".

## K. New-section verdict: **DON'T CREATE a new section — restructure instead**

Re-checked against the broader vision. The broader product does not need a fourth or fifth section; it needs the
existing pieces to form one loop (Part II §7). Plans replaces Rise; the community layer is *redesigned* (Explore, Part
II §5) rather than added. **No new section is justified right now.**

## L. Uniqueness opportunities (ranked, revised)

1. **The "go further" loop with one privacy model** — find out → get ready → go → look back → pass it on, where the
   hand-offs (plan → Heads-up → arrival → look-back → know-how) are the product. Separate apps cannot do the hand-offs
   and their business models depend on exposing the very data this needs to protect.
2. **Expectation instead of tracking.** Your people know when to expect you and when to worry (Heads-up, check-ins,
   calm all-clears) — connection without monitoring.
3. **Practical first-hand know-how about getting there, being there and getting back**, contributed anonymously
   after real outings and confirmed by independent people (if validated, Part II §5).
4. **Works when nothing else does** — phone-first SOS and walks, SMS without internet, readiness checks and drills.
5. **Anti-monitoring as a guarantee.**
6. **Evidence integrity** for manual capture.

---

# Part II — SafeSphere Beyond Safety

## 1. What SafeSphere should ultimately be

**A companion for doing unfamiliar things.** It helps a person find out what a place or situation is really like,
get ready, go — with the people they chose quietly expecting them and real safety infrastructure on standby — and
afterwards turn what happened into know-how: for their future self privately, and, if they choose, anonymously for
the next person.

One line: **"Go further, prepared."** (The philosophy "Safety that expands your world" stays; the mission — a safer,
more capable society — is served by people taking opportunities they would otherwise decline, and by knowledge
passing from those who went to those who are about to go.)

## 2. The problem beyond emergency safety

- Emergencies are rare. The **frequent** cost of an unsafe-feeling world is invisible: routes avoided, evening
  classes and late shifts declined, trips not taken, events skipped, colleges and jobs chosen for the commute rather
  than the opportunity. Researchers call the routine planning women do to stay safe "safety work" (Vera-Gray 2018,
  *The Right Amount of Panic* *(verify)*); in Delhi, perceived harassment risk measurably pushed women students
  towards lower-ranked colleges with safer commutes (Borker, "Safety First" *(verify)*).
- What makes the unfamiliar feel unsafe is mostly **missing practical knowledge**: which exit, which stretch is lit,
  where to wait, how late the last bus runs, whether the venue has a women's restroom, how people get back from there
  at night. That knowledge exists — in friends' heads, WhatsApp groups and Reddit threads — but scattered, identity-
  exposing, unstructured, unverifiable, and absent when you need it.
- And afterwards, people rarely keep what they learned; the next unfamiliar thing feels as hard as the first.

## 3. Why existing apps don't solve it

| Category (examples) | What it already does well | What it misses for this problem |
|---|---|---|
| Safety apps (SOS/alert apps; family trackers like Life360) | Emergency buttons, location sharing, alerts | Only the rare moment; often monitoring by others; nothing before or after; fear-framed |
| Community safety audits / incident apps (e.g. Safetipin, Citizen-style alerts) | Place ratings for lighting/crowds; incident feeds | Hazard-only, fear-driven; no plan, no people expecting you, no learning loop; incident feeds increase anxiety |
| Maps and travel (Google Maps, ride apps) | Routes, places, business reviews | Reviews are about businesses, not about getting there and back as *you*, at *that* hour; public reviewer identity; nothing about who expects you |
| Social media / community platforms (Instagram, Reddit, Facebook groups, WhatsApp groups) | Discovery, connection, local knowledge exists | Attention economy, public identity, harassment, noise, no verification, knowledge not attached to your plan |
| Productivity / habit apps | Goals, reminders, streaks | Not situational, no knowledge of places, no safety, pressure mechanics |
| Emergency services (112 / ERSS app) | Reaching official help | Only the emergency itself |

**The underserved intersection:** *the moment someone decides whether to do something unfamiliar* — where they need
trustworthy first-hand knowledge, a concrete plan, people who know when to expect them, a backstop if things go wrong,
and a way to keep what they learned. No category covers it, and stitching four apps together fails twice: the pieces
cannot hand off to each other, and their privacy models expose exactly the movements this person needs protected.

**"Why SafeSphere instead of a safety app + maps + a community app + a productivity app?"** Because the value is in
the hand-offs and the privacy model: the plan knows what to prepare and whom to tell; the Heads-up knows when you are
expected; arriving prompts the look-back; the look-back becomes your playbook and (if you choose) the next person's
know-how; and every step is built so that nobody — not other users, not a partner, not the operator — can turn it into
a record of where you go. SafeSphere doesn't replace maps or messaging; it is the layer none of them provide.

## 4. Why people would open SafeSphere during ordinary life

Honest target: **a few meaningful sessions a month, not daily use.** An app that needs daily opens to survive drifts
into engagement mechanics; this one should be *the first app you open when you are about to go somewhere new.*

| Moment | What they do | Value |
|---|---|---|
| Something unfamiliar is coming up (interview, class, trip, event, new city) | Make a plan; read what they (and later others) know about the place | Uncertainty turned into a checklist |
| Leaving | One tap: Heads-up to a chosen person, or a Safe Walk | Someone expects them; a backstop if the phone is lost |
| Arriving / coming back | "Arrived" goes out; a 20-second look-back | Their own know-how grows |
| Before the same kind of thing again | Reuse a plan; see "last time it went better than you expected" | Each first is easier than the last |
| Curious about a place (later, if validated) | Explore practical know-how and places worth going | Discovery that is about doing, not scrolling |
| Something goes wrong | SOS, Circle, evidence — fast, offline, trusted | The foundation |

Retention comes from being *where your plans and know-how live* plus *trusted safety infrastructure*, not from
notifications. Success metric for the viva: outings prepared, plans reused, looks-back completed — never time in app.

## 5. The strongest community concept: **"Know-how" — people who went help people about to go**

Not "people post things". Structured, practical, first-hand knowledge about **getting there, being there and getting
back** from real places, contributed after one's own outings, anonymous, confirmed by independent people.

**What a contribution looks like** (closed lists first; free text only after moderation capacity is proven):
- Anchored to a public place or a ~150 m cell (never the place a plan started from — likely home or a hostel; the
  phone refuses to offer it), and to a **time band** (morning / day / evening / late), never a time.
- Facts, not judgements: *getting there* (lit route from the station, auto/cab stand, which exit, crossing),
  *being there* (women's restroom, staffed late, waiting area, women-only sessions, step-free), *getting back* (last
  bus/metro reached, ride pickup point, shared rides common). Plus **"worth going"**: welcoming places and activities —
  libraries, study spaces, classes, women-led groups, venues comfortable to go to alone.
- The existing hazard signals remain one layer, shown with the same honesty (never "safe").

**Why people contribute without likes, followers or reputation:**
1. **It happens inside their own useful act.** The look-back asks "anything you'd tell the next person going there?"
   — ten seconds at the moment of maximum relevance; the answer is first saved to their own playbook.
2. **Reciprocity as a stated norm, not a score:** "Someone left this for you. Pass something on when you can."
3. **Private usefulness, banded and delayed:** the contributor alone may see "your notes helped people preparing to
   go there" (no number below a threshold, never ranked, never public, can be switched off). Usefulness is the reward
   that motivates OpenStreetMap- and Wikipedia-style contribution *(verify)*; it is not a popularity signal.
4. **Self-interest in quality:** the knowledge is about their own campus, commute and city.
5. **No persona to perform:** no profile, no audience, so shy or cautious contributors are not deterred.

**Privacy and safety design (reusing what exists):** cells from the phone, never coordinates (Phase 6); one
contribution per person per place and kind; shown only after **independent** confirmation (Phase 7 independence rule —
also the lure defence: a single account cannot steer people to an isolated place); **long random publication delay
(days, not minutes)** because know-how is not urgent — so a contribution cannot be linked to a visit; no link to the
plan or journey on the server; no person-related fields (nobody can be named, rated or accused); no "safe" claims
(existing language tests); human moderation and appeals (Phase 7) — which finally gives that machinery a purpose.

**Honest risks:** cold start (needs a seeded pilot community, e.g. one campus and its city), moderation capacity
(free text multiplies it), advice that becomes outdated (expiry by kind), and the operator could still correlate an
account with cells it contributed about (mitigated by delay and separation; anonymous credentials are a later option).
Therefore: **design + validation in this phase, implementation only after a pilot test with real people.**

## 6. The strongest Plans concept

Plans = **"turn real-life experiences into know-how for your future self"**: decide → prepare → go → look back →
playbook (§I). It is single-player, phone-only and useful on day one with zero other users — which is why it comes
before the community layer and also seeds it (pass-it-on drafts kept privately until the person chooses to share).

## 7. The core product loop

Derived from §2–§6, not copied from the example:

```
        ┌──────────────► FIND OUT ─────────────┐
        │   (your playbook; later Explore      │
        │    know-how and places worth going)  ▼
   PASS IT ON                               GET READY
   (optional, anonymous,                    (Plans: kind, worries,
    delayed, confirmed)                      checklist, who to tell)
        ▲                                      │
        │                                      ▼
   LOOK BACK  ◄──── arrived / walk ends ────  GO
   (what happened vs. expected,               (Heads-up: people expect you;
    what helped, next time)                    Safe Walk; SOS on standby)
```

- **Connection** in this loop is "the people you chose know when to expect you" (Heads-up), not followers.
- **Safety** lives inside GO: always available, never the headline.
- **Reinforcement:** the safety infrastructure creates the look-back moment (arrival), the look-back creates know-how,
  know-how makes the next preparation better, preparation launches the safety tools. Remove any piece and the others
  weaken — which is the coherence test the current five-tab app fails.

## 8. The strongest differentiation opportunities

1. The loop with its hand-offs (§7) under one privacy model.
2. Expectation, not tracking (Heads-up + calm escalation).
3. Know-how about getting there / being there / getting back, anonymous and independently confirmed (validated first).
4. Offline-first safety that keeps working when the network, the server or the session do not.
5. Permanent anti-monitoring.

## 9. What NOT to become

- **Not** a social network: no feed, likes, followers, public profiles, popularity, endless scroll, generic chat.
- **Not** an everything app: no maps clone, no route planner, no events marketplace, no messaging, no payments.
- **Not** a fear app: no incident feed, no crime alerts, no "danger score".
- **Not** a monitoring tool: no family/partner/guardian mode, ever.
- **Not** a habit tracker: no streaks, badges, goals for their own sake.
- **Not** a review site: no star ratings of businesses or people.

## 10. Ideas worth the next phase (Phase 11, see §N)

- Safety foundation: C1 fix; Heads-up + phone-first Safe Walk; Ready check + Test my SOS; SOS shortcut/tile;
  periodic Circle location; retention; real-device sprint.
- "Go further" first version, phone-only: **Plans** (with look-back on arrival and private pass-it-on drafts);
  **Home "Today"** and the navigation restructure.
- **Know-how: design and validation only** (concept test with 8–12 people from the target group; go/no-go criteria).

## 11. Ideas that wait for validation

- Explore / Know-how implementation (after the validation in §10, starting as a pilot for one campus/city).
- "Worth going" places and activities (part of Know-how; needs the lure defence proven).
- "Ask someone who has been there" (privacy of answerers; harassment risk).
- Free-text tips (moderation capacity).
- Events and group activities run by known organisations (organiser verification).
- Evidence capture during an offline SOS; "After" screen and owner export.
- Phone-only Safety Circle (drop third-party data from the server).

## 12. Ideas rejected completely

- Feeds, likes, follower counts, public profiles, influencer content, popularity rankings, generic chat or DMs.
- App-brokered meetings with strangers (the highest-harm pattern of dating/meetup apps).
- Rating or reporting individual people.
- Live location visible to the community or to anyone by default.
- Gamification, streaks, badges, points.
- AI-generated recommendations or risk scores; ads or paid placement.
- Automatic evidence recording; family/guardian monitoring.
- Scraping or importing third-party reviews.

---

# Part III — The design

## M. Proposed product architecture

### M.1 One product, four layers, one rule

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ 4  EVERYONE        Explore: know-how + places worth going + area picture │  server, anonymous,
  │                    (later, validated)                                    │  cells, delayed
  ├─────────────────────────────────────────────────────────────────────────┤
  │ 3  SAFETY NET      SOS · Safe Walk · location · Evidence Vault           │  phone first, server record
  ├─────────────────────────────────────────────────────────────────────────┤
  │ 2  MY PEOPLE       Safety Circle · Heads-up · SOS/walk SMS               │  phone → person by SMS
  ├─────────────────────────────────────────────────────────────────────────┤
  │ 1  ME              Plans · playbook · readiness                          │  phone only, encrypted
  └─────────────────────────────────────────────────────────────────────────┘
```

**The rule:** data moves *up* a layer only by the person's explicit act (send a Heads-up, start an SOS, pass a tip on),
and is stripped on the way (no plan, no journey id, no time finer than a band). Nothing flows *down* into a lower layer
automatically. The safety net never depends on layer 4 (existing isolation tests stay).

### M.2 Feature map

| Feature | Layer | Problem | Trigger | Privacy boundary | Failure behaviour | Why it exists |
|---|---|---|---|---|---|---|
| Plans + playbook | 1 | Unfamiliar things feel too uncertain; lessons are lost | Something new is coming up; arrival | Phone-only, encrypted | Unreadable → explicit message, never silent empty | Ordinary-life core |
| Ready check + Test my SOS | 1 | People learn their SOS texts nobody in an emergency | Onboarding, SOS tile | Phone-only | Each item states its consequence | Turns features into a working net |
| Heads-up | 2 | Dead-man switch dies with the phone; "someone knows" without tracking | Leaving (from a plan or directly) | SMS from the phone; server never sees text/recipients | Works offline; if the phone dies the contact still expects "arrived" | Connection without monitoring |
| Safety Circle + SOS SMS | 2 | Tell my people fast | SOS, walk L2 | Allow-list text | SMS failure shown per contact | Core |
| SOS | 3 | Immediate danger | Tile, shortcut, QS tile | Record + track with outcome-based retention | Offline/no session: still local SMS + location | Core |
| Safe Walk | 3 | Check-ins on the way | From a plan or Home | Last location only | Phone-first start | Core |
| Evidence Vault | 3 | Evidence lost/edited/found | Manual during SOS | Ciphertext only on server | Refusals keep items | Integrity |
| Explore (later) | 4 | Practical knowledge about unfamiliar places | Planning, curiosity | Cells, bands, delays, independence, moderation | Empty → honest "not enough people yet" | Discovery that helps people go |

### M.3 Data-flow map

```
 phone ─────────────────────────────────────────────────────────────────────────────
  Plans/playbook (encrypted) ── nothing leaves unless "Pass it on" is tapped ──┐
        │ person taps "Go"                                                     │
        ▼                                                                      │
  Heads-up / Safe Walk ──SMS──► chosen people        SOS ──SMS──► Safety Circle │
        │                                             │                        │
        └─ journey (server: ids, deadline, last loc) └─ emergency + track      │
                                                         (server, retention)   │
  Evidence (sealed) ─────────────────────────────► ciphertext + custody        │
                                                                               ▼
 server ───────────────────────────────────── Explore know-how: cell + band + facts,
                                              published days later, no plan/journey link
```

Isolation rules (existing tests extended): Plans imports nothing from SAFE and vice versa; Explore/intelligence never
read SOS, walks, evidence or Circle; SOS/walk never read Explore except the optional read-only "Check the way"; no
server-side join between knowledge contributions and journeys/emergencies; message texts are built on the phone from
allow-listed fields and never stored on the server.

### M.4 Navigation and Home

- **Tabs: Home · Plans · Nearby (→ Explore later) · You.** The Safety tab and the Rise tab go.
- **Home "Today":** your next plan (if any) with its one-tap "Go"; an active SOS/walk banner; a one-line readiness
  status ("SOS ready — texts 2 people"); "Plan something new"; "Start a walk". The SOS button stays prominent on Home
  and is also reachable from the launcher shortcut and a Quick Settings tile. No hazard map on Home.
- **You:** profile, Safety Circle and automatic SMS, Evidence Vault, SOS history, settings, account.
- Until Explore is validated, Nearby keeps today's behaviour with an honest empty state.

### M.5 Offline architecture

| Situation | Today | Proposed |
|---|---|---|
| No internet | SOS works; walk cannot start; evidence blocked | Walk starts locally; Heads-up by SMS; Plans fully offline; evidence still blocked (later) |
| No GPS | SOS SMS after ≤ 30 s without location; one follow-up | + periodic updates when fixes arrive |
| Backend down | As no internet | Same |
| App killed | Recovery on next open | Heads-up contact covers walks; SOS SMS already sent |
| Reboot | Recovery on open (no boot receiver) | Unchanged |
| Locked phone | Works if location was granted; first SOS asks | Permissions granted in the Ready check |
| Low battery | Walk throttles; SOS sends latest fix once | + one battery-critical SMS to the Circle |
| Session expired | **SOS wiped / Login** | Local SOS works; sync resumes via refresh secret |

### M.6 Security and privacy architecture

| Data | Where | Encrypted at rest | Retention | Never |
|---|---|---|---|---|
| Access token (24 h) + refresh secret | Phone | Yes (Keystore, new) | Until sign-out/revocation | Logged |
| Refresh secret hash, `sid` | Server `device_sessions` (V19) | Hash only | Sliding 60–90 d | Stored in clear |
| Plans, playbook, pass-it-on drafts | Phone | Yes (AES-GCM, AAD) | Until deleted | Sent anywhere without a tap |
| Heads-up / Circle message text | Built on the phone | n/a | Not stored | Sent to the server |
| SOS record | Server | DB-level | Account lifetime | — |
| SOS location points | Server | DB-level | False alarm 7 d; resolved 90 d unless kept | Shown to anyone but the owner |
| Journey | Server | DB-level | Coordinates deleted at a normal end (existing) | Full track |
| Evidence | Phone + server | SSEV1 | Owner-controlled with guards | Readable by the server |
| Know-how (later) | Server | — | Expiry by kind | Coordinates, authors, exact times, plan links |
| Passwords | Server | BCrypt | — | Logged/returned |

---

## N. Proposed Phase 11 (the next phase): **"Go further, prepared"**

Naming: the repository already calls "SOS without internet" Phase 11; it becomes **11A (done)**. The next phase is
**11B–11H**, in two tracks, each sub-phase gated by build + tests + the user's check as usual.

**Goal:** *a person can plan something unfamiliar, go with someone expecting them, come back and keep what they
learned — and if anything goes wrong, the SOS works even after weeks of not opening the app, offline, in seconds.*

| Sub-phase | Track | Scope | Backend | Android | Migration |
|---|---|---|---|---|---|
| **11B Session continuity v2** | Foundation (first, blocking) | C1: expired ≠ signed out; refresh secret with rotation + reuse detection; Keystore-encrypted token (A14) | `POST /api/auth/refresh`, `device_sessions`, stamp revocation, eraser coverage | `SessionEnder` keeps safety state on `EXPIRED`; non-blocking password prompt | **V19** |
| **11C Heads-up + phone-first Safe Walk** | Both (safety backstop *and* the loop's GO step) | SMS expectation to chosen people ("back by 22:10"), "arrived"/"no longer going"; walk starts locally with a client reference | `safe_journeys.client_ref`, idempotent create, clamped phone times (Phase 11A pattern) | `SafeWalkSession` local-first; Heads-up composer/automatic | **V20** |
| **11D Plans** | Go further | Rise → Plans (decide, prepare, go, look back on arrival, playbook, private pass-it-on drafts) | — | Reuse Rise storage/lock/tests; new policies + texts tests | — |
| **11E Home "Today" + navigation + onboarding** | Go further | Tabs Home · Plans · Nearby · You; Today card; onboarding shows the loop and runs the Ready check | — | Navigation, Home, onboarding | — |
| **11F SOS reach + Circle updates** | Foundation | Ready check + Test my SOS; launcher shortcut + QS tile; periodic SOS location SMS; battery-critical SMS | report kind if needed | `ReadinessPolicy`, `TileService`, `CircleAlertPolicy` | V21 only if a new kind needs a CHECK change |
| **11G Retention + copy** | Foundation | Outcome-based SOS location retention, keep flag, password-gated delete (not < 24 h); neutral lock-screen text; shorter SOS notices | Scheduled job, endpoint | History, strings | **V21/V22** |
| **11H Real-device sprint** | Both | Galaxy A23: §Q incl. the 25 h idle test, real SIM SMS offline, Heads-up with the phone switched off, OEM killing, TalkBack + dark mode on SOS/Ready check/Plans | — | — | — |
| **11V Know-how validation (no code)** | Beyond safety | Concept test with 8–12 people from the target group (students who commute/travel in their city), consented, paper or clickable prototype. Questions: would they contribute after an outing; which facts matter; would they trust anonymous confirmed tips; what worries them. **Go/no-go** for Explore with written criteria | — | — | — |

Explicitly **not** in Phase 11: Explore/Know-how code, evidence offline capture, "After"/export, anything in §O.

## O. What should NOT be built (mandatory)

- Everything in Part II §12 (feeds, likes, followers, profiles, chat, stranger meetups, rating people, public live
  location, gamification, AI recommendations/risk scores, ads, scraping).
- Automatic evidence recording; any covert capture.
- Monitoring of a person by another — permanent.
- More Phase 9 machinery (coordination follow-ups, rules-2 promotion), 10C collective proof as designed.
- A route planner (`PHASE8_DESIGN` §16.4); a maps clone.
- Live-location web links or dashboards before an HTTPS deployment, a link-leak threat model and expiry design.
- A server SMS gateway / server dead-man alerts before a real deployment; Heads-up covers most of the need.
- Duress PIN, background shake/voice/power-button triggers (document the OS's own Emergency SOS instead).
- Verified Nearby Assistance (strangers + location + emergencies) before a real organisation and vetting exist.
- Events marketplace, payments, messaging.
- New dependencies beyond the approved ones.

## P. Dependency-aware implementation order

```
11B Session v2 (V19) ─────────┬──► 11C Heads-up + phone-first walk (V20) ──► 11D Plans ──► 11E Home/nav/onboarding
                              │                                                               ▲
                              └──► 11F Ready check, shortcut/tile, Circle updates ────────────┘ (Ready check
11G Retention + copy (independent; delete endpoint after 11B)                                      lives in onboarding)
11V Know-how validation (no code; can run in parallel from the start)
11H Real-device sprint (after 11C and again at the end)
        ▼
Later, only on a "go" from 11V: Explore pilot (one campus/city) → "worth going" → free text with moderation
Later: evidence capture during a local SOS · "After" screen + export · phone-only Circle
```

Why this order: 11B is a correctness precondition for trusting anything else. 11C before 11D because Plans' GO and
LOOK BACK steps depend on Heads-up/arrival. 11E after 11D because Home "Today" shows plans. Explore last because it
alone needs other people and moderators, and must be validated before code.

## Q. Verification plan

### Q.0 Baseline in this session
- Android `testDebugUnitTest --rerun`: **527 / 0 failures / 0 errors**. Backend `mvn test`: **482 / 0 / 0 / 2 skipped**.
- Not run: emulator, MySQL, real phone, lint. These numbers say existing behaviour is unchanged; they say nothing about
  C1, which no test covers.

### Q.1 Verification status today (from the docs, reconciled)

| Area | Implemented/tested | Emulator | Real device (A23) | Notes |
|---|---|---|---|---|
| SOS start/end/recovery | yes | verified | not verified | |
| SOS without internet | yes | verified (server down) | not verified | Real SIM SMS offline not verified |
| Automatic Circle SMS | yes | emulator SMS only | not verified | 4D manual checklist skipped by the user |
| Call 112 prompt | yes | verified | not verified | |
| Location sharing (4A) | yes | verified (automated run) | not verified | "4A not verified" in `HANDOFF` = the user's manual checklist (§R #3) |
| Safe Walk (4B) / movement (4C) | yes | verified / partial | not verified | same wording conflict |
| Process death | yes | verified (force-stop, evidence job) | not verified | Samsung background killing not verified |
| Reboot | n/a | not verified | not verified | By design: recovery on open |
| **Long idle → session expiry** | **no test** | **not verified** | **not verified** | **C1** |
| Evidence Vault | yes | verified | partially verified | `PHASE5.md` §11.5.1 |
| Account / renewal | yes | verified | not verified | |
| Rise screen lock | unit | n/a | not verified | |
| Notifications | yes | verified | not verified | |
| TalkBack | — | SOS tile only | not verified | |
| Dark mode | — | Phase 8 screens only | not verified | |
| Android 8–13 paths | unit | not verified | A23 only | |

### Q.2 Per proposed change

| Change | Automated | MySQL | Emulator | Real phone (A23) |
|---|---|---|---|---|
| 11B Session v2 | rotation; reuse → revoke; stamp revoke; deletion cascade; `EXPIRED` keeps SOS (`SessionEnderTest`, `SosRecorderTest`); two-user isolation | V19 dry run; live refresh/reuse script | Token expiry 2 min in a test config, kill app, wait, reopen offline → SOS works; reconnect → recorded | Phone untouched ≥ 25 h, then SOS offline and online |
| 11C Heads-up / walk | local start idempotency; text allow-list; offline start | V20 dry run; idempotent create | Server down → start walk → recorded later | Phone switched off mid-walk: contact never gets "arrived" |
| 11D Plans | policy tests (kinds, worries split, look-back, playbook reuse, replay honesty); `PlansTextsTest` (language rules kept); isolation; encryption round trip | — | Full loop incl. look-back after "arrived" | Screen-lock gate on a locked device; TalkBack |
| 11E Home/nav | navigation rules; `HomeTextsTest` | — | All states, 320 dp/2.0× | Samsung launcher |
| 11F Ready/tile/updates | `ReadinessPolicyTest`; policy: moved/not moved, cap, stop at end, battery once | report kind | TEST SMS; tile/shortcut open the countdown | Real SIM TEST SMS, dual-SIM; 30-min SOS with a real contact |
| 11G Retention | job by outcome; keep flag; 24 h guard; password | dry run + job on real rows | History | — |
| 11V Validation | — | — | — | Interview notes, consent, criteria, written go/no-go |

---

## R. Documentation vs implementation disagreements

| # | Disagreement | Which is right |
|---|---|---|
| 1 | `app.account.session-max-age=30d` / `PHASE10.md` §11 imply a 30-day sign-in; in practice the phone signs out ~24 h after its process last ran | Code (C1) |
| 2 | `CLAUDE.md` §9 "minimise location retention" vs `emergency_locations` kept for the account's lifetime | Code; fix in 11G |
| 3 | `HANDOFF.md` §1 "4A, 4B not verified" vs `CLAUDE.md`/phase docs "verified on 2026-10-19 (emulator)" | Both, different meanings (automated emulator run vs the user's manual checklist); use "emulator-verified" vs "manually verified" |
| 4 | `C:\Users\ABDUL RAFIQ\Desktop\CLAUDE.md` (outside the repo, still loaded into sessions) says "4A has not started", "Permissions today: INTERNET only" | Repo copy; deleting/replacing the Desktop copy is the user's decision |
| 5 | `HANDOFF.md` §3–§4 still show Phase 8/9 numbers (388/450 tests, DB v15) as "Last actual results" | Top of `HANDOFF.md` (482/527, v18) |
| 6 | `CLAUDE.md` §11 "one foreground service … type location"; manifest `location|microphone` | Manifest (Phase 5) |
| 7 | 4D "offline SMS fallback" read as working before Phase 11 | Fixed in 11A (`PHASE11.md` §1) |
| 8 | `SAFETY_CIRCLE` context projection described as a recipient view; no client calls it | Code: unused endpoint |
| 9 | `CLAUDE.md` §1 describes RISE as "Proof" and the product as SAFE + RISE | To be updated if the user approves Plans and Part II |

## S. Critical bug handling in this session

C1 is critical, but its fix is a security-model change (new long-lived credential, migration, revocation rules) that
the user must approve; a quick patch (e.g. ignoring 401s) would silently break the Phase 10A revocation guarantees.
**Nothing was changed.** It is 11B and goes first.
**Interim advice for demos and the A23:** open the app at least once a day, or sign in again before relying on it.

## T. Likely viva questions

- *What is SafeSphere for when nothing is wrong?* Part II §1, §4, §7: doing unfamiliar things — find out, get ready,
  go, look back, pass it on; safety is the foundation inside "go".
- *Why not a social network?* Part II §9, §12: no audience, no profiles; contribution is motivated by usefulness and
  reciprocity, and the privacy model protects where people go.
- *Why would anyone contribute?* Part II §5: it happens inside their own look-back; private, banded usefulness; norms.
- *Why did Rise become Plans?* §I: no moment of use; the valuable part is preparing for real outings and learning
  from them.
- *Why no automatic recording?* §J.
- *Most important bug found?* C1, and why tests and emulator runs could not see it.
- *Why validate Explore before building it?* It is the one feature that depends on other people; Phases 6–9 showed
  how much can be built for users who are not there yet.
