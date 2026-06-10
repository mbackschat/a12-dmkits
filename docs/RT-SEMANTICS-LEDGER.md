# RT-semantics ledger — the runtime evaluation core, classified

The classified inventory of the kernel's **runtime evaluation core**: every top-level class of `com.mgmtp.a12.kernel.core.rt._30_8.internal.core` (the `kernel-core-runtime-30-8` artifact — the code that actually *evaluates* conditions and computations on document data). This is instrument 6 of the anti-whack-a-mole discipline (see `CLAUDE.md`): where the [MVK ledger](MVK-LEDGER.md) classifies every *diagnostic* the kernel can raise, this ledger classifies every *evaluation-semantics carrier* — so behavioral semantics (null/empty handling, comparison typing, aggregation uncertainty, date arithmetic) get mined **proactively from the source** instead of discovered by tripping over them.

**CI lock:** `adapter.RtSemanticsLedgerTest` scans the test classpath for the package's top-level classes (version-segment by pattern, so a kernel bump that renames `_30_8` still matches) and fails on any class this table misses *or* any row whose class is gone. A kernel upgrade that adds an evaluation class therefore lands in the queue loudly.

## How a row resolves

The resolution path is the proven MVK-ledger loop, with one addition learned the hard way: **source-read proposes, law test confirms.** A row is drained by (1) reading the class and extracting its semantics in our own words, (2) cross-checking against the two kernel-team oracles (`KERNEL-SEMANTICS.md`, `kernel-ba.md`), (3) locking each observable behavior as a `RuntimeLaws` law (`adapter/…/laws/`), (4) recording the fact in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md). Twice already the kernel corrected a plausible source-read (the aggregate omitted-tail typing here; `MVK_LANGUAGE_DUPLICATE` in the MVK drain) — an unlocked source-read is a hypothesis, not a finding.

Classifications:

| classification | meaning |
|---|---|
| **mined** | semantics extracted and law-locked (this is the drained state — the row names its locks) |
| **already-locked** | the class's observable semantics were already pinned by existing laws/findings before this ledger existed |
| **canonical** | `KERNEL-SEMANTICS.md` states the behavior; we cross-reference rather than re-mine |
| **infrastructure** | caches / command wrappers / internal SPI interfaces — no operator semantics of their own |
| **out-of-scope** | unreachable from rulekit-authored models, with the reason |
| **queued** | semantics-bearing, drain pending (the work-list) |

## The ledger (kernel 30.8.1 — 28 classes)

