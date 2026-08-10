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
# JAVA_HOME may point at a newer JDK). Elsewhere (Linux/CI) leave JAVA_HOME to the caller.
if [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

exec "$ROOT/gradlew" "$@"
