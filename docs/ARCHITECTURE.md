# a12-dmkits — architecture

The **structural** view of the system as it is **today**: the principal components, how the packages layer and depend on each other, where the kernel coupling sits, and how the CLI and its test harnesses are built. This is the *where the code lives and how it fits* doc.

- For the **why** — the design arguments, the rejected alternatives, the decision log — read [`PROJECT-DESIGN.md`](PROJECT-DESIGN.md) (the conceptual component view is §15; the phasing/roadmap is §16). The numbered API design findings live in [`API-FINDINGS.md`](API-FINDINGS.md).
- For the **CLI contract** (verbs, the result envelope, the self-describing surface) read [`CLI-SPEC.md`](CLI-SPEC.md) / [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md); for the **native build** read [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md); for **kernel facts** [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) / [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md).

> **This doc describes the present, not the plan.** It carries no roadmap, no "proposed/TBD" parts, and no per-feature status. That is deliberate — see [§7](#7-keeping-this-doc-alive). Future/▸planned structure belongs in PROJECT-DESIGN §16 or the ledgers, never here.

---

## 1. The big picture

dmkits is **one library with a shared core and two sibling kits**, an **opt-in kernel adapter** that reaches the kernel's internal read APIs, and a **CLI front-end** (`dmtool`) for the agent/BA. Everything sits on the **A12 kernel**, and the whole stack is built so the core never touches anything but the kernel's *public* surface.

```
                    ┌──────────────────────────── front-ends ─────────────────────────────┐
  agent / BA  ────▶ │  dmtool CLI  (:cli)              developer's own project              │
  developer   ────▶ │  picocli verbs · JSON in/out     (rulekit/modelkit as a library dep)  │
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
                    │            ModelIndex · ModelLoader · DmJson write · ModelResolver       │
                    │            ModelConsistencyException · ValueKind · the kernel gate       │
                    └────────────────────────────────┬────────────────────────────────────────┘
                            public kernel ONLY (D15)  │                ▲  internal-read, guarded (N8)
                                                      ▼                │  returns core types only
                    ┌──────────────────────────────────────┐   ┌──────┴───────────────────────┐
                    │  A12 kernel  (published to mavenLocal) │◀──│  :adapter                    │
                    │  public:   kernel-md-facade            │   │  a12internal READ tier       │
                    │            (expand/check/serialize)    │   │  (parsetree · tool · md-model)│
                    │  internal: parsetree-api · tool-api ·  │   │  + runtime-eval tier         │
                    │            md-model · runtime           │   │  KernelAdapter (converts AST,│
                    └──────────────────────────────────────┘   │   whereUsed, rewrite, eval)   │
                                                                 └──────────────────────────────┘

  read the map: arrows = "depends on / calls". Kernel coupling WIDENS downward. The core is public-kernel-only
  by construction (no internal jar on its classpath); ONLY :adapter crosses into the kernel's internal tier,
  and it converts every kernel type back to a dmkits type at the boundary (nothing internal escapes).
```

**The three principal layers, top to bottom:**

| Layer | Packages / units | Job |
|---|---|---|
| **front-ends** | `:cli` (`dmtool`); a developer's own project | drive the library — the CLI for an agent/BA over JSON; the library directly for an in-repo developer |
| **the library** | root project `:` — `dm.core`, `dm.rulekit`, `dm.modelkit`, `dm.edit` | author/read/edit/render/validate a document model and write DM-JSON back |
| **kernel coupling** | `:adapter` (opt-in) + the A12 kernel | the kernel is the validity oracle; the adapter is the *only* code that reaches its internal read APIs |

**The load-bearing invariants** (each enforced by a test, not just convention):

- **Public-kernel-only core (D15/N8).** The root project binds only `kernel-md-facade`; the internal kernel jars are on `:adapter`'s classpath alone. So the core *cannot* couple to the kernel's unstable internals by accident — and stays polyglot-portable (a future TS twin could rebuild the same index from the same DM-JSON).
- **Sibling kits (`dm.modelkit ⊥ dm.rulekit`).** Structure editing and rule authoring are peers over `dm.core`; neither imports the other. Held by `SiblingBoundaryTest` (a two-way package import sweep).
- **Data out, never kernel objects (§5e).** Every artifact that crosses a boundary is plain data (a `String`, a record, JSON) — never a live kernel object. That is what keeps the out-surface agent-consumable and the core portable.

---

## 2. Modules & dependencies

Four Gradle units. modelkit and `dm.edit` are **packages inside the root project**, not separate modules (modelkit was folded in 2026-06-09 when the kernel-free module wall was dropped — history in PROJECT-DESIGN §21H).

| Unit | Gradle id | Depends on | Kernel coupling | Ships as |
|---|---|---|---|---|
| **library** | root `:` | `api kernel-md-facade`; `implementation` Jackson (databind + yaml) | **public kernel only** | the engine: `dm.core` + `dm.rulekit` + `dm.modelkit` + `dm.edit` |
| **adapter** | `:adapter` | `api project(':')` + the kernel **internal-read** jars + the **runtime-eval** jars; `testImplementation project(':interpreter')` | the `a12internal` read tier + runtime evaluation, **confined to this module** | an opt-in read/convert/rewrite/evaluate surface (`KernelAdapter`) |
| **interpreter** | `:interpreter` (Kotlin Multiplatform: jvm + js) | **nothing** (no kernel, no dmtool — purity-lint-enforced) | **none** — kernel-free by construction | a native-safe, cross-platform runtime evaluator (`DmInterpreter`: `validateFull`/`testRule`/`compute`); the publishable standalone artifact (in build-out — [INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) / [INTERPRETER-BUILDOUT.md](INTERPRETER-BUILDOUT.md)) |
| **cli** | `:cli` (`application` + GraalVM `native` plugins) | `implementation project(':')` + `project(':adapter')` + picocli + Jackson | inherits the adapter's (bundled *inside* the binary, never a library surface) | the standalone `dmtool` front-end (JVM `installDist`; opt-in native image) |

The **`:interpreter`** is deliberately a leaf with **zero** dependencies: it interprets the rule AST directly (no Groovy codegen), so it runs in a GraalVM native image *and* in the browser from one Kotlin source. Its correctness is pinned by **kernel-differential tests that live in `:adapter`** (`…/adapter/laws/*DiffTest.kt`, consuming `:interpreter` in test scope) — keeping the module kernel-free even in test scope, which is what makes it cleanly publishable. The `verifyInterpreterPurity` lint fails the build on a forbidden import (`java.*`/`com.mgmtp.*`/`groovy.*` in `commonMain`). dmtool will consume it for the native-image runtime verbs (the Groovy ceiling, §5d).

```
            ┌──────────────────────────────────────────────────────────────┐
            │  :cli   application + native-image   (mainClass = CliMain)     │
            │         picocli command tree · ResultEnvelope · self-describe  │
            └───────────┬───────────────────────────────────┬──────────────┘
            implementation                          implementation
                        ▼                                    ▼
            ┌──────────────────────┐  api project(':')  ┌────────────────────┐
            │  :adapter            │───────────────────▶│  root  :           │
            │  a12internal read +  │                    │  dm.core/rulekit/  │
            │  runtime eval        │                    │  modelkit/edit     │
            └──────────┬───────────┘                    └─────────┬──────────┘
                       │ internal jars         testImpl   │       │ kernel-md-facade
                       │                ┌─ ─ ─ ─ ─ ─ ─ ─ ─ ┘ public│
                       ▼                ▼ (differential tests)     ▼
            ┌──────────────────────┐  ┌────────────────────────────────────┐
            │  A12 kernel          │  │  :interpreter  (KMP jvm+js)          │
            │  @ mavenLocal        │  │  kernel-free runtime evaluator       │
            │  (a12-kernel-        │  │  DmInterpreter · purity-lint-guarded │
            │   bootstrap)         │  └────────────────────────────────────┘
            └──────────────────────┘
```

- `./gradlew :test` — the library (core + rulekit + modelkit + edit). No internal kernel jar on the classpath, so the D15 boundary is build-checked by construction; also runs `SiblingBoundaryTest`.
- `./gradlew :interpreter:check` — the kernel-free evaluator on **both** targets (`jvmTest` + `jsNodeTest`) + the `verifyInterpreterPurity` lint.
- `./gradlew :adapter:test` — the adapter (the only suite with the internal tier) + the interpreter↔kernel differential tests.
- `./gradlew :cli:test` — the CLI (incl. the conformance matrix and the N6 gold oracle over `eval/tasks/`).
- `./gradlew test` — everything. Always build through [`./build.sh`](../build.sh) (isolated `GRADLE_USER_HOME` + JDK 21) so the machine's global Gradle config can't contaminate the build.

**Kernel coupling — three tiers (N8/D15).** This is the central architectural constraint:

| Tier | Who | Allowed |
|---|---|---|
| **public only** | the library (`:`) | `IDocumentModel`, `checkConsistency`, the public model API + serializer (`kernel-md-facade`). No internal jar on the classpath. |
| **internal read, guarded** | `:adapter` only | the `a12internal` *read* AST (`parsetree-api` `ICondition`, `tool-api` `IRule`/`IEC`, `md-model` `DocumentModelService`) + runtime evaluation — reached in one call from the md-tier model, **converted back to dmkits types at the boundary**. Never constructs kernel AST (read-only). |
| **internal allowed (tests)** | any test | fixtures/oracles may read the internal tier, provided nothing internal leaks into `src/main` or a public signature. |

---

## 3. The library

The root project is four package groups: a shared `dm.core`, the two sibling kits (`dm.rulekit`, `dm.modelkit`), and the `dm.edit` orchestration above them.

### 3.1 `dm.core` — the shared substrate (9 files)

The DSL-free foundation both kits stand on. No rulekit or modelkit dependency.

| Class | Role |
|---|---|
| `ModelIndex` | the kernel-expanded model index — group tree, field kinds, number scale, repeatability, enum domains |
| `ModelLoader` | binds DM-JSON → a kernel `IDocumentModel` (via the public facade), the entry to the index |
| `ModelResolver` / `ReferenceResolvers` | include resolution — the `-w` workspace / reference-resolver seam |
| `DmJson` | the generic, DSL-free DM-JSON **write** primitive (Jackson tree surgery, include-preserving) — shared by both kits |
| `ModelConsistencyException` | the one type a kernel rejection (load-time or write-time) surfaces as |
| `ValueKind`, `Suggest`, `ModelFormat` | value-type taxonomy, did-you-mean suggestions, the DM-JSON format-version reference |

### 3.2 `dm.rulekit` — the rule & computation DSL

The typed condition/computation language: author, render (EN/DE), read back, validate against the real kernel. Six sub-packages, strictly acyclic (verified by import sweep); `dsl` is the hub.

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
| `write` | `dsl` | the rule-aware DM-JSON write path (`ModelWriter`) over `core.DmJson` |

**Why `dsl` is one flat package of ~105 files (and stays flat).** Its spine is **sealed** — `Condition` and `Calculation` are `sealed interface`s with dozens of permitted subtypes each, plus a sealed operand hierarchy. The project has **no `module-info.java`**, so everything compiles into the *unnamed module*, and the JLS requires every permitted subtype of a sealed type to share the **same package** as its parent there. Sub-packaging (e.g. `dsl.conditions`) therefore wouldn't compile; the only escapes are adding a `module-info` (a heavy, risky change against the kernel/Groovy/native interplay for no navigability win) or dropping `sealed` (which forfeits the exhaustive-`switch` the renderer/visitors rely on — PROJECT-DESIGN §18.5). The package is one-class-per-file and nodes are named after the operators they model, so navigate by filename.

### 3.3 `dm.modelkit` — structure authoring & editing (28 files)

The structural peer of rulekit: build a model from scratch, and read/round-trip/edit its structure (fields, groups, type-defs, includes, doc config). Jackson tree surgery over `core.DmJson`, re-bound to the kernel gate.

| Class | Role |
|---|---|
| `DocumentModelDraft` | the from-scratch fluent builder (`Builder`/`GroupBuilder`/`TypeDefBuilder`/… nested; the field-kind builders extracted to their own files — `StringFieldBuilder`, `NumberFieldBuilder`, `EnumFieldBuilder`, `Date*FieldBuilder`, `CustomFieldBuilder`, …) |
| `ModelDocument` | read an existing model's structure + tree-surgery edits (add/modify/remove fields & groups, includes, `reprefixIds`, mount detection) |
| `FieldKind`, `FieldModification`, `FieldTypeSpec`, `MountOptions`, `AttachmentRule`, `IncludeLevel`, `DatePrecision` | the structural value types |

### 3.4 `dm.edit` — orchestration over both kits (8 files)

The **only** layer that imports both kits. `EditSession` threads one DM-JSON document through a sequence of structure + rule edits and validates **once** at the end (the read → ordered surgery → one terminal gate → write pattern the CLI `apply` verb exposes). It owns the `*Editor` family — `GroupEditor`, `FieldEditor`, `RuleEditor`, `ComputationEditor`, `ConfigEditor`, `IncludeEditor`, `TypeDefEditor` — the session-bound editor ergonomics (PROJECT-DESIGN D7 / API-GUIDE §5c).

```
   dm.edit.EditSession   ──imports──▶  dm.modelkit  (structure edits)
        │                ──imports──▶  dm.rulekit   (rule/computation edits)
        │                ──imports──▶  dm.core      (DmJson, index, the gate)
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
   │                       diagnostics · patterns · where-used · meta · …
   │
   ├── cli.model/ rule/ computation/ field/ group/ typedef/ include/ config/ workspace/
   │     each family = a parent command + its op subcommands + that family's spec
   │     (FieldSpec, ComputationSpec). Subcommands stay package-private; only the
   │     family roots + shared infra are public.
```

A family's parent and subcommands live together so the subcommands stay package-private; only the family roots and the cross-package shared infra are `public`. (Widening is harmless — `:cli` is a leaf nobody links against. Unlike the sealed `dsl` package, nothing *forces* the CLI flat; the families just make it navigable.) The mirroring test sources live in the same family sub-packages; the cross-cutting/conformance tests stay at the test root.

> The **live verb list is not enumerated here** — that's exactly the kind of fact that rots. It's derived from the picocli tree and served by `dmtool manifest`; see CLI-SPEC §5 for the surface.

### 5b. picocli — declarative parsing, and the manifest derives from the tree

One `@Command`/`@Option(description=…)` declaration per verb drives **parsing, `--help`, *and* the machine-readable `manifest`** — so help and the manifest cannot drift from behavior (they recompute from the same command tree). `BaseCommand` is the shared superclass: it resolves the `-m` model (the `WorkspaceOption` mixin), owns the `fail(...)`/`refuse(...)` paths, and every verb returns one **`ResultEnvelope`** (`{ok, valid, outcome, data, diagnostics, written, …}`, §3b of the spec) with exit codes keyed off `ok`/`valid`. A single `setExecutionExceptionHandler` seam in `Cli` maps an escaped `ModelConsistencyException` (an invalid-on-disk load) to a structured `rejected` envelope, so no verb leaks a stack trace.

```
  argv ─▶ picocli parse (RootCommand → family → op) ─▶ <Op>Command.call()
                                                          │ resolve -m / -w (WorkspaceOption)
                                                          │ load via dm.core / edit / adapter
                                                          │ mutate / read / validate (the kernel gate)
                                                          ▼
                                                   ResultEnvelope ──▶ JSON (or --text) on stdout, exit code
```

### 5c. The self-describing surface + conformance guards

The CLI is built to be **discoverable by a cold agent from the binary alone** (no skill): `manifest` (the whole verb tree + per-verb params + schema pointers), `schema <target> <op>` (I/O contracts, derived from the live tree), `operators`/`patterns` (DSL vocabulary + idioms), `diagnostics <CODE>` (the `RK_*` catalog). Because these are *derived*, drift is caught at build by the conformance machinery: `ManifestVerbTest`, `SchemaVerbTest`, `SchemaExamplesGuardTest` (every schema `examples` block is **executed through the tool**), `CliConformanceMatrixTest`, `CommandReferencesResolveTest`. Detail: [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md).

### 5d. Native image (GraalVM) — and its complications

The release artifact is a **GraalVM native image** (`:cli:nativeCompile`, the `org.graalvm.buildtools.native` plugin; the JVM `installDist` stays the dev/eval vehicle). Three complications, each handled:

| Complication | Why | Handling |
|---|---|---|
| **The Groovy wall** | the kernel's runtime *evaluation* (`checkConsistency`'s message interpolation, rule firing) does Groovy runtime codegen — not AOT-compilable | `model eval` / `rule test` / `model compute` are **hidden from the native binary** (`Cli.JVM_ONLY_VERBS` + `Cli.hideJvmOnlyVerbs` strip them from the command tree; locked by `NativeImageVerbHidingTest`), absent from every self-describing surface; Groovy is excluded from `nativeImageClasspath` (JVM keeps it) |
| **Reflection** | Jackson-deserialized POJOs, the operator catalog, the kernel's reflective use | **test-suite-driven** reflection metadata capture — [`scripts/capture-native-config.sh`](../scripts/capture-native-config.sh) runs `:cli:test` under the tracing agent, so any new test that exercises a new shape extends the bundled `reachability-metadata.json` automatically |
| **Resources** | StringTemplate/i18n bundles the kernel loads at runtime | bundled into the image via the native config |

