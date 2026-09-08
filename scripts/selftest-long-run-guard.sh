#!/bin/sh
#
# selftest-long-run-guard.sh — behavioural guard for the PreToolUse long-run hook.
#
# WHY IT LEADS WITH FAILURE. The thing under test can BLOCK a tool call, so its defects do not cost a wasted
# run, they cost the ability to work at all. Every input it touches is untrusted at the moment it runs — the
# payload may not parse, jq may be absent, the command field may be missing — and in every one of those cases
# the only acceptable behaviour is to let the command through. So the fail-open cases come first, and they
# outnumber the behavioural ones on purpose.
#
# WHY IT ASSERTS BOTH DIRECTIONS. A check that stops FIRING lets back the orphaned-build and self-matching-poll
# waste it exists for; one that wrongly BLOCKS makes the tool unusable and gets switched off. The second is the
# expensive direction, which is why every anchor here is pinned allow-side as well as block-side — a later
# widening has to come and argue with a case rather than quietly break it.
#
# THE HOOK'S TWO CHECKS SHIPPED UNTESTED, and writing these cases is what found G80: the `while` loop gate, the
# canonical spelling of the very anti-pattern check (2) is named for, had never matched, because the anchor
# list held only punctuation.
#
set -eu

REPO="$(cd "$(dirname "$0")/.." && pwd)"
HOOK="$REPO/.claude/hooks/guard-long-run-antipatterns.sh"
FAIL=0

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; printf '        %s\n' "${2:-}"; FAIL=1; }

# A hook payload for one command. Built with printf rather than a JSON tool so this selftest needs no
# dependency the thing under test does not already have.
payload() {
  printf '{"session_id":"%s","tool_name":"Bash","tool_input":{"command":"%s"}}' "$1" "$2"
}

ERR_FILE=""
RC=0
invoke() {
  ERR_FILE="$SANDBOX/stderr.txt"
  set +e
  payload "$1" "$2" | bash "$HOOK" >"$SANDBOX/stdout.txt" 2>"$ERR_FILE"
  RC=$?
  set -e
}

expect_allow() {
  invoke "$2" "$3"
  if [ "$RC" -eq 0 ]; then pass "$1"; else fail "$1" "exit $RC: $(cat "$ERR_FILE")"; fi
}

expect_block() {
  invoke "$2" "$3"
  if [ "$RC" -eq 2 ]; then pass "$1"; else fail "$1" "expected exit 2, got $RC"; fi
}

# Assert an exit code captured outside invoke(). Written as if/else rather than `A && pass || fail`, which
# SC2015 flags — rightly: that chain runs BOTH branches when `pass` returns non-zero.
expect_rc() {
  if [ "$3" -eq "$2" ]; then pass "$1"; else fail "$1" "expected exit $2, got $3"; fi
}

SANDBOX="$(mktemp -d)"
# shellcheck disable=SC2064  # expand SANDBOX now: it must be removed even if a later assignment changes it
trap "rm -rf '$SANDBOX'" EXIT

printf '\nfail-open — a guard that breaks the tool it guards is worse than no guard\n'

set +e
sh -c "printf 'this is not json' | bash '$HOOK'" >/dev/null 2>&1
rc_badjson=$?
sh -c "printf '' | bash '$HOOK'" >/dev/null 2>&1
rc_empty=$?
sh -c "printf '{\"tool_input\":{}}' | bash '$HOOK'" >/dev/null 2>&1
rc_nocommand=$?
set -e
expect_rc "an unparseable payload lets the command through" 0 "$rc_badjson"
expect_rc "an empty payload lets the command through" 0 "$rc_empty"
expect_rc "a payload carrying no command lets it through" 0 "$rc_nocommand"

# A copy outside the checkout — the shape a plugin copy or a stray backup has. Neither check reads anything
# beside itself, so it must behave identically rather than fail looking for the repository.
mkdir -p "$SANDBOX/orphan/hooks"
cp "$HOOK" "$SANDBOX/orphan/hooks/guard.sh"
set +e
printf '{"session_id":"fo1","tool_input":{"command":"ls -la"}}' \
  | bash "$SANDBOX/orphan/hooks/guard.sh" >/dev/null 2>&1
rc_orphan=$?
printf '{"session_id":"fo1","tool_input":{"command":"nohup ./build.sh :test &"}}' \
  | bash "$SANDBOX/orphan/hooks/guard.sh" >/dev/null 2>&1
rc_orphan_block=$?
set -e
expect_rc "a hook copy outside the checkout allows an ordinary command" 0 "$rc_orphan"
expect_rc "and still blocks an anti-pattern" 2 "$rc_orphan_block"

printf '\nnever refuse justified work — the failure that unwired scripts/lane-guard.sh\n'

