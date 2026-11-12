#!/usr/bin/env bash
# Phase D12 smoke test of a running IRL backend. READ-ONLY and anonymous: it signs in nowhere, creates nothing, sends no SMS,
# starts no SOS, and never calls /api/auth/*. It needs only curl.
#
#   smoke-test.sh BASE_URL [--allow-http] [--check-redirect] [--tunnel]
#     BASE_URL         for example https://<your domain>   (NO default: nothing is invented)
#     --allow-http     permit a plain http:// BASE_URL (ONLY for a local rehearsal; production must be https)
#     --check-redirect also check that http://<host>/ redirects to https (needs the proxy on port 80)
#     --tunnel         laptop + Cloudflare Tunnel (L1): also check the answer came through Cloudflare, that /api/admin and
#                      /api/admin/* are not served to the internet (404 from nginx or 403 from the Cloudflare rule), and that
#                      Cloudflare does not cache the API
#
# Exit status 0 only if every check passes.
set -u -o pipefail

base=""
allow_http=0
redirect=0
tunnel=0
for a in "$@"; do
  case "$a" in
    --allow-http) allow_http=1 ;;
    --check-redirect) redirect=1 ;;
    --tunnel) tunnel=1 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    -*) echo "unknown option: $a" >&2; exit 2 ;;
    *) base="$a" ;;
  esac
done
[ -n "$base" ] || { echo "usage: $0 BASE_URL [--allow-http] [--check-redirect] [--tunnel]" >&2; exit 2; }
base="${base%/}"
command -v curl >/dev/null || { echo "curl not found" >&2; exit 2; }

fail=0
ok()   { printf 'PASS  %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }
skip() { printf 'SKIP  %s\n' "$*"; }

case "$base" in
  https://*) is_https=1 ;;
  http://*)
    is_https=0
    if [ "$allow_http" -eq 1 ]; then echo "NOTE  plain http allowed for a local rehearsal (--allow-http)"; else
      echo "FAIL  BASE_URL is http://: production must be https:// (use --allow-http only for a local rehearsal)"; exit 1; fi ;;
  *) echo "BASE_URL must start with https:// (or http:// with --allow-http)" >&2; exit 2 ;;
esac

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# req NAME PATH [curl args...]: GET, writes $tmp/NAME.body and $tmp/NAME.head, sets $code (000 on a transport failure) and $rc
req() {
  local name="$1" path="$2"; shift 2
  code="$(curl -sS -m 20 -o "$tmp/$name.body" -D "$tmp/$name.head" -w '%{http_code}' "$@" "$base$path" 2>"$tmp/$name.err")"
  rc=$?
}
header() { grep -i "^$2:" "$tmp/$1.head" 2>/dev/null | head -1 | tr -d '\r'; }

echo "== reachability and TLS"
req health /api/health
if [ "$rc" -ne 0 ]; then
  if [ "$rc" -eq 60 ] || [ "$rc" -eq 35 ]; then bad "TLS: the certificate is not trusted or the handshake failed (curl exit $rc)"; else bad "cannot reach $base (curl exit $rc)"; fi
  echo; echo "RESULT: $fail failed (stopped: nothing answered)"; exit 1
fi
[ "$is_https" -eq 1 ] && ok "HTTPS connection with a certificate this machine trusts" || skip "TLS (plain http rehearsal)"

echo "== health check"
[ "$code" = "200" ] && ok "GET /api/health answers 200" || bad "GET /api/health answered $code"
[ "$(tr -d ' \r\n' < "$tmp/health.body")" = '{"status":"UP"}' ] && ok "the body is exactly {\"status\":\"UP\"}" || bad "the health body is not exactly {\"status\":\"UP\"}"
header health Cache-Control | grep -qi 'no-store' && ok "health is not cacheable (no-store)" || bad "health is missing Cache-Control: no-store"

