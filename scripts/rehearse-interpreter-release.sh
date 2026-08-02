#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=interpreter-release-assets.sh
. "$ROOT/scripts/interpreter-release-assets.sh"
REHEARSAL_ROOT="$(mktemp -d)"
trap 'rm -rf "$REHEARSAL_ROOT"' EXIT

SOURCE_TAG="interpreter-v0.10.1"
RELEASE_TAG="v0.10.1"
BACKEND="$ROOT/scripts/interpreter-release-rehearsal-host.sh"
PUBLISHER="$ROOT/scripts/publish-interpreter-release.sh"

create_synthetic_builder() {
  local target="$1"
  cat > "$target" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="$(sed -nE "s/^version = '([^']+)'/\1/p" "$ROOT/build.gradle")"
RELEASE_TAG="v$VERSION"
for argument in "$@"; do
  case "$argument" in
    -PinterpreterReleaseTag=*) RELEASE_TAG="${argument#*=}" ;;
  esac
done
OUTPUT="$ROOT/build/interpreter-release"
rm -rf "$OUTPUT"
mkdir -p \
  "$OUTPUT/npm-stage/package/dist" \
  "$OUTPUT/npm" \
  "$OUTPUT/maven/io/github/mbackschat/a12/dm/dm-interpreter/$VERSION" \
  "$OUTPUT/api-docs/stage/api/v$VERSION/kotlin" \
  "$OUTPUT/api-docs/stage/api/v$VERSION/typescript" \
  "$OUTPUT/api-docs" \
  "$OUTPUT/consumer/browser/dist/assets"

if [[ "${REHEARSAL_PACKAGE_MODE:-complete}" == "empty-js" ]]; then
  : > "$OUTPUT/npm-stage/package/dist/index.js"
else
  printf '%s\n' 'export const interpreter = "synthetic";' > "$OUTPUT/npm-stage/package/dist/index.js"
fi
if [[ "${REHEARSAL_PACKAGE_MODE:-complete}" != "missing-dts" ]]; then
  printf '%s\n' 'export declare const interpreter: string;' > "$OUTPUT/npm-stage/package/dist/index.d.ts"
fi
printf '%s\n' '{"name":"@mbackschat/a12-interpreter","version":"'"$VERSION"'"}' \
  > "$OUTPUT/npm-stage/package/package.json"
(
  cd "$OUTPUT/npm-stage"
  tar -czf "$OUTPUT/npm/a12-interpreter-$VERSION.tgz" package
)

printf '%s\n' '<project><version>'"$VERSION"'</version></project>' \
  > "$OUTPUT/maven/io/github/mbackschat/a12/dm/dm-interpreter/$VERSION/dm-interpreter-$VERSION.pom"
printf '%s\n' 'synthetic jar' \
  > "$OUTPUT/maven/io/github/mbackschat/a12/dm/dm-interpreter/$VERSION/dm-interpreter-$VERSION.jar"

if [[ "${REHEARSAL_PACKAGE_MODE:-complete}" != "missing-doc" ]]; then
  printf '%s\n' '<title>Interpreter API</title>' > "$OUTPUT/api-docs/stage/api/v$VERSION/index.html"
fi
printf '%s\n' '# Interpreter API guide' > "$OUTPUT/api-docs/stage/api/v$VERSION/INTERPRETER-API-GUIDE.md"
printf '%s\n' '<title>Kotlin API</title>' > "$OUTPUT/api-docs/stage/api/v$VERSION/kotlin/index.html"
printf '%s\n' '<title>TypeScript API</title>' > "$OUTPUT/api-docs/stage/api/v$VERSION/typescript/index.html"
printf '%s\n' '# API' > "$OUTPUT/api-docs/stage/api/v$VERSION/README.md"
printf '%s\n' '# llms' > "$OUTPUT/api-docs/stage/api/v$VERSION/llms.txt"
(
  cd "$OUTPUT/api-docs/stage"
  find api -type f -print | LC_ALL=C sort |
    zip -X -q "$OUTPUT/api-docs/dm-interpreter-$VERSION-api-docs.zip" -@
)

printf '%s\n' '<title>Interpreter showcase</title>' > "$OUTPUT/consumer/browser/dist/index.html"
printf '%s\n' 'console.log("showcase");' > "$OUTPUT/consumer/browser/dist/assets/app.js"

