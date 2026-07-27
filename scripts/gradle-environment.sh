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
