#!/usr/bin/env bash
# gen-scenarios.sh — generate the PUBLIC-MIRROR-ONLY SCENARIOS.md from the multi-turn probe sessions.
#
# This is a release artifact, NOT a source-repo file: it is generated FRESH into the mirror tree by
# scripts/assemble-release-repo.sh at release time (which redirects this script's stdout into
# $DEST/SCENARIOS.md). Nothing is committed in the source repo, so it can never go stale.
#
# Source of truth: probe/tasks/*.md. Each task carries a "## The session (multi-prompt, in order …)"
# block of natural-language turns. This scrapes ONLY the scenario title + the ordered turn prompts,
# and drops ALL internal framing — start/source, gold, "what it probes", anti-overfitting, "what to
# mine", and the inter-turn STOP markers. The result is a user-facing catalogue of the complex,
# multi-step sessions dmtool is exercised with — a capability showcase, never a test or answer key.
#
#   scripts/gen-scenarios.sh            # print the scenarios markdown to stdout
#   scripts/gen-scenarios.sh --check    # self-validate the generator (CI guard) — no file written
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

emit() {
  cat <<'HEADER'
# dmtool — example multi-step sessions

<!-- GENERATED — do not edit by hand; regenerate with scripts/gen-scenarios.sh. -->

Realistic, **multi-turn** sessions an author (or a coding agent) drives `dmtool` through — each a sequence of plain-language asks, *in order*, that build up or evolve a document model. They show the *kind* of non-trivial, multi-step work the CLI is exercised with: from-scratch authoring, evolving an existing model's rules, refactors, computations, dates and ranges, multi-file workspaces, and more.

These are the **asks**, not worked transcripts. For step-by-step sessions **with the actual `dmtool` commands and their output** — re-executed and exact-matched in CI — see the demos in [`examples/`](examples/README.md) (e.g. [`cli-edit-loop`](examples/cli-edit-loop.md), [`cli-apply`](examples/cli-apply.md), [`cli-structure-edit`](examples/cli-structure-edit.md)).
HEADER

  local f title_line title
  for f in probe/tasks/*.md; do
    title_line="$(head -1 "$f")"
    title="${title_line#*— }"      # drop the "# Probe task NN — " prefix
    title="${title%% (*}"          # drop a trailing " (…)" qualifier
    printf '\n## %s\n\n' "$title"
    # Extract the ordered turn prompts from the session block; strip STOP markers and all other text.
    awk '
      function flush() { if (buf != "") { print n ". " buf; buf = "" } }
      /^## The session/ { s = 1; next }
      /^## /            { if (s) flush(); s = 0; next }
      s && /^\*\*Turn /  { flush(); n++; coll = 1; next }
      s && coll && /^> / { line = $0; sub(/^> /, "", line); buf = (buf == "" ? line : buf " " line); next }
      s && coll && /^>$/ { next }
      s && coll          { flush(); coll = 0 }
      END               { if (s) flush() }
    ' "$f"
  done
}

if [[ "${1:-}" == "--check" ]]; then
  # No committed copy to diff against (this is a mirror-only artifact). Instead self-validate that the
  # generator still extracts scenarios and that NO internal framing leaked, so a broken probe/tasks
  # structure change fails CI here rather than shipping a malformed showcase to the public mirror.
  out="$(emit)"
  n="$(printf '%s\n' "$out" | grep -c '^## ' || true)"
  if [[ "${n:-0}" -lt 1 ]]; then
    echo "FAIL: gen-scenarios extracted 0 scenarios — did probe/tasks/ structure change?" >&2
    exit 1
  fi
  if printf '%s\n' "$out" | grep -qE 'STOP —|^## (Gold|What|Anti-overfitting|Source)|MVK_'; then
    echo "FAIL: internal framing (gold/STOP/section/MVK) leaked into the generated scenarios" >&2
    exit 1
  fi
  echo "OK: gen-scenarios produces $n scenarios, no internal framing leaked"
else
  emit
fi
