#!/bin/sh
# Keep the public release gate path stable; the shared evidence tool validates complete passes.
set -eu
SCRIPT_DIR="$(CDPATH='' cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/run-typescript.sh" "release performance gate" \
  "$SCRIPT_DIR/typescript/test-evidence.ts" --performance-gate "$@"
