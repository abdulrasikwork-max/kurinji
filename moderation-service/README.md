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

## Optional Explore understanding (Phase 17G-3)

`POST /v1/understand {"text": "..."}` (same `X-Moderation-Key`) turns one typed Explore request into closed-list chips
(`categories`, `facets`, `daypart`) with an AI model, through a small provider **adapter** in `kurinji_moderation/understand.py`:
**Groq** (free tier; default; `openai/gpt-oss-20b`) or Anthropic (`claude-haiku-5-5`, stub-tested only). **It is OFF unless
`IRL_UNDERSTANDING_API_KEY` is set in this process's environment** (a deliberately different name from any provider's own, so a key
set for another tool can never start spending money here); without it the route answers `503 {"error":"off"}`, `/v1/health` says
`"understanding": "OFF"`, and the backend / app behave exactly as before.

* `understand.py` is the **only** Python file that opens a network connection (`urllib` + `ssl`, no SDK, no new dependency;
  `ModelPlacesTest` pins this) and the only one that reads the key variables. One request, no retries, no tools, 6 s timeout,
  redirects refused, HTTPS only (a loopback address is accepted for tests). It sends the typed text and
  `data/explore-vocabulary-v1.tsv` (the closed lists) - no place, post, person, coordinate or account. The text and the key are
  never logged; failures log a category (`limit`, `auth`, `timeout`, `shape`...) only.
* The reply is untrusted: every entry is checked against the closed lists, extras are dropped and capped (2 categories, 4 tags).
* Variables (all optional except the key): `IRL_UNDERSTANDING_API_KEY`, `IRL_UNDERSTANDING_PROVIDER` (`groq` default | `anthropic`),
  `IRL_UNDERSTANDING_MODEL` (default: the provider's).
* **Not verified against a real provider yet**: the tests use a local stub only. Do the smoke test below once.

### First real test with Groq (free; you do this in your own PowerShell window - the key never goes into a file, git or a chat)

1. Create the key: sign in at console.groq.com -> **API Keys** -> **Create API Key** -> name it `irl-explore-dev` -> copy it (it is
   shown once). In **Settings -> Data Controls** switch on Zero Data Retention. On the **Limits** page note the numbers for
   `openai/gpt-oss-20b`.
2. In a NEW PowerShell window (not inside Claude Code), set it for this window only (nothing is shown or saved):

```powershell
$k = Read-Host "Groq API key" -AsSecureString
$env:IRL_UNDERSTANDING_API_KEY = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($k))
Remove-Variable k
```

3. Run the smoke test (3 requests with fixed sample sentences):

```powershell
cd "C:\Users\ABDUL RAFIQ\Desktop\SafeSphere-Phase3\SafeSphere\moderation-service"
& "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -E -s tools\smoke_understand.py
```

It prints `provider=groq model=openai/gpt-oss-20b` and, per sentence, the chips or a one-word reason. Closing the window forgets the key.

To run the real service with it (same window):

```powershell
$env:MODERATION_SERVICE_KEY = "change-me-to-a-long-random-key"
& "$env:USERPROFILE\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" -E -s -m kurinji_moderation.server --port 8091
```

and start the backend with `EXPLORE_UNDERSTANDING_MODE=ON` and the same `MODERATION_SERVICE_KEY`. Caps (20 per person and 300 in
total per UTC day) and a 10-minute breaker after any failure are the backend's own (`app.explore.understanding.*`). To stop at once:
close the window or unset the key (the route answers "off"), or set the mode back to `OFF`.
