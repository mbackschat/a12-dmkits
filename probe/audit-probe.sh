#!/usr/bin/env bash
# probe/audit-probe.sh — INTEGRITY GATE for a probe run (see docs/PROBE-SPEC.md "Material boundary").
#
# A probe's findings are only trustworthy if the agent-under-observation stayed inside its MATERIAL BOUNDARY.
# It may use ONLY:
#   - the skill at <sandbox>/SKILL.md,
#   - the `dmtool` binary (through the logging shim), and
#   - files it itself created in <sandbox>/work.
# It must NOT:
#   - read this project's repo (gold fixtures / source / docs), the A12 Tools workspaces, or the kernel checkouts,
#   - read or write any file outside the sandbox,
#   - hand-edit a MODEL file (every model mutation must go through dmtool — the tool is what's under test).
#
# This script audits one finished probe run against that boundary and FAILS LOUD if it was crossed. Run it
# after the probe agent finishes, BEFORE trusting the report. It is deterministic (scripts for checks,
# agents for judgment — the CLAUDE.md rule), so it can also gate a future automated probe.
#
#   ./probe/audit-probe.sh <agent-transcript.jsonl | agentId>
#
# The probe subagent's transcript is …/<session>/subagents/agent-<agentId>.jsonl; the Agent tool result
# prints the agentId. Pass the path, or just the agentId and the script locates it under ~/.claude/projects.
# Env: RK_PROBE_SANDBOX (default /tmp/rk-probe-sandbox).
set -euo pipefail

ARG="${1:?usage: audit-probe.sh <agent-transcript.jsonl | agentId>}"
SANDBOX="${RK_PROBE_SANDBOX:-/tmp/rk-probe-sandbox}"
LOG="$SANDBOX/dmtool-calls.log"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

command -v jq >/dev/null || { echo "audit needs jq" >&2; exit 3; }

# Resolve the transcript: a readable file as-is, else treat ARG as an agentId and find it.
if [[ -f "$ARG" ]]; then
  T="$ARG"
else
  T="$(find "$HOME/.claude/projects" -type f -name "agent-*${ARG}*.jsonl" 2>/dev/null | head -1 || true)"
  [[ -n "$T" && -f "$T" ]] || { echo "transcript not found for: $ARG" >&2; exit 3; }
fi
[[ -f "$LOG" ]] || { echo "shim log not found: $LOG (run prep-probe.sh + the probe first)" >&2; exit 3; }

# A sandbox path may surface as /tmp/... or its /private/tmp/... realpath — accept both.
SB_RE="^(/private)?${SANDBOX#/private}(/|$)"

echo "probe integrity audit"
echo "  transcript : $T"
echo "  sandbox    : $SANDBOX"
echo "  call log   : $LOG"
echo

fail=0
note() { printf '  %s\n' "$*"; }

