# Experience platform: product and technical design

**Date:** 2026-10-22 · **Type:** design only. **Status of everything below: planned.** No product code, migration,
navigation, dependency or test was changed for this document.

**Decision record:** on 2026-10-22 the user decided the **final product direction**: *a social platform for real-world
experiences and exploration, with the existing safety technology as a trust layer underneath.* Safety stops being the
identity, the main screen and the reason to install; the safety system is kept. The user approved the direction in
this document on the same day, and took **all decisions D1–D15** on 2026-10-22 (§15; name: Kurinji, pending
trademark, domain and store checks). Each sub-phase still needs its own implementation plan and the user's approval.

Working name in this document: **"the platform"**. The brand is **Kurinji** (D1, 2026-10-22, pending trademark,
domain and store checks; §14); "SafeSphere" stays in code until a rename is approved.

This document supersedes, for product direction only, `AUDIT_NEXT.md` Part II ("Go further, prepared") and the
"no social layer" verdicts in it. The audit's safety findings and the engineering rules in `CLAUDE.md` still apply.

---

## 0. Contents

1. Why the direction changed, and what it reverses
2. Product
3. Social model
4. Privacy model
5. Safety as a trust layer
6. AI system
7. Tamil / Tanglish abuse-detection model
8. Moderation
9. UI / UX direction
10. Scope: V1, V2, future; what is frozen or retired
11. Technical impact
12. Proposed sub-phases
13. Risks
14. Brand name shortlist
15. Decisions the user still has to make
16. Viva notes
17. Sources

---

## 1. Why the direction changed, and what it reverses

**Problem with the old identity.** A safety app is opened in the rare moment something is wrong (`AUDIT_NEXT.md` C2).
Even a very good one is replaceable and absent from ordinary life.

**New direction.** The everyday reason to open the app is discovering and sharing real-world experiences. Safety is
*why people can explore with more confidence*, not *why they install it*.

**Earlier written decisions this reverses (each needs its replacement design, given in brackets):**

| Earlier decision | Where | Replacement |
|---|---|---|
| Community = anonymous place signals, no text, photos or authors | `PHASE6.md`, CLAUDE.md decision log | Authored experiences with structural location privacy (§4); anonymous signals kept for safety heads-ups (§5) |
| No feed, likes, followers, profiles, chat | `AUDIT_NEXT.md` §9, §12 | A finite Discover, a butterfly that saves an intention, profiles without counts; still no followers and no chat (§3) |
| Rating or reporting individual people rejected | `AUDIT_NEXT.md` §12 | Reporting a person's *behaviour* is required on a social platform; nobody is ever rated (§8) |
| No learned model for anything users see (gates G1–G3) | `PHASE9_DESIGN.md`, CLAUDE.md §2, `NoMlRuntimeTest`, `EvaluationIsolationTest` | Models produce evidence; a deterministic policy decides; humans restrict people (§6). Reversed by decision **D2** (2026-10-22, §15) |
| Women-centred safety identity | CLAUDE.md §1 | General 18–26 audience; "designed with women's realities in mind" proposed as an internal principle (**D4**) |
| Rise "Proof" as a section | `PHASE10_DESIGN.md`, `PHASE11.md` §4 | Retired; private reflection becomes "Only me" experiences and the Cocoon (**D9**) |

**Kept without change:** "Nobody can use the app to monitor another person" (it now also governs social features),
honesty rules (no claim that a place or person is safe), ownership from the JWT only, foreign record → 404, Flyway
discipline, UTC, and every safety guarantee.

**Not built and not proposed:** "physical/shake SOS". `SHAKE` is a reserved trigger the server rejects; background
sensor triggers were rejected in `AUDIT_NEXT.md` §O (always-on service, false alarms). Android's own power-button
Emergency SOS is documented instead.

---

## 2. Product

### 2.1 Definition

> **A social space where people share first-hand experiences of places and activities — written for the next
> person — so others feel inspired and equipped to go experience the world themselves, with quiet safety built in.**

The core idea is that **content is written for the next person, not about the poster.** Instagram's unit is "look at
me"; this platform's unit is "here is what it is really like — go see for yourself". Every mechanic follows from that:
the reaction is an intention to go, the reward is that someone went, comments are perspectives from people who have
been, and the feed ends because its purpose is to send you outside.

### 2.2 Philosophy

**"Experience it. Share it forward. Fear less."**

- *Share it forward*, not *share more*: volume is what engagement farming optimises.
- *Fear less*: practical knowledge from people who went, and a safety net that is there when you go.

Product principles (each enforceable in code or tests, §11.8):

1. Real life is the product; the app is the doorway.
2. Written for the next person.
3. No public numbers: no follower, like, view or butterfly counts.
4. Finite by design: every list has an end.
5. Every notification comes from a person or from your own action, never from an engagement engine.
6. No direct messages.
7. Care, not control: nobody can follow where someone goes.
8. Safety always there, never in the way.

### 2.3 Target users and launch community

- **People aged 18–26:** college students and early-career people. Under-18s excluded at launch (§4.7, **D6**).
- **Launch community: Tamil Nadu, Chennai first** (**D5**). This makes the Tamil/Tanglish model a core requirement, not
  a demo: the launch community writes that way. Many students are new to the city, and they have the most to discover.
- Everyone is welcome. Women are explicitly comfortable here because there are no DMs, no counts and the trust layer.

### 2.4 Why install

1. A friend sends a link to an experience.
2. You are about to go somewhere and want recent, first-hand, practical knowledge, in your own language.
3. You have something worth telling about a place and want readers who care, without performing for an audience.

### 2.5 Why return (none is a hook)

- **Cocoon:** the private list of things you want to experience.
- **Asks** about places you know, which you can answer. Being useful is intrinsically motivating.
- **"Your butterfly landed":** someone went because of your post (V2).
- Your connections' new experiences, in a list that ends.
- **The weekly Edition** for your city, which ends.

Honest target: one to three short sessions a week. Success = experiences saved, been and shared; **never time in app.**

### 2.6 Positioning against Instagram

People will use it **alongside** Instagram for a different job: *Instagram is where you show where you have been;
this is where you find out where to go next and help the next person.*

| | Instagram | The platform |
|---|---|---|
| A post is about | The poster | The experience, for the reader |
| After scrolling you have | Envy, FOMO | A Cocoon list and practical tips |
| The reaction means | "I approve of you" | "I want to experience this" |
| Reward | Likes, followers | People went because of you (private) |
| Who reaches you | Followers, strangers who can DM | Nobody can message you |
| Status | Public counts | None |
| Comments | Compliments, hot takes | Perspectives from people who have been |
| Language | English-centric moderation | Tamil and Tanglish written and moderated natively |
| A session | Never ends | The Edition ends: "Go make your own." |

### 2.7 What the platform will not become

Instagram, TikTok, Facebook, Snapchat, a Reddit clone, a WhatsApp replacement, a dating app, an event or trip
planner, a follower competition, a reels platform, a doom-scrolling app, a generic chatbot, Google Maps or
Tripadvisor (no star ratings of places or businesses), a fear map (no danger scores, no incident feed), a monitoring
tool.

---

## 3. Social model

### 3.1 People

