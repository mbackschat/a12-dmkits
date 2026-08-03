# Interpreter release specification

This document is the stable publication contract for the independently consumable A12 Interpreter. It covers the generated public release repository, package identities, immutable artifacts, GitHub Pages surfaces, and the staged publisher. The public interpreter API itself is specified in [INTERPRETER-API-SPEC](INTERPRETER-API-SPEC.md); open release work is tracked only in [INTERPRETER-RELEASE-GAPS](INTERPRETER-RELEASE-GAPS.md).

## 1. Product and repository boundary

The interpreter is a kernel-free Kotlin Multiplatform library distributed from the public [`mbackschat/a12-interpreter-releases`](https://github.com/mbackschat/a12-interpreter-releases) repository. Its source repository may remain private; anonymous consumers need only the public release mirror and public third-party dependency repositories.

The public mirror is generated output. Changes originate in the source repository and pass the release assembler and guards before reaching the mirror. Public commits use the publisher-owned identity `Martin Backschat <mbackschat@mail.de>` independently of the release machine's global Git configuration.

The mirror is an independent project, not an official A12 or mgm artifact.

## 2. Release identity

The interpreter uses the repository root version. A separate interpreter version source is forbidden because it would let the Maven, TypeScript, dmtool, source-tag, and release-tag identities drift.

For version `<version>`:

| Identity | Value |
|---|---|
| Source tag | `interpreter-v<version>` |
| Public release tag | `v<version>` |
| TypeScript package | `@mbackschat/a12-interpreter` |
| Maven coordinate | `io.github.mbackschat.a12.dm:dm-interpreter:<version>` |
| API directory | `api/v<version>/` |

The `@a12` npm scope is excluded because it would imply an official A12 package.

## 3. Public artifacts

Every GitHub Release contains exactly these payloads:

| Artifact | Contract |
|---|---|
| `a12-interpreter-<version>.tgz` | npm-compatible ESM package containing the authored TypeScript façade, compiled Kotlin/JS runtime, declarations, browser-safe source maps, README, and MIT license |
| `dm-interpreter-<version>-maven-repository.zip` | Offline Maven repository containing root, JVM, and JS/KMP artifacts, POM and Gradle metadata, documentation JARs, source JARs, and checksums |
| `dm-interpreter-<version>-api-docs.zip` | Versioned developer guide, TypeScript reference, Kotlin reference, package README, and `llms.txt` |
| `dm-interpreter-<version>-showcase.zip` | Deployable static browser showcase with built-in workspaces, Documents, conformance data, and bundle metadata |
| `release-manifest.json` | Machine-readable schema version, artifact version, release/source identity, package coordinates, supported runtimes, Kernel-bytecode declaration, artifact sizes, and SHA-256 digests |
| `SHA256SUMS` | SHA-256 rows for the four binary/documentation payloads and `release-manifest.json` |

Release assets are immutable. A defect in artifact content is corrected by a later project version, never by replacing bytes under an existing name or tag.

## 4. Source and license policy

The portable interpreter core, TypeScript façade, and showcase are MIT-licensed. The release contains no A12 Kernel bytecode and has no Kernel runtime dependency.

Interpreter production sources ship with the library:

- Maven publications include source JARs for the published Kotlin artifacts.
- The TypeScript package includes browser-safe source maps with embedded source inputs for debugging.
- Source artifacts contain interpreter production sources, not the full private repository, test estate, adapter oracle, or Kernel implementation.

Shipping sources is the current publication contract. MIT, Maven, and npm do not require it, but changing this developer-experience policy is an explicit later release decision rather than an in-place artifact edit.

The separately licensed Kernel drop-in is not part of the MIT core; its release gap is [IRG1](INTERPRETER-RELEASE-GAPS.md#irg1).

## 5. Consumption

### 5.1 TypeScript and JavaScript

The npm-compatible tarball is installed directly from its public GitHub Release:

```sh
pnpm add https://github.com/mbackschat/a12-interpreter-releases/releases/download/v<version>/a12-interpreter-<version>.tgz
```

The package is ESM and supports Node.js and browser bundlers through its reviewed TypeScript façade. npmjs.org and GitHub Packages are not first-party publication channels.

### 5.2 Kotlin and Java

The first-party Maven repository is served by GitHub Pages:

```kotlin
repositories {
    maven("https://mbackschat.github.io/a12-interpreter-releases/maven")
    mavenCentral()
}

dependencies {
    implementation("io.github.mbackschat.a12.dm:dm-interpreter:<version>")
}
```

Only first-party interpreter artifacts resolve from the Pages repository. Public Kotlin and kotlinx dependencies continue to resolve from Maven Central.

The `/maven/` base is both a valid Gradle repository prefix and a human-readable landing page. Actual Maven resolution uses the conventional coordinate path below it.

## 6. Public mirror layout

```text
README.md
CHANGELOG.md
LICENSE
DISCLAIMER.md
release-manifest.json
PUBLIC-FILES.sha256
.github/workflows/
  build-release.yml
  deploy-pages.yml
site/
  index.html
  privacy.html
  showcase/
  api/
    latest/
    v<version>/
  maven/
    index.html
    io/github/mbackschat/a12/dm/...
```

The mirror has two state classes:

| Class | Paths | Rule |
|---|---|---|
| Immutable release content | GitHub Release assets, `site/api/v<version>/`, versioned Maven coordinate directories, public release tag | Never changed or republished |
| Mutable current presentation | Root README, `site/index.html`, `site/maven/index.html`, `site/api/latest/`, showcase, privacy notice, workflows, Maven metadata | May advance only through the guarded source process |

The cumulative assembler preserves all historical Maven and versioned API directories, refuses an already published target version, and updates only the mutable class. Assembly occurs in a temporary staging tree; a refusal leaves the previous verified mirror unchanged. Before writing its checksum-bound qualification receipt, the local publisher runs [`../scripts/update-code-statistics.sh`](../scripts/update-code-statistics.sh) with the maintainer's Tokei to compare fresh counts for the package and public-mirror READMEs. Release assembly and the public rebuild consume the immutable source tag and do not acquire the local-only tool.

`PUBLIC-FILES.sha256` inventories every generated public file except itself. Before assembly or refresh, the publisher verifies both its exact path set and every digest. A structural allowlist and public-tree scan reject unexpected files, secrets, private repository material, user paths, and machine-local paths.

## 7. Documentation and browser surfaces

The Pages root links the browser showcase, rendered developer guide, TypeScript API, Kotlin API, Maven repository, release repository, and privacy notice.

The browser showcase is a real consumer of the packed TypeScript artifact. It supports built-in and locally selected workspaces and Documents, include/typedef resolution, full or partial validation, computation, provider failures, integrity and cancellation diagnostics, repeated operations over one prepared model/interpreter, and opt-in offline Cache Storage.

A built-in scenario **demonstrates a repeated computed field**, not only repeatability-1 targets: the permit model charges every site-section row its own survey fee, so `compute` returns several results carrying the same `pointer.path`. Beside each raw `pointer` object the page renders the joined canonical A12 pointer from the published `A12PointerFormat`, because coordinates shown as a separate array do not identify a row to a reader. Demonstrating a capability the corpus already locks is a distinct obligation from having it: a walkthrough that runs is not a walkthrough that shows the thing.

The showcase displays the package version and exact A12 Kernel/A12 Tools semantics target from release metadata generated from the repository's version and semantics pins. The Chrome showcase gate asserts the rendered values, and the Pages showcase tree for a release must match that release's immutable `dm-interpreter-<version>-showcase.zip` asset. `verifyInterpreterBrowserSizeDocumentation` derives the rounded gzip claim from the built showcase's `bundle-size.json` and requires every public README surface—the source root, package README, and release-mirror README—to carry it, so a bundle change cannot leave the advertised footprint stale.

Selected user files remain local to the browser. The static site loads no remote font, analytics, advertising, or social embed, sets no cookie, and performs no explicit client-storage write before the user opts into offline use. The site-specific privacy notice names the actual operator and GitHub Pages host.

The versioned API archive contains the canonical README and tested guide plus generated references derived from the shipped Maven/npm contracts. Displayed TypeScript, Kotlin, and Java examples are locked to consumer programs that compile and execute against the packed or staged artifacts.

## 8. Qualification and package gates

Before qualification, staging, or promotion, [`check-release-gaps.sh`](../scripts/check-release-gaps.sh) derives every live `docs/*-GAPS.md` ledger and refuses publication unless every Open section contains only `_None open._`. Deferred, declined, divergence, and out-of-scope sections do not block.

Before staging a release, the source checkout must pass:

- JVM and Node interpreter tests;
- Kernel-backed adapter differentials required by changed semantics;
- reviewed Kotlin/JVM, Kotlin/KLIB, and TypeScript ABI locks — all three **member-level**, so a removed function, a changed signature, or a dropped property fails the build on every published surface. Kotlin's `byNames` filter applies at different declaration depths on JVM and KLIB, so the configured dump is not its own completeness oracle. `InterpreterAbiGoldenDepthTest` asks the same Kotlin ABI engine for an unfiltered dump of the assembled JVM JAR and JS KLIB, then projects the contract mechanically by exact root-package identity: whole JVM class blocks and every KLIB declaration-identity line. It models no member kinds, so nested, accessor, enum-generated, synthetic, and future declarations enter the denominator automatically. The configured golden must equal that projection after the explicit non-contract root-name judgment; exact package identity excludes every present and future engine subpackage. The one owner-wide JVM file-facade exclusion has its complete unfiltered block separately reviewed, so a new declaration cannot inherit that exclusion silently. Kind-unknown synthetic controls prove both projections can fail, and no hand-set count floor stands in for completeness;
- exact npm and Maven file/dependency allowlists;
- purity, source-map, secret, path, and license checks;
- standalone Kotlin/JVM, Kotlin/JS, TypeScript/Node, and packed-browser consumers;
- Chrome conformance, showcase, offline, and performance gates;
- generated API-reference, documentation-link, archive-layout, and reproducibility guards;
- deterministic public-mirror assembly and disposable publisher rehearsal.

Qualification writes a receipt containing the exact source commit, source tag, root version, public inventory digest, and release payload checksums. Staging consumes those unchanged outputs rather than rebuilding or trusting task status.

## 9. Publication protocol

The publisher follows this state machine:

1. Require all live gap ledgers to have drained Open sections, then require a clean source checkout whose local and origin source tag resolve to the current commit and whose root version matches both tags.
2. Verify the qualification receipt and exact output checksums.
3. Clone and verify the existing public mirror, including every historical commit's author and committer identity.
4. Push only the public workflow bootstrap required by a new mirror.
5. Create or reuse a draft GitHub Release.
6. Dispatch the public build with the exact source tag, source commit, artifact version, and release tag.
7. In the public workflow, check out private source and public mirror into separate directories, rerun the complete release gate, assemble into a validated staging tree, and verify the draft boundary.
8. Upload immutable draft assets before atomically pushing the generated mirror main branch and release tag.
9. Set and verify the GitHub Release `targetCommitish` against the generated mirror commit without moving the public release tag.
10. Enable GitHub Pages in workflow mode when the repository has no Pages site, then deploy the reviewed draft site.
11. Reacquire and verify the tagged mirror, draft assets, public history, Pages routes, API trees, and Maven POM.
12. Publish the release as non-latest.
13. Reacquire all assets anonymously, verify checksums, and execute fresh isolated Maven and TypeScript consumers against public URLs.
14. Mark a stable release latest only after both anonymous consumers pass. Prereleases remain non-latest.

GitHub does not expose draft assets anonymously, so the non-latest public interval between steps 12 and 14 is unavoidable. The process is resumable from that state; a failed anonymous check never promotes the release to latest.

## 10. Published Pages refresh

A presentation-only correction may refresh mutable Pages content after publication without creating a new artifact version. It requires an explicit `allow_published_refresh` workflow input and a published release tag.

Before deployment, the workflow fetches that immutable tag and requires no Git diff in:

- every `site/api/v<version>/` directory;
- the complete Maven coordinate tree under `site/maven/io/`.

The release assets, public release tag, versioned API content, and Maven artifacts remain unchanged. A change to any of those belongs in a later version.

## 11. External dependencies and hosting

GitHub Releases and GitHub Pages are the first-party host. Public third-party dependencies continue to resolve from their normal upstream registries. The project does not mirror Maven Central or npm dependencies and does not use the private source repository as a consumer resolution fallback.

The interpreter mirror reuses the dmtool release protocol, identity boundary, checksum format, generated-mirror discipline, draft/go-live split, Pages deployment, and anonymous verification. Interpreter-specific machinery is permitted only where its cumulative Maven/API and multi-consumer artifact shapes differ materially from dmtool's latest-only binary mirror.

## 12. Open release work

Only open or explicitly trigger-gated release work belongs in [INTERPRETER-RELEASE-GAPS](INTERPRETER-RELEASE-GAPS.md). The completed implementation program and its measured corrections remain available in the [archived release proposal](archived/INTERPRETER-RELEASE-PROPOSAL.md).