echo "== protection"
req prot /api/explore/understanding
[ "$code" = "401" ] && ok "a protected endpoint answers 401 without a token" || bad "a protected endpoint answered $code without a token (expected 401)"
header prot WWW-Authenticate | grep -qi 'bearer' && ok "401 carries WWW-Authenticate: Bearer" || bad "401 has no WWW-Authenticate: Bearer"
if grep -q '"status"[[:space:]]*:[[:space:]]*401' "$tmp/prot.body"; then ok "the error body is the app's JSON shape"; else bad "the error body is not the app's JSON shape"; fi
if grep -qiE 'trace|Exception|[[:space:]]at (com|org|java)\.' "$tmp/prot.body"; then bad "the error body leaks a stack trace or exception text"; else ok "no stack trace or exception text in the error body"; fi
req unknown /api/this-path-does-not-exist
[ "$code" = "401" ] && ok "an unknown /api path is 401 (nothing is public by accident)" || bad "an unknown /api path answered $code (expected 401)"

echo "== not exposed"
req internal /v1/health
[ "$code" != "200" ] && ok "the internal moderation service is not reachable through this address (answered $code)" || bad "/v1/health answered 200: the internal service is exposed"
req cors /api/health -H 'Origin: https://evil.example'
header cors Access-Control-Allow-Origin | grep -q . && bad "a foreign Origin got an Access-Control-Allow-Origin header" || ok "no CORS header for a foreign Origin"
if header health Server | grep -qiE 'nginx/[0-9]|apache|tomcat|jetty'; then bad "the Server header reveals software/version"; else ok "the Server header reveals no software/version"; fi

if [ "$tunnel" -eq 1 ]; then
  echo "== Cloudflare tunnel"
  header health CF-Ray | grep -q . && ok "the answer came through Cloudflare (CF-Ray header present)" || bad "no CF-Ray header: this address is not behind Cloudflare"
  # The admin API has two walls: nginx answers 404 for /api/admin/ and (since 2026-11-10) a Cloudflare WAF rule answers 403 for any path
  # starting with /api/admin. Either is fine; what must never happen is the app answering (its errors are JSON with a "status" field).
  # The body is never printed: Cloudflare's block page carries the visitor's own address.
  for adminpath in /api/admin/evaluation /api/admin; do
    req admin "$adminpath"
    if { [ "$code" = "404" ] || [ "$code" = "403" ]; } && ! grep -q '"status"[[:space:]]*:' "$tmp/admin.body"; then
      ok "$adminpath is not served to the internet (answered $code, not by the app)"
    else
      bad "$adminpath answered $code (expected 404 from nginx or 403 from Cloudflare, never the app)"
    fi
  done
  req again /api/health
  if header again cf-cache-status | grep -qi 'hit'; then bad "Cloudflare served /api/health from its cache (add the Cache Rule: bypass for the API host)"; else ok "Cloudflare did not serve /api/health from its cache"; fi
  req prot2 /api/explore/understanding
  if header prot2 cf-cache-status | grep -qiE 'hit|revalidated|stale'; then bad "an API answer was cached by Cloudflare"; else ok "an API answer was not cached by Cloudflare"; fi
fi

echo "== transport hardening"
if [ "$is_https" -eq 1 ]; then
  header health Strict-Transport-Security | grep -qi 'max-age=' && ok "HSTS header present" || bad "no Strict-Transport-Security header"
  if [ "$redirect" -eq 1 ]; then
    hostpart="${base#https://}"
    rcode="$(curl -sS -m 20 -o /dev/null -w '%{http_code} %{redirect_url}' "http://$hostpart/" 2>/dev/null)"
    case "$rcode" in 30[1278]\ https://*) ok "http:// redirects to https://" ;; *) bad "http:// did not redirect to https:// ($rcode)" ;; esac
  else skip "http -> https redirect (add --check-redirect)"; fi
else
  skip "HSTS and redirect (plain http rehearsal)"
fi

echo
echo "RESULT: $fail failed"
[ "$fail" -eq 0 ]
