# Phase 10: RISE as "Proof": design

**Second design pass, 2026-10-21.** Design only: no code, migration, API, dependency, configuration or database state was
changed. It replaces the first pass of the same day (committed as `fbe530b`). Decisions are the architect's own under the
user's briefs of 2026-10-21 ("full autonomy; design only", then "help women safely expand what they believe is possible
for their own lives"). Implementation starts only after the user's go-ahead, in gated sub-phases 10A–10D (§30).

**Status update 2026-10-22:** **10A is implemented, tested and verified on MySQL and the emulator** (results,
deviations D1–D12, the SAFE audit and the security/privacy review: `docs/PHASE10.md`). The main deviations: revocation
by an exact credentials stamp instead of `iat` (D1); a password change is **refused** during an active SOS or Safe Walk
instead of swapping tokens mid-emergency (D3, R8 below is therefore partly superseded); cancel is
`POST /api/users/me/deletion/cancel` (D4); anonymisation by audit references, not by role (D5); deletion postponed by
an emergency during the grace period (D6). 10B–10D: still **planned**.

**Status update 2026-10-22 (later):** **10B is implemented, tested and emulator-verified** with ten deliberate
deviations R1–R10 (no Home card, no private incident note, no "what helped", navigation-only SAFE bridge, SMS app
without a number, lock offered in the intro, …): `docs/PHASE11.md` §4. 10C is not built; 10D is not done.

Status of everything else here: **planned**. Facts about the current product come from reading the repository. Citations
marked *(verify)* must be checked against the original paper before they go into the project report.

---

## 0. What changed from the first pass, and why

| First pass | Problem found on reflection | This pass |
|---|---|---|
| Goals → steps → supports → private record | A to-do list helps people *do* things. It does nothing to change what they *believe* they can do, and the brief asks for exactly that | A belief-change mechanism: **expectation before, outcome after, and your own record shown back to you at the moment of doubt** (§3) |
| Suggested ladders ended with steps like "take it on your own" / "without Safe Walk" | **That quietly ranked support as weakness.** Removing support was the "top level". This is the exact message the user forbids | Steps are about the *activity*. Support is the person's choice on every step and is never ranked, never "levelled away" (§13, tested) |
| Goal kinds were mostly about mobility | Independence is also learning, work, money, leading, deciding and being oneself | Nine life domains (§8.2) |
| Every worry treated alike | "I'm not sure I can do this" and "I'm worried about my safety there" need opposite answers | **Self-doubts** are tested against evidence. **Safety concerns** are respected and lead to SAFE preparation, never to "prove it wrong" (§3.3) |
| "What helped" counts only | Weak community value | **Collective proof:** anonymous, thresholded counts of how things actually went for people who doubted the same thing (§8.5) |
| No stated identity | Rise felt like an add-on | Positioning: **"Safety that expands your world."** RISE: **"You decide what's for you."** (§2) |
| Silent on wording | Naming a stereotype, even to argue against it, repeats it | **Rise never names gender or a stereotype** (§13, tested) |

10A (coherence and data rights) is unchanged, except that its onboarding rewrite now carries the new identity.

---

## 1. Executive summary

**The mission, restated.** People's assumptions about what a woman can or cannot do should not decide her life. SafeSphere
should not argue with those assumptions. It should help a person act against them and keep the evidence that they did.
"Don't fight stereotypes with arguments; help people break them through action."

**The heart of Phase 10.** What can SafeSphere uniquely do to move someone from *"I don't think I can"* to *"I did it"*?
Three things that no notes app, social network or safety app combines:

1. **It turns a doubt into a prediction that can be tested.** Before a step: *"How do you think this will go?"* After:
   *"How did it go?"* A stereotype that someone has absorbed works as a prediction ("people like me can't do this"). Each
   step tests it against her own life.
2. **It shows her own evidence back to her at the moment it matters.** When she next writes *"I'm not sure I can do
   this on my own"*, Rise answers with her own record, not a slogan: *"You've felt this 4 times before. 3 went better
   than you expected."* Nobody argues with her. Her own past does the persuading.
3. **It makes preparation part of trying, so fear is planned for, not dismissed.** Where a step involves going
   somewhere, SAFE is right there: "Check the way", Safe Walk, a person from the Circle. Choosing support is presented as
   what capable people do, not as a weakness to outgrow.

This rests on the best-supported model of confidence change in psychology: Bandura's **self-efficacy** theory (1977). It
names four sources of self-belief:
- mastery experiences (strongest);
- vicarious experience (seeing similar people succeed);
- encouragement from others;
- emotional state.

Rise gives each one a privacy-safe form (§3.2).

**Identity.** SafeSphere becomes *"Safety that expands your world"*. SAFE: *"You deserve to feel safe."* RISE: *"You
decide what's for you."* The philosophy line stays: *"Safe enough to live. Free enough to grow."*

**Unchanged boundaries.**
- No feeds, followers, likes, profiles, streaks, points, badges or leaderboards.
- No contact between users, so no surface for harassment.
- No gender field: Rise is built around women's lives and is safe for anyone, because it has no audience.
- Personal plans never leave the phone.
- The only shared data is anonymous closed-list counts, shown in bands above a minimum.

**Size.** Smaller than the original RISE, and only slightly larger than the first pass: the mechanism adds a few fields
and one pure policy, not new infrastructure. **No AI/ML** (§18.4).

**Verdict.** This is genuinely better than the first pass (§33). The improvement is mostly product thinking, not code,
and that is the right kind of improvement.

---

## 2. Mission and product identity

### 2.1 The mission

Safety work often shrinks a person's world: warnings, curfews, "don't go there", "take someone with you". Some of it is
well meant, and some of it is the real danger talking. But a person who only ever hears what to avoid learns that the
world is not hers.

**SafeSphere's position: safety should expand a person's world, not shrink it.** Every SAFE feature is judged by whether
it lets someone do *more* with less fear. RISE is where that is measured in a person's own life.

### 2.2 Positioning options compared

| Candidate | Verdict |
|---|---|
| "An app that keeps women safe" | Accurate today, but passive and protective: it casts the user as someone to be guarded |
| "Stay safe enough to live freely" | Good, but long, and close to the existing philosophy line |
| **"Safety that expands your world"** | **Chosen.** It states a direction (outward), fits every SAFE feature, and is the opposite of the curfew logic of most safety advice. It needs no gendered wording |

| Side | Line |
|---|---|
| Product | **SafeSphere: safety that expands your world.** |
| Philosophy (unchanged) | *Safe enough to live. Free enough to grow.* |
| SAFE | *You deserve to feel safe.* |
| RISE | ***You decide what's for you.*** It answers "that's not for you / not for women" without repeating it, and puts the decision with the person |

The user's suggestion "You deserve the freedom to choose who you become" is strong in meaning. As a UI line it is long,
and it puts "deserve" next to SAFE's "deserve". "You decide what's for you" is shorter, active, and speaks directly to
the "not for women" message. The longer line can stay in the report and onboarding.

### 2.3 What "women-centred" means here

Rise is women-centred in **what it is built around**:
- the domains (going places, work, technology, money, deciding, being oneself);
- the doubts (doing it on my own, "not for someone like me", what others will say);
- the templates;
- the privacy design (coercive control is the main threat model).

It is **not** women-centred by **who is let in**. SafeSphere has no gender field and must not add one:
- Verifying gender needs identity data.
- It would exclude people.
- It would not stop a determined bad actor.
- With no audience in Rise, there is nothing to gate.

---

## 3. The heart: from "I don't think I can" to "I did it"

### 3.1 The mechanism: Doubt → Prepare → Try → Proof → Next

```
  DOUBT            PREPARE                TRY              PROOF                     NEXT
  "How do you      supports you choose;   the step         "How did it go?"          again · next step ·
   think this      for going somewhere:   itself           better / as expected /    something new ·
   will go?"       Check the way,                          harder / didn't go ahead  rest for now
  + optional       Safe Walk, a Circle                     → kept in Your proof      (all equal)
   self-doubt      person                                  → optional anonymous
                                                             reflection (delayed)
        ▲                                                                 │
        └────── next time the same doubt is chosen, your own proof is shown ┘
```

