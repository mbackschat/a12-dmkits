#!/usr/bin/env bash
set -euo pipefail

# This guard compares computed strings against ASCII text committed in the docs, so its arithmetic must format
# the same everywhere. Measured 2026-08-31: under LANG=de_DE.UTF-8, awk's `printf "%.1f"` honours LC_NUMERIC and
# emitted `47,7 kLOC` against the committed `47.7 kLOC`, so this guard REFUSED A CLEAN PUSH on a German-locale
# machine while passing in CI, whose runners are C/UTF-8. A guard whose verdict depends on the developer's
# environment is worse than none.
#
# ONLY the numeric category is forced, and that is deliberate: pinning LC_ALL=C instead traded this bug for its
# mirror, because the evidence line below is split on `·` (U+00B7, two UTF-8 bytes) and a C LC_CTYPE splits it
# bytewise into empty fields. LC_ALL is unset first because it would otherwise override LC_NUMERIC. The split
# itself no longer relies on the character class either — see the evidence line — so neither category can decide
# this guard's verdict. Locked by InfographicFreshnessLocaleTest.
unset LC_ALL
export LC_NUMERIC=C

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="$ROOT/interpreter/public-release/PERFORMANCE.md"
ROOT_README="$ROOT/README.md"
SPECIFICATION="$ROOT/docs/INFOGRAPHICS.md"
SPECIFICATION_DIR="$ROOT/docs"
CI_WORKFLOW="$ROOT/.github/workflows/ci.yml"

fail() {
  echo "infographic freshness: $*" >&2
  exit 1
}

table_cell() {
  local label="$1" column="$2"
  awk -F '|' -v label="$label" -v column="$column" '
    index($0, "| " label " |") {
      value = $column
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      gsub(/\*\*/, "", value)
      print value
      found = 1
      exit
    }
    END { if (!found) exit 1 }
  ' "$REPORT" || fail "missing performance-report table row: $label"
}

production_code_lines() {
  local language="$1"
  awk -F '|' -v language="$language" '
    /<!-- source-statistics:root:start -->/ { in_statistics = 1; next }
    in_statistics && /^### Verification$/ { exit }
    in_statistics {
      label = $2
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", label)
      if (label == language) {
        value = $5
        gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
        print value
        found = 1
        exit
      }
    }
    END { if (!found) exit 1 }
  ' "$ROOT_README" || fail "missing $language production code count in root Source statistics"
}

as_kloc() {
  awk -v code_lines="$1" 'BEGIN { printf "%.1f kLOC", code_lines / 1000 }'
}

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

require_prompt_line() {
  grep -Fqx -- "$1" "$SPECIFICATION" || fail "performance prompt has drifted from its source: $1"
}

contains() {
  local wanted="$1" candidate
  shift
  for candidate in "$@"; do
    [[ "$candidate" == "$wanted" ]] && return 0
  done
  return 1
}

check_preview() {
  local document="$1" relative="$2" width="$3"
  grep -Fq "<a href=\"$relative\">" "$document" \
    || fail "$document does not link to the full infographic $relative"
  grep -F "<img src=\"$relative\"" "$document" | grep -Fq "width=\"$width\"" \
    || fail "$document does not render the expected preview for $relative"
}

line_number() {
  local marker="$1"
  grep -F -n -m 1 -- "$marker" "$CI_WORKFLOW" | cut -d: -f1 \
    || fail "CI workflow does not contain: $marker"
}

grep -Fq '  infographic-preflight:' "$CI_WORKFLOW" \
  && fail "CI uses a separate infographic job, which duplicates runner startup and checkout on every green run"