| Element | Design | Reason |
|---|---|---|
| **Profile ("Journal")** | Display name, unique handle, short bio, languages, interests, optional city (never a neighbourhood), pinned experiences. **No counts.** | Identity without status |
| **Connections** | Mutual. Requested by handle, QR code or invite link; the other person accepts. **Your connection list is visible only to you.** No contact-book upload, no "people you may know". | No audience asymmetry; nobody can map someone's friends; minimum data |
| **Followers** | **Not built.** A private "keep up with" was considered and rejected for V1: silently following a person's real-world posts is monitoring. | The no-monitoring rule applies to social features |
| **Presence** | No online status, no "last active", no read receipts, no "seen by" | Nothing that reveals when someone is out |

### 3.2 Content types

| Type | Shape | Version |
|---|---|---|
| **Experience** (core) | What + place + location precision + when-band + time of day + body (short, or a long "Journal" layout) + **"Tip for the next person"** + up to 6 photos + one category + optional interests | V1 |
| **Ask** | A question tied to a place or a category ("Anyone been to X in the evening? How do people get back?"); answered by people who have been there; the asker marks answers "This helped" | V1 |
| **Take** | An opinion that starts from experience ("From my experience…"); replies are "Another perspective"; **no quote-posting** (quote-posts drive dunking) | V2, after moderation is proven (**D8**) |

A blog is not a separate type: it is an Experience with a long body. Fewer types keep "what do I post?" simple.

### 3.3 Conversation ("Perspectives")

- Comment kinds: **Been here too** (shown first, with a small stamp), **Question**, **Reply**. One level of nesting.
- On an Ask: answers, ordered by "This helped" from the asker, then "been there", then time. No public numbers.
- The author decides who may comment: everyone / connections / nobody; optional "approve people I'm not connected
  with first"; can hide any comment on their own post.
- V2: **"My experience differed"**: a Been-here-too perspective that is shown attached to the post. Lived experience
  corrects lived experience; AI does not fact-check (§6.5).

### 3.4 The butterfly 🦋 and the Cocoon

**Meaning: 🦋 = "This makes me want to experience it."** An intention, not approval. Three ideas meet in it:
*butterflies in the stomach* before something new ("fear less"), **metamorphosis** (experiences change you), and the
**butterfly effect** (one small post moves someone else to go).

Mechanics:

1. Tapping 🦋 saves the post to your **Cocoon** as *Want to experience* (one tap, one meaning, no separate "save").
2. When you mark it **Been**, the item "emerges" and you are invited to add your own perspective (Been here too) or
   your own Experience.
3. If you reached it through someone's post, that author privately sees **"Your butterfly landed — someone went
   because of your post"** (V2). No identity of who went.
4. The author sees their own butterfly count privately. **The public sees no count.**
5. Butterflies are private: nobody can list who butterflied what.

### 3.5 Places

- A place = name + category + map cell (7-character geohash, ~150 m) + area cell (6 characters). **No coordinates
  stored or sent** (Phase 6 pattern).
- Created by users; duplicates merged by moderators (`merged_into`). No third-party places API in V1 (cost, keys, and
  the provider would learn what people look up) (**D11**).
- **Place page:** public experiences, Asks, and **"Good to know"** (practical notes, including anonymous heads-ups from
  the existing place-signal pipeline). **No stars, no ratings, no rankings of places.**
- Residential places cannot be created as public places (the composer offers City or None instead).

### 3.6 Categories (closed list, expandable by migration)

Food & cafés · Nature & outdoors · Culture & heritage · Music & nightlife · Study & work spots · Day trips & travel ·
Activities & sport · Hobbies & making · City life · Hidden gems.

### 3.7 Discovery

- **Edition:** a weekly, finite selection for your city: near you, from your connections, Asks you could answer, one
  theme. It ends with an illustrated card: *"That's this week's edition. Go make your own."*
- **From your connections:** chronological, ends when you have seen it.
- **Place pages, category pages, search** by place name or category (not full-text people search in V1).
- **Map:** shows **places**, never people, never "now".
- Ordering is explainable ("Why am I seeing this?"): place relevance, recency band, experience completeness (has place,
  when and tip), Been-here-too perspectives. **Never** engagement prediction.
- Lists load more only on an explicit tap; no auto-loading infinite scroll.

### 3.8 Notifications

V1: in-app **Activity** in the You section only, and only for things people did that concern you (a perspective on
your post, a helpful answer, a connection request, a moderation decision). No push in V1 (no delivery channel exists;
FCM would be a new dependency, **D7**). No "you haven't posted in a while", no "trending near you".

---

## 4. Privacy model

### 4.1 "Who can see this?"

Three choices, each with one plain sentence on the selector:

| Choice | Sentence shown |
|---|---|
| 🌍 **Everyone** | "Anyone signed in can see this. It appears on the place page." |
| 👥 **Connections** | "Only the people you've connected with." |
| 🔒 **Only me** | "A note for yourself." |

- No preselected default on the first post; afterwards the last choice is remembered.
- The publish button repeats the audience ("Share with Connections").
- **Circles** (custom audiences) are V2: every extra level makes "who can see this?" harder to answer.
- Visibility can be narrowed after publishing; widening asks again.

### 4.2 Location privacy (structural, not a setting)

- **Precision per post:** Exact public place · Area (~1 km, 6-character cell) · City · None. Public posts default to
  **Area**.
- **Private places:** home, hostel and work zones are marked **on the phone only** and never uploaded. A post inside one
  drops to City or None automatically, before anything is sent.
- **No coordinates ever leave the phone** for social features: only cells (as in Phase 6).
- Public place pages show experiences, never "who is here" or "who was here today".

### 4.3 Time privacy

- **When-band** ("this week / this month / earlier") plus **time of day** (morning, afternoon, evening, night).
- **No exact public timestamps** on posts, comments or Been marks; the server stores exact times for ordering and
  moderation but returns bands to everyone except the author.
- **No live posting:** there is no "I'm here now" format; the composer encourages "share after you've left".

### 4.4 Photos

- EXIF/GPS stripped **on the phone** before upload, and re-encoded **on the server** (defence in depth).
- Android Photo Picker (no storage permission). Up to 6 per post.
- Guideline: no identifiable photos of other people without their consent, no children; a blur tool is V2.

### 4.5 Blocking, muting, hiding, filters

| Control | Effect |
|---|---|
| **Block** | Two-way invisible: profile, posts, comments, Asks, search, place pages. Silent (the other person is not told). Your block list is private. Removes an existing connection. |
| **Mute** | You stop seeing someone; they are not affected or told. |
| **Hide** | Hide a place or category from your Discover. |
| **Strong-language filter** | Viewer's own choice: collapse posts and comments the system marked as untargeted profanity. |

Blocks are enforceable because viewing requires signing in (no public web pages in V1). A blocked person can still
make another account; honest limitation, mitigated by new-account probation (§8.5).

### 4.6 Reporting privacy

The reported person never learns who reported. Reporters see the status of their own reports.

### 4.7 Age

**18+ at launch.** India's Digital Personal Data Protection Act 2023 treats people under 18 as children whose data
needs verifiable parental consent *(verify)*. Self-declared date of birth at sign-up, with honest wording that it is
self-declared (**D6**).

