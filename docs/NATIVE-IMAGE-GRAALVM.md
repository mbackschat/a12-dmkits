# GraalVM native image — recipe, findings & status

The single durable home for building the `dmtool` CLI as a **GraalVM native image**. Other docs point here and don't duplicate it:

- [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §3a — the release artifact. · [`CLI-SPEC.md`](CLI-SPEC.md) §11 — packaging *design/decision*. · [`RELEASE-PLAN.md`](RELEASE-PLAN.md) §8 — open decisions. · `CLAUDE.md` "Build" — routes here.

> **Why its own doc:** native-image for a kernel-embedding CLI has non-obvious, interacting constraints (closed-world reflection, Groovy's `invokedynamic`, resource bundling, GraalVM-version quirks). It is **release-only** — never in the dev inner loop (that stays JVM/`installDist`), never in CI.

## 1. Status (2026-06-14, GraalVM **25.0.3**)

| Aspect | State |
|---|---|
| Toolchain | **GraalVM for JDK 25** (`graalvm-25.jdk`), plugin `org.graalvm.buildtools.native:1.1.1`, Gradle 9 |
| Build | ✅ ~45–60 s |
| Size | ✅ **19 MB** bare, **31 MB** with reflection metadata — both via `-Os` (GraalVM 25 only) |
| Groovy | ✅ compiles on 25 (no analysis failure), but **excluded from the native classpath** (startup-init issue, §3.1) → JVM keeps it |
| **Showboat gate** | **5 / 6 demos PASS** — only `cli-runtime.md` fails (the runtime-eval verbs, §4) |
| Reflection coverage | ⚠️ **capture-bound** — the demo surface works, but inputs outside it (e.g. `model validate` on a model whose operators weren't exercised) still hit `MissingReflectionRegistrationError` (§3.3) |
| Runtime-eval verbs (`model eval`/`rule test`/`model compute`) | ❌ need Groovy → cannot go native (§4) |
| **Shippable?** | **Not yet** — needs comprehensive reflection coverage (§3.3) + a decision on the runtime-eval verbs (§4). But the authoring/check/structure/read surface (the #1 agent use case) **works**. |

**GraalVM 25 vs 21 — 25 is decisively better and is the toolchain going forward:**

| | GraalVM 21.0.11 | GraalVM **25.0.3** |
|---|---|---|
| Groovy `invokedynamic` | ❌ analysis fails (`MutableCallSite`) | ✅ compiles |
| `-Os` size optimization | ❌ "Invalid value 's'" | ✅ → 19 MB |
| Reflection metadata format | `reflect-config.json` (multi-file) | unified `reachability-metadata.json` |
| Demo gate | 0/6 | **5/6** |
| Build time / size | ~2 min / 52–61 MB | **~45–60 s / 19–31 MB** |
| `typeReached` warning | yes | cleared |

## 2. Build (committed, opt-in)

```sh
export GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home   # see the GOTCHA below
export JAVA_HOME="$GRAALVM_HOME"
export GRADLE_USER_HOME="$(cd .. && pwd)/.gradle-isolated"
./gradlew :cli:nativeCompile        # → cli/build/native/nativeCompile/dmtool (~31 MB)
```

The `:cli` build file carries: the plugin, `graalvmNative { toolchainDetection = false; binaries.main { imageName='dmtool'; buildArgs += [--no-fallback, -Os, IncludeResources…] } }`, and a **native-only** Groovy exclusion (`configurations.named('nativeImageClasspath') { exclude group: 'org.codehaus.groovy' }`). The reflection metadata is bundled at `cli/src/main/resources/META-INF/native-image/rulekit-agent/reachability-metadata.json` (auto-detected).

> **GOTCHA — `GRAALVM_HOME` wins over `JAVA_HOME`.** The plugin (with `toolchainDetection=false`) resolves the GraalVM via **`GRAALVM_HOME`** first. If it points at an old GraalVM (e.g. a stale `…/graalvm-jdk-21…`), the build uses *that* regardless of `JAVA_HOME` — failing with *"native-image wasn't found / JDK isn't a GraalVM distribution."* Export `GRAALVM_HOME` to the GraalVM 25 home (and `./gradlew --stop` to drop a stale daemon).

## 3. The three requirements beyond the defaults

### 3.1 Exclude Groovy — native-classpath-only
Groovy (`org.codehaus.groovy:groovy:3.0.25`, a kernel transitive) compiles on GraalVM 25, but **picocli's `DefaultFactory.loadClosureClass` initializes `groovy.lang.Closure` at build time** → `GroovySystem.<clinit>` reads Groovy's DGM metadata stream, which is **closed at runtime** (`java.io.IOException: Stream closed`). Excluding Groovy makes picocli skip Closure loading and startup works. **The exclusion MUST be native-only** (`nativeImageClasspath`, not `configurations.all`) — a global exclude breaks the JVM runtime-eval verbs, the eval, and the CI showboat gate. Verified: `:cli:test` stays green with the native-only form. (On GraalVM 21 the failure was different — `invokedynamic`/`MutableCallSite` broke *analysis* — but the conclusion is the same: exclude Groovy from native.)

### 3.2 Bundle resources
Closed-world drops anything read via `getResourceAsStream`, so `-H:IncludeResources=` for `catalog/.*\.json` (operator catalog), `internal/templates/.*` (StringTemplate, used by `checkConsistency`), `schema/.*\.json`, `verb-examples\.json`, `.*version\.properties`. Verified: the binary reads `catalog/operators.json` once these are set (a missing one fails as `IllegalStateException: … resource not found`).

### 3.3 Register reflection — the remaining robustness gap
Neither picocli-codegen nor GraalVM 25's auto-handling covers the Jackson-deserialized POJOs (`OperatorEntry` &c.) or the kernel's reflective hotspots — without config the binary throws `Cannot construct instance of OperatorEntry … configure reflection`. A **tracing-agent** capture over the showboat demos (§5) → `reachability-metadata.json` gets the **demo surface** working (5/6). **But coverage is capture-bound:** a direct `model validate` on a model whose operators/structures the demos didn't exercise still throws `MissingReflectionRegistrationError`. Closing this for arbitrary real-world input needs a **far more exhaustive capture** (many models × operators × verbs) and/or hand-authored metadata for the kernel's reflective hotspots. **This is the main work remaining before a robust release.**

## 4. The hard ceiling — runtime-eval verbs can't go native

`model eval` / `rule test` / `model compute` execute the kernel's **runtime validation** against a document instance, which **generates and loads Groovy classes at runtime** (`GroovyClassLoader.parseClass`; the agent captured `Com_mgmtp_a12_kernel_generated_*` classes). This is [`CLI-SPEC.md`](CLI-SPEC.md) §11's hard ceiling, breached by verbs that grew past it. Groovy is genuinely required by these 3 verbs (JVM and native), so: the §3.1 exclusion must stay native-only (JVM keeps them), and **the native binary cannot run them** (no Groovy → no runtime codegen). A release must omit them from the native command tree or make them error cleanly ("JVM-only verb"). The authoring/check/structure/read surface — `rule check`/`model validate` are *structural* (StringTemplate, native-OK) — is unaffected. (`cli-runtime.md` is the one demo that fails the gate.)

## 5. Generating the reflection metadata (tracing agent)

Run the agent on a **Groovy-present JVM** (the native-only exclusion means `installDist` keeps Groovy), driven by the showboat demos:
```sh
export GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home; export JAVA_HOME="$GRAALVM_HOME"
./gradlew :cli:installDist
CFG="/tmp/rk-ncfg"; mkdir -p "$CFG"
JAVA_TOOL_OPTIONS="-agentlib:native-image-agent=config-merge-dir=$CFG" \
  sh -c 'for d in examples/cli-*.md; do uvx showboat@0.6.1 verify "$d" >/dev/null 2>&1; done'
cp "$CFG"/*.json cli/src/main/resources/META-INF/native-image/rulekit-agent/   # GraalVM 25 → reachability-metadata.json
```
The demos only reach what they exercise — hence §3.3's capture-bound coverage. A robust release wants a much wider input sweep here.

## 6. Sizing & shrink levers

`-Os` (GraalVM 25) is the big lever: **19 MB** bare, **31 MB** with the reflection metadata (the metadata costs ~12 MB). Far below the GraalVM-21 numbers (52–61 MB) and well below the jpackage app-image (§10: ~65–90 MB/OS). Further levers if needed: `--strip-debug`, drop more unused kernel jars, tighter (hand-authored vs full-agent) metadata. `upx` (~50%) is possible but **breaks macOS notarization + slows startup** — not for distribution.

## 7. The strategic question — B2 (native) vs B1 (jpackage)

GraalVM 25 changed the calculus sharply. Native is now **fast (~50 s), small (~19–31 MB), and works for the whole authoring/check/structure/read surface (5/6 demos)** — attractive for an agent-first, latency-sensitive CLI (~37 ms start). Two things still gate a robust native release:

1. **Reflection coverage** (§3.3) — bounded, tractable work (comprehensive capture), but real.
2. **The 3 runtime-eval verbs** (§4) — architecturally JVM-only; native must drop/stub them.

**jpackage (B1)** still "just works" for *all* verbs with no reflection grind, at the cost of size (~65–90 MB/OS) and startup (~600 ms). The live decision — finish native (accepting #1's work + #2's verb loss) or ship B1 first — is in [`RELEASE-PLAN.md`](RELEASE-PLAN.md) §8. Given GraalVM 25's results, native is now the stronger candidate for the primary use case; B1 remains the fallback for full-verb coverage.

## 8. Verification gate

The native binary must pass the `examples/` showboat demos **byte-for-byte** before release ([`RELEASE-SPEC.md`](RELEASE-SPEC.md) §5) — that gate is what licenses the eval to run on the JVM launcher only. Current: **5/6** (all but `cli-runtime.md`). The gate correctly blocks shipping until the runtime-eval verbs are handled and reflection coverage is comprehensive.
