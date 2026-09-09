#!/usr/bin/env bash
#
# perf.sh — run an interpreter performance sweep with RELIABLE capture, then print only the result lines.
#
# Why this exists (the foolproofing): the perf tests print their numbers via `println` (test STANDARD_OUT),
# which Gradle only surfaces under `-i`. Three traps bite when capturing that by hand, and this script avoids
# all three:
#   1. NEVER pipe a backgrounded/long build through `grep`/`head` — `head` closes the pipe early (SIGPIPE) and
#      the real output is LOST. So we always write the FULL `-i` log to a file, then grep the FILE afterwards.
#   2. `-i` output buffers, so a live tail looks frozen mid-run — irrelevant here: we only read the file once
#      the run has finished.
#   3. ONE run = self-consistent numbers (interp_ms, kernel-Java_ms and the ratio are all measured together),
#      so an absolute column can never contradict the ratio column (the bug that motivated this).
#
# Usage:  scripts/perf.sh <mode> [counts | method-pattern | scenario-id]
#   gate          PerfGateTest — the ROUTINE gate: the sub-quadratic growth guards + the rich-model kernel
#                 canary (fast; run at every landing). Optional 2nd arg = a method pattern to run ONE check
#                 during a drill loop, e.g.  scripts/perf.sh gate 'semanticIndex*'
#   bench         PerfBenchTest — the KERNEL-RATIO benchmarks (per-scenario Groovy/Java codegen + baselines; the
#                 slow tier — run at perf-relevant checkpoints, not every landing). Optional 2nd arg = one exact
#                 scenario id, e.g. scripts/perf.sh bench B9-berlin-datetime; an unknown id fails.
#   crossover     rule-count sweep vs kernel-Groovy/-Java, both firing regimes.   counts default 6,12,24,48,96,192,384
#   distributed   1-rule-per-field "wide form" sweep vs kernel (kernel codegens fine here).   counts default 192,512,1000,2000
#   scaling       startup + row-scaling (interp-only).
#   huge          huge-document row-axis (interp vs kernel-Java at scale).
#   js            interpreter on Node — runs the compiled JS bundle directly (Gradle's JS reporter swallows println).
#
# The full log path is printed at the end; the extracted result lines are printed to stdout.
set -uo pipefail
cd "$(dirname "$0")/.."

mode="${1:-crossover}"
counts="${2:-}"
mkdir -p build
out="build/perf-${mode}.log"

# Result-line markers the perf tests emit (so the extractor stays accurate as tests are added).
markers='\[edge\]|\[perf\]|\[jsperf\]|^\s*rules=|^\s*distributed|^\s*B[0-9] |^\s*groups=|µs/row|us/row|interp=|MethodTooLarge|KERNEL CODEGEN FAILED|unknown benchmark scenario|cold=|warm-best='

run_gradle() {  # $1 = task, the rest = extra Gradle args; writes RAW to $out (no pipe!)
    local task="$1"
    shift
    # The perf tests are @Tag("perf"), excluded from :adapter:test unless opted in (adapter/build.gradle) — so
    # broad test-task modes pass -PrunPerf. The routine gate instead owns a dedicated, always-run task.
    echo "running: ./build.sh --console=plain -Pfast -i $task $* (raw → $out)"
    ./build.sh --console=plain -Pfast -i "$task" "$@" > "$out" 2>&1
}