"$ROOT/scripts/assemble-interpreter-release-repo.sh" \
  --destination "$OUTPUT/public-mirror" \
  --assets-directory "$OUTPUT/public-assets" \
  --version "$VERSION" \
  --release-tag "$RELEASE_TAG" \
  --source-tag "interpreter-v$VERSION" \
  --source-sha "$(git -C "$ROOT" rev-parse HEAD)" \
  --npm-tarball "$OUTPUT/npm/a12-interpreter-$VERSION.tgz" \
  --maven-repository "$OUTPUT/maven" \
  --api-documentation "$OUTPUT/api-docs/stage/api/v$VERSION" \
  --api-docs-archive "$OUTPUT/api-docs/dm-interpreter-$VERSION-api-docs.zip" \
  --showcase "$OUTPUT/consumer/browser/dist" \
  --readme "$ROOT/interpreter/public-release/README.md" \
  --changelog "$ROOT/interpreter/CHANGELOG.md" \
  --license "$ROOT/LICENSE" \
  --disclaimer "$ROOT/interpreter/public-release/DISCLAIMER.md" \
  --privacy "$ROOT/interpreter/public-release/privacy.html" \
  --landing-page "$ROOT/interpreter/public-release/index.html" \
  --maven-landing-page "$ROOT/interpreter/public-release/maven.html" \
  --workflow-directory "$ROOT/.github/interpreter-mirror-workflows"
EOF
  chmod +x "$target"
}

create_fixture() {
  local name="$1" build_version="${2:-0.10.1}" tag_version="${3:-0.10.1}"
  FIXTURE="$REHEARSAL_ROOT/$name"
  SOURCE="$FIXTURE/source"
  SOURCE_ORIGIN="$FIXTURE/source-origin.git"
  MIRROR_ORIGIN="$FIXTURE/mirror-origin.git"
  STATE="$FIXTURE/state"
  WORK="$FIXTURE/work"
  mkdir -p \
    "$SOURCE/scripts" \
    "$SOURCE/interpreter/public-release" \
    "$SOURCE/.github/interpreter-mirror-workflows" \
    "$STATE"

  cp "$ROOT/LICENSE" "$SOURCE/LICENSE"
  cp "$ROOT/interpreter/CHANGELOG.md" "$SOURCE/interpreter/CHANGELOG.md"
  cp "$ROOT/interpreter/public-release/"* "$SOURCE/interpreter/public-release/"
  cp "$ROOT/.github/interpreter-mirror-workflows/"* "$SOURCE/.github/interpreter-mirror-workflows/"
  for script in \
    assemble-interpreter-release-repo.sh \
    check-interpreter-release-repo.sh \
    interpreter-release-assets.sh \
    release-git-identity.sh \
    release-mirror-common.sh; do
    cp "$ROOT/scripts/$script" "$SOURCE/scripts/$script"
  done
  cat > "$SOURCE/scripts/update-code-statistics.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 3 && "$1" == "--check" && "$2" == "--artifact" && "$3" == "interpreter" ]] || {
  echo "synthetic source-statistics check received unexpected arguments: $*" >&2
  exit 2
}
EOF
  chmod +x "$SOURCE/scripts/update-code-statistics.sh"
  printf "version = '%s'\n" "$build_version" > "$SOURCE/build.gradle"
  printf '%s\n' '/build/' > "$SOURCE/.gitignore"
  create_synthetic_builder "$SOURCE/build.sh"

  git init --quiet --initial-branch=main "$SOURCE"
  git -C "$SOURCE" config user.name "Synthetic Source"
  git -C "$SOURCE" config user.email "source@example.invalid"
  git -C "$SOURCE" add -A
  git -C "$SOURCE" commit --quiet -m "synthetic source"
  git -C "$SOURCE" tag "interpreter-v$tag_version"
  git init --quiet --bare --initial-branch=main "$SOURCE_ORIGIN"
  git -C "$SOURCE" remote add origin "$SOURCE_ORIGIN"
  git -C "$SOURCE" push --quiet origin main "interpreter-v$tag_version"
  git init --quiet --bare --initial-branch=main "$MIRROR_ORIGIN"
  "$SOURCE/build.sh" >/dev/null
}

