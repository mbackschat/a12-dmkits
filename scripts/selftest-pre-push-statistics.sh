#!/usr/bin/env bash
# Behavioral guard for .githooks/pre-push: statistics must describe the commit(s) actually leaving the clone,
# never an ambient dirty worktree or an older/non-HEAD ref.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK="$ROOT/.githooks/pre-push"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REPO="$WORK/repo"
LOG="$WORK/statistics.log"
ZERO=0000000000000000000000000000000000000000

fail() { echo "SELFTEST FAIL: $*" >&2; exit 1; }

git init -q "$REPO"
git -C "$REPO" config user.name "Statistics Hook Self-Test"
git -C "$REPO" config user.email "statistics-hook@example.invalid"
mkdir -p "$REPO/scripts"
printf 'class Measured {}\n' > "$REPO/Measured.java"
cat > "$REPO/scripts/update-code-statistics.sh" <<'STUB'
#!/bin/sh
printf '%s\n' "$*" >> "$STATS_LOG"
exit "${FAKE_STATS_EXIT:-0}"
STUB
chmod +x "$REPO/scripts/update-code-statistics.sh"
git -C "$REPO" add .
git -C "$REPO" commit -qm "initial"
FIRST="$(git -C "$REPO" rev-parse HEAD)"

invoke() {
  local refs="$1"
  set +e
  INVOKE_OUTPUT="$(cd "$REPO" && printf '%b' "$refs" | STATS_LOG="$LOG" sh "$HOOK" origin example.invalid 2>&1)"
  INVOKE_STATUS=$?
  set -e
}

: > "$LOG"
invoke "refs/heads/main $FIRST refs/heads/main $ZERO\n"
[[ $INVOKE_STATUS -eq 0 ]] || fail "clean HEAD push was refused: $INVOKE_OUTPUT"
[[ "$(wc -l < "$LOG" | tr -d ' ')" -eq 1 ]] || fail "clean HEAD push did not run statistics exactly once"

printf '// uncommitted source\n' >> "$REPO/Measured.java"
: > "$LOG"
invoke "refs/heads/main $FIRST refs/heads/main $ZERO\n"
[[ $INVOKE_STATUS -ne 0 ]] || fail "dirty measured worktree was accepted"
[[ ! -s "$LOG" ]] || fail "statistics ran after the dirty-worktree refusal"
git -C "$REPO" restore Measured.java

printf 'second\n' > "$REPO/second.txt"
git -C "$REPO" add second.txt
git -C "$REPO" commit -qm "second"
SECOND="$(git -C "$REPO" rev-parse HEAD)"
: > "$LOG"
invoke "refs/heads/old $FIRST refs/heads/old $ZERO\n"
[[ $INVOKE_STATUS -ne 0 ]] || fail "a non-HEAD ref was measured from the HEAD worktree"
[[ ! -s "$LOG" ]] || fail "statistics ran after the non-HEAD refusal"

: > "$LOG"
invoke "refs/heads/main $SECOND refs/heads/main $ZERO\n"
[[ $INVOKE_STATUS -eq 0 ]] || fail "current clean HEAD was refused after the non-HEAD control: $INVOKE_OUTPUT"
[[ "$(wc -l < "$LOG" | tr -d ' ')" -eq 1 ]] || fail "current HEAD did not run statistics exactly once"

: > "$LOG"
invoke "refs/heads/deleted $ZERO refs/heads/deleted $FIRST\n"
[[ $INVOKE_STATUS -eq 0 ]] || fail "a deletion-only push was refused: $INVOKE_OUTPUT"
[[ ! -s "$LOG" ]] || fail "a deletion-only push unnecessarily measured the ambient worktree"

echo "SELFTEST OK: pre-push statistics are bound to a clean pushed HEAD"
