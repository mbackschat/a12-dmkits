#!/usr/bin/env bash
# Prove that the expensive Kotlin/JVM, Kotlin/JS, and adapter test compilation outputs are relocatable across
# two clean worktrees of the same commit. This verifies the optimization structurally; wall-clock timings are
# printed for diagnosis but are deliberately not a machine-dependent gate.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REVISION="$(git -C "$ROOT" rev-parse HEAD)"

if [[ -n "$(git -C "$ROOT" status --porcelain --untracked-files=all)" ]]; then
  echo "verify-build-cache: the source worktree must be clean so both probes use the exact same commit" >&2
  exit 2
fi

TMP_ROOT="$(mktemp -d "${TMPDIR:-/private/tmp}/a12-rulekit-build-cache.XXXXXX")"
PRIME_WORKTREE="$TMP_ROOT/prime"
HIT_WORKTREE="$TMP_ROOT/hit"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(cd "$ROOT/.." && pwd)/.gradle-isolated}"

cleanup() {
  git -C "$ROOT" worktree remove --force "$PRIME_WORKTREE" >/dev/null 2>&1 || true
  git -C "$ROOT" worktree remove --force "$HIT_WORKTREE" >/dev/null 2>&1 || true
  rmdir "$TMP_ROOT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

TASKS=(
  :interpreter:compileTestKotlinJvm
  :interpreter:compileTestDevelopmentExecutableKotlinJs
  :adapter:compileTestKotlin
)
EXPECTED_CACHE_HITS=(
  :interpreter:compileKotlinJvm
  :interpreter:compileTestKotlinJvm
  :interpreter:compileKotlinJs
  :interpreter:compileTestKotlinJs
  :interpreter:compileTestDevelopmentExecutableKotlinJs
  :adapter:compileTestKotlin
)

git -C "$ROOT" worktree add --quiet --detach "$PRIME_WORKTREE" "$REVISION"
git -C "$ROOT" worktree add --quiet --detach "$HIT_WORKTREE" "$REVISION"

run_probe() {
  local label="$1"
  local worktree="$2"
  local log="$worktree/build-cache-$label.log"
  local started
  started="$(date +%s)"
  (
    cd "$worktree"
    ./build.sh --console=plain "${TASKS[@]}"
  ) 2>&1 | tee "$log"
  echo "verify-build-cache: $label completed in $(( $(date +%s) - started ))s"
}

echo "verify-build-cache: priming $REVISION in a clean worktree"
run_probe prime "$PRIME_WORKTREE"

echo "verify-build-cache: requiring cross-worktree cache restoration for $REVISION"
run_probe hit "$HIT_WORKTREE"

HIT_LOG="$HIT_WORKTREE/build-cache-hit.log"
missing=()
for task in "${EXPECTED_CACHE_HITS[@]}"; do
  if ! grep -Fq "> Task $task FROM-CACHE" "$HIT_LOG"; then
    missing+=("$task")
  fi
done

if (( ${#missing[@]} > 0 )); then
  printf 'verify-build-cache: expected FROM-CACHE but did not observe it for:\n' >&2
  printf '  %s\n' "${missing[@]}" >&2
  exit 1
fi

echo "verify-build-cache: PASS — expensive compilation restored from the shared cache in a second clean worktree"
