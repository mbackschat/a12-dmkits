# a12-dmkits — architecture (physical view)

The **concrete** structure: the Gradle subprojects, the core's internal package layering, what couples to the kernel and how, and what crosses each boundary as an artifact. This complements the **conceptual** component view in [`PROJECT-PROPOSAL.md` §15](PROJECT-PROPOSAL.md) (the six components — catalog spine, model index, typed AST, kernel bridge, write path, integration/LLM artifacts) — read that for *why*; read this for *where the code lives and what it ships*. The per-feature status board stays in [`API-POC-STUDY.md` §9](API-POC-STUDY.md).

## 1. Subprojects & artifacts

Gradle units (core + adapter) plus the CLI — the standalone front-end — and the skill (teaching material). Widening rings of kernel coupling.

| Unit | Gradle | Depends on | Kernel coupling | Ships as | Status |
|---|---|---|---|---|---|
| **core + rulekit** | root project (`:`) | public kernel only | `kernel-md-facade` + public model API + serializer — **must not** touch `kernel-md-model`/`kernel-md-a12internal` (D15, enforced) | the library: typed builder, renderer, validator, diagnostics, context projections, DM-JSON write path (`dm.core` + `dm.rulekit.*`; the core↔rulekit Gradle split stays deferred) | Phase 1 ✅ |
| **modelkit** | `:modelkit` subproject | **none** — Jackson only (no kernel, no core/rulekit) | none | the standalone structure builder (`DocumentModelDraft`); compiler-enforces structure⊥dsl (D10). Test-only consumer of `:` (tests share fixtures + the kernel oracle) | Phase 3 — M1–M6 ✅, extracted 2026-06-06 |
| **adapter** | `:adapter` subproject | `api project(':')` + kernel internal-read modules | the `a12internal` **read** tier (`parsetree-api`, `tool-api`, `DocumentModelService`) under N8 guardrails | opt-in Java-only module: ingest, explain-support, whereUsed, diff helper | Phase 2, bootstrapped ✅ |
| **cli** | `:cli` subproject (`application` plugin) | core now (**+ adapter** as ingest/explain verbs land) | inherits adapter's once added (bundled inside the binary, not a library surface) | the **standalone, zero-toolchain front-end for the modeller/BA (N10)** — verbs + JSON in/out; self-contained app-image (JVM bundled). Detail: [`CLI-SPEC.md`](CLI-SPEC.md) | Phase 2 — `dump`/`describe`/`validate`/`check`/`add` ✅ |
| **skill** | *(no module)* | — | — | the N1 Claude Code skill (`skill/SKILL.md`): teaching material for **both** persona loops (dev in-repo; BA via CLI) + the `MVK_*` fix table; read by the agent, not a code dep | Phase 2 — first draft ✅ |

`settings.gradle` includes `adapter` and `cli`. `./gradlew :test` runs the core in isolation (no internal tier on the classpath — the D15 boundary is build-checked by construction); `./gradlew :adapter:test` runs the adapter; `./gradlew :cli:test` runs the CLI (incl. the N6 gold-oracle over `eval/tasks/`); `./gradlew test` runs all.

**There is no separate *developer* deployment unit (N10).** A developer uses rulekit as a library inside their own project; Claude Code edits that project's source and the project's own compiler supplies the type-steering. The only standalone front-end is the **CLI**, for the modeller/BA who has no Java toolchain.

### Subproject layering (today)

How the Gradle units stack, what each depends on, and where the kernel coupling sits. Arrows = "depends on". Widening kernel coupling top-to-bottom.

