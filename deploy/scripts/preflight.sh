#!/usr/bin/env bash
# Phase D12 preflight: checks the production environment files BEFORE the services are started.
# It prints variable NAMES and LENGTHS only. It never prints a value, never sources the file (a value is never executed)
# and never contacts anything. Exit status 1 if any check FAILs; WARN is advice.
#
#   deploy/scripts/preflight.sh /etc/irl/backend.env [/etc/irl/moderation.env]
set -u -o pipefail

fail=0
warn=0
pass() { printf 'PASS  %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*"; fail=$((fail + 1)); }
note() { printf 'WARN  %s\n' "$*"; warn=$((warn + 1)); }

if [ $# -lt 1 ]; then
  echo "usage: $0 BACKEND_ENV_FILE [MODERATION_ENV_FILE]" >&2
  exit 2
fi
B="$1"
M="${2:-}"
if [ ! -f "$B" ]; then
  echo "FAIL  backend env file not found: $B"
  exit 1
fi
if [ -n "$M" ] && [ ! -f "$M" ]; then
  echo "FAIL  moderation env file not found: $M"
  exit 1
fi

# getv FILE KEY: the last value of KEY (carriage return removed). The caller must never echo it.
getv() { grep -E "^$2=" "$1" 2>/dev/null | tail -1 | cut -d= -f2- | tr -d '\r'; }
# placeholder: does a value look like something copied from an example?
placeholder() { printf '%s' "$1" | grep -qiE 'change-?me|<[^>]*>|your-|example|replace|xxxx'; }

# need_secret NAME MINLEN FILE: present, long enough, not a placeholder (reports the length only)
need_secret() {
  local name="$1" min="$2" file="$3" v
  v="$(getv "$file" "$name")"
  if [ -z "$v" ]; then bad "$name is empty or missing"; return; fi
  if placeholder "$v"; then bad "$name looks like a placeholder"; return; fi
  if [ "${#v}" -lt "$min" ]; then bad "$name is too short (${#v} characters, need at least $min)"; return; fi
  pass "$name is set (${#v} characters)"
}

echo "== file protection"
check_mode() {
  local f="$1" mode
  if [ "$(uname -s)" != "Linux" ]; then note "$f: permissions not checked (this is not Linux)"; return; fi
  mode="$(stat -c %a "$f" 2>/dev/null || echo 777)"
  case "$mode" in
    400|600|640) pass "$f has mode $mode" ;;
    *) bad "$f has mode $mode (must be 600, owner root: it holds secrets)" ;;
  esac
}
check_mode "$B"
[ -n "$M" ] && check_mode "$M"

echo "== profile and network"
profile="$(getv "$B" SPRING_PROFILES_ACTIVE)"
case ",$profile," in *,prod,*) pass "SPRING_PROFILES_ACTIVE includes prod" ;; *) bad "SPRING_PROFILES_ACTIVE must include prod" ;; esac
addr="$(getv "$B" SERVER_ADDRESS)"
case "$addr" in
  ""|127.0.0.1|::1|localhost) pass "SERVER_ADDRESS is loopback (or unset: the prod profile defaults to 127.0.0.1)" ;;
  *) bad "SERVER_ADDRESS is not a loopback address: the backend would be reachable around nginx" ;;
esac
fwd="$(getv "$B" FORWARD_HEADERS_STRATEGY)"
[ "$fwd" = "native" ] && pass "FORWARD_HEADERS_STRATEGY=native" || bad "FORWARD_HEADERS_STRATEGY must be native behind nginx (else every rate limit counts the proxy as one person)"
cors="$(getv "$B" CORS_ALLOWED_ORIGINS)"
[ -z "$cors" ] && pass "CORS_ALLOWED_ORIGINS is empty (the app is not a browser)" || note "CORS_ALLOWED_ORIGINS is set: only needed for a browser client"

echo "== database"
url="$(getv "$B" DB_URL)"
if [ -z "$url" ]; then
  bad "DB_URL is empty or missing"
else
  printf '%s' "$url" | grep -qiE 'sslMode=(REQUIRED|VERIFY_CA|VERIFY_IDENTITY)' && pass "DB_URL requires TLS (sslMode)" || bad "DB_URL has no sslMode=REQUIRED|VERIFY_CA|VERIFY_IDENTITY"
  printf '%s' "$url" | grep -qiE 'useSSL=false|sslMode=(DISABLED|PREFERRED)' && bad "DB_URL disables or weakens TLS"
  printf '%s' "$url" | grep -qiE 'connectionTimeZone=UTC' && printf '%s' "$url" | grep -qiE 'forceConnectionTimeZoneToSession=true' && pass "DB_URL keeps the UTC settings (docs/TIMESTAMPS.md)" || note "DB_URL should keep connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
  printf '%s' "$url" | grep -qiE 'password=|//[^/@]*:[^/@]*@' && bad "DB_URL contains a password: use DB_PASSWORD"
