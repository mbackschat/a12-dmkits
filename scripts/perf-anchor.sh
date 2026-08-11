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
#                                                            (refuses to leave any lane the anchor carries
#                                                             without a bar; ALLOW_REMOVALS=1 overrides)
#   scripts/perf-anchor.sh show    [observations.jsonl ...]   print the diff a refresh WOULD make, change nothing
#
# PREFER SEVERAL RUNS. With more than one input the per-key anchor is their MEDIAN, which is what makes the
# tolerance tightenable: a single run's sample gives a lane anchored on a fast run a tighter-than-advertised bar
# and one anchored on a slow run a looser one (IG120). All inputs must come from the same runner class.
#
# The input defaults to the newest run recorded under `perf-results/`, which `scripts/perf.sh gate` writes there.
#
# AUTHORING A HOSTED RUNNER'S BLOCK. Its numbers can only come from a hosted run, and the nightly is dispatchable,
# so no schedule has to be waited for — once the revision under measurement is on the remote:
#
#   gh workflow run 'Nightly performance' --ref <branch>     # then `gh run watch` to follow it
#   gh run download <run-id> --name perf-observations --dir /tmp/anchor
#   scripts/perf-anchor.sh show /tmp/anchor/perf-results/*/*.jsonl

#
# DISPATCH THE RUNS ONE AT A TIME. Hosted runners are VMs on SHARED physical hosts, so concurrent jobs of your own
# contend for cache and memory bandwidth — which is spent in user-mode cycles and therefore inflates the CPU clock
# too. Three concurrently dispatched runs differed by 1.94x at the whole-run level, tracking their wall times
# monotonically; `gh workflow run` xN is not isolation just because each job gets its own VM. This is the remote
# form of CLAUDE.md's "a task carrying a timing assertion gets the machine to itself". Wait for each run to finish
# before dispatching the next, then download each artifact into its own directory and pass them all to one refresh.
set -uo pipefail
cd "$(dirname "$0")/.."

mode="${1:-show}"
shift || true
# Default: the single newest recorded run. Pass a glob (perf-results/<environment>/*.jsonl) to anchor at the
# per-key MEDIAN over several runs, which is what makes the tolerance tightenable.
if [ "$#" -eq 0 ]; then
    newest="$(find perf-results -name '*.jsonl' -type f -print0 2>/dev/null | xargs -0 ls -t 2>/dev/null | head -1)"
    [ -n "$newest" ] || { echo "no recorded runs under perf-results/ — run scripts/perf.sh gate first" >&2; exit 1; }
    set -- "$newest"
fi
# The committed anchor. Overridable so PerfAnchorScriptTest can EXECUTE this script — its refusals are the
# mechanism, so they are worth an executed oracle rather than a source-text guard.
anchor="${ANCHOR_FILE:-adapter/src/test/resources/perf/anchors.json}"

case "$mode" in
    refresh|show) ;;
    *) echo "usage: scripts/perf-anchor.sh {refresh|show} [observations.jsonl]" >&2; exit 2 ;;
esac

for input in "$@"; do
    [ -f "$input" ] || { echo "no observations at $input — run scripts/perf.sh gate first" >&2; exit 1; }
done
[ -f "$anchor" ] || { echo "missing anchor file $anchor" >&2; exit 1; }

commit="$(git rev-parse --short HEAD)" || { echo 'cannot resolve HEAD' >&2; exit 1; }
# An anchor captured from a dirty tree does not correspond to the commit it names. perf-results/ is excluded on
# purpose: a gate run always leaves a NEW record file there, and that file is the refresh's own input, so counting
# it as dirt would mark every honest anchor DIRTY.
if [ -n "$(git status --porcelain -- . ':(exclude)perf-results')" ]; then commit="$commit-DIRTY"; fi
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

# The invariant is "the input measured every lane the anchor knows about", NOT "every lane THIS block had" — a key
# names the lane, scenario fingerprint, scale, engine and clock, never the machine, so the key sets of two runner
# classes are directly comparable. That difference is the whole point for a NEW class: it has no previous block, so a
# same-block comparison cannot see a hole in it (found by rehearsing the IG120 step against a 34-of-36 input).
anchored_elsewhere = {}
for other_name, other_block in anchor.get("environments", {}).items():
    for key in other_block.get("observations", {}):
        anchored_elsewhere.setdefault(key, []).append(other_name)

added = sorted(k for k in observations if k not in previous)
missing = sorted(k for k in anchored_elsewhere if k not in observations)
changed = sorted(k for k in observations if k in previous and previous[k] != observations[k])

print(f"environment {environment}  ({len(observations)} observations from {runs} run(s))")
for key in added:
    print(f"  + {key} = {observations[key]}ms")
for key in changed:
    old, new = previous[key], observations[key]
    print(f"  ~ {key} = {old}ms -> {new}ms ({new / old:.2f}x)")
for key in missing:
    if key in previous:
        print(f"  - {key} (was {previous[key]}ms, not measured in this run)")
    else:
        print(f"  ! {key} (not measured; anchored on {', '.join(anchored_elsewhere[key])})")
if not (added or changed or missing):
    print("  (no change)")

if mode == "show":
    print("\nshow only — pass 'refresh' to write it")
    raise SystemExit(0)

# A refresh REPLACES the block, so a lane the input did not measure ends up with no bar — deleted from an existing
# block, or never written into a new one. Both are the "the bar silently disappears" case this mechanism exists to
# refuse, and neither is hypothetical: a lane whose growth floor breached contributes no observation, and the nightly
# uploads its record precisely WHEN the run breached, so the documented recovery path is the one most likely to be fed
# a partial run.
if missing and not os.environ.get("ALLOW_REMOVALS"):
    shown = "".join(f"\n    {key}" for key in missing[:5])
    if len(missing) > 5:
        shown += f"\n    … and {len(missing) - 5} more"
    print(
        f"\nREFUSED: this input does not measure {len(missing)} lane(s) the anchor carries, so the block it would"
        f" write for {environment} leaves them with no bar — and this is the only bar that sees a uniform regression:"
        f"{shown}"
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
