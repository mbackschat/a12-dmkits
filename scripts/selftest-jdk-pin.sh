#!/bin/sh
# Lock the OpenJDK-21 selection policy and exact GraalVM native-builder pin at the POSIX boundary.
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
# The Homebrew formula is absent until its own cases below, or this machine's install would answer every case.
a12_has_java_home_tool() { return 0; }
HOMEBREW_PREFIX="$TMP/no-homebrew"

echo "a12_pinned_jdk21_home — the selection policy"
a12_jdk21_homes() { printf '%s\n%s\n' "$graal" "$openjdk"; }
check "skips GraalVM even when it is listed first" "$openjdk" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { printf '%s\n' "$openjdk"; }
check "takes the only OpenJDK" "$openjdk" "$(a12_pinned_jdk21_home || echo '<refused>')"

a12_jdk21_homes() { printf '%s\n' "$graal"; }
check "REFUSES when GraalVM is the only JDK 21" "<refused>" "$(a12_pinned_jdk21_home 2>/dev/null || echo '<refused>')"

a12_jdk21_homes() { return 0; }
check "REFUSES when no JDK 21 is registered" "<refused>" "$(a12_pinned_jdk21_home 2>/dev/null || echo '<refused>')"

echo "a12_pinned_jdk21_home — Homebrew's openjdk@21 wins, whichever 21.x it currently is"
# java_home reports a versioned Cellar directory, which `brew upgrade` deletes; an exported pin naming it goes
# stale in every shell that outlives the upgrade. The formula's opt alias is moved by the upgrade instead.
brew_home="$TMP/brew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
mkdir -p "$brew_home/bin"
: > "$brew_home/bin/java"
chmod +x "$brew_home/bin/java"
HOMEBREW_PREFIX="$TMP/brew"
a12_jdk21_homes() { printf '%s\n' "$openjdk"; }
check "takes the formula over the home java_home lists" "$brew_home" "$(a12_pinned_jdk21_home || echo '<refused>')"
a12_jdk21_homes() { return 0; }
check "takes the formula when java_home lists nothing" "$brew_home" "$(a12_pinned_jdk21_home || echo '<refused>')"

echo "a12_jdk21_export_hint — a recovery hint names the pin rather than a path the next upgrade deletes"
check "exports the pinned formula home" "export JAVA_HOME=\"$brew_home\"" "$(a12_jdk21_export_hint)"
HOMEBREW_PREFIX="$TMP/no-homebrew"
case "$(a12_jdk21_export_hint)" in *"java_home -v 21"*) falls_back=yes ;; *) falls_back=no ;; esac
check "falls back to the java_home lookup without a pin" "yes" "$falls_back"

echo "a12_pinned_jdk21_home — a refusal explains itself"
a12_jdk21_homes() { printf '%s\n' "$graal"; }
graal_err="$(a12_pinned_jdk21_home 2>&1 >/dev/null || true)"
case "$graal_err" in *GraalVM*) names_graal=yes ;; *) names_graal=no ;; esac
case "$graal_err" in *"brew install openjdk@21"*) names_fix=yes ;; *) names_fix=no ;; esac
check "names GraalVM as the reason it refused" "yes" "$names_graal"
check "tells the reader how to fix it" "yes" "$names_fix"

echo "verify-graalvm-native.sh — the exact native-builder pin"
native_home="$TMP/native-image"
mkdir -p "$native_home/bin"
cat > "$native_home/bin/native-image" <<'NATIVE_IMAGE'
#!/bin/sh
[ -z "${FAKE_NATIVE_IMAGE_FAILURE:-}" ] || exit 23
printf '%s\n' "${FAKE_NATIVE_IMAGE_BANNER:?}"
printf '%s\n' "${FAKE_GRAALVM_RUNTIME_BANNER:?}"
NATIVE_IMAGE
chmod +x "$native_home/bin/native-image"
graalvm_identity="$ROOT/gradle/graalvm-native.properties"
expected_graalvm_version="$(sed -n 's/^graalvmVersion=//p' "$graalvm_identity")"
expected_java_version="$(sed -n 's/^javaVersion=//p' "$graalvm_identity")"
verify_graalvm="$ROOT/scripts/verify-graalvm-native.sh"

check "accepts the exact pinned builder" "accepted" "$(
  FAKE_NATIVE_IMAGE_BANNER="native-image $expected_java_version fixture" \
    FAKE_GRAALVM_RUNTIME_BANNER="GraalVM Runtime Environment Oracle GraalVM $expected_graalvm_version+fixture" \
    /bin/dash "$verify_graalvm" "$native_home" >/dev/null 2>&1 && echo accepted || echo refused
)"
check "REFUSES the 25.0 LTS train" "refused" "$(
  FAKE_NATIVE_IMAGE_BANNER="native-image $expected_java_version fixture" \
    FAKE_GRAALVM_RUNTIME_BANNER='GraalVM Runtime Environment Oracle GraalVM 25.0.4.1+fixture' \
    /bin/dash "$verify_graalvm" "$native_home" >/dev/null 2>&1 && echo accepted || echo refused
)"
check "REFUSES the previous 25.3 patch" "refused" "$(
  FAKE_NATIVE_IMAGE_BANNER='native-image 25.0.4 fixture' \
    FAKE_GRAALVM_RUNTIME_BANNER='GraalVM Runtime Environment Oracle GraalVM 25.3.4+fixture' \
    /bin/dash "$verify_graalvm" "$native_home" >/dev/null 2>&1 && echo accepted || echo refused
)"
check "REFUSES a broken native-image executable" "refused" "$(
  FAKE_NATIVE_IMAGE_BANNER='unused' FAKE_GRAALVM_RUNTIME_BANNER='unused' FAKE_NATIVE_IMAGE_FAILURE=1 \
    /bin/dash "$verify_graalvm" "$native_home" >/dev/null 2>&1 && echo accepted || echo refused
)"

echo
if [ "$failures" -ne 0 ]; then
  echo "selftest-jdk-pin: $failures check(s) failed"
  exit 1
fi
echo "selftest-jdk-pin: all checks passed"
