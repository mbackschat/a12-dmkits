#!/bin/sh
# Stable POSIX entrypoint for the typed build-environment doctor.
set -eu

ROOT="$(CDPATH='' cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/gradle-environment.sh
. "$ROOT/scripts/gradle-environment.sh"

A12_DOCTOR_GRADLE_HOME="$(a12_gradle_user_home "$ROOT")"
export A12_DOCTOR_GRADLE_HOME
if A12_DOCTOR_PINNED_JDK21="$(a12_pinned_jdk21_home 2>/dev/null)"; then
  A12_DOCTOR_PINNED_JDK21_AVAILABLE=1
else
  A12_DOCTOR_PINNED_JDK21=""
  A12_DOCTOR_PINNED_JDK21_AVAILABLE=0
fi
export A12_DOCTOR_PINNED_JDK21 A12_DOCTOR_PINNED_JDK21_AVAILABLE

# The shared launcher uses exit 2 for a missing runtime; the doctor's public verdict is one or more failed
# machine checks and has always exited 1. Preserve that boundary while retaining the launcher's diagnostic.
if ! doctor_node_error="$("$ROOT/scripts/run-typescript.sh" --check 2>&1)"; then
  printf '%s\n' "$doctor_node_error" >&2
  exit 1
fi

exec "$ROOT/scripts/run-typescript.sh" "build environment doctor" "$ROOT/scripts/typescript/doctor.ts" "$@"
