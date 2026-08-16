#!/usr/bin/env bash
#
# obligations.sh — print the same-commit obligations a change fires, at the moment it matters.
#
# WHY THIS EXISTS. CLAUDE.md carries a trigger table of the form "you changed X, therefore also update Y, and Z
# is the guard that catches you". It has no executor: it fires only when an agent re-reads a 300-line file and
# remembers. That is the densest piece of methodology in the project sitting behind the least reliable mechanism
# there is, and the cost is measured — a rename-only edit to one DOC-DISCIPLINE line left a policy bullet stating
# five false things, and no guard could see it because every link still resolved.
#
# WHAT IT IS, AND IS NOT. A REVIEW TRIGGER, never a guard. It prints what a change obliges and names the guard
# that would catch a miss; it decides nothing and it always exits 0 unless --strict is given. That ceiling is
# honest rather than modest: the residue it points at is semantic ("is this sentence still true?"), and a tool
# claiming to settle that would be advertising something it cannot point at.
#
# THE LINK-RENAME RULE is the one mechanism in this repo that addresses REFERENT DRIFT. A hunk whose only change
# inside a prose line is a link TARGET fires an obligation to re-read the surrounding sentence. It is a heuristic
# — a rename is usually harmless — but it is mechanically detectable, it is cheap, and it would have fired on the
# commit that started this: two rename-only lines that turned a true paragraph false.
#
# USAGE
#   scripts/obligations.sh                 the staged diff (pre-commit)
#   scripts/obligations.sh HEAD~1          against a ref
#   scripts/obligations.sh --strict        exit 1 if anything fired, for a caller that wants a gate
#
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

STRICT=0
REF=""
for arg in "$@"; do
  case "$arg" in
    --strict) STRICT=1 ;;
    *) REF="$arg" ;;
  esac
done

if [ -n "$REF" ]; then
  FILES=$(git diff --name-only "$REF") || exit 1
  DIFF=$(git diff -U0 --src-prefix=a/ --dst-prefix=b/ "$REF")
else
  FILES=$(git diff --cached --name-only)
  DIFF=$(git diff --cached -U0 --src-prefix=a/ --dst-prefix=b/)
fi

[ -n "$FILES" ] || { echo "no changes to check"; exit 0; }

FIRED=0
say() { FIRED=1; printf '\n  ▸ %s\n' "$1"; shift; for line in "$@"; do printf '      %s\n' "$line"; done; }

matches() { echo "$FILES" | grep -qE "$1"; }

echo "obligations fired by ${REF:-the staged diff}:"

# --- path-classifiable rows of CLAUDE.md's trigger table -------------------------------------------------
matches '^cli/src/main/' && say \
  "a CLI verb's surface may have changed" \
  "update: docs/CLI-SPEC.md + docs/CLI-CONFORMANCE-SPEC.md + the README verb tour" \
  "re-verify: ./scripts/verify-demos.sh  (gated on output shape/behaviour/diagnostics changing)" \
  "guard: ManifestVerbTest / CliConformanceMatrixTest / CommandReferencesResolveTest"

matches '^(rulekit|modelkit|core|edit)/src/main/' && say \
  "a DSL/API capability may have shipped" \
  "update: docs/API-GUIDE.md with a TEST-BACKED snippet (+ an API-FINDINGS entry if design-worthy)" \
  "guard: DocSnippetFreshnessTest pins the locked snippets"

matches '^(build\.gradle|settings\.gradle|[a-z]+/build\.gradle)$' && say \
  "a structural change (module, boundary, dependency edge)" \
  "update: docs/ARCHITECTURE.md — the as-is view, no roadmap or status" \
  "guard: none — this one is on you"

matches '^scripts/' && say \
  "a scripts/ file was added, renamed or removed" \
  "update: scripts/README.md — its index row" \
  "guard: DirectoryIndexGuardTest"

matches '^examples/' && say \
  "a demo was added, renamed or removed" \
  "update: examples/README.md + the CI-gate list" \
  "guard: DirectoryIndexGuardTest (partial) + the CI demo gate"

matches '^eval/' && say \
  "an eval task or harness changed" \
  "update: eval/TASKS.md + eval/README.md; measurements go to eval/RESULTS.md (dated, newest first)" \
  "guard: none for the measurement half"

matches '^skill/SKILL\.md$' && say \
  "the skill is the MATERIAL UNDER TEST for the N6 eval" \
  "before editing: re-read the WHOLE file, then run EVERY factual claim through the live binary" \
  "a runtime-semantics claim needs the KERNEL, not model eval — the interpreter can carry an IG divergence" \
  "guard: CommandReferencesResolveTest resolves command refs; nothing checks a claim's TRUTH"

matches '^docs/.*-GAPS\.md$' && say \
  "a gap ledger changed" \
  "if a gap was fixed: graduate the contract into the owning spec and DELETE the row" \
  "guard: StaleGapReferenceGuardTest catches present-tense cites of a deleted gap"

matches '^(adapter|interpreter)/src/main/' && say \
  "interpreter/adapter main source changed" \
  "the session-end checkpoint needs the FULL estate (corpus replay + fixture guards), not -Pfast" \
  "guard: the differential; :adapter:test carries the perf lanes and needs its own invocation"

# --- the link-rename rule: referent drift ---------------------------------------------------------------
# A changed prose line whose -/+ pair differs ONLY inside markdown links — LABEL as well as target. Blanking
# just the target was tried first and missed the founding case outright: that rename changed the label too
# (`[INTERPRETER-PERFORMANCE.md]` -> `[INTERPRETER-PERFORMANCE-RESULTS.md]`), so the keys still differed and the
# rule stayed silent on the one commit it exists for. The sentence around the link was untouched, which is
# exactly when a rename silently reassigns what that sentence asserts.
#
# The diff is taken with EXPLICIT a/ b/ prefixes. A user with `diff.mnemonicPrefix` gets `c/` and `i/` on a
# cached diff, and the `+++ b/` test then matches nothing — which made this trigger silently dead for the
# pre-commit case it exists for, on the author's own machine. Caught by selftest case 1.
RENAME_RULE='
import re, sys
LINK = re.compile(r"\[[^\]]*\]\([^)]*\)|\]\([^)]*\)")
cur = None
removed = {}
out = []
for line in sys.stdin.read().split("\n"):
    if line.startswith("+++ b/"):
        cur = line[6:]; removed.clear(); continue
    if not cur or not cur.endswith(".md"):
        continue
    if line.startswith("-") and not line.startswith("---"):
        removed[LINK.sub("@", line[1:]).strip()] = line[1:].strip()
    elif line.startswith("+") and not line.startswith("+++"):
        key = LINK.sub("@", line[1:]).strip()
        if key in removed and removed[key] != line[1:].strip() and "](" in line:
            out.append(cur + ": " + removed[key][:90])
for o in dict.fromkeys(out):
    print(o)
'
RENAMES=$(printf '%s' "$DIFF" | python3 -c "$RENAME_RULE") || RENAMES=""

if [ -n "$RENAMES" ]; then
  say "a LINK TARGET changed while its sentence did not — re-read the sentence" \
      "a rename keeps every link valid while reassigning what the prose around it asserts;" \
      "no guard can see this, which is why it is a trigger and not a check:"
  while IFS= read -r r; do printf '        %s\n' "$r"; done <<<"$RENAMES"
fi

if [ "$FIRED" -eq 0 ]; then
  echo "  (none)"
  exit 0
fi

printf '\nThis is a REVIEW TRIGGER, not a guard: it names what to check, it does not decide.\n'
[ "$STRICT" -eq 1 ] && exit 1
exit 0
