#!/usr/bin/env bash
# Gradle wrapper that pins the public-repos-only Gradle home + JDK 21 — so a *bare* `./gradlew` can't be
# silently contaminated by the machine's global `~/.gradle/init.d/repositories.gradle`, which force-injects
# a private, credentialed registry into EVERY project (incl. this one) at the plugin, dependency, AND
# buildscript levels. A project cannot remove an init-script-added repository from inside; the only complete
# defense is an ISOLATED Gradle home (init scripts load from $GRADLE_USER_HOME/init.d/, so a home without that
# file loads no init script at all). Forgetting the env var fails confusingly — looks like a network/auth
# error, not "you forgot a var". settings.gradle declares public plugin repositories first and the committed
# .no-global-gradle-repos marker opts this build out of such injection, so a bare ./gradlew resolves publicly
# too; this wrapper additionally pins the shared cache + JDK 21. Root cause: docs/BUILD-ENVIRONMENT.md.
#
#   ./build.sh test            # == ./gradlew test, but with the isolated home + JDK 21 guaranteed
#   ./build.sh :cli:installDist
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"

# Isolated, init.d-free Gradle home — a sibling dir shared across the public A12 repos (matches the README
# + the scripts). A linked worktree may live anywhere (often /private/tmp), so anchor the default at Git's
# common directory / primary checkout rather than at this worktree's filesystem parent. Respect an explicit
# GRADLE_USER_HOME (CI sets its own).
export GRADLE_USER_HOME="$(a12_gradle_user_home "$ROOT")"

# JDK 21 is the build toolchain. On macOS, pin it via java_home (the footgun's second half: the shell's
# JAVA_HOME may point at a newer JDK). Elsewhere (Linux/CI) leave JAVA_HOME to the caller. The pin lives in
# gradle-environment.sh so a launcher check can apply the SAME one — see a12_pinned_jdk21_home.
# A macOS box with no JDK 21 registered now ABORTS here rather than exporting an empty JAVA_HOME and building
# under whatever java is on PATH, which is what the previous `export JAVA_HOME="$(...)"` did — `export` is a
# special builtin whose status is 0 even when its substitution fails, so `set -e` never saw it. Fail-fast is
# the intent; the silent degrade was not.
pinned_jdk21_home="$(a12_pinned_jdk21_home)" \
  || { echo "build.sh: no OpenJDK 21 registered; install one (brew install openjdk@21). A GraalVM 21 alone is" >&2
       echo "          deliberately NOT accepted as the build JDK - GraalVM is for native image (GRAALVM_HOME)." >&2
       exit 1; }
[[ -z "$pinned_jdk21_home" ]] || export JAVA_HOME="$pinned_jdk21_home"

# THE LANE GUARD — refuse a test lane this change cannot reach (owner rule, 2026-08-24). CLAUDE.md already
# carried this as prose and the session that wrote the prose still ran `:cli:check`, the estate's heaviest
# lane, against a scripts-only diff. CI is exempt on purpose: it deliberately runs everything, and a guard
# that reddens CI is disabled within the week. `RK_LANE=all` overrides and SAYS SO, so an override is visible
# in the log rather than silent. Rationale + mapping: scripts/lane-guard.sh.
if [[ -z "${CI:-}" ]]; then
  . "$ROOT/scripts/lane-guard.sh"
  if [[ "${RK_LANE:-}" == "all" ]]; then
    echo "build.sh: RK_LANE=all - lane guard overridden for this run" >&2
  else
    unjustified="$(a12_lane_refusals "$@")"
    if [[ -n "$unjustified" ]]; then
      echo "build.sh: REFUSING a lane this change cannot reach:" >&2
      printf '%s' "$unjustified" >&2
      echo "  changed: $(a12_changed_paths | head -4 | tr '\n' ' ')" >&2
      echo "  Run the lane the change reaches, or RK_LANE=all ./build.sh ... to override deliberately." >&2
      echo "  Why: a broader run is never criticised on thoroughness and its cost lands on the maintainer" >&2
      echo "  (CLAUDE.md, the budget smell). scripts/lane-guard.sh carries the mapping." >&2
      exit 1
    fi
  fi
fi

# THE LANE GUARD — refuse a test lane this change cannot reach (owner rule, 2026-08-24). CLAUDE.md carried
# this as prose, and the session that quoted the prose still ran `:cli:check`, the estate's heaviest lane
# (~281 s spawning packaged launchers four at a time, load 23 on 8 cores), against a scripts-only diff whose
# cli-side guards had already gone green minutes earlier. CI is exempt on purpose: it deliberately runs
# everything, and a guard that reddens CI is switched off within the week. `RK_LANE=all` overrides and SAYS
# so, so an override is visible in the log instead of silent. Mapping + rationale: scripts/lane-guard.sh.
if [[ -z "${CI:-}" && "${RK_LANE:-}" != "all" ]]; then
  . "$ROOT/scripts/lane-guard.sh"
  unjustified="$(a12_lane_refusals "$@")"
  if [[ -n "$unjustified" ]]; then
    echo "build.sh: REFUSING a lane this change cannot reach:" >&2
    printf '%s' "$unjustified" >&2
    echo "  changed: $(a12_changed_paths | head -4 | tr '\n' ' ')" >&2
    echo "  Run the lane the change reaches, or RK_LANE=all ./build.sh ... to override deliberately." >&2
    exit 1
  fi
elif [[ "${RK_LANE:-}" == "all" && -z "${CI:-}" ]]; then
  echo "build.sh: RK_LANE=all - lane guard overridden for this run" >&2
fi

exec "$ROOT/gradlew" "$@"
