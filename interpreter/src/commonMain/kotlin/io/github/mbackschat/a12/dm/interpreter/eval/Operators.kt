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
 * A boolean presence predicate (a `Cond.Pred`), evaluated to a three-valued [Tri]. [polarity] is the
 * message type when the predicate fires: a "not filled" predicate is fixable by FILLING → OMISSION, a
 * positive presence check → VALUE (KERNEL-FINDINGS §3 — value-vs-omission typing).
 */
enum class PredicateOp(val kernelName: String, val polarity: MsgType) {
    FIELD_FILLED("FieldFilled", MsgType.VALUE),
    FIELD_NOT_FILLED("FieldNotFilled", MsgType.OMISSION),
    ALL_FIELDS_FILLED("AllFieldsFilled", MsgType.VALUE),
    GROUP_FILLED("GroupFilled", MsgType.VALUE),
    GROUP_NOT_FILLED("GroupNotFilled", MsgType.OMISSION),
    FIELD_VALUE_IN_LIST("FieldValueIncludedInValueList", MsgType.VALUE),
    FIELD_VALUE_NOT_IN_LIST("FieldValueNotIncludedInValueList", MsgType.VALUE),
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
