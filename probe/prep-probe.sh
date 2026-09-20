#!/usr/bin/env bash
# Set up an ISOLATED sandbox for a probe run (see probe/README.md). A probe drives a general agent through a
# multi-prompt session against the SHIPPED product — the NATIVE binary + the skill — and captures every
# `dmtool` call OBJECTIVELY via a logging shim, so the deep analysis can't be fooled by the agent's own
# self-report (the whole point: a false success the agent didn't notice still shows in the log + the model).
#
# Deterministic side-effecting setup lives here, not in an agent prompt (a subagent can't self-approve
# rm/mkdir — the CLAUDE.md "scripts for setup, agents for judgment" rule).
#
#   ./probe/prep-probe.sh                       # CLEAN start: empty work/ (a from-scratch authoring session)
#   ./probe/prep-probe.sh <repo-relative-path>  # GIVEN-WORKSPACE start: seed work/ from a committed fixture
#                                               #   (a model file OR a workspace dir) — a maintain/refactor session
#
# A task declares which it needs with a `**Start:** clean | fixture <path>` line; the main loop passes the
# fixture path here. A seeded fixture is GIVEN material (the model under edit), distinct from the task's GOLD
# (the expected end-state, never given to the agent) — the agent reads work/ legitimately; the anti-cheat
# audit only flags reading the repo's gold/source, not the sandbox.
#
# Env: RK_PROBE_SANDBOX (default /tmp/rk-probe-sandbox). Idempotent — safe to re-run.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SANDBOX="${RK_PROBE_SANDBOX:-/tmp/rk-probe-sandbox}"
FIXTURE="${1:-}"   # optional: a repo-relative model file or workspace dir to seed work/ (clean if absent)
NATIVE="$ROOT/cli/build/native/nativeCompile/dmtool"
LOG="$SANDBOX/dmtool-calls.log"

# Prove the digest's discriminator before preparing a run. In particular, a legitimate nested computation
# outcome:"error" must stay clean while a top-level RK_INTERNAL_ERROR must still fire.
"$ROOT/probe/digest-probe.sh" --self-test >/dev/null

# The probe tests the SHIPPED artifact — the native image (the v0.1.0 reflection crash was native-ONLY, which
# is exactly why the JVM-based eval never saw it). Build it first if missing.
[[ -x "$NATIVE" ]] || {
  echo "native binary not found: $NATIVE" >&2
  echo "build it first (GraalVM 25 auto-discovered):" >&2
  echo "  GRADLE_USER_HOME=\"\$(cd \"$ROOT/..\" && pwd)/.gradle-isolated\" \\" >&2
  echo "  GRAALVM_HOME=<see: /usr/libexec/java_home -V, the Oracle GraalVM 25 row> \\" >&2
  echo "  JAVA_HOME=\"\$(/usr/libexec/java_home -v 21)\" $ROOT/gradlew :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME" >&2
  exit 2
}

# FRESHNESS GATE — the #1 probe precondition: NEVER probe a stale native image (a probe measures the SHIPPED
# product; a stale binary measures a fiction). The binary must be newer than every :cli source/test file AND
# the captured reflection metadata. Because the metadata capture is TEST-SUITE-DRIVEN, a *new test* alone
# stales the binary (its reflective surface may be uncaptured) — so the check covers src AND test AND the
# bundled metadata. Read-only: this only VERIFIES + halts loudly; the main loop runs the capture + rebuild
# (deterministic side-effecting setup lives in those scripts, per CLAUDE.md).
# Cover EVERY module's src tree, not just cli+root (kernel-decoupling §3b #13 class-fix): the native binary
# links cli + all its dependency modules (the core/rulekit/modelkit/edit kits, :adapter, :interpreter,
# :schemakit) and its reflection metadata is captured from the whole test suite, so a source change in ANY
# module can stale it. `$ROOT/*/src` sweeps every subproject's src; `$ROOT/src` catches root's remaining
# integration-test tree. (Kotlin counts too — :interpreter/:adapter are partly .kt and ride the native image.)
stale="$(find "$ROOT"/*/src "$ROOT/src" -type f \( -name '*.java' -o -name '*.kt' -o -name '*.json' \) -newer "$NATIVE" -print -quit 2>/dev/null)"
if [[ -n "$stale" ]]; then
  echo "STALE native binary — refusing to probe a fiction (newer than the build: $stale)" >&2
  echo "  the capture is TEST-SUITE-DRIVEN, so re-capture metadata FIRST, then rebuild:" >&2
  echo "    GRAALVM_HOME=<see: /usr/libexec/java_home -V, the Oracle GraalVM 25 row> ./scripts/capture-native-config.sh" >&2
  echo "    GRAALVM_HOME=<see: /usr/libexec/java_home -V, the Oracle GraalVM 25 row> ./build.sh :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME" >&2
  echo "    ./scripts/native-gate.sh   # then confirm the rebuilt binary is clean" >&2
  exit 3
fi

# The staleness the sweep above cannot see: a rebuild WITHOUT a re-capture leaves the binary newer than every
# source file while the committed reflection metadata describes older code. ~2s; fails closed with no stamp.
"$ROOT/build.sh" --console=plain :cli:verifyNativeCaptureStamp

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

# GIVEN-WORKSPACE start: seed work/ from a committed fixture (a model file OR a workspace dir), or leave it
# clean for a from-scratch session. The fixture is OWN-domain committed material (license-safe); the agent
# discovers + edits it (it never sees the task's gold — that stays in the task file, not the sandbox).
if [[ -n "$FIXTURE" ]]; then
  src="$ROOT/$FIXTURE"
  [[ -e "$src" ]] || { echo "fixture not found: $src" >&2; exit 2; }
  if [[ -d "$src" ]]; then cp -R "$src"/. "$SANDBOX/work/"; else cp "$src" "$SANDBOX/work/"; fi
fi

cp "$ROOT/skill/SKILL.md" "$SANDBOX/SKILL.md"
# the SHIPPED bug-report skill (the same one plugin users get) — the probe's report pass points the agent at
# it, so the report rigor has one source (no probe-only preamble) and the agent files exactly as a user would.
cp "$ROOT/skill/BUG-REPORT-SKILL.md" "$SANDBOX/BUG-REPORT-SKILL.md"

echo "probe sandbox ready: $SANDBOX"
echo "  native : $("$NATIVE" --version 2>/dev/null | head -1)"
echo "  shim   : $SANDBOX/bin/dmtool   (objective call log → $LOG)"
echo "  skill  : $SANDBOX/SKILL.md  (+ $SANDBOX/BUG-REPORT-SKILL.md for the report pass)"
echo "  start  : $( [[ -n "$FIXTURE" ]] && echo "seeded from $FIXTURE (given-workspace)" || echo "clean (from-scratch)" )"
echo "  workdir: $SANDBOX/work"
echo
echo "drive the probe agent with: cwd=$SANDBOX/work, PATH=$SANDBOX/bin:\$PATH, skill at $SANDBOX/SKILL.md"