**The capture is the fragile part** — stale metadata makes a probe measure a fiction. The rules (see [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §5.1 + CLAUDE.md): touched `:cli`/`src` source or tests since the last capture → **re-capture → rebuild → [`scripts/native-gate.sh`](../scripts/native-gate.sh)** before probing or shipping. The native gate verifies the binary byte-for-byte against the showboat demos (skipping the JVM-only ones) plus a no-crash battery, and is the trip-wire if a re-capture was forgotten.

### 5e. How the test harnesses exercise the CLI

Two distinct instruments drive `dmtool` end-to-end (both *outside* the JUnit suites):

| Harness | What it is | How it touches the CLI |
|---|---|---|
| [`eval/`](../eval/) (**N6**) | self-sufficiency benchmark — can a *cold* agent, given only the binary (± the skill), author/read rules? Scored, pass@k. | a clean subagent runs the **JVM `installDist`** `dmtool` against a sandbox fixture tree; Layer-1 gold oracle runs in `:cli:test` (deterministic, over `RuleValidator`); Layer-2 = a Workflow pass@k over clean agents. Method: [`EVAL-SPEC.md`](EVAL-SPEC.md). |
| [`probe/`](../probe/) | proactive DX analysis of the **shipped product** — a real agent over a multi-prompt session, to find DX defects before a bug report | a general-purpose subagent drives the **native binary** through a logging `dmtool` shim (captures every command + stdout + stderr + exit); the analysis reads that objective log + the resulting model files. An anti-cheat audit gates each run. Method: [`PROBE-SPEC.md`](PROBE-SPEC.md). |

The split is deliberate: the **eval** runs the JVM build (fast, all verbs, scored self-sufficiency); the **probe** runs the *native* binary (the actual shipped artifact, so native-only bugs are visible) over realistic sessions. Both treat the CLI as a black box — they exercise the same self-describing surface a real agent sees.

---

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
