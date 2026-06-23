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
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
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
class Interpreter(
    private val ctx: EvalContext,
    private val clock: EvalClock? = null,
    /**
     * Whether a referenced field is **formally invalid** in this context — a present malformed value (a
     * format error) OR a required-but-empty field (a `mandatoryField` error). Such a field resolves to
     * [Val.Unknown], so the kernel's three-valued logic propagates it: a comparison goes UNKNOWN (does not
     * fire), but `… Or <true>` still fires and `… And <true>` stays UNKNOWN — the kernel's operand-level
     * "not check-relevant" behavior, NOT a whole-rule skip. Defaults to "always valid" (the `compute` path,
     * which has no formal-error increment yet). [DmInterpreter] supplies it from the model + context.
     */
    private val invalid: (String) -> Boolean = { false },
) {

    fun fires(cond: Cond): Boolean = eval(cond) == Tri.TRUE

    /**
     * Evaluate a value expression (a computation's RHS) to a typed [Val] — NOT gated by the row's
     * `evaluated` flag, since a computation computes even on an otherwise-empty row (an empty numeric
     * operand reads as 0, so `[BaseFee]` over no base fee yields 0, not nothing).
     */
    fun evalExpression(expr: Expr): Val = evalExpr(expr)

    fun eval(cond: Cond): Tri {
        if (!gated(cond)) return Tri.UNKNOWN
        return evalCond(cond)
    }

    /** The polarity [MsgType] of a fired rule, or null when it does not fire — VALUE vs OMISSION. */
    fun firedType(cond: Cond): MsgType? {
        if (!gated(cond)) return null
        val r = evalTyped(cond)
        return if (r.truth == Tri.TRUE) r.type else null
    }

    /**
     * Whether the row is evaluated at all. A non-empty row always is; an entirely-empty row is evaluated
     * only when the condition CAN fire empty ([canFireEmpty]) — so the negative presence conditions
     * (NoFieldFilled, NotAllFieldsFilled, FieldNotFilled, …) fire on a blank instance, while a comparison's
     * empty-as-0 stays suppressed (the kernel's structural "can fire empty" gate, not a data check).
     */
    private fun gated(cond: Cond): Boolean = ctx.evaluated || canFireEmpty(cond)

    /** Structural: would [cond] fire on an all-empty row? A comparison/pattern can't (empty-as-0 is
     *  suppressed; an empty string/pattern leaf is false); a presence predicate fires iff it is TRUE at a
     *  filled-count of 0; And/Or recurse. (Not is unreachable from the parser but modelled for totality.) */
    private fun canFireEmpty(cond: Cond): Boolean = when (cond) {
        is Cond.Cmp -> false
        is Cond.Pattern -> false
        is Cond.And -> canFireEmpty(cond.left) && canFireEmpty(cond.right)
        is Cond.Or -> canFireEmpty(cond.left) || canFireEmpty(cond.right)
        is Cond.Not -> !canFireEmpty(cond.c)
        is Cond.Pred -> predFiresOnEmpty(cond)
    }

    /** Whether a presence predicate is TRUE when all its operands are empty (k = 0 filled). */
    private fun predFiresOnEmpty(p: Cond.Pred): Boolean = when (val op = PredicateOp.byName(p.name)) {
        null, PredicateOp.FIELD_FILLED, PredicateOp.GROUP_FILLED,
        PredicateOp.FIELD_VALUE_IN_LIST, PredicateOp.FIELD_VALUE_NOT_IN_LIST,
        PredicateOp.DATE_RANGES_OVERLAP -> false   // needs present ranges; an empty row is not evaluated
        PredicateOp.FIELD_NOT_FILLED, PredicateOp.GROUP_NOT_FILLED -> true
        else -> quantifierFires(op, 0, p.args.size)
    }

    private data class Res(val truth: Tri, val type: MsgType)

    // The typing rides alongside the truth value (kernel: ValidierungsErgebnis.combineUND/combineODER):
    //   And — omission wins (a fillable branch makes the whole error fillable);
    //   Or  — value wins  (a satisfied value branch convicts regardless of filling).
    private fun evalTyped(c: Cond): Res = when (c) {
        is Cond.Cmp -> Res(evalCmp(c), leafType(c.op, c.left, c.right))
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

    /** The directional fillability of a comparison operand — whether continuing to fill could grow / shrink
     *  its value. An empty NUMBER field can always grow but shrinks only if SIGNED (kernel's
     *  `kannGroesserWerden`/`kannKleinerWerden = hatVorzeichen`); a filled field or literal can't move. Every
     *  other operand (star aggregate, function, arithmetic) keeps the prior *symmetric* omission notion — so
     *  for those, `leafType` below reduces exactly to the old `leafOmission(left) || leafOmission(right)`. */
    private fun fill(e: Expr): Fill = when (e) {
        is Expr.Field -> if (ctx.filled(e.path)) Fill(false, false) else Fill(true, ctx.signed(e.path))
        is Expr.Func -> funcFill(e)
        else -> leafOmission(e).let { Fill(it, it) }
    }

    /**
     * Directional fillability of a value FUNCTION. The starred aggregates carry the kernel's `NumberCombiner`
     * directions while their tail can still grow (an empty operand or an un-instantiated tail): a `Sum` can
     * grow, and shrink only if the summed field is SIGNED; `MaxValue` can only grow; `MinValue` can only
     * shrink. For a SIGNED field a `Sum`'s fill is `(true, true)` — identical to the old symmetric notion, so
     * signed aggregates are unchanged; only unsigned ones gain the directional VALUE typing. Every other
     * function (the count aggregates — a fillable operand is unconditionally OMISSION; operand-list `Min`/`Max`;
     * date functions) keeps the symmetric notion.
     */
    private fun funcFill(f: Expr.Func): Fill = when (FunctionOp.byName(f.name)) {
        FunctionOp.SUM -> starFill(f) { signed -> Fill(canGrow = true, canShrink = signed) }
        FunctionOp.MAX_VALUE -> starFill(f) { Fill(canGrow = true, canShrink = false) }
        FunctionOp.MIN_VALUE -> starFill(f) { Fill(canGrow = false, canShrink = true) }
        else -> leafOmission(f).let { Fill(it, it) }
    }

    /** A starred aggregate's fill: [build] (with the starred field's sign) while its tail can still grow,
     *  else fixed; falls back to the symmetric notion if the operand is not a single star. */
    private fun starFill(f: Expr.Func, build: (signed: Boolean) -> Fill): Fill {
        val star = f.args.singleOrNull() as? Expr.Star ?: return leafOmission(f).let { Fill(it, it) }
        return if (ctx.starOmission(star.path)) build(ctx.signed(star.path)) else Fill(false, false)
    }

    private data class Fill(val canGrow: Boolean, val canShrink: Boolean)

    /**
     * VALUE vs OMISSION of a fired comparison: OMISSION (fillable) iff filling an operand *in the breaking
     * direction* could clear it — the kernel's `BedingungsOperatorHelper.vergleiche` rule. GT/GE break when
     * the left shrinks or the right grows; LT/LE the mirror; EQ when either can move at all; NE depends on
     * which side is larger. So `[UnsignedEmpty] >= 0` is VALUE (an unsigned 0 can't shrink), while the same
     * over a signed field is OMISSION (KERNEL-FINDINGS; `DirectionalPolarityDiffTest`).
     */
    private fun leafType(op: CmpOp, left: Expr, right: Expr): MsgType {
        val l = fill(left)
        val r = fill(right)
        val fillable = when (op) {
            CmpOp.GT, CmpOp.GE -> l.canShrink || r.canGrow
            CmpOp.LT, CmpOp.LE -> l.canGrow || r.canShrink
            CmpOp.EQ -> l.canGrow || l.canShrink || r.canGrow || r.canShrink
            CmpOp.NE -> neFillable(left, right, l, r)
        }
        return if (fillable) MsgType.OMISSION else MsgType.VALUE
    }

    /** `!=` is directional on which operand is larger: if left < right it breaks when left grows or right
     *  shrinks; if left > right, the mirror (kernel `getFehlerFuerNE`). The ordering is only meaningful when
     *  the rule fires (both operands known); otherwise the type is discarded, so any answer is safe. */
    private fun neFillable(left: Expr, right: Expr, l: Fill, r: Fill): Boolean {
        val lv = evalExpr(left)
        val rv = evalExpr(right)
        if (lv is Val.Unknown || rv is Val.Unknown) return l.canGrow || l.canShrink || r.canGrow || r.canShrink
        return if (vglCompare(lv, rv) < 0) l.canGrow || r.canShrink else l.canShrink || r.canGrow
    }

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
            PredicateOp.DATE_RANGES_OVERLAP -> dateRangesOverlap(p.args)
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

    /**
     * `DateRangesOverlap(range, ranges)` — TRUE when the scalar [args]`[0]` range overlaps **any** of the
     * second operand's ranges (a starred `Items* / ItemCoverage` → the rows' ranges; else a single range). An
     * empty/absent first range → UNKNOWN (not evaluated); empty rows are skipped. Ranges are **closed
     * intervals** — overlap ⇔ `end1 ≥ start2 ∧ end2 ≥ start1` (so touching ranges overlap; KERNEL-FINDINGS §6).
     */
    private fun dateRangesOverlap(args: List<Expr>): Tri {
        val a = evalExpr(args[0]) as? Val.Range ?: return Tri.UNKNOWN
        val others = when (val b = args[1]) {
            is Expr.Star -> ctx.star(b.path)
            else -> listOf(evalExpr(b))
        }.filterIsInstance<Val.Range>()
        return Tri.of(others.any { rangesOverlap(a, it) })
    }

    /** Closed-interval overlap of two ISO date ranges (lexicographic compare = chronological on ISO). */
    private fun rangesOverlap(x: Val.Range, y: Val.Range): Boolean = x.end >= y.start && y.end >= x.start

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
            CmpOp.LT -> Tri.of(vglCompare(l, r) < 0)
            CmpOp.LE -> Tri.of(vglCompare(l, r) <= 0)
            CmpOp.GT -> Tri.of(vglCompare(l, r) > 0)
            CmpOp.GE -> Tri.of(vglCompare(l, r) >= 0)
        }
    }

    /**
     * The kernel's language comparison (`BedingungsOperatorHelper.vergleiche` / `vergleicheSTRING`): a NUMBER
     * pair is rounded to **scale 19** `HALF_UP` first (`VkBigDecimal.DEFAULT_SCALE`) — observable only beyond
     * 19 fractional digits (e.g. a scale-10 × scale-10 product); strings/dates compare lexicographically.
     * Distinct from [ORDER] (`min`/`max` *selection*), which the kernel does at full precision.
     */
    private fun vglCompare(l: Val, r: Val): Int = when {
        l is Val.Num && r is Val.Num -> l.v.scale19().compareTo(r.v.scale19())
        l is Val.Txt && r is Val.Txt -> l.v.compareTo(r.v)
        else -> error("incomparable values $l vs $r")
    }

    private fun evalExpr(e: Expr): Val = when (e) {
        is Expr.NumLit -> Val.Num(e.v)
        is Expr.StrLit -> Val.Txt(e.v)
        is Expr.BoolLit -> Val.Bool(e.v)
        is Expr.Field -> if (invalid(e.path)) Val.Unknown else ctx.field(e.path)
        is Expr.Star -> error("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
        is Expr.Func -> evalFunc(e)
        is Expr.Arith -> evalArith(e)
        is Expr.Const -> evalConst(e.name)
    }

    /**
     * A no-operand constant. `Today` resolves to the injected clock's current date (ISO text — a date
     * value comparing chronologically, like any date field). Without a clock the constant cannot be
     * resolved → reported as unsupported (tolerated by `validate`), not crashed.
     */
    private fun evalConst(name: String): Val = when (ConstantOp.byName(name)) {
        ConstantOp.TODAY -> Val.Txt(clock?.today
            ?: throw UnsupportedConstructException("Today needs an evaluation clock — construct DmInterpreter with an EvalClock"))
        null -> throw UnsupportedConstructException("unsupported constant $name")
    }

    /** Binary arithmetic on numbers; an empty numeric operand already arrives as 0. Unknown propagates. */
    private fun evalArith(a: Expr.Arith): Val {
        val l = evalExpr(a.left)
        val r = evalExpr(a.right)
        if (l is Val.Unknown || r is Val.Unknown) return Val.Unknown
        val x = num(l)
        val y = num(r)
        return when (a.op) {
            ArithOp.ADD -> Val.Num(x + y)
            ArithOp.SUB -> Val.Num(x - y)
            ArithOp.MUL -> Val.Num(x * y)
            // Division by zero is QUIET — the expression is non-evaluable → Unknown, so a referencing rule
            // simply does not fire / a computation clears (KERNEL-FINDINGS / operators.json Arithmetic).
            ArithOp.DIV -> if (y.compareTo(DEC_ZERO) == 0) Val.Unknown else Val.Num(x.div(y))
            ArithOp.POW -> power(x, y)
        }
    }

    /**
     * `base ^ exp` — the kernel evaluates it QUIETLY (→ Unknown, the rule does not fire) for the edge cases:
     * a non-whole exponent (model-rejected, so a defensive Unknown), an exponent outside `[-1000, 1000]`, or
     * `0` to a negative power. Otherwise `MathContext(50)` arithmetic via [Dec.pow]. (operators.json Arithmetic.)
     */
    private fun power(base: Dec, exp: Dec): Val {
        val e = exp.display().toIntOrNull() ?: return Val.Unknown
        if (e !in -1000..1000) return Val.Unknown
        if (base.compareTo(DEC_ZERO) == 0 && e < 0) return Val.Unknown
        return Val.Num(base.pow(e))
    }

    private fun evalFunc(f: Expr.Func): Val = when (FunctionOp.byName(f.name)) {
        // operand-list forms: every operand counts (an empty number arrives as the substituted 0)
        FunctionOp.MIN -> combine(f.args.map { evalExpr(it) }) { it.minWithOrNull(ORDER) ?: Val.Unknown }
        FunctionOp.MAX -> combine(f.args.map { evalExpr(it) }) { it.maxWithOrNull(ORDER) ?: Val.Unknown }
        // starred aggregates: empties are SKIPPED (Sum substitutes 0 = same result; Max/distinct ignore)
        FunctionOp.SUM -> combine(starVals(f)) { vs -> Val.Num(vs.fold(DEC_ZERO) { a, b -> a + num(b) }) }
        FunctionOp.MAX_VALUE -> combine(starVals(f)) { it.maxWithOrNull(ORDER) ?: Val.Unknown }
        FunctionOp.MIN_VALUE -> combine(starVals(f)) { it.minWithOrNull(ORDER) ?: Val.Unknown }
        FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> combine(starVals(f)) { vs ->
            val distinct = mutableListOf<Dec>()
            for (v in vs) { val x = num(v); if (distinct.none { it.compareTo(x) == 0 }) distinct.add(x) }
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
        // Date-component extractors — a number from the date's parts; an empty/absent date is Unknown.
        FunctionOp.YEAR_FROM_DATE -> dateComponent(f.args[0]) { y, _, _ -> y }
        FunctionOp.MONTH_FROM_DATE -> dateComponent(f.args[0]) { _, m, _ -> m }
        FunctionOp.DAY_FROM_DATE -> dateComponent(f.args[0]) { _, _, d -> d }
        FunctionOp.QUARTER_FROM_DATE -> dateComponent(f.args[0]) { _, m, _ -> (m - 1) / 3 + 1 }
        // Time-of-day extractors — a number from a TIME value's `HH:mm:ss` parts; empty/absent → Unknown.
        FunctionOp.HOURS_FROM_TIME -> timeComponent(f.args[0]) { h, _, _ -> h }
        FunctionOp.MINUTES_FROM_TIME -> timeComponent(f.args[0]) { _, m, _ -> m }
        FunctionOp.SECONDS_FROM_TIME -> timeComponent(f.args[0]) { _, _, s -> s }
        // Date arithmetic — date + n days / months / years → a date (ISO text). Empty/absent date → Unknown.
        FunctionOp.ADD_DAYS -> dateShift(f.args, ::addDays)
        FunctionOp.ADD_MONTHS -> dateShift(f.args, ::addMonths)
        FunctionOp.ADD_YEARS -> dateShift(f.args, ::addYears)
        FunctionOp.DIFFERENCE_IN_MONTHS -> dateDiff(f.args, ::differenceInMonths)
        FunctionOp.DIFFERENCE_IN_YEARS -> dateDiff(f.args, ::differenceInYears)
        // Date-range endpoint extractors — a DATE_RANGE operand → its start / end as an ISO date (so the
        // *FromDate extractors and date comparisons read it like any date). Empty/absent range → Unknown.
        FunctionOp.START_OF_DATE_RANGE -> (evalExpr(f.args[0]) as? Val.Range)?.let { Val.Txt(it.start) } ?: Val.Unknown
        FunctionOp.END_OF_DATE_RANGE -> (evalExpr(f.args[0]) as? Val.Range)?.let { Val.Txt(it.end) } ?: Val.Unknown
        null -> throw UnsupportedConstructException("unsupported function ${f.name}")
    }

    /** `DifferenceIn{Months,Years}(a, b)` → a signed whole-period count; Unknown if either date is empty. */
    private fun dateDiff(args: List<Expr>, diff: (String, String) -> Int?): Val {
        val a = (evalExpr(args[0]) as? Val.Txt)?.v ?: return Val.Unknown
        val b = (evalExpr(args[1]) as? Val.Txt)?.v ?: return Val.Unknown
        return diff(a, b)?.let { Val.Num(dec(it)) } ?: Val.Unknown
    }

    /** A number picked from a date operand's (year, month, day); Unknown when the date is empty/malformed. */
    private fun dateComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
        val parts = (evalExpr(e) as? Val.Txt)?.let { dateParts(it.v) } ?: return Val.Unknown
        return Val.Num(dec(pick(parts.first, parts.second, parts.third)))
    }

    /** A number picked from a TIME operand's (hours, minutes, seconds) `HH:mm:ss`; Unknown when empty/malformed. */
    private fun timeComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
        val m = (evalExpr(e) as? Val.Txt)?.let { TIME.matchEntire(it.v) } ?: return Val.Unknown
        return Val.Num(dec(pick(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())))
    }

    /** `Add{Days,Months,Years}(date, n)` → the [shift]ed date as ISO text; Unknown if the date or count is absent. */
    private fun dateShift(args: List<Expr>, shift: (String, Int) -> String?): Val {
        val date = (evalExpr(args[0]) as? Val.Txt)?.v ?: return Val.Unknown
        val n = (evalExpr(args[1]) as? Val.Num)?.v?.display()?.toIntOrNull() ?: return Val.Unknown
        return shift(date, n)?.let { Val.Txt(it) } ?: Val.Unknown
    }

    /** Epoch day of a date-valued expression (ISO text), or null if empty/malformed. */
    private fun epochOf(e: Expr): Long? = (evalExpr(e) as? Val.Txt)?.let { epochDay(it.v) }

    private fun starVals(f: Expr.Func): List<Val> = ctx.star((f.args.single() as Expr.Star).path)

    /**
     * Apply a numeric combiner ([Min]/[Max]/[Sum]/[MaxValue]/[MinValue]/[NumberOfDifferentValues]) to its
     * value list — but if ANY element is malformed (resolved to [Val.Unknown]), the WHOLE combiner is
     * non-evaluable and yields [Val.Unknown], so the referencing rule is **suppressed**. This matches the
     * kernel exactly (a formal-error operand makes the aggregate/combiner non-evaluable — verified for `Sum`
     * via the differential; KERNEL-FINDINGS §3/§1, `AggMalformedDiffTest`). Empties never reach a starred
     * aggregate (the star drops absent/empty cells); only a FILLED-but-malformed value is [Val.Unknown].
     */
    private inline fun combine(vals: List<Val>, op: (List<Val>) -> Val): Val =
        if (vals.any { it is Val.Unknown }) Val.Unknown else op(vals)

    private fun pathOf(e: Expr): String = (e as Expr.Field).path
    private fun num(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")

    private fun eq(l: Val, r: Val): Boolean = when {
        // NUMBER equality rounds both to scale 19 HALF_UP, like the comparison ops (the kernel's `vergleiche`).
        l is Val.Num && r is Val.Num -> l.v.scale19().compareTo(r.v.scale19()) == 0
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
        /** A TIME value `HH:mm:ss` — captures hours, minutes, seconds for the time-of-day extractors. */
        val TIME = Regex("""(\d{2}):(\d{2}):(\d{2})""")

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
