import http.client
import io
import json
import os
import sys
import threading
import unittest
from contextlib import redirect_stderr

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "tools"))
sys.path.insert(0, os.path.join(ROOT, "tests"))

from kurinji_moderation.linear import LinearModel, features, fnv1a, train  # noqa: E402
from kurinji_moderation.policy_reference import decide  # noqa: E402
from kurinji_moderation.server import serve  # noqa: E402
import make_vectors  # noqa: E402
from test_normalise import unesc  # noqa: E402

VECTORS = os.path.join(ROOT, "data", "vectors")
KEY = "test-key-0123456789"


class PolicyTest(unittest.TestCase):

    def test_sharedVectors_matchTheReference(self):
        with open(os.path.join(VECTORS, "policy-vectors.tsv"), encoding="utf-8") as f:
            lines = [l.rstrip("\n").split("\t") for l in f if not l.startswith("#")]
        self.assertEqual(len(lines), len(make_vectors.POLICY_CASES))
        for codes, strong, available, probation, photos, mode, scores, action, rule, reason in lines:
            parsed = {} if scores == "-" else {k: float(v) for k, v in (p.split("=") for p in scores.split(","))}
            got = decide(make_vectors.parse_codes("" if codes == "-" else codes), strong == "1", available == "1",
                         probation == "1", photos == "1", mode, parsed or None)
            self.assertEqual((action, rule, reason), (got[0], got[1], got[2] or "-"), codes)

    def test_theRulesThatMatterMost(self):
        quoted = [{"category": "THREAT", "severity": 3, "targeted": True, "quoted": True}]
        self.assertEqual("PUBLISH", decide(quoted)[0])
        self.assertEqual("PUBLISH", decide([], available=False)[0], "an outage never silences anyone")
        self.assertEqual("HOLD_FOR_REVIEW", decide([], available=False, probation=True)[0])
        model_only = decide([], model_mode="ACTIVE", model_scores={"THREAT": 1.0})
        self.assertEqual("PUBLISH_COLLAPSED", model_only[0], "a model alone never holds")


class LinearTest(unittest.TestCase):

    def test_fnv1a(self):
        self.assertEqual(0x811C9DC5, fnv1a(""))
        self.assertEqual(0xE40C292C, fnv1a("a"))
        self.assertEqual(0xBF9CF968, fnv1a("foobar"))

    def test_features_areDeterministic_andNormalised(self):
        buckets, value = features("idli and sambar")
        self.assertEqual(buckets, sorted(buckets))
        self.assertAlmostEqual(1.0, len(buckets) * value * value)
        self.assertEqual(([], 0.0), features(""))

    def test_toyModel_roundTripsExactly_andMatchesTheParityVectors(self):
        with open(os.path.join(VECTORS, "linear-test-model.lin"), encoding="utf-8") as f:
            text = f.read()
        model = LinearModel.from_text(text)
        self.assertEqual(text, model.to_text())
        with open(os.path.join(VECTORS, "linear-vectors.tsv"), encoding="utf-8") as f:
            for line in f:
                if line.startswith("#"):
                    continue
                raw, expected = line.rstrip("\n").split("\t")
                scores = model.scores(unesc(raw))
                self.assertEqual(expected, ",".join(f"{k}={v!r}" for k, v in sorted(scores.items())))

    def test_training_separatesTheToyCategories(self):
        model = train(make_vectors.TOY, ["FOOD", "TRAVEL"], "lin-0", epochs=20, seed=7)
        self.assertGreater(model.scores("dosa and chutney")["FOOD"], 0.5)
        self.assertGreater(model.scores("hill trek")["TRAVEL"], 0.5)
        self.assertLess(model.scores("hill trek")["FOOD"], 0.5)

    def test_aModelForAnotherNormaliser_isRefused(self):
        with self.assertRaises(ValueError):
            LinearModel.from_text("SSLIN1\tlin-0\tnorm-2\t262144\t1.0\n")


class ServerTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.log = io.StringIO()
        with redirect_stderr(cls.log):
            cls.server = serve("127.0.0.1", 0, KEY)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls._serve, daemon=True)
        cls.thread.start()

    @classmethod
    def _serve(cls):
        with redirect_stderr(cls.log):
            cls.server.serve_forever()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def call(self, method, path, body=None, key=KEY, raw=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if key:
            headers["X-Moderation-Key"] = key
        data = raw if raw is not None else (json.dumps(body).encode("utf-8") if body is not None else None)
        connection.request(method, path, body=data, headers=headers)
        response = connection.getresponse()
        payload = json.loads(response.read().decode("utf-8"))
        connection.close()
        return response.status, payload

    def test_health(self):
        status, body = self.call("GET", "/v1/health", key=None)
        self.assertEqual(200, status)
        self.assertEqual({"normaliser": "norm-1", "lexicon": "lex-1", "model": "-"}, body["versions"])

    def test_assess_needsTheKey_andAShape(self):
        self.assertEqual(401, self.call("POST", "/v1/assess", {"kind": "POST", "text": "hi"}, key=None)[0])
        self.assertEqual(401, self.call("POST", "/v1/assess", {"kind": "POST", "text": "hi"}, key="wrong")[0])
        self.assertEqual(400, self.call("POST", "/v1/assess", {"kind": "PROFILE", "text": "hi"})[0])
        self.assertEqual(400, self.call("POST", "/v1/assess", raw=b"{not json")[0])
        self.assertEqual(400, self.call("POST", "/v1/assess", {"kind": "POST", "text": "x" * 6001})[0])
        self.assertEqual(413, self.call("POST", "/v1/assess", raw=b"x" * (33 * 1024))[0])

    def test_assess_returnsEvidenceOnly_andNeverLogsTheText(self):
        secret = "Unna kolla poren near the lighthouse"
        status, body = self.call("POST", "/v1/assess", {"kind": "COMMENT", "text": secret})
        self.assertEqual(200, status)
        self.assertEqual({"versions", "script", "evidence", "strongLanguage", "selfHarm", "modelScores"}, set(body))
        self.assertEqual("THREAT", body["evidence"][0]["category"])
        self.assertNotIn("action", json.dumps(body))
        self.assertNotIn("kolla", self.log.getvalue())
        self.assertNotIn("lighthouse", self.log.getvalue())
        self.assertIn("POST /v1/assess 200", self.log.getvalue())

    def test_noKey_noStart(self):
        with self.assertRaises(SystemExit):
            serve("127.0.0.1", 0, "short")


if __name__ == "__main__":
    unittest.main()
