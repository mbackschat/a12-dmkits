#!/usr/bin/env bash
#
# selftest-obligations.sh — behavioural guard for obligations.sh.
#
# The script it drives is a REVIEW TRIGGER, so the thing worth guarding is not an exit code but WHICH
# obligations fire. Two directions matter equally and the second is the one that decides whether anyone keeps
# the tool switched on: a trigger that fires on everything is noise, and noise gets ignored rather than read.
#
# Every case runs against a fabricated git repository, so the assertions do not drift when this repo's own
# history grows. One case is the exception and is pinned deliberately: the real commit that motivated the
# link-rename rule, which is the only proof that the rule fires on the defect class it was built for.
#
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OBLIGATIONS="$REPO/scripts/obligations.sh"
FAIL=0

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; printf '        %s\n' "${2:-}"; FAIL=1; }

sandbox() {
  local dir; dir="$(mktemp -d)"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@t; git -C "$dir" config user.name t
  mkdir -p "$dir/scripts"; cp "$OBLIGATIONS" "$dir/scripts/obligations.sh"
  echo "$dir"
}

# --- 1. a rename-only prose line fires the referent-drift trigger ----------------------------------------
d=$(sandbox); mkdir -p "$d/docs"
printf 'See [OLD.md](OLD.md) for the method and its dated results.\n' > "$d/docs/A.md"
git -C "$d" add -A >/dev/null; git -C "$d" commit -qm base
printf 'See [NEW.md](NEW.md) for the method and its dated results.\n' > "$d/docs/A.md"
git -C "$d" add -A >/dev/null
out=$("$d/scripts/obligations.sh" 2>&1) || true
grep -q 'LINK TARGET changed' <<<"$out" \
  && pass "a rename-only prose line fires the re-read trigger" \
  || fail "a rename-only prose line fires the re-read trigger" "$out"
/bin/rm -rf "$d"

# --- 2. a line whose SENTENCE also changed does NOT fire it ----------------------------------------------
# This is the discriminator. Without it the rule would fire on every edited line that happens to hold a link,
# which is most of this estate's prose, and the trigger would be worthless.
d=$(sandbox); mkdir -p "$d/docs"
printf 'See [OLD.md](OLD.md) for the method.\n' > "$d/docs/A.md"
git -C "$d" add -A >/dev/null; git -C "$d" commit -qm base
printf 'Consult [NEW.md](NEW.md) for the calibration procedure instead.\n' > "$d/docs/A.md"
git -C "$d" add -A >/dev/null
out=$("$d/scripts/obligations.sh" 2>&1) || true
grep -q 'LINK TARGET changed' <<<"$out" \
  && fail "a rewritten sentence must NOT fire the rename trigger" "$out" \
  || pass "a rewritten sentence must NOT fire the rename trigger"
/bin/rm -rf "$d"

# --- 3. a clean tree reports nothing ---------------------------------------------------------------------
d=$(sandbox); echo x > "$d/readme"; git -C "$d" add -A >/dev/null; git -C "$d" commit -qm base
out=$("$d/scripts/obligations.sh" 2>&1) || true
grep -q 'no changes to check' <<<"$out" \
  && pass "a clean tree reports no changes" \
  || fail "a clean tree reports no changes" "$out"
/bin/rm -rf "$d"

# --- 4. a path-classified row fires, and an unrelated path does not --------------------------------------
d=$(sandbox); mkdir -p "$d/cli/src/main" "$d/unrelated"
echo x > "$d/unrelated/f.txt"; git -C "$d" add -A >/dev/null; git -C "$d" commit -qm base
echo y > "$d/cli/src/main/Verb.java"; git -C "$d" add -A >/dev/null
out=$("$d/scripts/obligations.sh" 2>&1) || true
grep -q "CLI verb's surface" <<<"$out" \
  && pass "a cli/src/main change fires the verb-surface obligation" \
  || fail "a cli/src/main change fires the verb-surface obligation" "$out"

echo z > "$d/unrelated/g.txt"; git -C "$d" add -A >/dev/null
git -C "$d" reset -q HEAD "cli" 2>/dev/null || true
out=$("$d/scripts/obligations.sh" 2>&1) || true
grep -q "CLI verb's surface" <<<"$out" \
  && fail "an unrelated path must not fire the verb obligation" "$out" \
  || pass "an unrelated path must not fire the verb obligation"
/bin/rm -rf "$d"

# --- 5. --strict turns a trigger into a gate -------------------------------------------------------------
d=$(sandbox); mkdir -p "$d/scripts"
echo x > "$d/readme"; git -C "$d" add -A >/dev/null; git -C "$d" commit -qm base
echo y > "$d/scripts/new-thing.sh"; git -C "$d" add -A >/dev/null
if "$d/scripts/obligations.sh" --strict >/dev/null 2>&1; then
  fail "--strict exits non-zero when something fired" "exited 0"
else
  pass "--strict exits non-zero when something fired"
fi
out=$("$d/scripts/obligations.sh" 2>&1) || true
grep -q 'REVIEW TRIGGER' <<<"$out" \
  && pass "the default run states it is a trigger, not a guard" \
  || fail "the default run states it is a trigger, not a guard" "$out"
/bin/rm -rf "$d"

# --- 6. THE PINNED CASE: the real commit the rule exists for ---------------------------------------------
# f9f92cdb split the performance doc and touched docs/DOC-DISCIPLINE.md by one line carrying link renames
# ONLY. Every guard passed and the surrounding paragraph was left stating five false things. A first version
# of the rule blanked only `](target)` and missed this outright, because the rename changed the link LABEL
# too — so this case is pinned rather than fabricated: it is the discriminator that version got wrong.
if git -C "$REPO" cat-file -e f9f92cdb^{commit} 2>/dev/null; then
  out=$("$OBLIGATIONS" 'f9f92cdb~1..f9f92cdb' 2>&1) || true
  grep -q 'DOC-DISCIPLINE.md' <<<"$out" \
    && pass "the founding referent-drift commit fires on DOC-DISCIPLINE.md" \
    || fail "the founding referent-drift commit fires on DOC-DISCIPLINE.md" "$out"
else
  printf '  skip  pinned commit f9f92cdb not in this clone\n'
fi

echo
[ "$FAIL" -eq 0 ] && { echo "selftest-obligations: all cases pass"; exit 0; }
echo "selftest-obligations: FAILURES above"; exit 1
