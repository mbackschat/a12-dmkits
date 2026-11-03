#!/usr/bin/env bash
# Native no-crash coverage battery (docs/NATIVE-IMAGE-GRAALVM.md §3.6) — the durable trip-wire for the
# capture-bound native-image gaps that shipped the v0.1.0/v0.1.1 bugs (§3.5).
#
# The bug class is LOUD on native: a by-name reflective access or a lazily-loaded resource the build didn't
# register surfaces as a distinctive failure (MissingReflectionRegistrationError / MissingResourceException /
# STNoSuchPropertyException / "Can't find bundle"). So we DON'T need byte-identity vs the JVM (no
# normalization layer) — we drive a BROAD, derived input matrix through the native binary and assert none of
# those signatures appears. Coverage is derived from enumerable sources (every committed fixture × the
# read/validate verbs + the FieldKind × constraint matrix + invalid conditions per locale + the
# current-date operators (Today/AddYears/YearFromDate/DifferenceInYears — the literal v0.1.0 crash) + the
# self-describing surface (manifest/operators) + a real rule read-back), so it grows as
# the corpus does. A legitimate kernel rejection (valid:false with a real MVK_* code) is NOT a failure here —
# only a native-image signature is.
#
# Usage:  ./scripts/native-battery.sh [path-to-native-dmtool]
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
NATIVE="${1:-$ROOT/cli/build/native/nativeCompile/dmtool}"
[[ -x "$NATIVE" ]] || { echo "native binary not found/executable: $NATIVE (build with :cli:nativeCompile)" >&2; exit 2; }

# Native-image failure signatures (the loud class). A hit = a registration/resource gap, not a model problem.
SIG='MissingReflectionRegistrationError|MissingResourceException|STNoSuchPropertyException|NoSuchMethodException|ClassNotFoundException|NoClassDefFoundError|Can'\''t find bundle|no such property|Exception in thread'

fails=0; checks=0
run(){ # run(label, args...) — invoke native, fail only on a native-image signature
  local label="$1"; shift
  checks=$((checks+1))
  local out; out="$("$NATIVE" "$@" 2>&1)"
  if echo "$out" | grep -qE "$SIG"; then
    fails=$((fails+1))
    echo "  FAIL  $label"
    echo "$out" | grep -E "$SIG" | head -1 | sed 's/^/        /'
  fi
}

echo "== fixture sweep (every committed model × read/validate verbs) =="
FIXTURES=()
while IFS= read -r fixture; do
  FIXTURES+=("$fixture")
done < <(find cli/src/test/resources/models examples/models -name "*.dm.json" 2>/dev/null | sort)
for m in "${FIXTURES[@]}"; do
  short="${m#*/models/}"
  for op in "model check" "model describe" "rule read" "computation read" "where-used"; do
    run "$short : $op" -m "$m" $op
  done
done
echo "  swept ${#FIXTURES[@]} fixtures × 5 verbs"

echo "== FieldKind × constraint matrix (deserialize setters + per-type codegen) =="
WORK="$(mktemp -d)"
base="$WORK/m.json"
"$NATIVE" model new --id batt --locale en_US --root R -o "$base" >/dev/null 2>&1
for k in STRING NUMBER DATE BOOLEAN CONFIRM; do
  cp "$base" "$WORK/k.json"
  run "field add plain $k"   -m "$WORK/k.json" field add --group /R --name "F$k" --kind "$k"
  run "validate after add $k" -m "$WORK/k.json" model check
done
# constraint-bearing variants (the localized-message setters + constraint codegen templates)
printf '{"group":"/R","name":"Pat","kind":"STRING","string":{"pattern":"[0-9]{5}","patternMessage":"five digits"}}' > "$WORK/pat.json"
cp "$base" "$WORK/c1.json"; run "STRING+pattern add"      -m "$WORK/c1.json" field add "$WORK/pat.json"; run "STRING+pattern validate" -m "$WORK/c1.json" model check
printf '{"group":"/R","name":"Amt","kind":"NUMBER","number":{"fractionDigits":2,"minValue":"0"}}' > "$WORK/num.json"
cp "$base" "$WORK/c2.json"; run "NUMBER+constraints add" -m "$WORK/c2.json" field add "$WORK/num.json"; run "NUMBER+constraints validate" -m "$WORK/c2.json" model check