expect_allow "an ordinary foreground build" c1 './build.sh :cli:test'
expect_allow "a filtered build" c1 "./build.sh :cli:test --tests '*Foo*' -Pfast"
expect_allow "a repeat of the same build — the hook is stateless by design" c1 './build.sh :cli:test'
expect_allow "the pre-commit hook's own prescribed doc-guard fix" c1 \
  './build.sh --console=plain :docGuardTest :cli:docGuardTest :cli:referentGuardTest'
expect_allow "a plain command" c1 'ls -la'

printf '\ncheck (1) — a hand-detached & orphans the real work\n'

expect_block "a trailing & is refused" r1 'nohup ./build.sh :test &'
expect_block "a subshell-closing detach is refused" r1 '(./build.sh :test &)'
expect_block "a detach before a ; is refused" r1 './build.sh :test & ; echo done'
# The three shapes whose & is NOT job control. A redirection carries one adjacent to > or <, and && is the
# boolean operator; refusing either would refuse most commands in this repository.
expect_allow "&& is not job control" r1 'cd /repo && ./build.sh :test'
expect_allow "2>&1 is a redirection, not a detach" r1 './build.sh :cli:check > run.log 2>&1'
expect_allow "&> is a redirection too" r1 './build.sh :test &> run.log'

printf '\ncheck (2) — the full-match process probe self-matches, so it cannot gate liveness\n'

# The first two are the check's original contract. The G80 keyword anchor carries ONLY the loop gates with no
# `!` — `until ! …` was already caught by the original `!` anchor, which a mutation audit confirmed by
# deleting the keyword alternative and watching that case survive. So the bang-less pair below is the part
# that would silently stop being covered, and both spellings are pinned rather than one standing for both.
expect_block "the bare form is refused" r1 'pgrep -f gradle'
expect_block "the ! loop-gate form is refused" r1 '! pgrep -f gradle'
expect_block "the while loop gate is refused (G80)" r1 'while pgrep -f gradle; do sleep 5; done'
expect_block "the bang-less until gate is refused (G80)" r1 'until pgrep -f gradle; do sleep 5; done'
expect_block "the until ! form is refused by the original anchor" r1 'until ! pgrep -f gradle; do sleep 5; done'
expect_block "the --full spelling is refused" r1 'pgrep --full gradle'
expect_block "after a pipe is refused" r1 'echo x | pgrep -f gradle'
# The keyword list stops at while/until ON PURPOSE. `do`, `then` and `else` also open a command position, but
# they precede this string in ordinary English, and a guard that refuses a commit message ABOUT the guard
# throws away the mention-safety the anchor exists for.
expect_allow "prose after 'then' is not refused" r1 \
  "git commit -m 'docs: explain that pgrep -f x is unreliable'"
expect_allow "a bare mention mid-sentence is not refused" r1 \
  "echo 'the poll used pgrep -f gradle and self-matched'"
expect_allow "the probe without the full-match flag is not the anti-pattern" r1 'pgrep gradle'

printf '\ndual-runtime — Codex CLI sends the same fields, so the same script must serve it\n'

# codex-cli 0.153.4 PreToolUse payload keys, measured from the binary: session_id, turn_id, agent_type,
# transcript_path, hook_event_name, model, permission_mode, tool_name, tool_input, tool_use_id. Only the one
# this script reads needs to match; the extra keys must be ignored rather than trip the parser.
codex_payload() {
  printf '{"session_id":"%s","turn_id":"t1","agent_type":"codex","hook_event_name":"PreToolUse",' "$1"
  printf '"model":"gpt-5","permission_mode":"auto","tool_name":"Bash","tool_use_id":"u1",'
  printf '"tool_input":{"command":"%s"}}' "$2"
}
set +e
codex_payload cx1 'nohup ./build.sh :test &' | bash "$HOOK" >/dev/null 2>&1
rc_cx_detach=$?
codex_payload cx1 'while pgrep -f gradle; do sleep 5; done' | bash "$HOOK" >/dev/null 2>&1
rc_cx_probe=$?
codex_payload cx1 './build.sh :test' | bash "$HOOK" >/dev/null 2>&1
rc_cx_allow=$?
set -e
expect_rc "a Codex payload's detach is blocked" 2 "$rc_cx_detach"
expect_rc "a Codex payload's loop gate is blocked" 2 "$rc_cx_probe"
expect_rc "a Codex payload's ordinary build is allowed" 0 "$rc_cx_allow"

printf '\n'
if [ "$FAIL" -eq 0 ]; then
  printf 'selftest-long-run-guard: all cases pass\n'
else
  printf 'selftest-long-run-guard: FAILURES above\n' >&2
fi
exit "$FAIL"
