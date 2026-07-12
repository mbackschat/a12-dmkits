#!/usr/bin/env bash
# PreToolUse(Bash) guard — blocks two mechanical anti-patterns that silently break long-running commands.
# Reads the hook JSON on stdin, inspects .tool_input.command, and exits 2 (block, message fed back to the
# agent) when the command hand-detaches with a job-control `&` or gates liveness on `pgrep -f`.
# Runtime-agnostic: the command string is read from stdin JSON, so any harness that can run a PreToolUse
# command hook (Claude Code settings.json) points at this same script. Rationale: CLAUDE.md / AGENTS.md
# "Detecting a long run's COMPLETION".
set -euo pipefail

# Extract the Bash command; degrade to "allow" if jq or the field is absent (never block on a parse failure).
cmd="$(jq -r '.tool_input.command // ""' 2>/dev/null || true)"
[ -z "$cmd" ] && exit 0

# (1) A job-control `&` — an isolated `&` (space/start before it; whitespace, end-of-line, `;`, or `)` after
#     it), NOT `&&` and NOT a redirect (`2>&1`/`>&`/`&>`, whose `&` is adjacent to `>`). grep tests per line,
#     so a `… &` ending a line (the `… & <next>` double-background) is caught; a quoted `A & B` mid-line is not.
if printf '%s' "$cmd" | grep -qE '(^|[[:space:]])&[[:space:]]*($|;|\))'; then
    printf '%s\n' 'Blocked: a job-control & backgrounds a child OUTSIDE harness tracking — the harness reaps the process group when the tool call returns, so the real work is orphaned or killed while a stray wrapper reports "done". For a long run use run_in_background:true ALONE (no &), or run it in the foreground; never hand-detach with &. See CLAUDE.md / AGENTS.md "Detecting a long run'\''s COMPLETION".' >&2
    exit 2
fi

# (2) `pgrep -f` (or `--full`) — matches its OWN command line, so it is unreliable as a liveness/completion gate.
#     Anchored to a command position (line start, or after `;` `|` `&` `(` `!`) so a mere *mention* of the string
#     in a quoted argument — a commit message, an echo, a doc edit — does not trip it, while `! pgrep -f` (the
#     loop-gate anti-pattern) and a bare `pgrep -f` still do.
if printf '%s' "$cmd" | grep -qE '(^|[;|&(!])[[:space:]]*pgrep[[:space:]]+(-[a-zA-Z]*f|--full)'; then
    printf '%s\n' 'Blocked: pgrep -f matches its OWN command line (self-match), so it is unreliable as a liveness or completion gate. Drop pgrep -f; wait on the command terminal log marker (a wrapper script emits its sub-build BUILD SUCCESSFUL before the real work — key on the final line), or use the harness completion signal.' >&2
    exit 2
fi

exit 0
