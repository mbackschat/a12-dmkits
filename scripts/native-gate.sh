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
VERSION="$(sed -nE "s/^version = '([^']+)'/\1/p" "$ROOT/build.gradle")"

# The demos call the bare `dmtool`; put the repo shim on PATH so it resolves there. The shim honors
# DMTOOL_CLI (resolution #1), set per-demo below to the native binary under test.
export PATH="$ROOT/bin:$PATH"

[[ -x "$NATIVE" ]] || { echo "native binary not found/executable: $NATIVE  (build with :cli:nativeCompile)" >&2; exit 2; }
[[ -n "$VERSION" ]] || { echo "native gate: repository version is missing from build.gradle" >&2; exit 2; }

# Never certify a binary older than the source it claims to be. `probe/prep-probe.sh` has enforced this on the
# PROBE path since it was written; the SHIP path — this gate — did not, so a stale binary could pass every
# check here and be published. Same oracle deliberately: an mtime sweep works on a dirty tree, where a
# git-revision comparison is inert (provenance reads CLEAN/DIRTY, and DIRTY says nothing about which edits are
# in the binary). Any source file newer than the artifact means this gate would be certifying a fiction.
stale="$(find "$ROOT"/*/src "$ROOT/src" -type f \( -name '*.java' -o -name '*.kt' -o -name '*.json' \) \
        -newer "$NATIVE" -print -quit 2>/dev/null)"
if [[ -n "$stale" ]]; then
    echo "STALE native binary — refusing to certify it (newer than the build: $stale)" >&2
    echo "  the reflection metadata capture is TEST-SUITE-DRIVEN, so re-capture FIRST, then rebuild:" >&2
    echo "    GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home ./scripts/capture-native-config.sh" >&2
    echo "    GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home ./build.sh :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME" >&2
    echo "    ./scripts/native-gate.sh   # then re-run this gate on the rebuilt binary" >&2
    exit 3
fi

# The staleness the mtime sweep above CANNOT see: edit source, skip the capture, rebuild — the binary is now
# newer than every source file, so the sweep passes while the committed reflection metadata describes older
# code. ~2s, and it fails closed when no stamp exists (§5.1).
"$ROOT/build.sh" --console=plain :cli:verifyNativeCaptureStamp
"$ROOT/scripts/native-no-java-smoke.sh" "$NATIVE" "$VERSION"

# Reuse the complete process-boundary contract and mutation matrix against the artifact itself. This catches
# native-only command discovery/schema/provenance/write-safety drift before the narrower demos and crash battery.
echo "----"
"$ROOT/build.sh" --no-daemon -PdmtoolContractExecutable="$NATIVE" :cli:publicContractTest

# Demos NOT byte-verifiable against the native binary, with WHY. Keep the reason here so a skip is never silent.
#
# As of 2026-06-24 the map is EMPTY — ALL demos are native-applicable. The two former skips are obsolete:
#   • cli-runtime.md — the runtime verbs (`model eval`/`rule eval`/`model compute`) used to run the kernel's
#     Groovy codegen (JVM-only); they now DEFAULT to the native-safe `dm.interpreter`, so they run on the
#     native binary. (`model expand`/`model eval`/`rule eval`/`model compute`/`model seed` are all interpreter-backed; the
#     JVM-only `--kernel` eval engine was retired — Set V — guarded by `NativeImageRuntimeVerbsTest`.) The demo
#     captures `schema <verb>` for orientation, NOT `<verb> --help` — `schema` is byte-identical on both builds
#     while `--help` can differ by picocli synopsis wrapping (CLI-TOUR-SPEC). Passes native.
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
  if out="$(DMTOOL_CLI="$NATIVE" uvx showboat@0.6.1 verify "$d" 2>&1)"; then
    printf 'PASS  %s\n' "$base"
  else
    printf 'FAIL  %s\n' "$base"
    # Surface the byte-divergence — on a CROSS-OS gate a silent FAIL is useless (the binary diverges on the
    # gate's OS, not the author's, so the diff can't be reproduced locally). Print showboat's expected/actual.
    printf '%s\n' "$out" | sed 's/^/    | /'
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
if [[ $total -eq 0 ]]; then echo "native gate: GREEN (public contract clean; demos byte-match; no-crash battery clean; JVM-only demos skipped)"; else echo "native gate: $fails demo(s) FAILED + battery $([ $battery_fail -ne 0 ] && echo FAILED || echo ok)"; fi
exit $(( total > 0 ? 1 : 0 ))
