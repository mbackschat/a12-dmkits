#!/usr/bin/env bash
#
# Collect the complete local IF172 evidence bundle from one clean commit and one named runner:
# independent JVM/Node first-answer processes, absolute JVM/Node throughput, fair generated-Java ratios,
# and native end-to-end first answer. The reports are informative until an owner promotes a number.
#
# Usage: scripts/interpreter-performance.sh <fresh-processes> <runner-id> <native-dmtool>
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -ne 3 ]; then
    echo "usage: $0 <fresh-processes> <runner-id> <native-dmtool>" >&2
    exit 2
fi

forks="$1"
runner="$2"
native_binary="$3"
case "$forks" in
    ''|*[!0-9]*)
        echo "fresh-processes must be a positive integer" >&2
        exit 2 ;;
esac
if [ "$forks" -le 0 ] || [ -z "$runner" ]; then
    echo "fresh-processes must be positive and runner-id must not be blank" >&2
    exit 2
fi
if [ ! -x "$native_binary" ]; then
    echo "native dmtool is missing or not executable: $native_binary" >&2
    exit 2
fi
if ! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
    echo "interpreter performance measurements require a clean committed tree" >&2
    exit 1
fi

commit="$(git rev-parse HEAD)"
warmup="${PERFORMANCE_WARMUP:-12}"
repetitions="${PERFORMANCE_REPETITIONS:-9}"
fair_warmup="${FAIR_JAVA_WARMUP:-12}"
fair_iterations="${FAIR_JAVA_ITERATIONS:-8}"

echo "commit=$commit runner=$runner fresh-processes=$forks"
./build.sh --console=plain :interpreter:interpreterPerformance \
    "-PperformanceForks=$forks" \
    "-PperformanceCommit=$commit" \
    "-PperformanceRunner=$runner" \
    "-PperformanceWarmup=$warmup" \
    "-PperformanceRepetitions=$repetitions"

./build.sh --console=plain :adapter:interpreterFairJavaReport \
    "-PperformanceCommit=$commit" \
    "-PperformanceRunner=$runner" \
    "-PfairJavaWarmup=$fair_warmup" \
    "-PfairJavaIterations=$fair_iterations"

./build.sh --console=plain :adapter:interpreterNativePerformance \
    "-PperformanceNativeBinary=$native_binary" \
    "-PperformanceNativeSamples=$forks" \
    "-PperformanceCommit=$commit" \
    "-PperformanceRunner=$runner"

echo "library report: interpreter/build/reports/interpreter-performance/library-performance-report.json"
echo "fair-Java report: adapter/build/reports/interpreter-performance/fair-java-report.json"
echo "native report: adapter/build/reports/interpreter-performance/native-first-answer-report.json"
