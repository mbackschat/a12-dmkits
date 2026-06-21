package io.github.mbackschat.a12.dm.interpreter.spike

/**
 * A tree-walking interpreter for the spike's condition AST over an [EvalContext] — pure static common
 * code (no kernel/groovy; the only platform primitive is [Dec]). Reproduces the kernel's runtime
 * semantics for the spike shapes: three-valued logic, the TYPE-DRIVEN empty-value handling (number→0,
 * confirm→False, others→not evaluated) gated by the filled-row precondition, operand-list vs starred
 * aggregates, and chronological (lexicographic on ISO) date comparison.
 *
 * A rule FIRES when its error condition evaluates to TRUE.
 */
class Interpreter(private val ctx: EvalContext) {

    fun fires(cond: Cond): Boolean = eval(cond) == Tri.TRUE

    fun eval(cond: Cond): Tri {
        if (!ctx.evaluated) return Tri.UNKNOWN   // entirely-empty row: not evaluated
        return evalCond(cond)
    }

    private fun evalCond(c: Cond): Tri = when (c) {
        is Cond.Cmp -> evalCmp(c)
        is Cond.And -> and(evalCond(c.left), evalCond(c.right))
        is Cond.Or -> or(evalCond(c.left), evalCond(c.right))
        is Cond.Not -> not(evalCond(c.c))
        is Cond.Pred -> evalPred(c)
    }

    private fun evalPred(p: Cond.Pred): Tri = when (p.name) {
        "FieldFilled" -> Tri.of(ctx.filled(pathOf(p.args.single())))
        "AllFieldsFilled" -> Tri.of(p.args.all { ctx.filled(pathOf(it)) })
        else -> error("spike: unsupported predicate ${p.name}")
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
        is Expr.Star -> error("spike: a starred path must sit inside an aggregate (Sum/MaxValue/…)")
        is Expr.Func -> evalFunc(e)
    }

    private fun evalFunc(f: Expr.Func): Val = when (f.name) {
        // operand-list forms: every operand counts (an empty number arrives as the substituted 0)
        "Min" -> f.args.map { evalExpr(it) }.minWithOrNull(ORDER) ?: Val.Unknown
        "Max" -> f.args.map { evalExpr(it) }.maxWithOrNull(ORDER) ?: Val.Unknown
        // starred aggregates: empties are SKIPPED (Sum substitutes 0 = same result; Max/distinct ignore)
        "Sum" -> Val.Num(starNums(f).fold(DEC_ZERO) { a, b -> a + b })
        "MaxValue" -> starVals(f).maxWithOrNull(ORDER) ?: Val.Unknown
        "MinValue" -> starVals(f).minWithOrNull(ORDER) ?: Val.Unknown
        "NumberOfDifferentValues" -> {
            val distinct = mutableListOf<Dec>()
            for (x in starNums(f)) if (distinct.none { it.compareTo(x) == 0 }) distinct.add(x)
            Val.Num(dec(distinct.size))
        }
        else -> error("spike: unsupported function ${f.name}")
    }

    private fun starVals(f: Expr.Func): List<Val> = ctx.star((f.args.single() as Expr.Star).path)
    private fun starNums(f: Expr.Func): List<Dec> = starVals(f).map { num(it) }

    private fun pathOf(e: Expr): String = (e as Expr.Field).path
    private fun num(v: Val): Dec = (v as? Val.Num)?.v ?: error("spike: expected a number, got $v")

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
                else -> error("spike: incomparable values $a vs $b")
            }
        }
    }
}
