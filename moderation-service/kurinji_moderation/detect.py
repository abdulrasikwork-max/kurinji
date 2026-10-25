"""lex-1 evidence: what the words of a text suggest (moderation-service/NORMALISER.md, "Evidence").

Evidence, never a decision: the server's DecisionPolicy (Java) decides. The rules here are deliberately simple so they
can be explained and implemented identically on the phone (core/textcheck/TextCheck.kt):
  - a word list hit is placed in its sentence; a second-person or third-person marker, or an @mention, in the same
    sentence makes it TARGETED;
  - hits inside closed quotation marks, or in a sentence with a reported-speech marker ("called me", "sonnan"), are
    QUOTED: people describing what was said to them must not be silenced (counter-speech, lived experience). Quotes
    count only when at least 3 words of the text are outside them;
  - an identity term (caste, religion, region, gender, sexuality) is never evidence alone; with an insult, profanity or
    slur at most 4 words away in the same sentence it is HATE;
  - untargeted profanity is PROFANITY (strong language), targeted it is HARASSMENT;
  - phone numbers and e-mail addresses are DOXXING severity 1 (shops share numbers), severity 3 with a possessive phrase
    about someone else ("her number", "avaloda number");
  - three links, or a link with a spam phrase, are SPAM severity 3;
  - first-person self-harm phrases only raise self_harm (never a category, never stored by the server).
"""

import re

from .lexicon import Lexicon
from .normalise import normalise

SENTENCE_ENDS = frozenset(".!?\n\r।")
OPEN_QUOTES = {"“": "”", "«": "»"}
STRAIGHT_QUOTE = '"'
IDENTITY_WINDOW = 4
MIN_OUTSIDE_QUOTES = 3
MAX_EVIDENCE = 20

# ASCII-only patterns with explicit look-arounds (no \b, \s or \w), so java.util.regex reads them the same way.
LINK = re.compile(r"https?://[^ \t\n\r]+|www\.[^ \t\n\r]+"
                  r"|(?<![a-z0-9.-])[a-z0-9-]+\.(?:com|in|net|org|io|me|ly|co|xyz|info|link|app)(?![a-z0-9])")
EMAIL = re.compile(r"(?<![a-z0-9._%+-])[a-z0-9._%+-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)*\.[a-z]{2,}")
PHONE = re.compile(r"(?<![0-9])(?:\+?91[ -]?)?[6-9][0-9]{4}[ -]?[0-9]{5}(?![0-9])")

SCORED = {"PROFANITY", "INSULT", "SEXUAL", "SLUR", "THREAT", "SPAM"}
ABUSIVE = {"PROFANITY", "INSULT", "SLUR"}


class Evidence:
    __slots__ = ("category", "severity", "targeted", "quoted", "start", "end", "source")

    def __init__(self, category, severity, targeted, quoted, start, end, source):
        self.category = category
        self.severity = severity
        self.targeted = targeted
        self.quoted = quoted
        self.start = start
        self.end = end
        self.source = source

    def as_dict(self):
        return {"category": self.category, "severity": self.severity, "targeted": self.targeted,
                "quoted": self.quoted, "start": self.start, "end": self.end, "source": self.source}

    def code(self):
        """The compact form used in the shared vectors: CATEGORY:severity:T|U:Q|-@start-end."""
        return (f"{self.category}:{self.severity}:{'T' if self.targeted else 'U'}:{'Q' if self.quoted else '-'}"
                f"@{self.start}-{self.end}")


class Result:
    def __init__(self, script, evidence, strong_language, self_harm):
        self.script = script
        self.evidence = evidence
        self.strong_language = strong_language
        self.self_harm = self_harm


