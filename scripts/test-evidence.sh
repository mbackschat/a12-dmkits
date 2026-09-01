#!/usr/bin/env bash
# Stable command path for the typed evidence reporter. Shell owns bootstrap only; parsing and state live in TS.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec /bin/bash "$SCRIPT_DIR/run-typescript.sh" \
  "test-evidence" "$SCRIPT_DIR/typescript/test-evidence.ts" "$@"