checkout_line="$(line_number '- name: Checkout rulekit')"
preflight_line="$(line_number '- name: Verify infographic claims, placements, and public copies')"
kernel_checkout_line="$(line_number '- name: Checkout the kernel bootstrap kit')"
java_setup_line="$(line_number '- name: Set up JDK 21 + 25')"
gradle_setup_line="$(line_number '- name: Set up Gradle cache')"
kernel_bootstrap_line="$(line_number '- name: Bootstrap the A12 kernel from public sources')"
(( checkout_line < preflight_line
  && preflight_line < kernel_checkout_line
  && preflight_line < java_setup_line
  && preflight_line < gradle_setup_line
  && preflight_line < kernel_bootstrap_line )) \
  || fail "CI must run the infographic preflight after rulekit checkout and before every paid setup step"

stress_label="$(table_cell '10,000 rows — stress' 2)"
stress_rows="${stress_label% — stress}"
stress_findings="$(table_cell '10,000 rows — stress' 3)"
interpreter_jvm="$(table_cell '**Interpreter — JVM**' 3)"
generated_java_jvm="$(table_cell 'A12 Kernel, generated Java — JVM' 3)"
dynamic_groovy_jvm="$(table_cell 'A12 Kernel, dynamic Groovy — JVM' 3)"
default_inventory="$(table_cell 'Default inventory, 15 cases' 3)"
repeated_documents="$(table_cell 'Repeated-document evaluation' 3)"
huge_document="$(table_cell 'Huge-document edge, 20,000 rows' 3)"
hardest_case="$(table_cell 'Hardest case — 384 concentrated low-firing rules' 3)"
interpreter_memory="$(table_cell '**Interpreter** — prepared model' 3)"
generated_java_memory="$(table_cell 'A12 Kernel, generated Java' 3)"
generated_java_memory_ratio="$(table_cell 'A12 Kernel, generated Java' 4)"
dynamic_groovy_memory="$(table_cell 'A12 Kernel, dynamic Groovy' 3)"
dynamic_groovy_memory_ratio="$(table_cell 'A12 Kernel, dynamic Groovy' 4)"
browser_first="$(table_cell '200 rows — representative' 4)"
browser_first="${browser_first% median}"
browser_warm="$(table_cell '200 rows — representative' 5)"
java_kloc="$(as_kloc "$(production_code_lines Java)")"
kotlin_kloc="$(as_kloc "$(production_code_lines Kotlin)")"

comparison_line="$(grep -F -m 1 'the interpreter is about **' "$REPORT")"
generated_ratio="$(sed -nE "s/.*interpreter is about \\*\\*([^ ]+) the A12 Kernel's generated Java\\*\\*.*/\\1/p" <<<"$comparison_line")"
dynamic_ratio="$(sed -nE 's/.*and about \*\*([^ ]+) its dynamic Groovy\*\*.*/\1/p' <<<"$comparison_line")"
[[ -n "$generated_ratio" && -n "$dynamic_ratio" ]] || fail "missing current JVM comparison ratios"

evidence_line="$(grep -F '<sub>Measured ' "$REPORT" | tail -n 1)"
evidence="${evidence_line#<sub>Measured }"
# Split on the literal separator rather than through IFS, whose members are BYTES: `·` is two UTF-8 bytes, so
# an IFS split yields phantom empty fields wherever LC_CTYPE is not multibyte-aware.
evidence_field() {
  awk -v index_wanted="$1" 'BEGIN { RS = "\0" }
    { n = split($0, parts, / *· */); if (index_wanted <= n) printf "%s", parts[index_wanted] }' <<<"$evidence"
}
measurement_date="$(trim "$(evidence_field 1)")"
machine="$(trim "$(evidence_field 2)")"
browser="$(trim "$(evidence_field 3)")"
jdk="$(trim "$(evidence_field 4)")"

