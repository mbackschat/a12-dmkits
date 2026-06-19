#!/usr/bin/env bash
# probe/digest-probe.sh — DETERMINISTIC trajectory digest from a probe's objective shim log.
#
# The deep, qualitative analysis (correctness vs gold, misconceptions) is the agent's judgment — but the
# MECHANICAL signals must not depend on a human reading 4000 log lines by eye. This script extracts the
# repeatable facts so every probe gets the same baseline analysis, and so the highest-value DX defect — a raw
# exception / stack trace escaping a verb instead of a structured diagnostic — is flagged AUTOMATICALLY, every
# run. (That P1 class was found by eye on the first run; it must never depend on that again.)
#
#   ./probe/digest-probe.sh            # digest $RK_PROBE_SANDBOX/dmtool-calls.log
#   RK_PROBE_SANDBOX=/path ./probe/digest-probe.sh
#
# Output is a scannable digest; exit is 0 normally, 1 if any RED FLAG (escaped exception) is present — so it
# can also gate. "scripts for deterministic facts, agents for judgment" (CLAUDE.md).
set -euo pipefail
SANDBOX="${RK_PROBE_SANDBOX:-/tmp/rk-probe-sandbox}"
LOG="${1:-$SANDBOX/dmtool-calls.log}"
[[ -f "$LOG" ]] || { echo "shim log not found: $LOG" >&2; exit 3; }

awk '
# the verb key (target + op) for a command, with -m/-w options stripped
function verbof(c,   s, n, a, k) {
  s = c
  sub(/^dmtool[ \t]+/, "", s)
  gsub(/-m[ \t]+[^ \t]+[ \t]+/, "", s)
  gsub(/-w[ \t]+[^ \t]+[ \t]+/, "", s)
  n = split(s, a, /[ \t]+/)
  k = a[1]
  if (n >= 2 && a[2] !~ /^[-\/]/) k = a[1] " " a[2]   # target op (skip flags/paths)
  return k
}
# purpose bucket: author (mutate) | verify | orient (discover/read)
function bucketof(v,   a, t1, t2) {
  split(v, a, " "); t1 = a[1]; t2 = a[2]
  if (t1 == "apply" || t1 == "batch") return "author"
  if (t1 == "workspace") return (t2 == "validate") ? "verify" : "orient"
  if (t1 ~ /help/ || t1 == "operators" || t1 == "manifest" || t1 == "schema" || t1 == "patterns" || t1 == "diagnostics" || t1 == "export") return "orient"
  if (t2 == "add" || t2 == "remove" || t2 == "modify" || t2 == "rename" || t2 == "move" || t2 == "extract" || t2 == "inline" || t2 == "new") return "author"
  if (t2 == "check" || t2 == "validate") return "verify"
  return "orient"
}
function flush(   v, b) {
  if (cmd == "") return
  total++
  count[cmd]++
  order[cmd] = (cmd in order) ? order[cmd] : (++ord)
  v = verbof(cmd); b = bucketof(v)
  vhist[v]++; vorder[v] = (v in vorder) ? vorder[v] : (++vord); bkt[b]++
  if (exitc != "" && exitc != "0") { nz[++nzn] = sprintf("%s  -> exit %s%s", cmd, exitc, (errfirst!="" ? "  | " errfirst : "")); failv[v]++ }
  if (exc != "")                   { rf[++rfn] = sprintf("%s\n      %s", cmd, exc) }
}
/^\$ dmtool / { flush(); cmd=$0; sub(/^\$ /,"",cmd); instderr=0; exitc=""; errfirst=""; exc=""; next }
/^--- stdout:/ { instderr=0; next }
/^--- stderr:/ { instderr=1; next }
/^\[exit /     { e=$0; gsub(/[^0-9]/,"",e); exitc=e; instderr=0; next }
{
  if (instderr && $0 != "") {
    if (errfirst == "") errfirst=$0
    if (exc == "" && ($0 ~ /Exception|Throwable|Caused by:|[ \t]+at [A-Za-z0-9_.$]+\([A-Za-z0-9_]+\.java:[0-9]+\)/)) exc=$0
  }
}
END {
  flush()
  printf "probe trajectory digest\n"
  printf "  total dmtool invocations : %d  (distinct: %d)\n", total, ord

  printf "\n[purpose buckets]  author %d  ·  verify %d  ·  orient %d\n", bkt["author"]+0, bkt["verify"]+0, bkt["orient"]+0
  printf "[verb histogram — calls (failed), most-used first]\n"
  maxc = 0
  for (v in vhist) if (vhist[v] > maxc) maxc = vhist[v]
  for (rank=maxc; rank>=1; rank--) for (v in vhist) if (vhist[v]==rank) {
    printf "  %3d%s  %s\n", vhist[v], (failv[v] ? sprintf(" (%d x)", failv[v]) : "     "), v
  }

  printf "\n[nonzero exits] %d\n", nzn+0
  for (i=1;i<=nzn;i++) printf "  - %s\n", nz[i]
  if (nzn==0) printf "  (none)\n"

  printf "\n[RED FLAGS — raw exception/stack trace escaped a verb] %d\n", rfn+0
  for (i=1;i<=rfn;i++) printf "  ! %s\n", rf[i]
  if (rfn==0) printf "  (none — every error surfaced as a structured envelope)\n"

  printf "\n[repeated identical invocations — retry/confusion signal]\n"
  rep=0
  for (c in count) if (count[c] > 1) { printf "  x%d  %s\n", count[c], c; rep++ }
  if (rep==0) printf "  (none)\n"

  exit (rfn>0 ? 1 : 0)
}
' "$LOG"
