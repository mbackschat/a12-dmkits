#!/usr/bin/env bash
#
# perf-history.sh — render the committed performance record as a text table.
#
# The record is one JSON-Lines file per run under perf-results/<environment>/<utc-timestamp>.jsonl (see that
# directory's README). This script only READS it; it never measures and never writes.
#
# WHY A TABLE PER ENVIRONMENT. Absolute timings are only comparable within one runner class: the same 36
# observations drift 0.92x-1.36x between runs on a fixed machine and up to 1.94x at the whole-run level on a
# 2-vCPU hosted runner. Rows from different environments are therefore never placed in the same table, and each
# run's column header carries the conditions (load average, processor count) that make its numbers readable.
#
# Usage:
#   scripts/perf-history.sh                 every lane, newest 8 runs per environment
#   scripts/perf-history.sh <pattern>        only lanes/scenarios matching (case-insensitive substring)
#   RUNS=20 scripts/perf-history.sh          widen the history window
#   ENV=darwin-aarch64 scripts/perf-history.sh   one environment only
set -uo pipefail
cd "$(dirname "$0")/.."

results="perf-results"
[ -d "$results" ] || { echo "no $results directory — run scripts/perf.sh gate first" >&2; exit 1; }

PATTERN="${1:-}" RUNS="${RUNS:-8}" ONLY_ENV="${ENV:-}" RESULTS="$results" python3 - <<'PY'
import json, os, statistics, sys
from pathlib import Path

results = Path(os.environ["RESULTS"])
pattern = os.environ.get("PATTERN", "").lower()
window = int(os.environ.get("RUNS", "8"))
only = os.environ.get("ONLY_ENV", "")

runs = {}   # environment -> [ (stamp, header, {key: (ms, load)}) ], oldest first
for path in sorted(results.rglob("*.jsonl"), key=lambda p: (p.parent.name, p.name)):
    header, obs = None, {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except json.JSONDecodeError:
            continue
        if rec.get("kind") == "run":
            header = rec
        elif rec.get("kind") == "observation":
            obs[rec["key"]] = (float(rec["medianMs"]), rec.get("loadAverage"))
    if header is None or not obs:
        continue
    env = header.get("environment", path.parent.name)
    if only and env != only:
        continue
    runs.setdefault(env, []).append((header.get("capturedAt", path.stem), header, obs))

if not runs:
    sys.exit("the record holds no complete runs yet" + (f" for {only}" if only else ""))

def label(key):
    # "<lane>|<scenario>@<v> fingerprint=<sha>|n=<N>|<engine>|<clock>" -> "<lane> @N <engine>/<clock>"
    parts = key.split("|")
    lane = parts[0]
    n = next((p[2:] for p in parts if p.startswith("n=")), "?")
    engine, clock = (parts[-2], parts[-1]) if len(parts) >= 2 else ("?", "?")
    return f"{lane} @{n} [{engine}/{clock}]"

for env in sorted(runs):
    series = runs[env][-window:]
    keys = sorted({k for _, _, o in series for k in o}, key=label)
    if pattern:
        keys = [k for k in keys if pattern in label(k).lower() or pattern in k.lower()]
    if not keys:
        continue

    print(f"\n=== {env} — {len(series)} of {len(runs[env])} recorded run(s), oldest first ===")
    for stamp, h, _ in series:
        print("    %-18s commit=%-9s jdk=%-8s procs=%-2s load=%-5s%s" % (
            stamp, h.get("commit") or "?", h.get("jdk") or "?", h.get("processors") or "?",
            ("%.2f" % h["loadAverage"]) if isinstance(h.get("loadAverage"), (int, float)) and h["loadAverage"] >= 0 else "n/a",
            "  [CI %s]" % h.get("runId") if h.get("ci") else ""))
    print()

    width = max(len(label(k)) for k in keys)
    print("%-*s %s   %8s" % (width, "lane @scale [engine/clock]",
                             " ".join("%9s" % s[5:] for s, _, _ in series), "trend"))
    for k in keys:
        cells, values = [], []
        for _, _, o in series:
            if k in o:
                cells.append("%9.2f" % o[k][0]); values.append(o[k][0])
            else:
                cells.append("%9s" % "-")
        # Trend compares the newest value with the median of the earlier ones, which is the only comparison a
        # two-point read can support; it is a report, never a verdict.
        if len(values) >= 2:
            base = statistics.median(values[:-1])   # a real median: for two earlier runs this is their MEAN.
            trend = "%+7.0f%%" % (100 * (values[-1] / base - 1)) if base else "      ?"
        else:
            trend = "      -"
        print("%-*s %s   %8s" % (width, label(k), " ".join(cells), trend))
PY
