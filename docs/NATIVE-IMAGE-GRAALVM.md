# GraalVM native image — recipe, findings & status

The single durable home for everything about building the `dmtool` CLI as a **GraalVM native image**. Other docs point here and do not duplicate it:

- [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a — the release artifact (refers here for the recipe).
- [`CLI-SPEC.md`](CLI-SPEC.md) §11 — the packaging *design/decision* (refers here for build mechanics).
- [`RELEASE-PLAN.md`](RELEASE-PLAN.md) §8 — the open decisions this doc informs.
- `CLAUDE.md` "Build" — routes here before anyone touches the native build.

> **Why this is its own doc:** native-image for a kernel-embedding CLI is a substantial topic with non-obvious, interacting constraints (closed-world reflection, Groovy's `invokedynamic`, resource bundling, GraalVM-version quirks). It is **release-only** — never in the dev inner loop, which stays JVM/`installDist`.

## 1. Status (2026-06-14)

| Step | State |
|---|---|
| `org.graalvm.buildtools.native` plugin `1.1.1` on Gradle 9.0 | ✅ resolves + applies |
| Resource bundling (templates, catalog, schemas, version.properties) | ✅ verified (binary reads `catalog/operators.json` at runtime) |
| Startup (Jackson POJO reflection) | ✅ fixed via a tracing-agent `reflect-config.json` (1480 classes) — `--version`/manifest work |
| **Full verb reflection coverage** | ❌ `model validate` still `MissingReflectionRegistrationError` — kernel reflects beyond demo coverage |
| **Runtime-eval verbs** (`model eval`/`rule test`/`model compute`) | ❌ need Groovy runtime codegen — cannot go native |
| **Showboat verification gate** (all 6 demos vs the native binary) | ❌ 0/6 |
| **Shippable?** | **No — not yet.** |

Measured: builds in ~2 min, **52 MB** (no reflect-config) → **61 MB** (with it), GraalVM 21.0.11, `--gc=serial`.

## 2. The verified build recipe (re-applicable)

This config was applied + verified to the status above, then reverted from the tree (the native build isn't shippable, so it isn't committed). Re-apply it to `cli/build.gradle` to continue:

```gradle
plugins {
    id 'application'
    id 'org.graalvm.buildtools.native' version '1.1.1'   // resolves on Gradle 9
}

// ⚠️ MUST be native-classpath-only, NOT global — Groovy is required by the JVM runtime-eval verbs (§4).
// (The global form below was used only to test; it breaks model eval/rule test/model compute on the JVM.)
configurations.all { exclude group: 'org.codehaus.groovy' }     // <-- make native-only before committing

graalvmNative {
    toolchainDetection = false        // use the GraalVM at JAVA_HOME directly
    binaries {
        named('main') {
            imageName = 'dmtool'
            buildArgs.add('--no-fallback')
            buildArgs.add('--gc=serial')   // smaller than G1 (size lever)
        }
    }
}
```

Build: `JAVA_HOME=<a GraalVM 21> ./gradlew :cli:nativeCompile` → `cli/build/native/nativeCompile/dmtool`. picocli-codegen's reflection config is auto-consumed. Reconcile the output path with the `bin/dmtool` shim (it lists `cli/build/native/dmtool` / `build/native-spike/…`; add the `nativeCompile/dmtool` path).

## 3. The three requirements beyond the defaults

### 3.1 Drop Groovy (analysis blocker)
Groovy (`org.codehaus.groovy:groovy:3.0.25`, a kernel transitive) uses `invokedynamic` **mutable call sites**; native-image analysis fails hard:
```
Error: Unsupported method java.lang.invoke.MethodHandleNatives.setCallSiteTargetNormal(...) is reachable
       while parsing java.lang.invoke.MutableCallSite.setTarget(...)
```
Excluding Groovy fixes the analysis **and** is the biggest size lever ([`CLI-SPEC.md`](CLI-SPEC.md) §10). **But the exclusion must be native-classpath-only** — see §4 for why a global exclude is wrong.

### 3.2 Bundle resources (closed-world drops `getResourceAsStream`)
`-H:IncludeResources=` for `internal/templates/.*` (StringTemplate, used by `checkConsistency`), `catalog/.*\.json` (operator catalog), `schema/.*\.json` (CLI schema contracts), `verb-examples\.json`, `.*version\.properties`. Verified working. (The tracing agent's `resource-config.json` also captures these, so bundling that config covers resources too.)

### 3.3 Register reflection (the deep, unfinished problem)
picocli-codegen registers only picocli. The Jackson-deserialized POJOs (`OperatorEntry`/`OperatorCatalog`, schema POJOs, the result envelope, diagnostics, apply/batch op-records) — **and a wide swath of kernel internals** — need reflection registration, or the binary throws `MissingReflectionRegistrationError` (with `--no-fallback`). A tracing-agent capture over the demos (§5) fixes **startup** but is **not sufficient** for the full verb surface. Completing this needs: a far more exhaustive agent sweep (every verb × varied models/operators/diagnostics), and/or hand-authored reflect-config for the kernel's reflective hotspots, possibly across several iterations. **This is the main remaining effort.**

## 4. The hard ceiling — runtime-eval verbs cannot be native

`model eval` / `rule test` / `model compute` execute the kernel's **runtime validation** against a document instance, which **generates and loads Groovy classes at runtime** (`GroovyClassLoader.parseClass` — the agent captured `Com_mgmtp_a12_kernel_generated_*…CalculationController.class`). This is the [`CLI-SPEC.md`](CLI-SPEC.md) §11 hard ceiling, now concretely breached by verbs that grew past it. Consequences:

- Groovy is **genuinely required** by those 3 verbs — on the **JVM and** native. So the §3.1 exclusion **cannot be global** (it would break them on the JVM, the eval, and the CI showboat gate); it must be native-classpath-only.
- Even native-only, **the native binary cannot run those 3 verbs** (no Groovy → no validator codegen). A native build must **omit them from the command tree or make them error cleanly** ("JVM-only verb").
- The authoring/checking/structure/read surface — the #1 (agent) use case — is **unaffected**: `rule check`/`model validate` are *structural* (StringTemplate, native-OK), not runtime evaluation.

## 5. Generating the reflection config (tracing agent)

The showboat demos are the most thorough verb-surface exercise, so drive the agent with them:

```sh
CFG="/tmp/rk-native-config-$(date +%s)"; mkdir -p "$CFG"
export JAVA_HOME="<a GraalVM 21>"
JAVA_TOOL_OPTIONS="-agentlib:native-image-agent=config-merge-dir=$CFG" \
  sh -c 'for d in examples/cli-*.md; do uvx showboat@0.6.1 verify "$d" >/dev/null 2>&1; done'
# → reflect-config.json, resource-config.json, serialization/proxy/jni configs
cp "$CFG"/*.json cli/src/main/resources/META-INF/native-image/rulekit-agent/   # auto-detected on the classpath
```
Caveats: the capture only covers what the demos reach (hence §3.3's incompleteness); it captures the Groovy-generated runtime classes from `cli-runtime.md` (harmless once Groovy is native-excluded — the patterns match nothing).

## 6. Sizing & shrink levers

Base ~52 MB; the 1480-class reflect-config adds ~9 MB → ~61 MB. (CLI-SPEC §10's ~36 MB was a spike-era estimate on a smaller CLI.) Even so, native is **smaller** than the jpackage app-image (§10: ~65–90 MB/OS). Levers, biggest first:

- **`-Os`** (optimize for size) — **rejected by GraalVM 21.0.11** ("Invalid value 's' for option Optimize"); needs a **newer GraalVM** (§7).
- **`--strip-debug`** / strip symbols (before signing).
- **Drop more unused kernel jars** (kernel-core-runtime/codegen — §10).
- **Tighter reflect-config** (only needed classes, not the agent's full 1480).
- `--gc=serial` (applied; minor).
- `upx` (~50%) — **breaks macOS notarization + slows startup**; not for distribution.

Realistic with `-Os` + strip + jar-trim: ~40–45 MB.

## 7. GraalVM version notes

- **21.0.11** (currently installed at `/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.11+9.1/`): works, but **no `-Os`**, and warns *"Found typeReached condition … not supported by this version … considered always true; consider upgrading."*
- **A newer GraalVM (e.g. for JDK 25)** brings a newer native-image engine: better reflection auto-handling (eases §3.3), `-Os` (eases §6), `typeReached` support. Building a JDK-21-target binary on a JDK-25 GraalVM is fine. **It does NOT fix the Groovy/runtime-eval wall (§4)** — that is architectural, not a toolchain version.

## 8. The strategic question — B2 (native) vs B1 (jpackage)

Native is **not a quick win** for the current CLI: §3.3 reflection coverage is multi-cycle work, and §4 forces dropping 3 verbs. **jpackage (B1)** — a trimmed JVM + launcher — has *none* of these problems (full reflection, Groovy works, all verbs work) at the cost of size (~65–90 MB/OS) and startup (~600 ms vs ~37 ms). [`CLI-SPEC.md`](CLI-SPEC.md) §11's "proven feasible" predates the catalog Jackson loading and the runtime-eval verbs. **The live decision — invest in native (accepting the verb loss + grind) or ship B1 first — is tracked in [`RELEASE-PLAN.md`](RELEASE-PLAN.md) §8.**

## 9. Verification gate

Whatever ships, the native binary must pass the `examples/` showboat demos **byte-for-byte** before release ([`RELEASE-SPEC.md`](RELEASE-SPEC.md) §5) — that gate is what licenses the eval to run on the JVM launcher only. It currently fails 0/6 (the §3.3 + §4 gaps), which is the gate doing its job.
