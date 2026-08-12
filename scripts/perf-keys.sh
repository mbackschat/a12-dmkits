#!/usr/bin/env bash
#
# perf-keys.sh — attribute a JFR profile's HASH-KEY mechanics to the interpreter code paying for them.
#
# WHY THIS EXISTS. IF284 found a memo whose key was a repetition list: hashing it walked the list and
# comparing it walked the list again, so `HashMap`/`ArrayList` mechanics were 79 % of that family's time
# against 2.6 % in the work being cached. That shape is not visible in an ordinary "hot methods" profile —
# the cost lands in `java.util.*` frames, attributed to nobody. This script reads the stacks instead, so a
# key mechanic is charged to the interpreter frame that built the key.
#
# It answers three questions, in the order you need them:
#   1. where the samples actually are (top self frames), so a key finding can be put in proportion;
#   2. how often a key mechanic appears ANYWHERE in the stack (the family's total reach);
#   3. when a key mechanic is the INNERMOST frame, which interpreter caller it arrives through — the
#      discriminating view. A shared helper looks hot on every path until you ask which caller drives it.
#
# Usage:
#   scripts/perf-keys.sh validate            # profile the validate driver (complexRealWorld), then report
#   scripts/perf-keys.sh compute             # profile the compute driver (CO-correlation), then report
#   scripts/perf-keys.sh <recording.jfr>     # report on an existing recording; measures nothing
#   scripts/perf-keys.sh validate Foo.bar    # add frames to the by-caller view (repeatable)
#
# A driving mode runs a ~20 s single-scenario loop under the flight recorder. It is a PROFILE, not a bar:
# nothing here asserts, and a contended machine changes the proportions, so read it beside its own totals
# rather than against a remembered number.
set -uo pipefail
cd "$(dirname "$0")/.."

mode="${1:-}"
[ -n "$mode" ] || { echo "usage: $0 <validate|compute|recording.jfr> [frame ...]" >&2; exit 2; }
shift || true

out="build/perf-keys"
mkdir -p "$out"

case "$mode" in
    validate) test_filter="*EvalProfileTest.loopComplexRealWorldForProfiler"; jfr="$PWD/$out/validate.jfr" ;;
    compute)  test_filter="*ComputeOverlayProfileTest.loopCorrelationForProfiler"; jfr="$PWD/$out/compute.jfr" ;;
    *.jfr)    test_filter=""; jfr="$mode" ;;
    *)        echo "unknown mode '$mode' — see the header of $0" >&2; exit 2 ;;
esac

if [ -n "$test_filter" ]; then
    log="$out/$mode.log"
    echo "profiling $mode (~20 s loop; raw log → $log)"
    # -Pfast drops the corpus-replay and fixture-guard prerequisites of :adapter:test, which a profile does
    # not need; the RAW log goes to a file because a pipe would SIGPIPE the build and lose its output.
    ./build.sh -Pfast --console=plain :adapter:test --tests "$test_filter" \
        -Dperf.profile=true -Dperf.jfr="$jfr" > "$log" 2>&1
    status=$?
    if [ $status -ne 0 ] || [ ! -s "$jfr" ]; then
        echo "the profiling run failed or recorded nothing — see $log" >&2
        exit 1
    fi
fi

[ -s "$jfr" ] || { echo "no recording at $jfr" >&2; exit 1; }

