# Phase 13E — AI v1: AI-assisted moderation, Tamil / Tanglish: results

**Date:** 2026-10-25 · **Status: implemented, compiled, tested, verified (MySQL dry run + applied, live API with the real
service, emulator API 37).** Plan: `docs/PHASE13E_PLAN.md` (choices E1–E30, decided under the user's delegation).
Design: `docs/EXPERIENCE_PLATFORM_DESIGN.md` §6–§8, §11.4, §11.8, §15 (D2, D3, D5). Builds on 13D (`docs/PHASE13D.md`).

## 1. What was built

- **A moderation service** (`moderation-service/`, Python 3.10+, **standard library only**): the norm-1 normaliser, a
  hashed Tamil / Tanglish / English lexicon (lex-1, 383 entries), evidence rules (target, quoted / reported speech,
  identity + abuse = hate, contact details, links, self-harm flag), the rung-2 linear-model runtime and trainer (no
  model ships), an HTTP server on 127.0.0.1 with a shared key that never logs a text, and offline tools (lexicon build,
  shared vectors, evaluation, benchmark).
- **The backend decides** (`moderation.assessment`): `social` publishes `ContentWritten` inside the write transaction
  (social still never references moderation); `AssessmentService` asks `ModerationServiceClient` (the only caller,
  1500 ms timeout, fail-closed reading), the pure `DecisionPolicy` (policy-1) decides PUBLISH / PUBLISH_COLLAPSED /
  HOLD_FOR_REVIEW, `content_assessments` keeps codes and offsets (no text), holds open a content case at once, the
  author can ask for a review of a collapse, `AssessmentJob` retries, releases a new account's waiting post after 2 h
  and deletes assessments after 90 days. Counters `AI_CHECKED` / `AI_REVIEWED` (9B pattern).
- **The phone** (`core/textcheck`, plain Kotlin): the same normaliser, keys, hashed lexicon (byte-identical asset),
  evidence rules and linear-model runtime; `DraftNudge` gives a private nudge before sharing (Edit / Share anyway), a
  contact-details question, or a support line (Tele-MANAS 14416) — the draft never leaves the phone. Author notices in
  amber ("Our system thinks this may be…", the marked phrase, "Ask a moderator to look"); others see "Collapsed: our
  system thinks this may be hurtful."; the viewer's own "Collapse strong language" switch; the moderator's case screen
  shows why the check acted (rule, findings with phrases, versions, "evidence, not a verdict").

## 2. AI / model decisions (the ladder, honestly)

| Rung | Status in 13E | Why |
|---|---|---|
| 1 Normaliser + phonetic lexicon + rules | **Decides** | Precise, explainable, testable without data; Tamil script, Tanglish and English in one key space design |
| 2 Char n-gram (2–4) hashed (2^18) + logistic regression | **Built, tested, OFF**: trainer + Python and Kotlin runtimes with parity vectors (a toy food/travel model only) | No dataset under D3 exists (user's data or licence-checked public sets with ≥ 2 annotators). Training on developer-written examples would learn the developer's script (the Phase 9 lesson) |
| 3–6 fastText, transformers (MuRIL/XLM-R), distillation, LLM benchmark | **Not built** | Need data first; a transformer also needs a runtime and a GPU; nothing to beat yet |

Gates before a model may act: **L1** a D3 dataset; **L2** beats rung 1 on held-out macro-F1 without more false actions
on banter, counter-speech and identity mentions, calibrated; **L3** the user's decision (`model-mode` SHADOW, then
ACTIVE). Even ACTIVE, a model alone can only collapse, never hold.

## 3. Tamil / Tanglish approach

- **norm-1** (`moderation-service/NORMALISER.md`): invisible characters removed, NFKC per cluster (Tamil vowel signs
  compose and keep offsets), homoglyphs and diacritics folded, ASCII lower case, leetspeak only inside Latin words (not
  `poda!`, not `@mention`, not `2024`), spaced-out letters re-joined, long runs squeezed; every character keeps its raw
  range, so the flagged phrase is shown in the author's own words.
- **Three key spaces** so each word is matched the way it is really written: `en` (fixed spelling), `enc` (elongations
  collapsed), `ta` (Tanglish phonetic key: zh/l, th/t, d/t, oo/u…, and a Tamil-script key: ண/ந → ன, ள/ழ → ல, ற → ர,
  long → short vowels, doubled consonants once).
- **Collisions found and designed out** (each documented in the source): *dei* shared its key with "the", *un* with
  "on", *vaada* with "vada" (food), *koothi* with "kutty", *baadu* with "paadu/paattu" (sing/song), *naaye* with *naai*
  (dog, neutral); *panni* is never listed (it also means "did/doing"), Latin *sunni* is never listed (the Muslim
  tradition), "rape" or "acid attack" alone are never threats (people warn others with them), the n-word is not
  collapsed (it would match the country Niger). An innocent-words test (548 everyday English, Tanglish and Tamil words)
  guards this on both phone and server.
- **Context rules for the harms that matter here:** people describing harassment they faced ("called me a …", quotes,
  "sonnan") are never collapsed or held; identity terms (incl. caste names such as Paraiyar, a celebrated *parai*
  drum) are never evidence alone; mild words (*loosu*, *idiot*) never act; untargeted profanity (*otha*, common banter)
  is only marked as strong language.

## 4. Dataset decisions (D3)

- **No dataset was created or downloaded.** D3 allows only the user's dataset and licence-checked public sets with
  ≥ 2 annotators; none was available, and no licence status was assumed.
- `restricted/dev_set.tsv` (204 items) is a **developer challenge set**: invented texts written by the developer
  (AI-assisted), one annotator, no real person; used only to test the rules (never to train). Part A (130) was used
  while writing the lexicon; part B (74) was written afterwards and measured once **before** any further change.
- `restricted/lexicon.tsv` is a developer seed list that **needs review by ≥ 2 native speakers before any pilot**
  (annotator welfare rules of design §7.11). **The folder is not in git** (only its README; decided by the user before
  the first push, 2026-10-25): tests that need it skip with a reason on a clone without it.

## 5. Results

| Check | Result |
|---|---|
| Python `unittest` | **37 / 37 OK** (normaliser, lexicon, evidence, policy reference, linear model, HTTP server incl. "never logs text") |
| Backend `mvn test` | **725 run, 0 failures, 0 errors, 2 skipped** (13D: 694). New: `DecisionPolicyTest` (7, incl. 41 shared vectors), `ModerationServiceClientTest` (4), `AssessmentIntegrationTest` (13), `AssessmentProbationIntegrationTest` (2), `ModelPlacesTest` (6, replaces the ML check of `EvaluationIsolationTest`) |
| Android `testDebugUnitTest` | **677, 0 failures** (13D: 657). New: `TextCheckParityTest` (5), `DraftNudgeTest` (6), `ModelPlacesTest` (4, replaces `NoMlRuntimeTest` (2)), `AiModerationTest` (7) |
| Android `lintDebug` (fresh `--rerun-tasks`) | **0 errors / 54 warnings** (13D: 54). One new `SetTextI18n` warning from 13E code was found and removed |
| Shared vectors Kotlin = Python | **all pass**: 50 normaliser inputs (text, light text, offsets, tokens, keys, script), 219 evidence vectors, the toy linear model to 1e-12, lexicon asset byte-identical |
| Policy parity Java = Python reference | **41 / 41** shared policy vectors |
| V24 dry run (MySQL 8.0.46, scratch `dry_*` tables built from the V24 text, dropped after) | **59 / 59** negative inserts rejected (every CHECK incl. NULL-source cases, unique, FK), **19 / 19** valid inserts accepted, delete of a post cascades its assessment (→ 0) |
| V24 applied | Flyway "Successfully applied 1 migration … now at version v24" (dev DB **v24**, next **V25**) |
| Live API (real service + backend on MySQL, throwaway `live13e.*`) | **28 / 28 PASS** on the second run; users **170 before = 170 after**. The first run had 6 failures, all in the script (PowerShell 5.1 read the BOM-less script as Windows-1252 and garbled the Tamil test text; one expectation wrongly required rule QUOTED for an untargeted quote) |
| Emulator (AVD `Medium_Phone_API_37.0`) | **E1–E8 PASS** after two findings were fixed (§6) |

### 5.1 Final runs

The backend, Android and lint numbers above are from the final runs after every change (including the two emulator
fixes); the Python suite, the dry run and the live check ran on the same code.

### 5.2 Rule evaluation (developer challenge set; numbers about rules on invented examples, not about a model or real posts)

| | Part A (tuning, n = 130) | **Part B held-out, before fixes (n = 74)** | Part B after fixes (no longer held-out) |
|---|---|---|---|
| Action accuracy | 0.992 | **0.946** | 1.000 |
| Collapse-or-hold precision / recall | 1.000 / 0.982 | **1.000 / 0.879** | 1.000 / 1.000 |
| Hold precision / recall | 1.000 / 1.000 | **1.000 / 0.789** | 1.000 / 1.000 |
| False actions on clean, banter, untargeted profanity, counter-speech, safety warnings, identity mentions | 0 / 57 | **0 / 31** | 0 / 31 |
| Support line recall / false support | 1.0 / 0.0 | **1.0 / 0.0** | 1.0 / 0.0 |

Part B's four held-out misses were general gaps, fixed after measuring: sexual solicitation without "you" ("send nude
pics", "wanna fuck …") is now aimed at the reader; dehumanising comparisons next to a group ("Muslims are dogs") are hate
(as phrases only, so "Christians feed stray dogs" stays clean); a slur next to an insult is hate. One part-A label
(a095) changed while tuning (identity + insult became hate severity 3). Remaining known miss: `f*cking` (masked
letters).

**Obfuscation benchmark** (attack success = an obfuscated copy gets a milder action than the original should; part A /
part B, every collapse/hold item obfuscated 6 ways):

| Attack | Without the normaliser | With norm-1 |
|---|---|---|
| Zero-width characters | 1.00 / 1.00 | **0.02 / 0.00** |
| Leetspeak | 0.91 / 0.94 | **0.09 / 0.06** |
| Cyrillic homoglyphs | 0.91 / 0.94 | **0.02 / 0.00** |
| Repeated vowels | 0.18 / 0.18 | **0.09 / 0.06** |
| Spaced letters (longest word) | 0.68 / 0.49 | **0.23 / 0.24** |
| Mixed | 0.98 / 1.00 | **0.05 / 0.06** |

Spacing stays the weakest (a spaced word next to a one-letter word joins with it; Tamil script is not re-joined).

### 5.3 Performance (this laptop, Python 3.12, measured while the backend test suite was running: pessimistic)

- One check in process: **6.5 ms** for a typical 50-word mixed post; **124 ms** worst case (1000 words, 5540 chars).
- HTTP, one at a time (500 posts): **p50 8.5 ms, p95 22.3 ms, p99 31.0 ms**; 8 parallel clients: **138 checks/s**
  (one process, the GIL). The backend's timeout is 1500 ms.
- Sizes: compiled lexicon **13 KB** (the same file in the APK); no model ships (a rung-2 model of this design is
  ~30 KB for 2 categories on toy data; size grows with the number of non-zero weights).

## 6. Deviations and findings

| # | What | Why |
|---|---|---|
| 13E-D1 | **Privacy finding, fixed:** the author's answer said `source: REPORTS` — telling the author that reports exist (13D M4). Authors now see `SYSTEM` or `MODERATOR` only; a report collapse has no source | Found by the existing 13D `ReportApiIntegrationTest` ("doesNotContain report"); now also asserted in `AssessmentIntegrationTest` |
| 13E-D2 | **Emulator finding, fixed:** list rows (profile, Discover, Cocoon, place/category pages) showed a collapsed post's first line; they now show the collapsed line. Pre-existing since 13D for every kind of collapse | A collapse must stay behind a tap everywhere |
| 13E-D3 | **Emulator finding, fixed:** a case opened by the check or the author (no reports) was headed "A guideline"; it now names the content's reason | Moderators need the "why" first |
| 13E-D4 | No image classifier; instead a new account's Everyone post with photos waits for a moderator (E23) | No data, no model; nothing pretends to look at photos |
| 13E-D5 | The phone shows the support line before sharing; the server's `supportSuggested` is not read by this app version (other clients can) | The phone's own check already offered it, privately |
| 13E-D6 | The strong-language switch lives on the Connections screen (the social hub until 13F), not in Settings | 10A rule: a feature's preferences stay on the feature's screen |
| 13E-D7 | Profiles, display names, place names and report notes are not checked (E26) | Scope; all are reportable (13D) |
| 13E-D8 | Python used for verification: a 3.12.14 interpreter found inside another tool's runtime folder on this PC (no Python on PATH). The service needs no packages; the team should install python.org Python 3.10+ | Disclosed; nothing installed or downloaded |
| 13E-D9 | `-I` cannot start the service (it drops the project folder from the module path); the README uses `-E -s` | Found when starting the live service |
| 13E-D10 | The emulator came back from an old snapshot with the 22 Oct build installed; the current APK was reinstalled before E1–E8 | Disclosed; all E-checks ran on the current build |

## 7. Security and privacy review

- **Nothing about a person leaves the server:** the service receives `{kind, text}` only; it binds to 127.0.0.1, needs
  a 16+ character key (constant-time compare), caps bodies at 32 KiB and never logs a text, header or body (tested;
  the live log contained requests but no word of any post).
- **No text is stored by the check:** `content_assessments` keeps codes, severities, offsets and versions; its V24
  CHECK makes a self-harm code unstorable; rows go with their content and after 90 days.
- **Self-harm** is never stored, counted or shown to moderators; it only adds a support line to the author's own
  answer (server) or a dialog before sharing (phone).
- **Only-me posts are never sent.** Drafts are checked on the phone only (`core/textcheck` has no network, storage or
  logging: `ModelPlacesTest`).
- **The machine never restricts anyone:** no strike, no pause, no removal; holds and collapses are reversible and every
  human decision on them is audited and appealable. Reports and moderators' states are never overridden.
- **Never silenced by an outage:** unavailable / timeout / bad answer = publish (new accounts wait at most 2 h).
- **No shadow ban:** the author always sees the state, the reason and the phrase; others see "collapsed".
- **The lexicon:** the app and git contain only truncated SHA-256 keys; the readable list and the abusive test sentences
  stay on the team's machines (`restricted/` is git-ignored).
- **Residual risks:** quoting abuse or adding "he said" avoids automatic action (by design: counter-speech is protected;
  reports and humans remain); a 64-bit key can be brute-forced against a dictionary (it hides the list from casual
  reading, not from a determined reader); the rules read words, not context or images.

## 8. Limitations

A lexicon is only words: sarcasm, context-dependent abuse, new slang, dialects (Madurai, Kongu, Sri Lankan, Malaysian
Tamil), masked letters (`f*ck`), Tamil spaced letters and text inside images are missed or misread. The developer
challenge set is small, single-annotator and written by the same author as the lexicon: its numbers are an upper bound
for rule behaviour, not generalisation. No real-world false-positive rate is known until moderators' outcomes are
counted (`AI_REVIEWED`) in a pilot. Not verified: real phone, TalkBack speech, dark mode, 320 dp / large fonts.

## 9. Brand: motto proposals (recommendations only; no app text changed)

| Motto | The idea |
|---|---|
| **"Written for the next one who goes."** | The product's rule in six words: every post is for the next person |
| "Some things bloom once in twelve years. Go." | Neelakurinji's rare bloom: experiences worth travelling for |
| "From cocoon to wings." | The Cocoon (Want → Been): an intention that becomes a real visit |
| "Been there. Now you go." | Sharing forward instead of showing off |
| "The hills remember who came." | Kurinji *tinai*, the landscape of the hills, and first-hand presence |
| "Less scrolling. More going." | Real-world discovery against passive feeds |
| "Go, then tell the next one." | The full loop: experience it, share it forward |

**Recommended: "Written for the next one who goes."** It states what makes Kurinji different from a feed (posts exist
to help someone else go), it is true of every feature (tips, Asks, the Cocoon), it works in Tamil too
("அடுத்து போகிறவருக்காக எழுதியது"), and it needs no explanation of the flower. "Some things bloom once in twelve years.
Go." is the strongest launch / campaign line where the Neelakurinji story can be told.

## 10. Viva notes

- **What / why:** the first AI in the product, under D2: evidence → a tested deterministic policy → only humans restrict
  people. Built for Tamil, Tanglish and English as people actually write them.
- **Key classes:** Python `normalise`, `phonetic`, `lexicon`, `detect`, `linear`, `server`; Java `DecisionPolicy`,
  `ModerationServiceClient`, `AssessmentService`, `ContentAssessment`, `AssessmentJob`; Kotlin `TextNormalizer`,
  `PhoneticKey`, `HashedLexicon`, `TextCheck`, `LinearModel`, `DraftNudge`.
- **Flow:** write → `ContentWritten` → service (evidence) → `DecisionPolicy` → state on the post (SYSTEM) + assessment
  row → hold = a case → moderator decision (audited, counted) → appeal as in 13D.
- **Likely questions:** Why no transformer? (no D3 data; a model needs evidence to earn the right to act.) Why rules in
  Java and words in Python? (D2 places; decisions must be tested and explainable.) How do you avoid silencing Tamil
  banter and victims? (severity, targeting, quoted/reported speech, identity-alone rule, collapse not remove, humans
  decide holds.) How do phone and server agree? (one spec, shared vectors, byte-identical lexicon.) What if the service
  is down? (publish; new accounts wait ≤ 2 h.) What did testing catch? (the REPORTS leak, list previews of collapsed
  posts, English/Tanglish key collisions.)
