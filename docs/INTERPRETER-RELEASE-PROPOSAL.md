# Proposal: publish the interpreter from a public GitHub release repository

## Status

**Execution is in progress; R2.1–R2.4 are complete.** This proposal is also the inter-session state carrier for taking `:interpreter` from an in-repo, source-consumable Kotlin Multiplatform module to a documented public artifact with portable workspace preparation, a browser-qualified TypeScript surface, and a GitHub Pages showcase.

Owner direction fixed on 2026-07-26:

- all first-party release artifacts are hosted by a new public GitHub repository;
- npm-compatible packages are welcome, but the public GitHub repository—not npmjs.org or GitHub Packages—is their host;
- TypeScript packages must not use the `@a12` scope because it implies an official A12 package; the accepted owner-signalling replacement is `@mbackschat/a12-interpreter`;
- public third-party dependencies continue to resolve from Maven Central/npm;
- the release needs a public `README.md`, developer API documentation, and a TypeScript/JavaScript browser showcase hosted on GitHub Pages;
- the browser interpreter must use a workspace abstraction to resolve included models and imported typedefs, making IG68 a required release gap alongside IG85 and IG86;
- the independently consumable interpreter core ships first; the separate Operator API is a much later artifact and does not block it;
- the immutable workspace/preparation boundary and asynchronous host-provider layer are accepted, including snapshot/integrity, cancellation/failure, acquisition-limit, safe-diagnostic, platform-projection, and browser-storage requirements;
- a separately packaged EUPL-1.2 JVM adapter may expose the interpreter as a drop-in `IDocumentRtService` implementation for existing Kernel-consuming applications after the MIT core `v0.1.0`; it embeds no Kernel bytecode and is not part of the portable core;
- implementing the core of [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md) is the intended API-productization follow-up.

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
| Purity | `verifyInterpreterPurity` excludes kernel, Groovy, dmtool, and sibling-kit runtime coupling | Preserve this as a publication gate |
| Correctness | Differential laws and the conformance corpus use the real kernel as oracle | Package/browser work must not weaken or replace these gates |
| Public API | `DmInterpreter` exists, but much of today’s Kotlin surface is an implementation-shaped API | Do not publish the accidental current surface as the long-lived contract |
| TypeScript | No functional `@JsExport` façade, production npm package, or generated `.d.ts` exists | Implement the minimum public API before publishing or building the showcase |
| Browser | Only `nodejs()` is configured; browser support and browser first-answer performance are unverified (IG86) | Add and qualify a real browser lane before making the browser claim |
| Workspace preparation | `ModelLoader` accepts expanded, self-contained, name-path-keyed DM-JSON; only the JVM-side Java `ModelReader` resolves raw includes, imported typedefs, opaque ids, and mounted references (IG68) | Implement one original commonMain workspace assembler and prove parity before the showcase may claim raw-model support |
| Packaging | No Maven/npm publication wiring or ABI lock exists (IG85) | Publication, non-repo consumer smokes, package measurement, and ABI locks land together |
| Docs | The root README explains the engine, but there is no interpreter-owned package README or shipped API reference | Add one canonical package README plus derived references |
| License | The interpreter core is original, kernel-free MIT code; the JVM drop-in has a separate Kernel-facing boundary | Keep the core artifacts MIT and publish the bytecode-free drop-in artifact under EUPL-1.2 by explicit owner policy; never copy Kernel code or silently publish a mixed-license binary |
| Kernel drop-in | The internal `:adapter` is a broad Kernel oracle/backend, not a narrow distributable replacement service | After core `v0.1.0`, design and publish a separate JVM-only `IDocumentRtService` adapter with a pinned compatibility matrix |

The browser workspace/showcase and publication request flip IG68, IG85, and IG86's re-open triggers. They are now open release requirements and must be resolved through their normal lifecycle when this work ships.

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

The TypeScript acquisition façade adds `ModelSourceProvider.load(modelId, context): Promise<WorkspaceModelSource | undefined>` and `ModelWorkspace.collect(entryModelId, provider, options)`. Collection calls the provider at most once for each discovered exact id, follows only the transitive closure, freezes the returned source bytes into an immutable workspace, and then invokes the synchronous common assembler. Platform projections use a Promise plus `AbortSignal` in TypeScript, a suspending Kotlin façade, and `CompletionStage` in Java; neither `Promise` nor `AbortSignal` enters `commonMain`.

