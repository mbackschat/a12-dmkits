#!/usr/bin/env bash
# selftest-dmtool-launcher.sh — prove `bin/dmtool`'s JDK gate REFUSES on every unusable-Java path.
#
# Why a script and not a JUnit test: the subject is a shell launcher, and what has to be exercised is the
# environment it reads (JAVA_HOME, JAVA_TOOL_OPTIONS, PATH) and the shape of the `java` it finds. A JUnit test
# would have to fabricate all of that through a process builder and would still not run the real script's
# resolution order.
#
# Every case below was a live bypass; the JDK-gate cases were found by review rather than by a test, because the
# gate had none. The pattern is uniform and worth stating once: each bypass ended in the gate returning SUCCESS,
# so the failure surfaced later (or not at all) — and one of them exited 0 with empty stdout, which an agent reads
# as "ran fine, nothing to report". The native-path case likewise fell through successfully to the wrong runtime.
# A gate that cannot fail is worse than no gate, because it is trusted.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DMTOOL="$ROOT/bin/dmtool"
EXPECTED_NATIVE_RELATIVE="${1:-}"
[[ "$EXPECTED_NATIVE_RELATIVE" == cli/build/native/* && "$EXPECTED_NATIVE_RELATIVE" != *..* ]] || {
  echo "usage: $0 <Gradle-native-output-relative-to-repo>" >&2
  exit 2
}
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

pass=0
fail=0

# A stand-in for the installDist launcher: a script the gate must recognise as needing a JDK (it names
# JAVA_HOME, exactly as the generated Gradle start script does).
LAUNCHER="$WORK/jvm-launcher"
cat > "$LAUNCHER" <<'EOF'
#!/usr/bin/env bash
echo "LAUNCHER RAN with JAVA_HOME=${JAVA_HOME:-}"
EOF
chmod +x "$LAUNCHER"

# A stand-in for a native image: no shebang, so no JDK needed and the gate must NOT apply.
NATIVE="$WORK/native-binary"
printf '\x7fELF fake native binary' > "$NATIVE"
chmod +x "$NATIVE"

# A fake JDK home whose `java` behaves as the case requires.
fake_jdk() {  # $1 = dir name, $2 = script body
  local home="$WORK/$1"
  mkdir -p "$home/bin"
  printf '#!/usr/bin/env bash\n%s\n' "$2" > "$home/bin/java"
  chmod +x "$home/bin/java"
  printf '%s' "$home"
}

check() {  # $1 = case, $2 = expected exit, $3.. = command
  local name="$1" want="$2"; shift 2
  local out status
  set +e
  out="$("$@" 2>&1)"
  status=$?
  set -e
  if [[ "$status" == "$want" ]]; then
    printf '  ok    %s (exit %s)\n' "$name" "$status"
    pass=$((pass + 1))
  else
    printf '  FAIL  %s — expected exit %s, got %s\n        output: %s\n' "$name" "$want" "$status" "$out"
    fail=$((fail + 1))
  fi
}

check_output() {  # $1 = case, $2 = expected output, $3.. = command
  local name="$1" want="$2"; shift 2
  local out status
  set +e
  out="$("$@" 2>&1)"
  status=$?
  set -e
  if [[ "$status" == 0 && "$out" == "$want" ]]; then
    printf '  ok    %s\n' "$name"
    pass=$((pass + 1))
  else
    printf '  FAIL  %s — expected exit 0 + output %q, got exit %s + output %q\n' \
      "$name" "$want" "$status" "$out"
    fail=$((fail + 1))
  fi
}

echo "bin/dmtool JDK gate — every unusable Java must be REFUSED (exit 2):"

# (1) JAVA_HOME with no java executable — the original reported case (a stale Homebrew Cellar path).
check "JAVA_HOME without bin/java" 2 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$WORK/not-a-jdk" "$DMTOOL" --version

# (2) `java -version` FAILS. Previously the gate returned success and the child's status leaked out with no
#     dmtool diagnostic at all.
JDK_FAILS="$(fake_jdk jdk-fails 'echo "boom" >&2; exit 42')"
check "java -version exits nonzero" 2 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_FAILS" "$DMTOOL" --version

# (3) An UNPARSEABLE banner, exit 0. Previously the gate passed and dmtool exited 0 with empty stdout.
JDK_MUTE="$(fake_jdk jdk-mute 'echo "some vendor tool, no version here"; exit 0')"
check "java -version prints no version" 2 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_MUTE" "$DMTOOL" --version

# (4) An OLD JDK whose banner is preceded by the "Picked up JAVA_TOOL_OPTIONS" line every JVM prepends when
#     that variable is set. This is the sharpest case: the old gate anchored its match to line 1, so the
#     version came out empty and `[[ -n "$major" && … ]]` short-circuited — making the gate inert in exactly
#     the environments (CI, containers, IDE shells) it exists to protect, while looking like it worked.
JDK_OLD_NOISY="$(fake_jdk jdk-old-noisy \
  'echo "Picked up JAVA_TOOL_OPTIONS: -Xmx512m" >&2; echo "openjdk version \"17.0.9\" 2023-10-17" >&2; exit 0')"
check "old JDK behind a JAVA_TOOL_OPTIONS notice" 2 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_OLD_NOISY" "$DMTOOL" --version

# (5) The same old JDK without the notice — the case that always worked, kept so a fix to (4) cannot regress it.
JDK_OLD="$(fake_jdk jdk-old 'echo "openjdk version \"17.0.9\" 2023-10-17" >&2; exit 0')"
check "old JDK, plain banner" 2 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_OLD" "$DMTOOL" --version

echo
echo "…and a usable Java must be ACCEPTED (the gate must not over-refuse):"

# (6) A supported JDK behind the same notice: the noise must not make a GOOD Java look unreadable either.
JDK_OK="$(fake_jdk jdk-ok \
  'echo "Picked up JAVA_TOOL_OPTIONS: -Xmx512m" >&2; echo "openjdk version \"21.0.12\" 2024-07-16" >&2; exit 0')"
check "supported JDK behind a JAVA_TOOL_OPTIONS notice" 0 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_OK" "$DMTOOL" --version

# (7) A NEWER JDK still runs — the documented tolerance posture, not an error.
JDK_NEW="$(fake_jdk jdk-new 'echo "openjdk version \"26.0.1\" 2026-09-15" >&2; exit 0')"
check "newer JDK is accepted" 0 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_NEW" "$DMTOOL" --version

echo
echo "…and the gate must apply to a JVM launcher reached by any route, but not to a native binary:"

# (8) A native binary needs no JDK, so a broken JAVA_HOME must NOT block it. (It exits nonzero here only
#     because the stand-in is not a real executable image; the point is that the gate did not refuse it — a
#     gate refusal is exit 2 with a dmtool diagnostic, which this is not.)
set +e
out="$(env DMTOOL_CLI="$NATIVE" JAVA_HOME="$WORK/not-a-jdk" "$DMTOOL" --version 2>&1)"
set -e
if [[ "$out" == *"dmtool: JAVA_HOME"* ]]; then
  printf '  FAIL  native binary must not be JDK-gated — but it was refused: %s\n' "$out"
  fail=$((fail + 1))
else
  printf '  ok    native binary is not JDK-gated\n'
  pass=$((pass + 1))
fi

echo
echo "…and RK_NATIVE=1 must select Gradle's actual nativeCompile artifact:"

# Exercise the Gradle task's declared output layout in an isolated repository-shaped tree. The JUnit wrapper
# obtains this argument from nativeCompile.outputFile, so this test does not repeat the launcher's path literal.
# The previous shim looked in cli/build/native/dmtool while Gradle produced a nested nativeCompile output, so
# RK_NATIVE=1 silently fell through to the JVM launcher.
TEST_ROOT="$WORK/repo"
mkdir -p "$TEST_ROOT/bin" "$(dirname "$TEST_ROOT/$EXPECTED_NATIVE_RELATIVE")" \
  "$TEST_ROOT/cli/build/install/dmtool/bin" "$TEST_ROOT/scripts"
cp "$DMTOOL" "$TEST_ROOT/bin/dmtool"
# The shim single-sources its JDK probe from here and refuses without it, so a repository-shaped fixture
# carries it too — omitting it would test a checkout the shim deliberately does not support.
cp "$ROOT/scripts/gradle-environment.sh" "$TEST_ROOT/scripts/gradle-environment.sh"
cat > "$TEST_ROOT/$EXPECTED_NATIVE_RELATIVE" <<'EOF'
#!/usr/bin/env bash
printf 'NATIVE'
EOF
cat > "$TEST_ROOT/cli/build/install/dmtool/bin/dmtool" <<'EOF'
#!/usr/bin/env bash
# JAVA_HOME marks this as a JVM launcher to bin/dmtool's content-based gate.
printf 'JVM'
EOF
chmod +x "$TEST_ROOT/bin/dmtool" \
  "$TEST_ROOT/$EXPECTED_NATIVE_RELATIVE" \
  "$TEST_ROOT/cli/build/install/dmtool/bin/dmtool"
check_output "RK_NATIVE selects nativeCompile output" NATIVE \
  env DMTOOL_CLI= RK_NATIVE=1 JAVA_HOME="$JDK_OK" "$TEST_ROOT/bin/dmtool" --version

# Windows appends .exe to imageName. Reproduce the Git-Bash-visible layout on this host so a missing Windows
# candidate cannot hide behind this test's POSIX-only JUnit wrapper.
WINDOWS_ROOT="$WORK/repo-windows"
mkdir -p "$WINDOWS_ROOT/bin" "$WINDOWS_ROOT/cli/build/native/nativeCompile" \
  "$WINDOWS_ROOT/cli/build/install/dmtool/bin" "$WINDOWS_ROOT/scripts"
cp "$DMTOOL" "$WINDOWS_ROOT/bin/dmtool"
cp "$ROOT/scripts/gradle-environment.sh" "$WINDOWS_ROOT/scripts/gradle-environment.sh"
cat > "$WINDOWS_ROOT/cli/build/native/nativeCompile/dmtool.exe" <<'EOF'
#!/usr/bin/env bash
printf 'NATIVE_EXE'
EOF
cat > "$WINDOWS_ROOT/cli/build/install/dmtool/bin/dmtool" <<'EOF'
#!/usr/bin/env bash
# JAVA_HOME marks this as a JVM launcher to bin/dmtool's content-based gate.
printf 'JVM'
EOF
chmod +x "$WINDOWS_ROOT/bin/dmtool" \
  "$WINDOWS_ROOT/cli/build/native/nativeCompile/dmtool.exe" \
  "$WINDOWS_ROOT/cli/build/install/dmtool/bin/dmtool"
check_output "RK_NATIVE selects Windows nativeCompile output" NATIVE_EXE \
  env DMTOOL_CLI= RK_NATIVE=1 JAVA_HOME="$JDK_OK" "$WINDOWS_ROOT/bin/dmtool" --version

echo
echo "…and the gate must model Gradle's OWN probe, plus refuse a checkout it cannot read:"

# (11) Gradle's start script prefers $JAVA_HOME/jre/sh/java and only then bin/java. A gate that looked for
#      bin/java alone refused a JAVA_HOME Gradle would have started from perfectly well.
JDK_JRE_SH="$WORK/jdk-jre-sh"
mkdir -p "$JDK_JRE_SH/jre/sh"
printf '#!/usr/bin/env bash\n%s\n' 'echo "openjdk version \"21.0.12\" 2024-07-16" >&2; exit 0' \
  > "$JDK_JRE_SH/jre/sh/java"
chmod +x "$JDK_JRE_SH/jre/sh/java"
check "JAVA_HOME with only jre/sh/java is accepted" 0 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_JRE_SH" "$DMTOOL" --version

# (12) The shim REQUIRES the shared helper its JDK probe lives in. Without it the gate would silently do
#      nothing while the shim still claimed to have checked, so absence is a refusal with its own message.
BROKEN_ROOT="$WORK/repo-no-helper"
mkdir -p "$BROKEN_ROOT/bin"
cp "$DMTOOL" "$BROKEN_ROOT/bin/dmtool"
chmod +x "$BROKEN_ROOT/bin/dmtool"
check "a checkout without the shared helper is refused" 2 \
  env DMTOOL_CLI="$LAUNCHER" JAVA_HOME="$JDK_OK" "$BROKEN_ROOT/bin/dmtool" --version

# (13) The one classifier kind no JAVA_HOME can stage: no java anywhere. Exercised directly against the
#      shared function, because emptying PATH for a whole script would take git, sed and mktemp with it —
#      inside `bash -c` only builtins are needed. This is the kind `prepare-dmtool-source.sh`'s caller note
#      reports as "JAVA_HOME is unset and no java is on PATH".
check_output "no java anywhere classifies as no-java" no-java \
  env -u JAVA_HOME PATH=/nonexistent /bin/bash -c \
    '. "$1"; a12_classify_java ""; printf %s "$a12_java_kind"' _ "$ROOT/scripts/gradle-environment.sh"

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
