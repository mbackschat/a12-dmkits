# a12-consistency

A standalone, Kernel-free consistency checker for A12 **DocumentModels** — the rules and computations, checked without a server, a Kernel or a licence. Kotlin Multiplatform: one implementation for the JVM, Node and the browser.

**Read this first: there are two questions here, and only one of them has a pass.** *"Would A12 accept this model?"* is a claim about every rule A12 has, and this checker is not feature-complete, so it answers `INVALID` or `INCONCLUSIVE` and never `VALID`. *"Did everything this checker implements pass?"* is a question about a finite, known list — so it does have a pass, published as `outcome: 'ALL_CHECKS_PASSED'`. The two are not the same green light, and `complete: false` is what keeps them apart.

<p align="center">
  <a href="../assets/infographics/a12-consistency-checker.avif">
    <img src="../assets/infographics/a12-consistency-checker.avif" alt="A12 Consistency Checker application inputs, checking operations, reports, snapshot reuse, and assurance boundaries" width="720">
  </a>
</p>
<p align="center"><sub>Click the infographic to open the full-resolution image.</sub></p>

**[Try the browser showcase](https://mbackschat.github.io/a12-consistency-releases/showcase/)** · [Public API guide](https://mbackschat.github.io/a12-consistency-releases/guide.html) · [Showcase development](showcase/README.md)

## Getting it

The Maven distribution uses the dedicated [checker release repository](https://github.com/mbackschat/a12-consistency-releases) and its GitHub Pages Maven repository. Add that repository alongside Maven Central:

```kotlin
repositories {
    maven("https://mbackschat.github.io/a12-consistency-releases/maven/")
    mavenCentral()
}
dependencies {
    implementation("io.github.mbackschat.a12.dm:dm-consistency:0.14.0")
}
```

Gradle uses the root coordinate's Kotlin Multiplatform metadata to select JVM or JS. Java/Maven consumers use `io.github.mbackschat.a12.dm:dm-consistency-jvm:0.14.0` with the same repository URL. These are two entry points to the same implementation; `dm-predicates` and its platform variants are published as the transitive dependency closure. JVM consumers need Java 21 or newer. The [standalone consumer projects](consumer-tests/) exercise both Gradle platform selection and direct Java/Maven use.

**npm registry publication remains deferred.** Build and install the npm-compatible tarball locally:

```sh
./build.sh :consistency:packConsistencyNpmPackage
pnpm add /path/to/a12-consistency-0.14.0.tgz
```

`:consistency:verifyCheckerArtifactIdentity` checks that the staged npm version, Maven version and compiled `CHECKER_VERSION` agree. The npm package declares no runtime dependencies. Maven consumers resolve the Kotlin runtime and the checker dependencies declared in the POMs.

## Preparing Maven publication

`./build.sh :consistency:verifyCheckerMavenPublication` creates a fresh Maven repository under `build/consistency-release/maven` with the checker and predicate dependency's root, JVM, and JS variants. The [publication guard](../scripts/typescript/checker-maven.ts) verifies the exact coordinate/version inventory, references, checksums, license metadata, generated identity source, and archive contents. It rejects local paths and unrelated archive packages; both JS libraries compile with relative debug paths. CI runs this artifact gate independently of the existing checker behavior tests.

The [Gradle consumer](consumer-tests/maven-gradle) resolves the root `dm-consistency` coordinate on JVM and JS; the [Maven consumer](consumer-tests/maven-java) resolves `dm-consistency-jvm` directly. Run them with `scripts/run-typescript.sh "checker Maven consumers" scripts/typescript/checker-consumers.ts VERSION file:///absolute/path/to/build/consistency-release/maven local`. The [runner](../scripts/typescript/checker-consumers.ts) uses fresh dependency caches and no `mavenLocal()` or project substitution, reuses only the Gradle wrapper distribution, and checks the committed storefront and broken-reference fixtures. The source build's resolved wrapper, JDK 21, Node, Python 3, and network access to public dependency repositories are required. Apache Maven 3.9.9 is downloaded under `build/` and checked against its published SHA-512.

## Using it

**Strings in, strings out, on every call.** The checker's Kotlin types are still moving, so exporting *them* would publish an ABI that changes under every consumer; a JSON string commits to the wire shape and to nothing else. **No call throws** — an unreadable source comes back as a report whose `diagnostics` explain the failure, so a caller wrapping it in `try`/`catch` will never see that branch.

```ts
import { checkModelJson, checkEditJson, checkDraftJson, impactOfJson, snapshotOfJson } from 'a12-consistency';

const report = JSON.parse(checkModelJson(modelJson));
report.status;          // 'INVALID' | 'INCONCLUSIVE' — A12's verdict; never 'VALID'
report.outcome;         // 'REJECTED' | 'ALL_CHECKS_PASSED' | 'NO_REJECTION' | 'NOT_ESTABLISHED'
report.complete;        // always false today — see `gaps`
report.findings;        // A12's verdict per declaration, with its code, span and corrective
report.checksCompleted; // which check families actually ran
report.gaps;            // what nobody examined
```

| call | question it answers |
|---|---|
| `checkModelJson(modelJson, label?)` | is this saved model consistent? |
| `checkEditJson(baselineJson, editedJson, label?)` | judged by what this edit **introduced**, not by the state it lands in — the one to gate a save on |
| `checkDraftJson(modelJson, at, member, text, label?)` | is this text, which the editor has not saved, consistent against the model it is typed into? |
| `impactOfJson(baselineJson, editedJson, label?)` | which declarations does this change **reach** — a blast radius, not a verdict |
| `snapshotOfJson(modelJsons, entryModelId)` | a handle that holds one analysis across many questions — the editor path, where a keystroke costs one check rather than a full re-admission |

From Kotlin or Java the same check is `check(ModelSource(json))`, returning the typed `ConsistencyReport`; `report.toJson()` produces the identical wire shape. A handle from `snapshotOf` answers `draftJson`, `reuseJson` and `withSource`/`withoutSource` for an editor that adds or drops a model mid-session.

**`ALL_CHECKS_PASSED` means every check this build implements ran and none rejected the model** — a scoped pass, and the scope is the point. It requires the full family denominator to have completed *and* no diagnostic to have been raised, so a model with one declaration the checker could not read gets `NO_REJECTION` instead: families completed, but something went unread. It is not a claim that A12 would accept the model, and `complete: false` still rides along on it. Use it as "this build has nothing to say against your model", never as "your model is valid".

## What it checks

**Its check families** (`CheckFamily`) are each a named group of A12 acceptance constraints, and every report names the ones that ran in `checksCompleted`. They cover: references (that a rule's error target, a computation's computed field and every entity named in a condition resolve); declaration shape (reserved name prefixes, error messages, the `header.locales` those messages answer to); element naming, uniqueness and the reserved segments A12 refuses; field and type-definition binding (string parameters, number digits, enum categories and S-values, pattern admission, temporal config); group structure (repetition counts, ordering carriers, repetition keys and their containment); condition syntax and grammar (lexicality, completeness, connective agreement); the operand rules of individual operators (arithmetic gates, comparison agreement across kinds, date construction slots, filter bodies, iteration scope, aggregate operands); computations (alternatives, preconditions, rounding, cycles, self-reads); and error-message text (parameter syntax and references, semantic index keys, category suffixes, forbidden characters).

**Every report also says what it did NOT check.** `gaps` names what nobody examined, and `complete` is `false` on every report today — published anyway, so a consumer switching on it keeps working when it starts carrying information. A consumer that shows a user "no problems found" without surfacing the gaps is reporting a verdict the checker did not give.

The full contract — the report's members, each call's arguments, the JVM entry points, and the two JVM façades (`PortableConsistencyGate` for a dmkits caller, `ConsistencyPreflight` for an interpreter consumer) — is [`docs/CONSISTENCY-API.md`](../docs/CONSISTENCY-API.md). Every `../` link here points into the source repository this package is built from; the npm tarball ships the README, the licence and the module itself, and nothing else.

## Worked consumers

Both live beside the module, install the packed tarball, and import it by package name — the only arrangement that exercises the resolution a real consumer performs.

- [`consumer-tests/node`](consumer-tests/node) — a plain Node project: the checks, an after-edit gate, the cold-start and retained-memory probes, and a **CI gate that cannot manufacture a pass** (`ci-gate.mjs`, with `ci-gate.test.mjs` proving it).
- [`consumer-tests/browser`](consumer-tests/browser) — the same shared cases bundled and replayed in a real Chrome, plus the cold and prepared timing pages.

Both replay [`consumer-tests/shared`](consumer-tests/shared), so a divergence between the two runtimes is a test failure rather than a surprise.

## Build & test

From the repository root, always through [`../build.sh`](../build.sh) (it pins an isolated `GRADLE_USER_HOME` and JDK 21).

```sh
./build.sh :consistency:check              # both targets (jvmTest + jsNodeTest) + the purity and dependency lints
./build.sh :consistency:nodeConsumerTest   # packs the npm tarball, installs it into a throwaway project (needs node + npm)
./build.sh :consistency:browserConsumerTest  # the same cases in a real Chrome (needs pnpm + a browser)
```

`:consistency:check` runs the correctness suites; the two consumer lanes are off `check` on purpose, because they need tooling a JVM lane should not require. A Kotlin Multiplatform module registers no plain `test` task, so `check` — not `test` — is the lane that reaches this module at all.

Two release bars ride the build rather than a reviewer's memory: `verifyConsistencyDeliveryCeiling` refuses an npm tarball over 1 MiB, and `verifyConsistencyShippedFiles` closes the denominator on what the package contains, so a file nobody decided to ship cannot arrive unremarked.

## Independence

The checker depends on **neither the A12 Kernel nor the interpreter** — `verifyConsistencyPurity` reads its compiled edges and `verifyConsistencyDependencies` allowlists what each target may resolve, in test scope too. Its only project edge is [`:predicates`](../predicates), the dependency-free leaf that holds the A12 laws it shares with the Java kits so neither has to restate them. Kernel differentials that keep it honest live in `:adapter`, outside this module, so its own test sources stay Kernel-free.

MIT licensed, and independent: not affiliated with, endorsed by or supported by mgm technology partners, and not an A12 product.
