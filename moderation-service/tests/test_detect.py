import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "tests"))

from kurinji_moderation.detect import check  # noqa: E402
from kurinji_moderation.lexicon import Lexicon, compile_source  # noqa: E402
from test_normalise import unesc  # noqa: E402

# A neutral test lexicon: the rules are tested with placeholder words, not with abuse.
TEST_LEXICON = Lexicon(compile_source([
    "you\tTARGET\t0\tenc\texact\n", "nee\tTARGET\t0\tta\texact\n", "called me\tREPORTED\t0\tenc\texact\n",
    "blorp\tINSULT\t2\tenc\texact\n", "zonk\tINSULT\t1\tenc\texact\n", "frak\tPROFANITY\t2\tenc\texact\n",
    "grawl\tSLUR\t3\tenc\texact\n", "smeg\tSLUR\t2\tenc\texact\n", "teamfoo\tIDENTITY\t0\tenc\texact\n",
    "are toasters\tDEHUMAN\t0\tenc\texact\n", "hurt you\tTHREAT\t3\tenc\texact\n",
    "send pics\tSEXUAL\t3\tenc\texact\n", "buy now\tSPAM\t2\tenc\texact\n", "her number\tDOXX\t0\tenc\texact\n",
    "end my life\tSELF_HARM\t0\tenc\texact\n",
]))


def codes(text):
    return [e.code() for e in check(text, TEST_LEXICON).evidence]


class DetectTest(unittest.TestCase):

    @unittest.skipUnless(os.path.exists(os.path.join(ROOT, "restricted", "evidence-vectors.tsv")),
                         "restricted/ is not in git (sensitive): these vectors run only where the team keeps it")
    def test_restrictedEvidenceVectors_matchTheCode(self):
        lexicon = Lexicon.load(os.path.join(ROOT, "data", "lexicon-v1.keys"))
        with open(os.path.join(ROOT, "restricted", "evidence-vectors.tsv"), encoding="utf-8") as f:
            lines = [l.rstrip("\n") for l in f if not l.startswith("#")]
        self.assertGreater(len(lines), 200)
        for line in lines:
            text, evidence, strong, self_harm = line.split("\t")
            r = check(unesc(text), lexicon)
            self.assertEqual(evidence, ";".join(e.code() for e in r.evidence), text)
            self.assertEqual(strong == "1", r.strong_language, text)
            self.assertEqual(self_harm == "1", r.self_harm, text)

    def test_aTargetInTheSameSentence(self):
        self.assertEqual(["HARASSMENT:2:T:-@4-9"], codes("you blorp"))
        self.assertEqual(["HARASSMENT:2:U:-@0-5"], codes("blorp. you are kind"))
        self.assertEqual(["HARASSMENT:2:T:-@6-11"], codes("@ravi blorp"))

    def test_profanity_isStrongLanguage_untilItIsAimedAtSomeone(self):
        r = check("frak this traffic", TEST_LEXICON)
        self.assertEqual(["PROFANITY:2:U:-@0-4"], [e.code() for e in r.evidence])
        self.assertTrue(r.strong_language)
        self.assertEqual(["HARASSMENT:2:T:-@4-8"], codes("nee frak"))

    def test_quotedAndReportedSpeech_areMarked(self):
        self.assertEqual(["HARASSMENT:2:T:Q@18-23"], codes('a man said to me "blorp you" at the stop'))
        self.assertEqual(["HARASSMENT:2:U:Q@15-20"], codes("he called me a blorp near the stop"))

    def test_quotes_countOnlyWithWordsOutsideThem_andWhenClosed(self):
        self.assertEqual(["HARASSMENT:2:T:-@1-6"], codes('"blorp you"'))
        self.assertEqual(["HARASSMENT:2:T:-@1-6"], codes('"blorp you and all of this'))

    def test_identityTerms_neverAlone_hateNextToAbuse(self):
        self.assertEqual([], codes("teamfoo families welcome everyone"))
        self.assertIn("HATE:3:T:-@0-13", codes("teamfoo blorp everywhere"))
        self.assertIn("HATE:1:T:-@0-12", codes("teamfoo zonk crowd"))
        self.assertEqual(["HATE:3:T:-@0-20"], codes("teamfoo are toasters"))
        self.assertEqual([], codes("teamfoo fans love toasters"))
        self.assertIn("HATE:3:T:-@0-10", codes("smeg blorp crowd"))

    def test_severeSlursAndSolicitations_areAimedByThemselves(self):
        self.assertEqual(["HATE:3:T:-@0-5"], codes("grawl"))
        self.assertEqual(["SEXUAL_HARASSMENT:3:T:-@0-9"], codes("send pics"))

    def test_contactDetails_mildAlone_severeWithAPossessivePhrase(self):
        self.assertEqual(["DOXXING:1:U:-@5-15"], codes("call 9840012345 to book"))
        self.assertEqual(["DOXXING:3:T:-@14-24"], codes("her number is 9876543210"))
        self.assertEqual(["DOXXING:1:U:-@8-24"], codes("mail at ravi@example.org"))

    def test_spam_threeLinksOrALinkAndAPhrase(self):
        self.assertEqual(["SPAM:2:U:-@0-7"], codes("buy now"))
        self.assertIn("SPAM:3:U:-@8-16", codes("buy now www.x.in"))
        self.assertEqual(["SPAM:3:U:-@0-5"], codes("a.com b.in c.org"))
        self.assertEqual([], codes("see www.trek.org"))

    def test_selfHarm_isOnlyAFlag(self):
        r = check("some days I want to end my life", TEST_LEXICON)
        self.assertTrue(r.self_harm)
        self.assertEqual([], r.evidence)
        self.assertFalse(check('she said "end my life" in the play, then we had tea', TEST_LEXICON).self_harm)


if __name__ == "__main__":
    unittest.main()
