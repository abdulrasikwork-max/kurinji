"""One real request to the Explore understanding provider (Phase 17G-3) - run by the PERSON who owns the key.

    python -E -s tools/smoke_understand.py

It reads the same environment variables as the service (see kurinji_moderation/understand.py: the key, optionally the provider
and the model), sends a few FIXED sample sentences (English, Tanglish, Tamil - nothing of yours), and prints the chips it got
or the short reason it could not (limit, auth, request, timeout, shape...). It prints the provider and model, never the key.
Exit code 0 only if every sample gave an answer. Each run uses 3 requests of the provider's free allowance.
"""

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from kurinji_moderation.understand import Unavailable, from_environment  # noqa: E402

SAMPLES = (
    "quiet cheap cafe to study in the evening",
    "shaanthamaana idam, kuraintha selavu, saayangaalam",
    "அமைதியான கஃபே, மாலை நேரம்",
)


def main():
    understander = from_environment()
    if not understander.enabled:
        print("OFF: IRL_UNDERSTANDING_API_KEY is not set in this window (nothing was sent).")
        return 2
    print(f"provider={understander.provider} model={understander.model}")
    failures = 0
    for text in SAMPLES:
        started = time.perf_counter()
        try:
            chips = understander.understand(text)
            status = "OK"
        except Unavailable as e:
            chips, status = None, f"UNAVAILABLE ({e.reason})"
            failures += 1
        print(f"- {text!r}\n    {status} in {time.perf_counter() - started:.1f}s  {chips if chips is not None else ''}")
    print("all answered" if not failures else f"{failures} of {len(SAMPLES)} gave no answer")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
