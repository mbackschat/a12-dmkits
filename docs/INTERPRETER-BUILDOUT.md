# Interpreter build-out — plan & coverage map

The working plan for growing the `:interpreter` module from the validated spike ([INTERPRETER-SPIKE-FINDINGS.md](INTERPRETER-SPIKE-FINDINGS.md)) into the full-blown evaluator the proposal commits to ([INTERPRETER-PROPOSAL.md](INTERPRETER-PROPOSAL.md) — built out **in-repo**, §8). This is the *method + architecture + order*; the **live coverage truth is the differential tests** (`adapter/…/laws/*DiffTest.kt`) plus a planned catalog-parity test, never a hand-maintained checklist here.

**Acceptance (both use-cases):**
1. **Native image** runs [`examples/cli-runtime.md`](../examples/cli-runtime.md) end-to-end (the `model eval` / `model compute` / `rule test` verbs, today `JVM_ONLY_VERBS`).
2. **Standalone** Kotlin/JVM + Kotlin/JS: full **and partial** validation, computations, and **single-rule / single-computation** eval over a document model.

Correctness throughout = **agrees with the real kernel** (the `RuntimeLaws` differential oracle).

## Current state (what the engine does today)

| area | today | gap to full-blown |
|---|---|---|
| **Conditions** | tree-walk over `Cond`/`Expr`; 3-valued logic; type-driven empty asymmetry; polarity (VALUE/OMISSION incl. omitted-tail) | — (solid foundation) |
| **Operators** | ~20 of 109 constructs (see below) | ~89 constructs across ~8 families |
| **Computations** | `compute()` → VALUE / CLEARED, kernel-verified (incl. empty-as-0 → `0`) | the ERRORED bucket (operand/computation error) + pre-conditions; awaits the formal-error increment |
| **Formal errors / suppression** | DATE (`datumFormatFalsch`) + NUMBER (`fuehrendesBlank`/`zahlHatUngueltigeZeichen`) gate → rule **suppressed**; `rule test` SUPPRESSED verdict + `suppressedBy`, kernel-verified | scale check (`zahlFalscheAnzahlNachkommastellen`, needs per-field scale) + the other formal codes + the German message-bundle tier |
| **Document model** | general `Document` (indexed placements, common-prefix resolution, star aggregation) — flat + nested-non-repeatable + one-level repetition, kernel-verified; spike's `SingleRowDoc`/`RepDoc` still present (consolidation pending) | multi-level repetition (resolution is general; only one level is kernel-tested) |
| **Model / instance loading** | structured `ModelDef`/`RuleDef`/`FieldDef` (interpreter owns its types); the consumer builds them | DM-JSON model → `ModelDef`; `{"fields":{…}}` instance → `Document` (dmtool via Jackson; a multiplatform JSON loader later) |
| **Public API** | `DmInterpreter.validateFull(doc)` — whole-document, kernel-verified | `validatePart` / `compute` / `testRule` / candidate-eval entry points |
| **Messages** | `$field.value$` value-interpolation, en/de (wired into `validateFull`) | `$field$` display-name tier; formal-error (German-keyed) bundles |
| **Parser** | recursive-descent subset (comparisons, And/Or, predicates, aggregates, patterns, paths/`*`) | full grammar (or kernel parse-tree-api) — §11 |

## Two tracks

The build-out splits cleanly, and the two are **largely independent**:

- **Track A — use-case-1 critical path (structural).** What `cli-runtime.md` needs is *depth*, not breadth: a **model loader**, an **instance loader**, a **general doc model**, **computations**, **formal-error/suppression**, the **public API**, and the **dmtool wiring**. `cli-runtime.md` itself only uses comparisons on numbers + dates (already supported) — so the operator set is *not* its blocker; the structure is.
- **Track B — use-case-2 breadth (operators).** The ~90 missing catalog operators, each added with a `*DiffTest` against the kernel. Needed for standalone "full" parity, not for the cli-runtime acceptance.

## Operator coverage snapshot

*Snapshot only — the diff tests are the truth.* Handled (~24): `And` `Or` `Not` · `LessThan` `LessThanOrEqual` `GreaterThan` `GreaterThanOrEqual` `Equal` `NotEqual` · `FieldFilled` `FieldNotFilled` `AllFieldsFilled` · `Min` `Max` `Sum` `MaxValue` `MinValue` `NumberOfDifferentValues` · `PatternMatched` `PatternViolated` · `DifferenceInDays` · arithmetic `+ - *` (`{ }` grouping; `/` `^` deferred). An unmodeled/external construct (e.g. `CustomCondition`) is *tolerated* by `validate` (reported `unsupported`), not a crash.

Missing families (~90), by group:

