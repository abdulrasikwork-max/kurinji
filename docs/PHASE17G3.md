# Phase 17G-3 — Optional smart understanding of an Explore request (2026-11-03)

Status: **17G-3 implemented, compiled, tested, and manually verified live with Groq on the emulator** (section 8: real model through the real
Python service and backend, and the real app). **Not verified:** a physical phone, TalkBack, the Galaxy A23, and the real 422 path (see section 8). The optional feature is
**OFF by default** at every layer. Nothing in this sub-phase changes what 17G-1 does when it is off: Explore still works with no AI,
no key and no cost. Plan and approved decisions: `PHASE17G_PLAN.md` §5 and G-9 / G-11. 17G-2 (ratings, U5) is not started.

## 1. What was built

**When — and only when — an operator turns it on and a person agrees, pressing Search first asks an AI model (Groq's free tier first, decision 17G-3-D7) to read the typed words and
fill the SAME chips the phone's word list fills. The model never sees a place, never ranks, never decides; the deterministic engine of
17G-1 still decides every result. Any problem at all → the phone's own word list is used and the search goes on.**

| Layer | What |
|---|---|
| Python service | `understand.py` + `POST /v1/understand` (same internal key as the moderation check). `urllib`/`ssl` only, no SDK. One request through a small provider ADAPTER: **Groq** (default; `openai/gpt-oss-20b`, strict JSON schema, `reasoning_effort: low`) or Anthropic (`claude-haiku-5-5`, kept, stub-tested only); chosen by `IRL_UNDERSTANDING_PROVIDER`, 6 s timeout, no retries, no tools, redirects refused, HTTPS only. Sends the typed text + `explore-vocabulary-v1.tsv` (closed lists). Reply validated against the lists, capped at 2 categories / 4 tags. Key only in `IRL_UNDERSTANDING_API_KEY`; no key → `503 {"error":"off"}`. |
| Backend (`explore` package) | `UnderstandingController` (`POST /api/explore/understand`, `GET /api/explore/understanding`), `UnderstandingService`, `UnderstandingGate` (per-person 20 / global 300 per UTC day + 10-minute circuit breaker after any failure), `UnderstandingClient` (the only caller, to OUR service; fail-closed reading). Mode `app.explore.understanding.mode` = `OFF` (default) / `ON`. Never holds the provider key. |
| Android | Switch "Smart understanding (Preview)" + one honest question on the Explore screen, shown only when the server offers it; the agreement is stored with ONE account id; on Search: try → replace chips on a non-empty answer, otherwise keep the phone's chips and say "Standard understanding was used." once; hand-set chips are never overwritten; late answers are dropped. |
| Tests that pin it | `ModelPlacesTest` (amended on purpose: third pinned client, `urllib`/`ssl` allowed, provider address/header/key only in `understand.py`, the JVM and the app never name the provider), `UnderstandingIsolationTest`, `ExploreVocabularyParityTest`, `Phase17GExploreTest` (endpoint guard updated 1 → 3 on purpose), `Phase17G3SmartUnderstandingTest`. |

## 2. Decisions and deviations

- **17G-3-D7 — Groq's free tier is the first real provider (user-approved 2026-11-03, option C).** The user does not want to buy API
  credits. The task (classify a short sentence into fixed lists) fits `openai/gpt-oss-20b`, which supports strict JSON-schema output
  on Groq. The provider is an adapter inside `understand.py` (request shape + reply reading); Anthropic stays as a second adapter.
  Nothing else changes: caps, breaker, validation, fallback, key handling are provider-independent. **Decision D-2 is therefore
  provider-neutral** (it named the Claude API). Groq specifics (documented, not yet exercised): `Authorization: Bearer`,
  `max_completion_tokens`, `reasoning_effort` low, `response_format` json_schema strict; a custom `User-Agent` is sent because
  edge networks often refuse urllib's own. Free-tier limits (to check on the account's limits page): about 30 requests/min,
  1,000/day, 8,000 tokens/min and 200,000 tokens/day for `gpt-oss-20b` - the daily TOKENS, not requests, are the tighter limit
  (roughly 130-250 calls a day), so the 300/day global cap will not be reached; a 429 simply opens the breaker and the phone's word
  list is used, with no payment involved. Groq does not retain inference data by default but may log inputs/outputs for up to 30 days
  when troubleshooting or investigating abuse; Zero Data Retention can be switched on in its Data Controls (recommended).
- **17G-3-D1 — the switch is on the Explore screen, not in Settings** (plan §5 said Settings). Settings holds only navigation rows
  (`SettingsHonestyTest`), and the question matters at the place where the words are typed. Easy to move later.
