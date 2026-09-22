#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
../gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
FRAMEWORK="$ROOT/shared/build/bin/iosSimulatorArm64/debugFramework"
OUT="$ROOT/iosApp/build/Debug-iphonesimulator/LynxSampleApp.app"
rm -rf "$OUT"
mkdir -p "$OUT/Frameworks"
xcrun swiftc \
  -target "arm64-apple-ios${SDK_VERSION}-simulator" \
  -sdk "$SDK" \
  -F "$FRAMEWORK" \
  -framework SampleShared \
  -framework UIKit \
  -framework Foundation \
  -lsqlite3 \
  "$ROOT/iosApp/SampleApp.swift" \
  -o "$OUT/SampleApp"
cp "$ROOT/iosApp/Info.plist" "$OUT/Info.plist"
codesign --force --sign - "$OUT"
echo "$OUT"
