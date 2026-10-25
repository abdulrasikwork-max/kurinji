"""The moderation service: internal network only, standard library only (docs/PHASE13E_PLAN.md E3, E4).

  POST /v1/assess  {"kind": "POST"|"COMMENT", "text": "..."} with header X-Moderation-Key -> evidence (never a decision)
  GET  /v1/health  -> {"status": "UP", "versions": {...}}

It never logs a text, a header or a body: only the method, the path, the status and the time taken. It binds to
127.0.0.1 unless told otherwise, and refuses to start without a key of at least 16 characters.

    MODERATION_SERVICE_KEY=... python -m kurinji_moderation.server [--host 127.0.0.1] [--port 8091] [--model FILE]
"""

import argparse
import hmac
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .service import Assessor

MAX_BODY = 32 * 1024


def make_handler(assessor, key):
    expected = key.encode("utf-8")

    class Handler(BaseHTTPRequestHandler):
        server_version = "kurinji-moderation"
        sys_version = ""

        def log_message(self, format, *args):  # noqa: A002 - the stdlib signature
            pass  # BaseHTTPRequestHandler would print the request line; we print our own safe line in _done

        def _done(self, status, body, started):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)
            ms = (time.perf_counter() - started) * 1000
            sys.stderr.write(f"{self.command} {self.path.split('?')[0]} {status} {ms:.1f}ms\n")

        def do_GET(self):
            started = time.perf_counter()
            if self.path == "/v1/health":
                self._done(200, {"status": "UP", "versions": assessor.versions()}, started)
            else:
                self._done(404, {"error": "not found"}, started)

        def do_POST(self):
            started = time.perf_counter()
            if self.path != "/v1/assess":
                self._done(404, {"error": "not found"}, started)
                return
            given = (self.headers.get("X-Moderation-Key") or "").encode("utf-8")
            if not hmac.compare_digest(given, expected):
                self._done(401, {"error": "key"}, started)
                return
            try:
                length = int(self.headers.get("Content-Length") or "-1")
            except ValueError:
                length = -1
            if length < 0:
                self._done(411, {"error": "length"}, started)
                return
            if length > MAX_BODY:
                self._done(413, {"error": "too large"}, started)
                return
            try:
                request = json.loads(self.rfile.read(length).decode("utf-8"))
                result = assessor.assess(request.get("kind"), request.get("text"))
            except (ValueError, AttributeError, UnicodeDecodeError):
                self._done(400, {"error": "shape"}, started)
                return
            self._done(200, result, started)

    return Handler


def serve(host, port, key, model=None):
    if not key or len(key) < 16:
        raise SystemExit("MODERATION_SERVICE_KEY must be set (at least 16 characters)")
    server = ThreadingHTTPServer((host, port), make_handler(Assessor(model_path=model), key))
    server.daemon_threads = True
    return server


def main():
    parser = argparse.ArgumentParser(description="Kurinji moderation service (internal only)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--model", default=None, help="an SSLIN1 model file (none ships in 13E)")
    args = parser.parse_args()
    server = serve(args.host, args.port, os.environ.get("MODERATION_SERVICE_KEY", ""), args.model)
    sys.stderr.write(f"moderation service on {args.host}:{args.port}\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
