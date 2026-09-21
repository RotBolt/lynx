#!/usr/bin/env bash
set -euo pipefail

python3 dummyapp/test-server/server.py >/tmp/lynx-android-fixture-server.log 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

adb wait-for-device
adb shell getprop sys.boot_completed | grep -q 1

APK="dummyapp/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
adb install -r "$APK"
adb shell am force-stop dev.lynx.dummyapp
adb shell monkey -p dev.lynx.dummyapp 1 >/dev/null

for _ in {1..30}; do
  if adb shell pidof dev.lynx.dummyapp | grep -q '[0-9]'; then
    break
  fi
  sleep 1
done

PID="$(adb shell pidof dev.lynx.dummyapp | tr -d '\r')"
test -n "$PID"
adb shell run-as dev.lynx.dummyapp test -f databases/dummyapp.db

for _ in {1..45}; do
  ROWS="$(adb shell run-as dev.lynx.dummyapp sqlite3 databases/dummyapp.db \
    'select count(*) from network_events;' 2>/dev/null | tr -d '\r' || true)"
  if [[ "$ROWS" =~ ^[3-9][0-9]*$ ]]; then
    break
  fi
  sleep 1
done
test "${ROWS:-0}" -ge 3

echo "ANDROID_UI_INTEGRATION_OK pid=$PID database=databases/dummyapp.db rows=$ROWS"
