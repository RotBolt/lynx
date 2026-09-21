#!/usr/bin/env bash
set -euo pipefail

BIN=${1:?usage: package-linux-native.sh /path/to/lynx.kexe output.tar.gz}
ARCHIVE=${2:?usage: package-linux-native.sh /path/to/lynx.kexe output.tar.gz}
test -x "$BIN"

ROOT=$(mktemp -d "${TMPDIR:-/tmp}/lynx-linux-package.XXXXXX")
trap 'rm -rf "$ROOT"' EXIT
DIST="$ROOT/dist"
mkdir -p "$DIST/lynx-skill"
cp "$BIN" "$DIST/lynx"

RUNTIME_DIR=${LYNX_LINUX_RUNTIME_LIB_DIR:-}
if [ -n "$RUNTIME_DIR" ]; then
  for library in libssl.so.3 libcrypto.so.3 libnghttp2.so.14; do
    test -f "$RUNTIME_DIR/$library"
    cp "$RUNTIME_DIR/$library" "$DIST/$library"
  done
else
  command -v curl >/dev/null
  command -v dpkg-deb >/dev/null
  curl -fsSL -o "$ROOT/libssl3.deb" \
    https://deb.debian.org/debian/pool/main/o/openssl/libssl3_3.0.17-1~deb12u2_amd64.deb
  curl -fsSL -o "$ROOT/libnghttp2.deb" \
    https://deb.debian.org/debian/pool/main/n/nghttp2/libnghttp2-14_1.52.0-1+deb12u3_amd64.deb
  dpkg-deb -x "$ROOT/libssl3.deb" "$ROOT/runtime"
  dpkg-deb -x "$ROOT/libnghttp2.deb" "$ROOT/runtime"
  for library in libssl.so.3 libcrypto.so.3 libnghttp2.so.14; do
    cp "$ROOT/runtime/usr/lib/x86_64-linux-gnu/$library" "$DIST/$library"
  done
fi

command -v patchelf >/dev/null
patchelf --set-rpath '$ORIGIN' "$DIST/lynx"
cp docs/agent-skill/SKILL.md "$DIST/lynx-skill/SKILL.md"
mkdir -p "$(dirname "$ARCHIVE")"
tar -czf "$ARCHIVE" -C "$DIST" \
  lynx libssl.so.3 libcrypto.so.3 libnghttp2.so.14 lynx-skill
