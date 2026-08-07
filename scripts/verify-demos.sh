#!/usr/bin/env bash
# verify-demos.sh — run the examples/ Showboat demos against a FRESH local CLI, exactly as CI does.
#
# Why this exists: the showboat demos are a CI-only drift gate (.github/workflows/ci.yml), so an
# output-shape change committed locally can leave a demo stale with CI red — UNNOTICED until the push.
# It bit us twice: fix B (field read echoes per-kind config) and the read-missing-target structured
# refusal both shifted demo output without a local re-verify. The rule that prevents it:
#
#   ANY change to a verb's output shape / behavior / diagnostics → run `./scripts/verify-demos.sh`
#   BEFORE committing.
#
# The demo set is DISCOVERED, not enumerated (an enumerated list is itself a staleness source — a new
# demo would silently go unverified). It globs examples/*.md and keeps the ones that are actually
# Showboat demos (carry a ```output block). It builds the JVM launcher (installDist — always reflects
# current source; native could be stale), then verifies the demos IN PARALLEL (bounded, VERIFY_JOBS).
#
# CI runs THIS script (one step, all demos, parallel) rather than a hand-enumerated list of `uvx`
# calls — so the demo set can't drift out of sync with a separate CI list.
#
#   ./scripts/verify-demos.sh                 # build + verify every discovered demo (parallel); non-zero if any drifted
#   VERIFY_JOBS=4 ./scripts/verify-demos.sh   # cap concurrent verifies (default 6)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# PREFLIGHT the demos' environment prerequisites — loud, never a hidden shim, BEFORE the build. A
# missing prerequisite must NOT read as a demo DRIFT: the cli-custom-types JS-worker blocks
# (`--custom-field-types-js`) degrade LENIENTLY without `node` (the construct reports `unsupported`,
# fired stays empty), so the verifier would diff a well-formed-but-different output and report a false
# "DRIFT" — the mechanism is then invisible in the failure (it cost a stash-bisect to find, 2026-07-18;
# node was nvm-only and off the non-interactive PATH). Fail fast and name the fix instead.
if ! command -v node >/dev/null 2>&1; then
  echo "verify-demos: 'node' is not on PATH — the cli-custom-types demo's JS-worker blocks would" >&2
  echo "degrade to 'unsupported' and read as a false DRIFT. Put a Node.js on PATH first" >&2
  echo "(e.g. 'export PATH=\"\$HOME/.nvm/versions/node/<version>/bin:\$PATH\"'), then re-run." >&2
  exit 1
fi

# Pin JAVA_HOME exactly like build.sh (the same footgun, second site): the BUILD runs through build.sh's
# pin, but the installDist LAUNCHER the demos invoke reads the caller's JAVA_HOME — a stale one (e.g. a
# Homebrew JDK path emptied by an upgrade) makes every demo's `actual` an error banner and reads as a
# mass false DRIFT (it did, 2026-07-23). One JDK policy for build and launcher alike.
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

echo "==> building the JVM CLI launcher (installDist — always current; native is never used here)"
# Delegate the environment policy to the one worktree-safe source of truth. Re-deriving GRADLE_USER_HOME
# from `cd ..` is wrong for a linked worktree under /private/tmp: it creates a second cache beside the
# worktree instead of the common checkout's sibling cache, and concurrent worktrees can corrupt it.
./build.sh --no-daemon :cli:installDist

# The demos call the bare `dmtool`; the repo shim resolves it to the freshly-built installDist launcher.
# Pin the launcher explicitly: DMTOOL_CLI is the shim's highest-priority selector, so an inherited value
# would otherwise make this "fresh build" gate verify an older or unrelated binary. RK_NATIVE is likewise
# cleared so it cannot select a stale native image.
export PATH="$ROOT/bin:$PATH"
export DMTOOL_CLI="$ROOT/cli/build/install/dmtool/bin/dmtool"
unset RK_NATIVE || true

