#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${1:-$ROOT/build/attribution-probes}"
MAC_PROBE="$ROOT/tests/attribution/macos-owner-probe.c"
ANDROID_PROBE="$ROOT/tests/attribution/android-owner-probe.c"

mkdir -p "$OUT_DIR"

[[ -f "$MAC_PROBE" ]] || { echo "missing macOS ownership probe: $MAC_PROBE" >&2; exit 1; }
[[ -f "$ANDROID_PROBE" ]] || { echo "missing Android ownership probe: $ANDROID_PROBE" >&2; exit 1; }

CLANG="$(xcrun --find clang)"
MACOS_SDK="$(xcrun --sdk macosx --show-sdk-path)"
"$CLANG" -isysroot "$MACOS_SDK" -Wall -Wextra -Werror -std=c11 "$MAC_PROBE" -o "$OUT_DIR/macos-owner-probe"
"$OUT_DIR/macos-owner-probe" --self-test > "$OUT_DIR/macos-self-test.json"
jq -e '.schema_version == "lynx.owner-probe.v1" and .platform == "macos" and .self_test == true' \
  "$OUT_DIR/macos-self-test.json" >/dev/null
"$CLANG" -isysroot "$MACOS_SDK" -Wall -Wextra -Werror -std=c11 -fsyntax-only "$ANDROID_PROBE"

NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_ROOT" ]]; then
  NDK_ROOT="$(find "$HOME/Library/Android/sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi
ANDROID_CLANG=""
if [[ -n "$NDK_ROOT" ]]; then
  for host_tag in darwin-arm64 darwin-x86_64; do
    candidate="$NDK_ROOT/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android21-clang"
    if [[ -x "$candidate" ]]; then ANDROID_CLANG="$candidate"; break; fi
  done
fi
if [[ -z "$ANDROID_CLANG" ]]; then
  jq -n --arg source "$ANDROID_PROBE" \
    '{schema_version:"lynx.owner-probe.v1",platform:"android",status:"blocked",reason:"Android NDK is not installed; set ANDROID_NDK_HOME to a supported NDK before building the bounded relay probe.",source:$source}' \
    > "$OUT_DIR/android-build.json"
  echo "ATTRIBUTION_PROBES_PARTIAL_OK output=$OUT_DIR android=blocked_missing_ndk"
  exit 0
fi

"$ANDROID_CLANG" \
  -Wall -Wextra -Werror -std=c11 "$ANDROID_PROBE" -o "$OUT_DIR/android-owner-probe"
NDK_REVISION="$(sed -n 's/^Pkg.Revision = //p' "$NDK_ROOT/source.properties" | head -n 1)"
ANDROID_CLANG_VERSION="$("$ANDROID_CLANG" --version | head -n 1)"
jq -n --arg ndk "$NDK_ROOT" --arg ndk_revision "$NDK_REVISION" --arg compiler "$ANDROID_CLANG_VERSION" \
  '{schema_version:"lynx.owner-probe.v1",platform:"android",status:"built",ndk:$ndk,ndk_revision:$ndk_revision,compiler:$compiler}' \
  > "$OUT_DIR/android-build.json"
echo "ATTRIBUTION_PROBES_BUILD_OK output=$OUT_DIR"
