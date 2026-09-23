#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: $0 <absolute-lynx-binary> <legacy|scoped> <android-serial> <simulator-udid> <output-directory>" >&2
  exit 2
}

[[ $# -eq 5 ]] || usage
LYNX="$1"
MODE="$2"
ANDROID_SERIAL="$3"
SIMULATOR_UDID="$4"
OUTPUT_DIR="$5"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_APP="dev.lynx.dummyapp"
IOS_APP="dev.lynx.dummyapp"

[[ "$LYNX" = /* && -x "$LYNX" ]] || { echo "Pass an absolute runnable Lynx binary" >&2; exit 2; }
[[ "$MODE" == legacy || "$MODE" == scoped ]] || usage
command -v jq >/dev/null || { echo "jq is required" >&2; exit 2; }
mkdir -p "$OUTPUT_DIR/android" "$OUTPUT_DIR/ios"

printf '%s\n' "$(git -C "$ROOT" rev-parse HEAD)" > "$OUTPUT_DIR/revision.txt"
git -C "$ROOT" status --short > "$OUTPUT_DIR/worktree-status.txt"
shasum -a 256 "$LYNX" > "$OUTPUT_DIR/lynx.sha256"
otool -L "$LYNX" > "$OUTPUT_DIR/lynx.dependencies" 2>&1 || true
shasum -a 256 "$ROOT/dummyapp/iosApp/SampleApp.swift" "$ROOT/dummyapp/shared/src/androidMain/kotlin/dev/lynx/dummyapp/AndroidExchangeStore.kt" > "$OUTPUT_DIR/sample-sources.sha256"
/usr/sbin/networksetup -getwebproxy "Wi-Fi" > "$OUTPUT_DIR/proxy-web-before.txt"
/usr/sbin/networksetup -getsecurewebproxy "Wi-Fi" > "$OUTPUT_DIR/proxy-secure-before.txt"
/usr/sbin/networksetup -getautoproxyurl "Wi-Fi" > "$OUTPUT_DIR/proxy-pac-before.txt"
/usr/sbin/networksetup -getproxyautodiscovery "Wi-Fi" > "$OUTPUT_DIR/proxy-autodiscovery-before.txt"
/usr/sbin/networksetup -getproxybypassdomains "Wi-Fi" > "$OUTPUT_DIR/proxy-bypass-before.txt"
adb reverse --list > "$OUTPUT_DIR/adb-reverse-before.txt" 2>&1 || true
"$LYNX" network ca show --json > "$OUTPUT_DIR/ca.json"

DOCTOR="$("$LYNX" network doctor --json)"
printf '%s\n' "$DOCTOR" > "$OUTPUT_DIR/network-doctor-before.json"
STATUS="$(jq -r '.capabilities.proxyStatus // "unknown"' <<<"$DOCTOR")"
[[ "$STATUS" != running ]] || { echo "Refusing concurrent capture not owned by this harness" >&2; exit 1; }

if [[ "$MODE" == legacy ]]; then
  LYNX="$LYNX" "$ROOT/.github/scripts/network-capture-smoke.sh" android "$ANDROID_SERIAL" "$ANDROID_APP" > "$OUTPUT_DIR/android/network-smoke.txt" 2>&1
  LYNX="$LYNX" "$ROOT/.github/scripts/network-capture-smoke.sh" ios "$SIMULATOR_UDID" "$IOS_APP" > "$OUTPUT_DIR/ios/network-smoke.txt" 2>&1
else
  echo "Scoped command validation is enabled after M1.1-09; legacy capture smoke is intentionally not reused." >&2
  exit 1
fi

"$ROOT/scripts/verify-native-database.sh" "$LYNX" android "$ANDROID_SERIAL" "$ANDROID_APP" databases/dummyapp.db "$OUTPUT_DIR/android"
"$ROOT/scripts/verify-native-database.sh" "$LYNX" ios "$SIMULATOR_UDID" "$IOS_APP" Documents/dummyapp.db "$OUTPUT_DIR/ios"

/usr/sbin/networksetup -getwebproxy "Wi-Fi" > "$OUTPUT_DIR/proxy-web-after.txt"
/usr/sbin/networksetup -getsecurewebproxy "Wi-Fi" > "$OUTPUT_DIR/proxy-secure-after.txt"
cmp -s "$OUTPUT_DIR/proxy-web-before.txt" "$OUTPUT_DIR/proxy-web-after.txt" || { echo "Web proxy was not restored" >&2; exit 1; }
cmp -s "$OUTPUT_DIR/proxy-secure-before.txt" "$OUTPUT_DIR/proxy-secure-after.txt" || { echo "Secure web proxy was not restored" >&2; exit 1; }
echo "NATIVE_INSPECTION_BASELINE_OK mode=$MODE output=$OUTPUT_DIR"