case "$mode" in
    gate)
        # counts doubles as an optional METHOD pattern: 'semanticIndex*' → --tests "*PerfGateTest.semanticIndex*"
        if [ -n "$counts" ]; then
            # A method pattern across the WHOLE routine tier, not just the two original classes: the task already
            # restricts to @Tag('perf'), so `*.$counts` cannot reach anything outside it, and naming classes here
            # made `gate groupScopedFillScalesSubQuadratically` fail with "No tests found" (review, 2026-08-13).
            run_gradle :adapter:perfGateTest --tests "*.$counts"
        else
            # No --tests: the task selects the whole @Tag('perf') routine tier itself (adapter/build.gradle), so a
            # new perf test cannot be left out of the gate by forgetting to name it here. A --tests pattern would
            # not even work as a widening — Gradle ANDs the command-line filter with the task's own, so
            # `--tests "*PerfTest"` against the old two-class filter matched NOTHING (measured 2026-08-13).
            run_gradle :adapter:perfGateTest
        fi ;;
    bench)
        if [ -n "$counts" ]; then
            run_gradle :adapter:test -PrunPerf --tests "*PerfBenchTest.gatesEveryInventoryScenario" -Dperf.scenario="$counts"
        else
            run_gradle :adapter:test -PrunPerf --tests "*PerfBenchTest"
        fi ;;
    crossover)
        run_gradle :adapter:test -PrunPerf --tests "*KernelEdgeTest.ruleCountCrossover" --tests "*KernelEdgeTest.ruleCountCrossoverLowFiring" \
            -Dperf.edge=true -Dperf.edge.counts="${counts:-6,12,24,48,96,192,384}" ;;
    distributed)
        run_gradle :adapter:test -PrunPerf --tests "*KernelEdgeTest.distributedRuleScaling" \
            -Dperf.edge=true -Dperf.edge.counts="${counts:-192,512,1000,2000}" ;;
    scaling)
        run_gradle :adapter:test -PrunPerf --tests "*PerfScalingTest" -Dperf.scaling=true ;;
    huge)
        run_gradle :adapter:test -PrunPerf --tests "*KernelEdgeTest.hugeDocScaling" -Dperf.edge=true ;;
    js)
        # The interpreter on Node: build the JS test bundle, then run it DIRECTLY under Node with the default
        # mocha reporter (the kotlin-mocha reporter swallows console.log; the default one prints it). The
        # JsScaleReportTest / DocumentScaleTest println its timings.
        echo "compiling the JS test bundle…"
        ./build.sh --console=plain :interpreter:compileTestDevelopmentExecutableKotlinJs > "$out" 2>&1
        node="$(find . -path '*nodejs/node-*/bin/node' 2>/dev/null | head -1)"
        nm="$PWD/build/js/node_modules"
        bundle="$PWD/build/js/packages/a12-dmkits-interpreter-test/kotlin/a12-dmkits-interpreter-test.js"
        if [ -z "$node" ] || [ ! -f "$bundle" ]; then echo "JS bundle/node not found — see $out"; exit 1; fi
        echo "running the JS bundle on Node (raw → $out)…"
        "$node" --require "$nm/source-map-support/register.js" "$nm/mocha/bin/mocha.js" "$bundle" \
            --require "$nm/kotlin-web-helpers/dist/kotlin-test-nodejs-runner.js" \
            --timeout 120000 --reporter spec >> "$out" 2>&1 ;;
    *)
        echo "unknown mode '$mode' — see the header of $0 for the modes"; exit 2 ;;
esac
status=$?

echo
echo "=== results ($mode) ==="
grep -E "$markers" "$out" | grep -vE "Gradle|daemon|Resolve|Skipping|Caching|mutations|^> Task|plugin|Evaluating|Build cache|Configure|Included|scripting|native-image|started\.|onlyIf|UP-TO-DATE|NO-SOURCE|compile" \
    || echo "(no result lines matched — inspect the full log)"
echo
grep -E "BUILD (SUCCESSFUL|FAILED)|passing|failing" "$out" | tail -2
echo "full log: $out"

# The record is COMMITTED history (never under build/), and this script does not commit it — that is the
# maintainer's call. So say so, with the exact command: an uncommitted record is a manual post-step with no
# mechanical detector, which is precisely how the interpreter changelog freeze got missed twice. Also covers
# the retention prune, whose deletions are tracked files and land in the same commit.
if [ -n "$(git status --porcelain -- perf-results 2>/dev/null)" ]; then
    echo
    echo "this run left perf-results/ changes — they are COMMITTED history, not build output:"
    git status --porcelain -- perf-results | sed 's/^/    /'
    echo "  commit them with:  git add -A perf-results && git commit -m 'chore(perf): record the local gate run [skip ci]'"
fi
exit $status
