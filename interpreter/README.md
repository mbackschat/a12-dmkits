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
- Explicit resource limits, integrity checks, cancellation, and immutable prepared models
- The same Kotlin implementation on JVM, Node.js, and browser targets

The package does not author models, render forms, persist Documents, provide a virtual filesystem, or bundle Kernel code. Kernel-shaped TypeScript and JVM drop-in adapters are separate, later compatibility products.

## Browser footprint and performance

Browser execution is a first-class qualified target, not a Node.js inference. The packed npm tarball is installed into an external consumer, its portable behavior corpus is replayed in system Chrome, and the same package powers the [live browser showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/).

The published 0.11.0 showcase's complete production bundle is about **232 kB gzip**. On the fixed Apple M1/Chrome runner, a representative 200-row Document validates in a **10.0 ms median**; the 10,000-row stress Document validates with 4,900 findings in a **187.7 ms median**. Both figures use 10 stable fresh Chrome processes and include interpreter construction through complete result materialization.

The JVM comparison deliberately uses the A12 Kernel's generated validator compiled as Java, with code generation outside the clock, as the fair algorithmic baseline. In the recorded run, the interpreter was approximately **2–3× faster** on rule-count sweeps, **5× faster** on the combined real-world case, **25–50× faster** on aggregates, and up to **165× faster** on the huge-document axis. These are dated measurements rather than universal promises; the scenarios, semantic preflight, timing boundaries, and full result tables are documented in [Interpreter performance](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).

## Install

Version `0.11.0` is distributed from the public `a12-interpreter-releases` repository.

For TypeScript or JavaScript, install the release tarball with `pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.11.0/a12-interpreter-0.11.0.tgz`.

For Kotlin or Java, add the Maven repository `https://mbackschat.github.io/a12-interpreter-releases/maven` and dependency `io.github.mbackschat.a12.dm:dm-interpreter:0.11.0`. Public third-party dependencies continue to resolve from Maven Central or npm.

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
| Kotlin | 106 | 17385 | 6324 | 2180 |
| TypeScript | 11 | 1385 | 228 | 168 |
| Lean | 0 | 0 | 0 | 0 |

Maintainers regenerate this table with the local statistics updater; both release publishers compare it with fresh counts before any public mutation.
<!-- source-statistics:interpreter-source:end -->

## Documentation

- [Developer API guide](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.11.0/site/api/v0.11.0/INTERPRETER-API-GUIDE.md)
- Version-matched Kotlin and TypeScript references plus `llms.txt` are packaged in `dm-interpreter-0.11.0-api-docs.zip` under `api/v0.11.0/`
- Browser showcase: `https://mbackschat.github.io/a12-interpreter-releases/showcase/`
- [MIT license](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.11.0/LICENSE)
