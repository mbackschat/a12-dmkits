#!/bin/sh
# Refuse a checkout whose history cannot answer NFR #1, in seconds rather than in half an hour.
#
# `:adapter:interpreterMeasurementCurrencyGuardTest` needs the commits that `perf-results/**/*.jsonl` name to be
# resolvable. A shallow clone has none of them, so on 2026-09-21 CI ran 31 minutes, reached `:adapter:test`, and
# only then reported a commit as unmeasured that had touched only `settings.gradle` and a doc. The guard now
# reports that state honestly, but it still costs the full lane to reach it — and the condition is one `git`
# call to detect. So this runs first and the billable failure is seconds long.
#
# Environment discovery is what shell is for here: it reads one git property and exits. The invariant itself
# stays in the Kotlin guard, which is where the records are parsed; this file deliberately duplicates none of
# that logic, only the precondition that makes it decidable.
set -eu

cd "$(dirname "$0")/.."

if [ "$(git rev-parse --is-shallow-repository 2>/dev/null || echo unknown)" = "true" ]; then
    echo "check-measurement-history: this checkout is SHALLOW." >&2
    echo >&2
    echo "  NFR #1's currency guard resolves the commits that perf-results/**/*.jsonl name, and a shallow" >&2
    echo "  clone contains none of them. Left to run, the build would spend the whole adapter lane before" >&2
    echo "  reporting that it could not decide." >&2
    echo >&2
    echo "  In GitHub Actions set 'fetch-depth: 0' on actions/checkout." >&2
    echo "  Locally, unshallow with: git fetch --unshallow" >&2
    exit 1
fi

echo "check-measurement-history: history is complete enough to decide measurement currency."
