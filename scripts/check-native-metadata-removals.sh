#!/usr/bin/env bash
# Refuse silent loss from a GraalVM tracing-agent recapture. Top-level reflection/resource entries are
# canonicalized as JSON sets, so formatting/order changes are ignored and a partial entry change (for example,
# one reflected method disappearing) is still reported as removal of the previous contract.
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

canonical_entries() {
  jq -e -S -c '(.reflection[] | {section:"reflection",entry:.}), (.resources[] | {section:"resources",entry:.})' "$1" | sort
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