| family | examples | notes |
|---|---|---|
| **filled-predicates** (rest) | `GroupFilled` `GroupNotFilled` `AllGroupsFilled` `AtLeastOneFieldFilled` `NoFieldFilled` `NotAllFieldsFilled` `MoreThanOneFieldFilled` `NotExactlyOneFieldFilled` `FieldsNotCollectivelyFilled` `NumberOfFilledFields` `NumberOfFilledGroups` … (~15) | `GroupFilled`/`GroupNotFilled` are *already parsed* but error in `evalPred` — a partial-support bug to close first |
| **value-list / enum** | `FieldValueIncludedInValueList` `FieldValueNotIncludedInValueList` `AtLeastOne…` `No…` `NotAll…` `EnumCategory` (~6) | membership against a declared value list |
| **pattern** | `PatternMatched` `PatternViolated` (2) | regex on string fields |
| **arithmetic** | `Arithmetic` `Abs`/`AbsValue` `RoundUp/Down/Accounting`(+`Value`) `SumOfProducts` `Length` `RangeAsNumber/String` `FieldValueAsNumber/AsString` `NumberOfValueInFields` (~15) | numeric expressions/coercions |
| **date / time** | `Today` `Now` `Date` `DateTime` `Time` `AddDays/…/Years` `DifferenceIn…` `DayFromDate` `MonthFromDate` `YearFromDate` `QuarterFromDate` `FirstDay` `LastDay` `BaseYear` `HoursFromTime` … (~30) | the largest family; needs the `java.time`⟷luxon shim (§5) |
| **date ranges** | `DateRange` `DateRangesOverlap` `AtLeastOneDateRangeOverlaps` `Start/EndOfDateRange` `DiffersWithToleranceRangeN` (~8) | |
| **uniqueness** | `FieldValuesNotUnique` `RepetitionNotUnique` (2) | cross-row |
| **misc / scope** | `CurrentRepetition` `FromScope` `Having` `SemanticIndex` `FirstFilledValue` `CustomCondition` `True` `False` `Valid`/`Invalid`/`ValidType`/`InvalidType` (~12) | |

## cli-runtime.md critical path (the minimal end-to-end slice)

The demo's scenarios pin Track A's deliverables. **The actual `order-ruled.dm.json` rules were inspected (2026-06-23)** — they need more than the demo prose implies:

| scenario | rule / op it actually uses | interpreter status |
|---|---|---|
| `model eval --rule /Order/DeliveryNotBeforeOrder` | `AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0` | ✓ — `DifferenceInDays`, `AllFieldsFilled`, `<` all kernel-verified (`DifferenceInDaysDiffTest`) |
| `rule test` (same rule) → fired/passed/**suppressed** | same + the formal-error gate (`not-a-date` → `datumFormatFalsch`) | ✓ — suppression + the date rule both verified |
| `model eval --condition "[/Order/Quantity] > 100"` | a candidate comparison | ✓ (`evalCandidate`) |
| `model compute` (`[BaseFee]`, empty-as-0) | `[BaseFee]` | ✓ (`compute`) |
| (model also contains) `EligibilityCheck` | `CustomCondition ExternalEligibility` | **uninterpretable by design** (external) — `validate` *tolerates* it (reports `unsupported`, fires the rest), like the kernel-adapter's `Opaque` ✓ |
| (model also contains) `PostalCodeFormat` | `… PatternViolated "[0-9]{5}"` | ✓ |

**Every interpretable rule in cli-runtime's models is now kernel-verified.** The remaining cli-runtime work is purely integration + toolchain: a **DM-JSON → `ModelDef` loader** (consumer-side, native-safe via the kernel read tier) and the **dmtool wiring + native build**.

## Prioritized order

1. ✅ **Public API + general doc model** — `ModelDef`/`RuleDef`/`FieldDef` + `DmInterpreter.validateFull` over a general `Document` (`ValidateFullDiffTest`).
2. ✅ **Single-rule eval** — `testRule` (FIRED/PASSED) + `evalCandidate` (`TestRuleDiffTest`).
3. ✅ **Computations** (`compute()` → value/cleared, empty-as-0) — `ComputeDiffTest`.
4. ✅ **Formal-error / suppression** — the `rule test` SUPPRESSED verdict + `suppressedBy` (`SuppressionDiffTest`); DATE + NUMBER formal codes so far.
5. **dmtool wiring + native image** → the cli-runtime acceptance (use-case 1). The remaining headline step: a **DM-JSON → `ModelDef` loader** (consumer-side, native-safe via the kernel read tier in `:adapter`/`:cli`, NOT the Groovy runtime), route the CLI's `JVM_ONLY_VERBS` (`model eval`/`compute`, `rule test`) through `DmInterpreter`, re-capture native reflection metadata, native build + `examples/cli-runtime.md` showboat on the native image. *The full semantic content of those verbs is already kernel-verified (1–4); this step is integration + the native toolchain.*
6. **Operator breadth** (Track B) — family by family, each `*DiffTest`-locked, toward full catalog parity (use-case 2). In progress: `PatternMatched`/`PatternViolated`, arithmetic `+ - *` shipped. Add a **catalog-parity test** so an unhandled operator fails the build rather than silently lurking; add division/power with their scale/div-by-zero semantics.
7. **Partial validation** (`validatePart`) + the **message tiers** (display-name, formal-error bundles).
8. **Doc-model consolidation** — fold the spike's `SingleRowDoc`/`RepDoc` onto the general `Document`.

## Input contract / API (design)

The interpreter owns its types (proposal §6/§7) and takes a structured model + instance — the *consumer* supplies JSON parsing (dmtool via Jackson; a multiplatform JSON loader for standalone JS is a later, additive increment, so the core stays lean):

```
ModelDef(fields: List<FieldDef>, rules: List<RuleDef>, computations: List<ComputationDef>)
  FieldDef(path, kind)                      // the metadata bridge (kinds drive empty-asymmetry + formal errors)
  RuleDef(path, group, conditionText, errorCode, errorEntityRelPath, severity, messages)
  ComputationDef(targetPath, expressionText, …)

DocumentInstance                            // the {"fields":{…}} value tree, general nesting/repetition

DmInterpreter(model)
  validateFull(doc): List<Message>          // every rule that fires
  validatePart(doc): List<Message>          // + the 3VL suppression contract
  compute(doc): List<Computed>              // value / cleared / errored
  testRule(rulePath, doc): Verdict          // fired / passed / suppressed
  evalCandidate(rule, doc): Verdict         // an injected, non-stored rule
```

This keeps the engine kernel-free (the purity lint holds) and lets dmtool adapt *to* it.
