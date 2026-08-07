# A12 Interpreter

Run A12 Document Models on the JVM, Node.js, and in the browser without the A12 Kernel at runtime.

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

## Browser footprint and performance

**Fast, and measured — never assumed.**

Browser execution is a first-class qualified target, not a Node.js inference. The packed npm tarball is installed into an external consumer, its portable behavior corpus is replayed in system Chrome, and the same package powers the [live browser showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/).

The showcase's measured production JavaScript entry is about **233 kB gzip**. On the fixed Apple M1/Chrome runner on 2026-08-03, a representative 200-row Document validated in a **10.5 ms median**; the 10,000-row stress Document validated with 4,900 findings in a **203.6 ms median**. Both figures use 10 independent fresh Chrome processes and include interpreter construction through complete result materialization.

The JVM comparison deliberately uses the A12 Kernel's generated validator compiled as Java, with code generation outside the clock, as the fair algorithmic baseline. In the 2026-08-03 sweep, the interpreter was **2.1× to 12.5× faster** across the 15-case default inventory and **10× to 16.7× faster** on repeated-document evaluation. The closest hostile point—384 concentrated low-firing rules—still led by **1.32×**, and the huge-document edge reached about **155×**. Neither generated Java nor dynamic Groovy won an eligible comparison in the default, repeated, rule-count, or 13-case edge suites. These are bounded same-machine measurements after exact result preflight, not universal promises; the scenarios, timing boundaries, and complete tables are documented in [Interpreter performance](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).

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
  than size, so size the cache in **bytes, not entries**. Budget it from structure instead:
  **≈ 1.4 kB × fields + 1.55 kB × rules + 2.3 kB × groups**, which holds within 4% across six models spanning 5 to 79
  fields, 3 to 29 groups and 0 to 192 rules.
- It is nonetheless far **smaller than the A12 Kernel runtime service it replaces**: on those same models,
  **3.8× to 9.2×** smaller than the Kernel's generated Java and **22× to 41×** smaller than its dynamic Groovy
  (measured 2026-08-07, JDK 21). The Kernel's own Document Model is comparable to a prepared model; the difference
  is the generated rule code — and a fifth to a half of it is class metadata in Metaspace, which a heap
  measurement does not show at all.
- An undersized cache is not slightly worse, it is no cache plus eviction overhead. The same 12 requests through a
  cache one entry too small cost **12 preparations and 10 evictions**.
- The cache is necessarily **in-process**: a prepared model is a live instance with a per-instance owner identity
  and no serialization, so only the expanded DM-JSON can live in a shared tier.

The complete pattern — cache keying, single-flight loading, eviction safety, and the trade-offs — is in
[the developer API guide](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.12.0/site/api/v0.12.0/INTERPRETER-API-GUIDE.md).

## Install

Version `0.12.0` is distributed from the public `a12-interpreter-releases` repository.

For TypeScript or JavaScript, install the release tarball with `pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.12.0/a12-interpreter-0.12.0.tgz`.

For Kotlin or Java, add the Maven repository `https://mbackschat.github.io/a12-interpreter-releases/maven` and dependency `io.github.mbackschat.a12.dm:dm-interpreter:0.12.0`. Public third-party dependencies continue to resolve from Maven Central or npm.

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
| Kotlin | 110 | 17618 | 6553 | 2232 |
| TypeScript | 11 | 1385 | 228 | 168 |

Maintainers regenerate this table with the local statistics updater; both release publishers compare it with fresh counts before any public mutation.
<!-- source-statistics:interpreter-source:end -->

## Documentation

- 🚀 **[Try the live browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)**
- 📘 **[Read the developer API guide →](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.12.0/site/api/v0.12.0/INTERPRETER-API-GUIDE.md)**
- 🧭 **[Browse the TypeScript API →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/typescript/)**
- 🧭 **[Browse the Kotlin API →](https://mbackschat.github.io/a12-interpreter-releases/api/latest/kotlin/)**
- 🛠️ **[Explore the main `a12-dmkits` project →](https://github.com/mbackschat/a12-dmkits)** — source, design, benchmark method, and complete test evidence
- ⚖️ **[Read the MIT license →](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.12.0/LICENSE)**

Version-matched references plus `llms.txt` are also packaged in `dm-interpreter-0.12.0-api-docs.zip` under `api/v0.12.0/`.
