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
enum class PredicateOp(val kernelName: String, val polarity: MsgType?) {
    FIELD_FILLED("FieldFilled", MsgType.VALUE),
    FIELD_NOT_FILLED("FieldNotFilled", MsgType.OMISSION),
    ALL_FIELDS_FILLED("AllFieldsFilled", MsgType.VALUE),
    GROUP_FILLED("GroupFilled", MsgType.VALUE),
    GROUP_NOT_FILLED("GroupNotFilled", MsgType.OMISSION),
    FIELD_VALUE_IN_LIST("FieldValueIncludedInValueList", MsgType.VALUE),
    FIELD_VALUE_NOT_IN_LIST("FieldValueNotIncludedInValueList", MsgType.VALUE),

    // Field-list quantifiers — fire by the count k of filled fields among the N operands (KERNEL-FINDINGS §1).
    // polarity = null → computed dynamically (fixable-by-filling → OMISSION, else VALUE).
    NO_FIELD_FILLED("NoFieldFilled", null),                              // k == 0
    AT_LEAST_ONE_FIELD_FILLED("AtLeastOneFieldFilled", null),            // k >= 1
    MORE_THAN_ONE_FIELD_FILLED("MoreThanOneFieldFilled", null),          // k >= 2
    NOT_ALL_FIELDS_FILLED("NotAllFieldsFilled", null),                   // k <  N
    NOT_EXACTLY_ONE_FIELD_FILLED("NotExactlyOneFieldFilled", null),      // k != 1
    FIELDS_NOT_COLLECTIVELY_FILLED("FieldsNotCollectivelyFilled", null), // 0 < k < N
    ;

    companion object {
        fun byName(name: String): PredicateOp? = entries.firstOrNull { it.kernelName == name }
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
    ;

    companion object {
        fun byName(name: String): FunctionOp? = entries.firstOrNull { it.kernelName == name }
    }
}
