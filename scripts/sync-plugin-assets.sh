#!/bin/sh
# Stable POSIX entrypoint for the typed plugin-asset synchronizer.
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/run-typescript.sh" "plugin asset sync" "$ROOT/scripts/typescript/sync-plugin-assets.ts" "$@"
