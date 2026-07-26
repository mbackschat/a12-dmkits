# Proposal: publish the interpreter from a public GitHub release repository

## Status

**Planning; no release implementation has started.** This proposal is also the inter-session state carrier for taking `:interpreter` from an in-repo, source-consumable Kotlin Multiplatform module to a documented public artifact with portable workspace preparation, a browser-qualified TypeScript surface, and a GitHub Pages showcase.

Owner direction fixed on 2026-07-26:

- all first-party release artifacts are hosted by a new public GitHub repository;
- npm-compatible packages are welcome, but the public GitHub repository—not npmjs.org or GitHub Packages—is their host;
- the release needs a public `README.md`, developer API documentation, and a TypeScript/JavaScript browser showcase hosted on GitHub Pages;
- the browser interpreter must use a workspace abstraction to resolve included models and imported typedefs, making IG68 a required release gap alongside IG85 and IG86;
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
| License | The interpreter is original, kernel-free MIT code | The public interpreter repository is MIT-only; do not copy dmtool’s EUPL bundle |

The browser workspace/showcase and publication request flip IG68, IG85, and IG86's re-open triggers. They are now open release requirements and must be resolved through their normal lifecycle when this work ships.

## 3. Distribution decision

### 3.1 Recommended identity

These names are proposed and need one owner confirmation before implementation:

| Surface | Recommended identity |
|---|---|
| Public repository | `mbackschat/a12-interpreter-releases` |
| TypeScript package name inside the tarball | `@a12/interpreter` |
| Maven coordinate | `io.github.mbackschat.a12.dm:dm-interpreter:X.Y.Z` |
| Private source tag | `interpreter-vX.Y.Z` |
| Public mirror release tag | `vX.Y.Z` |
| Pages root | `https://mbackschat.github.io/a12-interpreter-releases/` |

Use an independent interpreter SemVer train. Reusing dmtool’s root version would either begin the new library at an unrelated dmtool version or republish unchanged interpreter bytes on CLI-only releases. `:interpreter` may override the root version—the root build already reserves that option.

### 3.2 Why not GitHub Packages

GitHub Packages’ npm registry requires a classic personal access token even to install a public package. That violates the anonymous-consumer outcome. See GitHub’s [npm registry authentication contract](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-npm-registry).

Instead, each GitHub Release carries a standard npm-compatible tarball:

```sh
pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v0.1.0/a12-interpreter-0.1.0.tgz
```

The package’s own `package.json` still declares the normal `@a12/interpreter` import name. The release URL is the distribution coordinate; the lockfile records the resolved artifact and integrity.

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

The common interpreter API accepts an immutable, caller-populated workspace indexed by exact model `header.id`. Browser/Node/JVM adapters acquire source text; `commonMain` performs deterministic synchronous preparation without filesystem, network, credential, or Promise APIs.

Preparation resolves only the selected entry model's transitive closure and:

- follows include and `typeDefinitions` edges with cycle/resource-limit checks;
- reproduces `SINGLE_RG`/`MODEL_ROOT`, nesting, alias, `excludeRules`, and `excludeComputations` semantics;
- merges imported typedefs and inline effective types;
- normalizes opaque ids to name paths;
- remaps rule and computation references across include mounts;
- fails with structured source/model/reference-chain diagnostics on any ambiguity or residue.

The explicit `loadExpandedJson` fast path stays available. The showcase must use the workspace path, while a duplicate `header.id` is rejected deliberately rather than resolved by filesystem or caller ordering. The full public contract is in [INTERPRETER-API-PROPOSAL](INTERPRETER-API-PROPOSAL.md) §4.1.

### 5.3 Sequencing recommendation

Implement **the Track-B interpreter core**, not the entire 4,000-line end-state proposal, as the publication prerequisite. The service registry/pool, Worker coordinator, candidate programs, persistent form editor, Zod adapters, and broad Java façade remain consumer-gated follow-ups.

Before coding, reconcile one boundary with [TS-API-MVP-PROPOSAL](TS-API-MVP-PROPOSAL.md): whether the first interpreter tarball embeds the minimum value types it needs or depends on a separately released `@a12/operators` package. Recommendation: keep the first interpreter release independently consumable and do not make the full Operator API a release blocker; split `@a12/operators` only when its direct-operator surface ships as its own product.

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
- construct `ModelWorkspace`, select the entry by `header.id`, and prepare it in the browser;
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

| Set | Work items | Tier | Depends on | Status | Exit gate |
|---|---|---|---|---|---|
| R0 | Establish current facts, dmtool pattern, and GitHub-only topology | BEST | — | done | This proposal records the evidence and fixed hosting boundary |
| R1 | Ratify repository name, package coordinates, independent version train, first API slice, and first-party-only meaning of GitHub-only | BEST | R0 | pending | Owner decisions recorded here and in `RELEASE-SPEC` |
| R2 | Implement the original commonMain workspace assembler, structured preparation API, minimum public interpreter façade, and document ownership contract, TDD-first | BEST | R1 | pending | Java-reader/kernel expansion parity plus JVM/Node laws green; public surface contains no implementation leaks |
| R3 | Add production Maven/npm packaging, generated `.d.ts`, Kotlin/TS ABI locks, package allowlist, source/docs artifacts, and delivery-footprint measurement | BEST | R2 | pending | Fresh local Maven and packed-tarball consumers compile and run |
| R4 | Add the browser target, portable browser corpus, workspace-preparation coverage, host-sensitive locks, and browser first-answer evidence | BEST | R2 | pending | Real-browser evaluation/preparation parity and owner-ratified performance bar |
| R5 | Build the static showcase as a packed-package workspace consumer and add browser E2E verification | BEST | R3, R4 | pending | Include/typedef plus clean/firing/formal-invalid/computation scenarios pass in a browser |
| R6 | Write the canonical README/API guide, add tested snippets, and generate Dokka/TypeScript/`llms.txt` references | REGULAR | R2, R3 | pending | Docs describe only shipped API; links and examples are verified |
| R7 | Create the public mirror assembler, build/deploy workflows, cumulative Pages/Maven layout, and staged publisher | BEST | R1, R3, R5, R6 | pending | A dry-run release in a temporary/fresh mirror proves every refusal and success path |
| R8 | Publish `v0.1.0-rc.1`, consume it anonymously from GitHub Release and Pages, and run the full release review gate | BEST | R7 | pending | No in-repo resolution; all public URLs and checksums pass |
| R9 | Fix RC findings by class, publish stable `v0.1.0`, resolve IG68/IG85/IG86, update durable docs, and run the release retro | BEST | R8 | pending | Stable release live; Pages/showcase/API docs live; ledgers clean |

Each set lands as a verified conventional commit and updates this table in the same commit. No set may publish or deploy while another session is active in the worktree.

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
- ABI/package-content/delivery-footprint locks;
- anonymous non-repo consumers and immutable staged release flow.

### Optional after `v0.1.0`

- direct `@a12/operators` product;
- Zod adapters;
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
- changing the kernel consistency/MVK oracle or dmtool’s release channel.

## 13. Decisions still needed before R2

1. Confirm the recommended repository/name/version identities in §3.1.
2. Confirm that “GitHub-only” applies to first-party artifacts, while public third-party dependencies continue to resolve from Maven Central/npm.
3. Confirm the §5.3 recommendation: publish the independently consumable interpreter core first and do not make the full direct Operator API a `v0.1.0` blocker.
4. Confirm the §5.2 workspace shape: host adapters preload source text; common preparation remains deterministic and synchronous, indexed by `header.id`, with duplicate ids rejected.

These decisions do not block the plan; they block public API implementation and irreversible repository/package naming.
