import os
import re
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from kurinji_moderation.lexicon import Lexicon, compile_source  # noqa: E402
from kurinji_moderation.normalise import normalise  # noqa: E402

COMPILED = os.path.join(ROOT, "data", "lexicon-v1.keys")
SOURCE = os.path.join(ROOT, "restricted", "lexicon.tsv")
ANDROID = os.path.join(ROOT, "..", "android", "app", "src", "main", "assets", "textcheck", "lexicon-v1.keys")
SCORED = {"PROFANITY", "INSULT", "SEXUAL", "SLUR", "THREAT", "SPAM", "SELF_HARM", "DEHUMAN"}


class LexiconTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.lexicon = Lexicon.load(COMPILED)

    @unittest.skipUnless(os.path.exists(SOURCE), "restricted/ is not in git (sensitive): runs only where the team keeps it")
    def test_theCompiledFile_isUpToDateWithTheSource(self):
        with open(SOURCE, encoding="utf-8") as f:
            compiled = compile_source(f.readlines())
        with open(COMPILED, encoding="utf-8") as f:
            self.assertEqual(f.read(), "\n".join(compiled) + "\n")

    def test_theAppHasTheSameCompiledFile(self):
        with open(COMPILED, "rb") as a, open(ANDROID, "rb") as b:
            self.assertEqual(a.read(), b.read(), "run tools/build_lexicon.py")

    def test_theCompiledFile_holdsNoReadableWord(self):
        with open(COMPILED, encoding="utf-8") as f:
            lines = f.read().splitlines()
        self.assertEqual(lines[0], "SSLEX1\tlex-1\tnorm-1")
        for line in lines[1:]:
            self.assertRegex(line, r"^[0-9a-f]{16}\t[A-Z_]+\t[0-3]\t(exact|prefix)\t[1-5]$")

    def test_everydayWords_neverHitAScoredKind(self):
        with open(os.path.join(ROOT, "data", "vectors", "innocent-words.txt"), encoding="utf-8") as f:
            words = [w for line in f if not line.startswith("#") for w in line.split()]
        self.assertGreater(len(words), 400)
        for word in words:
            bad = [h for h in self.lexicon.match(normalise(word).tokens) if h.kind in SCORED]
            self.assertEqual([], bad, word)

    def test_englishFunctionWords_areNeverPersonMarkers(self):
        # "the" once shared its key with "dei", "on" with "un", "vada" with "vaada" (found while building lex-1)
        for word in "the on in a an to of at it is was this that there here vada pani paadu".split():
            hits = self.lexicon.match(normalise(word).tokens)
            self.assertFalse([h for h in hits if h.kind == "TARGET"], word)

    def test_compile_refusesUnsafeOrMalformedEntries(self):
        with self.assertRaises(ValueError):
            compile_source(["abc\tINSULT\t2\tta\tprefix\n"])         # a prefix stem shorter than 5
        with self.assertRaises(ValueError):
            compile_source(["word\tNICE\t2\ten\texact\n"])           # unknown kind
        with self.assertRaises(ValueError):
            compile_source(["word\tINSULT\t4\ten\texact\n"])         # severity 0-3
        with self.assertRaises(ValueError):
            compile_source(["word\tINSULT\t2\ten\texact\n", "word\tTHREAT\t2\ten\texact\n"])  # two meanings
        with self.assertRaises(ValueError):
            compile_source(["வணக்கம்\tINSULT\t2\ten\texact\n"])  # Tamil in "en"

    def test_prefixAndPhraseEntries(self):
        lexicon = Lexicon(compile_source(["placeholder\tINSULT\t2\tenc\tprefix\n", "go away now\tTHREAT\t3\tenc\texact\n",
                                          "kozhukattai\tSPAM\t1\tta\texact\n"]))
        self.assertEqual(["INSULT"], [h.kind for h in lexicon.match(normalise("placeholders here").tokens)])
        self.assertEqual([], lexicon.match(normalise("placehold").tokens))
        hits = lexicon.match(normalise("please go away now").tokens)
        self.assertEqual([(1, 4)], [(h.first, h.last) for h in hits])
        self.assertEqual(["SPAM"], [h.kind for h in lexicon.match(normalise("kolukattai").tokens)])

    @unittest.skipUnless(os.path.exists(SOURCE), "restricted/ is not in git (sensitive): runs only where the team keeps it")
    def test_theSourceSaysWhyCollidingWordsAreLeftOut(self):
        with open(SOURCE, encoding="utf-8") as f:
            source = f.read()
        self.assertNotRegex(source, re.compile(r"^panni\t", re.M), "panni also means did/doing")
        self.assertNotRegex(source, re.compile(r"^sunni\t", re.M), "Sunni is a Muslim tradition")
        self.assertNotRegex(source, re.compile(r"^rape\t", re.M), "people warn others with this word")


if __name__ == "__main__":
    unittest.main()
