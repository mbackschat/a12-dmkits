#!/bin/sh
# Stable command path for the typed evidence reporter. Shell owns bootstrap only; parsing and state live in TS.
set -eu
SCRIPT_DIR="$(CDPATH='' cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/run-typescript.sh" \
  "test-evidence" "$SCRIPT_DIR/typescript/test-evidence.ts" "$@"
