#!/usr/bin/env bash
# Maintainer-only Tokei updater for the four public README source tables.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="update"
ARTIFACT="all"

usage() {
  cat <<'EOF'
Usage: update-code-statistics.sh [--check] [--artifact all|root|dmtool|interpreter]

Update the selected public README tables, or compare them with a fresh Tokei measurement.
This maintainer-only release aid requires local `tokei` and `jq`; normal builds and CI do not.
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
  "$ROOT/consistency/src/commonMain/kotlin" "$ROOT/consistency/src/jsMain/kotlin"
  "$ROOT/predicates/src/commonMain/kotlin"
  "$ROOT/interpreter-bridge/src/main/kotlin" "$ROOT/consistency-gate/src/main/kotlin"
)
TYPESCRIPT_LIBRARY_SOURCES=("$ROOT/interpreter/typescript/src")
TYPESCRIPT_SHOWCASE_SOURCES=("$ROOT/interpreter/consumer-tests/browser/src")

# The verification estate, measured the same way. Kept as explicit lists rather than a glob so a new
# module has to be added deliberately, exactly like the production lists above.
JAVA_TEST_SOURCES=(
  "$ROOT/src/test/java" "$ROOT/schemakit/src/test/java" "$ROOT/adapter/src/test/java"
  "$ROOT/cli/src/test/java" "$ROOT/src/noBackendTest/java" "$ROOT/src/releaseGuardTest/java"
  "$ROOT/adapter/src/kernelClosureConsumer/java" "$ROOT/adapter/src/testFixtures/java"
  "$ROOT/interpreter/src/jvmTest/java" "$ROOT/consistency-gate/src/test/java"
  "$ROOT/interpreter/consumer-tests/kotlin-jvm/src/main/java"
)
KOTLIN_TEST_SOURCES=(
  "$ROOT/adapter/src/test/kotlin" "$ROOT/interpreter/src/commonTest/kotlin"
  "$ROOT/interpreter/src/jvmTest/kotlin" "$ROOT/interpreter/src/jsTest/kotlin"
  "$ROOT/consistency/src/commonTest/kotlin" "$ROOT/predicates/src/commonTest/kotlin"
  "$ROOT/interpreter-bridge/src/test/kotlin" "$ROOT/consistency-gate/src/test/kotlin"
  "$ROOT/interpreter/consumer-tests/kotlin-jvm/src/main/kotlin"
  "$ROOT/interpreter/consumer-tests/kotlin-js/src/jsMain/kotlin"
)
KOTLIN_INTERPRETER_TEST_SOURCES=(
  "$ROOT/interpreter/src/commonTest/kotlin" "$ROOT/interpreter/src/jvmTest/kotlin"
  "$ROOT/interpreter/src/jsTest/kotlin"
  "$ROOT/interpreter/consumer-tests/kotlin-jvm/src/main/kotlin"
  "$ROOT/interpreter/consumer-tests/kotlin-js/src/jsMain/kotlin"
)
TOOLING_JAVA_SOURCES=("$ROOT/scripts/SourceProvenance.java")
TOOLING_KOTLIN_SOURCES=(
  "$ROOT/interpreter/consumer-tests/kotlin-jvm/build.gradle.kts"
  "$ROOT/interpreter/consumer-tests/kotlin-jvm/settings.gradle.kts"
  "$ROOT/interpreter/consumer-tests/kotlin-js/build.gradle.kts"
  "$ROOT/interpreter/consumer-tests/kotlin-js/settings.gradle.kts"
)
TOOLING_JAVASCRIPT_SOURCES=(
  "$ROOT/eval/benchmark.workflow.js" "$ROOT/eval/gap-finder.workflow.js"
  "$ROOT/interpreter/typescript/scripts"
)
PRODUCTION_JAVASCRIPT_SOURCES=(
  "$ROOT/cli/src/main/resources/io/github/mbackschat/a12/dm/cli/interp/custom-js-worker.mjs"
)
TYPESCRIPT_TEST_SOURCES=(
  "$ROOT/interpreter/consumer-tests/typescript/src" "$ROOT/interpreter/consumer-tests/browser/scripts"
)

