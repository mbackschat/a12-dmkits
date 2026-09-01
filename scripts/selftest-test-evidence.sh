#!/usr/bin/env bash
#
# selftest-test-evidence.sh — locks the evidence reporter in both directions.
#
# This script's whole value is that a reader BELIEVES its counts, so a wrong count here is worse than no tool
# at all: it is a false green wearing the uniform of a check. Two defects of exactly that kind have shipped
# in it — a cutoff that marked every file stale (its first version), and a cutoff taken from a birth time that
# `>` does not reset, which on 2026-08-31 credited a 6-second run with 13 directories and 2016 tests from
# earlier runs. Both were invisible in the output. Hence the does-count / does-NOT-count pairing below, and the
# cutoff cases that drive the arithmetic directly through `--print-cutoff`. A third regression made the
# reporter abort under stock macOS Bash. The command is now a Bash-3.2 launcher over typed logic, so every
# invocation below deliberately uses `/bin/dash` as the POSIX oracle and copies the complete launcher/runtime source set.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/scripts/test-evidence.sh"
TYPESCRIPT_LAUNCHER="$ROOT/scripts/run-typescript.sh"
IMPLEMENTATION="$ROOT/scripts/typescript/test-evidence.ts"

failures=0
check() {
  local what="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then printf '  ok    %s\n' "$what"
  else printf '  FAIL  %s\n        expected: %s\n        actual:   %s\n' "$what" "$expected" "$actual"
       failures=$((failures + 1)); fi
}

trees=()
cleanup() { local t; for t in "${trees[@]:-}"; do [[ -n "$t" ]] && rm -rf "$t"; done; return 0; }
trap cleanup EXIT

# A fabricated repository: the script locates its root as `dirname $0/..`, so a copy under `<tmp>/scripts`
# makes `<tmp>` the tree it scans. Nothing here touches the real build.
mk_tree() {
  local tmp
  tmp="$(mktemp -d)"
  mkdir -p "$tmp/scripts/typescript" "$tmp/build"
  cp "$SCRIPT" "$tmp/scripts/test-evidence.sh"
  cp "$TYPESCRIPT_LAUNCHER" "$tmp/scripts/run-typescript.sh"
  cp "$IMPLEMENTATION" "$tmp/scripts/typescript/test-evidence.ts"
  trees+=("$tmp")
  printf '%s\n' "$tmp"
}

suite() { printf '<testsuite name="%s" tests="%s" failures="0" errors="0"/>\n' "$1" "$2"; }
run() { /bin/dash "$1/scripts/test-evidence.sh" "$1/build/run.log" 2>&1; }

# ---- the measured incident: a directory whose task this log never mentions -----------------------------------
echo "does NOT count — results the run did not produce"

tmp="$(mk_tree)"
mkdir -p "$tmp/build/test-results/docGuardTest" "$tmp/cli/build/test-results/docGuardTest"
printf '> Task :cli:docGuardTest\nBUILD SUCCESSFUL in 6s\n' > "$tmp/build/run.log"
sleep 1
suite Stale 2016 > "$tmp/build/test-results/docGuardTest/TEST-Stale.xml"
suite Real 70 > "$tmp/cli/build/test-results/docGuardTest/TEST-Real.xml"
out="$(run "$tmp")"
check "a root task absent from the log is withheld, however recent its files" \
  "withheld" "$(grep -q 'build/test-results/docGuardTest/ *ABSENT' <<<"$out" && echo withheld || echo counted)"
check "…and its tests are not added to the total" \
  "absent" "$(grep -q '2016 tests' <<<"$out" && echo present || echo absent)"
check "the task the log DOES report is counted" \
  "70" "$(grep -oE '[0-9]+ tests' <<<"$out" | head -1 | grep -oE '[0-9]+')"

tmp="$(mk_tree)"
mkdir -p "$tmp/cli/build/test-results/docGuardTest"
printf '> Task :cli:docGuardTest FROM-CACHE\nBUILD SUCCESSFUL in 2s\n' > "$tmp/build/run.log"
sleep 1
suite Cached 70 > "$tmp/cli/build/test-results/docGuardTest/TEST-Cached.xml"
out="$(run "$tmp")"
check "a FROM-CACHE task is not reported as this run's execution" \
  "withheld" "$(grep -q 'FROM-CACHE .*NOT this run' <<<"$out" && echo withheld || echo counted)"

# ---- the original defect: an executed task's unmatched siblings ----------------------------------------------
echo "does NOT count — stale classes inside a task that DID run"

