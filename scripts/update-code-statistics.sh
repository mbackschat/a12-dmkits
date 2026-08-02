#!/usr/bin/env bash
# Regenerate the Tokei source-statistics blocks in public READMEs, or verify that they are current.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LEAN_ROOT="${A12_LEAN_REPO:-$ROOT/../a12-kernel-lean}"
MODE="update"
ARTIFACT="all"
ALLOW_MISSING_LEAN=0

usage() {
  cat <<'EOF'
Usage: update-code-statistics.sh [--check] [--artifact all|root|dmtool|interpreter] [--allow-missing-lean]

Without --check, rewrite the selected generated README blocks. --check makes no changes and fails on drift.
The root statistics include the sibling a12-kernel-lean checkout; override it with A12_LEAN_REPO.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) MODE="check"; shift ;;
    --artifact) ARTIFACT="${2:?missing --artifact value}"; shift 2 ;;
    --allow-missing-lean) ALLOW_MISSING_LEAN=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$ARTIFACT" in
  all|root|dmtool|interpreter) ;;
  *) echo "artifact must be all, root, dmtool, or interpreter: $ARTIFACT" >&2; exit 2 ;;
esac

if [[ "$MODE" == "update" ]]; then
  for command_name in tokei jq; do
    command -v "$command_name" >/dev/null 2>&1 || {
      echo "$command_name is required to update source statistics" >&2
      exit 2
    }
  done
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

JAVA_SOURCES=(
  "$ROOT/core/src/main/java"
  "$ROOT/rulekit/src/main/java"
  "$ROOT/modelkit/src/main/java"
  "$ROOT/edit/src/main/java"
  "$ROOT/schemakit/src/main/java"
  "$ROOT/adapter/src/main/java"
  "$ROOT/cli/src/main/java"
)
KOTLIN_SOURCES=(
  "$ROOT/interpreter/src/commonMain/kotlin"
  "$ROOT/interpreter/src/jvmMain/kotlin"
  "$ROOT/interpreter/src/jsMain/kotlin"
)
TYPESCRIPT_LIBRARY_SOURCES=("$ROOT/interpreter/typescript/src")
TYPESCRIPT_SHOWCASE_SOURCES=("$ROOT/interpreter/consumer-tests/browser/src")

fingerprint_sources() {
  local suffix="$1"
  shift
  {
    local source_root
    for source_root in "$@"; do
      find "$source_root" -type f -name "*.${suffix}" -print
    done
  } | LC_ALL=C sort | git hash-object --stdin-paths | git hash-object --stdin
}

