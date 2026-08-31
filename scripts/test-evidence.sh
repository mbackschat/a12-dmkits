#!/usr/bin/env bash
# What did a build ACTUALLY run? — the answer `BUILD SUCCESSFUL` does not give you.
#
# A Gradle green means one of three different things per task — executed, UP-TO-DATE, or FROM-CACHE — and the
# distinction is printed as one line among dozens. Worse, `build/test-results/<task>/TEST-*.xml` files PERSIST:
# `--tests "*Foo*"` refreshes only the classes it matched, so a directory scan silently mixes runs and the stale
# files are indistinguishable from fresh ones. Both traps produced wrong conclusions on 2026-08-15 — a
# fixture-mutation sweep read 17 "still passing" classes that had simply not been re-run.
#
# This script reads a saved `--console=plain` log plus the result XMLs and reports only what is defensible:
# the state of each test task, and test counts from XMLs that this run wrote for a task it actually EXECUTED.
#
#   ./build.sh --console=plain :test > build/run.log 2>&1
#   ./scripts/test-evidence.sh build/run.log
#
# Read-only: it never runs Gradle and never writes to the build.
set -euo pipefail

PRINT_CUTOFF=0
if [[ "${1:-}" == "--print-cutoff" ]]; then
  PRINT_CUTOFF=1
  shift
fi

LOG="${1:-}"
if [[ -z "$LOG" || ! -f "$LOG" ]]; then
  echo "usage: $0 [--print-cutoff] <path-to-gradle-console-plain-log>" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

verdict="$(grep -oE 'BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -1 || true)"

# ---- the freshness cutoff -----------------------------------------------------------------------------------
#
# The cutoff must be when the run STARTED. Two candidates, and NEITHER is sufficient alone.
#
# (a) The log's birth time. This was the original rule, on the reasoning that "the shell CREATES the log at run
#     start". It does not: `>` TRUNCATES an existing path, and macOS preserves `st_birthtime` across truncation,
#     so reusing a log name — the obvious thing to do — pins the cutoff to whenever that name was first used.
#     Measured 2026-08-31: `build/run-docguard.log` carried a birth time of 2026-08-01, so a 6-second run was
#     credited with 13 result directories and 2016 tests, every one of them from earlier runs. That is the exact
#     false green this script exists to prevent, arriving through its own cutoff and formatted as evidence.
#
# (b) mtime minus the duration Gradle itself prints (`BUILD SUCCESSFUL in 1m 9s`). Immune to reuse, but it can
#     land slightly early, because the log's last write is at the verdict while Gradle's timer excludes daemon
#     and JVM startup.
#
# Taking the LATER of the two is right in both directions: for a freshly created log (a) is the true start and
# is later, so it wins; for a reused one (a) is ancient and (b) wins. The margin only widens (b), so it can only
# make a reused log's cutoff slightly conservative, never restore the month-wide hole.
#
# Timestamps come from python3 — already this script's XML reader — because the shell's are dialect-split and
# the split is silent: `stat -f %B` is BSD-only, and GNU's `stat -f` reads the FORMAT as a FILE, printing a
# filesystem block on stdout while exiting 1, so a `||` fallback appends the real number to garbage rather than
# replacing it (that exact shape had left the pre-commit doc-guard check dead on Linux; docs/BUILD-ENVIRONMENT.md
# "`stat` has two dialects"). `date -r` splits too: an epoch on BSD, a file on GNU.
CUTOFF_MARGIN_SECONDS="${TEST_EVIDENCE_CUTOFF_MARGIN:-30}"
temporary_files=()
cleanup() {
  local path
  for path in "${temporary_files[@]:-}"; do
    [[ -n "$path" ]] || continue
    rm -f "$path"
  done
}
trap cleanup EXIT

cutoff_data="$(mktemp)"
temporary_files+=("$cutoff_data")
python3 - "$LOG" "$CUTOFF_MARGIN_SECONDS" > "$cutoff_data" <<'CUTOFF'
import os, re, sys

log, margin = sys.argv[1], float(sys.argv[2])
info = os.stat(log)
birth = getattr(info, "st_birthtime", None) or info.st_mtime

# Gradle's own verdict line, e.g. "BUILD SUCCESSFUL in 1m 9s" / "in 523ms" / "in 1h 2m 3s".
units = {"h": 3600.0, "m": 60.0, "s": 1.0, "ms": 0.001}
duration = None
with open(log, "r", errors="replace") as handle:
    for line in handle:
        match = re.search(r"^BUILD (?:SUCCESSFUL|FAILED) in (.+?)\s*$", line)
        if match:
            total, seen = 0.0, False
            for value, unit in re.findall(r"(\d+(?:\.\d+)?)\s*(ms|[hms])", match.group(1)):
                total += float(value) * units[unit]
                seen = True
            if seen:
                duration = total
cutoff = max(birth, info.st_mtime - duration - margin) if duration is not None else birth
print(f"{cutoff:.6f}\t{'derived' if duration is not None else 'birth-only'}")
CUTOFF
IFS=$'\t' read -r cutoff_epoch cutoff_basis < "$cutoff_data"

