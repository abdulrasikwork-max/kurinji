# Kurinji moderation service (Phase 13E)

The automatic check of posts and perspectives: **evidence only**. The backend's tested `DecisionPolicy` decides
(publish / publish collapsed / hold for a moderator); only humans restrict people (decision D2). Design and decisions:
`docs/PHASE13E_PLAN.md`, results: `docs/PHASE13E.md`, rules: `NORMALISER.md`.

* **Python 3.10+, standard library only** — no `pip install`, no model runtime, nothing downloaded.
* **Internal only**: binds to `127.0.0.1` by default; every `POST /v1/assess` needs the header `X-Moderation-Key`.
* **Never logs a text**, a header or a body (one line per request: method, path, status, milliseconds).
* Receives `{kind, text}` only: no person, no post id, no place.

```
kurinji_moderation/  normalise.py (norm-1) · phonetic.py (keys) · lexicon.py (lex-1, hashed) · detect.py (evidence)
                     linear.py (rung 2: char n-grams + logistic regression; built, no model ships) · service.py · server.py
                     policy_reference.py (a copy of the Java policy for offline evaluation, pinned by shared vectors)
data/                lexicon-v1.keys (compiled, hashed) · vectors/ (shared test vectors; a TOY linear model for parity)
restricted/          NOT in git (only its README): the readable lexicon, the challenge set, the evidence vectors
tools/               build_lexicon.py · make_vectors.py · evaluate.py · benchmark.py
tests/               unittest
```

## Run

```bash
cd moderation-service
python -I -m unittest discover -s tests
```

```bash
python -I tools/evaluate.py
```

Start the service (choose your own key of 16+ characters, the same for the backend; `-E -s` ignores Python environment variables and the user site, `-I` would also drop this folder from the module path):

```powershell
$env:MODERATION_SERVICE_KEY = "change-me-to-a-long-random-key"; python -E -s -m kurinji_moderation.server --port 8091
```

The backend then needs `MODERATION_SERVICE_KEY` (and optionally `MODERATION_SERVICE_URL`, default
`http://127.0.0.1:8091`) in its environment or `backend/.env`. Without a key the backend publishes unchecked and says so
at start-up; with the service down it publishes and checks later (new accounts wait at most 2 hours).

## Changing the lexicon or the rules

1. edit `restricted/lexicon.tsv` (or the code in both Python and Kotlin);
2. `python -I tools/build_lexicon.py` (also copies the compiled file to the Android assets);
3. `python -I tools/make_vectors.py`;
4. run the Python, backend and Android tests; bump `lex-N` / `norm-N` when meaning changes.

## A learned model (rung 2) — gates before it may act

No model ships in 13E: no dataset under D3 exists yet. `tools/` contains no training entry point on purpose; once a
dataset exists (licence recorded per source, ≥ 2 annotators, every annotation kept, author-grouped split), train with
`kurinji_moderation.linear.train`, save with `to_text()`, pass `--model FILE` to the server, and keep the backend's
`app.moderation.ai.model-mode` at `SHADOW` until the held-out results beat the lexicon without raising false positives
on banter, counter-speech and identity mentions (gates L1–L3 in the plan). `ACTIVE` is the user's decision.
