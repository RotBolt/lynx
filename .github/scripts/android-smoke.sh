#!/usr/bin/env bash
set -euo pipefail
set -x

timeout 30 adb wait-for-device
timeout 30 adb shell getprop sys.boot_completed | grep -q 1

APK="dummyapp/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
timeout 60 adb install -r "$APK"
timeout 30 adb shell am force-stop dev.lynx.dummyapp
timeout 30 adb shell monkey -p dev.lynx.dummyapp 1 >/dev/null
timeout 30 adb shell uiautomator dump /sdcard/lynx-window.xml >/dev/null
UI="$(timeout 10 adb shell cat /sdcard/lynx-window.xml)"
for label in "HTTP/1.1 Call" "HTTP/2 Call" "WebSocket Start" "WebSocket Close"; do
  grep -Fq "text=\"$label\"" <<<"$UI"
done
echo "ANDROID_APP_LAUNCH_OK package=dev.lynx.dummyapp buttons=HTTP/1.1,HTTP/2,WebSocketStart,WebSocketClose"