One provider instance represents one logical read snapshot for a collection. An immutable HTTP manifest supplies a revision and per-model SHA-256 values; database/Git providers pin a transaction or revision. The collector verifies any expected digest and computes the final `ModelFingerprint` from the actual resolved bytes, so later provider mutation cannot change a prepared model and a mixed or corrupted closure fails explicitly.

Providers receive the effective per-source byte ceiling and cancellation signal so they can stop a response/file/blob while reading; checking only after constructing an oversized string is insufficient. The collector independently rechecks per-source and aggregate limits. `undefined` means not found; typed provider failures distinguish access denied, unavailable, cancelled, source too large, integrity mismatch, and unexpected failure, retain a retryability hint, and are never retried implicitly by the collector.

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

The explicit `loadExpandedJson` fast path stays available. The showcase must use the on-demand provider/workspace path, while a duplicate `header.id` is rejected deliberately rather than resolved by filesystem or caller ordering. Asynchronous provider calls end when collection produces the immutable workspace; evaluation and customization callbacks remain synchronous. Provider-wide caching, collector retries, writes, watches, implicit network access, batch-loading APIs, and a general virtual filesystem stay outside v0.1. The full public contract is in [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md) §4.1.

### 5.3 Sequencing recommendation

Implement **the Track-B interpreter core**, not the entire 4,000-line end-state proposal, as the publication prerequisite. The service registry/pool, Worker coordinator, candidate programs, persistent form editor, Zod adapters, and broad Java façade remain consumer-gated follow-ups.

The first interpreter tarball embeds or privately owns the minimum value types it needs. It does not depend on the complete later `@mbackschat/a12-operators` product. Shared public types may be extracted when that direct-operator artifact is selected, with an explicit compatibility plan for the already-published interpreter API.

The portable core also does not depend on Kernel API types. Existing JVM applications may later add the separate `io.github.mbackschat.a12.dm:dm-interpreter-kernel-dropin` artifact and substitute its `IDocumentRtService` implementation without changing their `DocumentV2`, `DocumentProcessingConfig`, validation-result, or computation-result call sites. That adapter is broader than the custom-extension compatibility artifacts: it must implement full/partial validation, computation and `applyTo`, precompile/cache behavior, model-code identifiers, and the pinned interface's deprecated compatibility overloads. Its artifact contains no Kernel bytecode; the already-Kernel-consuming host supplies the pinned Kernel API/runtime. [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md) §10.2 defines the contract and evidence gate.

### 5.4 ABI and package locks

The public contract is guarded at three layers:

- Kotlin/JVM/KMP ABI dump checked in and verified on every build;
- generated TypeScript `.d.ts` checked against a reviewed golden surface;
- an allowlisted packed-file manifest proving the `.tgz` contains the intended JS, `.d.ts`, README, license, and metadata—and no source-tree or machine-local leakage.

