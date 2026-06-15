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

# Reflection/resource smoke — the paths the demos DON'T cover but that shipped native-only bugs in
# v0.1.0/v0.1.1 (docs/NATIVE-IMAGE-GRAALVM.md §3.5). These exercise the two by-name reflective surfaces
# (§3.4) + the i18n bundle glob, so a regression in the rule-based registration fails here, loudly. This
# is the seed of the broader §3.6 no-crash battery; keep adding shapes as new ones are found.
echo "----"
SMOKE="$(mktemp -d)"
smoke_fail=0
schk(){ if eval "$2"; then printf 'PASS  smoke: %s\n' "$1"; else printf 'FAIL  smoke: %s\n' "$1"; smoke_fail=$((smoke_fail+1)); fi; }
"$NATIVE" model new --id smk --locale en_US --root R -o "$SMOKE/m.json" >/dev/null 2>&1
cat > "$SMOKE/pat.json" <<JSON
{ "group":"/R","name":"P","kind":"STRING","string":{"pattern":"[0-9]{5}","patternMessage":"five digits"} }
JSON
# Jackson setter reflection (StringType.setErrorMessage) + StringTemplate codegen (CodeGenMetaFields) on validate
schk "patterned STRING field add+validate (reflection: deserialize + codegen)" \
  '"$NATIVE" -m "$SMOKE/m.json" field add "$SMOKE/pat.json" >/dev/null 2>&1 && "$NATIVE" -m "$SMOKE/m.json" model validate 2>&1 | grep -q "\"valid\" : true"'
# Parser-error i18n bundle, both locales (the bug was: "Can't find bundle … locale <X>")
"$NATIVE" -m "$SMOKE/m.json" field add --group /R --name F --kind STRING >/dev/null 2>&1
schk "invalid condition en_US (i18n bundle: no 'Can't find bundle')" \
  '! "$NATIVE" -m "$SMOKE/m.json" rule check --field /R/F --condition "Bogus(F)" --code X 2>&1 | grep -q "find bundle"'
"$NATIVE" model new --id smkde --locale de_DE --root R -o "$SMOKE/de.json" >/dev/null 2>&1
"$NATIVE" -m "$SMOKE/de.json" field add --group /R --name F --kind STRING >/dev/null 2>&1
schk "invalid condition de_DE (i18n bundle, all locales)" \
  '! "$NATIVE" -m "$SMOKE/de.json" rule check --field /R/F --condition "FieldNotFilled(F)" --code X 2>&1 | grep -q "find bundle"'
rm -rf "$SMOKE"

echo "----"
total=$(( fails + smoke_fail ))
if [[ $total -eq 0 ]]; then echo "native gate: GREEN (native-applicable demos pass; reflection/resource smoke pass; JVM-only demos skipped)"; else echo "native gate: $fails demo(s) + $smoke_fail smoke check(s) FAILED"; fi
exit $(( total > 0 ? 1 : 0 ))
