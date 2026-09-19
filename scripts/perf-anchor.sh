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
#                                                            (refuses to leave a lane of THIS block without a bar —
#                                                             of every anchored class when the block is new;
#                                                             ALLOW_REMOVALS=1 overrides. Also refuses a LOCAL run
#                                                             captured above 1.00 runnable threads per core;
#                                                             ALLOW_CONTENDED=1 overrides)
#   scripts/perf-anchor.sh show    [observations.jsonl ...]   print the diff a refresh WOULD make, change nothing
#
# PREFER SEVERAL RUNS. With more than one input the per-key anchor is their MEDIAN, which is what made the
# tolerance tightenable: a single run's sample gives a lane anchored on a fast run a tighter-than-advertised bar
# and one anchored on a slow run a looser one (IF280). All inputs must come from the same runner class AND the same
# machine — the block records which one, because the gated lane refuses to assert against a block it cannot match.
# HOSTED classes (environment ending `-ci`) are the exception and are treated as machine-less: the provider picks
# the CPU per job, so runs are folded across models and the block records no machine. Do not try to pin one.
#
# The input defaults to the newest run recorded under `perf-results/`, which `scripts/perf.sh gate` writes there.
#
# AUTHORING A HOSTED RUNNER'S BLOCK. Its numbers can only come from a hosted run, and the nightly is dispatchable,
# so no schedule has to be waited for — once the revision under measurement is on the remote:
#
#   gh workflow run 'Nightly performance' --ref <branch>     # then `gh run watch` to follow it
#   gh run download <run-id> --name perf-observations --dir /tmp/anchor-<n>
#   scripts/perf-anchor.sh show /tmp/anchor-*/perf-results/*/*.jsonl
#
# The artifact carries ONLY that run's own record, so each download needs its own directory and the glob above then
# feeds every downloaded run to one refresh. Passing runs of two revisions is refused rather than averaged, as is
# mixing machines on a NON-hosted class — folding revisions averages away the change one of
# them introduced.

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
    # Ordered by the filename's UTC stamp, never by mtime: a clone, checkout or stash rewrites mtime, which would
    # make "newest" depend on when the tree was materialized rather than when the run happened.
    #
    # Sorted on the BASENAME, not the path. Sorting whole paths puts the environment DIRECTORY ahead of the stamp,
    # so with more than one recorded environment "newest" degenerates to "whichever directory name sorts last" —
    # `perf-results/linux-amd64-ci/…` beats every `perf-results/darwin-aarch64/…` however old it is. Measured
    # 2026-08-13: a fresh local gate run was ignored and the refresh silently rewrote the LINUX block instead,
    # downgrading a committed 2-run median to a 1-run block measured on different silicon.
    # RESULTS_DIR is overridable for the same reason ANCHOR_FILE is: this script `cd`s to the repo root, so the
    # default-input path cannot otherwise be executed by a test — and it was the one path with no case, which is
    # where the path-sort defect survived.
    newest="$(find "${RESULTS_DIR:-perf-results}" -name '*.jsonl' -type f 2>/dev/null \
        | awk -F/ '{ print $NF "\t" $0 }' | sort | tail -1 | cut -f2-)"
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