# The JDK's own `jfr` tool. On macOS the shell's JAVA_HOME may point at a newer JDK than the build's, and
# the tool only has to READ the recording, so either is fine — but resolve 21 when we can, for consistency
# with the JVM that wrote it.
if [ -z "${JAVA_HOME:-}" ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"
fi
jfr_tool="${JAVA_HOME:+$JAVA_HOME/bin/jfr}"
[ -x "${jfr_tool:-}" ] || jfr_tool="$(command -v jfr)"
[ -n "$jfr_tool" ] || { echo "no 'jfr' tool found — set JAVA_HOME to a JDK" >&2; exit 1; }

# The analyzer is read into a VARIABLE and passed with `-c`, not fed on stdin like `perf-history.sh` does:
# stdin here carries the recording, and a heredoc would take it over — which silently reports zero samples.
read -r -d '' analyzer <<'PY'
import collections
import os
import re
import sys

# Mechanics that mean "a hash key was built, hashed, or compared". Deliberately includes the plain map
# operations: a key whose hash walks a structure shows up as HashMap.hash/getNode, not as anything named
# after the key. `Integer.hashCode` is here because a List<Int> walk boxes and hashes each element.
MECH = (
    'java.util.AbstractList.hashCode', 'java.util.ArrayList.hashCode', 'java.util.ArrayList.hashCodeRange',
    'java.util.AbstractList.equals', 'java.util.ArrayList.equals', 'java.util.ArrayList.equalsRange',
    'kotlin.Pair.hashCode', 'kotlin.Pair.equals', 'kotlin.Triple.hashCode', 'kotlin.Triple.equals',
    'java.util.HashMap.hash', 'java.util.HashMap.getNode', 'java.util.HashMap.get',
    'java.util.HashMap.containsKey', 'java.util.HashMap.put', 'java.util.HashMap.putVal',
    'java.util.HashMap.resize', 'java.util.LinkedHashMap',
    'kotlin.jvm.internal.Intrinsics.areEqual', 'java.lang.Integer.hashCode',
    'java.lang.StringLatin1.equals', 'java.lang.String.equals', 'java.lang.String.hashCode',
)
INTERP = 'io.github.mbackschat.a12.dm.interpreter'
FRAME = re.compile(r'^\s+([\w$.<>]+\.[\w$<>]+)\(')

# The by-caller view's default targets: the interpreter's own key-bearing lookups. Extra frames may be
# passed as arguments — a finding is usually one frame you did not expect to be here.
targets = [t for t in os.environ.get('FRAMES', '').split() if t]

total = 0
top = collections.Counter()
presence = collections.Counter()
attribute = collections.Counter()
callers = collections.defaultdict(collections.Counter)
seen_targets = collections.Counter()


def flush(frames):
    global total
    if not frames:
        return
    total += 1
    top[frames[0]] += 1
    joined = '\n'.join(frames)
    for m in MECH:
        if m in joined:
            presence[m] += 1
    if any(frames[0].startswith(m) for m in MECH):
        attribute[next((f for f in frames if INTERP in f), '(no interpreter frame)')] += 1
    for t in targets:
        idx = next((i for i, f in enumerate(frames) if t in f), None)
        if idx is None:
            continue
        seen_targets[t] += 1
        j = idx + 1
        while j < len(frames) and t in frames[j]:   # walk out past recursion / overloads
            j += 1
        callers[t][frames[j] if j < len(frames) else '(root)'] += 1


frames, instack = [], False
for line in sys.stdin:
    if line.startswith('jdk.ExecutionSample'):
        flush(frames)
        frames, instack = [], False
        continue
    if 'stackTrace' in line:
        instack = True
        continue
    if instack:
        m = FRAME.match(line)
        if m:
            frames.append(m.group(1))
flush(frames)

if not total:
    sys.exit('no ExecutionSamples in the recording — did the profiled test actually run?')


def rows(counter, limit):
    for frame, count in counter.most_common(limit):
        print("  %6.2f%%  %6d  %s" % (100 * count / total, count, frame))


print("\ntotal ExecutionSamples = %d\n" % total)
print("— where the samples are: top 18 self frames (innermost) —")
rows(top, 18)
print("\n— key mechanics present ANYWHERE in the stack (a family's reach, not its self cost) —")
rows(presence, None)
print("\n— a key mechanic is the innermost frame: the nearest interpreter caller pays for it —")
rows(attribute, 16)
for t in targets:
    print("\n— %s: in %d samples (%.2f%%), by CALLER —" % (t, seen_targets[t], 100 * seen_targets[t] / total))
    rows(callers[t], 10)
PY

"$jfr_tool" print --events jdk.ExecutionSample --stack-depth 64 "$jfr" 2>/dev/null | \
    FRAMES="$*" python3 -c "$analyzer"
status=$?
[ $status -eq 0 ] || exit $status

echo
echo "recording: $jfr"
