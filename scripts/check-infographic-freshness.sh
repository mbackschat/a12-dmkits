#!/bin/sh
# Stable hook/CI command path; the document parsing and registry policy live in the typed implementation.
set -eu
SCRIPT_DIR="$(CDPATH='' cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/run-typescript.sh" \
  "infographic freshness" "$SCRIPT_DIR/typescript/check-infographic-freshness.ts" "$@"
