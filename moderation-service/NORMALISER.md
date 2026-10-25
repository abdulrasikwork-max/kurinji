# norm-1 and lex-1: the shared text rules (Phase 13E)

Implemented twice, identically: Python `kurinji_moderation/` (server) and Kotlin
`android/app/src/main/java/com/safesphere/app/core/textcheck/` (phone). The shared vector files in `data/vectors/` and
`restricted/evidence-vectors.tsv` are written by `tools/make_vectors.py` from the Python code; the Kotlin tests
(`TextCheckParityTest`) must reproduce every line. Change a rule = change both + regenerate vectors + bump the version.

The normalised text is a **matching view**: the person's words are never changed by it, and every normalised character
keeps the raw code-point range it came from (so a flagged phrase can be marked in their own words).

## norm-1 steps (in this order)

0. **Invisible characters removed**: U+00AD, U+034F, U+061C, U+115F, U+1160, U+17B4, U+17B5, U+3164, U+FEFF, U+FFA0
   and the ranges U+180B–180F, U+200B–200F (ZWSP, ZWNJ, ZWJ, LRM, RLM), U+202A–202E, U+2060–2064, U+2066–206F,
   U+FE00–FE0F, U+1D173–1D17A, U+E0000–E007F, U+E0100–E01EF.
1. **NFKC per cluster** (a character and the combining marks after it): fullwidth, circled, mathematical letters and
   ligatures become plain; Tamil vowel signs compose (க + ெ + ா → கொ) and keep the cluster's raw range.
2. **Homoglyphs** (Greek/Cyrillic look-alikes and ı → Latin), else **Latin diacritics folded** (U+00C0–024F → first code
   point of NFD), then **ASCII lower case**. The result of this step is the *light* text (contact details and links are
   read there).
3. **Leetspeak inside Latin words** (a run of letters and `0 1 3 4 5 7 8 @ $ ! | €` with at least one letter):
   `0→o 1→i 3→e 4→a 5→s 7→t 8→b @→a $→s !→i |→i €→e`; `!` and `|` only between letters (`poda!` stays), `@` only after a
   letter (`@ravi` stays a mention); digits-only runs never change (`2024`, `$100`).
4. **Single letters re-joined**: three or more single Latin letters each separated by one of ` . - _ * /` lose the
   separators (`p.u.n.d.a`, `k o l l a`).
5. **Runs of three or more equal characters squeezed to two** (`soooo` → `soo`).

**Tokens**: maximal runs of letters, marks and decimal digits; script TAMIL (U+0B80–0BFF), LATIN (a–z), MIXED or OTHER.

## Keys (three spaces)

* `en` — the token as normalised; `enc` — runs of equal characters collapsed (elongations: `fuuck` → `fuck`);
* `ta` — **Tanglish phonetic key**: digraphs `zh→l sh→s ch→s th→t dh→t ph→p bh→p kh→k gh→k ng→n ck→k`, then
  `b→p d→t g→k c→k q→k z→s j→s w→v y→i e→i o→u f→p x→ks`, runs collapsed; **Tamil-script key**: ண ந → ன, ள ழ → ல,
  ற → ர, ீ→ி, ூ→ு, ே→ெ, ோ→ொ, ா dropped, ஆ ஈ ஊ ஏ ஓ → அ இ உ எ ஒ, a doubled consonant (C்C) written once, runs collapsed.

Each lexicon entry chooses its space: short or colliding Tanglish words use `en`/`enc` (e.g. *naaye* in `enc` because
the phonetic key of the neutral *naai* (dog) is the same; *panni* is left out: it also means "did/doing").

## lex-1 (compiled lexicon)

`SSLEX1\tlex-1\tnorm-1` then one line per entry: `sha256("SSLEX1|" + space + "|" + keys joined by " ")[:16 hex]`, kind,
severity 0–3, `exact|prefix`, number of tokens (1–5). A prefix entry needs a last key of at least 5 code points.
Kinds: PROFANITY, INSULT, SEXUAL, SLUR, IDENTITY, DEHUMAN, THREAT, SELF_HARM, SPAM, DOXX, TARGET, REPORTED.

## Evidence rules

* A hit's **sentence** ends at `. ! ? \n \r ।` between tokens. A TARGET hit (second/third person) or an `@mention` in
  the sentence makes it **targeted**.
* **Quoted**: inside closed `" "`, `“ ”` or `« »` (only when at least 3 words of the text are outside quotes; an
  unclosed mark quotes nothing), or in a sentence with a REPORTED marker.
* PROFANITY → PROFANITY (untargeted) / HARASSMENT (targeted); INSULT → HARASSMENT; SEXUAL → SEXUAL_HARASSMENT (severity
  3 is targeted by itself); SLUR → HATE (severity 3 targeted by itself); THREAT → THREAT; SPAM → SPAM; SELF_HARM → only
  the self-harm flag (unquoted). IDENTITY and DEHUMAN are never evidence alone: a group (IDENTITY or SLUR) within 4
  tokens of PROFANITY, INSULT, SLUR or DEHUMAN in the same sentence is HATE (severity 3, or 1 next to a mild word).
* Links (`https?://…`, `www.…`, `name.(com|in|net|org|io|me|ly|co|xyz|info|link|app)`) on the light text: 3 links, or a
  link and a SPAM hit → SPAM 3. E-mail and Indian mobile numbers → DOXXING 1, or 3 (targeted) with a DOXX phrase.
* Strong language = any PROFANITY or SLUR hit or an INSULT of severity 3 (quoted or not).
* Merge per (category, start, end): highest severity, targeted if any, quoted only if all; sorted by start, end,
  category, severity; at most 20.
