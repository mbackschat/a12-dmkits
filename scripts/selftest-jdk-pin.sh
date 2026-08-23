#!/usr/bin/env bash
#
# selftest-jdk-pin.sh — locks the OpenJDK-21 build pin in gradle-environment.sh, both directions.
#
# WHY THIS EXISTS. `a12_pinned_jdk21_home` used to be `java_home -v 21`, which returns the HIGHEST-versioned
# JDK 21 installed. Installing Homebrew's openjdk@21 21.0.12.1 therefore displaced GraalVM 21.0.12 as the
# build JDK on 2026-08-19 with no commit in this repo, and moved 45 of 91 perf lanes (median 1.15x, worst
# 1.60x) — read at first as a code regression. GraalVM is reserved for native image (GRAALVM_HOME, GraalVM
# 25) and must never be the build JDK. A selection policy that is only agreed is one an install can undo, so
# it is executed here: the does-fire half (a GraalVM home is skipped, and refused when it is the only one) is
# the half that actually failed.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=scripts/gradle-environment.sh
source "$ROOT/scripts/gradle-environment.sh"

TMP="$(mktemp -d)"
trap '/bin/rm -rf "$TMP"' EXIT
failures=0

check() {
  local what="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    printf '  ok   %s\n' "$what"
  else
    printf '  FAIL %s\n       expected: %s\n       actual:   %s\n' "$what" "$expected" "$actual"
    failures=$((failures + 1))
  fi
}

make_home() {  # make_home <dir> [GRAALVM_VERSION value]
  local dir="$TMP/$1"; mkdir -p "$dir"
  { echo 'JAVA_VERSION="21.0.12"'; [[ -n "${2:-}" ]] && echo "GRAALVM_VERSION=\"$2\""; } > "$dir/release"
  printf '%s\n' "$dir"
}

graal="$(make_home graalvm 23.1.12)"
openjdk="$(make_home openjdk)"
bare="$TMP/no-release"; mkdir -p "$bare"

echo "a12_is_graalvm_home — the discriminator"
check "recognises a GraalVM home"            "yes" "$(a12_is_graalvm_home "$graal"   && echo yes || echo no)"
check "does not flag stock OpenJDK"          "no"  "$(a12_is_graalvm_home "$openjdk" && echo yes || echo no)"
check "a home with no release file is not GraalVM" "no" "$(a12_is_graalvm_home "$bare" && echo yes || echo no)"

echo "a12_pinned_jdk21_home — the selection policy"
a12_jdk21_homes() { printf '%s\n%s\n' "$graal" "$openjdk"; }
check "skips GraalVM even when it is listed first" "$openjdk" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { printf '%s\n' "$openjdk"; }
check "takes the only OpenJDK"                     "$openjdk" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { printf '%s\n' "$graal"; }
check "REFUSES when GraalVM is the only JDK 21"    "<refused>" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { return 0; }
check "REFUSES when no JDK 21 is registered"       "<refused>" "$(a12_pinned_jdk21_home || echo '<refused>')"

echo
if (( failures )); then echo "selftest-jdk-pin: $failures check(s) failed"; exit 1; fi
echo "selftest-jdk-pin: all checks passed"
