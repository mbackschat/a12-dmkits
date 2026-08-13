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
# run's column header carries the conditions (load average, processor count, machine) that make its numbers readable.
#
# WHY THE MACHINE IS DISCLOSED AND NOT SPLIT ON. An environment names an os/arch family, not a machine, and an
# absolute time is a property of the machine (IF280) — which is why `perf-anchor.sh` REFUSES to fold two machines
# into one anchor. This script must not copy that refusal: it renders a report rather than authoring a bar, and
# splitting the table on `machine` would cut the darwin series in two, because 17 of the 28 committed runs predate
# the field. That destroys the continuity a trend table exists for, to guard against a machine change that probably
# never happened. So the fold stays VISIBLE instead of forbidden — the escalation the margin band and the
# `[perf][STALE]` reporter already use.
#
# Usage:
#   scripts/perf-history.sh                 every lane, newest 8 runs per environment
#   scripts/perf-history.sh <pattern>        only lanes/scenarios matching (case-insensitive substring)
#   RUNS=20 scripts/perf-history.sh          widen the history window
#   ENV=darwin-aarch64 scripts/perf-history.sh   one environment only
set -uo pipefail
cd "$(dirname "$0")/.."

# PERF_RESULTS_DIR is the guard's seam (as in perf-trend.sh): a renderer asserted against the live record would
# be coupled to whatever the last real run happened to measure.
results="${PERF_RESULTS_DIR:-perf-results}"
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
    """"<lane>|<scenario>@<v> fingerprint=<sha>|n=<N>|<engine>|<clock>" -> "<lane> · <scenario> @N [<engine>/<clock>]".

    The SCENARIO belongs in the label. Without it one lane's many scenarios all render identically — the real
    record holds ten `kernel parity` rows that every printed as "kernel parity @1000 [groovy/wall]", so a table
    whose purpose is to show which cost drifted could not say which row it was. Found 2026-08-12 while building
    the sibling perf-trend.sh, which inherited this function.
    """
    parts = key.split("|")
    lane = parts[0]
    n = next((p[2:] for p in parts if p.startswith("n=")), "?")
    engine, clock = (parts[-2], parts[-1]) if len(parts) >= 2 else ("?", "?")
    scenario = parts[1].split("@")[0].strip() if len(parts) > 1 else "?"
    return f"{lane} · {scenario} @{n} [{engine}/{clock}]"

for env in sorted(runs):
    series = runs[env][-window:]
    keys = sorted({k for _, _, o in series for k in o}, key=label)
    if pattern:
        keys = [k for k in keys if pattern in label(k).lower() or pattern in k.lower()]
    if not keys:
        continue

    print(f"\n=== {env} — {len(series)} of {len(runs[env])} recorded run(s), oldest first ===")
    for stamp, h, _ in series:
        print("    %-18s commit=%-9s jdk=%-8s procs=%-2s load=%-5s%s machine=%s" % (
            stamp, h.get("commit") or "?", h.get("jdk") or "?", h.get("processors") or "?",
            ("%.2f" % h["loadAverage"]) if isinstance(h.get("loadAverage"), (int, float)) and h["loadAverage"] >= 0 else "n/a",
            "  [CI %s]" % h.get("runId") if h.get("ci") else "",
            h.get("machine") or "?"))
    # Runs that NAME different machines are folded into one table on purpose (see the header note) — but silently
    # would make the row a comparison of two hardware generations. Absence is not a difference: 17 of the 28
    # committed runs predate the field, and treating unknown as its own identity would print this on every
    # invocation over the real record, which is how a warning stops being read.
    named = sorted({h.get("machine") for _, h, _ in series if h.get("machine")})
    if len(named) > 1:
        print("    [!] these runs were measured on different machines (%s); an absolute time is a property of the"
              " machine, so read each row within one machine rather than across it (IF280)" % ", ".join(named))
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
