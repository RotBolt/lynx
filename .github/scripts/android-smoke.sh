#!/usr/bin/env bash
set -euo pipefail
set -x

python3 dummyapp/test-server/server.py >/tmp/lynx-android-fixture-server.log 2>&1 &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT

timeout 30 adb wait-for-device
timeout 30 adb shell getprop sys.boot_completed | grep -q 1

APK="dummyapp/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
timeout 60 adb install -r "$APK"
timeout 30 adb shell am force-stop dev.lynx.dummyapp
timeout 30 adb shell am start -n dev.lynx.dummyapp/.MainActivity
sleep 5
timeout 30 adb shell run-as dev.lynx.dummyapp test -s databases/dummyapp.db

PID="$(timeout 15 adb shell pidof dev.lynx.dummyapp | tr -d '\r' || true)"
echo "ANDROID_UI_INTEGRATION_OK pid=${PID:-unknown} database=databases/dummyapp.db"
