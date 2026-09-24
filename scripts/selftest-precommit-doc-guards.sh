#!/usr/bin/env bash
#
# selftest-precommit-doc-guards.sh — prove the pre-commit guard-evidence checks both FIRE and STAY QUIET.
#
# The doc-guard check exists because a prose rule did not hold: the same drift class shipped three times in one
# day, the last time hours after the note warning about it. The leak-lane check exists because a lane `-Pfast`
# skips sat red under 58 commits. A guard written for either reason has to be shown to fire — otherwise it is one
# more thing believed rather than known. Runs in a scratch clone so the real repo, its index, and its build
# outputs are never touched.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
REPO="$WORK/repo"

fail() { echo "SELFTEST FAIL: $*" >&2; exit 1; }

mkdir -p "$REPO/.githooks" "$REPO/cli/src/main/resources/schema" "$REPO/docs"
cp "$ROOT/.githooks/pre-commit" "$REPO/.githooks/pre-commit"
chmod +x "$REPO/.githooks/pre-commit"
git -C "$REPO" init -q
git -C "$REPO" config core.hooksPath .githooks
git -C "$REPO" config user.email selftest@example.invalid
git -C "$REPO" config user.name selftest
echo "seed" > "$REPO/seed.txt"
# As in the real repository, so `git add -A` never stages the fabricated evidence and the hook's build-output
# refusal cannot answer for a case written to exercise the evidence checks.
echo "build/" > "$REPO/.gitignore"
git -C "$REPO" add -A
git -C "$REPO" -c core.hooksPath=/dev/null commit -qm seed

# Every doc-driven lane the hook demands evidence from, in ONE place: the hook's list grew from two to four
# on 2026-09-03 and every fabrication site below has to move with it, so a second literal copy would let the
# positive controls pass against a stale idea of the contract.
fabricate_guard_evidence() {
  local d
  for d in build/test-results/docGuardTest cli/build/test-results/docGuardTest \
           cli/build/test-results/referentGuardTest cli/build/test-results/staleGapGuardTest \
           cli/build/test-results/semanticsMapGuardTest; do
    mkdir -p "$REPO/$d"
    printf '<testsuite name="x" tests="1" skipped="0" failures="0" errors="0">\n' > "$REPO/$d/TEST-x.xml"
  done
}
# The leak lane's evidence, green unless $1 says how many failures the result file records. Each doc case below
# fabricates it AFTER its edit, so the refusal it asserts can only be the doc check's: both checks refuse a bare
# trigger, and a doc case that let the leak check answer for it would pass with the doc check deleted.
fabricate_leak_evidence() {
  mkdir -p "$REPO/build/test-results/leakGuardTest"
  printf '<testsuite name="x" tests="1" skipped="0" failures="%s" errors="0">\n' "${1:-0}" \
    > "$REPO/build/test-results/leakGuardTest/TEST-x.xml"
}
commit_expecting_refusal() { # $1 = message; prints the hook's output, fails the selftest if the commit went in
  local out status
  set +e
  out="$(git -C "$REPO" commit -qm "$1" 2>&1)"
  status=$?
  set -e
  [[ $status -ne 0 ]] || fail "committed, but should have been refused: $1"
  printf '%s' "$out"
}

# 1. A file that cannot drift a doc commits without any doc-guard evidence — the doc check must not tax every
#    commit; only the leak lane's evidence is needed.
echo "print('hi')" > "$REPO/tool.py"
git -C "$REPO" add tool.py
fabricate_leak_evidence
git -C "$REPO" commit -qm "non-trigger" \
  || fail "a file that cannot drift a doc was refused (the doc check over-reaches)"