# Render source roots as repo-relative code spans, so the tables say exactly what was counted.
paths_md() {
  local out="" path
  for path in "$@"; do
    [[ -e "$path" ]] || continue
    out+="\`${path#"$ROOT/"}\`, "
  done
  printf '%s' "${out%, }"
}

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

# One subsection: a heading, its prose, the folders it counted, and the table. Composing the block from
# these avoids a make_block with fifteen positional arguments, which is the long-parameter-list smell.
section_md() {
  local heading="$1" intro="$2" java="$3" kotlin="$4" typescript="$5" javascript="$6" paths="$7"
  local jf jc jco jb kf kc kco kb tf tc tco tb sf sc sco sb
  IFS=$'\t' read -r jf jc jco jb <<< "$java"
  IFS=$'\t' read -r kf kc kco kb <<< "$kotlin"
  IFS=$'\t' read -r tf tc tco tb <<< "$typescript"
  IFS=$'\t' read -r sf sc sco sb <<< "$javascript"
  printf '\n### %s\n\n%s Counted from %s.\n\n' "$heading" "$intro" "$paths"
  # Tokei's own column order, including Lines. Without it a reader sums Code and compares it against
  # tokei's Lines total, which cannot match — the two are different quantities.
  printf '| Language | Files | Lines | Code | Comments | Blanks |\n|---|---:|---:|---:|---:|---:|\n'
  [[ "$jf" == 0 ]] || printf '| Java | %s | %s | %s | %s | %s |\n' "$jf" "$((jc + jco + jb))" "$jc" "$jco" "$jb"
  [[ "$kf" == 0 ]] || printf '| Kotlin | %s | %s | %s | %s | %s |\n' "$kf" "$((kc + kco + kb))" "$kc" "$kco" "$kb"
  [[ "$tf" == 0 ]] || printf '| TypeScript | %s | %s | %s | %s | %s |\n' "$tf" "$((tc + tco + tb))" "$tc" "$tco" "$tb"
  [[ "$sf" == 0 ]] || printf '| JavaScript | %s | %s | %s | %s | %s |\n' "$sf" "$((sc + sco + sb))" "$sc" "$sco" "$sb"
}

make_block() {
  local id="$1" description="$2" output="$3" sections="$4"
  {
    printf '<!-- source-statistics:%s:start -->\n## Source statistics\n\n' "$id"
    printf 'Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from %s. ' "$description"
    printf 'Fixtures, generated output and build trees are excluded from every count.\n' 
    cat "$sections"
    printf '\nMaintainers regenerate these tables with the local statistics updater; both release publishers compare them with fresh counts before any public mutation.\n'
    printf '<!-- source-statistics:%s:end -->\n' "$id"
  } > "$output"
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
JAVA_TESTS="$(measure Java "${JAVA_TEST_SOURCES[@]}")"
KOTLIN_TESTS="$(measure Kotlin "${KOTLIN_TEST_SOURCES[@]}")"
KOTLIN_INTERPRETER_TESTS="$(measure Kotlin "${KOTLIN_INTERPRETER_TEST_SOURCES[@]}")"
TYPESCRIPT_TESTS="$(measure TypeScript "${TYPESCRIPT_TEST_SOURCES[@]}")"
JAVASCRIPT_PRODUCTION="$(measure JavaScript "${PRODUCTION_JAVASCRIPT_SOURCES[@]}")"
TOOLING_JAVA="$(measure Java "${TOOLING_JAVA_SOURCES[@]}")"
TOOLING_KOTLIN="$(measure Kotlin "${TOOLING_KOTLIN_SOURCES[@]}")"
TOOLING_JAVASCRIPT="$(measure JavaScript "${TOOLING_JAVASCRIPT_SOURCES[@]}")"

PRODUCTION_INTRO="The shipped product."
VERIFICATION_INTRO="Tests, laws, kernel differentials, fuzzers, conformance replay, and the external consumer programs compiled against the packed artifacts."
TOOLING_INTRO="Build scripts, build-time generators, and the evaluation harness — neither shipped nor a check on what ships."

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "root" ]]; then
  {
    section_md Production "$PRODUCTION_INTRO" "$JAVA" "$KOTLIN" "$TYPESCRIPT_ALL" "$JAVASCRIPT_PRODUCTION" \
      "$(paths_md "${JAVA_SOURCES[@]}" "${KOTLIN_SOURCES[@]}" "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}" "${PRODUCTION_JAVASCRIPT_SOURCES[@]}")"
    section_md Verification "$VERIFICATION_INTRO" "$JAVA_TESTS" "$KOTLIN_TESTS" "$TYPESCRIPT_TESTS" "$ZERO" \
      "$(paths_md "${JAVA_TEST_SOURCES[@]}" "${KOTLIN_TEST_SOURCES[@]}" "${TYPESCRIPT_TEST_SOURCES[@]}")"
    section_md Tooling "$TOOLING_INTRO" "$TOOLING_JAVA" "$TOOLING_KOTLIN" "$ZERO" "$TOOLING_JAVASCRIPT" \
      "$(paths_md "${TOOLING_JAVA_SOURCES[@]}" "${TOOLING_KOTLIN_SOURCES[@]}" "${TOOLING_JAVASCRIPT_SOURCES[@]}")"
  } > "$WORK/root-sections.md"
  make_block root "this repository" "$WORK/root.md" "$WORK/root-sections.md"
  apply_block "$ROOT/README.md" root "$WORK/root.md"
