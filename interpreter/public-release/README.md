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
- Self-contained model expansion, separate from evaluator preparation
- Lossless, versioned Document JSON with structured input diagnostics
- Deterministic model-shaped Document seeds containing every declared group and field
- Full and partial validation, retained computation application, support reports, and model-owned pointers
- Canonical A12 `DocumentPointer` formatting and parsing, so a repeated computed row is separately addressable
- Synchronous custom field validators and custom conditions
- Thread-safe model reuse: one prepared model serves concurrent requests, each with its own interpreter
- Explicit resource limits, integrity checks, cancellation, and prepared models that are immutable to callers
- The same Kotlin implementation on JVM, Node.js, and browser targets

The package does not author models, render forms, persist Documents, provide a virtual filesystem, or bundle Kernel code. Kernel-shaped TypeScript and JVM drop-in adapters are separate, later compatibility products.

**Semantics target.** This release reproduces the evaluation behavior of **A12 Kernel 30.8.1**, as shipped in the **A12 Tools 2025.06-ext5** distribution. The implementation is clean-room and is verified by differential testing against that kernel; the target version is stated so you can tell which kernel's semantics a given release was matched to.

<p align="center">
  <a href="assets/infographics/a12-interpreter-runtime.avif">
    <img src="assets/infographics/a12-interpreter-runtime.avif" alt="A12 Interpreter preparation, reusable request lifecycle, outputs, and Kernel conformance evidence" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

## How it fits the wider toolchain

Kotlin Multiplatform lets this one evaluation engine serve JVM, Node.js, browser, and native dmtool consumers. GraalVM is the delivery mechanism for the separate dmtool executable, not the source of Interpreter semantics. The real A12 Kernel does not ship in this package; it remains the model-acceptance authority in dmtool and the external oracle for the differential tests that verify this clean-room implementation.

<p align="center">
  <a href="assets/infographics/kotlin-graalvm-kernel-roles.avif">
    <img src="assets/infographics/kotlin-graalvm-kernel-roles.avif" alt="How Kotlin Multiplatform, GraalVM Native Image, and the A12 Kernel divide responsibilities around the A12 Interpreter" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

## Performance

**[→ Performance and footprint](PERFORMANCE.md)** — what the package costs to ship, how fast it validates in the browser and on the JVM, what a resident model costs, and how each figure was measured.

<p align="center">
  <a href="assets/infographics/interpreter-vs-kernel.avif">
    <img src="assets/infographics/interpreter-vs-kernel.avif" alt="Measured A12 Interpreter performance and resident memory compared with the A12 Kernel" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

## TypeScript and JavaScript

Install the npm-compatible tarball directly from the GitHub Release:

```sh
pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/@RELEASE_TAG@/a12-interpreter-@VERSION@.tgz
```

Documents are A12's own Document JSON — the shape the A12 Kernel serializes: a nested object tree, a repeating group as an array whose index is the repetition, native JSON for numbers and booleans.

Keep the prepared model and interpreter when a form validates repeated edits; read a new immutable Document snapshot for each change.

The Document shown below is decoded against the prepared model before evaluation. When its structure is not yet known, `prepared.documents.seedDocument()` creates a best-effort candidate with every declared group and field and one row per repeatable group; computation-owned targets remain present-empty for the engine to fill.

<!-- snippet: interpreter-typescript-start -->
```ts
import {
  type DocumentSnapshot,
  type Interpreter,
  ModelWorkspace,
  type A12Document,
} from "@mbackschat/a12-interpreter";

export function runInterpreter(rawModelJson: string) {
  const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
  const prepared = workspace.prepare("permit-basic");
  const starter = prepared.documents.encodeDocument(
    prepared.documents.seedDocument(),
  );
  const input: A12Document = {
    Permit: {ApplicationNo: "P-42", RequestedArea: 120},
  };
  const document = prepared.documents.readDocument(input);
  const interpreter = prepared.createInterpreter();
  const relevant = prepared.model.relevantPointer(
    "/Permit/RequestedArea",
    [1, 1],
  );

  return {
    full: interpreter.validateFull(document),
    partial: interpreter.validatePart(document, [relevant]),
    computation: interpreter.compute(document),
    starter,
    document,
    prepared,
  };
}
```

