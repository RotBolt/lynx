#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LYNX="${LYNX:-$ROOT/apps/cli/build/bin/macosArm64/releaseExecutable/lynx.kexe}"

usage() {
  echo "Usage: LYNX=/path/to/lynx $0 <android|ios> <device-serial-or-simulator-udid> <package-or-bundle-id>" >&2
  exit 2
}

[[ $# -eq 3 ]] || usage
PLATFORM="$1"
TARGET="$2"
APP_ID="$3"
[[ -x "$LYNX" ]] || { echo "Lynx executable is not runnable: $LYNX" >&2; exit 2; }
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }

case "$PLATFORM" in
  android)
    command -v adb >/dev/null || { echo "adb must be on PATH (set ANDROID_HOME or ANDROID_SDK_ROOT)" >&2; exit 2; }
    echo "Ensure this Android target trusts the Lynx CA shown by: $LYNX network ca show --json"
    adb -s "$TARGET" shell monkey -p "$APP_ID" 1 >/dev/null
    "$LYNX" attach "$TARGET" "$APP_ID"
    ;;
  ios)
    command -v xcrun >/dev/null || { echo "xcrun is required" >&2; exit 2; }
    CA_PATH="$("$LYNX" network ca show --json | jq -er '.pemPath')"
    xcrun simctl keychain "$TARGET" add-root-cert "$CA_PATH"
    xcrun simctl launch "$TARGET" "$APP_ID" >/dev/null
    "$LYNX" attach "ios-simulator:$TARGET" "$APP_ID"
    ;;
  *) usage ;;
esac

cleanup() {
  "$LYNX" network stop --json >/dev/null 2>&1 || true
  "$LYNX" detach >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

START_RESULT="$("$LYNX" network start --json)"
echo "$START_RESULT" | jq -e '.type == "network_started"' >/dev/null || {
  echo "Lynx did not start capture: $START_RESULT" >&2
  exit 1
}

if [[ "$PLATFORM" == android ]]; then
  adb -s "$TARGET" shell am force-stop "$APP_ID"
  adb -s "$TARGET" shell monkey -p "$APP_ID" 1 >/dev/null
else
  xcrun simctl terminate "$TARGET" "$APP_ID" >/dev/null 2>&1 || true
  xcrun simctl launch "$TARGET" "$APP_ID" >/dev/null
fi

latest_request_id() {
  "$LYNX" network list --url "$1" --json | jq -r '.exchanges[-1].requestId // ""'
}

assert_capture() {
  local label="$1" url_fragment="$2" previous_id="$3" protocol="$4" status_test="$5" mode="${6:-http}"
  local event="" request_id="" attempt

  for attempt in {1..30}; do
    event="$("$LYNX" network list --url "$url_fragment" --limit 1 --json | jq -c '.exchanges[-1] // empty')"
    request_id="$(jq -r '.requestId // ""' <<<"$event")"
    if [[ -n "$request_id" && "$request_id" != "$previous_id" ]]; then
      break
    fi
    sleep 1
  done

  if [[ -z "$event" || "$request_id" == "$previous_id" ]]; then
    echo "FAIL $label: no new exchange appeared in lynx network list" >&2
    return 1
  fi

  if ! jq -e --arg protocol "$protocol" --arg status_test "$status_test" --arg mode "$mode" '
    .protocol == $protocol and
    .failure == null and
    (if $mode == "websocket" then
       .response.status == 101 and
       ([.frames[]?.payload] | any(. == "lynx-sample-ping"))
     else
       (if $status_test == "success_or_not_modified" then
          (.response.status >= 200 and .response.status < 300) or .response.status == 304
        else .response.status == ($status_test | tonumber)
        end)
     end)
  ' <<<"$event" >/dev/null; then
    echo "FAIL $label: latest exchange did not match the expected protocol/status: $event" >&2
    return 1
  fi

  echo "PASS $label — fresh requestId=$request_id"
  jq '{observedAt: .meta.observedAt, requestId, request: {method: .request.method, url: .request.url, userAgent: (.request.headers["User-Agent"] // .request.headers["user-agent"])}, response: {status: .response.status}, protocol, frames}' <<<"$event"
}

run_http_action() {
  local label="$1" url_fragment="$2" protocol="$3" status_test="$4" previous_id
  previous_id="$(latest_request_id "$url_fragment")"
  echo
  read -r -p "Tap '$label' in the $PLATFORM sample app, wait for its result, then press Enter: "
  assert_capture "$label" "$url_fragment" "$previous_id" "$protocol" "$status_test"
}

run_http_action "HTTP/1.1 Call" "lynx-sample-http1" "HTTP/1.1" "200"
run_http_action "HTTP/2 Call" "lynx-sample-http2" "HTTP/2" "success_or_not_modified"

previous_ws_id="$(latest_request_id 'ws.postman-echo.com')"
echo
read -r -p "Tap WebSocket Start, confirm the echoed ping, then tap WebSocket Close and press Enter: "
assert_capture "WebSocket" "ws.postman-echo.com" "$previous_ws_id" "WebSocket" "101" "websocket"

echo
echo "NETWORK_CAPTURE_SMOKE_OK platform=$PLATFORM target=$TARGET app=$APP_ID"
