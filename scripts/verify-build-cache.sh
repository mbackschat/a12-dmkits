#!/usr/bin/env bash
# Prove that the expensive Kotlin/JVM, Kotlin/JS, and adapter test compilation outputs are relocatable across
# two clean worktrees of the same commit. Gradle runs offline, so only the local cache can satisfy the second
# worktree. This verifies the optimization structurally; timings are diagnostic, not a machine-dependent gate.
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
  # The included build sits on every invocation's path, so whether ITS output relocates across worktrees is
  # a property of the whole estate's cache, not of one module. It is cheap to compile and would therefore
  # never earn a place on cost; it earns one because a composite build is the case most likely to carry an
  # absolute path into its cache key, and nothing else here would notice.
  :build-logic:compileKotlin
)
EXPECTED_CACHE_HITS=(
  :interpreter:compileKotlinJvm
  :interpreter:compileTestKotlinJvm
  :interpreter:compileKotlinJs
  :interpreter:compileTestKotlinJs
  :interpreter:compileTestDevelopmentExecutableKotlinJs
  :adapter:compileTestKotlin
  :build-logic:compileKotlin
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
    ./build.sh --offline --console=plain "${TASKS[@]}"
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
