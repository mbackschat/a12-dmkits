package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

/**
 * A tree-walking interpreter for the condition AST over an [EvalContext] — pure static common code
 * (no kernel/groovy; the only platform primitive is [Dec]). Reproduces the kernel's runtime semantics:
 * three-valued logic, the TYPE-DRIVEN empty-value handling (number→0, confirm→False, others→not
 * evaluated) gated by the filled-row precondition, operand-list vs starred aggregates, and chronological
 * (lexicographic on ISO) date comparison.
 *
 * A rule FIRES when its error condition evaluates to TRUE.
 */
class Interpreter(private val ctx: EvalContext) {

    fun fires(cond: Cond): Boolean = eval(cond) == Tri.TRUE

    /**
     * Evaluate a value expression (a computation's RHS) to a typed [Val] — NOT gated by the row's
     * `evaluated` flag, since a computation computes even on an otherwise-empty row (an empty numeric
     * operand reads as 0, so `[BaseFee]` over no base fee yields 0, not nothing).
     */
    fun evalExpression(expr: Expr): Val = evalExpr(expr)

    fun eval(cond: Cond): Tri {
        if (!ctx.evaluated) return Tri.UNKNOWN   // entirely-empty row: not evaluated
        return evalCond(cond)
    }

    /** The polarity [MsgType] of a fired rule, or null when it does not fire — VALUE vs OMISSION. */
    fun firedType(cond: Cond): MsgType? {
        if (!ctx.evaluated) return null
        val r = evalTyped(cond)
        return if (r.truth == Tri.TRUE) r.type else null
    }

    private data class Res(val truth: Tri, val type: MsgType)

    // The typing rides alongside the truth value (kernel: ValidierungsErgebnis.combineUND/combineODER):
    //   And — omission wins (a fillable branch makes the whole error fillable);
    //   Or  — value wins  (a satisfied value branch convicts regardless of filling).
    private fun evalTyped(c: Cond): Res = when (c) {
        is Cond.Cmp -> Res(evalCmp(c), leafType(c.left, c.right))
        is Cond.Pred -> Res(evalPred(c), predType(c))
        // A pattern fires only on a PRESENT value (empty → not evaluated), so a fired pattern is always a
        // present-value error → VALUE (there is no "fill more to fix it" omission — the field is filled).
        is Cond.Pattern -> Res(evalPattern(c), MsgType.VALUE)
        is Cond.And -> {
            val a = evalTyped(c.left)
            val b = evalTyped(c.right)
            val type = if (a.type == MsgType.OMISSION || b.type == MsgType.OMISSION) MsgType.OMISSION else MsgType.VALUE
            Res(and(a.truth, b.truth), type)
        }
        is Cond.Or -> {
            val a = evalTyped(c.left)
            val b = evalTyped(c.right)
            Res(or(a.truth, b.truth), orType(a, b))
        }
        is Cond.Not -> {
            val a = evalTyped(c.c)
            Res(not(a.truth), a.type)
        }
    }

    /** Or: among the branches that are TRUE, a VALUE branch convicts → VALUE; else OMISSION. */
    private fun orType(a: Res, b: Res): MsgType {
        val trueBranchTypes = buildList {
            if (a.truth == Tri.TRUE) add(a.type)
            if (b.truth == Tri.TRUE) add(b.type)
        }
        return if (trueBranchTypes.contains(MsgType.VALUE)) MsgType.VALUE else MsgType.OMISSION
    }

    /** A comparison that fired on a substituted-empty field — or on a still-fillable starred aggregate
     *  — is fillable → OMISSION; else VALUE. */
    private fun leafType(left: Expr, right: Expr): MsgType =
        if (leafOmission(left) || leafOmission(right)) MsgType.OMISSION else MsgType.VALUE

    /**
     * The value-vs-omission polarity of a fired predicate. Non-quantifier predicates carry a static
     * [PredicateOp.polarity]; a field-list quantifier's polarity is **data-dependent** and computed the way
     * the kernel does — OMISSION iff filling some currently-empty operand could clear the firing (so the
     * error is fixable by filling), else VALUE. (E.g. `NotExactlyOneFieldFilled` is OMISSION at k=0 but
     * VALUE at k=2 — KERNEL-FINDINGS §1.)
     */
    private fun predType(p: Cond.Pred): MsgType {
        val op = PredicateOp.byName(p.name) ?: return MsgType.VALUE
        op.polarity?.let { return it }
        val n = p.args.size
        val k = quantCount(op, p.args)
        val fillable = ((k + 1)..n).any { kp -> !quantifierFires(op, kp, n) }
        return if (fillable) MsgType.OMISSION else MsgType.VALUE
    }

    private fun leafOmission(e: Expr): Boolean = when (e) {
        is Expr.Field -> !ctx.filled(e.path)
        is Expr.Star -> ctx.starOmission(e.path)
        is Expr.Func -> e.args.any { leafOmission(it) }
        else -> false
    }

