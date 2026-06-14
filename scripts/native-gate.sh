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

[[ -x "$NATIVE" ]] || { echo "native binary not found/executable: $NATIVE  (build with :cli:nativeCompile)" >&2; exit 2; }

# Demos that cannot pass on the native binary, with WHY. Keep the reason here so the skip is never silent.
#   cli-runtime.md — model eval / rule test / model compute execute kernel runtime validation via Groovy
#                    (GroovyClassLoader.parseClass), which is not native-compilable (§4 hard ceiling).
#
# CONVENTION — isolate JVM-only verbs in their OWN demo file. showboat verify is per-file (one exit code),
# so a single JVM-only block (a `model eval`/`rule test`/`model compute` call) turns its ENTIRE demo
# JVM-only — you'd lose native coverage of every other block in it. So never sprinkle a runtime-eval call
# into a native-applicable demo (e.g. cli-tour); keep them confined to cli-runtime.md. (A *subset*
# divergence like cli-tour's manifest block once verbs are hidden is different — fix that with a
# native-aware capture, not by marking cli-tour JVM-only.) See docs/NATIVE-IMAGE-GRAALVM.md §8.
declare -A JVM_ONLY=(
  ["cli-runtime.md"]="runtime-eval verbs need Groovy — not in the native binary (§4)"
)

fails=0
for d in examples/cli-*.md; do
  base="$(basename "$d")"
  if [[ -n "${JVM_ONLY[$base]:-}" ]]; then
    printf 'SKIP  %-26s — JVM-only: %s\n' "$base" "${JVM_ONLY[$base]}"
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
