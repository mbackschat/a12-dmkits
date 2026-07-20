#!/usr/bin/env bash
#
# Rebuild and compare all IG79 delivery byte bases with the previous release using one collector definition.
# Numerical signals remain informative: this script promotes no threshold and changes no CI schedule.
#
# Usage: scripts/interpreter-delivery-footprint.sh <previous-release-ref> <runner-id>
set -euo pipefail

cd "$(dirname "$0")/.."

if [ "$#" -ne 2 ] || [ -z "$1" ] || [ -z "$2" ]; then
    echo "usage: $0 <previous-release-ref> <runner-id>" >&2
    exit 2
fi

previous_ref="$1"
runner="$2"
if ! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]; then
    echo "interpreter delivery-footprint measurements require a clean committed tree" >&2
    exit 1
fi

current_commit="$(git rev-parse HEAD)"
previous_commit="$(git rev-parse "${previous_ref}^{commit}")" || {
    echo "unknown previous release ref: $previous_ref" >&2
    exit 2
}
if [ "$previous_commit" = "$current_commit" ]; then
    echo "previous release ref resolves to the current commit" >&2
    exit 2
fi

root="$PWD"
collector="$root/scripts/interpreter-delivery-footprint.init.gradle"
collector_sha="$(shasum -a 256 "$collector" | awk '{print $1}')"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-"$(dirname "$root")/.gradle-isolated"}"
report_dir="$root/build/reports/interpreter-delivery-footprint"
previous_report="$report_dir/previous-delivery-footprint.json"
current_report="$report_dir/delivery-footprint.json"
smoke_model="$root/interpreter/src/commonTest/resources/performance/many-rules-48.dm.json"
smoke_instance="$root/interpreter/src/commonTest/resources/performance/many-rules-48.instance.json"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/a12-delivery-footprint.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT

mkdir -p "$report_dir"
git archive "$previous_commit" | tar -x -C "$temporary"

echo "rebuilding previous release $previous_ref@$previous_commit"
(
    cd "$temporary"
    "$temporary/build.sh" --console=plain --rerun-tasks \
        -I "$collector" interpreterDeliveryFootprintSnapshot \
        "-PdeliveryCommit=$previous_commit" \
        "-PdeliveryLabel=$previous_ref" \
        "-PdeliveryRunner=$runner" \
        "-PdeliveryCollectorSha256=$collector_sha" \
        "-PdeliverySmokeModel=$smoke_model" \
        "-PdeliverySmokeInstance=$smoke_instance" \
        "-PdeliveryPreviousBaseline=true" \
        "-PdeliveryOutput=$previous_report"
)

echo "rebuilding current commit $current_commit"
"$root/build.sh" --console=plain --rerun-tasks \
    -I "$collector" interpreterDeliveryFootprintSnapshot \
    "-PdeliveryCommit=$current_commit" \
    "-PdeliveryLabel=current" \
    "-PdeliveryRunner=$runner" \
    "-PdeliveryCollectorSha256=$collector_sha" \
    "-PdeliverySmokeModel=$smoke_model" \
    "-PdeliverySmokeInstance=$smoke_instance" \
    "-PdeliveryPreviousReport=$previous_report" \
    "-PdeliveryOutput=$current_report"

echo "structured report: $current_report"
