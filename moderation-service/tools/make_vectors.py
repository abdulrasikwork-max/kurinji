"""Writes the shared test vectors that pin the Kotlin (phone) and Java (server policy) twins to this code.

    python -I tools/make_vectors.py

  data/vectors/normaliser-vectors.tsv   norm-1 on innocuous inputs: text, light text, offsets, tokens, keys, script
  data/vectors/policy-vectors.tsv       policy-1 decisions (evidence codes + context -> action, rule, reason)
  data/vectors/linear-test-model.lin    a TOY rung-2 model (food vs travel words; nothing about abuse) and
  data/vectors/linear-vectors.tsv       its scores, for the Kotlin runtime's parity test
  restricted/evidence-vectors.tsv       lex-1 evidence on the challenge set and regression sentences (abusive text)
"""

import os
import sys
import unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from kurinji_moderation.detect import check  # noqa: E402
from kurinji_moderation.lexicon import Lexicon  # noqa: E402
from kurinji_moderation.linear import train  # noqa: E402
from kurinji_moderation.normalise import invisible, normalise  # noqa: E402
from kurinji_moderation.phonetic import keys  # noqa: E402
from kurinji_moderation.policy_reference import decide  # noqa: E402

VECTORS = os.path.join(ROOT, "data", "vectors")

NORMALISER_INPUTS = [
    "Hello World", "  spaces   and\ttabs\nnew line ", "", "Café crème brûlée", "Ångström",
    "ＡＢＣ fullwidth", "Ⓗⓔⓛⓛⓞ circled", "ﬁne ﬂow ligatures",
    "x² and ½", "\U0001d407\U0001d41e\U0001d425\U0001d425\U0001d428 bold", "b@nana p@rty @ravi @ 5pm",
    "h3ll0 w0rld 2024 4pm b4", "$hop a$$ $100", "sh!ne go! !wow", "p|ne x|y", "€uro gr€at",
    "p.l.a.c.e and a-b-c-d and x_y_z", "u r a b", "I am a b c", "soooo goooood!!!", "aaaa bbbbbb ccc",
    "zero​width‌join‍here", "soft­hyphen word⁠joiner", "bidi‮TEXT‬",
    "tag\U000e0041\U000e0042 chars", "variation️ selector", "саt роор (Cyrillic)",
    "αβγ ΑΒ Greek", "dır dotless", "ɡood script g",
    "மெரினா கடற்கரை",
    "கொ கொ decomposed and composed o",
    "தமிழ் நாடு வாழ்க",
    "வணக்கம் Chennai நண்பர்களே",
    "கூட்டம் அதிகம் இருக்கு",
    "ழளல ணநன றர", "ஆஈஊஏஓ vowels",
    "kozhukattai zh sh ch th dh ph bh kh gh ng ck", "nalla irukku, semma mass da!",
    "Thiruvanmiyur beach-la full moon", "WhatsApp 98400 12345 or +91-9840012345", "mail me at a.b@example.org",
    "visit www.example.com or https://x.in/a?b=1", "emoji \U0001f98b butterfly \U0001f338", "mixedசword",
    "123 456", "முருகன்‍முருகன் ZWJ in Tamil",
    "UPPER lower MiXeD", "quote \"inside\" and “curly”", "İstanbul dotted",
]

REGRESSION_SENTENCES = [
    "Christians feed stray dogs here every morning.", "Muslims don't eat pigs, so this mess has no pork.",
    "The Muslim street market is crowded but the food is amazing.", "Kodaikanal Suicide Point view at sunrise.",
    "enna panni irukeenga? kutty story super", "nee enna panni", "You can bring your dog here",
    "Call 9840012345 to book a table", "her number is 9876543210 call her", "Niger and Nigeria are in Africa.",
    "Sunni and Shia families pray at this dargah.", "The kuthu song was loud, paattu was fun",
    "\"Unna kolla poren\"", "Unna kolla poren \"wait\"", "she said \"nee oru thevidiya\" to me, report her.",
]


def esc(text):
    out = []
    for ch in text:
        cp = ord(ch)
        if ch == "\\":
            out.append("\\\\")
        elif ch == "\t":
            out.append("\\t")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif invisible(cp) or unicodedata.category(ch)[0] == "C":
            out.append(f"\\u{cp:04X}" if cp <= 0xFFFF else f"\\U{cp:08X}")
        else:
            out.append(ch)
    return "".join(out)


