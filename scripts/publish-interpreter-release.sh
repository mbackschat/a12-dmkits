#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=interpreter-release-assets.sh
. "$ROOT/scripts/interpreter-release-assets.sh"
# shellcheck source=release-git-identity.sh
. "$ROOT/scripts/release-git-identity.sh"
# shellcheck source=release-mirror-common.sh
. "$ROOT/scripts/release-mirror-common.sh"
# shellcheck source=interpreter-release-publisher-core.sh
. "$ROOT/scripts/interpreter-release-publisher-core.sh"

if [[ "${INTERPRETER_RELEASE_REHEARSAL:-0}" == "1" ]]; then
  backend="${INTERPRETER_RELEASE_HOST_BACKEND:?rehearsal requires INTERPRETER_RELEASE_HOST_BACKEND}"
  # shellcheck source=interpreter-release-rehearsal-host.sh
  . "$backend"
else
  # shellcheck source=interpreter-release-github.sh
  . "$ROOT/scripts/interpreter-release-github.sh"
fi

publish_interpreter_release "$@"
