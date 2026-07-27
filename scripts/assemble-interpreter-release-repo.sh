#!/usr/bin/env bash
# Assemble the cumulative PUBLIC a12-interpreter-releases tree and its immutable release payload.
#
# This deliberately does not reuse dmtool's latest-only source tree: the interpreter must carry every
# published Maven and API version into a validated staging tree before synchronizing the generated mirror.
# It does reuse the dmtool release protocol, Git identity boundary, checksum format, generated-mirror
# discipline, draft/go-live split, and anonymous-verification model.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=interpreter-release-assets.sh
. "$ROOT/scripts/interpreter-release-assets.sh"
# shellcheck source=release-mirror-common.sh
. "$ROOT/scripts/release-mirror-common.sh"

DESTINATION=""
ASSETS_DIRECTORY=""
VERSION=""
RELEASE_TAG=""
SOURCE_TAG=""
SOURCE_SHA=""
NPM_TARBALL=""
MAVEN_REPOSITORY=""
API_DOCUMENTATION=""
API_DOCS_ARCHIVE=""
SHOWCASE=""
README_SOURCE=""
CHANGELOG_SOURCE=""
LICENSE_SOURCE=""
DISCLAIMER_SOURCE=""
PRIVACY_SOURCE=""
LANDING_PAGE_SOURCE=""
WORKFLOW_DIRECTORY=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --destination) DESTINATION="${2:?missing --destination value}"; shift 2 ;;
    --assets-directory) ASSETS_DIRECTORY="${2:?missing --assets-directory value}"; shift 2 ;;
    --version) VERSION="${2:?missing --version value}"; shift 2 ;;
    --release-tag) RELEASE_TAG="${2:?missing --release-tag value}"; shift 2 ;;
    --source-tag) SOURCE_TAG="${2:?missing --source-tag value}"; shift 2 ;;
    --source-sha) SOURCE_SHA="${2:?missing --source-sha value}"; shift 2 ;;
    --npm-tarball) NPM_TARBALL="${2:?missing --npm-tarball value}"; shift 2 ;;
    --maven-repository) MAVEN_REPOSITORY="${2:?missing --maven-repository value}"; shift 2 ;;
    --api-documentation) API_DOCUMENTATION="${2:?missing --api-documentation value}"; shift 2 ;;
    --api-docs-archive) API_DOCS_ARCHIVE="${2:?missing --api-docs-archive value}"; shift 2 ;;
    --showcase) SHOWCASE="${2:?missing --showcase value}"; shift 2 ;;
    --readme) README_SOURCE="${2:?missing --readme value}"; shift 2 ;;
    --changelog) CHANGELOG_SOURCE="${2:?missing --changelog value}"; shift 2 ;;
    --license) LICENSE_SOURCE="${2:?missing --license value}"; shift 2 ;;
    --disclaimer) DISCLAIMER_SOURCE="${2:?missing --disclaimer value}"; shift 2 ;;
    --privacy) PRIVACY_SOURCE="${2:?missing --privacy value}"; shift 2 ;;
    --landing-page) LANDING_PAGE_SOURCE="${2:?missing --landing-page value}"; shift 2 ;;
    --workflow-directory) WORKFLOW_DIRECTORY="${2:?missing --workflow-directory value}"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
  echo "version must be SemVer-compatible: $VERSION" >&2; exit 2
}
RELEASE_TAG="${RELEASE_TAG:-v$VERSION}"
[[ "$RELEASE_TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
  echo "release tag must be vMAJOR.MINOR.PATCH with an optional suffix: $RELEASE_TAG" >&2; exit 2
}
[[ "$SOURCE_TAG" =~ ^interpreter-v[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
  echo "source tag must be interpreter-vMAJOR.MINOR.PATCH with an optional suffix: $SOURCE_TAG" >&2; exit 2
}
[[ "$SOURCE_SHA" =~ ^[0-9a-f]{40,64}$ ]] || {
  echo "source SHA must be 40–64 lowercase hexadecimal characters" >&2; exit 2
}

for file in \
  "$NPM_TARBALL" \
  "$API_DOCS_ARCHIVE" \
  "$README_SOURCE" \
  "$CHANGELOG_SOURCE" \
  "$LICENSE_SOURCE" \
  "$DISCLAIMER_SOURCE" \
  "$PRIVACY_SOURCE" \
  "$LANDING_PAGE_SOURCE"; do
  [[ -f "$file" ]] || { echo "required release input not found: $file" >&2; exit 2; }
done
for directory in "$MAVEN_REPOSITORY" "$API_DOCUMENTATION" "$SHOWCASE" "$WORKFLOW_DIRECTORY"; do
  [[ -d "$directory" ]] || { echo "required release input directory not found: $directory" >&2; exit 2; }
done
for workflow in build-release.yml deploy-pages.yml; do
  [[ -f "$WORKFLOW_DIRECTORY/$workflow" ]] || {
    echo "required public workflow not found: $WORKFLOW_DIRECTORY/$workflow" >&2
    exit 2
  }
done
grep -q "$VERSION\\|@VERSION@" "$README_SOURCE" || {
  echo "public README does not carry the release version or placeholder" >&2; exit 1
}
grep -q "$VERSION\\|@VERSION@" "$CHANGELOG_SOURCE" || {
  echo "interpreter changelog does not carry the release version or placeholder" >&2; exit 1
}

release_require_safe_derived_directory "$DESTINATION"
release_require_safe_derived_directory "$ASSETS_DIRECTORY"
mkdir -p "$DESTINATION"

if [[ -f "$DESTINATION/PUBLIC-FILES.sha256" ]]; then
  release_verify_public_inventory "$DESTINATION"
else
  unexpected="$(
    find "$DESTINATION" -path "$DESTINATION/.git" -prune -o -type f -print -quit
  )"
  [[ -z "$unexpected" ]] || {
    echo "fresh mirror has files but no public file inventory: ${unexpected#"$DESTINATION/"}" >&2
    exit 1
  }
fi

ASSEMBLY_STAGE="$(mktemp -d)"
trap 'rm -rf "$ASSEMBLY_STAGE"' EXIT
MIRROR_STAGE="$ASSEMBLY_STAGE/mirror"
ASSET_STAGE="$ASSEMBLY_STAGE/assets"
ARCHIVE_STAGE="$ASSEMBLY_STAGE/archive"
mkdir -p "$MIRROR_STAGE" "$ASSET_STAGE" "$ARCHIVE_STAGE"
rsync -a --exclude='.git/' "$DESTINATION/" "$MIRROR_STAGE/"

API_VERSION_DIRECTORY="$MIRROR_STAGE/site/api/v$VERSION"
if [[ -e "$API_VERSION_DIRECTORY" ]]; then
  echo "API version v$VERSION already exists — published versions are immutable" >&2
  exit 1
fi
if [[ -d "$MIRROR_STAGE/site/maven" ]] &&
  find "$MIRROR_STAGE/site/maven" -type d -name "$VERSION" -print -quit | grep -q .; then
  echo "Maven version $VERSION already exists — published versions are immutable" >&2
  exit 1
fi

render_versioned_source() {
  local source="$1" target="$2"
  mkdir -p "$(dirname "$target")"
  sed "s/@VERSION@/$VERSION/g; s/@RELEASE_TAG@/$RELEASE_TAG/g; s/@SOURCE_TAG@/$SOURCE_TAG/g" \
    "$source" > "$target"
}

mkdir -p "$MIRROR_STAGE/.github/workflows" "$MIRROR_STAGE/site/api" "$MIRROR_STAGE/site/maven"
render_versioned_source "$README_SOURCE" "$MIRROR_STAGE/README.md"
render_versioned_source "$CHANGELOG_SOURCE" "$MIRROR_STAGE/CHANGELOG.md"
cp "$LICENSE_SOURCE" "$MIRROR_STAGE/LICENSE"
cp "$DISCLAIMER_SOURCE" "$MIRROR_STAGE/DISCLAIMER.md"
cp "$WORKFLOW_DIRECTORY/build-release.yml" "$MIRROR_STAGE/.github/workflows/build-release.yml"
cp "$WORKFLOW_DIRECTORY/deploy-pages.yml" "$MIRROR_STAGE/.github/workflows/deploy-pages.yml"

cat > "$MIRROR_STAGE/CLAUDE.md" <<'EOF'
# A12 Interpreter public release mirror

This repository is generated release output. Do not edit it by hand; change the private source repository and run the staged interpreter publisher.

Published GitHub Release assets and versioned Maven/API directories are immutable. A broken artifact is superseded by a patch release.

Never commit local paths, credentials, tokens, private source material, or user-selected showcase files.
EOF
cat > "$MIRROR_STAGE/AGENTS.md" <<'EOF'
# AGENTS.md

This generated repository's contributor guidance lives in [CLAUDE.md](CLAUDE.md).
EOF

render_versioned_source "$LANDING_PAGE_SOURCE" "$MIRROR_STAGE/site/index.html"
cp "$PRIVACY_SOURCE" "$MIRROR_STAGE/site/privacy.html"
: > "$MIRROR_STAGE/site/.nojekyll"
rm -rf "$MIRROR_STAGE/site/showcase" "$MIRROR_STAGE/site/api/latest"
mkdir -p "$MIRROR_STAGE/site/showcase" "$MIRROR_STAGE/site/api/latest" "$API_VERSION_DIRECTORY"
cp -R "$SHOWCASE/." "$MIRROR_STAGE/site/showcase/"
cp -R "$API_DOCUMENTATION/." "$API_VERSION_DIRECTORY/"
cp -R "$API_DOCUMENTATION/." "$MIRROR_STAGE/site/api/latest/"
cp -R "$MAVEN_REPOSITORY/." "$MIRROR_STAGE/site/maven/"

ASSET_NAMES=()
while IFS= read -r asset_name; do
  ASSET_NAMES[${#ASSET_NAMES[@]}]="$asset_name"
done < <(interpreter_release_asset_names "$VERSION")
[[ "${#ASSET_NAMES[@]}" -eq 4 ]] || {
  echo "interpreter release asset manifest must provide exactly four payload names" >&2
  exit 1
}
NPM_ASSET="${ASSET_NAMES[0]}"
MAVEN_ASSET="${ASSET_NAMES[1]}"
API_ASSET="${ASSET_NAMES[2]}"
SHOWCASE_ASSET="${ASSET_NAMES[3]}"

release_require_safe_derived_directory "$ASSETS_DIRECTORY"
EXISTING_ASSETS=()
if [[ -d "$ASSETS_DIRECTORY" ]]; then
  while IFS= read -r existing; do
    case "$(basename "$existing")" in
      a12-interpreter-*.tgz|dm-interpreter-*-maven-repository.zip|dm-interpreter-*-api-docs.zip|dm-interpreter-*-showcase.zip|release-manifest.json|SHA256SUMS)
        EXISTING_ASSETS[${#EXISTING_ASSETS[@]}]="$existing"
        ;;
      *)
        echo "release assets directory contains an unexpected file: $existing" >&2
        exit 1
        ;;
    esac
  done < <(find "$ASSETS_DIRECTORY" -mindepth 1 -maxdepth 1 -type f -print)
  unexpected_asset_directory="$(
    find "$ASSETS_DIRECTORY" -mindepth 1 -maxdepth 1 -type d -print -quit
  )"
  [[ -z "$unexpected_asset_directory" ]] || {
    echo "release assets directory contains an unexpected directory: $unexpected_asset_directory" >&2
    exit 1
  }
fi

cp "$NPM_TARBALL" "$ASSET_STAGE/$NPM_ASSET"
cp "$API_DOCS_ARCHIVE" "$ASSET_STAGE/$API_ASSET"

mkdir -p "$ARCHIVE_STAGE/maven" "$ARCHIVE_STAGE/showcase"
cp -R "$MAVEN_REPOSITORY/." "$ARCHIVE_STAGE/maven/"
cp -R "$SHOWCASE/." "$ARCHIVE_STAGE/showcase/"
find "$ARCHIVE_STAGE" -exec touch -t 198001010000 {} +
(
  cd "$ARCHIVE_STAGE"
  find maven -type f -print | LC_ALL=C sort | zip -X -q "$ASSET_STAGE/$MAVEN_ASSET" -@
  find showcase -type f -print | LC_ALL=C sort | zip -X -q "$ASSET_STAGE/$SHOWCASE_ASSET" -@
)

artifact_json() {
  local name="$1"
  local file="$ASSET_STAGE/$name"
  printf '    {"name":"%s","bytes":%s,"sha256":"%s"}' \
    "$name" "$(release_file_size "$file")" "$(release_sha256 "$file")"
}

{
  cat <<EOF
{
  "schemaVersion": 1,
  "artifactVersion": "$VERSION",
  "releaseTag": "$RELEASE_TAG",
  "source": {
    "repository": "mbackschat/a12-dmkits",
    "tag": "$SOURCE_TAG",
    "sha": "$SOURCE_SHA"
  },
  "packages": {
    "typescript": "@mbackschat/a12-interpreter",
    "maven": "io.github.mbackschat.a12.dm:dm-interpreter:$VERSION"
  },
  "runtime": {
    "targets": ["jvm", "node", "browser"],
    "kernelBytecode": false
  },
  "artifacts": [
EOF
  for index in "${!ASSET_NAMES[@]}"; do
    artifact_json "${ASSET_NAMES[$index]}"
    if [[ "$index" -lt $((${#ASSET_NAMES[@]} - 1)) ]]; then
      printf ','
    fi
    printf '\n'
  done
  cat <<'EOF'
  ]
}
EOF
} > "$ASSET_STAGE/release-manifest.json"
cp "$ASSET_STAGE/release-manifest.json" "$MIRROR_STAGE/release-manifest.json"

(
  cd "$ASSET_STAGE"
  for file in "${ASSET_NAMES[@]}" release-manifest.json; do
    printf '%s  %s\n' "$(release_sha256 "$file")" "$file"
  done | LC_ALL=C sort > SHA256SUMS
)

release_scan_public_tree "$MIRROR_STAGE"
release_write_public_inventory "$MIRROR_STAGE"
"$ROOT/scripts/check-interpreter-release-repo.sh" "$MIRROR_STAGE" >/dev/null

rsync -a --checksum --delete --exclude='.git/' "$MIRROR_STAGE/" "$DESTINATION/"
release_verify_public_inventory "$DESTINATION"
mkdir -p "$ASSETS_DIRECTORY"
if [[ "${#EXISTING_ASSETS[@]}" -gt 0 ]]; then
  rm -f "${EXISTING_ASSETS[@]}"
fi
cp "$ASSET_STAGE/"* "$ASSETS_DIRECTORY/"

echo "assembled interpreter public mirror → $DESTINATION"
echo "assembled interpreter release assets → $ASSETS_DIRECTORY"
