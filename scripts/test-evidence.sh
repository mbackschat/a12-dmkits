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
# the state of each test task, and test counts from XMLs written AFTER the run began.
#
#   ./build.sh --console=plain :test > build/run.log 2>&1
#   ./scripts/test-evidence.sh build/run.log
#
# Read-only: it never runs Gradle and never writes to the build.
set -euo pipefail

LOG="${1:-}"
if [[ -z "$LOG" || ! -f "$LOG" ]]; then
  echo "usage: $0 <path-to-gradle-console-plain-log>" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

verdict="$(grep -oE 'BUILD (SUCCESSFUL|FAILED)' "$LOG" | tail -1 || true)"
echo "verdict:   ${verdict:-(none — the build did not finish; the log may still be buffering)}"

# The freshness cutoff must be when the run STARTED, and the log's mtime is when it ENDED — XMLs are written
# during the run, so comparing against mtime marks every one of them stale and reports "nothing is evidence"
# every single time. (That bug was in the first version of this script and its own demo exposed it, which is
# fitting for a tool whose whole job is to stop a plausible-looking build verdict being believed.) The shell
# CREATES the log at run start, so its birth time is the right cutoff; mtime is the fallback where the
# filesystem has no birth time, and there the answer is conservative rather than wrong.
cutoff="$(mktemp)"
# The timestamps come from python3 — already this script's XML reader — because the shell's are dialect-split
# and the split is silent: `stat -f %B` is BSD-only, and GNU's `stat -f` reads the FORMAT as a FILE, printing a
# filesystem block on stdout while exiting 1, so a `||` fallback appends the real number to garbage rather than
# replacing it (that exact shape had left the pre-commit doc-guard check dead on Linux; docs/BUILD-ENVIRONMENT.md
# "`stat` has two dialects"). `date -r` splits too: an epoch on BSD, a file on GNU. One portable API instead of
# two dialects. Birth time where the platform reports it, mtime where it does not — there the cutoff is
# conservative (it can only under-report freshness) rather than wrong.
python3 - "$LOG" "$cutoff" <<'CUTOFF'
import os, sys
log, cutoff = sys.argv[1], sys.argv[2]
info = os.stat(log)
start = getattr(info, "st_birthtime", None) or info.st_mtime
os.utime(cutoff, (start, start))
CUTOFF
trap 'rm -f "$cutoff"' EXIT

echo
printf '%-38s %-12s\n' "test task" "state"
printf '%-38s %-12s\n' "--------------------------------------" "------------"
# Only real test tasks: exclude the compile/resource/classes tasks whose names merely contain "test".
# A task can appear twice (once bare, once with FAILED) — keep the most severe state, since reporting a
# failed task as EXECUTED is precisely the misreading this script exists to prevent.
grep -oE '^> Task :[A-Za-z:_-]*[Tt]est[A-Za-z]* ?(UP-TO-DATE|FROM-CACHE|NO-SOURCE|SKIPPED|FAILED)?' "$LOG" \
  | sed 's/^> Task //' \
  | grep -vE 'compile|[Pp]rocess.*Resources|Classes|Jar' \
  | awk '{ st = ($2 == "" ? "EXECUTED" : $2)
           rank["FAILED"]=4; rank["EXECUTED"]=3; rank["FROM-CACHE"]=2; rank["UP-TO-DATE"]=1
           r = (st in rank ? rank[st] : 0)
           if (!(($1) in best) || r > bestrank[$1]) { best[$1] = st; bestrank[$1] = r } }
         END { for (t in best) printf "%-38s %-12s\n", t, best[t] }' | sort

echo
echo "fresh results (XMLs written after $LOG was created):"
found=0
for dir in "$ROOT"/build/test-results/*/ "$ROOT"/*/build/test-results/*/; do
  [[ -d "$dir" ]] || continue
  # -newer compares mtimes; an XML from a previous run is older than this run's log.
  mapfile -t fresh < <(find "$dir" -name 'TEST-*.xml' -newer "$cutoff" 2>/dev/null)
  (( ${#fresh[@]} )) || continue
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
  rel="${dir#"$ROOT"/}"
  printf '  %-46s %5s classes %6s tests  %s\n' "$rel" "${#fresh[@]}" "$t" \
    "$( (( f + e )) && echo "FAILURES=$f ERRORS=$e" || echo "0 failures" )"
done
(( found )) || echo "  (none — every result file predates this log, so NOTHING in this run is evidence)"

echo
echo "reminder: UP-TO-DATE and FROM-CACHE are valid greens ONLY if the inputs are the ones you meant to test."