| class | classification | semantics / locks |
|---|---|---|
| `BedingungsOperatorHelper` | mined | The comparison engine. Three-state results typed value-vs-omission (`adapter.laws.ErrorTypeLawsTest`); tolerance comparisons fire strictly beyond the range, boundary inclusive-tolerant (`ToleranceComparisonLawsTest`); `AddYears`/`AddMonths` leap-day correction + end-of-month clamp (`DateArithmeticLawsTest`); date/string null-or-empty compares false = §2 canonical (`EmptyValueLawsTest`). Source-only (not externally observable): operands are rescaled to scale 19 `HALF_UP` before comparing — model types cap fractional digits far below 19, so no legal model can surface it. Month extraction is 1-based. |
| `ValidierungsErgebnis` | mined | The three-state result algebra (`TRUE_WF` value error / `TRUE_AF` omission error / `FALSE_OR_UNKNOWN`) with `And`/`Or` truth tables: **And — omission wins; Or — value wins** (`ErrorTypeLawsTest`). The `IMessage.getMessageType()` channel is the public surface. |
| `Combiner` | mined | The aggregate contract: a `Having` filter ⇒ result always uncertain; an **omitted tail** of a repeatable group ⇒ result fillable — a starred aggregate is omission-typed even with every instantiated row filled, certain only when the declared repeatability is exhausted (`AggregateUncertaintyLawsTest`, probe-corrected 2026-06-10). |
| `NumberCombiner` | mined | `Sum`/`MinValue`/`MaxValue` skip empty operands and track growable/shrinkable per operator (Sum can shrink only for signed types). The 0-substitution / min-max-ignore halves are §2 canonical, already locked (`RepetitionLawsTest`); the uncertainty typing is `AggregateUncertaintyLawsTest`. |
| `FirstValueCombiner` | mined | `FirstFilledValue` is prefix-sensitive: operands after the first filled one are never read (a formal error there is invisible); an empty operand *before* it types the result omission; a formal-error operand before it suppresses the rule (`FirstFilledValueLawsTest`). |
| `NumberOfDifferentValuesCombiner` | mined | Distinct **filled** values only — an empty operand never bumps the count but marks it growable (`AggregateUncertaintyLawsTest`). Distinctness by value equality; type-homogeneity is parse-tier (MVK ledger). |
| `DateCombiner` | mined | Date `MaxValue`/`MinValue` follow the number-combiner uncertainty laws: omitted tail ⇒ fillable, exhausted repetition ⇒ certain, and an empty operand keeps the result fillable — probe-corrected: the combiner has no per-operand tracking, the not-given flag travels inside the `VkDate` value (`AggregateUncertaintyLawsTest`; read path `adapter.DateAggregateReadTest`). |
| `FormalChecker` | already-locked | The formal-error gate: a formally invalid value puts the field in the unknown state and suppresses rules over it — `FormalErrorLawsTest`, KERNEL-FINDINGS §3 supplement. |
| `BigDecimalUtils` | mined | `RoundDown` = FLOOR and `RoundUp` = CEILING (directional, **not** truncation: `RoundDown(−1.4) = −2`); `RoundAccounting` = HALF_UP (away from zero on .5); a scale-19 HALF_UP pre-rounding absorbs repeating-decimal artifacts (`RoundDown(q/3·3) = q`) — `RoundingLawsTest`. `Length("")` returns **0, not unknown** — the string family's empty-as-0 backdoor — `EmptyValueLawsTest`. |
| `MainValidatorController` | mined | Rule-evaluation orchestration: iteration/error landing/per-row attribution locked via the §9 laws (`RepetitionLawsTest`, `ParallelIterationLawsTest`, `CorrelationLawsTest`, `RepetitionNotUniqueLawsTest`); the evaluation-scope question drained 2026-06-10 — **formal-error suppression is branch-scoped, not rule-scoped** (probe-corrected vs the KERNEL-SEMANTICS §1 reading; `FormalErrorScopeLawsTest`). |
| `RuntimeController` | queued | The per-operator evaluation dispatcher (3.4k lines). Drained so far: pattern empties (`vergleichePatternSTRING` — empty never violates, fired patterns are always value errors); the `EntityListOperations`-vs-`ListOperOps` empty split (operand-list `Min`/`Max` substitute 0, starred aggregates skip); value-list empties both directions (an empty subject is never listed; an empty *list-entry field* can never be matched — no 0-substitution in lists); the filled-count semantics (`anzahlAngegebenerEntities`: a formal-error operand suppresses the count, grow-only typing); the date-range closed-interval overlap (`DatumBereich`: touching ranges overlap) — `EmptyValueLawsTest`, `FilledCountLawsTest`, `DateRangeOverlapLawsTest`; concat edges were already locked. Remaining tail: enum/category runtime access, semantic-index (`For`) value resolution, dynamic USB paths — drained opportunistically as features touch them. |
| `CalculationController` | mined | Overlapping preconditions → first declared alternative wins; division by zero computes nothing and clears a stale value (probe-corrected: the invalid number never surfaces as an error outcome); computed values are scale-normalized; a bare division has unknown scale (assignment needs an explicit rounding) — `ComputationEvaluationLawsTest`, plus the earlier `DateRangeComputationLawsTest` / §2 empty-operand finding. |
| `CalculationCommand` | infrastructure | Command wrapper around a computation run. |
| `CheckCommand` | infrastructure | Command wrapper around a partial-validation check run. |
| `ValidationCommand` | infrastructure | Command wrapper around a validation run (orchestration, no operator semantics). |
| `CalculationCache` | infrastructure | Memoization. |
| `ValidationCache` | infrastructure | Memoization. |
| `GroupFillCache` | infrastructure | Memoization of group-fill states. |
| `IndexFieldCache` | infrastructure | Memoization for index-field (`For`) resolution. |
| `USBIndexCache` | infrastructure | Memoization for the multi-form (USB) tier — the Vordruck conditions are parity-excluded anyway. |
| `RepetitionNotUniqueErrorCache` | infrastructure | Memoization; the RNU semantics themselves are locked (`RepetitionNotUniqueLawsTest`). |
| `DynamicEnumerationCommand` | out-of-scope | Table-backed (dynamic) enums are not DM-JSON-serializable (KERNEL-FINDINGS §8) — unreachable from rulekit-authored models. |
| `DynamicEnumerationController` | out-of-scope | Same reason. |
| `ICalculatorIntern` | infrastructure | Internal SPI interface. |
| `IDynEnumsIntern` | infrastructure | Internal SPI interface (dynamic enums, out-of-scope above). |
| `IMetaDataValidierung` | infrastructure | Internal SPI interface. |
| `ITeilValidierungChecker` | infrastructure | Internal SPI interface (partial validation). |
| `IValidatorIntern` | infrastructure | Internal SPI interface. |