### 4.8 Separation from safety data

Social features never read emergencies, journeys, locations, circle alerts, evidence or the Safety Circle, and the
safety system never depends on social features (isolation tests, §11.8). **Connections are never trusted people**
automatically.

### 4.9 Retention

| Data | Retention |
|---|---|
| Posts, comments | Until the author deletes them (soft delete → purge job), or account deletion |
| Butterflies, Cocoon | Until removed by their owner |
| AI assessments | Short (proposed 90 days), except when attached to an open case or appeal |
| Reports, moderation audit | As Phase 7 (audit append-only; anonymised accounts survive as tombstones) |
| Media | Deleted with the post |

Account deletion: `AccountEraser` must erase every new table; `AccountEraserCoverageTest` enforces this.

### 4.10 Privacy invariants (each becomes a test)

1. No social API request or response contains coordinates.
2. Every read of a post goes through one visibility definition (§11.2).
3. Public responses carry time bands, not timestamps.
4. A blocked pair never sees each other's content on any path.
5. Connection lists are returned only to their owner.
6. Butterflies are returned only to the person who gave them (and counts only to the author).
7. Uploaded images contain no EXIF location after server processing.
8. Social packages do not reference safety packages, and the reverse.

---

## 5. Safety as a trust layer

**Rule:** safety appears exactly when someone moves **from reading to going**, and stays invisible otherwise.

| Surface | Safety presence |
|---|---|
| Onboarding | No safety pitch. One line in the privacy step: "Trusted people and SOS live in You → Safety; set them up any time." |
| Discover, Edition, experience cards | **None** |
| Place page | **"Good to know"**: practical notes, including anonymous, independently confirmed heads-ups from the existing place-signal pipeline ("dark stretch after 8 pm, confirmed by people nearby"). Never a danger score, never "safe". |
| **"Going there?"** sheet (on any experience or place) | Save to Cocoon · **Let someone know** (Heads-up, see **D10**) · **Walk with check-ins** (Safe Walk) · Check the way. **This is the bridge** from inspiration to going. |
| During an active walk or SOS | The existing safety UI, behaviour unchanged (red, high contrast, `keepScreenOn`); a banner on Discover |
| You → Safety | Trusted people (Safety Circle), SOS set-up and test, Evidence Vault, emergency history |
| System entry points | Quick Settings tile and launcher shortcut for SOS (from the HANDOFF plan); Android's power-button Emergency SOS documented |
| Composer | Text that looks like a safety report ("someone followed me here") offers: **"Post this as an anonymous heads-up instead?"** It then goes to the existing anonymous, delayed place-signal pipeline. A public, authored harassment report invites retaliation. |
| Social harms | No DMs, no presence, silent block, private connection list, author comment controls |

**Boundaries:**

- The Safety Circle stays SMS-based and separate from Connections.
- A posting restriction never touches SOS, Safe Walk, the Safety Circle or the vault (existing Phase 7 decision).
- Social features may not import safety packages (extend `IntelligenceIsolationTest`-style tests).
- The existing emergency screens keep their behaviour; only typography and spacing follow the new design system. Red
  stays reserved for emergency UI.

---

## 6. AI system

### 6.1 Principle

**Models produce evidence → a deterministic, auditable policy decides → only humans restrict people.** This continues
the project's "rules decide what users see" principle: the model's output is an input to a tested policy, never a
verdict on its own.

### 6.2 Architecture

```
COMPOSE (phone)                              SUBMIT (server, authoritative)
 text ─► Normalizer ─► on-device linear        Spring ContentService
         model (Tamil / Tanglish / English;      │
         weights shipped as data, inference      ├─► Moderation service (Python, internal network only)
         in plain Kotlin, no ML runtime)         │     normalizer → script detection
   └─► gentle nudge: "Edit / Post anyway"        │     ├ lexicon + phonetic matcher (spelling variants)
       (the draft never leaves the phone)        │     ├ transformer classifier (fine-tuned, §7)
                                                 │     ├ spam features (velocity, near-duplicate, links)
                                                 │     └ image classifier (nudity / violence)
                                                 ▼
                                Assessment {categories, severity, target, confidence,
                                            flagged spans, model_version}
                                                 ▼
                                DecisionPolicy (pure Java, unit-tested like AreaIntelligencePolicy)
                     PUBLISH · PUBLISH_COLLAPSED · HOLD_FOR_REVIEW   (never a silent rejection)
                                                 ▼
                     moderation case → moderator → appeal → outcome counters → threshold review
```

### 6.3 Phone vs server

| On the phone | On the server |
|---|---|
| Normalizer + char n-gram linear model | **Authoritative** check (a modified app can skip the phone check) |
| Instant, private nudge while writing; drafts never uploaded | Transformer model (a MuRIL-base-sized model has ~240M parameters: too large for the phone) |
| EXIF stripping; private-places check | Image classification; spam and velocity signals; report aggregation |

- The Python service is reached only from the backend, over the internal network, with a timeout.
- **If the service is unavailable:** the post publishes and is assessed when the service returns; accounts in
  new-account probation wait for the assessment instead. Infrastructure failure must never silence people.
- Every assessment records `model_version`; thresholds live in configuration and are changed only with a recorded
  reason.

### 6.4 What the AI detects

