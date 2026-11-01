#!/bin/sh
# Keep the public release command stable while TypeScript owns counting and rendering.
set -eu
ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/run-typescript.sh" "source statistics" "$ROOT/scripts/typescript/update-code-statistics.ts" "$@"