# 2. A trigger file with NO evidence is refused, and the message names the command that fixes it — and the
#    leak lane's, so one refusal reports every lane the commit still owes.
rm -rf "$REPO/build/test-results"
printf '{"description":"x"}' > "$REPO/cli/src/main/resources/schema/field-add.schema.json"
git -C "$REPO" add -A
out="$(commit_expecting_refusal "schema edit")"
# Every lane, not just the two the hook started with: the refusal is the only place a session learns which
# commands to run, so a lane silently dropped from the hook would leave a doc-driven guard unrun with nothing to
# notice it — which is how a red landed on 033fe3ef6 before referentGuardTest was required here.
for task in ":docGuardTest" ":cli:docGuardTest" ":cli:referentGuardTest" ":cli:staleGapGuardTest" \
            ":cli:semanticsMapGuardTest" ":leakGuardTest"; do
  [[ "$out" == *"$task"* ]] || fail "the refusal did not name $task: $out"
done
[[ "$out" == *"-Pfast"* ]] || fail "the refusal did not warn that -Pfast skips them: $out"

# 3. FRESH evidence lets the same commit through — the checks key on staleness, not on the path alone.
fabricate_guard_evidence
fabricate_leak_evidence
git -C "$REPO" commit -qm "schema edit, guards run" \
  || fail "fresh guard evidence did not satisfy the checks"

# 4. STALE doc evidence is refused again: the trigger changed after the guards ran.
sleep 1
printf '{"description":"y"}' > "$REPO/cli/src/main/resources/schema/field-add.schema.json"
git -C "$REPO" add -A
fabricate_leak_evidence
stale_out="$(commit_expecting_refusal "schema edit again")"
[[ "$stale_out" == *"have not run since"* ]] || fail "the staleness reason was not stated: $stale_out"

# 5. Markdown ANYWHERE triggers, not just a curated set of doc folders. The first version listed docs/,
#    examples/, skill/ and a root-anchored README.md, and let a commit touching CLAUDE.md and scripts/README.md
#    straight through with the root guard's results deleted — a false green inside the check written to prevent
#    them. Both shapes are locked here because both were missed.
git -C "$REPO" commit -qm "schema edit again" --no-verify -q
rm -rf "$REPO/build/test-results" "$REPO/cli/build/test-results"
for doc in CLAUDE.md scripts/README.md docs/nested/deep.md; do
  mkdir -p "$REPO/$(dirname "$doc")"
  echo "content for $doc" > "$REPO/$doc"
  git -C "$REPO" add -A
  fabricate_leak_evidence
  md_out="$(commit_expecting_refusal "docs: touch $doc")"
  [[ "$md_out" == *"$doc"* ]] || fail "the refusal did not name $doc: $md_out"
  git -C "$REPO" commit -qm "docs: touch $doc" --no-verify -q
done

# 6. RED doc evidence is not evidence. Until 2026-09-24 the check read freshness alone, so a guard that ran red
#    after the change satisfied it exactly as a green run did.
echo "red doc" > "$REPO/docs/red.md"
git -C "$REPO" add -A
fabricate_guard_evidence
printf '<testsuite name="x" tests="2" skipped="0" failures="0" errors="1">\n' \
  > "$REPO/cli/build/test-results/referentGuardTest/TEST-x.xml"
fabricate_leak_evidence
red_doc_out="$(commit_expecting_refusal "docs: red guard")"
[[ "$red_doc_out" == *"CI-gated doc"* && "$red_doc_out" == *"FAILURE"* \
   && "$red_doc_out" == *"cli/build/test-results/referentGuardTest"* ]] \
  || fail "the doc check did not name its red guard as a failure: $red_doc_out"
fabricate_guard_evidence
git -C "$REPO" commit -qm "docs: red guard, fixed" || fail "re-run green doc evidence did not satisfy the check"

# 7. The leak lane is demanded for EVERY file, with no doc trigger in sight — refused when absent, then stale,
#    then red, and accepted green. Its trigger is deliberately not a curated set (the hook says why).
echo "leak" > "$REPO/tool.py"
git -C "$REPO" add -A
rm -rf "$REPO/build/test-results/leakGuardTest"
leak_out="$(commit_expecting_refusal "no leak evidence")"
[[ "$leak_out" == *":leakGuardTest"* && "$leak_out" == *"-Pfast"* ]] \
  || fail "the leak refusal did not name the lane and the -Pfast warning: $leak_out"