if (( PRINT_CUTOFF )); then
  printf '%s\n' "$cutoff_epoch"
  exit 0
fi

cutoff="$(mktemp)"
temporary_files+=("$cutoff")
python3 -c 'import os,sys; t=float(sys.argv[2]); os.utime(sys.argv[1], (t, t))' "$cutoff" "$cutoff_epoch"

echo "verdict:   ${verdict:-(none — the build did not finish; the log may still be buffering)}"

# ---- what each test task did --------------------------------------------------------------------------------
# Only real test tasks: exclude the compile/resource/classes tasks whose names merely contain "test".
# A task can appear twice (once bare, once with FAILED) — keep the most severe state, since reporting a
# failed task as EXECUTED is precisely the misreading this script exists to prevent.
states="$(mktemp)"
temporary_files+=("$states")
# `|| true` on the whole pipeline: a log naming no test task at all is a legitimate answer ("nothing ran"), and
# under `pipefail` an empty grep would otherwise abort the script before it could say so.
{ grep -oE '^> Task :[A-Za-z:_-]*[Tt]est[A-Za-z]* ?(UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED)?' "$LOG" \
  | sed 's/^> Task //' \
  | grep -vE 'compile|[Pp]rocess.*Resources|Classes|Jar' \
  | awk '{ st = ($2 == "" ? "EXECUTED" : $2)
           rank["FAILED"]=4; rank["EXECUTED"]=3; rank["FROM-CACHE"]=2; rank["UP-TO-DATE"]=1
           r = (st in rank ? rank[st] : 0)
           if (!(($1) in best) || r > bestrank[$1]) { best[$1] = st; bestrank[$1] = r } }
         END { for (t in best) printf "%s\t%s\n", t, best[t] }' | sort > "$states"; } || true

state_for_task() {
  awk -F'\t' -v wanted="$1" '
    $1 == wanted { print $2; found = 1; exit }
    END { if (!found) print "ABSENT" }
  ' "$states"
}

echo
printf '%-38s %-12s\n' "test task" "state"
printf '%-38s %-12s\n' "--------------------------------------" "------------"
awk -F'\t' '{ printf "%-38s %-12s\n", $1, $2 }' "$states"

# ---- results this run is entitled to claim --------------------------------------------------------------------
# A directory maps to the task that owns it: `build/test-results/<task>` is `:<task>`, and
# `<project>/build/test-results/<task>` is `:<project>:<task>`. The mtime cutoff alone is not enough — it
# answers "was this file written recently", never "did THIS run write it" — so the task's own reported state is
# the gate, and the timestamp then separates the classes a `--tests` filter matched from its unmatched siblings.
echo
echo "fresh results (this run's own executions):"
found=0
withheld=()
for dir in "$ROOT"/build/test-results/*/ "$ROOT"/*/build/test-results/*/; do
  [[ -d "$dir" ]] || continue
  # -newer compares mtimes; an XML from a previous run is older than this run's cutoff.
  fresh=()
  while IFS= read -r result_file; do
    fresh+=("$result_file")
  done < <(find "$dir" -name 'TEST-*.xml' -newer "$cutoff" 2>/dev/null)
  (( ${#fresh[@]} )) || continue

  rel="${dir#"$ROOT"/}"
  rel="${rel%/}"
  leaf="${rel##*/}"
  case "$rel" in
    build/test-results/*) task=":$leaf" ;;
    *) task=":${rel%%/*}:$leaf" ;;
  esac
  state="$(state_for_task "$task")"
  if [[ "$state" != "EXECUTED" && "$state" != "FAILED" ]]; then
    withheld+=("$(printf '  %-46s %-12s %5s recent file(s) — NOT this run' "$rel/" "$state" "${#fresh[@]}")")
    continue
  fi

  found=1
  read -r t f e < <(python3 - "${fresh[@]}" <<'PY'
import sys, xml.etree.ElementTree as ET
t=f=e=0
for p in sys.argv[1:]:
    try:
        r = ET.parse(p).getroot()
        t += int(r.get("tests", 0)); f += int(r.get("failures", 0)); e += int(r.get("errors", 0))
    except Exception:
        pass
print(t, f, e)
PY
)
  printf '  %-46s %-12s %5s classes %6s tests  %s\n' "$rel/" "$state" "${#fresh[@]}" "$t" \
    "$( (( f + e )) && echo "FAILURES=$f ERRORS=$e" || echo "0 failures" )"
done
(( found )) || echo "  (none — this run executed no test task whose results survive, so NOTHING here is evidence)"

if (( ${#withheld[@]} )); then
  echo
  echo "withheld — recent files whose task this log does not report as executed:"
  printf '%s\n' "${withheld[@]}"
  echo "  (a reused log name, a parallel build, or an editor touching files; none of it is this run's evidence)"
fi

echo
echo "reminder: UP-TO-DATE and FROM-CACHE are valid greens ONLY if the inputs are the ones you meant to test."
if [[ "$cutoff_basis" == "birth-only" ]]; then
  echo "warning:   the log carries no 'BUILD … in <time>' line, so the cutoff is the file's birth time alone."
  echo "           If this log path was reused, that birth time predates the run and the cutoff is too early."
fi
