#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_ROOT="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_ROOT" ]]; then
  NDK_ROOT="$(find "$HOME/Library/Android/sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi
[[ -n "$NDK_ROOT" && -d "$NDK_ROOT" ]] || { echo "ANDROID_NDK_HOME or a standard Android SDK NDK is required" >&2; exit 2; }

OUT="${1:-$ROOT/build/android-relay}"
mkdir -p "$OUT"
for ABI in arm64-v8a x86_64; do
  BUILD="$OUT/$ABI"; mkdir -p "$BUILD"
  case "$ABI" in
    arm64-v8a) TRIPLE="aarch64-linux-android26" ;;
    x86_64) TRIPLE="x86_64-linux-android26" ;;
  esac
  CLANG=""
  for HOST_TAG in darwin-arm64 darwin-x86_64; do
    CANDIDATE="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin/${TRIPLE}-clang"
    if [[ -x "$CANDIDATE" ]]; then CLANG="$CANDIDATE"; break; fi
  done
  [[ -x "$CLANG" ]] || { echo "missing Android NDK compiler: $CLANG" >&2; exit 2; }
  "$CLANG" -std=c11 -Wall -Wextra -Werror -fstack-protector-strong \
    "$ROOT/tools/android-relay/src/main.c" -o "$BUILD/lynx-android-relay"
done
REVISION="$(sed -n 's/^Pkg.Revision = //p' "$NDK_ROOT/source.properties" | head -n 1)"
printf '{"schema_version":"lynx.relay-build.v1","ndk":"%s","ndk_revision":"%s","abis":["arm64-v8a","x86_64"]}\n' "$NDK_ROOT" "$REVISION" > "$OUT/build.json"
echo "ANDROID_RELAY_BUILD_OK output=$OUT ndk_revision=$REVISION"
