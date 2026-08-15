#!/usr/bin/env bash
#
# perf-report.sh — the ONE performance question, answered in one command (IG137 piece b).
#
# The committed record under perf-results/ already had three readers, each answering a different half of "how is
# performance doing?": perf-history.sh renders the levels, perf-trend.sh finds the steps and the noise, and
# perf-anchor.sh show says what a bar refresh would change. Nothing composed them and nothing printed the one
# thing a maintainer looks at first — which run is the newest, and did its observations pass. So the habit was to
# hand-roll `awk` over the JSONL, and that is exactly how the reader-side locale bug happened (perf-results/README
# "Aggregating one of these files by hand"). This script exists so that habit has a replacement.
#
# IT IS A COMPOSITION, NOT A FOURTH READER. Sections 2-4 delegate to the existing scripts verbatim; only §1 parses
# the record, and only for the newest run's identity and its status tally — a projection none of the three has.
# Adding a fourth independent parser would recreate the very class of bug the script is meant to retire.
#
# READ-ONLY, and exits 0 on a readable record. Nothing here is a bar: a step change is what a deliberate
# optimization looks like too, and only a human can tell the two apart.
#
# Usage:
#   scripts/perf-report.sh                       every lane, every environment
#   scripts/perf-report.sh <pattern>             only lanes/scenarios matching (case-insensitive substring)
#   ENV=linux-amd64-ci scripts/perf-report.sh    one runner class only
#   RUNS=20 scripts/perf-report.sh               widen the history window (§2)
set -uo pipefail
cd "$(dirname "$0")/.."

# PERF_RESULTS_DIR is the guard's seam (as in perf-trend.sh/perf-history.sh): a reporter asserted against the live
# record would be coupled to whatever the last real run happened to measure.
results="${PERF_RESULTS_DIR:-perf-results}"
[ -d "$results" ] || { echo "no $results directory — run scripts/perf.sh gate first" >&2; exit 1; }

pattern="${1:-}"

rule() { printf '\n=== %s ===\n%s\n\n' "$1" "$2"; }

rule "1. STATUS — the newest run per runner class" \
     "identity, conditions, and how its observations landed. A 'breached' count is the only line here that is a verdict."
ONLY_ENV="${ENV:-}" RESULTS="$results" python3 - <<'PY'
import json, os
from pathlib import Path

results = Path(os.environ["RESULTS"])
only_env = os.environ.get("ONLY_ENV", "")

for env_dir in sorted(p for p in results.iterdir() if p.is_dir()):
    if only_env and env_dir.name != only_env:
        continue
    runs = sorted(env_dir.glob("*.jsonl"))
    if not runs:
        continue
    newest = runs[-1]
    header, statuses = None, {}
    for line in newest.read_text().splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("kind") == "run":
            header = row
        elif row.get("kind") == "observation":
            statuses[row.get("status", "?")] = statuses.get(row.get("status", "?"), 0) + 1
    if header is None:
        print(f"{env_dir.name}: {newest.name} carries no run header")
        continue
    where = header.get("machine") or ("CI runner" if header.get("ci") else "machine unrecorded")
    load = header.get("loadAverage")
    print(f"{env_dir.name}  ({len(runs)} runs recorded)")
    print(f"  newest    {header.get('capturedAt')}  commit {header.get('commit')}")
    print(f"  where     {where}  ·  {header.get('processors')} processors"
          + (f"  ·  load {load:.2f}" if isinstance(load, (int, float)) else "")
          + (f"  ·  CI run {header['runId']}" if header.get("runId") else ""))
    print(f"  jdk       {header.get('jdk')} on {header.get('os')}/{header.get('arch')}")
    total = sum(statuses.values())
    tally = "  ".join(f"{n} {s}" for s, n in sorted(statuses.items()))
    print(f"  landed    {total} observations — {tally if tally else 'none'}")
    if statuses.get("breached"):
        print(f"  ATTENTION {statuses['breached']} breached — re-measure the failing lane in isolation before "
              "reading it as a regression (a contended run looks identical)")
    print()
PY

rule "2. LEVELS — where each row currently sits" \
     "absolute medians, newest runs as columns. Comparable only within a runner class; the trend column is a report, never a verdict."
scripts/perf-history.sh "$pattern"

rule "3. STEPS — did a row's level actually change, and when" \
     "a step is reported only when its robust score and its size both clear their dials; the change point names the commit at its boundary."
scripts/perf-trend.sh "$pattern"

rule "4. HEADROOM — how much room the cross-engine gates have" \
     "the margin over each kernel strategy, its own spread, and the worst margin the record holds. A different quantity from §3: both engines share a JVM, so a load excursion largely cancels in a ratio."
scripts/perf-trend.sh --ratio "$pattern"

rule "5. BARS — what an anchor refresh would change" \
     "the committed absolute bar per lane. 'show' changes nothing; a refresh is a deliberate, reviewable diff (scripts/perf-anchor.sh refresh)."
scripts/perf-anchor.sh show
