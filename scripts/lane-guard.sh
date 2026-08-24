#!/usr/bin/env bash
#
# lane-guard.sh — refuse a test lane the current change cannot reach.
#
# WHY THIS IS A SCRIPT AND NOT A RULE. CLAUDE.md already says "treat 'more testing' as a budget smell, not a
# safe default", and names the asymmetry that makes it stick: the broader run is never criticised on
# thoroughness, and its cost lands on the maintainer. That prose was read and quoted by the very session that
# then ran `:cli:check` — the heaviest lane in the estate, ~281 s spawning packaged launchers four at a time,
# driving load to 23 on 8 cores — against a diff touching only `scripts/`. The cli-side guards that change
# COULD reach had already run green in the root lane minutes earlier. So the discipline does not stick as
# prose, exactly as reviewer-side mutant filtering did not; `scripts/mutant.sh` became the rule there, and
# this is the same move.
#
# THE MODEL is deliberately coarse: a lane is justified when the change can plausibly reach it, never
# "probably won't break it". Over-permitting costs one run; over-refusing costs trust and gets the guard
# disabled. Root build files justify everything, because task wiring can move any lane.
#
# BASELINE: uncommitted changes, or — on a clean tree — the files HEAD itself changed. That matches the loop
# (edit, run a lane, commit): right after a commit the tree is clean and HEAD's diff is what you just did.

# The paths this change touches. Overridable so the selftest can drive it without fabricating a repository.
a12_changed_paths() {
  local dirty
  dirty="$(git status --porcelain 2>/dev/null | sed 's/^...//' | sed 's/.* -> //')"
  if [[ -n "$dirty" ]]; then printf '%s\n' "$dirty"; else git diff --name-only HEAD~1 HEAD 2>/dev/null; fi
}

# Does `$1` (a Gradle argument) name something this guard should judge? Only test/verification tasks — an
# `installDist`, `dumpKitApi` or `nativeCompile` is a build product someone asked for on purpose.
a12_is_test_lane() {
  case "$1" in
    -*) return 1 ;;
    *[Tt]est|*[Tt]est[A-Za-z]*|*:check|check) return 0 ;;
    *) return 1 ;;
  esac
}

# Justified? Echoes nothing and returns 0 when the lane is reachable; otherwise returns 1.
a12_lane_justified() {
  local lane="$1" paths="$2" module
  # Root build files rewire any lane, so they justify every lane.
  grep -qE '^(build\.gradle|settings\.gradle|gradle\.properties|build\.sh|gradlew|gradle/)' <<<"$paths" && return 0
  # A doc/markdown change justifies exactly the doc-reading guards, in any module.
  if [[ "$lane" == *docGuardTest* || "$lane" == *referentGuardTest* || "$lane" == *docsTest* ]]; then
    grep -qE '(^docs/|\.md$|^skill/|^examples/)' <<<"$paths" && return 0
  fi
  # Scripts and hooks are executed by the delivery guards.
  if [[ "$lane" == *deliveryGuardTest* || "$lane" == *leakGuardTest* ]]; then
    grep -qE '^(scripts/|\.githooks/|bin/|build\.sh)' <<<"$paths" && return 0
  fi
  # The shared kits are compiled into every module, so a change there reaches every lane.
  grep -qE '^(core|rulekit|modelkit|edit)/src/' <<<"$paths" && return 0
  # `:module:task` is justified by a change under that module; a root task by a change under root sources.
  if [[ "$lane" == :*:* ]]; then
    module="${lane#:}"; module="${module%%:*}"
    grep -qE "^${module}/" <<<"$paths" && return 0
    # The adapter compiles against the interpreter, and the cli ships both.
    [[ "$module" == adapter ]] && grep -qE '^interpreter/' <<<"$paths" && return 0
    [[ "$module" == cli ]] && grep -qE '^(interpreter|adapter)/' <<<"$paths" && return 0
    return 1
  fi
  grep -qE '^(src/|scripts/|docs/|perf-results/|.*\.md$)' <<<"$paths" && return 0
  return 1
}

# The refusal text for every unjustified lane among "$@", or nothing when all are justified.
a12_lane_refusals() {
  local paths lane out=""
  paths="$(a12_changed_paths)"
  [[ -n "$paths" ]] || return 0
  for lane in "$@"; do
    a12_is_test_lane "$lane" || continue
    a12_lane_justified "$lane" "$paths" || out+="  $lane"$'\n'
  done
  printf '%s' "$out"
}
