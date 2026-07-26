# Proposal: publish the interpreter from a public GitHub release repository

## Status

**Execution is in progress; R2.1–R3.2 are complete.** This proposal is also the inter-session state carrier for taking `:interpreter` from an in-repo, source-consumable Kotlin Multiplatform module to a documented public artifact with portable workspace preparation, a browser-qualified TypeScript surface, and a GitHub Pages showcase.

Owner direction fixed on 2026-07-26:

- all first-party release artifacts are hosted by a new public GitHub repository;
- npm-compatible packages are welcome, but the public GitHub repository—not npmjs.org or GitHub Packages—is their host;
- TypeScript packages must not use the `@a12` scope because it implies an official A12 package; the accepted owner-signalling replacement is `@mbackschat/a12-interpreter`;
- public third-party dependencies continue to resolve from Maven Central/npm;
- the release needs a public `README.md`, developer API documentation, and a TypeScript/JavaScript browser showcase hosted on GitHub Pages;
- the browser interpreter must use a workspace abstraction to resolve included models and imported typedefs; that requirement was activated as IG68 and is now completed as IF224, while IG85 and IG86 remain live release gaps;
- the independently consumable interpreter core ships first; the separate Operator API is a much later artifact and does not block it;
- the immutable workspace/preparation boundary and asynchronous host-provider layer are accepted, including snapshot/integrity, cancellation/failure, acquisition-limit, safe-diagnostic, platform-projection, and browser-storage requirements;
- a separately packaged EUPL-1.2 JVM adapter may expose the interpreter as a drop-in `IDocumentRtService` implementation for existing Kernel-consuming applications after the MIT core `v0.1.0`; it embeds no Kernel bytecode and is not part of the portable core;
- the implemented public core is governed by [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md), while later compatibility and higher-level product surfaces remain in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md).

This proposal's execution state follows the one-active-session-per-worktree rule. No worker session is active.

## 1. Outcome

The finished release must let an anonymous developer do all three of these without access to the private source repository or a GitHub token:

```text
pnpm/TypeScript consumer ── GitHub Release .tgz ──► validate/compute
Gradle/Kotlin consumer ──── GitHub Pages Maven repo ──► validate/compute
browser visitor ─────────── GitHub Pages showcase ──► prepare workspace + validate/compute locally
```

