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
# Showboat demos (carry a ```output block). It builds the JVM launcher (installDist — always reflects
# current source; native could be stale), then verifies the demos IN PARALLEL (bounded, VERIFY_JOBS).
#
# CI runs THIS script (one step, all demos, parallel) rather than a hand-enumerated list of `uvx`
# calls — so the demo set can't drift out of sync with a separate CI list.
#
#   ./scripts/verify-demos.sh                 # build + verify every discovered demo (parallel); non-zero if any drifted
#   VERIFY_JOBS=4 ./scripts/verify-demos.sh   # cap concurrent verifies (default 6)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# PREFLIGHT the demos' environment prerequisites — loud, never a hidden shim, BEFORE the build. A
# missing prerequisite must NOT read as a demo DRIFT: the cli-custom-types JS-worker blocks
# (`--custom-field-types-js`) degrade LENIENTLY without `node` (the construct reports `unsupported`,
# fired stays empty), so the verifier would diff a well-formed-but-different output and report a false
# "DRIFT" — the mechanism is then invisible in the failure (it cost a stash-bisect to find, 2026-07-18;
# node was nvm-only and off the non-interactive PATH). Fail fast and name the fix instead.
if ! command -v node >/dev/null 2>&1; then
  echo "verify-demos: 'node' is not on PATH — the cli-custom-types demo's JS-worker blocks would" >&2
  echo "degrade to 'unsupported' and read as a false DRIFT. Put a Node.js on PATH first" >&2
  echo "(e.g. 'export PATH=\"\$HOME/.nvm/versions/node/<version>/bin:\$PATH\"'), then re-run." >&2
  exit 1
fi

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

# Verify in PARALLEL, bounded — a serial loop over ~11 demos (each spawning `uvx showboat`, which in
# turn runs many `dmtool` JVMs) dominated the CI wall-clock. Each verify writes to its own log +
# status file (interleaved stdout would be garbled); we print an ordered summary once all finish.
# Batched throttle (launch a batch of CAP, wait for it, launch the next) keeps this portable to bash
# 3.2 — `wait -n` is bash 4.3+, absent on stock macOS.
CAP="${VERIFY_JOBS:-6}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

verify_one() {
  local f="$1" base
  base="$(basename "$f" .md)"
  if uvx showboat@0.6.1 verify "$f" >"$tmp/$base.log" 2>&1; then
    echo 0 >"$tmp/$base.status"
  else
    echo 1 >"$tmp/$base.status"
  fi
}

echo "==> verifying ${#demos[@]} demos in parallel (cap $CAP)"
running=0
for f in "${demos[@]}"; do
  verify_one "$f" &
  if (( ++running >= CAP )); then wait; running=0; fi
done
wait

failed=0
for f in "${demos[@]}"; do
  base="$(basename "$f" .md)"
  if [[ "$(cat "$tmp/$base.status" 2>/dev/null)" == 0 ]]; then
    echo "==> verify $base ... OK"
  else
    echo "==> verify $base ... DRIFT"
    cat "$tmp/$base.log" 2>/dev/null || true
    failed=1
  fi
done

if [[ "$failed" == 1 ]]; then
  echo "" >&2
  echo "A demo drifted from the live CLI. Re-run the demo's command, paste the new output into its" >&2
  echo "examples/*.md block, and re-run this script until green — BEFORE committing (CLI-TOUR-SPEC)." >&2
  exit 1
fi
echo "==> all ${#demos[@]} discovered demos match the live CLI"
