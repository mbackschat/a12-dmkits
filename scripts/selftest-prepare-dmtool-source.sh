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

# Does this platform offer the JDK 21 pin? Asked of the PRODUCTION helper, not by re-testing
# /usr/libexec/java_home here — the same single-sourcing that the launcher gates already use, so a pin
# mechanism added for another platform is picked up by the cases below instead of silently skipping them.
# Two cases below assert "the pin carried the check", which is unassertable where there is no pin (Linux/CI).
# shellcheck source=scripts/gradle-environment.sh
source "$ROOT/scripts/gradle-environment.sh"
PLATFORM_HAS_PIN=0
[[ -n "$(a12_pinned_jdk21_home 2>/dev/null || true)" ]] && PLATFORM_HAS_PIN=1

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
if [[ "${FAKE_EMPTY_BUILD:-0}" == 1 ]]; then rm -f "$launcher"; exit 0; fi
# A build with a SIDE EFFECT on the source tree. The script rechecks HEAD and cleanliness after building for
# exactly this, and both branches were advertised and unguarded.
[[ "${FAKE_DIRTY_BUILD:-0}" != 1 ]] || printf '%s\n' side-effect > "$root/build-side-effect.txt"
if [[ "${FAKE_MOVE_HEAD:-0}" == 1 ]]; then
  git -C "$root" commit -q --allow-empty -m "moved by the build"
fi
cat > "$launcher" <<LAUNCHER
#!/usr/bin/env bash
# Mirror Gradle's generated start script: a JAVA_HOME that holds no runnable java is a START failure with a
# stderr diagnostic and a non-zero exit, NOT a differently-provenanced launcher.
if [[ -n "\${JAVA_HOME:-}" && ! -x "\$JAVA_HOME/jre/sh/java" && ! -x "\$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: JAVA_HOME is set to an invalid directory: \$JAVA_HOME" >&2
  exit 1
fi
printf '%s\n' 'a12-dmkits 9.9.9' '  source: $revision (clean)'
[[ "${FAKE_UNHEALTHY_BUILD:-0}" != 1 ]] || { echo "boom" >&2; exit 3; }
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

# `mode` shapes the ANSWER, which is what the script classifies on: `stdout` (the real launcher),
# `stderr` (the same banner on the other stream), `unhealthy` (the right banner, then a non-zero exit),
# `silent` (starts, exits 0, says nothing).
install_fake_launcher() {
  local revision="$1" mode="${2:-stdout}"
  mkdir -p "$(dirname "$LAUNCHER")"
  cat > "$LAUNCHER" <<EOF
#!/usr/bin/env bash
if [[ -n "\${JAVA_HOME:-}" && ! -x "\$JAVA_HOME/jre/sh/java" && ! -x "\$JAVA_HOME/bin/java" ]]; then
  echo "ERROR: JAVA_HOME is set to an invalid directory: \$JAVA_HOME" >&2
  exit 1
fi
if [[ -n "\${JAVA_HOME:-}" && -x "\$JAVA_HOME/bin/java" ]]; then
  # A runnable JDK is not necessarily a USABLE one: the real launcher dies with UnsupportedClassVersionError
  # on a too-old JVM. A fixture that starts happily there cannot tell a presence test from a version check.
  fake_major="\$("\$JAVA_HOME/bin/java" -version 2>&1 | sed -n 's/.*version "\\([0-9][0-9]*\\).*/\\1/p' | head -n 1)"
  if [[ -n "\$fake_major" && "\$fake_major" -lt 21 ]]; then
    echo "Error: LinkageError occurred while loading main class: UnsupportedClassVersionError" >&2
    exit 1
  fi
fi
case "$mode" in
  stderr) printf '%s\n' 'a12-dmkits 9.9.9' '  source: $revision (clean)' >&2 ;;
  silent) : ;;
  unhealthy) printf '%s\n' 'a12-dmkits 9.9.9' '  source: $revision (clean)'; echo boom >&2; exit 3 ;;
  *) printf '%s\n' 'a12-dmkits 9.9.9' '  source: $revision (clean)' ;;
