# dmkits DSL-API (developer perspective) — gaps

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the first batch. The doc keeps the review's framing (§1), the open backlog (§2), and the out-of-scope decisions (§3).
>
> **Lifecycle.** §2 holds the live, unfinished work. **This ledger records only OPEN work** — a shipped gap is **deleted**, never kept as a "shipped" list, because such a list duplicates git (the *what/when*) and the finding (the *why*) and rots against both. A gap's durable *why* is its numbered finding in [`API-FINDINGS.md`](API-FINDINGS.md), which names the `DGn` it closed, so a code comment citing `DEV-API-GAPS DGn` — or a legacy `DEV-API-GAPS §N` — resolves **by topic**: grep `API-FINDINGS.md` for the id. Two deliberate exceptions: the editor-ergonomics pair **DG11/DG12** is shown in API-GUIDE [§5c](API-GUIDE.md#refs-and-editors) instead, and **DG16** was declined rather than built, so its decision lives in §3 here.
>
> **Current state.** §2 lists what is open; read it rather than this paragraph. A row lands there only when a new developer-facing authoring friction is both surfaced and queued, and it is deleted when it ships — so a closed gap's *why* is its [API-FINDINGS](API-FINDINGS.md) finding, not a line here.
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

- **DG47 — `ReadResult.Opaque` now carries two different answers, and a consumer cannot tell them apart**
  (surfaced 2026-09-02 while widening the bridge's format gate). `Opaque` is documented as "a rule preserved
  verbatim because its condition uses a not-yet-modeled construct", and `InterpreterConditionReadBack.read`
  catches every `RuntimeException` into it — so it also means "mapped fine, but a rulekit check rejected it".
  Those are opposite messages to a consumer: the first says *the bridge cannot do this yet*, the second says
  *this condition is invalid*. Distinguishing them today means parsing the `reason` string. The conflation
  predates this slice — the kind gate's `MVK_NO_DATE` already landed there — but the format gate widens it.
  **Why filed rather than fixed:** the fix is a third `ReadResult` state, which is a published SPI change on a
  type both providers implement, and no consumer has yet asked to tell the two apart. **What would settle it:**
  a consumer that needs to distinguish "retry when the bridge learns this" from "this rule is broken".

- **DG44 — the interpreter-bridge's operator mapping is measured against the kernel for a fifth of its surface.**
  The bridge maps ~84 operator names; `adapter.ConditionReadBackParityTest` — the ONLY place a mapping is checked
  against the kernel rather than against our own expectation — covers 21 constructs, ~20 distinct ids (the three
  extraction/split cases added 2026-09-02 beside the format gate that changed their mapping). Seven names
  have no mention in any test: `AtLeastOneFieldFilled`, `DifferenceInMinutes`, `MoreThanOneFieldFilled`,
  `NotAllFieldsFilled`, `NotExactlyOneFieldFilled`, `RoundAccounting`, `RoundUpValue`. The shape matters more than
  the ratio, and it is why unit coverage does not close this: the module's own tests assert the mapping against
  **hand-authored rulekit types**, so a mapping that is self-consistently wrong — the bridge and its test agreeing,
  the kernel disagreeing — is invisible to them by construction. Measured, not argued: a cold review mutated
  `NotAllFieldsFilled` to `MoreThanOneFieldFilled` and `DifferenceInMinutes` to `SECONDS`, and both survived the
  module's own suite *and* the parity net. Families the differential never reaches: semantic index (`For`), the
  indexed field/group operands, date extraction, `FirstFilledValue`, `CountAggregate`, the `$` correlation,
  group-list quantifiers, `Valid`/`Invalid`, value lists, the rounding family, `CurrentRepetition`. **Growth goes
  into the parity corpus, not the module's unit tests** — only the corpus has the kernel as its oracle, and adding
  a case is one entry naming its rule group and error field. The open design question is whether the corpus should
  be generated from the operator catalog, so the denominator moves with the catalog instead of by hand. Recall
  limit on the 84: a regex over `when`-branch literals, so it misses any dispatch not written as a quoted branch.

- **DG45 — two guards state invariants broader than what they enforce.** Both hold today, and neither is enforced by the oracle its citation implies, so a future change can falsify them silently. `PublicApiGuardTest.noThirdPartyTypeOnAnyPublishedSignature` allows `java.`/`javax.`/`io.github.mbackschat.`, which RELEASE-SPEC §11 justifies as stopping an `implementation`-scoped dependency from reaching a consumer's compile classpath; `dmkits-interpreter-bridge` is the first published artifact whose signatures use a type (`dm.interpreter.ast.Expr`) from a **separate publication** sharing that prefix and `runtime`-scoped in its POM, so the letter passes while the purpose does not. Impact is bounded — those classes are Kotlin `internal`, hence API only by JVM accident — and closing it means distinguishing "our kits" from "our other publications" in the allowlist. Separately, `verifySchemakitPurity` scans source imports rather than compiled dependencies, so it cannot enforce the broader claim that no raw kernel dependency is present but unused; that requires a declared-dependency or resolved-classpath guard, not source parsing. The interpreter-bridge peer no longer belongs in this gap: it now advertises and enforces the narrower compiled-linkage invariant through the shared `jdeps` guard.

- **DG43 — `Computation.Builder` gates a constant against the target's declared KIND, and the kernel gates it against the declared FORMAT STRING** (found 2026-08-31 while measuring a12-kernel-lean's `SPEC-2026-08-31-03`; measured in [KF222](KERNEL-FINDINGS.md#kf222), locked in `adapter.laws.ConstantTargetFormatLawsTest.ourTypedBuilderIsStricterThanTheKernelOnTheCrossKindCells`). `build()` requires `operation.kind() == computedField.kind()`, so it refuses `Dsl.timeValue("12:30:00")` into a DATE-kind field — a cell the kernel both **admits and stores** when that field is declared `HH:mm:ss`, and symmetrically for a date constant into a TIME-kind field declared `yyyy-MM-dd`. This is a measured over-rejection under the standing client-side-refusal rule, and the fix is not a loosened predicate: the kernel's input is the declared format, which `FieldRef.kind()` does not carry, so a conforming check needs the format on the ref (or a format-aware `ValueKind` derivation) and reaches every constant-assignment site. **Updated 2026-09-02:** the missing half now exists and did NOT require format on the ref — `TemporalFormats.componentsOf` classifies a declaration, and `Dsl.dateExtraction`/`dateTimePart` show the pattern of resolving it through the model at the factory rather than carrying it on the operand ([KF1001](KERNEL-FINDINGS.md#kf1001)). What stays open is this row's actual subject, unchanged: whether the constant-assignment kind check should become format-aware everywhere or only for constants. Blast radius and whether the kind check should become format-aware everywhere or only for constants is a design call, so it is filed rather than fixed in passing. Until then the narrowing is documented in [KF222](KERNEL-FINDINGS.md#kf222) rather than merely true.

- **DG42 — the entity-list aggregates have no UNSTARRED group-operand factory, and the kernel admits one** (found 2026-08-31 while measuring a12-kernel-lean's `EXP-2026-08-31-02`). `AggregateOperand` permits `FieldOperand` and the **starred** `GroupListRef` only, so `Sum`, `MinValue`, `MaxValue`, `NumberOfDifferentValues` and every `numberOfValueInFields` overload can take a starred group but not a plain `GroupRef` — while `numberOfFilledFields(GroupRef first, GroupRef... rest)` already has exactly that overload, backed by `CountAggregate`'s separate `groups` list. Measured on kernel 30.8.1 over a nonrepeatable shell: `Sum(Shell)`, `MinValue(Shell)`, `MaxValue(Shell)`, `NumberOfDifferentValues(Shell)`, `NumberOfValueInFields("x" In Shell)` and `NumberOfValueInFields(True In Shell)` are **all admitted**, so this is a type-level erasure over a legal shape rather than a refusal — the shape reads as "not a thing" instead of "refused", which is the harder half to see. **The cost it imposed:** `adapter.laws.GroupOperandOverLimitExtentDiffTest` had to author its conditions as raw strings, so a probe of a kernel-legal shape could not be driven from the typed surface. **Shape of the fix:** give `ListAggregate` a `groups` list beside its operands, exactly as `CountAggregate` already carries one, and add the `GroupRef` overloads; the kind gate stays kernel-delegated for the same reason `AggregateOperand` already delegates it (the expansion's extent is not ours to re-derive). **What must be measured before widening further:** the admission matrix above covers six carriers on one fixture at `en_US`; a `GroupRef` overload on a carrier not in it would be an unmeasured widening.

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