1. **Doubt.** Before a step, an optional question: *"How do you think this will go?"* The answers are "I don't think I
   can", "I'm not sure", "Probably fine" and "I'm confident". She can also pick a self-doubt from a short first-person
   list (§3.3). None of it is required.
2. **Prepare.** She chooses supports, which are not ranked: with someone, at a time I choose, a way I know, practise
   first, a smaller version, someone knows where I am. For steps that involve going somewhere, three SAFE actions sit
   on the same screen (§9).
3. **Try.** She does the step. The app does nothing during it: no notifications, no timers. A Safe Walk, if she chose
   one, runs as usual and knows nothing about Rise.
4. **Proof.** *"How did it go?"*
   - The answers are "Better than I expected", "About as I expected", "Harder than I expected", "I didn't go ahead"
     (*"That was your call."*) and "Something happened that wasn't OK" (§3.4).
   - She can mark the step as a first time.
   - The step is kept in **Your proof** with its date (date only), her prediction and its outcome.
5. **Next.** She chooses: do it again, take the next step, try something new, or rest for now. The app never suggests
   something "bigger", "braver" or "with less support".

### 3.2 Why it works: self-efficacy, each source made privacy-safe

| Source of self-belief (Bandura 1977, *Psychological Review* 84(2)) | Rise's form | What is shared |
|---|---|---|
| **Mastery experience**, the strongest source | Steps the person chose and did, kept as Your proof | Nothing |
| **Expectation vs. outcome** (CBT "behavioural experiments": predict, test, compare; Bennett-Levy et al. 2004 *(verify)*) | Doubt before, outcome after, gap shown over time | Nothing |
| **Vicarious experience** (similar people succeeding) | **Collective proof**: "Most people who weren't sure they could do this on their own found it went better than they expected" | Anonymous closed-list counts, system-wide, thresholded |
| **Verbal persuasion** | Her own past evidence replayed at the moment of doubt; optionally a message from someone she chose in her Circle | An SMS she sends herself; nothing to the server |
| **Emotional state** | Calm wording; nerves before a first time are named as normal; preparation reduces uncertainty | Nothing |

Two honest limits:
- **This is a design rationale, not a clinical claim.** Rise is not therapy and does not say it is.
- **Whether it changes anyone's self-belief is untested.** 10D adds a small, consented check (§26.4). Its results will
  be anecdotal at student scale and will be reported as such.

### 3.3 Two kinds of worry, two opposite answers

| Kind | Examples (shown in the first person, never naming gender) | Rise's answer |
|---|---|---|
| **Self-doubt** (about me) | "I'm not sure I can do this" · "…on my own" · "It doesn't feel like it's for someone like me" · "I'm worried what others will think or say" · "I don't think I'm good enough at this yet" · "I've never done anything like this" | Treated as a **prediction to test**. Proof is shown back next time |
| **Safety concern** (about the situation) | "I'm worried about my safety getting there or being there" | **Respected, never "disproven".** It opens the SAFE preparation actions. It is never counted in collective proof, and Rise never suggests that a safety worry was unfounded |

This split is the most important safety rule in Phase 10. If Rise treated "is it safe?" as a confidence problem, it would
teach people to ignore real warnings. Rise teaches that **preparing for real risk is part of being capable.**

"It doesn't feel like it's for someone like me" is how an absorbed stereotype ("that career isn't for women") sounds from
the inside, without the app ever saying the stereotype.

### 3.4 When something goes wrong

"Something happened that wasn't OK" is a first-class outcome:
- **Wording:** *"That wasn't your fault."*
- **Options:**
  - SOS, if it is happening now;
  - "Tell someone in your Circle" (SMS app);
  - "Report a place problem" (opens Nearby);
  - "Keep a private note" (phone only).
- **What does not happen:**
  - It is **never** sent as a reflection and never counted.
  - It is never "harder than expected" or a failure.
  - Rise sends nothing to SAFE on its own; every option is a button she presses.

### 3.5 How it challenges stereotypes without arguing

- **The evidence is the argument.** A stereotype says "you can't". Your proof says "you did, eleven times". Nobody else
  has to be wrong for that to be true.
- **The stereotype is never repeated.** Rise text never says "women", "men", "girls", "male-dominated", "not for women"
  or "prove them wrong". Research on stereotype threat suggests that reminding people of a negative stereotype before a
  task can lower performance: Steele & Aronson 1995; for women and mathematics, Spencer, Steele & Quinn 1999 *(verify;
  later meta-analyses report smaller effects and publication bias, e.g. Flore & Wicherts 2015)*. Whatever the true
  effect size, leaving the stereotype out costs nothing.
- **No opponent.** "Others" appear only as a self-doubt ("what others will think"), never as a group to fight. There is
  no "them" in any Rise text (`RiseTextsTest`).
- **Collective proof replaces debate.** Thousands of anonymous "it went better than I expected" answers say more than any
  argument, and nobody can reply to them.

---

## 4. Current product assessment

### 4.1 Engineering strengths and weaknesses

Unchanged from the first pass, condensed.

**Strong:**
- Data minimisation at the source: geohash cells, delayed times, in-memory limiters.
- Honest failure semantics: "sent ≠ delivered", a dial prompt rather than a claimed 112 integration, no "safe" claims.
- A coercion-aware evidence chain.
- Pure, tested safety policies.
- Phase 9's reasoned "no ML".

