# Phase 17G-3 — Optional smart understanding of an Explore request (2026-11-03)

Status: **17G-3 implemented, compiled, tested (against local stubs only).** **Not verified against the real provider** (no funded API
key was available or used), **not verified on the emulator or a physical phone in this session**, and the optional feature is
**OFF by default** at every layer. Nothing in this sub-phase changes what 17G-1 does when it is off: Explore still works with no AI,
no key and no cost. Plan and approved decisions: `PHASE17G_PLAN.md` §5 and G-9 / G-11. 17G-2 (ratings, U5) is not started.

## 1. What was built

**When — and only when — an operator turns it on and a person agrees, pressing Search first asks Claude to read the typed words and
fill the SAME chips the phone's word list fills. Claude never sees a place, never ranks, never decides; the deterministic engine of
17G-1 still decides every result. Any problem at all → the phone's own word list is used and the search goes on.**

| Layer | What |
|---|---|
| Python service | `understand.py` + `POST /v1/understand` (same internal key as the moderation check). `urllib`/`ssl` only, no SDK. One request to the Messages API (`claude-haiku-5-5`, `effort: low`, structured output with enum schemas), 6 s timeout, no retries, no tools, redirects refused, HTTPS only. Sends the typed text + `explore-vocabulary-v1.tsv` (closed lists). Reply validated against the lists, capped at 2 categories / 4 tags. Key only in `IRL_UNDERSTANDING_API_KEY`; no key → `503 {"error":"off"}`. |
| Backend (`explore` package) | `UnderstandingController` (`POST /api/explore/understand`, `GET /api/explore/understanding`), `UnderstandingService`, `UnderstandingGate` (per-person 20 / global 300 per UTC day + 10-minute circuit breaker after any failure), `UnderstandingClient` (the only caller, to OUR service; fail-closed reading). Mode `app.explore.understanding.mode` = `OFF` (default) / `ON`. Never holds the provider key. |
| Android | Switch "Smart understanding (Preview)" + one honest question on the Explore screen, shown only when the server offers it; the agreement is stored with ONE account id; on Search: try → replace chips on a non-empty answer, otherwise keep the phone's chips and say "Standard understanding was used." once; hand-set chips are never overwritten; late answers are dropped. |
| Tests that pin it | `ModelPlacesTest` (amended on purpose: third pinned client, `urllib`/`ssl` allowed, provider address/header/key only in `understand.py`, the JVM and the app never name the provider), `UnderstandingIsolationTest`, `ExploreVocabularyParityTest`, `Phase17GExploreTest` (endpoint guard updated 1 → 3 on purpose), `Phase17G3SmartUnderstandingTest`. |

## 2. Decisions and deviations

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
| Python service | 66 tests OK (28 new: stub provider — valid, invalid codes, junk, no/two text blocks, refusal / cut-off, 400-529, timeout without retry, connection refused, redirect not followed, exact request shape, bad texts, off, https-only, busy, route + log silence) |
| Backend | **856 / 0** (+38; 2 skipped = the opt-in real-MySQL checks): gate 6, client 8, API 10, off-by-default 1, isolation 7, vocabulary parity 4, ModelPlacesTest 8 |
| Android | **950 / 0** (+23); lint **0 errors / 104 warnings** (unchanged); `assembleDebug` OK |
| Real provider | **NOT VERIFIED** (stub only) |
| Emulator / A23 / TalkBack | **NOT VERIFIED** for 17G-3 (the 17G-1 emulator walk-through is unchanged by it) |

## 5. How to use it (operator)

1. Create an Anthropic **API** account (separate from a Claude subscription), prepaid credits with auto-reload OFF, and a low spend
   limit. Create a key limited to this use.
2. Start the service with the key (see `moderation-service/README.md`) and the backend with `EXPLORE_UNDERSTANDING_MODE=ON`.
3. In the app: Explore → "Smart understanding (Preview)" → read the question → Turn on.
4. To stop spending at once: unset the key (the route answers "off"), or set the mode back to `OFF`.

## 6. Manual verification checklist (the user's)

1. **Real request (the one thing no test can show):** with a funded key and a small spend limit, run the service and call
   `/v1/understand` once with "quiet cheap cafe for the evening"; expect `{"categories":["FOOD_CAFES"],"facets":["QUIET","BUDGET_FRIENDLY"],"daypart":"EVENING"}`-like output.
   If it answers 502 `unavailable`, read the service's stderr line (a category such as `request`, `auth`, `billing`) — the likely
   cause is a request detail (`anthropic-version`, `output_config`) that needs adjusting in `understand.py`.
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
