#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPORT="$ROOT/interpreter/public-release/PERFORMANCE.md"
SPECIFICATION="$ROOT/docs/INFOGRAPHICS.md"
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

trim() {
  local value="$1"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s' "$value"
}

require_prompt_line() {
  grep -Fqx -- "$1" "$SPECIFICATION" || fail "performance prompt has drifted from its source: $1"
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

comparison_line="$(grep -F -m 1 'the interpreter is about **' "$REPORT")"
generated_ratio="$(sed -nE "s/.*interpreter is about \\*\\*([^ ]+) the A12 Kernel's generated Java\\*\\*.*/\\1/p" <<<"$comparison_line")"
dynamic_ratio="$(sed -nE 's/.*and about \*\*([^ ]+) its dynamic Groovy\*\*.*/\1/p' <<<"$comparison_line")"
[[ -n "$generated_ratio" && -n "$dynamic_ratio" ]] || fail "missing current JVM comparison ratios"

evidence_line="$(grep -F '<sub>Measured ' "$REPORT" | tail -n 1)"
evidence="${evidence_line#<sub>Measured }"
IFS='·' read -r measurement_date machine browser jdk ignored <<<"$evidence"
measurement_date="$(trim "$measurement_date")"
machine="$(trim "$machine")"
browser="$(trim "$browser")"
jdk="$(trim "$jdk")"

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
)

for prompt_line in "${prompt_lines[@]}"; do
  require_prompt_line "$prompt_line"
done

for canonical_asset in \
  a12-dmkits-toolchain.png \
  dmtool-safe-editing.png \
  a12-interpreter-runtime.png \
  interpreter-vs-kernel.png; do
  [[ -f "$ROOT/assets/infographics/$canonical_asset" ]] \
    || fail "missing canonical infographic: assets/infographics/$canonical_asset"
done

check_preview "$ROOT/README.md" 'assets/infographics/a12-dmkits-toolchain.png' 720
check_preview "$ROOT/README.md" 'assets/infographics/dmtool-safe-editing.png' 720
check_preview "$ROOT/README.md" 'assets/infographics/a12-interpreter-runtime.png' 720
check_preview "$ROOT/README.md" 'assets/infographics/interpreter-vs-kernel.png' 720
check_preview "$ROOT/plugin/README.md" 'assets/infographics/dmtool-safe-editing.png' 720
check_preview "$ROOT/plugin/README.md" 'assets/infographics/interpreter-vs-kernel.png' 720
check_preview "$ROOT/interpreter/public-release/README.md" 'assets/infographics/a12-interpreter-runtime.png' 720
check_preview "$ROOT/interpreter/public-release/README.md" 'assets/infographics/interpreter-vs-kernel.png' 720
check_preview "$SPECIFICATION" '../assets/infographics/a12-dmkits-toolchain.png' 540
check_preview "$SPECIFICATION" '../assets/infographics/dmtool-safe-editing.png' 540
check_preview "$SPECIFICATION" '../assets/infographics/a12-interpreter-runtime.png' 540
check_preview "$SPECIFICATION" '../assets/infographics/interpreter-vs-kernel.png' 540

cmp -s "$ROOT/assets/infographics/dmtool-safe-editing.png" \
  "$ROOT/plugin/assets/infographics/dmtool-safe-editing.png" || fail "dmtool infographic release copy has drifted"
cmp -s "$ROOT/assets/infographics/interpreter-vs-kernel.png" \
  "$ROOT/plugin/assets/infographics/interpreter-vs-kernel.png" || fail "dmtool performance infographic release copy has drifted"
cmp -s "$ROOT/assets/infographics/a12-interpreter-runtime.png" \
  "$ROOT/interpreter/public-release/assets/infographics/a12-interpreter-runtime.png" \
  || fail "Interpreter infographic release copy has drifted"
cmp -s "$ROOT/assets/infographics/interpreter-vs-kernel.png" \
  "$ROOT/interpreter/public-release/assets/infographics/interpreter-vs-kernel.png" \
  || fail "Interpreter performance infographic release copy has drifted"

echo "infographic freshness: OK"