def check(text, lexicon: Lexicon, basic=False):
    norm = normalise(text) if not basic else _basic(text)
    tokens = norm.tokens
    hits = lexicon.match(tokens)
    sentence = _sentences(norm)
    quoted = _quoted(norm)

    targeted_sentences = {sentence[h.first] for h in hits if h.kind == "TARGET"}
    for k, t in enumerate(tokens):
        if t.first > 0 and norm.chars[t.first - 1].ch == "@":
            targeted_sentences.add(sentence[k])
    reported_sentences = {sentence[h.first] for h in hits if h.kind == "REPORTED"}

    def span(first, last):
        return tokens[first].start, tokens[last - 1].end

    def is_quoted(first, last):
        return any(quoted[k] for k in range(first, last)) or sentence[first] in reported_sentences

    evidence = []
    self_harm = False
    strong = False
    for h in hits:
        if h.kind in ("PROFANITY", "SLUR") or (h.kind == "INSULT" and h.severity >= 3):
            strong = True
        if h.kind == "SELF_HARM":
            if not is_quoted(h.first, h.last):
                self_harm = True
            continue
        if h.kind not in SCORED:
            continue
        targeted = sentence[h.first] in targeted_sentences
        if h.kind == "PROFANITY":
            category = "HARASSMENT" if targeted else "PROFANITY"
        elif h.kind == "INSULT":
            category = "HARASSMENT"
        elif h.kind == "SEXUAL":
            category = "SEXUAL_HARASSMENT"
            targeted = targeted or h.severity >= 3   # a solicitation ("send nudes") is aimed at whoever reads it
        elif h.kind == "SLUR":
            category = "HATE"
            targeted = targeted or h.severity >= 3
        elif h.kind == "THREAT":
            category = "THREAT"
        else:
            category = "SPAM"
            targeted = False
        start, end = span(h.first, h.last)
        evidence.append(Evidence(category, h.severity, targeted, is_quoted(h.first, h.last), start, end, "LEXICON"))

    # A group (an identity term, or a slur, which names a group) next to an insult, profanity, another slur or a
    # dehumanising comparison in the same sentence.
    groups = [h for h in hits if h.kind in ("IDENTITY", "SLUR")]
    for h in hits:
        if h.kind not in ABUSIVE and h.kind != "DEHUMAN":
            continue
        for group in groups:
            if group is h or sentence[group.first] != sentence[h.first]:
                continue
            if max(group.first - h.last, h.first - group.last, 0) > IDENTITY_WINDOW:
                continue
            first, last = min(h.first, group.first), max(h.last, group.last)
            start, end = span(first, last)
            # abuse aimed at a group is hate at the top severity; a mild word next to a group stays mild (banter)
            severity = 3 if h.kind == "DEHUMAN" or h.severity >= 2 else 1
            evidence.append(Evidence("HATE", severity, True, is_quoted(first, last), start, end, "LEXICON"))

    light = norm.light
    links = [m for m in LINK.finditer(norm.light_text)]
    spam_hits = [e for e in evidence if e.category == "SPAM"]
    if len(links) >= 3 or (links and spam_hits):
        m = links[0]
        evidence.append(Evidence("SPAM", 3, False, False, light[m.start()].start, light[m.end() - 1].end, "PATTERN"))

    doxx_phrase = any(h.kind == "DOXX" for h in hits)
    contacts = sorted(list(EMAIL.finditer(norm.light_text)) + list(PHONE.finditer(norm.light_text)),
                      key=lambda m: m.start())
    for m in contacts:
        evidence.append(Evidence("DOXXING", 3 if doxx_phrase else 1, doxx_phrase, False, light[m.start()].start,
                                 light[m.end() - 1].end, "PATTERN"))

    evidence.sort(key=lambda e: (e.start, e.end, e.category, e.severity))
    return Result(norm.script(), _merge(evidence)[:MAX_EVIDENCE], strong, self_harm)


def _merge(evidence):
    """One entry per (category, span): the highest severity, targeted if any, quoted only if all are."""
    merged = {}
    order = []
    for e in evidence:
        key = (e.category, e.start, e.end)
        if key not in merged:
            merged[key] = Evidence(e.category, e.severity, e.targeted, e.quoted, e.start, e.end, e.source)
            order.append(key)
        else:
            m = merged[key]
            m.severity = max(m.severity, e.severity)
            m.targeted = m.targeted or e.targeted
            m.quoted = m.quoted and e.quoted
    return [merged[k] for k in order]


def _sentences(norm):
    tokens = norm.tokens
    ids = []
    current = 0
    for k, t in enumerate(tokens):
        if k > 0:
            between = norm.chars[tokens[k - 1].last:t.first]
            if any(c.ch in SENTENCE_ENDS for c in between):
                current += 1
        ids.append(current)
    return ids


def _quoted(norm):
    """Per token: inside a CLOSED pair of double quotation marks (straight, curly or guillemets)."""
    chars = norm.chars
    inside = [False] * len(chars)
    i = 0
    while i < len(chars):
        ch = chars[i].ch
        close = OPEN_QUOTES.get(ch) or (STRAIGHT_QUOTE if ch == STRAIGHT_QUOTE else None)
        if close is None:
            i += 1
            continue
        j = i + 1
        while j < len(chars) and chars[j].ch != close:
            j += 1
        if j >= len(chars):
            break  # an unclosed quotation mark quotes nothing
        for k in range(i + 1, j):
            inside[k] = True
        i = j + 1
    flags = [any(inside[t.first:t.last]) for t in norm.tokens]
    if sum(1 for f in flags if not f) < MIN_OUTSIDE_QUOTES:
        return [False] * len(flags)
    return flags


def _basic(text):
    """The baseline for the obfuscation benchmark: lower case and words only, none of the norm-1 steps."""
    from .normalise import Char, Normalised
    chars = [Char(ch.lower() if "A" <= ch <= "Z" else ch, i, i + 1) for i, ch in enumerate(text)]
    return Normalised(chars, [Char(c.ch, c.start, c.end) for c in chars])
