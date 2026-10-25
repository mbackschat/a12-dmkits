# dmkits DSL-API (developer perspective) — gaps

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the first batch. The doc keeps the review's framing (§1), the open backlog (§2), and the out-of-scope decisions (§3).
>
> **Lifecycle.** §2 holds the live, unfinished work. **This ledger records only OPEN work** — a shipped gap is **deleted**, never kept as a "shipped" list, because such a list duplicates git (the *what/when*) and the finding (the *why*) and rots against both. A gap's durable *why* is its numbered finding in [`API-FINDINGS.md`](API-FINDINGS.md), which names the `DGn` it closed, so a code comment citing `DEV-API-GAPS DGn` — or a legacy `DEV-API-GAPS §N` — resolves **by topic**: grep `API-FINDINGS.md` for the id. Two deliberate exceptions: the editor-ergonomics pair **DG11/DG12** is shown in API-GUIDE [§5c](API-GUIDE.md#refs-and-editors) instead, and **DG16** was declined rather than built, so its decision lives in §3 here.
>
> **Current state.** §2 lists what is open; read it rather than this paragraph. A row lands there only when a new developer-facing authoring friction is both surfaced and queued, and it is deleted when it ships — so a closed gap's *why* is its [API-FINDINGS](API-FINDINGS.md) finding, not a line here.
>
> **Id allocation — one CHUNK per author, declared here and nowhere else.** Two branches appending from one base allocate the same next id, so a merge has to renumber whichever side is smaller and move every citation with it — and a citation rewritten to the *wrong* entry still resolves, so no guard can see that mistake. Not hypothetical: `DG47` was allocated on both sides of the 2026-09-20 harvest and named two different gaps, one of which this repository had already closed. The same collision hit that harvest's kernel findings and API findings, each of which had appended from a shared base into ids this repository had since issued to other entries, so every one had to be renumbered by hand against its heading and every citation moved with it. Allocating from a per-author range removes the collision, and ids are dense within a range rather than globally. The boundaries live on the line below and nowhere else; an open upper bound means the range has no declared end. [CONSISTENCY-GAPS](CONSISTENCY-GAPS.md) draws its ids from the same chunks, so the two ledgers never issue one id twice.
>
> `dg-id-chunks: 1-999 = Martin Backschat; 1001- = Alexander Ried`
>
> **Scope — the filter that matters.** dmkits has **model-authoring scope**: it defines, edits, validates, reads, and tests a document **model** — its fields/groups/type-defs/includes/config and its **rules + computations** (the condition-language DSL). It is **not** a runtime form engine, a document-instance data-binding layer, a rendering/UI toolkit, or a persistence/service layer. The kernel dev doc is ~80% about the *runtime consumer* (manipulating document *instances*); those concerns are deliberately **out of scope** (§3). Where the portable `:consistency` checker reports a model differently from A12, the gap lives in [CONSISTENCY-GAPS](CONSISTENCY-GAPS.md) instead.

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

- **DG160 — `rule modify --condition` has no waiver flag, while the scale-gate corrective names one** (found 2026-10-25, cold review R2). The waiver is part of A12's condition text (KF75), so a new `--condition` replaces it, and the diff now shows the drop. A re-expression that still needs it is refused `MVK_INVALID_COMPARE_DEC_PLACES`, and the enricher's corrective (`StructuralKernelFix`) says "allowDifferingDecimals:true on the rule spec", a key `rule add` and `apply` take and `rule modify` does not. Writing the directive into `--condition` is admitted (`ScaleWaiverReadBackCliTest`). Settles by either a `rule modify` flag or a corrective that names the inline directive for this verb.

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
- **Grouping the `Dsl` factory into sub-facades** *(an API-shape non-goal, not a scope exclusion)* — `Dsl` stays a **flat** factory by design. A coding agent — a first-class consumer of this API — discovers operators by querying the **catalog** and grepping examples, not by browsing IDE autocomplete the way a human does; so sub-facades (`Dsl.dates().…`) would aid a human's autocomplete but be a *net* debt for the agent (an indirection + a "which facade?" wrong-guess surface + a dual API). The discovery investment goes into the catalog + consistent verb-prefix naming instead. Full human-vs-agent reasoning: **API-FINDINGS finding 52** / API-GUIDE [§6](API-GUIDE.md#semantics-as-data). Re-open only if `Dsl` becomes *incohesive* (mixes concerns beyond constructing a DSL node).
