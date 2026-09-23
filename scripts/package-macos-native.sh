#!/usr/bin/env bash
set -euo pipefail

BIN=${1:?usage: package-macos-native.sh /path/to/lynx.kexe output.tar.gz [relay-dir]}
ARCHIVE=${2:?usage: package-macos-native.sh /path/to/lynx.kexe output.tar.gz [relay-dir]}
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RELAY_DIR="${3:-${LYNX_ANDROID_RELAY_DIR:-$ROOT/build/android-relay-m1-1-08}}"

[[ "$BIN" = /* ]] && BIN="$(cd "$(dirname "$BIN")" && pwd)/$(basename "$BIN")"
[[ -x "$BIN" ]] || { echo "native executable is not runnable: $BIN" >&2; exit 2; }
[[ "$(uname -s)" == Darwin ]] || { echo "macOS packaging requires Darwin" >&2; exit 2; }

for abi in arm64-v8a x86_64; do
  [[ -x "$RELAY_DIR/$abi/lynx-android-relay" ]] || {
    echo "missing relay helper: $RELAY_DIR/$abi/lynx-android-relay" >&2
    echo "build with scripts/build-android-relay.sh or set LYNX_ANDROID_RELAY_DIR" >&2
    exit 2
  }
done

command -v brew >/dev/null || { echo "Homebrew is required to locate native runtime libraries" >&2; exit 2; }
command -v install_name_tool >/dev/null || { echo "install_name_tool is required" >&2; exit 2; }
command -v codesign >/dev/null || { echo "codesign is required" >&2; exit 2; }
command -v shasum >/dev/null || { echo "shasum is required" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 2; }
[[ -f "$RELAY_DIR/build.json" ]] || { echo "missing relay build manifest: $RELAY_DIR/build.json" >&2; exit 2; }

SSL_PREFIX="${LYNX_OPENSSL_PREFIX:-$(brew --prefix openssl@3)}"
H2_PREFIX="${LYNX_NGHTTP2_PREFIX:-$(brew --prefix libnghttp2)}"
BROTLI_PREFIX="${LYNX_BROTLI_PREFIX:-$(brew --prefix brotli)}"
for library in \
  "$SSL_PREFIX/lib/libssl.3.dylib" \
  "$SSL_PREFIX/lib/libcrypto.3.dylib" \
  "$H2_PREFIX/lib/libnghttp2.14.dylib" \
  "$BROTLI_PREFIX/lib/libbrotlidec.1.dylib" \
  "$BROTLI_PREFIX/lib/libbrotlicommon.1.dylib"; do
  [[ -f "$library" ]] || { echo "missing runtime library: $library" >&2; exit 2; }
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/lynx-macos-package.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
DIST="$WORK/dist"
mkdir -p "$DIST/lynx-skill" "$DIST/android-relay/arm64-v8a" "$DIST/android-relay/x86_64"
cp "$BIN" "$DIST/lynx"
cp "$RELAY_DIR/build.json" "$DIST/android-relay/build.json"
cp "$RELAY_DIR/arm64-v8a/lynx-android-relay" "$DIST/android-relay/arm64-v8a/lynx-android-relay"
cp "$RELAY_DIR/x86_64/lynx-android-relay" "$DIST/android-relay/x86_64/lynx-android-relay"
chmod 755 "$DIST/lynx" "$DIST/android-relay"/*/lynx-android-relay
cp "$ROOT/docs/agent-skill/SKILL.md" "$DIST/lynx-skill/SKILL.md"

cp "$SSL_PREFIX/lib/libssl.3.dylib" "$DIST/"
cp "$SSL_PREFIX/lib/libcrypto.3.dylib" "$DIST/"
cp "$H2_PREFIX/lib/libnghttp2.14.dylib" "$DIST/"
cp "$BROTLI_PREFIX/lib/libbrotlidec.1.dylib" "$DIST/"
cp "$BROTLI_PREFIX/lib/libbrotlicommon.1.dylib" "$DIST/"

install_name_tool -change "$SSL_PREFIX/lib/libssl.3.dylib" '@rpath/libssl.3.dylib' "$DIST/lynx"
install_name_tool -change "$SSL_PREFIX/lib/libcrypto.3.dylib" '@rpath/libcrypto.3.dylib' "$DIST/lynx"
install_name_tool -change "$H2_PREFIX/lib/libnghttp2.14.dylib" '@rpath/libnghttp2.14.dylib' "$DIST/lynx"
install_name_tool -change "$BROTLI_PREFIX/lib/libbrotlidec.1.dylib" '@rpath/libbrotlidec.1.dylib' "$DIST/lynx"
install_name_tool -id '@rpath/libbrotlidec.1.dylib' "$DIST/libbrotlidec.1.dylib"
install_name_tool -id '@rpath/libbrotlicommon.1.dylib' "$DIST/libbrotlicommon.1.dylib"
install_name_tool -add_rpath '@executable_path' "$DIST/lynx"
codesign --force --sign - "$DIST"/*.dylib "$DIST/lynx"

LYNX_VERSION="$("$DIST/lynx" --version 2>/dev/null | tr -d '\r\n' || true)"
LYNX_VERSION_JSON="$(printf '%s' "$LYNX_VERSION" | python3 -c 'import json, sys; print(json.dumps(sys.stdin.read()))')"
RELAY_PROTOCOL=1
cat > "$DIST/lynx-bundle.json" <<EOF
{
  "schema_version": "lynx.bundle.v1",
  "platform": "macos-arm64",
  "executable": "lynx",
  "version": $LYNX_VERSION_JSON,
  "relay_protocol": $RELAY_PROTOCOL,
  "relay_build_manifest": "android-relay/build.json",
  "relay_helpers": [
    {"abi":"arm64-v8a","path":"android-relay/arm64-v8a/lynx-android-relay","sha256":"$(shasum -a 256 "$DIST/android-relay/arm64-v8a/lynx-android-relay" | awk '{print $1}')"},
    {"abi":"x86_64","path":"android-relay/x86_64/lynx-android-relay","sha256":"$(shasum -a 256 "$DIST/android-relay/x86_64/lynx-android-relay" | awk '{print $1}')"}
  ]
}
EOF

mkdir -p "$(dirname "$ARCHIVE")"
tar -czf "$ARCHIVE" -C "$DIST" \
  lynx libssl.3.dylib libcrypto.3.dylib libnghttp2.14.dylib \
  libbrotlidec.1.dylib libbrotlicommon.1.dylib android-relay lynx-skill lynx-bundle.json
shasum -a 256 "$ARCHIVE" > "$ARCHIVE.sha256"
"$ROOT/scripts/verify-macos-native-package.sh" "$ARCHIVE"
printf 'LYNX_MACOS_PACKAGE_OK archive=%s\n' "$ARCHIVE"
