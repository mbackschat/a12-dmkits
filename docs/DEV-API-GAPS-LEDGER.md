# dmkits — DSL-API gaps ledger (developer perspective)

> **What this is — a graduation ledger, not a proposal** (the developer-perspective peer of [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md), one persona over). dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. This doc tracks the **DSL-API gaps in that developer loop** — surfaced by a 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) — and **where each graduated** once shipped (`DGn` → its [status-board](API-FINDINGS.md) finding). **DG1–DG6 have all graduated** (shipped — or, for DG5's (de)serialization-boundary records, a deliberate non-goal); only two cosmetic *smaller edges* (§2) remain unbuilt. New dev-API gaps land here, then graduate the same way.
>
> **Scope — the filter that matters.** dmkits has **model-authoring scope**: it defines, edits, validates, reads, and tests a document **model** — its fields/groups/type-defs/includes/config and its **rules + computations** (the condition-language DSL). It is **not** a runtime form engine, a document-instance data-binding layer, a rendering/UI toolkit, or a persistence/service layer. The kernel dev doc is ~80% about the *runtime consumer* (manipulating document *instances*); those concerns are deliberately **out of scope** (§4). The gaps below are only the model-authoring slice.

---

## 1. The loop we're improving

A developer using rulekit/modelkit writes Java, so the **project's own compiler is the first safety net** — a wrong path, or a number operator on a date, fails before the kernel ever runs. The loop:

```
bind (BoundModel.ofJson) → author rules/computations via typed handles → validate (RuleDiagnostics.diagnose)
   → read foreign rules back (KernelAdapter.read, tolerant) → edit structure + rules in one session (EditSession)
   → test behaviour on sample documents (KernelAdapter.evaluate / testRule / compute)
```

dmkits already serves this well, and these strengths are the baseline the gaps build on:

- **Handles come off the model** — `m.num(path)`/`m.enumOf(path)`/`m.group(path)` fail at lookup on a wrong path or kind; the AST never holds a string a typo could corrupt.
- **Illegal states are unrepresentable** — Field ≠ Group types, no `ConfirmRef.isFalse`, enum/category values domain-checked at build, number `==`/`!=` scale-gated, wildcards erased from scalar slots.
- **Immutable records + a sealed AST** with `transform`, and **snapshot write semantics** (every write returns a *new* re-validated `BoundModel`).
- **Tolerant read** — `KernelAdapter.read` returns a sealed `ReadResult` (`Converted`/`Opaque`); an unmodeled construct is preserved verbatim, never lost.
- **One author-time front door for rules** — `RuleDiagnostics.diagnose` folds kernel + lint + gotcha + version-skew into a single `ValidationResult`.
- **Self-describing catalogs** — `OperatorCatalog`/`PatternCatalog` carry meaning, gotchas, and CI-executed examples.
- **The L2c editor/ref layer** and **runtime eval as an oracle** (`evaluate`/`compute`/`testRule`) close the read→edit→reference and prove-it-fires loops.

The gaps below are the **ergonomic and consistency remainder** — what still adds friction, forces a drop out of the typed surface, or surprises a developer who expects symmetry between siblings.

---

## 2. The gaps

Each gap: the dev need → what's missing today → the proposal. Ordered by value. The `DGn` label is this review's item id.

### DG1 — Per-type field configuration when EDITING, not just authoring from scratch  *(medium; the dominant ergonomic divergence)* — ✅ shipped 2026-06-14 ([API-FINDINGS](API-FINDINGS.md) finding 36)

- **Dev need.** Editing an existing model should read like authoring one — same fluent, per-kind configuration.
- **Today.** From-scratch authoring is rich: `GroupBuilder.numberField(n -> n.fractionDigits(2).min("1"))`, `enumField(e -> …)`, etc. But the **edit** surface (`GroupEditor.addField(name, FieldKind)` / `addField(name, FieldTypeSpec)`, `ModelDocument.addField`) takes only a bare kind or a hand-constructed `FieldTypeSpec`. A developer fluent in the builder **drops to constructing records** to add a configured field mid-edit.
- **Proposal.** Bring the same `Consumer<XFieldBuilder>` builders to the editor surface — `GroupEditor.numberField(name, n -> …)`, `enumField`, `stringField`, `dateField`, … — reusing the from-scratch builders. Authoring and editing then read identically (the §5c "refs & editors" thesis), and the rich-config path is never lost when the model already exists.

### DG2 — Rule/computation API parity  *(medium)* — ✅ shipped 2026-06-14 ([API-FINDINGS](API-FINDINGS.md) finding 34)

- **Dev need.** A developer treats rules and computations as siblings and expects symmetric tooling.
- **Today.** Three asymmetries, all verified:
  1. Rules get the unified `RuleDiagnostics.diagnose(BoundModel, Rule)`; **computations have no `diagnose` peer** — a dev hand-composes `RuleValidator.check(DraftComputation)` + `ComputationLinter.lint(...)`.
  2. `Rule.Builder.build()` throws a **structured** `PrecheckException` (carrying an `RK_*`/`MVK_*` `Diagnostic`); `Computation.Builder.build()` throws a **bare** `IllegalStateException`.
  3. `ReadResult.Opaque` carries `canonicalText` (an unconverted rule round-trips verbatim); `ReadComputation.Opaque` carries only `reason` — an opaque computation **can't be re-emitted**, which will bite a computation write-back path.
- **Proposal.** Add `ComputationDiagnostics.diagnose(BoundModel, Computation)`; make `Computation.Builder.build()` throw the structured `PrecheckException`; add `canonicalText` to `ReadComputation.Opaque`. Closes the parity a dev assumes is already there.

### DG3 — One structured error vocabulary across write/edit/adapter  *(medium)* — ✅ shipped (rulekit-aware surfaces) 2026-06-16 ([API-FINDINGS](API-FINDINGS.md) finding 41)

- **Dev need.** Consistent, catalogued, catchable errors regardless of which layer the dev touches.
- **Today.** Validate/lint speak the structured `Diagnostic` (+ `PrecheckException` carrying it, with stable `RkCode`s). But the **write/edit/adapter** surfaces threw bare `IllegalArgumentException("no rule at path: …")` / `UncheckedIOException` — even though `RkCode` already defines `NO_SUCH_RULE`/`NO_SUCH_GROUP`/`NO_SUCH_COMPUTATION` that fit exactly. A dev catches two different error models depending on the layer.
- **Proposal.** Route the not-found / bad-target paths through the same structured `Diagnostic`, thrown as `PrecheckException`. One error model, one code set.
- **Shipped (2026-06-16).** A reusable `PrecheckException.notFound(RkCode, path)` factory, adopted by the not-found paths in **`ModelWriter`** (rule/group/computation — so `BoundModel.removeRule`/`removeComputation`/`replaceCondition`/`replaceComputation` propagate it) and **`KernelAdapter`** (computation). `PrecheckException` stays an `IllegalStateException`, so it's additive — a caller can now `catch (PrecheckException e) e.diagnostic()` for the coded shape. Lock: `rulekit.validate.ErrorVocabularyTest`. **Boundary note (by design, not an oversight):** `modelkit`'s `ModelDocument` and `core`'s `ModelIndex` keep bare exceptions — the `dm.modelkit ⊥ dm.rulekit` invariant and the `core → no rulekit` layering forbid them importing `Diagnostic`/`RkCode`. So a *structure* not-found raised purely inside modelkit (e.g. a bad path to `ModelDocument.setFieldType`) stays an `IllegalArgumentException`; the rule/computation surfaces — where the dev-facing edit loop lives — now speak the one vocabulary.

### DG4 — `java.time` date & time literals  *(small)* — ✅ shipped 2026-06-14 ([API-FINDINGS](API-FINDINGS.md) finding 33)

- **Dev need.** The library's whole thesis is "typed beats fragile strings." The last place a string sneaks in is a date/time constant.
- **Today.** `Dsl.dateValue("01.01.2022")` / `Dsl.timeValue("17:00:00")` take a **format-coupled raw string** — a typo or wrong format is caught only at the kernel, the exact failure mode the typed builder erases everywhere else.
- **Proposal.** Add `Dsl.dateValue(LocalDate)` / `Dsl.timeValue(LocalTime)` overloads that render to the model's configured format. Compile-time-valid literals; the `String` overload stays for the CLI/spec path (where values arrive as text).

### DG5 — Enum-typed public surface (kill the stringly-typed `kind`/`severity`/`outcome`)  *(small–medium; broad)* — ◑ the `"?"` sentinel slice shipped 2026-06-14 (finding 35); `FieldMatch.kind` → `ValueKind` shipped 2026-06-16 (finding 45); the **(de)serialization-boundary** records intentionally stay `String`

> **Scoping decision (2026-06-16).** The library-side `FieldMatch.kind()` is now `ValueKind` (finding 45) — a real consistency fix (it matches `FieldInfo.kind()` and is built from `field.kind().name()` anyway, library-only, zero wire risk). The remaining targets — `ComputedField.outcome()`, `EvalMessage.severity()`/`messageType()` — are deliberately **left as `String`**: they are runtime-eval *result* records built straight from a kernel enum's `.name()` and emitted onto the CLI **wire contract** (with schemas + demos). Enum-typing them is pure wire-preservation gymnastics (map enum → the exact wire string at every `.put()`) plus a kernel-vs-DSL `Severity` ambiguity, for marginal read-side benefit — the "arguably correct as String at the (de)serialization boundary" case this gap itself flags. Revisit only if a dev consumer needs to *switch* on them in Java.

- **Dev need.** Types the compiler can check, not strings validated late.
- **Today.** `Severity` and `ValueKind` enums exist, yet the public surface repeatedly downgrades to `String`: `DraftRule.severity`, the explanation projections' `kind` (`RuleExplanation.Reference.kind()` / `ComputationExplanation.computedFieldKind()` / `Operator.kind()` — formerly with a **cryptic `"?"`** for an unresolved kind), `FieldMatch.kind()`, `ComputedField.outcome()` (`"value"`/`"cleared"`/`"error"`), `EvalMessage.severity()`/`messageType()`. A wrong severity string is caught late.
- **Proposal.** Use `Severity`/`ValueKind` (and small enums for compute-outcome / message-type) on the public inputs and result records. Removes a whole class of "invalid string caught late."
- **Shipped (the narrow slice, finding 35).** The cryptic `"?"` is gone: the computed-field kind is sourced from the typed `FieldRef` (provably never unknown), and the remaining tolerant fallbacks surface a self-describing `UNKNOWN` token. The broader `String`→enum retype of the public surface is the open, lower-value remainder — do it opportunistically (and note `DraftRule.severity`-as-`String` is arguably correct: it's the deserialization boundary).

### DG6 — Model-modularity vocabulary completeness  *(medium)* — ✅ shipped 2026-06-16 ([API-FINDINGS](API-FINDINGS.md) finding 44)

- **Dev need.** The dev doc enumerates the model's modularity surface: an include's **`includeLevel`** (`SINGLE_RG` — the included model becomes one repeatable group — vs `MODEL_ROOT`), and four reference *purposes* (`include`, `transitiveInclude`, `typeDefinitions`, `import`).
- **Today (before).** modelkit authored includes + type-def imports, but did **not** model `includeLevel` at all, so a dev couldn't author a `MODEL_ROOT` include, and `transitiveInclude` wasn't distinguished on read.
- **Shipped (2026-06-16).** An `IncludeLevel` enum (`SINGLE_RG`/`MODEL_ROOT`, default SINGLE_RG) on `MountOptions` (`MountOptions.single().asModelRoot()`), emitted as the group's `includeLevel` attribute (SINGLE_RG omitted, matching the kernel default-absent) and threaded through every mount path (from-scratch `mount(name, alias, MountOptions)` + `IncludeEditor.mount`); the kernel accepts it (`ModelLoader.load` expands it). On read, `ModelDocument.includes()` is now exact (purpose `include`/blank) and `transitiveIncludes()` reads the kernel-added transitive references separately. Lock: `modelkit.IncludeLevelTest`.
- **Proposal.** Add an `includeLevel` option to the include builder/editor (default `SINGLE_RG`, matching the kernel) and recognize `transitiveInclude` on read. Completes the includes vocabulary the dev doc treats as standard.

### Smaller edges  *(lower individual value; batch alongside whichever area they touch)*

- **Catalog discovery queries.** — ✅ shipped 2026-06-16 ([API-FINDINGS](API-FINDINGS.md) finding 42): `OperatorCatalog.byKeyword`/`byKind`/`byReturns` (the catalog exists *to be discovered*; `byId`/`byInvalidError` were the only lookups before). *(small)*
- **Kernel-free resolver SAM.** — ✅ shipped 2026-06-16 ([API-FINDINGS](API-FINDINGS.md) finding 43): a `core.ModelResolver` SAM (`String id → String json`), `ReferenceResolvers.of(ModelResolver)`, and `BoundModel.ofJson`/`EditSession.read` overloads — multi-model loading no longer pulls a dev into `com.mgmtp.a12.kernel.*` at the include/import seam. *(small–medium)*
- **Read→modify→write cohesion on the adapter.** `KernelAdapter` exposes `read` + `diff` but no write-back — a dev reading only the adapter sees half the loop (write-back lives on `BoundModel`/`EditSession`). A pointer or a convenience method would close it. *(small)*
- **`Dsl` is one flat ~130-method factory.** Discovery is autocomplete-only; lightweight sub-facades (`Dsl.dates()…`) or grouping would aid exploration. *(small, cosmetic)*

---

## 3. Sequencing

- **First, cheap and broad:** **DG4** (`java.time` literals) and **DG5** (enum-typed surface) — small, and each removes a whole class of late/silent failure.
- **Then the consistency levers:** **DG2** (rule/computation parity) and **DG3** (one error vocabulary) — medium, with a high "principle of least surprise" payoff.
- **Then the biggest single ergonomic win:** **DG1** (per-type field config when editing).
- **Then DG6** (modularity completeness) when a `MODEL_ROOT` use case appears.
- The **smaller edges** ride alongside whichever area they touch.

When one ships it graduates onto the [status board](API-FINDINGS.md) (the single source of truth for what's built) with its own test lock — exactly as the BA-gap items did.

---

## 4. Adjacent but out of scope

- **Document-instance manipulation** (`DocumentV2` read/update/merge/traverse) — the runtime-consumer surface and ~80% of the dev doc. dmkits authors the *model*, not its instances; it runs instances only as a *test oracle* (`evaluate`/`compute`, already shipped).
- **Typed-accessor / overlay code generation** over document instances — consumer-app codegen, not model authoring.
- **Mapping runtime** (model→model data-transfer execution) — the dev doc covers running generated mapping code; mapping *modeling* is a separate modeler concern.
- **Rendering / UI / data-binding / ResourceBundle locale plumbing** — presentation and runtime, never model content.
- **Custom-condition / custom-field-type *implementations*.** The model only *references* them (`CustomCondition <Name>` in a condition; a `Custom` field type), and dmkits already represents those references (`CustomConditionRef`; the `custom` field builder). The Java/TS validator impls are runtime — out. *(Surfacing the kernel's documented "custom-condition name is inverted in an error condition" polarity trap as an operator gotcha is a minor in-scope nicety, not a gap.)*
- **Model-version migration** — bumping a stored model to a newer DM-version is a distinct concern with the kernel's own `MigratorCli` / `IDocumentModelMigrator`; dmkits authors/edits and deliberately never bumps a model's version on write (same call as [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) §4). dmkits' job is to **detect** skew (`RK_MODEL_VERSION_SKEW` / `RK_MODEL_VERSION_INCOMPATIBLE`) and stay tolerant — not to migrate.
- **Warm-kernel daemon / caching generated validation code** — a performance concern for the iterative loop; a larger build, noted but not scoped (the library peer of the BA doc's `dmtool serve`).
