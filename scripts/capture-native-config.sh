#!/usr/bin/env bash
# Native-image reflection capture (docs/NATIVE-IMAGE-GRAALVM.md §5) — TEST-SUITE driven.
#
# Runs the :cli JUnit suite under the GraalVM native-image tracing agent, so the captured
# reachability-metadata.json TRACKS THE TEST SUITE: every TDD test that exercises a new shape — a field
# kind, a constraint, an operator, an apply/refactor op, an error/diagnostic path — extends the metadata
# automatically, with no one having to remember to add it. This REPLACES the earlier curated verb-driving
# capture, whose coverage was bounded by the invocations the script happened to list — the root cause of
# the v0.1.0/v0.1.1 native-only bugs (§3.5). The rule-based registration of the two bounded reflective
# packages (scripts/gen-native-reflect-metadata.sh, §3.4) stays the shape-INDEPENDENT core; this capture
# covers the out-of-package tail, and now grows with the suite instead of by hand.
#
# The agent ships inside GraalVM, so the test JVM must be GraalVM (not the JDK-21 Gradle toolchain):
# build.gradle's `-PrkNativeAgent` mode points the test task's `executable` at $GRAALVM_HOME/bin/java,
# attaches the agent (config-merge-dir), and serializes forks (merge-dir is not fork-safe).
#
# Usage:  GRAALVM_HOME=/path/to/graalvm-25 ./scripts/capture-native-config.sh
# Then:   ./gradlew :cli:nativeCompile   (the metadata is bundled under cli/src/main/resources)
set -euo pipefail

: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM 25 home}"
export JAVA_HOME="$GRAALVM_HOME"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(cd .. && pwd)/.gradle-isolated}"

DEST="cli/src/main/resources/META-INF/native-image/rulekit-agent"
CFG="${RK_NATIVE_CFG:-/tmp/rk-native-capture}"
rm -rf "$CFG"; mkdir -p "$DEST" "$CFG"

# Run the whole :cli suite under the agent. A failing assertion still exercises its code path, so capture
# is robust to a red test; --continue keeps the suite going and `|| true` ignores the test-failure exit.
./gradlew --continue -PrkNativeAgent "-PrkNativeAgentDir=$CFG" :cli:test || true

shopt -s nullglob
files=("$CFG"/*.json)
[[ ${#files[@]} -gt 0 ]] || {
  echo "capture produced no metadata in $CFG — did the agent attach to a GraalVM test JVM? (GRAALVM_HOME=$GRAALVM_HOME)" >&2
  exit 1
}
\cp -f "${files[@]}" "$DEST/"   # \cp bypasses a `cp -i` alias that would prompt and skip the overwrite
echo "captured (test-suite) → $DEST/ ($(ls "$DEST" | tr '\n' ' '))"