esac
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
if [[ $PLATFORM_HAS_PIN -eq 1 ]]; then
  [[ $stale_java_status -eq 0 ]] \
    || fail "the pinned JDK 21 must carry the launcher check through a broken JAVA_HOME: $stale_java_out"
  [[ "$stale_java_out" == *"$LAUNCHER"* ]] \
    || fail "the pinned-JDK run did not print the launcher path: $stale_java_out"
  [[ "$stale_java_out" == *"holds no runnable java"* ]] \
    || fail "the caller was not told its own JAVA_HOME cannot run the returned launcher: $stale_java_out"
else
  [[ $stale_java_status -ne 0 ]] || fail "an unstartable launcher was accepted without a pin: $stale_java_out"
  [[ "$stale_java_out" == *"named no source revision"* && "$stale_java_out" == *"JAVA_HOME"* ]] \
    || fail "the launcher start failure was not surfaced: $stale_java_out"
fi

# 7. A launcher that is PRESENT but cannot run — a partial installDist, not an environment problem. The
#    previous script recovered by rebuilding; the first version of this one refused, which was a regression.
cat > "$LAUNCHER" <<'CORRUPT'
#!/usr/bin/env bash
echo "Error: Could not find or load main class io.github.mbackschat.a12.dm.cli.Cli" >&2
exit 1
CORRUPT
chmod +x "$LAUNCHER"
before_builds="$(wc -l < "$BUILD_COUNT" | tr -d ' ')"
resolved="$(bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "a corrupt install was not repaired by a rebuild: '$resolved'"
[[ "$(wc -l < "$BUILD_COUNT" | tr -d ' ')" == "$(( before_builds + 1 ))" ]] \
  || fail "the corrupt install did not trigger exactly one rebuild"

# 8. A build that reports success but leaves no launcher gets its OWN verdict, never the provenance message.
rm -f "$LAUNCHER"
set +e
empty_out="$(FAKE_EMPTY_BUILD=1 bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
empty_status=$?
set -e
[[ $empty_status -ne 0 ]] || fail "a build that produced no launcher was accepted: $empty_out"
[[ "$empty_out" != *"does not report source"* ]] \
  || fail "a missing launcher borrowed the provenance-mismatch message: $empty_out"
[[ "$empty_out" == *"left no runnable launcher"* ]] || fail "the empty build was not named: $empty_out"

# 9. The provenance answer is read from CONTENT, not from the stream it arrived on. A launcher whose banner
#    goes to stderr has answered; classifying on the exit status first filed it as "never started".
install_fake_launcher "$HEAD_REVISION" stderr
before_builds="$(wc -l < "$BUILD_COUNT" | tr -d ' ')"
resolved="$(bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "a banner on stderr was not read as an answer: '$resolved'"
[[ "$(wc -l < "$BUILD_COUNT" | tr -d ' ')" == "$before_builds" ]] \
  || fail "a launcher that answered correctly on stderr was rebuilt anyway"

# 10. …but content-first must not make a BROKEN launcher current. It names this revision and still exits
#     non-zero, so it earns the ordinary rebuild — and when the rebuild reproduces it, its own verdict.
install_fake_launcher "$HEAD_REVISION" unhealthy
before_builds="$(wc -l < "$BUILD_COUNT" | tr -d ' ')"
resolved="$(bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "a rebuild did not repair an unhealthy launcher: '$resolved'"
[[ "$(wc -l < "$BUILD_COUNT" | tr -d ' ')" == "$(( before_builds + 1 ))" ]] \
  || fail "the unhealthy launcher did not trigger exactly one rebuild"

install_fake_launcher "$HEAD_REVISION" unhealthy
set +e
unhealthy_out="$(FAKE_UNHEALTHY_BUILD=1 bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
unhealthy_status=$?
set -e
[[ $unhealthy_status -ne 0 ]] || fail "a launcher that exits non-zero was accepted: $unhealthy_out"
[[ "$unhealthy_out" != *"named no source revision"* ]] \
  || fail "a launcher that DID start borrowed the start-failure message: $unhealthy_out"
[[ "$unhealthy_out" != *"does not report source"* ]] \
  || fail "a launcher with correct provenance was called a mismatch: $unhealthy_out"
[[ "$unhealthy_out" == *"exits non-zero"* ]] || fail "the unhealthy verdict was not named: $unhealthy_out"

