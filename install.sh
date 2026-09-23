#!/usr/bin/env bash
set -euo pipefail

# Install the released Lynx executable and bundled agent skill.
#
# Remote install:
#   curl -fsSL https://raw.githubusercontent.com/RotBolt/lynx/main/install.sh | bash
#
# Local checkout:
#   ./install.sh --local
#
# A local checkout is detected automatically. Use --remote to force release
# download, or pass a release tag after --remote.

readonly REPOSITORY="RotBolt/lynx"
readonly INSTALL_DIR="${HOME}/.local/bin"

fail() {
  printf 'lynx install: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage: install.sh [--local | --remote [release-tag]]

  --local                 Install the native executable from this checkout.
  --remote [release-tag]  Download the release archive; default is latest.
EOF
}

command -v tar >/dev/null 2>&1 || fail "tar is required"

mode="auto"
release_tag=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --local|-l|--build)
      mode="local"
      shift
      ;;
    --remote|-r|--download)
      mode="remote"
      shift
      if [[ $# -gt 0 && "$1" != --* ]]; then
        release_tag="$1"
        shift
      fi
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument '$1' (use --help)"
      ;;
  esac
done

repo_root="$PWD"
if [[ "$mode" == "auto" ]]; then
  if [[ -f "$repo_root/gradlew" && -f "$repo_root/apps/cli/build.gradle.kts" &&
        -f "$repo_root/settings.gradle.kts" ]]; then
    mode="local"
    printf 'Local Lynx checkout detected; using local install mode.\n'
  else
    mode="remote"
  fi
fi

tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/lynx-install.XXXXXX")"
cleanup() { rm -rf "$tmp_dir"; }
trap cleanup EXIT

if [[ "$mode" == "local" ]]; then
  [[ -f "$repo_root/gradlew" && -f "$repo_root/apps/cli/build.gradle.kts" ]] ||
    fail "local mode must run from the Lynx repository root"

  os="$(uname -s)"
  arch="$(uname -m)"
  case "${os}:${arch}" in
    Darwin:arm64|Darwin:aarch64)
      target="macosArm64"
      ;;
    Linux:x86_64|Linux:amd64)
      target="linuxX64"
      ;;
    *)
      fail "local native install is unsupported on $os/$arch"
      ;;
  esac

  binary="$repo_root/apps/cli/build/bin/${target}/releaseExecutable/lynx.kexe"
  if [[ ! -x "$binary" ]]; then
    printf 'Building the local %s executable...\n' "$target"
    "$repo_root/gradlew" ":apps:cli:linkReleaseExecutable${target^}" --no-daemon
  fi
  [[ -x "$binary" ]] || fail "local build did not produce $binary"
  skill_file="$repo_root/docs/agent-skill/SKILL.md"
  [[ -f "$skill_file" ]] || fail "missing bundled skill: $skill_file"

  mkdir -p "$INSTALL_DIR"
  install -m 0755 "$binary" "$INSTALL_DIR/lynx"
  rm -rf "$INSTALL_DIR/lynx-skill"
  mkdir -p "$INSTALL_DIR/lynx-skill"
  cp "$skill_file" "$INSTALL_DIR/lynx-skill/SKILL.md"
else
  command -v curl >/dev/null 2>&1 || fail "curl is required for remote install"

  os="$(uname -s)"
  arch="$(uname -m)"
  case "${os}:${arch}" in
    Darwin:arm64|Darwin:aarch64)
      artifact="lynx-macos-arm64.tar.gz"
      ;;
    Linux:x86_64|Linux:amd64)
      artifact="lynx-linux-x64.tar.gz"
      ;;
    *)
      fail "unsupported host $os/$arch; download a supported archive from https://github.com/$REPOSITORY/releases"
      ;;
  esac

  archive="${tmp_dir}/${artifact}"
  if [[ -n "$release_tag" ]]; then
    base_url="https://github.com/$REPOSITORY/releases/download/$release_tag"
  else
    base_url="https://github.com/$REPOSITORY/releases/latest/download"
  fi
  printf 'Downloading Lynx%s (%s)\n' "${release_tag:+ $release_tag}" "$artifact"
  if ! curl -fL "$base_url/$artifact" -o "$archive"; then
    fail "release download failed. Publish a release asset named $artifact, or run ./install.sh --local from a Lynx checkout"
  fi

  checksum="${tmp_dir}/${artifact}.sha256"
  if curl -fsSL "$base_url/${artifact}.sha256" -o "$checksum"; then
    if command -v shasum >/dev/null 2>&1; then
      (cd "$tmp_dir" && shasum -a 256 -c "${artifact}.sha256")
    elif command -v sha256sum >/dev/null 2>&1; then
      (cd "$tmp_dir" && sha256sum -c "${artifact}.sha256")
    else
      fail "checksum file was published, but no SHA-256 verifier is available"
    fi
  else
    printf 'Warning: release has no checksum file; continuing without checksum verification.\n' >&2
  fi

  extracted="$tmp_dir/extracted"
  mkdir -p "$extracted"
  tar -xzf "$archive" -C "$extracted"
  binary="$(find "$extracted" -type f -name lynx -perm -u+x -print -quit)"
  [[ -n "$binary" ]] || fail "release archive does not contain an executable named lynx"
  skill_file="$(find "$extracted" -type f -path '*/lynx-skill/SKILL.md' -print -quit)"
  [[ -n "$skill_file" ]] || fail "release archive does not contain lynx-skill/SKILL.md"

  mkdir -p "$INSTALL_DIR"
  install -m 0755 "$binary" "$INSTALL_DIR/lynx"
  rm -rf "$INSTALL_DIR/lynx-skill"
  mkdir -p "$INSTALL_DIR/lynx-skill"
  cp "$skill_file" "$INSTALL_DIR/lynx-skill/SKILL.md"
fi

profile=""
case "${SHELL##*/}" in
  zsh) profile="${HOME}/.zshrc" ;;
  bash) profile="${HOME}/.bashrc" ;;
esac
if [[ -n "$profile" ]]; then
  touch "$profile"
  path_line='export PATH="$HOME/.local/bin:$PATH"'
  if ! grep -Fqx "$path_line" "$profile"; then
    printf '\n%s\n' "$path_line" >> "$profile"
  fi
fi

printf '\nLynx installed successfully.\n'
printf '  executable: %s/lynx\n' "$INSTALL_DIR"
printf '  agent skill: %s/lynx-skill/SKILL.md\n' "$INSTALL_DIR"
if [[ -n "$profile" ]]; then
  printf '  PATH profile: %s\n' "$profile"
  printf 'Open a new terminal, then run: lynx --version\n'
else
  printf 'Add %s to PATH, then run: lynx --version\n' "$INSTALL_DIR"
fi
