# Phase 13E — AI v1 (AI-assisted moderation, Tamil / Tanglish): implementation plan

**Date:** 2026-10-25 · **Status: implemented, compiled, tested and verified 2026-10-25 (results and deviations
13E-D1–D10: `docs/PHASE13E.md`); plan-level choices E1–E30 decided under the user's delegation** (the user
delegated plan-level and technical decisions; core product direction, D1–D15, Q1–Q12, C1–C15 and M1–M20 are
unchanged). Design: `docs/EXPERIENCE_PLATFORM_DESIGN.md` §6 (AI system), §7 (Tamil / Tanglish model), §8.1–§8.5
(layers, decision policy, probation), §9.9 (amber feedback), §11.4 (AI service), §11.8 (guards), §12, §15 D2/D3/D5.
Builds on 13D (`docs/PHASE13D.md`: states NONE / COLLAPSED / HELD / REMOVED, content cases, audit log, strikes,
appeals). Deferred to 13E by 13D-D8: AI holds, the composer nudge, the strong-language filter, the new-account photo
hold.

## 1. Goal and scope

After 13E:

- every Experience, Ask and perspective that someone else can see is **checked on the server** when it is written or
  its text is edited; the check produces **evidence** (categories, severity, target, flagged phrases, versions), a
  **pure, tested `DecisionPolicy`** turns the evidence into **PUBLISH / PUBLISH_COLLAPSED / HOLD_FOR_REVIEW** — never
  a silent rejection, never a restriction of a person;
- a hold puts the content in front of a **human moderator** at once (a 13D content case); a collapse stays visible
  behind a tap and the author can ask a human to look with one tap; strikes and pauses remain **human decisions only**;
- the author is told in amber, in plain words: *"Our system thinks this may be …"*, with the flagged phrase marked;
  others see only "collapsed: it may be hurtful";
- a **Python moderation service** (internal network only, standard library only) holds the normaliser, the
  Tamil / Tanglish / English lexicon matcher and the linear-model runtime; Spring calls it with a short timeout; when it
  is down, **people are not silenced** (posts publish and are checked later; only new accounts on probation wait, at
  most 2 h);
- the **phone** runs the same normaliser and lexicon (hashed keys only, no readable words) and gives a private nudge
  before sharing (*Edit / Share anyway*); **drafts never leave the phone**; a self-harm signal shows a calm support line
  (never a block, never stored);
- untargeted strong language is published and marked, and viewers can switch on **"Collapse strong language"**
  (a setting about themselves, on their phone);
- moderators see **why** the system acted (rule, evidence, versions, flagged phrase); automatic actions and their human
  review outcomes are **counted** with the Phase 9B pattern (weekly, system-wide, no content);
- `NoMlRuntimeTest` and the ML check in `EvaluationIsolationTest` are **replaced** by tests that pin exactly where a
  model may run (the Python service; the plain-Kotlin linear model in `core/textcheck`) (D2, design §11.8).

**Not in 13E:** a learned model that decides anything (E1: no licensed, multi-annotator data yet — the linear model is
built, tested and switched OFF); transformers, fastText, distillation, LLM benchmarks (13R); an image classifier (E23);
checking profile texts, display names and place names (E26, follow-up); near-duplicate spam detection across posts;
masked profanity with `*` wildcards; Tamil UI strings (D5: V2); the 13F redesign. Nothing in the safety stack changes.

## 2. Plan-level choices (decided under the user's delegation)

