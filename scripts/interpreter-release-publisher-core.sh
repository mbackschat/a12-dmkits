#!/usr/bin/env bash

# Product-neutral orchestration lives here; GitHub operations are supplied by a small host backend. The
# disposable R7.2 rehearsal uses the same functions with local Git and filesystem-backed release state.

interpreter_release_fail() {
  echo "interpreter release refused: $*" >&2
  return 1
}

interpreter_release_version() {
  sed -nE "s/^version = '([^']+)'/\1/p" "$1/build.gradle"
}

interpreter_release_parse() {
  [[ $# -ge 1 ]] || {
    echo "usage: publish-interpreter-release.sh interpreter-vX.Y.Z [--release-tag vX.Y.Z] [--qualify|--go-live]" >&2
    return 2
  }
  RELEASE_SOURCE_TAG="$1"
  shift
  RELEASE_TAG=""
  RELEASE_QUALIFY=0
  RELEASE_GO_LIVE=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --release-tag)
        RELEASE_TAG="${2:?missing --release-tag value}"
        shift 2
        ;;
      --qualify)
        RELEASE_QUALIFY=1
        shift
        ;;
      --go-live)
        RELEASE_GO_LIVE=1
        shift
        ;;
      *)
        echo "unknown interpreter publisher argument: $1" >&2
        return 2
        ;;
    esac
  done

  [[ "$RELEASE_SOURCE_TAG" =~ ^interpreter-v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] ||
    interpreter_release_fail "source tag must be interpreter-vMAJOR.MINOR.PATCH with an optional suffix"
  RELEASE_SOURCE_VERSION="${RELEASE_SOURCE_TAG#interpreter-v}"
  RELEASE_TAG="${RELEASE_TAG:-v$RELEASE_SOURCE_VERSION}"
  [[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] ||
    interpreter_release_fail "release tag must be vMAJOR.MINOR.PATCH with an optional suffix"
  [[ "$RELEASE_QUALIFY" != "1" || "$RELEASE_GO_LIVE" != "1" ]] ||
    interpreter_release_fail "--qualify and --go-live are mutually exclusive"
}

interpreter_release_source_preflight() {
  local source_root="$1" require_clean_head="$2"
  local version local_sha remote_sha
  version="$(interpreter_release_version "$source_root")"
  [[ -n "$version" ]] || interpreter_release_fail "root build.gradle has no repository version"
  [[ "$version" == "$RELEASE_SOURCE_VERSION" ]] ||
    interpreter_release_fail "source tag/version mismatch: $RELEASE_SOURCE_TAG names $RELEASE_SOURCE_VERSION but build.gradle is $version"
  RELEASE_ARTIFACT_VERSION="$version"

  git -C "$source_root" rev-parse "$RELEASE_SOURCE_TAG^{}" >/dev/null 2>&1 ||
    interpreter_release_fail "source tag does not exist locally: $RELEASE_SOURCE_TAG"
  local_sha="$(git -C "$source_root" rev-parse "$RELEASE_SOURCE_TAG^{}")"
  remote_sha="$(git -C "$source_root" ls-remote origin "refs/tags/$RELEASE_SOURCE_TAG^{}" 2>/dev/null | awk '{print $1}')"
  [[ -n "$remote_sha" ]] ||
    remote_sha="$(git -C "$source_root" ls-remote origin "refs/tags/$RELEASE_SOURCE_TAG" 2>/dev/null | awk '{print $1}')"
  [[ -n "$remote_sha" ]] ||
    interpreter_release_fail "source tag is not present on origin: $RELEASE_SOURCE_TAG"
  [[ "$local_sha" == "$remote_sha" ]] ||
    interpreter_release_fail "source tag/SHA mismatch: local $local_sha, origin $remote_sha"
  RELEASE_SOURCE_SHA="$local_sha"

  if [[ "$require_clean_head" == "true" ]]; then
    [[ -z "$(git -C "$source_root" status --porcelain)" ]] ||
      interpreter_release_fail "source working tree is dirty"
    [[ "$(git -C "$source_root" rev-parse HEAD)" == "$local_sha" ]] ||
      interpreter_release_fail "source HEAD must equal the immutable source tag before staging"
  fi
}

interpreter_release_expected_identity() {
  printf '%s <%s>' "$RK_RELEASE_GIT_NAME" "$RK_RELEASE_GIT_EMAIL"
}

interpreter_release_assert_configured_identity() {
  local repository="$1" expected author committer
  expected="$(interpreter_release_expected_identity)"
  author="$(git -C "$repository" var GIT_AUTHOR_IDENT | sed -E 's/ [0-9]+ [+-][0-9]{4}$//')"
  committer="$(git -C "$repository" var GIT_COMMITTER_IDENT | sed -E 's/ [0-9]+ [+-][0-9]{4}$//')"
  [[ "$author" == "$expected" ]] ||
    interpreter_release_fail "wrong author identity: $author (expected $expected)"
  [[ "$committer" == "$expected" ]] ||
    interpreter_release_fail "wrong committer identity: $committer (expected $expected)"
}

interpreter_release_verify_commit_identities() {
  local repository="$1" expected identity
  expected="$(interpreter_release_expected_identity)|$(interpreter_release_expected_identity)"
  if ! git -C "$repository" rev-parse --verify HEAD >/dev/null 2>&1; then
    return
  fi
  while IFS= read -r identity; do
    [[ "$identity" == "$expected" ]] ||
      interpreter_release_fail "wrong public commit identity: $identity (expected $expected)"
  done < <(git -C "$repository" log --all --format='%an <%ae>|%cn <%ce>')
}

interpreter_release_refuse_existing_version() {
  local mirror="$1" version="$2"
  [[ ! -e "$mirror/site/api/v$version" ]] ||
    interpreter_release_fail "API version v$version already exists — published versions are immutable"
  if [[ -d "$mirror/site/maven" ]] &&
    find "$mirror/site/maven" -type d -name "$version" -print -quit | grep -q .; then
    interpreter_release_fail "Maven version $version already exists — published versions are immutable"
  fi
}

interpreter_release_verify_payload() {
  local assets="$1" version="$2" release_tag="$3" source_tag="$4" source_sha="$5"
  local expected actual npm_asset maven_asset api_asset showcase_asset entry npm_entries api_entries maven_entries showcase_entries
  npm_asset="a12-interpreter-$version.tgz"
  maven_asset="dm-interpreter-$version-maven-repository.zip"
  api_asset="dm-interpreter-$version-api-docs.zip"
  showcase_asset="dm-interpreter-$version-showcase.zip"

  expected="$(mktemp)"
  actual="$(mktemp)"
  printf '%s\n' \
    "$npm_asset" \
    "$maven_asset" \
    "$api_asset" \
    "$showcase_asset" \
    release-manifest.json \
    SHA256SUMS | LC_ALL=C sort > "$expected"
  find "$assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | LC_ALL=C sort > "$actual"
  if ! diff -u "$expected" "$actual" >/dev/null; then
    diff -u "$expected" "$actual" >&2 || true
    rm -f "$expected" "$actual"
    interpreter_release_fail "release payload does not match the exact asset inventory"
  fi
  rm -f "$expected" "$actual"

  (cd "$assets" && shasum -a 256 -c SHA256SUMS >/dev/null) ||
    interpreter_release_fail "release payload checksum verification failed"
  grep -Fq "\"artifactVersion\": \"$version\"" "$assets/release-manifest.json" ||
    interpreter_release_fail "release manifest artifact version mismatch"
  grep -Fq "\"releaseTag\": \"$release_tag\"" "$assets/release-manifest.json" ||
    interpreter_release_fail "release manifest tag mismatch"
  grep -Fq "\"tag\": \"$source_tag\"" "$assets/release-manifest.json" ||
    interpreter_release_fail "release manifest source tag mismatch"
  grep -Fq "\"sha\": \"$source_sha\"" "$assets/release-manifest.json" ||
    interpreter_release_fail "release manifest source SHA mismatch"

  npm_entries="$(tar -tzf "$assets/$npm_asset")"
  grep -qx 'package/dist/index.d.ts' <<< "$npm_entries" ||
    interpreter_release_fail "npm package is missing package/dist/index.d.ts"
  entry="$(grep -x 'package/dist/index.js' <<< "$npm_entries" || true)"
  [[ -n "$entry" ]] ||
    interpreter_release_fail "npm package is missing package/dist/index.js"
  [[ -n "$(tar -xOzf "$assets/$npm_asset" package/dist/index.js)" ]] ||
    interpreter_release_fail "npm package JavaScript entry is empty"

  api_entries="$(unzip -Z1 "$assets/$api_asset")"
  grep -qx "api/v$version/index.html" <<< "$api_entries" ||
    interpreter_release_fail "API documentation is missing its landing page"
  grep -qx "api/v$version/INTERPRETER-API-GUIDE.md" <<< "$api_entries" ||
    interpreter_release_fail "API documentation is missing the developer guide"
  maven_entries="$(unzip -Z1 "$assets/$maven_asset")"
  grep -qx "maven/io/github/mbackschat/a12/dm/dm-interpreter/$version/dm-interpreter-$version.pom" <<< "$maven_entries" ||
    interpreter_release_fail "Maven archive is missing the JVM POM"
  showcase_entries="$(unzip -Z1 "$assets/$showcase_asset")"
  grep -qx 'showcase/index.html' <<< "$showcase_entries" ||
    interpreter_release_fail "showcase archive is missing index.html"
}

interpreter_release_assemble_public_mirror() {
  local source_root="$1" mirror="$2" assets="$3"
  "$source_root/scripts/assemble-interpreter-release-repo.sh" \
    --destination "$mirror" \
    --assets-directory "$assets" \
    --version "$RELEASE_ARTIFACT_VERSION" \
    --release-tag "$RELEASE_TAG" \
    --source-tag "$RELEASE_SOURCE_TAG" \
    --source-sha "$RELEASE_SOURCE_SHA" \
    --npm-tarball "$source_root/build/interpreter-release/npm/a12-interpreter-$RELEASE_ARTIFACT_VERSION.tgz" \
    --maven-repository "$source_root/build/interpreter-release/maven" \
    --api-documentation "$source_root/build/interpreter-release/api-docs/stage/api/v$RELEASE_ARTIFACT_VERSION" \
    --api-docs-archive "$source_root/build/interpreter-release/api-docs/dm-interpreter-$RELEASE_ARTIFACT_VERSION-api-docs.zip" \
    --showcase "$source_root/build/interpreter-release/consumer/browser/dist" \
    --readme "$source_root/interpreter/public-release/README.md" \
    --changelog "$source_root/interpreter/CHANGELOG.md" \
    --license "$source_root/LICENSE" \
    --disclaimer "$source_root/interpreter/public-release/DISCLAIMER.md" \
    --privacy "$source_root/interpreter/public-release/privacy.html" \
    --landing-page "$source_root/interpreter/public-release/index.html" \
    --maven-landing-page "$source_root/interpreter/public-release/maven.html" \
    --workflow-directory "$source_root/.github/interpreter-mirror-workflows"
}

interpreter_release_write_qualification_receipt() {
  local receipt="$1" preview="$2" assets="$3" temporary
  temporary="$receipt.tmp.$$"
  {
    printf 'source_tag=%s\n' "$RELEASE_SOURCE_TAG"
    printf 'source_sha=%s\n' "$RELEASE_SOURCE_SHA"
    printf 'artifact_version=%s\n' "$RELEASE_ARTIFACT_VERSION"
    printf 'release_tag=%s\n' "$RELEASE_TAG"
    printf 'public_inventory_sha256=%s\n' "$(release_sha256 "$preview/PUBLIC-FILES.sha256")"
    printf 'asset_checksums_sha256=%s\n' "$(release_sha256 "$assets/SHA256SUMS")"
  } > "$temporary"
  mv "$temporary" "$receipt"
}

interpreter_release_verify_qualified_outputs() {
  local source_root="$1" work_directory="$2" preview="$3" assets="$4" receipt expected
  receipt="$work_directory/qualified-release.txt"
  [[ -f "$receipt" ]] ||
    interpreter_release_fail "no qualified release receipt exists — run the publisher with --qualify first"

  release_verify_public_inventory "$preview"
  "$source_root/scripts/check-interpreter-release-repo.sh" "$preview" >/dev/null
  interpreter_release_verify_payload \
    "$assets" \
    "$RELEASE_ARTIFACT_VERSION" \
    "$RELEASE_TAG" \
    "$RELEASE_SOURCE_TAG" \
    "$RELEASE_SOURCE_SHA"

  expected="$(mktemp)"
  interpreter_release_write_qualification_receipt "$expected" "$preview" "$assets"
  if ! cmp -s "$receipt" "$expected"; then
    rm -f "$expected"
    interpreter_release_fail "qualified release receipt does not match the current source or outputs"
  fi
  rm -f "$expected"
}

interpreter_release_qualify() {
  local source_root="$1" work_directory="$2" preview assets receipt
  interpreter_release_source_preflight "$source_root" true
  preview="$source_root/build/interpreter-release/public-mirror"
  assets="$source_root/build/interpreter-release/public-assets"
  receipt="$work_directory/qualified-release.txt"
  release_require_safe_derived_directory "$preview"
  release_require_safe_derived_directory "$assets"
  rm -f "$receipt"

  release_verify_public_inventory "$preview"
  "$source_root/scripts/check-interpreter-release-repo.sh" "$preview" >/dev/null
  interpreter_release_verify_payload \
    "$assets" \
    "$RELEASE_ARTIFACT_VERSION" \
    "$RELEASE_TAG" \
    "$RELEASE_SOURCE_TAG" \
    "$RELEASE_SOURCE_SHA"
  interpreter_release_write_qualification_receipt "$receipt" "$preview" "$assets"
  echo "QUALIFIED: $RELEASE_TAG at $RELEASE_SOURCE_SHA"
}

interpreter_release_stage() {
  local source_root="$1" work_directory="$2" clone preview assets release_state notes commit_sha unexpected
  interpreter_release_source_preflight "$source_root" true
  release_host_require_access
  release_state="$(release_host_release_state "$RELEASE_TAG")"
  [[ "$release_state" != "published" ]] ||
    interpreter_release_fail "release $RELEASE_TAG is already published and immutable"

  clone="$work_directory/public-mirror"
  preview="$source_root/build/interpreter-release/public-mirror"
  assets="$source_root/build/interpreter-release/public-assets"
  release_require_safe_derived_directory "$clone"
  release_require_safe_derived_directory "$preview"
  release_require_safe_derived_directory "$assets"
  rm -rf "$clone"
  mkdir -p "$work_directory"
  interpreter_release_verify_qualified_outputs "$source_root" "$work_directory" "$preview" "$assets"
  release_host_clone_mirror "$clone"
  interpreter_release_verify_commit_identities "$clone"
  interpreter_release_refuse_existing_version "$clone" "$RELEASE_ARTIFACT_VERSION"

  if [[ -f "$clone/PUBLIC-FILES.sha256" ]]; then
    release_verify_public_inventory "$clone"
  else
    unexpected="$(find "$clone" -path "$clone/.git" -prune -o -type f -print -quit)"
    [[ -z "$unexpected" ]] ||
      interpreter_release_fail "fresh public mirror has content but no exact inventory: ${unexpected#"$clone/"}"
  fi
  mkdir -p "$clone/.github/workflows"
  cp "$source_root/.github/interpreter-mirror-workflows/build-release.yml" \
    "$clone/.github/workflows/build-release.yml"
  cp "$source_root/.github/interpreter-mirror-workflows/deploy-pages.yml" \
    "$clone/.github/workflows/deploy-pages.yml"
  release_scan_public_tree "$clone"
  release_write_public_inventory "$clone"

  configure_release_git_identity "$clone"
  interpreter_release_assert_configured_identity "$clone"
  git -C "$clone" add -A
  if [[ -n "$(git -C "$clone" status --porcelain)" ]]; then
    git -C "$clone" commit -m "release $RELEASE_TAG"
  fi
  interpreter_release_verify_commit_identities "$clone"
  git -C "$clone" push origin HEAD:main
  commit_sha="$(git -C "$clone" rev-parse HEAD)"

  notes="$work_directory/release-notes.md"
  cp "$preview/CHANGELOG.md" "$notes"
  if [[ "$release_state" == "missing" ]]; then
    release_host_create_draft "$RELEASE_TAG" "A12 Interpreter $RELEASE_TAG" "$notes" "$commit_sha"
  fi
  release_host_dispatch_build \
    "$RELEASE_SOURCE_TAG" \
    "$RELEASE_SOURCE_SHA" \
    "$RELEASE_ARTIFACT_VERSION" \
    "$RELEASE_TAG" \
    "$assets"
  echo "STAGED: $RELEASE_TAG remains a draft until the public build and review pass."
}

interpreter_release_smoke_pages() {
  local base="$1" version="$2" target
  for target in \
    index.html \
    showcase/index.html \
    "api/v$version/index.html" \
    "api/v$version/INTERPRETER-API-GUIDE.md" \
    "api/v$version/typescript/index.html" \
    "api/v$version/kotlin/index.html" \
    "maven/io/github/mbackschat/a12/dm/dm-interpreter/$version/dm-interpreter-$version.pom"; do
    curl --fail --location --silent --show-error "$base/$target" >/dev/null ||
      interpreter_release_fail "production URL smoke failed: $base/$target"
  done
}

interpreter_release_download_public_assets() {
  local base="$1" version="$2" target="$3" name
  mkdir -p "$target"
  while IFS= read -r name; do
    curl --fail --location --silent --show-error "$base/$name" --output "$target/$name" ||
      interpreter_release_fail "anonymous release asset smoke failed: $base/$name"
  done < <(
    interpreter_release_asset_names "$version"
    printf '%s\n' release-manifest.json SHA256SUMS
  )
}

interpreter_release_go_live() {
  local source_root="$1" work_directory="$2" release_state draft_assets clone public_assets
  interpreter_release_source_preflight "$source_root" true
  release_host_require_access
  release_state="$(release_host_release_state "$RELEASE_TAG")"
  [[ "$release_state" == "draft" || "$release_state" == "published" ]] ||
    interpreter_release_fail "release $RELEASE_TAG must exist before go-live"

  release_host_wait_for_build "$RELEASE_TAG"
  draft_assets="$work_directory/draft-assets"
  clone="$work_directory/review-mirror"
  rm -rf "$draft_assets" "$clone"
  mkdir -p "$draft_assets"
  release_host_download_assets "$RELEASE_TAG" "$draft_assets"
  interpreter_release_verify_payload \
    "$draft_assets" \
    "$RELEASE_ARTIFACT_VERSION" \
    "$RELEASE_TAG" \
    "$RELEASE_SOURCE_TAG" \
    "$RELEASE_SOURCE_SHA"

  release_host_clone_mirror "$clone"
  interpreter_release_verify_commit_identities "$clone"
  [[ "$(git -C "$clone" rev-parse "$RELEASE_TAG^{}")" == "$(git -C "$clone" rev-parse HEAD)" ]] ||
    interpreter_release_fail "public release tag does not point at the rebuilt mirror commit"
  [[ -d "$clone/site/api/v$RELEASE_ARTIFACT_VERSION" ]] ||
    interpreter_release_fail "public mirror is missing immutable API version v$RELEASE_ARTIFACT_VERSION"
  find "$clone/site/maven" -type d -name "$RELEASE_ARTIFACT_VERSION" -print -quit | grep -q . ||
    interpreter_release_fail "public mirror is missing immutable Maven version $RELEASE_ARTIFACT_VERSION"

  if [[ "$release_state" == "draft" ]]; then
    release_host_deploy_pages "$RELEASE_TAG"
    interpreter_release_smoke_pages "$INTERPRETER_RELEASE_PAGES_BASE" "$RELEASE_ARTIFACT_VERSION"
    release_host_publish_nonlatest "$RELEASE_TAG"
  else
    # GitHub cannot expose release assets anonymously while a release is a draft. A failure in the
    # post-publication consumer smoke therefore leaves a public, non-latest release. Re-entering go-live
    # must resume from that narrow state instead of making a transient consumer failure unrecoverable.
    interpreter_release_smoke_pages "$INTERPRETER_RELEASE_PAGES_BASE" "$RELEASE_ARTIFACT_VERSION"
  fi

  public_assets="$work_directory/public-assets"
  rm -rf "$public_assets"
  interpreter_release_download_public_assets \
    "$INTERPRETER_RELEASE_DOWNLOAD_BASE/$RELEASE_TAG" \
    "$RELEASE_ARTIFACT_VERSION" \
    "$public_assets"
  interpreter_release_verify_payload \
    "$public_assets" \
    "$RELEASE_ARTIFACT_VERSION" \
    "$RELEASE_TAG" \
    "$RELEASE_SOURCE_TAG" \
    "$RELEASE_SOURCE_SHA"
  release_host_smoke_public_consumers \
    "$source_root" \
    "$work_directory/public-consumer-smoke" \
    "$INTERPRETER_RELEASE_PAGES_BASE/maven" \
    "$public_assets/a12-interpreter-$RELEASE_ARTIFACT_VERSION.tgz" \
    "$RELEASE_ARTIFACT_VERSION"

  case "$RELEASE_TAG" in
    *-alpha.*|*-beta.*|*-rc.*)
      echo "PUBLISHED prerelease: $RELEASE_TAG (not marked latest)"
      ;;
    *)
      release_host_mark_latest "$RELEASE_TAG"
      echo "PUBLISHED latest release: $RELEASE_TAG"
      ;;
  esac
}

