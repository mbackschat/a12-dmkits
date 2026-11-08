#!/bin/sh
# Refuse a checkout whose history cannot answer NFR #1, in seconds rather than in half an hour.
#
# `scripts/typescript/measurement-currency.ts` needs the commits that `perf-results/**/*.jsonl` name to be
# resolvable, and a shallow clone has none of them. Detecting that needs only the checkout, so this runs before CI
# provisions anything, including the Node runtime the currency check itself needs.
#
# Environment discovery is what shell is for here: it reads one git property and exits. The invariant itself
# lives in the TypeScript check, which is where the records are parsed; this file deliberately duplicates none of
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
