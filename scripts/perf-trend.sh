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
# THE --ratio VIEW, and why it is a different quantity rather than a convenience. The cross-engine gates assert a
# RATIO (interpreter vs each kernel strategy), and a ratio's noise is not its operands' noise: both engines run in
# the same JVM on the same vCPUs in the same run, so a load excursion moves them together and largely cancels. A
# band for those gates must therefore be sized on the ratio series, which is not a key in the record at all — it is
# derived by joining the engines of one (lane, scenario, n, clock) row WITHIN each run. `--ratio` reports each
# row's current margin over each kernel strategy, the margin's own run-to-run spread, and the worst margin the
# record holds, which is what a band has to survive.
#
# Usage:
#   scripts/perf-trend.sh                    every lane, every environment
#   scripts/perf-trend.sh <pattern>          only lanes/scenarios matching (case-insensitive substring)
#   scripts/perf-trend.sh --ratio [pattern]  cross-engine margins and their spread, per row
#   BAND=0.45 scripts/perf-trend.sh --ratio       BACKTEST a floor at a fraction of each row's own median
#   BAND_SIGMA=4 scripts/perf-trend.sh --ratio    …or at k robust deviations below it (the noise-aware shape)
#   EMIT_FLOORS=1 BAND_SIGMA=6 …  --ratio         emit those floors as the committed margin-floor JSON
#   ENV=darwin-aarch64 scripts/perf-trend.sh one environment only
#   MIN_SIDE=4 MIN_SHIFT=1.10 Z=2.5 scripts/perf-trend.sh   loosen or tighten the report
#   PERF_RESULTS_DIR=<dir> scripts/perf-trend.sh            read a record elsewhere (the guard's seam)
set -uo pipefail
cd "$(dirname "$0")/.."

VIEW=steps
if [ "${1:-}" = "--ratio" ]; then
    VIEW=ratio
    shift
fi

results="${PERF_RESULTS_DIR:-perf-results}"
if [ ! -d "$results" ]; then
    echo "no record directory at '$results' — run scripts/perf.sh gate first" >&2
    exit 1
fi

PATTERN="${1:-}" ONLY_ENV="${ENV:-}" RESULTS="$results" VIEW="$VIEW" \
MIN_FLOOR="${MIN_FLOOR:-1.5}" MIN_SIDE="${MIN_SIDE:-3}" MIN_SHIFT="${MIN_SHIFT:-1.15}" Z="${Z:-3.0}" BAND="${BAND:-}" BAND_SIGMA="${BAND_SIGMA:-}" EMIT_FLOORS="${EMIT_FLOORS:-}" python3 - <<'PY'
import json, math, os, statistics, sys
from pathlib import Path

results = Path(os.environ["RESULTS"])
pattern = os.environ.get("PATTERN", "").lower()
only = os.environ.get("ONLY_ENV", "")
min_side = int(os.environ["MIN_SIDE"])
min_shift = float(os.environ["MIN_SHIFT"])
z_min = float(os.environ["Z"])
view = os.environ.get("VIEW", "steps")
band = float(os.environ["BAND"]) if os.environ.get("BAND") else None
min_floor = float(os.environ.get("MIN_FLOOR", "1.5"))
band_sigma = float(os.environ["BAND_SIGMA"]) if os.environ.get("BAND_SIGMA") else None
# Emitting the floors is the SAME computation the backtest prints, deliberately: a floor authored by one code
# path and validated by another is two contracts. The backtest is how the sigma is chosen; this writes the
# chosen one out, so what ships is what was measured.
emit_floors = bool(os.environ.get("EMIT_FLOORS"))
KERNEL_ENGINES = ("groovy", "java")

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
    """The best two-level split, or None when the series is too short to claim one.

    The split is LOCATED by best fit — the smallest pooled absolute deviation from the two side medians, an L1
    two-level fit — and only then SCORED against the robust noise. Locating it by the score instead is what the
    first version did, and it mislocates a step by one run: MAD is deliberately insensitive to a single outlier, so
    moving the last pre-step value across the boundary leaves both medians and the noise estimate essentially
    unchanged, the candidate splits TIE, and the tie breaks arbitrarily. One leaked pre-step value is not cosmetic
    downstream — it lands in the "current level" a band is backtested against and reports itself as a firing, which
    is precisely the false positive the level restriction exists to remove.
    """
    n = len(logs)
    if n < 2 * min_side:
        return None
    best = None
    for i in range(min_side, n - min_side + 1):
        left, right = logs[:i], logs[i:]
        ml, mr = statistics.median(left), statistics.median(right)
        residuals = [abs(v - ml) for v in left] + [abs(v - mr) for v in right]
        fit = math.fsum(residuals)
        noise = max(1.4826 * statistics.median(residuals), math.log(1.01))
        score = abs(mr - ml) / noise
        if best is None or fit < best[0]:
            best = (fit, score, i, ml, mr)
    fit, score, i, ml, mr = best
    return (score, i, ml, mr)

