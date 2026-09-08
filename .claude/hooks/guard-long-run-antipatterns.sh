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

# (3) A recursive `rm` of a GRADLE OUTPUT path. Hand-deleting part of `build/` leaves a state worse than
#     either extreme: on 2026-09-02 removing `adapter/build/kotlin` and then `adapter/build/classes/kotlin`
#     turned one corrupt Kotlin incremental cache into three different, mutually inconsistent compile failures
#     — duplicate declarations, then 48 files unable to resolve a class that had no error of its own — costing
#     ~8 minutes before `:adapter:clean` fixed it in one step. Gradle owns that tree; partial removal desyncs
#     the incremental caches from the class output they describe.
#
#     WHY A PATH SEGMENT AND NOT A RESOLVED PATH: resolving would need the command's effective cwd, which a
#     PreToolUse hook cannot know (the command may `cd` first, or run from anywhere). A `build` SEGMENT is
#     cwd-independent — `build/x`, `./build/x`, `adapter/build/x`, `/abs/adapter/build/x` all carry it — so no
#     cwd is needed. The match is scoped to the rm's OWN arguments, not the whole command, so a commit message
#     or echo that merely mentions such a path does not trip it; and it requires a RECURSIVE flag, so removing
#     a single file (`rm -f build/run.log`) is untouched. Known blind spot, left deliberately:
#     `cd adapter/build && rm -rf kotlin`, whose target carries no segment — closing it would need cwd
#     tracking for a form nobody writes.
#
#     NO exception for `build/test-results/`. CLAUDE.md names deleting it only as the FALLBACK for reading a
#     run's XMLs; `scripts/test-evidence.sh <log>` is the primary, counts only the XMLs written during that
#     run, and needs no deletion. Refusing the fallback is what routes to the better tool.
rm_targets="$(printf '%s' "$cmd" \
    | grep -oE '(^|[;|&(!])[[:space:]]*rm[[:space:]]+(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)([[:space:]]+[^;|&]*)?' \
    || true)"
if [ -n "$rm_targets" ] \
    && printf '%s' "$rm_targets" | grep -qE '(^|[^[:alnum:]_.-])build/|/build([[:space:]]|$)'; then
    printf '%s\n' 'Blocked: a recursive rm of a Gradle output path. Partial hand-deletion of build/ desyncs the incremental caches from the class output they describe, and the result does not look like a corrupted cache — it looks like nonsensical, inconsistent compile errors in your own code. Use `./build.sh :<module>:clean`; Gradle owns that tree, and nothing that must survive lives under build/ (SurvivingDataNotInBuildGuardTest enforces that). If you were clearing build/test-results to read a run'\''s XMLs, use `scripts/test-evidence.sh <log>` instead — it counts only the XMLs written during that run, so no deletion is needed.' >&2
    exit 2
fi

exit 0
