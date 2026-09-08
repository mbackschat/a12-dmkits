#!/bin/sh
# Stable command path; the case registry and report logic live in the typed implementation.
set -eu
SCRIPT_DIR="$(CDPATH='' cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/run-typescript.sh" \
  "bash hook self-test" "$SCRIPT_DIR/typescript/selftest-bash-hook.ts" "$@"