Abuse and harassment (including sexual harassment), hate (including caste), threats, self-harm signals (routed to
supportive resources, not punishment), doxxing (someone's location or identity), spam, nudity and graphic violence in
images. Categories are defined in §7.2 and shared by the text model and the policy.

### 6.5 "Experience quality": helper, not judge

| Question | Approach |
|---|---|
| Is it spam? | Yes, detected (rules + features). |
| Is it abusive or harmful? | Yes, detected (§7). |
| Is it meaningful / relevant? | **Not a gate.** A deterministic composer checklist prompts for place, when and tip. Experience-rich posts rank higher in discovery, with "Why am I seeing this?". |
| Is it misleading? | **AI does not fact-check.** People who have been add "My experience differed" perspectives (V2). |
| What is useful in it? | V2: AI **extraction** turns tips into chips on place pages ("quiet afternoons", "steep road after 7 pm"). |

### 6.6 Measuring the AI in production

Moderator outcomes (upheld / overturned holds, report outcomes) are counted with the existing Phase 9B pattern
(`OutcomeRecorder`, weekly, system-wide, no content stored), giving live precision of each automatic action. The rule
"nothing that decides what people see reads a counter" stays: counters inform the people who tune thresholds, not the
policy at run time.

### 6.7 Explaining decisions to users

- A nudge or hold names the category in plain words and highlights the flagged phrase.
- Wording: "Our system thinks this may be…", never "you are…". No raw confidence numbers.
- One tap to appeal; appeals go to a human.

---

## 7. Tamil / Tanglish abuse-detection model

This is proposed as the project's **research contribution**, potentially suitable for the DravidianLangTech workshop,
which has run Tamil abusive-comment shared tasks.

### 7.1 Contribution

1. A dataset with an **action-oriented label scheme**: severity, target, banter, counter-speech, and caste.
2. A **Tanglish normalisation and anti-evasion** pipeline.
3. An **obfuscation-robustness benchmark**.
4. A **two-stage phone/server architecture**, with distillation as the path to a stronger phone model.
5. Decisions that can be **explained** to users (flagged spans, category in plain words).

### 7.2 Dataset structure (one item)

```
id
text_raw                      original, untouched
parent_text                   context (optional) — for abuse that depends on what it replies to
source                        user-provided | public dataset name | pilot (consented)
script                        TAMIL | LATIN | MIXED
annotations[]                 one per annotator, ALL kept (no majority vote at storage):
    annotator_id, categories[], target, severity, is_banter, is_counter_speech, spans[], confidence
adjudicated                   categories[], target, severity (after review of disagreements)
split_group                   author / thread id, so the same author never appears in train and test
dialect_region                optional (e.g. Chennai, Madurai, Kongu, Sri Lankan)
created_at
```

### 7.3 Labels

- **Categories (multi-label):** NONE · PROFANITY (untargeted) · INSULT_BULLYING · SEXUAL_HARASSMENT · HATE
  (identity-based: **caste**, religion, gender, sexuality, region/language) · THREAT_VIOLENCE · SELF_HARM · SPAM ·
  DOXXING.
- **Target:** none / a person in the conversation / a group / self.
- **Severity:** 0 none · 1 mild · 2 moderate · 3 severe.
- **Flags:** `is_banter` (affectionate swearing between friends is common in Tamil and must not be punished),
  `is_counter_speech` (quoting abuse to oppose it).
- Caste is listed explicitly: it is central to abuse in Tamil and under-represented in public label sets.
- An annotation guideline with examples per label is a deliverable before labelling starts.

### 7.4 Tamil and Tanglish handling (preprocessing)

- Unicode NFC normalisation (Tamil vowel signs and combining marks).
- Remove zero-width characters (ZWJ, ZWNJ and others used to evade filters).
- Map look-alike characters (Latin/Cyrillic homoglyphs) and leetspeak (digits or symbols for letters).
- Squeeze repeated characters; re-join letters separated by spaces, dots or hyphens.
- Script detection per token.
- **Keep both views:** the raw text and the normalised text are both model inputs (normalising destroys some signal).
- **Tanglish has no standard spelling** (zh / l / ll for ழ, n / nn, doubled vowels). Two complementary answers:
  (a) a **phonetic key** for lexicon matching, so spelling variants collapse to one key;
  (b) optional Latin → Tamil transliteration (e.g. AI4Bharat IndicXlit) as an additional model input.
- The normaliser is specified once and implemented identically in Kotlin (phone) and Python (server), with a shared
  test-vector file (the same pattern as `GeoHash`/`GeoCell` and SSEV1).

### 7.5 Model ladder (each rung is the baseline for the next)

| # | Model | Role |
|---|---|---|
| 1 | Lexicon + phonetic rules | Precise, explainable; fails on context and over-flags innocent substrings |
| 2 | **Char n-gram TF-IDF + logistic regression / linear SVM** | Strong on noisy code-mixed text; tiny; explainable; **runs on the phone in plain Kotlin** |
| 3 | fastText / char-CNN | Subword robustness |
| 4 | **Fine-tuned transformers:** MuRIL (pretrained with transliterated pairs, a natural fit for Tanglish), XLM-R, IndicBERT v2; mBERT as baseline. Multi-head: categories + severity + target | Server model |
| 5 | Distillation of the best transformer into a small quantised model | Future phone model |
| 6 | Large-LLM zero-/few-shot run | **Benchmark only, not deployment.** Shows whether a small specialised model matches it at a fraction of the cost |

### 7.6 Training

- Class weights or focal loss (abuse is the minority class).
- Multi-task heads; k-fold cross-validation (small data).
- **Calibration** (temperature scaling): thresholds drive actions, so probabilities must mean what they say.
- **Per-action thresholds** chosen on validation data for a target precision. Holding content automatically requires
  very high precision on severe classes; these are **targets, not results**.
- Reproducible runs: fixed seeds, recorded data versions, model cards.

### 7.7 Evaluation

- Macro-F1, per-class precision / recall / F1, PR-AUC for severe classes, confusion matrix, calibration error (ECE).
- **Slices:** Tamil script vs Tanglish vs mixed · obfuscated vs clean · context-dependent abuse · **false-positive rate
  on banter and counter-speech** · **identity-term bias** (non-abusive mentions of a caste, religion or sexuality) ·
  dialect region where available.
- **Robustness:** attack success rate under the obfuscations in §7.4.
- **Cost:** phone latency and size; server latency.
- **Honest comparison** with published DravidianLangTech results: one 2022 participant reported macro-F1 ≈ 0.43 on the
  fine-grained Tamil task; a 2026 binary system reported ≈ 0.83 on validation. Fine-grained Tamil abuse detection is
  genuinely hard; the evaluation must report what was measured, not what was hoped for.
- **In production:** moderator outcomes (§6.6) measure real precision.

### 7.8 Data sources

- The user's own dataset (labelled under §7.3).
- Public datasets (DravidianLangTech Tamil and Tamil-English abusive/offensive comment sets, HASOC-Dravidian):
  **check each licence before use** (**D3**).
- Pilot data only with consent, after the pilot exists.
- **Domain shift warning:** public data are YouTube comments, not experience posts; in-domain data from the pilot are
  needed before trusting production thresholds.

### 7.9 Deployment

- **V1:** linear model on the phone (no ML runtime: weights as data) and on the server; transformer on the server via
  ONNX Runtime in the Python service.
- **Future:** distilled phone model (would need an approved on-device runtime dependency).

### 7.10 Limitations

Dataset size and label noise; slang drift; sarcasm; text inside images and voice are not covered; Sri Lankan,
Malaysian and Singaporean Tamil differ; a model never sees the full social context a human moderator sees.

### 7.11 Ethics

- **Annotator welfare:** annotators read abuse; rotate, limit exposure, provide support, allow opting out.
- **Consent and licences** for every source; usernames anonymised.
- **The slur lexicon is itself sensitive:** access restricted, never shipped as readable text in the app (hashed
  phonetic keys on the phone).
- **Bias:** over-flagging dialects or reclaimed speech of marginalised communities (including Dalit communities) is a
  real harm; measured with slices (§7.7) and mitigated with the banter / counter-speech labels.
- **No automatic punishment:** the model never restricts a person.
- **Transparency and appeal** for every automatic action; short retention of assessments.

---

## 8. Moderation

### 8.1 Layers (distributed control, so the platform is not the only judge)

```
1 YOU (viewer)    mute · block · hide · strong-language filter
2 AUTHOR          who may comment (everyone / connections / nobody) · approve strangers first ·
                  hide comments · personal word filter
3 SYSTEM          phone nudge → server assessment → DecisionPolicy (§8.2)
4 COMMUNITY       reports (closed reasons) on posts, comments and people; INDEPENDENT reporters
                  (Phase 7 independence rule) → temporary collapse pending review
5 HUMANS          queue ordered by severity × confidence × independent reports → moderator →
                  action with a reason code → audit log
6 APPEAL          one tap → a DIFFERENT moderator (Phase 7) → outcome counted (§6.6)
```

### 8.2 Decision policy (initial; thresholds are configuration)

| Assessment | Action | Author told |
|---|---|---|
| Clean | Publish | — |
| Untargeted profanity | Publish; collapsed for viewers who turned the filter on | — |
| Targeted insult, medium confidence | **Publish collapsed** ("may be hurtful — tap to view") | Yes, with the reason |
| Sexual harassment, threat, hate slur, doxxing — high confidence | **Hold for review** (visible to the author only, marked "waiting for review") | Yes, with the reason and appeal |
| Self-harm signals | Publish or hold depending on content; the author is shown support resources; never punished | Gently |
| Spam | Rate limit + hold | Yes |
| Nudity / graphic violence (image) | Hold | Yes |

- **No silent reach reduction ("shadow ban").** Collapsing is visible and explained.
- Content can be hidden automatically (reversibly); **people are restricted only by a human** (Phase 7 decision kept).

### 8.3 Reports

- Targets: post, comment, person (behaviour). Closed reasons matching §7.3 plus "other" with a short note.
- Independent reports (not linked accounts, Phase 7 `Independence`) can collapse content pending review.
- Report rate limits per account (existing `AttemptLimiter` / community write-limit patterns) against report bombing.

### 8.4 Repeat offenders

- Strikes weighted by category; **only human-confirmed violations count** (an automatic hold a moderator overturns
  never counts).
- Strikes decay (the Phase 7 "halved every 90 days" pattern).
- Graduated steps: warning → comment cooldown → 7-day posting pause → suspension. Each step decided by a human,
  audited, appealable.
- Restrictions never touch safety features.

### 8.5 New-account probation

New accounts: lower posting and comment limits, no links, public photo posts held for the assessment, and their
reports carry less weight until probation ends (existing 24 h community probation pattern, length to be tuned).

### 8.6 Moderators

- Pilot: the project team. Roles from the DB per request; ADMIN only via SQL (Phase 7).
- Moderators see content and case-scoped pseudonyms (Phase 7 HMAC pattern) unless the case needs the handle (e.g. a
  "report person" case), recorded in the audit log.
- Moderator wellbeing: rotation, blurred media by default, limits on severe queues.

### 8.7 Community guidelines (one screen, Tamil and English)

*Tell it true. Be kind to people, honest about places.* Not allowed: harassment, hate (including caste), threats,
sexual content involving others without consent, doxxing, posting where someone else is right now, spam, and
impersonation.

### 8.8 Legal note

A public launch in India brings obligations under the IT Rules 2021 (grievance officer, response timelines)
*(verify)* and the DPDP Act 2023. For the project: a **closed pilot** with the team as moderators (**D12**).

---

## 9. UI / UX direction

### 9.1 Design philosophy: "Field journal, not feed"

Tactile, editorial, warm: postcards and journal pages, not glossy grids. Photos matter; the **tip** is the hero.
No numbers in public view. Every endless pattern has an ending. Playful in motion and words, never childish.

### 9.2 Navigation (four sections)

**Discover · ✚ Share (centre action) · Cocoon · You.** No Safety tab, no Map tab, no notifications tab. Activity lives
in You. Existing secondary screens (toolbar + back) remain the pattern for detail screens.

### 9.3 Discover

- The Edition (§3.7), place pages, search, and a List / Map toggle (the map shows places, never people).
- Ends with the illustrated card "That's this week's edition. Go make your own."

### 9.4 Experience card

- A postmark-style **place stamp**: name · area · "this month" · time-of-day icon.
- Photo(s), headline, two lines of body, a highlighted **"Tip for the next person"** box.
- Actions: **🦋 Want to go · Perspectives · Share · ⋯**. No counts.

### 9.5 Share (composer)

1. Choose Experience / Ask (Take in V2).
2. What happened (photos optional).
3. **Where:** search or create a place; precision chips; private-places notice when it applies.
4. **When:** band + time of day.
5. **Tip for the next person.**
6. Category (and optional interests).
7. **Who can see this:** three large chips with their sentences; a summary line ("Location shown as: Besant Nagar
   area").
8. Publish button repeats the audience ("Share with Connections").

Long text switches to a Journal layout with section breaks. Inline, calm nudges (§6.7).

### 9.6 The butterfly interaction

- Tap: the butterfly lifts and flutters into the Cocoon tab (~600 ms, light haptic).
- Marking **Been**: a short cocoon "emerges" animation, then the invitation to add a perspective.
- TalkBack label: "Want to experience this. Saves to Cocoon." Reduce-motion replaces the flight with a fade.

### 9.7 Profile ("Journal")

Name, handle, bio, languages, interests, optional "Places I know well" (opt-in, city-level only), pinned experiences,
Connect button. No counts, no stats grid.

### 9.8 Perspectives (comments)

Been-here-too first with a stamp; on a Take, the reply prompt is "What's your experience?"; tone nudges before
posting; author controls in the ⋯ menu.

### 9.9 Moderation feedback

**Amber, never red** (red is emergency-only). A calm sentence with the flagged phrase highlighted:
*"This might come across as a personal attack. Edit / Post anyway."* For holds: *"Waiting for a quick review — here's
why. Think we got it wrong? Tell us."*

### 9.10 Visual language

- Background "paper" off-white, near-black "ink"; dark mode "dusk".
- Accents: **monarch amber-orange** and **morpho blue**, combined in an iridescent butterfly gradient. The bright
  orange fails 4.5:1 contrast as text on paper: use it for icons and fills, and a darker shade for text.
- Hand-drawn-feel line icons; no auto-playing video.
- **Red stays reserved for emergency UI.** Status is never shown by colour alone (existing rule).

### 9.11 Typography

- An expressive open-licence serif for headlines (candidates: Fraunces, Instrument Serif).
- A clean grotesk for UI and body (candidates: Plus Jakarta Sans, Inter).
- **A real Tamil typeface** (candidates: Anek Tamil, Noto Sans Tamil) with proper line height.
- Fonts bundled as resources (OFL licences); no network font loading.

### 9.12 Motion and micro-interactions

Butterfly flight, cocoon emergence, a postmark "stamp" on publish, the Edition-end illustration, gentle card lift.
All short (≤ 700 ms), all skippable, all respecting reduce-motion.

### 9.13 Empty states

- *"Nobody has told this place's story yet. Be the first to tell it true."*
- *"Your Cocoon is empty. Butterfly something you'd love to experience."*
- *"No Asks you can answer yet. Mark places you know well and we'll show you questions about them."*

### 9.14 Accessibility and language

TalkBack labels on every action; contrast ≥ 4.5:1; 320 dp and 2.0× font scale; Tamil and English UI (V2, **D5**).
Tamil script tested at large font scales.

### 9.15 What happens to existing screens

Onboarding, Home, Safety, Nearby, Rise and Profile are redesigned or retired. Emergency, Safe Walk, Safety Circle,
Evidence Vault, Account and moderator screens keep their behaviour and move under You → Safety / You → Account, with
the new typography.

---

## 10. Scope

### 10.1 V1 (essential)

| Area | V1 |
|---|---|
| People | Profiles with handles; mutual connections (handle, QR, invite link); block, mute |
| Content | Experience (short and long) and Ask; photos (≤ 6, EXIF stripped) |
| Places | User-created, cell-based; place pages with "Good to know" |
| Intent | 🦋 + Cocoon (Want → Been) |
| Conversation | Perspectives (Been here too, Question, Reply); author controls |
| Privacy | Three visibility levels; location precision; time bands; private places on the phone |
| Discovery | Edition, place and category pages, search by place/category, List/Map of places |
| AI | Normaliser + lexicon + linear model (phone and server); transformer on the server; image classifier |
| Moderation | Reports (post, comment, person), queue, holds, strikes, appeals (extending Phase 7) |
| Safety | Re-homed: You → Safety, "Going there?" sheet, Quick Settings tile + launcher shortcut |
| Research track | Tamil dataset and model work from day one, in parallel |
| Identity | New navigation, design system, onboarding; brand once chosen |

### 10.2 V2

Takes; Circles; Tamil + English UI; "Your butterfly landed"; "My experience differed"; push notifications from people
only (FCM, if approved); viewer strong-language filter extensions; AI tip extraction; image blur tool; moderator tools
for place merging at scale.

### 10.3 Future / experimental

Distilled on-device transformer; voice and image-text moderation; verified organisations/clubs; public web pages for
shared links (needs a link-leak threat model); saving a harassing comment as evidence in the vault; Verified Nearby
Assistance (Phase 12, unchanged as a future proposal); larger-scale infrastructure.

### 10.4 Frozen or retired

| Item | Proposal |
|---|---|
| Rise "Proof" (`feature/rise`) | Retire; its encrypted per-account store pattern may be reused (**D9**) |
| Home dashboard, Safety tab, Nearby hazard-map tab, onboarding safety pitch | Replaced |
| Phase 9 coordination detection, rules-2 shadow | Stay frozen; may later help against report bombing and butterfly rings |
| Pattern memory, region packs, "Check the way" | Kept; "Check the way" moves into the "Going there?" sheet |
| HANDOFF plan items (Ready check, automatic SMS first-run choice, Safe Walk local start, SOS location retention) | Re-prioritised under the new direction (**D10**) |

---

## 11. Technical impact

This is larger than Phases 6–9 together: the safety stack is unaffected, but the social layer is new territory, and
for the first time **accounts are connected to each other**.

### 11.1 New entities (migrations V20 onward; sketch, not final DDL)

| Table | Key columns and rules |
|---|---|
| `profiles` | user FK (cascade), unique `handle` (lower-case, CHECK pattern), display name, bio, languages, interests, city (nullable) |
| `connections` | `user_low`, `user_high` (ordered pair, CHECK low < high, unique), status PENDING/ACCEPTED, `requested_by` |
| `connection_invites` | random token (hash stored), owner, expiry, single use |
| `blocks` | blocker, blocked, unique pair; `mutes` likewise |
| `places` | random public id, name, normalised name, category CHECK, `cell` CHAR(7) + `area_cell` CHAR(6) (Phase 6 CHECKs), `merged_into` |
| `posts` | random public id, author FK, type CHECK, body, tip, visibility CHECK, place FK nullable, precision CHECK, when-band, daypart, status DRAFT/PUBLISHED/COLLAPSED/HELD/REMOVED/DELETED, script/language, comment policy, timestamps (UTC) |
| `post_media` | post FK cascade, storage key, size/type CHECKs, content hash, processed flag |
| `comments` | post FK cascade, author FK, parent (one level), kind CHECK, body, status |
| `butterflies` | user, post, unique pair (private) |
| `cocoon_items` | owner, post or place, state WANT/BEEN, `source_post_id` (for "landed") |
| `reports` | reporter, target type/id, reason CHECK, status, timestamps |
| `ai_assessments` | target type/id, model_version, categories, scores, severity, spans, decision; retention job |
| `strikes` | user, category, weight, decided_by, decayed value |
| `activity_items` | recipient, kind, reference; people-originated only |

Moderation cases are extended to post, comment and person targets. All new user-owned tables are added to
`AccountEraser`.

### 11.2 The one visibility definition

A single `ContentVisibility` (like `SignalVisibility` today) decides every read:

> A viewer may see a post if and only if neither has blocked the other, **and** (it is EVERYONE **or** the viewer is the
> author **or** it is CONNECTIONS and they are connected), **and** it is published or collapsed (the author also sees
> their own held posts).

Every read path uses it: Discover, Edition, place pages, profiles, comments, search, activity, Cocoon (a post you
saved and can no longer see disappears from your Cocoon view). Tests move from two-user to **four-user** cases:
author, connection, stranger, blocked.

### 11.3 APIs (outline)

`/api/profiles/me`, `/api/profiles/{handle}`, `/api/connections` (request, accept, remove, invite),
`/api/blocks`, `/api/mutes`, `/api/places` (search, create), `/api/posts` (create, read, edit, delete,
visibility), `/api/posts/{id}/comments`, `/api/posts/{id}/butterfly` (PUT / DELETE), `/api/cocoon`,
`/api/discover/edition`, `/api/places/{id}/page`, `/api/media`, `/api/reports`, `/api/activity`; moderation endpoints
extended. Random public ids everywhere (no sequential ids). Cursor pagination with explicit "load more". Ownership
from the JWT only; foreign or invisible record → 404.

### 11.4 AI service

A Python service (training scripts + ONNX Runtime inference) on the internal network only; Spring calls it with a
timeout; outbox table + scheduled job for retries (existing `@Scheduled` pattern). Its own tests, model cards and a
shared normaliser test-vector file with the Kotlin implementation.

### 11.5 Android

- New features: discover, compose, post, place, cocoon, profile, connections, activity; moderation screens extended.
- Photo Picker; on-phone image compression and EXIF stripping.
- On-phone normaliser and linear model in plain Kotlin.
- **New dependency:** the image loader **Coil** (**D7**, decided). Paging is written by hand.
- Navigation graph and design system rebuilt; emergency screens kept.

### 11.6 Infrastructure

A social pilot needs a **deployed HTTPS server**, image storage, backups and a privacy policy; today everything runs
locally (**D12**). One MySQL instance with read-time queries is fine for a pilot of thousands, not for a public launch.

### 11.7 Content ownership and lifecycle

Authors own posts and can edit (edit history kept for moderation), narrow visibility, and delete (soft delete → purge).
Post authors control comments on their posts. Comment authors can delete their comments.

### 11.8 Guards to add (tests)

- Visibility: four-user tests on every read path; blocked pairs invisible everywhere.
- Privacy invariants §4.10 (no coordinates, bands not timestamps, private connection lists, private butterflies, EXIF).
- Isolation: social packages ↔ safety packages.
- Language: no public counts, no engagement-bait copy, no "safe" claims (extend `HonestyTextsTest` / `HomeTextsTest`
  patterns).
- `NoMlRuntimeTest` and `EvaluationIsolationTest` (ML part): changed in 13E (D2 approved 2026-10-22), not earlier, and replaced by
  tests that pin where models may run (Python service; plain-Kotlin linear model on the phone).
- Normaliser test vectors shared between Kotlin and Python.
- `AccountEraserCoverageTest` covering every new table.

---

## 12. Proposed sub-phases (each gated by build + tests + the user's check)

Proposed as **Phase 13 — Experience platform** (Phase 12 stays reserved for Verified Nearby Assistance; **D13**).

| Sub-phase | Scope | Migration |
|---|---|---|
| **13A Identity foundation** | Profiles, handles, connections (invites, QR), blocks, mutes; `ContentVisibility` skeleton with four-user tests | V20 |
| **13B Experiences** | Places, posts (Experience + Ask), media with EXIF stripping, comments, author controls, privacy precision and bands | V21 |
| **13C Intent and discovery** | Butterfly, Cocoon, Edition, place and category pages, search, map of places | V22 |
| **13D Moderation v2** | Reports on posts/comments/people, holds, collapse, strikes, appeals, guidelines (extending Phase 7) | V23 |
| **13E AI v1** | Normaliser (Kotlin + Python), lexicon, linear model on phone and server, Python service, DecisionPolicy, image classifier, outcome counters | V24 if needed |
| **13F New UI and navigation** | Design system, Discover · Share · Cocoon · You, onboarding, safety re-homed (You → Safety, "Going there?", Quick Settings tile, shortcut) | — |
| **13R Research track (parallel from day one)** | Annotation guideline, dataset, model ladder, evaluation report, model card | — |

Order: 13A → 13B → 13C; 13D before any pilot; 13E's phone/server pipeline can start once 13B exists; 13F can begin with
the design system in parallel but ships after 13C; 13R runs throughout. Each sub-phase follows CLAUDE.md §3 (plan →
approval → implement → tests → MySQL dry run → emulator → security/privacy review → docs).

---

## 13. Risks

| Risk | Reality | Mitigation |
|---|---|---|
| **Scope** | The biggest risk: a second product on top of the first | Strict V1; the model as a separate research track; Takes deferred |
| Becoming an Instagram clone | High if counts, follows or video creep in | Principles §2.2 enforced by tests |
| Low initial users | Social products die empty | Value when sparse (place pages, Asks); one city/campus; the team writes the first real experiences |
| Moderation load | Free text and photos multiply it | Distributed layers; AI triage; closed pilot |
| AI false positives | Silencing Tamil banter or reclaimed speech | Collapse rather than remove; holds only at high confidence; banter/counter-speech labels; bias slices; appeals |
| AI false negatives | Harassment gets through | Independent reports, author controls, no DMs |
| Tamil dataset limits | Size, label noise, licences, domain shift | Multi-annotator scheme; consented in-domain pilot data; honest metrics |
| Location privacy | Public posts revealing routines = stalking | Precision defaults, time bands, private places, EXIF stripping, no live posting |
| Social-graph bugs | Visibility leaks private posts | One visibility definition; four-user tests on every path |
| Safety vs social conflict | Abusers join social platforms; the original users are the most exposed | Silent blocks, private connection lists, no DMs or presence, connections ≠ trusted people |
| Envy and comparison without likes | Beautiful photos still cause FOMO | Tip-first cards; experience framing; Edition ends |
| Legal | IT Rules, DPDP, minors | 18+, closed pilot, guidelines and grievance path before any public launch |
| Verification debt | Real-phone checks for the safety stack are still largely not verified (HANDOFF §5) | Keep the A23 sprint on the plan; the new identity does not lower the safety bar |

---

## 14. Brand name shortlist

Web searches were done on 2026-10-22 for existing uses. **Not done:** trademark search (IP India; classes 9, 42, 45),
domains, Play Store and App Store availability. No name is "unique" until those are checked.

| Name | Meaning | Status |
|---|---|---|
| **Thinai** (திணை) | Sangam Tamil poetics: each landscape is paired with a human experience/emotion — "place + feeling" | **Strongest candidate pending verification.** Only a Chennai millet-food outlet found. Romanised, it collides with தினை (millet) |
| **Imaginal** | Imaginal cells build the butterfly inside the chrysalis | **Potentially usable;** no notable app found. Sounds like "imaginary" |
| **Rasika** | Sanskrit and Tamil (ரசி, "to savour"): one who savours experience; echoes Razik / Rasik | **Potentially usable, adjacent notable use:** Rasika, a well-known Washington DC restaurant (food = our category); also a female first name |
| Saunter | Thoreau: walking with purpose and wonder | Small apps in the same category (hiking journal, step counter) → crowded-ish |
| Meander | A winding, curious path | Hackathon project, a walking-route app → potentially usable |
| Hither | "Come here" | Small "Hithr" app; "come hither" reads flirtatious (dating) → avoid |
| Yonder | "Out there" | **Existing notable use in this exact category** (outdoor social app, 500k+ users, acquired ~2017) → avoid |
| Sonder | Every stranger has a vivid life | **Existing notable use** (Sonder hospitality; wound down Nov 2025) → risky |
| Ulaa | Tamil: a stroll or procession | **Existing notable use:** Zoho's Ulaa browser; also sounds like "Ullu" → avoid |
| Imago | The adult butterfly | Crowded (photo agency, geoscience software, an AI photo app) |
| Flâneur | One who strolls and observes city life | Crowded (several walking/art apps); hard to spell |
| Kaleido | Shifting perspectives | Crowded (Kaleidos/Penpot, Kaleido AI acquired by Canva) |
| Psyche | Greek "soul" and "butterfly" | Crowded meaning (NASA mission, psychology) |
| Morpho | The iridescent blue butterfly | **Reject:** former IDEMIA biometrics/security brand; DeFi protocol |
| Titli | Hindi: butterfly | A Hindi name for a Tamil-first launch is culturally and politically wrong |
| Vannam | Tamil "colour" (*vannathupoochi*, butterfly) | Not checked |
| **Kurinji** (குறிஞ்சி) — **chosen 2026-10-22 (D1)** | Neelakurinji, the Western Ghats shrub whose mass flowering every ~12 years draws travellers to the hills: an experience worth going for. Also the Sangam *tinai* of the mountains | Searched 2026-10-22: **no notable app or company found**; the word is dominated by the plant; a thin "Kurinji.Com" influencer listing and the unrelated "Kurin" cleaning brand (Philippines). Common in South Indian local business names. The Sangam *kurinji tinai*'s theme is lovers' union: a literary connotation, not a dating one, but worth knowing for the viva. **Trademark, domain and store checks still to do** |
| Monarch, Flutter | Butterfly references | **Reject:** Monarch Money; Google's Flutter framework |

Avoid: names from the Arabic root *r-z-q* (Ar-Razzāq is a divine name), and anything near "rizz" (reads as dating).

**Top 3 proposed:** Thinai, Imaginal, Rasika. **The user chose Kurinji on 2026-10-22 (D1)**, subject to trademark
(IP India), domain and app-store checks.

Sources for the name and model claims are listed in §17.

---

## 15. Decisions the user still has to make

| # | Decision | Recommendation | Blocks |
|---|---|---|---|
| **D1** | Brand name | **DECIDED 2026-10-22: Kurinji**, subject to trademark (IP India), domain and app-store checks; the rename of app id, package and strings is a separate, later step | — |
| **D2** | Formally reverse "no learned model for anything users see" and change `NoMlRuntimeTest` / the ML part of `EvaluationIsolationTest` | **DECIDED 2026-10-22: yes, with the §6.1 rule** (models produce evidence, a tested deterministic policy decides, only humans restrict people). Models may run only in the Python service and as a plain-Kotlin linear model on the phone. `NoMlRuntimeTest` and the ML check in `EvaluationIsolationTest` are replaced in 13E (§11.8), not before | — |
| **D3** | Which datasets may be used (the user's own; public sets after licence checks) and who annotates | **DECIDED 2026-10-22: the user's dataset + public sets (DravidianLangTech, HASOC-Dravidian) only after each licence is checked; ≥ 2 annotators per item, every annotation kept** (§7.2) | — |
| **D4** | Keep "designed with women's realities in mind" as an internal design principle | **DECIDED 2026-10-22: yes, internal only** (drives the no-DM, no-presence, location and silent-block defaults; not used in marketing or onboarding copy) | — |
| **D5** | Tamil Nadu (Chennai) as the launch community; Tamil + English UI in V2 | **DECIDED 2026-10-22: Chennai first; Tamil and Tanglish content (and the abuse model) from V1; app interface English in V1, Tamil + English in V2** | — |
| **D6** | 18+ at launch with self-declared date of birth | **DECIDED 2026-10-22: 18+; sign-up asks for a self-declared date of birth and refuses under-18s** | — |
| **D7** | New dependencies: image loader (Coil or Glide) now; FCM push later | **DECIDED 2026-10-22: Coil** (Kotlin/coroutines, `ImageView.load` with XML Views) as the one new Android dependency for 13B; FCM push stays a V2 decision | V2 (push only) |
| **D8** | Takes (opinions/debates) in V2, not V1 | **DECIDED 2026-10-22: V2**, after moderation and the abuse model are proven on Experience and Ask | — |
| **D9** | Retire Rise "Proof" (`feature/rise`) | **DECIDED 2026-10-22: retire when 13F ships;** reuse its encrypted-storage pattern; existing local Rise data is deleted with a clear notice to the person | — |
| **D10** | Which pending safety items continue (Ready check, automatic SMS first-run choice, Safe Walk local start, Heads-up, SOS location retention, A23 sprint) and when | **DECIDED 2026-10-22: folded into Phase 13:** SOS Quick Settings tile + launcher shortcut in 13F; Safe Walk local start + Heads-up with the "Going there?" sheet (Heads-up needs its own short design first); Ready check + automatic-SMS choice in You → Safety; SOS location retention later; **Galaxy A23 sprint before any pilot** | — |
| **D11** | Places: user-created only (no third-party places API) in V1 | **DECIDED 2026-10-22: user-created only** (name + category + cell; moderators merge duplicates; no Geocoder or OpenStreetMap lookup for places) | — |
| **D12** | Deployment for a closed pilot (HTTPS server, storage, privacy policy) — whether and when | **DECIDED 2026-10-22: yes, before the pilot;** develop locally; before a closed pilot deploy an HTTPS server with image storage, backups and a privacy policy | — |
| **D13** | Phase numbering: "Phase 13 — Experience platform" with sub-phases 13A–13F + 13R | **DECIDED 2026-10-22: Phase 13**; Phase 12 stays reserved for Verified Nearby Assistance | — |
| **D14** | Default visibility behaviour: no preselection on the first post | **DECIDED 2026-10-22: no preselection on the first post;** the last choice is remembered afterwards | — |
| **D15** | Who moderates during the pilot, and moderator wellbeing rules | **DECIDED 2026-10-22: the project team, rotating;** blurred media by default, limits on severe queues, appeals to a different team member (§8.6) | — |

---

## 16. Viva notes

- **What is the product?** A social space for first-hand experiences of places, written for the next person; safety is
  the trust layer underneath.
- **Why not Instagram?** Different job: Instagram shows where you have been; this helps you decide where to go next
  and helps the next person. No counts, no follows, no DMs, a feed that ends, a reaction that means "I want to go".
- **Why keep the safety system?** It is why people can explore with more confidence, and it appears exactly when
  someone moves from reading to going.
- **Why no followers?** Following someone's real-world posts is monitoring; mutual connections and place-based
  discovery give connection without an audience.
- **How is location protected?** Cells, never coordinates; precision chosen per post; private places on the phone;
  time bands; no live posting; EXIF stripped twice.
- **What does the AI decide?** Nothing on its own: models produce evidence, a tested policy decides, humans restrict
  people, and every automatic action is explained and appealable.
- **What is the research contribution?** A Tamil/Tanglish abuse dataset with severity, target, banter, counter-speech
  and caste labels; a normalisation and anti-evasion pipeline; an obfuscation benchmark; a two-stage phone/server
  model, measured honestly against published results.

---

## 17. Sources

- Yonder: [AOL](https://www.aol.com/news/2014-06-11-daily-app-yonder-helps-you-find-treasured-outdoor-experiences.html),
  [Global Dating Insights](https://www.globaldatinginsights.com/news/dating-app-for-outdoor-enthusiasts-luvbyrd-acquires-social-adventure-platform-yonder/)
- Sonder: [Wikipedia](https://en.wikipedia.org/wiki/Sonder_(company)),
  [Nasdaq press release](https://www.nasdaq.com/press-release/sonder-holdings-inc-complete-immediate-wind-down-operations-2025-11-10)
- Zoho Ulaa: [Small Business Trends](https://smallbiztrends.com/2023/05/zoho-launches-new-web-browser-ulaa.html),
  [Storyboard18](https://www.storyboard18.com/digital/zohos-ulaa-browser-tops-apple-app-store-surpasses-google-chrome-82014.htm)
- Hithr: [Gust](https://gust.com/companies/hithrapp/financials); Saunter: [App Store](https://apps.apple.com/app/id6754249126);
  Meander: [Devpost](https://devpost.com/software/meander-nfs3ia)
- Imago: [IMAGO](https://logokit.com/brands/imago-images.com), [Seequent](https://help.seequent.com/Imago/en-GB/Content/intro.htm),
  [App Store](https://apps.apple.com/app/id6757312700)
- Rasika: [OpenTable](https://www.opentable.com/rasika); Thinai Foods: [Swiggy](https://www.swiggy.com/city/chennai/thinai-foods-porur-rest1055196);
  Titli: [Wikipedia](https://en.wikipedia.org/wiki/Titli_(2023_TV_series))
- Flâneur: [App Store](https://apps.apple.com/app/flaneur/id6772163432); Kaleidos: [Penpot (Wikipedia)](https://en.wikipedia.org/wiki/Penpot);
  Morpho: [IDEMIA (Wikipedia)](https://en.wikipedia.org/wiki/IDEMIA)
- Tamil abusive comment detection: [ACL 2022 overview](https://preview.aclanthology.org/setup/2022.dravidianlangtech-1.44),
  [Optimize_Prime 2022](https://physics.paperswithcode.com/paper/optimize-prime-dravidianlangtech-acl2022-1),
  [2023 overview](https://preview.aclanthology.org/setup/2023.dravidianlangtech-1.11),
  [KEC_AI_NLP 2023](https://preview.aclanthology.org/setup/2023.dravidianlangtech-1.43),
  [DravidianLangTech 2026](https://aclanthology.org/2026.dravidianlangtech-1.51/)
- MuRIL: [arXiv 2103.10730](https://arxiv.org/abs/2103.10730v2)
