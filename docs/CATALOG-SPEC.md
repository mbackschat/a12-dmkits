# CATALOG-SPEC — the operator catalog: schema, semantic facets, verification lanes

> **Status: shipped contract** (extracted from [PROJECT-DESIGN §9](PROJECT-DESIGN.md#9-operator-catalog) per the outgrows-its-section rule, 2026-07-08, when the WI-5a facet slice landed; §9 keeps the design intent and the honesty directions). This spec is the live contract for `src/main/resources/catalog/operators.json`, its Java model (`rulekit.catalog`), and the guards that keep it honest. The facet program context is [SEMANTICS-FORMALIZATION-PROPOSAL §9](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md).

## 1. What the catalog is

One bundled JSON resource — `catalog/operators.json`, loaded by `OperatorCatalog.load()` — describing **every kernel construct as data**: the language-neutral spine the typed builder, the renderer, the diagnostics enrichment, `dmtool operators`, and the LLM-facing surfaces all read from. Nothing operator-specific is hard-coded in Java.

Two knowledge tiers live in the file:

| tier | carrier | example |
|---|---|---|
| **prose for a reader** (human or LLM) | `meaning`, `constraints`, `gotchas` (kind-classified, with local `seeAlso` links), `validExample`/`invalidExample` | "an UNSPECIFIED field counts as 0 — unlike MaxValue/MinValue" |
| **data for a program** | the **semantics facets** (§3), the header **`emptyOperandDefaults`** (§4), the **`correctives`** (§6) | `"emptyOperand": [{"operand": "0", "behavior": "AS_ZERO", …}]` |

The machine tier exists because three consumers demonstrably guessed wrong when semantics lived only in prose: the `DiagnosticEnricher` served an operand-blind fix (the D1 incident, §6), `RK_UNGUARDED_NUMBER_COMPARISON` was pattern-matched and incomplete, and the eval judge false-positived on empty-operand equivalence.

## 2. The entry schema

`OperatorEntry` is the Jackson-loaded catalog shape (`ignoreUnknown` lets the JSON grow ahead of the record). Its prose fields are id, `kind` (`OperatorKind`), bilingual `keywords`, `operands` (type tokens), `returns`, `meaning`, `constraints`, `gotchas` (`GotchaKind`-classified, with local `seeAlso`), `validExample` / `invalidExample` / `computationExample`, `status` (`SUPPORTED`/`PLANNED` — the stub policy, PROJECT-DESIGN §9), `opposite`, top-level `seeAlso`, and `sinceKernel`. The machine tier adds:

**`operands` is a per-slot TYPE contract, NOT an arity contract — do not count it to validate a call (measured 2026-09-12).** A slot whose type names a list (`field-list-or-groups`, `number-field-list`, `date-range`, the value-list slots) absorbs a comma-separated sequence, so the argument count a legal call carries is unrelated to the number of slots. Measured against the catalog's own CI-executed valid examples: **15 of 110 operators pass a different count than their slot count**, `AllGroupsFilled(ShippingAddress, BillingAddress)` against one slot and `FieldValueIncludedInValueList(Priority, "EXPRESS", "OVERNIGHT")` against two among them. A checker that derived arity from `operands.length` would therefore reject models A12 accepts, which is the one direction a checker must never take; arity is not currently derivable from this catalog and needs its own measured basis before anything rests on it.

- **`semantics`** (§3) — the per-operator facet block, one key per dimension.
- **`correctives`** (§6) — plural code-specific, kernel-executed rejection fixes; an optional operand selector refines comparison failures beyond `invalidExample.fix`.
- **`dslEntry` / `dslEntryNote`** (§7a, G3) — the DSL authoring entry point(s): the rulekit Java method name(s) that render this operator, so a consumer crosses from "which operator" to "which call". **Derived, not hand-authored** — the single source of truth is a `catalog:<Id>` marker on each authoring method's Javadoc; the JSON is that map's shipped projection, locked by a two-way guard (§5). A constant with no standalone factory (`True`/`False`/`FirstDay`/`LastDay` — emitted only as a comparison operand or a keyword inside another op) carries an explicit empty `dslEntry` + a `dslEntryNote` reason, never a silent absence.

## 3. The `semantics` facet block

```jsonc
"semantics": {
  "emptyOperand": [ /* §4  — a LIST of per-operand claims */ ],
  "boundary": { … },          /* §3a — a SINGLE operator-level claim */
  "iterationRange": { … },    /* §3b */
  "aggregateIdentity": { … }, /* §3c — value XOR perKind */
  "polarity": { … }           /* §3d — probes carry an `outcome` */
}
```

Design rules, fixed by the WI-5a slice and held through the WI-5b dimensions:

- **One key per dimension.** Adding a dimension never reshapes an existing one (`OperatorSemantics` ignores unknown keys until the field lands). A dimension's *shape* matches its nature: `emptyOperand` and `aggregateIdentity` carry per-operand / per-kind structure (`emptyOperand` a **list**; `aggregateIdentity` a `value` XOR a `perKind` map); the plain operator-level dimensions (`boundary`, `iterationRange`, `polarity`) are a **single** claim (one value for the whole operator).
- **Every facet value is a verified claim.** A claim carries a **`basis`** (the differential lock / KERNEL-FINDINGS or IF finding it derives from — values are *derived from locks, never invented*) and **`probes`** (kernel-executed, §5). A claim without a probe **fails the build**.
- **Deviations only** (where a default exists). Type-level defaults live once in the header (§4); an operator carries an `emptyOperand` claim only where it deviates from (or per-kind refines) the operand kind's default. Absence of a claim = the defaults apply. The operator-level dimensions carry no header default — an operator states its claim iff the dimension applies to it (e.g. only threshold/interval operators carry `boundary`).

### 3a. The `boundary` dimension

**The question it answers:** for an operator whose semantics involve a **threshold or interval endpoint**, does the endpoint value itself satisfy the operator? One value per operator (`BoundaryClaim` = `{value, basis, note, probes}`), value vocabulary `Boundary` derived from the locks:

| value | meaning | canonical lock |
|---|---|---|
| `STRICT` | the endpoint is **excluded** — the endpoint value does not satisfy the operator | `<`/`>` (definition); the tolerance band `DiffersWithToleranceRangeN` (`ToleranceExactnessDiffTest`: fires iff `|R₁₉(a) − R₁₉(b)| > N` — exactly-N does not fire) |
| `INCLUSIVE` | the endpoint is **included** — the endpoint value satisfies the operator | `<=`/`>=` (definition); the date-range overlap predicates (IF72 / `DateRangeOverlapSemanticsDiffTest`: CLOSED intervals, `end1 ≥ start2 ∧ end2 ≥ start1`, so touching ranges overlap) |

The consumer this serves (the §21D razor): the eval judge, to reason that `[Q] < 100` ≡ `[Q] <= 99` over integers but `[Q] < 100` ≢ `[Q] <= 100`, and that touching date ranges overlap. Each claim's probe pins a document whose operand sits **exactly at** the endpoint, so a STRICT/INCLUSIVE mistake flips `fires`. Populated over the ten threshold/interval operators — the four orderings (`<` `>` `<=` `>=`), the four tolerance ops, and the two date-range overlap predicates; `Equal`/`NotEqual` carry no boundary (equality has no interval endpoint).

### 3b. The `iterationRange` dimension

**The question it answers:** for a field-fill quantifier over a starred / group scope, which repetition range does it iterate — the rows that *exist* or the full *declared* range? One value per operator (`IterationRangeClaim`), vocabulary `IterationRange` derived from IF49:

| value | meaning | canonical lock |
|---|---|---|
| `INSTANTIATED` | the rows that exist (the natural **default** — not recorded as a claim) | the count quantifiers (`AtLeastOne`/`No`/`MoreThanOne`/`NotExactlyOne`-`FieldFilled`) |
| `DECLARED` | the **full declared** range — an un-instantiated rep counts as empty, so `AllFieldsFilled(/Items*)` is TRUE only when every *declared* rep is filled | `AllFieldsFilled` / `NotAllFieldsFilled` (IF49 / `GroupScopedFieldFillDiffTest`) |
| `MIXED` | instantiated-≥1-filled AND declared-≥1-empty | `FieldsNotCollectivelyFilled` (IF49) |

This is a **deviation dimension** (like `emptyOperand`, but the default is not per-kind — it is the single natural `INSTANTIATED`). Only the two families that deviate carry a claim: `All`/`NotAll` (`DECLARED`) and `FieldsNotCollectivelyFilled` (`MIXED`). The **count quantifiers carry no claim** — deliberately, because DECLARED vs INSTANTIATED is *observationally identical for firing* there: an un-instantiated rep contributes no filled cell, so "is there ≥ 1 filled / are there 0 filled / how many filled" is unchanged. (The range does bite their *polarity* — the fillable declared tail — which is the `polarity` dimension, not firing.) Each claim's probe instantiates 2 of the 50 declared `Items` rows, so a DECLARED / INSTANTIATED slip flips `fires`.

### 3c. The `aggregateIdentity` dimension

**The question it answers:** what does a value-folding aggregate fold to over an **all-empty selection**? This is the terminal case `emptyOperand` deliberately leaves out (§4a — "one operand empty" vs "the WHOLE selection empty"). One claim per operator (`AggregateIdentityClaim`), but per **element kind** (like `emptyOperand`, `value` XOR `perKind`), vocabulary `AggregateIdentity`:

| value | meaning | canonical lock |
|---|---|---|
| `ZERO` | the empty fold is the fillable **0** — a comparison against 0 fires | `Sum`/`MaxValue`/`MinValue`/`SumOfProducts` (IF51, KERNEL-FINDINGS §1); a NUMBER `Min`/`Max`/`FirstFilledValue` (IF80) |
| `NO_VALUE` | the empty fold yields **no value** (a NULL date / owner-kind NOT-GIVEN) — a comparison cannot fire; a computation CLEARS | a temporal `Min`/`Max` (IF58 / `DateAggregateDiffTest` + `TemporalExtremaDiffTest`, the kernel's `DateCombiner`); every non-number kind admitted by `FirstFilledValue` (IF83 / `FirstFilledValueKindDiffTest` + `FirstFilledValueAdditionalKindDiffTest`) |

The per-kind operators (`Min`, `Max`, `FirstFilledValue`) carry a `perKind` split (`{number: ZERO, date/time/date-time: NO_VALUE}` for `Min`/`Max`; NUMBER `ZERO` and all nine other admitted kinds `NO_VALUE` for `FirstFilledValue`) — the very fact this dimension exists to record (`Sum(∅) = 0` **vs** another kind's no-value). The consumer (judge): `Sum(∅) >= 0` always holds, but `Min(temporal values…) >= X` is vacuous when empty — not equivalent. Each `perKind` branch has exactly one executable probe over an all-empty selection and the guard rejects a missing, duplicate, or unknown branch probe. The probes use **non-index** fields (an index field's empty cell is not-check-relevant — the invalid class, IF86, not emptiness). Populated over the seven value-folding aggregates; the counts (`NumberOf…`) carry no claim — a count of nothing is trivially 0 (obvious, and their empty-*operand* behavior is the `emptyOperand` dimension).

### 3d. The `polarity` dimension

**The question it answers:** how does an operator propagate the **directional fillability** of an unfilled operand — the kernel's `kannGroesserWerden`/`kannKleinerWerden` bits (the runtime `Polarity` object)? Combined with the enclosing comparison's direction, that decides whether a fired comparison is a **VALUE** or an **OMISSION** error. One value per operator (`PolarityClaim`), vocabulary `FillPolarity`:

| value | meaning | canonical lock |
|---|---|---|
| `SYMMETRIC` | the result can grow AND shrink off an unfilled operand → a fired comparison is OMISSION **in any direction** | the date diffs `DifferenceIn*`, the date/time extractors `*FromDate`/`*FromTime` (IF73 / `EmptyOperandFiringDiffTest`) |
| `GROW_ONLY` | the result can only grow → a fired `>=`/`>` is **VALUE** (filling cannot clear it), a fired `<`/`<=`/`==` is **OMISSION** | `Length` (IF73), the fill-count family `NumberOfFilled…`/`NumberOfDifferentValues` (IF51), `RangeAsNumber` (IF71) |

This dimension needs more than firing: the claim is about *how* it fires, so each probe pins a fired `F(empty) >= 0` **and an `outcome`** (VALUE/OMISSION, §5a) that the lane asserts against the kernel's message type. The UP direction (`>= 0`) is the discriminator — SYMMETRIC types it OMISSION, GROW_ONLY types it VALUE (the DOWN direction is OMISSION for both). The consumer (judge): a `SYMMETRIC` firing is always fillable (an omission you could still satisfy), a `GROW_ONLY` `>=` firing is a hard VALUE error — a real equivalence/severity distinction.

**Scoped out — the compositional-fill operators carry no scalar claim** (a deliberate boundary, not an omission): arithmetic `+`/`−`/`×`/`÷`/`^` (IF69), the value functions that transform a fillable argument (`Round*` preserves, `Abs` transforms, operand-list `Min`/`Max` combine — IF71), the starred aggregates (`Sum`/`MaxValue`/`MinValue`, whose all-empty identity is symmetric but whose present-value direction depends on the operator and, for Sum, the signedness of each missing declaration), and the filter-derived date-range overlaps (IF72). Their fill is a **truth table over the operands' fills/signs** or is computed **per evaluation** — not a single value a per-operator facet can carry, so it is represented by the differential locks (`ArithmeticFillPolarityDiffTest` / `FunctionFillPolarityDiffTest` / `NumericAggregatePolarityDiffTest` / `DateRangeOverlapSemanticsDiffTest`), not this dimension. A richer (non-scalar) representation is a future option if a consumer needs it.

## 4. The `emptyOperand` dimension

**The question it answers:** what does this operator do when a given operand is *empty* (a present-but-valueless field)? This is the #1 recurring semantic failure class (empty-as-0 firing, judge false-positives, incomplete lints), and the per-operator answers were already differentially locked by the WI-T campaign — the facet is their machine-readable projection.

### 4a. The value vocabulary (`EmptyBehavior`)

Derived from the locked findings (each value names its canonical lock), not a priori:

| value | meaning | canonical lock |
|---|---|---|
| `AS_ZERO` | the empty operand participates as the fillable **0**; the evaluation proceeds and can fire | `EmptyValueLawsTest` (number, `Length`), `EmptyOperandFiringDiffTest` (IF73: extractors, diffs) |
| `AS_FALSE` | participates as **False** | `EmptyValueLawsTest.emptyConfirmIsTreatedAsFalse` |
| `AS_EMPTY_STRING` | participates as **""** | `EmptyValueLawsTest.emptyStringInAConcatenationIsEvaluated` |
| `NOT_EVALUATED` | the enclosing comparison/predicate yields **no verdict** — cannot fire on this emptiness | `EmptyValueLawsTest` (string/boolean/date), KERNEL-FINDINGS §8 (value-list 3VL) |
| `SKIPPED` | the operand is **dropped from the operator's set/fold**; the operator still evaluates over the rest | IF51 (`MinValue(20, ∅) == 20`), IF58 (date `Min`/`Max` operands), IF60 (VLQ cells) |
| `EVALUATES_FALSE` | the predicate's verdict is a definite **False** (vs `NOT_EVALUATED`'s no-verdict) | IF72 (`AtLeastOneDateRangeOverlaps` empty scalar) |

Deliberately **not** in this dimension: what an *all-empty selection* folds to (`Sum(∅…) = 0` vs a date aggregate's no-value) — that is the `aggregateIdentity` dimension (WI-5b); and how a *formally-invalid* (malformed / constraint-violating) operand behaves — invalidity is not emptiness (IF84/IF85's not-check-relevant machinery), a possible future dimension.

### 4b. The header `emptyOperandDefaults` table

The per-kind defaults (KERNEL-SEMANTICS §2's type-asymmetry table) are stated **once**, as the catalog header key `emptyOperandDefaults` — kind token → `{behavior, basis, note, probes}`:

| kind token | default | the trap it encodes |
|---|---|---|
| `number` | `AS_ZERO` | `[Quantity] < 100` fires on an empty Quantity (0 < 100) — in a comparison *and* inside arithmetic |
| `confirm` | `AS_FALSE` | `[TermsConfirmed] != True` fires on an empty confirm |
| `string`, `enum`, `boolean`, `date`, `time`, `date-time` | `NOT_EVALUATED` | absence never satisfies a value test — absence needs `FieldNotFilled` |

Kinds outside the table: `date-range` (no comparison/value position exists — the range predicates/functions carry their own facets), `date-fragment` (rides the `date` row — a fragment is completed to a plain date before evaluation, IF57), `custom` (validity is a delegated SPI). `OperatorCatalog.defaultEmptyBehavior(ValueKind)` is the typed lookup.

The defaults hold **within a content-bearing row** — an entirely empty repeatable row is not evaluated in full validation (KERNEL-FINDINGS §2 / IF53; partial validation's relevance gate differs, IF54). The probe lane bakes this in by always probing on a filled row (§5).

### 4c. A claim's shape

```jsonc
"semantics": {
  "emptyOperand": [
    {
      "operand": "0",                 // "all", or a 0-based index into the entry's `operands`
      "behavior": "AS_ZERO",          // XOR perKind — exactly one of the two
      "perKind": { "number": "AS_ZERO", "date": "SKIPPED" },   // when the behavior varies by operand kind
      "basis": "IF73 / adapter.laws.EmptyOperandFiringDiffTest",
      "note": "why this deviates from the kind default",
      "probes": [ { /* §5 */ } ]
    }
  ]
}
```

`operand` selects the claim's scope — the value-list membership predicates split subject (`"0"`) vs entries (`"1"`); the variadic families use `"all"`. `perKind` exists because one operator can differ by operand kind: the operand-list `Min`/`Max` substitute an empty *number* as 0 (it can win the Min) but *skip* an empty DATE/TIME/DATETIME operand (IF58/IF204).

## 5. The verification lanes — how the catalog cannot rot

Every executable artifact in the catalog is CI-executed against the **real kernel**; the structural guards make the executable artifact mandatory. A claim nobody probes cannot ship.

| lane | guard | what it executes |
|---|---|---|
| N7 examples | `catalog.CatalogExamplesAreKernelValidTest` | every `validExample` kernel-ACCEPTED, every `invalidExample` REJECTED (with its `error` code when `MVK_*`) |
| N7 computations | `catalog.CatalogComputationExamplesAreKernelValidTest` | every `computationExample` runs as a real computation |
| **facet probes** | `adapter.laws.CatalogFacetProbesTest` | every `emptyOperandDefaults` probe and every `semantics.*` dimension probe (`emptyOperand`, `boundary`, `iterationRange`, `aggregateIdentity`, `polarity`): the probe rule is (1) kernel-**ACCEPTED**, (2) **fires ⟺ `fires`**, and (3) when it carries an `outcome`, the fired message's **value-vs-omission type matches** — all through the kernel runtime (`RuntimeLaws`) |
| **corrective probes** | `catalog.CatalogExamplesAreKernelValidTest` (corrective reject lane) | every `correctives[].probe.dsl` kernel-**REJECTED with exactly its `code`** |
| structural | `catalog.OperatorCatalogTest` | every claim, per dimension: well-formed value (emptyOperand/aggregateIdentity: valid selector + value XOR perKind; boundary/iterationRange/polarity: a value; polarity additionally: every probe pins an `outcome` and `fires: true`), non-blank basis, **≥ 1 probe**, well-formed probe paths; every corrective: known operand-class tokens, fix, reject probe |
| keyword / construct parity | `KeywordParityTest`, `adapter.KernelConstructParityTest` | catalog ↔ lexer terminals / parsetree construct enums (unchanged) |
| catalog ↔ DSL bridge (G3) | `catalog.DslCatalogBridgeTest` | the `dslEntry` map ⟺ the code-side `catalog:<Id>` markers (bidirectional **set-equality**, read through the loaded record); every member **resolves** to a public authoring method; every operator is mapped **or** an explicit empty + `dslEntryNote` with no marker (absence is impossible). On mismatch it **dumps** the marker-derived projection — author by derivation, never hand-author twice |

### 5a. The firing-probe contract (`FacetProbe`)

Probes are **fixture-bound to `models/order.dm.json`** (the N7 fixture — one shared vocabulary of fields covering every kind, including repeatable `Items` and date-range fields) and executed as a rule on `/Order`:

| field | meaning |
|---|---|
| `dsl` | the probe rule's error condition (canonical EN DSL against the Order fixture) |
| `errorField` | the rule's error field, **relative to `/Order`** (e.g. `"Quantity"`, `"Items/Count"`) — explicit by contract, no inference |
| `rows` | extra group rows to instantiate; a path part may carry a 1-based repetition index (`"/Order/Items[2]"`); `/Order` itself is implicit |
| `given` | filled cells, absolute path → value (same `[n]` indexing) |
| `empty` | **instantiated-empty** cells — present but valueless, how a form renders an unfilled field |
| `fires` | whether the rule must fire |
| `outcome` | (optional, `polarity` only) the value-vs-omission **type** the firing must carry (`VALUE`/`OMISSION`); when set the lane asserts `fires: true` and the fired message's type — a fill-polarity claim is about *how* it fires, not just that it does |

The lane always adds the content-bearing filler `/Order/CustomerName = "Acme"` (unless the probe itself uses CustomerName), so every probe runs on a filled row — the §4b precondition. Each probe is first validated (`RuleValidator` must ACCEPT the injected rule) so a `fires: false` probe can never pass because its DSL was silently broken — the false-green trap.

**Probes vs law tests — deliberately both.** The `adapter/laws` differentials lock the *engine finding*; the catalog probes lock the *catalog claim*. The two artifacts rot independently (a JSON edit doesn't touch a law test), so each carries its own executable guard; the claim's `basis` cross-references the law so the derivation is auditable and never re-derived blind.

**Free-form prose has no generic semantic parser.** `meaning`, `constraints`, and `gotchas[].text` are published semantic prose, so changing any of them is a high-risk surface change and receives the cold adversarial review required by [`CLAUDE.md`](../CLAUDE.md#cold-adversarial-review-gates). `CatalogProseReviewGuardTest` derives every live prose unit, content-binds it by locator + digest, and requires a separately authored classification: `REJECTION` for a model-check refusal, `ADMISSION` for a positive static/model-check domain assertion, `RUNTIME` for evaluation behavior, or exclusively `DESCRIPTIVE`. A new, removed, moved, or changed unit therefore fails unclassified; the registry never copies the prose. Every `constraints[]` unit must carry `ADMISSION` or `REJECTION`, so a constraint cannot hide as runtime-only. A narrow refusal-word tripwire (`reject`/`refuse`/`not allowed`/`must not`) also requires `REJECTION` outside that lane. This is deliberately a recall-limited positive control, not an NLP claim: synonymous or implicit refusals still depend on the exhaustive constraint rule and authored cold review. A rejection unit must carry a structured claim with a rejected outcome—plain test evidence is categorically insufficient. Admission evidence must be either an owner-bound claim probe whose selected side asserts `ACCEPTED`, or an exact method in the dedicated `validate/laws` package that directly reaches `KernelLaws`/`RuleValidator`; runtime tests and facets cannot discharge it. Runtime evidence remains a reviewed test or owner-bound facet link. The guard also requires bidirectional equality between the live kernel-probe inventory and all prose references, so an orphan probe or an unknown reference fails. Claim-probe evidence records the selected trigger/control side plus exact verdict/code, and a same-shaped probe owned by another operator is rejected. Literal `MVK_*` claims additionally require an exact code/outcome claim. Positive controls cover missing/changed/stale/out-of-range units, arbitrary-test rejection or admission substitution, runtime-only constraints, explicit refusal wording without rejection classification, empty evidence, mixed-axis omissions, invalid descriptive mixtures, unresolved facets, wrong outcomes, stale claim locators, cross-operator borrowing, and both directions of probe-inventory drift.

## 6. `correctives` — plural code-specific rejection evidence

**The incident this kills** (D1, [eval/RESULTS.md](../eval/RESULTS.md) 2026-06-17): `MVK_INVALID_TYPE_FOR_COMPARISON` was enriched by a catalog-join **by code only**, so the ordering operators' `invalidExample.fix` — written for the string-vs-string trigger ("compare strings with == / !=") — was served verbatim on a **date-vs-ISO-literal** trigger, where it is wrong (the correct move is a `dd.MM.yyyy` literal). A temporary 2026-06-17 stopgap widened that primary fix to an omnibus union. The plural corrective model below retires the duplication: the primary example again carries its exact string-ordering remedy, the date-literal remedy has its own probe, and only an unresolved-owner diagnostic derives their safe union.

An entry's primary `invalidExample` and plural `correctives` form one rejection-evidence set. Every corrective is keyed by `code`, carries a kernel-rejected `probe.dsl`, and optionally narrows to a comparison-operand shape. An empty `whenOperands` means the fix applies to the operator-specific trigger demonstrated by the probe; a populated list enables the D1 operand-aware selection:

```jsonc
"correctives": [
  { "code": "MVK_INVALID_TYPE_FOR_COMPARISON",
    "whenOperands": ["string", "string"],
    "fix": "ordering needs numbers or dates — compare the strings with == / != instead",
    "probe": { "dsl": "[CustomerName] < [ProductCode]" } },
  { "code": "MVK_INVALID_TYPE_FOR_COMPARISON",
    "whenOperands": ["date", "string-literal"],
    "fix": "one side is a DATE — write the literal in dd.MM.yyyy form (e.g. \"31.12.2024\"), not ISO",
    "probe": { "dsl": "[OrderDate] < \"2024-12-31\"" } }
]
```

- **`whenOperands`**, when populated, is an unordered multiset of operand-class tokens: a value-kind token (`number`, `string`, `date`, …) matches a field/expression of that kind; `string-literal` / `number-literal` match literal nodes. Empty means no comparison-operand classifier is required.
- **The catalog join reads both evidence lanes.** `operatorsForError`, the diagnostic enricher, and `SpanResolver` combine the primary `invalidExample` with every matching `correctives[]` row. A secondary code can therefore produce a fix and explanation instead of disappearing merely because the entry's singleton primary example is already occupied.
- **Operand selection lives in `lint.SpanResolver`** (per the S6 finding): the read pass holds the typed `Condition` AST, pins the operator by catalog∩usage intersection, classifies the pinned node's actual operands (kinds sit on `FieldRef`, literals are typed nodes — a `DateLiteral` classifies as `string-literal`, since it renders as a quoted literal and reaches a corrective only when the kernel refused its text as a date), and serves the matching corrective. No operand match → the code-specific fallback across both evidence lanes stays the fallback. The enricher remains operand-blind by design, but it now sees code-specific corrective probes and can serve one once the message or structured location establishes the operator.
- **The selection's reach — every path where a typed AST exists** (the built-rule `RuleDiagnostics` path, converted foreign reads) *plus the kernel-REJECTED text path, since G30*. A rejected *text* candidate (`rule check --condition …`, `rule add`, `rule patterns`) has **no rulekit AST**: the kernel's `IRuleParser.parseCondition*` returns `null` for an invalid condition *by contract* (empirically confirmed 2026-07-08 — this narrows the S6 finding's "foreign-rule fallback via `parseCondition`", which holds only for valid conditions). **G30 closes that gap at the CLI seam** (`cli.interp.TextConditionCorrective`, the only module that may depend on `:interpreter`): it *tolerantly* parses the candidate text with the **kernel-free interpreter parser** (which parses ill-typed text fine), classifies the pinned ordinary-comparison or pattern-comparison operands against `ModelIndex` field kinds, and calls the SAME `OperatorCatalog.correctiveFix(operatorId, code, operandTokens)` the typed `SpanResolver` uses. This includes the distinct pattern slots: a field or number in the right regex slot and a non-String field in the left value slot receive their own corrective. **Fallback rule (never worse than today):** on any parse failure, classification failure (an absent field, a kind outside the `kindToken` vocabulary), or ambiguity (no unique candidate operator, or the operator appears more than once), the diagnostic is returned **unchanged** — the union fix stays; it never narrows a union to a wrong corrective.
- **The fallback is silent in the output but not indistinguishable (2026-08-01).** "Never worse than today" is true of the *corrective* and was false of the *visibility*: the parse was wrapped in `catch (RuntimeException) -> Map.of()` and the caller treated an empty map exactly like "no unique candidate", so four different causes — unreadable text, an unbuildable `ModelIndex`, text carrying no comparison, and a genuine absence of a unique candidate — produced one identical result. That converts any future parser gap into silence, and it cost a debugging session on 2026-07-31 when a green test was provisionally blamed on a parse failure that `FilterConditionParseTest` disproved. `TextConditionCorrective` now returns a typed **`Outcome`** (`UPGRADED` · `PARSE_FAILED` · `MODEL_INDEX_FAILED` · `NO_COMPARISON` · `NO_UNIQUE_CANDIDATE`) alongside the diagnostics, and the parse throws instead of answering with an empty map, so the cause is a fact rather than an absence. **The published envelope is deliberately unchanged:** the harm on record was a developer's, and an author whose condition will not parse already holds the kernel's own rejection and cannot act on our parser's limits — promoting `Outcome` to an `INFO` advisory is a published-surface change with its own decision, not a side effect of making the fact knowable. Locked by `cli … interp.TextCorrectiveOutcomeTest`, whose discriminating pair is two inputs that yield the *same* diagnostics and must still differ by outcome.
- **Verification:** each corrective's `probe.dsl` must be kernel-rejected with exactly `code` (§5's reject lane); every `fix` additionally needs a **repair witness** in `operator-fix-probes.json` — a DSL the advice produces that the kernel ACCEPTS, content-bound by digest and ordered canonically (`CatalogKernelClaimEvidenceTest`), so advice that would not actually repair the rule cannot ship; the `SpanResolver` selection is law-tested on the D1 scenario (typed path); and the text path is locked by `cli … RuleCheckOperandCorrectiveTest` (the D1 cases through `rule check`) + `cli … interp.TextConditionCorrectiveTest` (the fallbacks).

**A refusal an operator owns belongs in `correctives`, not in `constraints` prose.** Both are published and both are served by `operators <id>`, but only the corrective is *executed* end to end — probe rejected with exactly its code, and `fix` proved repairable — where a constraint unit carries a reviewed classification and no run of its own. So when a measured refusal needs a home, the corrective is the stronger surface and the cheaper one: it adds no prose unit to the review denominator. The 2026-09-07 slice (G77) placed five such codes this way — the power's derived scale on `Arithmetic`, the extrema's `MVK_NOT_SORTABLE` sortable-class and group-expansion rule, `MVK_PARTIAL_DATE_NOT_ALLOWED` at the three decoding carriers, DATE_RANGE's refusal at the distinct count, and `AtLeastOneDateRangeOverlaps`' scalar-slot class. Reserve `constraints` for what shapes an operand list *before* any code fires.

**A cross-context code is served by the enricher, so the catalog alone does not close it.** `StructuralKernelFix` wins over the catalog join for codes several constructs raise (KERNEL-INTEGRATION-NOTES), which means a corrective for such a code reaches `operators <id>` and the typed `SpanResolver` but never a rejection. `MVK_INVALID_COMPARE_DEC_PLACES` is the live case: the power's derived-scale rule had to go into the enricher's hint as a third context as well, or an author meeting the rejection would still not learn why their side reads `?`. Check the precedence before assuming a catalog edit is user-visible.

## 7. Serving — `dmtool operators`

`dmtool operators <id>` serves the full entry including the machine tier: `semantics` (the facet claims across **every** dimension — `emptyOperand`, `boundary`, `iterationRange`, `aggregateIdentity`, `polarity`; probes elided, they are CI detail; the per-dimension `value`/`behavior`/`perKind`, `basis`, `note` travel), the header defaults ride the bare `operators` list response as `emptyOperandDefaults` (kind → behavior), `correctives` (code, optional whenOperands, fix), `gotchas` **with their `kind` and local `seeAlso` links**, and the **`dslEntry`** authoring bridge (§7a). The payload is part of the self-describing surface (CLI-SPEC [§2](CLI-SPEC.md#self-explaining)): a cold agent must be able to learn "an empty number fires this comparison", "the tolerance band is strict", and "`<` and `<=` differ" from the binary alone. It is also the **eval judge's ground-truth oracle** (§8) — the judge reads this block to reason about semantic equivalence.

**Primary examples favor the simplest composition that prevents a known authoring failure.** `Having` therefore owns the ordinary filtered-count recipe (`NumberOfFilledFields(Group*/Field Having …)`) and the rejected bare-group counterpart (`NumberOfFilledGroups(Group* Having …)` → `MVK_NO_GROUPS_ALLOWED`), while `CurrentRepetition` owns the advanced multi-star correlation example. The entries cross-link through `seeAlso`, and N7 executes both examples against the real kernel. This division keeps the common first use discoverable without dropping the higher-order correlation evidence.

### 7a. The `dslEntry` authoring bridge (G3)

The catalog answers "what does operator X mean?"; `dslEntry` closes the reverse dead-end — "which rulekit call authors X?" — for the developer/agent driving the typed API. It is **derived-complete over all 110 operators** with a single source of truth and a two-way guard:

- **Source of truth = code.** Each public authoring method carries a `catalog:<Id>` marker in its Javadoc (a `differsWithTolerance` renders four tolerance ops, so a marker may name several ids comma-separated; interface default methods — `Condition.and`/`or` — are covered too). The marker sits beside render-tested code, so the mapping lives where it's provably correct.
- **`dslEntry` in `operators.json` = the projection.** Populated by **author-by-derivation**: the guard (`DslCatalogBridgeTest`) dumps the marker-derived `id → {method}` map; that dump is injected into the JSON. The two are then locked by bidirectional set-equality — they cannot drift, and the map is never hand-authored twice.
- **All 110, no silent gap.** An operator with no standalone factory — the constants `True`/`False` (emitted as a boolean/confirm comparison operand or in `NumberOfValueInFields`) and the `ValueAsDate` day keywords `FirstDay`/`LastDay` — carries an explicit empty `dslEntry` + a `dslEntryNote` reason and **no** marker; the guard enforces that pairing, so "unmapped" is always either a real method set or an explained absence.

`operators <id>` serves `dslEntry` (and `dslEntryNote` when present), so the bridge is discoverable from the binary. `operators` is `CONTRACT_EXEMPT` (it renders a catalog payload, not the result envelope), so serving it is additive — no schema change.

## 8. Consumers (what makes the facets pass the §21D consumer-exists razor)

| consumer | what it reads | status |
|---|---|---|
| `lint.Linter` — `RK_UNGUARDED_NUMBER_COMPARISON` | header defaults + `AS_ZERO` claims: computes the value a comparison side takes when a field inside it is empty, flags precisely (no hand-listed operator patterns) | WI-5a |
| `lint.SpanResolver` — fix selection | `correctives` + the typed AST operand classification | WI-5a |
| `dmtool operators` | the whole machine tier (§7) — all five dimensions | WI-5a (emptyOperand); WI-5b (boundary/iterationRange/aggregateIdentity/polarity) |
| eval judge (`benchmark.workflow.js`) | the served `semantics` block as its equivalence oracle — empty-operand variants, `boundary` (`<` ≢ `<=`), `aggregateIdentity` (a date `Min`(∅) can't fire), `polarity`; wired into the rule + computation judge prompts, verify-by-execution primary. The served surface is guarded by `OperatorsVerbTest` | WI-5b |
| facet → conformance cases | each claim's kernel-executed **probe** (§5) IS its conformance case — accepted + fires⟺declared (+ outcome for polarity) against the real kernel | WI-5b |

## 9. Population state & boundaries (WI-5a → WI-5b)

**WI-5b dimensions** slot in additively as single operator-level claims (§3a/§3b). Shipped so far:
- **`boundary`** — the ten threshold/interval operators; STRICT for `<`/`>`/tolerance, INCLUSIVE for `<=`/`>=`/date-range-overlap (CLOSED).
- **`iterationRange`** — the field-fill quantifier deviations from the INSTANTIATED default: `AllFieldsFilled`/`NotAllFieldsFilled` = DECLARED, `FieldsNotCollectivelyFilled` = MIXED (IF49).
- **`aggregateIdentity`** — the seven value-folding aggregates' all-empty fold: `Sum`/`MaxValue`/`MinValue`/`SumOfProducts` = ZERO; operand-list `Min`/`Max` = per-kind {number: ZERO, date/time/date-time: NO_VALUE}, while `FirstFilledValue` carries its own supported-kind split (IF51/IF58/IF80/IF83/IF204).
- **`polarity`** — the fill-propagation class of the value functions with a clean per-operator scalar: 13 SYMMETRIC (the date diffs + date/time extractors, IF73), 5 GROW_ONLY (`Length`, the fill counts, `RangeAsNumber` — IF73/IF51/IF71). Each probe pins the outcome TYPE. The compositional-fill operators (arithmetic, `Round*`/`Abs`/operand-list `Min`/`Max`, the sign-dependent starred aggregates, the date-range overlaps) are scoped out — their fill is a truth-table/per-eval, not a scalar (§3d).

**All four WI-5b dimensions shipped.** `FieldValueAsNumber`'s empty operand and symmetric polarity are now characterized by SPEC-2026-07-22-13. The remaining `emptyOperand` gaps from §9a (the `Date`/`Time`/`DateTime` constructions with an empty part; `RangeAsString`/`ValueAsDate`/`AddDays`-family empties) stay open — no differential lock yet; characterize before claiming.

### 9a. The WI-5a `emptyOperand` slice

WI-5a populated the `emptyOperand` dimension for the families its consumers touch — the comparison defaults (header), the value/count aggregates, the value functions (`Length`, the date/time extractors, the `DifferenceIn*` family, `Min`/`Max`, `FieldValueAsString`), the value-list membership + quantifier predicates, the date-range overlap predicates, and `Concatenation` (a new entry — the intrinsic `+` previously had no catalog home). The lane earned its keep immediately: probing the claims surfaced the index-field carve-out (an INDEX field's empty cell is mandatory-empty → not-check-relevant — the *invalid* class, which poisons counts and `No…ValueList`; the probes deliberately use non-index fields) **and** a real interpreter divergence ([INTERPRETER-GAPS](INTERPRETER-GAPS.md) IG55: `NumberOfDifferentValues` over an index field's empty cell — kernel suppresses, interpreter fires). Not populated, with reasons:

- **`Date(d,m,y)` / `Time(h,m,s)` / `DateTime` constructions with an empty part** — kernel behavior uncharacterized (IF73 scoped them out; no corpus instance). Characterize before claiming — a facet must never state an unverified value.
- **`RangeAsString` / `ValueAsDate` / `AddDays`-family empty *operand* behavior** — no `emptyOperand` differential lock yet; characterize before claiming (and, where the interpreter disagrees with a fresh kernel probe, a WI-T gap row *first*). `RangeAsNumber` is closed separately: empty converts to 0 (`RangeCoercionDiffTest`) with GROW_ONLY polarity (`FunctionFillPolarityDiffTest`, IF71).

**Maintenance rules:** a new operator claim needs basis + probes in the same commit (the guards enforce it). An `operators.json` change is a **native-image re-capture trigger** ([NATIVE-IMAGE-GRAALVM §5.1](NATIVE-IMAGE-GRAALVM.md)). On a kernel bump the probes re-run against the new kernel automatically (they are ordinary CI tests — the [KERNEL-UPGRADE](KERNEL-UPGRADE.md) guardian net gains this lane for free).