# 11. Starts, exits 0, says nothing — the shape that answers nothing at all. It stays `unstartable` (one
#     rebuild), which is the only classification the content-first order must NOT change.
install_fake_launcher "$HEAD_REVISION" silent
before_builds="$(wc -l < "$BUILD_COUNT" | tr -d ' ')"
resolved="$(bash "$REPO/scripts/prepare-dmtool-source.sh")"
[[ "$resolved" == "$LAUNCHER" ]] || fail "a silent launcher was not repaired by a rebuild: '$resolved'"
[[ "$(wc -l < "$BUILD_COUNT" | tr -d ' ')" == "$(( before_builds + 1 ))" ]] \
  || fail "the silent launcher did not trigger exactly one rebuild"

# 12. The post-build rechecks. Both are advertised in scripts/README.md and both were unguarded — deleting
#     either left this file green. A build that dirties the worktree, and one that moves HEAD.
install_fake_launcher 4444444444444444444444444444444444444444
set +e
dirtied_out="$(FAKE_DIRTY_BUILD=1 bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
dirtied_status=$?
set -e
rm -f "$REPO/build-side-effect.txt"
[[ $dirtied_status -ne 0 ]] || fail "a build that dirtied the source tree was accepted: $dirtied_out"
[[ "$dirtied_out" == *"worktree changed while"* ]] \
  || fail "the post-build worktree recheck did not fire: $dirtied_out"

install_fake_launcher 5555555555555555555555555555555555555555
set +e
moved_out="$(FAKE_MOVE_HEAD=1 bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1)"
moved_status=$?
set -e
git -C "$REPO" reset -q --hard "$HEAD_REVISION"
[[ $moved_status -ne 0 ]] || fail "a build that moved HEAD was accepted: $moved_out"
[[ "$moved_out" == *"HEAD changed while"* ]] || fail "the post-build HEAD recheck did not fire: $moved_out"

# 13. The caller note covers every reason the caller's own environment will not start the launcher, not just
#     an empty JAVA_HOME. A runnable JDK of the WRONG major, and an unset JAVA_HOME with no java on PATH,
#     were both silent — the returned path fails for the caller either way. Same probe `bin/dmtool` uses.
#     It must run BEFORE the no-pin stub is committed: the note's whole subject is "the pin carried the
#     check, your own JDK will not run the result", which needs a pin to exist. Sitting after the stub, it
#     was reaching a launcher that could not start at all — and only a fixture that tolerated an old JDK
#     hid that. Revision matters for the same reason: HEAD has not moved yet here.
install_fake_launcher "$HEAD_REVISION"
old_jdk="$WORK/old-jdk"
mkdir -p "$old_jdk/bin"
printf '#!/usr/bin/env bash\n%s\n' 'echo "openjdk version \"17.0.9\" 2023-10-17" >&2; exit 0' \
  > "$old_jdk/bin/java"
chmod +x "$old_jdk/bin/java"
set +e
old_java_out="$(JAVA_HOME="$old_jdk" bash "$REPO/scripts/prepare-dmtool-source.sh" 2>"$WORK/oldjava.err")"
old_java_status=$?
set -e
old_java_err="$(cat "$WORK/oldjava.err")"
if [[ $PLATFORM_HAS_PIN -eq 1 ]]; then
  # The pin carries the check, so the run SUCCEEDS and the caller is told its own JDK will not run the result.
  [[ $old_java_status -eq 0 ]] || fail "a wrong-major caller JDK was made fatal: $old_java_err"
  [[ "$old_java_out" == "$LAUNCHER" ]] \
    || fail "the wrong-major run did not resolve the launcher: '$old_java_out'"
  [[ "$old_java_err" == *"is Java 17"* ]] \
    || fail "the caller was not told its own JDK is too old for the returned launcher: $old_java_err"
else
  # No pin: the wrong-major JDK is the ONLY java available, so it genuinely cannot start the launcher and
  # refusing is correct. The note's subject ("the pin carried the check, your own JDK will not run the
  # result") PRESUPPOSES a pin, which this platform has none of — so asserting it here asserted a
  # contradiction. CI failed on exactly that for six days while every Mac run took the branch above.
  [[ $old_java_status -ne 0 ]] \
    || fail "with no pin, a launcher startable by nothing was accepted: $old_java_err"
  [[ "$old_java_err" == *"named no source revision"* ]] \
    || fail "the unstartable-launcher classification did not fire without a pin: $old_java_err"
