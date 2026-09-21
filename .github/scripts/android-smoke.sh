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
adb shell am start -n dev.lynx.dummyapp/.MainActivity
sleep 5
adb shell run-as dev.lynx.dummyapp test -s databases/dummyapp.db

PID="$(adb shell pidof dev.lynx.dummyapp | tr -d '\r' || true)"
echo "ANDROID_UI_INTEGRATION_OK pid=${PID:-unknown} database=databases/dummyapp.db"
