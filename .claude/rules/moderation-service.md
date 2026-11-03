---
paths:
  - "moderation-service/**"
  - "android/app/src/**/core/textcheck/**"
  - "android/app/src/main/assets/textcheck/**"
  - "backend/src/**/moderation/assessment/**"
---
# Moderation service and the automatic text check (Phase 13E)

Rules: AI produces evidence → the Java `DecisionPolicy` decides → only humans restrict people (D2). The Python service
uses the standard library only, is internal, and never logs text. The phone has byte-identical twins (normaliser,
hashed lexicon, rules) in `core/textcheck`; shared vectors in `moderation-service/data/vectors/`. `restricted/` is
sensitive and git-ignored. Full Android and backend detail: `android-social.md`, `backend.md`; decisions E1–E30 in
`docs/PHASE13E_PLAN.md`.

Moved verbatim from CLAUDE.md §2 and §12 on 2026-11-03:

**13E is in the repository** (V24; `moderation-service/` Python, stdlib only, with `NORMALISER.md`, `restricted/`
(readable lexicon + developer challenge set: sensitive, **git-ignored**, only its README is in git; tests that need it skip); backend `moderation.assessment`
`ModerationServiceClient`, pure `DecisionPolicy`, `AssessmentService`, `ContentAssessment`, `AssessmentJob`;
`social/event/ContentWritten`; Android `core/textcheck` (`TextNormalizer`, `PhoneticKey`, `HashedLexicon`, `TextCheck`,
`LinearModel`, `DraftNudge`, `TextSpans`), `DraftNudgeDialogs`, `ViewerPreferences`): `docs/PHASE13E.md` (decisions
E1–E30 in `PHASE13E_PLAN.md`, deviations 13E-D1–D10).

- **Python (13E):** none on PATH on this PC; verification used the 3.12.14 interpreter inside
  `%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\` (another tool's runtime). Tools and
  tests run with `-I`; the service must start with `-E -s -m kurinji_moderation.server` (`-I` drops its folder from the
  module path). After changing the lexicon or the rules: `tools/build_lexicon.py`, then `tools/make_vectors.py`.

**17G-3 (optional Explore understanding):** `understand.py` + route `/v1/understand` + `data/explore-vocabulary-v1.tsv`; the ONLY Python file
that may use `urllib`/`ssl`, and the only place that names the provider's address or header; key env `IRL_UNDERSTANDING_API_KEY` (read in
`server.py`, never anywhere else). Provider = adapter (Groq default, Anthropic second); key variables are read only by `from_environment()`. The standard-library-only rule stands (no SDK). See the README section and `docs/PHASE17G3.md`.
