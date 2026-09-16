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
#
# CHUNKED capture (same result, split across several shorter runs). The whole traced suite takes 36-38
# minutes, and an agent harness cannot hold one process that long: a background task is killed by a memory
# watchdog and a Monitor is killed at its 30-minute ceiling (docs/AGENT-RUN-DISCIPLINE.md, both measured
# 2026-09-16). `config-merge-dir` already merges, so tracing N disjoint slices into one directory yields the
# union of their traces. That union is a SUPERSET of a single run's, never a subset — each slice re-runs JVM
# and static-initializer reflection the single run traced once — and over-approximated reachability is the
# safe direction.
#
#   RK_NATIVE_TESTS=<file>   trace ONLY the classes named in <file> (one FQCN per line), record them, stop
#                            before the assemble steps. This is one slice, never a whole capture.
#   RK_NATIVE_KEEP_CFG=1     keep an existing $RK_NATIVE_CFG and add to it, instead of wiping it.
#   RK_NATIVE_ASSEMBLE=1     skip tracing; run the removal check, strip, copy, hygiene and stamp over what
#                            the slices already accumulated.
#
#   for f in chunks/*; do RK_NATIVE_TESTS=$f RK_NATIVE_KEEP_CFG=1 ./scripts/capture-native-config.sh; done
#   RK_NATIVE_KEEP_CFG=1 RK_NATIVE_ASSEMBLE=1 ./scripts/capture-native-config.sh
#
# A slice-bounded capture is the v0.1.0/v0.1.1 defect class itself (§3.5: coverage bounded by what someone
# remembered to list), so the assemble step REFUSES to stamp until the recorded slices cover every compiled
# test class. The completeness claim is mechanical here, not a promise in a comment.
#
# Read that gate for exactly what it says: the SLICING omitted nothing. It does not certify the traced set
# itself. The test task still applies its own documented exclusions (mutationTailGuardTest's class as build
# tooling, NativeImageMetadataHygieneTest because it reads the pre-capture file), so a slice can pass 20
# classes and execute 18 — identical to what an unsliced capture traces, which is the point. And a reflective
# site no test reaches is invisible here just as it is to a single run; that is §3.5's open residue, not
# something slicing introduced or this gate closes.
set -euo pipefail

: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM 25 home}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
./scripts/verify-graalvm-native.sh "$GRAALVM_HOME"

DEST="cli/src/main/resources/META-INF/native-image/rulekit-agent"
CFG="${RK_NATIVE_CFG:-/tmp/rk-native-capture}"
CAPTURE_HEAD="$(git rev-parse --verify HEAD)"
REGRESSION_DIR="${RK_NATIVE_REGRESSION_DIR:-$ROOT/build/native-metadata-regression/${CAPTURE_HEAD:0:12}}"
BASELINE="$REGRESSION_DIR/baseline-reachability-metadata.json"
RAW_RECAPTURE="$REGRESSION_DIR/raw-recapture-reachability-metadata.json"
SHIPPED_RECAPTURE="$REGRESSION_DIR/shipped-recapture-reachability-metadata.json"
mkdir -p "$REGRESSION_DIR"
# Preserve the previous committed capture as a durable regression input for this entire build/gate cycle.
# Git remains the permanent owner, but a capture must not hide its own baseline in a temporary file that its
# EXIT trap deletes before a reviewer can inspect the exact old/raw/sanitized triple.
if ! git show "HEAD:$DEST/reachability-metadata.json" > "$BASELINE" 2>/dev/null; then
  [[ -f "$DEST/reachability-metadata.json" ]] || {
    echo "no committed or working native metadata baseline found" >&2
    exit 1
  }
  \cp -f "$DEST/reachability-metadata.json" "$BASELINE"
fi
MANIFEST="$CFG/traced-classes.txt"
if [[ -n "${RK_NATIVE_KEEP_CFG:-}" || -n "${RK_NATIVE_ASSEMBLE:-}" ]]; then
  mkdir -p "$DEST" "$CFG"
else
  rm -rf "$CFG"; mkdir -p "$DEST" "$CFG"
fi

