# A12 Interpreter releases

This repository is the public distribution of the independent, kernel-free A12 Document Model interpreter. Development happens in the main [`a12-dmkits` project](https://github.com/mbackschat/a12-dmkits); this mirror contains immutable release assets, the cumulative Maven repository, versioned API references, and the browser showcase.

> **Independent project.** This is not an official A12 or mgm artifact.

## Try it in your browser

**[Open the A12 Interpreter browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)**

Use the built-in models and Documents or load your own local workspace. Your selected files stay in the browser.

## Documentation

- 🚀 **[Try the live browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)**
- 📘 **[Read the developer API guide →](https://github.com/mbackschat/a12-interpreter-releases/blob/@RELEASE_TAG@/site/api/v@VERSION@/INTERPRETER-API-GUIDE.md)**
- 🧭 **[Browse the TypeScript API →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/typescript/)**
- 🧭 **[Browse the Kotlin API →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/kotlin/)**
- 🏠 **[Open the public release site →](https://mbackschat.github.io/a12-interpreter-releases/)** — current version, installation coordinates, showcase, API references, and privacy information
- 🛠️ **[Explore the main `a12-dmkits` project →](https://github.com/mbackschat/a12-dmkits)** — source, design, benchmark method, and complete test evidence

## What ships

- Finite in-memory workspaces and on-demand model providers
- Include mounting, imported type definitions, id normalization, and cross-model reference remapping
- Self-contained expanded model output before evaluator-specific support checks
- Lossless, versioned Document JSON with structured input diagnostics
- Full and partial validation, computations, support reports, and model-owned pointers
- Custom field validators and custom conditions
- Thread-safe model reuse: one prepared model serves concurrent requests, each with its own interpreter
- Resource limits, source integrity, cancellation, and prepared models that are immutable to callers

The package performs no implicit filesystem, network, storage, or credential access. It contains no A12 Kernel bytecode or runtime dependency.

**Semantics target.** This release reproduces the evaluation behavior of **A12 Kernel 30.8.1**, as shipped in the **A12 Tools 2025.06-ext5** distribution. The implementation is clean-room and is verified by differential testing against that kernel; the target version is stated so you can tell which kernel's semantics a given release was matched to.

## Performance

**Fast, and measured — never assumed.** The same engine runs on the JVM, Node.js and in the browser, and the numbers below come from two different runtimes — browser latency measured in system Chrome, cross-engine comparison and memory measured on the JVM. Each block says which. Browser figures are measured in a real browser, never inferred from Node.js.

### What you ship

What you actually ship, per target:

| | Browser / Node.js | Kotlin / JVM |
|---|---|---|
| Inside a bundled app | **240 kB gzip** entry, tree-shaken and minified | — |
| Third-party runtime dependencies | `big.js` (≈7 kB, bundled in) | Kotlin stdlib, kotlinx-serialization |
| Published artifact | 2.5 MB npm package — Kotlin runtime vendored in | 1.6 MB jar — Kotlin runtime resolved separately |

**Published artifact** and **inside a bundled app** measure different things, which is why they look so far apart: the published artifact is the whole library as distributed, while the bundled figure is what survives into an application after tree-shaking, minification and compression. The jar is the closer analogue of the npm package — and the npm package is in fact the larger of the two, because it vendors the Kotlin standard library and serialization runtime that the JVM resolves as ordinary dependencies.

No Kernel code ships in either target and neither declares a Kernel runtime dependency, which is what keeps the core MIT-licensed.

For scale, the A12 Kernel's own evaluation runtime, resolved from the 30.8.1 artifacts:

| A12 Kernel runtime | Jars | Size |
|---|---|---|
| Generated-Java strategy | 3 | **702 kB** |
| Document-model runtime service (dynamic Groovy) | 15 | **1 579 kB** |

*Declared first-level `com.mgmtp` dependencies only — the transitive closure is not walked, and Groovy itself, `slf4j`, `commons-lang3` and `base-model-*` are excluded, so both figures are lower bounds.*

The two are not directly substitutable, and the difference is structural rather than a matter of bytes. **The Kernel's runtime is a fixed library plus per-model generated code**: every Document Model is transformed into generated rule and computation classes that ship alongside it, so what a deployment actually carries grows with the number of models. The interpreter generates nothing — the artifact above is the complete cost for any number of models, which is also why its resident memory per model is a fraction of either Kernel strategy.

### How fast it runs (study)

Measured in system Chrome, over the two Documents the collector defines — one representative, one stress. Each is timed **two ways**, because an application experiences both: the **first answer** a user waits for when a form opens, which includes building the interpreter, and the **warm** cost of revalidating after every keystroke, which does not.

| Document | Findings | First answer *(includes building the interpreter)* | Warm *(validation only)* |
|---|---|---|---|
| 200 rows — representative | — | **11.55 ms median** | **1.1 ms** |
| 10,000 rows — stress | 4,900 | **166.1 ms median** | **53.3 ms** |

First answers drawn to scale — the stress Document carries **50× the rows and costs 14× the time**:

```
   200 rows  ███                                       11.55 ms
10,000 rows  ████████████████████████████████████████  166.1 ms
```

Every first answer is the median of independent fresh Chrome processes — 10 for a stable series, automatically extended to 50 for a noisy one — clocked from interpreter construction through complete result materialization. The warm figure is the median of repeated validations against an interpreter built outside the clock. No warm-up credit, no partial results, no discarded samples.

The warm column is the timed region the JVM lane uses, so those runtimes and engines can be put side by side on the same 10,000-row Document:

| Engine and runtime | 10,000 rows, 4,900 findings |
|---|---|
| Interpreter — browser (Chrome) | 53.3 ms |
| **Interpreter — JVM** | **17.5 ms** |
| A12 Kernel, generated Java — JVM | 695.4 ms |
| A12 Kernel, dynamic Groovy — JVM | 794.7 ms |

Every row is validation only against an already-prepared engine and decoded Document. On the JVM, where all three engines can be compared directly, the interpreter is about **40× the A12 Kernel's generated Java** and about **45× its dynamic Groovy** on this Document. The browser row sits above them for scale: the same engine, in a browser, still finishes ahead of either Kernel strategy running on the JVM.

### Further results (JVM)

Measured on JDK 21 against both A12 Kernel evaluation strategies. The baseline is deliberately the toughest one available: the A12 Kernel's own **generated validator compiled as Java**, with code generation outside the clock. Each row says how many times faster the interpreter finished the same work.

| Suite | Interpreter is faster by — **JVM** |
|---|---|
| Default inventory, 15 cases | **2.8× – 17.1×** |
| Repeated-document evaluation | **12.2× – 16.8×** |
| Huge-document edge, 20,000 rows | **≈152×** |
| Hardest case — 384 concentrated low-firing rules | **1.73×** |

The spread does not track model size. The widest margins were measured on computation-heavy and repeated-document scenarios; the narrowest, 1.73×, on 384 concentrated rules that almost never fire.

**Not one eligible comparison went the other way.** Across the default, repeated, rule-count and edge suites, neither generated Java nor dynamic Groovy won a single case — the hardest case above is the interpreter's narrowest win, not a loss.

### What it costs to keep resident on the JVM

Memory goes the same direction, which is what decides how many models a server can hold in a cache. Holding one prepared model resident on JDK 21, across six models from type-definition-only to 192 rules, the last derived from a real model's shape:

| Engine | Resident cost per model — **JVM** | vs the interpreter |
|---|---|---|
| **Interpreter** — prepared model | **54 – 325 kB** | — |
| A12 Kernel, generated Java | 379 kB – 1.46 MB | **4.2× – 8.1×** more |
| A12 Kernel, dynamic Groovy | 1.09 – 11.98 MB | **18.5× – 37.9×** more |

The Kernel's own expanded Document Model is comparable in size to a prepared model; what separates them is the generated rule and computation code — and a fifth to a half of that is class metadata in Metaspace, which a heap measurement does not see at all. All three engines are first confirmed to produce identical findings over the identical Document, so this compares engines doing equal work.

For sizing a model cache, a resident prepared model costs about **1.4 kB per field plus 1.55 kB per rule plus 2.3 kB per group**, within 4% across all six.

The interpreter runs the same clean-room Kotlin implementation on the JVM, Node.js, and in the browser. The qualified browser package replays 33 portable conformance cases, and the [live showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/) proves the packed package in system Chrome rather than inferring browser support from Node.js. These are bounded same-machine measurements after exact result preflight, not universal promises.

<sub>Measured 2026-08-17 · Apple M1 · Chrome 151 · JDK 21 · no discarded samples · sizing fit from an earlier sweep · [method, timing boundaries and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md)</sub>

## TypeScript and JavaScript

Install the npm-compatible tarball directly from the GitHub Release:

```sh
pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/@RELEASE_TAG@/a12-interpreter-@VERSION@.tgz
```

```ts
import {
  ModelWorkspace,
  type A12Document,
} from "@mbackschat/a12-interpreter";

const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
const prepared = workspace.prepare("permit-basic");
const input: A12Document = {
  Permit: {ApplicationNo: "P-42", RequestedArea: 120},
};
const document = prepared.documents.readDocument(input);
const result = prepared.createInterpreter().validateFull(document);
```

Documents are A12's own Document JSON — the shape the A12 Kernel serializes: a nested object tree, a repeating group as an array whose index is the repetition, native JSON for numbers and booleans.

Keep the prepared model and interpreter when a form validates repeated edits; read a new immutable Document snapshot for each change.

## Kotlin and Java

Resolve the first-party publication from this repository's [GitHub Pages Maven repository](https://mbackschat.github.io/a12-interpreter-releases/maven/). Third-party Kotlin dependencies continue to resolve from Maven Central.

```kotlin
repositories {
    maven("https://mbackschat.github.io/a12-interpreter-releases/maven")
    mavenCentral()
}

dependencies {
    implementation("io.github.mbackschat.a12.dm:dm-interpreter:@VERSION@")
}
```

```kotlin
val source = WorkspaceModelSource.builder().json(rawModelJson).build()
val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
val document = prepared.documents.readDocument(
    """{"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""",
)
val report = prepared.createInterpreter().validateFull(document)
```

Java callers use the same JVM artifact; `ModelWorkspaceJava.collect` projects asynchronous model providers through `CompletionStage`.

## Workspaces

`ModelWorkspace.fromSources` freezes a caller-supplied finite source set. `ModelWorkspace.collect` asks a caller-owned provider only for dependencies discovered from the entry model and loads each exact id at most once.

`expand(entryModelId)` resolves includes and imported type definitions into self-contained DM-JSON. `prepare(entryModelId)` continues through evaluator support checks and returns a reusable `PreparedModel`. Ambiguous, cyclic, incomplete, oversized, or integrity-invalid workspaces fail with structured diagnostics rather than a source-order guess.

The browser showcase uses this same public provider/workspace boundary. You can use its built-in examples or select your own local folder, model files, and Document; selected files remain in the browser.

<!-- source-statistics:interpreter-release:start -->
## Source statistics

Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from the standalone interpreter library, TypeScript facade, and browser showcase. Tests, fixtures, generated output, build trees, and code embedded in Markdown are excluded.

| Language | Files | Code | Comments | Blanks |
|---|---:|---:|---:|---:|
| Java | 0 | 0 | 0 | 0 |
| Kotlin | 114 | 18703 | 7575 | 2367 |
| TypeScript | 17 | 3190 | 269 | 277 |

Maintainers regenerate this table with the local statistics updater; both release publishers compare it with fresh counts before any public mutation.
<!-- source-statistics:interpreter-release:end -->

## Release contents

This release contains:

- `a12-interpreter-@VERSION@.tgz` — npm-compatible TypeScript/JavaScript package with the compiled browser/Node runtime, declarations, source maps, README, and license.
- `dm-interpreter-@VERSION@-maven-repository.zip` — complete offline Maven repository containing the Kotlin Multiplatform, JVM, and JS artifacts, Gradle metadata, POMs, documentation JARs, source JARs, and checksums.
- `dm-interpreter-@VERSION@-api-docs.zip` — versioned developer guide plus generated TypeScript and Kotlin API references for offline use or static hosting.
- `dm-interpreter-@VERSION@-showcase.zip` — deployable static browser showcase, including its built-in example workspaces, Documents, conformance data, and measured bundle metadata.
- `release-manifest.json` — machine-readable release identity, source tag and commit, package coordinates, supported targets, artifact sizes, and SHA-256 digests.
- `SHA256SUMS` — checksum list for verifying every release payload and the manifest.

Published GitHub Release assets and versioned Maven/API directories are immutable. A defect is fixed in a later patch release rather than by replacing a published artifact.

## License and privacy

The interpreter core and showcase are licensed under the [MIT License](LICENSE). See the [independence disclaimer](DISCLAIMER.md) and the showcase's [site-specific privacy notice](https://mbackschat.github.io/a12-interpreter-releases/privacy.html).
