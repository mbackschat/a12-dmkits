#!/usr/bin/env bash
#
# Run the local-only IG79 forked JVM + Node retained-heap/allocation measurement.
# Usage: scripts/interpreter-footprint.sh <forks>=5+ <base-N> <warmup> <repetitions>
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -ne 4 ]; then
    echo "usage: $0 <forks>=5+ <base-N> <warmup> <repetitions>" >&2
    exit 2
fi

forks="$1"
base="$2"
warmup="$3"
repetitions="$4"

case "$forks:$base:$warmup:$repetitions" in
    *[!0-9:]*)
        echo "forks, base-N, warmup, and repetitions must be positive integers" >&2
        exit 2 ;;
esac
if [ "$forks" -lt 5 ] || [ "$base" -le 0 ] || [ "$warmup" -le 0 ] || [ "$repetitions" -le 0 ]; then
    echo "forks must be >=5; base-N, warmup, and repetitions must be >0" >&2
    exit 2
fi

if ! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
    echo "interpreter footprint measurements require a clean committed tree" >&2
    exit 1
fi

commit="$(git rev-parse HEAD)"
scales="$base,$((base * 2)),$((base * 4)),$((base * 8))"
jvm_heap="${FOOTPRINT_JVM_HEAP:-512m}"
node_heap_mb="${FOOTPRINT_NODE_HEAP_MB:-512}"

echo "running $forks JVM + $forks Node footprint forks"
echo "commit=$commit scales=$scales warmup=$warmup repetitions=$repetitions"
./build.sh --console=plain :interpreter:interpreterFootprint \
    "-PfootprintForks=$forks" \
    "-PfootprintCommit=$commit" \
    "-PfootprintScales=$scales" \
    "-PfootprintWarmup=$warmup" \
    "-PfootprintRepetitions=$repetitions" \
    "-PfootprintJvmHeap=$jvm_heap" \
    "-PfootprintNodeHeapMb=$node_heap_mb"

echo "structured report: interpreter/build/reports/interpreter-footprint/footprint-report.json"
