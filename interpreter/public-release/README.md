# A12 Interpreter releases

This repository is the public distribution of the independent, kernel-free A12 Document Model interpreter. Development happens in a separate source repository; this mirror contains immutable release assets, the cumulative Maven repository, versioned API references, and the browser showcase.

> **Independent project.** This is not an official A12 or mgm artifact.

## Start here

- [Try the interpreter in your browser](https://mbackschat.github.io/a12-interpreter-releases/showcase/)
- [Read the developer guide](https://mbackschat.github.io/a12-interpreter-releases/api/latest/INTERPRETER-API-GUIDE.md)
- [Browse the TypeScript API](https://mbackschat.github.io/a12-interpreter-releases/api/latest/typescript/)
- [Browse the Kotlin API](https://mbackschat.github.io/a12-interpreter-releases/api/latest/kotlin/)

Version **@VERSION@** runs the same clean-room Kotlin implementation on the JVM, Node.js, and in the browser. The qualified browser package replays 33 portable conformance cases; the showcase's current production bundle is about 211 kB gzip. Its representative 200-row Document validates in a 10.2 ms median, while the 10,000-row stress Document validates with 4,900 findings in a 177.1 ms median.

## What ships

- Finite in-memory workspaces and on-demand model providers
- Include mounting, imported type definitions, id normalization, and cross-model reference remapping
- Self-contained expanded model output before evaluator-specific support checks
- Lossless, versioned Document JSON with structured input diagnostics
- Full and partial validation, computations, support reports, and model-owned pointers
- Custom field validators and custom conditions
- Resource limits, source integrity, cancellation, and immutable prepared models

The package performs no implicit filesystem, network, storage, or credential access. It contains no A12 Kernel bytecode or runtime dependency.

## TypeScript and JavaScript

Install the npm-compatible tarball directly from the GitHub Release:

```sh
pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/@RELEASE_TAG@/a12-interpreter-@VERSION@.tgz
```

```ts
import {
  ModelWorkspace,
  type DocumentInputV1,
} from "@mbackschat/a12-interpreter";

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
const result = prepared.createInterpreter().validateFull(document);
```

Keep the prepared model and interpreter when a form validates repeated edits; decode a new immutable Document snapshot for each change.

## Kotlin and Java

Resolve the first-party publication from this repository's GitHub Pages Maven tree. Third-party Kotlin dependencies continue to resolve from Maven Central.

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
val document = prepared.documents.readDocument(input)
val report = prepared.createInterpreter().validateFull(document)
```

Java callers use the same JVM artifact; `ModelWorkspaceJava.collect` projects asynchronous model providers through `CompletionStage`.

## Workspaces

`ModelWorkspace.fromSources` freezes a caller-supplied finite source set. `ModelWorkspace.collect` asks a caller-owned provider only for dependencies discovered from the entry model and loads each exact id at most once.

`expand(entryModelId)` resolves includes and imported type definitions into self-contained DM-JSON. `prepare(entryModelId)` continues through evaluator support checks and returns a reusable `PreparedModel`. Ambiguous, cyclic, incomplete, oversized, or integrity-invalid workspaces fail with structured diagnostics rather than a source-order guess.

The browser showcase uses this same public provider/workspace boundary. You can use its built-in examples or select your own local folder, model files, and Document; selected files remain in the browser.

## Release contents

Each release contains:

- `a12-interpreter-@VERSION@.tgz`
- `dm-interpreter-@VERSION@-maven-repository.zip`
- `dm-interpreter-@VERSION@-api-docs.zip`
- `dm-interpreter-@VERSION@-showcase.zip`
- `release-manifest.json`
- `SHA256SUMS`

Published GitHub Release assets and versioned Maven/API directories are immutable. A defect is fixed in a later patch release rather than by replacing a published artifact.

## License and privacy

The interpreter core and showcase are licensed under the [MIT License](LICENSE). See the [independence disclaimer](DISCLAIMER.md) and the showcase's [site-specific privacy notice](https://mbackschat.github.io/a12-interpreter-releases/privacy.html).
