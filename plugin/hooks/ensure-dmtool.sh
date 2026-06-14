#!/usr/bin/env bash
# SessionStart hook: ensure the `dmtool` native CLI is on PATH for this Claude Code session.
#
#   • PRODUCTION — download the per-OS binary from the dmtool-releases GitHub Release (anonymous,
#     public mirror), checksum-verify against SHA256SUMS, cache in CLAUDE_PLUGIN_DATA, add to PATH.
#   • DEV — if an explicit override or a sibling source-repo build is present, use that instead, so the
#     plugin is testable from a checkout (`claude --plugin-dir ./plugin`) before any release exists.
#
# Never hard-fails the session: on any download/verify problem it warns to stderr and exits 0 (the
# session continues; `dmtool` just won't be on PATH). The runtime-eval verbs are intentionally absent
# from the native binary (model eval / rule test / model compute are JVM-only — see the project docs).
set -uo pipefail

VERSION="v0.1.0"
REPO="mbackschat/dmtool-releases"
DATA="${CLAUDE_PLUGIN_DATA:-$HOME/.cache/dmtool-plugin}"
BIN_DIR="$DATA/bin"
DEST="$BIN_DIR/dmtool"
mkdir -p "$BIN_DIR"

warn() { echo "dmtool plugin: $*" >&2; }
put_on_path() { [[ -n "${CLAUDE_ENV_FILE:-}" ]] && echo "export PATH=\"$BIN_DIR:\$PATH\"" >> "$CLAUDE_ENV_FILE"; }

# --- DEV fallback: explicit override, then a sibling source-repo native build -------------------------
dev_binary() {
  if [[ -n "${RK_DMTOOL_LOCAL:-}" && -x "${RK_DMTOOL_LOCAL}" ]]; then echo "${RK_DMTOOL_LOCAL}"; return 0; fi
  local guess="${CLAUDE_PLUGIN_ROOT:-.}/../cli/build/native/nativeCompile/dmtool"
  if [[ -x "$guess" ]]; then
    local dir; dir="$(cd "$(dirname "$guess")" && pwd)"   # absolute path
    echo "$dir/dmtool"; return 0
  fi
  return 1
}
if dev="$(dev_binary)"; then
  ln -sf "$dev" "$DEST"; put_on_path
  exit 0
fi

# --- PRODUCTION: download from the public mirror release ----------------------------------------------
case "$(uname -s)/$(uname -m)" in
  Darwin/arm64)  asset="dmtool-macos-arm64" ;;
  Linux/x86_64)  asset="dmtool-linux-x64"   ;;
  *) warn "no prebuilt binary for $(uname -s)/$(uname -m) yet — install dmtool manually"; exit 0 ;;
esac

if [[ ! -x "$DEST" ]]; then
  base="https://github.com/$REPO/releases/download/$VERSION"
  curl -fsSL "$base/$asset" -o "$DEST" || { warn "download failed: $base/$asset"; rm -f "$DEST"; exit 0; }
  chmod +x "$DEST"
  if curl -fsSL "$base/SHA256SUMS" -o "$DATA/SHA256SUMS" 2>/dev/null; then
    expected="$(awk -v a="$asset" '$2==a || $2=="*"a {print $1}' "$DATA/SHA256SUMS")"
    actual="$(shasum -a 256 "$DEST" | awk '{print $1}')"
    if [[ -n "$expected" && "$expected" != "$actual" ]]; then
      warn "checksum mismatch for $asset — refusing the binary"; rm -f "$DEST"; exit 1
    fi
  fi
fi
put_on_path
