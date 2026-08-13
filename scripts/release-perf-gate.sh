#!/usr/bin/env bash
# THE RELEASE'S PERFORMANCE GATE: run the routine tier, then refuse a run that proved nothing.
#
# It lives in its own script for one reason: every branch below is a REFUSAL, and this project ships a refusal
# only once its firing is tested. Inside publish-release.sh these branches could not be driven — that script
# builds native images and pushes to a public mirror — so they were shipped on hand-arithmetic, and one of them
# did not work: under `set -euo pipefail` the skip-count assignment ABORTED on an absent XML glob, so the
# advertised "NO RESULT XMLs" diagnostic was unreachable (verified 2026-08-13: exit 1, message never printed).
# The release still halted, so it was a diagnostic hole rather than an escape, but a refusal whose explanation
# never prints is not the refusal that was advertised. scripts/selftest-release-perf-gate.sh drives every branch
# now, including the clean case that keeps them from being unconditional.
#
# Five ways a green gate can carry no evidence, each refused:
#   1. FAILED    — the gate itself broke a bar.
#   2. VACUOUS   — the anchor declined to assert (foreign machine, unnamed machine, or a shared lane), which
#                  prints a "not asserting" / "anchor not asserted" note and leaves the run green.
#   3. ABSENT    — the lane wrote no result XML directory, so what ran is unknown.
#   4. EMPTY     — the directory exists but holds no XML: same conclusion by a different route.
#   5. SKIPPED   — JUnit reports a skip as success, so a bar needing an absent system property would pass the
#                  gate having measured nothing. Counts are SUMMED across files; one file's zero must not cover
#                  another's skip.
#
# FRESHNESS IS STRUCTURAL, not a timestamp comparison. The lane's result directory is DELETED before the gate
# runs, so any XML read back was necessarily written by this invocation and a previous run's evidence cannot
# license this release. An mtime check was written first and rejected: `-nt` can compare whole seconds, so a gate
# that writes its XML in the same second as the reference file would be refused as stale — a false red on a
# release path, and one no test would reliably reproduce. The remaining way an XML could be someone else's is a
# build-cache restore, which is excluded one layer out: `perfGateTest` is non-cacheable and always-executes, and
# `GateTierWiringTest` pins exactly that.
#
# Usage: release-perf-gate.sh [gate-command ...]      (default: scripts/perf.sh gate)
#   RK_PERF_GATE_LOG      where to write the gate's full output   (default build/release-perf-gate.log)
#   RK_PERF_GATE_XML_DIR  the lane's JUnit result XML directory   (default adapter/build/test-results/perfGateTest)
# The three seams exist so the self-test can drive the refusals against fabricated artifacts; the release path
# passes none of them and gets the real gate.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PERF_LOG="${RK_PERF_GATE_LOG:-$ROOT/build/release-perf-gate.log}"
PERF_XML_DIR="${RK_PERF_GATE_XML_DIR:-$ROOT/adapter/build/test-results/perfGateTest}"
GATE_CMD=("$@")
[ "${#GATE_CMD[@]}" -gt 0 ] || GATE_CMD=("$ROOT/scripts/perf.sh" gate)

mkdir -p "$(dirname "$PERF_LOG")"
rm -rf "$PERF_XML_DIR"

if ! "${GATE_CMD[@]}" > "$PERF_LOG" 2>&1; then
    echo "PERFORMANCE GATE FAILED — the release is stopped. See $PERF_LOG" >&2
    grep -E '\[perf\]|FAILED|expected|breach' "$PERF_LOG" | head -20 >&2 || true
    exit 1
fi

# (2) A PASS is only a pass if the bar actually asserted.
if grep -qE 'not asserting|anchor not asserted' "$PERF_LOG"; then
    echo "PERFORMANCE GATE WAS VACUOUS — it ran but asserted nothing, so it cannot license a release." >&2
    grep -E 'not asserting|anchor not asserted' "$PERF_LOG" | head -5 >&2
    echo "Refresh the anchor on THIS machine (scripts/perf-anchor.sh refresh) or release from the machine that authored it." >&2
    exit 1
fi

# (3)+(4) Read the lane's own report rather than the log: the XMLs are the artifact, and a log line can be absent
# for unrelated reasons. `find`, never a glob — an absent directory must yield an empty list, not a shell error.
# `while read` rather than `mapfile`, which needs bash 4 and macOS ships 3.2.
XMLS=()
while IFS= read -r xml; do
    XMLS+=("$xml")
done < <(find "$PERF_XML_DIR" -maxdepth 1 -name '*.xml' -type f 2>/dev/null | sort)
if [ "${#XMLS[@]}" -eq 0 ]; then
    echo "PERFORMANCE GATE LEFT NO RESULT XMLs in $PERF_XML_DIR — cannot confirm what ran." >&2
    echo "The lane did not report. Run ./scripts/perf.sh gate directly and check that :adapter:perfGateTest executed." >&2
    exit 1
fi

# (5) Sum `skipped=` across every file. One awk over the files rather than a grep pipeline: a pipeline whose
# first stage matches nothing exits non-zero, which under pipefail is what made refusal (3) unreachable. This
# form always exits 0 and prints 0 for no matches, so the branch below decides rather than `set -e`.
SKIPPED="$(awk '{
    line = $0
    while (match(line, /skipped="[0-9]+"/)) {
        total += substr(line, RSTART + 9, RLENGTH - 10)
        line = substr(line, RSTART + RLENGTH)
    }
} END { print total + 0 }' "${XMLS[@]}")"
if [ "$SKIPPED" -ne 0 ]; then
    echo "PERFORMANCE GATE SKIPPED $SKIPPED test(s) — a skipped bar is not a passed bar." >&2
    grep -l 'skipped="[1-9]' "${XMLS[@]}" 2>/dev/null | sed 's|.*TEST-|  |;s|\.xml$||' >&2 || true
    echo "Either give the skipped bar what it needs to run, or move it out of the routine tier (@Tag 'perf-bench')." >&2
    exit 1
fi

echo "   performance gate asserted and passed (${#XMLS[@]} result XMLs, 0 skipped, all written by this run)"
