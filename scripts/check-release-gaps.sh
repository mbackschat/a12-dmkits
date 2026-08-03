#!/usr/bin/env bash
# Refuse publication while any live `docs/*-GAPS.md` ledger carries work in its Open section.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOCS="${1:-$ROOT/docs}"

fail() {
  echo "release-gap check failed: $*" >&2
  exit 1
}

[[ -d "$DOCS" ]] || fail "docs directory does not exist: $DOCS"

shopt -s nullglob
ledgers=("$DOCS"/*-GAPS.md)
shopt -u nullglob

[[ "${#ledgers[@]}" -gt 0 ]] || fail "no *-GAPS.md ledgers found under $DOCS — the denominator is empty"

blocked=()
for ledger in "${ledgers[@]}"; do
  if ! awk '
    function is_open_heading(line, text) {
      text = line
      sub(/^##[[:space:]]+/, "", text)
      sub(/^[0-9]+\.[[:space:]]*/, "", text)
      return text == "Open" || text ~ /^Open[[:space:]]/ || text ~ /^Open\(/
    }

    /^##[[:space:]]+/ {
      if (in_open) {
        in_open = 0
      }
      if (!saw_open && is_open_heading($0)) {
        saw_open = 1
        in_open = 1
      }
      next
    }

    in_open {
      line = $0
      sub(/\r$/, "", line)
      if (line == "_None open._") {
        markers++
      } else if (line !~ /^[[:space:]]*$/ && line !~ /^[[:space:]]*---[[:space:]]*$/) {
        content++
      }
    }

    END {
      exit !(saw_open && markers == 1 && content == 0)
    }
  ' "$ledger"; then
    blocked+=("$(basename "$ledger")")
  fi
done

if [[ "${#blocked[@]}" -gt 0 ]]; then
  printf 'release-gap check failed: publication requires every live Open section to contain only `_None open._`; blocked by:\n' >&2
  printf '  %s\n' "${blocked[@]}" >&2
  exit 1
fi

echo "release-gap check: OK (${#ledgers[@]} derived ledgers, all open sections drained)"
