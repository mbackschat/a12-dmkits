#!/usr/bin/env bash
# Stable hook/CI command path; the document parsing and registry policy live in the typed implementation.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec /bin/bash "$SCRIPT_DIR/run-typescript.sh" \
  "infographic freshness" "$SCRIPT_DIR/typescript/check-infographic-freshness.ts" "$@"
