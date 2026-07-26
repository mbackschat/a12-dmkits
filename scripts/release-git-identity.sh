#!/usr/bin/env bash

readonly RK_RELEASE_GIT_NAME="Martin Backschat"
readonly RK_RELEASE_GIT_EMAIL="mbackschat@mail.de"

configure_release_git_identity() {
  local repository="${1:?repository path is required}"
  git -C "$repository" config user.name "$RK_RELEASE_GIT_NAME"
  git -C "$repository" config user.email "$RK_RELEASE_GIT_EMAIL"
}