tmp="$(mk_tree)"
mkdir -p "$tmp/cli/build/test-results/docGuardTest"
printf '> Task :cli:docGuardTest\nBUILD SUCCESSFUL in 2s\n' > "$tmp/build/run.log"
suite Old 500 > "$tmp/cli/build/test-results/docGuardTest/TEST-Old.xml"
touch -t 202601010000 "$tmp/cli/build/test-results/docGuardTest/TEST-Old.xml"
sleep 1
suite New 7 > "$tmp/cli/build/test-results/docGuardTest/TEST-New.xml"
out="$(run "$tmp")"
check "a --tests filter's unmatched siblings stay out of the count" \
  "1 classes      7 tests" "$(grep -oE '[0-9]+ classes +[0-9]+ tests' <<<"$out" | head -1)"

# ---- does count: the ordinary run ----------------------------------------------------------------------------
echo "does count — what the run actually executed"

tmp="$(mk_tree)"
mkdir -p "$tmp/build/test-results/docGuardTest" "$tmp/cli/build/test-results/docGuardTest"
printf '> Task :docGuardTest\n> Task :cli:docGuardTest\nBUILD SUCCESSFUL in 6s\n' > "$tmp/build/run.log"
sleep 1
suite RootReal 13 > "$tmp/build/test-results/docGuardTest/TEST-RootReal.xml"
suite CliReal 70 > "$tmp/cli/build/test-results/docGuardTest/TEST-CliReal.xml"
out="$(run "$tmp")"
check "both executed tasks are credited" \
  "13 70" "$(grep -oE '[0-9]+ tests' <<<"$out" | grep -oE '[0-9]+' | tr '\n' ' ' | sed 's/ $//')"
check "nothing is withheld when every directory has an executing task" \
  "clean" "$(grep -q 'withheld' <<<"$out" && echo withheld || echo clean)"

tmp="$(mk_tree)"
mkdir -p "$tmp/cli/build/test-results/docGuardTest"
printf '> Task :cli:docGuardTest\nBUILD SUCCESSFUL in 2s\n' > "$tmp/build/run.log"
sleep 1
printf "<testsuite name='Quoted' tests = '7' failures = '0' errors = '0'/>\n" \
  > "$tmp/cli/build/test-results/docGuardTest/TEST-Quoted.xml"
out="$(run "$tmp")"
check "valid single-quoted XML attributes retain their counts" \
  "7" "$(grep -oE '[0-9]+ tests' <<<"$out" | head -1 | grep -oE '[0-9]+')"

# ---- the cutoff arithmetic, driven directly --------------------------------------------------------------------
echo 'the cutoff — derived from the log verdict, not from metadata that `>` does not reset'

now="$(date +%s)"
tmp="$(mk_tree)"
mkdir -p "$tmp/build"
printf '> Task :x\nBUILD SUCCESSFUL in 1s\n' > "$tmp/build/run.log"
# A reused path keeps its birth time; only the mtime moves. Pushing the mtime far out is the same shape as a
# month-old birth time, and it is the one of the two a test can set portably.
touch -t 203001010000 "$tmp/build/run.log"
cutoff="$(/bin/dash "$tmp/scripts/test-evidence.sh" --print-cutoff "$tmp/build/run.log")"
check "a stale birth time cannot pin the cutoff — the verdict line wins" \
  "later" "$(awk -v c="$cutoff" -v n="$now" 'BEGIN { print (c > n + 31536000) ? "later" : "at-birth" }')"

tmp="$(mk_tree)"
mkdir -p "$tmp/build"
printf '> Task :x\nBUILD SUCCESSFUL in 3600s\n' > "$tmp/build/run.log"
cutoff="$(/bin/dash "$tmp/scripts/test-evidence.sh" --print-cutoff "$tmp/build/run.log")"
check "…and on a freshly created log the birth time still wins, so a long run does not widen the window" \
  "at-birth" "$(awk -v c="$cutoff" -v n="$now" 'BEGIN { print (c > n - 60) ? "at-birth" : "widened" }')"

tmp="$(mk_tree)"
mkdir -p "$tmp/build"
printf '> Task :x\n' > "$tmp/build/run.log"
out="$(run "$tmp")"
check "a log with no verdict line says so instead of reporting a cutoff it cannot justify" \
  "warned" "$(grep -q '^warning:' <<<"$out" && echo warned || echo silent)"

echo
if (( failures )); then echo "selftest-test-evidence: $failures check(s) failed"; exit 1; fi
echo "selftest-test-evidence: all checks passed"
exit 0