def row_identity(key):
    """(lane, scenario-with-fingerprint, n, clock) — everything but the ENGINE, which is what a ratio varies."""
    parts = key.split("|")
    if len(parts) < 5:
        return None
    n = next((p[2:] for p in parts if p.startswith("n=")), "?")
    return (parts[0], parts[1], n, parts[-1])


def ratio_label(identity):
    lane, scenario, n, clock = identity
    return f"{lane} · {scenario.split('@')[0].strip()} @{n} [{clock}]"


if view == "ratio":
    total, tightest = 0, None
    firings, rows_firing = 0, 0
    emitted = {}
    unbandable = []
    for segment in sorted(segments, key=lambda s: tuple("" if p is None else p for p in s)):
        series = segments[segment]
        env, jdk, os_name, machine = segment
        head = f"{env} · jdk {jdk or '?'} · {os_name or '?'} · " + (machine or "machine unrecorded")

        # margins[(identity, kernel_engine)] -> [interp_ms / kernel_ms per run]
        margins = {}
        for _, _, obs in series:
            per_row = {}
            for key, ms in obs.items():
                identity = row_identity(key)
                if identity is None:
                    continue
                per_row.setdefault(identity, {})[key.split("|")[-2]] = ms
            for identity, engines in per_row.items():
                interp = engines.get("interpreter")
                if interp is None or interp <= 0:
                    continue
                for kernel in KERNEL_ENGINES:
                    other = engines.get(kernel)
                    if other is None or other <= 0:
                        continue
                    # The MARGIN is how many times faster the interpreter is: kernel / interp. Parity is 1.0 and
                    # the gate fails below it, so a bigger number is more headroom.
                    margins.setdefault((identity, kernel), []).append(other / interp)

        rows = sorted(margins, key=lambda r: (ratio_label(r[0]), r[1]))
        if pattern:
            rows = [r for r in rows if pattern in ratio_label(r[0]).lower()]
        if not rows:
            continue
        band_note = (f", band={band_sigma:.1f} robust deviations below its own median" if band_sigma
                     else f", band={band:.2f}x its own median" if band else "")
        if not emit_floors:
            print(f"\n=== {head} — {len(series)} run(s), cross-engine margins "
                  f"(kernel/interpreter; parity = 1.00{band_note}) ===")
        width = max(len(ratio_label(r[0])) for r in rows)
        for identity, kernel in rows:
            allValues = margins[(identity, kernel)]
            allLogs = [math.log(v) for v in allValues]

            # THE CURRENT LEVEL, not the whole series — piece 2 composed into piece 1 rather than duplicated.
            # A margin series spanning an optimization holds two levels, and a band derived from the newer one
            # trips on every older run: at 0.40x of each row's median the whole-series backtest fired 28 times,
            # none of which is a regression — they are pre-optimization margins. The gap named this trap
            # explicitly ("segmented by revision, not only by platform"), and measuring it is what caught the
            # first version of this backtest doing exactly that.
            step = best_split(allLogs)
            stepped = False
            start = 0
            if step is not None:
                score, i, ml, mr = step
                ratio = math.exp(mr - ml)
                if score >= z_min and (ratio >= min_shift or ratio <= 1 / min_shift):
                    stepped, start = True, i
            values, logs = allValues[start:], allLogs[start:]

            centre = statistics.median(logs)
            noise_pct = 100 * (math.exp(spread(logs, centre)) - 1)
            worst = min(values)
            total += 1
            current = math.exp(centre)
            if tightest is None or worst < tightest[0]:
                tightest = (worst, ratio_label(identity), kernel)
            level = f"{len(values)}/{len(allValues)}" if stepped else f"{len(values)}"
            line = (f"  {'%-*s' % (width, ratio_label(identity))}  vs {kernel:<7} runs={level:<7} "
                    f"margin={current:6.2f}x  spread=±{noise_pct:4.1f}%  worst={worst:6.2f}x")
            if band or band_sigma:
                # THE BACKTEST. A candidate band is always relative to the row's OWN history, because a flat one
                # cannot serve rows spanning 2x-19x. Counting how many historical runs it would have tripped is
                # what makes the choice evidence rather than the third guess: the two earlier attempts at this
                # band were a flat constant and a per-pair dispersion, and both were falsified AFTER being built.
                # A fraction of the median IGNORES the row's spread, so it fires on the noisiest rows
                # while leaving a very stable one unguarded until it halves. BAND_SIGMA expresses the floor in the
                # row's OWN robust deviations instead: tight where the row is quiet (a 2% row is guarded at 0.92x
                # of its median), tolerant where it genuinely wanders (a 34% row at 0.31x). That is the shape the
                # gap asked for, and it is only computable now that the noise is the within-level run-to-run
                # spread rather than the per-pair dispersion the falsified attempt used.
                floor = (math.exp(centre - band_sigma * spread(logs, centre)) if band_sigma
                         else current * band)
                lane, scenario, n, clock = identity
                # A floor at or below PARITY is vacuous: the cross-engine gate already fails there, so emitting it
                # would advertise a warning that can never fire before the build does. Such a row is reported as
                # having too little headroom to band rather than banded at a number that reads like coverage.
                #
                # The bar is MIN_FLOOR rather than parity itself, because "just above 1.0" is vacuous for the same
                # reason 1.0 is, and it is reachable by a mechanism that is invisible here: when `best_split` misses
                # a step, the pooled bimodal series' spread explodes and the floor lands just over parity. Measured
                # on `deepRich@240`, where the SAME step is detected on the groovy arm (runs=11/28, spread ±8.5%)
                # and missed on the java arm (runs=28, spread ±25.5%), putting its floor at 1.006x. A band emitted
                # there would read as coverage while being unable to fire before the parity gate does.
                if floor > min_floor:
                    emitted.setdefault(env, {})[f"{lane}|{scenario}|n={n}|{kernel}|{clock}"] = round(floor, 4)
                else:
                    unbandable.append((ratio_label(identity), kernel, current, floor))
                trips = sum(1 for v in values if v < floor)
                worst_trips = worst < floor
                firings += trips
                if trips:
                    rows_firing += 1
                line += f"  band={floor:6.2f}x  wouldHaveFired={trips}/{len(values)}"
                if worst_trips:
                    line += " (incl. its worst)"
            if not emit_floors:
                print(line)

    if emit_floors:
        if not (band or band_sigma):
            sys.exit("EMIT_FLOORS needs a band: set BAND_SIGMA=<k> (or BAND=<fraction>) so there is a floor to emit")
        if not emitted:
            sys.exit("no row produced a floor — nothing to emit")
        # Printed to stdout as the whole file, so authoring it is a redirect and reviewing it is a diff. The
        # sigma is recorded IN the file: a floor is meaningless without the multiple it was derived at, and a
        # later refresh at a different sigma must be visible as a change rather than as a silent retune.
        doc = {env: {"sigma": band_sigma, "fraction": band, "floors": dict(sorted(rows.items()))}
               for env, rows in sorted(emitted.items())}
        print(json.dumps(doc, indent=2, sort_keys=False))
        for label, kernel, current, floor in unbandable:
            print(f"[skipped] {label} vs {kernel}: median {current:.2f}x leaves a floor of {floor:.2f}x, "
                  f"below the {min_floor:.2f}x a band must clear to be able to fire — too little headroom",
                  file=sys.stderr)
        sys.exit(0)

    if tightest is not None:
        worst, name, kernel = tightest
        print(f"\n{total} cross-engine row(s). Tightest margin in the record: {worst:.2f}x on {name} vs {kernel}. "
              f"A band must sit below that and outside each row's own spread, or it fires on history.")
        if band or band_sigma:
            what = (f"{band_sigma:.1f} robust deviations below" if band_sigma else f"{band:.2f}x of")
            print(f"BACKTEST band={what} each row's own median: it would have fired {firings} time(s) "
                  f"across {rows_firing} of {total} rows in the committed series. Every one of those is a FALSE "
                  f"positive unless that run actually regressed — which is the number to drive to zero before a "
                  f"band is allowed to fail anything.")
    else:
        sys.exit("the record holds no row with both an interpreter and a kernel arm — the ratio view needs both")
    sys.exit(0)

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

    # Each ENGINE arm of one row is decided independently above, which leaves the reader to correlate them by
    # eye — and the correlation is the whole signal (IG137 piece c). A step on EVERY arm of a row is the
    # machine re-basing: all three engines share the JVM, the vCPUs and the load slice, so nothing about our
    # code can move them together. A step on the interpreter arm ALONE is ours. Reported after the rows rather
    # than folded into them, because the per-arm numbers are what a downstream band is sized from and must not
    # be rewritten by this reading.
    arms = {}
    width = max(len(label(k)) for k in keys)
    for key in keys:
        points = [(stamp, commit, o[key]) for stamp, commit, o in series if key in o]
        values = [ms for _, _, ms in points]
        name = "%-*s" % (width, label(key))
        engine = key.split("|")[-2]
        identity = row_identity(key)
        if len(values) < 2 * min_side:
            print(f"  {name}  runs={len(values)}  insufficient runs "
                  f"(need >= {2 * min_side} for a step claim)")
            if identity:
                arms.setdefault(identity, {})[engine] = None      # undecided, never counted as agreement
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
        if identity:
            arms.setdefault(identity, {})[engine] = (stepped, ratio)
        if stepped:
            changed += 1
            stamp, commit, _ = points[i]
            print(f"  {name}  {tail}  CHANGE at {stamp} ({commit}): "
                  f"{math.exp(ml):.2f} -> {math.exp(mr):.2f}ms ({100 * (ratio - 1):+.0f}%)")
        else:
            print(f"  {name}  {tail}  stable")

    for identity in sorted(arms):
        decided = {e: v for e, v in arms[identity].items() if v is not None}
        if len(decided) < 2 or not any(stepped for stepped, _ in decided.values()):
            continue                                   # nothing to reconcile, or nothing stepped
        moved = sorted(e for e, (stepped, _) in decided.items() if stepped)
        held = sorted(e for e, (stepped, _) in decided.items() if not stepped)
        directions = {ratio > 1 for e, (stepped, ratio) in decided.items() if stepped}
        if not held and len(directions) == 1:
            print(f"  ARMS  {ratio_label(identity)}: every arm ({', '.join(moved)}) stepped the same way — "
                  f"reads as the MACHINE re-basing, not as a code change")
        elif not held:
            ways = ", ".join(e + (" up" if r > 1 else " down")
                             for e, (s, r) in sorted(decided.items()) if s)
            print(f"  ARMS  {ratio_label(identity)}: every arm stepped but NOT the same way ({ways}) — "
                  f"opposite directions cannot be one environment shift")
        else:
            print(f"  ARMS  {ratio_label(identity)}: {', '.join(moved)} stepped, {', '.join(held)} held — "
                  f"the arms DISAGREE, so this is not an environment shift; attribute it to the side that moved")

print(f"\n{changed} row(s) reported a change point. This is a report, not a verdict: a change point is what a "
      f"deliberate optimization looks like too.")
PY