fi
[ -n "$(getv "$B" DB_USERNAME)" ] && pass "DB_USERNAME is set" || bad "DB_USERNAME is empty or missing"
need_secret DB_PASSWORD 16 "$B"

echo "== tokens and codes"
need_secret JWT_SECRET 32 "$B"
need_secret OTP_SECRET 32 "$B"
j="$(getv "$B" JWT_SECRET)"; o="$(getv "$B" OTP_SECRET)"
if [ -n "$j" ] && [ "$j" = "$o" ]; then bad "JWT_SECRET and OTP_SECRET are the same value (one leak would open both)"; fi

echo "== SMS"
prov="$(getv "$B" OTP_PROVIDER)"
[ "$prov" = "FAST2SMS" ] && pass "OTP_PROVIDER=FAST2SMS" || bad "OTP_PROVIDER must be FAST2SMS in production"
need_secret FAST2SMS_API_KEY 8 "$B"
[ -z "$(getv "$B" OTP_DEV_FILE)" ] && pass "OTP_DEV_FILE is not set" || bad "OTP_DEV_FILE is set: development sink, never in production"

echo "== storage"
ev="$(getv "$B" EVIDENCE_STORAGE_DIR)"; me="$(getv "$B" SOCIAL_MEDIA_DIR)"
case "$ev" in /*) pass "EVIDENCE_STORAGE_DIR is an absolute path" ;; *) bad "EVIDENCE_STORAGE_DIR must be an absolute path" ;; esac
case "$me" in /*) pass "SOCIAL_MEDIA_DIR is an absolute path" ;; *) bad "SOCIAL_MEDIA_DIR must be an absolute path" ;; esac
[ -n "$ev" ] && [ "$ev" = "$me" ] && bad "EVIDENCE_STORAGE_DIR and SOCIAL_MEDIA_DIR must be different folders"
for pair in "EVIDENCE_STORAGE_DIR:$ev" "SOCIAL_MEDIA_DIR:$me"; do
  n="${pair%%:*}"; p="${pair#*:}"
  if [ -n "$p" ] && [ "$(uname -s)" = "Linux" ]; then
    [ -d "$p" ] && pass "$n exists on this host" || note "$n does not exist on this host yet (create it, owner irl, mode 700)"
  fi
done
[ -z "$(getv "$B" EVIDENCE_VAULT_PUBLIC_KEY)" ] && note "EVIDENCE_VAULT_PUBLIC_KEY is empty: no recovery escrow for evidence (docs/PHASE5.md)" || pass "EVIDENCE_VAULT_PUBLIC_KEY is set"

echo "== moderation service"
mai="$(getv "$B" MODERATION_AI_ENABLED)"
if [ "$mai" = "false" ]; then
  note "MODERATION_AI_ENABLED=false: posts are published without the automatic check"
else
  need_secret MODERATION_SERVICE_KEY 16 "$B"
  [ -n "$(getv "$B" MODERATION_SERVICE_URL)" ] && pass "MODERATION_SERVICE_URL is set" || note "MODERATION_SERVICE_URL unset: the default http://127.0.0.1:8091 is used"
fi
if [ -n "$M" ]; then
  need_secret MODERATION_SERVICE_KEY 16 "$M"
  a="$(getv "$B" MODERATION_SERVICE_KEY)"; b="$(getv "$M" MODERATION_SERVICE_KEY)"
  if [ -n "$a" ] && [ "$a" = "$b" ]; then pass "MODERATION_SERVICE_KEY is the same in both files"; else bad "MODERATION_SERVICE_KEY differs between the backend and the moderation file"; fi
  if [ -n "$(getv "$M" IRL_UNDERSTANDING_API_KEY)" ]; then
    note "a provider key is set in the moderation file: typed Explore words would be sent to that provider (privacy policy must say so)"
  fi
else
  note "no moderation env file given: the key match was not checked"
fi
mode="$(getv "$B" EXPLORE_UNDERSTANDING_MODE)"
case "$mode" in ""|OFF) pass "EXPLORE_UNDERSTANDING_MODE is OFF" ;; *) note "EXPLORE_UNDERSTANDING_MODE=$mode: needs a provider key and a privacy-policy line" ;; esac

echo
echo "RESULT: $fail failed, $warn warnings"
[ "$fail" -eq 0 ]