    private fun evalCond(c: Cond): Tri = when (c) {
        is Cond.Cmp -> evalCmp(c)
        is Cond.And -> and(evalCond(c.left), evalCond(c.right))
        is Cond.Or -> or(evalCond(c.left), evalCond(c.right))
        is Cond.Not -> not(evalCond(c.c))
        is Cond.Pred -> evalPred(c)
        is Cond.Pattern -> evalPattern(c)
    }

    private fun evalPred(p: Cond.Pred): Tri {
        val op = PredicateOp.byName(p.name) ?: throw UnsupportedConstructException("unsupported predicate ${p.name}")
        return when (op) {
            PredicateOp.FIELD_FILLED -> Tri.of(ctx.filled(pathOf(p.args.single())))
            PredicateOp.FIELD_NOT_FILLED -> Tri.of(!ctx.filled(pathOf(p.args.single())))
            PredicateOp.GROUP_FILLED -> Tri.of(ctx.groupFilled(pathOf(p.args.single())))
            PredicateOp.GROUP_NOT_FILLED -> Tri.of(!ctx.groupFilled(pathOf(p.args.single())))
            PredicateOp.FIELD_VALUE_IN_LIST -> valueInList(p.args)
            PredicateOp.FIELD_VALUE_NOT_IN_LIST -> not(valueInList(p.args))
            // List quantifiers over the count k of filled operands (row-evaluated gate is upstream in eval).
            PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
            PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED, PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            PredicateOp.ALL_GROUPS_FILLED, PredicateOp.NO_GROUP_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
            PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED ->
                Tri.of(quantifierFires(op, quantCount(op, p.args), p.args.size))
        }
    }

    /** The filled-operand count behind a list quantifier — filled GROUPS when [PredicateOp.countsGroups], else filled fields. */
    private fun quantCount(op: PredicateOp, args: List<Expr>): Int =
        if (op.countsGroups) args.count { ctx.groupFilled(pathOf(it)) } else args.count { ctx.filled(pathOf(it)) }

    /** Whether a list quantifier [op] fires for [k] filled operands among [n] (the count law; field/group share it). */
    private fun quantifierFires(op: PredicateOp, k: Int, n: Int): Boolean = when (op) {
        PredicateOp.ALL_FIELDS_FILLED, PredicateOp.ALL_GROUPS_FILLED -> k == n
        PredicateOp.NO_FIELD_FILLED, PredicateOp.NO_GROUP_FILLED -> k == 0
        PredicateOp.AT_LEAST_ONE_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED -> k >= 1
        PredicateOp.MORE_THAN_ONE_FIELD_FILLED -> k >= 2
        PredicateOp.NOT_ALL_FIELDS_FILLED, PredicateOp.NOT_ALL_GROUPS_FILLED -> k < n
        PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> k != 1
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> k in 1 until n
        else -> error("not a list quantifier: $op")
    }

    /**
     * `FieldValueIncludedInValueList(field, m1, m2, …)` — TRUE when [args]`[0]`'s value equals a member, via
     * the same typed equality as `==` (string members compare as text, number members numerically; the
     * kernel rejects mixed types at compile, `MVK_INVALID_TYPES_FOR_COMPARISON`). An UNKNOWN operand (an
     * empty string/enum field) yields UNKNOWN, so NEITHER the included nor the `Not` form fires on it —
     * matching the kernel (a value-list check is not evaluated on a value-less field).
     */
    private fun valueInList(args: List<Expr>): Tri {
        val v = evalExpr(args[0])
        if (v is Val.Unknown) return Tri.UNKNOWN
        return Tri.of(args.drop(1).any { eq(v, evalExpr(it)) })
    }

    /** `value PatternMatched/PatternViolated "regex"` — anchored full-value match; empty → not evaluated. */
    private fun evalPattern(p: Cond.Pattern): Tri {
        val v = evalExpr(p.value)
        if (v is Val.Unknown) return Tri.UNKNOWN
        val text = (v as? Val.Txt)?.v ?: error("pattern comparison needs a string value, got $v")
        val matched = Regex((p.pattern as Expr.StrLit).v).matches(text)
        return Tri.of(if (p.expectMatch) matched else !matched)
    }

    private fun evalCmp(c: Cond.Cmp): Tri {
        val l = evalExpr(c.left)
        val r = evalExpr(c.right)
        if (l is Val.Unknown || r is Val.Unknown) return Tri.UNKNOWN
        return when (c.op) {
            CmpOp.EQ -> Tri.of(eq(l, r))
            CmpOp.NE -> Tri.of(!eq(l, r))
            CmpOp.LT -> Tri.of(ORDER.compare(l, r) < 0)
            CmpOp.LE -> Tri.of(ORDER.compare(l, r) <= 0)
            CmpOp.GT -> Tri.of(ORDER.compare(l, r) > 0)
            CmpOp.GE -> Tri.of(ORDER.compare(l, r) >= 0)
        }
    }

