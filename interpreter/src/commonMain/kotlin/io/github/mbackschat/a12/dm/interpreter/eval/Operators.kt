package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.model.MsgType

/**
 * The operator vocabulary the interpreter HANDLES — the closed registry behind the evaluator's dispatch
 * (`Interpreter.evalPred` / `evalFunc`), so those switch on an enum, not a raw string. Each entry carries
 * the kernel construct name ([kernelName]) the parser emits into the AST (`Cond.Pred.name` / `Expr.Func.name`).
 *
 * The AST keeps the name as a *string* on purpose — the kernel vocabulary is open/growing (109+ constructs)
 * and an unmodeled one must round-trip far enough to be REPORTED (tolerated as `unsupported`), not fail at
 * parse. [byName] decodes the handled subset; an unknown name yields null → `UnsupportedConstructException`.
 * As operator coverage grows these enums grow with it (and can feed a catalog-parity guard).
 */

/**
 * A boolean presence predicate (a `Cond.Pred`), evaluated to a three-valued [Tri]. [polarity] is the message
 * type when the predicate fires: a "not filled" predicate is fixable by FILLING → OMISSION, a positive
 * presence check → VALUE (KERNEL-FINDINGS §3 — value-vs-omission typing). It is **null for the field-list
 * quantifiers**, whose polarity is *data-dependent* (e.g. `NotExactlyOneFieldFilled` is OMISSION at k=0 but
 * VALUE at k=2) and is computed dynamically by `Interpreter.predType` from the filled-count.
 */
enum class PredicateOp(val kernelName: String, val polarity: MsgType?, val countsGroups: Boolean = false) {
    FIELD_FILLED("FieldFilled", MsgType.VALUE),
    FIELD_NOT_FILLED("FieldNotFilled", MsgType.OMISSION),
    GROUP_FILLED("GroupFilled", MsgType.VALUE),
    GROUP_NOT_FILLED("GroupNotFilled", MsgType.OMISSION),
    FIELD_VALUE_IN_LIST("FieldValueIncludedInValueList", MsgType.VALUE),
    FIELD_VALUE_NOT_IN_LIST("FieldValueNotIncludedInValueList", MsgType.VALUE),
    // Date-range overlap — polarity is DATA-dependent (null → computed in `Interpreter.predType`): OMISSION when
    // a FILTER-bearing operand (a rep-list arg with a `Having`) contributed the firing cell, VALUE otherwise
    // (KERNEL-FINDINGS §6). The two are NOT interchangeable: `DateRangesOverlap(op0, …)` is ANY-PAIR among ALL
    // operands' cells (a self-pair fires), while `AtLeastOneDateRangeOverlaps(scalar In list)` is genuinely
    // scalar-vs-list (the scalar is not in the set) — they differ exactly where the set semantics bites, so each
    // has its own eval (`overlapAny` / `atLeastOneOverlap`).
    DATE_RANGES_OVERLAP("DateRangesOverlap", null),
    ATLEAST_ONE_DATE_RANGE_OVERLAPS("AtLeastOneDateRangeOverlaps", null),
    // Two of the listed fields hold the same value → a value error (you change a value, you don't fill — both
    // are already filled). Empties are skipped (two empties are NOT a duplicate); a malformed operand suppresses.
    FIELD_VALUES_NOT_UNIQUE("FieldValuesNotUnique", MsgType.VALUE),
    // A repetition row whose (composite) key duplicates another row's within scope. An ordinary per-row 3VL leaf
    // (IF149): polarity is DATA-dependent (null → computed in `Interpreter.predType` from the per-row cache —
    // VALUE when every key component is present, OMISSION when a fillable component is empty). Its per-row result
    // is read from the call-scoped duplicate cache ([io.github.mbackschat.a12.dm.interpreter.RepetitionNotUniqueEvaluator]),
    // so it composes under arbitrary legal And/Or trees exactly like any other leaf.
    REPETITION_NOT_UNIQUE("RepetitionNotUnique", null),
    // Valid/Invalid(Date(...)): is the constructed date a real calendar date with all parts present? Valid is a
    // present-value verdict → VALUE. Invalid's polarity is data-dependent (null): OMISSION when an empty part
    // could be filled to make the date real, else VALUE — computed in `Interpreter.predType` (KERNEL-FINDINGS §6).
    VALID("Valid", MsgType.VALUE),
    INVALID("Invalid", null),

