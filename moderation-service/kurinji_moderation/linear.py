"""Rung 2 of the model ladder: character n-grams + logistic regression (design §7.5), in plain Python.

Built, tested and OFF (docs/PHASE13E_PLAN.md E1/E2): no dataset under D3 exists yet, so no trained model ships and the
server's DecisionPolicy ignores scores unless app.moderation.ai.model-mode=ACTIVE. The Kotlin twin
(core/textcheck/LinearModel.kt) gives the same scores for data/vectors/linear-vectors.tsv.

Features (lin-0): for every normalised token, "<token>" character n-grams of length 2-4 ("c2:<a") and its phonetic key
("k:..."), hashed with 32-bit FNV-1a over UTF-8 into 2^18 buckets, each present bucket weighted 1/sqrt(number of
buckets). Score = sigmoid((bias + w . x) / temperature).
"""

import math
import random

from .normalise import normalise
from .phonetic import keys as token_keys

DIMS = 1 << 18
FNV_OFFSET = 0x811C9DC5
FNV_PRIME = 0x01000193


def fnv1a(text):
    h = FNV_OFFSET
    for byte in text.encode("utf-8"):
        h ^= byte
        h = (h * FNV_PRIME) & 0xFFFFFFFF
    return h


def features(text):
    """Sorted bucket indices and the shared value of each (the vector is L2-normalised)."""
    buckets = set()
    for token in normalise(text).tokens:
        padded = "<" + token.text + ">"
        for n in (2, 3, 4):
            for i in range(len(padded) - n + 1):
                buckets.add(fnv1a(f"c{n}:{padded[i:i + n]}") % DIMS)
        for space, key in token_keys(token):
            if space == "ta":
                buckets.add(fnv1a("k:" + key) % DIMS)
    ordered = sorted(buckets)
    value = 1.0 / math.sqrt(len(ordered)) if ordered else 0.0
    return ordered, value


def sigmoid(z):
    if z >= 0:
        return 1.0 / (1.0 + math.exp(-z))
    e = math.exp(z)
    return e / (1.0 + e)


class LinearModel:
    def __init__(self, version, temperature, categories):
        self.version = version
        self.temperature = temperature
        self.categories = categories    # name -> (bias, {bucket: weight})

    def scores(self, text):
        buckets, value = features(text)
        out = {}
        for name in sorted(self.categories):
            bias, weights = self.categories[name]
            z = bias
            for b in buckets:
                w = weights.get(b)
                if w is not None:
                    z += w * value
            out[name] = sigmoid(z / self.temperature)
        return out

    def to_text(self):
        """SSLIN1 text: a header, then per category a C line and its W lines (floats as Python repr: exact)."""
        lines = [f"SSLIN1\t{self.version}\tnorm-1\t{DIMS}\t{self.temperature!r}"]
        for name, (bias, weights) in sorted(self.categories.items()):
            lines.append(f"C\t{name}\t{bias!r}")
            lines.extend(f"W\t{b}\t{w!r}" for b, w in sorted(weights.items()))
        return "\n".join(lines) + "\n"

    @classmethod
    def from_text(cls, text):
        lines = [l for l in text.splitlines() if l.strip()]
        header = lines[0].split("\t")
        if header[0] != "SSLIN1" or header[2] != "norm-1" or int(header[3]) != DIMS:
            raise ValueError("not a lin model for norm-1")
        categories = {}
        current = None
        for line in lines[1:]:
            parts = line.split("\t")
            if parts[0] == "C":
                current = parts[1]
                categories[current] = (float(parts[2]), {})
            elif parts[0] == "W" and current is not None:
                categories[current][1][int(parts[1])] = float(parts[2])
            else:
                raise ValueError("malformed lin model")
        return cls(header[1], float(header[4]), categories)


def train(examples, categories, version, epochs=30, rate=0.5, l2=1e-4, seed=13, prune=1e-4):
    """examples = [(text, set of category names)]. Plain SGD with class weights; deterministic for a seed."""
    rng = random.Random(seed)
    data = [(features(text), labels) for text, labels in examples]
    model = {}
    for name in categories:
        positives = sum(1 for _, labels in data if name in labels)
        negatives = len(data) - positives
        weight_pos = negatives / positives if positives else 1.0
        bias = 0.0
        weights = {}
        order = list(range(len(data)))
        for epoch in range(epochs):
            rng.shuffle(order)
            step = rate / (1.0 + epoch * 0.1)
            for k in order:
                (buckets, value), labels = data[k]
                y = 1.0 if name in labels else 0.0
                z = bias + sum(weights.get(b, 0.0) * value for b in buckets)
                gradient = (sigmoid(z) - y) * (weight_pos if y else 1.0)
                bias -= step * gradient
                for b in buckets:
                    w = weights.get(b, 0.0)
                    weights[b] = w - step * (gradient * value + l2 * w)
        model[name] = (bias, {b: w for b, w in weights.items() if abs(w) >= prune})
    return LinearModel(version, 1.0, model)