## What the 2026-06-10 drain found (the headline facts)

Mined from the comparison engine and the combiner family, each law-locked the same day — detail in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md):

- **Every fired message is typed value-vs-omission** (`IMessage.getMessageType()`), a dimension KERNEL-SEMANTICS doesn't cover: omission = "filling more fields could satisfy the rule". The algebra: empty-substituted operands make omission; **And — omission wins, Or — value wins**.
- **Tolerance comparisons are strict**: `DiffersWithToleranceRange{N}` fires only beyond N — a difference of exactly N does not fire.
- **Starred aggregates are omission-typed until the repetition is exhausted** — the un-instantiated remainder of a repeatable group counts as a fillable tail (the probe corrected the source-read here).
- **`FirstFilledValue` never reads past the first filled operand** — a formal error after it is invisible; before it, it suppresses the rule.
- **`NumberOfDifferentValues` counts filled values only** — empties don't count but keep the count growable.
- **`AddYears`/`AddMonths` are calendar-corrected**: Feb-28 + years lands on a leap day where one exists; end-of-month overflow clamps, never carries.

Second drain (same day, the `DateCombiner`/`BigDecimalUtils` rows):

- **Rounding is directional, not truncation**: `RoundDown` = FLOOR (`−1.4 → −2`), `RoundUp` = CEILING, `RoundAccounting` = HALF_UP away from zero; a scale-19 pre-rounding keeps `RoundDown(q/3·3) = q`.
- **`Length("")` is 0, evaluated** — a bare comparison on an empty string is skipped, but `Length()` coerces it to the number 0 (the string family's empty-as-0 backdoor).
- **Date aggregates follow the number-aggregate uncertainty laws** — including the empty operand, whose not-given flag travels inside the value (a probe-corrected source-read: the date combiner itself has no per-operand tracking).

Third drain (same day, the `RuntimeController` empty edges + `CalculationController`):

- **An empty string never violates a pattern** (absence needs `FieldNotFilled`); fired pattern comparisons are always value errors.
- **Operand-list `Min`/`Max` substitute an empty number as 0; the starred aggregates skip it** — the `EntityListOperations` vs `ListOperOps` split, observable.
- **Computations: first declared alternative wins on overlapping preconditions; division by zero computes nothing fresh and clears a stale value** (probe-corrected — the invalid number never surfaces as an error outcome); a bare division has unknown scale, so assignment to a scaled field needs an explicit rounding.
- **Formal-error suppression is branch-scoped, not rule-scoped** (probe-corrected vs the KERNEL-SEMANTICS §1 reading): a healthy `Or` branch still convicts past a formal error in the other branch's field; an `And` is absorbed — the §3 "silent suppression" was the single-branch face of the three-state algebra.

Fourth drain (same day, the `RuntimeController` dispatch tail):

- **Date ranges are closed intervals — touching ranges overlap** (a window ending 31.01 and a coverage starting 31.01 fire `DateRangesOverlap`; seamless periods need next-day starts).
- **No 0-substitution in value lists**: an empty subject field is never listed, and an empty *field entry* of a numeric value list can never be matched.
- **`NumberOfFilledFields`: a formal-error operand suppresses the whole count** (neither counted nor skipped — contrast `FirstFilledValue`'s position-sensitivity); the count is grow-only (partial fill → omission, full fill → value error; no omitted-tail problem for plain field lists).