prompt_lines=(
  "- Section title: \"WARM VALIDATION • $stress_rows • $stress_findings findings\""
  "- Bar value: \"$interpreter_jvm\""
  "- Bar value: \"$generated_java_jvm\""
  "- Bar value: \"$dynamic_groovy_jvm\""
  "- Callout: \"≈$generated_ratio faster vs generated Java\""
  "- Callout: \"≈$dynamic_ratio faster vs dynamic Groovy\""
  "- Card value: \"$default_inventory faster\""
  "- Card value: \"$repeated_documents faster\""
  "- Card value: \"$hardest_case faster\""
  "- Card value: \"$huge_document faster\""
  "- Memory value: \"$interpreter_memory\""
  "- Memory value: \"$generated_java_memory\""
  "- Memory callout: \"$generated_java_memory_ratio\""
  "- Memory value: \"$dynamic_groovy_memory\""
  "- Memory callout: \"$dynamic_groovy_memory_ratio\""
  "- Browser value: \"$browser_first first answer\""
  "- Browser value: \"$browser_warm warm\""
  "- Bottom evidence: \"Measured $measurement_date • $machine • $jdk • $browser\""
  "- Java size: \"$java_kloc\""
  "- Kotlin size: \"$kotlin_kloc\""
)

for prompt_line in "${prompt_lines[@]}"; do
  require_prompt_line "$prompt_line"
done

registered_assets=()
registered_placements=()
registry_rows=0
while IFS=$'\t' read -r canonical_relative document_target preview_relative preview_width; do
  [[ "$canonical_relative" == assets/infographics/*.png ]] \
    || fail "registry canonical asset must be under assets/infographics: $canonical_relative"

  canonical="$ROOT/$canonical_relative"
  document_candidate="$SPECIFICATION_DIR/$document_target"
  document_dir="$(cd "$(dirname "$document_candidate")" && pwd -P)" \
    || fail "registry document directory does not exist: $document_target"
  document="$document_dir/$(basename "$document_candidate")"
  linked_candidate="$(dirname "$document")/$preview_relative"
  linked_dir="$(cd "$(dirname "$linked_candidate")" && pwd -P)" \
    || fail "registry preview directory does not exist: $preview_relative"
  linked="$linked_dir/$(basename "$linked_candidate")"

  [[ -f "$canonical" ]] || fail "missing canonical infographic: $canonical_relative"
  [[ -f "$document" ]] || fail "missing infographic preview document: $document_target"
  [[ -f "$linked" ]] || fail "missing linked infographic: $document_target -> $preview_relative"
  check_preview "$document" "$preview_relative" "$preview_width"

  placement_key="$document:$preview_relative"
  contains "$placement_key" "${registered_placements[@]:-}" \
    && fail "duplicate infographic registry placement: $document_target -> $preview_relative"
  registered_placements+=("$placement_key")
  contains "$canonical_relative" "${registered_assets[@]:-}" \
    || registered_assets+=("$canonical_relative")
  ((registry_rows += 1))

  if [[ "$linked" != "$canonical" ]]; then
    cmp -s "$canonical" "$linked" \
      || fail "$linked has drifted from $canonical_relative"
  fi
done < <(
  awk -F '|' '
    /<!-- infographic-registry:start -->/ { in_registry = 1; next }
    /<!-- infographic-registry:end -->/ { in_registry = 0 }
    in_registry && /^\|/ {
      canonical = $3
      document = $4
      preview = $5
      width = $6
      if (canonical !~ /\.png/) next
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", canonical)
      gsub(/`/, "", canonical)
      sub(/^.*\]\(/, "", document)
      sub(/\).*$/, "", document)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", preview)
      gsub(/`/, "", preview)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", width)
      gsub(/`/, "", width)
      print canonical "\t" document "\t" preview "\t" width
    }
  ' "$SPECIFICATION"
)

((registry_rows > 0)) || fail "INFOGRAPHICS.md contains no parseable infographic registry placements"
for canonical in "$ROOT"/assets/infographics/*.png; do
  canonical_relative="assets/infographics/$(basename "$canonical")"
  contains "$canonical_relative" "${registered_assets[@]:-}" \
    || fail "canonical infographic is not registered in INFOGRAPHICS.md: $canonical_relative"
done

echo "infographic freshness: OK"
