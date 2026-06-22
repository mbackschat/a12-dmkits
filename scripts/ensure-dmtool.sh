#!/usr/bin/env bash
# On-demand installer — fetch the `dmtool` native CLI when the agent needs it. The bundled skill runs THIS
# script (it is NOT a session hook): when `dmtool` is `command not found`, the skill instructs the agent to
# run it, so the binary is delivered the moment it's first needed — including the very session the plugin was
# installed in (a SessionStart hook can't do that — it never fires at `/plugin install`).
#
# CANONICAL SOURCE. This one script serves BOTH plugins (Claude Code and Codex); it is copy-synced into
# each plugin's skill dir by scripts/sync-plugin-assets.sh (never hand-edit the copies — a CI --check fails
# on drift), so it sits beside SKILL.md and the agent can run it via the skill's directory. It is
# agent-agnostic: it reads whichever env vars the host provides (CLAUDE_* or PLUGIN_*/CODEX_*), so the same
# downloader works unmodified under either agent.
#
#   • PRODUCTION — download the per-OS binary from the a12-dmtool-releases GitHub Release (anonymous,
#     public mirror), checksum-verify against SHA256SUMS, cache in the plugin data dir. Prints the
#     absolute path on success — a script the agent runs mid-session cannot inject PATH, so the skill
#     tells the agent to invoke `dmtool` by the printed path (also exported via the host env-file if one
#     is offered, harmless best-effort).
#   • DEV — if an explicit override or a sibling source-repo build is present, use that instead, so the
#     plugin is testable from a checkout before any release exists.
#
# VERSION-AWARE CACHE (the cache key IS the version). The binary lives at $DATA/bin/$VERSION/dmtool, so a
# bumped pin resolves a NEW path and re-downloads — a cached older binary can never shadow a newer release.
# (A version-less fixed path with a "download only if absent" guard caused exactly that: an upgraded plugin
# kept serving the stale binary forever, because the bumped VERSION still found the old file present.) Old
# version dirs + the legacy fixed-path binary are pruned once the new one verifies. Guarded by
# scripts/selftest-ensure-dmtool.sh (EnsureDmtoolCacheTest).
#
# Never hard-fails the session: on any download/verify problem it warns to stderr and exits 0 (the
# session continues; `dmtool` just won't be on PATH). The runtime-eval verbs are intentionally absent
# from the native binary (model eval / rule test / model compute are JVM-only — see the project docs).
set -uo pipefail

VERSION="v0.7.0"
REPO="mbackschat/a12-dmtool-releases"
# Host-provided dirs differ by agent: Claude Code sets CLAUDE_PLUGIN_*, Codex sets PLUGIN_* — accept both.
DATA="${CLAUDE_PLUGIN_DATA:-${PLUGIN_DATA:-$HOME/.cache/dmtool-plugin}}"
PLUGIN_ROOT_DIR="${CLAUDE_PLUGIN_ROOT:-${PLUGIN_ROOT:-.}}"
ENV_FILE="${CLAUDE_ENV_FILE:-${CODEX_ENV_FILE:-}}"
# The cache key is the VERSION: each release lives at its own path, so an older cached binary cannot shadow
# a newer pin. put_on_path exports THIS version-specific dir.
BIN_ROOT="$DATA/bin"
BIN_DIR="$BIN_ROOT/$VERSION"
DEST="$BIN_DIR/dmtool"
STABLE="$BIN_ROOT/dmtool"   # self-correcting symlink → the current version's binary (deterministic path)
mkdir -p "$BIN_DIR"

warn() { echo "dmtool plugin: $*" >&2; }
# Best-effort PATH via the host env-file if one is offered (honored only by some hosts / some events). The
# binary is always at the deterministic $STABLE path, so the agent can invoke `dmtool` there directly — the
# skill instructs exactly that, because a script the agent runs mid-session can't reliably inject PATH.
put_on_path() { [[ -n "$ENV_FILE" ]] && echo "export PATH=\"$BIN_DIR:\$PATH\"" >> "$ENV_FILE"; }
# Repoint the stable path at the resolved binary (tracks the pinned VERSION, never stale across a bump — it
# also replaces any legacy fixed-path binary left by an older install), export PATH best-effort, and PRINT
# the absolute path so the agent can run dmtool there even when PATH injection didn't take.
finalize() { ln -sf "$DEST" "$STABLE"; put_on_path; echo "dmtool ready: $STABLE"; }
# Bound disk to the current version: drop sibling version dirs + the legacy version-less SHA256SUMS. The old
# fixed-path binary at $STABLE is replaced by the symlink in finalize(), so an upgrade self-heals its disk.
prune_stale_cache() {
  rm -f "$DATA/SHA256SUMS" 2>/dev/null || true
  for d in "$BIN_ROOT"/*/; do
    [[ -d "$d" && "$d" != "$BIN_DIR/" ]] && rm -rf "$d"
  done
}

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
  ln -sf "$dev" "$DEST"; finalize
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
  if curl -fsSL "$base/SHA256SUMS" -o "$BIN_DIR/SHA256SUMS" 2>/dev/null; then
    expected="$(awk -v a="$asset" '$2==a || $2=="*"a {print $1}' "$BIN_DIR/SHA256SUMS")"
    actual="$(shasum -a 256 "$DEST" | awk '{print $1}')"
    if [[ -n "$expected" && "$expected" != "$actual" ]]; then
      warn "checksum mismatch for $asset — refusing the binary"; rm -f "$DEST"; exit 1
    fi
  fi
  prune_stale_cache
fi

# Defense-in-depth (loud, never fatal): the resolved binary must self-report the pinned version. A mismatch
# means the release tag's asset disagrees with the pin (a mispublished release) — surface it, never run a
# skewed binary silently. This is the check that would have caught the v0.1.0-pin bug when the agent installs it.
have="$("$DEST" --version 2>/dev/null | awk 'NR==1{print $2}')"
[[ -n "$have" && "$have" != "${VERSION#v}" ]] \
  && warn "binary self-reports $have but the plugin pinned ${VERSION#v} — possible mispublished release tag"

finalize
