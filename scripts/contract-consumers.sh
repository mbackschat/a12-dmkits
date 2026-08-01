#!/usr/bin/env bash
# contract-consumers.sh — list every committed file that READS dmtool's output.
#
# Why this exists: a change to the published result contract has consumers well outside Java. Nesting the
# verb payloads under `data` (2026-08-01) was swept through the 78 Java call sites and NOT through the
# markdown, shell and JS ones — four demo `jq` filters silently went stale (two of them capturing a `jq`
# error as their expected output) and the eval harness kept telling its judging agents that `batch` returns
# a bare array. The consumer set was enumerated from memory, which is exactly what failed.
#
# So the set is DERIVED here rather than listed anywhere: run this before changing the envelope, and again
# after, and diff your work against it. `ResultEnvelopeFrameTripwireTest` fails the build when the envelope's
# top-level key set changes, and points here — so the sweep is triggered rather than remembered.
#
#   ./scripts/contract-consumers.sh            # the consumer files, one per line
#   ./scripts/contract-consumers.sh --detail   # each file with its matching lines
#
# Guarded consumers (no manual sweep needed, but re-verify):
#   examples/*.md            scripts/verify-demos.sh + DemoOutputIsRealTest
#   verb descriptions        VerbDemonstrationTest.noAgentFacingClaimNamesAnEnvelopeValueTheVerbNeverPublishes
#   schema examples          SchemaExamplesGuardTest (CI-executes them)
#   command spellings        CommandReferencesResolveTest (verbs/flags only — NOT envelope paths)
# Unguarded, so they are the ones to read by hand: eval/, probe/, skill*/, docs/ prose.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# A file "reads dmtool output" if it pipes dmtool into jq, or names an envelope path/key directly.
PATTERN='dmtool[^|]*\| *jq|\.data\.[a-zA-Z]|data\.results|"outcome" *:'

detail=false
[[ "${1:-}" == "--detail" ]] && detail=true

# Skip the archive: it is a historical record by definition, never a consumer to migrate.
files=$(git ls-files -- '*.md' '*.sh' '*.js' '*.json' '*.yml' '*.yaml' \
        | grep -v '^docs/archived/' \
        | xargs grep -lE "$PATTERN" 2>/dev/null || true)

if [[ -z "$files" ]]; then
  echo "contract-consumers: matched NOTHING — the pattern or the tree moved; fix this before trusting it" >&2
  exit 1
fi

if $detail; then
  for f in $files; do
    echo "=== $f"
    grep -nE "$PATTERN" "$f" | head -20
  done
else
  echo "$files"
fi

echo "---" >&2
echo "$(echo "$files" | wc -l | tr -d ' ') files read dmtool output. examples/ and the verb descriptions are" >&2
echo "guarded; eval/, probe/, skill*/ and docs/ prose are NOT — read those by hand." >&2
