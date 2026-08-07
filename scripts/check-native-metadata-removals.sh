#!/usr/bin/env bash
# Refuse silent loss from a GraalVM tracing-agent recapture — at MEMBER granularity, so a removal means a
# (type, field/method/flag) pair that the baseline had and the recapture does not.
#
# It used to compare whole top-level entries as JSON sets, which caught a disappearing method but ALSO fired
# whenever an entry merely GAINED one: the old entry no longer matched, so a strict superset read as a loss.
# Measured 2026-08-07: a recapture that grew 1308 → 1370 reflection entries was reported as "5 committed
# entries removed", of which four were supersets (`RuleEvalCommand` +code/+condition/+field, `BatchCommand`
# +observationsFile, `Computation` +setErrorCodesToSuppress, `EntityId` +getFilterConditionName) and only one
# was real. A guard that cries wolf on nearly every capture is a guard that gets waived, which is exactly what
# `RK_NATIVE_REMOVAL_REASON` would then have been used for.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <committed-baseline.json> <recaptured.json>" >&2
  exit 2
fi

for tool in jq comm sort; do
  command -v "$tool" >/dev/null 2>&1 || { echo "native metadata removal check: missing $tool" >&2; exit 2; }
done

OLD="$1"
NEW="$2"
[[ -f "$OLD" ]] || { echo "native metadata removal check: baseline not found: $OLD" >&2; exit 2; }
[[ -f "$NEW" ]] || { echo "native metadata removal check: recapture not found: $NEW" >&2; exit 2; }

WORK="$(mktemp -d /tmp/rk-native-metadata-removals.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# One line per REACHABLE THING, not per entry: the type itself, each field, each method (with its parameter
# types, so an overload cannot mask another), and each remaining key with its value (`allPublicMethods` and
# friends — a flag flipping off is a real loss). Resources have no members, so they stay whole entries.
canonical_entries() {
  jq -e -S -c '
    def members:
      (.type | tojson) as $t
      | {section:"reflection", type:$t, member:"<the type itself>"},
        (.fields  // [] | .[] | {section:"reflection", type:$t, member:("field " + .name)}),
        (.methods // [] | .[] | {section:"reflection", type:$t,
                                 member:("method " + .name + "(" + ((.parameterTypes // []) | join(",")) + ")")}),
        (. as $e | keys_unsorted[]
           | select(. != "type" and . != "fields" and . != "methods")
           | {section:"reflection", type:$t, member:(. + "=" + ($e[.] | tojson))});
    (.reflection // [] | .[] | members),
    (.resources  // [] | .[] | {section:"resources", type:tojson, member:"<the resource>"})
  ' "$1" | sort -u
}

canonical_entries "$OLD" > "$WORK/old.entries"
canonical_entries "$NEW" > "$WORK/new.entries"
comm -23 "$WORK/old.entries" "$WORK/new.entries" > "$WORK/removed.entries"

if [[ ! -s "$WORK/removed.entries" ]]; then
  echo "native metadata removal check: 0 committed entries removed"
  exit 0
fi

COUNT="$(wc -l < "$WORK/removed.entries" | tr -d ' ')"
echo "native metadata removal check: $COUNT committed entries removed:" >&2
sed 's/^/  /' "$WORK/removed.entries" >&2

if [[ -z "${RK_NATIVE_REMOVAL_REASON:-}" ]]; then
  echo "refusing silent metadata loss; re-run only after review with RK_NATIVE_REMOVAL_REASON='<why every removal is safe>'" >&2
  exit 1
fi

echo "native metadata removals explicitly acknowledged: $RK_NATIVE_REMOVAL_REASON"
