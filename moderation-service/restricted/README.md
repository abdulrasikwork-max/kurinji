# restricted/ — sensitive moderation reference material (NOT in git)

Only this README is in git. The files below contain **slurs, profanity, threats and abusive example sentences** in
English, Tamil and Tanglish, on purpose: they are what the automatic check must recognise. They are kept on the team's
machines (and a private backup), never in the repository (`moderation-service/.gitignore`).

* `lexicon.tsv` — the readable lexicon source (lex-1). Compiled by `tools/build_lexicon.py` into
  `data/lexicon-v1.keys` (hashed keys only), which is the only form the service and the app load and the only form in
  git. **Never copy this file into the app or any public place.**
* `dev_set.tsv` — the developer challenge set used by `tools/evaluate.py`. It is **not a dataset under D3**: invented
  texts written by the developer (AI-assisted), one annotator, no real person, used only to test rules and to measure
  false positives. Never train anything that ships on it.
* `evidence-vectors.tsv` — shared test vectors (written by `tools/make_vectors.py`) that pin the phone's evidence rules to
  the server's.

**Without these files** (a fresh clone): the service, the backend and the app work normally (they use the compiled
`data/lexicon-v1.keys`); the tests that need them are skipped with a reason (Python `test_lexicon` source check and
`test_detect` evidence vectors; Android `TextCheckParityTest` evidence vectors), and `build_lexicon.py` / `evaluate.py`
stop with a message. To change the lexicon you need the files: ask the project team for them.

Access: the project team only. Reviewing the lexicon is annotation work: it needs at least two native speakers before
any pilot (D3), rotation and the option to stop (design §7.11, annotator welfare).
