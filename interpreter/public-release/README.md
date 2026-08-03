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
- Resource limits, source integrity, cancellation, and immutable prepared models

The package performs no implicit filesystem, network, storage, or credential access. It contains no A12 Kernel bytecode or runtime dependency.

**Semantics target.** This release reproduces the evaluation behavior of **A12 Kernel 30.8.1**, as shipped in the **A12 Tools 2025.06-ext5** distribution. The implementation is clean-room and is verified by differential testing against that kernel; the target version is stated so you can tell which kernel's semantics a given release was matched to.

## Performance

**Fast, and measured — never assumed.**

The interpreter runs the same clean-room Kotlin implementation on the JVM, Node.js, and in the browser. The qualified browser package replays 33 portable conformance cases, and the [live showcase](https://mbackschat.github.io/a12-interpreter-releases/showcase/) proves the packed package in system Chrome rather than inferring browser support from Node.js.

The @VERSION@ showcase's measured production JavaScript entry is about **232 kB gzip**. On the fixed Apple M1/Chrome runner on 2026-08-03, a representative 200-row Document validated in a **10.5 ms median**; the 10,000-row stress Document validated with 4,900 findings in a **203.6 ms median**. Both figures use 10 independent fresh Chrome processes and include interpreter construction through complete result materialization.

The JVM comparison uses the A12 Kernel's generated validator compiled as Java, with code generation outside the clock, as the fair algorithmic baseline. In the 2026-08-03 sweep, the interpreter was **2.1× to 12.5× faster** across the 15-case default inventory and **10× to 16.7× faster** on repeated-document evaluation. The closest hostile point—384 concentrated low-firing rules—still led by **1.32×**, and the huge-document edge reached about **155×**. Neither generated Java nor dynamic Groovy won an eligible comparison in the default, repeated, rule-count, or 13-case edge suites. These are bounded same-machine measurements after exact result preflight, not universal promises; the scenarios, timing boundaries, and complete tables are documented in [Interpreter performance](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md).

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
| Kotlin | 106 | 17423 | 6336 | 2185 |
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
