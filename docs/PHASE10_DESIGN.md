# Phase 10: RISE, redesigned ("Go further, at your pace"): design

Written 2026-10-21 in a **design-only session**. No code, migration, API, dependency, configuration or database state was
changed. Decisions are the architect's own, made under the user's brief of 2026-10-21 ("full autonomy; challenge the old
plans; design only"). As `CLAUDE.md` §3 requires, implementation starts only after the user's go-ahead. It runs in gated
sub-phases 10A–10D (§27), with a build and test between each.

Status of everything in this document: **planned**. Facts in §2 come from reading the repository on 2026-10-21. Where
something was not run, it says so.

---

## 1. Executive summary

**Verdict on the original RISE:** do not build it as specified. A feed of Small Wins, Achievements, Stories and Support
from strangers is a social network. It needs free text, which SafeSphere has deliberately never accepted (`PHASE6.md`
§3). It needs a working moderation team, which does not exist (`HANDOFF.md` §4: no MODERATOR in any setup). And it
cannot keep its promise to be women-centred, because SafeSphere does not know, and must not collect, anyone's gender.
It would turn "a safety app" into "a safety app plus a social app", which is exactly the incoherence the brief warns
about. It would also give harassers something no current SafeSphere feature gives them: a place to find and reach women.

**Recommended direction: RISE as a private "range" tool, with SAFE as its scaffolding.** Fear of harassment narrows
where and when women go: which college, which job, which bus, which evening class. RISE helps a person pick something
they want to do that feels out of reach, break it into small steps that they choose, take each step with SafeSphere's
existing tools if they want them ("Check the way", Safe Walk, the Safety Circle), and keep a private record of what
they did. The community side follows the rule SafeSphere already uses for places, **signals, not posts**: people may
add an anonymous, closed-list note of *what helped* (for example "did it with someone first"). Everyone sees only
system-wide totals, in bands, and only once enough people have contributed.

This keeps every RISE boundary by construction:

- No profiles, followers, likes, feeds or text shared between people.
- No contact surface, so no harassment surface.
- No popularity, because there is nothing to be popular for.
- No gamification. Streaks and badges that reward going out alone would be a safety hazard, not a motivator.
- No moderation queue, because nothing anyone writes reaches anyone else.

**Before RISE, a foundation pass (10A).** Inspection found that the product contradicts its own honesty rules in
visible places:

- Onboarding still says the features "are not available in this version yet".
- 7 of 11 Settings rows are "Coming soon", behind a Safety Center entry labelled **Available**.
- The privacy policy is a placeholder.
- There is no way to delete an account, change a password or revoke a token.

RISE adds a sensitive new data class (personal plans), so data rights must exist before it ships.

**Phase 10 is therefore smaller than the old plan, not bigger:**

| Sub-phase | What it builds |
|---|---|
| 10A | Coherence and data rights |
| 10B | Private goals. Android only, **no backend at all** |
| 10C | "What helped" anonymous counts. One small backend package, one migration. It has a stop rule and can be cut |
| 10D | Verification on a real phone |

**No AI/ML.** There is nothing to learn, no ground truth, and closed lists with fixed thresholds explain themselves (§15.4).

---

## 2. Current SafeSphere product assessment (from the repository, 2026-10-21)

### 2.1 What exists

| Area | Size (main source) | State |
|---|---|---|
| Android `feature/*` | evidence 3 842 · safewalk 1 861 · emergency 1 694 · community 1 452 · moderation 1 126 · circlealert 1 087 · safetycircle 784 · intelligence 715 · auth 574 · home 356 · onboarding 171 · profile 147 · settings 75 · safety 47 · map 0 (Kotlin lines) | 4A–9 implemented, compiled, unit-tested; Android 454 tests |
| Backend packages | community 2 482 · evidence 2 300 · moderation 1 584 · intelligence 931 · emergency 734 · journey 729 · evaluation 706 · config 496 · circlealert 401 · safetycontact 391 · emergencycontext 288 · security 262 · auth 250 · user 206 (Java lines) | 453 tests, 0 failures, 2 skipped |
| Database | V1–V15, next **V16** | V15 on dev MySQL |
| Endpoints | 70 mappings; **none** for account deletion, password change, password reset, data export or logout | — |

### 2.2 What is genuinely strong (and why)

1. **Data minimisation at the source.**
   - The phone sends geohash cells, never coordinates.
   - Reports store a delayed `counts_from` instead of the real time.
   - Area look-ups are limited in memory, because a database log of where people look would itself be a tracking log.

   These are design choices you can defend in a viva, and each is tested.
2. **Honest failure semantics.**
   - "Sent" is never "delivered".
   - Level 4 is a dial prompt, never "we called 112".
   - The Home card states only facts ("Nothing running"), never "safe".
   - Phase 8 never says a place is safe.

   Most student safety apps fake these.
3. **The evidence chain.** Phone-side encryption, a server re-hash before SEALED, a hash-chained custody record and
   coercion-aware deletion (refused while an emergency is ACTIVE and for 24 h after it ends). This is real security
   engineering.
4. **Pure, tested policies.** `CheckInPolicy`, `MovementPolicy`, `BatteryPolicy`, `SignalPolicy`, `TrustPolicy` and
   `AreaIntelligencePolicy` are pure, tested functions. The logic can be explained line by line.
5. **Saying no to ML (Phase 9).** "No ground truth, deliberate forgetting, therefore no model" is the most mature
   decision in the project.

### 2.3 What is weak (brutally)

| # | Weakness | Evidence | Decision |
|---|---|---|---|
| W1 | **Onboarding contradicts the product.** All three pages say features come "in future versions" / "are not available in this version yet" | `strings.xml` `onboarding_*_body` (Phase 1 text) | **10A** |
| W2 | **Settings is mostly a placeholder and is mislabelled.** 7 of 11 rows are Coming soon; Safety Center's "Safety Settings" is badged **Available** and opens that list | `SettingsFragment.kt`, `SafetyFragment.kt` | **10A**: remove dead rows, link real settings that exist elsewhere (automatic SMS, vault lock, recovery), fix the badge |
| W3 | **Home carries Phase 1 placeholders.** The "Your Safety Journey" timeline says "Emergency status: Coming soon" (emergencies exist since Phase 3). The "Safe Walk" and "Check-in" quick actions open the same screen | `HomeContent.kt` `TimelineSteps`, `QuickActions` | **10A**: remove the timeline (RISE takes its slot) and merge the duplicate tile |
| W4 | **Profile has three Coming-soon items for things that exist** ("My Safety Activity" = Emergency History; "My Community Contributions" = Community's "your reports") | `ProfileFragment.kt` | **10A**: link or remove |
| W5 | **No account deletion.** Google Play requires in-app account deletion for apps that create accounts. India's DPDP Act 2023 gives a right to erasure. For a *privacy-first* safety app this is the most visible gap | no endpoint; `users` FKs cascade, but moderation actor/decider FKs do **not** (V13) | **10A** (§12.1) |
| W6 | **No password change, no token revocation.** A stolen 24 h token cannot be stopped. A disabled account's token works for 24 h on non-moderation endpoints (`PHASE7.md` §8). A per-request user check exists only for moderation paths (`RoleAuthorization`) | `security/` | **10A** |
| W7 | **The privacy policy is a placeholder** ("not a final policy") while the app processes location, audio, SMS and evidence | `privacy_policy_body` | **10A**: an accurate data notice. Not legal advice; label it so |
| W8 | **The Evidence Vault and recovery passphrase are reachable only through the SOS / emergency-details screens.** A recovery passphrase must be set up *before* a phone is lost | `EmergencyFragment.kt:86`, `EmergencyDetailFragment.kt:84` | **10A**: Safety Center entry |
| W9 | **Terminology drift.** "Community" is place intelligence, not a community (and collides with RISE). Others: "Safety Profile" for the account page, "I'm OK" in the app vs "I'm Safe" in `CLAUDE.md`, the tagline "Safety, connected." vs the stated philosophy | strings, `CLAUDE.md` §11 | **10A**: terminology pass; tab "Community" → "Nearby" |
| W10 | **Verification debt.** 4A/4B never manually verified, 4D skipped, real phone deferred for 5–9, TalkBack never verified in 6–9. The demo-critical SOS/Safe Walk path has the *least* real-device evidence | `HANDOFF.md` §1, §5 | **10D** (part of Phase 10's definition of done) |
| W11 | **Phase 9 machinery assumes moderators who do not exist.** It is academically sound (measured attack costs) but operationally idle: coord-1 is SHADOW, rules-2 is SHADOW, every 9B rate is NOT_ENOUGH_DATA | `PHASE9.md` | **Keep frozen.** Do not extend. Present it as "adversarial evaluation", never as AI |
| W12 | **The session token is unencrypted in DataStore** (known) | `CLAUDE.md` §5 | Later hardening (pre-release) |
| W13 | **Release readiness.** Minification off (Gson keep rules missing), HTTPS deployment absent, Spring Boot 3.5.16 past OSS support, Play declarations (SMS, microphone FGS) not filed | `HANDOFF.md` §7 | Later hardening |
| W14 | **English only** for an India-first safety product | Phase 1 | Backlog (high value: Hindi + one regional language) |
| W15 | **Dead code.** `feature/map` is a README-only package | tree | Remove (10A) |
| W16 | **Project hygiene.** (a) **The folder is not a git repository.** Nine phases of work have no history, no backup and no diff, in a team project. (b) A second, stale `CLAUDE.md` in the parent `Desktop` folder (says "4A has not started") is loaded into every session alongside the real one. (c) The repo's `CLAUDE.md` §5 still says `feature/evidence` is "README-only" (it holds 25 files), and `README.md` stops at Phase 5 and says "no rate limiting" | file system | **Do before 10A**: `git init` + first commit; the user removes or renames the parent file; fix the stale lines in 10A |

None of W1–W9 is hard. Together they are what an examiner sees in the first two minutes of a demo.

---

## 3. Why Phase 10 exists

SAFE answers *"what happens when something goes wrong?"* It does not address the larger, everyday harm of insecurity,
which is avoidance. Women give up options to stay safe: the later class, the farther college, the job with a late shift,
the new neighbourhood.

There is published evidence for this. For example, Borker (World Bank / Brown, "Safety First", 2018–2021) reports
that Delhi University women choose lower-ranked colleges to get commutes they see as safer. *Check the exact citation
before using it in the report.*

A safety platform that stops at emergencies leaves that harm untouched. Phase 10 exists to close that gap without turning
SafeSphere into something else: **"Safe enough to live" (SAFE) is the means; "free enough to grow" (RISE) is the
point.**

---

## 4. The original RISE concept

From `CLAUDE.md` §1: a women-centred positive community made of Small Wins, Achievements, Everyday Progress, Break the
Box, Stories and support, with healthy sharing. Explicitly not LinkedIn and not Instagram.

What it gets right: the *values*. It wants growth, encouragement, no popularity and no influencers.

What it gets wrong: the *mechanism*. Every listed element except "Everyday Progress" is a sharing feature, and sharing
between strangers needs text, identity, moderation and contact. Each of those is a risk SafeSphere has avoided so far.
§5.1 evaluates it in detail.

---

## 5. Alternative concepts considered

### 5.1 The candidates

**A. Original RISE (shared community).** Posts of wins and stories, encouragement from others, achievements.
- Needs free text and probably photos, so it needs text moderation, image moderation, EXIF stripping, reporting and appeals.
- Needs a gender gate to be "women-centred". That means identity checks (privacy-hostile) or self-declaration (useless
  against harassers).
- Engagement mechanics creep in, because without reactions a feed feels dead.

**B. RISE redesigned: private range-building + anonymous "what helped" (recommended).**
- Personal goals, with steps the person chooses. SAFE tools are optional scaffolding.
- A private record of wins and comfort over time.
- A closed-list, system-wide, thresholded aggregate of strategies that helped.
- No contact between people.

**C. Circle Response (SAFE deepening, not RISE).** Safety Circle members get a capability link in the SOS SMS. Opening
it lets them say "I've seen it / I'm on my way", and the person in danger sees that on the SOS screen. This closes
SafeSphere's largest *safety* gap: today nobody knows whether anyone read the alert.
- Needs a public HTTPS deployment for the link to work outside the developer's PC.
- Needs a hardened public endpoint and anti-false-reassurance rules.

**D. Product 1.0 only (no new feature).** Honesty pass, data rights, onboarding, release hardening, localisation and the
real-phone verification sprint.

**E. Small private support circles.** Invite-only groups of up to 8 friends sharing wins. This is WhatsApp with fewer
features. People already have those groups. It adds text, moderation and group-abuse cases.

**F. Mentorship / opportunities board.** Women share jobs, courses and mentors. This is LinkedIn by the back door, and the
brief excludes it. Real identities are needed for trust, and a list of women seeking opportunities is a target.

### 5.2 Comparison

H = high, M = medium, L = low. "Safety risk" and "bloat risk": lower is better.

| Criterion | A Original | **B Redesigned** | C Circle Response | D 1.0 only | E Circles | F Mentorship |
|---|---|---|---|---|---|---|
| User value | M (if it worked) | **H**: targets avoidance, the everyday harm | H (emergency) | M | L–M | M |
| Uniqueness | L (a social app) | **H**: "signals, not posts" applied to empowerment | M–H | L | L | L |
| Technical depth | L–M (CRUD + moderation) | **M–H**: encrypted local store, cross-feature correlation defence, k-threshold aggregation, isolation | H (capability URLs, public endpoint hardening) | M (deletion lifecycle, revocation) | L | L |
| Safety risk | **H** (contact surface, harassment, doxxing) | **L** (no contact surface) | M (forwarded links, false reassurance) | L | M | H |
| Privacy | L (self-disclosure in posts) | **H** (plans never leave the phone) | M (link holders see a live state) | H | M | L |
| Complexity / maintenance | H (moderation forever) | **M** | M–H | M | M | H |
| Demo quality | M | **H** (a story that runs through SAFE) | H (needs two phones + deployment) | L | L | M |
| Viva value | L ("why not Instagram?") | **H** (judgment + privacy engineering) | H | M | L | L |
| Placement value | L | **M–H** | H | M (deletion and revocation are classic interview topics) | L | L |
| Relationship with SAFE | Weak ("random social app") | **Strong** (SAFE tools are its scaffolding) | Core SAFE | Neutral | Weak | None |
| Feature-bloat risk | H | **L** (closed scope, stop rule) | M | L | M | H |

### 5.3 Decision

**B, preceded by the minimum of D (10A).** C is the best *safety* feature not yet built, but it is not RISE and needs a
deployment. It is recommended as **Phase 11** (currently undefined) in §29. A, E and F are rejected (§25).

Why B beats C *for Phase 10*:

- Phase 10 is the RISE phase, and the product needs its "grow" side to be coherent.
- B can be demonstrated fully offline on one phone.
- B adds no new attack surface to the emergency path.

---

## 6. Recommended direction: "Rise: go further, at your pace"

One sentence: **Rise helps you do something you have been holding back from, one step at a time, with SafeSphere
beside you, and remembers what you did, only on your phone.**

| Element | What it is | From the original concept |
|---|---|---|
| **Goals** | Something you want to do that feels out of reach (title written by you, phone only) + a kind from a closed list | "Break the Box" becomes goal kinds (study, work, skill, sport, speaking up, travel) |
| **Steps** | A ladder of small steps you choose, with suggested ladders per kind; each step can carry *supports* (with someone, in daylight, known route, Safe Walk, tell my Circle, practise first, shorter version) | — |
| **Plan with SAFE** | From a travel step: "Check the way" and "Start a Safe Walk" with the destination pre-filled; "Tell someone in my Circle" | New: the bridge |
| **Wins** | A calm record of steps done and goals reached, with dates (date only) | Small Wins (private) |
| **Comfort** | An optional "how did it feel?" (5 labelled options) before/after a step; shown as first vs. latest per goal | Everyday Progress (private, never compared) |
| **Share a win** | Opens the SMS app with a short editable message to one Safety Circle person; nothing recorded | "Support", from people you chose |
| **What helped others** | System-wide bands of which supports people found helpful per goal kind, once ≥ 20 reflections exist | The "community", without posts |
| — | Achievements, badges, streaks, points | **Dropped** (§25) |
| — | Stories, feeds, reactions | **Dropped** (§25) |

### 6.1 How it differs from the original RISE

The original RISE puts growth in *sharing*. This design puts growth in *doing*, and puts community in *knowing you are
not alone and what helped*. It also turns the Safety Circle from an emergency list into people who are part of
ordinary life. That makes it more likely the Circle is kept up to date when an emergency does come.

---

## 7. Product goals

| ID | Goal | Measure |
|---|---|---|
| G1 | Help a person turn an avoided activity into small, self-chosen steps | Goal → ≥ 2 steps in under 2 minutes (usability check, 10D) |
| G2 | Make SAFE tools useful *before* anything goes wrong | A travel step opens "Check the way" / Safe Walk pre-filled in one tap |
| G3 | Never increase risk | No text pushes action; no notification or reminder from RISE; "Not now" is a first-class outcome (`RiseTextsTest`) |
| G4 | Plans never leave the phone | No goal title, step text, date, place or feeling in any request (`RiseIsolationTest` + request-body tests) |
| G5 | Community without contact | No endpoint returns anything written by, or about, another person |
| G6 | One product, not two apps | Rise reuses SafeSphere components, tone, colours and the Circle; SAFE is unaffected when Rise is unused |

Non-goals: engagement, retention metrics, daily active use, virality, growth hacking, any "time in app" target.

---

## 8. User personas and use cases

The personas are fictional. Their names and situations are design devices, not real people.

| Persona | Situation | Use case |
|---|---|---|
| **Asha**, 19, first-year engineering student | Leaves the library at 5 pm because the bus stop feels isolated after dark | Goal "Stay for the evening study group" (TRAVEL_EVENING). Steps: go once with a friend → go with a Safe Walk and "Check the way" → go with a Safe Walk only → go. Marks each done, notes that "with someone first" helped |
| **Meera**, 26, accountant | Offered a better job across the city with a 7 pm finish | Goal "Try the new commute" (PUBLIC_TRANSPORT). Uses Safe Walk on the first trips; shares the win with a sister in the Circle |
| **Fatima**, 34 | Wants to learn to ride a scooter; family doubts it | Goal (SKILL), no SAFE tools at all; private record of lessons. Rise works without location, permissions or a network |
| **Priya** (threat persona) | A partner checks the phone and knows its PIN | Must be able to keep titles vague, lock Rise behind the screen lock, delete everything in one action, and get no notification that reveals anything (§16) |
| **Adversaries** | A harasser looking for women; a manipulator gaming "what helped"; an abuser forcing account deletion to destroy evidence; a curious server operator | §16 |

---

## 9. Feature boundaries

**In scope (10A–10C):**

- Everything in §6.
- The 10A honesty and information-architecture pass.
- Password change with token revocation.
- Account deletion with coercion protections.

**Out of scope for Phase 10:**

- Data export (backlog: a valuable DPDP-style "access" right, but it creates a sensitive file on the phone; it needs its
  own design).
- Password reset (needs an e-mail channel).
- Encrypted cloud backup of Rise data (§29).
- Localisation.
- Circle Response (Phase 11).
- Release hardening (W12, W13).

**Hard rules:**

1. Rise never reads SAFE data. No journey, emergency, location, circle-alert or community data flows into Rise. Rise may
   only *navigate* to Safe Walk with a destination string.
2. SAFE never reads Rise data. An SOS, Safe Walk or Safety Circle message never mentions a goal.
3. Rise is invisible during an active SOS. The red banner stays the single emergency element on Home.
4. No Rise notifications of any kind.
5. No free text leaves the phone.

---

## 10. UX flow

```
Bottom nav (10A): Home · Safety · Nearby · Rise · Profile          (Nearby = today's Community tab, renamed)

Rise (tab)
├─ Empty state: "Is there something you'd like to do that feels out of reach right now?"  [Add a goal]
│     small print: "Your goals stay on this phone. SafeSphere never sees them."
├─ Add a goal
│     1. Kind (closed list with examples: "Travel in the evening", "A new place", "Public transport",
│        "Study or work opportunity", "Learn a skill", "Sport or fitness", "Speak up", "Something else")
│     2. Your words (≤ 80 chars, optional; hint "Only on this phone. Keep it vague if you like.")
│     3. Suggested steps for that kind (edit, reorder, delete, add your own) → [Save]
├─ Goal screen
│     steps as a ladder; each: [Plan it] [Done] [Not now]
│     Plan it → supports chips; for travel kinds: [Check the way] [Start a Safe Walk] (destination pre-filled
│               from the step if the person typed one) · [Tell someone in my Circle] (SMS app)
│     Done → "How did it feel?" (5 labelled options, or Skip) → "What helped?" (chips, optional)
│          → "Add this to what helped others?" (anonymous; shown once with explanation, default OFF)
│          → Win card: "Step done." + [Tell someone in my Circle] + [Back to your goal]
│     Not now → "That's fine. It stays here for when you want it." (no counter, no penalty)
│     Goal reached / Let it go / Pause (all neutral words)
├─ Your record: steps done and goals reached (date only); per goal "felt at first … / lately …"
├─ What helped others: per goal kind, top supports as bands ("most often" / "often" / "sometimes"),
│     "Based on dozens of reflections in the last 12 weeks", or "Not enough reflections yet"
└─ Rise settings: Keep Rise behind my screen lock · Share reflections by default · Delete all Rise data

Home (10A + 10B): the Phase 1 "Your Safety Journey" timeline is replaced by one calm card
"Rise: your goals, at your pace" (never a goal title; hidden during an SOS).
```

**Tone rules** (enforced by `RiseTextsTest`, like `HomeTextsTest`):

- No "safe/safer/safest" claims, no "brave/fearless/no excuses/push yourself", no "should/must" aimed at the person.
- No numbers that invite comparison.
- No exclamation-mark celebration on risky steps.

---

## 11. Android architecture

Same stack and conventions: XML Views, Fragments, ViewBinding, MVVM, `AppContainer`, `ApiCallHandler`. **No new
dependency.**

```
feature/rise/
├── domain/  GoalKind, Support, Feeling, Goal, Step, StepStatus, GoalStatus (enums + data classes)
│            GoalPolicy        (pure: limits ≤ 20 open goals, ≤ 12 steps, title ≤ 80, transitions)
│            StepTemplates     (static suggested ladders per kind; strings only)
│            ReflectionPolicy  (pure: what may be sent, ≤ 1 per kind per ISO week, random delay 6–48 h, drop > 28 d)
│            RiseMessages      (pure: the editable "share a win" SMS text; no goal title unless the person adds it)
├── data/    RiseStore (interface) + EncryptedFileRiseStore
│            RiseApi, RiseDtos (closed enums; unknown values dropped; toString redacts nothing sensitive because
│            nothing sensitive is in them), RemoteRiseRepository (ApiCallHandler)                       [10C]
└── ui/      RiseFragment + RiseViewModel (overview, record), GoalEditorFragment + VM, GoalFragment + VM,
             StepDoneSheet, WhatHelpedFragment + VM [10C], RiseSettingsFragment
```

- **Encrypted local store.**
  - One file `files/rise/rise-<accountId>.bin`, encrypted with AES-256-GCM. AAD = `SSRISE1|accountId`.
  - The data key is wrapped by the existing `LocalKeyWrapper` (`AndroidKeystoreKeyWrapper`).
  - Written atomically (temp file + fsync + rename, with a `.bak` fallback; the pattern is from `EvidenceVault`'s index).
  - Why encrypt when app-private storage already exists: goals describe future plans. The cost is one small class
    reusing existing primitives. App-private storage alone does not survive a backup extraction or a rooted-phone copy.
    `dataExtractionRules` already exclude everything.
- **Ownership.** The file is per account. A different signed-in account sees nothing (the AAD check fails closed).
  Logout keeps the file; the logout dialog says so and offers "Delete Rise data too". Account deletion (10A) removes it.
- **Lock.** "Keep Rise behind my screen lock" reuses `ScreenLockGate` and the `VaultLock` pattern. `ScreenLockGate`
  moves from `feature/evidence/ui` to `core/ui` (a pure move, no behaviour change). It is offered once at the first
  goal and is off by default, because a positive feature should not feel like a vault. `FLAG_SECURE` is set on all
  Rise screens, so the recents thumbnail cannot show goal titles. Wins are shared through the explicit SMS action,
  not screenshots.
- **SAFE bridge (the only touch points).**
  1. Safe Walk gains an optional nav argument `prefillDestination: String`. `SafeWalkFragment` puts it in the
     destination field. Nothing else in `feature/safewalk` changes, and the walk never learns where the text came from.
  2. "Check the way" is reached through that Safe Walk set-up screen.
  3. "Tell someone in my Circle" reuses the `ACTION_SENDTO smsto:` composer path from 4D. It needs no permission and
     no automatic SMS, ever.
- **Reflection outbox (10C).** Lives inside the encrypted store: `{goalKind, outcome, helped[], sendAfter}`. It is sent
  on a later app start or Rise visit after `sendAfter`, with no background job. Refused or stale entries are dropped.
- **Lifecycle.** No service, no WorkManager, no alarms, no receivers. Rise state survives rotation through ViewModels.
  Process death loses only an unsaved editor.

---

## 12. Backend architecture

### 12.1 10A: accounts (package `user`, `security`)

- **`ActiveAccountCheck`** (security). On every authenticated request, one primary-key lookup confirms that the user
  exists, is enabled, is not erased, and that the token's `iat` ≥ `credentials_changed_at`. Otherwise the answer is 401.
  - This generalises what `RoleAuthorization` already does for moderation paths.
  - It closes W6 (a disabled account's 24 h token) and makes revocation possible without refresh tokens.
  - It changes the Phase 2 decision "no server-side logout" into "revocation by timestamp". The decision log gets a
    new row.
  - Cost: one indexed read per request.
- **Password change.** `AccountService.changePassword` checks the current password (BCrypt, 72-byte rule), sets the new
  hash and `credentials_changed_at = now`, and returns a fresh token. Every other session ends. Wrong passwords are
  counted with `AttemptLimiter` (5 per 15 min → 429).
- **Account deletion** (coercion-aware, mirroring Phase 5's evidence rules).
  1. `POST /api/users/me/deletion {password}`. Refused with 409 while an emergency is ACTIVE, or within
     `app.evidence.delete-protection` (24 h) after one ended. Otherwise it sets `deletion_due_at = now + 7 days`.
  2. During the grace period the account works normally. The app shows a banner and "Cancel deletion" (password
     needed). Signing in does **not** cancel silently: an abuser's sign-in would otherwise undo a victim's deletion,
     and a victim's sign-in would undo nothing they wanted.
  3. `AccountDeletionJob` (scheduled, hourly) handles each due account.
     - It first deletes the evidence ciphertext files under `app.evidence.storage-dir`. Rows do not hold the files, so
       a cascade would orphan them.
     - Then it deletes the `users` row. FK cascades remove contacts, emergencies, locations, journeys, escalations,
       circle alerts, evidence rows, custody, recovery, community contributions, flags, standing and appeals.
     - A file-delete failure leaves the account due, and the next run retries. An orphan sweep compares storage keys
       with rows.
  4. **Moderators and admins are anonymised, not deleted.** `moderation_actions.actor_id` and
     `moderation_appeals.decided_by` intentionally have no cascade (V13: the audit trail must survive). Such an account
     gets name "Deleted account", e-mail `erased-<uuid>@invalid`, a random password hash, `enabled = false` and
     `erased_at`. Their *own* safety data is still deleted.
  5. 9B `evaluation_counters` hold no user and are unaffected. Pattern memory holds no user.

### 12.2 10C: `rise` package

```
rise/
├── controller/RiseController         POST /api/rise/reflections, GET /api/rise/insights
├── service/ReflectionService         validate closed lists; count only accounts ≥ 7 days old; receipt insert-or-ignore;
│                                     increment counters in the same transaction; always the same 202
├── service/InsightService            sum the last 12 complete ISO weeks; apply InsightPolicy
├── InsightPolicy (pure)              thresholds, bands, ordering; no numbers below the minimum
├── repository/…, entity/…, dto/…     records; enums only
└── config/RiseProperties             min-account-age 7d, min-reflections 20, min-support-occurrences 10,
                                      window-weeks 12, counter-retention-weeks 52, max-reflections-per-week 5
```

- Clean-up runs inside the existing scheduled job style. It deletes receipts once their week has ended and counters
  older than 52 weeks.
- **Isolation.** `RiseIsolationTest` (source scan, like `IntelligenceIsolationTest` / `EvaluationIsolationTest`).
  - `rise` must not reference `emergency`, `journey`, `escalation`, `circlealert`, `safetycontact`, `evidence`,
    `community`, `intelligence`, `moderation` or `evaluation`.
  - None of those may reference `rise`.
  - The Android equivalent covers `feature/rise` and the SAFE features. The one allowed edge is a navigation action ID.

---

## 13. Database model

**What is stored where**:

| Location | Data | Why it is needed |
|---|---|---|
| Phone (encrypted, per account) | goals (id, kind, optional title, created date, status), steps (id, optional text, optional destination text, supports, status, done date, feelings), reflection outbox, Rise settings | It *is* the feature |
| Phone | nothing about other people | — |
| Server, 10A | `users.credentials_changed_at`, `users.deletion_due_at`, `users.erased_at` | Revocation, grace period, audit-preserving anonymisation |
| Server, 10C | `rise_reflection_counters` (no user column), `rise_reflection_receipts` (user + week + goal kind, ≤ 8 days) | Aggregates; one-per-week dedupe |
| Server, never | goal titles, step text, destinations, dates finer than an ISO week, feelings, which insight was viewed, any per-user Rise history | — |

**"Do we need to store this?"**, applied to each field:

| Field | Keep? | Reason |
|---|---|---|
| Goal title (phone) | Yes, optional | Without words, a goal is meaningless to its owner |
| Step destination text (phone) | Yes, optional | Needed to pre-fill Safe Walk; never sent by Rise (the Safe Walk geocoder sees it only if the person starts a walk, as today) |
| Done *date* (phone) | Yes, date only | "Your record" needs an order; the hour adds nothing |
| Feelings (phone) | Yes, optional | Private "everyday progress"; never sent |
| Feelings (server) | **No** | Not needed for "what helped"; sensitive |
| Outcome in a reflection | Yes: STEP_DONE / GOAL_REACHED | Lets the aggregate speak about steps vs. goals |
| Receipt `user_id` | Yes, ≤ 8 days | The only way to enforce one reflection per goal kind per week. Hashing it would be theatre, because the server holds the key. Honest and short-lived instead |
| Exact time of a reflection (server) | **No** (week only) | Prevents correlation with Safe Walk end times (§16 T3) |

**V16 (10A) `V16__account_lifecycle.sql`:**
```sql
ALTER TABLE users
    ADD COLUMN credentials_changed_at DATETIME(6) NULL,   -- UTC; tokens issued before it are rejected
    ADD COLUMN deletion_due_at        DATETIME(6) NULL,   -- UTC; set by a deletion request, cleared by cancel
    ADD COLUMN erased_at              DATETIME(6) NULL,   -- UTC; moderator/admin anonymised instead of deleted
    ADD CONSTRAINT ck_users_erased_disabled CHECK (erased_at IS NULL OR enabled = FALSE),
    ADD CONSTRAINT ck_users_erased_no_deletion CHECK (erased_at IS NULL OR deletion_due_at IS NULL);
CREATE INDEX idx_users_deletion_due ON users (deletion_due_at);
```

**V17 (10C) `V17__rise_reflections.sql`:**
```sql
CREATE TABLE rise_reflection_counters (
    week_start  DATE        NOT NULL,                        -- ISO Monday, UTC
    goal_kind   VARCHAR(32) NOT NULL COLLATE utf8mb4_bin,
    metric      VARCHAR(16) NOT NULL COLLATE utf8mb4_bin,    -- REFLECTED | GOAL_REACHED | HELPED
    support     VARCHAR(24) NOT NULL COLLATE utf8mb4_bin,    -- '-' unless metric = HELPED
    occurrences INT         NOT NULL,
    PRIMARY KEY (week_start, goal_kind, metric, support),
    CONSTRAINT ck_rrc_monday   CHECK (DAYOFWEEK(week_start) = 2),
    CONSTRAINT ck_rrc_kind     CHECK (goal_kind IN ('TRAVEL_EVENING','NEW_PLACE','PUBLIC_TRANSPORT','STUDY_OR_WORK',
                                                    'SKILL','SPORT_OR_FITNESS','SPEAKING_UP','OTHER')),
    CONSTRAINT ck_rrc_metric   CHECK (metric IN ('REFLECTED','GOAL_REACHED','HELPED')),
    CONSTRAINT ck_rrc_support  CHECK ((metric = 'HELPED' AND support IN ('WITH_SOMEONE','IN_DAYLIGHT','KNOWN_ROUTE',
                                       'SAFE_WALK','CIRCLE_KNEW','PRACTISED_FIRST','SHORTER_VERSION'))
                                      OR (metric <> 'HELPED' AND support = '-')),
    CONSTRAINT ck_rrc_nonneg   CHECK (occurrences >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rise_reflection_receipts (
    user_id    BIGINT      NOT NULL,
    week_start DATE        NOT NULL,
    goal_kind  VARCHAR(32) NOT NULL COLLATE utf8mb4_bin,
    PRIMARY KEY (user_id, week_start, goal_kind),
    CONSTRAINT fk_rrr_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_rrr_monday CHECK (DAYOFWEEK(week_start) = 2),
    CONSTRAINT ck_rrr_kind   CHECK (goal_kind IN ('TRAVEL_EVENING','NEW_PLACE','PUBLIC_TRANSPORT','STUDY_OR_WORK',
                                                  'SKILL','SPORT_OR_FITNESS','SPEAKING_UP','OTHER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
```

Notes:
- The goal-kind taxonomy deliberately has no health, relationship, religion, family-conflict or sexuality kinds. A
  stored kind can never reveal a special category of data. "Something else" covers them, and the person's words stay
  on the phone.
- Binary collation on the closed lists is the lesson from the V15 dry run (`support_core` slipped through).
- Both migrations need a MySQL dry run of their CHECKs, because H2 does not exercise them (`CLAUDE.md` §6).

---

## 14. API design

**10A**

| Method | Path | Body → answer | Errors |
|---|---|---|---|
| PUT | `/api/users/me/password` | `{currentPassword, newPassword}` → 200 `{accessToken, user}` (old tokens now 401) | 400 validation, 401 wrong current password, 429 after 5 wrong in 15 min |
| POST | `/api/users/me/deletion` | `{password}` → 202 `{deletionDueAt}` | 401 wrong password, 409 emergency ACTIVE or within 24 h of one, 429 |
| DELETE | `/api/users/me/deletion` | `{password}` → 204 (cancelled) | 401, 404 nothing pending, 429 |
| GET | `/api/users/me` | adds `deletionDueAt` (null normally) | — |

**10C**

| Method | Path | Body → answer | Notes |
|---|---|---|---|
| POST | `/api/rise/reflections` | `{goalKind, outcome STEP_DONE\|GOAL_REACHED, helped[] ≤ 7}` → **202 `{status:"RECEIVED"}`, always the same** | Duplicate this week, a probation account, or over the weekly cap: all accepted and ignored with the identical answer, so nothing is learned from the response. Unknown enum → 400. Unknown fields ignored |
| GET | `/api/rise/insights` | → `{windowWeeks:12, currentWeekIncluded:false, kinds:[{goalKind, status OK\|NOT_ENOUGH_DATA, reflections DOZENS\|HUNDREDS\|THOUSANDS\|null, helped:[{support, share MOST_OFTEN\|OFTEN\|SOMETIMES}]}]}` | **All kinds in one response**, so the server never learns which goal a person is looking at. Same for every caller; cacheable 1 h; no counts below 20; supports below 10 occurrences omitted |

Bands: reflections 20–99 DOZENS, 100–999 HUNDREDS, ≥ 1000 THOUSANDS. Share of reflections naming a support: ≥ 50 %
MOST_OFTEN, 25–49 % OFTEN, 10–24 % SOMETIMES, < 10 % omitted. Exact numbers are never sent.

---

## 15. Privacy model

### 15.1 Invariants (each becomes a test, §23)

| ID | Invariant |
|---|---|
| P1 | No Rise request body contains free text, a date finer than the server's own clock, a coordinate, a cell or a feeling |
| P2 | No Rise response contains anything specific to the caller or to any other person |
| P3 | The server stores no Rise data with a user column except receipts, which are deleted after their week |
| P4 | No reflection is sent less than 6 h after it was made |
| P5 | Rise and SAFE do not read each other's data (source scans both sides) |
| P6 | No Rise notification exists (manifest + source scan: Rise does not reference `NotificationManager` / channels) |
| P7 | A different account on the same phone cannot read another account's Rise file (AAD mismatch → empty) |
| P8 | Insight output for an aggregate below its minimum is indistinguishable from no data |

### 15.2 Cross-feature correlation (the subtle risk)

The server knows when a person's Safe Walk ended and, for EMERGENCY walks, its destination. Suppose a reflection
"TRAVEL_EVENING, SAFE_WALK helped" arrived two minutes after a walk ended. The server would then know that this walk
was a Rise step, and so what this person is working towards.

The fix has three parts:

1. The phone delays each reflection by a random 6–48 h.
2. The server stores only the ISO week.
3. The receipt is gone after that week.

The server still *sees* the request when it arrives. It does not keep its time, and the delay breaks the link to any one
walk. The Phase 6 random publication delay does the same job for reports.

### 15.3 What stays honest

- The server operator can see that a given account sent *some* reflection of kind X this week, for up to 8 days
  (receipts). That is stated in the in-app notice.
- The phone holds sensitive plans. Encryption protects against copying, not against someone who can unlock the phone
  and open the app. The lock option and FLAG_SECURE raise the cost but cannot remove it (§26).

### 15.4 AI/ML: deliberately none

| Possible "AI" | Why not |
|---|---|
| Recommending next steps | Fixed templates the person edits are clearer, offline and unbiased; a model would need personal history SafeSphere must not keep |
| Classifying a goal from its title | The person picks the kind in one tap; NLP would need the title, which never leaves the phone |
| LLM coach / story generation | A third party would see the most sensitive text in the app; hallucinated safety advice is dangerous; it cannot be explained in a viva |
| Ranking "what helped" | Counting with thresholds is the honest method; there is no label to learn |
| Mood analytics | Not needed; storing feelings server-side is a special-category risk |

Same conclusion as Phase 9: a professional system knows when not to use AI.

---

## 16. Security threat model

| # | Threat | Defence | Residual risk |
|---|---|---|---|
| T1 | A coercive partner with the phone and its PIN reads goals | Optional screen-lock gate; FLAG_SECURE; vague titles encouraged; "Delete all Rise data" (one confirmation); no Home goal text; no notifications | Someone who can unlock the phone and open the app can read Rise. Not solvable in-app; stated in Rise settings |
| T2 | A stalker learns a person's plans from SafeSphere | Plans never leave the phone (P1); there is no contact surface | — |
| T3 | Linking a reflection to a Safe Walk | 6–48 h random delay, week-only storage, receipts ≤ 8 days (§15.2) | The operator sees the request arrive (not stored) |
| T4 | A Sybil farm skews "what helped" | Accounts ≥ 7 days old; 1 per kind per week per account; ≤ 5 per week per account; bands, not numbers; minimum counts; **the support list is benign by construction**, so the worst outcome is reordering harmless options (measured in §24) | Large patient farms can still reorder bands |
| T5 | Harassment, sexual harassment, unwanted contact via Rise | No user-to-user channel; no names, profiles or text shared | — |
| T6 | Screenshots, recents preview | FLAG_SECURE on Rise screens | Another camera |
| T7 | Backup / device-transfer extraction | `dataExtractionRules` exclude all; file encrypted with a Keystore-wrapped key | Rooted, unlocked device with a live process |
| T8 | Notification or Home leakage | No Rise notifications; Home card shows no goal; hidden during SOS | — |
| T9 | Forced account deletion to destroy evidence | Password; refused while ACTIVE and for 24 h after; 7-day grace with visible banner; cancel needs password | An abuser with the password and patience (same as Phase 5's evidence rule) |
| T10 | Stolen token after the person changes the password | `credentials_changed_at` check on every request | Token lifetime until the change |
| T11 | A deleted or disabled account's token | Per-request account check → 401 | — |
| T12 | Inference from which insight is viewed | One all-kinds response, cacheable | — |
| T13 | Harmful advice through Rise texts | Closed, reviewed lists; `RiseTextsTest` forbidden-word scan; templates never suggest going alone, at night, or without telling someone as a "next level" | Copy review is human |
| T14 | Encouraging risk (gamified exposure) | No streaks, points, badges, reminders; "Not now" is neutral; nothing compares people | — |
| T15 | Aggregate re-identification | System-wide only (no city, area or cohort); weekly; minimum 20; bands | With very few users everything is NOT_ENOUGH_DATA (correct) |
| T16 | Server or database breach | Receipts (≤ 8 days: user + kind + week) and counters only | Breach reveals "account X reflected on kind Y this week" |
| T17 | Brute-force password change / deletion | `AttemptLimiter` 5 per 15 min → 429; generic errors | In-memory, per instance (as Phase 7) |
| T18 | Moderator or admin misuse | No Rise admin view or endpoint; erasure keeps the audit trail but anonymises the person | DB administrator |
| T19 | Shared phone, several accounts | Per-account file + AAD; the signed-in account check | — |
| T20 | Fake "women-only" assurance | Rise claims no audience; it is safe for anyone because it has no audience | — |

---

## 17. Abuse and misuse prevention

1. **Remove the surface rather than police it.** Contact, text, identity and audience are absent, so the large classes of
   social abuse (harassment, doxxing, brigading, impersonation, popularity contests) have nothing to act on.
2. **Make manipulation harmless.** Every option in the shared list is a reasonable support. Gaming the counts can change
   which reasonable support appears first, never introduce a harmful one.
3. **Make silence the default.** Sharing a reflection is opt-in (default OFF). The explanation is shown before the
   first share.
4. **No self-harm vectors.** No nudges, no "you haven't taken a step in 5 days", no comparisons with others.
5. **Women-centred without gatekeeping.** Rise is designed around women's documented constraints (taxonomy, templates,
   copy). It does not verify gender. Verification would need identity data, would exclude people, and would still not
   stop a determined harasser. With no audience, there is nothing to gate.

---

## 18. Moderation requirements

- **Phase 10 needs no moderation queue, by construction.** No content reaches another person.
- What could still go wrong is the *product's own words* (templates, labels). Those go through code review and
  `RiseTextsTest`.
- **Gate for any future shared text** (e.g. "words that helped"): none may be built until all of these hold:
  1. ≥ 3 active moderators, measured with the 9B moderator counters.
  2. Pre-moderation (nothing visible before a human approves).
  3. A written privacy review.
  4. The user's decision.

  This mirrors gate G1 of Phase 9. Expected result for a student deployment: never met, and that is fine.

---

## 19. Accessibility considerations

- Every Rise card and step row is one TalkBack item with a full sentence ("Step 2 of 4: Go with a Safe Walk. Done on
  3 November."). Ladders announce position.
- Feelings are five labelled buttons, not a slider or emoji alone ("Very uneasy" … "At ease").
- Supports are chips with text. Status is never colour-only (word + icon, as the design system requires).
- Touch targets ≥ 48 dp. Layouts checked at 320 dp × 2.0 font, as the SOS tile was (`HANDOFF.md` §6).
- Plain language, short sentences, no idioms, so the copy translates well later (W14).
- Motion uses `Motion` (off when system animations are off). No confetti.
- 10D includes the TalkBack walkthrough that Phases 6–9 never had. It runs on a real phone.

---

## 20. Offline behaviour

| Function | Offline |
|---|---|
| Goals, steps, record, feelings, settings, delete | Fully offline (phone only) |
| Plan with SAFE | Safe Walk's own offline behaviour (4B queue); "Check the way" needs the network (Phase 8) |
| Share a win | SMS composer: no internet needed |
| Reflections | Queued in the encrypted store; sent after `sendAfter` when online; dropped after 28 days |
| What helped others | Last response cached ≤ 7 days with "as of <date>"; otherwise "Not available offline" |
| Password change / deletion | Online only (clear message) |

---

## 21. Data retention

| Data | Retention |
|---|---|
| Rise file (phone) | Until the person deletes a goal, "Delete all Rise data", account deletion, or app uninstall. Logout keeps it (stated), with an option to delete |
| Reflection outbox (phone) | Until sent, or 28 days |
| Insight cache (phone) | 7 days |
| `rise_reflection_receipts` | Deleted once their ISO week has ended (≤ 8 days) |
| `rise_reflection_counters` | 52 weeks (insights read 12) |
| `users.deletion_due_at` | Grace period 7 days, then the account is gone |
| Erased moderator/admin rows | As long as their audit rows (365 days), then deletable by the same job |

---

## 22. Rate limiting

| Action | Limit | Store |
|---|---|---|
| Reflections | 1 per goal kind per ISO week (DB key); ≤ 5 counted per week per account; identical 202 beyond | DB receipts |
| Insights | 30 per hour per account (cache makes more pointless) | In memory |
| Password change, deletion, cancel | 5 wrong passwords per 15 min → 429 | `AttemptLimiter` (in memory) |
| Account check | none (one PK read per request) | — |

---

## 23. Testing strategy (planned test names)

**Android (JUnit4 + coroutines-test, fakes in `testing/`)**
- `GoalPolicyTest`: limits, transitions (PLANNED → DONE / NOT_NOW, goal REACHED / PAUSED / LET_GO), "not now" changes nothing else.
- `StepTemplatesTest`: every kind has a ladder. No template step names "alone at night" or "without telling anyone"
  as a target.
- `ReflectionPolicyTest`: never < 6 h, ≤ 48 h, ≤ 1 per kind per week, stale dropped, nothing sent when sharing is off,
  body has only closed enums (P1, P4).
- `EncryptedFileRiseStoreTest`: round trip; tampered byte → empty plus a "could not read" state, never a crash; other
  account → empty (P7); atomic write with `.bak` recovery.
- `RiseViewModelTest` / `GoalViewModelTest`: create, edit, done with feelings and supports, not now, delete all, lock
  required ⇒ nothing rendered before unlock.
- `RiseTextsTest`: scans every Rise string for forbidden words (safe/safer/safest, brave, fearless, no excuses, push
  yourself, should, must, streak, level up, `!`).
- `RiseMessagesTest`: the SMS text holds no goal kind, location or date unless the person typed it.
- `RiseIsolationTest` (source scan): `feature/rise` ↔ emergency, safewalk session/policies, circlealert, evidence,
  community, intelligence, tracking. The only allowed edge is the Safe Walk nav action. No notification APIs in
  `feature/rise` (P6).
- `SafeWalkPrefillTest`: the argument fills the field only; the walk starts exactly as before.
- 10A: `OnboardingTextsTest` (no "future versions" / "not available"), `SettingsHonestyTest` (no row badged Available
  that is Coming soon; no Coming-soon row left without a reason), `ProfileViewModelTest` and `AccountViewModelTest`
  (password change replaces the token; deletion banner; cancel).

**Backend (`@SpringBootTest` + MockMvc, two-user tests are the norm)**
- `AccountLifecycleApiIntegrationTest`:
  - old token 401 after a password change;
  - a disabled account's token 401;
  - deletion refused while ACTIVE and within 24 h (409);
  - grace period, then the job deletes the rows and the evidence files;
  - a file-delete failure retries;
  - a moderator is anonymised, the audit rows are kept and name nobody;
  - the other user is unaffected;
  - wrong password 5× → 429.
- `RiseReflectionApiIntegrationTest`:
  - identical 202 for new / duplicate / probation / over-cap;
  - unknown enum 400;
  - unknown field ignored and not stored;
  - receipts removed after the week;
  - insights identical for two different users (P2);
  - below the minimum ⇒ NOT_ENOUGH_DATA with no numbers (P8);
  - bands correct at the boundaries.
- `InsightPolicyTest` (pure): thresholds, bands, ordering, ties.
- `RiseIsolationTest` (backend source scan, both directions).
- `TimeConventionsTest` extended to the new code. `UtcTimeZone` week arithmetic is tested in Asia/Kolkata (surefire
  already runs there).
- **MySQL dry runs** of V16 and V17: every CHECK violated once in a rolled-back transaction, including a lower-case
  `support_core`-style value against the binary collation.

---

## 24. Adversarial testing

These extend the Phase 9A simulator style: the real API, a virtual clock, SIMULATED reports in `backend/target/`.

| Scenario | Expectation (pass criterion) |
|---|---|
| R1 Sybil farm: 30 accounts ≥ 7 days push `IN_DAYLIGHT` every week | Report the number of accounts needed to move it one band at 100 / 1 000 honest reflections; must be ≥ 10 % of the honest volume (else raise minimums) |
| R2 Fresh accounts (< 7 days) | Zero effect |
| R3 One account, every kind, every week | ≤ 5 counted per week; never more than one per kind |
| R4 Response oracle | Every reflection answer is byte-identical whether counted or ignored |
| R5 Timing correlation | Property test over the outbox: no reflection is sent within 6 h of creation, and send times are uniform enough that the hour of a walk's end cannot be predicted (Kolmogorov–Smirnov sanity check on simulated delays) |
| R6 Enumeration | No endpoint accepts a user id, goal id or week parameter |
| R7 Coerced deletion | Abuser with the password during an ACTIVE SOS → 409; 1 h after → 409; after 24 h → grace + banner; victim cancels with the password |
| R8 Token after deletion / password change | 401 on the next request, including on SOS endpoints (stated as intended: the person changed the credentials) |

R8 needs care. A person who changes their password mid-emergency must not lose the SOS screen. The app replaces its own
token from the 200 response *before* any other call. Test: `passwordChange_duringActiveEmergency_keepsTheEmergencyWorkingOnThisPhone`.

---

## 25. What should NOT be built

| Not built | Why |
|---|---|
| Feeds, posts, comments, stories from people | Needs text + moderation that does not exist; harassment and self-disclosure surface |
| Likes, reactions, followers, follower counts, public profiles | Popularity mechanics, which the brief forbids |
| Achievements, badges, streaks, points, levels, leaderboards | Gamifying exposure to perceived risk can push people into danger; streaks create guilt |
| Group chats, DMs, friend requests, "women near you" | Contact surface; location exposure |
| In-app events or meet-ups with strangers | Physical-safety risk SafeSphere cannot vet |
| Gender verification | Identity data; exclusion; ineffective against determined harassers |
| Mentorship / opportunity board | LinkedIn by the back door; real identities required |
| Aggregate Safe Walk statistics ("N women walked here at night") | A predator's map; would also break the SAFE/intelligence isolation |
| City- or area-level Rise insights | Re-identification and stigma; system-wide only |
| AI coach, LLM, mood analytics, recommendations | §15.4 |
| Rise reminders / notifications | Pressure and lock-screen leakage |
| Server sync of goals (in Phase 10) | Plans are the most sensitive Rise data. Sync needs zero-knowledge encryption (§29), not "trust us" |

---

## 26. Risks and limitations (honest)

1. **Value depends on the person.** Rise is a quiet tool. Some people will find it too simple. That is the price of
   no engagement mechanics. 10D includes a usability check with 3–5 real classmates (consented, no data kept), and
   the result is reported honestly.
2. **"What helped" will show NOT_ENOUGH_DATA in every demo with real numbers.** A student deployment will not reach
   20 reflections per kind in 12 weeks. The demo uses the simulator, labelled SIMULATED, as Phase 9A did. 10C has a
   **stop rule**: if 10B's user check shows nobody would share, cut 10C and record why.
3. **A coercive partner with the PIN can read Rise.** That is true of any app, and it is stated.
4. **Phone loss loses Rise data** (no sync in Phase 10). It is stated at first use. Zero-knowledge sync comes later.
5. **The 7-day deletion grace is a trade-off.** It protects evidence from a coerced deletion and delays a real wish to
   leave. Both directions are documented.
6. **The per-request account check** adds one DB read per request. Negligible at this scale; it can be cached for
   seconds later.
7. **The receipt table is a short-lived per-user record.** It is chosen over pretend anonymity and disclosed.
8. **Tab rename** ("Community" → "Nearby") touches Phase 6 strings and one nav label. No Phase 6–9 logic changes.
9. **Scope creep from 10A.** Data export, password reset and localisation are tempting. They stay in the backlog.

---

## 27. Implementation phases (each gated by build + tests + the user's check, as in Phases 4 and 8)

| Step | Scope | Output |
|---|---|---|
| **0 (before anything)** | `git init` + first commit of the current tree; the user removes or renames the stale `Desktop\CLAUDE.md` | A history to diff against |
| **10A.1** Honesty and IA pass (Android only) | Onboarding rewritten (SAFE + RISE, true today); Settings rebuilt (only working rows: automatic SMS, vault lock, recovery passphrase, notifications link to system settings, privacy notice); Safety Center gets "Evidence Vault & recovery"; Home timeline removed, duplicate tile merged; Profile links to Emergency History and "your reports" instead of Coming soon; tagline; tab rename; `feature/map` removed; accurate in-app privacy notice; `CLAUDE.md`/`README.md` stale lines fixed | Tests: `OnboardingTextsTest`, `SettingsHonestyTest`, `HomeTextsTest` still passes |
| **10A.2** Account security (V16 part 1) | `ActiveAccountCheck`, `credentials_changed_at`, password change API + Android screen | `AccountLifecycleApiIntegrationTest` (revocation part), R8 |
| **10A.3** Account deletion (V16 part 2) | Deletion request/cancel, grace banner, job, evidence file deletion, moderator anonymisation, Android flow, local data wipe | Lifecycle tests, R7; V16 MySQL dry run |
| **10B** Rise private | `feature/rise` domain/data/ui, encrypted store, templates, Safe Walk prefill, share a win, lock option, Home card, tab | Android tests §23; emulator run |
| **10C** What helped (stoppable) | V17, `rise` package, outbox, insights screen, simulator R1–R6 | Backend tests, V17 dry run, live API check |
| **10D** Verification | Real phone (Galaxy A23): SOS → Safe Walk → Rise flows; TalkBack walkthrough; dark mode; 320 dp × 2.0; the oldest deferred 4A/4B checks | `PHASE10.md` with results in the status vocabulary |

Approximate size, measured against existing features:

| Sub-phase | Size | Compared with |
|---|---|---|
| 10A | ~0.5 × `feature/safetycircle` on Android plus ~400 backend lines | — |
| 10B | ~1.0–1.3 × `feature/community` (≈ 1 500–1 900 Kotlin lines) | — |
| 10C | ~0.6 × `evaluation` (≈ 400–500 Java lines) | — |

---

## 28. Definition of done (Phase 10)

1. 10A–10C implemented, compiled. `mvn test` and `testDebugUnitTest` at 0 failures. `lintDebug` 0 errors, warnings
   not increased without a reason.
2. V16 and V17 dry-run on MySQL (every CHECK violated once) and applied.
3. Live API check (real defaults) for revocation, deletion and reflections, with clean-up in `try/finally`
   (`CLAUDE.md` §12).
4. Emulator run of every Rise flow and the 10A screens. **Real-phone run (10D)** and a TalkBack walkthrough, or an
   explicit "NOT VERIFIED" for each item the user defers.
5. Privacy invariants P1–P8 and isolation scans green. Adversarial scenarios R1–R8 reported with numbers.
6. Security and privacy review written in `PHASE10.md`. `CLAUDE.md`, `API.md`, `DESIGN_SYSTEM.md` and
   `PLATFORM_CONSTRAINTS.md` updated. Decision-log rows added (revocation by timestamp; deletion grace; Rise phone-only;
   signals-not-posts for empowerment).
7. Viva notes: what changed, why, key classes, flows, lifecycle, privacy, likely questions (`CLAUDE.md` §3).
8. Only then "complete".

---

## 29. Future extension points

| Extension | Precondition |
|---|---|
| **Phase 11 proposal: Circle Response** (capability link in the SOS SMS; contacts answer "seen / on my way"; never lowers escalation; the link expires with the emergency) | Public HTTPS deployment; its own threat model (forwarded links, false reassurance, link scraping) |
| Zero-knowledge sync of Rise data | Reuse the Phase 5 recovery key (per-account RK wraps a Rise data key); server stores ciphertext only |
| Curated guides with partner organisations | Content review process; labelled as editorial |
| Pre-moderated "words that helped" | §18 gate |
| Localisation (Hindi + one regional language) | String freeze after 10A; `ResourceEncodingTest` already guards encoding |
| Data export | Own design for the sensitive file it creates |
| Release hardening (token encryption, minification, HTTPS, Spring Boot 4) | Before any public release |
| Phase 12 Verified Nearby Assistance | Unchanged; independent of Rise |

---

## 30. Final recommendation

1. **Proceed with Phase 10 as redesigned here, not as the original RISE.** The original concept's values are right and
   its mechanism is wrong. A private, SAFE-scaffolded Rise with anonymous "what helped" counts delivers growth,
   encouragement and community feeling without creating a social network that SafeSphere cannot protect.
2. **Do step 0 and 10A first, whatever happens to Rise.** They fix the visible honesty breaks, add the data rights a
   privacy-first product must have, and remove the main thing an examiner would catch in a demo.
3. **Treat 10C as optional.** It is the most "technical" part but the least valuable at student scale. Cut it if 10B's
   user check says nobody would share.
4. **Make 10D non-negotiable.** The real-phone and TalkBack evidence for SOS and Safe Walk is worth more to the
   project than any new feature.
5. **Leave Phase 9 frozen** and present it as adversarial evaluation, not AI.
6. **Propose Circle Response as Phase 11.** It is the best remaining improvement to SAFE.

---

## Appendix: viva notes (short)

- **Why not a women's social network?** Text, identity and contact are exactly what harassers need, and SafeSphere has
  no moderators. Removing the surface beats policing it.
- **How is Rise "community" then?** The same way Community Safety is: structured signals, not posts. People add what
  helped; everyone sees anonymous, thresholded totals.
- **Why no badges or streaks?** Rewarding exposure to perceived risk can push people into danger.
- **Why do goals never leave the phone?** They are future plans, the most stalking-relevant data in the product.
- **Why delay reflections 6–48 h?** So the server cannot link a reflection to the Safe Walk that just ended.
- **Why one insights response for all kinds?** So the server cannot learn which goal a person is looking at.
- **Why a 7-day deletion grace that sign-in does not cancel?** Coerced deletion must not destroy evidence, and an
  abuser's sign-in must not undo a victim's choice.
- **How does revocation work without refresh tokens?** One timestamp per user. Tokens issued before it are rejected by
  a per-request account check.
- **Why no AI?** No labels, no ground truth, and personal history we refuse to keep. Closed lists and thresholds are
  explainable and sufficient.