fi

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "dmtool" ]]; then
  {
    section_md Production "$PRODUCTION_INTRO" "$JAVA" "$KOTLIN" "$ZERO" "$JAVASCRIPT_PRODUCTION" \
      "$(paths_md "${JAVA_SOURCES[@]}" "${KOTLIN_SOURCES[@]}" "${PRODUCTION_JAVASCRIPT_SOURCES[@]}")"
    section_md Verification "$VERIFICATION_INTRO" "$JAVA_TESTS" "$KOTLIN_TESTS" "$ZERO" "$ZERO" \
      "$(paths_md "${JAVA_TEST_SOURCES[@]}" "${KOTLIN_TEST_SOURCES[@]}")"
  } > "$WORK/dmtool-sections.md"
  make_block dmtool "the sources linked into the dmtool artifact" "$WORK/dmtool.md" "$WORK/dmtool-sections.md"
  apply_block "$ROOT/plugin/README.md" dmtool "$WORK/dmtool.md"
fi

if [[ "$ARTIFACT" == "all" || "$ARTIFACT" == "interpreter" ]]; then
  # The npm package README is deliberately minimal and links to the public repository instead of
  # restating anything, so it carries no statistics block; the human-facing mirror README does.
  # The interpreter's own verification estate only: the kernel differentials live in :adapter and are
  # not part of what this repository distributes.
  {
    section_md Production "$PRODUCTION_INTRO" "$ZERO" "$KOTLIN" "$TYPESCRIPT_ALL" "$ZERO" \
      "$(paths_md "${KOTLIN_SOURCES[@]}" "${TYPESCRIPT_LIBRARY_SOURCES[@]}" "${TYPESCRIPT_SHOWCASE_SOURCES[@]}")"
    section_md Verification "$VERIFICATION_INTRO" "$ZERO" "$KOTLIN_INTERPRETER_TESTS" "$TYPESCRIPT_TESTS" "$ZERO" \
      "$(paths_md "${KOTLIN_INTERPRETER_TEST_SOURCES[@]}" "${TYPESCRIPT_TEST_SOURCES[@]}")"
  } > "$WORK/interpreter-sections.md"
  make_block interpreter-release "the standalone interpreter library, TypeScript facade, and browser showcase" "$WORK/interpreter-release.md" "$WORK/interpreter-sections.md"
  apply_block "$ROOT/interpreter/public-release/README.md" interpreter-release "$WORK/interpreter-release.md"
fi

[[ "$FAILURES" -eq 0 ]] || exit 1
[[ "$MODE" == "update" ]] || echo "source statistics are current (${ARTIFACT})"
