#!/usr/bin/env bash
# Sync the canonical N1 skill into the Claude Code plugin (single source of truth).
#
# skill/SKILL.md is canonical (the N6 material-under-test; the eval reads it at that exact path).
# The plugin bundles a COPY at plugin/skills/a12-rules/SKILL.md — generated here, never hand-edited.
# A freshness check (--check) fails if they diverge, so the copy can't rot (run in CI / before release).
#
#   ./scripts/sync-plugin-skill.sh          # copy canonical → plugin (regenerate)
#   ./scripts/sync-plugin-skill.sh --check  # verify they match; non-zero if not (CI guard)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/skill/SKILL.md"
DST="$ROOT/plugin/skills/a12-rules/SKILL.md"

[[ -f "$SRC" ]] || { echo "canonical skill not found: $SRC" >&2; exit 2; }

if [[ "${1:-}" == "--check" ]]; then
  if [[ -f "$DST" ]] && diff -q "$SRC" "$DST" >/dev/null; then
    echo "plugin skill is in sync with skill/SKILL.md"
  else
    echo "plugin skill is STALE — run ./scripts/sync-plugin-skill.sh" >&2
    exit 1
  fi
else
  mkdir -p "$(dirname "$DST")"
  cp -f "$SRC" "$DST"
  echo "synced skill/SKILL.md → plugin/skills/a12-rules/SKILL.md"
fi
