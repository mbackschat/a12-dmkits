#!/usr/bin/env bash
# Maintainer-only Tokei updater for the four public README source tables.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEAN_ROOT="${A12_LEAN_REPO:-$ROOT/../a12-kernel-lean}"
MODE="update"
ARTIFACT="all"

usage() {
  cat <<'EOF'
Usage: update-code-statistics.sh [--check] [--artifact all|root|dmtool|interpreter]

Update the selected public README tables, or compare them with a fresh Tokei measurement.
This maintainer-only release aid requires local `tokei` and `jq`; normal builds and CI do not.
The root table also requires the sibling a12-kernel-lean checkout (override with A12_LEAN_REPO).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) MODE="check"; shift ;;
    --artifact) ARTIFACT="${2:?missing --artifact value}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done
case "$ARTIFACT" in
  all|root|dmtool|interpreter) ;;
  *) echo "artifact must be all, root, dmtool, or interpreter: $ARTIFACT" >&2; exit 2 ;;
esac
for command_name in tokei jq; do
  command -v "$command_name" >/dev/null 2>&1 || {
    echo "$command_name is required for the maintainer source-statistics update" >&2
    exit 2
  }
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
ZERO=$'0\t0\t0\t0'
FAILURES=0

JAVA_SOURCES=(
  "$ROOT/core/src/main/java" "$ROOT/rulekit/src/main/java" "$ROOT/modelkit/src/main/java"
  "$ROOT/edit/src/main/java" "$ROOT/schemakit/src/main/java" "$ROOT/adapter/src/main/java"
  "$ROOT/cli/src/main/java"
)
KOTLIN_SOURCES=(
  "$ROOT/interpreter/src/commonMain/kotlin" "$ROOT/interpreter/src/jvmMain/kotlin"
  "$ROOT/interpreter/src/jsMain/kotlin"
)
TYPESCRIPT_LIBRARY_SOURCES=("$ROOT/interpreter/typescript/src")
TYPESCRIPT_SHOWCASE_SOURCES=("$ROOT/interpreter/consumer-tests/browser/src")

