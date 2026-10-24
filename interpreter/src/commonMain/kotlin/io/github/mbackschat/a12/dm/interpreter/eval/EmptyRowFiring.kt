package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp

/** Structural: would this condition fire on an all-empty row? A comparison/pattern can't (empty-as-0 is
 *  suppressed; an empty string/pattern leaf is false); a presence predicate fires iff it is TRUE at a
 *  filled-count of 0; And/Or recurse. (Not is unreachable from the parser but modelled for totality.) */
internal fun Cond.canFireEmpty(): Boolean = when (val cond = this) {
    is Cond.Cmp -> false
    is Cond.Pattern -> false
    is Cond.Tolerance -> false   // |0 − 0| = 0 is never > N (the band is ≥ 1) — an all-empty row can't fire
    is Cond.ValueListQuant -> cond.op == ValueListQuantOp.NO   // "no field in list" is TRUE when all are empty
    is Cond.And -> cond.left.canFireEmpty() && cond.right.canFireEmpty()
    is Cond.Or -> cond.left.canFireEmpty() || cond.right.canFireEmpty()
    is Cond.Not -> !cond.c.canFireEmpty()
    is Cond.Pred -> cond.firesOnEmpty()
    is Cond.Custom -> true    // hidden dependencies are callback-owned; the leaf is structurally empty-eligible
}

/** Whether a presence predicate is TRUE when every operand is known empty (t = 0, f = n, u = 0). */
private fun Cond.Pred.firesOnEmpty(): Boolean = when (val op = PredicateOp.byName(name)) {
    null, PredicateOp.FIELD_FILLED, PredicateOp.GROUP_FILLED,
    PredicateOp.FIELD_VALUE_IN_LIST, PredicateOp.FIELD_VALUE_NOT_IN_LIST,
    PredicateOp.DATE_RANGES_OVERLAP, PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS,
    PredicateOp.FIELD_VALUES_NOT_UNIQUE, PredicateOp.REPETITION_NOT_UNIQUE,
    PredicateOp.VALID -> false   // needs present ranges / ≥2 present equal values / a real present date / a present value
    PredicateOp.FIELD_NOT_FILLED, PredicateOp.GROUP_NOT_FILLED -> true
    // Invalid(Date(...)) (1 arg) fires on an empty row — every part unspecified IS invalid (kernel-probed,
    // OMISSION); Invalid(field, "Type") (2 args) does NOT — an empty field is "not specified", not evaluated.
    PredicateOp.INVALID -> args.size == 1
    else -> quantifierFires(op, t = 0, f = args.size, n = args.size)   // all-empty: every operand FALSE
}
