"""lex-1: the hashed lexicon (compile from the restricted source, load, match).

The readable source (restricted/lexicon.tsv) never leaves the team: the service and the phone load only the compiled
file, in which each entry is the first 16 hex digits of SHA-256("SSLEX1|" + space + "|" + keys). Matching hashes the
keys of the text's own words, so the words of the lexicon are never needed at run time.
"""

import hashlib

from .normalise import NORMALISER_VERSION, normalise
from .phonetic import collapse, latin_key, tamil_key

LEXICON_VERSION = "lex-1"
MAGIC = "SSLEX1"
KINDS = ("PROFANITY", "INSULT", "SEXUAL", "SLUR", "IDENTITY", "DEHUMAN", "THREAT", "SELF_HARM", "SPAM", "DOXX",
         "TARGET", "REPORTED")
SPACES = ("en", "enc", "ta")
MAX_TOKENS = 5
MIN_PREFIX = 5   # a prefix entry needs at least 5 code points in its last key (shorter stems hit innocent words)


class Entry:
    __slots__ = ("kind", "severity", "prefix", "tokens")

    def __init__(self, kind, severity, prefix, tokens):
        self.kind = kind
        self.severity = severity
        self.prefix = prefix
        self.tokens = tokens


class Hit:
    __slots__ = ("kind", "severity", "first", "last")

    def __init__(self, kind, severity, first, last):
        self.kind = kind
        self.severity = severity
        self.first = first    # token index
        self.last = last      # token index after the last one

    def __repr__(self):
        return f"{self.kind}:{self.severity}@{self.first}-{self.last}"


def digest(space, keys):
    return hashlib.sha256((MAGIC + "|" + space + "|" + " ".join(keys)).encode("utf-8")).hexdigest()[:16]


def token_key(token, space):
    if space == "ta":
        if token.script == "TAMIL":
            return tamil_key(token.text)
        if token.script == "LATIN":
            return latin_key(token.text)
        return None
    if token.script == "TAMIL":
        return None
    return collapse(token.text) if space == "enc" else token.text


def compile_source(lines):
    """Readable TSV lines -> compiled lines. Raises ValueError on a malformed or unsafe entry."""
    out = {}
    for number, line in enumerate(lines, 1):
        line = line.rstrip("\n").rstrip("\r")
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 5:
            raise ValueError(f"line {number}: expected term, kind, severity, space, match")
        term, kind, severity, space, match = (p.strip() for p in parts[:5])
        if kind not in KINDS or space not in SPACES or match not in ("exact", "prefix"):
            raise ValueError(f"line {number}: unknown kind, space or match")
        severity = int(severity)
        if not 0 <= severity <= 3:
            raise ValueError(f"line {number}: severity 0-3")
        tokens = normalise(term).tokens
        keys = [token_key(t, space) for t in tokens]
        if not keys or None in keys or len(keys) > MAX_TOKENS:
            raise ValueError(f"line {number}: the term does not fit the space")
        if match == "prefix" and len(keys[-1]) < MIN_PREFIX:
            raise ValueError(f"line {number}: prefix stem shorter than {MIN_PREFIX}")
        h = digest(space, keys)
        record = f"{h}\t{kind}\t{severity}\t{match}\t{len(keys)}"
        if h in out and out[h] != record:
            raise ValueError(f"line {number}: the same key with another meaning")
        out[h] = record
    header = f"{MAGIC}\t{LEXICON_VERSION}\t{NORMALISER_VERSION}"
    return [header] + sorted(out.values())


class Lexicon:
    def __init__(self, compiled_lines):
        lines = [l.rstrip("\n").rstrip("\r") for l in compiled_lines if l.strip()]
        header = lines[0].split("\t")
        if header[0] != MAGIC or header[2] != NORMALISER_VERSION:
            raise ValueError("not a lex file for this normaliser")
        self.version = header[1]
        self.entries = {}
        for line in lines[1:]:
            h, kind, severity, match, n = line.split("\t")
            self.entries[h] = Entry(kind, int(severity), match == "prefix", int(n))

    @classmethod
    def load(cls, path):
        with open(path, encoding="utf-8") as f:
            return cls(f.readlines())

    def match(self, tokens):
        """Every lexicon entry found in the tokens (whole words or phrases; a prefix entry also matches longer words)."""
        hits = []
        for i in range(len(tokens)):
            for n in range(1, MAX_TOKENS + 1):
                if i + n > len(tokens):
                    break
                for space in SPACES:
                    keys = [token_key(t, space) for t in tokens[i:i + n]]
                    if None in keys:
                        continue
                    found = self.entries.get(digest(space, keys))
                    if found is not None and found.tokens == n:
                        hits.append(Hit(found.kind, found.severity, i, i + n))
                        continue
                    last = keys[-1]
                    for length in range(len(last) - 1, MIN_PREFIX - 1, -1):
                        found = self.entries.get(digest(space, keys[:-1] + [last[:length]]))
                        if found is not None and found.prefix and found.tokens == n:
                            hits.append(Hit(found.kind, found.severity, i, i + n))
                            break
        return _dedupe(hits)


def _dedupe(hits):
    seen = set()
    out = []
    for h in hits:
        key = (h.kind, h.severity, h.first, h.last)
        if key not in seen:
            seen.add(key)
            out.append(h)
    return out
