#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
../gradlew :shared:linkDebugFrameworkIosSimulatorArm64 --no-daemon

SDK="$(xcrun --sdk iphonesimulator --show-sdk-path)"
SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
FRAMEWORK="$ROOT/shared/build/bin/iosSimulatorArm64/debugFramework"
OUT="$ROOT/iosApp/build/Debug-iphonesimulator/LynxDummyApp.app"
rm -rf "$OUT"
mkdir -p "$OUT/Frameworks"
xcrun swiftc \
  -target "arm64-apple-ios${SDK_VERSION}-simulator" \
  -sdk "$SDK" \
  -F "$FRAMEWORK" \
  -framework DummyShared \
  -framework UIKit \
  -framework Foundation \
  -lsqlite3 \
  "$ROOT/iosApp/DummyApp.swift" \
  -o "$OUT/DummyApp"
cp "$ROOT/iosApp/Info.plist" "$OUT/Info.plist"
codesign --force --sign - "$OUT"
echo "$OUT"