echo "== current-date operators (THE v0.1.0 native crash surface: Today / AddYears / YearFromDate / DifferenceInYears) =="
# v0.1.0 crashed natively on the current-date operators: their codegen reflectively reaches date-runtime
# classes the build didn't register. No fixture rule uses them (DeliveryNotBeforeOrder is field-vs-field), so
# the sweeps above never drove that template — which is exactly how the bug shipped. Each `rule check` here
# forces checkConsistency → generateValidationCode for the named operator on the NATIVE binary; a SIG hit = the
# gap is back. (Conditions verified kernel-valid 2026-06-19; probe report 01b drove the same path end-to-end.)
DM="$WORK/dates.json"
"$NATIVE" model new --id dates --locale en_US --root R -o "$DM" >/dev/null 2>&1
"$NATIVE" -m "$DM" field add --group /R --name D --kind DATE >/dev/null 2>&1
run "date op: [D] > Today"                       -m "$DM" rule check --field /R/D --condition 'FieldFilled(D) And [D] > Today' --code C
run "date op: DifferenceInYears(D, Today) < 18"  -m "$DM" rule check --field /R/D --condition 'FieldFilled(D) And DifferenceInYears(D, Today) < 18' --code C
run "date op: [D] > AddYears(Today, 2)"          -m "$DM" rule check --field /R/D --condition 'FieldFilled(D) And [D] > AddYears(Today, 2)' --code C
run "date op: YearFromDate(D) < 2000"            -m "$DM" rule check --field /R/D --condition 'FieldFilled(D) And YearFromDate(D) < 2000' --code C

echo "== parser-error i18n bundle, per locale (the de_DE/en_US bug) =="
# en_US/de_DE were the original bug pair; de (short, no country), it + it_IT (a third language, both forms)
# confirm the base-LexerTerminals fallback covers ANY locale, not just the two the capture happened to record.
for loc in en_US de_DE de it it_IT; do
  lm="$WORK/$loc.json"
  "$NATIVE" model new --id "b$loc" --locale "$loc" --root R -o "$lm" >/dev/null 2>&1
  "$NATIVE" -m "$lm" field add --group /R --name F --kind STRING >/dev/null 2>&1
  run "invalid condition ($loc) → must format the parser error" -m "$lm" rule check --field /R/F --condition 'Bogus(F)' --code X
done

echo "== error-text PARAMETER tokens (probe 11 native crash: ErrortextParameterMultiLanguageHandler LexerTerminals bundle) =="
# A rule message with an error-text PARAMETER token ($<Field>.value$, $#<Group>$) invokes the kernel's
# error-text PARAMETER parser (RuleErrortextChecker), whose ErrortextParameterMultiLanguageHandler.<clinit>
# loads a LexerTerminals ResourceBundle — a DIFFERENT bundle from the condition parser-error one above. No
# other battery/test path hit it, so a native resource-registration gap crashed ANY token message
# (MissingResourceException "Can't find bundle" → ExceptionInInitializerError → exit 70), invisible to the
# JVM. Drive rule add with a real token message on the native binary; a SIG hit = the bundle gap is back.
TOK="$WORK/tok.json"
"$NATIVE" model new --id tok --locale en_US --root Order -o "$TOK" >/dev/null 2>&1
"$NATIVE" -m "$TOK" group add --parent /Order --name Items --repeatable 99 >/dev/null 2>&1
"$NATIVE" -m "$TOK" field add --group /Order/Items --name Count --kind NUMBER >/dev/null 2>&1
cat > "$WORK/tokrule.json" <<'JSON'
{ "field":"/Order/Items/Count", "group":"/Order",
  "condition":"GroupFilled(Items) And [Count] <= 0", "code":"COUNT_POS",
  "messages":[ {"locale":"en_US","text":"Row $#/Order/Items$: count $/Order/Items/Count.value$ must be > 0."} ] }
JSON
run "error-text token msg: rule add (value + row-index tokens)" -m "$TOK" rule add "$WORK/tokrule.json"

