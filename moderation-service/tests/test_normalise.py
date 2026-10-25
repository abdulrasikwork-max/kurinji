import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "tools"))

from kurinji_moderation.normalise import normalise  # noqa: E402
from kurinji_moderation.phonetic import keys, latin_key, tamil_key  # noqa: E402
import make_vectors  # noqa: E402


def unesc(text):
    out, i = [], 0
    while i < len(text):
        ch = text[i]
        if ch != "\\":
            out.append(ch)
            i += 1
            continue
        nxt = text[i + 1]
        if nxt == "u":
            out.append(chr(int(text[i + 2:i + 6], 16)))
            i += 6
        elif nxt == "U":
            out.append(chr(int(text[i + 2:i + 10], 16)))
            i += 10
        else:
            out.append({"\\": "\\", "t": "\t", "n": "\n", "r": "\r"}[nxt])
            i += 2
    return "".join(out)


class NormaliseTest(unittest.TestCase):

    def test_sharedVectors_matchTheCode(self):
        path = os.path.join(ROOT, "data", "vectors", "normaliser-vectors.tsv")
        with open(path, encoding="utf-8") as f:
            lines = [l.rstrip("\n") for l in f if not l.startswith("#")]
        self.assertEqual(len(lines), len(make_vectors.NORMALISER_INPUTS))
        for line in lines:
            raw, text, light, offsets, tokens, token_keys, script = line.split("\t")
            n = normalise(unesc(raw))
            self.assertEqual(make_vectors.esc(n.text), text, raw)
            self.assertEqual(make_vectors.esc(n.light_text), light, raw)
            self.assertEqual(",".join(f"{c.start}-{c.end}" for c in n.chars), offsets, raw)
            self.assertEqual(n.script(), script, raw)
            self.assertEqual(";".join(f"{make_vectors.esc(t.text)}|{t.script}|{t.start}-{t.end}" for t in n.tokens),
                             tokens, raw)
            self.assertEqual(";".join(",".join(f"{s}={make_vectors.esc(k)}" for s, k in keys(t)) for t in n.tokens),
                             token_keys, raw)

    def test_invisibleCharacters_areRemoved_andOffsetsPointAtTheRawText(self):
        raw = "th​ev‍id⁠iya"
        n = normalise(raw)
        self.assertEqual(n.text, "thevidiya")
        token = n.tokens[0]
        self.assertEqual(raw[token.start:token.end], raw)

    def test_compatibilityForms_homoglyphs_andCase(self):
        self.assertEqual(normalise("ＨＥＬＬＯ").text, "hello")
        self.assertEqual(normalise("роса").text, "poca")  # Cyrillic p o c a
        self.assertEqual(normalise("Café").text, "cafe")
        self.assertEqual(normalise("ⒷⒶⒹ").text, "bad")

    def test_leetspeak_onlyInsideLatinWords(self):
        self.assertEqual(normalise("h3ll0").text, "hello")
        self.assertEqual(normalise("2024 at 4pm").text, "2024 at apm")
        self.assertEqual(normalise("sh!ne go!").text, "shine go!")
        self.assertEqual(normalise("p@rty @ravi").text, "party @ravi")
        self.assertEqual(normalise("$100").text, "$100")

    def test_singleLettersSplitBySeparators_areJoined_fromThreeOn(self):
        self.assertEqual(normalise("p.l.a.c.e").text, "place")
        self.assertEqual(normalise("p l a c e").text, "place")
        self.assertEqual(normalise("a b").text, "a b")
        self.assertEqual(normalise("I am a good").text, "i am a good")

    def test_longRuns_areSqueezedToTwo(self):
        self.assertEqual(normalise("soooo goooood").text, "soo good")

    def test_tamilClusters_composeAndKeepOffsets(self):
        n = normalise("கொ")   # க + ெ + ா  ->  கொ
        self.assertEqual(n.text, "கொ")
        self.assertEqual((n.tokens[0].start, n.tokens[0].end), (0, 3))
        self.assertEqual(n.tokens[0].script, "TAMIL")

    def test_keys_collapseSpellingVariants(self):
        self.assertEqual(latin_key("thevidiya"), latin_key("thevidya"))
        self.assertEqual(latin_key("oththa"), latin_key("otha"))
        self.assertEqual(latin_key("kozhukattai"), latin_key("kolukattai"))
        self.assertNotEqual(latin_key("panni"), latin_key("paattu"))
        self.assertEqual(tamil_key("நாள்"), tamil_key("னல்"))  # நாள் ~ னல்

    def test_scripts(self):
        self.assertEqual(normalise("vanakkam").script(), "LATIN")
        self.assertEqual(normalise("வணக்கம்").script(), "TAMIL")
        self.assertEqual(normalise("வணக்கம் Chennai").script(), "MIXED")
        self.assertEqual(normalise("123 !!").script(), "NONE")


if __name__ == "__main__":
    unittest.main()
