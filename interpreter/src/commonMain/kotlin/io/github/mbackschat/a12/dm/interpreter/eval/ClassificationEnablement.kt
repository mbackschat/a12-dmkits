package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp

/**
 * Whether a TRUE rule runs the kernel's second, eager classification pass over every condition leaf — a static
 * property of the authored constructs, independent of the document (a12-kernel-lean `SPEC-2026-10-03-06`).
 *
 * The kernel tests truth first, short-circuiting both connectives, and only when the complete condition is true
 * and this predicate holds does it re-evaluate every leaf in authored order to combine the message type. The two
 * passes agree on the type, so the second one is observable only through a leaf that can FAIL at runtime
 * ([TemporalLabelFailure]): a failure skipped by the truth pass is reached here and aborts the rule.
 *
 * The predicate follows the kernel's omission-capability visitor (`CodeGenHelper.canCreateOmissionErrors`) node
 * kind by node kind, on the kernel's parsed form — which is why the presence keywords need their lowering:
 * `FieldFilled` parses to `AllFieldsFilled` and stays exempt unless filtered, `FieldNotFilled` to `NoFieldFilled`
 * and enables, and a non-root group condition to field-list conditions over its contents, which enable
 * (`FeldListenBedingungCreator`, `KontextListenBedingungCreator`). Root-group presence is a separate family in
 * which only the negative form enables. A predefined-type validity names its field as an entity id, not a value
 * operation, so it enables nothing; a `Date(...)` validity enables through the operations inside it.
 */
internal fun classificationEnabled(cond: Cond, isRootGroup: (String) -> Boolean): Boolean = when (cond) {
    is Cond.And -> classificationEnabled(cond.left, isRootGroup) || classificationEnabled(cond.right, isRootGroup)
    is Cond.Or -> classificationEnabled(cond.left, isRootGroup) || classificationEnabled(cond.right, isRootGroup)
    is Cond.Not -> classificationEnabled(cond.c, isRootGroup)
    is Cond.Cmp -> readsValue(cond.left) || readsValue(cond.right)
    is Cond.Tolerance -> readsValue(cond.left) || readsValue(cond.right)
    is Cond.Pattern -> readsValue(cond.value)
    is Cond.Custom -> false
    is Cond.ValueListQuant -> when (cond.op) {
        ValueListQuantOp.NO, ValueListQuantOp.NOT_ALL -> true
        ValueListQuantOp.AT_LEAST_ONE -> (cond.fields + cond.values).any(::filtered)
    }
    is Cond.Pred -> predicateEnables(cond, isRootGroup)
}

private fun predicateEnables(p: Cond.Pred, isRootGroup: (String) -> Boolean): Boolean =
    when (PredicateOp.byName(p.name)) {
        PredicateOp.FIELD_FILLED, PredicateOp.ALL_FIELDS_FILLED, PredicateOp.MORE_THAN_ONE_FIELD_FILLED,
        PredicateOp.FIELD_VALUES_NOT_UNIQUE -> p.args.any(::filtered)
        PredicateOp.GROUP_FILLED, PredicateOp.ALL_GROUPS_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
        PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED ->
            !p.args.all { rootOperand(it, isRootGroup) }
        PredicateOp.GROUP_NOT_FILLED, PredicateOp.NO_GROUP_FILLED -> true
        PredicateOp.FIELD_VALUE_IN_LIST, PredicateOp.FIELD_VALUE_NOT_IN_LIST -> p.args.drop(1).any(::readsValue)
        PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS -> p.args.drop(1).any(::filtered)
        PredicateOp.VALID, PredicateOp.INVALID -> p.args.size == 1 && readsValue(p.args.single())
        PredicateOp.FIELD_NOT_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
        PredicateOp.NOT_ALL_FIELDS_FILLED, PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED,
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED, PredicateOp.REPETITION_NOT_UNIQUE,
        PredicateOp.DATE_RANGES_OVERLAP -> true
        null -> false
    }

private fun rootOperand(e: Expr, isRootGroup: (String) -> Boolean): Boolean =
    e is Expr.Field && isRootGroup(e.path)

/** A value operation: a field, list, semantic-index or category read anywhere inside the expression. */
private fun readsValue(e: Expr): Boolean = when (e) {
    is Expr.Field, is Expr.Star, is Expr.EntitySpec, is Expr.SemanticIndex, is Expr.EnumCategory -> true
    // `CurrentRepetition` names a group as an entity id; it reads no value.
    is Expr.Func -> FunctionOp.byName(e.name) != FunctionOp.CURRENT_REPETITION && e.args.any(::readsValue)
    is Expr.Arith -> readsValue(e.left) || readsValue(e.right)
    is Expr.Const, is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit -> false
}

private fun filtered(e: Expr): Boolean = when (e) {
    is Expr.Star -> e.having != null
    is Expr.EntitySpec -> (e.source as? Expr.Star)?.having != null
    else -> false
}

/**
 * Whether the condition holds a DateTime literal — the only leaf that can raise a [TemporalLabelFailure]. The
 * eager classification pass agrees with the lazy single walk on every message type, so it is run only where it
 * could reach a failure the truth pass skipped; everywhere else it would be a second walk with nothing to observe.
 * The shape test is deliberately loose: a false positive costs one extra walk, never a different result.
 */
internal fun containsDateTimeLiteral(cond: Cond): Boolean = when (cond) {
    is Cond.And -> containsDateTimeLiteral(cond.left) || containsDateTimeLiteral(cond.right)
    is Cond.Or -> containsDateTimeLiteral(cond.left) || containsDateTimeLiteral(cond.right)
    is Cond.Not -> containsDateTimeLiteral(cond.c)
    is Cond.Cmp -> hasDateTimeLiteral(cond.left) || hasDateTimeLiteral(cond.right)
    is Cond.Tolerance -> hasDateTimeLiteral(cond.left) || hasDateTimeLiteral(cond.right)
    is Cond.Pattern -> hasDateTimeLiteral(cond.value)
    is Cond.Custom -> false
    is Cond.ValueListQuant -> (cond.fields + cond.values).any(::hasDateTimeLiteral)
    is Cond.Pred -> cond.args.any(::hasDateTimeLiteral)
}

private val DATE_TIME_LITERAL_SHAPE = Regex("""\d{1,2}\.\d{1,2}\.\d{4}T\d{1,2}:\d{1,2}:\d{1,2}""")

private fun hasDateTimeLiteral(e: Expr): Boolean = when (e) {
    is Expr.StrLit -> DATE_TIME_LITERAL_SHAPE.matches(e.v)
    is Expr.Func -> e.args.any(::hasDateTimeLiteral)
    is Expr.Arith -> hasDateTimeLiteral(e.left) || hasDateTimeLiteral(e.right)
    is Expr.Star -> e.having?.let(::containsDateTimeLiteral) ?: false
    is Expr.EntitySpec -> hasDateTimeLiteral(e.source)
    is Expr.Field, is Expr.SemanticIndex, is Expr.EnumCategory, is Expr.Const, is Expr.NumLit, is Expr.BoolLit -> false
}
