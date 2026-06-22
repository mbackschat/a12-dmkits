#!/usr/bin/env bash
# verify-demos.sh — run the examples/ Showboat demos against a FRESH local CLI, exactly as CI does.
#
# Why this exists: the showboat demos are a CI-only drift gate (.github/workflows/ci.yml), so an
# output-shape change committed locally can leave a demo stale with CI red — UNNOTICED until the push.
# It bit us twice: fix B (field read echoes per-kind config) and the read-missing-target structured
# refusal both shifted demo output without a local re-verify. The rule that prevents it:
#
#   ANY change to a verb's output shape / behavior / diagnostics → run `./scripts/verify-demos.sh`
#   BEFORE committing.
#
# The demo set is DISCOVERED, not enumerated (an enumerated list is itself a staleness source — a new
# demo would silently go unverified). It globs examples/*.md and keeps the ones that are actually
# Showboat demos (carry a ```output block). It then cross-checks the CI gate's enumerated list against
# the discovered set and warns if CI is missing a demo (so the OTHER list can't rot silently either).
# It builds the JVM launcher (installDist — always reflects current source; native could be stale).
#
#   ./scripts/verify-demos.sh           # build + verify every discovered demo; non-zero if any drifted
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# JDK 21 + the isolated, public-repos-only Gradle home (same as ./build.sh) so the build can't be
# contaminated by the machine's global init.d.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(cd .. && pwd)/.gradle-isolated}"

echo "==> building the JVM CLI launcher (installDist — always current; native is never used here)"
./gradlew --no-daemon :cli:installDist

# the demos call the bare `dmtool`; the repo shim resolves it to the freshly-built installDist launcher
# (RK_NATIVE is deliberately unset, so a stale native image can't shadow this build)
export PATH="$ROOT/bin:$PATH"
unset RK_NATIVE || true

# DISCOVER the demos: every examples/*.md (except README) that carries a Showboat ```output block.
shopt -s nullglob
demos=()
for f in examples/*.md; do
  [[ "$(basename "$f")" == "README.md" ]] && continue
  grep -q '```output' "$f" || continue   # not a Showboat demo — skip
  demos+=("$f")
done

if [[ "${#demos[@]}" -eq 0 ]]; then
  echo "no Showboat demos found under examples/ — nothing to verify" >&2
  exit 1
fi

failed=0
for f in "${demos[@]}"; do
  base="$(basename "$f" .md)"
  printf '==> verify %s ... ' "$base"
  if uvx showboat@0.6.1 verify "$f" >/tmp/verify-demos-$$.log 2>&1; then
    echo "OK"
  else
    echo "DRIFT"
    cat /tmp/verify-demos-$$.log
    failed=1
  fi
  # cross-check: the CI gate enumerates demos by hand — warn if it's missing this one (CI list staleness)
  if [[ -f .github/workflows/ci.yml ]] && ! grep -q "examples/$base.md" .github/workflows/ci.yml; then
    echo "    WARN: $f is not in the CI showboat gate (.github/workflows/ci.yml) — add it there too" >&2
  fi
done
rm -f "/tmp/verify-demos-$$.log"

if [[ "$failed" == 1 ]]; then
  echo "" >&2
  echo "A demo drifted from the live CLI. Re-run the demo's command, paste the new output into its" >&2
  echo "examples/*.md block, and re-run this script until green — BEFORE committing (CLI-TOUR-SPEC)." >&2
  exit 1
fi
echo "==> all ${#demos[@]} discovered demos match the live CLI"
