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

CURRENT_STATUS="$("$LYNX" network doctor --json | jq -r '.capabilities.proxyStatus // "stopped"')"
[[ "$CURRENT_STATUS" != running ]] || {
  echo "A network capture is already running; stop it before starting this regression." >&2
  exit 1
}

SESSION_ATTACHED=0
CAPTURE_STARTED=0
cleanup() {
  if (( CAPTURE_STARTED )); then "$LYNX" network stop --json >/dev/null 2>&1 || true; fi
  if (( SESSION_ATTACHED )); then "$LYNX" detach >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT INT TERM

case "$PLATFORM" in
  android)
    command -v adb >/dev/null || { echo "adb must be on PATH (set ANDROID_HOME or ANDROID_SDK_ROOT)" >&2; exit 2; }
    adb -s "$TARGET" shell monkey -p "$APP_ID" 1 >/dev/null
    "$LYNX" attach "$TARGET" "$APP_ID"
    SESSION_ATTACHED=1
    EXPECTED_DEVICE="$TARGET"
    ;;
  ios)
    command -v xcrun >/dev/null || { echo "xcrun is required" >&2; exit 2; }
    command -v lldb >/dev/null || { echo "lldb is required to trigger the sample app's real UI actions" >&2; exit 2; }
    CA_PATH="$("$LYNX" network ca show --json | jq -er '.pemPath')"
    xcrun simctl keychain "$TARGET" add-root-cert "$CA_PATH"
    "$LYNX" attach "ios-simulator:$TARGET" "$APP_ID"
    SESSION_ATTACHED=1
    EXPECTED_DEVICE="ios-simulator:$TARGET"
    ;;
  *) usage ;;
esac

START_RESULT="$("$LYNX" network start --json)"
echo "$START_RESULT" | jq -e '.type == "network_started"' >/dev/null || {
  echo "Lynx did not start capture: $START_RESULT" >&2
  exit 1
}
CAPTURE_STARTED=1

if [[ "$PLATFORM" == android ]]; then
  adb -s "$TARGET" shell am force-stop "$APP_ID"
  adb -s "$TARGET" shell monkey -p "$APP_ID" 1 >/dev/null
else
  xcrun simctl terminate "$TARGET" "$APP_ID" >/dev/null 2>&1 || true
  LAUNCH_RESULT="$(xcrun simctl launch "$TARGET" "$APP_ID")"
  APP_PID="${LAUNCH_RESULT##*: }"
  [[ "$APP_PID" =~ ^[0-9]+$ ]] || { echo "Could not obtain sample app PID from: $LAUNCH_RESULT" >&2; exit 1; }
fi

tap_sample_action() {
  local label="$1"
  if [[ "$PLATFORM" == android ]]; then
    local bounds
    bounds="$(adb -s "$TARGET" shell uiautomator dump /sdcard/lynx-window.xml >/dev/null
      adb -s "$TARGET" shell cat /sdcard/lynx-window.xml | python3 -c '
import sys, xml.etree.ElementTree as ET
label = sys.argv[1]
root = ET.fromstring(sys.stdin.read())
for node in root.iter("node"):
    if node.attrib.get("text") == label and node.attrib.get("clickable") == "true" and node.attrib.get("enabled") == "true":
        print(node.attrib["bounds"])
        break
' "$label")"
    [[ -n "$bounds" ]] || { echo "Could not find enabled Android sample button: $label" >&2; return 1; }
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 < <(sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' <<<"$bounds")
    adb -s "$TARGET" shell input tap "$(((x1 + x2) / 2))" "$(((y1 + y2) / 2))"
  else
    local selector
    case "$label" in
      "HTTP/1.1 Call") selector=callHttp1 ;;
      "HTTP/2 Call") selector=callHttp2 ;;
      "WebSocket Start") selector=startWebSocket ;;
      "WebSocket Close") selector=closeWebSocket ;;
      *) echo "Unknown iOS sample action: $label" >&2; return 2 ;;
    esac
    lldb --batch --no-lldbinit -p "$APP_PID" \
      -o "expr -l objc -O -- [(id)[[UIApplication sharedApplication] delegate] performSelectorOnMainThread:@selector($selector) withObject:nil waitUntilDone:NO]" \
      -o 'process detach' -o 'quit' >/dev/null
  fi
}

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

  if ! jq -e --arg protocol "$protocol" --arg status_test "$status_test" --arg mode "$mode" \
    --arg device "$EXPECTED_DEVICE" --arg package "$APP_ID" --arg url_fragment "$url_fragment" '
    .protocol == $protocol and
    .failure == null and
    .meta.deviceSerial == $device and
    .meta.packageName == $package and
    .meta.processId != null and
    (if $mode == "websocket" then
       (.request.url | startswith("wss://")) and
       .response.status == 101 and
       ([.frames[]?.payload] | any(. == "lynx-sample-ping"))
     else
       (.request.url | startswith("https://")) and
        (if $status_test == "success_or_not_modified" then
          (.response.status >= 200 and .response.status < 300) or .response.status == 304
        else .response.status == ($status_test | tonumber)
        end) and
        (if ($url_fragment | contains("http2")) and .response.status == 200 then
           (try (.response.body | fromjson | has("userId")) catch false)
         else true end)
     end)
  ' <<<"$event" >/dev/null; then
    echo "FAIL $label: latest exchange did not match the expected HTTPS/protocol/status contract: $event" >&2
    return 1
  fi

  echo "PASS $label — fresh requestId=$request_id device=$EXPECTED_DEVICE package=$APP_ID pid=$(jq -r '.meta.processId' <<<"$event")"
  jq '{observedAt: .meta.observedAt, requestId, request: {method: .request.method, url: .request.url, userAgent: (.request.headers["User-Agent"] // .request.headers["user-agent"])}, response: {status: .response.status, body: .response.body}, protocol, frames}' <<<"$event"
}

run_http_action() {
  local label="$1" url_fragment="$2" protocol="$3" status_test="$4" previous_id
  previous_id="$(latest_request_id "$url_fragment")"
  if [[ "$PLATFORM" == ios && "$label" == "HTTP/2 Call" ]]; then
    lldb --batch --no-lldbinit -p "$APP_PID" \
      -o 'expr -l objc -O -- [[NSURLCache sharedURLCache] removeAllCachedResponses]' \
      -o 'process detach' -o 'quit' >/dev/null
  fi
  echo "Triggering real sample-app action: $label"
  tap_sample_action "$label"
  assert_capture "$label" "$url_fragment" "$previous_id" "$protocol" "$status_test"
}

run_http_action "HTTP/1.1 Call" "lynx-sample-http1" "HTTP/1.1" "200"
run_http_action "HTTP/2 Call" "lynx-sample-http2" "HTTP/2" "success_or_not_modified"

previous_ws_id="$(latest_request_id 'ws.postman-echo.com')"
echo "Triggering real sample-app WebSocket start"
tap_sample_action "WebSocket Start"
sleep 2
tap_sample_action "WebSocket Close"
assert_capture "WebSocket" "ws.postman-echo.com" "$previous_ws_id" "WebSocket" "101" "websocket"

echo
echo "NETWORK_CAPTURE_SMOKE_OK platform=$PLATFORM target=$TARGET app=$APP_ID"
