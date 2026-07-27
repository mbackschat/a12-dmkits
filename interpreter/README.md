# A12 Interpreter

Run A12 Document Models on the JVM, Node.js, and in the browser without the A12 Kernel at runtime.

The interpreter prepares model workspaces, resolves includes and imported type definitions, reads Documents, runs full or partial validation, and computes derived values. Applications keep control of file, network, storage, and credential access.

## Features

- Finite in-memory workspaces and on-demand model providers
- Self-contained model expansion, separate from evaluator preparation
- Lossless, versioned Document JSON with structured input diagnostics
- Full and partial validation, computations, support reports, and model-owned pointers
- Synchronous custom field validators and custom conditions
- Explicit resource limits, integrity checks, cancellation, and immutable prepared models
- The same Kotlin implementation on JVM, Node.js, and browser targets

The package does not author models, render forms, persist Documents, provide a virtual filesystem, or bundle Kernel code. Kernel-shaped TypeScript and JVM drop-in adapters are separate, later compatibility products.

## Install

Version `0.9.1` is distributed from the public `a12-interpreter-releases` repository.

For TypeScript or JavaScript, install the release tarball with `pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.9.1/a12-interpreter-0.9.1.tgz`.

For Kotlin or Java, add the Maven repository `https://mbackschat.github.io/a12-interpreter-releases/maven` and dependency `io.github.mbackschat.a12.dm:dm-interpreter:0.9.1`. Public third-party dependencies continue to resolve from Maven Central or npm.

## TypeScript

The Document shown below is decoded against the prepared model before evaluation. A prepared model and interpreter can be retained while a form creates a new immutable Document snapshot after each change.

<!-- snippet: interpreter-typescript-start -->
```ts
import {
  ModelWorkspace,
  type DocumentInputV1,
} from "@mbackschat/a12-interpreter";

export function runInterpreter(rawModelJson: string) {
  const workspace = ModelWorkspace.fromSources([{json: rawModelJson}]);
  const prepared = workspace.prepare("permit-basic");
  const input: DocumentInputV1 = {
    version: 1,
    groups: [{path: "/Permit", coordinates: [1]}],
    fields: [
      {path: "/Permit/ApplicationNo", coordinates: [1, 1], raw: "P-42"},
      {path: "/Permit/RequestedArea", coordinates: [1, 1], raw: "120"},
    ],
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
    document,
    prepared,
  };
}
```

## Kotlin

<!-- snippet: interpreter-kotlin-start -->
```kotlin
import io.github.mbackschat.a12.dm.interpreter.ComputationReport
import io.github.mbackschat.a12.dm.interpreter.DocumentInputV1
import io.github.mbackschat.a12.dm.interpreter.FieldPlacementInput
import io.github.mbackschat.a12.dm.interpreter.GroupPlacementInput
import io.github.mbackschat.a12.dm.interpreter.ModelWorkspace
import io.github.mbackschat.a12.dm.interpreter.ValidationReport
import io.github.mbackschat.a12.dm.interpreter.WorkspaceModelSource

data class InterpreterResult(
    val validation: ValidationReport,
    val computation: ComputationReport,
)

fun runInterpreter(modelJson: String): InterpreterResult {
    val source = WorkspaceModelSource.builder().json(modelJson).build()
    val prepared = ModelWorkspace.fromSources(listOf(source)).prepare("permit-basic")
    val document = prepared.documents.readDocument(
        DocumentInputV1(
            groups = listOf(GroupPlacementInput("/Permit", listOf(1))),
            fields = listOf(
                FieldPlacementInput("/Permit/ApplicationNo", listOf(1, 1), "P-42"),
                FieldPlacementInput("/Permit/RequestedArea", listOf(1, 1), "120"),
            ),
        ),
    )
    val interpreter = prepared.createInterpreter()
    return InterpreterResult(
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

The core is MIT-licensed and contains no Kernel bytecode or runtime dependency. Supported model time zones are `UTC`, `GMT`, and `Europe/Berlin`; optional-month and optional-year date precisions remain outside the evaluator boundary. `supportReport()` and the `unsupported` members on validation and computation results keep unsupported programs explicit.

## Documentation

- [Developer API guide](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.9.1/site/api/v0.9.1/INTERPRETER-API-GUIDE.md)
- Version-matched Kotlin and TypeScript references plus `llms.txt` are packaged in `dm-interpreter-0.9.1-api-docs.zip` under `api/v0.9.1/`
- Browser showcase: `https://mbackschat.github.io/a12-interpreter-releases/showcase/`
- [MIT license](https://github.com/mbackschat/a12-interpreter-releases/blob/v0.9.1/LICENSE)
