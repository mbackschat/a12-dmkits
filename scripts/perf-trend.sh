#!/usr/bin/env bash
#
# perf-trend.sh — detect STEP CHANGES in the committed performance record (IG121 piece 2).
#
# The record is one JSON-Lines file per run under perf-results/<environment>/<utc-timestamp>.jsonl (see that
# directory's README). This script only READS it; it never measures and never writes.
#
# WHAT IT REPORTS, AND WHAT IT DELIBERATELY DOES NOT. For each row it answers two questions: has the row's level
# STEPPED somewhere in the series, and what is its run-to-run NOISE within the current level. It is a REPORT and
# always exits 0 on a readable record — the enforcement half (IG121 piece 1's early-warning band) is downstream of
# this and sizes itself from the noise figure printed here. That ordering is not a preference: piece 1 was once
# built on the spread between a single run's interleaved pairs, which is dispersed by construction and warned
# about three rows the real quantity leaves alone. The only honest per-row noise is the run-to-run spread across
# the committed series, which is what this computes.
#
# WHY SEGMENTATION COMES FIRST. A platform change re-bases every number, so comparing across one reads as a
# regression. Runs are therefore grouped by (environment, jdk, os, machine) and a series is only ever compared
# within one group. Two subtleties, both of which the real record actually contains:
#
#   * An ABSENT field is unknown, not a distinct value. `machine` was added after 17 darwin runs were committed,
#     so those carry it as null; treating null as its own value would split the local series in two on a SCHEMA
#     change and halve the history every noise estimate reads. A null resolves to the environment's known value
#     when that environment has exactly one, and otherwise prints as "machine unrecorded" without splitting.
#   * The step SEARCH is what discovers a re-basing revision, so no hand-maintained epoch list is needed: the
#     change point is reported with the commit at its boundary, which is how a consumer learns which revision
#     re-based the row.
#
# THE METHOD. Levels are compared in log space, because these are ratios. For every split with at least MIN_SIDE
# runs on each side, the shift is median(log right) - median(log left) and the noise is the MAD of each point
# around its OWN side's median, pooled — so a real step does not inflate the dispersion it is measured against.
# The split with the largest robust score wins, and it is reported only when the score clears Z *and* the shift
# clears MIN_SHIFT. Both must hold: the score alone would flag a 2% shift on a very quiet row, and the shift alone
# would flag ordinary wander on a noisy one.
#
# Usage:
#   scripts/perf-trend.sh                    every lane, every environment
#   scripts/perf-trend.sh <pattern>          only lanes/scenarios matching (case-insensitive substring)
#   ENV=darwin-aarch64 scripts/perf-trend.sh one environment only
#   MIN_SIDE=4 MIN_SHIFT=1.10 Z=2.5 scripts/perf-trend.sh   loosen or tighten the report
#   PERF_RESULTS_DIR=<dir> scripts/perf-trend.sh            read a record elsewhere (the guard's seam)
set -uo pipefail
cd "$(dirname "$0")/.."

results="${PERF_RESULTS_DIR:-perf-results}"
if [ ! -d "$results" ]; then
    echo "no record directory at '$results' — run scripts/perf.sh gate first" >&2
    exit 1
fi

PATTERN="${1:-}" ONLY_ENV="${ENV:-}" RESULTS="$results" \
MIN_SIDE="${MIN_SIDE:-3}" MIN_SHIFT="${MIN_SHIFT:-1.15}" Z="${Z:-3.0}" python3 - <<'PY'
import json, math, os, statistics, sys
from pathlib import Path

results = Path(os.environ["RESULTS"])
pattern = os.environ.get("PATTERN", "").lower()
only = os.environ.get("ONLY_ENV", "")
min_side = int(os.environ["MIN_SIDE"])
min_shift = float(os.environ["MIN_SHIFT"])
z_min = float(os.environ["Z"])

# --- read: one entry per run, oldest first within an environment -------------------------------------
runs = []
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
            obs[rec["key"]] = float(rec["medianMs"])
    if header is None or not obs:
        continue
    env = header.get("environment", path.parent.name)
    if only and env != only:
        continue
    runs.append((env, header.get("capturedAt", path.stem), header, obs))

if not runs:
    sys.exit("the record holds no complete runs yet" + (f" for {only}" if only else "")
             + " — a run needs a 'run' header line and at least one observation")

# --- segment: platform identity, with an absent field resolved rather than split on --------------------
FIELDS = ("jdk", "os", "machine")
known = {}   # (env, field) -> set of non-null values seen
for env, _, h, _ in runs:
    for f in FIELDS:
        if h.get(f) is not None:
            known.setdefault((env, f), set()).add(h[f])

