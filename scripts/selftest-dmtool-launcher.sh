#!/usr/bin/env bash
# selftest-dmtool-launcher.sh — prove `bin/dmtool`'s JDK gate REFUSES on every unusable-Java path.
#
# Why a script and not a JUnit test: the subject is a shell launcher, and what has to be exercised is the
# environment it reads (JAVA_HOME, JAVA_TOOL_OPTIONS, PATH) and the shape of the `java` it finds. A JUnit test
# would have to fabricate all of that through a process builder and would still not run the real script's
# resolution order.
#
# Every case below was a live bypass; all four were found by review rather than by a test, because the gate had
# none. The pattern is uniform and worth stating once: each bypass ended in the gate returning SUCCESS, so the
# failure surfaced later (or not at all) — and one of them exited 0 with empty stdout, which an agent reads as
# "ran fine, nothing to report". A gate that cannot fail is worse than no gate, because it is trusted.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DMTOOL="$ROOT/bin/dmtool"
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

printf '\n%s passed, %s failed\n' "$pass" "$fail"
[[ "$fail" -eq 0 ]]