`interpreter.compute(source)` returns report-all `results`, target diagnostics for ERRORED results, eager `formalErrorsInOperands`, Kernel-equivalent `noErrorOccurred`, and source-relative `actions`. Retain that report and call `applyTo(destination)` to apply it without recomputing; source and destination must belong to the same `PreparedModel`. `unsupported` remains an independent support axis. `interpreter.applyComputations(source)` is the same-document convenience. Every snapshot remains immutable.

The ordinary form-engine sequence is explicit and Kernel-shaped: `const computation = interpreter.compute(document); const applied = computation.applyTo(document); const validation = interpreter.validateFull(applied);`. The form retains `applied`.

## Kotlin and Java

Resolve the first-party publication from this repository's [GitHub Pages Maven repository](https://mbackschat.github.io/a12-interpreter-releases/maven/). Third-party Kotlin dependencies continue to resolve from Maven Central.

```gradle
repositories {
    maven("https://mbackschat.github.io/a12-interpreter-releases/maven")
    mavenCentral()
}

dependencies {
    implementation("io.github.mbackschat.a12.dm:dm-interpreter:@VERSION@")
}
```

Java callers use the same JVM artifact; `ModelWorkspaceJava.collect` projects asynchronous model providers through `CompletionStage`.

The Kotlin and Java surface has the same shape as the TypeScript one: prepare a workspace, read a Document against the prepared model, then create an interpreter and ask it for validation or computation. The differences are idiomatic rather than structural — model sources are assembled through a typed builder, and `readDocument` takes A12 Document JSON as text.

<!-- snippet: interpreter-kotlin-start -->
```kotlin
import io.github.mbackschat.a12.dm.interpreter.ComputationReport
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.ValidationReport
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

data class InterpreterResult(
    val starterJson: String,
    val validation: ValidationReport,
    val computation: ComputationReport,
)

fun runInterpreter(modelJson: String): InterpreterResult {
    val source = WorkspaceModelSource.builder().json(modelJson).build()
    val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
    val starterJson = prepared.documents.encodeDocument(prepared.documents.seedDocument())
    val document = prepared.documents.readDocument(
        """{"Permit":{"ApplicationNo":"P-42","RequestedArea":120}}""",
    )
    val interpreter = prepared.createInterpreter()
    return InterpreterResult(
        starterJson = starterJson,
        validation = interpreter.validateFull(document),
        computation = interpreter.compute(document),
    )
}
```

`ValidationReport` and `ComputationReport` carry the same information as their TypeScript counterparts, including the model-owned pointers that tell apart the rows of a computation inside a repeatable group. Java consumers use these same types directly; a worked Java example lives in the developer API guide linked above.

One thing to carry over from *Server-side model cache* below: the `PreparedModel` returned by `prepare` is the expensive, reusable part and is safe to share across concurrent requests, while the `Interpreter` from `createInterpreter()` is per request. Retaining the prepared model and creating an interpreter per call is the intended pattern, not an optimization.

## Workspaces

`ModelWorkspace.fromSources` freezes a caller-supplied finite source set. `ModelWorkspace.collect` asks a caller-owned provider only for dependencies discovered from the entry model and loads each exact id at most once. Neither method performs implicit I/O.

`expand(entryModelId)` returns self-contained DM-JSON after resolving includes, imported type definitions, exclusions, ids, and cross-model references. `prepare(entryModelId)` continues through evaluator support checks and returns a reusable `PreparedModel`. Use `PreparedModel.loadExpandedJson` when that self-contained artifact is already available.

Preparation rejects ambiguous or incomplete workspaces instead of choosing a source-order winner. Errors carry stable structured diagnostics; provider exception messages, file paths, URLs, and credentials are not copied into them.