def normaliser_vectors():
    lines = ["# norm-1 vectors: input, normalised, light, offsets (start-end per normalised char), tokens "
             "(text|script|start-end), keys (space=key per token), script. Escapes: \\\\ \\t \\n \\r \\uXXXX \\UXXXXXXXX"]
    for raw in NORMALISER_INPUTS:
        n = normalise(raw)
        offsets = ",".join(f"{c.start}-{c.end}" for c in n.chars)
        tokens = ";".join(f"{esc(t.text)}|{t.script}|{t.start}-{t.end}" for t in n.tokens)
        token_keys = ";".join(",".join(f"{space}={esc(key)}" for space, key in keys(t)) for t in n.tokens)
        lines.append("\t".join([esc(raw), esc(n.text), esc(n.light_text), offsets, tokens, token_keys, n.script()]))
    write(os.path.join(VECTORS, "normaliser-vectors.tsv"), lines)


def evidence_vectors(lexicon):
    if not os.path.exists(os.path.join(ROOT, "restricted", "dev_set.tsv")):
        print("restricted/ is not here (it is never in git): evidence vectors not written")
        return
    lines = ["# lex-1 evidence vectors (RESTRICTED): text, evidence codes CATEGORY:severity:T|U:Q|-@start-end "
             "(';'), strong language 0|1, self-harm 0|1"]
    texts = []
    with open(os.path.join(ROOT, "restricted", "dev_set.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            texts.append(line.rstrip("\n").split("\t", 5)[5])
    for text in texts + REGRESSION_SENTENCES:
        r = check(text, lexicon)
        lines.append("\t".join([esc(text), ";".join(e.code() for e in r.evidence), "1" if r.strong_language else "0",
                                "1" if r.self_harm else "0"]))
    write(os.path.join(ROOT, "restricted", "evidence-vectors.tsv"), lines)


POLICY_CASES = [
    # evidence codes, strong, available, probation, public photos, model mode, model scores
    ("", False, True, False, False, "OFF", None),
    ("", True, True, False, False, "OFF", None),
    ("PROFANITY:3:U:-", True, True, False, False, "OFF", None),
    ("HARASSMENT:1:T:-", False, True, False, False, "OFF", None),
    ("HARASSMENT:2:U:-", False, True, False, False, "OFF", None),
    ("HARASSMENT:2:T:-", False, True, False, False, "OFF", None),
    ("HARASSMENT:3:T:-", True, True, False, False, "OFF", None),
    ("HARASSMENT:3:T:Q", True, True, False, False, "OFF", None),
    ("THREAT:2:T:-", False, True, False, False, "OFF", None),
    ("THREAT:3:T:-", False, True, False, False, "OFF", None),
    ("THREAT:3:U:-", False, True, False, False, "OFF", None),
    ("THREAT:3:T:Q", False, True, False, False, "OFF", None),
    ("SEXUAL_HARASSMENT:3:T:-", False, True, False, False, "OFF", None),
    ("SEXUAL_HARASSMENT:2:T:-", False, True, False, False, "OFF", None),
    ("SEXUAL_HARASSMENT:1:U:-", False, True, False, False, "OFF", None),
    ("HATE:3:T:-", False, True, False, False, "OFF", None),
    ("HATE:2:T:-", False, True, False, False, "OFF", None),
    ("HATE:1:T:-", False, True, False, False, "OFF", None),
    ("DOXXING:1:U:-", False, True, False, False, "OFF", None),
    ("DOXXING:3:T:-", False, True, False, False, "OFF", None),
    ("SPAM:2:U:-", False, True, False, False, "OFF", None),
    ("SPAM:3:U:-", False, True, False, False, "OFF", None),
    ("HARASSMENT:2:T:-;THREAT:3:T:-", False, True, False, False, "OFF", None),
    ("SPAM:3:U:-;HATE:3:T:-", False, True, False, False, "OFF", None),
    ("HARASSMENT:2:T:-;SEXUAL_HARASSMENT:2:T:-", False, True, False, False, "OFF", None),
    ("THREAT:3:T:-", False, False, False, False, "OFF", None),
    ("", False, False, True, False, "OFF", None),
    ("", False, False, False, False, "OFF", None),
    ("", False, True, True, True, "OFF", None),
    ("", False, True, True, False, "OFF", None),
    ("", False, True, False, True, "OFF", None),
    ("HARASSMENT:2:T:-", False, True, True, True, "OFF", None),
    ("THREAT:3:T:-", False, True, True, True, "OFF", None),
    ("", False, True, False, False, "ACTIVE", {"HARASSMENT": 0.95}),
    ("", False, True, False, False, "ACTIVE", {"HARASSMENT": 0.5}),
    ("", False, True, False, False, "SHADOW", {"HARASSMENT": 0.99}),
    ("", False, True, False, False, "OFF", {"THREAT": 0.99}),
    ("", False, True, False, False, "ACTIVE", {"THREAT": 0.99, "HARASSMENT": 0.92}),
    ("", False, True, False, False, "ACTIVE", {"PROFANITY": 0.99, "HARASSMENT": 0.91}),
    ("", False, True, False, False, "ACTIVE", {"HATE": 0.95, "HARASSMENT": 0.95}),
    ("HARASSMENT:2:T:-", False, True, False, False, "ACTIVE", {"THREAT": 0.99}),
]


def parse_codes(codes):
    out = []
    for code in filter(None, codes.split(";")):
        category, severity, target, quoted = code.split(":")
        out.append({"category": category, "severity": int(severity), "targeted": target == "T",
                    "quoted": quoted == "Q"})
    return out


def policy_vectors():
    lines = ["# policy-1 vectors: evidence (CATEGORY:severity:T|U:Q|- ';'), strong, available, probation, "
             "public photos, model mode, model scores (CATEGORY=score ','), action, rule, reason (- for none)"]
    for codes, strong, available, probation, photos, mode, scores in POLICY_CASES:
        action, rule, reason = decide(parse_codes(codes), strong, available, probation, photos, mode, scores)
        lines.append("\t".join([codes or "-", b(strong), b(available), b(probation), b(photos), mode,
                                ",".join(f"{k}={v!r}" for k, v in sorted((scores or {}).items())) or "-",
                                action, rule, reason or "-"]))
    write(os.path.join(VECTORS, "policy-vectors.tsv"), lines)


TOY = [
    ("idli dosa sambar chutney breakfast", {"FOOD"}), ("biryani parotta mutton dinner", {"FOOD"}),
    ("filter kaapi and vada", {"FOOD"}), ("pongal and kesari at the temple", {"FOOD"}),
    ("beach sunrise walk", {"TRAVEL"}), ("trek to the hill top", {"TRAVEL"}), ("bus to ooty and the lake", {"TRAVEL"}),
    ("train ride to pondy", {"TRAVEL"}), ("idli after the trek", {"FOOD", "TRAVEL"}), ("quiet library", set()),
    ("music at the sabha", set()), ("rain today", set()),
]
TOY_TEXTS = ["idli and sambar", "trek at sunrise", "a quiet evening", "இட்லி dosa",
             "Biryani after the beach walk", "", "h3ll0 tr3k"]


def linear_vectors():
    model = train(TOY, ["FOOD", "TRAVEL"], "lin-0", epochs=20, seed=7)
    with open(os.path.join(VECTORS, "linear-test-model.lin"), "w", encoding="utf-8", newline="\n") as f:
        f.write(model.to_text())
    lines = ["# lin-0 parity vectors for the TOY model linear-test-model.lin: text, CATEGORY=score (repr) ','"]
    for text in TOY_TEXTS:
        scores = model.scores(text)
        lines.append(esc(text) + "\t" + ",".join(f"{k}={v!r}" for k, v in sorted(scores.items())))
    write(os.path.join(VECTORS, "linear-vectors.tsv"), lines)


def b(flag):
    return "1" if flag else "0"


def write(path, lines):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def main():
    lexicon = Lexicon.load(os.path.join(ROOT, "data", "lexicon-v1.keys"))
    normaliser_vectors()
    evidence_vectors(lexicon)
    policy_vectors()
    linear_vectors()
    print("vectors written")


if __name__ == "__main__":
    main()
