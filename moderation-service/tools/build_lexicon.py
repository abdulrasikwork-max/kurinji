"""Compiles restricted/lexicon.tsv into data/lexicon-v1.keys and copies it to the Android assets.

    python -I tools/build_lexicon.py            (from moderation-service/)
"""

import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from kurinji_moderation.lexicon import compile_source  # noqa: E402

SOURCE = os.path.join(ROOT, "restricted", "lexicon.tsv")
TARGET = os.path.join(ROOT, "data", "lexicon-v1.keys")
ANDROID = os.path.join(ROOT, "..", "android", "app", "src", "main", "assets", "textcheck", "lexicon-v1.keys")


def main():
    if not os.path.exists(SOURCE):
        raise SystemExit("restricted/lexicon.tsv is not here: it is kept by the team, never in git (restricted/README.md)")
    with open(SOURCE, encoding="utf-8") as f:
        compiled = compile_source(f.readlines())
    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    with open(TARGET, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(compiled) + "\n")
    os.makedirs(os.path.dirname(ANDROID), exist_ok=True)
    shutil.copyfile(TARGET, ANDROID)
    print(f"{len(compiled) - 1} entries -> {TARGET} and the Android assets")


if __name__ == "__main__":
    main()