fi
#     (The remaining `no-java` kind cannot be staged here — emptying PATH would take git, sed and mktemp
#     with it — so the classifier itself carries that case, in selftest-dmtool-launcher.sh.)

# The other two note arms, which a mutation sweep found unenforced: a `java` that FAILS, and one whose banner
# carries no version. Both are real shapes (a broken install, a vendor wrapper) and both were advertised in
# scripts/README.md with nothing asserting them.
stage_fake_jdk() {  # $1 = dir name, $2 = script body
  local home="$WORK/$1"
  mkdir -p "$home/bin"
  printf '#!/usr/bin/env bash\n%s\n' "$2" > "$home/bin/java"
  chmod +x "$home/bin/java"
  printf '%s' "$home"
}

failing_jdk="$(stage_fake_jdk failing-jdk 'echo "boom" >&2; exit 42')"
set +e
failing_err="$(JAVA_HOME="$failing_jdk" bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1 >/dev/null)"
failing_status=$?
set -e
[[ $failing_status -eq 0 ]] || fail "a failing caller java was made fatal: $failing_err"
[[ "$failing_err" == *"-version\` failed (exit 42)"* ]] \
  || fail "the caller was not told its own java cannot run: $failing_err"

mute_jdk="$(stage_fake_jdk mute-jdk 'echo "some vendor tool, no version here"; exit 0')"
set +e
mute_err="$(JAVA_HOME="$mute_jdk" bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1 >/dev/null)"
mute_status=$?
set -e
[[ $mute_status -eq 0 ]] || fail "an unreadable caller java banner was made fatal: $mute_err"
[[ "$mute_err" == *"reports no readable version"* ]] \
  || fail "the caller was not told its own java reports no version: $mute_err"

# 14. The same broken JAVA_HOME with NO pin available — the branch CI runs, and the only one that reaches
#    fail_unstartable. Case 6's macOS branch is satisfied by the pin alone, so without this case the
#    unstartable/mismatch classification, the preserved launcher stderr, and the named JAVA_HOME are all
#    advertised and untested. The pin is removed by stubbing the shared helper — one variable moved, and no
#    test-only hook in the production script. Last, because the stub has to be committed to keep the
#    clean-worktree gate satisfied and that moves HEAD.
sed 's|/usr/libexec/java_home|/nonexistent/java_home|g' \
  "$ROOT/scripts/gradle-environment.sh" > "$REPO/scripts/gradle-environment.sh"
! grep -q '/usr/libexec/java_home' "$REPO/scripts/gradle-environment.sh" \
  || fail "the no-pin stub did not take — the case would silently re-test the pinned path"
git -C "$REPO" add -A
git -C "$REPO" commit -qm "no-pin stub"
NOPIN_REVISION="$(git -C "$REPO" rev-parse HEAD)"
install_fake_launcher "$NOPIN_REVISION"
# stdout and stderr are captured SEPARATELY here: the diagnostic legitimately names the launcher path, so a
# merged capture cannot tell "printed a ready path" from "named the launcher it could not start".
set +e
nopin_out="$(JAVA_HOME="$missing_jdk" bash "$REPO/scripts/prepare-dmtool-source.sh" 2>"$WORK/nopin.err")"
nopin_status=$?
set -e
nopin_err="$(cat "$WORK/nopin.err")"
[[ $nopin_status -ne 0 ]] || fail "an unstartable launcher was accepted with no pin: $nopin_out"
[[ "$nopin_err" != *"does not report source"* ]] \
  || fail "with no pin, a start failure was still reported as a provenance mismatch: $nopin_err"
[[ "$nopin_err" == *"named no source revision"* ]] || fail "the start failure was not named: $nopin_err"
# The `unstartable` verdict now covers a launcher that STARTS and says nothing as well as one that never
# ran, so the message says what was actually observed — no provenance answer — and names the JDK verdict
# rather than asserting a start that may well have happened.
[[ "$nopin_err" == *"that JDK is unusable"* ]] \
  || fail "the diagnostic did not classify the JDK it used: $nopin_err"
