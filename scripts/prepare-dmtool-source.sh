#!/usr/bin/env bash
# Resolve a JVM dmtool launcher built from this exact clean source revision. This is the reproducible sibling-
# project evidence route: it never selects a native image, never needs native reflection capture, and invokes
# Gradle only when the installed launcher's own provenance does not match the current commit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LAUNCHER="$ROOT/cli/build/install/dmtool/bin/dmtool"

fail() {
  echo "dmtool source preparation: $*" >&2
  exit 2
}

[[ -d "$ROOT/.git" || -f "$ROOT/.git" ]] \
  || fail "$ROOT is not a Git worktree"

revision="$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" \
  || fail "cannot resolve the source revision"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] \
  || fail "source revision is not a full Git commit: $revision"

source_status="$(git -C "$ROOT" status --porcelain --untracked-files=normal)" \
  || fail "cannot inspect the source worktree"
[[ -z "$source_status" ]] \
  || fail "source worktree is not clean; commit or remove its changes before retaining evidence"

launcher_matches() {
  [[ -x "$LAUNCHER" ]] || return 1
  "$LAUNCHER" --version 2>/dev/null \
    | grep -Fqx "  source: $revision (clean)"
}

if ! launcher_matches; then
  echo "dmtool source preparation: rebuilding the JVM launcher for $revision" >&2
  "$ROOT/build.sh" --console=plain -q :cli:installDist >&2 \
    || fail "the JVM launcher build failed"

  [[ "$(git -C "$ROOT" rev-parse --verify HEAD 2>/dev/null)" == "$revision" ]] \
    || fail "source HEAD changed while the JVM launcher was being built"
  [[ -z "$(git -C "$ROOT" status --porcelain --untracked-files=normal)" ]] \
    || fail "source worktree changed while the JVM launcher was being built"
  launcher_matches \
    || fail "rebuilt launcher does not report source $revision (clean)"
fi

launcher_dir="$(cd "$(dirname "$LAUNCHER")" && pwd)"
printf '%s\n' "$launcher_dir/$(basename "$LAUNCHER")"
