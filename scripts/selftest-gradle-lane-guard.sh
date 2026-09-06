#!/bin/sh
#
# selftest-gradle-lane-guard.sh — behavioural guard for the PreToolUse hook's repeat-lane check.
#
# WHY THIS EXISTS AND WHY IT LEADS WITH FAILURE. The thing under test can BLOCK a tool call, so its defects do
# not cost a wasted run, they cost the ability to work at all. Every dependency it touches is optional at the
# moment it runs — Node may be absent, the checkout may not be where the hook thinks, the payload may not
# parse, the state file may be corrupt or unwritable — and in every one of those cases the ONLY acceptable
# behaviour is to let the command through. So the fail-open cases come first here, and they outnumber the
# behavioural ones on purpose.
#
# The second half guards the direction that decides whether a guard survives at all. `scripts/lane-guard.sh`
# sits in this tree unwired because a cold review found it refusing justified work; its header names the two
# shapes that did it, and both are asserted below — a `--tests` OPERAND read as a task, and the pre-commit
# hook's own prescribed fix command refused. A guard that fights another guard gets switched off.
#
# The last case is a REGRESSION guard, not a feature one: adding check (3) restructured how the hook reads
# stdin (`payload="$(cat)"` before jq, because the new check needs `.session_id` and a stream is readable
# once). That edit could silently break checks (1) and (2), which had nothing to do with this change.
#
set -eu

REPO="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="$REPO/.claude/hooks/guard-long-run-antipatterns.sh"
FAIL=0

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; printf '        %s\n' "${2:-}"; FAIL=1; }

# A hook payload for one command. Built with printf rather than a JSON tool so the selftest has no dependency
# the thing under test does not already have.
payload() {
  printf '{"session_id":"%s","tool_name":"Bash","tool_input":{"command":"%s"}}' "$1" "$2"
}

# Run the hook for <session> <command>; sets RC and captures stderr into $ERR_FILE.
#
# THE OVERRIDES ARE ONE-SHOT, AND THAT IS A CORRECTNESS REQUIREMENT, NOT TIDINESS. In POSIX sh a variable
# assignment PREFIXING a function call persists in the shell after the function returns — so the natural
# spelling `A12_NODE=/nonexistent expect_allow ...` silently leaves every later case running without a Node.
# The first version of this file did exactly that: the fail-open cases passed, and then nothing was ever
# recorded, so every block case reported a false pass in the safe-looking direction. Set OVER_* immediately
# before a call; `invoke` clears them, so an override cannot outlive the case that wanted it.
ERR_FILE=""
RC=0
OVER_NODE=""
OVER_ROOT=""
OVER_TMP=""
invoke() {
  ERR_FILE="$SANDBOX/stderr.txt"
  set +e
  payload "$1" "$2" | env \
    A12_NODE="${OVER_NODE:-node}" \
    CLAUDE_PROJECT_DIR="${OVER_ROOT:-$REPO}" \
    TMPDIR="${OVER_TMP:-$STATE_DIR}" \
    bash "$HOOK" >"$SANDBOX/stdout.txt" 2>"$ERR_FILE"
  RC=$?
  set -e
  OVER_NODE=""
  OVER_ROOT=""
  OVER_TMP=""
}

expect_allow() {
  invoke "$2" "$3"
  if [ "$RC" -eq 0 ]; then pass "$1"; else fail "$1" "exit $RC: $(cat "$ERR_FILE")"; fi
}

# Assert an exit code captured outside invoke(). Written as if/else rather than `A && pass || fail`, which
# SC2015 flags that chain, and it is right: it really does run BOTH branches if `pass` returns non-zero.
expect_rc() {
  if [ "$3" -eq "$2" ]; then pass "$1"; else fail "$1" "expected exit $2, got $3"; fi
}

expect_block() {
  invoke "$2" "$3"
  if [ "$RC" -eq 2 ]; then pass "$1"; else fail "$1" "expected exit 2, got $RC"; fi
}

SANDBOX="$(mktemp -d)"
# shellcheck disable=SC2064  # expand SANDBOX now: it must be removed even if a later assignment changes it
trap "rm -rf '$SANDBOX'" EXIT

# State lives under a sandbox TMPDIR, so this selftest can never see or disturb a real session's state.
STATE_DIR="$SANDBOX/state"
mkdir -p "$STATE_DIR"

