#!/usr/bin/env bash
# Behavioral guard for prepare-dmtool-source.sh. A disposable Git repository supplies a fake Gradle build and
# dmtool launcher so the test exercises revision reuse, stale rebuild, dirty refusal, and post-build provenance
# without compiling the real CLI.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBJECT="$ROOT/scripts/prepare-dmtool-source.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REPO="$WORK/repo"
LAUNCHER="$REPO/cli/build/install/dmtool/bin/dmtool"
BUILD_COUNT="$REPO/cli/build/prepare-build-count"

fail() { echo "SELFTEST FAIL: $*" >&2; exit 1; }

mkdir -p "$REPO/scripts"
cp "$SUBJECT" "$REPO/scripts/prepare-dmtool-source.sh"
cp "$ROOT/scripts/gradle-environment.sh" "$REPO/scripts/gradle-environment.sh"
chmod +x "$REPO/scripts/prepare-dmtool-source.sh"
cat > "$REPO/.gitignore" <<'EOF'
/cli/build/
EOF
cat > "$REPO/build.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Model the REAL build.sh, which ends in `exec "$ROOT/gradlew" "$@"` with no `cd` and no `-p`: Gradle takes
# the CALLER's directory as the project directory. A fake that computed its own root would be better behaved
# than the thing under test and could never reproduce the defect.
[[ "$PWD" == "$root" ]] || {
  echo "fake build: Gradle would treat $PWD as the project directory, not $root" >&2
  exit 1
}
launcher="$root/cli/build/install/dmtool/bin/dmtool"
mkdir -p "$(dirname "$launcher")"
printf '%s\n' build >> "$root/cli/build/prepare-build-count"
revision="$(git -C "$root" rev-parse HEAD)"
[[ "${FAKE_STALE_BUILD:-0}" != 1 ]] || revision=0000000000000000000000000000000000000000
cat > "$launcher" <<LAUNCHER
#!/usr/bin/env bash
# Mirror Gradle's generated start script: a JAVA_HOME that holds no runnable java is a START failure with a
# stderr diagnostic and a non-zero exit, NOT a differently-provenanced launcher.
if [[ -n "\${JAVA_HOME:-}" && ! -x "\$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: JAVA_HOME is set to an invalid directory: \$JAVA_HOME" >&2
  exit 1
fi
printf '%s\n' 'a12-dmkits 9.9.9' '  source: $revision (clean)'
LAUNCHER
chmod +x "$launcher"
EOF
chmod +x "$REPO/build.sh"

git -C "$REPO" init -q
git -C "$REPO" config user.email selftest@example.invalid
git -C "$REPO" config user.name selftest
git -C "$REPO" add .
git -C "$REPO" commit -qm initial
HEAD_REVISION="$(git -C "$REPO" rev-parse HEAD)"

install_fake_launcher() {
  local revision="$1"
  mkdir -p "$(dirname "$LAUNCHER")"
  cat > "$LAUNCHER" <<EOF
#!/usr/bin/env bash
if [[ -n "\${JAVA_HOME:-}" && ! -x "\$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: JAVA_HOME is set to an invalid directory: \$JAVA_HOME" >&2
  exit 1
fi
printf '%s\n' 'a12-dmkits 9.9.9' '  source: $revision (clean)'
EOF
  chmod +x "$LAUNCHER"
}

# 1. A launcher built from the exact clean revision is reused without invoking Gradle.
install_fake_launcher "$HEAD_REVISION"
resolved="$(bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "current launcher resolved as '$resolved', want '$LAUNCHER'"
[[ ! -e "$BUILD_COUNT" ]] || fail "current launcher unnecessarily invoked the build"

# 2. A stale launcher is rebuilt once, and only the newly verified JVM path is printed.
install_fake_launcher 1111111111111111111111111111111111111111
resolved="$(bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "rebuilt launcher resolved as '$resolved', want '$LAUNCHER'"
[[ "$(wc -l < "$BUILD_COUNT" | tr -d ' ')" == 1 ]] || fail "stale launcher did not build exactly once"
"$LAUNCHER" --version | grep -Fq "source: $HEAD_REVISION (clean)" \
  || fail "the fake build did not produce a current launcher"

# 3. A dirty source tree is not a reproducible evidence identity and must fail before building.
printf '%s\n' dirty > "$REPO/untracked-source.txt"
set +e
dirty_out="$(bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
dirty_status=$?
set -e
[[ $dirty_status -ne 0 ]] || fail "dirty source was accepted"
[[ "$dirty_out" == *"not clean"* ]] || fail "dirty refusal was not corrective: $dirty_out"
[[ "$(wc -l < "$BUILD_COUNT" | tr -d ' ')" == 1 ]] || fail "dirty refusal invoked the build"
rm -f "$REPO/untracked-source.txt"

# 4. A build that reports the wrong revision is refused rather than cached or printed as ready.
install_fake_launcher 2222222222222222222222222222222222222222
set +e
stale_out="$(FAKE_STALE_BUILD=1 bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
stale_status=$?
set -e
[[ $stale_status -ne 0 ]] || fail "post-build provenance mismatch was accepted"
[[ "$stale_out" == *"does not report"* ]] || fail "provenance refusal was not corrective: $stale_out"
[[ "$stale_out" != *"$LAUNCHER"$'\n'* ]] || fail "failed preparation printed a ready launcher path"

# 5. A rebuild triggered from an UNRELATED working directory still succeeds. This is the cross-project route:
#    a sibling repository calls the script by absolute path and never chdirs, so a preparation that hands the
#    build off without anchoring its own directory works only while a current launcher happens to exist.
install_fake_launcher 3333333333333333333333333333333333333333
resolved="$(cd "$WORK" && bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "rebuild from an unrelated directory resolved as '$resolved'"
"$LAUNCHER" --version | grep -Fq "source: $HEAD_REVISION (clean)" \
  || fail "the rebuild from an unrelated directory did not produce a current launcher"

# 6. A stale or removed JAVA_HOME must never be reported as a source mismatch. The launcher's provenance is
#    embedded and correct; only the JVM that would run it is missing. Two outcomes are legitimate — the same
#    JDK-21 pin build.sh applies makes the check succeed (macOS), or the platform offers no pin and the script
#    refuses while naming the start failure — and exactly one is forbidden: a provenance verdict.
install_fake_launcher "$HEAD_REVISION"
missing_jdk="$WORK/removed-jdk-home"
set +e
stale_java_out="$(JAVA_HOME="$missing_jdk" bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
stale_java_status=$?
set -e
[[ "$stale_java_out" != *"does not report source"* ]] \
  || fail "a broken JAVA_HOME was reported as a provenance mismatch: $stale_java_out"
if [[ -x /usr/libexec/java_home ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  [[ $stale_java_status -eq 0 ]] \
    || fail "the pinned JDK 21 must carry the launcher check through a broken JAVA_HOME: $stale_java_out"
  [[ "$stale_java_out" == *"$LAUNCHER"* ]] \
    || fail "the pinned-JDK run did not print the launcher path: $stale_java_out"
  [[ "$stale_java_out" == *"holds no runnable java"* ]] \
    || fail "the caller was not told its own JAVA_HOME cannot run the returned launcher: $stale_java_out"
else
  [[ $stale_java_status -ne 0 ]] || fail "an unstartable launcher was accepted without a pin: $stale_java_out"
  [[ "$stale_java_out" == *"did not start"* && "$stale_java_out" == *"JAVA_HOME"* ]] \
    || fail "the launcher start failure was not surfaced: $stale_java_out"
fi

echo "SELFTEST OK: clean-revision reuse, stale rebuild, dirty refusal, post-build provenance, foreign cwd, broken JAVA_HOME"