[[ "$leak_out" != *"CI-gated doc"* ]] || fail "a non-doc file drew the doc refusal: $leak_out"
fabricate_leak_evidence
sleep 1
echo "leak again" > "$REPO/tool.py"
git -C "$REPO" add -A
leak_stale_out="$(commit_expecting_refusal "stale leak evidence")"
[[ "$leak_stale_out" == *"Stale"* ]] || fail "stale leak evidence was not reported as stale: $leak_stale_out"
fabricate_leak_evidence 3
leak_red_out="$(commit_expecting_refusal "red leak evidence")"
[[ "$leak_red_out" == *"leak lane has no green result"* && "$leak_red_out" == *"FAILURE"* ]] \
  || fail "the leak check did not report its red lane as a failure: $leak_red_out"
fabricate_leak_evidence
git -C "$REPO" commit -qm "leak lane green" || fail "fresh green leak evidence did not satisfy the check"

# 8. A red run of ANY lane written since the change is refused, not only the lanes the blocks above name:
#    a878443c8 landed with :catalogProseReviewGuardTest red because nothing in the hook read that lane. The
#    other blocks are satisfied first, so the refusal can only be this one; a green re-run then clears it, and
#    a red run OLDER than the change does not refuse, since this check reads only what ran after it.
fabricate_any_lane() { # $1 = failures, then the result directories
  local failures=$1 d
  shift
  for d in "$@"; do
    mkdir -p "$REPO/$d"
    printf '<testsuite name="x" tests="1" skipped="0" failures="%s" errors="0">\n' "$failures" > "$REPO/$d/TEST-x.xml"
  done
}
echo "any lane" > "$REPO/tool.py"
git -C "$REPO" add -A
fabricate_leak_evidence
sleep 1
fabricate_any_lane 1 build/test-results/catalogProseReviewGuardTest adapter/build/test-results/test
any_red_out="$(commit_expecting_refusal "any lane red")"
[[ "$any_red_out" == *"lane :catalogProseReviewGuardTest"* && "$any_red_out" == *"lane :adapter:test"* ]] \
  || fail "a red root lane and a red module lane were not both named with their task: $any_red_out"
[[ "$any_red_out" != *"leak lane has no green"* && "$any_red_out" != *"CI-gated doc"* ]] \
  || fail "another check answered for the any-lane case: $any_red_out"
fabricate_any_lane 0 build/test-results/catalogProseReviewGuardTest adapter/build/test-results/test
git -C "$REPO" commit -qm "any lane re-run green" || fail "a green re-run did not clear the red-run refusal"
fabricate_any_lane 1 build/test-results/catalogProseReviewGuardTest
sleep 1
echo "edited after the red run" > "$REPO/tool.py"
git -C "$REPO" add -A
fabricate_leak_evidence
git -C "$REPO" commit -qm "red run older than the change" \
  || fail "a red run older than the newest staged change refused the commit"

# 9. --no-verify still bypasses, deliberately — a local hook must not be the only thing standing anywhere.
echo "one more" > "$REPO/docs/bypass-me.md"
git -C "$REPO" add -A
git -C "$REPO" commit -qm "docs: bypass" --no-verify \
  || fail "--no-verify did not bypass the hook"

