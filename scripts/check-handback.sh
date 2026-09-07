#!/bin/sh
#
# check-handback.sh — pre-send checks for an outbound a12-kernel-lean handback letter.
#
# Usage: scripts/check-handback.sh <path-to-letter.md>
#
# The letter has no in-repo home on purpose (a copy makes two heads with no merge base), so this reads it BY
# PATH and stays the only committed part. Exit 0 = clean, 1 = findings, 2 = usage. The logic is TypeScript
# under scripts/typescript/, per the repository's shell/TypeScript split; this is the stable public path.
#
set -eu

if [ $# -lt 1 ]; then
  echo "usage: scripts/check-handback.sh <letter.md>" >&2
  exit 2
fi

letter="$1"
if [ ! -f "$letter" ]; then
  echo "check-handback: no such file: $letter" >&2
  exit 2
fi

repo="$(cd "$(dirname "$0")/.." && pwd)"
exec "$repo/scripts/run-typescript.sh" "handback check" "$repo/scripts/typescript/handback-check.ts" \
  "$letter" "$repo"