    private fun evalExpr(e: Expr): Val = when (e) {
        is Expr.NumLit -> Val.Num(e.v)
        is Expr.StrLit -> Val.Txt(e.v)
        is Expr.BoolLit -> Val.Bool(e.v)
        is Expr.Field -> ctx.field(e.path)
        is Expr.Star -> error("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
        is Expr.Func -> evalFunc(e)
        is Expr.Arith -> evalArith(e)
    }

    /** Binary arithmetic on numbers; an empty numeric operand already arrives as 0. Unknown propagates. */
    private fun evalArith(a: Expr.Arith): Val {
        val l = evalExpr(a.left)
        val r = evalExpr(a.right)
        if (l is Val.Unknown || r is Val.Unknown) return Val.Unknown
        val x = num(l)
        val y = num(r)
        return Val.Num(when (a.op) {
            ArithOp.ADD -> x + y
            ArithOp.SUB -> x - y
            ArithOp.MUL -> x * y
        })
    }

    private fun evalFunc(f: Expr.Func): Val = when (FunctionOp.byName(f.name)) {
        // operand-list forms: every operand counts (an empty number arrives as the substituted 0)
        FunctionOp.MIN -> f.args.map { evalExpr(it) }.minWithOrNull(ORDER) ?: Val.Unknown
        FunctionOp.MAX -> f.args.map { evalExpr(it) }.maxWithOrNull(ORDER) ?: Val.Unknown
        // starred aggregates: empties are SKIPPED (Sum substitutes 0 = same result; Max/distinct ignore)
        FunctionOp.SUM -> Val.Num(starNums(f).fold(DEC_ZERO) { a, b -> a + b })
        FunctionOp.MAX_VALUE -> starVals(f).maxWithOrNull(ORDER) ?: Val.Unknown
        FunctionOp.MIN_VALUE -> starVals(f).minWithOrNull(ORDER) ?: Val.Unknown
        FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> {
            val distinct = mutableListOf<Dec>()
            for (x in starNums(f)) if (distinct.none { it.compareTo(x) == 0 }) distinct.add(x)
            Val.Num(dec(distinct.size))
        }
        // DifferenceInDays(a, b) = b − a in whole days (sign = second − first), as a number. An empty or
        // malformed date operand → Unknown (the rule typically guards it with AllFieldsFilled).
        FunctionOp.DIFFERENCE_IN_DAYS -> {
            val a = epochOf(f.args[0])
            val b = epochOf(f.args[1])
            if (a == null || b == null) Val.Unknown else Val.Num(dec((b - a).toString()))
        }
        // Count-of-filled functions (used in a comparison, e.g. NumberOfFilledFields(a, b, c) < 2).
        FunctionOp.NUMBER_OF_FILLED_FIELDS -> Val.Num(dec(f.args.count { ctx.filled(pathOf(it)) }))
        FunctionOp.NUMBER_OF_FILLED_GROUPS -> Val.Num(dec(f.args.count { ctx.groupFilled(pathOf(it)) }))
        null -> throw UnsupportedConstructException("unsupported function ${f.name}")
    }

    /** Epoch day of a date-valued expression (ISO text), or null if empty/malformed. */
    private fun epochOf(e: Expr): Long? = (evalExpr(e) as? Val.Txt)?.let { epochDay(it.v) }

    private fun starVals(f: Expr.Func): List<Val> = ctx.star((f.args.single() as Expr.Star).path)
    private fun starNums(f: Expr.Func): List<Dec> = starVals(f).map { num(it) }

    private fun pathOf(e: Expr): String = (e as Expr.Field).path
    private fun num(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")

    private fun eq(l: Val, r: Val): Boolean = when {
        l is Val.Num && r is Val.Num -> l.v.compareTo(r.v) == 0
        l is Val.Txt && r is Val.Txt -> l.v == r.v
        l is Val.Bool && r is Val.Bool -> l.v == r.v
        else -> false
    }

    private fun and(a: Tri, b: Tri): Tri = when {
        a == Tri.FALSE || b == Tri.FALSE -> Tri.FALSE
        a == Tri.UNKNOWN || b == Tri.UNKNOWN -> Tri.UNKNOWN
        else -> Tri.TRUE
    }

    private fun or(a: Tri, b: Tri): Tri = when {
        a == Tri.TRUE || b == Tri.TRUE -> Tri.TRUE
        a == Tri.UNKNOWN || b == Tri.UNKNOWN -> Tri.UNKNOWN
        else -> Tri.FALSE
    }

    private fun not(a: Tri): Tri = when (a) {
        Tri.TRUE -> Tri.FALSE
        Tri.FALSE -> Tri.TRUE
        Tri.UNKNOWN -> Tri.UNKNOWN
    }

    private companion object {
        /** Orders comparable values: numbers numerically, text lexicographically (ISO dates → chronological). */
        val ORDER = Comparator<Val> { a, b ->
            when {
                a is Val.Num && b is Val.Num -> a.v.compareTo(b.v)
                a is Val.Txt && b is Val.Txt -> a.v.compareTo(b.v)
                else -> error("incomparable values $a vs $b")
            }
        }
    }
}
