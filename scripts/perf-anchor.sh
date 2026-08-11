#!/usr/bin/env bash
#
# perf-anchor.sh — refresh the committed per-runner-class performance anchor from a gate run's observations.
#
# WHY THIS IS A DELIBERATE, HUMAN-RUN STEP. The anchor is the only bar that can see a UNIFORM interpreter
# slowdown: a growth ratio is unchanged by one at every factor, and the interp/kernel bars need 2.4x-3800x
# (docs/INTERPRETER-PERFORMANCE.md "the blind-band audit"). Because absolute timings drift 2.07x-2.69x between
# runner classes, the comparison is against the SAME class's own recorded numbers — and because a rolling
# baseline would let successive within-tolerance degradations compound into an unbounded one, the numbers are
# committed and only move when someone moves them. Refreshing is therefore a reviewable diff, and it belongs in
# the same commit as the change that made the cost legitimately higher.
#
# Usage:
#   scripts/perf-anchor.sh refresh [observations.jsonl ...]   fold run observations into the anchor
#                                                            (refuses to DELETE anchors the inputs did not
#                                                             measure; ALLOW_REMOVALS=1 overrides)
#   scripts/perf-anchor.sh show    [observations.jsonl ...]   print the diff a refresh WOULD make, change nothing
#
# PREFER SEVERAL RUNS. With more than one input the per-key anchor is their MEDIAN, which is what makes the
# tolerance tightenable: a single run's sample gives a lane anchored on a fast run a tighter-than-advertised bar
# and one anchored on a slow run a looser one (IG120). All inputs must come from the same runner class.
#
# The input defaults to adapter/build/perf-observations.jsonl, which `scripts/perf.sh gate` writes. A hosted
# runner's block can only be authored from a hosted run, so take that file from the nightly workflow's uploaded
# `perf-observations` artifact and pass it here.
set -uo pipefail
cd "$(dirname "$0")/.."

mode="${1:-show}"
shift || true
if [ "$#" -eq 0 ]; then set -- adapter/build/perf-observations.jsonl; fi
anchor="adapter/src/test/resources/perf/anchors.json"

case "$mode" in
    refresh|show) ;;
    *) echo "usage: scripts/perf-anchor.sh {refresh|show} [observations.jsonl]" >&2; exit 2 ;;
esac

for input in "$@"; do
    [ -f "$input" ] || { echo "no observations at $input — run scripts/perf.sh gate first" >&2; exit 1; }
done
[ -f "$anchor" ] || { echo "missing anchor file $anchor" >&2; exit 1; }

commit="$(git rev-parse --short HEAD)" || { echo 'cannot resolve HEAD' >&2; exit 1; }
# An anchor captured from a dirty tree does not correspond to the commit it names.
if [ -n "$(git status --porcelain)" ]; then commit="$commit-DIRTY"; fi
captured="$(date -u +%F)"

MODE="$mode" ANCHOR="$anchor" COMMIT="$commit" CAPTURED="$captured" python3 - "$@" <<'PY'
import json, os, statistics, sys

mode, anchor_path = os.environ["MODE"], os.environ["ANCHOR"]
inputs = sys.argv[1:]

run, samples = None, {}
for input_path in inputs:
    header = None
    with open(input_path) as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            if record.get("kind") == "run":
                header = record
            elif record.get("kind") == "observation":
                samples.setdefault(record["key"], []).append(float(record["medianMs"]))
    if header is None:
        sys.exit(f"{input_path} has no run header — it was not written by a complete gate run")
    if run is not None and header["environment"] != run["environment"]:
        sys.exit(
            f"{input_path} was measured on {header['environment']} but a previous input on {run['environment']};"
            " absolute times are only comparable within one runner class"
        )
    run = header

if not samples:
    sys.exit("the inputs carry no observations")

# With several runs the anchor is the per-key MEDIAN, so one fast or one contended run cannot set the bar.
observations = {key: round(statistics.median(values), 2) for key, values in samples.items()}
runs = len(inputs)
partial = sorted(key for key, values in samples.items() if len(values) != runs)
if partial:
    print(f"note: {len(partial)} key(s) were not measured by all {runs} input(s); their median uses fewer samples")

environment = run["environment"]
with open(anchor_path) as handle:
    anchor = json.load(handle)
block = anchor.setdefault("environments", {}).get(environment, {})
previous = block.get("observations", {})

added = sorted(k for k in observations if k not in previous)
removed = sorted(k for k in previous if k not in observations)
changed = sorted(k for k in observations if k in previous and previous[k] != observations[k])

print(f"environment {environment}  ({len(observations)} observations from {runs} run(s))")
for key in added:
    print(f"  + {key} = {observations[key]}ms")
for key in changed:
    old, new = previous[key], observations[key]
    print(f"  ~ {key} = {old}ms -> {new}ms ({new / old:.2f}x)")
for key in removed:
    print(f"  - {key} (was {previous[key]}ms, not measured in this run)")
if not (added or changed or removed):
    print("  (no change)")

if mode == "show":
    print("\nshow only — pass 'refresh' to write it")
    raise SystemExit(0)

# A refresh REPLACES the block, so a key the input did not measure would be deleted — and deleting an anchor is
# exactly the "the bar silently disappears" case this mechanism exists to refuse. It is not hypothetical: a lane
# whose growth floor breached contributes no observation, and the nightly uploads its record precisely when the run
# breached, so the documented recovery path is the one most likely to be fed a partial run.
if removed and not os.environ.get("ALLOW_REMOVALS"):
    print(
        f"\nREFUSED: this input does not measure {len(removed)} anchor(s) that {environment} currently carries, and a"
        f" refresh would delete them — removing the only bar that sees a uniform regression on those lanes."
        f"\nFeed a COMPLETE gate run, or, if the lanes are genuinely gone, re-run with ALLOW_REMOVALS=1.",
        file=sys.stderr,
    )
    raise SystemExit(1)

anchor["environments"][environment] = {
    "capturedAt": os.environ["CAPTURED"],
    "commit": os.environ["COMMIT"],
    "os": run.get("os"),
    "arch": run.get("arch"),
    "jdk": run.get("jdk"),
    "processors": run.get("processors"),
    "observations": dict(sorted(observations.items())),
}
with open(anchor_path, "w") as handle:
    json.dump(anchor, handle, indent=2, sort_keys=False)
    handle.write("\n")
print(f"\nwrote {anchor_path}")
PY
