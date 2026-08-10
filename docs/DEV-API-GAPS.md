# dmkits DSL-API (developer perspective) — gaps

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the first batch. **This ledger records only OPEN work** — like its sibling [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md), a shipped gap is **deleted**, not kept as a "shipped" list (git is the *what/when*; the gap's **API-FINDINGS finding** is the *why*). **Every implementable `DGn` gap has shipped** (DG1–DG15 and DG17–DG18; DG16 was declined as out of scope — §3) and so is gone from here; their durable *why* lives in the [API design findings](API-FINDINGS.md) (and API-GUIDE §5c for the editor-ergonomics pair DG11/DG12) — and because each finding back-references its `DGn`, a code comment citing `DEV-API-GAPS DGn` — or a legacy `DEV-API-GAPS §N` — resolves **by topic** to that finding, not to a section here (the closure record lives in **Current state.** below). The doc keeps the review's framing (§1), the open backlog (§2), and the out-of-scope decisions (§3).
>
> **Lifecycle.** §2 holds the live, unfinished work as a table. (Shipped gaps are **not** recorded here — a closed gap is no longer a gap; git is the history of *what/when*, its **API-FINDINGS finding** is the *why*, and DG1–DG14 all shipped this way — a code comment citing `DEV-API-GAPS DGn` resolves via the back-referencing finding, DG11/DG12 via API-GUIDE §5c.)
>
> **Current state.** The backlog is empty again as of 2026-08-10. DG31 surfaced and closed on 2026-08-10 while root-causing a consumer's category-erasure report: `CategoryRef` exposed its projection for two of its three roles (`listOperand()`, `firstFilledOperand()`) and kept the value-expression role private, so the adapter's computation/value-list/comparison sites had no typed way to rebuild one and approximated it with a plain `FieldValue`. Finding 119 records the additive `value()` accessor, the one shared converter helper it enabled, and the `verifyCategoryPreserved` backstop that keeps the next unthreaded position honest. DG30 surfaced and closed while correcting the raw `Length` feedback: `ModelIndex` could read String bounds and patterns but not the effective `noValueValidation` declaration, which forced a diagnostic consumer toward raw DM-JSON inspection or an unsafe inference from generic `MVK_INTERNAL_ERROR`. Finding 118 records the additive accessor and its contextual-diagnostic use. DG29, surfaced while enriching the interpreter showcase, shipped as finding 109: one sealed `ElementMetadataEditor` now gives held field, group, rule, and computation editors the complete annotation plus internal/external-description set/remove contract. DG28, surfaced while testing SPEC-2026-07-27-01's pre-1900 separator, shipped as finding 108: modelkit/CLI can now author and read A12's `DateType.youngerThan1900Check`. DG26's Number rendered-length/integer-digit declarations shipped as finding 104. DG27 was deleted as a false gap rather than implemented: KF63 proves `IStringType.isExtensibleEnum()` is client-SPI runtime state with no DM-JSON property, so modelkit must not invent one. Before that, two rows had been surfaced while reconciling the lean project's `SPEC-2026-07-27-*` Date/Time component-declaration entries. The four WI-T typed shapes (date `Min`/`Max`, indexed-cell presence, starred value-list, `RuleGroup`) and a computation's `errorCodesToSuppress` shipped as findings 58–61; the Dsl consistency batch shipped as findings 63–65; the cross-component interpreter review tail—typed indexed-construct read-back, bare-group fill catalog parity, multi-star correlation guidance, `RepetitionNotUnique` authoring semantics, and atomic combined-grapheme charset authoring—shipped as findings 74–78; DG18 completed typed TIME/DATETIME operand-list `Min`/`Max` plus DATE/TIME/DATETIME adapter read-back as finding 92; the 2026-07-23 00:21 CEST SPEC-13 audit surfaced and closed DG19, the missing typed `FieldValueAsNumber(Enum -> Category)` source/read shape, as finding 93; the 2026-07-23 01:15 CEST SPEC-04 correction surfaced and closed DG20, `Abs` losing the numeric call-site role of polymorphic `BaseYear`, as finding 94; DG21 — `Dsl.numberOfValueInFields` unable to author the kernel-legal single-list form — shipped as finding 97 (the `value × FieldListRef` overloads); and interpreter workspace dogfooding on 2026-07-26 surfaced and closed DG22, the draft gate overlooking include-contributed typedefs, as finding 99, DG23, group `usageType` being inaccessible through typed navigation, as finding 100, and DG24, typed rule/computation writes always rendering English even for a German-language destination model, as finding 101. (The briefly queued DG18 row accidentally reused the already-shipped DG15 semantic-index identifier; finding 92 is the durable correction.)
>
> Interpreter R2.8 differential dogfooding later the same day surfaced and closed DG25: relative rendering attempted to cross a root-group boundary; finding 102 records the absolute cross-root path contract.
>
> A row lands here only when a new developer-facing authoring friction is both surfaced and queued.
>
> **Scope — the filter that matters.** dmkits has **model-authoring scope**: it defines, edits, validates, reads, and tests a document **model** — its fields/groups/type-defs/includes/config and its **rules + computations** (the condition-language DSL). It is **not** a runtime form engine, a document-instance data-binding layer, a rendering/UI toolkit, or a persistence/service layer. The kernel dev doc is ~80% about the *runtime consumer* (manipulating document *instances*); those concerns are deliberately **out of scope** (§3).

---

## 1. The loop we're improving

A developer using rulekit/modelkit writes Java, so the **project's own compiler is the first safety net** — a wrong path, or a number operator on a date, fails before the kernel ever runs. The loop:

```
bind (BoundModel.ofJson) → author rules/computations via typed handles → validate (RuleDiagnostics.diagnose)
   → read foreign rules back (KernelAdapter.read, tolerant) → edit structure + rules in one session (EditSession)
   → test behaviour on sample documents (KernelAdapter.evaluate / testRule / compute)
```

dmkits already serves this well, and these strengths are the baseline the gaps built on:

- **Handles come off the model** — `m.num(path)`/`m.enumOf(path)`/`m.group(path)` fail at lookup on a wrong path or kind; the AST never holds a string a typo could corrupt.
- **Illegal states are unrepresentable** — Field ≠ Group types, no `ConfirmRef.isFalse`, enum/category values domain-checked at build, number `==`/`!=` scale-gated, wildcards erased from scalar slots.
- **Immutable records + a sealed AST** with `transform`, and **snapshot write semantics** (every write returns a *new* re-validated `BoundModel`).
- **Tolerant read** — `KernelAdapter.read` returns a sealed `ReadResult` (`Converted`/`Opaque`); an unmodeled construct is preserved verbatim, never lost.
- **One author-time front door for rules** — `RuleDiagnostics.diagnose` folds kernel + lint + gotcha + version-skew into a single `ValidationResult`.
- **Self-describing catalogs** — `OperatorCatalog`/`PatternCatalog` carry meaning, gotchas, and CI-executed examples.
- **The L2c editor/ref layer** and **runtime eval as an oracle** (`evaluate`/`compute`/`testRule`) close the read→edit→reference and prove-it-fires loops.

The gaps below were the **ergonomic and consistency remainder** — what added friction, forced a drop out of the typed surface, or surprised a developer expecting symmetry between siblings.

---

## 2. Open

_None open._

---

## 3. Adjacent but out of scope

The durable decisions about what dmkits deliberately does *not* do (its model-authoring scope — not a runtime form engine, instance-data layer, or UI):

- **Document-instance manipulation** (`DocumentV2` read/update/merge/traverse) — the runtime-consumer surface and ~80% of the dev doc. dmkits authors the *model*, not its instances; it runs instances only as a *test oracle* (`evaluate`/`compute`).
- **Typed-accessor / overlay code generation** over document instances — consumer-app codegen, not model authoring.
- **Mapping runtime** (model→model data-transfer execution) — the dev doc covers running generated mapping code; mapping *modeling* is a separate modeler concern.
- **Rendering / UI / data-binding / ResourceBundle locale plumbing** — presentation and runtime, never model content.
- **Custom-condition / custom-field-type *implementations*.** The model only *references* them (`CustomCondition <Name>` in a condition; a `Custom` field type), and dmkits already represents those references (`CustomConditionRef`; the `custom` field builder). The Java/TS validator impls are runtime — out. The `CustomCondition` operator catalog entry carries the author-facing callback-owned dependency, formal/relevance-pointer, empty-eligibility, and error-condition polarity gotchas; there is no remaining authoring-surface gap.
- **Model-version migration** — a distinct concern with the kernel's own `MigratorCli` / `IDocumentModelMigrator`; dmkits authors/edits and deliberately never bumps a model's version on write (same call as [`FEATURE-GAPS.md`](FEATURE-GAPS.md) §4). dmkits' job is to **detect** skew (`RK_MODEL_VERSION_SKEW` / `RK_MODEL_VERSION_INCOMPATIBLE`) and stay tolerant — not to migrate.
- **`variantFilter` authoring** (was DG16) — a field/rule/computation may carry a `variantFilter` (`{filterType: ONLY|ALL_EXCEPT, variants}`) that gates its per-variant membership (KERNEL-FINDINGS "variant filtering"). dmkits deliberately does **not author or interpret** it: it is a **pass-through** — an existing model that carries one has it **preserved verbatim** on a modelkit load→edit→save (the raw-JSON tree surgery never strips an unmodeled slot; the interpreter still *honors* it on load, IF36). *Decision 2026-07-06 (was briefly built as DG16, then rolled back): variant modeling is a modeler concern; the authoring surface would need to span field/rule/computation × draft/edit/serializer for no current consumer. Re-open only if a consumer must AUTHOR variant-scoped elements. Preservation is locked by `modelkit.VariantFilterPreservationTest`.*
- **Warm-kernel daemon / caching generated validation code** — a performance concern, the library peer of the BA doc's `dmtool serve`; out of scope for the same reasons (see that non-goal in [`FEATURE-GAPS.md`](FEATURE-GAPS.md) §4).
- **Grouping the `Dsl` factory into sub-facades** *(an API-shape non-goal, not a scope exclusion)* — `Dsl` stays a **flat** factory by design. A coding agent — a first-class consumer of this API — discovers operators by querying the **catalog** and grepping examples, not by browsing IDE autocomplete the way a human does; so sub-facades (`Dsl.dates().…`) would aid a human's autocomplete but be a *net* debt for the agent (an indirection + a "which facade?" wrong-guess surface + a dual API). The discovery investment goes into the catalog + consistent verb-prefix naming instead. Full human-vs-agent reasoning: **API-FINDINGS finding 52** / API-GUIDE §6. Re-open only if `Dsl` becomes *incohesive* (mixes concerns beyond constructing a DSL node).
