#!/usr/bin/env bash
#
# selftest-precommit-doc-guards.sh — prove the pre-commit doc-guard staleness check both FIRES and STAYS QUIET.
#
# The check exists because a prose rule did not hold: the same drift class shipped three times in one day, the
# last time hours after the note warning about it. A guard written for that reason has to be shown to fire —
# otherwise it is one more thing believed rather than known. Runs in a scratch clone so the real repo, its
# index, and its build outputs are never touched.
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
git -C "$REPO" add -A
git -C "$REPO" -c core.hooksPath=/dev/null commit -qm seed

# 1. A NON-trigger file commits freely even with no guard evidence at all — the check must not tax every commit.
echo "print('hi')" > "$REPO/tool.py"
git -C "$REPO" add tool.py
git -C "$REPO" commit -qm "non-trigger" \
  || fail "a file that cannot drift a doc was refused (the check over-reaches)"

# 2. A trigger file with NO evidence is refused, and the message names the command that fixes it.
printf '{"description":"x"}' > "$REPO/cli/src/main/resources/schema/field-add.schema.json"
git -C "$REPO" add -A
set +e
out="$(git -C "$REPO" commit -qm "schema edit" 2>&1)"
status=$?
set -e
[[ $status -ne 0 ]] || fail "a schema edit committed with no doc-guard evidence"
[[ "$out" == *":docGuardTest"* && "$out" == *":cli:docGuardTest"* ]] \
  || fail "the refusal did not name both guard tasks: $out"
[[ "$out" == *"-Pfast"* ]] || fail "the refusal did not warn that -Pfast skips them: $out"

# 3. FRESH evidence lets the same commit through — the check keys on staleness, not on the path alone.
mkdir -p "$REPO/build/test-results/docGuardTest" "$REPO/cli/build/test-results/docGuardTest"
touch "$REPO/build/test-results/docGuardTest/TEST-x.xml" \
      "$REPO/cli/build/test-results/docGuardTest/TEST-y.xml"
git -C "$REPO" commit -qm "schema edit, guards run" \
  || fail "fresh doc-guard evidence did not satisfy the check"

# 4. STALE evidence is refused again: the trigger changed after the guards ran.
sleep 1
printf '{"description":"y"}' > "$REPO/cli/src/main/resources/schema/field-add.schema.json"
git -C "$REPO" add -A
set +e
stale_out="$(git -C "$REPO" commit -qm "schema edit again" 2>&1)"
stale_status=$?
set -e
[[ $stale_status -ne 0 ]] || fail "evidence older than the change was accepted as fresh"
[[ "$stale_out" == *"have not run since"* ]] || fail "the staleness reason was not stated: $stale_out"

# 5. Markdown ANYWHERE triggers, not just a curated set of doc folders. The first version listed docs/,
#    examples/, skill/ and a root-anchored README.md, and let a commit touching CLAUDE.md and scripts/README.md
#    straight through with the root guard's results deleted — a false green inside the check written to prevent
#    them. Both shapes are locked here because both were missed.
rm -rf "$REPO/build/test-results" "$REPO/cli/build/test-results"
for doc in CLAUDE.md scripts/README.md docs/nested/deep.md; do
  mkdir -p "$REPO/$(dirname "$doc")"
  echo "content for $doc" > "$REPO/$doc"
  git -C "$REPO" add -A
  set +e
  md_out="$(git -C "$REPO" commit -qm "docs: touch $doc" 2>&1)"
  md_status=$?
  set -e
  [[ $md_status -ne 0 ]] || fail "$doc did not trigger the check (markdown outside docs/ must still trigger)"
  [[ "$md_out" == *"$doc"* ]] || fail "the refusal did not name $doc: $md_out"
  git -C "$REPO" commit -qm "docs: touch $doc" --no-verify -q
done

# 6. --no-verify still bypasses, deliberately — a local hook must not be the only thing standing anywhere.
echo "one more" > "$REPO/docs/bypass-me.md"
git -C "$REPO" add -A
git -C "$REPO" commit -qm "docs: bypass" --no-verify \
  || fail "--no-verify did not bypass the hook"

echo "SELFTEST OK: non-trigger passes, missing evidence refused, fresh evidence accepted, stale evidence refused, bypass works"
