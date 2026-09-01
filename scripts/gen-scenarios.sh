#!/bin/sh
# Stable POSIX entrypoint for the typed public-scenario generator.
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
exec "$ROOT/scripts/run-typescript.sh" "scenario generator" "$ROOT/scripts/typescript/gen-scenarios.ts" "$@"
