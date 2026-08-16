#!/usr/bin/env bash
#
# mutant.sh — plant one mutant, run ONLY its control class, report the verdict, restore.
#
# WHY THIS EXISTS, measured. Mutation testing is the most expensive thing a review does, and nearly all of that
# cost is self-inflicted: the same mutant, same verdict, took **3 m 17 s** run as `-Pfast :adapter:test` and
# **18 s** filtered to the class that should catch it. Ten times, per mutant, five to ten mutants per review.
# Telling reviewers to filter did not stick, so this makes the cheap path the easy one.
#
# It also removes two traps that cost real work in this repo on 2026-08-16:
#
#   1. `git checkout --` to revert a mutant DESTROYS uncommitted work in the same file. It happened three times
#      in one session, each time silently. This script refuses to run on a dirty target and restores from its
#      own byte copy, never from git.
#   2. A mutant that never applied reports SURVIVED, which reads as a finding. Two verdicts were wrong this way
#      because shell escaping ate the pattern. This script fails loudly if the text is absent or unchanged.
#
# USAGE
#   scripts/mutant.sh <file> <old-text> <new-text> <test-filter> [gradle-lane]
#
# The lane defaults to :adapter:test. Name the ONE lane that owns the control — passing several defeats the
# point: the first version of this script ran `:adapter:test :cli:test` and timed out at 2 minutes, which is
# the very cost it exists to remove.
#
#   scripts/mutant.sh adapter/src/test/kotlin/.../PerfHarness.kt \
#       'if (seen.single().growth < ceiling) return seen.single()' \
#       'if (true) return seen.single()' \
#       '*CrossEngineClassRetryTest*'
#
# The filter names the control that SHOULD catch it. If you cannot name one, stop — that is the finding, and
# writing the control first is what makes the mutant cheap.
#
# EXIT: 0 = killed (the control fired). 1 = SURVIVED (report it). 2 = the mutant could not be applied.
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

[ $# -ge 4 ] || { sed -n '3,34p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
FILE=$1; OLD=$2; NEW=$3; FILTER=$4; LANE=${5:-:adapter:test}

[ -f "$FILE" ] || { echo "mutant: no such file: $FILE" >&2; exit 2; }
if ! git diff --quiet -- "$FILE" || ! git diff --cached --quiet -- "$FILE"; then
  echo "mutant: REFUSING — $FILE has uncommitted changes." >&2
  echo "        Commit first. Reverting a mutant over uncommitted work is how three edits were lost in one" >&2
  echo "        session; this script will not create that situation." >&2
  exit 2
fi

BACKUP=$(mktemp); cp "$FILE" "$BACKUP"
restore() { cp "$BACKUP" "$FILE"; /bin/rm -f "$BACKUP"; }
trap restore EXIT

python3 - "$FILE" "$OLD" "$NEW" <<'PY' || exit 2
import sys, pathlib
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
p = pathlib.Path(path); t = p.read_text()
if old not in t:
    sys.exit(f"mutant: the text to replace is NOT in {path} — the mutation would silently not apply, and an\n"
             f"        unapplied mutant reports SURVIVED, which reads as a finding. Text was:\n          {old[:160]}")
if old == new:
    sys.exit("mutant: old and new text are identical — that mutant tests nothing")
p.write_text(t.replace(old, new, 1))
PY

# Prove it landed rather than trusting the write, because that is the failure this guards.
if git diff --quiet -- "$FILE"; then
  echo "mutant: the file is unchanged after the edit — refusing to report a verdict" >&2
  exit 2
fi
echo "mutant applied: $(git diff --shortstat -- "$FILE" | sed 's/^ *//')"

LOG=$(mktemp)
if ./build.sh --console=plain -Pfast "$LANE" --tests "$FILTER" > "$LOG" 2>&1; then
  VERDICT=0
else
  VERDICT=1
fi
# A filter that matched NO test also "fails" — distinguish it, or a typo reads as a kill.
if grep -q 'No tests found for given includes' "$LOG"; then
  echo "mutant: the filter '$FILTER' matched no test — that is a typo, not a verdict" >&2
  /bin/rm -f "$LOG"; exit 2
fi

if [ "$VERDICT" -eq 1 ]; then
  echo "killed ✓  — $FILTER caught it"
  /bin/rm -f "$LOG"; exit 0
fi
echo "SURVIVED ✗ — $FILTER did NOT catch it."
echo "            Before reporting this, confirm no OTHER control covers it; only that needs a wider run."
/bin/rm -f "$LOG"; exit 1