printf '\nfail-open — a guard that breaks the tool it guards is worse than no guard\n'

# Node is the one hard dependency of check (3). A12_NODE is how run-typescript.sh selects its runtime.
OVER_NODE="$SANDBOX/definitely-not-node"
expect_allow "no Node runtime lets the command through" fo1 './build.sh :cli:test'
OVER_NODE="$SANDBOX/definitely-not-node"
expect_allow "and still lets a REPEAT through" fo1 './build.sh :cli:test'

# The hook SELF-LOCATES its implementation from its own path. A copy of it sitting outside the checkout finds
# no scripts/ beside it — the shape a plugin copy or a stray backup would have — and must let commands through.
mkdir -p "$SANDBOX/orphan/hooks"
cp "$HOOK" "$SANDBOX/orphan/hooks/guard.sh"
set +e
printf '{"session_id":"fo2","tool_input":{"command":"./build.sh :cli:test"}}' \
  | env TMPDIR="$STATE_DIR" bash "$SANDBOX/orphan/hooks/guard.sh" >/dev/null 2>&1
rc_orphan=$?
set -e
expect_rc "a hook copy outside the checkout lets the command through" 0 "$rc_orphan"

set +e
env TMPDIR="$STATE_DIR" CLAUDE_PROJECT_DIR="$REPO" sh -c "printf 'this is not json' | bash '$HOOK'" >/dev/null 2>&1
rc_badjson=$?
env TMPDIR="$STATE_DIR" CLAUDE_PROJECT_DIR="$REPO" sh -c "printf '' | bash '$HOOK'" >/dev/null 2>&1
rc_empty=$?
set -e
expect_rc "an unparseable payload lets the command through" 0 "$rc_badjson"
expect_rc "an empty payload lets the command through" 0 "$rc_empty"

# A payload with no session_id still has a command; it must be judged, never crash.
set +e
env TMPDIR="$STATE_DIR" CLAUDE_PROJECT_DIR="$REPO" sh -c \
  "printf '{\"tool_input\":{\"command\":\"./build.sh :cli:test\"}}' | bash '$HOOK'" >/dev/null 2>&1
rc_nosession=$?
set -e
expect_rc "a payload without session_id lets the command through" 0 "$rc_nosession"

# A corrupt state file reads as "nothing has run yet".
mkdir -p "$STATE_DIR/a12-gradle-lane-guard"
printf 'not json at all' > "$STATE_DIR/a12-gradle-lane-guard/fo3.json"
expect_allow "a corrupt state file lets the command through" fo3 './build.sh :cli:test'
printf '{"lanes":"not-an-array"}' > "$STATE_DIR/a12-gradle-lane-guard/fo4.json"
expect_allow "a state file of the wrong shape lets the command through" fo4 './build.sh :cli:test'

# An unwritable state directory degrades to "every run is the first". It must never block.
mkdir -p "$SANDBOX/ro/a12-gradle-lane-guard"
chmod 500 "$SANDBOX/ro/a12-gradle-lane-guard"
OVER_TMP="$SANDBOX/ro"
expect_allow "an unwritable state dir lets the command through" fo5 './build.sh :cli:test'
OVER_TMP="$SANDBOX/ro"
expect_allow "and does not start blocking on the second try" fo5 './build.sh :cli:test'
chmod 700 "$SANDBOX/ro/a12-gradle-lane-guard"

printf '\nnever refuse justified work — the failure that unwired scripts/lane-guard.sh\n'

expect_allow "the first run of a lane" c1 './build.sh :cli:test'
expect_allow "a DIFFERENT lane, after one already ran" c1 './build.sh :adapter:test'
expect_allow "a third and fourth distinct lane" c1 './build.sh :interpreter:check'
expect_allow "a filtered repeat of an already-run lane" c1 "./build.sh :cli:test --tests '*Foo*' -Pfast"
expect_allow "the pre-commit hook's own prescribed doc-guard fix" c1 \
  './build.sh --console=plain :docGuardTest :cli:docGuardTest :cli:referentGuardTest'
expect_allow "the same fix with --rerun" c1 \
  './build.sh --console=plain :docGuardTest --rerun :cli:docGuardTest --rerun'