    // List quantifiers — fire by the count k of filled OPERANDS among the N (fields, or groups when
    // [countsGroups]); KERNEL-FINDINGS §1. polarity = null → computed dynamically (fixable-by-filling →
    // OMISSION, else VALUE). The firing law per op is `Interpreter.quantifierFires`.
    ALL_FIELDS_FILLED("AllFieldsFilled", null),                          // k == N
    NO_FIELD_FILLED("NoFieldFilled", null),                              // k == 0
    AT_LEAST_ONE_FIELD_FILLED("AtLeastOneFieldFilled", null),            // k >= 1
    MORE_THAN_ONE_FIELD_FILLED("MoreThanOneFieldFilled", null),          // k >= 2
    NOT_ALL_FIELDS_FILLED("NotAllFieldsFilled", null),                   // k <  N
    NOT_EXACTLY_ONE_FIELD_FILLED("NotExactlyOneFieldFilled", null),      // k != 1
    FIELDS_NOT_COLLECTIVELY_FILLED("FieldsNotCollectivelyFilled", null), // 0 < k < N
    ALL_GROUPS_FILLED("AllGroupsFilled", null, countsGroups = true),                          // k == N
    NO_GROUP_FILLED("NoGroupFilled", null, countsGroups = true),                              // k == 0
    AT_LEAST_ONE_GROUP_FILLED("AtLeastOneGroupFilled", null, countsGroups = true),            // k >= 1
    NOT_ALL_GROUPS_FILLED("NotAllGroupsFilled", null, countsGroups = true),                   // k <  N
    GROUPS_NOT_COLLECTIVELY_FILLED("GroupsNotCollectivelyFilled", null, countsGroups = true), // 0 < k < N
    ;

    companion object {
        fun byName(name: String): PredicateOp? = entries.firstOrNull { it.kernelName == name }
    }
}

/**
 * A no-operand constant construct (an `Expr.Const`), resolved against the evaluation clock/model config to
 * a [Val]. `Today` is the current date; `Now` is the current date-time (compares only to a date-time field);
 * `BaseYear` joins as the base-year family lands.
 */
enum class ConstantOp(val kernelName: String) {
    TODAY("Today"),
    NOW("Now"),
    BASE_YEAR("BaseYear"),
    ;

    companion object {
        fun byName(name: String): ConstantOp? = entries.firstOrNull { it.kernelName == name }
    }
}