**Weak** (each is in §30's 10A unless marked):

| # | Weakness |
|---|---|
| W1 | Onboarding says features are "not available in this version yet" |
| W2 | 7 of 11 Settings rows are "Coming soon", behind an **Available** badge |
| W3 | The Home timeline is a Phase 1 placeholder; the duplicate "Check-in" tile opens Safe Walk |
| W4 | Profile lists Coming-soon items that already exist elsewhere |
| W5 | No account deletion (Play policy; DPDP Act 2023) |
| W6 | No password change or token revocation; a disabled account's token works for 24 h |
| W7 | The privacy policy is a placeholder |
| W8 | The vault and recovery passphrase are reachable only via SOS screens |
| W9 | Terminology drift ("Community" is not a community; the tagline "Safety, connected.") |
| W10 | Verification debt (4A/4B, real phone 5–9, TalkBack): **10D** |
| W11 | Phase 9 machinery is idle without moderators: **keep frozen; present as adversarial evaluation** |
| W12 | The session token is unencrypted: later hardening |
| W13 | Release readiness: later hardening |
| W14 | English only: backlog |
| W15 | `feature/map` is dead code |
| W16 | Project hygiene. **Git was initialised on 2026-10-21 (`fbe530b`).** Still open: the stale `Desktop\CLAUDE.md` (the user removes it), the stale lines in `CLAUDE.md`/`README.md` (10A) |

### 4.2 Whole-project review against the new philosophy

The question for each part: does it expand the user's world, or shrink it?

| Part | Supports "safety that expands your world"? | Evidence | Change (planned, not made) |
|---|---|---|---|
| **SOS** | **Yes.** It is under the person's control: a cancellable countdown, nothing sent unless she opted in, no claimed rescue | 4D opt-in SMS, honest Level 4 | None |
| **Safety Circle** | **Partly.** Chosen people, no tracking on demand, no "guardian" role. But it is introduced only for emergencies: *"…part of your emergency response network"* | `safety_circle_empty_message` | 10A: *"People you choose to keep in the loop: when something goes wrong, and when something goes right."* Rise gives the Circle a positive use (sharing a win) |
| **Safe Walk** | **Yes.** She starts it, answers "I'm OK" herself, arrival never ends it automatically, nobody can start it for her | 4B/4C | 10A: one line on the set-up screen, *"Using support is a choice, not a weakness."* Rise can pre-fill the destination |
| **Community Safety Intelligence** | **Mostly.** Place-only, no people, and **safe places as well as risks**. But the summary leads with concerns ("1 recent concern, 2 places to take care, 1 safe place") | `PHASE6.md` §1 | 10A: lead with where you *can* go (nearest safe place), then concerns. The order of words only; no logic change |
| **Safety Intelligence** | **Yes, notably.** It never says "avoid" or "stay away" (no such string exists); "Take care" is advice to prepare, not a prohibition; unknown is never "safe" | `outlook_*`; grep for "avoid" finds nothing | Present "Check the way" as *planning your way*, not *checking for danger*: label only |
| **Trust and moderation** | **Yes.** Protects honest voices from being silenced (no flag-bombing a real warning), never punishes automatically, no visible status | `PHASE7.md` | None |
| **Evidence Vault** | **Yes.** Her evidence, her control, protected against coerced deletion. Being believed is a form of power | `PHASE5.md` | Make it reachable from Safety Center (W8) |
| **Architecture** | **Yes.** Strict isolation between features, pure policies, phone-side privacy: Rise fits the same patterns | Isolation tests in Phases 8 and 9 | None |
| **What SafeSphere does NOT do** | **Strongly yes.** No feature lets anyone else track her: no family mode, no location on demand, no parent/partner dashboard. Many "safety" apps are sold to families as monitoring tools. SafeSphere's refusal is its most important empowerment decision, and it was never written down as one | code inspection | Record it in the decision log as permanent (§28) |

**Where the product accidentally reinforces fear or dependence:**
1. The Circle is framed as only for emergencies.
2. The community summary leads with concerns.
3. Onboarding says nothing is ready.
4. My own first-pass Rise ladders (fixed here).

Nothing in SAFE promises safety, and Rise must keep that rule: it never says a step is safe. It only says she prepared.

---

## 5. Why Phase 10 exists

- **SAFE handles the moment something goes wrong.** It does not touch the bigger everyday cost of insecurity, which is
  avoidance: choosing the nearer college, refusing the late-shift job, skipping the event, not travelling.
  - Borker ("Safety First", World Bank/Brown 2018–2021 *(verify)*) reports that Delhi University women choose
    lower-ranked colleges to get commutes they see as safer.
- **Stereotypes add a second, internal kind of avoidance.** It shows up as "that's not for me", "I can't do that on my
  own" and "what will people say".
- **Phase 10 exists so that SafeSphere addresses both:**
  - SAFE reduces the real risk;
  - RISE helps the person test the internal limit, and keeps the proof.

---

## 6. The original RISE concept

Small Wins, Achievements, Everyday Progress, Break the Box, Stories and support, shared in a women-centred community.

- **The values are right.** The mechanism is not:
  - Sharing between strangers needs free text, identity, moderation and contact, which SafeSphere has never accepted
    and has no moderators for.
  - It cannot keep its "women-centred" promise without collecting gender.
- **It rewards visibility,** which is the attention model the brief rejects.

---

## 7. Alternatives considered

| | Concept |
|---|---|
| **A** | **First-pass Rise:** private goals → steps → supports → record, plus anonymous "what helped" |
| **B** | **Rise "Proof" (recommended):** A plus the self-efficacy mechanism (expectation vs. outcome, proof replay), the self-doubt / safety-concern split, life domains, unranked supports, collective proof |
| **C** | **Playbooks library:** curated, expert-written guides for common "firsts" (first solo trip, first interview, first coding project, opening a bank account), each with preparation checklists linked to SAFE |
| **D** | **Curated role-model stories:** an editorial collection of real women's first-time stories, written or approved by the team or partner organisations |
| **E** | Original RISE (shared community), small friend circles, mentorship board: rejected in the first pass, still rejected (§28) |

H = high, M = medium, L = low.

| Criterion | A | **B** | C | D |
|---|---|---|---|---|
| Real-world value | M: helps doing | **H**: helps doing *and* believing | M: good information, generic | L–M: inspiring, passive |
| Uniqueness | M | **H**: no app combines safety preparation with predicted-vs-actual proof | L (blogs exist) | L (media exists) |
| Privacy | H | **H** (same model, a few more phone-only fields) | H (static) | M (real people's stories are personal data) |
| Abuse risk | L | **L** (no contact surface; benign closed lists) | L | M (stories attract harassment of their subjects; consent and takedown work) |
| Technical depth | M–H | **M–H** (+ a pure `ProofPolicy`, a richer aggregate model) | L (content) | L |
| Relationship with SAFE | Strong | **Strongest**: SAFE is the "Prepare" stage, and the safety-concern split is a safety rule | M | Weak |
| Complexity | M | **M** (≈ +15 % over A) | M (content writing and upkeep) | M–H (editorial work forever) |
| Academic value | M | **H**: grounded in a named theory, testable design, explicit ethics | L | L |
| Placement value | M–H | **H**: product reasoning plus privacy engineering | L | L |
| Long-term usefulness | M | **H**: the record grows with the person | M (content goes stale) | L |
| Final-year demo | M | **H**: one story runs from doubt through Check the way and Safe Walk to proof | M | L |
| Risk of "gender war" tone | L | **L** (stereotypes never named, tested) | M (guides tend to explain "why women…") | **H** (stories are framed against someone) |

**Decision: B.**
- **C** is folded in as lightweight templates per domain (§8.2): the useful part of a playbook is the suggested steps,
  which B already has.
- **D** is rejected for Phase 10. Editorial stories need consent, takedown and moderation processes and drift towards
  "us vs them" framing. Collective proof gives the vicarious effect without real people's stories.

---

## 8. Recommended concept: Rise "Proof"

### 8.1 One sentence

**Rise helps you try something you've been told, or have told yourself, is not for you. You prepare with SafeSphere if
you want to, and Rise keeps the proof of what you did, only on your phone.**

### 8.2 Life domains (closed list; templates included)

| Domain (UI) | Code | Example template steps (suggestions she edits; never "remove support") |
|---|---|---|
| Go places | `GO` | Look at the way · Go once at a time you choose, with any support you want · Go to the event / trip / class |
| Learn | `LEARN` | Try one beginner lesson · Make one small thing · Show it to someone you choose |
| Work and build | `WORK` | Find one opening or idea that interests you · Ask someone who does it one question · Prepare · Apply / start |
| Lead and speak up | `LEAD` | Say one thing in a meeting or class · Volunteer for one small task · Present to people you know |
| Move | `MOVE` | Find a place or group · Go once · Keep going if you want |
| Money of my own | `MONEY` | Find out one thing (an account, a budget, a course) · Do the first step · Keep track your own way |
| My own decision | `DECIDE` | Write what you want · Find out the facts · Talk to someone you choose · Decide |
| Be myself | `BE_ME` | Something you'd like to wear, say or choose for yourself, in a place you pick |
| Something else | `OTHER` | Her own steps |

- **What is stored where.** The domain is stored as a code. Her own words for the goal and the steps stay on the phone.
- **Deliberately absent domains.** No health, relationship, religion or sexuality domains exist, so a stored code can
  never reveal a special category of data. "My own decision" and "Something else" cover them, in her own words, on the
  phone.
- **Rules for template wording.** No template is gendered ("for women", "even though you're a woman"). No template
  names a field as male-dominated: the app does not reinforce a stereotype by describing it.

### 8.3 Your proof (the private record)

- **What it lists:** steps done, with date only, prediction and outcome, and an optional "a first" mark.
- **Per goal:** a plain summary, e.g. *"You weren't sure 5 times. 4 went better than you expected."*
- **What it never shows:** a headline count, a progress bar towards a "level", a comparison with other people, or a
  streak.
- **The replay moment:** when she picks a self-doubt she has picked before, the doubt screen shows her own matching
  proof. *"You've felt this 4 times. 3 went better than you expected. 1 was harder, and you did it."* There is a "See
  them" link. If her past outcomes were mostly harder, it says so honestly and adds nothing persuasive. The words are
  computed on the phone by `ProofPolicy`.

### 8.4 Share with someone you chose (optional)

- **"Tell someone in your Circle"** is offered after Proof, or before a step ("tell them you're trying").
  - It opens the SMS app with editable text built by `RiseMessages`. Nothing is recorded.
  - The goal's words are included only if she types them.
- **Default off, never suggested twice in a row.** A Circle member may be the very person who discourages her.

### 8.5 Collective proof (anonymous, optional, stoppable)

- **What a reflection is.** After Proof, she may add an anonymous reflection: `{domain, outcome, selfDoubt?, helped[],
  first}`. A safety concern and "something happened" are never included.
- **How it is sent.** The phone sends it 6–48 h later (§18.2).
- **What everyone sees:** system-wide bands over the last 12 weeks, once at least 20 reflections exist. Examples:
  - *"Hundreds of people reached a step in Learn recently. When people weren't sure it was for someone like them, it
    went better than they expected most often."*
  - *"What helped most often: practising first."*
- **"Harder than expected" and "didn't go ahead" are shown too.** Collective proof must not become collective pressure.

---

## 9. How it connects with SAFE

| Moment | SAFE tool | How |
|---|---|---|
| Prepare a step that involves going somewhere | **Check the way** (Phase 8) | Opens Safe Walk set-up with the destination pre-filled. The brief is computed on the phone; the server never sees a route |
| Do it with support | **Safe Walk** (4B/4C) | Same screen, same session. The walk does not know it came from Rise (one nav argument, a string) |
| Someone knows | **Safety Circle** (Phase 3) | SMS app with a "trying something today" text she edits and sends |
| Something goes wrong | **SOS** (Phases 3–5) | One tap from the Proof screen and always on Home. Rise disappears during an SOS |
| A place was a problem | **Nearby** (Phase 6) | "Report a place problem" opens the existing report flow. Rise sends nothing on its own |
| Being believed | **Evidence Vault** (Phase 5) | Not linked. Evidence exists only inside an emergency (existing rule); Rise never touches it |

Data flows only by navigation: SAFE never reads Rise, and Rise never reads SAFE (§14, tested). The product story is
**SAFE makes trying possible; RISE makes trying count.**

---

## 10. Product goals

| ID | Goal | Measure |
|---|---|---|
| G1 | Turn a doubt into a testable step in under 2 minutes | Usability check (10D) |
| G2 | Make choosing support feel like strength | No text ranks support; `StepTemplatesTest` + `RiseTextsTest` |
| G3 | Never increase risk | No nudges, notifications, streaks or "braver next step"; safety concerns never "disproven" |
| G4 | Plans never leave the phone | Request-body tests; isolation scans |
| G5 | Belief change from her own evidence | The replay moment appears whenever matching proof exists (tested); small consented check in 10D |
| G6 | Community without contact | No endpoint returns anything written by, or about, a person |
| G7 | Never name gender or a stereotype in Rise | `RiseTextsTest` forbidden-word scan |

Non-goals: engagement, retention, daily use, virality, time in app.

---

## 11. Personas and use cases

The personas are fictional; their names and situations are design devices.

| Persona | Doubt | Use |
|---|---|---|
| **Asha**, 19, engineering student | "It doesn't feel like it's for someone like me" (a hackathon) | LEARN goal. Steps: one tutorial → a tiny app → sign up → go (GO step with Check the way and a Safe Walk home). Proof: better than expected |
| **Meera**, 26, accountant | "I'm worried what others will think" (applying for a team-lead role) | LEAD goal; no SAFE tools; shares the win with a sister |
| **Fatima**, 34, returning to study | "I've never done anything like this" | LEARN + MONEY goals; works offline, no permissions |
| **Kavya**, 22, first solo trip | "…on my own" (self-doubt) + "worried about my safety there" (concern) | The concern opens Check the way and Safe Walk; the doubt is tested; Proof records she did it *with* Safe Walk, a full success |
| **Priya** (threat persona) | A partner checks the phone and knows its PIN | Quiet mode, lock, one-action delete, no notifications, no Home text (§18.3) |
| **Adversaries** | A harasser; a manipulator gaming collective proof; an abuser forcing account deletion; a family member monitoring the phone; a curious operator | §19 |

---

## 12. UX flow

```
Bottom nav (10A): Home · Safety · Nearby · Rise · Profile

Rise
├─ Empty state: "Is there something you'd like to try that feels out of reach?"   [Start]
│     "You decide what's for you. Everything here stays on this phone."
├─ New goal: domain → your words (optional, ≤ 80; "Keep it vague if you like") → suggested steps (edit freely)
├─ Goal: step ladder; each step [Prepare & try] [Not now]
│   Prepare & try
│     1  "How do you think this will go?"  (4 options, or skip)
│        "What's on your mind?"  self-doubts (first person) │ "I'm worried about my safety there or getting there"
│        → if a doubt matches past proof: replay card ("You've felt this before…")
│        → if the safety concern is picked: the SAFE actions move to the top, with
│          "Preparing for real risk is part of doing this well."
│     2  Supports (chips, unranked)  ·  [Check the way]  [Start a Safe Walk]  [Tell someone]   (when relevant)
│     3  [I'm going to try it]  → the step is marked "in progress" (no timer, no notification)
│   How did it go?  better / as expected / harder / didn't go ahead / something happened that wasn't OK
│     → "Was this a first for you?"  → "What helped?" (optional)  → "Add to collective proof?" (anonymous, default off)
│     → Proof card: "That's in your proof."  [Tell someone]  · Next: again / next step / something new / rest for now
├─ Your proof: list + per-goal summaries + "Firsts"
├─ Collective proof [10C]: per domain, bands, or "Not enough reflections yet"
└─ Rise settings: Quiet mode (hide words in lists) · Keep Rise behind my screen lock · Collective proof default ·
                  Delete all Rise data

Home: one calm card "Rise: you decide what's for you" (never a goal; hidden during SOS; hidden in quiet mode)
```

---

## 13. Language rules (enforced by tests)

| Rule | Test |
|---|---|
| No gender words in Rise strings (women, woman, girl, men, man, male, female, lady, ladies, feminine, masculine) | `RiseTextsTest` |
| No opponent framing (them, they say, prove them, show them, haters, critics) | `RiseTextsTest` |
| No risk glorification (brave, fearless, no excuses, push yourself, conquer, level up, streak, challenge accepted) | `RiseTextsTest` |
| No obligation (should, must, have to, need to) aimed at the person | `RiseTextsTest` |
| No safety claims (safe, safer, safest, guaranteed), as on Home | `RiseTextsTest` |
| No template or ranking removes support as progress ("on your own", "without Safe Walk", "alone" never appear in templates; a person may write them) | `StepTemplatesTest` |
| "Didn't go ahead" and "Not now" use neutral, approving words ("That was your call") | `RiseTextsTest` checks the strings exist and are used |
| Support is named as strength at least once, in the support step | copy review |

---

## 14. Android architecture

Same stack: XML Views, Fragments, ViewBinding, MVVM, `AppContainer`, `ApiCallHandler`. **No new dependency.**

```
feature/rise/
├── domain/  Domain, SelfDoubt, Expectation, Outcome, Support, Goal, Step, StepStatus, GoalStatus
│            GoalPolicy       (pure: limits ≤ 20 open goals, ≤ 12 steps, ≤ 80-char words; transitions)
│            ProofPolicy      (pure: per-goal summaries, replay selection for a doubt, honest wording choice)
│            StepTemplates    (static per domain; never support-removing)
│            ReflectionPolicy (pure: what may be sent; never SAFETY_CONCERN or SOMETHING_HAPPENED;
│                              ≤ 1 per domain per ISO week; random delay 6–48 h; drop after 28 d)
│            RiseMessages     (pure: SMS texts; no goal words unless typed)
├── data/    RiseStore + EncryptedFileRiseStore (AES-256-GCM, AAD "SSRISE1|accountId", data key wrapped by the
│            existing LocalKeyWrapper; atomic write + .bak); RiseApi, RiseDtos, RemoteRiseRepository   [10C]
└── ui/      RiseFragment, GoalEditorFragment, GoalFragment, PrepareTrySheet, OutcomeSheet, ProofFragment,
             CollectiveProofFragment [10C], RiseSettingsFragment — ViewModels with factory(container); FLAG_SECURE
```

- **Lock and quiet mode.** `ScreenLockGate` moves from `feature/evidence/ui` to `core/ui` (a pure move, no behaviour
  change). A `RiseLock` follows the `VaultLock` pattern. Quiet mode is a display rule: lists show the domain and "Goal 2"
  instead of her words until she unlocks or taps.
- **The SAFE bridge** (the only touch points):
  - an optional `prefillDestination: String` argument for Safe Walk set-up;
  - the existing `ACTION_SENDTO smsto:` path from 4D;
  - navigation to Nearby's report flow and to SOS.
- **Lifecycle.** No service, receiver, alarm, job or notification in `feature/rise`. Process death loses only an unsaved
  editor.
- **Ownership.** One file per account. Another signed-in account on the same phone sees nothing (AAD mismatch). Logout
  keeps the file, says so, and offers "Delete Rise data too". Account deletion (10A) removes it.

---

## 15. Backend architecture

### 15.1 10A: accounts (unchanged from the first pass)

- **`ActiveAccountCheck`.** One PK lookup per authenticated request confirms the account exists, is enabled and not
  erased, and that the token's `iat` ≥ `credentials_changed_at`. Otherwise 401. It closes the 24 h disabled-token gap
  and makes revocation possible without refresh tokens.
- **Password change.** Requires the current password and returns a fresh token; all other sessions end.
  `AttemptLimiter` allows 5 wrong passwords per 15 min.
- **Account deletion.** Password required.
  - Refused (409) while an emergency is ACTIVE or within 24 h of one ending (the evidence rule).
  - A 7-day grace period with a banner; cancelling needs the password, and signing in does **not** cancel it.
  - `AccountDeletionJob` deletes the evidence ciphertext files first, then the user row (FK cascades), and retries on
    failure, with an orphan sweep.
- **Moderators and admins are anonymised, not deleted.** V13 actor/decider FKs deliberately have no cascade: the audit
  trail survives. Their own safety data is still deleted.

### 15.2 10C: `rise` package

```
rise/  controller/RiseController   POST /api/rise/reflections · GET /api/rise/insights
       service/ReflectionService   closed enums only; accounts ≥ 7 days count; receipt insert-or-ignore; counters
                                   incremented in the same transaction; identical 202 always
       service/InsightService      last 12 complete ISO weeks → InsightPolicy
       InsightPolicy (pure)        minimums, bands, ordering; nothing below a minimum
       config/RiseProperties       min-account-age 7d · min-reflections 20 · min-cell 10 · window 12w ·
                                   retention 52w · max 5 per account per week
```

- **Clean-up** follows the existing scheduled-job pattern: receipts are deleted once their week has ended, counters
  after 52 weeks.
- **Isolation.** `RiseIsolationTest` (backend and Android). `rise` must not reference `emergency`, `journey`,
  `escalation`, `circlealert`, `safetycontact`, `evidence`, `community`, `intelligence`, `moderation` or `evaluation`,
  and none of them may reference `rise`. On Android the only allowed edges are navigation actions.

---

## 16. Database model

**"Do we need to store this?"**

| Field | Where | Keep? | Reason |
|---|---|---|---|
| Goal words, step words | Phone, encrypted | Yes, optional | Without them a goal is meaningless to its owner |
| Destination text of a step | Phone | Yes, optional | Pre-fills Safe Walk; Rise never sends it |
| Domain | Phone; server counters | Yes | Organises goals; aggregates need it; no special category exists in the list |
| Expectation, outcome | Phone; outcome in counters | Yes | They *are* the mechanism. The server needs only the outcome |
| Self-doubt | Phone; counters (optional) | Yes | The replay moment and collective proof need it. Never on a per-user server row |
| Safety concern | Phone only | Yes | Moves the SAFE actions up; **never sent** (it would hint at places and fears) |
| "Something happened" | Phone only | Yes | Her own record; **never sent** |
| Private note after an incident | Phone only | Yes, optional | Her choice; deleted with Rise data |
| Done date | Phone | Date only | An order is needed; the hour is not |
| First-time mark | Phone; counters | Yes | "Firsts" list; a count of firsts per domain is harmless |
| Gender | Nowhere | **No** | Not needed (§2.3) |
| Which insight was viewed | Nowhere | **No** | One all-domains response (§17) |
| Receipt `user_id` | Server, ≤ 8 days | Yes | The only way to enforce one reflection per domain per week. Disclosed; hashing would be theatre because the server holds the key |
| Exact time of a reflection | Nowhere | **No** | ISO week only, against correlation with Safe Walk (§18.2) |

**V16 (10A) `V16__account_lifecycle.sql`** (the plan below; **as built** it also has `deletion_requested_at` and four
more CHECKs, `PHASE10.md` §4 D7):
```sql
ALTER TABLE users
    ADD COLUMN credentials_changed_at DATETIME(6) NULL,
    ADD COLUMN deletion_due_at        DATETIME(6) NULL,
    ADD COLUMN erased_at              DATETIME(6) NULL,
    ADD CONSTRAINT ck_users_erased_disabled    CHECK (erased_at IS NULL OR enabled = FALSE),
    ADD CONSTRAINT ck_users_erased_no_deletion CHECK (erased_at IS NULL OR deletion_due_at IS NULL);
CREATE INDEX idx_users_deletion_due ON users (deletion_due_at);
```

**V17 (10C) `V17__rise_collective_proof.sql`.** It follows the V15 pattern: a closed metric list, a constrained
dimension, binary collation, Monday weeks, and no person.
```sql
CREATE TABLE rise_counters (
    week_start  DATE        NOT NULL,                         -- ISO Monday, UTC
    domain      VARCHAR(16) NOT NULL COLLATE utf8mb4_bin,
    metric      VARCHAR(16) NOT NULL COLLATE utf8mb4_bin,     -- REFLECTED | OUTCOME | DOUBT_OUTCOME | HELPED | FIRST
    dimension   VARCHAR(48) NOT NULL COLLATE utf8mb4_bin,     -- '-' | 'BETTER' | 'ON_MY_OWN.BETTER' | 'PRACTISED_FIRST'
    occurrences INT         NOT NULL,
    PRIMARY KEY (week_start, domain, metric, dimension),
    CONSTRAINT ck_rc_monday    CHECK (DAYOFWEEK(week_start) = 2),
    CONSTRAINT ck_rc_domain    CHECK (domain IN ('GO','LEARN','WORK','LEAD','MOVE','MONEY','DECIDE','BE_ME','OTHER')),
    CONSTRAINT ck_rc_metric    CHECK (metric IN ('REFLECTED','OUTCOME','DOUBT_OUTCOME','HELPED','FIRST')),
    CONSTRAINT ck_rc_dimension CHECK (REGEXP_LIKE(dimension, '^(-|[A-Z_]+([.][A-Z_]+)?)$', 'c')),
    CONSTRAINT ck_rc_nonneg    CHECK (occurrences >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE rise_receipts (
    user_id    BIGINT      NOT NULL,
    week_start DATE        NOT NULL,
    domain     VARCHAR(16) NOT NULL COLLATE utf8mb4_bin,
    PRIMARY KEY (user_id, week_start, domain),
    CONSTRAINT fk_rr_user   FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_rr_monday CHECK (DAYOFWEEK(week_start) = 2),
    CONSTRAINT ck_rr_domain CHECK (domain IN ('GO','LEARN','WORK','LEAD','MOVE','MONEY','DECIDE','BE_ME','OTHER'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
```

- The dimension values themselves (outcome, doubt and support codes) are validated by service enums. The regex CHECK
  makes free text or a cell unstorable, as in V15.
- Both migrations need a MySQL dry run with every CHECK violated once (H2 does not run them).

---

## 17. API design

**10A** (the plan; **as built**: wrong passwords answer 409, password change is refused during an SOS or Safe Walk,
cancel is `POST /api/users/me/deletion/cancel` → 204: `docs/API.md` "Phase 10A endpoints", `PHASE10.md` §4)

| Method | Path | Body → answer | Errors |
|---|---|---|---|
| PUT | `/api/users/me/password` | `{currentPassword, newPassword}` → 200 `{accessToken, user}` | 400, 401, 429 |
| POST | `/api/users/me/deletion` | `{password}` → 202 `{deletionDueAt}` | 401, 409 (ACTIVE or < 24 h), 429 |
| DELETE | `/api/users/me/deletion` | `{password}` → 204 | 401, 404, 429 |
| GET | `/api/users/me` | adds `deletionDueAt` | — |

**10C**

| Method | Path | Body → answer |
|---|---|---|
| POST | `/api/rise/reflections` | `{domain, outcome BETTER\|AS_EXPECTED\|HARDER\|DID_NOT_GO_AHEAD, selfDoubt?, helped[] ≤ 6, first}` → **202 `{status:"RECEIVED"}`, identical** whether counted, duplicate, probation or over the cap. Unknown enum → 400. `SAFETY_CONCERN` / `SOMETHING_HAPPENED` do not exist in the API at all |
| GET | `/api/rise/insights` | **All domains in one response**: `{windowWeeks:12, domains:[{domain, status OK\|NOT_ENOUGH_DATA, reflections DOZENS\|HUNDREDS\|THOUSANDS\|null, outcomes:[{outcome, share}], doubts:[{selfDoubt, betterThanExpected share}], helped:[{support, share}], firsts DOZENS\|…\|null}]}`. Identical for every caller; cacheable 1 h |

- **Bands:**
  - reflections: 20–99 DOZENS, 100–999 HUNDREDS, ≥ 1000 THOUSANDS;
  - shares: ≥ 50 % MOST_OFTEN, 25–49 % OFTEN, 10–24 % SOMETIMES, < 10 % omitted.
- **Minimum per shown cell: 10.** Exact numbers are never sent.
- **Outcomes always include HARDER and DID_NOT_GO_AHEAD** when above the minimum, so the picture is never only good news.

---

## 18. Privacy model

### 18.1 Invariants (each is tested, §26)

| ID | Invariant |
|---|---|
| P1 | No Rise request contains free text, a date, a coordinate, a cell, an expectation, a safety concern or an incident |
| P2 | No Rise response is specific to the caller or to any other person |
| P3 | The only Rise server data tied to a user is receipts, deleted after their week |
| P4 | No reflection is sent less than 6 h after the step |
| P5 | Rise and SAFE do not read each other's data (source scans, both sides) |
| P6 | `feature/rise` has no notification, service, receiver, alarm or job |
| P7 | Another account on the same phone cannot read Rise (AAD mismatch → empty) |
| P8 | Below a minimum, the output is indistinguishable from no data |
| P9 | In quiet mode no goal or step words appear in any list, on Home or in the recents preview |

### 18.2 Cross-feature correlation

- **The risk.** The server knows when a Safe Walk ended (and, for EMERGENCY walks, where). A GO reflection arriving
  minutes later would tie that walk to a Rise goal.
- **The defences:**
  1. a random 6–48 h delay on the phone;
  2. the server keeps the ISO week only;
  3. receipts are deleted after that week;
  4. Rise never tells Safe Walk anything (one string argument).
- **What remains.** The server sees a request arrive but does not store when.

### 18.3 Coercive control (the main threat model)

| Situation | Design response |
|---|---|
| Someone checks the phone | Quiet mode hides her words everywhere; optional screen-lock gate; FLAG_SECURE blocks the recents preview and screenshots; no Home goal text; no notifications |
| Someone knows the PIN | Stated honestly: such a person can open Rise. "Delete all Rise data" is one confirmed action, and quiet mode keeps casual looks uninformative |
| Someone sees the app itself | SafeSphere's outer identity is **a safety app**, which families usually accept. Rise sits inside it and is quiet by default. This is a deliberate side effect, stated without making it a "disguise" feature (a fake calculator app would be a different, harder design that needs expert input) |
| Someone installs SafeSphere *to monitor her* | **Impossible by design**: no tracking on demand, no family or parent mode, no remote view. It stays that way (decision log, §28) |
| The Circle includes a controlling person | Sharing is never default and never suggested twice in a row; the SMS composer shows exactly what is sent |
| Goals linked to emergencies | Isolation: an emergency, its context packet and Safety Circle messages never mention Rise; Rise never reads emergencies |
| Shared device, several accounts | Per-account file + AAD; the signed-in check |
| A "first" or an incident note discovered | Phone-only, encrypted, quiet mode, one-action delete |

### 18.4 AI/ML: still none

| Possible "AI" | Why not |
|---|---|
| Next-step recommendations | Her choice, plus templates; a model would need history we must not keep, and would push |
| Classifying doubts or goals from text | One tap does it; the text never leaves the phone |
| LLM coach or encouragement | A third party would see the most sensitive text in the app; it could invent safety advice; it cannot be explained in a viva |
| Predicting "success" | There is no label; and a prediction of failure would be the stereotype, automated |
| Mood or self-efficacy scoring | Not needed; storing feelings on the server is a special-category risk |

The last two are the strongest argument. **A model trained on who succeeds would learn the social bias Rise exists to
work against.**

---

## 19. Security threat model

| # | Threat | Defence | Residual risk |
|---|---|---|---|
| T1 | A coercive partner or family member reads plans | §18.3 | Someone who knows the PIN and opens the app |
| T2 | A stalker learns plans | Plans never leave the phone; no contact surface | — |
| T3 | Reflection ↔ Safe Walk correlation | §18.2 | The operator sees the arrival (not stored) |
| T4 | A Sybil farm inflates "better than expected" to pressure people | Accounts ≥ 7 days, 1 per domain per week, ≤ 5 per week; bands; HARDER and DID_NOT_GO_AHEAD always shown; safety concerns excluded; never phrased as a recommendation | Patient farms can shift bands (measured, §27) |
| T5 | Harassment, sexual harassment, unwanted contact | No user-to-user channel or shared text | — |
| T6 | Screenshots, recents | FLAG_SECURE | Another camera |
| T7 | Backup or device-transfer extraction | `dataExtractionRules` exclude all; Keystore-wrapped encryption | Rooted, unlocked phone |
| T8 | Notification or lock-screen leakage | None exist (P6) | — |
| T9 | Forced account deletion to destroy evidence | Password, ACTIVE/24 h refusal, 7-day grace, banner | An abuser with the password and patience |
| T10 | Stolen token after a password change | `credentials_changed_at` check | Until the change |
| T11 | A deleted or disabled account's token | Per-request check | — |
| T12 | Inference from the insight viewed | One all-domains response | — |
| T13 | Harmful or stereotyping copy | §13 tests + review | Human review of new copy |
| T14 | Gamified risk | No streaks, points, badges, nudges; support never ranked | — |
| T15 | Aggregate re-identification | System-wide, weekly, minimum 20 and 10 per cell, bands | Few users ⇒ NOT_ENOUGH_DATA (correct) |
| T16 | Server breach | Receipts ≤ 8 days (user + domain + week); counters hold no person | "Account X reflected on LEARN this week" |
| T17 | Brute force on password change or deletion | `AttemptLimiter` | In memory, per instance |
| T18 | Operator or admin misuse | No Rise admin view; audit kept on erasure | DB administrator |
| T19 | A self-doubt code reveals a home situation ("what others will think") | Only in counters, never with a user row; optional | — |
| T20 | Collective proof read as a promise ("it'll be fine") | Wording: "how it went for others", never "it will"; harder outcomes shown | Readers may still over-read it |

---

## 20. Abuse and misuse prevention

1. **Remove the surface.** No contact, text, identity or audience leaves nothing for social abuse to act on.
2. **Benign by construction.** Every support is reasonable, every outcome is honest, and safety concerns and incidents
   are excluded. Gaming the counts can only reorder harmless information.
3. **Silence by default.** Collective proof and Circle sharing are opt-in.
4. **No pressure.** No nudges, no "you haven't tried in a while", no comparisons, no "bigger next step".
5. **Respect for stopping.** "Didn't go ahead" and "Rest for now" are first-class outcomes in the record and in
   collective proof.

---

## 21. Moderation requirements

- **None by construction.** Nothing a person writes reaches anyone else.
- **Copy is reviewed instead.** The product's own words go through code review and `RiseTextsTest`.
- **Any future shared text needs a gate.** All of these must hold first:
  1. ≥ 3 active moderators (9B counters);
  2. pre-moderation;
  3. a written privacy review;
  4. the user's decision.

  Expected result for a student deployment: never met, and that is fine.

---

## 22. Accessibility

- Each step, proof entry and replay card is one TalkBack sentence. The replay is read in full ("You've felt this 4 times
  before…").
- Expectation and outcome are labelled buttons, never emoji or colour alone. Status is word + icon (design system).
- Touch targets ≥ 48 dp; checked at 320 dp × 2.0 font.
- Plain, short, idiom-free sentences, which also translate well later (W14).
- `Motion` respects the system animation setting. No confetti, no celebratory animation.
- 10D includes a TalkBack walkthrough on a real phone.

---

## 23. Offline behaviour

| Function | Offline |
|---|---|
| Goals, steps, Prepare & try, outcomes, proof, replay, quiet mode, lock, delete | Fully offline |
| Check the way | Needs the network (Phase 8) |
| Safe Walk | Its own offline behaviour (4B) |
| Tell someone | SMS; no internet needed |
| Reflections | Queued in the encrypted store; sent after the delay when online; dropped after 28 d |
| Collective proof | Cached ≤ 7 days with "as of <date>" |
| Password change, deletion | Online only (clear message) |

---

## 24. Data retention

| Data | Retention |
|---|---|
| Rise file (phone) | Until she deletes a goal or all Rise data, the account is deleted, or the app is uninstalled. Logout keeps it (stated) |
| Reflection outbox | Until sent, or 28 days |
| Collective-proof cache | 7 days |
| `rise_receipts` | Until their ISO week ends (≤ 8 days) |
| `rise_counters` | 52 weeks (insights read 12) |
| Account deletion grace | 7 days |
| Erased moderator/admin rows | As long as their audit rows (365 days) |

---

## 25. Rate limiting

| Action | Limit | Store |
|---|---|---|
| Reflections | 1 per domain per ISO week (DB key); ≤ 5 counted per account per week; identical 202 beyond | DB receipts |
| Insights | 30 per hour per account | In memory |
| Password change, deletion, cancel | 5 wrong passwords per 15 min → 429 | `AttemptLimiter` |

---

## 26. Testing strategy (planned test names)

### 26.1 Android

| Test | What it checks |
|---|---|
| `GoalPolicyTest` | Limits and transitions; "not now" and "rest for now" change nothing else |
| `ProofPolicyTest` | Replay chosen only for the same self-doubt; honest wording when past outcomes were mostly harder; a safety concern never gets a replay; summaries per goal; no counts headline |
| `StepTemplatesTest` | Every domain has templates; no template contains support-removing or gendered words |
| `ReflectionPolicyTest` | Never `SAFETY_CONCERN` or `SOMETHING_HAPPENED`; ≥ 6 h, ≤ 48 h; ≤ 1 per domain per week; stale dropped; nothing when opted out; body = closed enums only (P1, P4) |
| `EncryptedFileRiseStoreTest` | Round trip; a tampered byte → empty plus a "could not read" state; another account → empty (P7); atomic write with `.bak` |
| `RiseViewModelTest`, `PrepareTryViewModelTest`, `OutcomeViewModelTest` | Full flow; the safety concern moves the SAFE actions up; "something happened" shows the SAFE options and records nothing to send; lock ⇒ nothing rendered before unlock; quiet mode hides words (P9) |
| `RiseTextsTest` | §13 word lists over every Rise string |
| `RiseMessagesTest` | SMS text holds no domain, date or place unless typed |
| `RiseIsolationTest` | Rise ↔ SAFE scans; no notification APIs in Rise (P5, P6) |
| `SafeWalkPrefillTest` | The argument only fills the field |
| 10A tests | `OnboardingTextsTest`, `SettingsHonestyTest`, `AccountViewModelTest` |

### 26.2 Backend

| Test | What it checks |
|---|---|
| `AccountLifecycleApiIntegrationTest` | Revocation; disabled token 401; deletion 409 while ACTIVE / < 24 h; grace period; job deletes rows and files; retry; moderator anonymised with audit kept; other user unaffected; 429 |
| `RiseReflectionApiIntegrationTest` | Identical 202 in every case; unknown enum 400; no safety/incident value accepted; receipts purged; insights identical for two users (P2); minimums (P8); band boundaries |
| `InsightPolicyTest` (pure) | Bands, minimums; HARDER / DID_NOT_GO_AHEAD always present above the minimum |
| `RiseIsolationTest` (backend) | Both directions |
| `TimeConventionsTest` | Extended to Rise week arithmetic (surefire runs in Asia/Kolkata) |
| MySQL dry runs | V16 and V17: every CHECK violated once, including a lower-case code against the binary collation |

### 26.3 Manual (10D)

- Real phone: SOS → Safe Walk → Rise with a GO step through Check the way and Safe Walk.
- TalkBack walkthrough.
- Dark mode.
- 320 dp at 2.0 font size.
- Quiet mode and the lock seen from a "second person" point of view.

### 26.4 Small consented check (optional, honest)

- **Who:** 3–5 classmates, each using Rise for 2 weeks on their own phones.
- **Before and after:** a standard self-efficacy questionnaire on paper, for example the 10-item General Self-Efficacy
  Scale (Schwarzer & Jerusalem *(verify)*). Nothing is kept in the app.
- **How it is reported:** as anecdotal (n < 10, no control group). This is a usability-and-plausibility check, not
  evidence of effect. The participants' Rise data stays on their phones.

---

## 27. Adversarial testing

Phase 9A style: the real API, a virtual clock, SIMULATED reports.

| Scenario | Pass criterion |
|---|---|
| R1 Sybil farm pushes "BETTER" for one doubt every week | Report how many accounts it takes to move one band at 100 and at 1 000 honest reflections. It must be ≥ 10 % of the honest volume, or the minimums are raised |
| R2 Accounts < 7 days old | Zero effect |
| R3 One account, every domain, every week | ≤ 5 counted, ≤ 1 per domain |
| R4 Response oracle | Byte-identical reflection answers in every case |
| R5 Timing | Property test: no send < 6 h after the step; delays uniform enough that a walk's end hour cannot be predicted (KS sanity check on simulated delays) |
| R6 Enumeration | No endpoint takes a user, goal, week or doubt parameter |
| R7 Coerced deletion | During an ACTIVE SOS → 409; 1 h after → 409; > 24 h → grace + banner; cancel with the password |
| R8 Password change during an SOS | The phone swaps in its new token before any other call; the emergency keeps working on this phone (`passwordChange_duringActiveEmergency_keepsTheEmergencyWorkingOnThisPhone`) |
| R9 Safety-value injection | A crafted request with `SAFETY_CONCERN` / `SOMETHING_HAPPENED` → 400; never counted |

---

## 28. What should NOT be built

| Not built | Why |
|---|---|
| Feeds, posts, stories, comments, reactions, followers, public profiles | Popularity and harassment surface; no moderators |
| Streaks, points, badges, levels, leaderboards, "30-day challenges" | Gamifying exposure to perceived risk can push people into danger |
| "Next level = less support" ladders | Ranks support as weakness (the first pass's mistake) |
| Gender field or verification | Not needed; identity data; exclusion; ineffective |
| Stereotype content: quotes, "myth-busting" cards, "women in X" statistics in the UI, "prove them wrong" | Repeats the stereotype; frames an opponent; drifts into gender-war tone |
| Motivational quote or affirmation feeds | Generic persuasion is the weakest source of self-belief; engagement bait |
| Sharing proof to social media (image cards) | Exposure, popularity mechanics, and a goal revealed to the wrong people |
| Family, partner or parent monitoring; location on demand; a "guardian" dashboard | **Permanent no.** It would turn a safety tool into a control tool |
| Mentorship board, job board, events with strangers | LinkedIn by the back door; physical-safety risk |
| Aggregates by city or area; Safe Walk statistics | Re-identification; a predator's map |
| AI coach, LLM, success prediction, mood scoring | §18.4 |
| Rise reminders or notifications | Pressure; lock-screen leakage |
| Server sync of goals (in Phase 10) | Needs zero-knowledge encryption first (§32) |
| A disguised "calculator" app mode | Needs expert design with domestic-violence organisations; a half-done disguise is worse than none |

---

## 29. Risks and limitations

1. **The belief-change effect is plausible, not proven.** It rests on established theory; SafeSphere's own effect is
   untested and §26.4 is anecdotal. Say so in the viva.
2. **Collective proof will show NOT_ENOUGH_DATA in any real demo.** The demo uses the simulator, labelled SIMULATED.
   10C has a **stop rule**: cut it if 10B's check shows nobody would share.
3. **A replay could discourage.** If her past steps were mostly harder, the honest replay is not cheerful. It is shown
   plainly, with "you did it" for completed steps and nothing for the rest. Silence is better than spin.
4. **Self-doubts are a closed list.** Some doubts won't fit. Her own words are possible for the goal and steps, but not
   for doubts: free-text doubts are the most sensitive text in the app, and nothing in Phase 10 needs them.
5. **Someone who knows the PIN can read Rise.** That is true of any app, and it is stated.
6. **Phone loss loses Rise** (no sync in Phase 10). Stated at first use.
7. **The 7-day deletion grace** protects evidence from coerced deletion and delays a real wish to leave. Both directions
   are documented.
8. **The domain list can feel prescriptive.** "Something else" plus her own words is always available.
9. **Tone is a human judgement.** Tests catch forbidden words, not every unhelpful sentence. All Rise copy is reviewed
   before 10B closes.

---

## 30. Implementation phases (each gated by build + tests + the user's check)

| Step | Scope | Gate |
|---|---|---|
| **0** | ~~git init~~ **done (`fbe530b`)**. The user removes or renames the stale `Desktop\CLAUDE.md` | — |
| **10A.1** Honesty, identity and language | Onboarding rewritten around "Safety that expands your world"; Settings rebuilt (only working rows); Safety Center gets Evidence Vault & recovery; Home timeline removed, duplicate tile merged; Profile links instead of Coming soon; tab "Community" → "Nearby"; community summary leads with where you can go; Circle described as "people you choose to keep in the loop"; Safe Walk "support is a choice" line; `feature/map` removed; accurate privacy notice; stale `CLAUDE.md`/`README.md` lines | `OnboardingTextsTest`, `SettingsHonestyTest`; `HomeTextsTest` still green |
| **10A.2** Account security (V16) | `ActiveAccountCheck`, password change, revocation | Lifecycle tests (revocation), R8 |
| **10A.3** Account deletion (V16) | Request/cancel, grace banner, job, evidence files, moderator anonymisation, local wipe | Lifecycle tests, R7, V16 dry run |
| **10B** Rise "Proof" (Android only) | Domains, templates, Prepare & try, outcomes, Your proof, replay, firsts, quiet mode, lock, SAFE bridge, Tell someone, Home card | §26.1; emulator run; copy review |
| **10C** Collective proof (stoppable) | V17, `rise` package, outbox, screen, R1–R6 + R9 | §26.2; V17 dry run; live API check |
| **10D** Verification | §26.3 on the Galaxy A23, the oldest deferred 4A/4B checks, optional §26.4 | `PHASE10.md` in the status vocabulary |

**Size estimate**, measured against existing features:

| Sub-phase | Estimate |
|---|---|
| 10A | ~0.5 × `feature/safetycircle` on Android, plus ~400 backend lines |
| 10B | ~1.2–1.5 × `feature/community` (≈ 1 700–2 200 Kotlin lines) |
| 10C | ~0.7 × `evaluation` (≈ 450–550 Java lines) |

---

## 31. Definition of done

1. 10A–10C implemented and compiled. Both test suites at 0 failures. Lint at 0 errors, with no warning increase without
   a reason.
2. V16 and V17 dry-run on MySQL (every CHECK violated once) and applied.
3. Live API check with real defaults, cleaned up in `try/finally`.
4. Emulator run of every flow. The real-phone and TalkBack checks of 10D are done, or each is explicitly marked **NOT
   VERIFIED** if the user defers it.
5. P1–P9, isolation scans, the §13 language tests and R1–R9 are green or reported with numbers.
6. Security and privacy review written in `PHASE10.md`. `CLAUDE.md`, `API.md`, `DESIGN_SYSTEM.md` (identity lines,
   language rules) and `PLATFORM_CONSTRAINTS.md` updated.
7. Decision-log rows added:
   - revocation by timestamp;
   - the deletion grace period;
   - Rise phone-only;
   - the self-doubt / safety-concern split;
   - no support ranking;
   - never naming stereotypes;
   - **no monitoring of a person by anyone else, permanently**.
8. Viva notes written. Only then is Phase 10 "complete".

---

## 32. Future extension points

| Extension | Precondition |
|---|---|
| **Phase 11 proposal: Circle Response** (contacts answer "seen / on my way" through a capability link in the SOS SMS; it never lowers escalation) | Public HTTPS deployment; own threat model |
| Zero-knowledge sync of Rise data via the Phase 5 recovery key | Design review; the server stores ciphertext only |
| Curated playbooks with partner organisations | Content review; labelled as editorial |
| Pre-moderated "words that helped" | §21 gate |
| Localisation (Hindi + one regional language) | String freeze after 10A; the §13 lists translated and re-tested |
| Data export | Own design for the sensitive file it creates |
| Release hardening (token encryption, minification, HTTPS, Spring Boot 4) | Before any public release |

---

## 33. Final recommendation, and is this better than the first pass?

**Yes, genuinely, and for reasons you can state in one breath:**

| | Mechanism | Belief change | Support | Breadth | Stereotypes |
|---|---|---|---|---|---|
| First pass | A planner | Absent | Implicitly ranked as weakness | Mostly mobility | Not addressed in copy |
| **This pass** | **A self-efficacy engine** | Built in: prediction vs. outcome, plus her own proof replayed at the moment of doubt | Unranked, and explicitly called strength | **Nine life domains** | **Challenged by evidence, never named** |

The safety-concern split is a new safety rule that the first pass lacked. The cost is a few phone-only fields and one
pure policy.

**Recommendation:**
1. **Proceed with 10A, then 10B as designed here.** 10A is needed whatever happens to Rise.
2. **Treat 10C as stoppable.**
3. **Make 10D non-negotiable.**
4. **Leave Phase 9 frozen.**
5. **Record "no monitoring of a person by anyone else" as a permanent product decision.** It is the quiet foundation of
   the whole philosophy: safety the person controls is safety that expands her world.

---

## Appendix: viva notes

- **What is Rise?** A self-efficacy engine. You predict how a step will go, you try it, and you record how it went. Your
  own record answers your doubts later.
- **Why not a community feed?** Text, identity and contact are what harassers need, and there are no moderators.
  Collective proof gives the "people like me did it" effect through anonymous counts.
- **How do you fight stereotypes without a gender war?** We never state them. Doubts are first-person ("not for someone
  like me"), and evidence answers them. Naming a stereotype can strengthen it (stereotype-threat research; the effect
  size is debated).
- **Why separate safety concerns from self-doubts?** Treating "is it safe?" as a confidence problem would teach people
  to ignore real warnings. Concerns lead to preparation; doubts lead to proof.
- **Why no badges or streaks?** Rewarding exposure to perceived risk can push people into danger, and support would
  start to look like weakness.
- **Why no ML?** There are no labels, and a "success predictor" would learn exactly the social bias Rise exists to work
  against.
- **Privacy in one line:** plans stay on the phone, encrypted; the server sees only delayed, closed-list, system-wide
  counts in bands.
- **Who can monitor a user through SafeSphere?** Nobody. That is a permanent design decision.
