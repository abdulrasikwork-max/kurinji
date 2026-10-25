"""norm-1: the text normaliser shared by the server and the phone (moderation-service/NORMALISER.md).

The normalised text is a MATCHING VIEW only: the author's words are never changed or stored by it. Every normalised
character remembers the code-point range of the raw text it came from, so a flagged phrase can be shown in the author's
own words. The Kotlin twin is android/.../core/textcheck/TextNormalizer.kt; both must give the same output for every
line of data/vectors/normaliser-vectors.tsv.
"""

import unicodedata

NORMALISER_VERSION = "norm-1"

# Step 0: characters that are invisible or only steer rendering, used to split words past a filter.
INVISIBLE_SINGLE = frozenset([0x00AD, 0x034F, 0x061C, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x3164, 0xFEFF, 0xFFA0])
INVISIBLE_RANGES = ((0x180B, 0x180F), (0x200B, 0x200F), (0x202A, 0x202E), (0x2060, 0x2064), (0x2066, 0x206F),
                    (0xFE00, 0xFE0F), (0x1D173, 0x1D17A), (0xE0000, 0xE007F), (0xE0100, 0xE01EF))

# Step 2: Greek and Cyrillic letters that look like Latin ones (and the dotless i).
HOMOGLYPHS = {
    0x0131: "i",
    # Cyrillic lower and upper case
    0x0430: "a", 0x0435: "e", 0x043E: "o", 0x0440: "p", 0x0441: "c", 0x0443: "y", 0x0445: "x", 0x043A: "k",
    0x043C: "m", 0x0442: "t", 0x043D: "h", 0x0432: "b", 0x0456: "i", 0x0458: "j", 0x0455: "s", 0x0501: "d",
    0x0410: "a", 0x0415: "e", 0x041E: "o", 0x0420: "p", 0x0421: "c", 0x0422: "t", 0x041D: "h", 0x041A: "k",
    0x041C: "m", 0x0412: "b", 0x0425: "x", 0x0423: "y", 0x0406: "i", 0x0408: "j", 0x0405: "s",
    # Greek lower and upper case
    0x03B1: "a", 0x03BF: "o", 0x03C1: "p", 0x03BD: "v", 0x03C4: "t", 0x03B9: "i", 0x03BA: "k", 0x03B5: "e",
    0x03C5: "u", 0x03C7: "x", 0x0391: "a", 0x0392: "b", 0x0395: "e", 0x0396: "z", 0x0397: "h", 0x0399: "i",
    0x039A: "k", 0x039C: "m", 0x039D: "n", 0x039F: "o", 0x03A1: "p", 0x03A4: "t", 0x03A5: "y", 0x03A7: "x",
    # Latin small capitals and the script g
    0x0261: "g",
}

# Step 3: digits and symbols written instead of letters inside Latin words.
LEET = {"0": "o", "1": "i", "3": "e", "4": "a", "5": "s", "7": "t", "8": "b", "@": "a", "$": "s", "!": "i",
        "|": "i", "€": "e"}
INTERIOR_ONLY = frozenset("!|")      # mapped only with a Latin letter on both sides ("sh!t", not "poda!")
AFTER_LETTER_ONLY = frozenset("@")   # mapped only after a Latin letter ("p@nda", not "@ravi")

# Step 4: separators between single letters ("p u n d a", "p.u.n.d.a").
SEPARATORS = frozenset(" .-_*/")

TAMIL_FIRST, TAMIL_LAST = 0x0B80, 0x0BFF


class Char:
    """One normalised character and the raw code-point range [start, end) it came from."""

    __slots__ = ("ch", "start", "end")

    def __init__(self, ch, start, end):
        self.ch = ch
        self.start = start
        self.end = end


class Token:
    """A word of the normalised text: its text, its raw range and its script (TAMIL / LATIN / MIXED / OTHER)."""

    __slots__ = ("text", "start", "end", "script", "first", "last")

    def __init__(self, text, start, end, script, first, last):
        self.text = text
        self.start = start      # raw code points
        self.end = end
        self.script = script
        self.first = first      # index of its first character in Normalised.chars
        self.last = last        # index after its last character


class Normalised:
    def __init__(self, chars, light):
        self.chars = chars          # after every step
        self.light = light          # after steps 0-2 only (contact details and links are read here)
        self.text = "".join(c.ch for c in chars)
        self.light_text = "".join(c.ch for c in light)
        self.tokens = _tokens(chars)

    def script(self):
        scripts = {t.script for t in self.tokens if t.script in ("TAMIL", "LATIN", "MIXED")}
        if not scripts:
            return "NONE"
        if scripts == {"TAMIL"}:
            return "TAMIL"
        if scripts == {"LATIN"}:
            return "LATIN"
        return "MIXED"