run, samples, headers = None, {}, []
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
                # `breached` would ratchet the anchor to a value the gate REJECTED, and `unasserted` was taken
                # outside the isolated single-fork lane where an absolute time is a coin flip — neither may set a
                # bar. `unanchored` may: it means this key had no anchor at all, so there is nothing to ratchet
                # against, and it is precisely the observation a refresh needs to AUTHOR that key. Excluding it
                # left the documented recovery path with no way forward but ALLOW_REMOVALS. A record predating
                # the status field (an imported run) is trusted, as it always was.
                # `flagged` folds too: on shared hosted hardware a breach is a FLAG, not a verdict, and excluding
                # it made one noise-driven flag drop that lane from the artifact — after which this script refuses
                # the whole refresh for incomplete coverage. `breached` stays excluded: there the gate REJECTED the
                # number, so folding it would ratchet the bar to a value a build already failed on.
                if record.get("status", "accepted") not in ("accepted", "unanchored", "flagged"):
                    continue
                samples.setdefault(record["key"], []).append(float(record["medianMs"]))
    if header is None:
        sys.exit(f"{input_path} has no run header — it was not written by a complete gate run")
    # Two ways a run fails to identify its machine, with DIFFERENT correctives — and a truthiness test only sees
    # the first. `"unknown"` is the probe's own failure sentinel (MachineIdentity.UNKNOWN_MODEL, emitted when the
    # OS-level model string cannot be read; reachable on linux-aarch64, whose /proc/cpuinfo carries CPU
    # implementer/part and no model name). A block carrying it is WORSE than one carrying nothing: the gated lane
    # falls to its MISMATCH arm and reports "captured on unknown xN and this run measured on ...", which reads as a
    # machine swap rather than as a block that was authored wrong. Re-measuring cannot fix that one — the probe has
    # to learn the machine — so the two say different things.
    # A HOSTED runner class does not have a machine, and pretending otherwise is unmaintainable. The provider
    # hands out whatever CPU it likes per job — three consecutive dispatches on 2026-08-16 landed on three
    # different models while the committed block named a fourth — and the fleet is not enumerable.
    # Requiring one machine there means a refresh can only proceed after enough dispatches happen to collide on
    # one model, which is a lottery that gets worse as the provider's fleet grows. So for a hosted class the
    # machine is NOT required and NOT compared: the block is a bar over the runner CLASS, exactly as the gated
    # lane already treats it (TimingEnforcement.FLAGGED — "a breach here is flagged, never failed"), and folding
    # across models is what makes the median represent the class instead of one lucky draw.
    hosted = str(header.get("environment", "")).endswith("-ci")
    machine = (header.get("machine") or "").strip()
    if not hosted and (not machine or machine == "unknown"):
        sys.exit(
            f"{input_path} does not name the machine it was measured on, so the block it would write cannot be"
            " asserted against: the gated lane refuses a block whose machine it cannot match, which makes it a bar"
            " that never fires."
            + (" Re-measure with a current gate run (records written before 2026-08-12 predate the field)."
               if not machine else
               " It reports machine='unknown', the probe's own failure sentinel rather than an identity, so"
               " re-measuring will produce it again: teach PerfAnchors.machine to name this machine first.")
        )
    # A CONTENDED capture is not a measurement, and the per-key median below does not rescue it. That median
    # bounds ONE contended run among several; a capture SESSION on a busy machine inflates every input alike, so
    # the median is inflated too. Load is recorded on every run record and, until 2026-09-19, compared nowhere —
    # the "machine to itself" rule lived in prose and in whoever remembered it. Measured over the 30 committed
    # darwin runs: the median run sits at 0.54 runnable threads per core, and six sit above 1.00 with the worst
    # at 3.34. Above 1.00 there are more runnable threads than cores by definition, so the run was competing for
    # the CPU it was measuring. HOSTED classes are exempt because the bar means something different there: all
    # ten committed CI runs sit between 1.01 and 1.43, a 2-core VM being at capacity from the job itself, and
    # that class is already a flag-not-fail bar over the runner CLASS rather than over a machine.
    # An ABSENT loadAverage is trusted, on the same precedent as a record predating the status field: every
    # current gate run writes it, so only imported history lacks it.
    load, cores = header.get("loadAverage"), header.get("processors")
    if not hosted and load is not None and cores and load / cores > 1.0 \
            and not os.environ.get("ALLOW_CONTENDED"):
        sys.exit(
            f"{input_path} was captured at load {load:.2f} on {cores} processors"
            f" ({load / cores:.2f} runnable threads per core), so it measured a machine it was competing with."
            " Folding it would bar every later run against a contended number, and the per-key median cannot"
            " undo that when the whole capture session was contended."
            " Re-capture on a quiet machine, or re-run with ALLOW_CONTENDED=1 if that load was not CPU work."
        )
    identity = [("environment", "absolute times are only comparable within one runner class"),
                ("commit", "folding revisions would average away the change one of them introduced")]
    if not hosted:
        identity += [("machine", "an absolute time is a property of the machine, so two machines cannot be folded"),
                     ("processors", "a processor count is part of that machine's identity")]
    for field, why in identity:
        if run is not None and header.get(field) != run.get(field):
            sys.exit(
                f"{input_path} reports {field}={header.get(field)!r} but a previous input {run.get(field)!r}; {why}."
                + (" Refresh from runs of one revision." if hosted else
                   " Refresh from runs of one machine and one revision.")
            )
    headers.append(header)
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

