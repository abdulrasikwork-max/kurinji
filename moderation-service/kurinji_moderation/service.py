"""The assessment the backend asks for: lexicon evidence (+ model scores when a model file is configured)."""

import os

from .detect import check
from .lexicon import Lexicon
from .linear import LinearModel
from .normalise import NORMALISER_VERSION

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_LEXICON = os.path.join(HERE, "..", "data", "lexicon-v1.keys")
KINDS = ("POST", "COMMENT")
MAX_TEXT = 6000   # code points: a post body (5000) and its tip (300) with room to spare


class Assessor:
    def __init__(self, lexicon_path=DEFAULT_LEXICON, model_path=None):
        self.lexicon = Lexicon.load(lexicon_path)
        self.model = None
        if model_path:
            with open(model_path, encoding="utf-8") as f:
                self.model = LinearModel.from_text(f.read())

    def versions(self):
        return {"normaliser": NORMALISER_VERSION, "lexicon": self.lexicon.version,
                "model": self.model.version if self.model else "-"}

    def assess(self, kind, text):
        if kind not in KINDS:
            raise ValueError("kind")
        if not isinstance(text, str) or len(text) > MAX_TEXT:
            raise ValueError("text")
        result = check(text, self.lexicon)
        return {
            "versions": self.versions(),
            "script": result.script,
            "evidence": [e.as_dict() for e in result.evidence],
            "strongLanguage": result.strong_language,
            "selfHarm": result.self_harm,
            "modelScores": {k: round(v, 4) for k, v in self.model.scores(text).items()} if self.model else {},
        }
