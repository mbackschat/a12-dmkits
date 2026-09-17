# a12-dmkits — architecture

The **structural** view of the system as it is **today**: the principal components, how the packages layer and depend on each other, where the kernel coupling sits, and how the CLI and its test harnesses are built. This is the *where the code lives and how it fits* doc.

- For the **why** — the design arguments, the rejected alternatives, the decision log — read [`PROJECT-DESIGN.md`](PROJECT-DESIGN.md) (the conceptual component view is §15; the phasing/roadmap is §16). The numbered API design findings live in [`API-FINDINGS.md`](API-FINDINGS.md).
- For the **CLI contract** (verbs, the result envelope, the self-describing surface) read [`CLI-SPEC.md`](CLI-SPEC.md) / [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md); for the **native build** read [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md); for **kernel facts** [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) / [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md).

> **This doc describes the present, not the plan.** It carries no roadmap, no "proposed/TBD" parts, and no per-feature status. That is deliberate — see [§7](#7-keeping-this-doc-alive). Future/▸planned structure belongs in PROJECT-DESIGN §16 or the ledgers, never here.

---

## 1. The big picture

dmkits is **one library with a shared core and two sibling kits**, an **opt-in kernel adapter** that reaches the kernel's internal read APIs, and a **CLI front-end** (`dmtool`) for the agent/BA — which the same binary also **projects over MCP** (§5f), for a caller that cannot execute it. Everything sits on the **A12 kernel**, and the whole stack is built so the core never touches anything but the kernel's *public* surface.

```
                    ┌──────────────────────────── front-ends ─────────────────────────────┐
  agent / BA  ────▶ │  dmtool CLI  (:cli)              developer's own project              │
  developer   ────▶ │  picocli verbs · profiled JSON/text I/O (rulekit/modelkit library)    │
  MCP host    ────▶ │  dmtool mcp — the same verbs over stdio or a bound port (§5f)         │
                    └───────────────┬──────────────────────────────────┬───────────────────┘
                                    │ implementation                    │ depends on (library)
                    ┌───────────────┴──────────────── the library  (root project ` : `) ─────┐
                    │                                                                         │
                    │   dm.edit   EditSession — orchestrates ONE DM-JSON through BOTH kits,   │
                    │             validates once (the only layer that imports both kits)      │
                    │                  ┌──────────────────┐      ┌──────────────────┐         │
                    │   sibling kits   │   dm.modelkit    │  ⊥   │   dm.rulekit     │         │
                    │   (neither       │  structure:      │      │  conditions &    │         │
                    │    imports the   │  fields · groups │      │  computations:   │         │
                    │    other —       │  includes · type-│      │  typed DSL,      │         │
                    │    SiblingBoundary│ defs · doc config│      │  render+validate │         │
                    │    Test)         └────────┬─────────┘      └────────┬─────────┘         │
                    │                           └───────────┬─────────────┘                   │
                    │                          dm.core — the shared substrate                 │
                    │            ModelIndex · ModelLoader · DmKeys/ElementType · ModelResolver │
                    │            ModelConsistencyException · ValueKind · Backends (SPI)        │
                    └────────────────────────────────┬────────────────────────────────────────┘
                            SPI ports (ServiceLoader) │                ▲  internal-read, guarded (N8)
                             — no kernel dep in main   │                │  implements the SPI ports
                    ┌──────────────────────────────────────┐   ┌──────┴───────────────────────┐
                    │  A12 kernel  (published to mavenLocal) │◀──│  :adapter                    │
                    │  public:   kernel-md-facade            │   │  a12internal READ tier       │
                    │            (expand/check/serialize)    │   │  (parsetree · tool · md-model)│
                    │  internal: parsetree-api · tool-api ·  │   │  + runtime-eval tier         │
                    │            md-model · runtime           │   │  KernelAdapter (converts AST,│
                    └──────────────────────────────────────┘   │   whereUsed, rewrite, eval)   │
                                                                 └──────────────────────────────┘

  read the map: arrows = "depends on / calls". Kernel coupling WIDENS downward. The kits are KERNEL-FREE
  (no kernel dep in main — the kernel lives behind the SPI ports, resolved by ServiceLoader; verifyKitPurity);
  ONLY :adapter implements those ports + crosses into the kernel's internal tier, converting every kernel
  type back to a dmkits type at the boundary (nothing kernel escapes into a kit).
```

**The three principal layers, top to bottom:**

| Layer | Packages / units | Job |
|---|---|---|
| **front-ends** | `:cli` (`dmtool`); a developer's own project | drive the library — the CLI for an agent/BA over JSON; the library directly for an in-repo developer |
| **the library** | root project `:` — `dm.core`, `dm.rulekit`, `dm.modelkit`, `dm.edit` | author/read/edit/render/validate a document model and write DM-JSON back |
| **kernel coupling** | `:adapter` (opt-in) + the A12 kernel | the kernel is the validity oracle; the adapter is the *only* code that reaches its internal read APIs |

**The load-bearing invariants** (each enforced by a test, not just convention):

- **Kernel-free kits, kernel behind the SPI (D15/N8; kernel-decoupling stage 3).** The kits (`dm.core` / `dm.rulekit` / `dm.modelkit` / `dm.edit`) import **zero** `com.mgmtp` — the root project has **no** kernel dependency in its main compile classpath. The kernel lives behind three narrow SPI ports (`dm.core.spi.ConsistencyOracle`, `dm.core.spi.BackendInfo`, `dm.rulekit.spi.ConditionReadBack`), resolved by `dm.core.Backends` / `dm.rulekit.spi.ReadBackends` via `ServiceLoader`. `:adapter` implements + registers all three; `ConditionReadBack` has a **second, kernel-free provider** in `:interpreter-bridge`, so a consumer who wants read-back without the kernel has one (ruling 6). `verifyKitPurity` locks the linkage invariant by failing on any compiled dependency edge from a kit main class to `com.mgmtp.*`; it also requires a non-empty class denominator and a compiled positive control. The kernel stays the oracle in TESTS (test-scope `:adapter` provider). This is what unblocks the Maven-Central publish and keeps the kits polyglot-portable.
- **Sibling kits (`dm.modelkit ⊥ dm.rulekit`).** Structure editing and rule authoring are peers over `dm.core`; neither imports the other. Since the Stage-4 module cut this is a **build-graph fact** — `:modelkit` and `:rulekit` declare no dependency on each other, so a sideways import is unrepresentable-illegal (the strongest form). The old import-sweep `SiblingBoundaryTest` graduated (retired) when the modules landed.
- **Data out, never kernel objects (§5e).** Every artifact that crosses a boundary is plain data (a `String`, a record, JSON) — never a live kernel object. That is what keeps the out-surface agent-consumable and the core portable.

---

## 2. Modules & dependencies

Fourteen Gradle units (the Stage-4 per-kit module cut, [KERNEL-DECOUPLING-PROPOSAL.md](KERNEL-DECOUPLING-PROPOSAL.md) §3a). The kits are four published, kernel-free modules — `core` (`dm.core`), `rulekit` (`dm.rulekit.*` + the operator catalog), `modelkit` (`dm.modelkit`), `edit` (`dm.edit`, the cross-kit top layer) — under `:adapter` (the kernel backend), `:cli` (`dmtool`), `:schemakit`, `:interpreter`, `:interpreter-bridge` (the kernel-free `ConditionReadBack` provider), `:consistency` (the portable checker) and `:predicates` (the shared-predicate leaf, the one module BOTH the Java kits and the multiplatform checker depend on). **Layering: `core ← {rulekit, modelkit} ← edit`** (jdeps-verified acyclic; the sibling boundary `modelkit ⊥ rulekit` is now a build-graph fact). **Root `:` is a pure aggregator + the cross-kit integration-test host** — it has no main source; its `src/test` (unmoved) holds the ~150 cross-cutting tests, which span all four kits + share `Fixtures`/`ModelDrafts`, so they run at root with `testImplementation` on the kits + the `:adapter` oracle. **Packages were never renamed — only files moved** — so every FQCN test filter, the SEMANTICS-MAP simple-name cites, and reflection metadata stay stable. (History: modelkit was folded into root 2026-06-09, then re-split into modules here — PROJECT-DESIGN §21H.)

| Unit | Gradle id | Depends on | Kernel coupling | Ships as |
|---|---|---|---|---|
| **core** | `:core` | `implementation` Jackson (databind) + `implementation project(':predicates')` | **none** (kernel-free; `verifyKitPurity`) | `dm.core` — the DM-JSON reader/index/resolver + diagnostic vocabulary + the SPI ports (`dm.core.spi.*`) · published `dmkits-core` (MIT) |
| **rulekit** | `:rulekit` | `api project(':core')` + `implementation project(':predicates')` + `implementation` Jackson | **none** | `dm.rulekit.*` (one SCC) + the operator catalog resource + the R4 read seam (`dm.rulekit.spi.ConditionReadBack`) · published `dmkits-rulekit` (MIT) |
| **modelkit** | `:modelkit` | `api project(':core')` + `implementation` Jackson (databind + yaml) | **none** | `dm.modelkit` structure builder (sibling of rulekit; no edge either way) · published `dmkits-modelkit` (MIT) |
| **edit** | `:edit` | `api project(':core')` + `project(':modelkit')` + `project(':rulekit')` | **none** | `dm.edit` — the cross-kit editing top layer · published `dmkits-edit` (MIT) |
| **(aggregator + test host)** | root `:` | `testImplementation` the four kits + `:schemakit`, `:consistency-gate` and `:interpreter-preflight` (joined for the public-API lock, which loads their classes to dump them) + `project(':adapter')` + `kernel-md-facade` + Jackson | **none in main** (no main source); the kernel-oracle TESTS get the backend at test scope | no artifact of its own — the `dmkits` aggregator POM + the ~150 cross-kit integration tests + the five-kit public-API lock (`src/test`) |
| **adapter** | `:adapter` | `api project(':core')`/`(':rulekit')`/`(':modelkit')`/`(':edit')` + `implementation kernel-md-facade` (the public tier the kits no longer carry) + the kernel **internal-read** jars + the **runtime-eval** jars + `implementation project(':interpreter')` | the kernel backend — the `a12internal` read tier + runtime evaluation + **the SPI-port impls**, all **confined to this module** | an opt-in read/convert/rewrite/evaluate surface (`KernelAdapter`) + the string-only `ModelProfiles.extract`/`compare` performance-calibration boundary + **the three SPI-port providers** (`KernelConsistencyOracle`/`KernelBackendInfo`/`KernelConditionReadBack`, registered via `META-INF/services`) + `KernelCustomConditions` |
| **interpreter** | `:interpreter` (Kotlin Multiplatform: jvm + js) | **one** common lib — kotlinx-serialization-json (tree API, for the DM-JSON loader; whitelisted in the purity lint); no kernel, no dmtool — purity-lint-enforced | **none** — kernel-free by construction | a native-safe, cross-platform runtime evaluator (`DmInterpreter`: `validateFull`/`validatePart`/`testRule`/`compute`) + a DM-JSON → `ModelDef` loader (`ModelLoader`); the publishable standalone artifact (design [INTERPRETER-SPEC.md](INTERPRETER-SPEC.md) · impl [INTERPRETER-ARCHITECTURE.md](INTERPRETER-ARCHITECTURE.md)) |
| **interpreter-bridge** | `:interpreter-bridge` (Kotlin/JVM) | `api project(':rulekit')` + `implementation project(':interpreter')` + Jackson | **none** — kernel-free in main AND test scope (`verifyInterpreterBridgePurity`) | the second `ConditionReadBack` provider (`InterpreterConditionReadBack`, registered via `META-INF/services`): the interpreter's AST mapped onto rulekit's typed `Condition`, so a published consumer gets condition read-back with no kernel present (ruling 6) |
| **cli** | `:cli` (`application` + GraalVM `native` plugins) | `implementation project(':core')`/`(':rulekit')`/`(':modelkit')`/`(':edit')` + `project(':adapter')` + `project(':interpreter')` + `project(':schemakit')` + `project(':consistency')` + picocli + Jackson | inherits the adapter's (bundled *inside* the binary, never a library surface) | the standalone `dmtool` front-end, including the top-level `profile extract`/`synthesize`/`compare` performance-calibration target (JVM `installDist`; opt-in native image) |
| **schemakit** | `:schemakit` (JVM/Java; package `…dm.schemakit`) | `implementation project(':core')`/`(':modelkit')`/`(':rulekit')` + `project(':adapter')` + Jackson | reaches the kernel only through the project's own **mediated** modules (the kits, `:adapter`'s N8 type-converting boundary, the kernel-free `:interpreter`) — never the raw kernel jars directly (`verifyPublishedClasspathsAreKernelFree`, over what the published POM resolves); its own compiled classes link no kernel type (`verifySchemakitPurity`) | JSON Schema ⇄ Document Model transcoder ([SCHEMAKIT-SPEC.md](SCHEMAKIT-SPEC.md)) · published `dmkits-schemakit` (MIT) |
| **consistency** | `:consistency` (Kotlin Multiplatform, jvm + js; package `…dm.consistency`) | `kotlinx-serialization-json` + `api project(':predicates')` — the only project edge it has | **none**, and none on `:interpreter` either. `:cli` consumes it (2026-09-16) for `model check --engine=portable`, which is a one-way edge: the checker gains no dependency and keeps its own closure, so the Kernel-free claim that makes it publishable is unaffected by having a Kernel-bundling consumer. The standalone Kernel-free checker for rules and computations ([PORTABLE-CONSISTENCY-PROPOSAL](PORTABLE-CONSISTENCY-PROPOSAL.md) H1 + NFR #1): sharing a syntax leaf with the evaluator would move `Dec` off the interpreter's arithmetic paths, so the checker owns its analysis syntax outright. Its own test source sets stay Kernel-free; Kernel differentials live in `:adapter` |
| **predicates** | `:predicates` (Kotlin Multiplatform, jvm + js; package `…dm.predicates`) | **nothing at all** — no project edge, no third-party library, not even kotlinx; `verifyPredicatesPurity` fails on any resolved dependency beyond the Kotlin stdlib, and since 2026-09-17 on any intra-repo project edge too, which its module filter had been dropping because a project component carries this project's own group | **none**, by having no dependency to carry one | side-effect-free predicates over a stored A12 declaration, so one law has one live statement across the Java kits and the portable checker · published `dm-predicates` (MIT). It exists because `:rulekit` (JVM/Java) and `:consistency` (multiplatform) could otherwise share a law only by restating it: the reserved-namespace law had three copies and had already drifted, and A12's pattern-admission blacklist had two that disagreed in both directions (`:core`'s `KernelPatternPolicy` against the checker's pattern family). A predicate earns a place here by having two consumers that would otherwise state the same law twice — the narrowness is the contract, since a leaf that grows dependencies becomes a second checker |
| **consistency-gate** | `:consistency-gate` (JVM/Java; package `…dm.gate`) | `api project(':rulekit')` + `api project(':consistency')` + `implementation project(':modelkit')` | **none** — kernel-free in main AND test scope (`verifyConsistencyGatePurity`), which is the point: its whole claim is a verdict reached with no kernel present | the F3 integration façade — `PortableConsistencyGate` runs the portable checker for a dmkits caller and returns the verdict as a `ValidationResult` with a `PORTABLE_CHECKED` receipt. Beside both products rather than inside either, for `:interpreter-bridge`'s reason · published `dmkits-consistency-gate` (MIT) |
| **interpreter-preflight** | `:interpreter-preflight` (Kotlin/JVM; package `…dm.preflight`) | `api project(':interpreter')` + `api project(':consistency')` | **none** — Kernel-free in main AND test scope (`verifyInterpreterPreflightPurity`) | the F3-c façade — `ConsistencyPreflight` answers whether A12 rejects a model, which is a different question from whether the interpreter can prepare it. Its own module rather than a class in `:consistency-gate`, because the two dependency sets do not overlap and each POM should say what its own consumer needs · published `dmkits-interpreter-preflight` (MIT) |

**Adding a published module: where it has to be registered.** The estate's guards fire on the finished thing rather than on the edit, so a module wired in half-way compiles and passes its own lane while failing someone else's. The list, from the three most recent additions: [`settings.gradle`](../settings.gradle) · the module's own `build.gradle` with a `verifyCompiledPurity` gate on `check` · the root `kernelFreePublications` list · [`ci.yml`](../.github/workflows/ci.yml)'s single `gradlew` line (`GateTierWiringTest` fails when a `check`-wired gate runs nowhere, and its `CHECK_WIRED_BUILD_FILES` map needs the module too) · `PurityGuardMechanismTest`'s build-file list · `PublicApiGuardTest`'s `KITS` **and** `ALLOWED_TYPE_PREFIXES` (an artifact's own package is not allowed implicitly) plus a regenerated `api/<artifact>.api` · [`scripts/mutant.sh`](../scripts/mutant.sh)'s lane inference, where a missing case falls back to a lane that compiles nothing and reports a false SURVIVED · this document and [RELEASE-SPEC §11](RELEASE-SPEC.md).

One **test-only fixture edge** crosses this production graph without changing it: `:adapter` applies `java-test-fixtures` and exposes the kernel probe's message-address projector; `:cli` consumes that fixture through `testImplementation(testFixtures(project(':adapter')))`. This lets the leaf application's policy owner and the adapter test-source publisher run an equivalence property without moving the tool-owned house fallback into interpreter production API. The fixture is absent from adapter and CLI shipping artifacts.

The runtime verbs (`model eval`/`compute`, `rule eval`) **run on the `:interpreter` engine — the sole runtime eval engine on every target** — `:cli`'s `interp.InterpreterModelLoader` loads a DM-JSON model into the kernel-free `ModelDef` and `interp.InterpreterEngine` renders the result envelope, so they need no Groovy; the opt-in **`--kernel` eval option was retired** (Set V, 2026-07-12), so there is no kernel eval opt-in and nothing to strip under the native profile. Because the engine is kernel-free, these verbs **run in the native image** — `Cli.JVM_ONLY_VERBS` is empty (no verb hidden), and all 7 showboat demos (incl. `cli-runtime.md`) pass on the native binary (`NativeImageRuntimeVerbsTest` guards that the runtime verbs are present on the native profile and that the retired `--kernel` flag is now rejected as an unknown option on every profile). The kernel-free `model seed` (generate a sample instance) is likewise native.

**Custom-construct support (G29–G31) rides the interpreter's consumer SPIs.** A model that uses a `CustomFieldType` or `CustomCondition` still runs: `--predefined-types` feeds the declarative `PredefinedType` registry; what the engine cannot evaluate is surfaced in `data.unsupported` (never a silent pass), with `--strict-custom` to fail instead. For imperative logic, `--custom-conditions-js` / `--custom-field-types-js` run the project's own `<Name>.js` through `interp.CustomJsWorker`, a single lazily spawned persistent Node subprocess (bundled `custom-js-worker.mjs`, NDJSON RPC, self-cleaning on stdin EOF + idle + shutdown hook). A field-type request forwards the exact `{ locale, minLength, maxLength, isDisplayValue }` context and accepts only the documented Boolean or `{ valid, errorKey?, errorMessage? }` result vocabulary; malformed results enter the same unsupported/strict boundary. The interpreter's call-local formal-result cache invokes that bridge at most once per relevant **cell** — not once per value, since two cells holding the same value each consult it — and shares its code/message result with emission and suppression (IF44/IF150/IF152, the cardinality kernel-oracled in [IF284](INTERPRETER-FINDINGS.md#if284)). This adds an optional `node` runtime dependency and a bundled-resource native concern (§5d).

The **`:interpreter`** is deliberately a near-leaf — its only third-party dependency is kotlinx-serialization-json (common, tree API, for the DM-JSON loader; whitelisted in the purity lint) and, crucially, **no kernel/dmtool**: it interprets the rule AST directly (no Groovy codegen), so it runs in a GraalVM native image and as a JavaScript library from one Kotlin source. The bounded portable workspace assembler is implemented, dogfooded by dmtool, and qualified against the portable corpus plus raw include/typedef workspace and the owner-ratified first-answer release bar in system Chrome (IF224/IF225). Its correctness is pinned by **kernel-differential tests that live in `:adapter`** (`…/adapter/laws/*DiffTest.kt`, consuming `:interpreter` in test scope) — keeping the module kernel-free even in test scope, which is what makes it cleanly publishable. The `verifyInterpreterPurity` lint fails the build on a forbidden import (`java.*`/`com.mgmtp.*`/`groovy.*` in `commonMain`). dmtool consumes it for the native-image runtime verbs and portable `model expand` conversion (the Groovy ceiling, §5d).

Internally `commonMain` is **layered into subpackages** under `dm.interpreter`: `.model` (the data contract consumers build/read — `ModelDef`/`FieldDef`/`RuleDef`/`Message`/`Kind`/`MsgType`/…), `.load` (the kernel-free DM-JSON → `ModelDef` loader `ModelLoader`, WI-2), `.ast` (`Cond`/`Expr`/`Val`/`Tri` + `typedValue`/`emptyValue`), `.parse` (the recursive-descent `parseCondition`/`parseExpression`), and `.eval` (the engine). The `.eval` layer is itself split into an **evaluation *strategy*** — the `Interpreter` tree-walk that resolves operands over `EvalContext`/`Document` and dispatches — and a **pure runtime-*semantics* layer** of strategy-agnostic functions over already-resolved values: the `PredicateOp`/`FunctionOp`/`ConstantOp` registries, `Kleene` (3-valued logic), `DateMath`, `ValCompare` (value order), `Aggregates` (the `Sum`/`Min`/`Max`/`NumberOfDifferentValues`/`SumOfProducts` folds), and `Polarity` (comparison fillability) — plus `FormalError`, `Pointer`, and the message renderer. That strategy/semantics split is **generator-ready** (the layering INTERPRETER-FINDINGS IF10 targets and 2026-06-27 realized): a future code-*generator* would be a second strategy emitting calls to the same runtime-semantics functions — *not* a goal of the current artifact, just a property the layering preserves. The root holds the façade (`DmInterpreter`), the `Dec` decimal `expect`/`actual`, and `UnsupportedConstructException`. Dependency direction: `model ← ast ← parse`, `model,ast ← eval`, `model ← load`, all ← root. Impl detail: [INTERPRETER-ARCHITECTURE.md](INTERPRETER-ARCHITECTURE.md).

```
            ┌──────────────────────────────────────────────────────────────┐
            │  :cli   application + native-image   (mainClass = CliMain)     │
            │         picocli command tree · ResultEnvelope · self-describe  │
            └───────────┬───────────────────────────────────┬──────────────┘
             implementation                          implementation
                        ▼                                    ▼
            ┌──────────────────────┐      api      ┌───────────────────────────┐
            │  :adapter            │──────────────▶│  the kits  (kernel-FREE)   │
            │  a12internal read +  │               │  :core ← {:rulekit,        │
            │  runtime eval +      │               │          :modelkit} ← :edit│
            │  the 3 SPI providers │               │  SPI ports · verifyKitPurity│
            └──────────┬───────────┘               └───────────────────────────┘
                       │ internal jars + kernel-md-facade
                       ▼                            ┌───────────────────────────┐
            ┌──────────────────────┐               │  :interpreter  (KMP jvm+js)│
            │  A12 kernel          │               │  kernel-free evaluator     │
            │  @ mavenLocal        │               │  (differential in :adapter)│
            │  (a12-kernel-        │               └─────────────┬─────────────┘
            │   bootstrap)         │                             │ implementation
            │                      │               ┌─────────────▼─────────────┐
            │                      │               │  :interpreter-bridge      │
            │                      │               │  AST → typed Condition:   │
            │                      │               │  the kernel-FREE          │
            │                      │               │  ConditionReadBack        │
            │                      │               └───────────────────────────┘
            └──────────────────────┘   root `:` — aggregator + cross-kit integration tests
                                       (NO main source): testImplementation the four kits (+ :schemakit,
                                       :consistency-gate and :interpreter-preflight for the API lock)
                                       + :adapter + kernel-md-facade
```

- `./gradlew :test` — the cross-kit integration suite (root `:` is the test host; the kits' main is kernel-free, the kernel enters only as a test-scope `:adapter` oracle). `:verifyKitPurity` (no kit main names a kernel type) + `:noBackendTest` (the §2c degraded lane) ride `:check`, and both now also run in CI.
- `./gradlew :interpreter:check` — the kernel-free evaluator on **both** targets (`jvmTest` + `jsNodeTest`) + the `verifyInterpreterPurity` lint.
- `./gradlew :adapter:test` — the adapter (the only suite with the internal tier) + the interpreter↔kernel differential tests.
- `./gradlew :cli:test` — the CLI (incl. the conformance matrix and the N6 gold oracle over `eval/tasks/`).
- `./gradlew :interpreter-bridge:check` — the AST→`Condition` mapping (a lane with no kernel artifact on it at all) + `verifyInterpreterBridgePurity`.
- `./gradlew :schemakit:check` — the JSON-Schema transcoder tests + `verifySchemakitPurity` (the no-raw-kernel-types lint, over compiled edges). A CI lane since 2026-09-05; before that the module appeared in none, so neither had ever run there. Kernel-accept rides the public facade (`ModelLoader`), so the structural shapes need no internal tier; `:adapter`/`:interpreter` join as those increments land.
- `./gradlew :consistency:check` — the portable checker on **both** targets (`jvmTest` + `jsNodeTest`) + `verifyConsistencyPurity`/`verifyConsistencyDependencies`. `:consistency:nodeConsumerTest` is separate and slower: it packs the npm tarball and installs it into a throwaway Node project, so it proves the published package is consumable rather than that the code is correct. `:consistency:browserConsumerTest` is the same proof in a browser — it bundles that tarball and replays the same shared cases in a real Chrome — and is off `check` too, needing pnpm and a browser.
- `./gradlew :consistency-gate:check` — the F3 façade's mapping rows + `verifyConsistencyGatePurity`. Fast, and in CI since it landed; the lint is the guard behind the module's claim rather than a formality.
- `./gradlew :interpreter-preflight:check` — the F3-c façade's outcome and independence rows + `verifyInterpreterPreflightPurity`.
- `./gradlew :predicates:check` — the shared leaf on both targets + `verifyPredicatesPurity` (no resolved dependency at all).
- `./gradlew test` — everything. Always build through [`./build.sh`](../build.sh) (isolated `GRADLE_USER_HOME` + JDK 21) so the machine's global Gradle config can't contaminate the build.

**Kernel coupling — three tiers (N8/D15).** This is the central architectural constraint:

| Tier | Who | Allowed |
|---|---|---|
| **none in main (SPI only)** | the kit modules (`:core`/`:rulekit`/`:modelkit`/`:edit` main) | **no kernel type at all** — the kernel is reached only through the SPI ports (`ConsistencyOracle`/`BackendInfo`/`ConditionReadBack`), resolved at runtime by `ServiceLoader`. `verifyKitPurity` enforces zero `com.mgmtp` across all four kits' `src/main`. |
| **public + internal read, guarded** | `:adapter` only | `kernel-md-facade` (the public tier — root no longer carries it) + the `a12internal` *read* AST (`parsetree-api` `ICondition`, `tool-api` `IRule`/`IEC`, `md-model` `DocumentModelService`) + runtime evaluation — reached in one call from the md-tier model, **converted back to dmkits types at the boundary** (and behind the SPI ports for the kits). Never constructs kernel AST (read-only). |
| **kernel-as-oracle (tests)** | any test | fixtures/oracles may read the kernel (the kit tests get it via a test-scope `:adapter` provider + `kernel-md-facade`), provided nothing kernel leaks into `src/main` or a public signature. |

---

## 3. The library

The root project is four package groups: a shared `dm.core`, the two sibling kits (`dm.rulekit`, `dm.modelkit`), and the `dm.edit` orchestration above them.

### 3.1 `dm.core` — the shared substrate

The DSL-free foundation both kits stand on. No rulekit or modelkit dependency. It **owns the vocabulary the kits' public surface speaks** — the reference-resolver type and the diagnostic types are ours, not the kernel's, so a consumer never has to import `com.mgmtp.*` to name them (kernel-decoupling, [KERNEL-DECOUPLING-PROPOSAL](KERNEL-DECOUPLING-PROPOSAL.md)). **`dm.core` is kernel-free** (stage 3): model assembly + indexing run without the kernel (`ModelReader`); the consistency gate (`ModelLoader.loadAndCheck`, R3) and the version read route through the SPI ports (`dm.core.spi.ConsistencyOracle`/`BackendInfo`), resolved by `dm.core.Backends`. With no backend the gate degrades to structural-only and carries a `Verification.STRUCTURAL_ONLY` marker (§2c) — never a fabricated verdict.

| Class | Role |
|---|---|
| `ModelReader` | the **kernel-free Java assembly/index layer** (stage 2): parse DM-JSON → **expand includes** (mount the included root group's children under the pre-existing `modelAlias` group; `includeLevel`; `excludeRules`/`excludeComputations`) → **resolve imported type-definitions to effective concrete types** → index by **name-path**. A clean-room reimplementation of the kernel's `expand` (KERNEL-FINDINGS "DocumentModel expansion"); emits self-contained, name-path-keyed DM-JSON (`toJson()`) for Java core index/metadata consumers and remains a parity comparator. dmtool's interpreter runtime and `model expand` use the portable commonMain `ModelWorkspace` assembler instead, avoiding a third resolver while exercising the published boundary. |
| `ModelIndex` | the **kernel-free typed view** over `ModelReader` — group tree, field kinds, number scale/bounds, string length/pattern, repeatability, enum domains, element annotations. The retired kernel-typed accessors (`model()`/`fieldType()`, and the kernel returns of `field()`/`group()`) are gone; parity-locked to the kernel by `adapter.ModelIndexParityTest` |
| `ModelLoader` | the **consistency gate** (R3): `loadAndCheck` routes through the `ConsistencyOracle` port (backend present → real kernel `deserialize → expand → checkConsistency`, throwing on ERROR; no backend → structural expand only, no verdict). `BoundModel`/`ModelDocument`/`EditSession` call it to gate, then index kernel-free via `ModelReader`; takes a `ModelResolver` |
| `Backends`, `spi.ConsistencyOracle`, `spi.BackendInfo` | the **SPI resolution point + the two core ports** (stage 3): `Backends` discovers the backend via `ServiceLoader` (+ `override` for tests) and exposes `verification()` (KERNEL_CONFIRMED iff a backend is present); the ports speak only our types. `:adapter` implements + registers them |
| `Verification` | the degraded-mode honesty marker (`KERNEL_CONFIRMED` / `STRUCTURAL_ONLY`) — carried by a `ValidationResult` and a `BoundModel` binding so a green result can never be read as kernel-confirmed without a backend (§2c) |
| `ModelResolver` | **the** include-resolution type — a kernel-free `id → DM-JSON` SAM; every kit signature that resolves references speaks it (never the kernel resolver) |
| `ReferenceResolvers` | `ModelResolver` factories (`selfContained`/`directory`) — all kernel-free (Jackson only); the kernel-resolver bridge moved into `:adapter` (`KernelResolvers`) at stage 3 |
| `Severity`, `Notification`, `Annotation` | owned value vocabulary — the **diagnostics** (INFO/WARNING/ERROR; severity + message; a kit `Diagnostic`/`ValidationResult`/`ModelConsistencyException` carries these, never a kernel `RankedNotification`/`Severity`) and an element's **annotation** (name/value, read kernel-free off `ModelIndex`). The kernel→ours mapper (`KernelNotifications`) moved into `:adapter` at stage 3 |
| `ModelConsistencyException` | the one type a kernel rejection (load-time or write-time) surfaces as — carries our `Notification`s |
| `ValueKind`, `Suggest`, `ModelFormat`, … | value-type taxonomy (kernel-free — from the stored DM-JSON discriminator), did-you-mean suggestions, the DM-JSON format reference, `DmKeys`/`ElementType`/`UnresolvedReferenceException` |

### 3.2 `dm.rulekit` — the rule & computation DSL

The typed condition/computation language: author, render (EN/DE), read back, validate against the real kernel. Six sub-packages; `dsl` is the hub. **Not acyclic**: `{dsl, catalog, validate, write}` form one strongly connected component (jdeps over the built jar, 2026-07-11 — `catalog.BuiltInPattern` → dsl while `DslRenderer` → catalog; `dsl.BoundModel` → `write.ModelWriter` via fully-qualified references no import sweep sees, while `ModelWriter` → dsl types; dsl builders → `validate.Diagnostic` while `validate.DiagnosticEnricher` → catalog). `lint`/`context` sit above the SCC, `core` below — so rulekit ships as one unit; see [KERNEL-DECOUPLING-PROPOSAL.md](KERNEL-DECOUPLING-PROPOSAL.md) §1b.

```
            ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  consumers │  lint (6)    │  │  context (6) │  │  write (1)   │
  of dsl    │ Linter,      │  │ ModelContext │  │ ModelWriter  │
            │ RuleDiagnost.│  │ describe/    │  │ (DM-JSON     │
            │              │  │ find/explain │  │  write path) │
            └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
                   └─────────────────┼─────────────────┘
            ┌────────────────────────┴──────────────────────────┐
   heart    │  dsl (105) — the typed sealed AST + handles +      │
            │  renderer. BoundModel, Dsl (static facade),        │
            │  DslRenderer, Rule/Computation builders, Condition/ │
            │  Calculation sealed hierarchies, RenderedDiff       │
            └──────┬──────────────────────────────┬──────────────┘
            ┌──────┴───────┐                       │
   ground   │ validate (14)│                       │
   truth    │ RuleValidator│                       │
            │ Diagnostic,  │                       │
            │ RkCode,      │                       │
            │ KernelDiag-  │                       │
            │ nostics      │                       │
            └──────┬───────┘                       │
            ┌──────┴────────────┐     ┌────────────┴───────────┐
  founda-   │ catalog (8)       │     │ (dm.core: model index)  │
  tions     │ OperatorCatalog,  │     │ ModelIndex/ModelLoader  │
            │ OperatorEntry —   │     └─────────────────────────┘
            │ EN/DE spine, N7   │
            └───────────────────┘
```

| Package | Imports | Role |
|---|---|---|
| `catalog` | — | the language-neutral operator spine: ids, EN/DE keywords, kinds, meanings, gotchas, `opposite`/`seeAlso`; self-verifying against the kernel (N7). Served by the CLI `operators` verb. |
| `validate` | `catalog`, core | ground-truth validation through the real kernel (`RuleValidator`), the LSP-shaped `Diagnostic`, the single-source diagnostic vocabulary `RkCode`, `KernelDiagnostics` (the `MVK_*` enrich/join), `KernelVersion` |
| `dsl` | `catalog`, `validate`, core | the typed **sealed** AST, model-bound handles, the catalog-driven EN/DE renderer, the authoring builders, `BoundModel` (the immutable model+rules rep), the `Dsl` static-import facade, `RenderedDiff` |
| `lint` | `catalog`, `dsl`, `validate` | the AST lint layer + `RuleDiagnostics`, the aggregator across diagnostic origins |
| `context` | `catalog`, `dsl`, core | the F9/F9a projections: `describe`/`find`/`explain` → `ModelCard`/`FieldMatch`/`RuleExplanation` |
| `write` | `dsl` | the rule-aware DM-JSON write path (`ModelWriter`) over its package-private tree-surgery primitives (`write.DmJson` — rulekit-private since Set W: no Jackson type crosses a published kit signature) |

**Why `dsl` is one flat package of ~105 files (and stays flat).** Its spine is **sealed** — `Condition` and `Calculation` are `sealed interface`s with dozens of permitted subtypes each, plus a sealed operand hierarchy. The project has **no `module-info.java`**, so everything compiles into the *unnamed module*, and the JLS requires every permitted subtype of a sealed type to share the **same package** as its parent there. Sub-packaging (e.g. `dsl.conditions`) therefore wouldn't compile; the only escapes are adding a `module-info` (a heavy, risky change against the kernel/Groovy/native interplay for no navigability win) or dropping `sealed` (which forfeits the exhaustive-`switch` the renderer/visitors rely on — PROJECT-DESIGN §18.5). The package is one-class-per-file and nodes are named after the operators they model, so navigate by filename.

### 3.3 `dm.modelkit` — structure authoring & editing (28 files)

The structural peer of rulekit: build a model from scratch, and read/round-trip/edit its structure (fields, groups, type-defs, includes, doc config). Jackson tree surgery over its own package-private node helpers (`DmNodes`), re-bound to the kernel gate.

| Class | Role |
|---|---|
| `DocumentModelDraft` | the from-scratch fluent builder (`Builder`/`GroupBuilder`/`TypeDefBuilder`/… nested; the field-kind builders extracted to their own files — `StringFieldBuilder`, `NumberFieldBuilder`, `EnumFieldBuilder`, `Date*FieldBuilder`, `CustomFieldBuilder`, …) |
| `ModelDocument` | read an existing model's structure + tree-surgery edits (add/modify/remove fields & groups, includes, `reprefixIds`, mount detection) |
| `FieldKind`, `FieldModification`, `FieldTypeSpec`, `MountOptions`, `AttachmentRule`, `IncludeLevel`, `DatePrecision` | the structural value types |

### 3.4 `dm.edit` — orchestration over both kits (8 files)

The **only** layer that imports both kits. `EditSession` threads one DM-JSON document through a sequence of structure + rule edits and validates **once** at the end (the read → ordered surgery → one terminal gate → write pattern the CLI `apply` verb exposes). It owns the `*Editor` family — `GroupEditor`, `FieldEditor`, `RuleEditor`, `ComputationEditor`, `ConfigEditor`, `IncludeEditor`, `TypeDefEditor` — the session-bound editor ergonomics (PROJECT-DESIGN D7 / API-GUIDE [§5c](API-GUIDE.md#refs-and-editors)).

```
   dm.edit.EditSession   ──imports──▶  dm.modelkit  (structure edits)
        │                ──imports──▶  dm.rulekit   (rule/computation edits)
        │                ──imports──▶  dm.core      (index, the gate, the wire vocabulary)
        └─ read JSON → apply ordered edits (transient-invalid tolerated) → validate() once → write JSON
```

### 3.5 The public API surface, and what crosses each boundary

The library's entry points (what a developer or the CLI calls):

| Entry point | Package | What it gives |
|---|---|---|
| `Dsl` (static facade) + the builders | `rulekit.dsl` | author conditions/computations in typed Java (the jOOQ-style single static import) |
| `BoundModel` | `rulekit.dsl` | the immutable model+rules rep — add/modify/remove rules, `toJson()` |
| `DslRenderer` | `rulekit.dsl` | render a typed condition to DSL text (EN default; DE supported) |
| `RuleValidator` | `rulekit.validate` | validate a candidate rule/model through the real kernel → `Diagnostic`s |
| `OperatorCatalog` | `rulekit.catalog` | the operator vocabulary (also served by the CLI) |
| `ModelContext` | `rulekit.context` | `describe`/`find`/`explain` projections |
| `DocumentModelDraft`, `ModelDocument` | `modelkit` | build / read+edit structure |
| `EditSession` | `edit` | one transactional read→edit→validate→write session |
| `KernelAdapter` | `adapter` | read foreign rules, `whereUsed`, reference-rewrite, runtime-evaluate (opt-in) |

**The "out" surface — plain data only** (§1 invariant): every artifact that leaves the library is portable.

| Artifact | Type | Produced by |
|---|---|---|
| rendered `errorCondition` | `String` (EN/DE) | `dsl.DslRenderer` |
| model DM-JSON | `String` | `dsl.BoundModel.toJson()` / `write.ModelWriter` / `modelkit` |
| `Diagnostic` | record (LSP-shaped, JSON) | `validate` / `lint.RuleDiagnostics` |
| `RuleExplanation` / `ModelCard` | record (JSON) | `context.ModelContext` |
| `ReadResult` (`Converted`/`Opaque`) | sealed record | `adapter.KernelAdapter.read` |
| `RenderedDiff` | record (unified text / JSON) | `dsl.RenderedDiff` / `adapter.KernelAdapter.diff` |

---

## 4. Kernel integration — the adapter (N8 boundary)

The kernel is the **validity oracle**, but most of its rich read APIs are in the unstable `a12internal` tier. `:adapter` is the *only* unit that touches them, and it exists to convert the kernel's world into dmkits' own types.

**What it provides** (`adapter` package, 12 files):

| Capability | Class(es) | Returns (dmkits type) |
|---|---|---|
| read a stored rule back to the typed AST | `KernelAdapter.read` → `ConditionConverter` | `ReadResult.Converted(Condition)` or `ReadResult.Opaque(text, reason)` |
| read a computation | `ReadComputation`, `ComputedField` | dmkits records |
| `where-used` (exact) | `KernelAdapter.whereUsed` / `whereUsedInComputations` | `Set<String>` full names |
| reference rewriting (rename/move) | `ReferenceRewriter` (wraps the kernel's `MoveSupportDM`) | rewritten DM-JSON |
| runtime evaluation (does a rule FIRE on data?) | `KernelAdapter.evaluate` | `EvalResult` / `RuleTestOutcome` / `ComputeResult` |

**The four guardrails** (N8):

1. **Boundary conversion** — a kernel type never escapes a public adapter signature. `KernelAdapter` returns rulekit `Condition`/`ReadResult`/`String`/records, never an `ICondition` or `IRule`. The kernel's `ICondition` tree is the adapter's *transport format*, not a representation users touch.
2. **Read-only** — the adapter never *constructs* kernel AST. Authoring goes through rulekit's own typed builder + `DslRenderer` → text → the kernel parser. (D15.)
3. **Pin + CI canary** — the `a12internal` jars carry no stability guarantee, so the kernel version is pinned and a bump fails loudly (the guardian tests in [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md)).
4. **Fail-fast coverage** — a construct the converter doesn't model throws inside the converter and surfaces as a tolerant `ReadResult.Opaque`, never a silent miss (`UnsupportedConstructException`).

---

## 5. The CLI — `dmtool`

`:cli` is a leaf **application** module: it depends on the library + adapter and nobody depends on it. It turns the engine into a self-describing, agent-facing tool of **target-first verbs over JSON** (`dmtool -m <model> <target> <op> [args]`). Full contract: [`CLI-SPEC.md`](CLI-SPEC.md).

### 5a. Components & package organization

A root **hub** package plus nine **verb-family** sub-packages:

```
  cli/  (root hub, 36 files)
   │  Cli / CliMain / RootCommand     the picocli tree + entry point
   │  BaseCommand · ResultEnvelope     shared command infra: the envelope, exit codes
   │  SafetyGate · SafetyChecks · Refusal      the §6 safe-delete / refactor gate
   │  StructureRefactor · ReferenceRewritingRefactorCommand · GroupRemoval   shared edit logic
   │  WorkspaceOption (-m / -w mixin) · ParamKind(s) · ModelPaths · Suggestions · SpecKeys
   │  cross-cutting verbs: apply · batch · export · manifest · schema · operators ·
   │                       diagnostics · patterns · where-used · meta · mcp · …
   │  Mcp{Command,Dispatcher,ToolRegistry,Invoker,Frames,Protocol}   the MCP server (§5f)
   │  Mcp{Stdio,Http}Binding · McpHttpConfig    its two transports over that one seam
   │
   ├── cli.model/ rule/ computation/ field/ group/ typedef/ include/ config/ workspace/
   │     each family = a parent command + its op subcommands + that family's spec
   │     (FieldSpec, ComputationSpec). Subcommands stay package-private; only the
   │     family roots + shared infra are public.
```

A family's parent and subcommands live together so the subcommands stay package-private; only the family roots and the cross-package shared infra are `public`. (Widening is harmless — `:cli` is a leaf nobody links against. Unlike the sealed `dsl` package, nothing *forces* the CLI flat; the families just make it navigable.) The mirroring test sources live in the same family sub-packages; the cross-cutting/conformance tests stay at the test root.

> The **live verb list is not enumerated here** — that's exactly the kind of fact that rots. It's derived from the picocli tree and served by `dmtool manifest`; see CLI-SPEC [§5](CLI-SPEC.md#verb-surface) for the surface.

### 5b. picocli — declarative parsing, and the manifest derives from the tree

One `@Command`/`@Option(description=…)` declaration per verb drives **parsing, `--help`, *and* the machine-readable `manifest`** — so help and the manifest cannot drift from behavior (they recompute from the same command tree). `BaseCommand` is the shared superclass: it resolves the `-m` model (the `WorkspaceOption` mixin) and owns the `fail(...)`/`refuse(...)` paths. Invocations whose effective output kind is `RESULT_ENVELOPE` return one **`ResultEnvelope`** (`{ok, valid, outcome, data, diagnostics, written, …}`, §3b of the spec) with exit codes keyed off `ok`/`valid`; a matching raw view or an artifact/catalog profile emits its declared raw JSON/text or empty-stdout form instead. A single `setExecutionExceptionHandler` seam in `Cli` maps an escaped `ModelConsistencyException` (an invalid-on-disk load) to a structured `rejected` envelope, so no verb leaks a stack trace.

```
  argv ─▶ picocli parse (RootCommand → family → op) ─▶ <Op>Command.call()
                                                          │ resolve -m / -w (WorkspaceOption)
                                                          │ load via dm.core / edit / adapter
                                                          │ mutate / read / validate (the kernel gate)
                                                          ▼
                                                   ResultEnvelope or declared raw artifact ──▶ stdout, exit code
```

### 5c. The self-describing surface + conformance guards

The CLI is built to be **discoverable by a cold agent from the binary alone** (no skill): `manifest` (the whole verb tree + per-verb params + schema pointers), `schema <target> <op>` (I/O contracts, derived from the live tree), `operators`/`patterns` (DSL vocabulary + idioms), `diagnostics <CODE>` (the `RK_*` catalog). Because these are *derived*, drift is caught at build by the conformance machinery: `ManifestVerbTest`, `SchemaVerbTest`, `SchemaExamplesGuardTest` (every schema `examples` block is **executed through the tool**), `CliConformanceMatrixTest`, `CommandReferencesResolveTest`. Detail: [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md).

### 5d. Native image (GraalVM) — and its complications

The release artifact is a **GraalVM native image** (`:cli:nativeCompile`, the `org.graalvm.buildtools.native` plugin; the JVM `installDist` stays the dev/eval vehicle). Three complications, each handled:

| Complication | Why | Handling |
|---|---|---|
| **The Groovy wall** | the kernel's runtime *evaluation* (rule firing, `checkConsistency`'s message interpolation) does Groovy runtime codegen — not AOT-compilable | the runtime verbs (`model eval`/`compute`, `rule eval`) **run on the kernel-free `:interpreter` engine — the sole runtime eval engine**, so they **run in the native binary** — `Cli.JVM_ONLY_VERBS` is empty (no verb hidden); the **`--kernel` eval option was retired** (Set V; `NativeImageRuntimeVerbsTest` now asserts the flag is rejected on every profile), and Groovy is excluded from `nativeImageClasspath` (the JVM build keeps it only for the consistency oracle — `rule check`/`checkConsistency` — never the eval path). The kernel's runtime evaluation stays reachable for **measurement** through the test-scoped `:adapter:kernelProbe` Gradle task ([KERNEL-PROBE-SPEC](KERNEL-PROBE-SPEC.md)) — no verb, no gate, and a `sourceSets.test.runtimeClasspath` that cannot enter a shipped artifact |
| **Reflection** | Jackson-deserialized POJOs, the operator catalog, the kernel's reflective use | **test-suite-driven** reflection metadata capture — [`scripts/capture-native-config.sh`](../scripts/capture-native-config.sh) runs `:cli:test` under the tracing agent, so any new test that exercises a new shape extends the bundled `reachability-metadata.json` automatically |
| **Resources** | StringTemplate/i18n bundles the kernel loads at runtime; **the G31 Node worker script** (`custom-js-worker.mjs`, read via `getResourceAsStream`) | bundled into the image via `-H:IncludeResources` globs (incl. `.*custom-js-worker\.mjs`) |
| **The JS escape's `node`** | `--custom-conditions-js` / `--custom-field-types-js` spawn the system `node` (a subprocess — native-clean, no embedded JS VM) | an **optional runtime dependency**: absent → the construct degrades to `unsupported`, not a crash (same as on the JVM); re-verify the resource + spawn when native is first built with it (NATIVE-IMAGE-GRAALVM §3.2) |

**The capture is the fragile part** — stale metadata makes a probe measure a fiction. The rules (see [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §5.1 + CLAUDE.md): touched `:cli`/`src` source or tests since the last capture → **re-capture → rebuild → [`scripts/native-gate.sh`](../scripts/native-gate.sh)** before probing or shipping. The native gate verifies the binary byte-for-byte against the showboat demos (skipping the JVM-only ones) plus a no-crash battery, and is the trip-wire if a re-capture was forgotten.

### 5e. How the test harnesses exercise the CLI

Two distinct instruments drive `dmtool` end-to-end (both *outside* the JUnit suites):

| Harness | What it is | How it touches the CLI |
|---|---|---|
| [`eval/`](../eval/) (**N6**) | self-sufficiency benchmark — can a *cold* agent, given only the binary (± the skill), author/read rules? Scored, pass@k. | a clean subagent runs the **JVM `installDist`** `dmtool` against a sandbox fixture tree; Layer-1 gold oracle runs in `:cli:test` (deterministic, over `RuleValidator`); Layer-2 = a Workflow pass@k over clean agents. Method: [`EVAL-SPEC.md`](EVAL-SPEC.md). |
| [`probe/`](../probe/) | proactive DX analysis of the **shipped product** — a real agent over a multi-prompt session, to find DX defects before a bug report | a general-purpose subagent drives the **native binary** through a logging `dmtool` shim (captures every command + stdout + stderr + exit); the analysis reads that objective log + the resulting model files. An anti-cheat audit gates each run. Method: [`PROBE-SPEC.md`](PROBE-SPEC.md). |

The split is deliberate: the **eval** runs the JVM build (fast, all verbs, scored self-sufficiency); the **probe** runs the *native* binary (the actual shipped artifact, so native-only bugs are visible) over realistic sessions. Both treat the CLI as a black box — they exercise the same self-describing surface a real agent sees.

---

### 5f. The MCP server — a second surface over the same tree

`dmtool mcp` serves the tool over the Model Context Protocol, for a caller that cannot execute the binary. It is a **projection, not a second implementation**: `McpToolRegistry` derives the tool set from the running picocli tree plus `CommandContracts`, and `McpInvoker` routes every call through `Cli.run` — the same entry point, the same verbs, the same envelope. Nothing about the CLI is reshaped to suit it.

**Two transports, one seam.** `McpStdioBinding` (newline-delimited JSON-RPC on stdin/stdout, for a host that launches the binary) and `McpHttpBinding` (`--http`; Streamable HTTP on a bound port, for a caller that reaches it over the network) are the *only* classes that name a transport shape. Both build their frames with `McpFrames` and hand a request to the same `McpDispatcher`, a method-name table over our own records, which reaches the same registry and invoker. That is the payoff of the content-carrying request model: with no session and no server-side state, adding a transport touched one class rather than threading a lifecycle through the stack. The HTTP binding runs on the JDK's own `com.sun.net.httpserver`, so it adds no dependency edge; its bounded worker pool is the one place two calls are in flight together, which is why the per-request lifetimes (a fresh command tree, a fresh temporary tree, a fresh interpreter) are load-bearing rather than incidental.

It lives in the **root hub package** rather than a `cli.mcp/` sub-package, and that is a dependency fact rather than a preference: `RootCommand`, `ManifestCommand`, `TextRenderer`, and `CommandContracts` are package-private, so a sub-package would need four visibility widenings to reach what a projection must read. Contract: [`MCP-SPEC.md`](MCP-SPEC.md); standing the hosted form up: [`MCP-HOSTING.md`](MCP-HOSTING.md).

## 6. Maintenance map (when the kernel changes)

When the kernel version bumps, [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md) names each guardian test, what its failure means, and the realignment. The D15 boundary (no internal jar on the core classpath) and the N8 guardrails (§4) confine upgrade risk to the opt-in adapter. A kernel/catalog/Jackson-surface change is also a **native re-capture trigger** (§5d).

---

## 7. Keeping this doc alive

**Why this doc went stale before (the lesson).** The previous version carried a long *"Proposed components (TBD)"* section and per-row *"Phase N ✅"* status columns. Those are **dispositions** — statements of plan and status — and a disposition goes false the moment work ships. The CLI, modelkit, `whereUsed`, the editor family all shipped while the doc still listed them as "proposed/deferred," because the doc was never part of the feature-commit reflex and nobody went back to flip the status. This is the exact trap CLAUDE.md names: *rationale is timeless; a disposition is status.*

**The discipline that keeps it living:**

1. **Describe only the present structure.** No roadmap, no "proposed/TBD/Phase," no status columns, no "✅". Planned structure lives in PROJECT-DESIGN §16 or the ledgers; this doc answers *where does the code live today*.
2. **Update it in the same commit as a structural change** — a new module or package group, a moved boundary, a renamed kit, a changed dependency edge, a new verb family, a native-build change. Treat it like the other living surfaces (README, the specs): the structural change isn't done until this doc matches it. This is the trigger to add to your change reflex.
3. **Don't enumerate what a derived surface already lists.** The live verb list comes from `manifest`; the operator inventory from `operators`; per-feature status from git. Pointing at those instead of copying them is what stops the rot — a copied list has no guard and drifts silently.
4. **Diagrams over prose for structure.** The layering, the dependency graph, the request flow are spatial; keep them as ASCII (it travels everywhere and doesn't rot). Prose carries only the *why-it's-shaped-this-way* that doesn't tabulate.