```
  front-ends            ┌──────────────────────────┐   ┌──────────────────────────────┐
  (who drives it)       │  :cli  (application)      │   │  Claude Code in the dev's    │
                        │  verbs: dump describe     │   │  OWN project (rulekit as a   │
                        │  validate check add       │   │  library dependency)         │
                        └───────┬──────────────────┘   └───────────────┬──────────────┘
                                │ implementation               depends on │
        skill/SKILL.md ┄┄┄┄┄┄┄┄┄┤ (+ :adapter — powers                    │
        (read by agent,         │  ingest/explain)                        │
         not a code dep)        ▼                                         ▼
                        ┌──────────────┐   api    ┌───────────────────────────────────┐
                        │  :adapter    │─────────▶│  core  (root project ` : `)        │
   opt-in internal tier │  a12internal │          │  catalog · model · validate · dsl  │
                        │  READ        │          │  · lint · context · write (DmJson) │
                        └──────┬───────┘          └─────────────────┬─────────────────┘
                               │ internal-read                       │ public OSS only (D9/D15)
                               ▼                                     ▼
                        ┌─────────────────────────────────────────────────────────────┐
                        │  A12 kernel @ mavenLocal                                      │
                        │  public: kernel-md-facade (expand/checkConsistency/serialize) │
                        │  a12internal: parsetree-api · tool-api · DocumentModelService │
                        └─────────────────────────────────────────────────────────────┘

  eval/ (benchmark corpus + runner): tasks/*.json (data) ; Layer-1 gold oracle runs in :cli:test
  over core's RuleValidator ; Layer-2 runner (Workflow, planned) drives clean agents through :cli.
```

