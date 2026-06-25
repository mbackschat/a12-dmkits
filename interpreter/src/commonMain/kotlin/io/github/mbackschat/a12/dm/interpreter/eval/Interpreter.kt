package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.RoundMode
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.decOrNull
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.EvalClock
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.PredefinedType
import io.github.mbackschat.a12.dm.interpreter.model.Kind
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
    /** The model's configured base year (the kernel's `modelInfo.baseYear`), or null when the model declares
     *  none — the value of the `BaseYear` point-in-time constant. Model config, supplied by [DmInterpreter]
     *  from the [io.github.mbackschat.a12.dm.interpreter.model.ModelDef] (NOT from the clock). */
    private val baseYear: Int? = null,
    /** Repeatable groups' index-field paths (the kernel's `indexFieldName`s) — the `SemanticIndex`
     *  (`[field For "value"]`) lookup resolves a group's index field from this. Supplied by [DmInterpreter]. */
    private val indexFields: Set<String> = emptySet(),
    /** The consumer-supplied predefined-type registry (the kernel's `IPredefinedType` client SPI) — the
     *  validation domain for `Valid(field, "Name")` / `Invalid(field, "Name")`. An unregistered type name →
     *  the rule is reported unsupported (fail-fast). Supplied by [DmInterpreter]; default empty. */
    private val predefinedTypes: Map<String, PredefinedType> = emptyMap(),
    /** Consumer-supplied custom conditions (the kernel's `ICustomCondition` SPI, cross-platform), keyed by name —
     *  the validation domain for `CustomCondition X`. An unregistered name → the rule is reported unsupported.
     *  Supplied by [DmInterpreter]; default empty. */
    private val customConditions: Map<String, CustomCondition> = emptyMap(),
    /** The current rule's error-field pointer (its `errorEntityRelPath` resolved to the row's reps) — the
     *  `errorField` a custom condition's `check` receives. Null outside a rule eval (a computation / `Having`
     *  sub-eval), where `CustomCondition` cannot appear. */
    private val customErrorField: FieldPointer? = null,
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

    /**
     * The polarity [MsgType] of a fired rule, or null when it does not fire — VALUE vs OMISSION. Uses the
     * [evalTypedLazy] single walk (polarity computed only on TRUE nodes), measured faster than the full typed
     * walk in BOTH the realistic low-firing and the error-heavy high-firing regimes (INTERPRETER-PERFORMANCE.md).
     */
    fun firedType(cond: Cond): MsgType? {
        if (!gated(cond)) return null
        val r = evalTypedLazy(cond)
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
        is Cond.Tolerance -> false   // |0 − 0| = 0 is never > N (the band is ≥ 1) — an all-empty row can't fire
        is Cond.ValueListQuant -> cond.op == ValueListQuantOp.NO   // "no field in list" is TRUE when all are empty
        is Cond.And -> canFireEmpty(cond.left) && canFireEmpty(cond.right)
        is Cond.Or -> canFireEmpty(cond.left) || canFireEmpty(cond.right)
        is Cond.Not -> !canFireEmpty(cond.c)
        is Cond.Pred -> predFiresOnEmpty(cond)
        is Cond.Custom -> false   // a custom condition is guarded by a field ref; an all-empty row is not evaluated
    }

    /** Whether a presence predicate is TRUE when all its operands are empty (k = 0 filled). */
    private fun predFiresOnEmpty(p: Cond.Pred): Boolean = when (val op = PredicateOp.byName(p.name)) {
        null, PredicateOp.FIELD_FILLED, PredicateOp.GROUP_FILLED,
        PredicateOp.FIELD_VALUE_IN_LIST, PredicateOp.FIELD_VALUE_NOT_IN_LIST,
        PredicateOp.DATE_RANGES_OVERLAP, PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS,
        PredicateOp.FIELD_VALUES_NOT_UNIQUE,
        PredicateOp.VALID -> false   // needs present ranges / ≥2 present equal values / a real present date / a present value
        PredicateOp.FIELD_NOT_FILLED, PredicateOp.GROUP_NOT_FILLED -> true
        // Invalid(Date(...)) (1 arg) fires on an empty row — every part unspecified IS invalid (kernel-probed,
        // OMISSION); Invalid(field, "Type") (2 args) does NOT — an empty field is "not specified", not evaluated.
        PredicateOp.INVALID -> p.args.size == 1
        else -> quantifierFires(op, t = 0, f = p.args.size, n = p.args.size)   // all-empty: every operand FALSE
    }

    /** A node's (truth, polarity). **Interned** (3 × 2 = 6 instances) — the kernel returns an interned
     *  `ValidierungsErgebnis` enum (0 alloc); a node result is allocated per-node-per-row in the hot walk, so
     *  routing construction through [of] removes that allocation. Use `Res.of(...)`, never the constructor. */
    private data class Res(val truth: Tri, val type: MsgType) {
        companion object {
            private val interned = Array(Tri.entries.size) { t -> Array(MsgType.entries.size) { m -> Res(Tri.entries[t], MsgType.entries[m]) } }
            fun of(truth: Tri, type: MsgType): Res = interned[truth.ordinal][type.ordinal]
        }
    }

    // The typing rides alongside the truth value (kernel: ValidierungsErgebnis.combineUND/combineODER):
    //   And — omission wins (a fillable branch makes the whole error fillable);
    //   Or  — value wins  (a satisfied value branch convicts regardless of filling).
    private fun evalTyped(c: Cond): Res = when (c) {
        is Cond.Cmp -> Res.of(evalCmp(c), leafType(c.op, c.left, c.right))
        is Cond.Tolerance -> Res.of(evalTolerance(c), toleranceType(c))
        is Cond.ValueListQuant -> Res.of(evalValueListQuant(c), valueListQuantType(c))
        is Cond.Pred -> Res.of(evalPred(c), predType(c))
        // A pattern fires only on a PRESENT value (empty → not evaluated), so a fired pattern is always a
        // present-value error → VALUE (there is no "fill more to fix it" omission — the field is filled).
        is Cond.Pattern -> Res.of(evalPattern(c), MsgType.VALUE)
        // A custom condition's verdict is host-decided; a fired one is a present-value error → VALUE (kernel-probed).
        is Cond.Custom -> Res.of(evalCustom(c), MsgType.VALUE)
        is Cond.And -> {
            val a = evalTyped(c.left)
            val b = evalTyped(c.right)
            val type = if (a.type == MsgType.OMISSION || b.type == MsgType.OMISSION) MsgType.OMISSION else MsgType.VALUE
            Res.of(Kleene.and(a.truth, b.truth), type)
        }
        is Cond.Or -> {
            val a = evalTyped(c.left)
            val b = evalTyped(c.right)
            Res.of(Kleene.or(a.truth, b.truth), orType(a, b))
        }
        is Cond.Not -> {
            val a = evalTyped(c.c)
            Res.of(Kleene.not(a.truth), a.type)
        }
    }

    /**
     * **Lazy-polarity single walk** — the production typed walk behind [firedType], the kernel-mimic that pays
     * (INTERPRETER-PERFORMANCE.md #62). It computes the truth like [evalTyped] but computes a node's POLARITY
     * (`leafType`/`predType`/the And·Or combine) **only when that node's truth is TRUE**; a non-firing node
     * returns a placeholder type that is provably never read. So a **non-firing** row skips all the
     * `fill`/`leafType` work (the low-firing win) and a **firing** row does ONE walk — no double-walk, no leaf
     * re-eval, no double custom-condition invocation (the naive evalCond-then-evalTyped two-phase's costs, which
     * measured a net loss). The placeholder is sound by induction on the read sites: an `And`/`Or`'s type is
     * read only when ITS truth is TRUE, which forces both operands TRUE (And) or the read operands TRUE
     * (`orType` reads only TRUE branches) — and a TRUE node always computed its real type. The ONE exception is
     * `Not` (a firing `Not` has a FALSE child whose type it needs); `Not` is unreachable from the parser but,
     * for totality, its subtree falls back to the full [evalTyped]. Pattern / Custom keep the constant VALUE
     * type, so their truth (the costly part) is still evaluated exactly once. Correctness is gated by the full
     * kernel differential (the rendered messages' polarity must match the kernel on the whole corpus).
     */
    private fun evalTypedLazy(c: Cond): Res = when (c) {
        is Cond.Cmp -> evalCmp(c).let { Res.of(it, if (it == Tri.TRUE) leafType(c.op, c.left, c.right) else MsgType.VALUE) }
        is Cond.Tolerance -> evalTolerance(c).let { Res.of(it, if (it == Tri.TRUE) toleranceType(c) else MsgType.VALUE) }
        is Cond.ValueListQuant -> evalValueListQuant(c).let { Res.of(it, if (it == Tri.TRUE) valueListQuantType(c) else MsgType.VALUE) }
        is Cond.Pred -> evalPred(c).let { Res.of(it, if (it == Tri.TRUE) predType(c) else MsgType.VALUE) }
        is Cond.Pattern -> Res.of(evalPattern(c), MsgType.VALUE)
        is Cond.Custom -> Res.of(evalCustom(c), MsgType.VALUE)
        is Cond.And -> {
            val a = evalTypedLazy(c.left)
            val b = evalTypedLazy(c.right)
            val truth = Kleene.and(a.truth, b.truth)
            // type read only when truth==TRUE ⟹ both a,b TRUE ⟹ both types real (not the placeholder).
            val type = if (truth == Tri.TRUE && (a.type == MsgType.OMISSION || b.type == MsgType.OMISSION)) MsgType.OMISSION else MsgType.VALUE
            Res.of(truth, type)
        }
        is Cond.Or -> {
            val a = evalTypedLazy(c.left)
            val b = evalTypedLazy(c.right)
            val truth = Kleene.or(a.truth, b.truth)
            Res.of(truth, if (truth == Tri.TRUE) orType(a, b) else MsgType.VALUE)
        }
        is Cond.Not -> evalTyped(c.c).let { Res.of(Kleene.not(it.truth), it.type) }   // dead path: full typing keeps Not sound
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
        is Expr.Field -> if (ctx.filled(e.path)) Fill.of(false, false) else Fill.of(true, ctx.signed(e.path))
        is Expr.Func -> funcFill(e)
        // A SemanticIndex resolves to a matched cell or (absent row / empty cell) the empty value — fillable
        // exactly when empty, with the target field's sign, so its directional polarity matches a field ref.
        is Expr.SemanticIndex -> if (semanticIndexFilled(e)) Fill.of(false, false) else Fill.of(true, ctx.signed(e.path))
        else -> leafOmission(e).let { Fill.of(it, it) }
    }

    /** Whether a SemanticIndex resolves to a present (filled) cell — vs an absent row / empty cell (or an
     *  empty/absent key field for the field-keyed form, which also yields no match). */
    private fun semanticIndexFilled(e: Expr.SemanticIndex): Boolean =
        indexFieldOf(e.path)?.let { idx -> resolveIndexKey(e)?.let { key -> ctx.indexedCell(e.path, idx, key) != null } } ?: false

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
        FunctionOp.SUM -> starFill(f) { signed -> Fill.of(canGrow = true, canShrink = signed) }
        FunctionOp.MAX_VALUE -> starFill(f) { Fill.of(canGrow = true, canShrink = false) }
        FunctionOp.MIN_VALUE -> starFill(f) { Fill.of(canGrow = false, canShrink = true) }
        // CurrentRepetition is the structural ROW INDEX — no field-fill can change it, so a fired comparison
        // against it is always a VALUE error (kernel-probed). The generic fallback would wrongly read its group
        // argument as an "unfilled field" and type it OMISSION.
        FunctionOp.CURRENT_REPETITION -> Fill.of(false, false)
        // FirstFilledValue is displaceable by filling only when an instantiated EMPTY row PRECEDES the first
        // filled one (filling it could become the new first value); else the first value is fixed → VALUE. The
        // generic fallback (any empty/short star tail → fillable) over-reports OMISSION (kernel-probed).
        FunctionOp.FIRST_FILLED_VALUE ->
            ((f.args.singleOrNull() as? Expr.Star)?.path?.let { ctx.firstStarRowEmpty(it) } ?: false)
                .let { Fill.of(it, it) }
        else -> leafOmission(f).let { Fill.of(it, it) }
    }

    /** A starred aggregate's fill: [build] (with the starred field's sign) while its tail can still grow,
     *  else fixed; falls back to the symmetric notion if the operand is not a single star. */
    private fun starFill(f: Expr.Func, build: (signed: Boolean) -> Fill): Fill {
        val star = f.args.singleOrNull() as? Expr.Star ?: return leafOmission(f).let { Fill.of(it, it) }
        return if (ctx.starOmission(star.path)) build(ctx.signed(star.path)) else Fill.of(false, false)
    }

    /** Directional fillability of a comparison operand. **Interned** (2 × 2 = 4 instances) — the kernel packs
     *  these as booleans on the value (no separate object); allocated per `Cmp` operand in the hot walk, so use
     *  `Fill.of(...)`, never the constructor. */
    private data class Fill(val canGrow: Boolean, val canShrink: Boolean) {
        companion object {
            private val FF = Fill(false, false); private val FT = Fill(false, true)
            private val TF = Fill(true, false); private val TT = Fill(true, true)
            fun of(canGrow: Boolean, canShrink: Boolean): Fill =
                if (canGrow) (if (canShrink) TT else TF) else (if (canShrink) FT else FF)
        }
    }

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
     * `left DiffersWithToleranceRangeN right` — fires iff `|left − right| > [Cond.Tolerance.tolerance]` (the
     * band is STRICT). Numbers only; an empty operand resolves to `Val.Num(0)` (empty-as-0, like any
     * comparison); a malformed / non-numeric operand → Unknown → the rule is suppressed. The band compare
     * rescales to scale 19, like the kernel's `vergleiche`.
     */
    private fun evalTolerance(c: Cond.Tolerance): Tri {
        val a = (evalExpr(c.left) as? Val.Num)?.v ?: return Tri.UNKNOWN
        val b = (evalExpr(c.right) as? Val.Num)?.v ?: return Tri.UNKNOWN
        return Tri.of((a - b).abs().scale19().compareTo(c.tolerance.scale19()) > 0)
    }

    /** Tolerance polarity is EQ-like (kernel-probed): OMISSION when filling an empty operand toward the other
     *  could close the gap and clear the firing, else VALUE — so it is fillable iff either operand can move. */
    private fun toleranceType(c: Cond.Tolerance): MsgType {
        val l = fill(c.left)
        val r = fill(c.right)
        val fillable = l.canGrow || l.canShrink || r.canGrow || r.canShrink
        return if (fillable) MsgType.OMISSION else MsgType.VALUE
    }

    /**
     * The field-list value-list quantifiers (shape B, kernel-probed). Classify each field against the value
     * list as IN (present value ∈ list), OUT (present value ∉ list), or EMPTY (skipped from both counts); a
     * malformed field or value-list member makes the rule non-evaluable → Unknown (suppressed). Then:
     * `AtLeastOne` fires iff some field is IN, `No` iff none is IN, `NotAll` iff some field is OUT. Equality is
     * the typed [eq], as for the scalar `FieldValueIncludedInValueList`.
     */
    private fun evalValueListQuant(c: Cond.ValueListQuant): Tri {
        val list = c.values.map { evalExpr(it) }
        if (list.any { it is Val.Unknown }) return Tri.UNKNOWN
        var nIn = 0
        var nOut = 0
        for (f in c.fields) {
            if (!ctx.filled(pathOf(f))) continue            // EMPTY → counted in neither
            val v = evalExpr(f)
            if (v is Val.Unknown) return Tri.UNKNOWN          // a filled-but-malformed field → non-evaluable
            if (list.any { eq(v, it) }) nIn++ else nOut++
        }
        return when (c.op) {
            ValueListQuantOp.AT_LEAST_ONE -> Tri.of(nIn >= 1)
            ValueListQuantOp.NO -> Tri.of(nIn == 0)
            ValueListQuantOp.NOT_ALL -> Tri.of(nOut >= 1)
        }
    }

    /** Value-list-quantifier polarity (kernel-probed): `AtLeastOne`/`NotAll` fire on a PRESENT in/out value →
     *  VALUE; `No` (zero fields in list) is fixable by filling an EMPTY field with a list value → OMISSION when
     *  some field is still empty, else VALUE. */
    private fun valueListQuantType(c: Cond.ValueListQuant): MsgType = when (c.op) {
        ValueListQuantOp.AT_LEAST_ONE, ValueListQuantOp.NOT_ALL -> MsgType.VALUE
        ValueListQuantOp.NO ->
            if (c.fields.any { !ctx.filled(pathOf(it)) }) MsgType.OMISSION else MsgType.VALUE
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
        // Invalid(Date(...)) is directional: an empty (fillable) part could be filled to make the date real →
        // OMISSION; an all-present-but-unreal date is a value error → VALUE (kernel-probed, KERNEL-FINDINGS §6).
        // The predefined-type form `Invalid(field, "Name")` (2 args) is a present-value verdict → VALUE; the
        // date-construct `Invalid(Date(...))` (1 arg) is OMISSION only when an empty (fillable) part remains.
        if (op == PredicateOp.INVALID)
            return if (p.args.size == 2 || dateStatusOf(p.args.single()) != DateStatus.HAS_EMPTY) MsgType.VALUE
                else MsgType.OMISSION
        op.polarity?.let { return it }
        val n = p.args.size
        val (t, f) = quantTally(op, p.args)
        // OMISSION iff filling some currently-empty (FALSE) operand could clear the firing — each fill moves a
        // FALSE to TRUE: (t, f) → (t+j, f-j). (UNKNOWN operands aren't "fillable" — they need correcting, not
        // filling — so they don't enter this; the type isn't differential-checked with malformed operands.)
        val fillable = (1..f).any { j -> !quantifierFires(op, t + j, f - j, n) }
        return if (fillable) MsgType.OMISSION else MsgType.VALUE
    }

    private fun leafOmission(e: Expr): Boolean = when (e) {
        is Expr.Field -> !ctx.filled(e.path)
        is Expr.Star -> ctx.starOmission(e.path)
        is Expr.Func -> e.args.any { leafOmission(it) }
        is Expr.SemanticIndex -> !semanticIndexFilled(e)
        else -> false
    }

    private fun evalCond(c: Cond): Tri = when (c) {
        is Cond.Cmp -> evalCmp(c)
        is Cond.Tolerance -> evalTolerance(c)
        is Cond.ValueListQuant -> evalValueListQuant(c)
        is Cond.And -> Kleene.and(evalCond(c.left), evalCond(c.right))
        is Cond.Or -> Kleene.or(evalCond(c.left), evalCond(c.right))
        is Cond.Not -> Kleene.not(evalCond(c.c))
        is Cond.Pred -> evalPred(c)
        is Cond.Pattern -> evalPattern(c)
        is Cond.Custom -> evalCustom(c)
    }

    /**
     * `CustomCondition <name>` — delegate to the consumer-supplied [CustomCondition] (the kernel's
     * `ICustomCondition` SPI). Fires iff `check` returns true; an **unregistered** name → the rule is reported
     * unsupported (fail-fast). Passes the row's data view + the rule's error field; `relevant = null` (full
     * validation) and `formallyIncorrect` = the row's formally-invalid fields, mirroring the kernel's call.
     */
    private fun evalCustom(c: Cond.Custom): Tri {
        val condition = customConditions[c.name]
            ?: throw UnsupportedConstructException("unregistered custom condition \"${c.name}\" — register it in the custom-condition registry")
        val errorField = customErrorField
            ?: throw UnsupportedConstructException("custom condition \"${c.name}\" needs the rule's error field — unavailable in this context")
        val data = ctx.customConditionData()
        val formallyIncorrect = data.fields().filter { invalid(it.path) }.toSet()
        return Tri.of(condition.check(data, relevant = null, formallyIncorrect = formallyIncorrect, errorField = errorField))
    }

    private fun evalPred(p: Cond.Pred): Tri {
        val op = PredicateOp.byName(p.name) ?: throw UnsupportedConstructException("unsupported predicate ${p.name}")
        return when (op) {
            PredicateOp.FIELD_FILLED -> filledTri(pathOf(p.args.single()))
            PredicateOp.FIELD_NOT_FILLED -> Kleene.not(filledTri(pathOf(p.args.single())))
            PredicateOp.GROUP_FILLED -> Tri.of(ctx.groupFilled(pathOf(p.args.single())))
            PredicateOp.GROUP_NOT_FILLED -> Tri.of(!ctx.groupFilled(pathOf(p.args.single())))
            PredicateOp.FIELD_VALUE_IN_LIST -> valueInList(p.args)
            PredicateOp.FIELD_VALUE_NOT_IN_LIST -> Kleene.not(valueInList(p.args))
            PredicateOp.DATE_RANGES_OVERLAP,
            PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS -> dateRangesOverlap(p.args)
            PredicateOp.FIELD_VALUES_NOT_UNIQUE -> fieldValuesNotUnique(p.args)
            // Valid/Invalid(Date(...)): 3VL over the construction's status. A malformed part makes the whole
            // non-evaluable (UNKNOWN — both go quiet, the formal error fires); else Valid iff real-and-all-present,
            // Invalid its complement (unreal OR an empty part).
            // Two overloads share the Valid/Invalid keyword, disambiguated by arity: the date-construct form
            // `Valid(Date(...))` (1 arg) and the predefined-type form `Valid(field, "TypeName")` (2 args).
            PredicateOp.VALID -> if (p.args.size == 2) predefinedTypeValidity(p.args, expectValid = true)
                else when (dateStatusOf(p.args.single())) {
                    DateStatus.REAL -> Tri.TRUE
                    DateStatus.UNREAL_PRESENT, DateStatus.HAS_EMPTY -> Tri.FALSE
                    DateStatus.MALFORMED -> Tri.UNKNOWN
                }
            PredicateOp.INVALID -> if (p.args.size == 2) predefinedTypeValidity(p.args, expectValid = false)
                else when (dateStatusOf(p.args.single())) {
                    DateStatus.REAL -> Tri.FALSE
                    DateStatus.UNREAL_PRESENT, DateStatus.HAS_EMPTY -> Tri.TRUE
                    DateStatus.MALFORMED -> Tri.UNKNOWN
                }
            // List quantifiers over the (TRUE, FALSE) operand tally (row-evaluated gate is upstream in eval).
            PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
            PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED, PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            PredicateOp.ALL_GROUPS_FILLED, PredicateOp.NO_GROUP_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
            PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> {
                val (t, f) = quantTally(op, p.args)
                Tri.of(quantifierFires(op, t, f, p.args.size))
            }
        }
    }

    /**
     * `Valid(field, "Name")` / `Invalid(field, "Name")` — predefined-type validity (the kernel's client-SPI
     * `IPredefinedType`; KERNEL-FINDINGS §8). An EMPTY field is "not specified" → not evaluated (UNKNOWN → no
     * fire, kernel-probed); a present value is TRUE/FALSE by the registered type's [PredefinedType.accepts]
     * (`Invalid` is its complement). An UNREGISTERED type name is **unsupported** (fail-fast) — the consumer
     * must supply the validator, mirroring the kernel's client SPI.
     */
    private fun predefinedTypeValidity(args: List<Expr>, expectValid: Boolean): Tri {
        val path = pathOf(args[0])
        val typeName = (args[1] as? Expr.StrLit)?.v
            ?: throw UnsupportedConstructException("predefined-type validity needs a string type name, got ${args[1]}")
        val type = predefinedTypes[typeName]
            ?: throw UnsupportedConstructException("unknown predefined type \"$typeName\" — register it in the predefined-type registry")
        if (!ctx.filled(path)) return Tri.UNKNOWN
        val value = ctx.displayValue(path) ?: return Tri.UNKNOWN
        return Tri.of(type.accepts(value) == expectValid)
    }

    /**
     * The three-valued presence of a field — the kernel's `isWertAngegeben`: a field that is **not
     * check-relevant** (present but formally invalid — a malformed value, or a duplicate index) is **UNKNOWN**,
     * NOT a plain TRUE; an empty (check-relevant but unfilled) field is FALSE; a valid present value is TRUE. So
     * `FieldFilled(malformedQty) And …` does NOT fire (the And goes UNKNOWN), matching the kernel — a presence
     * check on a malformed field is not-check-relevant even though it reads no value. (KERNEL-FINDINGS §3.)
     */
    private fun filledTri(path: String): Tri = when {
        ctx.presenceUnknown(path) -> Tri.UNKNOWN   // presence itself is 3VL (invalid parallel index) — before filled
        !ctx.filled(path) -> Tri.FALSE
        invalid(path) -> Tri.UNKNOWN
        else -> Tri.TRUE
    }

    /** A list quantifier's operand tally as `(t, f)` — `t` = TRUE (present + check-relevant), `f` = FALSE
     *  (check-relevant but empty); the rest are UNKNOWN (present but not check-relevant — a malformed value),
     *  counted in NEITHER, matching the kernel's `isWertAngegeben`. GROUP operands have no UNKNOWN state (a group
     *  can't be malformed), so `f = n - t`. The kernel counts only TRUE for "filled" and only FALSE for "empty";
     *  a malformed field is neither — so e.g. `AtLeastOneFieldFilled` does NOT fire on a lone malformed field. */
    private fun quantTally(op: PredicateOp, args: List<Expr>): Pair<Int, Int> {
        if (op.countsGroups) {
            val t = args.count { ctx.groupFilled(pathOf(it)) }
            return t to (args.size - t)
        }
        var t = 0
        var f = 0
        for (a in args) when {
            ctx.presenceUnknown(pathOf(a)) -> {}   // presence 3VL (invalid parallel index) → UNKNOWN, neither
            !ctx.filled(pathOf(a)) -> f++          // empty (check-relevant) → FALSE
            invalid(pathOf(a)) -> {}               // present but not check-relevant (malformed/dup) → UNKNOWN
            else -> t++                            // present + check-relevant → TRUE
        }
        return t to f
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
        val l = evalCmpOperand(c.left, c.right)
        val r = evalCmpOperand(c.right, c.left)
        if (l is Val.Unknown || r is Val.Unknown) return Tri.UNKNOWN
        // The kernel's `vergleicheSTRING` returns false when EITHER operand is the empty string — for `==` AND
        // `!=` alike (an empty operand never matches and never differs). So an empty `RangeAsString` result, or a
        // literal `""`, suppresses the comparison (it does not fire) rather than comparing as a value. An empty
        // string *field* already arrives as Unknown (handled above); this covers the non-field empty strings.
        if ((l is Val.Txt && l.v.isEmpty()) || (r is Val.Txt && r.v.isEmpty())) return Tri.UNKNOWN
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
        l is Val.Num && r is Val.Num -> l.v.compareScaled19(r.v)
        l is Val.Txt && r is Val.Txt -> l.v.compareTo(r.v)
        else -> error("incomparable values $l vs $r")
    }

    private fun evalExpr(e: Expr): Val = when (e) {
        is Expr.NumLit -> e.asVal
        is Expr.StrLit -> e.asVal
        is Expr.BoolLit -> e.asVal
        is Expr.Field -> if (invalid(e.path)) Val.Unknown else ctx.field(e.path)
        is Expr.Star -> error("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
        is Expr.Func -> evalFunc(e)
        is Expr.Arith -> evalArith(e)
        is Expr.Const -> evalConst(e.name)
        is Expr.SemanticIndex -> evalSemanticIndex(e)
        is Expr.EnumCategory -> ctx.enumCategory(e.path, e.category)
    }

    /** `[field For "value"]` — the field's value in the row whose index field equals the value; an absent row
     *  or empty cell reads as the kind's EMPTY value (a number → 0, fillable), matching a missing field ref. */
    private fun evalSemanticIndex(e: Expr.SemanticIndex): Val {
        val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException("SemanticIndex needs an index field on the group of ${e.path}")
        val key = resolveIndexKey(e) ?: return emptyValue(ctx.declaredKind(e.path) ?: return Val.Unknown)
        return ctx.indexedCell(e.path, idx, key) ?: emptyValue(ctx.declaredKind(e.path) ?: return Val.Unknown)
    }

    /** The lookup key of a [Expr.SemanticIndex]: the string literal, or the CURRENT value of the key field
     *  ([Expr.SemanticIndex.indexFieldPath]) for the field-keyed `[F For keyField]` form — null when that key
     *  field is empty/absent (→ no row matches → the empty value, same as a non-matching literal). */
    private fun resolveIndexKey(e: Expr.SemanticIndex): String? =
        e.indexValue ?: e.indexFieldPath?.let { ctx.displayValue(it)?.takeIf { v -> v.isNotEmpty() } }

    /** The index-field path of [fieldPath]'s repeatable group (its sibling index field), or null if none. */
    private fun indexFieldOf(fieldPath: String): String? {
        val group = fieldPath.substringBeforeLast('/')
        return indexFields.firstOrNull { it.substringBeforeLast('/') == group }
    }

    /**
     * A no-operand constant. `Today` resolves to the injected clock's current date and `Now` to its current
     * date-time (ISO text — values comparing chronologically, like any date / date-time field). Without the
     * clock (or, for `Now`, without [EvalClock.now]) the constant cannot be resolved → reported as unsupported
     * (tolerated by `validate`), not crashed.
     */
    private fun evalConst(name: String): Val = when (ConstantOp.byName(name)) {
        ConstantOp.TODAY -> Val.Txt(clock?.today
            ?: throw UnsupportedConstructException("Today needs an evaluation clock — construct DmInterpreter with an EvalClock"))
        ConstantOp.NOW -> Val.Txt(clock?.now
            ?: throw UnsupportedConstructException("Now needs an evaluation clock with a current date-time — construct DmInterpreter with an EvalClock(today, now)"))
        // BaseYear is polymorphic by the comparison's OTHER operand (resolved in evalCmp): the year NUMBER vs a
        // number field, or the base-year-START DATE (Jan 1) vs a date field — kernel-probed. This context-free
        // path (a non-comparison use) defaults to the date form, the common case.
        ConstantOp.BASE_YEAR -> baseYearDate()
        null -> throw UnsupportedConstructException("unsupported constant $name")
    }

    /** Evaluate a comparison operand, applying the two context-typed resolutions: (a) a polymorphic `BaseYear`
     *  by the OTHER operand's type (year NUMBER vs numeric, else the base-year-START date); (b) a quoted
     *  German-format date LITERAL canonicalized to ISO when its counterpart yields a date (else it stays a
     *  plain string — the kernel types a literal by the other operand). A plain operand evaluates normally. */
    private fun evalCmpOperand(self: Expr, other: Expr): Val = when {
        self is Expr.Const && ConstantOp.byName(self.name) == ConstantOp.BASE_YEAR ->
            if (evalExpr(other) is Val.Num) baseYearNumber() else baseYearDate()
        self is Expr.StrLit && producesDate(other) -> coerceDate(Val.Txt(self.v))
        else -> evalExpr(self)
    }

    /** Whether [e] statically yields a DATE value — the signal that a counterpart string LITERAL is a date
     *  literal (German `dd.MM.yyyy`), not a plain string. A DATE field, the date constants (`Today`/`BaseYear`),
     *  or a date-producing function. */
    private fun producesDate(e: Expr): Boolean = when (e) {
        is Expr.Field -> ctx.declaredKind(e.path) == Kind.DATE
        is Expr.Const -> ConstantOp.byName(e.name).let { it == ConstantOp.TODAY || it == ConstantOp.BASE_YEAR }
        is Expr.Func -> FunctionOp.byName(e.name) in DATE_PRODUCING_FUNCS
        is Expr.SemanticIndex -> ctx.declaredKind(e.path) == Kind.DATE
        else -> false
    }

    /** Canonicalize a German `dd.MM.yyyy` date literal to ISO `yyyy-MM-dd`; any other value (already-ISO, a
     *  non-date string) is unchanged — so it only ever rewrites a literal that genuinely parses as a date. */
    private fun coerceDate(v: Val): Val =
        if (v is Val.Txt) parseDateToIso(v.v, "dd.MM.yyyy")?.let { Val.Txt(it) } ?: v else v

    /** `BaseYear` as the base-year-START date (ISO `yyyy-01-01`). */
    private fun baseYearDate(): Val = Val.Txt("${baseYearOrThrow().toString().padStart(4, '0')}-01-01")

    /** `BaseYear` as the year NUMBER. */
    private fun baseYearNumber(): Val = Val.Num(dec(baseYearOrThrow()))

    private fun baseYearOrThrow(): Int = baseYear
        ?: throw UnsupportedConstructException("BaseYear needs the model's base year (modelInfo.baseYear)")

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

        // DateFromDateTime/TimeFromDateTime: split the canonical ISO date-time `yyyy-MM-dd'T'HH:mm:ss` at the
        // `T` into its DATE part (a `yyyy-MM-dd` the date extractors read) and its TIME part (an `HH:mm:ss` the
        // time extractors read). Empty/malformed operand (no `T`) → Unknown.
        FunctionOp.DATE_FROM_DATE_TIME -> dateTimePart(f.args[0], time = false)
        FunctionOp.TIME_FROM_DATE_TIME -> dateTimePart(f.args[0], time = true)
        // DateTime(date, time): compose a DATE (`yyyy-MM-dd`) and a TIME (`HH:mm:ss`) into the canonical
        // date-time `yyyy-MM-dd'T'HH:mm:ss`. Either operand empty/malformed → Unknown.
        FunctionOp.DATE_TIME -> {
            val date = (evalExpr(f.args[0]) as? Val.Txt)?.v
            val time = (evalExpr(f.args[1]) as? Val.Txt)?.v
            if (date == null || time == null) Val.Unknown else Val.Txt("${date}T$time")
        }
        // Date(day, month, year): the constructed calendar date as ISO text — Unknown when the date is not real
        // or a part is empty/malformed (kernel-probed: the extractors see no value). Valid/Invalid inspect the
        // construction directly (they distinguish unreal from empty from malformed; this value path does not).
        FunctionOp.DATE -> analyzeDate(f.args).let { if (it.status == DateStatus.REAL) Val.Txt(it.iso!!) else Val.Unknown }
        // Time(hours, minutes, seconds): the constructed time as `HH:mm:ss`. Components are in range by model
        // validity (an out-of-range constant is MVK_WRONG_DATE_FORMAT_FOR_OP at codegen); empty/malformed → Unknown.
        FunctionOp.TIME -> timeCompose(f.args)
        // DateRange(start, end): a date range from two date operands (computation-only — the kernel rejects it in
        // a condition). Rendered to the target field's format at the compute() boundary. Empty/malformed → Unknown.
        FunctionOp.DATE_RANGE -> {
            val s = (evalExpr(f.args[0]) as? Val.Txt)?.v
            val e = (evalExpr(f.args[1]) as? Val.Txt)?.v
            if (s == null || e == null) Val.Unknown else Val.Range(s, e)
        }
        // ValueAsDate(field, FirstDay|LastDay): resolve a partial (DAY_OPTIONAL) date field's RAW value to a
        // concrete date — FirstDay → day 01, LastDay → the month's last day; a full value → itself. Reads the
        // raw value + the field's format (the canonicalized Val is Unknown for a `00`-day value).
        FunctionOp.VALUE_AS_DATE -> valueAsDate(f.args)
        // Date-time arithmetic — reduces to integer seconds, so a day boundary carries automatically.
        FunctionOp.ADD_HOURS -> dateTimeShift(f.args, 3_600L)
        FunctionOp.ADD_MINUTES -> dateTimeShift(f.args, 60L)
        FunctionOp.ADD_SECONDS -> dateTimeShift(f.args, 1L)
        // DifferenceInUnit(a, b) = (epochSecond(b) − epochSecond(a)) / unit, TRUNCATED toward zero (kernel-probed:
        // a reverse difference of −5.5 units yields −5, not −6 — so plain integer division, not floor).
        FunctionOp.DIFFERENCE_IN_HOURS -> dateTimeDiff(f.args, 3_600L)
        FunctionOp.DIFFERENCE_IN_MINUTES -> dateTimeDiff(f.args, 60L)
        FunctionOp.DIFFERENCE_IN_SECONDS -> dateTimeDiff(f.args, 1L)
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
        // |x| — Abs over an expression, AbsValue over a field; a non-numeric/Unknown operand → Unknown.
        FunctionOp.ABS, FunctionOp.ABS_VALUE -> (evalExpr(f.args[0]) as? Val.Num)?.let { Val.Num(it.v.abs()) } ?: Val.Unknown
        // The character count of a string field's value; an empty/absent (Unknown) operand → Unknown.
        FunctionOp.LENGTH -> (evalExpr(f.args[0]) as? Val.Txt)?.let { Val.Num(dec(it.v.length)) } ?: Val.Unknown
        // Rounding — `Round*([expr], n)` to n decimal places; the `*Value(field)` siblings to an integer (0).
        FunctionOp.ROUND_DOWN -> roundExpr(f.args, RoundMode.FLOOR)
        FunctionOp.ROUND_UP -> roundExpr(f.args, RoundMode.CEILING)
        FunctionOp.ROUND_ACCOUNTING -> roundExpr(f.args, RoundMode.HALF_UP)
        FunctionOp.ROUND_DOWN_VALUE -> roundValue(f.args, RoundMode.FLOOR)
        FunctionOp.ROUND_UP_VALUE -> roundValue(f.args, RoundMode.CEILING)
        FunctionOp.ROUND_ACCOUNTING_VALUE -> roundValue(f.args, RoundMode.HALF_UP)
        // FirstFilledValue: the first FILLED value in row order (empties already dropped by `star`). NOT
        // `combine` — a later malformed element is invisible; but if the first filled one is malformed it
        // arrives as Unknown, so the comparison is Unknown and the rule is suppressed. No filled row → Unknown.
        FunctionOp.FIRST_FILLED_VALUE -> starVals(f).firstOrNull() ?: Val.Unknown
        // NumberOfValueInFields(value In f1, f2, …): count the FILLED fields equal to the constant.
        FunctionOp.NUMBER_OF_VALUE_IN_FIELDS -> numberOfValueInFields(f.args)
        // RangeAsString/RangeAsNumber(field, s, e): a substring of a STRING field by 1-based inclusive range.
        FunctionOp.RANGE_AS_STRING -> rangeSubstring(f.args)?.let { Val.Txt(it) } ?: Val.Unknown
        FunctionOp.RANGE_AS_NUMBER -> rangeSubstring(f.args)?.let {
            Val.Num(if (DIGITS.matches(it)) dec(it) else DEC_ZERO)   // kernel: only `\d+` parses, else 0
        } ?: Val.Unknown
        // FieldValueAsString(numberField): the RAW stored value as a string (empty → ""). Coercion to string.
        FunctionOp.FIELD_VALUE_AS_STRING ->
            pathOf(f.args[0]).let { if (invalid(it)) Val.Unknown else Val.Txt(ctx.displayValue(it) ?: "") }
        // FieldValueAsNumber(string/numeric-enum field): the value parsed to a number; empty → 0 (kernel feldWertAlsZahl).
        FunctionOp.FIELD_VALUE_AS_NUMBER -> fieldValueAsNumber(pathOf(f.args[0]))
        // SumOfProducts(a*, b*): Σ aᵢ·bᵢ paired row-by-row (empty → 0); any malformed element → suppressed.
        FunctionOp.SUM_OF_PRODUCTS -> sumOfProducts(f.args)
        // CurrentRepetition(group): the current row's 1-based index at that group's level (the last reps part).
        FunctionOp.CURRENT_REPETITION -> Val.Num(dec(ctx.repsOf(pathOf(f.args[0])).last()))
        null -> throw UnsupportedConstructException("unsupported function ${f.name}")
    }

    /** `Round*([expr], n)` — round the numeric first operand to the n-decimal-places literal with [mode];
     *  a non-numeric/Unknown value or a non-whole places operand → Unknown. */
    private fun roundExpr(args: List<Expr>, mode: RoundMode): Val {
        val v = (evalExpr(args[0]) as? Val.Num)?.v ?: return Val.Unknown
        val places = (evalExpr(args[1]) as? Val.Num)?.v?.display()?.toIntOrNull() ?: return Val.Unknown
        return Val.Num(v.roundTo(places, mode))
    }

    /** `Round*Value(field)` — round the bare field to an integer (0 places) with [mode]; the kernel's
     *  `Math.max(0, decimalPlaces)` field form. A non-numeric/Unknown value → Unknown. */
    private fun roundValue(args: List<Expr>, mode: RoundMode): Val =
        (evalExpr(args[0]) as? Val.Num)?.let { Val.Num(it.v.roundTo(0, mode)) } ?: Val.Unknown

    /**
     * `NumberOfValueInFields(value In f1, f2, …)` — `args[0]` is the constant, `args[1..]` the bare field refs.
     * The kernel (`RuntimeController.anzahlWertInFeldlisteIntern`) counts only **filled** fields whose value
     * equals the constant: an EMPTY field is skipped (not counted), and a formally-invalid (Unknown) field
     * makes the whole count non-evaluable → the rule is suppressed. Equality uses the same typed `==` semantics.
     */
    private fun numberOfValueInFields(args: List<Expr>): Val {
        val target = evalExpr(args[0])
        var count = 0
        for (i in 1 until args.size) {
            val path = pathOf(args[i])
            if (!ctx.filled(path)) continue          // empty → not counted (kernel: b == FALSE)
            val v = evalExpr(args[i])
            if (v is Val.Unknown) return Val.Unknown // a filled-but-malformed operand → non-evaluable
            if (eq(v, target)) count++
        }
        return Val.Num(dec(count))
    }

    /**
     * `FieldValuesNotUnique(…)` — fires iff two of the operands' values are **equal**, i.e. the present values
     * are not all distinct (kernel codegen). Two operand shapes, both kernel-probed and identical in semantics:
     * a **plain field list** `(f1, f2, …)` (distinctness across named sibling fields) and a **starred** operand
     * `(Items* / Count)` (distinctness across the repetition's rows — the no-`--group` alternative to
     * `RepetitionNotUnique`). In both, **empties are skipped** (two empties are NOT a duplicate — only present
     * values compare); a filled-but-malformed operand makes the rule non-evaluable → suppressed (Unknown).
     * Equality is the typed [eq] (numbers numerically — `5` == `5.00` across scales — strings as strings). The
     * collected list is short, so an O(k²) pairwise scan is fine.
     */
    private fun fieldValuesNotUnique(args: List<Expr>): Tri {
        val values = mutableListOf<Val>()
        for (a in args) when (a) {
            is Expr.Star -> {
                val rows = ctx.star(a.path)                      // filled row values (empties dropped)
                if (rows.any { it is Val.Unknown }) return Tri.UNKNOWN  // a malformed row → non-evaluable
                values.addAll(rows)
            }
            else -> {
                if (!ctx.filled(pathOf(a))) continue             // empty → skipped (no value to compare)
                val v = evalExpr(a)
                if (v is Val.Unknown) return Tri.UNKNOWN          // a filled-but-malformed operand → non-evaluable
                values.add(v)
            }
        }
        for (i in values.indices) {
            for (j in i + 1 until values.size) if (eq(values[i], values[j])) return Tri.TRUE
        }
        return Tri.FALSE
    }

    /**
     * `SumOfProducts(a*, b*)` — Σ aᵢ·bᵢ over two parallel field lists of the SAME repeatable group, paired
     * **row-by-row** (the kernel zips the two row iterators). An empty cell reads as `0` (so its product is 0);
     * any malformed cell makes the whole sum non-evaluable → the rule is suppressed (the formal mechanism). Both
     * stars resolve over the same group, so `starAll` yields equal-length, row-aligned lists.
     */
    private fun sumOfProducts(args: List<Expr>): Val {
        val a = ctx.starAll((args[0] as Expr.Star).path)
        val b = ctx.starAll((args[1] as Expr.Star).path)
        if (a.any { it is Val.Unknown } || b.any { it is Val.Unknown }) return Val.Unknown
        var sum = DEC_ZERO
        for (i in a.indices) sum += num(a[i]) * num(b[i])
        return Val.Num(sum)
    }

    /** `FieldValueAsNumber(field)` — the field's value parsed to a number; an EMPTY field reads as `0` (kernel
     *  `feldWertAlsZahl`), a formally-invalid one → Unknown. The field's values are compile-guaranteed numeric
     *  (a numeric-valued enum or a numeric string), so a filled value always parses. */
    private fun fieldValueAsNumber(path: String): Val {
        if (invalid(path)) return Val.Unknown
        if (!ctx.filled(path)) return Val.Num(DEC_ZERO)
        return ctx.displayValue(path)?.let { decOrNull(it) }?.let { Val.Num(it) } ?: Val.Unknown
    }

    /**
     * The substring of a STRING field's value by the 1-based inclusive `(field, start, end)` [args] — the kernel's
     * `getSubstring`: an EMPTY value OR `end > length` → `""` (NOT a partial substring); else `value[start-1, end)`.
     * Returns null only when the field ref is formally invalid (the caller yields Unknown; STRING fields have no
     * formal error, so this is defensive). The bounds are integer literals, validated `1 <= start <= end` at compile.
     */
    private fun rangeSubstring(args: List<Expr>): String? {
        val path = pathOf(args[0])
        if (invalid(path)) return null
        val value = ctx.displayValue(path) ?: ""
        val s = (evalExpr(args[1]) as Val.Num).v.display().toInt()
        val e = (evalExpr(args[2]) as Val.Num).v.display().toInt()
        return if (value.isEmpty() || e > value.length) "" else value.substring(s - 1, e)
    }

    /** `DifferenceIn{Months,Years}(a, b)` → a signed whole-period count; Unknown if either date is empty. */
    private fun dateDiff(args: List<Expr>, diff: (String, String) -> Int?): Val {
        val a = (evalExpr(args[0]) as? Val.Txt)?.v ?: return Val.Unknown
        val b = (evalExpr(args[1]) as? Val.Txt)?.v ?: return Val.Unknown
        return diff(a, b)?.let { Val.Num(dec(it)) } ?: Val.Unknown
    }

    /** A number picked from a date operand's (year, month, day); Unknown when the date is empty/malformed. */
    private fun dateComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
        val parts = (coerceDate(evalExpr(e)) as? Val.Txt)?.let { dateParts(it.v) } ?: return Val.Unknown
        return Val.Num(dec(pick(parts.first, parts.second, parts.third)))
    }

    /** A number picked from a TIME operand's (hours, minutes, seconds) `HH:mm:ss`; Unknown when empty/malformed. */
    private fun timeComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
        val m = (evalExpr(e) as? Val.Txt)?.let { TIME.matchEntire(it.v) } ?: return Val.Unknown
        return Val.Num(dec(pick(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())))
    }

    /** The DATE (`time=false`) or TIME (`time=true`) part of a date-time operand — the substring before / after
     *  the canonical `T`. Unknown when the operand is empty/malformed (no `T`). */
    private fun dateTimePart(e: Expr, time: Boolean): Val {
        val v = (evalExpr(e) as? Val.Txt)?.v ?: return Val.Unknown
        val t = v.indexOf('T')
        if (t < 0) return Val.Unknown
        return Val.Txt(if (time) v.substring(t + 1) else v.substring(0, t))
    }

    /** The status of a `Date(day, month, year)` construction — the basis for the constructor's value AND the
     *  `Valid`/`Invalid` verdicts (kernel-probed, KERNEL-FINDINGS §6). MALFORMED (a part is a non-evaluable
     *  Unknown) makes the whole non-evaluable; HAS_EMPTY (a part is an empty field) is fillable. */
    private enum class DateStatus { REAL, UNREAL_PRESENT, HAS_EMPTY, MALFORMED }
    private data class DateAnalysis(val status: DateStatus, val iso: String?)

    /** Classify a `Date(day, month, year)` construction's three operands. Precedence: any malformed part →
     *  MALFORMED; else any empty field part → HAS_EMPTY; else REAL/UNREAL_PRESENT by the calendar check. */
    private fun analyzeDate(parts: List<Expr>): DateAnalysis {
        if (parts.size != 3) return DateAnalysis(DateStatus.MALFORMED, null)   // 4-arg century form not yet modeled
        var hasEmpty = false
        val nums = IntArray(3)
        for (i in 0..2) {
            val e = parts[i]
            if (e is Expr.Field && !ctx.filled(e.path)) { hasEmpty = true; continue }
            nums[i] = valToInt(evalExpr(e)) ?: return DateAnalysis(DateStatus.MALFORMED, null)
        }
        if (hasEmpty) return DateAnalysis(DateStatus.HAS_EMPTY, null)
        val (d, m, y) = Triple(nums[0], nums[1], nums[2])
        return if (isRealDate(y, m, d)) DateAnalysis(DateStatus.REAL, isoDate(y, m, d))
        else DateAnalysis(DateStatus.UNREAL_PRESENT, null)
    }

    /** The [DateStatus] of a `Valid`/`Invalid` predicate's single operand — its `Date(...)` construction;
     *  a non-`Date(...)` operand is non-evaluable here → MALFORMED (the predicate goes quiet, not wrong). */
    private fun dateStatusOf(arg: Expr): DateStatus {
        val parts = (arg as? Expr.Func)?.takeIf { FunctionOp.byName(it.name) == FunctionOp.DATE }?.args
            ?: return DateStatus.MALFORMED
        return analyzeDate(parts).status
    }

    /** `Time(hours, minutes, seconds)` → `HH:mm:ss`; a non-evaluable (empty field / malformed / out-of-range)
     *  component → Unknown. Components arrive as number fields or quoted-constant strings. */
    private fun timeCompose(args: List<Expr>): Val {
        if (args.size != 3) return Val.Unknown
        val c = IntArray(3)
        for (i in 0..2) {
            val e = args[i]
            if (e is Expr.Field && !ctx.filled(e.path)) return Val.Unknown
            c[i] = valToInt(evalExpr(e)) ?: return Val.Unknown
        }
        val (h, m, s) = Triple(c[0], c[1], c[2])
        if (h !in 0..23 || m !in 0..59 || s !in 0..59) return Val.Unknown
        return Val.Txt("${pad2(h)}:${pad2(m)}:${pad2(s)}")
    }

    /** An integer from a number value or a digit-string constant (the `Date`/`Time` components); null otherwise. */
    private fun valToInt(v: Val): Int? = when (v) {
        is Val.Num -> v.v.display().toIntOrNull()
        is Val.Txt -> v.v.toIntOrNull()
        else -> null
    }

    private fun pad2(n: Int): String = n.toString().padStart(2, '0')

    /** `Add{Hours,Minutes,Seconds}(dateTime, n)` → the date-time shifted by `n · [unitSeconds]` (day boundary
     *  carried), as ISO text; Unknown if the date-time or count is absent/malformed. */
    private fun dateTimeShift(args: List<Expr>, unitSeconds: Long): Val {
        val dt = (evalExpr(args[0]) as? Val.Txt)?.v ?: return Val.Unknown
        val n = (evalExpr(args[1]) as? Val.Num)?.v?.display()?.toLongOrNull() ?: return Val.Unknown
        return addDateTimeSeconds(dt, n * unitSeconds)?.let { Val.Txt(it) } ?: Val.Unknown
    }

    /** `DifferenceIn{Hours,Minutes,Seconds}(a, b)` → `(epochSecond(b) − epochSecond(a)) / [unitSeconds]`,
     *  truncated toward zero; Unknown if either date-time is absent/malformed. */
    private fun dateTimeDiff(args: List<Expr>, unitSeconds: Long): Val {
        val a = (evalExpr(args[0]) as? Val.Txt)?.v?.let { epochSecond(it) } ?: return Val.Unknown
        val b = (evalExpr(args[1]) as? Val.Txt)?.v?.let { epochSecond(it) } ?: return Val.Unknown
        return Val.Num(dec(((b - a) / unitSeconds).toString()))
    }

    /** `ValueAsDate(field, FirstDay|LastDay)` — resolve a (DAY_OPTIONAL) date field's raw value to a concrete
     *  date: a specified day → itself, an omitted `00` day → the month's first (`FirstDay`) or last (`LastDay`)
     *  day. Unknown when the field is empty/formally-invalid or the value doesn't parse. */
    private fun valueAsDate(args: List<Expr>): Val {
        val path = pathOf(args[0])
        if (invalid(path)) return Val.Unknown
        val raw = ctx.displayValue(path) ?: return Val.Unknown
        val lastDay = (args[1] as? Expr.Const)?.name == "LastDay"
        return resolvePartialDate(raw, ctx.dateFormatOf(path) ?: "yyyy-MM-dd", lastDay)?.let { Val.Txt(it) } ?: Val.Unknown
    }

    /** `Add{Days,Months,Years}(date, n)` → the [shift]ed date as ISO text; Unknown if the date or count is absent. */
    private fun dateShift(args: List<Expr>, shift: (String, Int) -> String?): Val {
        val date = (coerceDate(evalExpr(args[0])) as? Val.Txt)?.v ?: return Val.Unknown
        val n = (evalExpr(args[1]) as? Val.Num)?.v?.display()?.toIntOrNull() ?: return Val.Unknown
        return shift(date, n)?.let { Val.Txt(it) } ?: Val.Unknown
    }

    /** Epoch day of a date-valued expression (ISO text), or null if empty/malformed. */
    private fun epochOf(e: Expr): Long? = (coerceDate(evalExpr(e)) as? Val.Txt)?.let { epochDay(it.v) }

    private fun starVals(f: Expr.Func): List<Val> {
        val star = f.args.single() as Expr.Star
        if (star.having == null) return ctx.star(star.path)
        // Having: evaluate the per-row filter in each row context of the star's group, keep the matching rows'
        // filled values (empties dropped, like `star`) — the aggregate then consumes only those.
        val group = star.path.substringBeforeLast('/')
        return ctx.rowContexts(group)
            .filter { rc -> Interpreter(rc, clock, baseYear, indexFields, predefinedTypes, customConditions, customErrorField, invalid).fires(star.having) }
            .mapNotNull { rc -> if (rc.filled(star.path)) rc.field(star.path) else null }
    }

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
        l is Val.Num && r is Val.Num -> l.v.compareScaled19(r.v) == 0
        l is Val.Txt && r is Val.Txt -> l.v == r.v
        l is Val.Bool && r is Val.Bool -> l.v == r.v
        else -> false
    }

    private companion object {
        /** A TIME value `HH:mm:ss` — captures hours, minutes, seconds for the time-of-day extractors. */
        val TIME = Regex("""(\d{2}):(\d{2}):(\d{2})""")

        /** Functions that yield a DATE value — so a counterpart string literal beside one is a date literal
         *  ([producesDate]). The date-time/range constructors return other kinds; the extractors return numbers. */
        val DATE_PRODUCING_FUNCS = setOf(
            FunctionOp.DATE, FunctionOp.DATE_FROM_DATE_TIME,
            FunctionOp.START_OF_DATE_RANGE, FunctionOp.END_OF_DATE_RANGE,
            FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS,
            FunctionOp.VALUE_AS_DATE,
        )

        /** `RangeAsNumber` parses its substring ONLY when it is a run of digits (kernel `\d+`); else the value is 0. */
        val DIGITS = Regex("""\d+""")

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