publisher_raw() {
  local extra_environment=()
  local publisher_arguments=()
  local argument
  for argument in "$@"; do
    case "$argument" in
      *=*) extra_environment[${#extra_environment[@]}]="$argument" ;;
      *) publisher_arguments[${#publisher_arguments[@]}]="$argument" ;;
    esac
  done
  env \
    INTERPRETER_RELEASE_REHEARSAL=1 \
    INTERPRETER_RELEASE_HOST_BACKEND="$BACKEND" \
    INTERPRETER_RELEASE_SOURCE_ROOT="$SOURCE" \
    INTERPRETER_RELEASE_WORK_DIRECTORY="$WORK" \
    INTERPRETER_RELEASE_REHEARSAL_STATE="$STATE" \
    INTERPRETER_RELEASE_REHEARSAL_MIRROR_REMOTE="$MIRROR_ORIGIN" \
    INTERPRETER_RELEASE_PAGES_BASE="file://$STATE/pages" \
    INTERPRETER_RELEASE_DOWNLOAD_BASE="file://$STATE/public-assets" \
    "${extra_environment[@]}" \
    "$PUBLISHER" "$SOURCE_TAG" "${publisher_arguments[@]}"
}

publisher() {
  case " $* " in
    *" --qualify "*|*" --go-live "*) publisher_raw "$@" ;;
    *)
      publisher_raw "$@" --qualify >/dev/null
      publisher_raw "$@"
      ;;
  esac
}

expect_refusal() {
  local label="$1" expected="$2"
  shift 2
  local output status
  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e
  [[ "$status" -ne 0 ]] || {
    echo "expected refusal '$label', command succeeded" >&2
    return 1
  }
  grep -Fq "$expected" <<< "$output" || {
    echo "refusal '$label' did not contain '$expected':" >&2
    echo "$output" >&2
    return 1
  }
  echo "PASS $label"
}

seed_mirror_commit() {
  local author_name="$1" author_email="$2" committer_name="$3" committer_email="$4" relative="$5"
  local clone="$FIXTURE/seed"
  git clone --quiet "$MIRROR_ORIGIN" "$clone"
  git -C "$clone" config user.name "$committer_name"
  git -C "$clone" config user.email "$committer_email"
  mkdir -p "$clone/$(dirname "$relative")"
  printf '%s\n' seed > "$clone/$relative"
  git -C "$clone" add -A
  GIT_AUTHOR_NAME="$author_name" GIT_AUTHOR_EMAIL="$author_email" \
    git -C "$clone" commit --quiet -m "seed mirror"
  git -C "$clone" push --quiet origin HEAD:main
}

assert_public_identities() {
  local clone="$FIXTURE/identity-check"
  local expected="Martin Backschat <mbackschat@mail.de>|Martin Backschat <mbackschat@mail.de>"
  git clone --quiet "$MIRROR_ORIGIN" "$clone"
  while IFS= read -r identity; do
    [[ "$identity" == "$expected" ]] || {
      echo "wrong rehearsal public identity: $identity" >&2
      return 1
    }
  done < <(git -C "$clone" log --format='%an <%ae>|%cn <%ce>')
}

create_fixture wrong-author
seed_mirror_commit \
  "Wrong Author" \
  "wrong-author@example.invalid" \
  "Martin Backschat" \
  "mbackschat@mail.de" \
  README.md
expect_refusal "wrong author identity" "wrong public commit identity" publisher

create_fixture wrong-committer
seed_mirror_commit \
  "Martin Backschat" \
  "mbackschat@mail.de" \
  "Wrong Committer" \
  "wrong-committer@example.invalid" \
  README.md
expect_refusal "wrong committer identity" "wrong public commit identity" publisher

create_fixture dirty-source
printf '%s\n' dirty >> "$SOURCE/build.gradle"
expect_refusal "dirty source" "source working tree is dirty" publisher

create_fixture version-mismatch 0.10.2 0.10.1
expect_refusal "source tag/version mismatch" "source tag/version mismatch" publisher

create_fixture sha-mismatch
printf '%s\n' '# moved tag' >> "$SOURCE/build.gradle"
git -C "$SOURCE" add build.gradle
git -C "$SOURCE" commit --quiet -m "move remote tag"
git -C "$SOURCE" push --quiet origin main
git -C "$SOURCE" push --quiet --force origin HEAD:refs/tags/0.10.1
git -C "$SOURCE_ORIGIN" update-ref "refs/tags/$SOURCE_TAG" "$(git -C "$SOURCE" rev-parse HEAD)"
expect_refusal "source tag/SHA mismatch" "source tag/SHA mismatch" publisher

create_fixture unqualified-stage
expect_refusal "unqualified stage" "no qualified release receipt exists" publisher_raw

