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
| `DateCombiner` | queued | Date `MaxValue`/`MinValue`; filter/omitted-tail mark the result not-given (read path locked in `adapter.DateAggregateReadTest`; the runtime typing law is the queue item). |
| `FormalChecker` | already-locked | The formal-error gate: a formally invalid value puts the field in the unknown state and suppresses rules over it — `FormalErrorLawsTest`, KERNEL-FINDINGS §3 supplement. |
| `BigDecimalUtils` | queued | Rounding helpers (`RoundDown`/`RoundUp`/`Round` at digit positions), `Length`, `Abs`. Rounding modes are §5 canonical; the runtime edge (rounding of already-scale-rounded values) is the queue item. |
| `MainValidatorController` | queued | Rule-evaluation orchestration: iteration, error landing, per-row message attribution. Large parts already locked via §9 laws (`RepetitionLawsTest`, `ParallelIterationLawsTest`, `CorrelationLawsTest`, `RepetitionNotUniqueLawsTest`); remaining queue: evaluation order/short-circuit observability. |
| `RuntimeController` | queued | The per-operator evaluation dispatcher (3.4k lines). Drain continues here family-by-family; next candidates: pattern-match null handling, string concatenation edge semantics, `EntityListOperation` vs `ListOperOp` validity testing (the `testValidity` flag). |
| `CalculationController` | queued | Computation evaluation; partially locked (`DateRangeComputationLawsTest`, the §2 empty-operand-computation finding, the `compute()` seam in `RuntimeLaws`). |
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
