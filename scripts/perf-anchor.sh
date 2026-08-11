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
#   scripts/perf-anchor.sh refresh [observations.jsonl]   fold a run's observations into the anchor
#   scripts/perf-anchor.sh show    [observations.jsonl]   print the diff the refresh WOULD make, change nothing
#
# The input defaults to adapter/build/perf-observations.jsonl, which `scripts/perf.sh gate` writes. A hosted
# runner's block can only be authored from a hosted run, so take that file from the nightly workflow's uploaded
# `perf-observations` artifact and pass it here.
set -uo pipefail
cd "$(dirname "$0")/.."

mode="${1:-show}"
input="${2:-adapter/build/perf-observations.jsonl}"
anchor="adapter/src/test/resources/perf/anchors.json"

case "$mode" in
    refresh|show) ;;
    *) echo "usage: scripts/perf-anchor.sh {refresh|show} [observations.jsonl]" >&2; exit 2 ;;
esac

[ -f "$input" ] || { echo "no observations at $input — run scripts/perf.sh gate first" >&2; exit 1; }
[ -f "$anchor" ] || { echo "missing anchor file $anchor" >&2; exit 1; }

commit="$(git rev-parse --short HEAD)"
captured="$(date -u +%F)"

MODE="$mode" INPUT="$input" ANCHOR="$anchor" COMMIT="$commit" CAPTURED="$captured" python3 - <<'PY'
import json, os, sys

mode, input_path, anchor_path = os.environ["MODE"], os.environ["INPUT"], os.environ["ANCHOR"]

run, observations = None, {}
with open(input_path) as handle:
    for line in handle:
        line = line.strip()
        if not line:
            continue
        record = json.loads(line)
        if record.get("kind") == "run":
            run = record
        elif record.get("kind") == "observation":
            observations[record["key"]] = round(float(record["medianMs"]), 2)

if run is None:
    sys.exit(f"{input_path} has no run header — it was not written by a complete gate run")
if not observations:
    sys.exit(f"{input_path} carries no observations")

environment = run["environment"]
with open(anchor_path) as handle:
    anchor = json.load(handle)
block = anchor.setdefault("environments", {}).get(environment, {})
previous = block.get("observations", {})

added = sorted(k for k in observations if k not in previous)
removed = sorted(k for k in previous if k not in observations)
changed = sorted(k for k in observations if k in previous and previous[k] != observations[k])

print(f"environment {environment}  ({len(observations)} observations)")
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

anchor["environments"][environment] = {
    "capturedAt": os.environ["CAPTURED"],
    "commit": os.environ["COMMIT"],
    "os": run.get("os"),
    "arch": run.get("arch"),
    "jdk": run.get("jdk"),
    "observations": dict(sorted(observations.items())),
}
with open(anchor_path, "w") as handle:
    json.dump(anchor, handle, indent=2, sort_keys=False)
    handle.write("\n")
print(f"\nwrote {anchor_path}")
PY
