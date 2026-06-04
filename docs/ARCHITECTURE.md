# a12-rulekit — architecture (physical view)

The **concrete** structure: the Gradle subprojects, the core's internal package layering, what couples to the kernel and how, and what crosses each boundary as an artifact. This complements the **conceptual** component view in [`PROJECT-PROPOSAL.md` §15](PROJECT-PROPOSAL.md) (the six components — catalog spine, model index, typed AST, kernel bridge, write path, integration/LLM artifacts) — read that for *why*; read this for *where the code lives and what it ships*. The per-feature status board stays in [`API-POC-STUDY.md` §9](API-POC-STUDY.md).

## 1. Subprojects & artifacts

Gradle units (core + adapter) plus the CLI — the standalone front-end — and the skill (teaching material). Widening rings of kernel coupling.

| Unit | Gradle | Depends on | Kernel coupling | Ships as | Status |
|---|---|---|---|---|---|
| **core** | root project (`:`) | public kernel only | `kernel-md-facade` + public model API + serializer — **must not** touch `kernel-md-model`/`kernel-md-a12internal` (D15, enforced) | the library: typed builder, renderer, validator, diagnostics, context projections, DM-JSON write path | Phase 1 ✅ |
| **adapter** | `:adapter` subproject | `api project(':')` + kernel internal-read modules | the `a12internal` **read** tier (`parsetree-api`, `tool-api`, `DocumentModelService`) under N8 guardrails | opt-in Java-only module: ingest, explain-support, whereUsed, diff helper | Phase 2, bootstrapped ✅ |
| **cli** | `:cli` subproject (`application` plugin) | core now (**+ adapter** as ingest/explain verbs land) | inherits adapter's once added (bundled inside the binary, not a library surface) | the **standalone, zero-toolchain front-end for the modeller/BA (N10)** — verbs + JSON in/out; self-contained app-image (JVM bundled). Detail: [`CLI-SPEC.md`](CLI-SPEC.md) | Phase 2, `dump` verb scaffolded |
| **skill** | *(no module)* | — | — | the N1 Claude Code skill: teaching material for **both** persona loops (dev in-repo; BA via CLI) + the `MVK_*` fix table | Phase 2, planned |

`settings.gradle` includes only `adapter` today. `./gradlew :test` runs the core in isolation (no internal tier on the classpath — the D15 boundary is build-checked by construction); `./gradlew :adapter:test` runs the adapter; `./gradlew test` runs both.

**There is no separate *developer* deployment unit (N10).** A developer uses rulekit as a library inside their own project; Claude Code edits that project's source and the project's own compiler supplies the type-steering. The only standalone front-end is the **CLI**, for the modeller/BA who has no Java toolchain.

**Decision (2026-06-04, superseding the earlier "ship-with-skill" framing): the file/JSONL dump emitter is a CLI verb, not core and not a separate JBang script.** The agent-facing artifacts (finding 13 — `fields.jsonl`/`groups.jsonl`/`rules.jsonl`/`model-card.md`) are emitted by the CLI (`rulekit dump model.json fields`), which consumes core's public projections (`m.fields()`, `m.groups()`, `ModelContext`/`ModelCard`) and serializes them. The serialization/IO concern stays out of the core library; core exposes the programmatic projections (also directly usable by an in-repo developer), the CLI renders them to files. *(The standalone-vehicle question collapsed to a single answer — the CLI — once we recognized developers don't need a bespoke vehicle, N10; that supersedes the brief skill-tools/JBang-script home.)*

## 2. Core package layering

Eight packages, strictly acyclic (verified by import sweep). Bottom = no rulekit dependencies; each layer only depends downward.

