#!/bin/sh
# Stable POSIX entrypoint for the typed Showboat demo verifier.
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"

# The CLI's custom-JS worker launches literal PATH `node`, so a valid A12_NODE override must not hide an obsolete
# worker runtime and turn its lenient degradation into a false demo drift.
if ! node_version="$(A12_NODE=node "$ROOT/scripts/run-typescript.sh" --check 2>&1)"; then
  echo "verify-demos: $node_version" >&2
  echo "the cli-custom-types demo's JS-worker blocks would degrade to 'unsupported' and read as false DRIFT." >&2
  echo "Put Node.js >= 24.12.0 on PATH, then re-run." >&2
  exit 1
fi

# The built launcher runs after build.sh returns, so apply the same shared JDK pin to the verifier process too.
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"
if ! demo_pinned_jdk21_home="$(a12_pinned_jdk21_home)"; then
  exit 1
fi
if [ -n "$demo_pinned_jdk21_home" ]; then
  export JAVA_HOME="$demo_pinned_jdk21_home"
fi

exec "$ROOT/scripts/run-typescript.sh" "demo verifier" "$ROOT/scripts/typescript/verify-demos.ts" "$@"