What matters for this repository is that the browser showcase uses that same public provider/workspace boundary. You can use its built-in examples or select your own local folder, model files, and Document; selected files remain in the browser.

## Server-side model cache

**A prepared model is shareable across concurrent requests; an interpreter is not.**

A `PreparedModel` owns everything derived from the model alone — condition and operation ASTs, iteration scopes,
uniqueness plans, the path index, the computation dependency order — memoized in thread-safe lazy state. One cache
entry therefore serves every tenant and every concurrent request for that model, whatever their configuration, and a
second interpreter over it performs no model-static work at all. In the shipped consumer example, 12 concurrent
requests across 4 threads over 3 models cost **3 preparations**.

An `Interpreter` is per request. That is a safety requirement, not a cost preference: it carries snapshot-scoped
state that custom conditions read, so sharing one across threads is unsupported.

Four things to know before sizing a cache:

- A resident prepared model is **larger than the DM-JSON it came from**, and the ratio moves with model SHAPE rather
  than size, so size the cache in **bytes, not entries**. The measured per-model cost and the structural budgeting
  rule are in [Performance and footprint](PERFORMANCE.md).
- An undersized cache is not slightly worse, it is no cache plus eviction overhead. The same 12 requests through a
  cache one entry too small cost **12 preparations and 10 evictions**.
- The cache is necessarily **in-process**: a prepared model is a live instance with a per-instance owner identity
  and no serialization, so only the expanded DM-JSON can live in a shared tier.

The complete pattern — cache keying, single-flight loading, eviction safety, and the trade-offs — is in
[the developer API guide](https://github.com/mbackschat/a12-interpreter-releases/blob/@RELEASE_TAG@/site/api/v@VERSION@/INTERPRETER-API-GUIDE.md).

## Compatibility and limits

The clean-room implementation is tested against the normative dynamic-Groovy Kernel strategy and against static Java wherever both Kernel strategies agree. JVM, Node.js, and packed-package Chrome consumers run the same portable behavior corpus.

The core is MIT-licensed and contains no Kernel bytecode or runtime dependency. Supported model time zones are `UTC`, `GMT`, and `Europe/Berlin`; all four A12 Date precisions are supported. `supportReport()` and the `unsupported` members on validation and computation results keep unsupported programs explicit; each entry's `subject` says whether it addresses a model element or a single Document cell.

<!-- source-statistics:interpreter-release:start -->
## Source statistics

Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from the standalone interpreter library, TypeScript facade, and browser showcase. Fixtures, generated output and build trees are excluded from every count.

### Production

The shipped product. Counted from `interpreter/src/commonMain/kotlin`, `interpreter/src/jvmMain/kotlin`, `interpreter/src/jsMain/kotlin`, `interpreter/typescript/src`, `interpreter/consumer-tests/browser/src`.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| Kotlin | 143 | 34855 | 21943 | 9971 | 2941 |
| TypeScript | 17 | 3794 | 3227 | 286 | 281 |

### Verification

Tests, laws, kernel differentials, fuzzers, conformance replay, and the external consumer programs compiled against the packed artifacts. Counted from `interpreter/src/commonTest/kotlin`, `interpreter/src/jvmTest/kotlin`, `interpreter/src/jsTest/kotlin`, `interpreter/consumer-tests/kotlin-jvm/src/main/kotlin`, `interpreter/consumer-tests/kotlin-js/src/jsMain/kotlin`, `interpreter/consumer-tests/typescript/src`, `interpreter/consumer-tests/browser/scripts`.

| Language | Files | Lines | Code | Comments | Blanks |
|---|---:|---:|---:|---:|---:|
| Kotlin | 346 | 38009 | 29923 | 4020 | 4066 |
| TypeScript | 15 | 3399 | 3178 | 75 | 146 |

Maintainers regenerate these tables with the local statistics updater; both release publishers compare them with fresh counts before any public mutation.
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
