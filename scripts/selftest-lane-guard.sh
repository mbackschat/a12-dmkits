#!/usr/bin/env bash
#
# selftest-lane-guard.sh — locks the lane guard in BOTH directions.
#
# The does-refuse half is the one that matters: it reproduces the exact case that created this guard, a
# `:cli:check` requested against a diff touching only `scripts/`. The does-NOT-refuse half matters just as
# much in practice — a guard that blocks justified work gets switched off within a week, so every ordinary
# lane a real change needs is asserted to pass through untouched.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/lane-guard.sh
source "$ROOT/scripts/lane-guard.sh"

failures=0
check() {
  local what="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then printf '  ok   %s\n' "$what"
  else printf '  FAIL %s\n       expected: %s\n       actual:   %s\n' "$what" "$expected" "$actual"; failures=$((failures + 1)); fi
}

# Drive the guard from a fixed path list instead of a real repository.
with_paths() { CHANGED="$1"; a12_changed_paths() { printf '%s\n' "$CHANGED"; }; }
refused() { a12_lane_refusals "$@" | tr -d ' \n'; }

echo "does REFUSE — the case that created this guard"
with_paths "scripts/gradle-environment.sh
scripts/selftest-jdk-pin.sh"
check "a scripts-only diff does not justify :cli:check"     ":cli:check" "$(refused :cli:check)"
check "…nor :adapter:test"                                  ":adapter:test" "$(refused :adapter:test)"
check "…nor :interpreter:check"                             ":interpreter:check" "$(refused :interpreter:check)"

with_paths "docs/EXAMPLE-DOC.md"
check "a docs-only diff does not justify :cli:test"         ":cli:test" "$(refused :cli:test)"

echo "does NOT refuse — ordinary work must pass through"
with_paths "scripts/gradle-environment.sh"
check "scripts justify the delivery guard"                  "" "$(refused :deliveryGuardTest)"
check "scripts justify the root lane"                       "" "$(refused :test)"
with_paths "docs/EXAMPLE-DOC.md"
check "docs justify both docGuard lanes"                    "" "$(refused :docGuardTest :cli:docGuardTest)"
with_paths "cli/src/main/java/Foo.java"
check "cli source justifies :cli:check"                     "" "$(refused :cli:check)"
with_paths "interpreter/src/commonMain/kotlin/Foo.kt"
check "interpreter source justifies :adapter:test"          "" "$(refused :adapter:test)"
check "…and :cli:check, which ships it"                     "" "$(refused :cli:check)"
with_paths "rulekit/src/main/java/Foo.java"
check "a shared kit justifies every lane"                   "" "$(refused :cli:check :adapter:test :interpreter:check)"
with_paths "build.gradle"
check "a root build file justifies every lane"              "" "$(refused :cli:check :adapter:test)"
with_paths "adapter/src/test/kotlin/perf/PerfAnchors.kt"
check "adapter perf sources justify the perf gate"          "" "$(refused :adapter:perfGateTest)"

echo "non-test tasks are never judged"
with_paths "docs/README.md"
check "installDist passes regardless"                       "" "$(refused installDist)"
check "dumpKitApi passes regardless"                        "" "$(refused dumpKitApi)"

echo
if (( failures )); then echo "selftest-lane-guard: $failures check(s) failed"; exit 1; fi
echo "selftest-lane-guard: all checks passed"
