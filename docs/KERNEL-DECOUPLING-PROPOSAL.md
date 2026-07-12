# Kernel decoupling — the proposal (Set N, design-first)

> **PROPOSAL** (2026-07-11) — intent + rationale, not yet built. Per [DOC-DISCIPLINE.md](DOC-DISCIPLINE.md) §5: as items ship, their contracts graduate into the owning specs ([ARCHITECTURE](ARCHITECTURE.md), [CLI-SPEC](CLI-SPEC.md), [TESTING-SPEC](TESTING-SPEC.md), [RELEASE-SPEC](RELEASE-SPEC.md)) and this doc archives when emptied. **Provisional pending the master review gate.** No production code changed in the set that authored this.

**The program.** The kits (dm.core, rulekit, modelkit, edit, review, schemakit) shall publish to Maven Central under MIT on a shared 0.x release train. Today the kernel dependency blocks that structurally — [RELEASE-SPEC](RELEASE-SPEC.md) §8/§11 already records "Maven Central is structurally blocked — the kernel `api` dep isn't on Central" — and drags the [LICENSING.md](LICENSING.md) caveat onto every artifact. Target end state: **kits with zero kernel imports; one kernel-backend module (today's `:adapter`) behind narrow SPI ports; the kernel stays the oracle in every differential and law test (verification unchanged); dmtool keeps bundling the backend.** The interpreter is already clean (lint-enforced).

**Relation to the interpreter drop-in goal (Scope A, 2026-07-11 — [INTERPRETER-SPEC](INTERPRETER-SPEC.md) §8a / [INTERPRETER-GAPS](INTERPRETER-GAPS.md) IG67).** This program keeps the kernel backend *bundled* in dmtool; a **separate** interpreter goal retires the backend's **`--kernel` EVAL use** (the interpreter becomes the sole runtime eval engine) once its compatibility certificate is green. The two do not conflict: the backend serves **three** runtime uses — the consistency/MVK oracle (R3), the read-back seam (R4), and today's `--kernel` eval — and the drop-in goal retires only the third; the oracle + read seam keep the backend bundled, so "dmtool keeps bundling the backend" holds under both. The **shared enabler is §4 Stage 2** (the kernel-free assembly layer): its parity bar is therefore **differential vs the kernel's expand**, not merely structural — it must satisfy both this program (kernel-free reads for the published kits) and the eval drop-in (correct include/typedef expansion on the interpreter eval path, which today silently drops includes — §1a last bullet).

---

## §1 The audit — every kernel dependence, classified

### §1a The corrected role decomposition

The first-pass R1–R3 decomposition **holds but is incomplete**. Verified counts (grep + jdeps over the built `a12-dmkits-0.8.2.jar`, 2026-07-11):

| role | what it is | where | count |
|---|---|---|---|
| **R1 — leaked diagnostic vocabulary** | `com.mgmtp.a12.model.notification.Severity` + `RankedNotification` became the vocabulary of OUR diagnostics (`Diagnostic`, `RkCode`, `ValidationResult`, the lint layer). Pure type leak. Note: these live in **a12-base's `model-consistency` artifact**, not the kernel jar proper — they arrive transitively via `kernel-md-facade`. | root kits: 12 files import `Severity`, 6 import `RankedNotification`; CLI: 4 more (`BaseCommand`, `ApplyCommand`, `WorkspaceCheckCommand`, `ModelCheckCommand`) | 15 root files (overlap-deduped) + 4 CLI |
| **R2 — the kernel as model parser/holder** | `deserialize → expand(resolver)` + `IDocumentModel` + the `IFieldType` family backing `ModelIndex`/`ValueKind`/`ModelLoader`/`ReferenceResolvers`, plus the **resolver type threaded through OUR public API signatures** (`BoundModel.ofJson`, `EditSession.read`, `RuleValidator.check`, `ModelDocument.validate`, `KernelAdapter.ofJson`, `LintBackstop.lintCandidate`) | root kits: `core/{ModelIndex, ModelLoader, ReferenceResolvers, ValueKind}`, `dsl/BoundModel`, `edit/EditSession`, `modelkit/ModelDocument`; CLI: **42 files** import `IDocumentModelReferenceResolver` | 7 root files + 42 CLI |
| **R3 — the consistency oracle** | `checkConsistency` (the MVK gate), `RuleValidator`'s inject-then-parse confirmation, `KernelVersion`'s F11 skew read. **Not re-implementable; must never silently degrade.** | `core/ModelLoader` (the load gate), `validate/{RuleValidator, KernelDiagnostics, Diagnostic.fromKernel, KernelVersion}`, `modelkit/ModelDocument.validate` (delegates to ModelLoader) | 5 root files (overlapping R1/R2 files) |
| **R4 — the internal-tier read/rewrite/eval surface** *(the role the first pass missed)* | condition parse/read-back (`ConditionConverter` — powers the read/explain/format/deps/usage verbs, the **review engine** `adapter.review.{ModelDiff, ModelReport}`, and schemakit's `TierAProjection`), reference rewriting (`MoveSupportDM` behind the refactor gates), the runtime eval oracle (`--kernel` + the differential estate), the custom-condition bridge | `:adapter` main (4 kernel-importing files of 18), consumed by ~30 CLI files + schemakit | already module-confined (D15/N8) |

**Corrections to the first pass:**

- The "22 files" count is really **21 kernel-dependent kit files + 1 Javadoc-only mention** (`core/ModelResolver.java` names `com.mgmtp` only in prose). The 20 files with `import com.mgmtp` plus `rulekit/dsl/BoundModel.java` (fully-qualified kernel references, no import — invisible to import-sweep greps) are the real set.
- The first pass **missed the CLI entirely**: 44 `cli/src/main` files import `com.mgmtp` — 42 of them only `IDocumentModelReferenceResolver`. Verified: this is **pure pass-through** (obtained from `WorkspaceOption.resolverFor` = `ReferenceResolvers.directory(...)`, threaded into our own APIs; no kernel method is ever invoked on it) with **exactly one exception**: `GroupExtractCommand` hand-builds a composed resolver lambda, calls the kernel's `getDocumentModel(ref)` and uses `IDocumentModelSerializer.deserialize` directly. The mechanism behind the CLI leak is a single root cause: **our own kit APIs speak the kernel resolver type, and root `:` declares `api kernel-md-facade`** so the type reaches every consumer's compile classpath. Fix the kit signatures (R2) and the 42-file surface evaporates.
- The first pass also missed that **the review engine lives in `:adapter`** (`adapter.review.ModelDiff/ModelReport`), not in the kits or the CLI — so "review" as a publishable kit does not exist yet as a package; its kernel dependence is via `KernelAdapter.read` (R4). See §5 ruling 3.
- `schemakit` has **zero** kernel imports (purity-linted) but depends on `:adapter` (`TierAProjection` → `KernelAdapter.read`), so it is kernel-coupled at the module level — the R4 port matters for it.
- **R1 fan-out detail** (from the consumer audit): the only `RankedNotification` getters production code ever calls are `getMessage()` + `getSeverity()` — the replacement record needs exactly two fields. The 5 CLI consumption sites all share one idiom (`for (n) → KernelDiagnostics.enriched(n, null)`).
- **Dead public surface found:** `ModelIndex.model()`, `ModelIndex.fieldType()`, `ValueKind.of(IFieldType)` (outside ModelIndex) and the public `validate/Problem` record have **zero production consumers** — retire/hide instead of porting. `ModelIndex.field()/group()` have exactly one consumer (BoundModel's annotation mapping, already converting to our own `dsl.Annotation`).
- **The kernel-free equivalents claim verified, with gaps.** We do own kernel-free machinery (modelkit's Jackson-tree reads + `DmKeys` + `ValueKind.fromKernelType(String)`; the interpreter's `ModelLoader`/`ModelDef`), but three capabilities exist **nowhere** kernel-free: (1) **include expansion** (mounting an included model's groups under a `modelAlias`, merging typedef namespaces), (2) **imported type-definition resolution + effective types** (constraint reads through a typedef), (3) the **consistency gate** itself (R3 — stays kernel, behind the port). Also: the interpreter's loader keys elements by `id` assuming `id == name-path`, which real-world models violate (the F-IDX finding) — it is **not** a safe foundation for the re-backing; the new reader must index by name-path like `ModelIndex` does.
- **Live defect surfaced (pre-existing, out of this set's scope to fix):** the kernel-free runtime path passes the **raw, unexpanded** model to the interpreter (`ModelEvalCommand` → `InterpreterEngine`), so an includes-bearing model silently loses its mounted content and typedef constraints on the default (non-`--kernel`) path. The stage-2 kernel-free expander (§4) fixes the class; until then this is a known silent-wrong-result hazard to record.

### §1b The DAG probe (jdeps over `a12-dmkits-0.8.2.jar`)

**Kit-level layering is clean and acyclic:** `core ← {rulekit, modelkit} ← edit`; the sibling boundary `modelkit ⊥ rulekit` holds (no edge either way); `edit` depends on both siblings plus `rulekit.{dsl, validate, write}` — so **edit is a cross-kit top layer, not a modelkit appendage** (the program brief's "modelkit+edit" grouping would drag rulekit into modelkit's consumers; cut them separately, §3).

**Rulekit's sub-packages are NOT acyclic** — a finding, not a fix (this set changes no code). `{dsl, catalog, validate, write}` form one strongly connected component:

- `catalog → dsl`: `BuiltInPattern` authors pattern templates through the DSL, while `DslRenderer` (dsl) reads `OperatorCatalog`/`OperatorEntry`.
- `write ↔ dsl`: `ModelWriter` consumes `Rule`/`Computation`/`Severity` (dsl), while `BoundModel` (dsl) calls `ModelWriter` — via **fully-qualified references, no import**, which is why the import-sweep-based "strictly acyclic" claim in ARCHITECTURE §3 didn't catch it (corrected there in this commit).
- `dsl → validate` (`Rule`/`Computation` builders produce `Diagnostic`s/`Draft*`) and `validate → catalog` (`DiagnosticEnricher` joins the catalog) close the larger loops.

`lint` and `context` sit above the SCC (nothing depends on them from below); `core` sits below everything. Consequence for §3: **rulekit publishes as one artifact**; sub-package modularization is off the table without refactors nobody needs.

**Kernel-import surface per package** (jdeps): `core` → md.facade, md.model.api(+fieldtypes, services), model.header, model.notification. `rulekit.validate` → md.facade, md.model.api(+services), model.notification. `rulekit.dsl` → md.model.api(+services), model.header. `rulekit.lint` → model.notification only. `modelkit`, `edit` → md.model.api(+services) (resolver threading + `ModelLoader` return-type descriptors). **`rulekit.catalog`, `rulekit.context`, `rulekit.write` are already kernel-free.**

**The transitive leak lever:** the only `api`-scoped kernel dependency in the whole build is root's `api "com.mgmtp.a12.kernel:kernel-md-facade"`. `:adapter`'s internal-tier jars are all `implementation` (properly confined). Deleting the root `api` edge is the single decisive cut — everything in §4 stages toward it.

### §1c Per-site replacement strategy (the audit table)

| site | role | replacement strategy |
|---|---|---|
| `core/ModelIndex` (holds `IDocumentModel`, walks `IField`/`IGroup`/`IFieldType`) | R2 | re-back onto the new kernel-free reader (§2, stage 2); **retire** the dead `model()`/`fieldType()` accessors; `field()`/`group()` become internal reads of our own element records (annotations already mapped to `dsl.Annotation`) |
| `core/ModelLoader` (deserialize→expand→checkConsistency) | R2+R3 | split: parse+expand = our reader; the consistency gate = `ConsistencyOracle` port (§2) |
| `core/ReferenceResolvers` (kernel resolver factories) | R2 | invert: `ModelResolver` (ours, exists) becomes the primary; the kernel bridge moves into the backend; `directory(...)`'s indexing half is already kernel-free |
| `core/ValueKind.of(IFieldType)` | R2 | delete (zero consumers); `fromKernelType(String)` already covers the kernel-free path |
| `core/ModelConsistencyException` (carries `RankedNotification`) | R1 | carries `core.Notification` (ours) |
| `rulekit/dsl/BoundModel` (kernel resolver field + annotation read-back) | R2 | field becomes `ModelResolver`; kernel-typed `ofJson`/`ofJsonLenient`/`resolver()` overloads removed (0.x break, fail-fast); the two `KernelAdapter` call sites of `resolver()` move to the backend seam |
| `edit/EditSession`, `modelkit/ModelDocument` (resolver threading; validate delegation) | R2+R3 | `ModelResolver` in signatures; `validate` routes through the port |
| `rulekit/validate/{Diagnostic, ValidationResult, RkCode, Problem, ModelVersion}` + `rulekit/lint/*` (kernel `Severity`) | R1 | own `core.Severity`; `Problem` deleted (dead) |
| `rulekit/validate/KernelDiagnostics` | R1+R3 | signature takes `core.Notification`; the backend converts at the seam |
| `rulekit/validate/RuleValidator` (inject + kernel pass) | R3 | keeps injection + prechecks + enrichment (ours); the kernel pass goes through `ConsistencyOracle` |
| `rulekit/validate/KernelVersion` (reads kernel jar manifest) | R3 | `BackendInfo` port; the F11 advisory text/mechanics unchanged |
| CLI: 42 resolver pass-through files | R2 | vanish automatically once the kit signatures speak `ModelResolver`; `WorkspaceOption.resolverFor` returns ours |
| CLI: `GroupExtractCommand` (kernel serializer + resolver composition) | R2 | compose over `ModelResolver` (id→JSON lambda serving the extracted sub-model from memory — strictly simpler than today) |
| CLI: 4 `RankedNotification` files + `ModelCheckCommand`'s kernel-`Severity` compare | R1 | own vocabulary |
| `adapter/KernelAdapter.ofJson(String, IDocumentModelReferenceResolver)` + `KernelCustomConditions.fromCore(ICustomCondition)` | R4 | `ofJson` overload switches to `ModelResolver`; `fromCore` **stays kernel-typed by design** (it is the bridge's entry — kernel-side of the seam) |
| `adapter/{ConditionConverter, ReferenceRewriter}` + `adapter.review` | R4 | stay in the backend; `ConditionReadBack` port (§2) for published consumers (schemakit, future review kit); CLI may keep calling the backend directly (it bundles it) |

Incidental smell recorded for the extraction stage: `ConditionConverter` uses `org.apache.commons.lang3.tuple.Pair` from an **undeclared** transitive kernel dependency — declare it or drop `Pair` when touched.

---

## §2 The SPI design — narrow ports, not one gate

Not one `KernelGate`: **three narrow ports**, each living in the lowest kit that owns its vocabulary, speaking **only our types**. The backend (`:adapter`) implements all three and registers via `ServiceLoader`.

### §2a The ports

**1. `dm.core.spi.ConsistencyOracle`** (the R3 seam):

```java
public interface ConsistencyOracle {
    /** deserialize → expand(resolver) → checkConsistency on the REAL kernel; returns every notification. */
    List<Notification> checkConsistency(String dmJson, ModelResolver resolver);
}
```

with the owned vocabulary (stage 1): `dm.core.Severity` (`INFO`, `WARNING`, `ERROR`) and `dm.core.Notification(Severity severity, String message)` — the audit proved `getMessage`+`getSeverity` is the entire consumed surface. The existing `rulekit.dsl.Severity` (a rule's *declared* severity attribute) stays a separate type: same values, different concept, and unifying them would add a `validate → dsl` edge right where §1b found the cycle knot (ruling 4).

**2. `dm.core.spi.BackendInfo`** (the F11 seam): `String version()` (the kernel's `Implementation-Version`), `String name()`. `KernelVersion.current()` consults it; absent backend ⇒ no skew advisory (nothing to skew against), plus the degraded-mode marker below carries the "no backend" fact.

**3. `dm.rulekit.spi.ConditionReadBack`** (the R4 read seam, for *published* consumers): condition text + bound model in, typed `Condition`/`ReadResult` out — the port shape of today's `KernelAdapter.read`/`canonicalConditionText`/`diagnose`. Lives in rulekit because it speaks rulekit's AST. Two foreseen providers: the kernel backend (authoritative, canonical) and — later — an interpreter-bridge (kernel-free parse; the interpreter already parses the full grammar, but into its own AST; the mapping to rulekit `Condition` is a new, bounded component — ruling 6). The CLI does **not** need this port (it bundles the backend and may keep calling `KernelAdapter` directly); it exists for schemakit's `TierAProjection` and the future review kit.

**Not ports:** the runtime eval oracle (`evaluate`/`compute`/`testRule` — only dmtool's `--kernel` flag and the differential estate call it; both live with/next to the backend), reference rewriting (`MoveSupportDM` — only the CLI refactor gates), and the custom-condition bridge (deliberately kernel-typed). These stay plain public API of the backend module.

### §2b Discovery + injection

`dm.core.Backends` is the single resolution point: `ServiceLoader` discovery (the backend jar on the classpath ⇒ ports present — dmtool JVM *and* native, which embeds the kernel md-tier today; a Central consumer without the backend ⇒ empty), plus an explicit `Backends.override(...)` for tests. Injection stays **inside** the existing entry points — `BoundModel.ofJson`, `EditSession.read`, `RuleValidator.check`, `ModelDocument.validate` resolve the oracle at construction and **retain it alongside the retained `ModelResolver`**, so the snapshot-revalidate contract survives intact: every write still re-binds through `ofJson` with the *same* resolver and the *same* oracle — "a write that breaks the model fails loudly at re-bind" holds in both modes, and the mode is a property of the binding, never ambient state read mid-flight. Native-image note: `ServiceLoader` rides the existing test-captured reachability metadata; the standard re-capture applies ([NATIVE-IMAGE-GRAALVM](NATIVE-IMAGE-GRAALVM.md) §5.1).

### §2c The degraded-mode contract (no backend on the classpath)

| capability | no-backend consumer gets | honesty mechanism |
|---|---|---|
| typed authoring (DSL, builders, EditSession, drafts) | **full** — pure our-code | — |
| model load + index (`BoundModel.ofJson`, `ModelIndex`) | **full structural**: our reader parses, expands includes, resolves typedefs, indexes by name-path (stage 2) | binding carries `Verification.STRUCTURAL_ONLY` |
| our prechecks + lint (`RK_*`) | **full** | — |
| kernel parse-confirmation + MVK verdicts (`RuleValidator.check`, `ModelDocument.validate`, re-bind gates) | structural prechecks only; **no MVK verdict is fabricated** | `ValidationResult` gains `Verification verification()` ∈ {`KERNEL_CONFIRMED`, `STRUCTURAL_ONLY`} + one `ENVIRONMENT`-source diagnostic `RK_NO_KERNEL_BACKEND` per result; `ok()` keeps meaning "no ERROR among the checks that ran" and can never be read as kernel-green without checking `verification()` — never a fake green |
| condition read-back (text → typed AST) | unavailable until the interpreter-bridge provider lands (ruling 6); fail-fast `BackendRequiredException` with a corrective message | explicit throw, not a degraded result — parsing has no honest partial answer |
| interpreter parse/eval/testRule | **full** (the `:interpreter` artifact, kernel-free by construction) | — |

The result envelope / CLI schemas gain the `verification` key **at implementation time** with the usual same-commit schema+guard discipline (CLAUDE.md schema table); dmtool itself always bundles the backend, so the degraded mode is a *library* posture — but the CLI surfaces `verification` anyway so agents can see it.

---

## §3 The module cut

### §3a Target Gradle layout

```
a12-dmkits/
  core/         dm.core                    → published  (io.github.mbackschat.a12.dm:dmkits-core)
  rulekit/      dm.rulekit.* (one SCC)     → published  (…:dmkits-rulekit)
  modelkit/     dm.modelkit                → published  (…:dmkits-modelkit)
  edit/         dm.edit                    → published  (…:dmkits-edit; depends on BOTH siblings)
  schemakit/    (exists)                   → published  (…:dmkits-schemakit)
  interpreter/  (exists, KMP)              → published  (Maven + npm, own coordinates — INTERPRETER-SPEC §7)
  adapter/      (exists, UNMOVED)          → the kernel backend; NOT published (ruling 2)
  cli/          (exists)                   → dmtool; bundles everything incl. the backend
  (root)        pure aggregator — no source sets, no `api kernel-md-facade`
```

Plus an aggregator POM `…:dmkits` depending on the four kit artifacts, preserving the coordinate RELEASE-SPEC §8 already documents (ruling 1). **Packages never rename — only files move.** That single rule keeps stable: every FQCN-keyed Gradle test filter (`:adapter`'s CorpusReplay include/exclude), the SEMANTICS-MAP's simple-name test cites, reflection/native metadata, and every `import` in consumer snippets.

**The backend absorbs nothing physically and moves nothing.** `:adapter` already *is* the kernel module (D15/N8) — it gains the SPI provider implementations and loses the kernel types from its public API (§1c), keeping its folder, name, build file, and **the entire differential/laws test estate (324 files, 221 laws, 171 `*DiffTest`) exactly where it is**. That is the ruling on "does the differential estate move": **it stays put** — the overwhelming-justification bar for moving lock trees is not met, and not-moving costs only the mild historical name "adapter" (which remains accurate: it adapts the kernel to our SPI). The `SEMANTICS-MAP` lock-tree machinery keyed to `adapter/src/test/...` is untouched.

**One lock tree does move:** the accept/reject tier `src/test/java/.../rulekit/validate/laws` (21 files) rides the root-source split into `rulekit/src/test/...`. Its kernel-as-oracle tests keep running via `testImplementation project(':adapter')` — test-scope backend deps are legal and non-leaking (`rulekitTest → adapterMain → rulekitMain` is acyclic), which is exactly how "the kernel stays the oracle in every law test" survives kernel-free kits. Path-keyed touchpoints of this one move: `SemanticsMapResolveTest` line ~62 + `cli/build.gradle` `semanticsMapGuardTest` fileTree input — two sites, listed below.

`SiblingBoundaryTest` **graduates**: the module cut makes `modelkit ⊥ rulekit` a build-graph fact (neither module depends on the other), so the import-sweep test retires with a note — the boundary is now unrepresentable-illegal, the strongest form.

### §3b The path-keyed-machinery sweep list (the folder moves' blast radius)

The known trap: every guard task's `fileTree` input is `.optional()` — a moved folder silently empties the input and the task goes up-to-date/cache-green (**the silent-empty-fileTree false-green class**). The sweep list every reorg commit must visit, and the class-level fix (last row):

| # | touchpoint | what changes |
|---|---|---|
| 1 | `settings.gradle` | four new includes; root loses its source-module role |
| 2 | root `build.gradle` | module config (java-library, kernel dep during transition stages, publishing) splits into the four kit build files; root keeps only aggregation; the catalog resource `src/main/resources/catalog/operators.json` moves to `rulekit/` |
| 3 | root `docGuardTest` (build.gradle ~:90–96) | its fileTree inputs (`…rulekit/dsl` sources, `catalog`, API-GUIDE) re-point or the task moves to `:rulekit` |
| 4 | `cli/build.gradle` `docGuardTest` (~:153–167) | inputs `cli/src/test/resources/models` + `examples/models` unchanged; verify catalog path if referenced |
| 5 | `cli/build.gradle` `semanticsMapGuardTest` (~:185–197) | the `src/test/.../validate/laws` fileTree re-points to `rulekit/src/test/...`; the three adapter/interpreter trees unchanged |
| 6 | `SemanticsMapResolveTest` (~:56–65) | the `../src/...` hardcoded candidates for validate/laws + catalog re-point; adapter/interpreter paths unchanged |
| 7 | `DocSnippetFreshnessTest` + `syncDocSnippets` | the tagged snippet-source tests move with rulekit — re-point the walk roots |
| 8 | `SiblingBoundaryTest` | retire (graduated to the module boundary) |
| 9 | `-Xlint:removal -Werror` ratchet | today on `:`,`:adapter`,`:cli`; after stage 3 only `:adapter`+`:cli` compile kernel code — move the config accordingly |
| 10 | `adapter/build.gradle` | `api project(':')` → the kit projects; FQCN test filters unchanged (packages stable) |
| 11 | `cli/build.gradle`, `schemakit/build.gradle` | `project(':')` → the kit list |
| 12 | `.github/workflows/ci.yml` (~:75, :85) | root `test` no longer exists on an aggregator — enumerate kit test tasks or use `check`; nightly `:adapter:test -PrunPerf` unchanged |
| 13 | `probe/prep-probe.sh` (~:44) | the staleness `find` sweeps `cli/src` + `src` — must cover the kit folders (pre-existing gap: it already misses `adapter/src`/`interpreter/src`; fix the class, not the symptom) |
| 14 | `eval/prep-sandbox.sh`, `scripts/{perf,verify-demos,capture-native-config,native-gate}.sh` | verified path-stable (cli/adapter paths only) — re-verify at reorg time |
| 15 | native-image reachability metadata | test-suite-captured ⇒ any source move stales it — **re-capture before any native build** (routine trigger-table item) |
| 16 | docs sweep | ARCHITECTURE module map; README build/verb-tour paths; TESTING-SPEC path mentions; CLAUDE.md (placement-rule table names `src/main/resources/catalog/operators.json`; testing bullet names `cli/src/test/resources/models`); SEMANTICS-MAP prose naming the three lock trees |
| 17 | **the class fix** | every `.optional()` fileTree-input guard task gains a non-empty-inputs assertion (fail loudly when a guarded tree goes missing) — locks the whole false-green class, not just this reorg |

Corpus machinery (`CorpusFiles` walks up to `settings.gradle`; `rootProject.fileTree('corpus')`) and `KernelMmReadCoverageTest`'s `../` candidates are move-safe as long as `:adapter` stays put — one more reason it does. Size: **17 touchpoint families** (≈25 concrete edits).

---

## §4 The staging plan — each stage behavior-neutral, independently gateable

The first-pass order (vocabulary → re-backing → SPI+extraction+reorg) **validates**, with the third step split and the V1→V2 migration placed as an orthogonal backend-internal stage:

| stage | what lands | net (what must show green) |
|---|---|---|
| **1 — type ownership** (R1 + the resolver de-leak) | `core.Severity` + `core.Notification`; every OUR-API signature that speaks `IDocumentModelReferenceResolver`/`RankedNotification`/kernel-`Severity` switches to our types (kernel overloads deleted — 0.x clean break, fail-fast); `WorkspaceOption.resolverFor` returns `ModelResolver`; the 42 CLI pass-through files + `GroupExtractCommand` migrate; dead surface deleted (`ModelIndex.model()/fieldType()`, `Problem`, `ValueKind.of(IFieldType)`) | full suite + CLI conformance matrix + `verify-demos.sh`; **zero rendered-output/envelope change**; ratchet stays green. Kernel imports drop from 65 files to ~10 (kit internals only) |
| **2 — R2 re-backing** (parity-locked) — **SHIPPED (Set P, 2026-07-12)** | the kernel-free DM-JSON reader in `dm.core` (`ModelReader`; Jackson; name-path indexed; **include expansion + imported-typedef/effective-type resolution** — the two genuinely new capabilities; the rest merges existing `ModelDocument`/`DmKeys`/`fromKernelType` reads); `ModelIndex` pivots onto it; `ModelLoader` still runs the kernel `checkConsistency` gate (R3 untouched) | **the parity net**: a dual-run comparator asserting kernel-index == our-index over every fixture + BOTH opt-in corpora (`adapter.ModelIndexParityTest` vs the preserved `KernelModelOracle`); full suite; differential suites untouched by construction; perf gate (index-build regression) |
| **3 — SPI landing + backend consolidation** — **SHIPPED (Set Q, 2026-07-12)** | the three ports + `Backends` discovery; `RuleValidator`/`ModelLoader`/`ModelDocument.validate` route through `ConsistencyOracle`; `:adapter` implements + registers; **root deletes `api kernel-md-facade`** (the decisive cut — kits now have zero kernel imports); `KernelAdapter` public API purged; degraded mode + `verification` marker + envelope/schema updates land together | full suite + conformance + demos + corpus sweeps; **a new degraded-mode lane**: run the kit test-tier without the backend on the classpath, proving the §2c table row by row and that no fake green is possible; native re-capture + native-gate (ServiceLoader metadata) |
| **4 — folder reorg + publishing wiring** | pure file moves per §3; per-kit publications + the `dmkits` aggregator POM; `SiblingBoundaryTest` graduates; the §3b sweep incl. the class fix (#17) | full suite **forced fresh** (`--rerun-tasks` — up-to-date/cache greens prove nothing across moves); every guard task demonstrably sees non-empty inputs; `verify-demos.sh`; CI workflow green on the new task names |
| **5 — V1→V2 document construction** (the [A12K-3589](KERNEL-INTEGRATION-NOTES.md) retained bridge) | backend-internal only: `KernelAdapter`/`RuntimeLaws` document construction moves off the plain-`@Deprecated` V1 `IDocumentFactory` + V1V2 converter onto the V2-native builder | the full differential + fuzz estate + both corpus sweeps + perf gate — the oracle's own construction path is what changes, and the differential estate is precisely the net that catches any drift. **Orthogonal to stages 1–4** (touches no kit code, no time-bomb pressure since the ratchet already blocks forRemoval APIs); schedule last or in parallel with 4 |

Each stage is a shippable checkpoint; nothing forces them into one release. The interpreter-bridge `ConditionReadBack` provider (degraded read-back) is deliberately **not** a stage — see ruling 6.

**Status — Stage 3 SHIPPED (Set Q, 2026-07-12; provisional pending the master review gate).** The three SPI ports land: `dm.core.spi.ConsistencyOracle` (R3), `dm.core.spi.BackendInfo` (F11) — both resolved by `dm.core.Backends` (`ServiceLoader` + `override`) — and `dm.rulekit.spi.ConditionReadBack` (R4 read seam, resolved by `dm.rulekit.spi.ReadBackends`, fail-fast `BackendRequiredException` with no backend). `:adapter` implements all three (`KernelConsistencyOracle`/`KernelBackendInfo`/`KernelConditionReadBack`) and registers them via `META-INF/services`; the R1 mapper (`KernelNotifications`) and the kernel-resolver bridge (`KernelResolvers`) relocated INTO `:adapter`. `ModelLoader.loadAndCheck` / `RuleValidator.check` / `ModelDocument.validate` / `EditSession.validate` / `KernelVersion` all route through the ports. **The decisive cut: root deleted `api kernel-md-facade`** — the kits (`core`, `rulekit`, `modelkit`, `edit`) now import **ZERO** `com.mgmtp` (measured: **kit-touching files 7 → 0**; only Javadoc prose mentions remain, comment-stripped by the guard). `KernelAdapter`'s public API was already kernel-free (Stage 1/2); the read-back DTOs `ReadResult`/`ReadComputation` moved to `dm.rulekit.read` so the port returns them, and schemakit's `TierAProjection` routes through `ConditionReadBack` (its source now names no kernel/adapter type). **Degraded-mode contract (§2c):** `ValidationResult` + `BoundModel` gain `Verification verification() ∈ {KERNEL_CONFIRMED, STRUCTURAL_ONLY}`; with no backend the consistency gate is structural-only + one `RK_NO_KERNEL_BACKEND` `ENVIRONMENT` advisory and **never a fake green** (`ok()` can't read as kernel-green without a backend); the CLI surfaces the `verification` key on `rule check`/`model check` (schema + `SchemaVerbTest`). **The acceptance bar met:** a dedicated backend-free source set (`noBackendTest`, classpath = kit main + Jackson + JUnit, NO `:adapter`/kernel) proves the §2c table row by row (`NoBackendLaneTest`); the `verifyKitPurity` guard (peer of `verifyInterpreterPurity`, does-fire verified) locks the cut. **Behaviour-neutral for dmtool:** the adapter differential/laws estate is bit-for-bit green (the oracle routes through the port), zero rendered-output change beyond the additive `verification` key. Nets green forced-fresh: `:test` + `:adapter:test` + `:cli:test` + `:interpreter:check` + conformance + `verify-demos.sh` + both corpus sweeps; the A12K-3589 ratchet stayed green (now guarding root TEST + `:adapter` + `:cli`, since root MAIN is kernel-free). **Native deferred (owner ruling):** the new `ServiceLoader` provider metadata (three `META-INF/services` files) must be picked up by the next release's reflection re-capture ([NATIVE-IMAGE-GRAALVM](NATIVE-IMAGE-GRAALVM.md) §5.1) — no native chain was run this set. **One bounce:** none — the schemakit `ConditionReadBack` routing landed cleanly (the `ReadResult` relocation was mechanical). Stages 4 (folder reorg + publishing) and 5 (V1→V2 construction) remain.

**Status — Stage 2 SHIPPED (Set P, 2026-07-12; provisional pending the master review gate).** The kernel-free `dm.core.ModelReader` is the assembly layer — it clean-room reimplements the kernel's `expand` (include mounting under the pre-existing `modelAlias` group per the source-verified semantics; `includeLevel` SINGLE_RG/MODEL_ROOT; `excludeRules`/`excludeComputations`; imported-typedef→effective-type inlining; id normalization to name-path), kernel-free (KERNEL-FINDINGS "DocumentModel expansion"). `dm.core.ModelIndex` is now the **kernel-free typed view** over it: the kernel-typed `model()`/`fieldType()` **and** the kernel-typed returns of `field()`/`group()` are **retired**; `annotationsOf`/`annotationsOfGroup` carry the one production read-back kernel-free via a new `core.Annotation`. `BoundModel` keeps the real-kernel consistency gate (R3, `ModelLoader.load`) but indexes kernel-free — it no longer names any `com.mgmtp` type; `KernelAdapter`'s structural index is kernel-free too. **The parity bar is met**: `adapter.ModelIndexParityTest` dual-runs the kernel-free index and the preserved kernel oracle (`KernelModelOracle`) over authored models + committed fixtures + the `a12-kernel-mm` corpus (opt-in workspaces), asserting identical name-paths + facts — the *differential vs the kernel's expand*. The **8 kernel-honored-slot verification tests** that kept the kernel-typed accessors alive re-homed to `:adapter` onto `KernelModelOracle` (no coverage deleted — "the kernel stays the oracle"). **A4 payoff:** the CLI eval verbs pre-expand kernel-free (`InterpreterEngine.expandForEval`), closing the silent-includes-drop defect; an included-model's own rules refuse loudly (`RK_INTERPRETER_INCLUDE_RULES_UNSUPPORTED`) pending relativization (IG67). Two parity-surfaced kernel facts recorded (KERNEL-FINDINGS): the number-bound-at-declared-scale read-back, and the expand mechanism. Nets green forced-fresh: `:test` + `:adapter:test` (incl. the parity net + differential/fuzz/laws) + `:cli:test` + `verify-demos.sh`; the A12K-3589 ratchet stayed green; zero rendered-output/envelope change except the intended A4 fix. Stages 3–5 remain.

**Status — Stage 1 SHIPPED (Set O, 2026-07-11; provisional pending the master review gate).** `dm.core.Severity`/`Notification` + the single `KernelNotifications` boundary mapper own the R1 vocabulary; `dm.core.ModelResolver` is now THE resolver type in every OUR-API signature (the kernel-typed overloads deleted, incl. the dead `ModelReport`/`ModelDiff` review overloads), with `ReferenceResolvers.of(ModelResolver)` the one interim bridge; `WorkspaceOption.resolverFor` returns `ModelResolver`; the 42 CLI pass-through files + `GroupExtractCommand` migrated (its composed resolver is now a trivial `id → DM-JSON` lambda); `Problem` + `ValueKind.of(IFieldType)` deleted (the classifier moved onto `ModelIndex`, making `ValueKind` kernel-free). **Measured: kernel-touching files 65 → 7 kit internals** (`ModelIndex`, `ModelLoader`, `ReferenceResolvers`, `KernelVersion`, `RuleValidator`, `KernelNotifications`, `BoundModel`), **CLI 44 → 0.** Nets green forced-fresh: `:test` + `:cli:test` (incl. the CLI conformance matrix) + `:adapter:test` (the oracle differential/fuzz/laws) + `verify-demos.sh`; the A12K-3589 `-Xlint:removal -Werror` ratchet stayed green; zero rendered-output/envelope change. **One bounce:** the proposal listed `ModelIndex.model()`/`fieldType()` as deletable "dead public surface", but both back kernel-read-back *verification tests* (`FieldConfigExtrasTest`, `SupportedCharactersTest`, `AttachmentTemplateTest`) that prove the kernel *honored* an authored slot — with no kernel-free equivalent by design — so they were **kept**; retiring them belongs with stage 2's re-backing (when the read-back verification re-homes onto the kernel-free reader / `:adapter`). Rationale graduated to [ARCHITECTURE](ARCHITECTURE.md) §3.1 + [API-FINDINGS](API-FINDINGS.md) 66. Stages 2–5 remain; this doc stays until they land.

---

## §5 Open rulings (recommendation first; the master rules at the gate)

1. **Artifact granularity.** Recommend: four slim kit artifacts + `dmkits` aggregator POM (keeps the RELEASE-SPEC §8 coordinate working, lets consumers take modelkit without rulekit). Alternative: one merged `dmkits` jar from the root as today — smaller sweep (stage 4 shrinks to publishing wiring only), but weld-shut granularity and a permanent root-as-module wart. The stage-4 scope depends on this ruling.
2. **Backend publication.** Recommend: `:adapter` stays unpublished; kernel-licensed consumers who want the oracle build it from source (as dmtool does). Re-open trigger: a real external consumer asks — then publish under the [LICENSING.md](LICENSING.md) EUPL-1.2 posture with the kernel LICENSE/NOTICE riders, coordinates clearly outside the MIT train.
3. **The review kit.** The engine (`adapter.review`) is kernel-coupled via the read path and its CLI glue lives in `:cli`. Recommend: **do not extract in this program** — keep the seam clean (review consumes `ConditionReadBack` + modelkit reads after stage 3) and extract to a published `review/` module in a follow-up set once the port exists and [MODEL-REVIEW-GAPS](MODEL-REVIEW-GAPS.md) MR1 (AST-level diff) clarifies what the kit's degraded mode should be.
4. **Two `Severity` types.** Recommend keeping `dm.core.Severity` (diagnostic) and `rulekit.dsl.Severity` (rule attribute) separate — same values, different concepts, and unification would thread new edges through the §1b cycle knot. Cost: a rare dual-import needs one FQN.
5. **Degraded `RuleValidator` semantics.** Recommend: structural-only result + `verification()` marker + `RK_NO_KERNEL_BACKEND` diagnostic (per §2c), NOT a hard throw — the library must stay usable kernel-free (that is the program's point), and F11's "the live kernel is the sole authority" is honored by never *claiming* kernel confirmation. Alternative (hard `BackendRequiredException` on `check`) is honest but kills the degraded library story.
6. **The interpreter-bridge read-back provider** (kernel-free text→`Condition`). Recommend: out of this program — the port ships with the kernel provider only; the bridge (interpreter AST → rulekit `Condition` mapping) is its own bounded follow-up with its own parity net. Until then degraded read-back throws fail-fast (§2c).
7. **Two kernel-free loaders** (the new `dm.core` Java reader vs the interpreter's KMP `ModelLoader`). Recommend: accept the duplication, cross-locked by the stage-2 parity net + the existing interpreter parity guards; unifying would force KMP into `dm.core` for zero consumer benefit. Re-open trigger: the two drift on a corpus model.
8. **Discovery mechanism.** Recommend `ServiceLoader` + explicit override (standard, native-image-compatible, zero-config for dmtool). Alternative — explicit-injection-only — is purer (no ambient state) but taxes every entry point signature and the CLI wiring for no honesty gain (the `verification` marker is what keeps it honest, not the wiring style).
9. **Stale-doc corrections found during research** (not this set's deliverable, flagged for a docs pass): RELEASE-SPEC §3a still claims the runtime-eval verbs are hidden from the shipped binary (contradicted by NATIVE-IMAGE-GRAALVM §1 / ARCHITECTURE §5d); RELEASE-SPEC §10's "developer jars v1" row contradicts §1/§11 "deferred"; INTERPRETER-SPEC §1's "today they are the JVM_ONLY_VERBS". (ARCHITECTURE §3's "strictly acyclic" claim was corrected in this commit — the jdeps probe falsified it.)
10. **The silent includes-drop defect on the kernel-free runtime path** (§1a last bullet). Recommend: fix with stage 2 (the expander is the root fix); if a quicker interim guard is wanted, a loud refusal when the kernel-free path meets `modelReferences` is the honest stopgap.