# Every tool input the agent issued (Bash command bodies + the path-ish args of file tools).
tool_inputs() {
  jq -rc 'select(.message.content) | .message.content[]? | select(.type=="tool_use")
    | (.input.command // empty), (.input.file_path // empty), (.input.path // empty)' "$T"
}
# File-tool target paths only (what the agent read/wrote/searched directly, bypassing dmtool).
file_paths() {
  jq -rc 'select(.message.content) | .message.content[]? | select(.type=="tool_use")
    | select(.name=="Read" or .name=="Write" or .name=="Edit" or .name=="NotebookEdit" or .name=="Glob" or .name=="Grep")
    | .input.file_path // .input.path // empty' "$T"
}
# Every Bash command body, one per line, JSON-escaped (a multi-line/heredoc command stays a single line).
bash_cmds() {
  jq -rc 'select(.message.content) | .message.content[]? | select(.type=="tool_use" and .name=="Bash")
    | .input.command // empty | @json' "$T"
}
write_basenames() {
  jq -rc 'select(.message.content) | .message.content[]? | select(.type=="tool_use" and (.name=="Write" or .name=="Edit" or .name=="NotebookEdit"))
    | .input.file_path // empty' "$T" | while read -r p; do [[ -n "$p" ]] && basename -- "$p"; done | sort -u
}

# --- Check 1: no project / workspace / kernel material referenced in any tool input ---
echo "[1] material boundary — repo / workspace / kernel content"
hits="$(tool_inputs | grep -nE "${REPO}/|/Volumes/Buffer/Apps/A12 Tools|a12-kernel(-demo|-mm)?[/\"' ]" || true)"
if [[ -n "$hits" ]]; then
  fail=1; note "SUSPECT — a tool input references forbidden material:"; printf '%s\n' "$hits" | sed 's/^/      /'
else
  note "clean — no repo/workspace/kernel path in any Bash command or file op"
fi

# --- Check 2: every file read/written/searched is inside the sandbox ---
echo "[2] material boundary — file ops confined to the sandbox"
out="$(file_paths | grep -vE "$SB_RE" | grep -vE '^$' || true)"
if [[ -n "$out" ]]; then
  fail=1; note "SUSPECT — a file tool touched a path outside the sandbox:"; printf '%s\n' "$out" | sed 's/^/      /'
else
  note "clean — every Read/Write/Edit/Glob/Grep path is under $SANDBOX"
fi

# --- Check 3: model files were mutated ONLY by dmtool (no hand-edit bypassing the tool) ---
echo "[3] tool-only mutations — agent never hand-wrote a MODEL file"
# Model files dmtool managed, mined from the objective call log (-m / -o / export <file> / --reference <ref>).
# A model file is the token after -m/-o/export/--reference, but only when that token is itself a value
# (not another flag — e.g. `export --help` must not mine `--help` as a model file).
modelfiles="$(grep '^\$ dmtool ' "$LOG" | awk '{
  for (i=1;i<=NF;i++){
    nxt = (i+1<=NF) ? $(i+1) : ""
    if (nxt ~ /^-/) continue
    if ($i=="-m" || $i=="-o" || $i=="export") { if (nxt != "") print nxt }
    else if ($i=="--reference")               { if (nxt != "") { if (nxt !~ /\./) nxt=nxt".dm.json"; print nxt } }
  }
}' | while read -r f; do [[ -n "$f" ]] && basename -- "$f"; done | sort -u)"
clash=""
while read -r w; do
  [[ -z "$w" ]] && continue
  if printf '%s\n' "$modelfiles" | grep -qxF "$w"; then clash+="$w"$'\n'; fi
done < <(write_basenames)
if [[ -n "${clash// /}" ]]; then
  fail=1; note "SUSPECT — agent Write/Edit targeted a model file dmtool also manages:"; printf '%s\n' "$clash" | sed '/^$/d;s/^/      /'
else
  note "clean — model files [$(printf '%s' "$modelfiles" | paste -sd' ' -)] were only ever touched by dmtool"
fi

# --- Check 4: model files were not hand-edited via BASH either (the Write/Edit check above misses sed/python/redirect) ---
# A model file's content must come only from dmtool. A Bash command that WRITES a model file (redirect / tee /
# in-place editor / cp·mv / a python·node open-for-write) without invoking dmtool is a hand-edit that bypasses
# the very surface the probe measures — and the agent reasoned about doing exactly this ("rather than
# hand-editing JSON"). A redirect FED BY dmtool (`dmtool … > m`) is legitimate (the content is the tool's), so
# the command is exempt when it invokes dmtool.
echo "[4] tool-only mutations — agent never hand-edited a MODEL file via Bash (sed/python/redirect/cp)"
bashhit=""
while IFS= read -r cmd; do
  [[ -z "$cmd" || "$cmd" == '""' ]] && continue
  printf '%s' "$cmd" | grep -q 'dmtool' && continue   # content originates from the tool → not a hand-edit
  while IFS= read -r m; do
    [[ -z "$m" ]] && continue
    if printf '%s' "$cmd" | grep -qE "((>>?|tee)[^|;&]*${m}|(sed|perl|ruby)[[:space:]]+-i[^|]*${m}|(cp|mv|install|dd)[^|;&]*${m}|open\([^)]*${m}[^)]*['\"]?w|>[[:space:]]*['\"]?${m})"; then
      bashhit+="$m :: ${cmd:0:160}"$'\n'; break
    fi
  done <<< "$modelfiles"