/** A value-producing function/aggregate construct (an `Expr.Func`), evaluated to a [Val]. */
enum class FunctionOp(val kernelName: String) {
    MIN("Min"),
    MAX("Max"),
    SUM("Sum"),
    MAX_VALUE("MaxValue"),
    MIN_VALUE("MinValue"),
    NUMBER_OF_DIFFERENT_VALUES("NumberOfDifferentValues"),
    DIFFERENCE_IN_DAYS("DifferenceInDays"),
    NUMBER_OF_FILLED_FIELDS("NumberOfFilledFields"),
    NUMBER_OF_FILLED_GROUPS("NumberOfFilledGroups"),
    YEAR_FROM_DATE("YearFromDate"),
    MONTH_FROM_DATE("MonthFromDate"),
    DAY_FROM_DATE("DayFromDate"),
    QUARTER_FROM_DATE("QuarterFromDate"),
    HOURS_FROM_TIME("HoursFromTime"),
    MINUTES_FROM_TIME("MinutesFromTime"),
    SECONDS_FROM_TIME("SecondsFromTime"),
    DATE_FROM_DATE_TIME("DateFromDateTime"),
    TIME_FROM_DATE_TIME("TimeFromDateTime"),
    DATE_TIME("DateTime"),
    DATE("Date"),
    TIME("Time"),
    DATE_RANGE("DateRange"),
    VALUE_AS_DATE("ValueAsDate"),
    ADD_HOURS("AddHours"),
    ADD_MINUTES("AddMinutes"),
    ADD_SECONDS("AddSeconds"),
    DIFFERENCE_IN_HOURS("DifferenceInHours"),
    DIFFERENCE_IN_MINUTES("DifferenceInMinutes"),
    DIFFERENCE_IN_SECONDS("DifferenceInSeconds"),
    ADD_DAYS("AddDays"),
    ADD_MONTHS("AddMonths"),
    ADD_YEARS("AddYears"),
    DIFFERENCE_IN_MONTHS("DifferenceInMonths"),
    DIFFERENCE_IN_YEARS("DifferenceInYears"),
    START_OF_DATE_RANGE("StartOfDateRange"),
    END_OF_DATE_RANGE("EndOfDateRange"),
    // |x| — the bracketed-expression form `Abs([a]-[b])` and the bare-field form `AbsValue(field)`; same value.
    ABS("Abs"),
    ABS_VALUE("AbsValue"),
    // character length of a string field's value (a bare-field form): `Length(field)`.
    LENGTH("Length"),
    // Rounding: the operand-list `Round*([expr], n)` forms round a numeric expression to n decimal places;
    // the `*Value(field[, n])` siblings round a bare field with the same optional places. RoundDown = FLOOR (toward −∞),
    // RoundUp = CEILING (toward +∞), RoundAccounting = HALF_UP (away from zero on .5) — KERNEL-FINDINGS §4.
    ROUND_DOWN("RoundDown"),
    ROUND_UP("RoundUp"),
    ROUND_ACCOUNTING("RoundAccounting"),
    ROUND_DOWN_VALUE("RoundDownValue"),
    ROUND_UP_VALUE("RoundUpValue"),
    ROUND_ACCOUNTING_VALUE("RoundAccountingValue"),
    // The FIRST specified value over a repetition list, in row order — empties are skipped, and operands after
    // the first filled one are never read (a formal error there is invisible; a formal error BEFORE/AT the
    // first filled one suppresses the rule). A `Sum` sibling; returns the scope's element type (number or date).
    FIRST_FILLED_VALUE("FirstFilledValue"),
    // `NumberOfValueInFields(value In f1, f2, …)` — the count of the listed fields whose FILLED value equals the
    // constant. An empty field is NOT counted (kernel: only `b == TRUE` counts); a formal error → non-evaluable.
    NUMBER_OF_VALUE_IN_FIELDS("NumberOfValueInFields"),
    // `RangeAsString(field, s, e)` / `RangeAsNumber(field, s, e)` — a substring of a STRING field's value by
    // 1-based inclusive char range. Out-of-range (e > length) or empty → `""` (RangeAsString) / `0` (RangeAsNumber,
    // which parses ONLY `\d+`, else 0). KERNEL-FINDINGS — `getSubstring`/`bereichAlsZahl`.
    RANGE_AS_STRING("RangeAsString"),
    RANGE_AS_NUMBER("RangeAsNumber"),
    // `FieldValueAsString(numberField)` — a NUMBER field's RAW stored value as a string (no re-canonicalization);
    // empty → `""`. `FieldValueAsNumber(stringOrNumericEnumField)` — the field's value parsed to a number; empty
    // → `0` (kernel `feldWertAlsZahl`). KERNEL-FINDINGS family 4.
    FIELD_VALUE_AS_STRING("FieldValueAsString"),
    FIELD_VALUE_AS_NUMBER("FieldValueAsNumber"),
    // `SumOfProducts(a*, b*)` — Σ aᵢ·bᵢ over two parallel field lists of the SAME repeatable group, paired
    // row-by-row (empty → 0, kernel `summeVonProdukten`); any malformed element → non-evaluable (suppressed).
    SUM_OF_PRODUCTS("SumOfProducts"),
    // `CurrentRepetition(group)` — the current iteration row's 1-based index for that group (the rule must
    // iterate over it, driven by a sibling field ref). A number expression. KERNEL-FINDINGS.
    CURRENT_REPETITION("CurrentRepetition"),
    ;

    companion object {
        fun byName(name: String): FunctionOp? = entries.firstOrNull { it.kernelName == name }
    }
}

/**
 * Whether a list quantifier [op] fires for a tally of [t] TRUE + [f] FALSE operands among [n] (the rest
 * UNKNOWN) — the kernel's runtime quantifier semantics (`alleEntitiesAngegeben`/`keineEntityAngegeben`/…,
 * KERNEL-FINDINGS §1/§3). Reduces to the binary count law when no operand is UNKNOWN (`f = n − t`); field
 * and group quantifiers share it. A **pure, strategy-agnostic** function over the resolved tally (no AST-walk,
 * no `EvalContext`), co-located with [PredicateOp] so the interpreter tree-walk and a future generator share
 * one definition (INTERPRETER-FINDINGS IF10).
 */
fun quantifierFires(op: PredicateOp, t: Int, f: Int, n: Int): Boolean = when (op) {
    PredicateOp.ALL_FIELDS_FILLED, PredicateOp.ALL_GROUPS_FILLED -> t == n
    PredicateOp.NO_FIELD_FILLED, PredicateOp.NO_GROUP_FILLED -> f == n
    PredicateOp.AT_LEAST_ONE_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED -> t >= 1
    PredicateOp.MORE_THAN_ONE_FIELD_FILLED -> t >= 2
    PredicateOp.NOT_ALL_FIELDS_FILLED, PredicateOp.NOT_ALL_GROUPS_FILLED -> f >= 1
    PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> f == n || t >= 2
    PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> t >= 1 && f >= 1
    else -> error("not a list quantifier: $op")
}