```
            ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
  consumers │   lint       │  │   context    │  │   write      │
            │ Linter,      │  │ ModelContext │  │ ModelWriter  │
            │ RuleDiagnos- │  │ describe/    │  │ (F6 DM-JSON  │
            │ tics (F4a)   │  │ find/explain │  │  write path) │
            └──────┬───────┘  │ (F9/F9a)     │  └──────┬───────┘
                   │          └──────┬───────┘         │
                   │                 │                 │
            ┌──────┴─────────────────┴─────────────────┴───────┐
   heart    │   dsl  (59 files) — typed AST + handles + renderer │
            │   Condition/Calculation, BoundModel, DslRenderer,  │
            │   Rule/Computation builders, RenderedDiff (F8)      │
            └──────┬───────────────────────────────┬────────────┘
                   │                                │
            ┌──────┴───────┐                        │
   ground   │  validate    │                        │
   truth    │ RuleValidator│                        │
            │ Diagnostic,  │                        │
            │ KernelVersion│                        │
            │ (F4/F4a/F11) │                        │
            └──────┬───────┘                        │
                   │                                │
            ┌──────┴────────────┐      ┌────────────┴───────────┐
  founda-   │   catalog         │      │   model                │
  tions     │ OperatorCatalog   │      │ ModelIndex, ModelLoader │
            │ (bilingual spine, │      │ (kernel-expanded index, │
            │  self-verifying)  │      │  kinds/scale/repeat)    │
            └───────────────────┘      └────────────────────────┘
```

Exact edges (each package → the rulekit packages it imports):

| Package | Imports | Role |
|---|---|---|
| `catalog` | — | the language-neutral operator spine: ids, EN/DE keywords, kinds, meanings, gotchas; self-verifying (N7) |
| `model` | — | the kernel-expanded model index: group tree, field kinds, number scale, repeatability, enum domains |
| `validate` | `catalog`, `model` | ground-truth validation against the real kernel + the LSP-shaped `Diagnostic`; `KernelVersion` (F11) |
| `dsl` | `catalog`, `model`, `validate` | the typed sealed AST, model-bound handles, the catalog-driven EN/DE renderer, the authoring builders, `RenderedDiff` |
| `lint` | `catalog`, `dsl`, `validate` | the AST lint layer + `RuleDiagnostics`, the unifying aggregator across all diagnostic origins |
| `context` | `catalog`, `dsl`, `model` | the F9/F9a projections: `describe`/`find`/`explain` → `ModelCard`/`FieldMatch`/`RuleExplanation` |
| `write` | `dsl` | the F6 DM-JSON write path (Jackson tree surgery), the only shipped write surface (D9) |

`dsl` is the hub; `lint`/`context`/`write` are independent consumers of it (no edges between them). `catalog` and `model` never depend on anything rulekit, so the spine and the index are reusable foundations.

## 3. The kernel-coupling boundary (N8 / D15)

Three tiers of access to the kernel API, per proposal §N8:

