#!/bin/sh
# The a12_java_* assignments are the sourced helper's documented multi-value output contract; callers read them.
# shellcheck disable=SC2034

# Resolve the init.d-free Gradle user home shared by the primary checkout and its linked worktrees. A subshell
# scopes the working variables without relying on `local`, which POSIX does not define.
a12_gradle_user_home() (
  a12_repository_root="${1:?repository root is required}"
  if [ -n "${GRADLE_USER_HOME:-}" ]; then
    printf '%s\n' "$GRADLE_USER_HOME"
    return
  fi

  a12_common_git_directory="$(git -C "$a12_repository_root" rev-parse --path-format=absolute --git-common-dir)"
  a12_common_checkout="$(CDPATH='' cd "$a12_common_git_directory/.." && pwd)"
  printf '%s/.gradle-isolated\n' "$(CDPATH='' cd "$a12_common_checkout/.." && pwd)"
)

# GraalVM writes GRAALVM_VERSION into its release file and stock OpenJDK does not, so this discriminator is a
# file read rather than a JVM launch. Keep its working value in a subshell for the same POSIX scoping reason.
a12_is_graalvm_home() (
  a12_jdk_home="${1:?a JDK home is required}"
  [ -r "$a12_jdk_home/release" ] && grep -q '^GRAALVM_VERSION=' "$a12_jdk_home/release"
)

# Every JDK 21 home macOS knows about, one per line. Split out from the selection policy so the self-test can
# substitute fixture homes on Linux as well as macOS.
a12_has_java_home_tool() { [ -x /usr/libexec/java_home ]; }

a12_jdk21_homes() {
  a12_has_java_home_tool || return 0
  /usr/libexec/java_home -V 2>&1 | sed -n 's|^ *21\..* \(/.*\)$|\1|p'
}

# Homebrew's openjdk@21 through its formula alias, or nothing when it is not installed. The alias names no patch
# version and is moved by every `brew upgrade`, whereas the versioned Cellar home java_home reports is deleted by
# it, so a JAVA_HOME exported from that one goes stale in every shell that outlives the upgrade (direnv re-runs
# .envrc only on a watched-file change). HOMEBREW_PREFIX, which `brew shellenv` sets, replaces both defaults.
a12_homebrew_jdk21_home() (
  if [ -n "${HOMEBREW_PREFIX:-}" ]; then set -- "$HOMEBREW_PREFIX"; else set -- /opt/homebrew /usr/local; fi
  for a12_brew_prefix in "$@"; do
    a12_brew_home="$a12_brew_prefix/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
    if [ -x "$a12_brew_home/bin/java" ]; then
      printf '%s\n' "$a12_brew_home"
      return 0
    fi
  done
)

# Resolve the OpenJDK 21 home pinned for both the build and any launcher it verifies: Homebrew's openjdk@21 where
# it is installed, else the first stock JDK 21 java_home lists, else nothing on a platform without java_home. GraalVM is reserved for native image (GRAALVM_HOME) and is deliberately skipped.
# The function is a subshell so callers that source this file do not inherit its enumeration state.
a12_pinned_jdk21_home() (
  a12_jdk21_brew="$(a12_homebrew_jdk21_home)"
  if [ -n "$a12_jdk21_brew" ]; then
    printf '%s\n' "$a12_jdk21_brew"
    return 0
  fi
  a12_has_java_home_tool || return 0
  a12_jdk21_candidates="$(a12_jdk21_homes)"
  a12_jdk21_saw_any=0
  while IFS= read -r a12_jdk21_home; do
    [ -n "$a12_jdk21_home" ] || continue
    a12_jdk21_saw_any=1
    a12_is_graalvm_home "$a12_jdk21_home" && continue
    printf '%s\n' "$a12_jdk21_home"
    return 0
  done <<EOF
$a12_jdk21_candidates
EOF

  if [ "$a12_jdk21_saw_any" -ne 0 ]; then
    echo "the only JDK 21 registered is a GraalVM, which is reserved for native image (GRAALVM_HOME) and is" >&2
    echo "not accepted as the build JDK. Install one: brew install openjdk@21" >&2
  elif [ -x /usr/libexec/java_home ]; then
    /usr/libexec/java_home -v 21 >/dev/null || true
  fi
  return 1
)

# The command a recovery hint tells a reader to type: the pin itself where it resolves, so the advice survives the
# next `brew upgrade`, and the java_home lookup only where there is no pin to name.
a12_jdk21_export_hint() {
  if a12_jdk21_hint_home="$(a12_pinned_jdk21_home 2>/dev/null)" && [ -n "$a12_jdk21_hint_home" ]; then
    printf 'export JAVA_HOME="%s"\n' "$a12_jdk21_hint_home"
  else
    # The command substitution is text for the reader to type, so it must not expand here.
    # shellcheck disable=SC2016
    printf '%s\n' 'export JAVA_HOME="$(/usr/libexec/java_home -v 21)"'
  fi
  unset a12_jdk21_hint_home
}

# Classify the Java that `$1` (a JAVA_HOME, or empty for PATH) would run against the development floor of JDK
# 21. The answer is exported through the documented a12_java_* globals because the callers need all five values.
# POSIX functions restore their positional parameters, so no non-contract working variable needs global scope.
#
#   a12_java_kind    ok | no-home-binary | no-java | failed | unreadable | too-old
#   a12_java_bin     the java the classification used (empty when none was found)
#   a12_java_major   the parsed major version (ok / too-old only)
#   a12_java_status  the exit status of `java -version` (failed only)
#   a12_java_banner  the raw `java -version` output, both streams, when one was captured
a12_classify_java() {
  a12_java_kind=''
  a12_java_bin=''
  a12_java_major=''
  a12_java_status=0
  a12_java_banner=''

  if [ -n "${1:-}" ]; then
    if [ -x "$1/jre/sh/java" ]; then
      a12_java_bin="$1/jre/sh/java"
    elif [ -x "$1/bin/java" ]; then
      a12_java_bin="$1/bin/java"
    else
      a12_java_kind=no-home-binary
      return 0
    fi
  else
    if ! command -v java >/dev/null 2>&1; then
      a12_java_kind=no-java
      return 0
    fi
    a12_java_bin=java
  fi

  a12_java_banner="$("$a12_java_bin" -version 2>&1)" || a12_java_status=$?
  if [ "$a12_java_status" -ne 0 ]; then
    a12_java_kind=failed
    return 0
  fi

  a12_java_major="$(printf '%s\n' "$a12_java_banner" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n '1p')"
  case "$a12_java_major" in
    ''|*[!0-9]*)
      a12_java_kind=unreadable
      return 0
      ;;
  esac
  if [ "$a12_java_major" -lt 21 ]; then
    a12_java_kind=too-old
  else
    a12_java_kind=ok
  fi
}
