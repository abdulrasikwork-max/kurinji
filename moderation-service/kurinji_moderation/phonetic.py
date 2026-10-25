"""norm-1 matching keys of one normalised token (moderation-service/NORMALISER.md, "Keys").

Three key spaces, so that a word is matched the way it is really written:
  "en"  the token as normalised (English words whose spelling is fixed: "panni" must not become "pani", work);
  "enc" the same with every run of equal characters collapsed (elongations: "fuuck" -> "fuck");
  "ta"  a phonetic key: Tanglish has no standard spelling (zh / l / ll for one letter, d / t, oo / u ...), so variants
        collapse to one key; Tamil-script tokens get their own key (ண/ந -> ன, ள/ழ -> ல, ற -> ர, long vowels -> short).
"""

LATIN_DIGRAPHS = (("zh", "l"), ("sh", "s"), ("ch", "s"), ("th", "t"), ("dh", "t"), ("ph", "p"), ("bh", "p"),
                  ("kh", "k"), ("gh", "k"), ("ng", "n"), ("ck", "k"))
LATIN_SINGLE = {"b": "p", "d": "t", "g": "k", "c": "k", "q": "k", "z": "s", "j": "s", "w": "v", "y": "i", "e": "i",
                "o": "u", "f": "p", "x": "ks"}

TAMIL_MAP = {
    "ண": "ன", "ந": "ன",          # ண ந -> ன
    "ள": "ல", "ழ": "ல",          # ள ழ -> ல
    "ற": "ர",                              # ற -> ர
    "ீ": "ி", "ூ": "ு",          # ீ -> ி, ூ -> ு
    "ே": "ெ", "ோ": "ொ",          # ே -> ெ, ோ -> ொ
    "ா": "",                                    # ா (long a) dropped
    "ஆ": "அ", "ஈ": "இ", "ஊ": "உ", "ஏ": "எ", "ஓ": "ஒ",
}
PULLI = "்"
TAMIL_CONSONANTS = frozenset(chr(cp) for cp in range(0x0B95, 0x0BBA))


def collapse(text):
    out = []
    for ch in text:
        if not out or out[-1] != ch:
            out.append(ch)
    return "".join(out)


def latin_key(token):
    out = []
    i = 0
    while i < len(token):
        pair = token[i:i + 2]
        for digraph, replacement in LATIN_DIGRAPHS:
            if pair == digraph:
                out.append(replacement)
                i += 2
                break
        else:
            out.append(LATIN_SINGLE.get(token[i], token[i]))
            i += 1
    return collapse("".join(out))


def tamil_key(token):
    mapped = "".join(TAMIL_MAP.get(ch, ch) for ch in token)
    out = []
    i = 0
    while i < len(mapped):
        # a doubled consonant (C + pulli + C) is written once
        if (mapped[i] in TAMIL_CONSONANTS and i + 2 < len(mapped) and mapped[i + 1] == PULLI
                and mapped[i + 2] == mapped[i]):
            i += 2
            continue
        out.append(mapped[i])
        i += 1
    return collapse("".join(out))


def keys(token):
    """The (space, key) pairs a token can match in."""
    if token.script == "LATIN":
        return (("en", token.text), ("enc", collapse(token.text)), ("ta", latin_key(token.text)))
    if token.script == "TAMIL":
        return (("ta", tamil_key(token.text)),)
    return (("en", token.text),)
