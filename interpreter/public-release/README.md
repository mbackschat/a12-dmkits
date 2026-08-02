# A12 Interpreter releases

This repository is the public distribution of the independent, kernel-free A12 Document Model interpreter. Development happens in a separate source repository; this mirror contains immutable release assets, the cumulative Maven repository, versioned API references, and the browser showcase.

> **Independent project.** This is not an official A12 or mgm artifact.

## Try it in your browser

**[Open the A12 Interpreter browser showcase →](https://mbackschat.github.io/a12-interpreter-releases/showcase/)**

Use the built-in models and Documents or load your own local workspace. Your selected files stay in the browser.

## Documentation

- [Read the developer guide](https://github.com/mbackschat/a12-interpreter-releases/blob/@RELEASE_TAG@/site/api/v@VERSION@/INTERPRETER-API-GUIDE.md) — rendered Markdown for the released version
- [Browse the TypeScript API](https://mbackschat.github.io/a12-interpreter-releases/api/latest/typescript/)
- [Browse the Kotlin API](https://mbackschat.github.io/a12-interpreter-releases/api/latest/kotlin/)
- [Open the public release site](https://mbackschat.github.io/a12-interpreter-releases/) — current version, installation coordinates, showcase, API references, and privacy information

The interpreter runs the same clean-room Kotlin implementation on the JVM, Node.js, and in the browser. The qualified browser package replays 33 portable conformance cases; the showcase's current production bundle is about 211 kB gzip. Its representative 200-row Document validates in a 10.2 ms median, while the 10,000-row stress Document validates with 4,900 findings in a 177.1 ms median.

## What ships

- Finite in-memory workspaces and on-demand model providers
- Include mounting, imported type definitions, id normalization, and cross-model reference remapping
- Self-contained expanded model output before evaluator-specific support checks
- Lossless, versioned Document JSON with structured input diagnostics
- Full and partial validation, computations, support reports, and model-owned pointers
- Custom field validators and custom conditions
- Resource limits, source integrity, cancellation, and immutable prepared models

The package performs no implicit filesystem, network, storage, or credential access. It contains no A12 Kernel bytecode or runtime dependency.

**Semantics target.** This release reproduces the evaluation behavior of **A12 Kernel 30.8.1**, as shipped in the **A12 Tools 2025.06-ext5** distribution. The implementation is clean-room and is verified by differential testing against that kernel; the target version is stated so you can tell which kernel's semantics a given release was matched to.

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
| Kotlin | 106 | 17385 | 6324 | 2180 |
| TypeScript | 17 | 3190 | 269 | 277 |
| Lean | 0 | 0 | 0 | 0 |

Regenerate with `./scripts/update-code-statistics.sh`; pushes and release assembly reject stale values.
<!-- source-statistics:interpreter-release:inputs java=none kotlin=1c1c7245f62819f820af334cff9ed1c75f46a08b typescript=5862c52523a21ee1f935ef3d7e7e95e3399c7877 lean=none measurements=99741b2bfb79409f1a818a7bc5db6d62d21a81d5 -->
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
