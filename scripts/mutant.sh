#!/bin/sh
# Stable command path for the mutant runner: plant one mutant or a batch, run only its control, report, restore.
# Shell owns bootstrap only; every refusal and verdict lives in typescript/mutant.ts, which prints the usage.
set -eu
SCRIPT_DIR="$(CDPATH='' cd "$(dirname "$0")" && pwd)"
exec "$SCRIPT_DIR/run-typescript.sh" "mutant" "$SCRIPT_DIR/typescript/mutant.ts" "$@"