measure() {
  local language="$1"
  shift
  [[ $# -gt 0 ]] || { printf '%s\n' "$ZERO"; return; }
  tokei --output json --types "$language" "$@" | jq -r --arg language "$language" '
    (.[$language] // {}) as $s
    | [(($s.reports // []) | length), ($s.code // 0), ($s.comments // 0), ($s.blanks // 0)]
    | @tsv
  '
}

make_block() {
  local id="$1" description="$2" java="$3" kotlin="$4" typescript="$5" lean="$6" output="$7"
  local jf jc jco jb kf kc kco kb tf tc tco tb lf lc lco lb
  IFS=$'\t' read -r jf jc jco jb <<< "$java"
  IFS=$'\t' read -r kf kc kco kb <<< "$kotlin"
  IFS=$'\t' read -r tf tc tco tb <<< "$typescript"
  IFS=$'\t' read -r lf lc lco lb <<< "$lean"
  cat > "$output" <<EOF
<!-- source-statistics:${id}:start -->
## Source statistics

Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from ${description}. Tests, fixtures, generated output, build trees, and code embedded in Markdown are excluded.

| Language | Files | Code | Comments | Blanks |
|---|---:|---:|---:|---:|
| Java | ${jf} | ${jc} | ${jco} | ${jb} |
| Kotlin | ${kf} | ${kc} | ${kco} | ${kb} |
| TypeScript | ${tf} | ${tc} | ${tco} | ${tb} |
| Lean | ${lf} | ${lc} | ${lco} | ${lb} |

Maintainers regenerate this table with the local statistics updater; both release publishers compare it with fresh counts before any public mutation.
<!-- source-statistics:${id}:end -->
EOF
}

apply_block() {
  local target="$1" id="$2" expected="$3"
  local start="<!-- source-statistics:${id}:start -->" end="<!-- source-statistics:${id}:end -->"
  if [[ "$(grep -Fxc "$start" "$target")" -ne 1 || "$(grep -Fxc "$end" "$target")" -ne 1 ]]; then
    echo "$target must contain exactly one source-statistics block '$id'" >&2
    FAILURES=1
    return
  fi
  local current="$WORK/current-${id}.md"
  awk -v start="$start" -v end="$end" '
    $0 == start { copying = 1 }
    copying { print }
    $0 == end { exit }
  ' "$target" > "$current"
  cmp -s "$expected" "$current" && return
  if [[ "$MODE" == "check" ]]; then
    echo "stale source statistics: ${target#"$ROOT/"}" >&2
    diff -u "$current" "$expected" >&2 || true
    FAILURES=1
    return
  fi
  local rewritten="$WORK/rewritten-${id}.md"
  awk -v start="$start" -v end="$end" -v block="$expected" '
    BEGIN {
      while ((getline line < block) > 0) replacement[++count] = line
      close(block)
    }
    $0 == start {
      for (n = 1; n <= count; n++) print replacement[n]
      replacing = 1
      next
    }
    replacing && $0 == end { replacing = 0; next }
    !replacing { print }
  ' "$target" > "$rewritten"
  cp "$rewritten" "$target"
  echo "updated ${target#"$ROOT/"}"
}

JAVA="$ZERO"
KOTLIN="$ZERO"
TYPESCRIPT_LIBRARY="$ZERO"
TYPESCRIPT_ALL="$ZERO"
if [[ "$ARTIFACT" != "interpreter" || "$ARTIFACT" == "all" ]]; then
  JAVA="$(measure Java "${JAVA_SOURCES[@]}")"
fi
KOTLIN="$(measure Kotlin "${KOTLIN_SOURCES[@]}")"
TYPESCRIPT_LIBRARY="$(measure TypeScript "${TYPESCRIPT_LIBRARY_SOURCES[@]}")"
TYPESCRIPT_ALL="$(measure TypeScript "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}")"

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "root" ]]; then
  [[ -d "$LEAN_ROOT/A12Kernel" && -f "$LEAN_ROOT/A12Kernel.lean" ]] || {
    echo "Lean checkout not found at $LEAN_ROOT (set A12_LEAN_REPO)" >&2
    exit 2
  }
  LEAN="$(measure Lean "$LEAN_ROOT/A12Kernel" "$LEAN_ROOT/A12Kernel.lean")"
  make_block root "the production sources in this repository and the sibling A12 Lean formalization" "$JAVA" "$KOTLIN" "$TYPESCRIPT_ALL" "$LEAN" "$WORK/root.md"
  apply_block "$ROOT/README.md" root "$WORK/root.md"
fi

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "dmtool" ]]; then
  make_block dmtool "the production sources linked into the dmtool artifact" "$JAVA" "$KOTLIN" "$ZERO" "$ZERO" "$WORK/dmtool.md"
  apply_block "$ROOT/plugin/README.md" dmtool "$WORK/dmtool.md"
fi

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "interpreter" ]]; then
  make_block interpreter-source "the standalone interpreter library and its authored TypeScript facade" "$ZERO" "$KOTLIN" "$TYPESCRIPT_LIBRARY" "$ZERO" "$WORK/interpreter-source.md"
  apply_block "$ROOT/interpreter/README.md" interpreter-source "$WORK/interpreter-source.md"
  make_block interpreter-release "the standalone interpreter library, TypeScript facade, and browser showcase" "$ZERO" "$KOTLIN" "$TYPESCRIPT_ALL" "$ZERO" "$WORK/interpreter-release.md"
  apply_block "$ROOT/interpreter/public-release/README.md" interpreter-release "$WORK/interpreter-release.md"
fi

[[ "$FAILURES" -eq 0 ]] || exit 1
[[ "$MODE" == "update" ]] || echo "source statistics are current (${ARTIFACT})"