- **17G-3-D2 — no Anthropic SDK in the Python service**, although the Claude API guidance prefers the SDK: the service is stdlib-only
  by project rule (13E, `ModelPlacesTest`), "no new dependency without approval" (CLAUDE.md), and the plan (approved) said
  `urllib` + `ssl`. Cost of that choice: the request shape follows the published API reference but has not been exercised against the
  real endpoint (see §6).
- **17G-3-D3 — key name `IRL_UNDERSTANDING_API_KEY`**, not `ANTHROPIC_API_KEY`: a key set on this PC for another tool must never
  start spending money here.
- **17G-3-D4 — the agreement belongs to an account**, not to the phone: someone else signing in on the same phone has not agreed.
  It is re-checked at the moment of sending.
- **17G-3-D5 — failures never show an error.** Off, unreachable, out of credit, over our caps, timeout, junk or empty answers all
  end in the phone's word list, with one calm line. A signed-out session is the only thing that stops the search (as everywhere).
- **17G-3-D6 — what the provider receives:** the typed text (≤ 200 characters) and the closed vocabulary. No place facts (narrower
  than D-2 allows). The consent question says this, says IRL does not save the words, and says the AI service has its own rules
  (what the provider keeps is NOT verified here and is not claimed either way).
- Search is offered when smart understanding is on even if the phone's word list found nothing (otherwise the person could never
  reach it); if it cannot help either, nothing is searched.

## 3. Security, privacy and rules review

- **Key:** exists only in the Python process environment; tests prove the JVM (`src/main`) and the app (`src/main`) contain no provider
  name, header or key variable; `toString()` of the properties omits the key; logs carry a failure category only.
- **Prompt injection:** the model's answer is data, validated twice (Python, then Java) against closed lists, then again on the phone;
  the worst a hostile sentence can do is return different vocabulary codes that the person sees as editable chips. The system prompt
  holds no secret and no data about people or places.
- **Spend:** default OFF; our own caps and breaker are independent of the provider; Python limits itself to 4 calls in flight.
  Residual: the counters are in memory (a restart resets them; several backend instances would each have their own), and the
  caps count per UTC day. The real ceiling must be a low spend limit set at the provider.
- **Privacy:** the typed words are sent only by one call, only after the question, only for the agreeing account; nothing is stored by
  us (no table, no file, no log); the phone stores one account id. The search request still has exactly four closed-list fields.
- **Isolation:** `explore` imports only closed lists and plumbing; social and safety never import it; no `@Scheduled`/`@Async`.
- **Honesty:** the feature is labelled Preview; the app never names the provider; no text claims anything is safe.
- Found and fixed in review: the Java text check allowed `\r`, which the service refuses — the service's 400 would have been read as a
  provider failure and opened the breaker for everyone. The two checks are now identical and a test covers `\r` and DEL.

## 4. Results

| Check | Result |
|---|---|
| Python service | 83 tests OK (45 new: both adapters against a stub provider — valid, invalid codes, junk, no/two text blocks, refusal / cut-off, 400-529, timeout without retry, connection refused, redirect not followed, exact request shape, bad texts, off, https-only, busy, route + log silence) |
| Backend | **858 / 0** (+40; 2 skipped = the opt-in real-MySQL checks): gate 6, client 9, API 11, off-by-default 1, isolation 7, vocabulary parity 4, ModelPlacesTest 8 |
| Android | **950 / 0** (+23); lint **0 errors / 104 warnings** (unchanged); `assembleDebug` OK |
| Real provider | **Verified live with Groq (`openai/gpt-oss-20b`)**, section 8 |
| Emulator | **Manually verified** (API 37): consent question, smart search, hand edit, repeat, our cap fallback, section 8 |
| A23 / TalkBack | **NOT VERIFIED** for 17G-3 |

## 5. How to use it (operator)

1. Create a free Groq account and an API key (console.groq.com -> API Keys). Switch on Zero Data Retention in Data Controls.
2. Start the service with the key (see `moderation-service/README.md`) and the backend with `EXPLORE_UNDERSTANDING_MODE=ON`.
3. In the app: Explore → "Smart understanding (Preview)" → read the question → Turn on.
4. To stop spending at once: unset the key (the route answers "off"), or set the mode back to `OFF`.

## 6. Manual verification checklist (the user's)

1. **Real request (the one thing no test can show):** in your own PowerShell window set the key (never in a file or this chat) and run
   `tools/smoke_understand.py` (exact commands: `moderation-service/README.md`). It sends 3 fixed sample sentences (English, Tanglish,
   Tamil) and prints the chips or a one-word reason. Expect e.g. `quiet cheap cafe to study in the evening` -> STUDY_WORK/FOOD_CAFES,
   QUIET, BUDGET_FRIENDLY, EVENING. A reason `auth` = wrong key; `limit` = free allowance used; `request` = a request detail to adjust
   in `understand.py`; `incomplete`/`shape` = the model's answer was unusable (the fallback would be used). **Judge the Tamil and
   Tanglish answers yourself: their quality is not verified.**