done < <(bash_cmds)
if [[ -n "${bashhit// /}" ]]; then
  fail=1; note "SUSPECT — a Bash command hand-edited a model file outside dmtool:"; printf '%s\n' "$bashhit" | sed '/^$/d;s/^/      /'
else
  note "clean — no Bash command hand-edited a model file (no non-dmtool write to a managed model)"
fi

# --- Check 5: no main-loop CONTEXT leaked into the subagent (isolation, not just the file boundary) ---
# The Agent-tool subagent inherits the runner's session TaskList via system-reminders — so a main-loop task
# (its SUBJECT included) bleeds into the probe's context and can BIAS it (it once leaked "findings 6+7",
# steering the agent away from the path under test). The runner must clear its TaskList before spawning
# (PROBE-SPEC); this tripwire catches a forgotten clear: a `task #<n>` reference in the subagent transcript is
# a leaked TaskList id (0 in a clean run; the probe never authors that token itself).
# `|| true` inside the group: a clean run has NO matches, and a no-match grep exits 1 — which under
# `set -euo pipefail` would crash the script (truncating the report) instead of reporting CLEAN.
taskleak="$({ grep -oiE "task #[0-9]+" "$T" 2>/dev/null || true; } | sort -u | tr '\n' ' ')"
echo "[5] context isolation — no main-loop TaskList leaked into the subagent"
if [[ -n "${taskleak// /}" ]]; then
  fail=1; note "SUSPECT — main-loop task context leaked into the probe (clear the TaskList before spawning): $taskleak"
else
  note "clean — no main-loop task reference in the subagent's context"
fi

# --- Check 6: no RAW model-JSON inspection — a model is read ONLY through dmtool (describe/read/export) ---
# A real dmtool user never opens the kernel's DM-JSON by hand — the tool's read surface is the whole point; an
# agent that `cat`/`Read`s a model file is going AROUND the surface (and usually doing so because a read verb
# was insufficient — itself a finding). So reading a MODEL file by any non-dmtool means is a boundary breach.
# (Spec inputs the agent authored — rule.json etc. — are NOT models, so reading those is fine.)
echo "[6] tool-only reads — agent never inspected a MODEL file's raw JSON (must use describe/read/export)"
is_model() { local b; b="$(basename -- "$1")"; printf '%s\n' "$modelfiles" | grep -qxF "$b" || [[ "$b" == *.dm.json || "$b" == *_DM.json ]]; }
rawread=""
# (a) file-tool reads (Read/Glob/Grep) targeting a model file
while read -r p; do [[ -n "$p" ]] && is_model "$p" && rawread+="file-op  $p"$'\n'; done < <(file_paths)
# (b) Bash reads of a model file (cat/jq/grep/head/tail/less/awk/sed/python/node) that do NOT invoke dmtool
while IFS= read -r cmd; do
  [[ -z "$cmd" || "$cmd" == '""' ]] && continue
  printf '%s' "$cmd" | grep -q 'dmtool' && continue   # `dmtool export | jq` etc. is the legitimate read path
  while IFS= read -r m; do
    [[ -z "$m" ]] && continue
    if printf '%s' "$cmd" | grep -qE "(cat|jq|head|tail|less|more|grep|rg|awk|sed|python3?|node)[^|;&]*${m}"; then
      rawread+="bash     ${m} :: ${cmd:0:130}"$'\n'; break
    fi
  done <<< "$modelfiles"
done < <(bash_cmds)
if [[ -n "${rawread// /}" ]]; then
  fail=1; note "SUSPECT — the agent read a MODEL file's raw JSON instead of using dmtool (describe/read/export):"
  printf '%s\n' "$rawread" | sed '/^$/d;s/^/      /'
else
  note "clean — models were inspected only through dmtool (no raw-JSON read)"
fi

echo
if [[ "$fail" -ne 0 ]]; then
  echo "VERDICT: SUSPECT — material boundary crossed; this run's findings are NOT trustworthy."
  exit 1
fi
echo "VERDICT: CLEAN — agent stayed within skill + dmtool + own work-dir files."
