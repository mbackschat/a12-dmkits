#!/usr/bin/env bash
# PreToolUse(Bash) guard — blocks two mechanical anti-patterns that silently break long-running commands.
# Reads the hook JSON on stdin, inspects .tool_input.command, and exits 2 (block, message fed back to the
# agent) when the command hand-detaches with a job-control `&` or gates liveness on `pgrep -f`.
# Runtime-agnostic, and that is now load-bearing rather than aspirational: BOTH Claude Code
# (`.claude/settings.json`) and Codex CLI (`.codex/hooks.json`) run this same file. Measured against codex-cli
# 0.153.4: its PreToolUse payload carries the identical `tool_input.command`, `tool_name` and `session_id`
# fields, and it blocks on the identical contract — its own diagnostic reads "PreToolUse hook exited with code
# 2 but did not write a blocking reason to stderr". So the script is shared verbatim; only the two config files
# differ. Rationale: CLAUDE.md / AGENTS.md "Detecting a long run's COMPLETION" and "run TARGETED tests".
set -euo pipefail

# stdin is readable ONCE, and check (3) needs the whole payload (it keys state on .session_id), so capture it
# here rather than letting jq consume the stream.
payload="$(cat)"

# Extract the Bash command; degrade to "allow" if jq or the field is absent (never block on a parse failure).
cmd="$(printf '%s' "$payload" | jq -r '.tool_input.command // ""' 2>/dev/null || true)"
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

# (3) A REPEAT of a Gradle lane this session already ran UNFILTERED. Stateful, so the decision lives in
#     scripts/typescript/gradle-lane-guard.ts per the repo's "application logic is TypeScript" rule; this stays
#     the thin shell boundary the hook contract needs. The `case` keeps Node out of the path for every command
#     that is not a Gradle invocation, which is nearly all of them.
#
#     It keys per LANE, never per session: a change can legitimately need several lanes, and a guard that makes
#     the 2nd..6th ask permission would trade saved CPU for lost coverage, since skipping is the cheapest way
#     to satisfy a nagging guard.
#
#     The SENTINEL is what blocks, never the exit code: a missing Node, an absent checkout, a crashed guard or
#     an empty stdout all yield no `BLOCK:` line and the command proceeds. A guard that breaks the tool it
#     guards is worse than no guard, and scripts/lane-guard.sh sits in the tree unwired as the standing
#     reminder of what over-refusing costs.
case "$cmd" in
    *build.sh*|*gradlew*)
        # SELF-LOCATING, not env-driven: this file lives at <repo>/.claude/hooks/, so `../..` is the checkout
        # regardless of the process CWD. Codex sets no CLAUDE_PROJECT_DIR, and relying on it there would have
        # silently degraded check (3) to "always allow" while looking wired.
        root="$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd || echo "${CLAUDE_PROJECT_DIR:-.}")"
        verdict="$(printf '%s' "$payload" \
            | "$root/scripts/run-typescript.sh" "gradle lane guard" \
                "$root/scripts/typescript/gradle-lane-guard.ts" 2>/dev/null || true)"
        case "$verdict" in
            BLOCK:*)
                printf '%s\n' "${verdict#BLOCK:}" >&2
                exit 2
                ;;
        esac
        ;;
esac

exit 0