Kotlin’s compiler generates `.d.ts` from exported declarations when TypeScript-definition generation is enabled; see [Kotlin/JS project setup](https://kotlinlang.org/docs/js-project-setup.html). Export design must respect `@JsExport`’s exportable-type boundary rather than leaking arbitrary Kotlin collections or implementation types.

The release gate also executes the same-version packages from fresh consumers and records the package bytes/SHA plus previous-version delta in the IF173 delivery report, as IG85 requires.

## 6. Documentation contract

### 6.1 `interpreter/README.md`

This is the canonical human entry point and is copied to the public repository root and packed into the TypeScript/Maven documentation artifacts. It covers:

- what the interpreter does and does not do;
- kernel-free/MIT status and the exact compatibility evidence;
- anonymous install from the GitHub Release tarball;
- Gradle consumption from the Pages Maven URL;
- one minimal TypeScript example and one minimal Kotlin example;
- the workspace-preparation contract and expanded-model fast path;
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

The app deliberately does not become a form builder, model editor, rule authoring tool, performance dashboard, directory crawler, or arbitrary remote-URL loader. Its small bundled raw workspace is a required IG68 consumer, not a browser-only assembly workaround.

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
2. Run `scripts/publish-interpreter-release.sh interpreter-vX.Y.Z`; it assembles the public mirror workflow/content, pushes it, creates a **draft** `vX.Y.Z` release, and dispatches the public mirror build.
3. The public workflow checks out the private source tag using a read-only `SOURCE_REPO_TOKEN`, builds all artifacts, runs JVM/Node/browser/package/consumer gates, uploads release assets, and writes `SHA256SUMS`.
4. Review the draft and run `scripts/publish-interpreter-release.sh interpreter-vX.Y.Z --go-live`.
5. The go-live path verifies the asset manifest and checksums, adds the immutable Maven/API version directories to the public tree, deploys Pages, runs anonymous production-URL consumer smokes, and only then publishes the GitHub Release and updates `latest`.

The go-live operation refuses a published release, an existing version directory, a source-tag/version mismatch, a missing `.d.ts`, an empty JS package, a missing documentation page, or a failed production URL smoke.

## 10. Work program

Status values: `pending`, `in progress`, `gated`, `done`. Model tier follows the master-session policy: **BEST** for public-contract, semantics, and cross-platform truth; **REGULAR** only for mechanical work after the contract is frozen.

The `R0`–`R10` rows are release milestones. The `R<n>.<n>` rows in §10.1 are the one-session work sets that advance them. R0 and R1 were completed before this decomposition. Only one set may be `in progress`, every finished set lands as one verified conventional commit, and the same commit updates both its set and milestone status.

| Milestone | Outcome | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R0 | Establish current facts, dmtool pattern, and GitHub-only topology | BEST | — | done | This proposal records the evidence and fixed hosting boundary |
| R1 | Ratify repository name, package coordinates, independent version train, first API slice, provider boundary, first-party-only meaning of GitHub-only, and drop-in sequencing/license | BEST | R0 | done | Every release-boundary decision is recorded in §13 |
| R2 | Implement the original commonMain workspace assembler, platform-projected async source-provider collector, structured preparation API, minimum public interpreter façade, and document ownership contract, TDD-first | BEST | R1 | in progress | Java-reader/kernel expansion parity plus snapshot/integrity/cancellation/failure/limit/privacy provider laws green on JVM/Node; public surface contains no implementation leaks |
| R3 | Add production Maven/npm packaging, generated `.d.ts`, Kotlin/TS ABI locks, package allowlist, source/docs artifacts, and delivery-footprint measurement | BEST | R2 | pending | Fresh local Maven and packed-tarball consumers compile and run |
| R4 | Add the browser target, portable browser corpus, workspace-preparation coverage, host-sensitive locks, and browser first-answer evidence | BEST | R2, R3 | pending | Real-browser evaluation/preparation parity and owner-ratified performance bar |
| R5 | Build the static showcase as a packed-package, revisioned manifest/SHA-backed on-demand workspace consumer with Cache Storage support and add browser E2E verification | BEST | R3, R4 | pending | Provider closure, integrity/cancellation/limit failures, offline reload, include/typedef, and clean/firing/formal-invalid/computation scenarios pass in a browser |
| R6 | Write the canonical README/API guide, add tested snippets, and generate Dokka/TypeScript/`llms.txt` references | REGULAR | R3, R5 | pending | Docs describe only shipped API; links and examples are verified |
| R7 | Create the public mirror assembler, build/deploy workflows, cumulative Pages/Maven layout, and staged publisher | BEST | R1, R3, R5, R6 | pending | A dry-run release in a temporary/fresh mirror proves every refusal and success path |
| R8 | Publish `v0.1.0-rc.1`, consume it anonymously from GitHub Release and Pages, and run the full release review gate | BEST | R7 | pending | No in-repo resolution; all public URLs and checksums pass |
| R9 | Fix RC findings by class, publish stable `v0.1.0`, confirm IG68/IG85/IG86 closure, update durable docs, and run the release retro | BEST | R8 | pending | Stable release live; Pages/showcase/API docs live; ledgers clean |
| R10 | Specify, license, implement, and publish the narrow JVM Kernel drop-in artifact as its own follow-up release | BEST | R9 | pending | A pinned `IDocumentRtService` consumer and a real host substitute the implementation unchanged; differential, result-application, cache, extension, source-availability, and mixed-license gates pass |

### 10.1 Session-sized task queue

The current frontier is **R2.5**. Do not start a later set until its dependencies are `done` and the preceding landing has passed the §11 review gate. The public contract comes from [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md), while [INTERPRETER-GAPS](INTERPRETER-GAPS.md) owns IG68, IG85, and IG86 until their full acceptance gates pass. Every implementation set uses red/green TDD, preserves each pre-fix failure in a unique `build/<set>-<item>-red.log`, runs the relevant JVM/Node/browser or external-consumer locks fresh, and closes or amends the owning gap/finding rows in the same commit when their acceptance gate has actually passed.

#### R2 — portable workspace and minimum public API

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R2.1 | Build the reusable model-preparation parity harness and license-safe own-domain raw-workspace fixtures for a self-contained entry, imported typedef, include mount, nested include, alias/exclusion, and cross-model references | BEST | R1 | done | The adapter obtains Java `ModelReader` plus real-Kernel expansion observations, a deliberate negative control proves the comparison fires, and the self-contained baseline agrees with the current common loader on JVM and Node |
| R2.2 | Add immutable `WorkspaceModelSource` admission: exact `header.id` indexing, duplicate rejection, SHA-256 verification, sanitized labels, frozen bytes, and per-source/aggregate resource limits with structured diagnostics | BEST | R2.1 | done | Admission invariants each have a failing lock and pass on JVM/Node; no filesystem, network, storage, Kernel, or JVM type enters `commonMain` |
| R2.3 | Assemble imported and inline type definitions into effective portable types | BEST | R2.2 | done | Typedef fixture matrix matches the R2.1 Java-reader/Kernel observations and unresolved or cyclic typedef residue fails loudly |
| R2.4 | Mount includes with exact `SINGLE_RG`/`MODEL_ROOT`, nesting, alias, `excludeRules`, and `excludeComputations` semantics | BEST | R2.3 | done | Include fixture matrix and a predicted sibling case match both expansion oracles; cycle and depth limits fail structurally |
| R2.5 | Normalize opaque element ids to name paths and remap every reference-bearing rule/computation fragment, including preconditions, alternatives, operations, and relative/short references | BEST | R2.4 | pending | A reference-family sweep matches the Java reader and Kernel expansion with no unprepared residue; a second-instance lock covers each shared remapping mechanism |
| R2.6 | Add `ModelWorkspace.fromSources`, `prepare`/`tryPrepare`, `PreparedModel` dependency metadata/fingerprint/support report, and the strict expanded-input fast path | BEST | R2.5 | pending | Raw and pre-expanded paths converge on the same prepared semantics, unused sources remain outside the closure, diagnostics contain no acquisition secrets, and the public boundary exposes no internal AST or mutable state |
| R2.7 | Add asynchronous logical-id collection through `ModelSourceProvider` with snapshot revision, expected digest, one-load-per-id, typed no-retry failures, cancellation, acquisition-time byte limits, and Kotlin suspension/Java `CompletionStage`/TypeScript `Promise` plus `AbortSignal` projections | BEST | R2.6 | pending | Snapshot, integrity, cancellation, failure, limit, privacy, and discovered-closure laws pass on JVM/Node; platform async types remain outside `commonMain` |
| R2.8 | Add placement transport and model-owned document snapshots: V1 decode/encode, bounds, structured input errors, owner identity, pointers, and cross-model/stale-object rejection | BEST | R2.6 | pending | Transport round trips on JVM/Node; malformed, oversized, foreign, and stale inputs fail without partial mutation |
| R2.9 | Define and expose the minimum interpreter façade for full/partial validation and computation plus configuration, custom condition, and custom field-type SPIs through JS-safe result/error types | BEST | R2.7, R2.8 | pending | Kotlin, Java, and TypeScript compile fixtures use only the public surface; adapter differentials prove callback and evaluation semantics; `verifyInterpreterPurity` and all common JVM/Node tests pass; IG68 implementation is complete pending its real-browser gate |

#### R3–R6 — packages, browser, showcase, and documentation

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R3.1 | Add the independent version source and Maven/KMP publication with POM identity, MIT metadata, sources, API docs, and a fresh Kotlin/JVM consumer | BEST | R2.9 | pending | A consumer outside the repository resolves only the staged GitHub-layout Maven tree, compiles, and evaluates a model |
| R3.2 | Add the intentional TypeScript export façade and pack the real npm-compatible tarball with JavaScript, `.d.ts`, browser-safe source maps, metadata, license, and README | BEST | R2.9 | pending | A fresh pnpm project installs the tarball by file/URL form, type-checks, prepares a workspace, validates, and computes without repository substitution |
| R3.3 | Add Kotlin and TypeScript ABI locks, package allowlists, dependency/secret scans, reproducible checksums, and real npm/Maven delivery-footprint plus previous-release delta reporting | REGULAR | R3.1, R3.2 | pending | Contract and package-content drift fail mechanically; the packed artifacts pass purity and consumer smokes; IG85 implementation is complete pending anonymous public-repository consumption |
| R4.1 | Add the Kotlin/JS browser target and a minimal bundler consumer of the packed tarball with no Node-only API | BEST | R3.2 | pending | The packed interpreter loads and evaluates in a real browser under the repository-relative Pages base path |
| R4.2 | Replay the portable conformance and R2 raw-workspace subsets in a real browser, retain host-sensitive decimal/regex locks, and measure browser first-answer independently | BEST | R4.1, R2.9 | pending | Browser results match the qualified JVM/Node corpus; the owner ratifies the explicitly scoped performance bar; IG68 and IG86 graduate to findings |
| R5.1 | Build the accessible static TypeScript showcase as a genuine packed-package consumer: edit placement JSON, prepare file-map and manifest/provider workspaces, validate, compute, and render structured diagnostics | BEST | R3.3, R4.2 | pending | Browser E2E covers clean, firing, formal-invalid, computation, include, and imported-typedef scenarios without internal imports or hand-expanded showcase models |
| R5.2 | Add revisioned manifest/SHA acquisition, cancellation/integrity/limit failure UX, Cache Storage offline reload, and production-style GitHub Pages base-path tests | BEST | R5.1 | pending | A real browser proves discovered-closure loading, safe errors, offline reload, cache revision isolation, and no credential/private-path exposure |
| R6.1 | Write the public interpreter `README.md` and concise developer guide with tested TypeScript, Kotlin, Java, finite-map workspace, on-demand provider, document, validation, computation, and customization examples | REGULAR | R3.3, R5.1 | pending | Every snippet compiles or executes against packed artifacts and every referenced Markdown link resolves |
| R6.2 | Generate versioned Kotlin and TypeScript API references plus `llms.txt`, package them as the documented API-doc asset, and add no-drift guards | REGULAR | R6.1 | pending | Public declarations are documented, generated references match ABI locks, the archive has no private/internal surface, and docs contain no promise beyond the shipped contract |

#### R7–R9 — public repository and release

| Set | Deliverable | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R7.1 | Build the deterministic public-mirror assembler with public README, license/disclaimer, showcase, cumulative Maven/API layout, workflow sources, and an explicit file allowlist | BEST | R3.3, R5.2, R6.2 | pending | Two assemblies are byte-identical apart from declared provenance fields; secret/private-source and unexpected-file injections are rejected |
| R7.2 | Add staged source-tag publisher and public-repository workflows for draft assets, checksums, Pages deployment, immutable version directories, anonymous smokes, and go-live refusals | BEST | R7.1 | pending | A disposable fresh mirror exercises every refusal and the complete draft-to-production path without publishing a production identity |
| R8.1 | Create the public `a12-interpreter-releases` repository and publish `v0.1.0-rc.1` through the staged path | BEST | R7.2 | pending | The release remains draft until anonymous Maven, tarball, API-doc, showcase, checksum, and production Pages smokes pass; the reviewed release is then made public and IG85 graduates to a finding |
| R9.1 | Review RC evidence and user feedback; for every finding, reproduce, generalize, and insert one bounded corrective `R9.F<n>` set for the root-cause class | BEST | R8.1 | pending | The RC has no unresolved release blocker and every correction has fresh package/browser/consumer evidence |
| R9.2 | Publish stable `v0.1.0`, preserve immutable RC/versioned Pages content, confirm IG68/IG85/IG86 disposition, update durable docs, and run the full program retro | BEST | R9.1 | pending | Stable assets, Maven tree, API docs, and showcase are anonymously consumable from the public repository; manifests/checksums agree; ledgers are clean |

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
- publish the independently consumable MIT core `v0.1.0` before the JVM Kernel drop-in;
- publish the bytecode-free JVM drop-in artifact under EUPL-1.2 by explicit owner policy; this is deliberately conservative and is not presented as a consequence of interface linking.

R2.4 is complete. The next program set is R2.5.