[[ "$nopin_err" == *"JAVA_HOME used: $missing_jdk"* ]] \
  || fail "the diagnostic did not name the JAVA_HOME it actually used: $nopin_err"
[[ "$nopin_err" == *"| ERROR: JAVA_HOME is set to an invalid directory"* ]] \
  || fail "the launcher's own stderr was not preserved: $nopin_err"
[[ -z "$nopin_out" ]] || fail "a failed preparation printed something on stdout: $nopin_out"

# 15. The pin's OWN failure is surfaced rather than swallowed. Distinct from case 14: there the platform
#     offers no pin at all (nothing to report), here the pin exists and fails — a macOS box with no JDK 21
#     registered. The stub keeps java_home present but non-zero, which is exactly that shape.
# The stub must WRITE TO STDERR as the real `java_home` does when no matching JVM is installed — pointing it
# at /usr/bin/false exercised the failure branch but never the stderr passthrough that is the whole reason
# this block uses a temp file and a trap. A mutation sweep found that passthrough unenforced.
noisy_java_home="$WORK/noisy-java-home"
cat > "$noisy_java_home" <<'STUB'
#!/usr/bin/env bash
echo 'Unable to find any JVMs matching version "21".' >&2
exit 1
STUB
chmod +x "$noisy_java_home"
sed "s|/usr/libexec/java_home|$noisy_java_home|g" \
  "$ROOT/scripts/gradle-environment.sh" > "$REPO/scripts/gradle-environment.sh"
! grep -q '/usr/libexec/java_home' "$REPO/scripts/gradle-environment.sh" \
  || fail "the failing-pin stub did not take"
git -C "$REPO" add -A
git -C "$REPO" commit -qm "failing-pin stub"
FAILPIN_REVISION="$(git -C "$REPO" rev-parse HEAD)"
install_fake_launcher "$FAILPIN_REVISION"
set +e
failpin_out="$(bash "$REPO/scripts/prepare-dmtool-source.sh" 2>"$WORK/failpin.err")"
failpin_status=$?
set -e
failpin_err="$(cat "$WORK/failpin.err")"
[[ $failpin_status -eq 0 ]] \
  || fail "a failing pin was made fatal; the caller's JAVA_HOME may still work: $failpin_err"
[[ "$failpin_out" == "$LAUNCHER" ]] || fail "the failing-pin run did not resolve the launcher: '$failpin_out'"
[[ "$failpin_err" == *"none is registered"* ]] \
  || fail "the pin's own failure was swallowed rather than surfaced: $failpin_err"
[[ "$failpin_err" == *'| Unable to find any JVMs matching version "21".'* ]] \
  || fail "the pin resolver's own stderr was not passed through: $failpin_err"

# 16. A runnable-but-TOO-OLD caller JDK with no pin available. This is the case a presence test gets wrong:
#     `$JAVA_HOME/bin/java` exists, so "is it runnable?" says yes, the launcher dies anyway, and the old
#     script then advised a rebuild it had just performed. The verdict must name the JDK, not the launcher.
install_fake_launcher "$NOPIN_REVISION"
set +e
old_nopin_err="$(JAVA_HOME="$old_jdk" bash "$REPO/scripts/prepare-dmtool-source.sh" 2>&1 >/dev/null)"
old_nopin_status=$?
set -e
[[ $old_nopin_status -ne 0 ]] || fail "a launcher that cannot run on the caller's JDK was accepted"
[[ "$old_nopin_err" == *"that JDK is unusable (too-old, Java 17)"* ]] \
  || fail "the unusable JDK was not classified; a presence test would call it runnable: $old_nopin_err"
[[ "$old_nopin_err" != *"rebuild"* ]] \
  || fail "a JDK problem was diagnosed as a launcher problem, advising a rebuild: $old_nopin_err"

echo "SELFTEST OK: clean-revision reuse, stale rebuild, dirty refusal, post-build provenance, foreign cwd, broken JAVA_HOME, corrupt install, empty build, stderr banner, unhealthy launcher, silent launcher, post-build rechecks, no-pin start failure, caller-environment note, surfaced pin failure, unusable caller JDK"
