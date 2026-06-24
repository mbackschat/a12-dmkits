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
#
# As of 2026-06-24 the map is EMPTY — ALL demos are native-applicable. The two former skips are obsolete:
#   • cli-runtime.md — the runtime verbs (`model eval`/`rule test`/`model compute`) used to run the kernel's
#     Groovy codegen (JVM-only); they now DEFAULT to the native-safe `dm.interpreter`, so they run on the
#     native binary. (`--kernel` is the only JVM-only part — refused under the native profile, guarded by
#     `NativeImageRuntimeVerbsTest`; `model seed` is interpreter-backed too.) Verified: cli-runtime passes native.
#   • cli-tour.md — the `manifest` verb list is no longer a native subset: `Cli.JVM_ONLY_VERBS` is empty (no
#     verb is hidden), so the native manifest equals the JVM manifest. Verified: cli-tour passes native.
declare -A NATIVE_SKIP=()

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

# No-crash coverage battery (docs/NATIVE-IMAGE-GRAALVM.md §3.6) — the demos byte-match a happy-path slice;
# the battery drives a BROAD derived matrix (every fixture × read/validate verbs + the FieldKind × constraint
# matrix + invalid conditions per locale) through the native binary and asserts no native-image failure
# signature. This is what catches the capture-bound gaps the demo-only gate missed (the v0.1.0/v0.1.1 bugs, §3.5).
echo "----"
battery_fail=0
"$ROOT/scripts/native-battery.sh" "$NATIVE" || battery_fail=1

echo "----"
total=$(( fails + battery_fail ))
if [[ $total -eq 0 ]]; then echo "native gate: GREEN (demos byte-match; no-crash battery clean; JVM-only demos skipped)"; else echo "native gate: $fails demo(s) FAILED + battery $([ $battery_fail -ne 0 ] && echo FAILED || echo ok)"; fi
exit $(( total > 0 ? 1 : 0 ))
