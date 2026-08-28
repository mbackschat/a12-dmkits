#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "$TEST_ROOT"' EXIT
FIXTURE="$TEST_ROOT/repo"
HOOK="$FIXTURE/.githooks/pre-push"
PREFLIGHT="$FIXTURE/scripts/check-infographic-freshness.sh"
ACTIONLINT_STUB="$TEST_ROOT/actionlint"
ACTIONLINT_LOG="$TEST_ROOT/actionlint.log"
OUTPUT="$TEST_ROOT/output"

mkdir -p "$FIXTURE/.githooks" "$FIXTURE/scripts" "$FIXTURE/.github/workflows"
cp "$ROOT/.githooks/pre-push" "$HOOK"
chmod +x "$HOOK"
printf '%s\n' 'name: synthetic' > "$FIXTURE/.github/workflows/ci.yml"

write_preflight() {
  printf '#!/usr/bin/env bash\necho preflight >> %q\nexit %s\n' "$TEST_ROOT/preflight.log" "$1" > "$PREFLIGHT"
  chmod +x "$PREFLIGHT"
}

write_actionlint() {
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$*" >> %q\nexit %s\n' "$ACTIONLINT_LOG" "$1" > "$ACTIONLINT_STUB"
  chmod +x "$ACTIONLINT_STUB"
}

expect_failure() {
  local label="$1"
  shift
  if "$@" > "$OUTPUT" 2>&1; then
    echo "pre-push self-test: expected failure: $label" >&2
    exit 1
  fi
}

write_preflight 1
write_actionlint 0
expect_failure "a failed infographic preflight" env ACTIONLINT="$ACTIONLINT_STUB" /bin/bash "$HOOK"
[[ ! -e "$ACTIONLINT_LOG" ]] || {
  echo "pre-push self-test: actionlint ran after the preflight failed" >&2
  exit 1
}

write_preflight 0
expect_failure "missing actionlint" env ACTIONLINT="$TEST_ROOT/missing-actionlint" /bin/bash "$HOOK"
grep -Fq 'actionlint is required' "$OUTPUT" || {
  echo "pre-push self-test: the missing-actionlint refusal was not corrective" >&2
  exit 1
}

write_actionlint 1
expect_failure "invalid GitHub Actions workflow" env ACTIONLINT="$ACTIONLINT_STUB" /bin/bash "$HOOK"

write_actionlint 0
env ACTIONLINT="$ACTIONLINT_STUB" /bin/bash "$HOOK" > "$OUTPUT" 2>&1
grep -Fq "$FIXTURE/.github/workflows/ci.yml" "$ACTIONLINT_LOG" || {
  echo "pre-push self-test: actionlint did not receive the workflow" >&2
  exit 1
}
grep -Fq 'pre-push: infographic preflight and GitHub Actions workflows are valid' "$OUTPUT" || {
  echo "pre-push self-test: the successful verdict was missing" >&2
  exit 1
}

echo "pre-push self-test: OK"
