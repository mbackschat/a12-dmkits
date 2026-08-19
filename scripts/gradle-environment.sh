#!/usr/bin/env bash

# Resolve the init.d-free Gradle user home shared by the primary checkout and its linked worktrees.
a12_gradle_user_home() {
  local repository_root="${1:?repository root is required}"
  if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
    printf '%s\n' "$GRADLE_USER_HOME"
    return
  fi

  local common_git_directory common_checkout
  common_git_directory="$(git -C "$repository_root" rev-parse --path-format=absolute --git-common-dir)"
  common_checkout="$(cd "$common_git_directory/.." && pwd)"
  printf '%s/.gradle-isolated\n' "$(cd "$common_checkout/.." && pwd)"
}

# Resolve the JDK 21 home this project pins for BOTH its Gradle build and any launcher it verifies, or print
# nothing where the platform offers no pin (Linux/CI leave JAVA_HOME to the caller). Single-sourced because
# the build pinning a JDK while a post-build launcher check keeps the caller's is exactly how a stale
# JAVA_HOME was reported as a source-provenance mismatch (dmtool feedback 2026-08-19). A present but broken
# `java_home -v 21` stays a failure here; each caller decides whether that is fatal.
a12_pinned_jdk21_home() {
  [[ -x /usr/libexec/java_home ]] || return 0
  /usr/libexec/java_home -v 21
}