# DISCOVER the demos: every examples/*.md (except README) that carries a Showboat ```output block.
shopt -s nullglob
demos=()
for f in examples/*.md; do
  [[ "$(basename "$f")" == "README.md" ]] && continue
  grep -q '```output' "$f" || continue   # not a Showboat demo — skip
  demos+=("$f")
done

if [[ "${#demos[@]}" -eq 0 ]]; then
  echo "no Showboat demos found under examples/ — nothing to verify" >&2
  exit 1
fi

# REFUSE a demo that would hang this gate. `showboat verify` executes EVERY fenced block — it runs the
# fence's info string as the interpreter — so a block starting a server that never returns blocks the run
# forever: no timeout, no output, nothing in the log after "verifying N demos". `mcp --http` is the only
# verb we ship that behaves that way. It sat in a demo for weeks and passed, because it only ever
# "finished" when its port happened to be occupied; on a free port it hung. Show such a command as an
# INDENTED code block instead — indented blocks are not fenced, so showboat leaves them alone.
hangs=()
for f in "${demos[@]}"; do
  if awk '/^```/ { fenced = !fenced; next }
          fenced && /mcp[[:space:]]+--http/ { found = 1 }
          END { exit !found }' "$f"; then
    hangs+=("$f")
  fi
done
if [[ "${#hangs[@]}" -gt 0 ]]; then
  echo "demo(s) carry a fenced \`mcp --http\` block, which never returns: ${hangs[*]}" >&2
  echo "indent that command by four spaces instead of fencing it (see the comment above this check)" >&2
  exit 1
fi

# RATCHET the width of command lines. A demo is read far more often than it is written, and a 400-character
# one-liner forces horizontal scrolling in every renderer — `cli-mcp.md` had one of 816. The fix is to break
# at the pipes with a trailing `\` and let the continuation start the next line (see `cli-jsonschema.md`,
# which does it throughout). A frame for the stdio transport is the one thing that must NOT wrap, since the
# protocol is newline-delimited; build it with `jq -nc`, whose output is one line however its source is laid
# out. This is a ratchet rather than a flat cap because nine demos predate the rule: a file may only get
# BETTER. Lower a number when you rewrap a file; the gate fails if any file regresses past its own mark.
python3 - "${demos[@]}" <<'RATCHET' || exit 1
import sys, pathlib
BUDGET = {  # longest line inside a ```bash block; ratchet down, never up
    "cli-apply.md": 164, "cli-custom-types.md": 103, "cli-discover.md": 128,
    "cli-edit-loop.md": 207, "cli-jsonschema.md": 149, "cli-mcp.md": 110,
    "cli-public-contract.md": 106, "cli-review.md": 173, "cli-runtime.md": 122,
    "cli-structure-edit.md": 232, "cli-tour.md": 142, "cli-version.md": 109,
    "cli-workspace.md": 125,
}
GOAL = 120  # what a new or rewrapped demo must meet
bad = []
for name in sys.argv[1:]:
    path = pathlib.Path(name)
    fenced, lang, worst = False, None, 0
    for line in path.read_text().split("\n"):
        if line.startswith("```"):
            if not fenced:
                lang = line[3:].strip()
            fenced = not fenced
            continue
        if fenced and lang == "bash":
            worst = max(worst, len(line))
    allowed = BUDGET.get(path.name, GOAL)
    if worst > allowed:
        bad.append(f"{path.name}: longest bash line {worst} > {allowed}"
                   + ("" if path.name in BUDGET else f" (the bar for a new demo is {GOAL})"))
if bad:
    print("command lines got wider — break them at the pipes with a trailing backslash:", file=sys.stderr)
    print("  " + "\n  ".join(bad), file=sys.stderr)
    sys.exit(1)
RATCHET

# Verify in PARALLEL, bounded — a serial loop over ~11 demos (each spawning `uvx showboat`, which in
# turn runs many `dmtool` JVMs) dominated the CI wall-clock. Each verify writes to its own log +
# status file (interleaved stdout would be garbled); we print an ordered summary once all finish.
# Batched throttle (launch a batch of CAP, wait for it, launch the next) keeps this portable to bash
# 3.2 — `wait -n` is bash 4.3+, absent on stock macOS.
CAP="${VERIFY_JOBS:-6}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

verify_one() {
  local f="$1" base
  base="$(basename "$f" .md)"
  if uvx showboat@0.6.1 verify "$f" >"$tmp/$base.log" 2>&1; then
    echo 0 >"$tmp/$base.status"
  else
    echo 1 >"$tmp/$base.status"
  fi
}

echo "==> verifying ${#demos[@]} demos in parallel (cap $CAP)"
running=0
for f in "${demos[@]}"; do
  verify_one "$f" &
  if (( ++running >= CAP )); then wait; running=0; fi
done
wait

failed=0
for f in "${demos[@]}"; do
  base="$(basename "$f" .md)"
  if [[ "$(cat "$tmp/$base.status" 2>/dev/null)" == 0 ]]; then
    echo "==> verify $base ... OK"
  else
    echo "==> verify $base ... DRIFT"
    cat "$tmp/$base.log" 2>/dev/null || true
    failed=1
  fi
done

if [[ "$failed" == 1 ]]; then
  echo "" >&2
  echo "A demo drifted from the live CLI. Re-run the demo's command, paste the new output into its" >&2
  echo "examples/*.md block, and re-run this script until green — BEFORE committing (CLI-TOUR-SPEC)." >&2
  exit 1
fi
echo "==> all ${#demos[@]} discovered demos match the live CLI"