| # | Choice | Decision | Why |
|---|---|---|---|
| **E1** | The model ladder (design §7.5) | **Rung 1 decides; rung 2 is built but OFF.** Rung 1 = normaliser + phonetic lexicon + deterministic rules (target, quoted/reported speech, identity + insult, contact details, spam phrases). Rung 2 = character n-gram (1–4 on the normalised text, hashed into 2^18 features) + per-category logistic regression: training, evaluation and inference in Python, inference also in plain Kotlin, with parity test vectors — but **no trained model ships** and the policy ignores model scores unless `app.moderation.ai.model-mode=ACTIVE`. Rungs 3–6 are not built | D3 allows only the user's dataset and licence-checked public sets with ≥ 2 annotators; none exists in the repository today. A model trained on examples the developer wrote would learn the developer's test script (the Phase 9 lesson), and its numbers would mean nothing. A transformer (MuRIL ~240M parameters) needs data, a GPU to fine-tune and an ONNX runtime dependency; it is not justified before rung 2 has real data to beat. Rung 1 is precise, explainable and testable now |
| **E2** | Activation gates for a model (like Phase 9 G1–G3) | **L1** a dataset under D3 (licence recorded per source, ≥ 2 annotators, all annotations kept, author-grouped split); **L2** on its held-out split the model beats rung 1 on macro-F1 **and** does not raise the false-positive rate on the banter, counter-speech and identity-mention slices; calibrated (ECE reported); **L3** the user decides (`model-mode` SHADOW first, then ACTIVE) | A model must earn the right to act; weakening or strengthening what people see needs evidence |
| **E3** | Python service | `moderation-service/` at the repository root: **Python ≥ 3.10, standard library only** (`http.server.ThreadingHTTPServer`, `unicodedata`, `hashlib`, `json`, `unittest`); `POST /v1/assess`, `GET /v1/health`; bound to `127.0.0.1` by default; a shared key header (`X-Moderation-Key`, env `MODERATION_SERVICE_KEY`); body ≤ 32 KiB; **never logs text** (only status and latency); returns evidence, never a decision | No pip packages = no supply-chain risk, nothing to download, runs on any team laptop; explainable line by line in a viva; the decision stays in tested Java |
| **E4** | What leaves the server | Spring sends only `{kind, text}` (text = body + tip, or the perspective). **No user id, post id, handle or place** | The service cannot learn who wrote what; a leak of its memory reveals no person |
| **E5** | Where the decision lives | Pure `DecisionPolicy` (Java, `moderation.assessment`), versioned `policy-1`, thresholds in `app.moderation.ai.*` | Design §6.1: evidence → deterministic policy; unit-testable like `AreaIntelligencePolicy` |
| **E6** | How social triggers a check without depending on moderation | `social` publishes its own event record `ContentWritten` (kind, id, author, probation, audience, photos, text changed) through Spring's `ApplicationEventPublisher`, **synchronously inside the write transaction**; `moderation` listens. The event carries a "support suggested" flag back. `social` still never references `moderation` (`SocialIsolationTest` unchanged) | Keeps M2's one-way dependency; the author's answer already reflects the decision (no flash of held content) |
| **E7** | Timeout and failure | Connect 300 ms, whole call 1500 ms (configurable). Unavailable / timeout / malformed answer ⇒ **ordinary accounts: PUBLISH**, assessment `PENDING`, retried by the hourly-or-faster job (backoff 1 → 30 min, gives up after 24 h as `UNAVAILABLE`); **probation accounts: HOLD as "waiting for a check"**, released by the job when checked — or **published unchecked after 2 h at the latest**. Never a refusal | "Infrastructure failure must never silence people; probation accounts may wait" (design §6.3, the brief) — and the wait is bounded |
| **E8** | Representing automatic states | V24 adds `moderation_source` REPORTS / SYSTEM / MODERATOR (null iff NONE) on posts and comments and replaces the 13D state CHECK: REPORTS = COLLAPSED with no reason and no decision; **SYSTEM = COLLAPSED or HELD, reason set, no decision** (never REMOVED); MODERATOR = as 13D. Existing rows are back-filled. The system moves content only between NONE and SYSTEM states: it **never overrides** a report collapse or a moderator's state | An explicit column is clearer than "reason without decision"; a machine can hide reversibly but never remove |
| **E9** | Human review of automatic actions | A **system HOLD** opens (or joins) a 13D content case at once (`content_cases.origin` SYSTEM) and marks the post `in_review` (edit history, Q7). A **system COLLAPSE** opens a case only when the author taps "Ask for a review" (`origin` AUTHOR_REQUEST), answered with the same 202 whether a case already exists or not (M4). Moderators decide with the 13D actions (KEEP restores, REMOVE strikes …) | Holds hide content, so a human must look soon; collapses are visible, so humans look when asked (pilot moderator load) |
| **E10** | The audit log | Automatic actions are **not** audit rows (`moderation_actions.actor_id` is a person); they live in `content_assessments` with model, normaliser and policy versions. Every human decision on system-actioned content is audited as in 13D | The audit log stays "who decided"; the assessment says "what the machine saw" |
| **E11** | Strikes and pauses | Unchanged: only a moderator's REMOVE or person step creates a strike or a pause. An automatic hold that a moderator keeps never counts | Design §8.4; D2 "only humans restrict people" |
| **E12** | Self-harm | **Never stored** (not in the assessment row, not counted, not shown to moderators), **never punishes**, never changes the decision by itself. The author's own write answer carries `supportSuggested: true` once, and the phone shows a calm support line (Tele-MANAS 14416) — the phone also shows it before sharing | Health data is the most sensitive category (DPDP); support, not surveillance |
| **E13** | Only-me posts | Never sent to the service, never assessed | Private notes are nobody's business; narrowing is one-way, so an only-me post cannot later reach anyone |
| **E14** | Edits | Text changes are re-assessed. A SYSTEM state may be lifted (clean now) or changed; REPORTS / MODERATOR states are untouched; an open case keeps revisions (13D Q7) | "Post clean, then edit" is the obvious evasion |
| **E15** | Counter-speech and lived experience | Hits **inside quotation marks** or after a **reported-speech marker** ("called me", "said to me", "shouted", "ennai … nu sonnan", "kitta sonnaanga" …) are evidence for moderators but never act by themselves | People describing harassment they faced must not be silenced — on a platform designed with women's realities in mind, that is the most likely false positive |
| **E16** | Identity terms (caste, religion, region, sexuality) | Never evidence alone. An identity term **plus** an insult / dehumanising word in the same sentence (window of 4 tokens) is HATE | Over-flagging people who name their own community (incl. Dalit communities) is a real harm (design §7.11) |
| **E17** | Banter and severity | Severity 1 (mild: "loosu", "mokka", "idiot") never acts. Untargeted profanity (any severity) publishes with `strongLanguage`. Collapse needs severity ≥ 2 **and** a second-person target; hold needs severity 3 in THREAT / SEXUAL_HARASSMENT / HATE / DOXXING / SPAM (targeted where the category needs a target) | Affectionate swearing is common in Tamil; collapse rather than remove; hold only at the top |
| **E18** | Contact details (doxxing) | Phone numbers / e-mail addresses alone = DOXXING severity 1 (evidence + phone nudge only: shops share numbers); with a possessive third-person phrase ("her number", "avaloda number", "ivan veedu") severity 3 → hold | A business number is legitimate; posting someone's number is not |
| **E19** | Spam | ≥ 3 links, or a link with spam phrases ("earn daily", "whatsapp me", "click here" …) → severity 3 → hold; fewer signals → evidence only | Design §8.2 "rate limit + hold"; probation already limits links and posts (13D M16) |
| **E20** | Strong-language filter | `strongLanguage` on posts and perspectives (set by the check, seen by everyone); a phone setting "Collapse strong language" (DataStore, about the viewer only, default off) shows such content behind "Show it" | Design §8.1 layer 1 ("YOU"); the setting describes the viewer, not anyone else |
| **E21** | The lexicon and its sensitivity | Readable source `moderation-service/restricted/lexicon.tsv` (slurs and their variants) with a warning README, **kept out of git** (user's decision, 2026-10-25); the build tool compiles it to `lexicon-v1.keys` = truncated SHA-256 of `SSLEX1|key` + codes. **Both the service and the phone load only the compiled file**; the phone never contains readable words. If the repository becomes public, `restricted/` moves to private storage (documented) | Design §7.11 ("never shipped as readable text in the app"); one compiled file = the same matches on phone and server |
| **E22** | The normaliser (`norm-1`) | Specified once (`moderation-service/NORMALISER.md`): NFKC → remove invisible characters (ZW*, bidi, tags, variation selectors, soft hyphen, fillers) → Latin diacritics folded → Greek/Cyrillic homoglyphs → lower-case ASCII → leetspeak inside Latin words → re-join ≥ 3 single letters split by one separator → runs of ≥ 3 equal characters squeezed to 2; tokens + script per token; a **phonetic key** per token (Tanglish: zh→l, sh/ch/j/z→s, th/dh/d→t, b→p, g/c/q→k, w→v, vowel classes a / e-i-y / o-u, doubles collapsed, trailing vowels dropped; Tamil script: ண/ந→ன, ள/ழ→ல, ற→ர, long vowel signs → short, doubled consonants collapsed). Implemented in Python and Kotlin with **one shared vector file**; every normalised character keeps its raw offset so flagged phrases point at the author's own words | §7.4; the GeoHash/SSEV1 pattern of shared vectors |
| **E23** | Images | **No image classifier** in 13E (no licensed data, no model, would need a runtime). The deterministic §8.5 rule instead: a probation account's **Everyone post with photos is held for a human** (`NEW_ACCOUNT_PHOTOS`) | Honest: nothing pretends to look at photos |
| **E24** | Explainability | The author: category in plain words + the flagged phrase(s) (code-point offsets in their own text) + what happens next + review button; never a score; "Our system thinks this may be …", never "you are …". Viewers: only "Collapsed: our system thinks it may be hurtful" | §6.7; nobody else learns why |
| **E25** | Retention | `content_assessments` deleted 90 days after the check, longer only while the content has an open case or an open appeal; deleted with its content. **No text stored**: codes, severities, offsets, rounded scores, versions | Design §7.11 "short retention"; minimum data |
| **E26** | What is checked | Experiences, Asks (body + tip) and perspectives. Not: profiles, display names, place names, report notes (follow-up) | Scope; those are reportable (13D) |
| **E27** | Counters (9B) | V24 extends V15's metric list with `AI_CHECKED` (dimension = action, e.g. `HOLD_FOR_REVIEW`; `UNAVAILABLE`) and `AI_REVIEWED` (dimension `HOLD.KEPT` / `HOLD.ACTED` / `COLLAPSE.KEPT` / `COLLAPSE.ACTED`), version = the lexicon version (`lex-1`). Written only through typed `OutcomeRecorder` methods; nothing that decides reads them | Live precision of each automatic action (§6.6) without storing anything about anyone |
| **E28** | Versions | `norm-1`, `lex-1`, `lin-0` (no model), `policy-1` — all match V15's version pattern | Every assessment says which rules produced it |
| **E29** | Where models may run (D2 guards) | Backend: still **no ML library in `pom.xml`**, no model file in `src/main`, only `moderation.assessment` talks to the service (`ModerationServiceClient`). Android: no ML runtime dependency; a model file only under `assets/textcheck/`; `core/textcheck` has no network, storage or logging (drafts never leave). Python: no third-party import | Replaces `NoMlRuntimeTest` / the ML check exactly as D2 says |
| **E30** | Evaluation in 13E | A **developer challenge set** (`restricted/dev_set.tsv`, written by the developer, single annotator — **not a dataset under D3**, never used to train anything that ships): per-category precision/recall of rung 1, false-positive rates on banter / counter-speech / identity mentions, and an **obfuscation benchmark** (each abusive item obfuscated 6 ways: zero-width, leetspeak, homoglyphs, repeats, spacing, mixed) with and without the normaliser. The rung-2 pipeline is demonstrated on a split of the same set and reported **only as a pipeline check** | Honest numbers about rules; no claim about a model |

## 3. Database: migration V24 (`V24__ai_moderation.sql`)

| Change | Columns and constraints |
|---|---|
| `posts`, `comments` | `moderation_source` VARCHAR(9) binary NULL (REPORTS / SYSTEM / MODERATOR), back-filled; `strong_language` BOOLEAN NOT NULL DEFAULT FALSE; the state CHECK replaced (NONE ⇔ no source/reason/decision; REPORTS ⇒ COLLAPSED, no reason/decision; SYSTEM ⇒ COLLAPSED/HELD, reason, no decision; MODERATOR ⇒ reason + decision); `COALESCE` on every nullable column inside `IN` (13D-D1) |
| `content_cases` | `origin` REPORTS / SYSTEM / AUTHOR_REQUEST (default REPORTS, CHECK) |
| `content_assessments` (new) | `public_id` UUID; `target_kind` POST/COMMENT; `post_id`/`comment_id` FK cascade, UNIQUE each (one row per target, replaced on edit; "exactly one" in code); `status` PENDING / DONE / UNAVAILABLE; `attempts` 0–100, `next_attempt_at`; `action` PUBLISH / PUBLISH_COLLAPSED / HOLD_FOR_REVIEW (NULL only while PENDING without a hold); `rule` closed list; `reason` (13D codes) NULL; `evidence` VARCHAR(400) `CATEGORY:severity:T|U:Q|-` list (REGEXP CHECK, no free text); `spans` VARCHAR(200) `start-end` list (digits only); `strong_language`; `lexicon_version`, `model_version`, `policy_version`, `normaliser_version` (V15 pattern); `checked_at`, `created_at`; status/times CHECKs |
| `evaluation_counters` | metric CHECK replaced: + `AI_CHECKED`, `AI_REVIEWED` |

`AccountEraser`: assessments go with the person's posts and perspectives (deleted before them; no user column).
`AccountEraserCoverageTest` keeps checking every `REFERENCES users` (V24 adds none).

## 4. Backend

| Area | Files |
|---|---|
| `social` | `event/ContentWritten` (record + support flag); `PostService.create/edit`, `CommentService.create` publish it (not for only-me posts); `Post`/`Comment` `moderationSource`, `strongLanguage`, `moderateBySystem`; `ModerationView` + `source`, `flagged`; post/perspective answers + `strongLanguage`, `supportSuggested` (author's write answer only), `collapsed` BY_SYSTEM; `Visibility` unchanged (HELD is author-only whoever set it) |
| `moderation.assessment` (new) | `ModerationServiceClient` (java.net.http, timeout, key, fail-closed parsing → `Evidence`), `Evidence` (records; unknown categories dropped), pure `DecisionPolicy`, `AssessmentService` (listener, apply, case for holds, author review request, moderator view), `ContentAssessment` entity + repository, `AssessmentJob` (`@Scheduled`: pending retries, 2 h probation release, 90-day retention), `AssessmentController` (`POST /api/content-checks/review`) |
| `moderation` (13D) | `ContentCase.origin`; case summaries/detail + `origin` + `assessment`; KEEP/REMOVE on a SYSTEM state counted (`AI_REVIEWED`); `ContentModerationService.maintain` unchanged |
| `evaluation` | `Metric` + 2 codes; `OutcomeRecorder.aiChecked(action, version)`, `aiReviewed(action, outcome, version)` |
| `config` | `ModerationAiProperties` (`app.moderation.ai.enabled`, `url`, `key`, `connect-timeout`, `timeout`, `model-mode` OFF/SHADOW/ACTIVE, `probation-wait-max` 2h, `retry-for` 24h, `retention` 90d, `job-interval`) |

## 5. API

| Method and path | Body / result | Errors |
|---|---|---|
| Post / perspective answers | + `strongLanguage` (everyone); author `moderation` + `source` (REPORTS / SYSTEM / MODERATOR), `flagged [{start, end}]` (code points, SYSTEM only), `pendingCheck`; others `collapsed` + `BY_SYSTEM`; the author's create/edit answer + `supportSuggested` | — |
| `POST /api/content-checks/review` | `{targetKind POST\|COMMENT, targetId}` (own content collapsed or held by the system) → 202 `{status: RECEIVED}` always the same | 404 not own / not a system state |
| Moderator case answers | + `origin`; detail + `assessment {action, rule, evidence[{category, severity, targeted, quoted}], flagged[{start,end}], versions}` (never self-harm) | — |
| Internal: Python `POST /v1/assess` | `{kind, text}` + `X-Moderation-Key` → `{versions{normaliser, lexicon, model}, script, evidence[{category, severity, targeted, quoted, start, end, source}], strongLanguage, selfHarm, modelScores{}}` | 401 key, 413 size, 400 shape |

## 6. Android

| Area | Files |
|---|---|
| `core/textcheck` (new, pure Kotlin, no Android types except asset loading in one class) | `TextNormalizer` (norm-1, offsets), `PhoneticKey`, `HashedLexicon` (asset `textcheck/lexicon-v1.keys`), `TextCheck` (hits, target, quoted, contact details, self-harm), `LinearModel` (loader + predict; no model shipped), `DraftNudge` (pure: NONE / CARE(category, spans) / SUPPORT) |
| `feature/social` | Compose + perspective box: nudge dialog (*Edit* / *Share anyway*) and support dialog before sending; DTOs (source, flagged, pendingCheck, strongLanguage, supportSuggested, BY_SYSTEM; unknown source → MODERATOR wording without a phrase, unknown collapse → UNDER_REVIEW); author notices ("Our system thinks this may be …" + flagged phrase marked, "Ask for a review"); viewer collapse "may be hurtful"; strong-language collapse |
| `feature/settings` | "Collapse strong language" switch (DataStore) |
| `feature/moderation` | case screen: "Opened by the automatic check", rule + evidence lines, flagged phrase marked |

Amber for all moderation feedback (never red); no scores; no "you are"; nothing about other people stored on the phone.

## 7. Tests

**Python (`unittest`):** normaliser vectors (shared file), phonetic keys, lexicon compile + match (stems, phrases,
identity rule, quoted / reported speech, target window), contact and spam detectors, linear model (hashing, training
converges on a toy set, save/load, deterministic scores = Kotlin vectors), HTTP server (key, size, shape, no logging of
text), evaluation script runs.

**Backend:** `DecisionPolicyTest` (every rule, probation, only-me, unavailable, model OFF/SHADOW/ACTIVE),
`ModerationServiceClientTest` (stub HTTP server: OK, timeout, 500, bad JSON, unknown codes), `AssessmentIntegrationTest`
(publish / collapse / hold end to end with a stub service, case opened, author view, others' view, edits, never
overrides reports or moderators, unavailable → publish / probation hold → job release at 2 h, review request same 202,
self-harm never stored, only-me never sent, retention), `VisibilityTest` (SYSTEM states), `ContentModerationIntegration`
(KEEP on a system hold restores; counters), `ModelPlacesTest` (replaces the ML check), `SocialIsolationTest`
unchanged + `AssessmentIsolationTest` (only `ModerationServiceClient` calls out; no text logged).

**Android:** `TextNormalizerTest` (shared vectors), `HashedLexiconTest` (shared match vectors), `LinearModelTest`
(parity), `DraftNudgeTest`, `TextCheckIsolationTest` (no network/storage/log in `core/textcheck`), `ModelPlacesTest`
(replaces `NoMlRuntimeTest`), DTO fail-closed tests, texts (`ModerationV2Test` extended: no "you are", no score, amber).

**Verification:** lint (fresh); V24 dry run with negative inserts for every CHECK and unique (scratch `dry_*` tables)
then applied; Python service tests + latency/throughput; live API script with the real Python service (throwaway
accounts, try/finally, users before = after); emulator (API 37) walk-through.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Silencing Tamil banter, dialects or reclaimed speech | Severity 1 never acts; untargeted never collapses; identity terms never alone; quoted/reported speech never acts; collapse not removal; humans decide holds; appeals; slices measured (E30) |
| Silencing people describing harassment | E15 quoted / reported speech; the dev set has a counter-speech slice |
| Infrastructure failure silences people | E7: publish; probation waits ≤ 2 h |
| Evasion (spelling, zero-width, leet, spacing, homoglyphs, edits) | E22 normaliser; obfuscation benchmark; re-check on edit; reports and humans remain |
| A lexicon is only words | Documented; rung 2 ready for real data (E1, E2); reports and moderators cover context |
| Leaking content or authors to the service | E4 no ids; internal bind + key; no logging; E13 only-me never sent |
| Sensitive lexicon published | E21 hashed on phone; restricted folder; documented move |
| Self-harm data misuse | E12 never stored or shown |
| Moderator overload by holds | Holds only at severity 3; collapses reviewed on request; counters show volume |
| MySQL-only DDL | Mandatory dry run (COALESCE lesson) |

## 9. Order of work (each step buildable)

1. Python: normaliser + keys + vectors, lexicon build + matcher, detectors, linear model, server, tests, evaluation.
2. V24 + entities + `ContentWritten` + `moderation.assessment` (client, policy, service, job) → backend tests.
3. Author/viewer/moderator answers, review request, counters → tests; isolation tests replaced.
4. Android `core/textcheck` (+ parity tests), nudge, DTOs, notices, strong-language setting, moderator lines → tests;
   lint.
5. MySQL dry run + apply, live API with the real service, emulator, security/privacy review, docs, commit (no push).
