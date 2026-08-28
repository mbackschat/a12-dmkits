# dmkits DSL-API (developer perspective) — gaps

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the first batch. The doc keeps the review's framing (§1), the open backlog (§2), and the out-of-scope decisions (§3).
>
> **Lifecycle.** §2 holds the live, unfinished work. **This ledger records only OPEN work** — a shipped gap is **deleted**, never kept as a "shipped" list, because such a list duplicates git (the *what/when*) and the finding (the *why*) and rots against both. A gap's durable *why* is its numbered finding in [`API-FINDINGS.md`](API-FINDINGS.md), which names the `DGn` it closed, so a code comment citing `DEV-API-GAPS DGn` — or a legacy `DEV-API-GAPS §N` — resolves **by topic**: grep `API-FINDINGS.md` for the id. Two deliberate exceptions: the editor-ergonomics pair **DG11/DG12** is shown in API-GUIDE [§5c](API-GUIDE.md#refs-and-editors) instead, and **DG16** was declined rather than built, so its decision lives in §3 here.
>
> **Current state.** §2 is empty as of 2026-08-24. A row lands here only when a new developer-facing authoring friction is both surfaced and queued.
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

- **DG40 — the typed temporal surface is KIND-keyed, so a kernel-legal cross-format temporal condition is unrepresentable (opened 2026-08-28, measured).** [KF206](KERNEL-FINDINGS.md#kf206) makes `timeField("F").format("yyyy-MM-dd")` a legal declaration and [KF211](KERNEL-FINDINGS.md#kf211) measures that the kernel then treats that cell as a date at every gate: it **admits** `[TimeAsDate] == [DateAsDate]`, `[TimeAsDate] > [DateAsDate]`, `YearFromDate(TimeAsDate)` and `DifferenceInDays(TimeAsDate, DateAsDate)`, while refusing `HoursFromTime` on the same cell and `[TimeAsTime] == [DateAsDate]` on a clock-declared sibling. Our surface can express **none** of the admitted four: `m.time(path)` yields a `TimeRef` whose only comparison is `agreesWith(TimeRef)`, and `m.date(path)` **throws** on a TIME field (`BoundModel.date`, `kind != ValueKind.DATE`). So the gate we present is the field's `kind`; the kernel's is the declared format's component set.

  This is the **type-level** form of an ungrounded client-side refusal — API-FINDINGS finding 128's shape, where there is no error to measure, only a missing factory, so the gap reads as "not a thing" rather than "refused". The refusal is *correct* for every conventional declaration (the kernel refuses `HH:mm:ss` TIME against a DATE, measured), which is exactly why it went unnoticed: kind and format agree everywhere except on the exotic declaration.

  **Why it is a row rather than a fix.** Making the temporal refs format-keyed is a public-surface design decision with fan-out across `DateRef`/`TimeRef`/`DateTimeRef`/`DateFragmentRef`, the comparability laws, and the operator catalog, and the honest question — *should the typed surface model A12's component sets instead of its kinds?* — is the owner's, not a side effect of the task that found it. Until then `adapter.laws.TemporalDeclaredFormatGateKernelTest` authors these conditions as raw strings and says why. **What would settle it:** a decision on whether the kind-keyed accessors stay the front door with a format-aware escape hatch beside them, or the refs become format-keyed throughout.

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
- **Grouping the `Dsl` factory into sub-facades** *(an API-shape non-goal, not a scope exclusion)* — `Dsl` stays a **flat** factory by design. A coding agent — a first-class consumer of this API — discovers operators by querying the **catalog** and grepping examples, not by browsing IDE autocomplete the way a human does; so sub-facades (`Dsl.dates().…`) would aid a human's autocomplete but be a *net* debt for the agent (an indirection + a "which facade?" wrong-guess surface + a dual API). The discovery investment goes into the catalog + consistent verb-prefix naming instead. Full human-vs-agent reasoning: **API-FINDINGS finding 52** / API-GUIDE [§6](API-GUIDE.md#semantics-as-data). Re-open only if `Dsl` becomes *incohesive* (mixes concerns beyond constructing a DSL node).
