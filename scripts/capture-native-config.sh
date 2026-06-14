#!/usr/bin/env bash
# Comprehensive native-image reflection capture (docs/NATIVE-IMAGE-GRAALVM.md §5).
#
# Drives the production CLI (bin/dmtool → installDist JVM, Groovy present) under the GraalVM
# native-image tracing agent across the WHOLE fixture surface + a spread of rule-check conditions,
# so the captured reachability-metadata.json covers far more than the 6 showboat demos. Re-run when
# the kernel is bumped, the CLI/catalog surface changes, or a reflection-using dependency bumps —
# the native gate (showboat + the fixture robustness check) tells you when it's gone stale.
#
# Usage:  GRAALVM_HOME=/path/to/graalvm-25 ./eval/capture-native-config.sh
# Then:   ./gradlew :cli:nativeCompile   (the merged metadata is bundled under cli/src/main/resources)
set -euo pipefail

: "${GRAALVM_HOME:?set GRAALVM_HOME to a GraalVM 25 home}"
export JAVA_HOME="$GRAALVM_HOME"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$(cd .. && pwd)/.gradle-isolated}"

MODELS="cli/src/test/resources/models"
DEST="cli/src/main/resources/META-INF/native-image/rulekit-agent"
CFG="${RK_NATIVE_CFG:-/tmp/rk-native-capture}"
mkdir -p "$DEST" "$CFG"

./gradlew -q :cli:installDist   # Groovy present (native-only exclusion); the agent traces the real verbs

run() { JAVA_TOOL_OPTIONS="-agentlib:native-image-agent=config-merge-dir=$CFG" bin/dmtool "$@" >/dev/null 2>&1 || true; }

# 1. every fixture, every read/validate verb (top-level models)
for m in "$MODELS"/*.dm.json; do
  run -m "$m" model validate
  run -m "$m" model describe
  run -m "$m" model info
  run -m "$m" export
done
# 2. multi-file + inheritance models need a workspace root
run -w "$MODELS/multifile" -m "$MODELS/multifile/app/storefront.dm.json" model validate
run -w "$MODELS/multifile" -m "$MODELS/multifile/app/storefront.dm.json" model describe
run -w "$MODELS/inheritance" -m "$MODELS/inheritance/ProductSingle.dm.json" model validate
run -w "$MODELS/inheritance" -m "$MODELS/inheritance/ProductBundle.dm.json" model validate
# 3. self-describing surface (catalog/schema/manifest/diagnostics — their own Jackson POJOs)
run manifest; run operators; run diagnostics; run schema result
for id in $(bin/dmtool operators 2>/dev/null | grep -oE '"id" *: *"[^"]+"' | sed 's/.*"\([^"]*\)"$/\1/'); do
  run operators "$id"
done

# 4. the showboat demos — REQUIRED. They cover the end-to-end mutating paths (apply/edit/structure) AND
# the ERROR paths (e.g. a version-incompatible model → the kernel i18n ErrorMessages ResourceBundle).
# Validating only *valid* fixtures (steps 1–2) misses those; without this the version/apply/edit demos
# regress to MissingResourceException / output mismatch. Drive them under the same agent (merges in).
JAVA_TOOL_OPTIONS="-agentlib:native-image-agent=config-merge-dir=$CFG" \
  sh -c 'for d in examples/cli-*.md; do uvx showboat@0.6.1 verify "$d" >/dev/null 2>&1; done'

\cp -f "$CFG"/*.json "$DEST/"   # \cp bypasses a `cp -i` alias that would prompt and skip the overwrite
echo "captured → $DEST/ ($(ls "$DEST" | tr '\n' ' '))"