The public repository is a generated distribution mirror, much like [`a12-dmtool-releases`](https://github.com/mbackschat/a12-dmtool-releases), while `:interpreter` remains developed and differentially verified in this repository.

Success means:

- the shipped interpreter is kernel-free and MIT-licensed;
- the TypeScript package is a real, non-empty production package with generated `.d.ts`;
- the Kotlin Multiplatform/JVM publications resolve from an anonymous GitHub Pages Maven URL;
- both packages are consumed by fresh projects with no Gradle project substitution or in-repo npm workspace;
- an actual browser runs the portable conformance subset;
- the browser prepares a raw multi-model workspace containing both an include and an imported typedef;
- the showcase acquires only the entry model's discovered closure through `ModelSourceProvider`;
- after one successful load, the showcase can reconstruct that immutable manifest-pinned closure from Cache Storage while offline;
- the showcase consumes the packed public TypeScript artifact rather than reaching into build output or internal Kotlin declarations;
- human and generated API documentation describe only the shipped public surface;
- releases are staged, verified, and then published immutably.

## 2. Current state and the gaps that matter

| Area | Current fact | Release consequence |
|---|---|---|
| Engine | One `commonMain` interpreter targets JVM and JS/Node and is already the sole dmtool runtime evaluator | Reuse the proven engine; do not fork or rewrite it |
| Purity | `verifyInterpreterPurity` excludes kernel, Groovy, dmtool, sibling-kit runtime coupling, and a coroutine-library ABI | Preserve this as a publication gate |
| Correctness | Differential laws and the conformance corpus use the real kernel as oracle | Package/browser work must not weaken or replace these gates |
| Public API | Finite and provider-collected workspaces expose preparation, model-owned documents/pointers, validation, computation, support reporting, and customization SPIs through one minimum façade | Preserve this intentional surface through the package and ABI work rather than exporting implementation-shaped engine types |
| TypeScript | The functional two-layer Kotlin bridge/authored-TypeScript façade is packed as `@mbackschat/a12-interpreter` with generated `.d.ts` and a strict external Node consumer | Consume this exact tarball in the browser and showcase rather than importing an in-repo module |
| Browser | `nodejs()` and `browser()` are configured, and a fresh Vite consumer executes the packed tarball in system Chrome under the repository-relative Pages base; all 33 portable corpus cases, raw workspace preparation, and host-sensitive semantics pass, while the first-answer bar remains unratified (IG86) | Measure the fixed B1 scenario over 50 fresh Chrome processes and ratify its p95 bar before making the browser claim |
| Workspace preparation | The original commonMain assembler and provider collector resolve raw includes, imported typedefs, opaque ids, and mounted references with Java-reader/Kernel parity on JVM/Node and packed-package parity in real Chrome (IF224) | Reuse this shared boundary in the showcase; do not add a browser-only resolver |
| Packaging | Maven/KMP and npm artifacts, standalone Kotlin/JVM/Kotlin/JS/TypeScript consumers, ABI/content/dependency/secret/checksum locks, and IF173 delivery bases are complete locally (IG85) | Preserve the gates through public-mirror assembly and prove anonymous GitHub consumption |
| Docs | The root README explains the engine, but there is no interpreter-owned package README or shipped API reference | Add one canonical package README plus derived references |
| License | The interpreter core is original, kernel-free MIT code; the JVM drop-in has a separate Kernel-facing boundary | Keep the core artifacts MIT and publish the bytecode-free drop-in artifact under EUPL-1.2 by explicit owner policy; never copy Kernel code or silently publish a mixed-license binary |
| Kernel drop-in | The internal `:adapter` is a broad Kernel oracle/backend, not a narrow distributable replacement service | After core `v0.1.0`, design and publish a separate JVM-only `IDocumentRtService` adapter with a pinned compatibility matrix |

The browser workspace/showcase and publication request flipped IG68, IG85, and IG86's re-open triggers. IG68 has completed its lifecycle as IF224; IG85 and IG86 remain open release requirements until public anonymous delivery and the browser first-answer bar respectively pass.

## 3. Distribution decision

### 3.1 Ratified identity

All identities were ratified on 2026-07-26:

| Surface | Identity | Status |
|---|---|---|
| Public repository | `mbackschat/a12-interpreter-releases` | ratified |
| TypeScript package name inside the tarball | `@mbackschat/a12-interpreter` | ratified |
| Maven coordinate | `io.github.mbackschat.a12.dm:dm-interpreter:X.Y.Z` | ratified |
| Private source tag | `interpreter-vX.Y.Z` | ratified |
| Public mirror release tag | `vX.Y.Z` | ratified |
| Pages root | `https://mbackschat.github.io/a12-interpreter-releases/` | ratified |

Use an independent interpreter SemVer train. Reusing dmtool’s root version would either begin the new library at an unrelated dmtool version or republish unchanged interpreter bytes on CLI-only releases. `:interpreter` may override the root version—the root build already reserves that option.

`@mbackschat/a12-interpreter` was selected because the npm scope communicates authorship while the package portion communicates A12 compatibility. The same convention is reserved for the later, separately selected Operator artifact (`@mbackschat/a12-operators`); neither name presents the package as an official `@a12` publication.

### 3.2 Why not GitHub Packages

GitHub Packages’ npm registry requires a classic personal access token even to install a public package. That violates the anonymous-consumer outcome. See GitHub’s [npm registry authentication contract](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry).

Instead, each GitHub Release carries a standard npm-compatible tarball:

```sh
pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.1.0/a12-interpreter-0.1.0.tgz
```

The package’s own `package.json` declares `@mbackschat/a12-interpreter` as its import name. The release URL is the distribution coordinate; the lockfile records the resolved artifact and integrity.

### 3.3 Maven from GitHub Pages

Pages serves a conventional Maven repository layout:

```kotlin
repositories {
    maven("https://mbackschat.github.io/a12-interpreter-releases/maven")
    mavenCentral() // third-party Kotlin/kotlinx dependencies only
}

dependencies {
    implementation("io.github.mbackschat.a12.dm:dm-interpreter:0.1.0")
}
```

The matching complete Maven repository is also attached to the GitHub Release as a zip. The zip is the immutable evidence/archive; Pages is the package-manager-friendly view.

“GitHub-only” here means every **first-party interpreter artifact** is hosted by this public GitHub repository. Kotlin, kotlinx serialization, and `big.js` remain third-party dependencies resolved from their normal public upstream repositories. Mirroring all transitive dependencies is a different and much larger supply-chain requirement and is excluded unless the owner explicitly asks for it.

## 4. Public repository and Pages contract

The public repository is generated from this source repository and has no hand-maintained release content:

```text
a12-interpreter-releases/
├── README.md
├── CHANGELOG.md
├── LICENSE
├── release-manifest.json
├── .github/workflows/
│   ├── build-release.yml
│   └── deploy-pages.yml
└── site/
    ├── index.html
    ├── showcase/
    ├── api/
    │   ├── latest/
    │   └── vX.Y.Z/
    │       ├── kotlin/
    │       ├── typescript/
    │       └── llms.txt
    └── maven/
        └── io/github/mbackschat/a12/dm/...
```

The Pages workflow uses GitHub’s supported custom-workflow path: build a static Pages artifact, upload it, and deploy it with the `github-pages` environment. See [Using custom workflows with GitHub Pages](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

Unlike dmtool’s latest-only mirror tree, the interpreter mirror contains a versioned Maven repository and versioned API docs. Its assembler must:

- start from a fresh clone of the public mirror;
- add the new version without changing any older version directory;
- refuse if the target Maven/API version already exists;
- update only `latest`, the landing page, the changelog, and current showcase pointers;
- never use whole-tree `rsync --delete` across the historical package area.

Published GitHub Releases and published Maven/npm artifact versions are immutable. A broken public artifact is superseded by a patch release.

## 5. First public API boundary

### 5.1 Required before publication

Do not publish the current implementation-shaped surface merely because it compiles on JVM and Node. The first public release should implement the smallest interpreter slice already isolated by [TS-API-MVP-PROPOSAL](TS-API-MVP-PROPOSAL.md):

- strict prepared-model loading;
- bounded immutable `ModelWorkspace` preparation with includes, imported/inline typedefs, id normalization, and reference-aware mounts;
- assembler-only expanded DM-JSON output that does not apply interpreter-evaluability gates;
- `PreparedModel` and model-owner identity;
- versioned, bounded placement-document decoding and lossless encoding;
- model-owned immutable `DocumentSnapshot`;
- `validateFull`, `validatePart`, and `compute`;
- Custom Field Type and Custom Condition SPIs;
- configuration support reporting;
- JS-safe structured errors and results.

The browser showcase is the first real external consumer of this surface. Any friction it finds is an API gap to fix in the library, not a reason to use internal classes or hand-coded document-model strings.

### 5.2 Workspace boundary

The common interpreter API accepts an immutable, caller-populated workspace indexed by exact model `header.id`. `ModelWorkspace.fromSources` covers the initial finite file-map case. Browser/Node/JVM adapters acquire source text; `commonMain` performs deterministic synchronous preparation without filesystem, network, credential, or Promise APIs.

Preparation resolves only the selected entry model's transitive closure and:

- follows include and `typeDefinitions` edges with cycle/resource-limit checks;
- reproduces `SINGLE_RG`/`MODEL_ROOT`, nesting, alias, `excludeRules`, and `excludeComputations` semantics;
- merges imported typedefs and inline effective types;
- normalizes opaque ids to name paths;
- remaps rule and computation references across include mounts;
- fails with structured source/model/reference-chain diagnostics on any ambiguity or residue.

`ModelWorkspace.expand` returns the compact assembled DM-JSON at that boundary. It shares the exact include/typedef/reference assembler used by `prepare` but stops before `ModelLoader`; expansion therefore accepts a kernel-legal model that uses an interpreter-unsupported time zone or date precision, while `prepare` may refuse the same expanded source as unevaluable. The output removes consumed model references and include directives, inlines every field type-definition use, and retains local type-definition declarations with concrete field types. It never retains a second model-sized JSON string inside every `PreparedModel`.

The TypeScript acquisition façade adds `ModelSourceProvider.load(modelId, context): Promise<WorkspaceModelSource | undefined>` and `ModelWorkspace.collect(entryModelId, provider, options)`. Collection calls the provider at most once for each discovered exact id, follows only the transitive closure, freezes the returned source bytes into an immutable workspace, and returns it for synchronous common preparation. R2.7 provides the suspending common Kotlin boundary and a stdlib-only Kotlin/JS `Promise`/`AbortSignal` bridge; R3.2 assigns its intentional exported TypeScript names, and the Java `CompletionStage` projection follows with the first Java consumer fixture in R2.10. No platform async type enters `commonMain`.

One provider instance represents one logical read snapshot for a collection. An immutable HTTP manifest supplies a revision and per-model SHA-256 values; database/Git providers pin a transaction or revision. The collector verifies any expected digest and computes the final `ModelFingerprint` from the actual resolved bytes, so later provider mutation cannot change a prepared model and a mixed or corrupted closure fails explicitly.

Providers receive the effective per-source byte ceiling and cancellation signal so they can stop a response/file/blob while reading; checking only after constructing an oversized string is insufficient. The collector independently rechecks per-source and aggregate limits. `undefined` means not found; typed provider failures distinguish access denied, unavailable, cancelled, source too large, integrity mismatch, and unexpected failure. The collector never retries, so the initial contract carries no unused retry hint.

Only a sanitized diagnostic label may cross the provider boundary. Raw filesystem paths, credential-bearing URLs, signed-query parameters, authorization data, connections, and mutable provider state never enter a workspace, fingerprint, or diagnostic.

The provider is deliberately smaller than a virtual filesystem: it resolves logical model ids to source text. This admits:

| Host/source | Provider strategy |
|---|---|
| initial bundle or test | immutable in-memory `Map<ModelId, WorkspaceModelSource>` |
| HTTP/CDN/server | immutable revisioned manifest maps ids to URLs and SHA-256 values; fetch only discovered dependencies |
| browser-local database | IndexedDB object store keyed by model id |
| browser read-only/offline cache | Cache Storage/service-worker provider over immutable manifest entries |
| browser-local filesystem-like store | Origin Private File System (OPFS) |
| user-selected browser directory | permission-gated File System Access adapter, with a file-input/import fallback |
| Node.js or Next.js Node runtime | `node:fs/promises` directory adapter that indexes `header.id`, optionally from a generated manifest |
| Next.js Edge runtime | HTTP, database, or platform KV/object-store provider; no filesystem assumption |
| application database/CMS/Git/object store | application-owned lookup callback implementing the same provider |

IndexedDB is the default recommendation for an editable browser-local workspace; it is an asynchronous transactional key-value store intended for substantial structured local data. Cache Storage is appropriate for immutable fetched model responses and an offline-capable showcase. OPFS is appropriate when the application wants origin-private filesystem semantics or larger file-oriented storage. Direct user-directory access is an optional progressive enhancement because it is permission-gated and its File System Access specification remains incubating. Synchronous, size-limited, string-only `localStorage` is explicitly not a workspace provider.

Next.js should consume the same interpreter package on both server and client. In the default Node.js server runtime, a server-only provider may use `node:fs/promises`; Next.js documents Node.js as the default/recommended runtime. Its Edge runtime does not support filesystem access, so that deployment uses a fetch/database/KV provider instead. Browser Client Components use IndexedDB, Cache Storage, OPFS, fetch, or a user-selected directory; no Node adapter may enter the browser bundle. See the official [Next.js runtime contract](https://nextjs.org/docs/app/api-reference/edge), [Node.js filesystem API](https://nodejs.org/api/fs.html), [IndexedDB specification](https://www.w3.org/TR/IndexedDB/), [Service Worker cache specification](https://w3c.github.io/ServiceWorker/#cache-interface), and [File System Access draft](https://wicg.github.io/file-system-access/).

Providers must not derive a filesystem path or remote URL directly from an untrusted model id. A directory adapter indexes ids from validated headers; an HTTP adapter uses an explicit manifest. This prevents path traversal and server-side request-forgery while preserving `header.id` as the only resolution identity.

The explicit `loadExpandedJson` fast path stays available. The showcase must use the on-demand provider/workspace path, while a duplicate `header.id` is rejected deliberately rather than resolved by filesystem or caller ordering. Asynchronous provider calls end when collection produces the immutable workspace; evaluation and customization callbacks remain synchronous. Provider-wide caching, collector retries, writes, watches, implicit network access, batch-loading APIs, and a general virtual filesystem stay outside v0.1. The full public contract is in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md) §2.

### 5.3 Sequencing recommendation

Implement **the Track-B interpreter core**, not the entire 4,000-line end-state proposal, as the publication prerequisite. The service registry/pool, Worker coordinator, candidate programs, persistent form editor, Zod adapters, and broad Java façade remain consumer-gated follow-ups.

The first interpreter tarball embeds or privately owns the minimum value types it needs. It does not depend on the complete later `@mbackschat/a12-operators` product. Shared public types may be extracted when that direct-operator artifact is selected, with an explicit compatibility plan for the already-published interpreter API.

The portable core also does not depend on Kernel API types. Existing JVM applications may later add the separate `io.github.mbackschat.a12.dm:dm-interpreter-kernel-dropin` artifact and substitute its `IDocumentRtService` implementation without changing their `DocumentV2`, `DocumentProcessingConfig`, validation-result, or computation-result call sites. That adapter is broader than the custom-extension compatibility artifacts: it must implement full/partial validation, computation and `applyTo`, precompile/cache behavior, model-code identifiers, and the pinned interface's deprecated compatibility overloads. Its artifact contains no Kernel bytecode; the already-Kernel-consuming host supplies the pinned Kernel API/runtime. [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md#iag2--jvm-idocumentrtservice-drop-in) defines the deferred contract and re-open gate.

### 5.4 ABI and package locks

The public contract is guarded at three layers:

- Kotlin/JVM and Kotlin/JS KLIB ABI dumps check in only the intentional root façade; default-public engine packages and the legacy evaluator/arithmetic surface are not frozen as a consumer promise;
- the package-root TypeScript export graph is rendered deterministically from the generated `.d.ts` and checked against a reviewed golden; a negative control proves that a public declaration cannot reference a hidden package-local supporting type;
- exact npm and normalized Maven packed-file manifests prove both artifacts contain only the reviewed files, while dependency and secret scans reject unexpected coordinates, private repositories, credentials, machine-local paths, corporate-domain residue, and private keys.

Kotlin’s compiler generates `.d.ts` from exported declarations when TypeScript-definition generation is enabled; see [Kotlin/JS project setup](https://kotlinlang.org/docs/js-project-setup.html). Export design must respect `@JsExport`’s exportable-type boundary rather than leaking arbitrary Kotlin collections or implementation types.

R3.2 uses that compiler output as a private transport, not as the product API. A thin `jsMain` handle bridge projects only primitives, arrays, promises, and opaque handles; an authored strict-TypeScript façade owns the stable public names, native `Error` subclasses, readonly DTOs, `unknown` placement decoder, provider/`AbortSignal` adapter, model-owned pointers, validation/computation records, and synchronous customization callbacks. The package exports only `dist/index.js`/`dist/index.d.ts`; Kotlin runtime chunks and generated bridge declarations remain package-private implementation files. Source maps embed stable package-local own sources and replace dependency/build-worker paths before packing.

The release gate executes the same-version packages from fresh standalone Kotlin/JVM, Kotlin/JS, and strict TypeScript consumers and writes one sorted SHA-256 manifest over the actual staged artifacts. IF173 protocol v2 records those real npm/Maven bytes and SHAs beside the JVM/KLIB/native bases. If an archived revision predates a surface it records `NOT_AVAILABLE_IN_BASELINE`/`NO_PREVIOUS_MEASUREMENT` rather than inventing zero bytes. The checksum manifest is deterministic for one assembled payload; raw npm rebuild byte identity is not claimed because the pinned Kotlin 2.2 linker can reorder generated stdlib JS across equivalent runs. R7 owns byte-identical complete mirror assembly.

## 6. Documentation contract

### 6.1 `interpreter/README.md`

This is the canonical human entry point and is copied to the public repository root and packed into the TypeScript/Maven documentation artifacts. It covers:

- what the interpreter does and does not do;
- kernel-free/MIT status and the exact compatibility evidence;
- anonymous install from the GitHub Release tarball;
- Gradle consumption from the Pages Maven URL;
- one minimal TypeScript example and one minimal Kotlin example;
- the workspace expansion/preparation contract and expanded-model fast path;
- full versus partial validation and computation;
- customization entry points;
- supported runtime/zone scope and honest exclusions;
- links to the version-matched API reference and showcase.

Examples in the README are compiled and executed by the non-repo consumer fixtures. The README never documents a proposed type that is not present in the packed artifact.

### 6.2 Developer API guide and generated references

Use one authored guide plus derived references:

| Artifact | Source of truth | Drift guard |
|---|---|---|
| `docs/INTERPRETER-API-GUIDE.md` | task-oriented concepts and tested examples | examples execute in consumer fixtures |
| Kotlin HTML reference | KDoc on the public API, rendered by Dokka | generated from the release source/tag |
| TypeScript HTML reference | the shipped `.d.ts` plus public TSDoc | generated from the packed tarball |
| `llms.txt` | public signatures plus the tested guide snippets | generated and compared to the API/golden inputs |

Dokka supports Kotlin Multiplatform HTML and documentation JARs; see [Dokka’s Gradle documentation](https://kotlinlang.org/docs/dokka-gradle.html). Hand-maintained signature catalogues are excluded because they would drift from the public binary and `.d.ts`.

Every reference is published under a versioned URL. `/api/latest/` is only a pointer/copy updated after the matching release passes the complete gate.

## 7. Browser qualification and showcase

### 7.1 Browser support gate

Adding `browser()` to the Kotlin/JS target is not sufficient evidence. Before the README may claim browser support:

- the portable conformance subset runs in a real browser;
- decimal, regex, timezone, Unicode/grapheme, partial validation, and computation cases cover the host-sensitive boundary;
- browser results match the same expected corpus outcomes as JVM and Node;
- an explicit browser first-answer measurement is recorded and an owner-ratified bar is added;
- the production bundle and source maps contain no Node-only import or polyfill dependency;
- the packed tarball, not an in-repo module shortcut, passes a browser consumer smoke.

Node remains a supported runtime; browser support becomes a separately verified peer, not an assumption inferred from Node.

### 7.2 Showcase scope

The first showcase is a static TypeScript app with no server:

- fetch a bundled workspace manifest containing an entry model, an included model, and a typedef model;
- implement the manifest-backed `ModelSourceProvider`, collect the entry closure on demand, and prepare it in the browser;
- visibly demonstrate one included field/program and one field whose imported typedef supplies its effective semantics;
- edit the versioned placement JSON;
- run full validation, partial validation, and compute-then-validate;
- inspect findings, pointers, severities, suppressions, unsupported constructs, and computed values;
- see the exact package/version and compatibility scope used by the page.

The app deliberately does not become a form builder, model editor, rule authoring tool, performance dashboard, directory crawler, or arbitrary remote-URL loader. Its small bundled raw workspace remains the user-facing IF224 consumer, not a browser-only assembly workaround.

The sample workspace is authored through the dmkits' typed surface and exported into committed/generated showcase fixtures with a freshness guard. The app itself consumes only the public interpreter package.

The app is built with `pnpm`, served under the repository-relative Pages base path, checked with a browser end-to-end smoke, and deployed at:

```text
https://mbackschat.github.io/a12-interpreter-releases/showcase/
```

## 8. Release assets and manifest

Each draft GitHub Release contains at least:

| Asset | Purpose |
|---|---|
| `a12-interpreter-X.Y.Z.tgz` | npm-compatible TypeScript/JavaScript package |
| `dm-interpreter-X.Y.Z-maven-repository.zip` | complete Maven/KMP publication tree for archival/offline install |
| `dm-interpreter-X.Y.Z-api-docs.zip` | version-matched Kotlin + TypeScript reference and `llms.txt` |
| `dm-interpreter-X.Y.Z-showcase.zip` | exact static showcase deployed to Pages |
| `release-manifest.json` | version, source tag/SHA, coordinates, runtime scope, artifact names, sizes, and SHA-256 values |
| `SHA256SUMS` | integrity check over every release asset |

The TypeScript tarball and Maven POMs declare MIT, the public repository URL, the matching GitHub release/tag, and only the dependencies actually present in the artifact graph.

When R10 later publishes `dm-interpreter-kernel-dropin`, the same public release repository additionally carries its binary and source JARs, generated API documentation, EUPL-1.2 text, exact source revision, pinned Kernel API compatibility metadata, and its own checksums. The mirror README and Maven metadata state licenses per artifact; the MIT core is never relabeled or made dependent on the drop-in.

## 9. Release flow

Adopt dmtool’s good mechanics while changing the artifact-specific parts:

1. Bump the independent interpreter version, update `interpreter/CHANGELOG.md`, verify the public API/ABI diff, commit, and push `interpreter-vX.Y.Z`.
2. Run `scripts/publish-interpreter-release.sh interpreter-vX.Y.Z`; it assembles the public mirror workflow/content, configures the fresh mirror clone through [`scripts/release-git-identity.sh`](../scripts/release-git-identity.sh), verifies both `GIT_AUTHOR_IDENT` and `GIT_COMMITTER_IDENT`, pushes it, creates a **draft** `vX.Y.Z` release, and dispatches the public mirror build. The publisher refuses to commit or push unless both identities are exactly `Martin Backschat <mbackschat@mail.de>`, independently of the release machine's global Git configuration.
3. The public workflow checks out the private source tag using a read-only `SOURCE_REPO_TOKEN`, builds all artifacts, runs JVM/Node/browser/package/consumer gates, uploads release assets, and writes `SHA256SUMS`.
4. Review the draft and run `scripts/publish-interpreter-release.sh interpreter-vX.Y.Z --go-live`.
5. The go-live path verifies the asset manifest and checksums, adds the immutable Maven/API version directories to the public tree, deploys Pages, runs anonymous production-URL consumer smokes, and only then publishes the GitHub Release and updates `latest`.

The publisher/go-live operation refuses a wrong author or committer identity, a published release, an existing version directory, a source-tag/version mismatch, a missing `.d.ts`, an empty JS package, a missing documentation page, or a failed production URL smoke.

## 10. Work program

Status values: `pending`, `in progress`, `gated`, `done`. Model tier follows the master-session policy: **BEST** for public-contract, semantics, and cross-platform truth; **REGULAR** only for mechanical work after the contract is frozen.

The `R0`–`R10` rows are release milestones. The `R<n>.<n>` rows in §10.1 are the one-session work sets that advance them. R0 and R1 were completed before this decomposition. Only one set may be `in progress`, every finished set lands as one verified conventional commit, and the same commit updates both its set and milestone status.

| Milestone | Outcome | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R0 | Establish current facts, dmtool pattern, and GitHub-only topology | BEST | — | done | This proposal records the evidence and fixed hosting boundary |
| R1 | Ratify repository name, package coordinates, independent version train, first API slice, provider boundary, first-party-only meaning of GitHub-only, and drop-in sequencing/license | BEST | R0 | done | Every release-boundary decision is recorded in §13 |
| R2 | Implement the original commonMain workspace assembler, assembler-only expanded-model artifact, platform-projected async source-provider collector, structured preparation API, minimum public interpreter façade, document ownership contract, and dmtool workspace dogfooding, TDD-first | BEST | R1 | done | Java-reader/kernel expansion parity plus snapshot/integrity/cancellation/failure/limit/privacy provider laws green on JVM/Node; dmtool expansion/runtime preparation uses the public workspace boundary; public surface contains no implementation leaks |
| R3 | Add production Maven/npm packaging, generated `.d.ts`, Kotlin/TS ABI locks, package allowlist, source/docs artifacts, and delivery-footprint measurement | BEST | R2 | done | Fresh local Maven and packed-tarball consumers compile and run |
| R4 | Add the browser target, portable browser corpus, workspace-preparation coverage, host-sensitive locks, and browser first-answer evidence | BEST | R2, R3 | in progress | Real-browser evaluation/preparation parity and owner-ratified performance bar |
| R5 | Build the static showcase as a packed-package, revisioned manifest/SHA-backed on-demand workspace consumer with Cache Storage support and add browser E2E verification | BEST | R3, R4 | pending | Provider closure, integrity/cancellation/limit failures, offline reload, include/typedef, and clean/firing/formal-invalid/computation scenarios pass in a browser |
| R6 | Write the canonical README/API guide, add tested snippets, and generate Dokka/TypeScript/`llms.txt` references | REGULAR | R3, R5 | pending | Docs describe only shipped API; links and examples are verified |
| R7 | Create the public mirror assembler, build/deploy workflows, cumulative Pages/Maven layout, and staged publisher | BEST | R1, R3, R5, R6 | pending | A dry-run release in a temporary/fresh mirror proves every refusal and success path |
| R8 | Publish `v0.1.0-rc.1`, consume it anonymously from GitHub Release and Pages, and run the full release review gate | BEST | R7 | pending | No in-repo resolution; all public URLs and checksums pass |
| R9 | Fix RC findings by class, publish stable `v0.1.0`, confirm IF224 plus IG85/IG86 disposition, update durable docs, and run the release retro | BEST | R8 | pending | Stable release live; Pages/showcase/API docs live; ledgers clean |
| R10 | Specify, license, implement, and publish the narrow JVM Kernel drop-in artifact as its own follow-up release | BEST | R9 | pending | A pinned `IDocumentRtService` consumer and a real host substitute the implementation unchanged; differential, result-application, cache, extension, source-availability, and mixed-license gates pass |

### 10.1 Session-sized task queue

The current frontier is **R4.2**. Do not start a later set until its dependencies are `done` and the preceding landing has passed the §11 review gate. The public contract comes from [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md); deferred public surfaces live in [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md); [INTERPRETER-FINDINGS](INTERPRETER-FINDINGS.md) owns completed IF224, while [INTERPRETER-GAPS](INTERPRETER-GAPS.md) owns IG85 and IG86 until their acceptance gates pass. Every implementation set uses red/green TDD, preserves each pre-fix failure in a unique `build/<set>-<item>-red.log`, runs the relevant JVM/Node/browser or external-consumer locks fresh, and closes or amends the owning gap/finding rows in the same commit when their acceptance gate has actually passed.

#### R2 — portable workspace and minimum public API

R2.9–R2.11 deliberately use one common assembler for two different terminal operations: `expand` stops at the self-contained JSON artifact, while `prepare` continues through `ModelLoader` and interpreter-evaluability checks. The JVM/Jackson `ModelReader` remains the published `dmkits-core` index/metadata implementation and a parity comparator during v0.1; its first-definition-wins behavior is Kernel-parity permissiveness, while the portable workspace's cross-provider ambiguity refusal is documented deliberate strictness. The adapter's Kernel expansion remains the licensed oracle/read tier. Neither is a third implementation for the new command. R2.11 makes dmtool expansion and runtime preparation share the portable refusal contract and removes `ModelReader` from the runtime path, so the coexistence is a migration stage rather than an accidental permanent split. Extracting a new shared module is excluded until measured maintenance or delivery evidence justifies changing the Java-core and multiplatform publication boundaries.

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R2.1 | Build the reusable model-preparation parity harness and license-safe own-domain raw-workspace fixtures for a self-contained entry, imported typedef, include mount, nested include, alias/exclusion, and cross-model references | BEST | R1 | done | The adapter obtains Java `ModelReader` plus real-Kernel expansion observations, a deliberate negative control proves the comparison fires, and the self-contained baseline agrees with the current common loader on JVM and Node |
| R2.2 | Add immutable `WorkspaceModelSource` admission: exact `header.id` indexing, duplicate rejection, SHA-256 verification, sanitized labels, frozen bytes, and per-source/aggregate resource limits with structured diagnostics | BEST | R2.1 | done | Admission invariants each have a failing lock and pass on JVM/Node; no filesystem, network, storage, Kernel, or JVM type enters `commonMain` |
| R2.3 | Assemble imported and inline type definitions into effective portable types | BEST | R2.2 | done | Typedef fixture matrix matches the R2.1 Java-reader/Kernel observations and unresolved or cyclic typedef residue fails loudly |
| R2.4 | Mount includes with exact `SINGLE_RG`/`MODEL_ROOT`, nesting, alias, `excludeRules`, and `excludeComputations` semantics | BEST | R2.3 | done | Include fixture matrix and a predicted sibling case match both expansion oracles; cycle and depth limits fail structurally |
| R2.5 | Normalize opaque element ids to name paths and remap every reference-bearing rule/computation fragment, including preconditions, alternatives, operations, and relative/short references | BEST | R2.4 | done | A reference-family sweep matches the Java reader and Kernel expansion with no unprepared residue; a second-instance lock covers each shared remapping mechanism |
| R2.6 | Add `ModelWorkspace.fromSources`, `prepare`/`tryPrepare`, `PreparedModel` dependency metadata/fingerprint/opaque owner and version identities, and the strict expanded-input fast path | BEST | R2.5 | done | Raw and pre-expanded paths converge on the same prepared semantics, unused sources remain outside the closure, diagnostics contain no acquisition secrets, and the public boundary exposes no internal AST or mutable state |
| R2.7 | Add asynchronous logical-id collection through `ModelSourceProvider` with snapshot revision, expected digest, one-load-per-id, typed no-retry failures, cancellation, acquisition-time byte limits, Kotlin suspension, and the Kotlin/JS `Promise`/`AbortSignal` bridge | BEST | R2.6 | done | Snapshot, integrity, cancellation, failure, limit, privacy, and discovered-closure laws pass on JVM/Node; platform async types remain outside `commonMain` |
| R2.8 | Add placement transport and model-owned document snapshots: V1 typed read/encode through one shared validation engine, bounds, structured input errors, owner/version-bound snapshots, and cross-model/stale-snapshot rejection. Public pointers move to R2.10 with their canonical `ModelMetadata.pointer` factory — pointer classes without that factory would be dead surface | BEST | R2.6 | done | Transport round trips on JVM/Node; malformed, oversized, foreign, and stale inputs fail without partial mutation; over-limit group-coordinate acceptance and field-terminal refusal carry permanent Kernel locks |
| R2.9 | Expose `ModelWorkspace.expand`/`tryExpand` as the assembler-only expanded DM-JSON boundary and register the additive public result surface without retaining the JSON in `PreparedModel` | BEST | R2.7, R2.8 | done | JVM/Node laws prove expansion stops before `ModelLoader`: kernel-legal unsupported-zone and month/year-optional models expand while `prepare` refuses; output has no include/`TypeDefType` residue, retains concrete local typedef declarations, matches the Java-reader parity projection, respects preparation limits, and returns no partial artifact on failure; temporary public-surface and purity guards pass |
| R2.10 | Define and expose the minimum interpreter façade for full/partial validation, computation, configuration-aware support reporting, custom condition/custom field-type SPIs through JS-safe result/error types, the Java `CompletionStage` collection projection, and the public pointer types with their canonical `ModelMetadata.pointer` factory (renaming the internal custom-condition `FieldPointer` immediately before they land) | BEST | R2.7, R2.8, R2.9 | done | Common Kotlin JVM/Node consumers and a Java compile fixture use only the public surface; the Java fixture exercises `CompletionStage` success and cancellation, while R3.2 owns the first intentionally exported TypeScript compile fixture; adapter differentials prove callback and evaluation semantics; `verifyInterpreterPurity` and all common JVM/Node tests pass; the browser residual later closes as IF224 |
| R2.11 | Dogfood the public workspace boundary in dmtool: add the bare-artifact/`-o` `model expand` conversion and migrate interpreter runtime preparation (`model eval`/`compute`/`seed` and `rule eval`) from `ModelReader` to on-demand `ModelWorkspace` collection and expansion followed by the strict expanded-input preparation gate | BEST | R2.9, R2.10 | done | The command loads only the discovered exact-id closure, emits no partial artifact, preserves concrete local typedef declarations, expands kernel-legal interpreter-unsupported configuration while runtime verbs refuse it at the evaluator gate, and shares portable ambiguity/residue behavior with runtime preparation; the runtime path assembles once rather than calling both `prepare` and `expand`, while the strict fast path preserves the same evaluator checks; `ModelReader` remains only in core index/metadata and parity roles; the same commit updates `ManifestVerbTest.EXPECTED_VERBS`, the described/parameter/schema/demonstration conformance matrix, `verb-examples.json`, README verb tour, CLI-SPEC, command-reference coverage, `SchemaCommand.CONTRACT_EXEMPT` with its artifact rationale, semantics map, and all affected JVM/native/demo guards |

#### R3–R6 — packages, browser, showcase, and documentation

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R3.1 | Add the independent version source and Maven/KMP publication with POM identity, MIT metadata, sources, API docs, and a fresh Kotlin/JVM consumer | BEST | R2.11 | done | A consumer outside the repository resolves only the staged GitHub-layout Maven tree, compiles, and evaluates a model |
| R3.2 | Add the intentional TypeScript export façade and pack the real npm-compatible tarball with JavaScript, `.d.ts`, browser-safe source maps, metadata, license, and README | BEST | R2.11 | done | A fresh pnpm project installs the tarball by file/URL form, type-checks, expands and prepares a workspace, validates, and computes without repository substitution |
| R3.3 | Add Kotlin and TypeScript ABI locks, package allowlists, dependency/secret scans, a deterministic checksum manifest, and real npm/Maven delivery-footprint plus previous-release delta reporting | REGULAR | R3.1, R3.2 | done | Contract and package-content drift fail mechanically; the packed artifacts pass purity and consumer smokes; IG85 implementation is complete pending anonymous public-repository consumption |
| R4.1 | Add the Kotlin/JS browser target and a minimal bundler consumer of the packed tarball with no Node-only API | BEST | R3.2 | done | The packed interpreter loads and evaluates in a real browser under the repository-relative Pages base path |
| R4.2 | Replay the portable conformance and R2 raw-workspace subsets in a real browser, retain host-sensitive decimal/regex locks, and measure browser first-answer independently | BEST | R4.1, R2.11 | in progress | Browser results match the qualified JVM/Node corpus and IF224 is graduated; the remaining owner-ratified performance bar graduates IG86 |
| R5.1 | Build the accessible static TypeScript showcase as a genuine packed-package consumer: edit placement JSON, prepare file-map and manifest/provider workspaces, validate, compute, and render structured diagnostics | BEST | R3.3, R4.2 | pending | Browser E2E covers clean, firing, formal-invalid, computation, include, and imported-typedef scenarios without internal imports or hand-expanded showcase models |
| R5.2 | Add revisioned manifest/SHA acquisition, cancellation/integrity/limit failure UX, Cache Storage offline reload, and production-style GitHub Pages base-path tests | BEST | R5.1 | pending | A real browser proves discovered-closure loading, safe errors, offline reload, cache revision isolation, and no credential/private-path exposure |
| R6.1 | Write the public interpreter `README.md` and concise developer guide with tested TypeScript, Kotlin, Java, finite-map workspace, on-demand provider, document, validation, computation, and customization examples | REGULAR | R3.3, R5.1 | pending | Every snippet compiles or executes against packed artifacts and every referenced Markdown link resolves |
| R6.2 | Generate versioned Kotlin and TypeScript API references plus `llms.txt`, package them as the documented API-doc asset, and add no-drift guards | REGULAR | R6.1 | pending | Public declarations are documented, generated references match ABI locks, the archive has no private/internal surface, and docs contain no promise beyond the shipped contract |

#### R7–R9 — public repository and release

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R7.1 | Build the deterministic public-mirror assembler with public README, license/disclaimer, showcase, cumulative Maven/API layout, workflow sources, and an explicit file allowlist | BEST | R3.3, R5.2, R6.2 | pending | Two assemblies are byte-identical apart from declared provenance fields; secret/private-source and unexpected-file injections are rejected |
| R7.2 | Add staged source-tag publisher and public-repository workflows for draft assets, checksums, Pages deployment, immutable version directories, anonymous smokes, publisher-owned author/committer identity, and go-live refusals | BEST | R7.1 | pending | A disposable fresh mirror proves every commit is `Martin Backschat <mbackschat@mail.de>` and exercises every refusal plus the complete draft-to-production path without publishing a production identity |
| R8.1 | Create the public `a12-interpreter-releases` repository and publish `v0.1.0-rc.1` through the staged path | BEST | R7.2 | pending | The release remains draft until anonymous Maven, tarball, API-doc, showcase, checksum, and production Pages smokes pass; the reviewed release is then made public and IG85 graduates to a finding |
| R9.1 | Review RC evidence and user feedback; for every finding, reproduce, generalize, and insert one bounded corrective `R9.F<n>` set for the root-cause class | BEST | R8.1 | pending | The RC has no unresolved release blocker and every correction has fresh package/browser/consumer evidence |
| R9.2 | Publish stable `v0.1.0`, preserve immutable RC/versioned Pages content, confirm IF224 plus IG85/IG86 disposition, update durable docs, and run the full program retro | BEST | R9.1 | pending | Stable assets, Maven tree, API docs, and showcase are anonymously consumable from the public repository; manifests/checksums agree; ledgers are clean |

#### R10 — later EUPL Kernel drop-in

R10 starts only after the independently consumable MIT core is stable. Re-audit the pinned Kernel interface and license boundary at that time; if the contract has changed, decompose these rows further before implementation.

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R10.1 | Create the separate EUPL-1.2 JVM drop-in module, pin and audit the complete `IDocumentRtService` contract, keep Kernel APIs compile-only/host-provided, and add bytecode/runtime-closure/source-availability guards | BEST | R9.2 | pending | The artifact contains no Kernel bytecode or runtime dependency, ships corresponding source/license/docs, and a pinned consumer compiles against every required method including deprecated overloads |
| R10.2 | Implement Kernel model/document ingress, prepared-model resolution, `precompileDocumentModel`, `createModelCodeIdentifier`, and lifecycle/cache identity | BEST | R10.1 | pending | Pinned-service differentials cover model conversion, ownership, precompile/cache hits and misses, variants, identifier behavior, and failure translation without Kernel evaluation delegation |
| R10.3 | Implement modern and deprecated `validateFull`/`validatePart` methods plus the complete `IDocumentValidationResult` projection | BEST | R10.2 | pending | Dynamic-Groovy differentials match messages, formal findings, pointers, repetition coordinates, locale, and partial scope; static-Java evidence is recorded wherever applicable |
| R10.4 | Implement modern and deprecated `compute` methods plus complete `IDocumentComputationResult` partitions and `applyTo(DocumentV2)` behavior | BEST | R10.3 | pending | Dynamic-Groovy differentials match stored values, clear operations, result partitions, and applied documents; static-Java evidence is recorded wherever applicable |
| R10.5 | Adapt clock, custom-condition, custom-field-type, and legacy `additionalInformation` configuration with exact factory cardinality and lane-confined lifecycle | BEST | R10.4 | pending | Existing Kernel extension implementations run unchanged; callback, lifecycle, deprecated-context, and no-customization-path differentials pass |
| R10.6 | Substitute the drop-in unchanged in a real host, publish it as a separately licensed artifact, and publish the pinned compatibility matrix | BEST | R10.5 | pending | Real-host behavior, mixed-license metadata, source availability, checksums, anonymous consumption, and compatibility claims all pass |

No set may publish or deploy while another session is active in the worktree.

### 10.2 Program log

- R2.1 extracted one representation-neutral preparation comparator and reused it in the older model-index parity lock, avoiding a second expansion-comparison mechanism.
- The own-domain fixture author and freshness guard make the committed raw source closure reproducible through the typed kits; the same self-contained source executes on JVM and Node.
- The red compile failure is preserved at `build/R2.1-parity-compile-red.log`; the R2.1 landing passed the real-Kernel/JVM preparation comparison, its deliberate negative control, include-remapping tri-check, fixture freshness guard, common JVM/Node baseline, purity lint, and semantics-map guard.
- R2.2 admits an exact-id immutable source snapshot with strict UTF-8 SHA-256, duplicate and integrity diagnostics, syntactically sanitized labels, and documented browser-bounded defaults/hard maxima. A non-allocating strict UTF-8 pass refuses per-source and aggregate excess before allocating frozen bytes, parsing, or hashing; exposed lists and byte arrays resist runtime mutation on JVM and Node. The pre-fix failures are preserved at `build/R2.2-source-admission-red.log`, `build/R2.2-limit-order-red.log`, `build/R2.2-aggregate-bounded-red.log`, and `build/R2.2-strict-immutable-red.log`.
- R2.3 resolves local, imported, transitive, diamond-shared, and direct or transitively include-contributed type definitions into concrete portable field types with iterative memoized chains, mixed-purpose graph-cycle checks, bounded structure traversal, assembly-wide ambiguity refusal, structured malformed-declaration failures, and no hidden unused residue. The fixture manifest carries Java-reader effective-type observations already checked against the real Kernel, and the portable JVM/Node suite compares directly to them. Targeted Kernel locks establish the deliberately stricter cross-provider collision policy; include-contributed typedef authoring exposed and closed modelkit gap DG22. Representative pre-fix failures are preserved at `build/R2.3-typedef-assembly-red.log`, `build/R2.3-unused-typedef-residue-red.log`, `build/R2.3-typedef-only-residue-red.log`, `build/R2.3-include-contributed-typedef-red.log`, `build/R2.3-modelkit-include-typedef-red.log`, `build/R2.3-node-surrogate-guard-red.log`, `build/R2.3-malformed-typedef-red.log`, and `build/R2.3-transitive-include-typedef-red.log`. The landing passed common JVM/Node tests, the modelkit consumer lock, Java-reader/Kernel preparation and collision locks, fixture freshness, purity, semantics-map, and documentation guards.
- R2.4 mounts exact-alias `SINGLE_RG` and `MODEL_ROOT` includes, including repeated mounts, nested providers, an include at the discarded provider root, independent rule/computation exclusions, final-expanded-`usageType` attachment shielding, and consumed-directive cleanup. A shared presence-aware group classifier makes malformed source structure fail without silent drops; the real-Kernel matrix characterizes zero/multiple roots, provider body/elements types, both permissive malformed exclusions, and the noncanonical repeatability values that the portable boundary deliberately refuses. Each mount now fully expands its provider before applying only that mount's exclusions, so a nested group's final marker controls shielding and excluded occurrences still count as expansion work. The Kernel measurement also corrected a provisional fallback hypothesis before landing: for `SINGLE_RG`, final provider-root absence clears a raw mount marker and supplies no attachment shield, for both ordinary and discarded-provider-root nesting. The full structural projection compares group repeatability/`usageType`, field kind and constraints, annotations, rules, and computation targets through Java `ModelReader` and Kernel expansion; that work exposed and closed typed navigation gap DG23 and corrected `ModelReader`'s discarded-root attachment and provider-absence behavior. Each physical executable occurrence retains immutable provider namespace provenance, source/output mappings, source-local short-name/language policy, the full reference chain, and a structural ordinal without quadratic copying. Cycle, combined-depth, expanded-element, exact compact-UTF-8-byte, and Unicode-scalar guards bound preparation before unbounded output is built. Representative pre-fix failures are preserved at `build/R2.4-include-assembly-red.log`, `build/R2.4-invalid-include-structure-red.log`, `build/R2.4-node-portability-red.log`, `build/R2.4-predicted-siblings-red.log`, `build/R2.4-final-usage-shield-red.log`, `build/R2.4-provider-absence-red.log`, and `build/R2.4-excluded-work-limit-red.log`; focused post-fix and Kernel evidence is at `build/R2.4-provider-absence-green.log` and `build/R2.4-remaining-include-kernel-basis.log`. The fresh complete gates are preserved at `build/R2.4-full-interpreter-gate.log` and `build/R2.4-full-integration-gate.log`: common JVM/Node suites, purity lint, Java-reader/Kernel preparation and structural-oracle suites, fixture freshness/output declarations, API goldens, semantics-map, and documentation guards all passed.
- R2.5 normalizes every retained opaque element id to its final name path, binds absolute, relative, and unique provider-local short references before projecting them through segment-indexed mount mappings, and remaps rule conditions plus every computation fragment without reformatting authored DSL bytes. Each executable retains source-language provenance; English and German parsing is catalog-locked, foreign function and `@From`/`@Von` tokens fail closed, language-neutral mixed-provider fragments remain legal, and the Java/common scanners share an exact accepted-vocabulary matrix. Final id and fragment growth is charged before derived paths or replacement strings are allocated. Typed own-domain fixtures cover single, model-root, repeated, nested, German, neutral, and rejected mixed-language mounts; the prepared output loads and executes on JVM and Node and is compared to Java-reader and real-Kernel evidence. The landing is `3273aaa2`; its fresh complete gate passed root API/docs, common JVM/Node, purity, typed fixture freshness, and the full Kernel-backed adapter suite.
- R2.6 exposes the first finite public preparation boundary: immutable `WorkspaceModelSource` lists enter `ModelWorkspace.fromSources`, `prepare` and `tryPrepare` resolve only the selected closure, and `PreparedModel.loadExpandedJson`/`tryLoadExpandedJson` provide the strict pre-expanded path. The prepared result exposes each distinct dependency edge once in first-discovery order, a deterministic fingerprint, and unforgeable process-local owner/version identities without leaking the internal `ModelDef`. The fingerprint is intentionally small: it reuses admission's exact-source SHA-256 values and hashes one versioned, mode-separated, byte-length-framed record ordered by unsigned UTF-8 model ids; R2.6 adds no content-addressed store, cache, virtual filesystem, provider lifecycle, or hash-algorithm abstraction. Unused sources—including an admission-valid but preparation-invalid source—remain outside both preparation and identity. A shared presence-aware classifier makes the validated `content.modelRoot.rootGroups`/nested-`Group.elements` tree the only element source for both raw assembly and strict expanded loading; real-Kernel probes lock refusals for element records hidden in either header metadata or a real Field body, and document the two deliberately stricter portable identity/body cases. Unknown date precision and above-root computation targets likewise fail as structured preparation errors rather than being silently defaulted or leaked as host exceptions. Post-landing review also removed the production-only test byte accessor and caller-shaped completion flag, made timezone failures retain their structured id, and hardened the temporary JVM surface guard against direct and generic root-package leaks; the complete packaged ABI remains assigned to R3.3. The pre-fix failures are preserved at `build/R2.6-public-preparation-api-red.log`, `build/R2.6-strict-expanded-input-red.log`, and `build/R2.6-review-corrections-red.log`. The semantics-map guard now discovers the full `Workspace*Test` preparation-lock category at the shared root instead of enumerating individual files.
- R2.7 adds the snapshot-scoped `ModelSourceProvider` and a deterministic sequential collector that loads each discovered exact id at most once, never retries, verifies returned integrity and identity before following edges, supplies the remaining byte ceiling and cancellation state to every load, independently rechecks all source limits, and freezes only the discovered closure into `ModelWorkspace`. Typed and untyped provider failures preserve a stable reason and cause without copying provider messages or URLs into diagnostics; snapshot revision is optional sanitized provenance and does not enter `PreparedModel` identity. Collection and synchronous preparation now share one reference enumerator plus the occurrence-based edge/depth/cycle guard, so declared-but-unmounted includes and imports suppressed by local typedef mode are not fetched and graph failures retain the same complete chain. The own-domain typed fixture covers a diamond typedef closure, a mounted include, and a declared-but-unmounted model; a real-Kernel probe also established that a structural include provider cannot legally double as a typedef-import provider, defeating the only candidate need for collector role-state. The common API uses Kotlin suspension plus `ModelCollectionSignal` with no `kotlinx.coroutines` dependency, while an unexported stdlib-only Kotlin/JS bridge proves real `Promise` and `AbortSignal` behavior until R3.2 owns the exported TypeScript façade. Pre-fix evidence is preserved at `build/R2.7-collection-fixture-red.log`, `build/R2.7-collection-api-red.log`, and `build/R2.7-js-promise-bridge-red.log`; the fresh gates passed full interpreter JVM/Node suites, purity and temporary JVM-surface locks, typed-fixture freshness, Java-reader/real-Kernel preparation parity, and the semantics-map guard.
- R2.8 adds the lossless V1 placement transport and opaque model-owned `DocumentSnapshot` boundary in `b29d4221` and `6f413949`: one full-validation-before-build engine enforces resource limits, strict envelope shape, model paths/arity, duplicate/conflict rules, owner authenticity, and absent/present-empty/raw fidelity on JVM and Node. Kernel measurement then refined the coordinate contract in `d23ba700`: group coordinates above their effective maximum stay representable for formal validation, while a field terminal other than 1 is refused at conversion and now receives the dedicated `INVALID_FIELD_REPETITION` diagnostic. The measured fixed-group runtime sibling landed in `aaee5ca5`: one group-only effective-bound map drives `zuGrosseLfdNummer`, ordinary-check suppression, and aggregate/join exclusion without treating fields or unbounded repetitions as bounded axes. Typed differential dogfooding exposed the adjacent shared renderer defect fixed in `dbc9fc56`, so references across root groups remain absolute instead of producing an illegal parent traversal. Red evidence is preserved at `build/R2.8-substrate-red.log`, `build/R2.8-placement-transport-red.log`, `build/R2.8-field-repetition-red.log`, `build/R2.8-cross-root-render-red.log`, and `build/R2.8-nonrepeatable-groups-red.log`. The completion gate passed full interpreter JVM/Node, runtime-closure and purity checks, the complete Kernel-backed adapter estate, the CLI estate, and documentation/semantics-map guards.
- R2.9 exposes one assembler-only expanded-model conversion through `ModelWorkspace.expand`/`tryExpand`: it reuses the exact assembly seam used by `prepare` but returns before `ModelLoader`, does not retain a second model-sized string in `PreparedModel`, and returns no partial artifact on failure. JVM/Node laws prove that Kernel-legal `Europe/Paris`, `MONTH_OPTIONAL`, and `YEAR_OPTIONAL` sources expand even though interpreter preparation refuses them; retained local typedef declarations, consumed-residue removal, prepared-byte limits, and throwing/non-throwing failure parity are locked. The shared reference-assembly fixture remains directly compared with Java-reader/Kernel observations, and the additive result types are registered in the temporary JVM surface guard. Red evidence is preserved at `build/R2.9-expansion-api-red.log`; the completion gate passed full interpreter JVM/Node, runtime-closure and purity checks plus the focused adapter fixture/parity guards.
- R2.10 exposes the model-owned minimum interpreter façade rather than publishing the internal engine shape: one `PreparedModel` now owns metadata and canonical group/field/relevance pointer factories, document snapshots, full/partial validation, computation, support reporting, and immutable custom-condition/custom-field registries. Host factory, callback, and recursive-entry failures cross the boundary as stable typed integration errors; results contain detached immutable records, and foreign or stale model-owned inputs fail before evaluation. The Java projection adapts the common suspending collector to `CompletionStage`, propagates caller cancellation into both the portable signal and in-flight provider stage, and translates provider-stage cancellation back into the common `CANCELLED` diagnostic without adding a coroutine dependency. Common JVM/Node consumers, Java compile/runtime consumption, public-surface construction/leak guards, and real-Kernel customization differentials lock the contract; R3.2 retains responsibility for intentional TypeScript export names and declarations. Red evidence is preserved at `build/R2.10-public-interpreter-api-red.log` and `build/R2.10-java-completion-stage-red.log`.
- R2.11 adds dmtool `model expand` as a bare self-contained DM-JSON artifact or no-partial-write `-o` operation and migrates every interpreter runtime verb from the Java `ModelReader` assembler to public on-demand `ModelWorkspace` collection/expansion plus the strict expanded-input evaluator gate. The CLI adapter supplies only ids requested by the portable collector through the existing filesystem `ModelResolver`; assembly remains single-sourced in commonMain, while `ModelReader` remains only in Java core metadata/index and parity roles. Conversion stops before evaluator-only timezone/date-precision checks, runtime retains the JVM-valid-timezone versus typo distinction, portable diagnostics map to corrective `refused` envelopes, and the obsolete CLI relativization exception is removed. Manifest/help, parameter, schema-exemption, demonstration, command-reference, forced-native discoverability, README/CLI-SPEC/architecture, gap/finding, and semantics-map obligations travel with the feature. Red evidence is preserved at `build/R2.11-model-expand-red.log`; the focused runtime/conformance suites passed, all 11 discovered demos matched the live CLI, and `build/r211-full.log` records the 6m12s scoped completion gate across interpreter checks, fresh Kernel-backed adapter/CLI estates, and documentation guards.
- R3.1 gives `:interpreter` its independent `interpreter/VERSION` SemVer source and stages the root, JVM, and JS Kotlin Multiplatform publications under the exact GitHub Pages Maven layout. Every publication carries the MIT/public-release/tag POM identity, Kotlin module metadata, source artifact, and a Dokka HTML documentation JAR; implementation subpackages stay auditable in source artifacts without being presented as the supported API reference. The verification task rejects missing or empty platform, metadata, source, documentation, or POM artifacts and validates identity/license/release metadata. Its fresh Kotlin/JVM consumer is copied into a separate build, uses a separate Gradle home with no repository init scripts, resolves first-party coordinates only from the staged tree and third-party dependencies only from public upstream repositories, then prepares a typed-authored model and executes validation plus computation. The intentional initial failure is preserved at `build/R3.1-maven-publication-red.log`; focused publication evidence is at `build/R3.1-maven-publication-green.log`.
- R3.2 packages `@mbackschat/a12-interpreter` as ESM through a two-layer boundary: a private compiler-generated Kotlin/JS bridge and an authored TypeScript façade containing the intentional public ABI. The façade exposes finite and provider-collected workspaces, assembler-only expansion, strict preparation, runtime-`unknown` placement decoding, owner-bound documents/pointers, partial/full validation, computation, support reporting, native typed errors, and synchronous custom condition/field-type callbacks. Its positive signed-32-bit coordinate guard is locked against every pinned Kernel `DocumentPointer` factory and prevents Kotlin/JS narrowing; thenables fail as typed integration errors rather than being awaited. The tarball carries MIT metadata, README/license, browser-safe embedded-source maps, and only the runtime `big.js` dependency. A fresh pnpm project installs the actual tarball with no substitution, compiles under strict TypeScript, exercises include/typedef collection and expansion, structured decode failures, coordinate refusals, validation/computation, customization invocation, and the thenable refusal, then runs on Node. Red evidence is preserved at `build/R3.2-typescript-package-red.log` and `build/R3.2-customization-red.log`.
- R3.3 freezes the intentional package-root Kotlin and TypeScript contracts in reviewed ABI files, with a TypeScript negative control proving that a public declaration cannot leak a package-local support type. Exact normalized Maven and npm file allowlists, explicit POM/module dependency sets, source/metadata secret scans, clean Maven staging, and one sorted SHA-256 manifest make package drift mechanical without claiming byte-identical compiler output. Fresh standalone Kotlin/JVM and Kotlin/JS builds resolve only the staged first-party Maven coordinates and execute validation/computation; the existing strict TypeScript build installs and executes the packed tarball. IF173 protocol v2 adds the resulting npm tarball and exact staged Maven repository to the five existing bases, records pre-package revisions as `NOT_AVAILABLE_IN_BASELINE`, and emits no invented zero or ratio. The baseline archive is initialized only as a disposable empty Git worktree so the standard isolated build wrapper can run; report identity remains the resolved archived commit. Red evidence is preserved at `build/R3.3-kotlin-abi-red.log`, `build/R3.3-package-policy-red.log`, and `build/R3.3-delivery-baseline-red.log`; the completion gate passes the full interpreter JVM/Node estate, package/ABI/purity locks, all three external consumers, adapter and CLI estates, documentation guards, and the clean-tree current/previous delivery comparison.
- R4.1 registers Kotlin/JS's browser environment while retaining Node as the shared-test host, then adds one fresh Vite consumer that installs the actual tarball and fetches its typed-authored model relative to `/a12-interpreter-releases/showcase/`. A deadline-bounded `playwright-core` harness launches the system Chrome/Chromium rather than downloading a browser and proves preparation, structured validation, and decimal computation from the production bundle. The package's existing Node-import scan remains the static purity gate. Kotlin's Node and browser library-distribution tasks share one output directory, so the external browser gate intentionally consumes the already packed Node-produced library instead of requesting a redundant conflicting copy task. Red evidence is preserved at `build/R4.1-browser-target-red.log`, `build/R4.1-browser-consumer-red.log`, and `build/R4.1-browser-distribution-red.log`.
- R4.2 semantic qualification extends that same packed-package consumer rather than adding an in-repo browser shortcut. System Chrome replays all 33 clock-independent Kernel-grounded corpus cases with exact result multisets and no unsupported programs; the one explicit-clock case is named and excluded because the v1 exported façade intentionally has no clock projection. The exact four-source raw workspace is prepared through both finite and asynchronous collection, which lock deterministic request order, equal fingerprints, mounted references, and imported typedef constraints. The typed-authored baseline adds decimal precision, Java horizontal-whitespace/grapheme regex, and Europe/Berlin spring-DST discriminators that run on JVM, Node, and Chrome. The B1 10,000-row first-answer scenario prepares and materializes outside the clock, then times `createInterpreter()` through the complete, semantically checked `validateFull` result; the separate 50-fresh-Chrome collector remains informative until the owner ratifies its p95 bar. IF224 graduates the workspace gap. Red evidence is preserved at `build/R4.2-host-fixture-red.log` and `build/R4.2-browser-qualification-red.log`.

## 11. Review gate per landing

In addition to the normal master-session review gate, every release-proposal landing answers:

1. Is the change at the public boundary or shared root, rather than a showcase/package workaround?
2. Does every advertised API invariant have an enforcement locus and a failing test?
3. Did a consumer resolve the packed/published artifact without project substitution?
4. Are Node, browser, and JVM claims backed by the appropriate runtime?
5. Did purity, differential, conformance, and delivery-footprint gates run fresh?
6. Does the public package contain exactly the allowlisted files and dependencies?
7. Are older Maven/API versions still byte-identical and reachable?
8. Did any private path, username, token, private repository URL, source-only document, or proprietary fixture leak?
9. Are the README, API references, release manifest, artifact version, and source tag the same identity?
10. Is the release still a draft until Pages and anonymous production consumption are verified?

## 12. Required, optional, and excluded

### Required for `v0.1.0`

- minimum public interpreter API;
- commonMain raw-workspace preparation with include/typedef/id/reference-remap parity;
- JVM/KMP and TypeScript packages;
- GitHub Release and Pages distribution;
- README, tested developer guide, generated Kotlin/TypeScript references, and `llms.txt`;
- real-browser qualification and static showcase;
- revision/digest-pinned showcase workspace with verified Cache Storage offline reload;
- ABI/package-content/delivery-footprint locks;
- anonymous non-repo consumers and immutable staged release flow.

### Optional after `v0.1.0`

- direct `@mbackschat/a12-operators` product;
- Zod adapters;
- packaged reusable IndexedDB, Cache Storage, OPFS, user-directory, Node/Next.js, database, or object-store provider adapters beyond the core provider port and showcase-local HTTP/Cache Storage implementations;
- JVM-only EUPL-1.2 `dm-interpreter-kernel-dropin`, published separately after core `v0.1.0` and its Kernel-version compatibility implementation is proven;
- richer showcase visualizations;
- custom domain or custom Pages theme;
- public source snapshot beyond the source JAR.

### Explicitly excluded from this proposal

- npmjs.org, Maven Central, and GitHub Packages publication;
- mirroring third-party dependency registries;
- extracting `:interpreter` into a sibling development repository;
- the full service pool/Worker coordinator/candidate-program/form-editor end state;
- host filesystem/network discovery inside the common interpreter API;
- an arbitrary remote-workspace browser UI beyond the bundled showcase;
- changing the kernel consistency/MVK oracle or dmtool’s release channel;
- publishing the existing broad internal `:adapter` module.

## 13. Decision record

Ratified on 2026-07-26:

- the repository, Maven coordinate, tag, Pages, and independent-version identities in §3.1;
- `@mbackschat/a12-interpreter` as the TypeScript package name;
- “GitHub-only” applies to first-party artifacts; third-party dependencies continue to resolve from Maven Central/npm;
- publish the independently consumable interpreter core first; the Operator API is a separate, much later artifact;
- keep preparation deterministic and synchronous over an immutable workspace indexed by exact `header.id`;
- reject duplicate ids rather than selecting by caller/filesystem order;
- expose the minimal asynchronous provider/collector contract with snapshot consistency, integrity, platform-native async/cancellation, typed failures, acquisition-time limits, sanitized diagnostics, and the storage boundaries in §5.2;
- expose expanded DM-JSON through the portable assembler-only `ModelWorkspace.expand` boundary; do not route conversion through `prepare`/`ModelLoader` or retain a second model-sized JSON string in every `PreparedModel`;
- retain concrete local type-definition declarations in canonical expanded output while removing consumed references/directives and every `TypeDefType` use;
- add dmtool's `model expand` only after the Java on-demand provider projection, then migrate the interpreter runtime verbs to the same on-demand `ModelWorkspace` collector/assembler plus strict expanded-input gate; keep `ModelReader` for the published Java core's index/metadata role and parity evidence rather than creating a third resolver or a speculative shared module;
- configure and verify every public interpreter-mirror commit through `scripts/release-git-identity.sh`; author and committer must both be exactly `Martin Backschat <mbackschat@mail.de>` before any push;
- publish the independently consumable MIT core `v0.1.0` before the JVM Kernel drop-in;
- publish the bytecode-free JVM drop-in artifact under EUPL-1.2 by explicit owner policy; this is deliberately conservative and is not presented as a consequence of interface linking.

R4.2 semantic qualification is complete. Its 50-process first-answer evidence and owner-ratified p95 bar remain before the set is done.
