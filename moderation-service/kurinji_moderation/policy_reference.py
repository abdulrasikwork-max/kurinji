"""policy-1, a REFERENCE COPY for offline evaluation only (tools/evaluate.py).

The authoritative DecisionPolicy is Java (backend .../moderation/assessment/DecisionPolicy.java): the service never
decides. Both are pinned by data/vectors/policy-vectors.tsv, so the evaluation measures the policy that really runs.

Rules, first match wins:
  1. the check was unavailable: probation -> HOLD (CHECK_PENDING); everyone else -> PUBLISH (CHECK_PENDING);
  2. unquoted, targeted, severity >= 3 THREAT / SEXUAL_HARASSMENT / HATE / DOXXING -> HOLD (SEVERE);
     unquoted severity >= 3 SPAM -> HOLD (SPAM);
  3. probation and a post for everyone with photos -> HOLD (NEW_ACCOUNT_PHOTOS);
  4. unquoted, targeted, severity >= 2 HARASSMENT / SEXUAL_HARASSMENT / HATE / THREAT -> COLLAPSE (TARGETED);
  5. model ACTIVE and a score >= 0.9 -> COLLAPSE (MODEL) (never a hold from a model alone);
  6. otherwise PUBLISH: QUOTED if rule 2 or 4 would have acted on quoted evidence, else STRONG_LANGUAGE / CLEAN.
"""

POLICY_VERSION = "policy-1"
HOLD_CATEGORIES = ("THREAT", "SEXUAL_HARASSMENT", "HATE", "DOXXING")
COLLAPSE_CATEGORIES = ("HARASSMENT", "SEXUAL_HARASSMENT", "HATE", "THREAT")
RANK = ("THREAT", "SEXUAL_HARASSMENT", "HATE", "DOXXING", "SPAM", "HARASSMENT")


def _holds(e):
    return ((e["category"] in HOLD_CATEGORIES and e["targeted"] and e["severity"] >= 3)
            or (e["category"] == "SPAM" and e["severity"] >= 3))


def _collapses(e):
    return e["category"] in COLLAPSE_CATEGORIES and e["targeted"] and e["severity"] >= 2


def _top(evidence):
    return min(evidence, key=lambda e: (RANK.index(e["category"]), -e["severity"]))["category"]


def decide(evidence, strong_language=False, available=True, probation=False, public_photos=False,
           model_mode="OFF", model_scores=None):
    """-> (action, rule, reason); action PUBLISH | PUBLISH_COLLAPSED | HOLD_FOR_REVIEW."""
    if not available:
        return ("HOLD_FOR_REVIEW", "CHECK_PENDING", "AWAITING_CHECK") if probation else ("PUBLISH", "CHECK_PENDING", None)
    live = [e for e in evidence if not e["quoted"]]
    hold = [e for e in live if _holds(e)]
    if hold:
        top = _top(hold)
        return "HOLD_FOR_REVIEW", "SPAM" if top == "SPAM" else "SEVERE", top
    if probation and public_photos:
        return "HOLD_FOR_REVIEW", "NEW_ACCOUNT_PHOTOS", "NEEDS_REVIEW"
    collapse = [e for e in live if _collapses(e)]
    if collapse:
        return "PUBLISH_COLLAPSED", "TARGETED", _top(collapse)
    if model_mode == "ACTIVE" and model_scores:
        candidates = [k for k in sorted(model_scores) if k in RANK]
        if candidates:
            best = max(candidates, key=lambda k: model_scores[k])   # ties: the first name in alphabetical order
            if model_scores[best] >= 0.9:
                return "PUBLISH_COLLAPSED", "MODEL", best
    quoted = [e for e in evidence if e["quoted"] and (_holds(e) or _collapses(e))]
    if quoted:
        return "PUBLISH", "QUOTED", None
    return "PUBLISH", "STRONG_LANGUAGE" if strong_language else "CLEAN", None
