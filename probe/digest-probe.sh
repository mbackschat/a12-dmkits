#!/usr/bin/env bash
# probe/digest-probe.sh — DETERMINISTIC trajectory digest from a probe's objective shim log.
#
# The deep, qualitative analysis (correctness vs gold, misconceptions) is the agent's judgment — but the
# MECHANICAL signals must not depend on a human reading 4000 log lines by eye. This script extracts the
# repeatable facts so every probe gets the same baseline analysis, and so the highest-value DX defect — an
# internal tool error — is flagged AUTOMATICALLY, every run. That is either a raw exception/stack trace
# escaping a verb, OR (after the CLI-boundary fix) a STRUCTURED `outcome:"error"` / `RK_INTERNAL_ERROR`
# envelope on stdout with the trace on stderr / exit 70 — both are caught. (That P1 class was found by eye on
# the first run; it must never depend on that again.)
#
#   ./probe/digest-probe.sh            # digest $RK_PROBE_SANDBOX/dmtool-calls.log
#   RK_PROBE_SANDBOX=/path ./probe/digest-probe.sh
#
# Output leads with a DX HEADLINE — calls · nonzero (ratio) · RED FLAGS — the at-a-glance effort/friction
# metric to track across runs (a fix should drop the call count and the nonzero ratio; RED FLAGS are the real
# internal-error defects, distinct from a legitimate rejection/refusal). Then a scannable digest; exit is 0
# normally, 1 if any RED FLAG (escaped exception) is present — so it can also gate. "scripts for deterministic
# facts, agents for judgment" (CLAUDE.md).
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
  if (exc != "" || internalerr != "" || exitc == "70") {
    reason = (exc != "") ? exc : (internalerr != "" ? internalerr : "exit 70 — internal tool error (RK_INTERNAL_ERROR)")
    rf[++rfn] = sprintf("%s\n      %s", cmd, reason)
  }
}
/^\$ dmtool / { flush(); cmd=$0; sub(/^\$ /,"",cmd); instderr=0; exitc=""; errfirst=""; exc=""; internalerr="";
  # a catalog/self-describe verb ECHOES diagnostic codes as DATA — its stdout mentioning RK_INTERNAL_ERROR is a
  # lookup, not a verb erroring (the agent ran `diagnostics RK_INTERNAL_ERROR`). Exempt it from stdout flagging.
  catalogcmd = (verbof(cmd) ~ /^(diagnostics|manifest|schema|operators|patterns)( |$)/); next }
/^--- stdout:/ { instderr=0; next }
/^--- stderr:/ { instderr=1; next }
/^\[exit /     { e=$0; gsub(/[^0-9]/,"",e); exitc=e; instderr=0; next }
{
  if (instderr && $0 != "") {
    if (errfirst == "") errfirst=$0
    if (exc == "" && ($0 ~ /Exception|Throwable|Caused by:|[ \t]+at [A-Za-z0-9_.$]+\([A-Za-z0-9_]+\.java:[0-9]+\)/)) exc=$0
  } else if (!instderr && $0 != "") {
    # Post-CLI-boundary fix, an internal tool error is a STRUCTURED envelope on stdout (outcome:"error" +
    # RK_INTERNAL_ERROR), with the trace on stderr — not a raw trace on stdout. Catch that signal too — but
    # NOT when a catalog/self-describe verb is just echoing the code as data (exit 70 still flags those).
    if (!catalogcmd && internalerr == "" && ($0 ~ /"outcome"[^}]*"error"/ || $0 ~ /RK_INTERNAL_ERROR/)) internalerr=$0
  }
}
END {
  flush()
  printf "probe trajectory digest\n"
  printf "  total dmtool invocations : %d  (distinct: %d)\n", total, ord
  # DX headline: the at-a-glance effort/friction metric (track it across runs — a fix should drop calls and the
  # nonzero ratio; RED FLAGS are the real internal-error defects, distinct from a legitimate rejection/refusal).
  printf "  DX headline              : %d calls  ·  %d nonzero (%.0f%%)  ·  %d RED FLAGS\n",
         total, nzn+0, (total ? 100.0*nzn/total : 0), rfn+0

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

  printf "\n[RED FLAGS — internal tool error: escaped exception, RK_INTERNAL_ERROR, or exit 70] %d\n", rfn+0
  for (i=1;i<=rfn;i++) printf "  ! %s\n", rf[i]
  if (rfn==0) printf "  (none — every error surfaced as a structured, non-internal envelope)\n"

  printf "\n[repeated identical invocations — retry/confusion signal]\n"
  rep=0
  for (c in count) if (count[c] > 1) { printf "  x%d  %s\n", count[c], c; rep++ }
  if (rep==0) printf "  (none)\n"

  exit (rfn>0 ? 1 : 0)
}
' "$LOG"
