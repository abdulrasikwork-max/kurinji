"""Latency and throughput of the moderation service on this machine (docs/PHASE13E.md "Performance").

    python -I tools/benchmark.py

Starts the real HTTP server on a free local port, then measures: one check in process (typical post and a 6000
character worst case), the HTTP round trip one request at a time (p50 / p95 / p99), and throughput with 8 parallel
clients. Texts are invented and harmless (the lexicon is still searched for every word).
"""

import http.client
import json
import os
import statistics
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from kurinji_moderation.detect import check  # noqa: E402
from kurinji_moderation.lexicon import Lexicon  # noqa: E402
from kurinji_moderation.server import serve  # noqa: E402

KEY = "benchmark-key-0123456789"
TYPICAL = ("Went to Besant Nagar beach at 6 am, sema view da, the sunrise was worth waking up for. Murugan idli kadai "
           "la ghee podi idli must try pannunga. Parking illa, auto la vaanga. மெரினா கடற்கரையில் சூரிய உதயம் அழகாக "
           "இருந்தது. Carry water and go before 7, it gets crowded after that.")
WORST = (TYPICAL + " ") * 20


def percentile(values, p):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(p / 100 * (len(ordered) - 1))))]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    lexicon = Lexicon.load(os.path.join(ROOT, "data", "lexicon-v1.keys"))
    for name, text in (("typical", TYPICAL), ("worst", WORST[:6000])):
        runs = 200 if name == "typical" else 20
        started = time.perf_counter()
        for _ in range(runs):
            check(text, lexicon)
        print(f"in process, {name} ({len(text)} chars, {len(text.split())} words): "
              f"{(time.perf_counter() - started) * 1000 / runs:.2f} ms per check")

    server = serve("127.0.0.1", 0, KEY)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    body = json.dumps({"kind": "POST", "text": TYPICAL}).encode("utf-8")
    headers = {"Content-Type": "application/json", "X-Moderation-Key": KEY}

    def one(connection):
        connection.request("POST", "/v1/assess", body=body, headers=headers)
        response = connection.getresponse()
        response.read()
        return response.status

    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    for _ in range(20):
        one(connection)   # warm up
    latencies = []
    for _ in range(500):
        started = time.perf_counter()
        assert one(connection) == 200
        latencies.append((time.perf_counter() - started) * 1000)
    connection.close()
    print(f"HTTP one at a time (500 typical posts): p50 {percentile(latencies, 50):.2f} ms, "
          f"p95 {percentile(latencies, 95):.2f} ms, p99 {percentile(latencies, 99):.2f} ms, "
          f"mean {statistics.mean(latencies):.2f} ms")

    done = []

    def client():
        c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        for _ in range(100):
            one(c)
            done.append(1)
        c.close()

    started = time.perf_counter()
    threads = [threading.Thread(target=client) for _ in range(8)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    elapsed = time.perf_counter() - started
    print(f"HTTP 8 parallel clients: {len(done)} checks in {elapsed:.2f} s = {len(done) / elapsed:.0f} checks/s")
    server.shutdown()
    server.server_close()
    for path in ("data/lexicon-v1.keys", "data/vectors/linear-test-model.lin"):
        print(f"{path}: {os.path.getsize(os.path.join(ROOT, path))} bytes")


if __name__ == "__main__":
    main()
