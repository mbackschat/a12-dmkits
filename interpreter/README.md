# A12 Interpreter

A **Kotlin Multiplatform** engine for A12 Document Models: **one implementation, three targets — JVM, Node.js and browser** — with no A12 Kernel at runtime.

The interpreter prepares model workspaces, resolves includes and imported type definitions, reads Documents, runs full or partial validation, and computes derived values. Applications keep control of file, network, storage, and credential access.

## Features

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

## Footprint and performance

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

For scale, the A12 Kernel's own evaluation runtime, resolved from the Kernel artifacts this release targets:

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

Memory goes the same direction, which is what decides how many models a server can hold in a cache. Holding one prepared model resident on JDK 21, across six models from type-definition-only to 192 rules:

| Engine | Resident cost per model — **JVM** | vs the interpreter |
|---|---|---|
| **Interpreter** — prepared model | **54 – 325 kB** | — |
| A12 Kernel, generated Java | 379 kB – 1.46 MB | **4.2× – 8.1×** more |
| A12 Kernel, dynamic Groovy | 1.09 – 11.98 MB | **18.5× – 37.9×** more |

The interpreter generates no code, so its cost is entirely heap. Both kernel strategies generate and load classes per model, and that metadata lives in Metaspace — invisible to a heap-only measurement, so all three arms report heap, Metaspace and loaded-class count. Every arm validates the identical Document and must agree on the message codes before it is measured, so this compares engines doing equal work.

Browser execution is a first-class qualified target: the packed npm tarball is installed into an external consumer, its portable behavior corpus is replayed in system Chrome, and that same package powers the [live browser showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/). These are bounded same-machine measurements taken after an exact result preflight, not universal promises.

<sub>Measured 2026-08-17 · Apple M1 · Chrome 151 · JDK 21 · no discarded samples · [method, timing boundaries and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md)</sub>

## Server-side model cache

*Every number in this section is **JVM**.*

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
  than size, so size the cache in **bytes, not entries**. Budget it from structure instead:
  **≈ 1.4 kB × fields + 1.55 kB × rules + 2.3 kB × groups**, which holds within 4% across six models spanning 5 to 79
  fields, 3 to 29 groups and 0 to 192 rules.
- It is nonetheless far **smaller than the A12 Kernel runtime service it replaces**: on those same models,
  **4.2× to 8.1×** smaller than the Kernel's generated Java and **18.5× to 37.9×** smaller than its dynamic Groovy.
  The Kernel's own Document Model is comparable to a prepared model; the difference
  is the generated rule code — and a fifth to a half of it is class metadata in Metaspace, which a heap
  measurement does not show at all.
- An undersized cache is not slightly worse, it is no cache plus eviction overhead. The same 12 requests through a
  cache one entry too small cost **12 preparations and 10 evictions**.
- The cache is necessarily **in-process**: a prepared model is a live instance with a per-instance owner identity
  and no serialization, so only the expanded DM-JSON can live in a shared tier.

The complete pattern — cache keying, single-flight loading, eviction safety, and the trade-offs — is in
[the developer API guide](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.13.0/site/api/v0.13.0/INTERPRETER-API-GUIDE.md).

## Install

Version `0.13.0` is distributed from the public `a12-interpreter-releases` repository.

For TypeScript or JavaScript, install the release tarball with `pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.13.0/a12-interpreter-0.13.0.tgz`.

For Kotlin or Java, add the Maven repository `https://mbackschat.github.io/a12-interpreter-releases/maven` and dependency `io.github.mbackschat.a12.dm:dm-interpreter:0.13.0`. Public third-party dependencies continue to resolve from Maven Central or npm.

## TypeScript

The Document shown below is decoded against the prepared model before evaluation. When its structure is not yet known, `prepared.documents.seedDocument()` creates a best-effort candidate with every declared group and field and one row per repeatable group; computation-owned targets remain present-empty for the engine to fill. A prepared model and interpreter can be retained while a form creates a new immutable Document snapshot after each change.

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

## Kotlin

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

`ValidationReport` and `ComputationReport` carry the same information as their TypeScript counterparts, including the model-owned pointers that tell apart the rows of a computation inside a repeatable group. Java consumers use these same types directly; a worked Java example lives in the developer API guide linked at the end of this document.

One thing to carry over from *Server-side model cache* above: the `PreparedModel` returned by `prepare` is the expensive, reusable part and is safe to share across concurrent requests, while the `Interpreter` from `createInterpreter()` is per request. Retaining the prepared model and creating an interpreter per call is the intended pattern, not an optimization.

## Workspaces

`ModelWorkspace.fromSources` freezes a caller-supplied finite source set. `ModelWorkspace.collect` asks a caller-owned provider only for dependencies discovered from the entry model and loads each exact id at most once. Neither method performs implicit I/O.

`expand(entryModelId)` returns self-contained DM-JSON after resolving includes, imported type definitions, exclusions, ids, and cross-model references. `prepare(entryModelId)` continues through evaluator support checks and returns a reusable `PreparedModel`. Use `PreparedModel.loadExpandedJson` when that self-contained artifact is already available.

Preparation rejects ambiguous or incomplete workspaces instead of choosing a source-order winner. Errors carry stable structured diagnostics; provider exception messages, file paths, URLs, and credentials are not copied into them.

## Compatibility and limits

The clean-room implementation is tested against the normative dynamic-Groovy Kernel strategy and against static Java wherever both Kernel strategies agree. JVM, Node.js, and packed-package Chrome consumers run the same portable behavior corpus.

The core is MIT-licensed and contains no Kernel bytecode or runtime dependency. Supported model time zones are `UTC`, `GMT`, and `Europe/Berlin`; all four A12 Date precisions are supported. `supportReport()` and the `unsupported` members on validation and computation results keep unsupported programs explicit; each entry's `subject` says whether it addresses a model element or a single Document cell.

<!-- source-statistics:interpreter-source:start -->
## Source statistics

Generated with [Tokei](https://github.com/XAMPPRocky/tokei) from the standalone interpreter library and its authored TypeScript facade. Tests, fixtures, generated output, build trees, and code embedded in Markdown are excluded.

| Language | Files | Code | Comments | Blanks |
|---|---:|---:|---:|---:|
| Java | 0 | 0 | 0 | 0 |
| Kotlin | 114 | 18703 | 7575 | 2367 |
| TypeScript | 11 | 1385 | 228 | 168 |

Maintainers regenerate this table with the local statistics updater; both release publishers compare it with fresh counts before any public mutation.
<!-- source-statistics:interpreter-source:end -->

## Documentation

- 🚀 **[Try the live browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)**
- 📘 **[Read the developer API guide →](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.13.0/site/api/v0.13.0/INTERPRETER-API-GUIDE.md)**
- 🧭 **[Browse the TypeScript API →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/typescript/)**
- 🧭 **[Browse the Kotlin API →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/kotlin/)**
- 🛠️ **[Explore the main `a12-dmkits` project →](https://github.com/mbackschat/a12-dmkits)** — source, design, benchmark method, and complete test evidence
- ⚖️ **[Read the MIT license →](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.13.0/LICENSE)**

Version-matched references plus `llms.txt` are also packaged in `dm-interpreter-0.13.0-api-docs.zip` under `api/v0.13.0/`.
