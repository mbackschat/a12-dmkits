#!/usr/bin/env bash
# Mint a new row in a `-GAPS` ledger: derive the next free id, append a template-conformant
# heading + anchor + stub, print the path and line to edit.
#
# Why this exists: assigning an id by hand needs a word-bounded search over docs, src AND git
# history, because a shipped row is DELETED from its ledger — so the live rows do not show which
# ids are taken. Reading the rows alone hands you an id that is in use. That happened on
# 2026-08-29: `CG16` was minted on top of a live id cited from ~25 sites, because the `IG16`
# substring hides a bare-`CG16` collision from a naive grep and nothing resolves an id back to a
# row. The word-boundary handling below is the whole point of the script.
#
# Usage:  scripts/gap.sh <FAMILY> "<title>"
#         scripts/gap.sh --next <FAMILY>     # print the next free id and exit
# Example: scripts/gap.sh CG "computation read omits the declaring group"
set -euo pipefail

cd "$(dirname "$0")/.."

# family → ledger. One entry per `-GAPS` ledger that mints ids.
ledger_for() {
    case "$1" in
        G)    echo docs/FEATURE-GAPS.md ;;
        DG)   echo docs/DEV-API-GAPS.md ;;
        CG)   echo docs/CLI-CONFORMANCE-GAPS.md ;;
        IG)   echo docs/INTERPRETER-GAPS.md ;;
        IAG)  echo docs/INTERPRETER-API-GAPS.md ;;
        IRG)  echo docs/INTERPRETER-RELEASE-GAPS.md ;;
        MG)   echo docs/MCP-GAPS.md ;;
        MR)   echo docs/MODEL-REVIEW-GAPS.md ;;
        SG)   echo docs/SCHEMAKIT-GAPS.md ;;
        *)    return 1 ;;
    esac
}

families() { echo "G DG CG IG IAG IRG MG MR SG"; }

usage() {
    echo "usage: scripts/gap.sh <FAMILY> \"<title>\"" >&2
    echo "       scripts/gap.sh --next <FAMILY>" >&2
    echo "families: $(families)" >&2
    exit 2
}

# Files where a gap id is meaningful. Lockfiles are excluded because base64 integrity hashes
# contain runs like `…0G76SLRO…` that read as an id to any regex; that inflated the `G` family by
# 14 the first time this ran, so the exclusion is load-bearing, not tidiness.
SCAN_PATHS=(':(exclude)*/build/*' ':(exclude)kotlin-js-store/*' ':(exclude)*.lock' ':(exclude)api/*.api')

# Every id of a family that appears in a blob, one integer per line.
#
# Both boundaries matter and each has already been the bug. LEFT: refuse a preceding ASCII letter,
# or `IG16` reads as a bare `G16` — treating it as one is exactly the collision this script
# prevents. RIGHT: refuse a following letter as well as a digit, or a base64 blob's `G76SLRO`
# reads as `G76`. `git grep -E` is POSIX ERE and has NO `\b`, which is why this is spelled out — a
# `\b` pattern there matches nothing and reports a clean 0, indistinguishable from "no collisions".
ids_in() {
    local family="$1"; shift
    git grep -hoE "(^|[^A-Za-z])${family}[0-9]+([^A-Za-z0-9]|\$)" "$@" 2>/dev/null \
        | grep -oE "${family}[0-9]+" | sed "s/^${family}//"
}

next_id() {
    local family="$1" max=0 n
    for n in $( { ids_in "$family" -- "${SCAN_PATHS[@]}"
                  # Every id the history ever carried, since a shipped row is DELETED from the tree
                  # — the live rows alone under-report, which is the whole reason this script exists.
                  # ONE `git log -p` pass over the ledgers, not a `git grep` per commit: the latter
                  # was correct and took >2 min for nine families, which is the ceremony this script
                  # is meant to remove.
                  git log --all -p --format= -- 'docs/*GAPS*.md' 2>/dev/null \
                  | grep -oE "(^|[^A-Za-z])${family}[0-9]+([^A-Za-z0-9]|\$)" \
                  | grep -oE "${family}[0-9]+" | sed "s/^${family}//"
                } | sort -un ); do
        [ "$n" -gt "$max" ] && max="$n"
    done
    echo $((max + 1))
}

[ $# -ge 1 ] || usage

if [ "$1" = "--next" ]; then
    [ $# -eq 2 ] || usage
    ledger_for "$2" >/dev/null || usage
    echo "$2$(next_id "$2")"
    exit 0
fi

[ $# -eq 2 ] || usage
FAMILY="$1"
TITLE="$2"
LEDGER="$(ledger_for "$FAMILY")" || usage
[ -f "$LEDGER" ] || { echo "no such ledger: $LEDGER" >&2; exit 1; }

N="$(next_id "$FAMILY")"
ID="${FAMILY}${N}"
ANCHOR="$(echo "$ID" | tr '[:upper:]' '[:lower:]')"

# The Open section's heading number differs per ledger (§1, §2, §3 …), so find it rather than
# assume one. GapLedgerTemplateGuardTest pins the `## <N>. Open` shape, so this match is safe.
OPEN_LINE="$(grep -nE '^## [0-9]+\. Open' "$LEDGER" | head -1 | cut -d: -f1)" \
    || { echo "$LEDGER has no '## <N>. Open' section" >&2; exit 1; }
[ -n "$OPEN_LINE" ] || { echo "$LEDGER has no '## <N>. Open' section" >&2; exit 1; }

python3 - "$LEDGER" "$OPEN_LINE" "$ID" "$ANCHOR" "$TITLE" <<'PY'
import sys, pathlib

path, open_line, gap_id, anchor, title = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
lines = pathlib.Path(path).read_text(encoding="utf-8").splitlines(keepends=True)

row = (f"### {gap_id} — {title} <a id=\"{anchor}\"></a>\n"
       "\n"
       "TODO: what is missing or wrong, and what it costs. State the measured basis, not a guess.\n"
       "\n"
       "*Blocks: TODO. Re-open trigger / what would close it: TODO.*\n"
       "\n")

# Insert directly after the `## <N>. Open` heading and its blank line, replacing the drained
# marker if the section carries one — the guard rejects the marker sitting beside a real row.
i = open_line          # 0-based index of the line AFTER the heading
while i < len(lines) and lines[i].strip() == "":
    i += 1
if i < len(lines) and lines[i].strip() == "_None open._":
    del lines[i]
    while i < len(lines) and lines[i].strip() == "":
        del lines[i]

lines.insert(i, row)
pathlib.Path(path).write_text("".join(lines), encoding="utf-8")
print(f"{path}:{i + 1}")
PY

echo
echo "minted $ID in $LEDGER — fill in the TODOs, then:"
echo "  ./build.sh --console=plain :docGuardTest --rerun    # GapLedgerTemplateGuardTest checks the shape"