def segment_of(env, h):
    parts = []
    for f in FIELDS:
        value = h.get(f)
        if value is None:
            candidates = known.get((env, f), set())
            # Exactly one known value in this environment: the absent one is that value, not a new segment.
            value = next(iter(candidates)) if len(candidates) == 1 else None
        parts.append(value)
    return (env, *parts)

segments = {}   # segment -> [ (stamp, commit, {key: ms}) ], oldest first
for env, stamp, h, obs in runs:
    segments.setdefault(segment_of(env, h), []).append((stamp, h.get("commit") or "?", obs))

def label(key):
    """"<lane>|<scenario>@<v> fingerprint=<sha>|n=<N>|<engine>|<clock>" -> "<lane> · <scenario> @N [<engine>/<clock>]".

    The SCENARIO is part of the label, unlike in perf-history.sh, whose label drops it. That is not cosmetic
    here: one lane holds many scenarios, so without it ten distinct `kernel parity` rows all render as
    "kernel parity @1000 [groovy/wall]" and a report naming a changed row cannot say WHICH row changed.
    """
    parts = key.split("|")
    n = next((p[2:] for p in parts if p.startswith("n=")), "?")
    engine, clock = (parts[-2], parts[-1]) if len(parts) >= 2 else ("?", "?")
    scenario = parts[1].split("@")[0].strip() if len(parts) > 1 else "?"
    return f"{parts[0]} · {scenario} @{n} [{engine}/{clock}]"

def spread(logs, centre):
    """MAD of |log - centre|, scaled to a normal-consistent sigma, with a floor so a flat run cannot divide by 0."""
    mad = statistics.median([abs(v - centre) for v in logs]) if logs else 0.0
    return max(1.4826 * mad, math.log(1.01))

def best_split(logs):
    """The split maximizing |shift| / pooled-noise, or None when the series is too short to claim one."""
    n = len(logs)
    if n < 2 * min_side:
        return None
    best = None
    for i in range(min_side, n - min_side + 1):
        left, right = logs[:i], logs[i:]
        ml, mr = statistics.median(left), statistics.median(right)
        residuals = [abs(v - ml) for v in left] + [abs(v - mr) for v in right]
        noise = max(1.4826 * statistics.median(residuals), math.log(1.01))
        score = abs(mr - ml) / noise
        if best is None or score > best[0]:
            best = (score, i, ml, mr)
    return best

changed = 0
for segment in sorted(segments, key=lambda s: tuple("" if p is None else p for p in s)):
    series = segments[segment]
    env, jdk, os_name, machine = segment
    head = f"{env} · jdk {jdk or '?'} · {os_name or '?'} · " + (machine or "machine unrecorded")
    keys = sorted({k for _, _, o in series for k in o}, key=label)
    if pattern:
        keys = [k for k in keys if pattern in label(k).lower() or pattern in k.lower()]
    if not keys:
        continue
    print(f"\n=== {head} — {len(series)} run(s), {series[0][0]} .. {series[-1][0]} ===")

    width = max(len(label(k)) for k in keys)
    for key in keys:
        points = [(stamp, commit, o[key]) for stamp, commit, o in series if key in o]
        values = [ms for _, _, ms in points]
        name = "%-*s" % (width, label(key))
        if len(values) < 2 * min_side:
            print(f"  {name}  runs={len(values)}  insufficient runs "
                  f"(need >= {2 * min_side} for a step claim)")
            continue

        logs = [math.log(v) for v in values]
        found = best_split(logs)
        score, i, ml, mr = found
        ratio = math.exp(mr - ml)
        stepped = score >= z_min and (ratio >= min_shift or ratio <= 1 / min_shift)

        # The CURRENT level is what a downstream band must be sized from: the runs since the last step, or the
        # whole series when there is none. Reporting the whole-series spread on a stepped row would hand piece 1
        # a noise figure inflated by the step itself.
        current = logs[i:] if stepped else logs
        centre = statistics.median(current)
        noise_pct = 100 * (math.exp(spread(current, centre)) - 1)
        tail = f"runs={len(values)}  current={math.exp(centre):.2f}ms  noise=±{noise_pct:.1f}%"
        if stepped:
            changed += 1
            stamp, commit, _ = points[i]
            print(f"  {name}  {tail}  CHANGE at {stamp} ({commit}): "
                  f"{math.exp(ml):.2f} -> {math.exp(mr):.2f}ms ({100 * (ratio - 1):+.0f}%)")
        else:
            print(f"  {name}  {tail}  stable")

print(f"\n{changed} row(s) reported a change point. This is a report, not a verdict: a change point is what a "
      f"deliberate optimization looks like too.")
PY
