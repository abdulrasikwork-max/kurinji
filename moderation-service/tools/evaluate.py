"""Rung-1 evaluation on the developer challenge set (docs/PHASE13E_PLAN.md E30).

    python -I tools/evaluate.py [--json OUT]

Reports, per part (A = used while writing the lexicon, B = written afterwards): action accuracy and confusion, the
precision / recall of "acts" (collapse or hold) and of holds, false-action rates on the slices where nothing should
happen (clean, banter, untargeted profanity, counter-speech, safety warnings, identity mentions), the support line, the
obfuscation benchmark (attack success with and without the normaliser) and the time per check. These are numbers about
RULES on developer-written examples, not about a model and not about real posts.
"""

import argparse
import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from kurinji_moderation.detect import check  # noqa: E402
from kurinji_moderation.lexicon import Lexicon  # noqa: E402
from kurinji_moderation.policy_reference import decide  # noqa: E402

ACTIONS = {"PUBLISH": "PUBLISH", "COLLAPSE": "PUBLISH_COLLAPSED", "HOLD": "HOLD_FOR_REVIEW"}
RANK = {"PUBLISH": 0, "PUBLISH_COLLAPSED": 1, "HOLD_FOR_REVIEW": 2}
QUIET_SLICES = ("CLEAN", "BANTER", "PROFANITY", "COUNTER", "SAFETY", "IDENTITY", "INSULT_MILD")

CYRILLIC = {"a": "а", "e": "е", "o": "о", "p": "р", "c": "с"}
LEET = {"a": "4", "e": "3", "i": "1", "o": "0", "s": "5", "t": "7"}


def load_set(path):
    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or not line.strip():
                continue
            ident, part, slice_, expected, support, text = line.rstrip("\n").split("\t", 5)
            items.append({"id": ident, "part": part, "slice": slice_, "expected": ACTIONS[expected],
                          "support": support == "1", "text": text})
    return items


def run(text, lexicon, basic=False):
    result = check(text, lexicon, basic=basic)
    action, rule, reason = decide([e.as_dict() for e in result.evidence], result.strong_language)
    return action, rule, result.self_harm


def _words(text):
    return text.split(" ")


def obfuscations(text):
    words = _words(text)
    longest = max(range(len(words)), key=lambda k: len(words[k]))
    return {
        "zero_width": " ".join("​".join(w[i:i + 2] for i in range(0, len(w), 2)) for w in words),
        "leetspeak": "".join(LEET.get(ch, ch) for ch in text),
        "homoglyphs": "".join(CYRILLIC.get(ch, ch) for ch in text),
        "repeats": "".join(ch * 3 if ch in "aeiou" else ch for ch in text),
        "spacing": " ".join(" ".join(w) if k == longest else w for k, w in enumerate(words)),
        "mixed": " ".join(("​".join(w) if k % 3 == 0 else
                           "".join(LEET.get(ch, ch) for ch in w) if k % 3 == 1 else
                           "".join(CYRILLIC.get(ch, ch) for ch in w)) for k, w in enumerate(words)),
    }


def rate(numerator, denominator):
    return None if denominator == 0 else round(numerator / denominator, 3)


def evaluate(items, lexicon):
    out = {}
    for part in ("A", "B"):
        rows = [i for i in items if i["part"] == part]
        if not rows:
            continue
        started = time.perf_counter()
        predicted = [run(i["text"], lexicon) for i in rows]
        per_check_ms = (time.perf_counter() - started) * 1000 / len(rows)
        confusion = {e: {p: 0 for p in RANK} for e in RANK}
        for i, (action, _, _) in zip(rows, predicted):
            confusion[i["expected"]][action] += 1
        correct = sum(confusion[a][a] for a in RANK)
        acted = [(i, p) for i, p in zip(rows, predicted) if p[0] != "PUBLISH"]
        should = [(i, p) for i, p in zip(rows, predicted) if i["expected"] != "PUBLISH"]
        holds = [(i, p) for i, p in zip(rows, predicted) if p[0] == "HOLD_FOR_REVIEW"]
        should_hold = [(i, p) for i, p in zip(rows, predicted) if i["expected"] == "HOLD_FOR_REVIEW"]
        slices = {}
        for s in QUIET_SLICES:
            quiet = [(i, p) for i, p in zip(rows, predicted) if i["slice"] == s and i["expected"] == "PUBLISH"]
            slices[s] = {"n": len(quiet), "false_action": sum(1 for _, p in quiet if p[0] != "PUBLISH"),
                         "rate": rate(sum(1 for _, p in quiet if p[0] != "PUBLISH"), len(quiet))}
        support_true = [(i, p) for i, p in zip(rows, predicted) if i["support"]]
        support_false = [(i, p) for i, p in zip(rows, predicted) if not i["support"]]
        mistakes = [{"id": i["id"], "slice": i["slice"], "expected": i["expected"], "got": p[0], "rule": p[1]}
                    for i, p in zip(rows, predicted) if p[0] != i["expected"]]
        out[part] = {
            "n": len(rows), "accuracy": rate(correct, len(rows)), "confusion": confusion,
            "acts_precision": rate(sum(1 for i, _ in acted if i["expected"] != "PUBLISH"), len(acted)),
            "acts_recall": rate(sum(1 for _, p in should if p[0] != "PUBLISH"), len(should)),
            "hold_precision": rate(sum(1 for i, _ in holds if i["expected"] == "HOLD_FOR_REVIEW"), len(holds)),
            "hold_recall": rate(sum(1 for _, p in should_hold if p[0] == "HOLD_FOR_REVIEW"), len(should_hold)),
            "quiet_slices": slices,
            "support_recall": rate(sum(1 for _, p in support_true if p[2]), len(support_true)),
            "false_support": rate(sum(1 for _, p in support_false if p[2]), len(support_false)),
            "per_check_ms": round(per_check_ms, 2),
            "mistakes": mistakes,
            "obfuscation": obfuscation_benchmark([i for i in rows if i["expected"] != "PUBLISH"], lexicon),
        }
    return out


def obfuscation_benchmark(rows, lexicon):
    """Attack success = an obfuscated copy gets a milder action than the original should get."""
    result = {}
    for basic in (False, True):
        name = "baseline_no_normaliser" if basic else "norm_1"
        per = {}
        for i in rows:
            for kind, text in obfuscations(i["text"]).items():
                action = run(text, lexicon, basic=basic)[0]
                hit = per.setdefault(kind, [0, 0])
                hit[1] += 1
                if RANK[action] < RANK[i["expected"]]:
                    hit[0] += 1
        clean_misses = sum(1 for i in rows if RANK[run(i["text"], lexicon, basic=basic)[0]] < RANK[i["expected"]])
        result[name] = {"unobfuscated_miss_rate": rate(clean_misses, len(rows)),
                        **{k: rate(v[0], v[1]) for k, v in per.items()}}
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--json", default=None)
    args = parser.parse_args()
    lexicon = Lexicon.load(os.path.join(ROOT, "data", "lexicon-v1.keys"))
    path = os.path.join(ROOT, "restricted", "dev_set.tsv")
    if not os.path.exists(path):
        raise SystemExit("restricted/dev_set.tsv is not here: it is kept by the team, never in git (restricted/README.md)")
    items = load_set(path)
    report = evaluate(items, lexicon)
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    if args.json:
        with open(args.json, "w", encoding="utf-8", newline="\n") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    main()
