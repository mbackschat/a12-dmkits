#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=release-mirror-common.sh
. "$ROOT/scripts/release-mirror-common.sh"

MIRROR="${1:?usage: check-interpreter-release-repo.sh <public-mirror-directory>}"
[[ -d "$MIRROR" ]] || { echo "public mirror directory not found: $MIRROR" >&2; exit 1; }

release_check_interpreter_mirror_allowlist "$MIRROR"
release_scan_public_tree "$MIRROR"
release_verify_public_inventory "$MIRROR"

echo "interpreter public mirror check: OK"