echo "== self-describing surface + rule read-back (catalog + adapter reflection the read/validate sweep misses) =="
# manifest builds the full picocli command tree AND deserializes the operator catalog (rulekit.catalog POJOs);
# operators re-reads it. NOTE: the fixture sweep's 'rule read' above runs with NO <rulePathInModel> — picocli
# rejects it before the read tier, so it never exercised the typed-AST read-back. Drive a real rule here so
# manifest/operators (catalog reflection) and rule read/explain (adapter read-back) are covered on native,
# not only by the Windows CI smoke (RELEASE-SPEC §4).
run "manifest"  manifest
run "operators" operators
RULE_MODEL="examples/models/order-ruled.dm.json"
run "rule read /Order/DeliveryNotBeforeOrder"    -m "$RULE_MODEL" rule read /Order/DeliveryNotBeforeOrder
run "rule explain /Order/DeliveryNotBeforeOrder" -m "$RULE_MODEL" rule explain /Order/DeliveryNotBeforeOrder

echo "== header annotation on the SERIALIZE path (AnnotationImpl.getName/getValue — the v0.6.0 native crash) =="
# A `roles` header annotation serializes via Jackson getters, which fire only during kernel EXPAND/copy — so
# no read/validate over an UN-annotated fixture drove them, and the capture had the annotation SETTERS but not
# the GETTERS. That is exactly how the crash shipped. Drive both report triggers on the native binary:
#   A) extract a subgroup of an annotated model (its own annotation serializes during the validate-the-pair),
#   B) validate/describe/export a parent that INCLUDES an annotated sub-model (expansion serializes the sub).
# ORDER MATTERS: annotate the sub AFTER `include add` attaches it — on a still-buggy binary `include add`
# itself expands+serializes, so annotating first would crash setup and silently skip the guard (false GREEN).
AW="$(mktemp -d)"
"$NATIVE" model new --id catalog --locale en_US --root Catalog -o "$AW/catalog.dm.json" >/dev/null 2>&1
"$NATIVE" -m "$AW/catalog.dm.json" group add --parent /Catalog --name G >/dev/null 2>&1
"$NATIVE" -m "$AW/catalog.dm.json" field add --group /Catalog/G --name F --kind STRING >/dev/null 2>&1
"$NATIVE" model new --id store --locale en_US --root Store -o "$AW/store.dm.json" >/dev/null 2>&1
"$NATIVE" -m "$AW/store.dm.json" -w "$AW" include add --reference catalog --parent /Store --name Catalog >/dev/null 2>&1
"$NATIVE" -m "$AW/catalog.dm.json" config modify --role admin >/dev/null 2>&1     # annotate the sub AFTER attaching
"$NATIVE" -m "$AW/store.dm.json"   config modify --role manager >/dev/null 2>&1   # and the parent
# Fail LOUD if setup didn't take, so a broken setup can't make the guard a silent no-op (testing-strategy rule).
if ! grep -q includeConfig "$AW/store.dm.json" || ! grep -q roles "$AW/catalog.dm.json"; then
  fails=$((fails+1)); checks=$((checks+1))
  echo "  FAIL  annotated-include setup did not take (include or roles annotation missing) — guard would be a no-op"
fi
run "annotated model: group extract"    -m "$AW/catalog.dm.json" -w "$AW" group extract /Catalog/G --reference subg
run "annotated include: model check" -m "$AW/store.dm.json" -w "$AW" model check
run "annotated include: model describe" -m "$AW/store.dm.json" -w "$AW" model describe
run "annotated include: export"         -m "$AW/store.dm.json" -w "$AW" export

echo "----"
if [[ $fails -eq 0 ]]; then
  echo "native battery: GREEN — $checks checks, 0 native-image signatures"
else
  echo "native battery: $fails / $checks checks hit a native-image signature — a registration/resource gap"
  echo "  → add the offending package to scripts/gen-native-reflect-metadata.sh (reflection) or the IncludeResources glob (resource), re-derive, rebuild. See docs/NATIVE-IMAGE-GRAALVM.md §3.4/§3.6."
fi
exit $(( fails > 0 ? 1 : 0 ))