def invisible(cp):
    if cp in INVISIBLE_SINGLE:
        return True
    return any(lo <= cp <= hi for lo, hi in INVISIBLE_RANGES)


def is_mark(ch):
    return unicodedata.category(ch)[0] == "M"


def is_latin_letter(ch):
    return "a" <= ch <= "z"


def is_word_char(ch):
    category = unicodedata.category(ch)
    return category[0] in ("L", "M") or category == "Nd"


def is_tamil(ch):
    return TAMIL_FIRST <= ord(ch) <= TAMIL_LAST


def normalise(raw):
    # Step 0: drop invisible characters, keeping each remaining character's raw position.
    kept = [(ch, i) for i, ch in enumerate(raw) if not invisible(ord(ch))]

    # Step 1: NFKC per cluster (a character and the marks after it), so offsets survive composition.
    out = []
    i = 0
    while i < len(kept):
        j = i + 1
        while j < len(kept) and is_mark(kept[j][0]):
            j += 1
        start, end = kept[i][1], kept[j - 1][1] + 1
        for ch in unicodedata.normalize("NFKC", "".join(c for c, _ in kept[i:j])):
            out.append(Char(ch, start, end))
        i = j

    # Step 2: homoglyphs, Latin diacritics folded, ASCII lower case.
    for c in out:
        cp = ord(c.ch)
        if cp in HOMOGLYPHS:
            c.ch = HOMOGLYPHS[cp]
        elif 0x00C0 <= cp <= 0x024F:
            c.ch = unicodedata.normalize("NFD", c.ch)[0]
        if "A" <= c.ch <= "Z":
            c.ch = c.ch.lower()
    light = [Char(c.ch, c.start, c.end) for c in out]

    out = _leet(out)
    out = _join_single_letters(out)
    out = _squeeze(out)
    return Normalised(out, light)


def _leet(chars):
    n = len(chars)
    i = 0
    while i < n:
        if not (is_latin_letter(chars[i].ch) or chars[i].ch in LEET):
            i += 1
            continue
        j = i
        while j < n and (is_latin_letter(chars[j].ch) or chars[j].ch in LEET):
            j += 1
        letters = [k for k in range(i, j) if is_latin_letter(chars[k].ch)]
        if letters:
            first, last = letters[0], letters[-1]
            for k in range(i, j):
                ch = chars[k].ch
                if ch not in LEET:
                    continue
                if ch in INTERIOR_ONLY and not (first < k < last):
                    continue
                if ch in AFTER_LETTER_ONLY and not first < k:
                    continue
                chars[k].ch = LEET[ch]
        i = j
    return chars


def _join_single_letters(chars):
    n = len(chars)

    def single(p):
        return (is_latin_letter(chars[p].ch) and (p == 0 or not is_latin_letter(chars[p - 1].ch))
                and (p == n - 1 or not is_latin_letter(chars[p + 1].ch)))

    drop = set()
    p = 0
    while p < n:
        if not single(p):
            p += 1
            continue
        chain = [p]
        while (chain[-1] + 2 < n and chars[chain[-1] + 1].ch in SEPARATORS and single(chain[-1] + 2)):
            chain.append(chain[-1] + 2)
        if len(chain) >= 3:
            for q in chain[:-1]:
                drop.add(q + 1)
        p = chain[-1] + 1
    return [c for k, c in enumerate(chars) if k not in drop]


def _squeeze(chars):
    out = []
    for c in chars:
        if len(out) >= 2 and out[-1].ch == c.ch and out[-2].ch == c.ch:
            continue
        out.append(c)
    return out


def _tokens(chars):
    tokens = []
    i = 0
    n = len(chars)
    while i < n:
        if not is_word_char(chars[i].ch):
            i += 1
            continue
        j = i
        while j < n and is_word_char(chars[j].ch):
            j += 1
        text = "".join(c.ch for c in chars[i:j])
        tamil = any(is_tamil(ch) for ch in text)
        latin = any(is_latin_letter(ch) for ch in text)
        script = "MIXED" if tamil and latin else "TAMIL" if tamil else "LATIN" if latin else "OTHER"
        tokens.append(Token(text, chars[i].start, chars[j - 1].end, script, i, j))
        i = j
    return tokens
