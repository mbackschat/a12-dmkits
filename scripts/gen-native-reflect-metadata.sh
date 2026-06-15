#!/usr/bin/env bash
# Regenerate the RULE-BASED native-image reflection metadata (docs/NATIVE-IMAGE-GRAALVM.md §3.4).
#
# WHY this exists, and why it is NOT the tracing-agent capture:
#   Two kernel surfaces are reached by NAME at runtime, so GraalVM's static analysis cannot see them and
#   the tracing agent only records the *shapes a traced run happens to exercise* (capture-bound — the
#   whack-a-mole that produced the patterned-field + parser-error bugs):
#     1. Jackson deserialization of the field-type / model POJOs  (FieldtypeDeserializer reflects setters)
#     2. StringTemplate codegen during checkConsistency            (ObjectModelAdaptor reflects getters by name)
#   Both live in BOUNDED, ENUMERABLE packages. Registering every class in them by RULE covers ALL model
#   shapes — not just the ones a capture run touched — so a new field constraint / construct can't
#   reintroduce a MissingReflectionRegistrationError or StringTemplate STNoSuchProperty on native.
#
# RELATION to capture-native-config.sh: that script is the AGENT capture for the rest of the reflective
#   surface (operator entries, parser, …). THIS script is the rule-based registration for the two packages
#   above. Both are merged at build (separate META-INF/native-image/<id>/ dirs). On a kernel bump, RE-RUN BOTH.
#
# Output: cli/src/main/resources/META-INF/native-image/rulekit-manual/reachability-metadata.json (committed).
# Usage:  ./scripts/gen-native-reflect-metadata.sh    (reads the pinned kernel jars from mavenLocal)

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KVER="$(grep -oE "kernelVersion *= *'[^']+'" "$ROOT/build.gradle" | grep -oE "[0-9][0-9.]+")"
M2="$HOME/.m2/repository/com/mgmtp/a12/kernel"
OUT="$ROOT/cli/src/main/resources/META-INF/native-image/rulekit-manual/reachability-metadata.json"

# The reflective-surface packages to register WHOLESALE (slash form, trailing slash). Extend ONLY if the
# native gate surfaces a MissingReflection/STNoSuchProperty in a class outside these — see §3.4.
PACKAGES=(
  "com/mgmtp/a12/kernel/md/model/a12internal/"            # Jackson-deserialized model + field-type POJOs
  "com/mgmtp/a12/kernel/core/codegen/internal/generator/" # StringTemplate codegen backing beans (checkConsistency)
)

[[ -d "$M2" ]] || { echo "kernel not in mavenLocal ($M2) — bootstrap it first" >&2; exit 2; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
JARS=$(find "$M2" -name "*-${KVER}.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar")
[[ -n "$JARS" ]] || { echo "no kernel jars for version $KVER under $M2" >&2; exit 2; }
for j in $JARS; do unzip -o -q "$j" -d "$WORK" '*.class' 2>/dev/null || true; done

PKGS_CSV="$(IFS=,; echo "${PACKAGES[*]}")" KJ="$WORK" OUT="$OUT" KVER="$KVER" python3 - <<'PY'
import os, json
kj=os.environ["KJ"]; out=os.environ["OUT"]; kver=os.environ["KVER"]
pkgs=tuple(os.environ["PKGS_CSV"].split(","))
types=set()
for root,_,files in os.walk(kj):
    for f in files:
        if not f.endswith(".class"): continue
        rel=os.path.join(root,f)[len(kj)+1:]
        if rel.startswith(pkgs):
            types.add(rel[:-6].replace("/","."))
refl=[{"type":t,"allDeclaredMethods":True,"allDeclaredConstructors":True,"allDeclaredFields":True}
      for t in sorted(types)]
os.makedirs(os.path.dirname(out),exist_ok=True)
json.dump({"reflection":refl}, open(out,"w"), indent=1)
open(out,"a").write("\n")
print(f"kernel {kver}: wrote {len(refl)} reflection entries to {out}")
for p in pkgs:
    print(f"  {p:55s} {sum(1 for t in types if p.replace('/','.') in t+'.')} classes")
PY
echo "Rebuild + verify:  GRAALVM_HOME=…/graalvm-25 ./gradlew :cli:nativeCompile && ./scripts/native-gate.sh"