create_fixture changed-qualified-output
publisher_raw --qualify >/dev/null
printf '\n' >> "$SOURCE/build/interpreter-release/public-assets/release-manifest.json"
(
  cd "$SOURCE/build/interpreter-release/public-assets"
  while IFS= read -r file; do
    printf '%s  %s\n' "$(shasum -a 256 "$file" | awk '{print $1}')" "$file"
  done < <(
    interpreter_release_asset_names "${RELEASE_TAG#v}"
    printf '%s\n' release-manifest.json
  ) | LC_ALL=C sort > SHA256SUMS.changed
  mv SHA256SUMS.changed SHA256SUMS
)
expect_refusal "changed qualified outputs" "qualified release receipt does not match" publisher_raw

create_fixture published-release
mkdir -p "$STATE/releases/$RELEASE_TAG"
echo published > "$STATE/releases/$RELEASE_TAG/state"
expect_refusal "published release" "already published and immutable" publisher

create_fixture existing-version
seed_mirror_commit \
  "Martin Backschat" \
  "mbackschat@mail.de" \
  "Martin Backschat" \
  "mbackschat@mail.de" \
  "site/api/v0.10.1/index.html"
expect_refusal "existing immutable version" "already exists" publisher

create_fixture missing-dts
REHEARSAL_PACKAGE_MODE=missing-dts "$SOURCE/build.sh" >/dev/null
expect_refusal "missing TypeScript declaration" "missing package/dist/index.d.ts" \
  publisher_raw --qualify

create_fixture empty-js
REHEARSAL_PACKAGE_MODE=empty-js "$SOURCE/build.sh" >/dev/null
expect_refusal "empty JavaScript package" "JavaScript entry is empty" \
  publisher_raw --qualify

create_fixture missing-doc
REHEARSAL_PACKAGE_MODE=missing-doc "$SOURCE/build.sh" >/dev/null
expect_refusal "missing API documentation" "missing its landing page" \
  publisher_raw --qualify

create_fixture failed-url
publisher >/dev/null
expect_refusal "failed production URL smoke" "production URL smoke failed" \
  publisher INTERPRETER_RELEASE_REHEARSAL_BREAK_PAGES=1 --go-live
[[ "$(cat "$STATE/releases/$RELEASE_TAG/state")" == "draft" ]] || {
  echo "failed Pages smoke did not leave the release in draft" >&2
  exit 1
}

create_fixture go-live-source-moved
publisher >/dev/null
printf '%s\n' '# post-tag source change' >> "$SOURCE/build.gradle"
git -C "$SOURCE" add build.gradle
git -C "$SOURCE" commit --quiet -m "post-tag source change"
git -C "$SOURCE" push --quiet origin main
expect_refusal "go-live source checkout must match tag" "source HEAD must equal the immutable source tag" \
  publisher --go-live

create_fixture complete
publisher >/dev/null
publisher --go-live >/dev/null
[[ "$(cat "$STATE/releases/$RELEASE_TAG/state")" == "published" ]]
[[ "$(cat "$STATE/latest")" == "$RELEASE_TAG" ]]
[[ "$(cat "$STATE/public-consumer-smoke")" == "passed" ]]
assert_public_identities
echo "PASS anonymous public consumer programs"
echo "PASS default publication is stable root version"
echo "PASS complete draft-to-production path"

create_fixture resumable-public-smoke
publisher >/dev/null
expect_refusal "anonymous consumer smoke after publication" "rehearsal anonymous consumer smoke failed" \
  publisher INTERPRETER_RELEASE_REHEARSAL_BREAK_CONSUMER=1 --go-live
[[ "$(cat "$STATE/releases/$RELEASE_TAG/state")" == "published" ]]
publisher --go-live >/dev/null
[[ "$(cat "$STATE/latest")" == "$RELEASE_TAG" ]]
echo "PASS published consumer smoke resumes"

create_fixture prerelease
"$SOURCE/build.sh" -PinterpreterReleaseTag=v0.10.1-rc.1 >/dev/null
publisher --release-tag v0.10.1-rc.1 >/dev/null
publisher --release-tag v0.10.1-rc.1 --go-live >/dev/null
[[ "$(cat "$STATE/releases/v0.10.1-rc.1/state")" == "published" ]]
[[ ! -e "$STATE/latest" ]]
assert_public_identities
echo "PASS prerelease remains non-latest"

echo "interpreter release rehearsal: OK"
