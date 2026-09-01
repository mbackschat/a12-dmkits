#!/bin/sh
# Shared bootstrap for repository TypeScript tools. Node 24.12 made direct type stripping stable, so the tools
# need no package install or generated JavaScript copy; this launcher exists to make that runtime boundary loud.
set -eu

MIN_NODE_MAJOR=24
MIN_NODE_MINOR=12
requested_node="${A12_NODE:-node}"
node_command="$(command -v "$requested_node" 2>/dev/null || true)"

if [ -z "$node_command" ]; then
  echo "TypeScript tools: Node.js >= 24.12.0 is required, but '$requested_node' is not executable" >&2
  exit 2
fi

version="$("$node_command" -p 'process.versions.node' 2>/dev/null || true)"
numeric_version="${version%%+*}"
case "$numeric_version" in
  *-*)
    echo "TypeScript tools: Node.js $version is a pre-release; the required >= 24.12.0 boundary starts at the stable release" >&2
    exit 2
    ;;
esac
old_ifs="$IFS"
IFS=. read -r major minor patch extra <<EOF
$numeric_version
EOF
IFS="$old_ifs"
is_digits() {
  case "$1" in
    ''|*[!0-9]*) return 1 ;;
    *) return 0 ;;
  esac
}
if ! is_digits "$major" || ! is_digits "$minor" || ! is_digits "$patch" || [ -n "$extra" ]; then
    echo "TypeScript tools: could not read a semantic Node.js version from '$node_command' (answered '$version')" >&2
    exit 2
fi

if [ "$major" -lt "$MIN_NODE_MAJOR" ] \
    || { [ "$major" -eq "$MIN_NODE_MAJOR" ] && [ "$minor" -lt "$MIN_NODE_MINOR" ]; }; then
  echo "TypeScript tools: Node.js $version is older than the required >= 24.12.0 ($node_command)" >&2
  exit 2
fi

if [ "${1:-}" = "--check" ]; then
  printf '%s\n' "$version"
  exit 0
fi

tool="${1:-TypeScript tool}"
source="${2:-}"
if [ -z "$source" ] || [ ! -f "$source" ]; then
  echo "$tool: TypeScript implementation is missing: ${source:-(no path supplied)}" >&2
  exit 2
fi
shift 2
exec "$node_command" "$source" "$@"
