#!/usr/bin/env bash
set -euo pipefail

BIN=${1:?usage: native-linux-smoke.sh /path/to/lynx}
test -x "$BIN"

WORK=$(mktemp -d "${TMPDIR:-/tmp}/lynx-linux-smoke.XXXXXX")
HTTP_PORT=${LYNX_SMOKE_HTTP_PORT:-38133}
PROXY_PORT=${LYNX_SMOKE_PROXY_PORT:-62006}
export LYNX_EXECUTABLE="$BIN"

python3 -m http.server "$HTTP_PORT" --bind 127.0.0.1 >"$WORK/http.log" 2>&1 &
FIXTURE_PID=$!
cleanup() {
  kill "$FIXTURE_PID" 2>/dev/null || true
  wait "$FIXTURE_PID" 2>/dev/null || true
  "$BIN" network stop --json >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$BIN" network start --port "$PROXY_PORT" --json
for attempt in $(seq 1 20); do
  if curl --silent --show-error --noproxy '' \
      -x "http://127.0.0.1:$PROXY_PORT" \
      "http://127.0.0.1:$HTTP_PORT/README.md" \
      -o "$WORK/body" -w '%{http_code}' >"$WORK/status"; then
    break
  fi
  sleep 0.2
done

test "$(<"$WORK/status")" = 200
LIST=$("$BIN" network list --json)
printf '%s' "$LIST" | grep -q '"method":"GET"'
printf '%s' "$LIST" | grep -q '"status":200'

CA=$("$BIN" network ca install --json)
printf '%s' "$CA" | grep -q '"configured":true'
printf '%s' "$CA" | grep -q 'user_installation_required'
"$BIN" network ca remove --json >/dev/null
"$BIN" network doctor --json >/dev/null
printf 'NATIVE_LINUX_HTTP1_SMOKE=PASS\n'
