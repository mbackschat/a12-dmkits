#!/usr/bin/env bash
# SessionStart hook — ensure the `dmtool` native CLI is on PATH for this agent session.
#
# CANONICAL SOURCE. This one script serves BOTH plugins (Claude Code and Codex); it is copy-synced into
# each plugin's hooks/ by scripts/sync-plugin-assets.sh (never hand-edit the copies — a CI --check fails
# on drift). It is agent-agnostic: it reads whichever env vars the host provides (CLAUDE_* or PLUGIN_*/
# CODEX_*), so the same downloader works unmodified under either agent.
#
#   • PRODUCTION — download the per-OS binary from the dmtool-releases GitHub Release (anonymous,
#     public mirror), checksum-verify against SHA256SUMS, cache in the plugin data dir, add to PATH.
#   • DEV — if an explicit override or a sibling source-repo build is present, use that instead, so the
#     plugin is testable from a checkout before any release exists.
#
# Never hard-fails the session: on any download/verify problem it warns to stderr and exits 0 (the
# session continues; `dmtool` just won't be on PATH). The runtime-eval verbs are intentionally absent
# from the native binary (model eval / rule test / model compute are JVM-only — see the project docs).
set -uo pipefail

VERSION="v0.1.0"
REPO="mbackschat/dmtool-releases"
# Host-provided dirs differ by agent: Claude Code sets CLAUDE_PLUGIN_*, Codex sets PLUGIN_* — accept both.
DATA="${CLAUDE_PLUGIN_DATA:-${PLUGIN_DATA:-$HOME/.cache/dmtool-plugin}}"
PLUGIN_ROOT_DIR="${CLAUDE_PLUGIN_ROOT:-${PLUGIN_ROOT:-.}}"
ENV_FILE="${CLAUDE_ENV_FILE:-${CODEX_ENV_FILE:-}}"
BIN_DIR="$DATA/bin"
DEST="$BIN_DIR/dmtool"
mkdir -p "$BIN_DIR"

warn() { echo "dmtool plugin: $*" >&2; }
# Inject PATH via the host's session env-file if it offers one; the binary is always at $DEST regardless,
# so an agent whose hooks can't set PATH can still invoke it by that deterministic path (see AGENTS.md).
put_on_path() { [[ -n "$ENV_FILE" ]] && echo "export PATH=\"$BIN_DIR:\$PATH\"" >> "$ENV_FILE"; }

# --- DEV fallback: explicit override, then a sibling source-repo native build -------------------------
dev_binary() {
  if [[ -n "${RK_DMTOOL_LOCAL:-}" && -x "${RK_DMTOOL_LOCAL}" ]]; then echo "${RK_DMTOOL_LOCAL}"; return 0; fi
  local guess="${PLUGIN_ROOT_DIR}/../cli/build/native/nativeCompile/dmtool"
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
