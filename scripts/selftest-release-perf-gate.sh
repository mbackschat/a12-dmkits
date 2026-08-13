#!/usr/bin/env bash
# Behavioral guard for scripts/release-perf-gate.sh — the POSITIVE CONTROL its refusals lacked.
#
# The release's performance gate refuses a run that carries no evidence: the gate command failed, the anchor
# declined to assert ("vacuous"), the lane reported nothing, or a bar was SKIPPED (JUnit reports a skip as
# success). Those were shell branches nothing drove, and one of them did not work — see the header of the script
# under test. This drives each branch with a FABRICATED gate command, log and result-XML directory, and closes
# with a clean case so the refusals cannot be vacuously unconditional.
#
# The fake gate WRITES ITS XMLs while it runs, exactly as the real lane does. That matters: a fake that only
# emitted log lines while the XMLs sat there from before would let this self-test pass on evidence no invocation
# produced — which is the very thing the script's clear-then-run makes impossible, so the control has to exercise
# it rather than sidestep it.
#
# Self-contained; needs only bash + awk + find. Run by ReleasePerfGateRefusalTest, so it rides :test and CI.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GATE="$ROOT/scripts/release-perf-gate.sh"
[ -x "$GATE" ] || { echo "SELFTEST FAIL: $GATE is missing or not executable" >&2; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
XML_DIR="$WORK/results"
fail() { echo "SELFTEST FAIL: $*" >&2; exit 1; }

# The fake gate: prints $FAKE_GATE_LOG_BODY, writes one result XML per entry of $FAKE_GATE_XMLS (`class:skipped`),
# then exits $FAKE_GATE_EXIT. Writing during the invocation is the point (see the header).
cat > "$WORK/fake-gate.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${FAKE_GATE_LOG_BODY:-}"
if [ -n "${FAKE_GATE_XMLS:-}" ]; then
    mkdir -p "$RK_PERF_GATE_XML_DIR"
    for spec in $FAKE_GATE_XMLS; do
        printf '<testsuite name="%s" tests="3" skipped="%s" failures="0" errors="0"/>\n' \
            "${spec%%:*}" "${spec##*:}" > "$RK_PERF_GATE_XML_DIR/TEST-${spec%%:*}.xml"
    done
fi
exit "${FAKE_GATE_EXIT:-0}"
STUB
chmod +x "$WORK/fake-gate.sh"

# Run the gate under fabricated conditions. $1 log body, $2 exit code, $3 XML specs the fake gate writes.
out=""; code=0
run_gate() {
    set +e
    out="$(FAKE_GATE_LOG_BODY="$1" FAKE_GATE_EXIT="$2" FAKE_GATE_XMLS="$3" \
        RK_PERF_GATE_LOG="$WORK/gate.log" RK_PERF_GATE_XML_DIR="$XML_DIR" \
        bash "$GATE" "$WORK/fake-gate.sh" 2>&1)"
    code=$?
    set -e
}

CLEAN='[perf] B1-per-row growth 4.21x (floor 9.0) accepted
[perf] anchor asserted at 1.02x of its 1.65x tolerance'
OK_XMLS='PerfGateTest:0 CharsetPerfTest:0 PatternPerfTest:0'

# --- 1. the gate command itself failed ---------------------------------------------------------------
run_gate "$CLEAN
FAILED: growth floor breached" 1 "$OK_XMLS"
[ "$code" -ne 0 ] || fail "a FAILING gate command was accepted (exit 0)"
grep -q 'PERFORMANCE GATE FAILED' <<<"$out" || fail "a failing gate did not say so: $out"

# --- 2 + 3. vacuous: the anchor declined to assert, both wordings ------------------------------------
for note in 'not asserting: the anchor block was captured on another machine' \
            '[perf] absolute anchor not asserted (shared machine)'; do
    run_gate "$CLEAN
$note" 0 "$OK_XMLS"
    [ "$code" -ne 0 ] || fail "a VACUOUS gate was accepted (exit 0) for note: $note"
    grep -q 'VACUOUS' <<<"$out" || fail "a vacuous gate did not say so for note '$note': $out"
done

# --- 4. the lane wrote no result XML at all ----------------------------------------------------------
run_gate "$CLEAN" 0 ''
[ "$code" -ne 0 ] || fail "a gate that reported NOTHING was accepted (exit 0)"
grep -q 'NO RESULT XML' <<<"$out" || fail "an absent report did not say so: $out"
# ... and this is the branch that `set -euo pipefail` made unreachable, so assert the DIAGNOSTIC printed, not
# merely that the exit code was non-zero — an abort at the assignment also exits non-zero, silently.
grep -q 'perfGateTest executed' <<<"$out" || fail "the absent-report refusal aborted before its guidance: $out"

# --- 5. an empty directory reaches the same branch ---------------------------------------------------
rm -rf "$XML_DIR"; mkdir -p "$XML_DIR"
run_gate "$CLEAN" 0 ''
[ "$code" -ne 0 ] || fail "an EMPTY result directory was accepted (exit 0)"
grep -q 'NO RESULT XML' <<<"$out" || fail "an empty result directory did not say so: $out"

# --- 6. STALE: evidence that predates this invocation cannot license a release -----------------------
# Freshness is structural — the script clears the directory before running — so a gate contributing nothing must
# be refused even though a full, clean, zero-skip report is sitting there when it starts.
rm -rf "$XML_DIR"; mkdir -p "$XML_DIR"
for spec in $OK_XMLS; do
    printf '<testsuite name="%s" tests="3" skipped="0" failures="0" errors="0"/>\n' \
        "${spec%%:*}" > "$XML_DIR/TEST-${spec%%:*}.xml"
done
run_gate "$CLEAN" 0 ''
[ "$code" -ne 0 ] || fail "a PREVIOUS run's result XMLs licensed a release (exit 0)"
grep -q 'NO RESULT XML' <<<"$out" || fail "stale evidence was not rejected as absent: $out"

# --- 7. one skipped bar ------------------------------------------------------------------------------
run_gate "$CLEAN" 0 'PerfGateTest:0 CharsetPerfTest:1'
[ "$code" -ne 0 ] || fail "a SKIPPED bar was accepted (exit 0)"
grep -q 'SKIPPED 1 test' <<<"$out" || fail "a skipped bar was not counted as 1: $out"
grep -q 'CharsetPerfTest' <<<"$out" || fail "the refusal did not name the skipping class: $out"

# --- 8. skips SUM across files (2 + 1 = 3), so no file can hide behind another's zero ----------------
run_gate "$CLEAN" 0 'PerfGateTest:2 CharsetPerfTest:1 PatternPerfTest:0'
[ "$code" -ne 0 ] || fail "3 skipped bars across files were accepted (exit 0)"
grep -q 'SKIPPED 3 test' <<<"$out" || fail "skips did not sum across files to 3: $out"

# --- 9. THE POSITIVE CONTROL: a clean run must PASS, or nothing above proves anything ----------------
run_gate "$CLEAN" 0 "$OK_XMLS"
[ "$code" -eq 0 ] || fail "a CLEAN gate run was refused (exit $code) — the refusals are unconditional: $out"
grep -q 'asserted and passed' <<<"$out" || fail "a clean run did not report a pass: $out"
grep -q '3 result XMLs, 0 skipped' <<<"$out" || fail "a clean run did not report what it read: $out"

# --- 10. an XML with no `skipped` attribute at all must not abort the sum ----------------------------
# The awk sum replaced a grep pipeline for this reason: no match used to mean a non-zero exit, and under
# pipefail that aborted the script instead of reaching a decision.
rm -rf "$XML_DIR"
FAKE_GATE_LOG_BODY="$CLEAN" FAKE_GATE_EXIT=0 FAKE_GATE_XMLS='' \
    RK_PERF_GATE_LOG="$WORK/gate.log" RK_PERF_GATE_XML_DIR="$XML_DIR" \
    bash -c 'mkdir -p "$1"; printf "<testsuite name=\"NoAttrTest\" tests=\"1\"/>\n" > "$1/TEST-NoAttrTest.xml"' _ "$WORK/preseed" \
    >/dev/null
cat > "$WORK/seeding-gate.sh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "${FAKE_GATE_LOG_BODY:-}"
mkdir -p "$RK_PERF_GATE_XML_DIR"
cp "$SEED_DIR"/*.xml "$RK_PERF_GATE_XML_DIR/"
STUB
chmod +x "$WORK/seeding-gate.sh"
set +e
out="$(FAKE_GATE_LOG_BODY="$CLEAN" SEED_DIR="$WORK/preseed" \
    RK_PERF_GATE_LOG="$WORK/gate.log" RK_PERF_GATE_XML_DIR="$XML_DIR" \
    bash "$GATE" "$WORK/seeding-gate.sh" 2>&1)"
code=$?
set -e
[ "$code" -eq 0 ] || fail "an XML without a skipped attribute aborted the sum (exit $code): $out"

echo "selftest-release-perf-gate.sh: 10 cases OK (failed, vacuous x2, absent, empty, stale, skipped, summed, clean, no-attribute)"
