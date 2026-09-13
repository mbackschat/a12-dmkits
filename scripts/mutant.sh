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
# The lane is INFERRED from the mutated file's module and printed; pass a 5th argument to override. Name the
# ONE lane that owns the control — passing several defeats the point: the first version of this script ran
# `:adapter:test :cli:test` and timed out at 2 minutes, which is the very cost it exists to remove.
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
FILE=$1; OLD=$2; NEW=$3; FILTER=$4

# The lane, INFERRED from where the mutated file lives, because getting it wrong costs a whole run and reads
# like a finding: `:adapter:test` was the unconditional default, so a mutant planted in core/ or cli/ reported
# "matched no test" and had to be re-launched with the lane appended (measured twice on 2026-09-04). An explicit
# 5th argument still wins — the inference is a default, not a policy. Root `:test` owns every module without its
# own test source set (core, rulekit, modelkit, edit), which is why it is the fallback rather than an error.
if [ $# -ge 5 ]; then
  LANE=$5
else
  # Repo-relative, so an absolute or ./-prefixed path (the form a review packet quotes) still matches.
  REL=${FILE#"$PWD/"}; REL=${REL#./}
  case $REL in
    interpreter/src/jsMain/*|interpreter/src/jsTest/*)
      echo "mutant: REFUSING to guess a lane for a JS-only source set. :interpreter:jvmTest never compiles" >&2
      echo "        $REL, so the mutant applies and every test passes — a false SURVIVED, which is the one" >&2
      echo "        outcome this script exists to prevent. Name the JS lane explicitly as the 5th argument." >&2
      exit 2 ;;
    consistency/src/jsMain/*|consistency/src/jsTest/*)
      echo "mutant: REFUSING to guess a lane for a JS-only source set. :consistency:jvmTest never compiles" >&2
      echo "        $REL, so the mutant applies and every test passes — a false SURVIVED. Name the JS lane" >&2
      echo "        (:consistency:jsNodeTest) explicitly as the 5th argument." >&2
      exit 2 ;;
    adapter/*) LANE=:adapter:test ;;
    cli/*) LANE=:cli:test ;;
    # A Kotlin Multiplatform module registers no plain `test` task, so the fallback below would not merely run
    # the wrong lane — it would run one that compiles none of this module's sources and matches no test, which
    # is a false SURVIVED rather than an error (measured 2026-09-12, planting into consistency/src/commonMain).
    consistency/*) LANE=:consistency:jvmTest ;;
    interpreter/*) LANE=:interpreter:jvmTest ;;
    interpreter-bridge/*) LANE=:interpreter-bridge:test ;;
    schemakit/*) LANE=:schemakit:test ;;
    *) LANE=:test ;;
  esac
  echo "mutant: lane inferred from the file's module: $LANE (pass a 5th argument to override)"
fi

[ -f "$FILE" ] || { echo "mutant: no such file: $FILE" >&2; exit 2; }
if ! git diff --quiet -- "$FILE" || ! git diff --cached --quiet -- "$FILE"; then
  echo "mutant: REFUSING — $FILE has uncommitted changes." >&2
  echo "        Commit first. Reverting a mutant over uncommitted work is how three edits were lost in one" >&2
  echo "        session; this script will not create that situation." >&2
  exit 2
fi

STARTED=$(mktemp); BACKUP=$(mktemp); cp "$FILE" "$BACKUP"
# A killed mutant leaves a FAILING result XML behind, and those persist. Left there it makes every later
# "are you about to commit on red?" check cry wolf, which is how an honest signal gets ignored. The experiment
# cleans up after itself.
restore() {
  cp "$BACKUP" "$FILE"; /bin/rm -f "$BACKUP"
  find . -name 'TEST-*.xml' -newer "$STARTED" 2>/dev/null \
    | xargs grep -l -E 'failures="[1-9]|errors="[1-9]' 2>/dev/null | xargs -r /bin/rm -f 2>/dev/null || true
  /bin/rm -f "$STARTED"
}
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

# Prove it landed rather than trusting the write, because that is the failure this guards. The proof compares
# BYTES against the backup, never `git diff`: git diff is silent for an UNTRACKED file, so the git form
# reported "unchanged after the edit" for every mutant planted in a file that had just been created — the new
# guard class being the case that found it (2026-09-13). The byte comparison needs no index and is the same
# copy `restore` puts back, so it cannot disagree with what will be undone.
if cmp -s "$BACKUP" "$FILE"; then
  echo "mutant: the file is unchanged after the edit — refusing to report a verdict" >&2
  exit 2
fi
echo "mutant applied: $(git diff --shortstat -- "$FILE" | sed 's/^ *//' | grep . || echo 'untracked file, 1 replacement')"

LOG=$(mktemp)
# `-Pfast` drops the guard prerequisites, which is the whole speed win — but it SKIPS docGuardTest,
# evalGuardTest and mutationTailGuardTest outright, so naming one of them as the lane under -Pfast runs
# nothing and reports the mutant SURVIVED. Whenever the lane IS a guard task, the flag comes off.
FAST=-Pfast
case $LANE in
  *docGuardTest|*evalGuardTest|*mutationTailGuardTest|*GuardTest)
    FAST=
    echo "mutant: $LANE is skipped by -Pfast, so this run drops the flag (slower, and the only correct option)" ;;
esac
# `--rerun` is not optional. Gradle skips a task it finds UP-TO-DATE, and a mutated file is only an input of
# the tasks that declare it — a doc never is — so re-using a filter string from an earlier run leaves the lane
# up-to-date, runs NOTHING, and reports SURVIVED. Measured 2026-09-12 on `docs/MVK-LEDGER.md`: the same mutant
# read SURVIVED, then KILLED on a re-run whose only difference was that the lane actually executed.
if ./build.sh --console=plain $FAST "$LANE" --rerun --tests "$FILTER" > "$LOG" 2>&1; then
  VERDICT=0
else
  VERDICT=1
fi
# A lane that is not a Test task rejects `--tests` while CONFIGURING, so Gradle fails before running anything
# and every mutant reads as killed. This is the false verdict in the opposite direction from the ones below,
# and the more dangerous one: it confirms whatever the operator hoped. Measured 2026-09-12, when a review
# packet named `:consistency:check` — a lifecycle task — and its first three mutants were void.
if grep -q "Unknown command-line option '--tests'" "$LOG"; then
  echo "mutant: $LANE is not a Test task, so it rejects --tests and fails before running anything —" >&2
  echo "        every mutant would read 'killed'. Name the Test task itself (for a Kotlin Multiplatform" >&2
  echo "        module that is :<module>:jvmTest or :<module>:jsNodeTest, never :<module>:check)." >&2
  exit_and_clean=2
fi
# A filter that matched NO test also "fails" — distinguish it, or a typo reads as a kill.
if grep -q 'No tests found for given includes' "$LOG"; then
  echo "mutant: the filter '$FILTER' matched no test in $LANE — that is a typo or the wrong lane, not a verdict" >&2
  exit_and_clean=2
fi
# The control may be hosted by a DEDICATED task, which the broad lane excludes by construction
# (`routeExclusivelyHostedTests` derives each exclude list from the dedicated tasks' includes). The build
# already names the task to run; surface that instead of leaving the operator to guess, because a wrong lane
# is the one outcome that looks like a finding. Measured 2026-09-04: the lane inference cannot know which
# classes a dedicated task hosts, so the routing message is the only reliable signal.
if grep -q 'deliberately excludes' "$LOG"; then
  echo "mutant: $LANE does not host that control — the build names the task that does:" >&2
  sed -n '/deliberately excludes/,/^$/p' "$LOG" | sed 's/^/        /' >&2
  echo "        Re-run with that task as the 5th argument." >&2
  exit_and_clean=2
fi
if [ "${exit_and_clean:-0}" -ne 0 ]; then
  /bin/rm -f "$LOG"; exit "$exit_and_clean"
fi

if [ "$VERDICT" -eq 1 ]; then
  echo "killed ✓  — $FILTER caught it"
  /bin/rm -f "$LOG"; exit 0
fi
echo "SURVIVED ✗ — $FILTER did NOT catch it."
echo "            Before reporting this, confirm no OTHER control covers it; only that needs a wider run."
/bin/rm -f "$LOG"; exit 1
