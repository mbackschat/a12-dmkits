#!/usr/bin/env bash
# Native verification gate (docs/NATIVE-IMAGE-GRAALVM.md §8). Verifies the native binary against the
# showboat demos byte-for-byte — but SKIPS demos that are legitimately JVM-only on the native subset,
# so a known, expected non-applicability is reported as SKIP(reason), never a spurious FAIL.
#
# Two gates, two targets:
#   • JVM gate   — CI, the installDist launcher, ALL demos green (.github/workflows/ci.yml).
#   • Native gate — THIS script, the native binary, the native-applicable subset green.
#
# Exit non-zero only if a NATIVE-APPLICABLE demo fails (a real regression). Skips never fail the gate.
#
# Usage:  ./scripts/native-gate.sh [path-to-native-dmtool]
#         (defaults to cli/build/native/nativeCompile/dmtool)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
NATIVE="${1:-$ROOT/cli/build/native/nativeCompile/dmtool}"

# The demos call the bare `dmtool`; put the repo shim on PATH so it resolves there. The shim honors
# DMTOOL_CLI (resolution #1), set per-demo below to the native binary under test.
export PATH="$ROOT/bin:$PATH"

[[ -x "$NATIVE" ]] || { echo "native binary not found/executable: $NATIVE  (build with :cli:nativeCompile)" >&2; exit 2; }

# Demos NOT byte-verifiable against the native binary, with WHY. Keep the reason here so a skip is never silent.
# Two distinct causes:
#   • JVM-ONLY VERBS  — cli-runtime.md is entirely model eval/rule test/model compute, which execute kernel
#     Groovy codegen (not native-compilable, §4). CONVENTION: isolate JVM-only verbs in their OWN demo —
#     showboat verify is per-file, so one such block turns its whole demo unverifiable on native. Never
#     sprinkle a runtime-eval call into a native-applicable demo.
#   • SUBSET DIVERGENCE — cli-tour.md's `manifest` block enumerates the full verb list, which is legitimately
#     SHORTER on native (the 3 JVM-only verbs are hidden from the tree). The native-aware manifest assertion
#     lives in NativeImageVerbHidingTest (JUnit) instead; native JSON-output fidelity for cli-tour's other
#     (read) verbs is already proven by the 4 passing demos. See docs/NATIVE-IMAGE-GRAALVM.md §8.
declare -A NATIVE_SKIP=(
  ["cli-runtime.md"]="JVM-only verbs — model eval/rule test/model compute need Groovy (§4)"
  ["cli-tour.md"]="manifest block lists the full verb list — a subset on native; asserted by NativeImageVerbHidingTest"
)

fails=0
for d in examples/cli-*.md; do
  base="$(basename "$d")"
  if [[ -n "${NATIVE_SKIP[$base]:-}" ]]; then
    printf 'SKIP  %-26s — %s\n' "$base" "${NATIVE_SKIP[$base]}"
    continue
  fi
  if DMTOOL_CLI="$NATIVE" uvx showboat@0.6.1 verify "$d" >/dev/null 2>&1; then
    printf 'PASS  %s\n' "$base"
  else
    printf 'FAIL  %s\n' "$base"
    fails=$((fails+1))
  fi
done

echo "----"
if [[ $fails -eq 0 ]]; then echo "native gate: GREEN (native-applicable demos pass; JVM-only demos skipped)"; else echo "native gate: $fails native-applicable demo(s) FAILED"; fi
exit $(( fails > 0 ? 1 : 0 ))
