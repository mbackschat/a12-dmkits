#!/usr/bin/env bash
# Execute the existing standalone TypeScript and Kotlin/JVM consumers against anonymously acquired public artifacts.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"
# shellcheck source=release-mirror-common.sh
. "$ROOT/scripts/release-mirror-common.sh"

VERSION=""
MAVEN_REPOSITORY=""
NPM_TARBALL=""
WORK_DIRECTORY=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) VERSION="${2:?missing --version value}"; shift 2 ;;
    --maven-repository) MAVEN_REPOSITORY="${2:?missing --maven-repository value}"; shift 2 ;;
    --npm-tarball) NPM_TARBALL="${2:?missing --npm-tarball value}"; shift 2 ;;
    --work-directory) WORK_DIRECTORY="${2:?missing --work-directory value}"; shift 2 ;;
    *) echo "unknown public consumer smoke argument: $1" >&2; exit 2 ;;
  esac
done

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$ ]] || {
  echo "version must be SemVer-compatible: $VERSION" >&2
  exit 2
}
[[ "$MAVEN_REPOSITORY" =~ ^https:// ]] || {
  echo "public Maven smoke requires an anonymous HTTPS repository URL" >&2
  exit 2
}
[[ -f "$NPM_TARBALL" ]] || {
  echo "anonymously downloaded npm tarball not found: $NPM_TARBALL" >&2
  exit 2
}
release_require_safe_derived_directory "$WORK_DIRECTORY"

deadline=()
if command -v timeout >/dev/null 2>&1; then
  deadline=(timeout)
elif command -v gtimeout >/dev/null 2>&1; then
  deadline=(gtimeout)
else
  echo "public consumer smoke needs timeout (macOS: brew install coreutils)" >&2
  exit 2
fi

KOTLIN_CONSUMER="$WORK_DIRECTORY/kotlin-jvm"
TYPESCRIPT_CONSUMER="$WORK_DIRECTORY/typescript"
GRADLE_HOME="$WORK_DIRECTORY/gradle-home"
PNPM_STORE="$WORK_DIRECTORY/pnpm-store"
SOURCE_GRADLE_USER_HOME="$(a12_gradle_user_home "$ROOT")"
SOURCE_WRAPPER_DISTS="$SOURCE_GRADLE_USER_HOME/wrapper/dists"
rm -rf "$WORK_DIRECTORY"
mkdir -p "$WORK_DIRECTORY" "$GRADLE_HOME/wrapper"
[[ -d "$SOURCE_WRAPPER_DISTS" ]] || {
  echo "resolved Gradle wrapper distribution not found: $SOURCE_WRAPPER_DISTS" >&2
  exit 2
}
# Dependency caches and init scripts stay isolated. Only the already-verified wrapper distribution is shared,
# so an anonymous consumer smoke cannot fail on or be delayed by a redundant Gradle download.
ln -s "$SOURCE_WRAPPER_DISTS" "$GRADLE_HOME/wrapper/dists"

"${deadline[@]}" 120 \
  "$ROOT/build.sh" \
  --console=plain \
  :interpreter:prepareExternalInterpreterConsumer \
  :interpreter:prepareExternalInterpreterTypeScriptConsumer
cp -R "$ROOT/build/interpreter-release/consumer/kotlin-jvm" "$KOTLIN_CONSUMER"
cp -R "$ROOT/build/interpreter-release/consumer/typescript" "$TYPESCRIPT_CONSUMER"

env \
  GRADLE_USER_HOME="$GRADLE_HOME" \
  INTERPRETER_MAVEN_REPOSITORY="$MAVEN_REPOSITORY" \
  INTERPRETER_VERSION="$VERSION" \
  "${deadline[@]}" 120 \
  "$ROOT/gradlew" \
  --no-daemon \
  --console=plain \
  --project-dir "$KOTLIN_CONSUMER" \
  clean \
  run

(
  cd "$TYPESCRIPT_CONSUMER"
  CI=true "${deadline[@]}" 60 pnpm install \
    --config.confirmModulesPurge=false \
    --frozen-lockfile=false \
    --lockfile=false \
    --store-dir "$PNPM_STORE"
  CI=true "${deadline[@]}" 60 pnpm add \
    --save-exact \
    --lockfile=false \
    --store-dir "$PNPM_STORE" \
    "$NPM_TARBALL"
  CI=true "${deadline[@]}" 60 pnpm run check
)

echo "anonymous interpreter Maven and TypeScript consumers passed"