Key reads: **core never sees the internal tier** (D15 — enforced by classpath); the **adapter** is the only unit that crosses into `a12internal`, and it returns core types (boundary conversion, N8); **cli** bundles whatever it depends on into the app-image (it's an *app*, not a published library, so pulling `:adapter` later is fine); **skill** is teaching material the *agent* reads — not a build dependency.

### Scenarios & consumers (who uses what)

The same engine, three consumer paths:

```
 (A) DEVELOPER            Claude Code ──edits──▶ dev's project ──depends on──▶ core ──▶ public kernel
     (in-repo library)    feedback = the project's own javac + RuleValidator diagnostics.
                          No CLI, no adapter (unless ingesting foreign rules). N10 "no bespoke vehicle".

 (B) MODELLER / BA        BA's agent ──runs──▶ :cli launcher ──▶ core (+ :adapter for ingest/explain)
     (standalone CLI)        describe/dump → orient   check → kernel verdict   add → persist DM-JSON
                          feedback = kernel Diagnostics (runtime). JVM bundled & invisible (N10).

 (C) EVAL / N6            clean subagent ──reads──▶ skill/SKILL.md + catalog ; ──runs──▶ :cli ──▶ core ──▶ kernel
     (self-sufficiency)   measures whether the shipped material suffices.   Layer-1 gold oracle = :cli:test
                          (deterministic, core RuleValidator) ; Layer-2 = Workflow pass@k over clean agents.

 (future) TS TWIN         a parallel impl builds the same model index from the same DM-JSON + catalog —
                          possible only because core stays public-only & data-out (§3/§4). Design-informing only.
```


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
| **F10 long tail** — the residual unmodeled constructs (e.g. `ValueNotConsistent`, the multi-form/Vordruck root-group conditions, `MaximalAngegebenerZeilenindex`, `AktuelleVordruckNummer`) | **`adapter.ConditionConverter`** | each unmodeled construct stays a tolerant `Ingest.Opaque` until covered |
| **CI version matrix** (F11 carry-forward) — second-kernel canary | **build infra** (`adapter`'s pin+canary) | triggers when a 2nd kernel publishes |

The CLI's heavy costs are **distribution, not code**: self-contained packaging and code-signing/notarization (macOS Gatekeeper / Windows SmartScreen on a downloaded binary). Two viable packaging paths (decided in [`CLI-SPEC.md`](CLI-SPEC.md) §B1/§B2 after the 2026-06-05 native-image spike): **B1 — jlink/jpackage app-image** (~JRE-sized) **ships first**, zero AOT risk; **B2 — GraalVM native-image** is **proven feasible** for rulekit's `checkConsistency`/parse verbs (spike: byte-identical output, ~37 ms startup vs ~600 ms JVM, 36 MB) and is the startup-win **upgrade**, gated on bundling the kernel's StringTemplate resources and never crossing into the kernel's validation-*execution* path (the one AOT-hostile boundary — Groovy runtime compilation; rulekit never uses it). Root-cause in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) ("`checkConsistency` runs runtime code generation"). **Licensing is settled** ([`LICENSING.md`](LICENSING.md)): the kernel LICENSE grants redistribution, so bundling is permitted; the bundled distributable ships under **EUPL-1.2** (notices + source pointer) while rulekit source stays MIT — no longer an open gate, just an EUPL-compliance obligation on the artifact. A JVM-resident fat jar proves the verb surface first; self-contained packaging follows.

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
 <repo: a12-dmkits   ·   package root: io.github.mbackschat.a12.dm>
  ├── :core      (dm.core)      index/read · GENERIC dsl-free DM-JSON write · validate · diagnostics   ← shared base
  ├── :rulekit   (dm.rulekit)   catalog · AST/dsl · rule builders            ── over :core
  ├── :modelkit  (dm.modelkit)  Field/Group/TypeDef builders (structure)     ── standalone: Jackson only (no core, no rulekit) ✅ split out
  ├── :adapter   (dm.adapter)   internal-tier read — serves the kits
  └── :cli       (dm.cli) · skill   front-ends spanning the kits
```

In today's package graph (§2) this means `modelkit` sits **beside `dsl`**, both over `model`/`validate`/`write` — `modelkit` must not depend on `dsl`. **The `:modelkit` module now exists (M1–M6; extracted 2026-06-06):** `DocumentModelDraft` authors a model from scratch and emits DM-JSON, kernel-validated through both gates — Jackson-only, zero kernel/`dsl` coupling, and that boundary is now **compiler-enforced** by the separate module (its main can't import `dm.core`/`dm.rulekit`). Detail: [`MODELKIT-PROPOSAL.md`](MODELKIT-PROPOSAL.md) §H.

**The one prep that enables it — ✅ done 2026-06-05:** the generic, `dsl`-free half is now `write.DmJson` (`insertIntoGroup`/`groupElements`/`findElement`, include-preserving), and `write.ModelWriter` (the `Rule`-node knowledge) builds on it. modelkit's structural write reuses `DmJson` directly. Still TBD: **source-artifact provenance** on the index (mirror of D14) so include-aware editing later writes to the owning artifact.

**Name — decided 2026-06-06:** repo **`a12-dmkits`** (plural: a collection of kits for the document model); Maven group **`io.github.mbackschat.a12.dm`**, artifacts `a12-dmkits-{core,rulekit,modelkit,adapter,cli}`; Java **package root `io.github.mbackschat.a12.dm`** — deliberately short (it prefixes every package; the longer descriptive name lives in the repo/Maven id, à la `spring-framework`/`org.springframework`). Sub-packages map 1:1 to the future subprojects: **`dm.core`** (shared — named `core`, not `model`, to avoid the `dm.model`/`dm.modelkit` look-alike) · **`dm.rulekit`** (catalog/dsl/context/lint/validate-`RuleValidator`/write-`ModelWriter`) · **`dm.modelkit`** (structure) · **`dm.adapter`** · **`dm.cli`**. The kits keep their names — no concept rename. `dm` = document model (the project's own vocabulary, DM-JSON).

**Rename trigger & timing (D10):** the trigger fired — modelkit is a real peer (M1–M6), so the rename to **`a12-dmkits`** is underway. **Done (2026-06-06):** the Java package root (`io.github.mbackschat.a12.dm.*`), the Maven group (`io.github.mbackschat.a12.dm`), the Gradle `rootProject.name` (`a12-dmkits`), the CI checkout dir, and all in-repo doc/text references. **Remaining (owner action — external, deferred):** the GitHub repo rename + the local on-disk directory rename, plus any sibling-clone references in `a12-kernel-bootstrap`. The in-repo config is name-independent of those (CI's `path:` is the runner's own checkout dir, not the GitHub repo name; the build reads the *parent* dir, not its own name), so nothing breaks in the window before the external rename. The layout **pre-stages the module split** (each `dm.<x>` = a future subproject). **`:modelkit` is now split out** (2026-06-06 — its main is Jackson-standalone, the cleanest boundary); the `:core`/`:rulekit` split stays deferred (tightly coupled, most-shared test fixtures — low boundary value).

## 6. Maintenance

When the kernel changes, the coupling map in [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md) names each guardian test, what its failure means, and the realignment. The D15 boundary (core has no internal modules on its classpath) and the N8 guardrails (§3) are what confine upgrade risk to the opt-in adapter. Keep this doc current when a subproject is added or the package layering changes.
