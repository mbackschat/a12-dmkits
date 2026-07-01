#!/usr/bin/env bash
# Sync the canonical, single-source plugin assets into EVERY agent plugin tree (Claude Code + Codex).
#
# Three assets are shared verbatim across both plugins and must never be hand-edited in the plugin copies:
#   • the authoring skill   — skill/SKILL.md          (also the N6 material-under-test, read at that path)
#   • the bug-report skill  — skill-bug-report/SKILL.md (helps a user file a good report; the probe uses it too)
#   • the binary installer  — scripts/ensure-dmtool.sh (agent-agnostic on-demand downloader; the skill runs
#                             it, so it is synced INTO the skill dir, beside SKILL.md)
#   • the asset manifest    — scripts/release-assets.sh (canonical release asset names + platform mapping)
#
# The per-agent files (each plugin's manifest + AGENTS.md) are NOT synced — they differ by host and are
# maintained in place. This is the proven "one source → copies, freshness-checked" pattern (symlinks don't
# survive the release-mirror push). There is no session hook: the binary is fetched on demand by the skill.
#
#   ./scripts/sync-plugin-assets.sh          # copy canonical → every plugin (regenerate)
#   ./scripts/sync-plugin-assets.sh --check  # verify every copy matches; non-zero if not (CI guard)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SKILL_SRC="$ROOT/skill/SKILL.md"
BUGREPORT_SRC="$ROOT/skill-bug-report/SKILL.md"
INSTALLER_SRC="$ROOT/scripts/ensure-dmtool.sh"
ASSETS_SRC="$ROOT/scripts/release-assets.sh"
[[ -f "$SKILL_SRC" ]] || { echo "canonical skill not found: $SKILL_SRC" >&2; exit 2; }
[[ -f "$BUGREPORT_SRC" ]] || { echo "canonical bug-report skill not found: $BUGREPORT_SRC" >&2; exit 2; }
[[ -f "$INSTALLER_SRC" ]] || { echo "canonical installer not found: $INSTALLER_SRC" >&2; exit 2; }
[[ -f "$ASSETS_SRC" ]] || { echo "canonical release asset manifest not found: $ASSETS_SRC" >&2; exit 2; }

# Each plugin tree that receives the shared assets. Add a row here when a new agent plugin is introduced.
PLUGINS=("$ROOT/plugin" "$ROOT/plugin-codex")

# "<src>|<relative dest under each plugin>". The installer lands in the skill dir (beside SKILL.md) so the
# skill can run it on demand — there is no hooks/ dir any more.
ASSETS=(
  "$SKILL_SRC|skills/a12-dmtool/SKILL.md"
  "$INSTALLER_SRC|skills/a12-dmtool/ensure-dmtool.sh"
  "$ASSETS_SRC|skills/a12-dmtool/release-assets.sh"
  "$BUGREPORT_SRC|skills/a12-dmtool-bug-report/SKILL.md"
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
      # mirror the source's executable bit (the installer is +x, the skill is not)
      if [[ -x "$src" ]]; then chmod +x "$dst"; else chmod a-x "$dst"; fi
      echo "synced → $rel"
    fi
  done
done

[[ "$check" == 1 && "$stale" == 1 ]] && exit 1
exit 0