# 10. THE `stat` DIALECT TRAP, locked on EVERY platform — the defect that shipped. `stat` speaks two
#    incompatible dialects and picking the wrong one does NOT fail cleanly: GNU's `stat -f %m F` reads `%m`
#    as a FILE, so it prints F's FILESYSTEM block on stdout and exits 1, and the original
#    `stat -f %m … || stat -c %Y …` therefore yielded that block CONCATENATED with the real number. Every
#    later `[ "$t" -gt … ]` died as a non-integer — silently, because a test on the LEFT of a `&&` is exempt
#    from `set -e` — so every mtime read as 0, `0 -lt 0` was false, and the check NEVER FIRED on Linux. It
#    worked on macOS, so only CI saw it, and steps 1-9 above could not: they exercise whatever dialect the
#    host happens to speak. These shims speak exactly ONE dialect each, so both branches are exercised
#    wherever this selftest runs.
make_stat_shim() { # $1 = gnu|bsd|garbage -> prints a PATH-prefix directory holding a one-dialect `stat`
  local dir="$WORK/shim-$1"
  mkdir -p "$dir"
  { echo '#!/usr/bin/env bash'; echo "DIALECT=$1"; cat <<'SHIM'
# A `stat` that speaks exactly one dialect, faithful to the measured behaviour of the real ones
# (GNU coreutils 9.11 and macOS BSD stat, both probed 2026-08-22). `perl` supplies the mtime so the shim
# itself needs no dialect.
set -u
mtime() { perl -e 'my @s = stat($ARGV[0]) or exit 1; print $s[9], "\n"' "$1"; }
case "$DIALECT:${1:-}" in
  gnu:-c)  shift; [ "${1:-}" = %Y ] && shift; [ "${1:-}" = -- ] && shift; mtime "$1";;
  gnu:-f)  # GNU takes the format as a FILE: filesystem block on STDOUT, complaint on stderr, exit 1
           echo "  File: \"${3:-}\""; echo "Block size: 4096       Type: ext4"
           echo "stat: cannot read file system information for '${2:-}': No such file or directory" >&2
           exit 1;;
  bsd:-f)  shift; [ "${1:-}" = %m ] && shift; mtime "$1";;
  bsd:-c)  echo "stat: illegal option -- c" >&2; exit 1;;
  garbage:*) echo "not-a-number"; exit 1;;
  *) echo "stat shim: unexpected invocation: $*" >&2; exit 64;;
esac
SHIM
  } > "$dir/stat"
  chmod +x "$dir/stat"
  echo "$dir"
}

for dialect in gnu bsd; do
  shim="$(make_stat_shim "$dialect")"
  rm -rf "$REPO/build/test-results" "$REPO/cli/build/test-results"
  printf '{"description":"%s"}' "$dialect" > "$REPO/cli/src/main/resources/schema/field-add.schema.json"
  git -C "$REPO" add -A
  fabricate_leak_evidence
  set +e
  dialect_out="$(PATH="$shim:$PATH" git -C "$REPO" commit -qm "schema edit, $dialect stat" 2>&1)"
  dialect_status=$?
  set -e
  [[ $dialect_status -ne 0 ]] \
    || fail "with a ${dialect}-only stat, a schema edit with NO evidence was accepted — the mtime read degraded silently"
  # Positive control: the same commit must pass once the guards have run, so the refusal above is the check
  # measuring rather than the check refusing everything.
  fabricate_guard_evidence
  PATH="$shim:$PATH" git -C "$REPO" commit -qm "schema edit, $dialect stat, guards run" \
    || fail "with a ${dialect}-only stat, fresh doc-guard evidence was rejected"
done

# 11. When NEITHER dialect answers, the check REFUSES. This is the property whose absence made step 10's bug
#     invisible: a check that cannot measure must not wave a commit through.
shim="$(make_stat_shim garbage)"
rm -rf "$REPO/build/test-results" "$REPO/cli/build/test-results"
printf '{"description":"blind"}' > "$REPO/cli/src/main/resources/schema/field-add.schema.json"
git -C "$REPO" add -A
set +e
blind_out="$(PATH="$shim:$PATH" git -C "$REPO" commit -qm "schema edit, unusable stat" 2>&1)"
blind_status=$?
set -e
[[ $blind_status -ne 0 ]] || fail "an unusable stat let the commit through — the check passed without measuring"
[[ "$blind_out" == *"mtime"* ]] || fail "the refusal did not say it could not read mtimes: $blind_out"

echo "SELFTEST OK: doc check spares a non-trigger, missing/stale/red evidence refused, fresh green accepted; leak lane demanded for every file, stale and red refused; a red run of any lane since the change refused, an older one ignored; both stat dialects measured, an unusable stat refused, bypass works"
