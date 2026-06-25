# dmkits — DSL-API gaps ledger (developer perspective)

> **What this is** — the developer-perspective peer of [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md), one persona over. dmkits (rulekit + modelkit) is also driven directly as a **typed Java library** by a **developer working inside their own JVM project** — binding a model, authoring rules/computations as typed code the compiler steers, editing structure, reading foreign rules back, and testing against sample documents, all kernel-confirmed. A 2026-06-14 review against the kernel team's developer reference (`a12-kernel/documentation/_merged/kernel-dev.md`, consult-only) surfaced the gaps below; **DG1–DG6 all shipped**, their durable *why* now lives in the [API design findings](API-FINDINGS.md). The doc keeps the review's framing (§1), the gap identity + rationale-pointer (§2 — so `DGn` code refs stay meaningful), and the out-of-scope decisions (§3). Code comments citing `DEV-API-GAPS DGn` resolve here by name.
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

## 2. The gaps, and where their rationale lives

| gap | what it is | rationale now in |
|---|---|---|
| **DG1** | per-type field builders on the **edit** surface (editing reads like authoring) | API-FINDINGS finding 36 |
| **DG2** | rule/computation API parity (a `diagnose` peer, a structured `build()` throw, `Opaque.canonicalText`) | API-FINDINGS finding 34 |
| **DG3** | one structured error vocabulary across the rulekit-aware write/edit/adapter paths | API-FINDINGS finding 41 |
| **DG4** | `java.time` date/time literal overloads | API-FINDINGS finding 33 |
| **DG5** | enum-typed public surface — the typed-`FieldRef` slices shipped; the (de)serialization-boundary result records stay `String` by design | API-FINDINGS findings 35, 45 |
| **DG6** | model-modularity completeness (`includeLevel` authorable, transitive-include distinguished on read) | API-FINDINGS finding 44 |
| *smaller edges* | catalog discovery queries (`byKeyword`/`byKind`/`byReturns`); the kernel-free `ModelResolver` SAM | API-FINDINGS findings 42, 43 |

**Genuinely open (the live backlog):** two cosmetic — a read→modify→write *cohesion pointer* on `KernelAdapter` (it exposes `read`+`diff` but not write-back — that lives on `BoundModel`/`EditSession`), and grouping the flat ~130-method `Dsl` factory into lightweight sub-facades for discovery (batch alongside whichever area they touch) — and one **structural-authoring gap surfaced 2026-06-26: modelkit has no `GroupBuilder.sortField(…)`** to author a repeatable group's declared row **sort fields** (`IGroup.getSortInfo` / `ISortField`). Authoritative entry + the CLI half + the eval/read-fidelity analysis: [`FEATURE-GAPS-LEDGER.md`](FEATURE-GAPS-LEDGER.md) §3 **G28** (it's purely an authoring + read-fidelity concern — the kernel runtime does not consume sort order for `validateFull`/`compute`, so the interpreter correctly ignores it).

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
