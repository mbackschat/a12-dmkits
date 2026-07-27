#!/usr/bin/env bash

RELEASE_HOST_REPOSITORY="${INTERPRETER_RELEASE_REPOSITORY:-mbackschat/a12-interpreter-releases}"

release_host_require_access() {
  gh auth status >/dev/null 2>&1 ||
    interpreter_release_fail "gh is not authenticated"
  gh repo view "$RELEASE_HOST_REPOSITORY" >/dev/null 2>&1 ||
    interpreter_release_fail "cannot reach public repository $RELEASE_HOST_REPOSITORY"
}

release_host_release_state() {
  local tag="$1" draft
  draft="$(gh release view "$tag" --repo "$RELEASE_HOST_REPOSITORY" --json isDraft -q .isDraft 2>/dev/null || true)"
  case "$draft" in
    true) echo draft ;;
    false) echo published ;;
    *) echo missing ;;
  esac
}

release_host_clone_mirror() {
  gh repo clone "$RELEASE_HOST_REPOSITORY" "$1" -- --depth 1
}

release_host_create_draft() {
  local tag="$1" title="$2" notes="$3" target="$4"
  local prerelease=()
  case "$tag" in
    *-alpha.*|*-beta.*|*-rc.*) prerelease=(--prerelease) ;;
  esac
  gh release create "$tag" \
    --repo "$RELEASE_HOST_REPOSITORY" \
    --target "$target" \
    --draft \
    "${prerelease[@]}" \
    --title "$title" \
    --notes-file "$notes"
}

release_host_dispatch_build() {
  local source_tag="$1" source_sha="$2" artifact_version="$3" release_tag="$4"
  gh workflow run build-release.yml \
    --repo "$RELEASE_HOST_REPOSITORY" \
    -f source_ref="$source_tag" \
    -f source_sha="$source_sha" \
    -f artifact_version="$artifact_version" \
    -f release_tag="$release_tag"
}

release_host_run_for_title() {
  local workflow="$1" title="$2"
  gh run list \
    --repo "$RELEASE_HOST_REPOSITORY" \
    --workflow "$workflow" \
    --limit 20 \
    --json databaseId,displayTitle \
    --jq "first(.[] | select(.displayTitle == \"$title\") | .databaseId) // empty"
}

release_host_wait_for_build() {
  local run_id
  run_id="$(release_host_run_for_title build-release.yml "Build release $1")"
  [[ -n "$run_id" ]] ||
    interpreter_release_fail "no public build-release workflow run exists for $1"
  gh run watch "$run_id" --repo "$RELEASE_HOST_REPOSITORY" --exit-status
}

release_host_download_assets() {
  gh release download "$1" \
    --repo "$RELEASE_HOST_REPOSITORY" \
    --dir "$2"
}

release_host_ensure_pages() {
  local endpoint build_type
  endpoint="repos/$RELEASE_HOST_REPOSITORY/pages"
  if build_type="$(gh api "$endpoint" --jq '.build_type // empty' 2>/dev/null)"; then
    [[ "$build_type" == "workflow" ]] ||
      gh api --method PUT "$endpoint" -f build_type=workflow >/dev/null
  else
    # A new public mirror has no Pages site until it is explicitly created. Configure that repository
    # boundary before dispatch so the deployment cannot fail later inside actions/configure-pages.
    gh api --method POST "$endpoint" -f build_type=workflow >/dev/null
  fi
}

release_host_deploy_pages() {
  local tag="$1" previous run_id attempts
  release_host_ensure_pages
  previous="$(release_host_run_for_title deploy-pages.yml "Deploy Pages for $tag" || true)"
  gh workflow run deploy-pages.yml \
    --repo "$RELEASE_HOST_REPOSITORY" \
    -f release_tag="$tag"
  run_id=""
  attempts=0
  while [[ -z "$run_id" || "$run_id" == "$previous" ]]; do
    attempts=$((attempts + 1))
    [[ "$attempts" -le 30 ]] ||
      interpreter_release_fail "deploy-pages workflow did not start"
    sleep 2
    run_id="$(release_host_run_for_title deploy-pages.yml "Deploy Pages for $tag" || true)"
  done
  gh run watch "$run_id" --repo "$RELEASE_HOST_REPOSITORY" --exit-status
}

release_host_publish_nonlatest() {
  gh release edit "$1" \
    --repo "$RELEASE_HOST_REPOSITORY" \
    --draft=false \
    --latest=false
}

release_host_mark_latest() {
  gh release edit "$1" \
    --repo "$RELEASE_HOST_REPOSITORY" \
    --latest
}

release_host_smoke_public_consumers() {
  local source_root="$1" work_directory="$2" maven_repository="$3" npm_tarball="$4" version="$5"
  "$source_root/scripts/smoke-interpreter-public-release.sh" \
    --version "$version" \
    --maven-repository "$maven_repository" \
    --npm-tarball "$npm_tarball" \
    --work-directory "$work_directory"
}