# Which keys are anchored where. A key names the lane, scenario fingerprint, scale, engine and clock, never the
# machine, so the key sets of two runner classes are directly comparable.
anchored_anywhere = {}
for other_name, other_block in anchor.get("environments", {}).items():
    for key in other_block.get("observations", {}):
        anchored_anywhere.setdefault(key, []).append(other_name)

# WHAT THIS INPUT MUST MEASURE, and the two cases differ:
#   * an EXISTING block -> its own keys. Anything it carried that this run did not measure is a real removal.
#   * a NEW block -> the union across the other classes, since there is no own set to compare against and a partial
#     run would otherwise author a block full of silent holes. That is the hosted-authoring case, and it is not
#     hypothetical:
#     the nightly uploads its record precisely WHEN a lane breached, so the documented recovery path is the one
#     most likely to be fed an incomplete run (rehearsed against a 34-of-36 input).
# Requiring the union of an EXISTING block was wrong. Two classes legitimately diverge while a scenario's
# fingerprint is re-measured class by class, and under that rule the FIRST refresh of either one is refused for
# carrying the other's now-stale keys — so ALLOW_REMOVALS becomes the routine way to run this, which retires the
# refusal altogether. A bar nobody can refresh without the override is a bar nobody keeps.
required = set(previous) if previous else set(anchored_anywhere)

added = sorted(k for k in observations if k not in previous)
missing = sorted(k for k in required if k not in observations)
elsewhere_only = sorted(k for k in anchored_anywhere if k not in observations and k not in required)
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
        print(f"  ! {key} (not measured; anchored on {', '.join(anchored_anywhere[key])})")
for key in elsewhere_only:
    where = ", ".join(anchored_anywhere[key])
    print(f"  · {key} (not measured; anchored only on {where}, so not required of this block)")
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

# Derived from the INPUTS, not from HEAD and today: the block describes the runs it was measured from. Stamping
# the current checkout made a block of 1845f8af data claim the commit that happened to be checked out at refresh
# time, and `jdk` silently took the last input's value while hiding that another run used a different one.
jdks = sorted({h.get("jdk") for h in headers if h.get("jdk")})
# The VM FAMILY beside the version, because the version alone cannot separate the runtimes: Oracle GraalVM 21
# reports 21.0.12 and Homebrew OpenJDK reports 21.0.12.1, differing by coincidence rather than by family. The
# run record gained this field on 2026-08-23; the BLOCK is the artifact a reader actually consults, and it was
# reading the block's bare `21.0.12` that produced the "GraalVM-era" misreading recorded in the results log.
jdk_families = sorted({h.get("jdkFamily") for h in headers if h.get("jdkFamily")})
hosted_class = str(environment).endswith("-ci")
anchor["environments"][environment] = {
    "capturedAt": max((h.get("capturedAt") or "" for h in headers), default="") or os.environ["CAPTURED"],
    "refreshedAt": os.environ["CAPTURED"],
    "commit": run.get("commit") or os.environ["COMMIT"],
    "runs": len(headers),
    "os": run.get("os"),
    "arch": run.get("arch"),
    "jdk": jdks[0] if len(jdks) == 1 else ",".join(jdks),
    **({"jdkFamily": jdk_families[0] if len(jdk_families) == 1 else ",".join(jdk_families)}
       if jdk_families else {}),
    # `processors` is dropped for a hosted class for the same reason as `machine`: it is no longer part of the
    # fold identity there, so writing the last input's value would record the identity of whichever machine
    # happened to serve the final run, as if it were the class's.
    **({} if hosted_class else {"processors": run.get("processors")}),
    # The machine, not just its core count: the gated lane asserts only against a block captured on the machine it is
    # running on, so a block that does not name one is a bar that never fires (IF280). A HOSTED class is exempt and
    # records NO machine, because it does not have one — the provider picks the CPU per job. Recording the model that
    # happened to serve one refresh would state an identity the class cannot honour, and the lane already treats a
    # hosted block as a bar over the CLASS (TimingEnforcement.FLAGGED).
    **({} if hosted_class else {"machine": run.get("machine")}),
    "observations": dict(sorted(observations.items())),
}
with open(anchor_path, "w") as handle:
    json.dump(anchor, handle, indent=2, sort_keys=False)
    handle.write("\n")
print(f"\nwrote {anchor_path}")
PY