2. Backend `ON` without a key in the service: the Explore screen shows NO smart switch.
3. With everything on: switch on (question shown, "Not now" leaves it off); search "shaanthamaana cafe, cheap"; chips change and the
   line "Understood with smart understanding" appears; edit a chip and search again → no new call.
4. Stop the service: search again → "Standard understanding was used." and results still appear.
5. Sign in as a second account on the same phone: the switch is off for it.
6. 2.0x text and TalkBack on the Explore screen (switch, summary, note are live regions / labelled).

## 7. Viva notes

- **Why a separate Python service and not the Java backend?** One place for the key and the provider address (pinned by a test); the
  JVM stays free of any AI dependency; same internal pattern as the moderation check.
- **Why validate twice and still let the engine decide?** D2: a model produces evidence, a tested deterministic policy decides.
  A wrong or hostile answer can only change editable chips.
- **Why caps and a breaker if the provider has limits?** Provider limits are the provider's; ours protect the demo and the budget
  and keep a failing provider from slowing every search (a refused payment repeats and carries no retry-after).
- **Why per-account consent?** The words of one person must never be sent because another person agreed on the same phone.
- **What is not guaranteed?** That the provider accepts our request as written (stub-verified only) and what the provider retains.

## 8. Live verification (2026-11-03, the user's Groq free-tier key; real service + backend + emulator)

Setup: the user ran the Python service in their own PowerShell window (the key never left it); a backend with `EXPLORE_UNDERSTANDING_MODE=ON`
and a per-person cap of 3 ran on this PC; the app was the debug APK on the API 37 emulator. Scratch accounts and places were seeded through the
real sign-up (dev-file OTP); the emulator used the existing throwaway account.

| Check | Result |
|---|---|
| Service smoke test (3 fixed sentences: English, Tanglish, Tamil) | all answered in about 1 s |
| Offered only when the service holds a key | not offered with the service down; offered once it was up |
| Refusals before any provider call | blank, 201 chars, control character: 400; no sign-in: 401 |
| Real answers contain only closed-list codes, exactly 3 fields | yes (every run) |
| Chips from the real model found the right place through the deterministic engine | yes (the cafe, 5 of 5) |
| **Stability after the prompt and `temperature: 0` change** | Tanglish identical 3 of 3, Tamil identical 2 of 2, no invented category, 0.7-1.2 s per call |
| App: question shown; "Not now" leaves it off; "Turn on" switches it on and the helper says the words are sent | yes |
| App: smart search replaces the chips and says "Understood with smart understanding." | yes (English, Tanglish, a third sentence) |
| App: the same words again make no new call; a hand-edited chip is searched as the person set it | yes |
| App: the 4th sentence (our cap of 3) falls back to the phone's chips with "Standard understanding was used." and the search still returns places | yes |
| App: fallback while the breaker was open (first run) | yes, same behaviour |
| Typed words or key in the backend log or the repo | none found |

Findings made by the live run (both fixed, tests added):
- **17G-3-D8: a failure of ONE request must not open the breaker for everybody.** A prompt-injection sentence made Groq answer HTTP 400; the
  backend took it as a provider failure and paused smart understanding for everyone for 10 minutes. Now the service answers **422** for "this
  request got no usable answer" (a provider 400 `json_validate_failed`, a refused or cut-off answer, a malformed reply, our own busy limit) and **502**
  for provider failures (auth, billing, limit, other 4xx, 5xx, timeout); the backend's `Unusable` outcome falls back without opening the breaker. The
  service also logs the provider's short error code (never its message, which may echo the words). **Not verified live:** the exact code string
  `json_validate_failed` and the 422 path itself. The same sentence answered 200 (empty chips) on the next runs, so the 400 was not reproduced.
- **17G-3-D9: quality.** At the default temperature the Tanglish sentence got a different invented category on each run. The prompt now says a mood is a
  tag, never a category, and Groq requests send `temperature: 0`.
- Test-tool notes (not product): a PowerShell 5.1 script file loses Tamil letters without a BOM (the first "Tamil" call was empty); a loop in one PowerShell
  test script ran away, so the stability check was redone in Python.

Still not verified: a physical phone and TalkBack; Tamil quality beyond the three fixed sentences (judge it yourself); Groq's real free-tier behaviour when its
daily token allowance is exhausted (a 429 is handled and unit-tested, never reached live); what Groq keeps of the words beyond its documented policy.
