#!/bin/sh
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
IDENTITY="$ROOT/gradle/graalvm-native.properties"
EXPECTED_GRAALVM="$(sed -n 's/^graalvmVersion=//p' "$IDENTITY")"
EXPECTED_JAVA="$(sed -n 's/^javaVersion=//p' "$IDENTITY")"
GRAAL_HOME="${1:-${GRAALVM_HOME:-}}"

if [ -z "$EXPECTED_GRAALVM" ] || [ -z "$EXPECTED_JAVA" ]; then
  echo "verify-graalvm-native: incomplete identity in $IDENTITY" >&2
  exit 1
fi

if [ -z "$GRAAL_HOME" ]; then
  echo "verify-graalvm-native: GRAALVM_HOME is required for native metadata capture and compilation" >&2
  exit 1
fi

NATIVE_IMAGE="$GRAAL_HOME/bin/native-image"
if [ ! -x "$NATIVE_IMAGE" ]; then
  echo "verify-graalvm-native: no executable native-image at $NATIVE_IMAGE" >&2
  exit 1
fi

if ! BANNER="$("$NATIVE_IMAGE" --version 2>&1)"; then
  echo "verify-graalvm-native: $NATIVE_IMAGE --version failed" >&2
  printf '%s\n' "$BANNER" >&2
  exit 1
fi

FIRST_LINE="$(printf '%s\n' "$BANNER" | sed -n '1p')"
case "$FIRST_LINE" in
  "native-image $EXPECTED_JAVA"|"native-image $EXPECTED_JAVA "*) ;;
  *)
    echo "verify-graalvm-native: expected native-image $EXPECTED_JAVA, got: $FIRST_LINE" >&2
    exit 1
    ;;
esac

case "$BANNER" in
  *"GraalVM Runtime Environment Oracle GraalVM $EXPECTED_GRAALVM+"*) ;;
  *)
    echo "verify-graalvm-native: expected Oracle GraalVM $EXPECTED_GRAALVM runtime identity" >&2
    printf '%s\n' "$BANNER" >&2
    exit 1
    ;;
esac

printf '%s\n' "$BANNER"
