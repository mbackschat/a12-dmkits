#!/bin/sh
# Lock both directions of the OpenJDK-21 selection policy in gradle-environment.sh at the POSIX boundary.
# The fixture overrides are called indirectly by the sourced policy; ShellCheck neither follows nor sees that call.
# shellcheck disable=SC1091,SC2329
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"

TMP="$(mktemp -d)"
trap '/bin/rm -rf "$TMP"' EXIT HUP INT TERM
failures=0

check() {
  if [ "$2" = "$3" ]; then
    printf '  ok   %s\n' "$1"
  else
    printf '  FAIL %s\n       expected: %s\n       actual:   %s\n' "$1" "$2" "$3"
    failures=$((failures + 1))
  fi
}

make_home() (
  directory="$TMP/$1"
  mkdir -p "$directory"
  printf '%s\n' 'JAVA_VERSION="21.0.12"' > "$directory/release"
  if [ -n "${2:-}" ]; then
    printf 'GRAALVM_VERSION="%s"\n' "$2" >> "$directory/release"
  fi
  printf '%s\n' "$directory"
)

graal="$(make_home graalvm 23.1.12)"
openjdk="$(make_home openjdk)"
bare="$TMP/no-release"
mkdir -p "$bare"

echo "a12_is_graalvm_home — the discriminator"
check "recognises a GraalVM home" "yes" "$(a12_is_graalvm_home "$graal" && echo yes || echo no)"
check "does not flag stock OpenJDK" "no" "$(a12_is_graalvm_home "$openjdk" && echo yes || echo no)"
check "a home with no release file is not GraalVM" "no" "$(a12_is_graalvm_home "$bare" && echo yes || echo no)"

# Substitute the platform premise and enumeration together so these cases execute identically on Linux and macOS.
a12_has_java_home_tool() { return 0; }

echo "a12_pinned_jdk21_home — the selection policy"
a12_jdk21_homes() { printf '%s\n%s\n' "$graal" "$openjdk"; }
check "skips GraalVM even when it is listed first" "$openjdk" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { printf '%s\n' "$openjdk"; }
check "takes the only OpenJDK" "$openjdk" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { printf '%s\n' "$graal"; }
check "REFUSES when GraalVM is the only JDK 21" "<refused>" "$(a12_pinned_jdk21_home 2>/dev/null || echo '<refused>')"

a12_jdk21_homes() { return 0; }
check "REFUSES when no JDK 21 is registered" "<refused>" "$(a12_pinned_jdk21_home 2>/dev/null || echo '<refused>')"

echo "a12_pinned_jdk21_home — a refusal explains itself"
a12_jdk21_homes() { printf '%s\n' "$graal"; }
graal_err="$(a12_pinned_jdk21_home 2>&1 >/dev/null || true)"
case "$graal_err" in *GraalVM*) names_graal=yes ;; *) names_graal=no ;; esac
case "$graal_err" in *"brew install openjdk@21"*) names_fix=yes ;; *) names_fix=no ;; esac
check "names GraalVM as the reason it refused" "yes" "$names_graal"
check "tells the reader how to fix it" "yes" "$names_fix"

echo
if [ "$failures" -ne 0 ]; then
  echo "selftest-jdk-pin: $failures check(s) failed"
  exit 1
fi
echo "selftest-jdk-pin: all checks passed"