expect_allow "a narrower test task" c1 './build.sh :cli:publicContractTest'
expect_allow "a non-Gradle command that MENTIONS a lane" c1 "grep -rn 'build.sh :cli:test' docs/"
expect_allow "a mutant run, which names a lane as an argument" c1 \
  './scripts/mutant.sh a.java x y "*Test*" ":cli:test"'

printf '\nfire on the shape that actually wasted a session\n'

expect_block "a REPEAT of an already-run lane, unfiltered" c1 './build.sh :cli:test'
if grep -q ':cli:test already ran' "$ERR_FILE"; then
  pass "the refusal names the repeated lane"
else
  fail "the refusal names the repeated lane" "$(cat "$ERR_FILE")"
fi
if grep -q '# why:' "$ERR_FILE"; then
  pass "the refusal names the escape hatch"
else
  fail "the refusal names the escape hatch" "$(cat "$ERR_FILE")"
fi
expect_allow "the same repeat, with the question stated" c1 \
  './build.sh :cli:test # why: regression backstop after six commits'
expect_block "a repeat of the OTHER already-run lane" c1 './build.sh :adapter:test'

printf '\nregression — the stdin restructure must not have broken checks (1) and (2)\n'

expect_block "check (1): a job-control & is still refused" r1 'nohup ./build.sh :test &'
# The spellings check (2) anchors on. The first two are its original contract; the `while`/`until` pair is
# G80, closed 2026-09-07 — `while pgrep -f x; do … done` is the canonical loop gate this check is named
# for, and it slipped through from the check's first day because the anchor list had no keyword in it.
expect_block "check (2): the bare form is refused" r1 'pgrep -f gradle'
expect_block "check (2): the ! loop-gate form is refused" r1 '! pgrep -f gradle'
expect_block "check (2): the while loop gate is refused (G80)" r1 'while pgrep -f gradle; do sleep 5; done'
expect_block "check (2): the until loop gate is refused (G80)" r1 'until ! pgrep -f gradle; do sleep 5; done'
# The keyword list stops at while/until ON PURPOSE. `do`, `then` and `else` also introduce a command position,
# but they precede this string in ordinary English, and a guard that refuses a commit message ABOUT the guard
# throws away the mention-safety the anchor exists for. Both directions asserted, so a later widening has to
# come here and argue with this case rather than quietly break it.
expect_allow "check (2): prose after 'then' is NOT refused" r1 \
  "git commit -m 'docs: explain that pgrep -f x is unreliable'"
expect_allow "check (2): a bare mention mid-sentence is NOT refused" r1 \
  "echo 'the poll used pgrep -f gradle and self-matched'"
expect_allow "and a plain command is still allowed" r1 'ls -la'

printf '\ndual-runtime — Codex CLI sends the same fields, so the same script must serve it\n'

# codex-cli 0.153.4 PreToolUse payload keys, measured from the binary: session_id, turn_id, agent_type,
# transcript_path, hook_event_name, model, permission_mode, tool_name, tool_input, tool_use_id. Only the
# three this script reads need to match; the extra keys must be ignored rather than trip the parser.
codex_payload() {
  printf '{"session_id":"%s","turn_id":"t1","agent_type":"codex","hook_event_name":"PreToolUse",' "$1"
  printf '"model":"gpt-5","permission_mode":"auto","tool_name":"Bash","tool_use_id":"u1",'
  printf '"tool_input":{"command":"%s"}}' "$2"
}
set +e
codex_payload cx1 './build.sh :cli:test' | env TMPDIR="$STATE_DIR" bash "$HOOK" >/dev/null 2>&1
rc_cx_first=$?
codex_payload cx1 './build.sh :cli:test' | env TMPDIR="$STATE_DIR" bash "$HOOK" >/dev/null 2>&1
rc_cx_repeat=$?
codex_payload cx1 "./build.sh :cli:test --tests '*Foo*'" | env TMPDIR="$STATE_DIR" bash "$HOOK" >/dev/null 2>&1
rc_cx_filtered=$?
set -e
expect_rc "a Codex payload's first lane is allowed" 0 "$rc_cx_first"
expect_rc "a Codex payload's repeat lane is blocked" 2 "$rc_cx_repeat"
expect_rc "a Codex payload's filtered repeat is allowed" 0 "$rc_cx_filtered"

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  printf 'selftest-gradle-lane-guard: all cases pass\n'
else
  printf 'selftest-gradle-lane-guard: FAILURES above\n' >&2
fi
exit "$FAIL"
