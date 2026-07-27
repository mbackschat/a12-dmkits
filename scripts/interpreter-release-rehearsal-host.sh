#!/usr/bin/env bash

# Filesystem-backed release host for scripts/rehearse-interpreter-release.sh. It is reachable only when the
# publisher's explicit rehearsal switch is set; production always sources interpreter-release-github.sh.

: "${INTERPRETER_RELEASE_REHEARSAL_STATE:?rehearsal state directory is required}"
: "${INTERPRETER_RELEASE_REHEARSAL_MIRROR_REMOTE:?rehearsal mirror remote is required}"

release_rehearsal_state_file() {
  printf '%s/releases/%s/state' "$INTERPRETER_RELEASE_REHEARSAL_STATE" "$1"
}

release_host_require_access() {
  [[ "${INTERPRETER_RELEASE_REHEARSAL_ACCESS:-ok}" == "ok" ]] ||
    interpreter_release_fail "rehearsal release host access denied"
}

release_host_release_state() {
  local state_file
  state_file="$(release_rehearsal_state_file "$1")"
  if [[ -f "$state_file" ]]; then
    cat "$state_file"
  else
    echo missing
  fi
}

release_host_clone_mirror() {
  git clone --quiet "$INTERPRETER_RELEASE_REHEARSAL_MIRROR_REMOTE" "$1"
}

release_host_create_draft() {
  local tag="$1" title="$2" notes="$3" target="$4" release_directory
  release_directory="$INTERPRETER_RELEASE_REHEARSAL_STATE/releases/$tag"
  mkdir -p "$release_directory"
  echo draft > "$release_directory/state"
  printf '%s\n' "$title" > "$release_directory/title"
  cp "$notes" "$release_directory/notes.md"
  printf '%s\n' "$target" > "$release_directory/target"
}

release_host_dispatch_build() {
  local source_tag="$1" source_sha="$2" artifact_version="$3" release_tag="$4" source_assets="$5"
  local release_directory workflow_clone
  release_directory="$INTERPRETER_RELEASE_REHEARSAL_STATE/releases/$release_tag"
  [[ "$(cat "$release_directory/state")" == "draft" ]] ||
    interpreter_release_fail "rehearsal build requires a draft release"
  mkdir -p "$release_directory/assets"
  cp "$source_assets/"* "$release_directory/assets/"
  {
    printf 'source_tag=%s\n' "$source_tag"
    printf 'source_sha=%s\n' "$source_sha"
    printf 'artifact_version=%s\n' "$artifact_version"
  } > "$release_directory/build-inputs"

  workflow_clone="$(mktemp -d)"
  git clone --quiet "$INTERPRETER_RELEASE_REHEARSAL_MIRROR_REMOTE" "$workflow_clone"
  configure_release_git_identity "$workflow_clone"
  if [[ "${INTERPRETER_RELEASE_REHEARSAL_WRONG_WORKFLOW_IDENTITY:-0}" == "1" ]]; then
    git -C "$workflow_clone" config user.name "Wrong Publisher"
  fi
  interpreter_release_assemble_public_mirror \
    "$INTERPRETER_RELEASE_SOURCE_ROOT" \
    "$workflow_clone" \
    "$source_assets"
  git -C "$workflow_clone" add -A
  git -C "$workflow_clone" commit -m "workflow build $release_tag" >/dev/null
  git -C "$workflow_clone" push --quiet origin HEAD:main
  git -C "$workflow_clone" tag -f "$release_tag"
  git -C "$workflow_clone" push --quiet --force origin "refs/tags/$release_tag"
  git -C "$workflow_clone" rev-parse HEAD > "$release_directory/target"
  rm -rf "$workflow_clone"
  echo success > "$release_directory/build-state"
}

release_host_wait_for_build() {
  local build_state="$INTERPRETER_RELEASE_REHEARSAL_STATE/releases/$1/build-state"
  [[ -f "$build_state" && "$(cat "$build_state")" == "success" ]] ||
    interpreter_release_fail "rehearsal public build did not succeed"
}

release_host_download_assets() {
  cp "$INTERPRETER_RELEASE_REHEARSAL_STATE/releases/$1/assets/"* "$2/"
}

release_host_deploy_pages() {
  local clone
  clone="$(mktemp -d)"
  git clone --quiet "$INTERPRETER_RELEASE_REHEARSAL_MIRROR_REMOTE" "$clone"
  rm -rf "$INTERPRETER_RELEASE_REHEARSAL_STATE/pages"
  mkdir -p "$INTERPRETER_RELEASE_REHEARSAL_STATE/pages"
  cp -R "$clone/site/." "$INTERPRETER_RELEASE_REHEARSAL_STATE/pages/"
  rm -rf "$clone"
  if [[ "${INTERPRETER_RELEASE_REHEARSAL_BREAK_PAGES:-0}" == "1" ]]; then
    rm -f "$INTERPRETER_RELEASE_REHEARSAL_STATE/pages/showcase/index.html"
  fi
}

release_host_publish_nonlatest() {
  local release_directory="$INTERPRETER_RELEASE_REHEARSAL_STATE/releases/$1"
  [[ "$(cat "$release_directory/state")" == "draft" ]] ||
    interpreter_release_fail "rehearsal release is not a draft"
  echo published > "$release_directory/state"
  mkdir -p "$INTERPRETER_RELEASE_REHEARSAL_STATE/public-assets/$1"
  cp "$release_directory/assets/"* "$INTERPRETER_RELEASE_REHEARSAL_STATE/public-assets/$1/"
}

release_host_mark_latest() {
  printf '%s\n' "$1" > "$INTERPRETER_RELEASE_REHEARSAL_STATE/latest"
}

release_host_smoke_public_consumers() {
  local source_root="$1" work_directory="$2" maven_repository="$3" npm_tarball="$4" version="$5"
  local pom
  : "$source_root" "$work_directory" "$maven_repository"
  [[ "${INTERPRETER_RELEASE_REHEARSAL_BREAK_CONSUMER:-0}" != "1" ]] ||
    interpreter_release_fail "rehearsal anonymous consumer smoke failed"
  [[ -s "$npm_tarball" ]] ||
    interpreter_release_fail "rehearsal anonymous npm artifact is missing"
  pom="$INTERPRETER_RELEASE_REHEARSAL_STATE/pages/maven/io/github/mbackschat/a12/dm/dm-interpreter/$version/dm-interpreter-$version.pom"
  [[ -s "$pom" ]] ||
    interpreter_release_fail "rehearsal anonymous Maven artifact is missing"
  printf '%s\n' passed > "$INTERPRETER_RELEASE_REHEARSAL_STATE/public-consumer-smoke"
}
