#!/bin/sh
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
IDENTITY="$ROOT/gradle/graalvm-native.properties"
EXPECTED_GRAALVM="$(sed -n 's/^graalvmVersion=//p' "$IDENTITY" | tr -d '\r')"
EXPECTED_JAVA="$(sed -n 's/^javaVersion=//p' "$IDENTITY" | tr -d '\r')"
GRAAL_HOME="${1:-${GRAALVM_HOME:-}}"

if [ -z "$EXPECTED_GRAALVM" ] || [ -z "$EXPECTED_JAVA" ]; then
  echo "verify-graalvm-native: incomplete identity in $IDENTITY" >&2
  exit 1
fi

if [ -z "$GRAAL_HOME" ]; then
  echo "verify-graalvm-native: GRAALVM_HOME is required for native metadata capture and compilation" >&2
  exit 1
fi

GRAAL_HOME="$(CDPATH='' cd "$GRAAL_HOME" && pwd)"
NATIVE_IMAGE="$GRAAL_HOME/bin/native-image"
# GraalVM uses .cmd on Windows: https://www.graalvm.org/latest/getting-started/windows/
if [ ! -x "$NATIVE_IMAGE" ] && [ -f "$NATIVE_IMAGE.cmd" ]; then
  NATIVE_IMAGE="$NATIVE_IMAGE.cmd"
fi
if [ ! -f "$NATIVE_IMAGE" ]; then
  echo "verify-graalvm-native: no executable native-image at $NATIVE_IMAGE" >&2
  exit 1
fi

native_image_version() {
  case "$NATIVE_IMAGE" in
    *.cmd)
      # MSYS2 rewrites slash-prefixed arguments: https://www.msys2.org/docs/filesystem-paths/
      # cmd needs its switches unchanged and a Windows path for the batch file.
      WINDOWS_NATIVE_IMAGE="$(cygpath -w "$NATIVE_IMAGE")" || return
      MSYS2_ARG_CONV_EXCL='*' cmd.exe /d /c call "$WINDOWS_NATIVE_IMAGE" --version
      ;;
    *) "$NATIVE_IMAGE" --version ;;
  esac
}

if ! BANNER="$(native_image_version 2>&1)"; then
  echo "verify-graalvm-native: $NATIVE_IMAGE --version failed" >&2
  printf '%s\n' "$BANNER" >&2
  exit 1
fi

FIRST_LINE="$(printf '%s\n' "$BANNER" | sed -n '1p' | tr -d '\r')"
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
