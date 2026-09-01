#!/bin/sh
# Check every tracked or newly-created POSIX shell entrypoint with the language and executable oracles.
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
SHELLCHECK="${A12_SHELLCHECK:-$(command -v shellcheck || true)}"
DASH="${A12_DASH:-$(command -v dash || true)}"

if [ -z "$SHELLCHECK" ] || [ ! -x "$SHELLCHECK" ]; then
  echo "check-shell-portability: shellcheck not found; install it before checking POSIX scripts" >&2
  exit 2
fi
if [ -z "$DASH" ] || [ ! -x "$DASH" ]; then
  echo "check-shell-portability: dash not found; install it before checking POSIX scripts" >&2
  exit 2
fi

paths="$(mktemp "${TMPDIR:-/tmp}/a12-posix-shell-paths.XXXXXX")"
trap 'rm -f "$paths"' EXIT HUP INT TERM
git -C "$ROOT" -c core.quotePath=false ls-files --cached --others --exclude-standard > "$paths"

checked=0
while IFS= read -r relative; do
  file="$ROOT/$relative"
  [ -f "$file" ] || continue
  IFS= read -r first_line < "$file" || first_line=''
  [ "$first_line" = '#!/bin/sh' ] || continue
  "$SHELLCHECK" -x -s sh "$file"
  "$DASH" -n "$file"
  checked=$((checked + 1))
done < "$paths"

if [ "$checked" -eq 0 ]; then
  echo "check-shell-portability: no #!/bin/sh entrypoints found; the discovery denominator is broken" >&2
  exit 1
fi

echo "check-shell-portability: $checked POSIX shell entrypoints passed ShellCheck and Dash"
