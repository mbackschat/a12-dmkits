# dmkits — DSL-API gaps (developer perspective)

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the first batch. **This ledger records only OPEN work** — like its sibling [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md), a shipped gap is **deleted**, not kept as a "shipped" list (git is the *what/when*; the gap's **API-FINDINGS finding** is the *why*). **Every implementable `DGn` gap has shipped** (DG1–DG15 and DG17; DG16 was declined as out of scope — §3) and so is gone from here; their durable *why* lives in the [API design findings](API-FINDINGS.md) (and API-GUIDE §5c for the editor-ergonomics pair DG11/DG12) — and because each finding back-references its `DGn`, a code comment citing `DEV-API-GAPS DGn` resolves via the finding. The doc keeps the review's framing (§1), the open backlog (§2), and the out-of-scope decisions (§3).
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

## 2. Open backlog

The live, unfinished work as a table. (Shipped gaps are **not** recorded here — a closed gap is no longer a gap; git is the history of *what/when*, its **API-FINDINGS finding** is the *why*, and DG1–DG14 all shipped this way — a code comment citing `DEV-API-GAPS DGn` resolves via the back-referencing finding, DG11/DG12 via API-GUIDE §5c.)

| Gap | Surfaced | The friction | Decision / next step |
|---|---|---|---|
| **Authoring exemplars teach the editor idiom** | 2026-06-26 | The authoring exemplars (API-GUIDE snippets, README samples, a few canonical tests) should use the editor relative-nav (`EditSession.read(...).group(p).num("child")` / `.flatten(...)`) *where it earns its keep* — authoring **new** structure, path-written-once. | Scoped **adoption** task; **no missing API**. By-path (`m.num("/full/path")`) stays **blessed** for referencing an already-loaded model (clearer/safer for a stateless agent), so the bulk by-path corpus is **deliberately not retrofitted**. Re-open only if the editor idiom isn't visibly winning in new *authoring* contributions. |
| **Typed DSL cannot author three kernel-legal shapes the WI-T/IG38 differentials needed** | 2026-07-07 (14:00, the IG38 DiffTests fell back to raw stored-form strings — the WI-9 precedent) | (1) **Date-valued min/max** — `Dsl.min/max/minValue/maxValue` are typed `NumberExpr` over number operands, but the kernel accepts DATE operands (`minDatum`/`minWertDatum`, KERNEL-FINDINGS §6 DateCombiner); a date `Min([A],[B]) < "20.03.2026"` is not typed-authorable. (2) **Presence over a semantic index** — `FieldFilled(Count For "K")` / an indexed ref as a fill-quantifier operand (KERNEL-FINDINGS §9 presence): `forIndex` exists only as a typed VALUE expr; no `.filled()` on it. (3) **Starred FIELDS side of the value-list quantifiers** — `AtLeastOneFieldValueIncludedInValueList(Items* then Code In "A")`: the `Dsl.*ValueList` entry points take plain `StringRef` fields, no flatten overload. | Extend the typed DSL (each needs renderer + kind-check design — date-expr comparisons for (1), a presence handle on indexed refs for (2), flatten overloads for (3)); showcase in API-GUIDE when built. Until then the stored-form string is the documented fallback for DiffTests (parser conformance is in scope there). |

---

## 3. Adjacent but out of scope

The durable decisions about what dmkits deliberately does *not* do (its model-authoring scope — not a runtime form engine, instance-data layer, or UI):

- **Document-instance manipulation** (`DocumentV2` read/update/merge/traverse) — the runtime-consumer surface and ~80% of the dev doc. dmkits authors the *model*, not its instances; it runs instances only as a *test oracle* (`evaluate`/`compute`).
- **Typed-accessor / overlay code generation** over document instances — consumer-app codegen, not model authoring.
- **Mapping runtime** (model→model data-transfer execution) — the dev doc covers running generated mapping code; mapping *modeling* is a separate modeler concern.
- **Rendering / UI / data-binding / ResourceBundle locale plumbing** — presentation and runtime, never model content.
- **Custom-condition / custom-field-type *implementations*.** The model only *references* them (`CustomCondition <Name>` in a condition; a `Custom` field type), and dmkits already represents those references (`CustomConditionRef`; the `custom` field builder). The Java/TS validator impls are runtime — out. *(Surfacing the kernel's documented "custom-condition name is inverted in an error condition" polarity trap as an operator gotcha is a minor in-scope nicety, not a gap.)*
- **Model-version migration** — a distinct concern with the kernel's own `MigratorCli` / `IDocumentModelMigrator`; dmkits authors/edits and deliberately never bumps a model's version on write (same call as [`FEATURE-GAPS.md`](FEATURE-GAPS.md) §4). dmkits' job is to **detect** skew (`RK_MODEL_VERSION_SKEW` / `RK_MODEL_VERSION_INCOMPATIBLE`) and stay tolerant — not to migrate.
- **`variantFilter` authoring** (was DG16) — a field/rule/computation may carry a `variantFilter` (`{filterType: ONLY|ALL_EXCEPT, variants}`) that gates its per-variant membership (KERNEL-FINDINGS "variant filtering"). dmkits deliberately does **not author or interpret** it: it is a **pass-through** — an existing model that carries one has it **preserved verbatim** on a modelkit load→edit→save (the raw-JSON tree surgery never strips an unmodeled slot; the interpreter still *honors* it on load, IF36). *Decision 2026-07-06 (was briefly built as DG16, then rolled back): variant modeling is a modeler concern; the authoring surface would need to span field/rule/computation × draft/edit/serializer for no current consumer. Re-open only if a consumer must AUTHOR variant-scoped elements. Preservation is locked by `modelkit.VariantFilterPreservationTest`.*
- **Warm-kernel daemon / caching generated validation code** — a performance concern, the library peer of the BA doc's `dmtool serve`; out of scope for the same reasons (see that non-goal in [`FEATURE-GAPS.md`](FEATURE-GAPS.md) §4).
- **Grouping the `Dsl` factory into sub-facades** *(an API-shape non-goal, not a scope exclusion)* — `Dsl` stays a **flat** factory by design. A coding agent — a first-class consumer of this API — discovers operators by querying the **catalog** and grepping examples, not by browsing IDE autocomplete the way a human does; so sub-facades (`Dsl.dates().…`) would aid a human's autocomplete but be a *net* debt for the agent (an indirection + a "which facade?" wrong-guess surface + a dual API). The discovery investment goes into the catalog + consistent verb-prefix naming instead. Full human-vs-agent reasoning: **API-FINDINGS finding 52** / API-GUIDE §6. Re-open only if `Dsl` becomes *incohesive* (mixes concerns beyond constructing a DSL node).