publish_interpreter_release() {
  interpreter_release_parse "$@"
  local source_root work_directory
  source_root="${INTERPRETER_RELEASE_SOURCE_ROOT:-$ROOT}"
  work_directory="${INTERPRETER_RELEASE_WORK_DIRECTORY:-$source_root/build/interpreter-release/publisher}"
  INTERPRETER_RELEASE_PAGES_BASE="${INTERPRETER_RELEASE_PAGES_BASE:-https://mbackschat.github.io/a12-interpreter-releases}"
  INTERPRETER_RELEASE_DOWNLOAD_BASE="${INTERPRETER_RELEASE_DOWNLOAD_BASE:-https://github.com/mbackschat/a12-interpreter-releases/releases/download}"
  "$source_root/scripts/check-release-gaps.sh" "$source_root/docs"
  release_require_safe_derived_directory "$work_directory"
  mkdir -p "$work_directory"

  case "$RELEASE_QUALIFY:$RELEASE_GO_LIVE" in
    1:0) interpreter_release_qualify "$source_root" "$work_directory" ;;
    0:1) interpreter_release_go_live "$source_root" "$work_directory" ;;
    0:0) interpreter_release_stage "$source_root" "$work_directory" ;;
    *) interpreter_release_fail "invalid publisher operation" ;;
  esac
}
