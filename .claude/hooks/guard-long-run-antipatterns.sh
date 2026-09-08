#!/usr/bin/env bash
# PreToolUse(Bash) guard — blocks two mechanical anti-patterns that silently break long-running commands.
# Reads the hook JSON on stdin, inspects .tool_input.command, and exits 2 (block, message fed back to the
# agent) when the command hand-detaches with a job-control `&` or gates liveness on `pgrep -f`.
# Runtime-agnostic: the command string is read from stdin JSON, so every harness that can run a PreToolUse
# command hook points at this same file — Claude Code via `.claude/settings.json`, Codex CLI via
# `.codex/hooks.json`. Codex sends the same `tool_input.command` and `tool_name` fields and blocks on the same
# exit-2-plus-stderr contract, so the script is shared verbatim and only the two configs differ; it therefore
# reads no harness-specific environment variable. Rationale: CLAUDE.md / AGENTS.md "Detecting a long run's
# COMPLETION".
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
#
#     The SECOND pattern closes G80: `while pgrep -f x; do … done` is the canonical spelling of the very
#     anti-pattern this check names, and the anchor list above matches none of `while`/`until`, so it slipped
#     through from the check's first day until 2026-09-07. Only those two keywords are added, deliberately:
#     `do`, `then` and `else` would also anchor a command position, but they occur in ordinary prose right
#     before this string ("…and then pgrep -f is unreliable"), and a guard that trips on a commit message
#     explaining the guard is how the mention-safety in the paragraph above gets thrown away.
if printf '%s' "$cmd" | grep -qE '(^|[;|&(!])[[:space:]]*pgrep[[:space:]]+(-[a-zA-Z]*f|--full)' \
   || printf '%s' "$cmd" | grep -qE '(^|[[:space:]])(while|until)[[:space:]]+pgrep[[:space:]]+(-[a-zA-Z]*f|--full)'; then
    printf '%s\n' 'Blocked: pgrep -f matches its OWN command line (self-match), so it is unreliable as a liveness or completion gate. Drop pgrep -f; wait on the command terminal log marker (a wrapper script emits its sub-build BUILD SUCCESSFUL before the real work — key on the final line), or use the harness completion signal.' >&2
    exit 2
fi

# (3) A recursive `rm` of a GRADLE OUTPUT path. Gradle owns that tree, and partial removal desyncs the
#     incremental caches from the class output they describe — the result does not read as a corrupt cache, it
#     reads as inconsistent compile errors in your own code. `./build.sh :<module>:clean` is the whole fix.
#
#     WHY A PATH SEGMENT AND NOT A RESOLVED PATH: resolving needs the command's effective cwd, which a
#     PreToolUse hook cannot know. A `build` SEGMENT is cwd-independent — `build/x`, `./build/x`,
#     `adapter/build/x`, `/abs/adapter/build/x` all carry it. The match is scoped to the rm's OWN arguments, so
#     a commit message or echo that merely mentions such a path does not trip it; and it requires a RECURSIVE
#     flag, so removing a single file (`rm -f build/run.log`) is untouched. Known blind spot, left
#     deliberately: `cd adapter/build && rm -rf kotlin`, whose target carries no segment.
#
#     NO exception for `build/test-results/`. CLAUDE.md names deleting it only as the FALLBACK for reading a
#     run's XMLs; `scripts/test-evidence.sh <log>` is the primary and needs no deletion, so refusing the
#     fallback is what routes to the better tool.
rm_targets="$(printf '%s' "$cmd" \
    | grep -oE '(^|[;|&(!])[[:space:]]*rm[[:space:]]+(-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)([[:space:]]+[^;|&]*)?' \
    || true)"
if [ -n "$rm_targets" ] \
    && printf '%s' "$rm_targets" | grep -qE '(^|[^[:alnum:]_.-])build/|/build([[:space:]]|$)'; then
    printf '%s\n' 'Blocked: a recursive rm of a Gradle output path. Partial hand-deletion of build/ desyncs the incremental caches from the class output they describe, and the result does not look like a corrupted cache — it looks like nonsensical, inconsistent compile errors in your own code. Use `./build.sh :<module>:clean`; Gradle owns that tree, and nothing that must survive lives under build/ (SurvivingDataNotInBuildGuardTest enforces that). If you were clearing build/test-results to read a run'"'"'s XMLs, use `scripts/test-evidence.sh <log>` instead — it counts only the XMLs written during that run, so no deletion is needed.' >&2
    exit 2
fi

exit 0
