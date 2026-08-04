#!/usr/bin/env bash
# Native-image reflection capture (docs/NATIVE-IMAGE-GRAALVM.md §5) — TEST-SUITE driven.
#
# Runs the ordinary :cli JUnit product tests under the GraalVM native-image tracing agent, so the captured
# reachability-metadata.json TRACKS THE TEST SUITE: every TDD test that exercises a new shape — a field
# kind, a constraint, an operator, an apply/refactor op, an error/diagnostic path — extends the metadata
# automatically, with no one having to remember to add it. This REPLACES the earlier curated verb-driving
# capture, whose coverage was bounded by the invocations the script happened to list — the root cause of
# the v0.1.0/v0.1.1 native-only bugs (§3.5). The rule-based registration of the two bounded reflective
# packages (scripts/gen-native-reflect-metadata.sh, §3.4) stays the shape-INDEPENDENT core; this capture
# covers the out-of-package tail, and now grows with the suite instead of by hand.
#
# The agent ships inside GraalVM, so the test JVM must be GraalVM (not the JDK-21 Gradle toolchain):
# build.gradle's `-PrkNativeAgent` mode points the ordinary test task's `executable` at $GRAALVM_HOME/bin/java,
# attaches the agent (config-merge-dir), and serializes forks (merge-dir is not fork-safe).
# Repository-structure guard tasks remain mandatory dependencies but run untraced under the normal JDK; their
# parsers and other build-time tooling are not dmtool runtime reachability.
#
# Usage:  GRAALVM_HOME=/path/to/graalvm-25 ./scripts/capture-native-config.sh
# Then:   ./gradlew :cli:nativeCompile   (the metadata is bundled under cli/src/main/resources)
set -euo pipefail

: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM 25 home}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DEST="cli/src/main/resources/META-INF/native-image/rulekit-agent"
CFG="${RK_NATIVE_CFG:-/tmp/rk-native-capture}"
BASELINE="$(mktemp /tmp/rk-native-metadata-baseline.XXXXXX)"
trap 'rm -f "$BASELINE"' EXIT
if ! git show "HEAD:$DEST/reachability-metadata.json" > "$BASELINE" 2>/dev/null; then
  [[ -f "$DEST/reachability-metadata.json" ]] || {
    echo "no committed or working native metadata baseline found" >&2
    exit 1
  }
  \cp -f "$DEST/reachability-metadata.json" "$BASELINE"
fi
rm -rf "$CFG"; mkdir -p "$DEST" "$CFG"

# Run the full :cli verification graph: the ordinary product test is traced except
# NativeImageMetadataHygieneTest, which necessarily reads the PRE-CAPTURE committed file; repository-structure
# guards execute untraced. Every executed test must pass: ignoring red tests contaminates metadata with
# failure-serialization paths and can publish a capture from a genuinely broken suite.
# build.sh keeps the Gradle daemon on the project's JDK 21 and resolves the shared isolated Gradle home
# through Git's common directory. The rkNativeAgent mode independently launches the traced test JVM from
# GRAALVM_HOME, so the capture still uses GraalVM without reimplementing the wrapper's environment policy.
./build.sh -PrkNativeAgent "-PrkNativeAgentDir=$CFG" :cli:test

shopt -s nullglob
files=("$CFG"/*.json)
[[ ${#files[@]} -gt 0 ]] || {
  echo "capture produced no metadata in $CFG — did the agent attach to a GraalVM test JVM? (GRAALVM_HOME=$GRAALVM_HOME)" >&2
  exit 1
}
[[ -f "$CFG/reachability-metadata.json" ]] || {
  echo "capture did not produce the expected unified reachability-metadata.json" >&2
  exit 1
}
./scripts/check-native-metadata-removals.sh "$BASELINE" "$CFG/reachability-metadata.json"
\cp -f "${files[@]}" "$DEST/"   # \cp bypasses a `cp -i` alias that would prompt and skip the overwrite

# Now the hygiene assertion has the artifact it owns. Run it outside the agent so its test machinery does not
# become capture input; set -e makes either the traced suite or this post-copy check terminal.
./build.sh :cli:test --tests 'io.github.mbackschat.a12.dm.cli.NativeImageMetadataHygieneTest'

# Record WHAT this capture was taken from, so a later stale capture cannot pass unnoticed. Written last, only
# on success, so a failed capture never leaves a stamp claiming freshness it did not establish. The stamp is
# committed alongside the metadata; `verifyNativeCaptureStamp` (run by the probe and ship gates) fails when the
# sources have moved on. NATIVE-IMAGE-GRAALVM §5.1.
./build.sh :cli:nativeCaptureStamp

echo "captured (test-suite) → $DEST/ ($(ls "$DEST" | tr '\n' ' '))"
