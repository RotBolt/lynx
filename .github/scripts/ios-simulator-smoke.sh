#!/usr/bin/env bash
set -euo pipefail

if ! xcrun simctl list runtimes | grep -q 'iOS'; then
  echo "::warning::No iOS Simulator runtime is installed on this hosted runner; iOS smoke test skipped."
  exit 0
fi

DEVICE="$(xcrun simctl list devices available | awk -F '[()]' '/iPhone 17 Pro/ { print $2; exit }')"
if [[ -z "$DEVICE" ]]; then
  DEVICE="$(xcrun simctl list devices available | awk -F '[()]' '/iPhone/ { print $2; exit }')"
fi
if [[ -z "$DEVICE" ]]; then
  echo "::warning::No available iPhone Simulator device is installed on this hosted runner; iOS smoke test skipped."
  exit 0
fi

SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
SDK_MAJOR="${SDK_VERSION%%.*}"
SDK_MINOR="${SDK_VERSION#*.}"
SDK_MINOR="${SDK_MINOR%%.*}"
if (( SDK_MAJOR < 26 || (SDK_MAJOR == 26 && SDK_MINOR < 4) )); then
  echo "::warning::iOS Simulator SDK $SDK_VERSION lacks the Apple symbols required by the fixture; UI smoke test skipped."
  exit 0
fi

xcrun simctl boot "$DEVICE" >/dev/null 2>&1 || true
xcrun simctl bootstatus "$DEVICE" -b

dummyapp/iosApp/build-simulator.sh
APP="dummyapp/iosApp/build/Debug-iphonesimulator/LynxSampleApp.app"
test -d "$APP"
test -x "$APP/SampleApp"
[[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")" == "dev.lynx.dummyapp" ]]
[[ "$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$APP/Info.plist")" == "SampleApp" ]]

xcrun simctl install "$DEVICE" "$APP"
xcrun simctl terminate "$DEVICE" dev.lynx.dummyapp >/dev/null 2>&1 || true
LAUNCH_OUTPUT="$(xcrun simctl launch "$DEVICE" dev.lynx.dummyapp)"
sleep 5

PID="${LAUNCH_OUTPUT##*: }"
[[ "$PID" =~ ^[0-9]+$ ]]

# The app intentionally makes no requests on launch. Real network capture is
# exercised separately by network-capture-smoke.sh after explicit button taps.
echo "IOS_APP_LAUNCH_OK device=$DEVICE package=dev.lynx.dummyapp pid=$PID"
