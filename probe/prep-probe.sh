#!/usr/bin/env bash
# Set up an ISOLATED sandbox for a probe run (see probe/README.md). A probe drives a general agent through a
# multi-prompt session against the SHIPPED product — the NATIVE binary + the skill — and captures every
# `dmtool` call OBJECTIVELY via a logging shim, so the deep analysis can't be fooled by the agent's own
# self-report (the whole point: a false success the agent didn't notice still shows in the log + the model).
#
# Deterministic side-effecting setup lives here, not in an agent prompt (a subagent can't self-approve
# rm/mkdir — the CLAUDE.md "scripts for setup, agents for judgment" rule).
#
#   ./probe/prep-probe.sh            # verify native binary, (re)create sandbox + logging shim + skill copy
#
# Env: RK_PROBE_SANDBOX (default /tmp/rk-probe-sandbox). Idempotent — safe to re-run.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SANDBOX="${RK_PROBE_SANDBOX:-/tmp/rk-probe-sandbox}"
NATIVE="$ROOT/cli/build/native/nativeCompile/dmtool"
LOG="$SANDBOX/dmtool-calls.log"

# The probe tests the SHIPPED artifact — the native image (the v0.1.0 reflection crash was native-ONLY, which
# is exactly why the JVM-based eval never saw it). Build it first if missing.
[[ -x "$NATIVE" ]] || {
  echo "native binary not found: $NATIVE" >&2
  echo "build it first (GraalVM 25 auto-discovered):" >&2
  echo "  GRADLE_USER_HOME=\"\$(cd \"$ROOT/..\" && pwd)/.gradle-isolated\" \\" >&2
  echo "  GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \\" >&2
  echo "  JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\" $ROOT/gradlew :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME" >&2
  exit 2
}

# FRESHNESS GATE — the #1 probe precondition: NEVER probe a stale native image (a probe measures the SHIPPED
# product; a stale binary measures a fiction). The binary must be newer than every :cli source/test file AND
# the captured reflection metadata. Because the metadata capture is TEST-SUITE-DRIVEN, a *new test* alone
# stales the binary (its reflective surface may be uncaptured) — so the check covers src AND test AND the
# bundled metadata. Read-only: this only VERIFIES + halts loudly; the main loop runs the capture + rebuild
# (deterministic side-effecting setup lives in those scripts, per CLAUDE.md).
stale="$(find "$ROOT/cli/src" "$ROOT/src" -type f \( -name '*.java' -o -name '*.json' \) -newer "$NATIVE" -print -quit 2>/dev/null)"
if [[ -n "$stale" ]]; then
  echo "STALE native binary — refusing to probe a fiction (newer than the build: $stale)" >&2
  echo "  the capture is TEST-SUITE-DRIVEN, so re-capture metadata FIRST, then rebuild:" >&2
  echo "    GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home ./scripts/capture-native-config.sh" >&2
  echo "    GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home ./build.sh :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME" >&2
  echo "    ./scripts/native-gate.sh   # then confirm the rebuilt binary is clean" >&2
  exit 3
fi

rm -rf "$SANDBOX"
mkdir -p "$SANDBOX/bin" "$SANDBOX/work"
: > "$LOG"

# The logging `dmtool` shim: record the invocation + full stdout + stderr + exit code to $LOG, then replay
# them so the agent sees exactly what the real binary produced (`dmtool … | jq` still works — output is
# captured then re-emitted). NATIVE + LOG are baked as absolute paths; the shim's own runtime vars are \$-escaped.
cat > "$SANDBOX/bin/dmtool" <<SHIM
#!/usr/bin/env bash
printf '\n\$ dmtool %s\n' "\$*" >> "$LOG"
o="\$(mktemp)"; e="\$(mktemp)"
"$NATIVE" "\$@" >"\$o" 2>"\$e"; code=\$?
{ echo "--- stdout:"; cat "\$o"; echo "--- stderr:"; cat "\$e"; echo "[exit \$code]"; } >> "$LOG"
cat "\$o"; cat "\$e" >&2
rm -f "\$o" "\$e"
exit \$code
SHIM
chmod +x "$SANDBOX/bin/dmtool"

cp "$ROOT/skill/SKILL.md" "$SANDBOX/SKILL.md"

echo "probe sandbox ready: $SANDBOX"
echo "  native : $("$NATIVE" --version 2>/dev/null | head -1)"
echo "  shim   : $SANDBOX/bin/dmtool   (objective call log → $LOG)"
echo "  skill  : $SANDBOX/SKILL.md"
echo "  workdir: $SANDBOX/work"
echo
echo "drive the probe agent with: cwd=$SANDBOX/work, PATH=$SANDBOX/bin:\$PATH, skill at $SANDBOX/SKILL.md"
