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
    // Two PRESENT date ranges that clash → a value error (no fill clears an overlap; you change a value).
    DATE_RANGES_OVERLAP("DateRangesOverlap", MsgType.VALUE),

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
 * a [Val]. `Today` is the current date; `Now` / `BaseYear` join as the date-time and base-year families land.
 */
enum class ConstantOp(val kernelName: String) {
    TODAY("Today"),
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
    ADD_DAYS("AddDays"),
    ADD_MONTHS("AddMonths"),
    ADD_YEARS("AddYears"),
    DIFFERENCE_IN_MONTHS("DifferenceInMonths"),
    DIFFERENCE_IN_YEARS("DifferenceInYears"),
    START_OF_DATE_RANGE("StartOfDateRange"),
    END_OF_DATE_RANGE("EndOfDateRange"),
    ;

    companion object {
        fun byName(name: String): FunctionOp? = entries.firstOrNull { it.kernelName == name }
    }
}