# The traced denominator: every top-level compiled :cli test class. Derived from the COMPILED output rather
# than from sources, because that is what the test task actually selects from.
#
# The `Test$` filter drops no test, verified 2026-09-16: the nine compiled top-level classes that do not end
# in `Test` (CliTestSupport, GitTestSupport, KernelEval, OperatorIndex, PackagedDmtool, PublicMarkdownSurfaces,
# RepoWalk, ResultEnvelopeAssertions, StaleGapReferenceClassifier) carry no @Test, @ParameterizedTest,
# @TestFactory, @RepeatedTest or @TestTemplate between them — they are fixtures and helpers. The filter is
# needed because a `--tests` pattern naming a class with no tests contributes nothing while still counting
# against the manifest, which would make an incomplete capture look complete.
all_test_classes() {
  find cli/build/classes -path '*test*' -name '*.class' ! -name '*$*' \
    | sed -E 's|.*/classes/[a-z]+/test/||; s|/|.|g; s|\.class$||' \
    | grep -E 'Test$' | sort -u
}

# Run the full :cli verification graph: the ordinary product test is traced except
# NativeImageMetadataHygieneTest, which necessarily reads the PRE-CAPTURE committed file; repository-structure
# guards execute untraced. Every executed test must pass: ignoring red tests contaminates metadata with
# failure-serialization paths and can publish a capture from a genuinely broken suite.
# build.sh keeps the Gradle daemon on the project's JDK 21 and resolves the shared isolated Gradle home
# through Git's common directory. The rkNativeAgent mode independently launches the traced test JVM from
# GRAALVM_HOME, so the capture still uses GraalVM without reimplementing the wrapper's environment policy.
if [[ -z "${RK_NATIVE_ASSEMBLE:-}" ]]; then
  if [[ -n "${RK_NATIVE_TESTS:-}" ]]; then
    [[ -f "$RK_NATIVE_TESTS" ]] || { echo "RK_NATIVE_TESTS=$RK_NATIVE_TESTS is not a file" >&2; exit 1; }
    slice=()
    while read -r cls; do [[ -n "$cls" ]] && slice+=("$cls"); done < "$RK_NATIVE_TESTS"
    [[ ${#slice[@]} -gt 0 ]] || { echo "RK_NATIVE_TESTS=$RK_NATIVE_TESTS names no class" >&2; exit 1; }
    # A name that matches nothing would be recorded as traced while contributing no trace, so the manifest
    # would over-count and the completeness gate would pass on a capture that is genuinely short. Reject the
    # typo here instead, against the same derivation the gate uses.
    unknown="$(comm -13 <(all_test_classes) <(printf '%s\n' "${slice[@]}" | sort -u))"
    [[ -z "$unknown" ]] || { echo "RK_NATIVE_TESTS names classes that are not compiled :cli tests:" >&2
                             echo "$unknown" >&2; exit 1; }
    filters=()
    for cls in "${slice[@]}"; do filters+=(--tests "$cls"); done
    ./build.sh -PrkNativeAgent "-PrkNativeAgentDir=$CFG" :cli:test "${filters[@]}"
    printf '%s\n' "${slice[@]}" >> "$MANIFEST"
    remaining="$(comm -23 <(all_test_classes) <(sort -u "$MANIFEST") | wc -l | tr -d ' ')"
    echo "slice traced: ${#slice[@]} class(es); $remaining of $(all_test_classes | wc -l | tr -d ' ') still untraced in $CFG"
    echo "run the remaining slices, then: RK_NATIVE_KEEP_CFG=1 RK_NATIVE_ASSEMBLE=1 $0"
    exit 0
  fi
  ./build.sh -PrkNativeAgent "-PrkNativeAgentDir=$CFG" :cli:test
  all_test_classes > "$MANIFEST"
fi

# Nothing below may run on a capture that traced only part of the suite. §3.5's shipped defect was exactly a
# capture whose coverage was bounded by a list, so the check is against the compiled denominator rather than
# against an intention: an unstamped partial capture is recoverable, a stamped one claims a freshness it does
# not have and the gate will believe it.
[[ -f "$MANIFEST" ]] || { echo "no traced-class manifest in $CFG — run at least one slice first" >&2; exit 1; }
untraced="$(comm -23 <(all_test_classes) <(sort -u "$MANIFEST"))"
if [[ -n "$untraced" ]]; then
  echo "refusing to assemble: $(printf '%s\n' "$untraced" | wc -l | tr -d ' ') compiled test class(es) were never traced:" >&2
  printf '%s\n' "$untraced" >&2
  exit 1
fi

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
\cp -f "$CFG/reachability-metadata.json" "$RAW_RECAPTURE"
./scripts/check-native-metadata-removals.sh "$BASELINE" "$RAW_RECAPTURE" "$REGRESSION_DIR/member-diff"
# Drop own-resource globs that ONLY a TEST resource tree provides. The agent records what production code
# ASKS for, including a test fixture loaded on the test classpath (`schema/broken-ref.schema.json`, reached
# through SpecKeys.forSchema) — the derived access filter removes test CLASSES and nothing removed test
# RESOURCES, so that glob shipped for months. Applied AFTER the removal check on purpose: the check should
# compare like with like against the baseline, and this strip is a stated policy rather than a loss.
# `NativeImageMetadataHygieneTest.everyOwnResourceGlobStillNamesAShippedResource` fails if one survives.
#
# The predicate is POSITIVE — strip only what we can place on a test tree — because this is the one stage
# whose output nothing else loss-checks (the removal check ran above, against the pre-strip file). Its earlier
# form was negative ("no main tree provides it") and a cold review showed that wrongly dropping a real
# resource was one missing root away: on a synthetic tree it dropped 4 globs where this drops 1, the three
# extras being an `adapter/`-provided resource, one produced by a GENERATED resources dir, and a wildcard
# pattern that `Path.exists()` can never resolve. Unresolvable now means KEEP, which is the safe direction.
python3 - "$CFG/reachability-metadata.json" <<'STRIP'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
data = json.loads(path.read_text())
repo = pathlib.Path(".")
def roots(kind):
    # Derived, never hand-listed: a hard-coded `cli` + `rulekit` pair silently strips whatever a module
    # outside it provides. Fail on an empty derivation — the silent-empty-fileTree false-green class.
    found = sorted(p for p in [*repo.glob(f"src/{kind}/resources"), *repo.glob(f"*/src/{kind}/resources")]
                   if p.is_dir())
    if not found:
        sys.exit(f"no {kind} resource root under {repo.resolve()} — refusing to strip against nothing")
    return found
MAIN, TEST = roots("main"), roots("test")
def provides(root, glob):
    # Case-EXACT: macOS resolves `schema/RESULT.schema.json` and the Linux native build does not, so
    # `Path.exists()` here would answer for a platform this metadata is not built on.
    candidate = root / glob
    return candidate.parent.is_dir() and candidate.name in {p.name for p in candidate.parent.iterdir()}
def strip(entry):
    glob = entry.get("glob", "")
    if not (glob.startswith("schema/") or glob.startswith("catalog/")):
        return False
    if any(ch in glob for ch in "*?["):
        return False    # a PATTERN, not a path: `provides` can only answer for a literal
    if any(provides(root, glob) for root in MAIN):
        return False
    return any(provides(root, glob) for root in TEST)
before = data.get("resources", [])
dropped = [entry.get("glob") for entry in before if strip(entry)]
data["resources"] = [entry for entry in before if not strip(entry)]
path.write_text(json.dumps(data, indent=2) + "\n")
print(f"stripped {len(dropped)} test-classpath own-resource glob(s): {', '.join(dropped) or '(none)'}")
STRIP

\cp -f "$CFG/reachability-metadata.json" "$SHIPPED_RECAPTURE"

\cp -f "${files[@]}" "$DEST/"   # \cp bypasses a `cp -i` alias that would prompt and skip the overwrite

# Now the hygiene assertion has the artifact it owns. Run it outside the agent so its test machinery does not
# become capture input; set -e makes either the traced suite or this post-copy check terminal.
./build.sh :cli:test -Pfast --tests 'io.github.mbackschat.a12.dm.cli.NativeImageMetadataHygieneTest'

# Record WHAT this capture was taken from, so a later stale capture cannot pass unnoticed. Written last, only
# on success, so a failed capture never leaves a stamp claiming freshness it did not establish. The stamp is
# committed alongside the metadata; `verifyNativeCaptureStamp` (run by the probe and ship gates) fails when the
# sources have moved on. NATIVE-IMAGE-GRAALVM §5.1.
#
# `-PrkCaptureInProgress` is what authorizes the mint at all: reaching this line means the traced suite, the
# removal check and the hygiene assertion all passed, and the task refuses a bare hand-run that can claim the
# same freshness with none of them behind it.
./build.sh -PrkCaptureInProgress :cli:nativeCaptureStamp

echo "metadata regression artifacts preserved in $REGRESSION_DIR"
echo "captured (test-suite) → $DEST/ ($(ls "$DEST" | tr '\n' ' '))"