- **Core — public only.** Binds `IDocumentModel`, `checkConsistency`, the public model API and serializer. The compile-time guarantee: the core classpath has no `a12internal` module on it, so the boundary can't be crossed by accident. This is what keeps the core polyglot-ready (a future TS twin builds the *same* index from the *same* DM-JSON content).
- **Tests — internal allowed.** Fixtures, oracles, and parity sources may read the internal tier, provided nothing internal leaks into `src/main` or a public signature.
- **Adapter — internal read, guarded.** The `a12internal` read tier (the kernel's own AST: `parsetree-api` conditions/operations, `tool-api` `IRule`/`IEC`, `DocumentModelService`) reachable in one call from the md-tier model. Guardrails: **boundary conversion** (kernel types never escape a public signature — `KernelAdapter` returns rulekit `Condition`/`Ingest`/`String`, never an `ICondition`), **read-only** (no kernel-AST *construction*, ever — D15), **pin + CI canary** (the `a12internal` modules carry no stability guarantee, so a bump fails loudly), **fail-fast coverage** (an unmodeled construct throws inside the converter and surfaces as a tolerant `Ingest.Opaque`, never a silent miss).

The kernel's `ICondition` tree is the adapter's **transport format**, never the representation users or LLMs touch — all work happens on rulekit's own AST (§N8, "not a workbench over the kernel's AST").

## 4. What crosses each boundary (the "out" surface)

rulekit deliberately emits **plain data**, never kernel objects — so every artifact is polyglot-safe and agent-consumable:

| Artifact | Type | Produced by | Boundary |
|---|---|---|---|
| rendered `errorCondition` | `String` (EN/DE) | `dsl.DslRenderer` | core → kernel (validation) / out |
| model DM-JSON | `String` | `dsl.BoundModel.toJson()` / `write.ModelWriter` | core → disk/kernel |
| `Diagnostic` | record (LSP-shaped, JSON) | `validate` / `lint.RuleDiagnostics` | core → caller/LLM |
| `RuleExplanation` | record (JSON) | `context.ModelContext.explain` | core → caller/LLM |
| `RenderedDiff` | record (unified text / JSON) | `dsl.RenderedDiff` / `adapter.KernelAdapter.diff` | core/adapter → caller |
| `Ingest` (`Converted`/`Opaque`) | sealed record | `adapter.KernelAdapter.ingest` | adapter → core types |
| `ModelCard` / context JSONL | JSON / JSONL / markdown | `context` (programmatic) → CLI (files) | core → CLI → agent |

## 5. Proposed components (TBD)

Everything below is **not yet built** — it's laid out here so the home of each proposed part is decided in advance and the boundaries above (§3 public-only core, §4 data-only out-surface) are honored when it lands. Status flips happen on the [`API-POC-STUDY.md` §9](API-POC-STUDY.md) board, not here; this section says *where the code goes*, not *when*. Nothing in this section is a commitment to a date.

### 5.1 Near-term (Phase 2)

| Proposed part | TBD — home | Boundary note |
|---|---|---|
| **CLI** — the standalone, zero-toolchain front-end for the modeller/BA (N10); verbs + JSON in/out, JVM bundled | **new `:cli` subproject** (core + adapter) | JSON-in deserializes *through the typed builder*; never shells out to `javac`; kernel stays bundled-inside, not a library surface |
| **JSON rule-spec + deserializer** — the language-neutral authoring contract the CLI accepts | **`cli`** (schema) → **core** typed builder (compile) | the BA's "type safety" = schema validation + builder pre-checks + kernel `Diagnostic`s (F4a) |
| **JSONL/file dump emitter** — `fields.jsonl`/`groups.jsonl`/`rules.jsonl`/`model-card.md` (finding 13) | **CLI verb** (`rulekit dump …`, decided 2026-06-04, §1) | serializes `context` projections + a DM-JSON rule walk; **stays out of core** |
| **N1 skill** — teaches *both* persona loops (dev in-repo via the library; BA via the CLI) + the `MVK_*` fix table | **skill** (no module; Claude Code skill bundle) | teaching material only; the runnable engine is core/adapter/CLI |
| **`whereUsed` exact resolver** (F9a) | **API in core `context`** (pluggable reference-resolver seam) + **exact resolver in `adapter`** (`getDirectlyReferencedEntities`) | core ships a labeled heuristic today; the adapter contributes the exact one — N8-clean |
| **`IProblem → Diagnostic`** — fold the adapter's discarded kernel problems into F4a | **`adapter`** (produces) reusing **core `validate.Diagnostic`** (the type) | adapter emits core `Diagnostic` records, not kernel `IProblem` — boundary conversion |
| **F9 rule-envelope enrichment** — error-field role labels, severity, `referencesIteration` cross-check | **core `context`** (the projection) + **`adapter`** (the kernel cross-check oracles) | enrichment data crosses as plain record fields |
| **F10 long tail** — `CustomCondition`, operand wrappers (`Length`/`Round`/`Abs`), count-of-filled, tolerance comparisons, `Now`/`BaseYear`/`AddDays` | **`adapter.ConditionConverter`** | each unmodeled construct stays a tolerant `Ingest.Opaque` until covered |
| **CI version matrix** (F11 carry-forward) — second-kernel canary | **build infra** (`adapter`'s pin+canary) | triggers when a 2nd kernel publishes |

The CLI's heavy costs are **distribution, not code**: self-contained packaging (jlink/jpackage app-image, ~JRE-sized — *not* GraalVM native-image, which the kernel's reflection/service-loading would make fragile), and code-signing/notarization (macOS Gatekeeper / Windows SmartScreen on a downloaded binary). **Licensing is settled** ([`LICENSING.md`](LICENSING.md)): the kernel LICENSE grants redistribution, so bundling is permitted; the bundled distributable ships under **EUPL-1.2** (notices + source pointer) while rulekit source stays MIT — no longer an open gate, just an EUPL-compliance obligation on the artifact. A JVM-resident fat jar proves the verb surface first; self-contained packaging follows.

> **Note (N10, 2026-06-04):** the earlier compile-first **JBang / `java --source 21`** "developer execution vehicle" is **not** a shipped part. A developer doesn't need it (Claude Code edits rulekit-the-library in their own project, with the project compiler steering); a BA can't use it (no toolchain → the CLI). It survives only as a possible internal mechanism for the **N6 self-sufficiency eval** harness.

### 5.2 Deferred / longer-horizon

| Proposed part | TBD — home | Note |
|---|---|---|
| **modelkit** (Phase 3, fork #8) — build/edit document-model *structure* (fields, groups, type-defs, includes) and save back, include-preserving | **`structure` layer parallel to `dsl`**, over the shared base — *not* on top of rulekit | uses public DM-JSON only (D9; no `betterbuilders`); the MM_Common metamodels are its schema oracle. See "the umbrella" below. |
| **Codegen refs** (Phase 3) — static navigation counterpart to `context` (generated schema handles) | **TBD: new `codegen` unit** | jOOQ's generated-schema analogue; the L2b static counterpart to runtime navigation |
| **TypeScript twin** (Phase 4, **design-informing only — do not propose implementation**) | **TBD: parallel implementation** | builds the same `model` index from the same DM-JSON content, shares the catalog JSON spine + DM-JSON write path. The public-only core boundary (§3) and data-only out-surface (§4) are precisely what keep this possible — do not let kernel types or internal-tier coupling leak into core, or the twin becomes impossible |

### The umbrella (D10 revisit, 2026-06-05) — parallel kits over one model-core

modelkit and rulekit are **parallel layers over a shared model-core**, not one atop the other. Target shape once modelkit ships as a peer:

```
 <umbrella: a12-kernel-kits>
  ├── model-core   (index/read · GENERIC dsl-free DM-JSON write · validate · diagnostics)   ← shared base
  ├── rulekit      (catalog · AST/dsl · rule builders)        ── over model-core
  ├── modelkit     (Field/Group/TypeDef/Include builders)     ── over model-core (does NOT use dsl)
  ├── adapter      (internal-tier read — serves both kits)
  └── cli · skill  (front-ends spanning all kits)
```

In today's package graph (§2) this means `structure` sits **beside `dsl`**, both over `model`/`validate`/`write` — `structure` must not depend on `dsl`.

**The one prep that enables it (do on the next `write` touch):** `write.ModelWriter` currently depends on `dsl` (it knows `Rule`). Split it: a **generic, `dsl`-free** half (`findElement`/`insertElement`/`removeElement`, include-preserving `toJson`, depending only on `model`) that both the rule write and the structural write build on; the `Rule`-node knowledge stays with the rule layer. Also add **source-artifact provenance** to the index (mirror of D14) so include-aware editing later writes to the owning artifact.

**Rename trigger & timing (D10):** keep `a12-rulekit` while rules-only (accurate today); rename the repo + move the package root to the umbrella **when modelkit becomes a real peer**. Because renames of Maven coordinates + the Java package root are *cheapest before first publish*, lock those in the pre-publish window even if the repo rename waits. Don't module-split prematurely — modelkit starts as a **package** (`structure`) in core (it crosses no kernel boundary, so it hasn't "earned" a module, §1); extract `:model-core` + `:modelkit` only when size/coupling justify it.

## 6. Maintenance

When the kernel changes, the coupling map in [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md) names each guardian test, what its failure means, and the realignment. The D15 boundary (core has no internal modules on its classpath) and the N8 guardrails (§3) are what confine upgrade risk to the opt-in adapter. Keep this doc current when a subproject is added or the package layering changes.
