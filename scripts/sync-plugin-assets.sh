#!/usr/bin/env bash
# Sync the canonical, single-source plugin assets into EVERY agent plugin tree (Claude Code + Codex).
#
# Two assets are shared verbatim across both plugins and must never be hand-edited in the plugin copies:
#   • the N1 skill          — skill/SKILL.md          (also the N6 material-under-test, read at that path)
#   • the binary downloader — scripts/ensure-dmtool.sh (agent-agnostic SessionStart hook)
#
# The per-agent files (each plugin's manifest + hooks.json + AGENTS.md) are NOT synced — they differ by
# host and are maintained in place. This is the proven "one source → copies, freshness-checked" pattern
# (symlinks don't survive the release-mirror push, and the two hooks.json genuinely differ).
#
#   ./scripts/sync-plugin-assets.sh          # copy canonical → every plugin (regenerate)
#   ./scripts/sync-plugin-assets.sh --check  # verify every copy matches; non-zero if not (CI guard)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SKILL_SRC="$ROOT/skill/SKILL.md"
HOOK_SRC="$ROOT/scripts/ensure-dmtool.sh"
[[ -f "$SKILL_SRC" ]] || { echo "canonical skill not found: $SKILL_SRC" >&2; exit 2; }
[[ -f "$HOOK_SRC"  ]] || { echo "canonical downloader not found: $HOOK_SRC" >&2; exit 2; }

# Each plugin tree that receives the shared assets. Add a row here when a new agent plugin is introduced.
PLUGINS=("$ROOT/plugin" "$ROOT/plugin-codex")

# "<src>|<relative dest under each plugin>"
ASSETS=(
  "$SKILL_SRC|skills/a12-rules/SKILL.md"
  "$HOOK_SRC|hooks/ensure-dmtool.sh"
)

check=0
[[ "${1:-}" == "--check" ]] && check=1
stale=0

for plugin in "${PLUGINS[@]}"; do
  for asset in "${ASSETS[@]}"; do
    src="${asset%%|*}"
    dst="$plugin/${asset##*|}"
    rel="${dst#"$ROOT"/}"
    if [[ "$check" == 1 ]]; then
      if [[ -f "$dst" ]] && diff -q "$src" "$dst" >/dev/null; then
        echo "in sync: $rel"
      else
        echo "STALE: $rel — run ./scripts/sync-plugin-assets.sh" >&2
        stale=1
      fi
    else
      mkdir -p "$(dirname "$dst")"
      cp -f "$src" "$dst"
      # mirror the source's executable bit (the hook is +x, the skill is not)
      if [[ -x "$src" ]]; then chmod +x "$dst"; else chmod a-x "$dst"; fi
      echo "synced → $rel"
    fi
  done
done

[[ "$check" == 1 && "$stale" == 1 ]] && exit 1
exit 0
