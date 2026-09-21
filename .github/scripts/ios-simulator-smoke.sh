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
APP="dummyapp/iosApp/build/Debug-iphonesimulator/LynxDummyApp.app"

xcrun simctl install "$DEVICE" "$APP"
xcrun simctl terminate "$DEVICE" dev.lynx.dummyapp >/dev/null 2>&1 || true
xcrun simctl launch "$DEVICE" dev.lynx.dummyapp
sleep 5

PID="$(xcrun simctl spawn "$DEVICE" ps -A 2>/dev/null | awk '/DummyApp/ {print $1; exit}' || true)"
CONTAINER="$(xcrun simctl get_app_container "$DEVICE" dev.lynx.dummyapp data)"
DB="$CONTAINER/Documents/dummyapp.db"
test -f "$DB"

for _ in {1..45}; do
  ROWS="$(sqlite3 "$DB" 'select count(*) from network_events;' 2>/dev/null || true)"
  if [[ "$ROWS" =~ ^[3-9][0-9]*$ ]]; then
    break
  fi
  sleep 1
done
test "${ROWS:-0}" -ge 3

echo "IOS_UI_INTEGRATION_OK device=$DEVICE database=$DB pid=${PID:-unknown} rows=$ROWS"
