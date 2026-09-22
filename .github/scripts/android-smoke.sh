#!/usr/bin/env bash
set -euo pipefail
set -x

timeout 30 adb wait-for-device
timeout 30 adb shell getprop sys.boot_completed | grep -q 1

APK="dummyapp/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
timeout 60 adb install -r "$APK"
timeout 30 adb shell am force-stop dev.lynx.dummyapp
timeout 30 adb shell monkey -p dev.lynx.dummyapp 1 >/dev/null
echo "ANDROID_UI_INTEGRATION_OK package=dev.lynx.dummyapp"