measure() {
  local language="$1"
  shift
  if [[ $# -eq 0 ]]; then
    printf '0\t0\t0\t0\n'
    return
  fi
  local output="$WORK/tokei-${language}-${RANDOM}.json"
  tokei --output json --types "$language" "$@" > "$output"
  jq -r --arg language "$language" '
    (.[$language] // {}) as $stats
    | [
        (($stats.reports // []) | length),
        ($stats.code // 0),
        ($stats.comments // 0),
        ($stats.blanks // 0)
      ]
    | @tsv
  ' "$output"
}

existing_lean_fingerprint() {
  sed -nE 's/^<!-- source-statistics:root:inputs .* lean=([0-9a-f]+|none) measurements=.* -->$/\1/p' "$ROOT/README.md"
}

write_block() {
  local id="$1" title_note="$2" java="$3" kotlin="$4" typescript="$5" lean="$6" output="$7"
  local java_fingerprint="$8" kotlin_fingerprint="$9" typescript_fingerprint="${10}" lean_fingerprint="${11}"
  local jf jc jco jb kf kc kco kb tf tc tco tb lf lc lco lb
  IFS=$'\t' read -r jf jc jco jb <<< "$java"
  IFS=$'\t' read -r kf kc kco kb <<< "$kotlin"
  IFS=$'\t' read -r tf tc tco tb <<< "$typescript"
  IFS=$'\t' read -r lf lc lco lb <<< "$lean"
  local measurement_fingerprint
  measurement_fingerprint="$(printf '%s\n%s\n%s\n%s\n' "$java" "$kotlin" "$typescript" "$lean" | git hash-object --stdin)"
  cat > "$output" <<EOF
<!-- source-statistics:${id}:start -->
## Source statistics

Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from ${title_note}. Tests, fixtures, generated output, build trees, and code embedded in Markdown are excluded.

| Language | Files | Code | Comments | Blanks |
|---|---:|---:|---:|---:|
| Java | ${jf} | ${jc} | ${jco} | ${jb} |
| Kotlin | ${kf} | ${kc} | ${kco} | ${kb} |
| TypeScript | ${tf} | ${tc} | ${tco} | ${tb} |
| Lean | ${lf} | ${lc} | ${lco} | ${lb} |

Regenerate with \`./scripts/update-code-statistics.sh\`; pushes and release assembly reject stale values.
<!-- source-statistics:${id}:inputs java=${java_fingerprint} kotlin=${kotlin_fingerprint} typescript=${typescript_fingerprint} lean=${lean_fingerprint} measurements=${measurement_fingerprint} -->
<!-- source-statistics:${id}:end -->
EOF
}

measurement_fingerprint_from_block() {
  local target="$1" id="$2"
  awk -F '|' -v start="<!-- source-statistics:${id}:start -->" -v end="<!-- source-statistics:${id}:end -->" '
    $0 == start { inside = 1; next }
    $0 == end { inside = 0 }
    inside {
      language = $2
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", language)
      if (language == "Java" || language == "Kotlin" || language == "TypeScript" || language == "Lean") {
        for (column = 3; column <= 6; column++) {
          value = $column
          gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
          printf "%s%s", value, (column == 6 ? "\n" : "\t")
        }
      }
    }
  ' "$target" | git hash-object --stdin
}

FAILURES=0
install_or_check_block() {
  local target="$1" id="$2" expected="$3" java_fingerprint="$4" kotlin_fingerprint="$5" typescript_fingerprint="$6" lean_fingerprint="$7"
  local start="<!-- source-statistics:${id}:start -->"
  local end="<!-- source-statistics:${id}:end -->"
  if [[ "$(grep -Fxc "$start" "$target")" -ne 1 || "$(grep -Fxc "$end" "$target")" -ne 1 ]]; then
    echo "$target must contain exactly one generated source-statistics block '$id'" >&2
    FAILURES=1
    return
  fi

  if [[ "$MODE" == "check" ]]; then
    local fingerprint_prefix fingerprint_line recorded_measurements actual_measurements
    fingerprint_prefix="<!-- source-statistics:${id}:inputs java=${java_fingerprint} kotlin=${kotlin_fingerprint} typescript=${typescript_fingerprint} lean=${lean_fingerprint} measurements="
    fingerprint_line="$(grep -F "<!-- source-statistics:${id}:inputs " "$target" || true)"
    case "$fingerprint_line" in
      "$fingerprint_prefix"*" -->") ;;
      *)
        echo "stale source statistics inputs: ${target#"$ROOT/"}" >&2
        FAILURES=1
        return
        ;;
    esac
    recorded_measurements="${fingerprint_line#"$fingerprint_prefix"}"
    recorded_measurements="${recorded_measurements% -->}"
    actual_measurements="$(measurement_fingerprint_from_block "$target" "$id")"
    if [[ ! "$recorded_measurements" =~ ^[0-9a-f]{40}$ || "$recorded_measurements" != "$actual_measurements" ]]; then
      echo "edited or malformed source statistics table: ${target#"$ROOT/"}" >&2
      FAILURES=1
    fi
    return
  fi

  local current="$WORK/current-${id}.md"
  awk -v start="$start" -v end="$end" '
    $0 == start { copying = 1 }
    copying { print }
    $0 == end { exit }
  ' "$target" > "$current"

  if cmp -s "$expected" "$current"; then
    return
  fi
  local rewritten="$WORK/rewritten-${id}.md"
  awk -v start="$start" -v end="$end" -v block="$expected" '
    BEGIN {
      while ((getline line < block) > 0) replacement[++replacement_count] = line
      close(block)
    }
    $0 == start {
      for (line_number = 1; line_number <= replacement_count; line_number++) print replacement[line_number]
      replacing = 1
      next
    }
    replacing && $0 == end { replacing = 0; next }
    !replacing { print }
  ' "$target" > "$rewritten"
  cp "$rewritten" "$target"
  echo "updated ${target#"$ROOT/"}"
}

ZERO=$'0\t0\t0\t0'
NONE="none"

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "root" ]]; then
  JAVA_FINGERPRINT="$(fingerprint_sources java "${JAVA_SOURCES[@]}")"
  KOTLIN_FINGERPRINT="$(fingerprint_sources kt "${KOTLIN_SOURCES[@]}")"
  TYPESCRIPT_FINGERPRINT="$(fingerprint_sources ts "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}")"
  if [[ -d "$LEAN_ROOT/A12Kernel" && -f "$LEAN_ROOT/A12Kernel.lean" ]]; then
    LEAN_FINGERPRINT="$(fingerprint_sources lean "$LEAN_ROOT/A12Kernel" "$LEAN_ROOT/A12Kernel.lean")"
  elif [[ "$ALLOW_MISSING_LEAN" -eq 1 && "$MODE" == "check" ]]; then
    LEAN_FINGERPRINT="$(existing_lean_fingerprint)"
    [[ -n "$LEAN_FINGERPRINT" ]] || { echo "cannot preserve the Lean source fingerprint from README.md" >&2; exit 2; }
    echo "warning: Lean checkout not found at $LEAN_ROOT; preserving its committed measurement" >&2
  else
    echo "Lean checkout not found at $LEAN_ROOT (set A12_LEAN_REPO or use --allow-missing-lean with --check)" >&2
    exit 2
  fi
  if [[ "$MODE" == "update" ]]; then
    JAVA="$(measure Java "${JAVA_SOURCES[@]}")"
    KOTLIN="$(measure Kotlin "${KOTLIN_SOURCES[@]}")"
    TYPESCRIPT="$(measure TypeScript "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}")"
    LEAN="$(measure Lean "$LEAN_ROOT/A12Kernel" "$LEAN_ROOT/A12Kernel.lean")"
    write_block root "the production sources in this repository and the sibling A12 Lean formalization" "$JAVA" "$KOTLIN" "$TYPESCRIPT" "$LEAN" "$WORK/root.md" "$JAVA_FINGERPRINT" "$KOTLIN_FINGERPRINT" "$TYPESCRIPT_FINGERPRINT" "$LEAN_FINGERPRINT"
  fi
  install_or_check_block "$ROOT/README.md" root "$WORK/root.md" "$JAVA_FINGERPRINT" "$KOTLIN_FINGERPRINT" "$TYPESCRIPT_FINGERPRINT" "$LEAN_FINGERPRINT"
fi

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "dmtool" ]]; then
  JAVA_FINGERPRINT="$(fingerprint_sources java "${JAVA_SOURCES[@]}")"
  KOTLIN_FINGERPRINT="$(fingerprint_sources kt "${KOTLIN_SOURCES[@]}")"
  if [[ "$MODE" == "update" ]]; then
    JAVA="$(measure Java "${JAVA_SOURCES[@]}")"
    KOTLIN="$(measure Kotlin "${KOTLIN_SOURCES[@]}")"
    write_block dmtool "the production sources linked into the dmtool artifact" "$JAVA" "$KOTLIN" "$ZERO" "$ZERO" "$WORK/dmtool.md" "$JAVA_FINGERPRINT" "$KOTLIN_FINGERPRINT" "$NONE" "$NONE"
  fi
  install_or_check_block "$ROOT/plugin/README.md" dmtool "$WORK/dmtool.md" "$JAVA_FINGERPRINT" "$KOTLIN_FINGERPRINT" "$NONE" "$NONE"
fi

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "interpreter" ]]; then
  KOTLIN_FINGERPRINT="$(fingerprint_sources kt "${KOTLIN_SOURCES[@]}")"
  TYPESCRIPT_FINGERPRINT="$(fingerprint_sources ts "${TYPESCRIPT_LIBRARY_SOURCES[@]}")"
  if [[ "$MODE" == "update" ]]; then
    KOTLIN="$(measure Kotlin "${KOTLIN_SOURCES[@]}")"
    TYPESCRIPT="$(measure TypeScript "${TYPESCRIPT_LIBRARY_SOURCES[@]}")"
    write_block interpreter-source "the standalone interpreter library and its authored TypeScript facade" "$ZERO" "$KOTLIN" "$TYPESCRIPT" "$ZERO" "$WORK/interpreter-source.md" "$NONE" "$KOTLIN_FINGERPRINT" "$TYPESCRIPT_FINGERPRINT" "$NONE"
  fi
  install_or_check_block "$ROOT/interpreter/README.md" interpreter-source "$WORK/interpreter-source.md" "$NONE" "$KOTLIN_FINGERPRINT" "$TYPESCRIPT_FINGERPRINT" "$NONE"

  TYPESCRIPT_FINGERPRINT="$(fingerprint_sources ts "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}")"
  if [[ "$MODE" == "update" ]]; then
    TYPESCRIPT="$(measure TypeScript "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}")"
    write_block interpreter-release "the standalone interpreter library, TypeScript facade, and browser showcase" "$ZERO" "$KOTLIN" "$TYPESCRIPT" "$ZERO" "$WORK/interpreter-release.md" "$NONE" "$KOTLIN_FINGERPRINT" "$TYPESCRIPT_FINGERPRINT" "$NONE"
  fi
  install_or_check_block "$ROOT/interpreter/public-release/README.md" interpreter-release "$WORK/interpreter-release.md" "$NONE" "$KOTLIN_FINGERPRINT" "$TYPESCRIPT_FINGERPRINT" "$NONE"
fi

if [[ "$FAILURES" -ne 0 ]]; then
  echo "run ./scripts/update-code-statistics.sh and commit the regenerated README blocks" >&2
  exit 1
fi

if [[ "$MODE" == "check" ]]; then
  echo "source statistics are current (${ARTIFACT})"
fi
