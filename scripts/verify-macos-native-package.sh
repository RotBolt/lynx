#!/usr/bin/env bash
set -euo pipefail

ARCHIVE=${1:?usage: verify-macos-native-package.sh /path/to/lynx-macos-arm64.tar.gz}
[[ -f "$ARCHIVE" ]] || { echo "archive not found: $ARCHIVE" >&2; exit 2; }
command -v tar >/dev/null || exit 2
command -v shasum >/dev/null || exit 2
command -v python3 >/dev/null || exit 2

WORK="$(mktemp -d "${TMPDIR:-/tmp}/lynx-macos-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
tar -xzf "$ARCHIVE" -C "$WORK"

for required in lynx lynx-bundle.json lynx-skill/SKILL.md \
  android-relay/build.json \
  libssl.3.dylib libcrypto.3.dylib libnghttp2.14.dylib \
  libbrotlidec.1.dylib libbrotlicommon.1.dylib \
  android-relay/arm64-v8a/lynx-android-relay \
  android-relay/x86_64/lynx-android-relay; do
  [[ -e "$WORK/$required" ]] || { echo "package missing $required" >&2; exit 1; }
done

[[ -x "$WORK/lynx" ]] || { echo "lynx is not executable" >&2; exit 1; }
[[ -x "$WORK/android-relay/arm64-v8a/lynx-android-relay" ]] || { echo "arm64 relay is not executable" >&2; exit 1; }
[[ -x "$WORK/android-relay/x86_64/lynx-android-relay" ]] || { echo "x86_64 relay is not executable" >&2; exit 1; }

python3 - "$WORK/lynx-bundle.json" "$WORK" <<'PY'
import hashlib, json, os, sys
manifest_path, root = sys.argv[1:]
with open(manifest_path, encoding="utf-8") as fh:
    manifest = json.load(fh)
assert manifest.get("schema_version") == "lynx.bundle.v1"
assert manifest.get("platform") == "macos-arm64"
assert manifest.get("executable") == "lynx"
assert manifest.get("relay_protocol") == 1
assert manifest.get("relay_build_manifest") == "android-relay/build.json"
helpers = manifest.get("relay_helpers")
assert {entry.get("abi") for entry in helpers} == {"arm64-v8a", "x86_64"}
for entry in helpers:
    path = entry["path"]
    full = os.path.join(root, path)
    with open(full, "rb") as fh:
        digest = hashlib.sha256(fh.read()).hexdigest()
    assert digest == entry["sha256"], f"hash mismatch: {path}"
with open(os.path.join(root, "android-relay", "build.json"), encoding="utf-8") as fh:
    build = json.load(fh)
assert build.get("schema_version") == "lynx.relay-build.v1"
assert set(build.get("abis", [])) == {"arm64-v8a", "x86_64"}
assert build.get("ndk_revision"), "missing NDK revision"
print("manifest=valid")
PY

if command -v codesign >/dev/null; then
  codesign --verify --deep --strict "$WORK/lynx" >/dev/null
fi
printf 'LYNX_MACOS_PACKAGE_VERIFY_OK archive=%s\n' "$ARCHIVE"
