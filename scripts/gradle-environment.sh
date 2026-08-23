#!/usr/bin/env bash

# Resolve the init.d-free Gradle user home shared by the primary checkout and its linked worktrees.
a12_gradle_user_home() {
  local repository_root="${1:?repository root is required}"
  if [[ -n "${GRADLE_USER_HOME:-}" ]]; then
    printf '%s\n' "$GRADLE_USER_HOME"
    return
  fi

  local common_git_directory common_checkout
  common_git_directory="$(git -C "$repository_root" rev-parse --path-format=absolute --git-common-dir)"
  common_checkout="$(cd "$common_git_directory/.." && pwd)"
  printf '%s/.gradle-isolated\n' "$(cd "$common_checkout/.." && pwd)"
}

# Is `$1` a GraalVM home? GraalVM writes GRAALVM_VERSION into its `release` file and stock OpenJDK does not,
# so this is a file read rather than a JVM launch — it runs on every ./build.sh.
a12_is_graalvm_home() {
  local home="${1:?a JDK home is required}"
  [[ -r "$home/release" ]] && grep -q '^GRAALVM_VERSION=' "$home/release"
}

# Every JDK 21 home macOS knows about, one per line. Split out from the selection policy below so that policy
# is testable against fixture homes without a real java_home (scripts/selftest-jdk-pin.sh overrides this).
a12_jdk21_homes() {
  [[ -x /usr/libexec/java_home ]] || return 0
  /usr/libexec/java_home -V 2>&1 | sed -n 's|^ *21\..* \(/.*\)$|\1|p'
}

# Resolve the JDK 21 home this project pins for BOTH its Gradle build and any launcher it verifies, or print
# nothing where the platform offers no pin (Linux/CI leave JAVA_HOME to the caller). Single-sourced because
# the build pinning a JDK while a post-build launcher check keeps the caller's is exactly how a stale
# JAVA_HOME was reported as a source-provenance mismatch (dmtool feedback 2026-08-19). A present but broken
# `java_home -v 21` stays a failure here; each caller decides whether that is fatal.
#
# THE PIN IS OPENJDK, AND A GRAALVM HOME IS SKIPPED RATHER THAN ACCEPTED (owner rule 2026-08-23). This used
# to be `java_home -v 21`, which returns the HIGHEST-versioned JDK 21 present — so `brew install openjdk@21`
# displaced GraalVM 21.0.12 as the build JDK on 2026-08-19 with no commit here, moving 45 of 91 perf lanes
# (median 1.15x, worst 1.60x) and reading at first as a code regression. GraalVM belongs to native image
# alone (GRAALVM_HOME, GraalVM 25). Refusing beats falling back: a silent second-choice JDK is the exact
# failure this replaces.
a12_pinned_jdk21_home() {
  [[ -x /usr/libexec/java_home ]] || return 0
  local home
  while IFS= read -r home; do
    [[ -n "$home" ]] || continue
    a12_is_graalvm_home "$home" && continue
    printf '%s\n' "$home"
    return 0
  done < <(a12_jdk21_homes)
  return 1
}

# Classify the Java that `$1` (a JAVA_HOME, or empty to mean "whatever PATH resolves") would actually run,
# against the development floor of JDK 21. Single-sourced because two consumers ask the same question with
# different severity — `bin/dmtool` REFUSES so the Gradle start script cannot die undiagnosed, while
# `prepare-dmtool-source.sh` only WARNS, since the launcher path it prints runs in the caller's environment
# rather than its own. Duplicating the probe would re-encode the version floor in a second place, which is
# the same defect the JDK pin above was extracted to fix.
#
# Results go into globals rather than stdout: the caller needs four values, and a command substitution would
# run this in a subshell. Always returns 0 — severity is the CALLER's decision, and so is the wording.
#
#   a12_java_kind    ok | no-home-binary | no-java | failed | unreadable | too-old
#   a12_java_bin     the java the classification used ("" when none was found)
#   a12_java_major   the parsed major version (ok / too-old only)
#   a12_java_status  the exit status of `java -version` (failed only)
#   a12_java_banner  the raw `java -version` output, both streams, when one was captured
a12_classify_java() {
  local java_home="${1:-}"
  a12_java_kind=""
  a12_java_bin=""
  a12_java_major=""
  a12_java_status=0
  a12_java_banner=""

  if [[ -n "$java_home" ]]; then
    # Probe BOTH locations Gradle's generated start script probes, preferring the same one it prefers. A gate
    # that only looked for `bin/java` refused a JAVA_HOME that Gradle would have started from `jre/sh/java`.
    if [[ -x "$java_home/jre/sh/java" ]]; then
      a12_java_bin="$java_home/jre/sh/java"
    elif [[ -x "$java_home/bin/java" ]]; then
      a12_java_bin="$java_home/bin/java"
    else
      a12_java_kind=no-home-binary
      return 0
    fi
  else
    command -v java >/dev/null 2>&1 || { a12_java_kind=no-java; return 0; }
    a12_java_bin=java
  fi

  # `|| status=$?` rather than a `set +e`/`set -e` pair: the pair would turn errexit ON in a caller that
  # deliberately runs without it (perf-keys.sh does), which contradicts this function's own "always returns 0,
  # severity is the caller's decision" contract by killing the caller at its next failing command.
  local banner status=0
  banner="$("$a12_java_bin" -version 2>&1)" || status=$?
  a12_java_banner="$banner"
  a12_java_status=$status
  if (( status != 0 )); then
    a12_java_kind=failed
    return 0
  fi

  # Match the version on ANY line: a JVM prepends "Picked up JAVA_TOOL_OPTIONS: …" (and the _JAVA_OPTIONS
  # twin) whenever those are set, which is routine in CI, containers, and IDE-spawned shells. Anchoring to
  # line 1 yielded an empty version there and made the gate silently inert in exactly those environments.
  local major
  major="$(printf '%s\n' "$banner" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
  if [[ -z "$major" ]]; then
    a12_java_kind=unreadable
    return 0
  fi
  a12_java_major="$major"
  if (( major < 21 )); then a12_java_kind=too-old; else a12_java_kind=ok; fi
}
