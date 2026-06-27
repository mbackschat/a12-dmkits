# dmkits — DSL-API gaps ledger (developer perspective)

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the first batch. **This ledger records only OPEN work** — like its sibling [`INTERPRETER-GAPS-LEDGER.md`](INTERPRETER-GAPS-LEDGER.md), a shipped gap is **deleted**, not kept as a "shipped" list (git is the *what/when*; the gap's **API-FINDINGS finding** is the *why*). **All the `DGn` gaps (DG1–DG14) have shipped** and so are gone from here; their durable *why* lives in the [API design findings](API-FINDINGS.md) (and API-GUIDE §5c for the editor-ergonomics pair DG11/DG12) — and because each finding back-references its `DGn`, a code comment citing `DEV-API-GAPS DGn` resolves via the finding. The doc keeps the review's framing (§1), the open backlog (§2), and the out-of-scope decisions (§3).
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

The live, unfinished work. (Shipped gaps are **not** recorded here — a closed gap is no longer a gap; git is the history of *what/when*, and its **API-FINDINGS finding** is the *why*. DG1–DG14 all shipped this way; each finding back-references its `DGn`, so a code comment citing `DEV-API-GAPS DGn` resolves via the finding — DG11/DG12 via API-GUIDE §5c.)

**One cosmetic item, plus a discoverability question below:** grouping the flat ~130-method `Dsl` factory into lightweight sub-facades for discovery (batch alongside whichever area they touch). *(The modelkit `GroupBuilder.sortField` gap shipped — API-FINDINGS finding 51; the remaining CLI half of sort-field authoring lives in [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) §3 G28.)*

**Discoverability — the editor/relative-ref idiom is not the corpus default (surfaced 2026-06-26, OPEN).** The dogfooding lesson of the 2026-06-26 PerfCatalog migration (CLAUDE.md "eat your own dogfood"): a new consumer reaches for **by-path** `BoundModel.ofJson(...).num("/full/path")` because that is what the existing internal test corpus (the `:adapter` `*DiffTest`s + most `BoundModel`-based tests) models — not the **editor relative-nav** (`EditSession.read(...).group(p).num("child")` / `.flatten(...)`) the README + API-GUIDE call the *preferred* idiom. The **discipline for NEW code is now codified** (the mantra: read the API, not the corpus; prefer the editor). What stays **open** is the *remediation*: whether to retrofit the existing by-path corpus to the editor idiom so it stops teaching the lower-level form. **Undecided, not committed** — by-path is legitimate for referencing an *existing loaded* model (API-GUIDE: "the common case"), so a blanket retrofit may be low-value churn; the highest-leverage subset is the **authoring-style examples** a newcomer copies (the guide snippets + a few exemplar tests), not the full corpus. Re-open as a focused task if the editor idiom isn't visibly winning in new contributions. (No missing API — purely adoption/discoverability; that's why it sits here, not as a `DGn` capability gap.)

---

## 3. Adjacent but out of scope

The durable decisions about what dmkits deliberately does *not* do (its model-authoring scope — not a runtime form engine, instance-data layer, or UI):

- **Document-instance manipulation** (`DocumentV2` read/update/merge/traverse) — the runtime-consumer surface and ~80% of the dev doc. dmkits authors the *model*, not its instances; it runs instances only as a *test oracle* (`evaluate`/`compute`).
- **Typed-accessor / overlay code generation** over document instances — consumer-app codegen, not model authoring.
- **Mapping runtime** (model→model data-transfer execution) — the dev doc covers running generated mapping code; mapping *modeling* is a separate modeler concern.
- **Rendering / UI / data-binding / ResourceBundle locale plumbing** — presentation and runtime, never model content.
- **Custom-condition / custom-field-type *implementations*.** The model only *references* them (`CustomCondition <Name>` in a condition; a `Custom` field type), and dmkits already represents those references (`CustomConditionRef`; the `custom` field builder). The Java/TS validator impls are runtime — out. *(Surfacing the kernel's documented "custom-condition name is inverted in an error condition" polarity trap as an operator gotcha is a minor in-scope nicety, not a gap.)*
- **Model-version migration** — a distinct concern with the kernel's own `MigratorCli` / `IDocumentModelMigrator`; dmkits authors/edits and deliberately never bumps a model's version on write (same call as [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) §4). dmkits' job is to **detect** skew (`RK_MODEL_VERSION_SKEW` / `RK_MODEL_VERSION_INCOMPATIBLE`) and stay tolerant — not to migrate.
- **Warm-kernel daemon / caching generated validation code** — a performance concern, the library peer of the BA doc's `dmtool serve`; out of scope for the same reasons (see that non-goal in [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) §4).
