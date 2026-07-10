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
import io.github.mbackschat.a12.dm.interpreter.model.CustomFieldType
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
    /** Partial validation only: whether a star-aggregate CELL at `(path, reps)` is relevant. A starred aggregate
     *  with ANY non-relevant cell evaluates to [Val.Unknown] — the kernel treats the aggregate as non-evaluable
     *  under a subset relevant set (it does NOT apply directional 3VL to the non-relevant cells, kernel-probed;
     *  IF35). **Null in full validation** — the gate is skipped entirely (no `starCells` call, so the flat-spike
     *  `RepDoc` context is unaffected). [DmInterpreter] supplies it only for partial validation. */
    private val cellRelevant: ((String, List<Int>) -> Boolean)? = null,
    /** Partial validation only: whether an ALL-ROWS aggregate's starred path is **wildcard-relevant** — the
     *  kernel evaluates `Sum`/`Max`/`Min`/`NumberOfDifferentValues`/`SumOfProducts` only when the relevant set
     *  WILDCARDS the starred level; concrete per-row enumeration (even of all rows) is NOT enough (IF42).
     *  `FirstFilled` does NOT use this (it is order-aware via [cellRelevant]). **Null in full validation.** */
    private val starRelevant: ((String) -> Boolean)? = null,
    /** Partial validation only: the relevant entity set passed to a [CustomCondition]'s `check` (the kernel's
     *  `relevantEntities` — `MainValidatorController` feeds it `getAllPruefungsrelevanteEntitiesExtern`). **Null
     *  in full validation** (the kernel passes `null` there, "all relevant"); a partial validation supplies the
     *  caller's relevant entities + the auto-added global fields, wildcards preserved. So a relevance-aware
     *  custom condition can return false when its fields are not relevant (IF38). [DmInterpreter] supplies it. */
    private val customRelevant: Set<FieldPointer>? = null,
    /** Consumer-supplied CODE-BASED custom field types (the kernel's `ICustomFieldType` SPI) — the imperative tail
     *  of `Valid(field, "Name")` / `Invalid(field, "Name")`, consulted ONLY after [predefinedTypes] misses (so that
     *  declarative path stays monomorphic). Supplied by [DmInterpreter]; default empty. */
    private val customFieldTypes: Map<String, CustomFieldType> = emptyMap(),
    /** The ENCLOSING iteration's context for `$` outer-correlation refs (KERNEL-FINDINGS §"$ correlation"):
     *  set only while evaluating a `Having` filter, so a `$`-marked ref inside the filter resolves against the
     *  row the aggregate/quantifier is iterating FROM, not the inner filtered row. Null at top level (a `$`
     *  there is an error — it needs an enclosing iteration). */
    private val outer: EvalContext? = null,
    /** The overlap-predicate `Having` filter (a plain boolean predicate's rep-list arg) has NO enclosing
     *  iteration to bind `$` to — unlike a quantifier's `Having`, whose star iterates. So here a `$`-marked
     *  ref is UNBOUND and resolves to UNKNOWN (the kernel then keeps the cell: keep-law = not-FALSE keeps),
     *  rather than binding to [outer] or throwing. Set only by [overlapFilterInterp] (KERNEL-FINDINGS §6, IF72). */
    private val outerUnbound: Boolean = false,
    /**
     * COMPUTE-mode evaluation semantics (IF85 — the kernel's calculation path, which differs from validation):
     * - a read of a formally-invalid cell POISONS the computation instance ([PoisonedReadException]) instead of
     *   the three-valued "not check-relevant" exclusion — the [invalid] gate THROWS (supplied by
     *   `DmInterpreter.computeInstances`), a star cell resolving to [Val.Unknown] throws at its read site;
     * - the field-fill quantifiers SCAN their cells in row order with the kernel's per-operator stop
     *   ([computeFillQuantifier]) — a cell after the deciding one is never read, so it cannot poison;
     * - `And` short-circuits a not-TRUE left (FALSE and UNKNOWN alike — the generated `isKnownAndTrue`
     *   collapse), `Or` a TRUE left;
     * - a quantifier/aggregate `Having` keeps only TRUE rows (the 2-valued collapse — validation's not-FALSE
     *   keep-law does not apply on the compute path).
     * False (the default) = validation semantics, unchanged.
     */
    private val computeMode: Boolean = false,
) {

    /** The context a node resolves against: the enclosing (`outer`) iteration for a `$`-marked ref, else [ctx]. */
    private fun ctxOf(node: Expr): EvalContext = when {
        node is Expr.Field && node.outer -> outerOrThrow()
        node is Expr.Star && node.outer -> outerOrThrow()
        node is Expr.EnumCategory && node.outer -> outerOrThrow()
        else -> ctx
    }

    /** Whether a `$`-marked node is UNBOUND in this context — an overlap-predicate `Having` ([outerUnbound]) has
     *  no enclosing iteration, so a `$` ref there resolves to UNKNOWN (not [outer], not a throw). */
    private fun outerUnknown(node: Expr): Boolean = outerUnbound && when (node) {
        is Expr.Field -> node.outer
        is Expr.Star -> node.outer
        is Expr.EnumCategory -> node.outer
        else -> false
    }

    private fun outerOrThrow(): EvalContext =
        outer ?: throw UnsupportedConstructException("a \$ outer-correlation reference is only valid inside a Having filter")

    /** Whether [e]'s expression tree references a `$` outer-correlation — used ONLY in [outerUnbound] mode (an
     *  overlap-predicate `Having`) to detect a term the codegen cannot compile (no enclosing iteration to bind
     *  `$` to), which is therefore trivially satisfied. */
    private fun hasUnboundOuter(e: Expr): Boolean = when (e) {
        is Expr.Field -> e.outer
        is Expr.Star -> e.outer
        is Expr.EnumCategory -> e.outer
        is Expr.Func -> e.args.any { hasUnboundOuter(it) }
        is Expr.Arith -> hasUnboundOuter(e.left) || hasUnboundOuter(e.right)
        else -> false
    }

    /** A sub-interpreter for a `Having` filter row [rc], carrying the SAME config and setting the enclosing
     *  iteration ([ctx]) as its `outer` — so a `$`-marked ref in the filter resolves against this row. */
    private fun havingInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, clock, baseYear, indexFields, predefinedTypes, customConditions, customErrorField, invalid,
            cellRelevant = cellRelevant, starRelevant = starRelevant, customRelevant = customRelevant,
            customFieldTypes = customFieldTypes, outer = ctx, computeMode = computeMode)

    /** A sub-interpreter for the overlap predicates' `Having` filter row [rc] — like [havingInterp] but with NO
     *  outer binding ([outerUnbound]): a plain boolean predicate's rep-list arg has no enclosing iteration, so a
     *  `$` term in its filter is unbound → UNKNOWN → the cell is KEPT (keep-law: not-FALSE keeps; KERNEL-FINDINGS §6). */
    private fun overlapFilterInterp(rc: EvalContext): Interpreter =
        Interpreter(rc, clock, baseYear, indexFields, predefinedTypes, customConditions, customErrorField, invalid,
            cellRelevant = cellRelevant, starRelevant = starRelevant, customRelevant = customRelevant,
            customFieldTypes = customFieldTypes, outer = null, outerUnbound = true, computeMode = computeMode)

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

    /** Partial-validation mode (the caller supplied a relevant set → [cellRelevant] is non-null). In partial the
     *  row-content gate is REPLACED by the upstream relevance gate: [DmInterpreter.rowOutcomes] only reaches an
     *  eval when the rule's error-field instance is RELEVANT, and the kernel checks every relevant instance —
     *  firing a comparison's empty-as-0 or an empty-identity aggregate even on a non-content-bearing (empty /
     *  phantom) relevant row (kernel-probed: `MinValue` over `Lines* then Qty` `< 5` on a `/Cart`-relevant empty
     *  document, and `[Note] < 5` on a relevant empty `Note`, both FIRE in partial though neither fires in full). So
     *  the content gate must NOT suppress here; the IF42 star-relevance gate still bounds aggregates (IF54). */
    private val partial: Boolean = cellRelevant != null

    /**
     * Whether the row is evaluated at all. In FULL validation: a content-bearing row always is; an entirely-empty
     * row is evaluated only when the condition CAN fire empty ([canFireEmpty]) — so the negative presence
     * conditions (NoFieldFilled, NotAllFieldsFilled, FieldNotFilled, …) fire on a blank instance, while a
     * comparison's empty-as-0 stays suppressed (the kernel's structural "can fire empty" gate, not a data check).
     * In [partial] validation the gate is bypassed — the upstream relevance gate has already decided (see [partial]).
     */
    private fun gated(cond: Cond): Boolean = partial || ctx.evaluated || canFireEmpty(cond)

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
    private fun fill(e: Expr): Fill {
        // The kernel's combiners mark ANY `Having`-filtered result "not specified" UNCONDITIONALLY
        // (`getValue(containsFilter)` — the DateCombiner note on [starFill] holds for every combiner), so a
        // fired comparison consuming a filtered star is TRUE_AF → OMISSION in BOTH directions — even when the
        // filter KEPT a filled row (kernel-pinned, IF104; the comparison twin of IF101's quantifier escalation).
        if (hasHavingStar(e)) return Fill.of(true, true)
        return fillOf(e)
    }

    /** Whether [e]'s tree consumes a `Having`-FILTERED star (the combiner escalation trigger, IF104). */
    private fun hasHavingStar(e: Expr): Boolean = when (e) {
        is Expr.Star -> e.having != null
        is Expr.Func -> e.args.any { hasHavingStar(it) }
        is Expr.Arith -> hasHavingStar(e.left) || hasHavingStar(e.right)
        else -> false
    }

    private fun fillOf(e: Expr): Fill = when (e) {
        is Expr.Field -> ctxOf(e).let { c -> if (c.filled(e.path)) Fill.of(false, false) else Fill.of(true, c.signed(e.path)) }
        is Expr.Func -> funcFill(e)
        // A SemanticIndex resolves to a matched cell or (absent row / empty cell) the empty value — fillable
        // exactly when empty, with the target field's sign, so its directional polarity matches a field ref.
        is Expr.SemanticIndex -> if (semanticIndexFilled(e)) Fill.of(false, false) else Fill.of(true, ctx.signed(e.path))
        is Expr.Arith -> arithFill(e)
        else -> leafOmission(e).let { Fill.of(it, it) }
    }

    /**
     * Directional fillability of an ARITHMETIC expression — the kernel's `VkBigDecimal` fill propagation
     * (`add`/`subtract`/`multiply`/`divide`/`power`) applied to the operands' resolved [fill]s, in the runtime
     * layer [ArithFill] (IF10). `×`/`÷` are SIGN-DEPENDENT so each operand's resolved value SIGN is passed;
     * `^` needs the base magnitude + exponent parity so their values are passed. A `+` over string operands is
     * CONCATENATION, not numeric — it carries no `VkBigDecimal` fill (a fired string comparison is VALUE), so it
     * keeps the fixed leaf. A malformed / non-numeric operand makes the whole term non-evaluable (the comparison
     * is UNKNOWN and does not fire, so its type is discarded) → the fixed leaf, matching `INVALID_NUMBER`.
     */
    private fun arithFill(a: Expr.Arith): Fill {
        if (a.op == ArithOp.ADD && (producesString(a.left) || producesString(a.right))) return Fill.of(false, false)
        val l = fill(a.left)
        val r = fill(a.right)
        return when (a.op) {
            ArithOp.ADD -> ArithFill.add(l, r)
            ArithOp.SUB -> ArithFill.subtract(l, r)
            ArithOp.MUL -> ArithFill.multiply(l, signOf(a.left), r, signOf(a.right))
            ArithOp.DIV -> ArithFill.divide(l, signOf(a.left), r, signOf(a.right))
            ArithOp.POW -> {
                val base = numValOrNull(a.left); val exp = numValOrNull(a.right)
                if (base == null || exp == null) Fill.of(false, false) else ArithFill.power(l, base, r, exp)
            }
        }
    }

    /** The resolved value SIGN (−1 / 0 / +1) of an operand — 0 for empty (empty-as-0) or a non-numeric/Unknown
     *  value (then the whole term is Unknown and its polarity is discarded). */
    private fun signOf(e: Expr): Int = (evalExpr(e) as? Val.Num)?.v?.compareTo(DEC_ZERO) ?: 0

    /** The resolved numeric value of an operand, or null when non-numeric/Unknown (a division-by-zero, a
     *  malformed cell) — the caller then reports the term fixed (it never fires). */
    private fun numValOrNull(e: Expr): Dec? = (evalExpr(e) as? Val.Num)?.v

    /** Whether a SemanticIndex resolves to a present (filled) cell — vs an absent row / empty cell (or an
     *  empty/absent key field for the field-keyed form, which also yields no match). */
    private fun semanticIndexFilled(e: Expr.SemanticIndex): Boolean = semanticIndexCell(e) != null

    /** The values appearing MORE than once among [cells] (each duplicate-valued cell carries the kernel's
     *  uniqueIndex error). Values compare as [Val] (typed; a Txt index compares by text). */
    private fun duplicatedValues(cells: List<Pair<List<Int>, Val?>>): Set<Val> {
        val seen = HashSet<Val>()
        val dups = HashSet<Val>()
        for ((_, v) in cells) if (v != null && v !is Val.Unknown && !seen.add(v)) dups += v
        return dups
    }

    /**
     * Whether a starred INDEX-field operand carries ANY NOT-CHECK-RELEVANT cell — the kernel's field-local index
     * errors (IF15/IF60, the VLQ [classifyVlqCell] mechanism generalized to the aggregate/count path): a
     * mandatory-EMPTY (or absent) cell of the auto-required index field, and EVERY cell of a DUPLICATED value
     * (`uniqueIndex`). A malformed cell is already not-check-relevant via [resolveValue] → [Val.Unknown], so it is
     * NOT re-counted here. The kernel's shared aggregate loop `combineFeldListe` (Sum/Min/Max/NumberOfDifferentValues)
     * and `anzahlAngegebenerFelder` (NumberOfFilledFields) return the NOT-CHECK-RELEVANT value on the FIRST such
     * cell, so the whole aggregate is non-evaluable and the referencing rule is suppressed (`IndexAggregateDiffTest`).
     * False for a non-index field or a clean index column.
     */
    private fun indexOperandNonEvaluable(c: EvalContext, path: String, bindDepth: Int = -1): Boolean {
        if (path !in indexFields) return false
        val cells = c.starCells(path, bindDepth)
        val dups = duplicatedValues(cells)
        return cells.any { (_, v) -> v == null || (v !is Val.Unknown && v in dups) }
    }

    /**
     * The instantiated fill tally of a starred INDEX-field operand, index-aware: a mandatory-EMPTY/absent cell and
     * a DUPLICATED-value cell are NOT-CHECK-RELEVANT (the kernel's `isWertAngegeben` → UNKNOWN), so they land in
     * NEITHER `t` nor `f` (the [FillScope.hasUnknownInst] bucket) exactly like a malformed cell — never as an empty
     * (`f`) or present (`t`) cell. This drives the fill quantifiers over an index field: `AtLeastOneFieldFilled`
     * stops firing when every cell is not-check-relevant (a fully-duplicated index), `NoFieldFilled` stops firing
     * when a cell is not-check-relevant (`f != n`). The DECLARED-range flags are taken from the plain [fillScope]
     * (the un-instantiated tail dominates them). `IndexAggregateDiffTest`.
     */
    private fun indexStarFillScope(c: EvalContext, path: String): FillScope {
        val declared = c.fillScope(path, isGroup = false)
        val cells = c.starCells(path)
        val dups = duplicatedValues(cells)
        var t = 0
        var n = 0
        var unknown = false
        for ((_, v) in cells) {
            n++
            when {
                v == null -> unknown = true                 // mandatory-empty index cell → not-check-relevant
                v is Val.Unknown -> unknown = true          // malformed → not-check-relevant
                v in dups -> unknown = true                 // duplicated value → uniqueIndex → not-check-relevant
                else -> t++
            }
        }
        return FillScope(t, 0, n, unknown, declared.allFilledDeclared, declared.anyEmptyDeclared)
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
            ((f.args.singleOrNull() as? Expr.Star)?.let { ctx.firstStarRowEmpty(it.path, it.bindDepth) } ?: false)
                .let { Fill.of(it, it) }
        // The COUNT aggregates are monotonic NON-DECREASING under filling — filling a field / row / distinct value
        // can only ADD to the count, never remove one. So they grow like an UNSIGNED `Sum`: `canGrow` while a
        // fillable operand remains, `canShrink` NEVER. Directionally this makes a fired `count >= n` / `count > n`
        // a VALUE error (filling can't clear it) while `count < n` / `count == n` stays OMISSION (filling still
        // can) — the old symmetric `leafOmission` wrongly typed the `>=`/`>` direction OMISSION (KERNEL-FINDINGS;
        // MultiFieldAggregateDiffTest.firedPolarityMatchesKernel, NumberOfFilledDiffTest).
        FunctionOp.NUMBER_OF_FILLED_FIELDS, FunctionOp.NUMBER_OF_FILLED_GROUPS,
        FunctionOp.NUMBER_OF_DIFFERENT_VALUES, FunctionOp.NUMBER_OF_VALUE_IN_FIELDS ->
            Fill.of(canGrow = leafOmission(f), canShrink = false)
        // Value FUNCTIONS carry their arg's directional fill through the kernel's `VkBigDecimal` (IF69's sibling).
        // Rounding is `setScale`, which PRESERVES `kannGroesser/Kleiner` exactly — so a `Round*([expr], n)` /
        // `Round*Value(field)` propagates its rounded operand's [fill] unchanged (the places literal is fixed).
        FunctionOp.ROUND_DOWN, FunctionOp.ROUND_UP, FunctionOp.ROUND_ACCOUNTING,
        FunctionOp.ROUND_DOWN_VALUE, FunctionOp.ROUND_UP_VALUE, FunctionOp.ROUND_ACCOUNTING_VALUE -> fill(f.args[0])
        // |x| — `VkBigDecimal.abs` TRANSFORMS the fill with the operand's value sign (grows off either direction,
        // shrinks only toward 0). Both `Abs([expr])` and `AbsValue(field)` take one operand.
        FunctionOp.ABS, FunctionOp.ABS_VALUE -> ArithFill.abs(fill(f.args[0]), signOf(f.args[0]))
        // Operand-list Min/Max — `VkBigDecimal.min`/`max` carry directional fill (min shrinks via either, grows
        // via the smaller; max mirrors), left-folded over the operands. DATE-like min/max ride the symmetric
        // DateCombiner, so they keep the leaf notion (numeric only here).
        FunctionOp.MIN, FunctionOp.MAX -> minMaxFill(f)
        // RangeAsNumber returns a number ≥ 0 (only `\d+` parses; kernel `bereichAlsZahl` sets `kannKleiner=false`,
        // `kannGroesser = the source string is empty`), so it is GROW-ONLY — the same directional shape as the
        // count aggregates. An empty source string still yields 0 and FIRES (unlike Length / the date extractors,
        // whose empty-operand resolution is a separate gap).
        FunctionOp.RANGE_AS_NUMBER -> Fill.of(canGrow = leafOmission(f.args[0]), canShrink = false)
        // Length is GROW-ONLY on an empty string (`laenge`: `kannGroesserWerden = nichtAngegeben`, `kannKleiner
        // = false`) — filling the string only lengthens it. So a fired `Length(empty) < n` is OMISSION (fillable
        // up), while `Length(empty) > n` / `>= n` is VALUE (0 can't shrink). The date/time extractors + diffs are
        // SYMMETRIC on an empty operand (`getDatumsTeil`/`getDatumsDiff`: grow = shrink = nichtAngegeben) — the
        // generic `else` leaf already yields that (IF73).
        FunctionOp.LENGTH -> Fill.of(canGrow = leafOmission(f.args[0]), canShrink = false)
        else -> leafOmission(f).let { Fill.of(it, it) }
    }

    /** Directional fill of an operand-list `Min`/`Max` — [ArithFill.min]/[ArithFill.max] left-folded over the
     *  operands' resolved [fill]s, selecting by value ORDER (the smaller wins a `Min`'s grow direction). Numeric
     *  operands only; a DATE-like or non-numeric operand falls back to the symmetric leaf (the kernel's date
     *  min/max carries only the symmetric `nichtAngegeben`, matching [leafOmission]). */
    private fun minMaxFill(f: Expr.Func): Fill {
        val isMin = FunctionOp.byName(f.name) == FunctionOp.MIN
        val terms = f.args.map { it to (evalExpr(it) as? Val.Num)?.v }
        // A non-numeric / DATE-like / Unknown operand → the symmetric leaf (kernel date min/max is symmetric).
        if (terms.any { it.second == null }) return leafOmission(f).let { Fill.of(it, it) }
        return terms.drop(1).fold(fill(terms[0].first) to terms[0].second!!) { (accFill, accVal), (e, v) ->
            val less = accVal.compareTo(v!!)                 // running selection vs the next operand
            val combined = if (isMin) ArithFill.min(accFill, fill(e), less) else ArithFill.max(accFill, fill(e), less)
            combined to (if (isMin == (less <= 0)) accVal else v)   // the new running min / max VALUE
        }.first
    }

    /** A starred aggregate's fill: [build] (with the starred field's sign) while its tail can still grow,
     *  else fixed; falls back to the symmetric notion if the operand is not a single star. At the EMPTY identity
     *  (no filled value yet — the aggregate is `0`), filling the FIRST value can move it EITHER way (up always;
     *  down only if signed), so the fill is `(canGrow = true, canShrink = signed)` regardless of the per-op
     *  direction — the directional [build] (Min shrink-only, Max grow-only) applies only once ≥1 value is present.
     *  So a fired `MinValue(star) < n` over an all-empty star is OMISSION (fill to raise off 0), not VALUE
     *  (kernel-probed; the fuzz surfaced this after the empty→0 identity fix — IF51).
     *
     *  A DATE-like star has NO directional fill at all: the kernel's `DateCombiner` carries only the symmetric
     *  `nichtAngegeben` (any empty cell, an un-instantiated tail, or a `Having` filter — the combiner's
     *  `getValue(containsFilter)` marks a filtered result unconditionally), and `vergleicheDATUM` types a fired
     *  comparison OMISSION on it regardless of the comparison direction (`DateAggregateDiffTest`). */
    private fun starFill(f: Expr.Func, build: (signed: Boolean) -> Fill): Fill {
        val star = f.args.singleOrNull() as? Expr.Star ?: return leafOmission(f).let { Fill.of(it, it) }
        if (dateLike(ctx.declaredKind(star.path))) {
            val om = ctx.starOmission(star.path, star.bindDepth) || star.having != null
            return Fill.of(om, om)
        }
        if (!ctx.starOmission(star.path, star.bindDepth)) return Fill.of(false, false)   // exhausted (all filled) → fixed
        if (ctx.star(star.path, star.bindDepth).isEmpty()) return Fill.of(canGrow = true, canShrink = ctx.signed(star.path))
        return build(ctx.signed(star.path))
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
        // Resolve each operand's Fill here (ctx-coupled); the per-operator polarity RULE is pure ([Polarity], IF10).
        val fillable = if (op == CmpOp.NE) neFillable(left, right, l, r) else Polarity.fillable(op, l, r)
        return if (fillable) MsgType.OMISSION else MsgType.VALUE
    }

    /** `!=` is directional on which operand is larger — the strategy resolves the ordering (both operands), then
     *  [Polarity.neFillable] applies the rule. The ordering is only meaningful when the rule fires (both known);
     *  a non-orderable pair (e.g. a DATE_RANGE `!=`) falls back to the symmetric [Polarity.anyMove] (the answer is
     *  discarded unless the rule fires with both present, where symmetric → VALUE is correct). */
    private fun neFillable(left: Expr, right: Expr, l: Fill, r: Fill): Boolean {
        val lv = evalExpr(left)
        val rv = evalExpr(right)
        if (!orderable(lv) || !orderable(rv)) return Polarity.anyMove(l, r)
        return Polarity.neFillable(ValCompare.language(lv, rv) < 0, l, r)
    }

    private fun orderable(v: Val): Boolean = v is Val.Num || v is Val.Txt

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
    private fun toleranceType(c: Cond.Tolerance): MsgType =
        if (Polarity.anyMove(fill(c.left), fill(c.right))) MsgType.OMISSION else MsgType.VALUE

    /**
     * One side of a field-list × value-list quantifier, expanded to per-cell classes: the [present] cells'
     * typed values, whether an empty cell was seen ([anyEmpty] — an empty plain field / an empty star cell /
     * a starred entry's un-instantiated declared tail; the kernel's `einWertDerWerteListeNichtAngegeben` /
     * `isAuslassungsFehler` flag sources), whether a malformed, not-check-relevant cell was seen
     * ([anyUnknown]), and whether any operand carried a `Having` filter ([hasHaving] — the kernel escalates a
     * fired result to TRUE_AF/OMISSION on a filtered operand, IG48). A star expands to one cell per
     * instantiated row.
     */
    private class VlqSide {
        val present = mutableListOf<Val>()
        var anyEmpty = false
        var anyUnknown = false
        var hasHaving = false
    }

    private fun vlqSide(entries: List<Expr>): VlqSide {
        val out = VlqSide()
        for (e in entries) when (e) {
            is Expr.Star -> {
                val c = ctxOf(e)
                // A starred INDEX field's formal errors are field-LOCAL and make the CELL not check-relevant
                // → UNKNOWN: an empty cell is mandatory-empty (the kernel auto-requires an index field per
                // row), and EVERY cell of a duplicated value carries the uniqueIndex error (over ALL rows, not
                // just filter-kept ones). A clean sibling field in the same row reads normally — the row is not
                // collectively compromised. `ValueListQuantifierStarDiffTest.compromisedIndexRows`.
                val dupIndexValues = if (e.path in indexFields) duplicatedValues(c.starCells(e.path, e.bindDepth)) else null
                if (e.having == null) {
                    for ((_, v) in c.starCells(e.path, e.bindDepth)) classifyVlqCell(out, v, dupIndexValues)
                    if (c.starOmission(e.path, e.bindDepth)) out.anyEmpty = true   // un-instantiated declared tail counts as empty
                } else {
                    // A `Having` filter (IG48): classify ONLY rows the filter keeps (keep iff it fires TRUE — the
                    // IF72 keep-law; a malformed filter operand → UNKNOWN → drop), the same per-cell classes over
                    // the kept subset. A dropped row contributes nothing; the un-instantiated tail is unreachable
                    // (no data to filter), and a Having-bearing operand forces OMISSION on fire ([hasHaving]).
                    out.hasHaving = true
                    val group = e.path.substringBeforeLast('/')
                    for (rc in c.rowContexts(group, e.bindDepth)) {
                        if (!havingInterp(rc).fires(e.having)) continue
                        classifyVlqCell(out, if (rc.filled(e.path)) rc.field(e.path) else null, dupIndexValues)
                    }
                }
            }
            is Expr.Field, is Expr.SemanticIndex -> when (presenceTri(e)) {
                Tri.TRUE -> out.present += evalExpr(e)
                Tri.FALSE -> out.anyEmpty = true
                Tri.UNKNOWN -> out.anyUnknown = true
            }
            else -> out.present += evalExpr(e)   // a literal member (never empty/unknown)
        }
        return out
    }

    /** Classify one star cell of a VLQ side by its resolved value (null = empty, [Val.Unknown] = malformed, an
     *  index-field duplicated value = field-local UNKNOWN) into [out] — shared by the unfiltered + filtered paths. */
    private fun classifyVlqCell(out: VlqSide, v: Val?, dupIndexValues: Set<Val>?) {
        when {
            v == null -> if (dupIndexValues != null) out.anyUnknown = true else out.anyEmpty = true
            v is Val.Unknown -> out.anyUnknown = true
            dupIndexValues != null && v in dupIndexValues -> out.anyUnknown = true
            else -> out.present += v
        }
    }

    /**
     * The field-list value-list quantifiers (shape B) — kernel-exact per the 30.8.1 runtime
     * (`mindestensEinFeldWertInWerteListe` / `keinFeldWertInWerteListe` / `nichtAlleFeldWerteInWerteListe`,
     * shared by both strategies; `ValueListQuantifierStarDiffTest`). Both sides expand per-cell ([vlqSide]) —
     * this includes the ENTITY value list (`f In` the flatten of `/Group*` then `x`, IF50) and STARRED fields
     * entries; equality is the
     * typed [eq] (numbers scale-19-insensitively — the kernel's `VkBigDecimal.equals` rescales too). The
     * malformed (UNKNOWN) handling is OP-ASYMMETRIC, never a blanket suppression:
     *
     *  - `AtLeastOne`: empty/malformed cells and members are SKIPPED outright; an empty value SET → no fire;
     *    fires iff some present cell ∈ values.
     *  - `No`: a malformed FIELDS cell or VALUES member POISONS (→ Unknown, no fire); fires iff no present
     *    cell ∈ values — including when nothing is filled at all.
     *  - `NotAll`: no filled FIELDS cell → no fire (`noEntityFilled`); a malformed VALUES member poisons, a
     *    malformed FIELDS cell is skipped; fires iff some present cell ∉ values.
     */
    private fun evalValueListQuant(c: Cond.ValueListQuant): Tri = when (c.op) {
        ValueListQuantOp.AT_LEAST_ONE -> {
            val values = vlqSide(c.values)
            if (values.present.isEmpty()) Tri.FALSE
            else Tri.of(vlqSide(c.fields).present.any { p -> values.present.any { eq(p, it) } })
        }
        ValueListQuantOp.NO -> {
            val values = vlqSide(c.values)
            if (values.anyUnknown) Tri.UNKNOWN
            else {
                val fields = vlqSide(c.fields)
                if (fields.anyUnknown) Tri.UNKNOWN
                else Tri.of(fields.present.none { p -> values.present.any { eq(p, it) } })
            }
        }
        ValueListQuantOp.NOT_ALL -> {
            val fields = vlqSide(c.fields)
            if (fields.present.isEmpty()) Tri.FALSE
            else {
                val values = vlqSide(c.values)
                if (values.anyUnknown) Tri.UNKNOWN
                else Tri.of(fields.present.any { p -> values.present.none { eq(p, it) } })
            }
        }
    }

    /** Fired value-list-quantifier polarity (kernel TRUE_AF vs TRUE_WF): a `Having` filter on ANY operand
     *  escalates a fired result to OMISSION (TRUE_AF), unconditionally (IG48) — else `AtLeastOne` fires on a
     *  PRESENT matching value → VALUE (empties are irrelevant to it); a fired `No` is OMISSION on any empty
     *  FIELDS cell / un-instantiated tail OR an empty VALUES member; a fired `NotAll` is OMISSION only on the
     *  VALUES side's account — it has no fields-tail flag (the No-vs-NotAll asymmetry,
     *  `ValueListQuantifierStarDiffTest` / `ValueListQuantifierHavingDiffTest`). */
    private fun valueListQuantType(c: Cond.ValueListQuant): MsgType {
        val fields = vlqSide(c.fields)
        val values = vlqSide(c.values)
        if (fields.hasHaving || values.hasHaving) return MsgType.OMISSION
        return when (c.op) {
            ValueListQuantOp.AT_LEAST_ONE -> MsgType.VALUE
            ValueListQuantOp.NO -> if (fields.anyEmpty || values.anyEmpty) MsgType.OMISSION else MsgType.VALUE
            ValueListQuantOp.NOT_ALL -> if (values.anyEmpty) MsgType.OMISSION else MsgType.VALUE
        }
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
        // The overlap predicates' polarity is filter-derived, computed in the SAME scan as the truth (shared [Res]).
        if (op == PredicateOp.DATE_RANGES_OVERLAP) return overlapAny(p.args).type
        if (op == PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS) return atLeastOneOverlap(p.args).type
        op.polarity?.let { return it }
        if (!op.countsGroups && hasFillScopeOperand(p.args)) return fillScopeType(op, fillScopeInfo(p.args))
        // A fired quantifier over a Having-FILTERED field star is UNCONDITIONALLY escalated to TRUE_AF →
        // OMISSION (the fill-quantifier twin of the §8 value-list rule "a Having-filtered entry escalates a
        // fired result to TRUE_AF"): kernel-pinned in BOTH directions — NoFieldFilled fired with nothing kept
        // AND AtLeastOneFieldFilled fired over a fully-kept FILLED row are both OMISSION (IF101). The kept-rows
        // fillability below would type the zero-kept case VALUE (nothing to fill) — the E3 flip class.
        if (!op.countsGroups && (p.args.singleOrNull() as? Expr.Star)?.having != null) return MsgType.OMISSION
        val (t, f, n) = quantCounts(op, p.args)
        // OMISSION iff filling more content could clear the firing. For a STARRED group operand the fix is ADDING
        // a row — (t, f, n) → (t+1, f, n+1), n is not fixed — so `NoGroupFilled(G*)` at zero rows is OMISSION
        // (add a row) while `AtLeastOneGroupFilled(G*)` is VALUE (adding never clears it) (IF68). For a fixed-arity
        // quantifier each fill moves a FALSE operand to TRUE: (t, f) → (t+j, f-j). (UNKNOWN operands aren't
        // "fillable" — they need correcting, not filling — so they don't enter this.)
        val fillable = if (op.countsGroups && p.args.singleOrNull() is Expr.Star) !quantifierFires(op, t + 1, f, n + 1)
            else (1..f).any { j -> !quantifierFires(op, t + j, f - j, n) }
        return if (fillable) MsgType.OMISSION else MsgType.VALUE
    }

    private fun leafOmission(e: Expr): Boolean = when (e) {
        is Expr.Field -> !ctx.filled(e.path)
        // A `Having`-FILTERED star escalates a fired consumer to TRUE_AF unconditionally — the kernel's
        // filtered-iterator escalation, the same rule the filtered fill quantifiers follow (IF101): even a
        // comparison whose filter KEPT a filled row fires OMISSION (kernel-pinned, IF104). An unfiltered star
        // keeps the kept-set omission notion ([EvalContext.starOmission]).
        is Expr.Star -> e.having != null || ctx.starOmission(e.path, e.bindDepth)
        is Expr.Func -> e.args.any { leafOmission(it) }
        is Expr.SemanticIndex -> !semanticIndexFilled(e)
        else -> false
    }

    private fun evalCond(c: Cond): Tri = when (c) {
        is Cond.Cmp -> evalCmp(c)
        is Cond.Tolerance -> evalTolerance(c)
        is Cond.ValueListQuant -> evalValueListQuant(c)
        // COMPUTE mode short-circuits like the kernel's generated `isKnownAndTrue` collapse (IF85): an And
        // whose LEFT is not-TRUE (FALSE or UNKNOWN) never evaluates the right — so a poisoning read there
        // does not happen; an Or with a TRUE left likewise. Validation keeps eager Kleene (its UNKNOWN∧FALSE
        // = FALSE distinction is observable in polarity/keep-laws; no poison channel exists there).
        is Cond.And ->
            if (computeMode) evalCond(c.left).let { l -> if (l != Tri.TRUE) l else evalCond(c.right) }
            else Kleene.and(evalCond(c.left), evalCond(c.right))
        is Cond.Or ->
            if (computeMode) evalCond(c.left).let { l -> if (l == Tri.TRUE) Tri.TRUE else Kleene.or(l, evalCond(c.right)) }
            else Kleene.or(evalCond(c.left), evalCond(c.right))
        is Cond.Not -> Kleene.not(evalCond(c.c))
        is Cond.Pred -> evalPred(c)
        is Cond.Pattern -> evalPattern(c)
        is Cond.Custom -> evalCustom(c)
    }

    /**
     * `CustomCondition <name>` — delegate to the consumer-supplied [CustomCondition] (the kernel's
     * `ICustomCondition` SPI). Fires iff `check` returns true; an **unregistered** name → the rule is reported
     * unsupported (fail-fast). Passes the row's data view + the rule's error field; [customRelevant] (null in
     * full validation, the relevant set in partial — IF38) and `formallyIncorrect` = the row's formally-invalid
     * fields, mirroring the kernel's call.
     */
    private fun evalCustom(c: Cond.Custom): Tri {
        val condition = customConditions[c.name]
            ?: throw UnsupportedConstructException("unregistered custom condition \"${c.name}\" — register it in the custom-condition registry")
        val errorField = customErrorField
            ?: throw UnsupportedConstructException("custom condition \"${c.name}\" needs the rule's error field — unavailable in this context")
        val data = ctx.customConditionData()
        val formallyIncorrect = data.fields().filter { invalid(it.path) }.toSet()
        return Tri.of(condition.check(data, relevant = customRelevant, formallyIncorrect = formallyIncorrect, errorField = errorField))
    }

    private fun evalPred(p: Cond.Pred): Tri {
        // Overlap-predicate Having: a predicate touching an unbound `$` is trivially TRUE (see [evalCmp]).
        if (outerUnbound && p.args.any { hasUnboundOuter(it) }) return Tri.TRUE
        val op = PredicateOp.byName(p.name) ?: throw UnsupportedConstructException("unsupported predicate ${p.name}")
        return when (op) {
            PredicateOp.FIELD_FILLED -> presenceTri(p.args.single())
            PredicateOp.FIELD_NOT_FILLED -> Kleene.not(presenceTri(p.args.single()))
            PredicateOp.GROUP_FILLED -> Tri.of(ctxOf(p.args.single()).groupFilled(pathOf(p.args.single())))
            PredicateOp.GROUP_NOT_FILLED -> Tri.of(!ctxOf(p.args.single()).groupFilled(pathOf(p.args.single())))
            PredicateOp.FIELD_VALUE_IN_LIST -> valueInList(p.args)
            PredicateOp.FIELD_VALUE_NOT_IN_LIST -> Kleene.not(valueInList(p.args))
            PredicateOp.DATE_RANGES_OVERLAP -> overlapAny(p.args).truth
            PredicateOp.ATLEAST_ONE_DATE_RANGE_OVERLAPS -> atLeastOneOverlap(p.args).truth
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
            // COMPUTE mode replaces the tally with the kernel's ordered SCAN for the FIELD-fill family (IF85):
            // each visited cell is a read (an invalid one poisons), and the per-operator stop ends the scan —
            // a later invalid cell is never read. Group-fill quantifiers keep the tally (group presence reads
            // the fill caches, not cell values).
            PredicateOp.ALL_FIELDS_FILLED, PredicateOp.NO_FIELD_FILLED, PredicateOp.AT_LEAST_ONE_FIELD_FILLED,
            PredicateOp.MORE_THAN_ONE_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
            PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED, PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED,
            PredicateOp.ALL_GROUPS_FILLED, PredicateOp.NO_GROUP_FILLED, PredicateOp.AT_LEAST_ONE_GROUP_FILLED,
            PredicateOp.NOT_ALL_GROUPS_FILLED, PredicateOp.GROUPS_NOT_COLLECTIVELY_FILLED -> when {
                computeMode && !op.countsGroups -> computeFillQuantifier(op, p.args)
                !op.countsGroups && hasFillScopeOperand(p.args) -> Tri.of(fillScopeFires(op, fillScopeInfo(p.args)))
                else -> {
                    val (t, f, n) = quantCounts(op, p.args)
                    Tri.of(quantifierFires(op, t, f, n))
                }
            }
        }
    }

    /** A field-fill quantifier operand that needs the group/starred-scope treatment (IF49): a bare GROUP ref (a
     *  `Field` whose path is not a declared field), or a starred field/group WITHOUT a `Having` (a Star with a
     *  Having is the filtered-correlation path, handled by [starQuantTally]). A plain field list is not a scope. */
    private fun hasFillScopeOperand(args: List<Expr>): Boolean = args.any {
        (it is Expr.Star && it.having == null) || (it is Expr.Field && ctxOf(it).declaredKind(it.path) == null)
    }

    /** Aggregate the [FillScope] tally across a fill quantifier's operands (a group scope expands to its
     *  descendant fields; the counts sum, the declared flags OR/AND). */
    private fun fillScopeInfo(args: List<Expr>): FillScope {
        var t = 0; var f = 0; var n = 0; var unknown = false
        var allFilled = true; var anyEmpty = false
        for (a in args) {
            val path = scopePathOf(a)
            val c = ctxOf(a)
            // A starred index-field operand's instantiated tally is index-aware (mandatory-empty / duplicated
            // cells are not-check-relevant → the UNKNOWN bucket), so a fill quantifier over an index field matches
            // the kernel's `isWertAngegeben` (AtLeastOne stops firing on a fully-duplicated index; IF15/IF60).
            val fs = if (a is Expr.Star && a.having == null && a.path in indexFields) indexStarFillScope(c, a.path)
                     else c.fillScope(path, isGroup = c.declaredKind(path) == null)
            t += fs.tInst; f += fs.fInst; n += fs.nInst; unknown = unknown || fs.hasUnknownInst
            allFilled = allFilled && fs.allFilledDeclared
            anyEmpty = anyEmpty || fs.anyEmptyDeclared
        }
        return FillScope(t, f, n, unknown, allFilled, anyEmpty)
    }

    /** Whether a field-fill quantifier fires over a group/starred scope — `AllFieldsFilled`/`NotAllFieldsFilled`
     *  use the DECLARED range, `FieldsNotCollectivelyFilled` mixes, the rest use the instantiated tally (IF49). */
    private fun fillScopeFires(op: PredicateOp, fs: FillScope): Boolean = when (op) {
        PredicateOp.ALL_FIELDS_FILLED -> fs.allFilledDeclared
        PredicateOp.NOT_ALL_FIELDS_FILLED -> fs.anyEmptyDeclared
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> fs.tInst >= 1 && fs.anyEmptyDeclared
        else -> quantifierFires(op, fs.tInst, fs.fInst, fs.nInst)
    }

    /** The message polarity of a fired group/starred-scope fill quantifier — the kernel's fixed WF/AF per op
     *  (`NoField`/`NotAll`/`FieldsNotCollectively` are omission errors; `NotExactlyOne` is omission at 0 filled,
     *  value at ≥2; the rest are value errors). IF49. */
    private fun fillScopeType(op: PredicateOp, fs: FillScope): MsgType = when (op) {
        PredicateOp.NO_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> MsgType.OMISSION
        PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> if (fs.tInst == 0) MsgType.OMISSION else MsgType.VALUE
        else -> MsgType.VALUE
    }

    private fun scopePathOf(e: Expr): String = when (e) {
        is Expr.Field -> e.path
        is Expr.Star -> e.path
        else -> throw UnsupportedConstructException("not a fill-scope operand: $e")
    }

    // ---- COMPUTE-mode fill-quantifier scans (IF85 — the kernel's RuntimeController read order) --------------

    /**
     * COMPUTE-mode field-fill quantifiers — the kernel's ordered SCAN, not validation's tally: each visited
     * cell is a READ (an INVALID one poisons, [PoisonedReadException]), and each operator stops at its deciding
     * cell, so a cell after the stop is never read (kernel `mindestensEineEntityAngegeben` stops at the first
     * filled; `keineEntityAngegeben` at the first filled; `alleEntitiesAngegeben` / `nichtAlleOderKeine…` at the
     * first empty — the latter two over the DECLARED range, where an un-instantiated rep reads empty without a
     * value read). `NotExactlyOne` and `FieldsNotCollectivelyFilled` compose two scans, like the kernel.
     * During compute there is no not-relevant UNKNOWN (the kernel resets the sets and throws instead), so a
     * cell is simply FILLED or EMPTY. Locked by `adapter.laws.ComputePoisonReadDiffTest`.
     */
    private fun computeFillQuantifier(op: PredicateOp, args: List<Expr>): Tri = when (op) {
        PredicateOp.AT_LEAST_ONE_FIELD_FILLED -> Tri.of(scanFilled(args, declaredTail = false).any { it })
        PredicateOp.NO_FIELD_FILLED -> Tri.of(scanFilled(args, declaredTail = false).none { it })
        PredicateOp.ALL_FIELDS_FILLED -> Tri.of(scanFilled(args, declaredTail = true).all { it })
        PredicateOp.NOT_ALL_FIELDS_FILLED -> Tri.of(!scanFilled(args, declaredTail = true).all { it })
        PredicateOp.MORE_THAN_ONE_FIELD_FILLED -> {
            var seen = 0
            Tri.of(scanFilled(args, declaredTail = false).any { it && ++seen > 1 })
        }
        // The kernel's two-pass composition: TRUE when NONE is filled, else the more-than-one scan decides.
        PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED ->
            if (scanFilled(args, declaredTail = false).none { it }) Tri.TRUE
            else {
                var seen = 0
                Tri.of(scanFilled(args, declaredTail = false).any { it && ++seen > 1 })
            }
        // at-least-one filled AND not-all filled (kernel `entitiesNichtGemeinsamAngegeben`, two passes).
        PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED ->
            if (scanFilled(args, declaredTail = false).none { it }) Tri.FALSE
            else Tri.of(!scanFilled(args, declaredTail = true).all { it })
        else -> throw UnsupportedConstructException("not a field-fill quantifier: $op")
    }

    /**
     * The lazy filled/empty cell stream of a fill quantifier's operands, in the kernel's read order — operands
     * in list order, a star's rows in row order, a `Having` filter evaluated per row inside the scan (its reads
     * can poison too). An INVALID visited cell throws ([PoisonedReadException]); [declaredTail] appends the
     * DECLARED-range emptiness after a star/group's instantiated cells (an un-instantiated rep reads empty —
     * no value read, so it cannot poison; kernel `alleEntitiesAngegeben`'s declared iteration).
     */
    private fun scanFilled(args: List<Expr>, declaredTail: Boolean): Sequence<Boolean> = sequence {
        for (a in args) when {
            a is Expr.Star && a.having == null -> {
                val c = ctxOf(a)
                for (state in c.starScanStates(a.path, a.bindDepth)) yield(filledOrPoison(a.path, state))
                if (declaredTail && c.fillScope(a.path, isGroup = false).anyEmptyDeclared) yield(false)
            }
            a is Expr.Star -> {
                // A filtered star: the filter runs per row inside the scan (its reads poison row-correctly); an
                // un-instantiated declared rep cannot pass a filter, so there is no declared tail.
                val group = a.path.substringBeforeLast('/')
                for (rc in ctxOf(a).rowContexts(group, a.bindDepth)) {
                    if (!havingInterp(rc).fires(a.having!!)) continue
                    yield(filledOrPoison(a.path, rc.cellState(a.path)))
                }
            }
            a is Expr.Field && ctxOf(a).declaredKind(a.path) == null -> {
                // A GROUP scope expands to its contained fields. The tally reads every instantiated cell (the
                // poison is order-free once no scan stop can precede it within the group — an approximation of
                // the kernel's per-field declared interleaving, which no probe distinguishes yet).
                val fs = ctxOf(a).fillScope(a.path, isGroup = true)
                if (fs.hasUnknownInst) throw PoisonedReadException(a.path)
                repeat(fs.tInst) { yield(true) }
                repeat(fs.fInst) { yield(false) }
                if (declaredTail && fs.anyEmptyDeclared) yield(false)
            }
            else -> yield(
                when (presenceTri(a)) {
                    Tri.TRUE -> true
                    // No compute-time not-relevant state exists (the kernel resets it; invalidity THROWS in
                    // presenceTri/filledTri) — a residual UNKNOWN (unbound `$`, invalid parallel index) reads empty.
                    else -> false
                },
            )
        }
    }

    private fun filledOrPoison(path: String, state: CellState): Boolean = when (state) {
        CellState.INVALID -> throw PoisonedReadException(path)
        CellState.FILLED -> true
        CellState.EMPTY -> false
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
        // Declarative first (monomorphic), then a code-based type; unregistered in BOTH → unsupported (fail-fast).
        val accept: (String) -> Boolean = predefinedTypes[typeName]?.let { it::accepts }
            ?: customFieldTypes[typeName]?.let { it::validate }
            ?: throw UnsupportedConstructException("unknown predefined type \"$typeName\" — register it in the predefined-type or custom-field-type registry")
        if (!ctx.filled(path)) return Tri.UNKNOWN
        val value = ctx.displayValue(path) ?: return Tri.UNKNOWN
        return Tri.of(accept(value) == expectValid)
    }

    /**
     * The three-valued presence of a field — the kernel's `isWertAngegeben`: a field that is **not
     * check-relevant** (present but formally invalid — a malformed value, or a duplicate index) is **UNKNOWN**,
     * NOT a plain TRUE; an empty (check-relevant but unfilled) field is FALSE; a valid present value is TRUE. So
     * `FieldFilled(malformedQty) And …` does NOT fire (the And goes UNKNOWN), matching the kernel — a presence
     * check on a malformed field is not-check-relevant even though it reads no value. (KERNEL-FINDINGS §3.)
     */
    private fun filledTri(c: EvalContext, path: String): Tri = when {
        c.presenceUnknown(path) -> Tri.UNKNOWN   // presence itself is 3VL (invalid parallel index) — before filled
        // COMPUTE mode: a presence check READS the cell — an invalid one poisons (IF85), row-correct via the
        // context's own cellState (a `Having` filter row judges ITS row's cell, not the computation's).
        computeMode && c.cellState(path) == CellState.INVALID -> throw PoisonedReadException(path)
        !c.filled(path) -> Tri.FALSE
        invalid(path) -> Tri.UNKNOWN
        else -> Tri.TRUE
    }

    /** A list quantifier's operand tally as `(t, f)` — `t` = TRUE (present + check-relevant), `f` = FALSE
     *  (check-relevant but empty); the rest are UNKNOWN (present but not check-relevant — a malformed value),
     *  counted in NEITHER, matching the kernel's `isWertAngegeben` (the shared [presenceTri], which also
     *  resolves a SEMANTIC-INDEX operand to its one matched cell — `SemanticIndexPresenceDiffTest`). GROUP
     *  operands have no UNKNOWN state (a group can't be malformed), so `f = n - t`. The kernel counts only TRUE
     *  for "filled" and only FALSE for "empty"; a malformed field is neither — so e.g. `AtLeastOneFieldFilled`
     *  does NOT fire on a lone malformed field. A `$`-marked operand tallies against the enclosing row (ctxOf). */
    private fun quantTally(op: PredicateOp, args: List<Expr>): Pair<Int, Int> {
        if (op.countsGroups) {
            val t = args.count { ctxOf(it).groupFilled(pathOf(it)) }
            return t to (args.size - t)
        }
        var t = 0
        var f = 0
        for (a in args) {
            when (presenceTri(a)) {
                Tri.TRUE -> t++
                Tri.FALSE -> f++
                Tri.UNKNOWN -> {}
            }
        }
        return t to f
    }

    /** The quantifier's `(TRUE, FALSE, N)` tally — a STARRED operand expands to its filtered rows: a
     *  countsGroups predicate's star is a starred repeatable GROUP ([starGroupTally]); a field quantifier's star
     *  is a field-list (`AtLeastOneFieldFilled(Group* / Field Having …)`, [starQuantTally]); else a plain
     *  field/group operand list. */
    private fun quantCounts(op: PredicateOp, args: List<Expr>): Triple<Int, Int, Int> {
        val single = args.singleOrNull()
        if (single is Expr.Star) return if (op.countsGroups) starGroupTally(single) else starQuantTally(single)
        val (t, f) = quantTally(op, args)
        return Triple(t, f, args.size)
    }

    /**
     * Tally a STARRED single repeatable-GROUP quantifier operand — `NoGroupFilled(G*)` / `AtLeastOneGroupFilled(G*)`.
     * Unlike a field-list star ([starQuantTally], whose path ends at a FIELD), the operand IS the group, and EVERY
     * instantiated row counts as a "filled" group (a created row is content — IF46, matching `NumberOfFilledGroups`),
     * so `t = n = instantiated (Having-matching) rows` and `f = 0` (probe-verified: the kernel does NOT fire
     * `NoGroupFilled(G*)` on one instantiated-but-empty row, and DOES fire `AtLeastOneGroupFilled(G*)`). The kernel
     * forbids `*` on a group for the AllGroupsFilled / NotAllGroupsFilled / collectively family
     * (`MVK_NO_WILDCARDS_G_ALLOWED`), so only the count-zero / count-≥1 predicates reach here on a valid model.
     * (The earlier code mis-routed this to [starQuantTally], which resolved the group's PARENT and counted zero —
     * an over-fire of `NoGroupFilled` and an under-fire of `AtLeastOneGroupFilled`; IF68.)
     */
    private fun starGroupTally(star: Expr.Star): Triple<Int, Int, Int> {
        val rows = ctxOf(star).rowContexts(star.path, star.bindDepth)
        val n = if (star.having == null) rows.size else rows.count { havingInterp(it).fires(star.having) }
        return Triple(n, 0, n)
    }

    /**
     * `NumberOfFilledGroups(...)`'s count — the STARRED single-repeatable-group form `NumberOfFilledGroups(
     * /Group*)` (the `RootGroupCount` DSL node / `Dsl.numberOfFilledGroups(repeatableGroup)`) counts the group's
     * **instantiated repetitions** (a created-but-empty row counts too — kernel-probed, IF46); the plain
     * multi-group form counts each listed group that is [EvalContext.groupFilled]. Linear: O(rows) for the
     * starred form (one row-context list, already scoped + cached), O(args) for the plain form.
     */
    private fun filledGroupsCount(args: List<Expr>): Int {
        val star = args.singleOrNull() as? Expr.Star
        if (star != null) return ctxOf(star).rowContexts(star.path, star.bindDepth).size
        return args.count { ctxOf(it).groupFilled(pathOf(it)) }
    }

    /**
     * `NumberOfFilledFields(...)`'s count across its operands — a plain field counts 1 when filled; a STARRED
     * repetition list (`Items* then Count`) counts its FILLED cells across all rows (IF51, the `pathOf(Star)`
     * crash); a GROUP operand counts the FILLED fields INSIDE it recursively (IF51, the kernel `ExpandService` →
     * `getAllContainedFields`, via [EvalContext.fillScope]). A present-but-**malformed** cell is a formal error
     * that makes the count **non-evaluable** → the whole function is [Val.Unknown] and the referencing rule is
     * suppressed (the kernel's formal-suppression, exactly as [combine] does for the value aggregates — the fuzz
     * surfaced that "count the valid ones" was WRONG: a malformed cell among valid ones suppresses, not skips;
     * IF51). Empties are simply not counted. Linear: O(rows) per star, O(instantiated cells) per group.
     */
    private fun filledFieldsValue(args: List<Expr>): Val {
        var count = 0
        for (arg in args) when (arg) {
            is Expr.Star -> {
                if (arg.having != null) {
                    // The count HONORS a Having filter (IF94 — the kernel's `anzahlAngegebenerFelder` rides the
                    // same filtered iterator as every field-list aggregate; ignoring the filter over-counted).
                    // The filter drops rows FIRST — a malformed cell in a dropped row never suppresses (kernel-
                    // pinned) — then a KEPT row's malformed cell makes the count non-evaluable (poison in
                    // compute). Left index-unaware like the other Having paths (IF86's no-untested-logic call).
                    val group = arg.path.substringBeforeLast('/')
                    for (rc in ctx.rowContexts(group, arg.bindDepth)) {
                        if (!havingInterp(rc).fires(arg.having)) continue
                        if (!rc.filled(arg.path)) continue
                        if (rc.field(arg.path) is Val.Unknown) {
                            if (computeMode) throw PoisonedReadException(arg.path)
                            return Val.Unknown
                        }
                        count++
                    }
                } else {
                    val cells = ctx.star(arg.path, arg.bindDepth)   // filled cells in row order (empties dropped)
                    if (cells.any { it is Val.Unknown }) {          // a malformed cell → non-evaluable
                        if (computeMode) throw PoisonedReadException(arg.path)   // …and a POISON on the compute path (IF85)
                        return Val.Unknown
                    }
                    // An index field's not-check-relevant cell (mandatory-empty / duplicated value) makes the count
                    // non-evaluable too — `anzahlAngegebenerFelder` returns NICHT_PRUEF_REL on the first UNKNOWN cell.
                    if (indexOperandNonEvaluable(ctx, arg.path, arg.bindDepth)) return Val.Unknown
                    count += cells.size
                }
            }
            else -> {
                val path = pathOf(arg)
                if (ctx.declaredKind(path) == null) {              // GROUP operand — its descendant fields
                    val fs = ctx.fillScope(path, isGroup = true)
                    if (fs.hasUnknownInst) {                       // a malformed descendant → non-evaluable
                        if (computeMode) throw PoisonedReadException(path)
                        return Val.Unknown
                    }
                    count += fs.tInst
                } else {                                           // plain field
                    if (ctx.filled(path) && invalid(path)) return Val.Unknown   // malformed → non-evaluable
                    if (ctx.filled(path)) count++
                }
            }
        }
        return Val.Num(dec(count))
    }

    /**
     * Tally a STARRED field-list quantifier operand — `AtLeastOneFieldFilled(Group* / Field Having …)`: iterate
     * the star's group rows, keep those the optional `Having` filter accepts, and count each kept row's cell as
     * TRUE (present + check-relevant) / FALSE (empty) / neither (malformed) — the same 3-state as [quantTally].
     * **Linear in the group's rows** (O(1) per row: the filter's `$` refs resolve against the fixed enclosing
     * row via [havingInterp], and `filled`/`rowContexts` are O(1)/O(rows)). Returns `(t, f, n = kept rows)`.
     */
    private fun starQuantTally(star: Expr.Star): Triple<Int, Int, Int> {
        val group = star.path.substringBeforeLast('/')
        // Fast path: an equality self-correlation Having (`[X] == [$X]` ± a CurrentRepetition self-exclusion) is
        // a HASH JOIN — O(1) per outer row against buckets the Document builds + caches once (O(N)). This is the
        // kernel's own linear strategy; the O(N²) scan below is the fallback for an arbitrary Having. A
        // MULTI-star operand (bindDepth above the leaf group's parent — IF94) takes the naive scan: the bucket
        // cache is keyed on the single-star scope, and the correct-but-slower loop needs no new join logic.
        if (!isMultiStar(star)) {
            star.having?.let { parseCorrelationJoin(it, group) }?.let { return hashJoinTally(star, group, it) }
        }
        return naiveStarQuantTally(star, group)
    }

    /** Whether [star] binds ABOVE its leaf group's parent — a multi-star operand (`A* / B* / F`, IF94). */
    private fun isMultiStar(star: Expr.Star): Boolean =
        star.bindDepth >= 0 && star.bindDepth < star.path.trim('/').split('/').size - 2

    /**
     * The O(N²) inner-scan `(t, f, n)` tally of a starred filter quantifier — the general fallback for an
     * arbitrary `Having`, and the **reference** the hash-join fast path must equal on a recognized correlation
     * `Having` (WI-4 property 6, [correlationTallyBothWays]). Iterates the group's rows, keeps those the filter
     * accepts, and 3-state-counts each kept row's star cell (present / empty / not-check-relevant).
     */
    private fun naiveStarQuantTally(star: Expr.Star, group: String): Triple<Int, Int, Int> {
        var t = 0
        var f = 0
        var n = 0
        for (rc in ctx.rowContexts(group, star.bindDepth)) {
            if (star.having != null && !havingInterp(rc).fires(star.having)) continue
            n++
            when {
                rc.presenceUnknown(star.path) -> {}
                !rc.filled(star.path) -> f++
                // filled-but-invalid, ROW-correct: the KEPT row's own cell (rc.field → resolveValue → Unknown,
                // IF84) — the old `invalid(star.path)` resolved at the ENCLOSING rule row's reps, so a per-row
                // rule whose OWN cell was invalid poisoned the tally of a valid KEPT row (IF105); a filter-
                // DROPPED row's invalid cell never suppresses (the kernel filter drops first, IF94).
                rc.field(star.path) is Val.Unknown -> {}
                else -> t++
            }
        }
        return Triple(t, f, n)
    }

    // ---- WI-4 property seams (proposal §8; internal — consumed only by the property pack in commonTest) -------

    /**
     * **Property 1 (lazy ≡ eager polarity).** Whether the production lazy-polarity walk [evalTypedLazy] agrees
     * with the full eager walk [evalTyped] for [cond] in this context: the same truth, and — when the rule FIRES
     * (truth TRUE) — the same [MsgType] polarity. That is exactly the invariant [firedType] relies on (it walks
     * lazily and reads the type only on a TRUE node). Compared only when the row is [gated] (else [firedType]
     * would not walk at all, so there is nothing to compare). The property sweeps this over sampled (model, doc).
     */
    internal fun lazyEagerConsistent(cond: Cond): Boolean {
        if (!gated(cond)) return true
        val eager = evalTyped(cond)
        val lazy = evalTypedLazy(cond)
        return eager.truth == lazy.truth && (eager.truth != Tri.TRUE || eager.type == lazy.type)
    }

    /**
     * **Property 6 (hash-join ≡ nested-loop).** For a starred filter quantifier [star] whose `Having` is a
     * recognized `$`-equality self-correlation, the `(t, f, n)` tally computed by the O(1) hash-join fast path
     * ([hashJoinTally]) paired with the O(N²) naive inner scan ([naiveStarQuantTally]) — the property asserts the
     * two are equal on every sampled input. Returns null when [star] carries no recognized correlation `Having`
     * (no fast path exists to compare against the scan).
     */
    internal fun correlationTallyBothWays(star: Expr.Star): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>>? {
        val group = star.path.substringBeforeLast('/')
        val join = star.having?.let { parseCorrelationJoin(it, group) } ?: return null
        return hashJoinTally(star, group, join) to naiveStarQuantTally(star, group)
    }

    /** A recognized `$` equality-correlation `Having`: aligned inner/outer field paths of its equality conjuncts
     *  (`[innerFields[i]] == [$outerFields[i]]`) + whether a `CurrentRepetition(G) != CurrentRepetition($G)`
     *  self-exclusion is present (which drops the outer row from its own bucket). */
    private class JoinSpec(val innerFields: List<String>, val outerFields: List<String>, val selfExcluded: Boolean)

    /** Recognize a `Having` as a PURE equality self-correlation → the hash-join fast path; null otherwise (→ the
     *  O(N²) fallback). Must be a conjunction of: 0-or-1 self-exclusion `CurrentRepetition(G) !=
     *  CurrentRepetition($G)` and ≥1 equality `[A] == [$B]` (a plain field == its `$` outer counterpart), and
     *  NOTHING else — so its exact semantics reduce to a value join (any other shape keeps the general scan). */
    private fun parseCorrelationJoin(having: Cond, group: String): JoinSpec? {
        val inner = mutableListOf<String>()
        val outer = mutableListOf<String>()
        var selfExcluded = false
        for (c in flattenAnd(having)) {
            if (isSelfExclusion(c, group)) { selfExcluded = true; continue }
            val pair = asEqualityJoin(c) ?: return null
            inner.add(pair.first); outer.add(pair.second)
        }
        return if (inner.isEmpty()) null else JoinSpec(inner, outer, selfExcluded)
    }

    private fun flattenAnd(c: Cond): List<Cond> =
        if (c is Cond.And) flattenAnd(c.left) + flattenAnd(c.right) else listOf(c)

    /** `(groupPath, isOuter)` if [e] is `CurrentRepetition(<groupRef>)`, else null. */
    private fun currentRepOf(e: Expr): Pair<String, Boolean>? {
        if (e !is Expr.Func || FunctionOp.byName(e.name) != FunctionOp.CURRENT_REPETITION) return null
        val g = e.args.singleOrNull() as? Expr.Field ?: return null
        return g.path to g.outer
    }

    /** Whether [c] is `CurrentRepetition(G) != CurrentRepetition($G)` over the star's [group] (either order). */
    private fun isSelfExclusion(c: Cond, group: String): Boolean {
        if (c !is Cond.Cmp || c.op != CmpOp.NE) return false
        val l = currentRepOf(c.left) ?: return false
        val r = currentRepOf(c.right) ?: return false
        return l.first == group && r.first == group && l.second != r.second
    }

    /** `(innerFieldPath, outerFieldPath)` if [c] is `[A] == [$B]` — one plain field vs one `$` outer field
     *  (either operand order); null otherwise. */
    private fun asEqualityJoin(c: Cond): Pair<String, String>? {
        if (c !is Cond.Cmp || c.op != CmpOp.EQ) return null
        val l = c.left as? Expr.Field ?: return null
        val r = c.right as? Expr.Field ?: return null
        return when {
            !l.outer && r.outer -> l.path to r.path
            l.outer && !r.outer -> r.path to l.path
            else -> null
        }
    }

    /**
     * The `(t, f, n)` of a filtered quantifier whose `Having` is an equality self-correlation — computed by a
     * HASH JOIN (O(1) per outer row) instead of the O(N²) inner scan: look up the outer row's bucket by its
     * OUTER-field values, then remove the outer row's own contribution when a self-exclusion is present (it sits
     * in its own bucket iff its inner-field values equal its outer-field values). Returns exactly what the scan
     * would for well-formed data (the differential + fuzz lock it). Buckets are built + cached by the Document.
     */
    private fun hashJoinTally(star: Expr.Star, group: String, join: JoinSpec): Triple<Int, Int, Int> {
        val buckets = ctx.correlationBuckets(group, join.innerFields, star.path)
        val outerKey = join.outerFields.map { p -> if (invalid(p)) Val.Unknown else ctx.field(p) }
        if (outerKey.any { it is Val.Unknown }) return Triple(0, 0, 0)   // an Unknown outer value never matches
        val tfn = buckets[outerKey] ?: return Triple(0, 0, 0)
        var t = tfn[0]
        var f = tfn[1]
        var n = tfn[2]
        if (join.selfExcluded && ctx.evaluated) {
            val outerInnerKey = join.innerFields.map { p -> if (invalid(p)) Val.Unknown else ctx.field(p) }
            if (outerInnerKey == outerKey) {           // the outer row is in its own bucket → drop it
                n--
                when {
                    ctx.presenceUnknown(star.path) -> {}
                    !ctx.filled(star.path) -> f--
                    // a filled-but-INVALID own cell contributed to NEITHER bucket (IF105) — nothing to undo
                    ctx.field(star.path) is Val.Unknown -> {}
                    else -> t--
                }
            }
        }
        return Triple(t, f, n)
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
     * `DateRangesOverlap(op0, op1, …)` — **any-pair among ALL** operands' kept, filled range cells (kernel
     * `datumsBereicheUeberlappend`): every operand's cells feed ONE growing set in operand order, and the
     * predicate fires on the FIRST range overlapping any already-seen member. There is NO distinguished first
     * operand — so the SAME row's cell arriving via a scalar ref AND via the star is two members whose identical
     * range "overlaps" (the self-pair), and a list-internal pair fires even when a scalar operand is disjoint.
     * Empty/malformed cells are SKIPPED (no unknown-suppression); an INVALID range never overlaps ([rangesOverlap]).
     *
     * Polarity is **filter-derived** (not fill-derived): OMISSION iff a filled cell of a FILTER-bearing operand
     * (a rep-list arg carrying a `Having`) was processed at/before the fire — the `containsFilter` flag evolves
     * in operand order, matching the kernel exactly. Returns the (truth, polarity) [Res] so [evalPred] and
     * [predType] share ONE scan (KERNEL-FINDINGS §6, IF72).
     */
    private fun overlapAny(args: List<Expr>): Res {
        val seen = mutableListOf<Val.Range>()
        var containsFilter = false
        for (arg in args) {
            val filtered = arg is Expr.Star && arg.having != null
            for (cell in overlapCells(arg)) {
                if (filtered) containsFilter = true
                if (seen.any { rangesOverlap(cell, it) })
                    return Res.of(Tri.TRUE, if (containsFilter) MsgType.OMISSION else MsgType.VALUE)
                seen.add(cell)
            }
        }
        return Res.of(Tri.FALSE, MsgType.VALUE)
    }

    /**
     * `AtLeastOneDateRangeOverlaps(scalar In list…)` — genuinely scalar-vs-list (kernel
     * `mindestensEinDatumsBereichUeberlappendFL`): the single unstarred [args]`[0]` range is compared against
     * each list cell; it is NOT a member of the set, so there is no self-pair. A not-filled/malformed scalar →
     * FALSE (no fire — NOT unknown-suppression). Polarity: OMISSION iff the MATCHED container (the list operand
     * whose cell overlapped) carries a `Having`, VALUE otherwise (KERNEL-FINDINGS §6, IF72).
     */
    private fun atLeastOneOverlap(args: List<Expr>): Res {
        val scalar = evalExpr(args[0]) as? Val.Range ?: return Res.of(Tri.FALSE, MsgType.VALUE)
        for (i in 1 until args.size) {
            val arg = args[i]
            val filtered = arg is Expr.Star && arg.having != null
            for (cell in overlapCells(arg)) {
                if (rangesOverlap(scalar, cell))
                    return Res.of(Tri.TRUE, if (filtered) MsgType.OMISSION else MsgType.VALUE)
            }
        }
        return Res.of(Tri.FALSE, MsgType.VALUE)
    }

    /**
     * The kept, FILLED range cells of an overlap operand, in row order — empties/malformed dropped (the kernel's
     * `isWertAngegeben != TRUE → continue`). A scalar operand contributes its one range (or nothing); a starred
     * operand expands over its group's rows, and a `Having` KEEPS a row iff the filter is TRUE (the kernel
     * compiles the filter to a BOOLEAN — a runtime-UNKNOWN comparison, e.g. a malformed operand, is not-TRUE →
     * the row is dropped). A `$`-correlation term has no enclosing iteration for a plain predicate's arg, so the
     * codegen cannot compile it and the whole term is trivially TRUE ([evalCmp]/[evalPred] short-circuit under
     * [outerUnbound]) — that is why a `!= $RuleGroup` / `== $RuleGroup` self-"exclusion" keeps ALL rows, while a
     * malformed real operand drops them. Distinct from the quantifier-`Having` path ([starOperandVals], `$` bound
     * to the enclosing row). (KERNEL-FINDINGS §6, IF72.)
     */
    private fun overlapCells(arg: Expr): List<Val.Range> = when (arg) {
        is Expr.Star -> filteredStarCells(arg).filterIsInstance<Val.Range>()
        else -> listOfNotNull(evalExpr(arg) as? Val.Range)
    }

    /**
     * The FILLED cells of a starred rep-list operand of a plain boolean predicate (`DateRangesOverlap`,
     * `FieldValuesNotUnique`), honoring an optional `Having` — the SHARED filter path. Without a `Having`:
     * [EvalContext.star] (empties dropped, a malformed cell → [Val.Unknown]). With one: keep a row iff the filter
     * FIRES (TRUE) — the kernel compiles the filter to a boolean, so a malformed operand (runtime-UNKNOWN) drops
     * the row, and a `$`-correlation term (no enclosing iteration here) is trivially TRUE ([evalCmp] short-circuit
     * via [overlapFilterInterp]); a kept row's empty cell is dropped, a kept malformed cell surfaces as
     * [Val.Unknown]. Distinct from the quantifier-`Having` path ([starOperandVals], `$` bound). KERNEL-FINDINGS §6.
     */
    private fun filteredStarCells(star: Expr.Star): List<Val> {
        val c = ctxOf(star)
        if (star.having == null) return c.star(star.path, star.bindDepth)
        return c.rowContexts(star.path.substringBeforeLast('/'), star.bindDepth)
            .filter { rc -> overlapFilterInterp(rc).fires(star.having) }
            .mapNotNull { rc -> if (rc.filled(star.path)) rc.field(star.path) else null }
    }

    /** Closed-interval overlap of two ISO date ranges (lexicographic compare = chronological on ISO). An INVALID
     *  range (start > end) never overlaps anything (kernel `DatumBereich.ueberlappt` returns false for either
     *  side invalid), so both sides must be well-formed first. Overlap ⇔ `end1 ≥ start2 ∧ end2 ≥ start1` (closed —
     *  touching ranges overlap; KERNEL-FINDINGS §6). */
    private fun rangesOverlap(x: Val.Range, y: Val.Range): Boolean =
        x.start <= x.end && y.start <= y.end && x.end >= y.start && y.end >= x.start

    /** `value PatternMatched/PatternViolated "regex"` — anchored full-value match; empty → not evaluated. */
    private fun evalPattern(p: Cond.Pattern): Tri {
        val v = evalExpr(p.value)
        if (v is Val.Unknown) return Tri.UNKNOWN
        val text = (v as? Val.Txt)?.v ?: error("pattern comparison needs a string value, got $v")
        val matched = Regex((p.pattern as Expr.StrLit).v).matches(text)
        return Tri.of(if (p.expectMatch) matched else !matched)
    }

    private fun evalCmp(c: Cond.Cmp): Tri {
        // Overlap-predicate Having: a comparison touching an unbound `$` cannot be compiled (no enclosing
        // iteration) → the kernel treats the term as trivially TRUE (KERNEL-FINDINGS §6). This is why a
        // `$`-self-"exclusion" keeps every row, unlike a malformed real operand (which drops, evaluated below).
        if (outerUnbound && (hasUnboundOuter(c.left) || hasUnboundOuter(c.right))) return Tri.TRUE
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
            CmpOp.LT -> Tri.of(ValCompare.language(l, r) < 0)
            CmpOp.LE -> Tri.of(ValCompare.language(l, r) <= 0)
            CmpOp.GT -> Tri.of(ValCompare.language(l, r) > 0)
            CmpOp.GE -> Tri.of(ValCompare.language(l, r) >= 0)
        }
    }

    private fun evalExpr(e: Expr): Val = when (e) {
        is Expr.NumLit -> e.asVal
        is Expr.StrLit -> e.asVal
        is Expr.BoolLit -> e.asVal
        // COMPUTE mode: a value read of a formally-invalid cell poisons (IF85) — row-correct via the resolving
        // context's own cellState (a `Having` filter row reads ITS row's cell, where the parent-row [invalid]
        // gate cannot judge it).
        is Expr.Field -> when {
            outerUnknown(e) -> Val.Unknown
            computeMode && ctxOf(e).cellState(e.path) == CellState.INVALID -> throw PoisonedReadException(e.path)
            invalid(e.path) -> Val.Unknown
            else -> ctxOf(e).field(e.path)
        }
        is Expr.Star -> throw UnsupportedConstructException("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
        is Expr.Func -> evalFunc(e)
        is Expr.Arith -> evalArith(e)
        is Expr.Const -> evalConst(e.name)
        is Expr.SemanticIndex -> evalSemanticIndex(e)
        is Expr.EnumCategory -> if (outerUnknown(e)) Val.Unknown else ctxOf(e).enumCategory(e.path, e.category)
    }

    /** `[field For "value"]` — the field's value in the row whose index field equals the value; an absent row
     *  or empty cell reads as the kind's EMPTY value (a number → 0, fillable), matching a missing field ref.
     *
     *  COMPUTE mode differs on ONE kernel-pinned point (IF107): an INVALID index COLUMN — any empty,
     *  duplicated, or malformed key cell — POISONS the reading computation even when a clean row matches (the
     *  IndexFieldCache marking, the semantic-index analog of IF96's parallel-iteration rule). A clean column's
     *  no-match and a matched-but-empty cell both read empty-as-0 exactly like the validation read
     *  (kernel-pinned — the corpus minimal's CLEARED came from its invalid 16-digit cell, not the no-match). */
    private fun evalSemanticIndex(e: Expr.SemanticIndex): Val {
        val kind = ctx.declaredKind(e.path) ?: return Val.Unknown
        if (computeMode) {
            val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
                "SemanticIndex needs an index field on the group of ${e.path}")
            val cells = ctx.starCells(idx)
            val dups = duplicatedValues(cells)
            if (cells.any { (_, v) -> v == null || v is Val.Unknown || v in dups }) throw PoisonedReadException(idx)
        }
        return semanticIndexCell(e) ?: emptyValue(kind)
    }

    /** The matched CELL of `[F For key]`, or null when nothing matches — an empty/absent key field, no row
     *  carrying the key, or a matched row whose cell is empty. A matched-but-malformed cell is [Val.Unknown]. */
    private fun semanticIndexCell(e: Expr.SemanticIndex): Val? {
        val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException("SemanticIndex needs an index field on the group of ${e.path}")
        val key = resolveIndexKey(e) ?: return null
        return ctx.indexedCell(e.path, idx, key)
    }

    /**
     * The 3VL presence of `[F For key]` — the kernel resolves the indexed row and answers `isWertAngegeben` on
     * the ONE matched cell: matched + check-relevant filled → TRUE; an ABSENT row, an empty matched cell, or an
     * empty/no-match key → FALSE (an absent row is judged like an empty cell, NOT skipped — kernel
     * `ValidationCache.resolveSemanticIndex` → `isEntityFilled`); a malformed matched cell, or a formally
     * invalid KEY field, → UNKNOWN (not check-relevant). `SemanticIndexPresenceDiffTest`.
     */
    private fun semanticIndexPresence(e: Expr.SemanticIndex): Tri {
        e.indexFieldPath?.let { if (invalid(it)) return Tri.UNKNOWN }
        val cell = semanticIndexCell(e) ?: return Tri.FALSE
        return if (cell is Val.Unknown) Tri.UNKNOWN else Tri.TRUE
    }

    /** The 3VL presence of a field-valued predicate/quantifier operand — a plain field ref ([filledTri]) or a
     *  semantic index ([semanticIndexPresence]). Any other operand shape is not a presence operand. */
    private fun presenceTri(e: Expr): Tri = when {
        outerUnknown(e) -> Tri.UNKNOWN   // an unbound `$` presence operand (overlap-predicate Having) → UNKNOWN
        e is Expr.SemanticIndex -> semanticIndexPresence(e)
        else -> filledTri(ctxOf(e), pathOf(e))
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
     *  a date-producing function, or a min/max whose operands are dates ([dateAggregate] — the kernel types
     *  `Min`/`MinValue` polymorphically by their operands, so a literal beside a date min is a date literal). */
    private fun producesDate(e: Expr): Boolean = when (e) {
        is Expr.Field -> ctx.declaredKind(e.path) == Kind.DATE
        is Expr.Const -> ConstantOp.byName(e.name).let { it == ConstantOp.TODAY || it == ConstantOp.BASE_YEAR }
        is Expr.Func -> FunctionOp.byName(e.name).let { op ->
            op in DATE_PRODUCING_FUNCS || (op in MIN_MAX_FUNCS && dateAggregate(e))
        }
        is Expr.SemanticIndex -> ctx.declaredKind(e.path) == Kind.DATE
        else -> false
    }

    /** Canonicalize a German date literal to ISO `yyyy-MM-dd`; any other value (already-ISO, a non-date string)
     *  is unchanged — so it only ever rewrites a literal that genuinely parses as a date. Two literal shapes: the
     *  full `dd.MM.yyyy`, and the **omitted-year** `dd.MM.` (a reference-year feature — the kernel injects the
     *  model's `baseYear`, so `"13.07."` means `13.07.<baseYear>`; a model with NO base year rejects it at
     *  validation, `MVK_INVALID_COMPARE_TO_DATE`, so we only ever see it when `baseYear` is set — IF52). */
    private fun coerceDate(v: Val): Val {
        if (v !is Val.Txt) return v
        parseDateToIso(v.v, "dd.MM.yyyy")?.let { return Val.Txt(it) }
        if (baseYear != null && OMITTED_YEAR.matches(v.v)) {
            parseDateToIso(v.v + baseYear.toString().padStart(4, '0'), "dd.MM.yyyy")?.let { return Val.Txt(it) }
        }
        return v
    }

    /** `BaseYear` as the base-year-START date (ISO `yyyy-01-01`). */
    private fun baseYearDate(): Val = Val.Txt("${baseYearOrThrow().toString().padStart(4, '0')}-01-01")

    /** `BaseYear` as the year NUMBER. */
    private fun baseYearNumber(): Val = Val.Num(dec(baseYearOrThrow()))

    private fun baseYearOrThrow(): Int = baseYear
        ?: throw UnsupportedConstructException("BaseYear needs the model's base year (modelInfo.baseYear)")

    /** Binary arithmetic; an empty numeric operand already arrives as 0, Unknown propagates. `+` is overloaded:
     *  STRING CONCATENATION when an operand is textual, else numeric addition (IF48). */
    private fun evalArith(a: Expr.Arith): Val {
        val l = evalExpr(a.left)
        val r = evalExpr(a.right)
        // `+` = string concatenation when an operand is a string VALUE, or STATICALLY a string-typed operand (so
        // two EMPTY strings still concat to "", where the runtime `Val.Txt` check alone would see only Unknowns).
        // An empty/absent string operand concatenates as "" (kernel-probed: `"" + " " + "Smith"` = " Smith"), NOT
        // as non-evaluable. (The kernel rejects a mixed number+string `+` at compile, so a concat is all-strings.)
        if (a.op == ArithOp.ADD && (l is Val.Txt || r is Val.Txt || producesString(a.left) || producesString(a.right))) {
            // A FILLED-but-formally-invalid concat operand makes the WHOLE concatenation non-evaluable → the
            // computed field CLEARS (IF81, was IG51 — differential-confirmed: `[S] + "x"` over a too-long S
            // yields CLEARED). Distinct from an EMPTY operand, which concatenates as "" — even a REQUIRED-empty
            // one (kernel-probed: required-empty stays "", not cleared). `invalid` is the operand-invalidity gate.
            if (concatOperandInvalid(a.left) || concatOperandInvalid(a.right)) return Val.Unknown
            return Val.Txt(concatText(l) + concatText(r))
        }
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

    /** A concat operand as text: a string value as-is; an empty/absent/non-evaluable string operand as "" (the
     *  kernel treats an empty string field in a concat as ""); a number defensively via its display form. */
    private fun concatText(v: Val): String = when (v) {
        is Val.Txt -> v.v
        is Val.Num -> v.v.display()
        else -> ""
    }

    /** Whether a concat operand (possibly a nested `+` concatenation) contains a FILLED-but-formally-invalid
     *  string field — the kernel makes the whole concatenation non-evaluable (the computed field CLEARS),
     *  distinct from an EMPTY operand which concatenates as "" (IF81, was IG51). `filled &&` excludes a
     *  required-empty field (empty-as-"", not cleared — kernel-probed); a constant/function operand is never
     *  a field-invalidity site here. */
    private fun concatOperandInvalid(e: Expr): Boolean = when (e) {
        is Expr.Field -> ctxOf(e).filled(e.path) && invalid(e.path)
        is Expr.Arith -> e.op == ArithOp.ADD && (concatOperandInvalid(e.left) || concatOperandInvalid(e.right))
        else -> false
    }

    /** Whether [e] statically yields a STRING — the signal that a `+` is concatenation (so two EMPTY string
     *  operands still concat to "", where the runtime `Val.Txt` check alone would see only Unknowns). */
    private fun producesString(e: Expr): Boolean = when (e) {
        is Expr.StrLit -> true
        is Expr.Field -> ctx.declaredKind(e.path) == Kind.STRING
        is Expr.Arith -> e.op == ArithOp.ADD && (producesString(e.left) || producesString(e.right))
        else -> false
    }

    private fun evalFunc(f: Expr.Func): Val {
        val op = FunctionOp.byName(f.name)
        // Partial validation only: an ALL-ROWS starred aggregate (Sum/Max/Min/distinct/sum-of-products) is
        // UNKNOWN unless the relevant set WILDCARDS its starred level — concrete per-row enumeration, even of all
        // rows, is not enough (kernel-probed, IF42). `FirstFilled` is EXEMPT here (handled order-aware in its
        // branch). Full validation: starRelevant is null → skipped. O(relevant-set), no per-cell scan.
        if (starRelevant != null && op != FunctionOp.FIRST_FILLED_VALUE) {
            f.args.forEach { arg ->
                val sp = (arg as? Expr.Star)?.path
                if (sp != null && !starRelevant.invoke(sp)) return Val.Unknown
            }
        }
        return when (op) {
        // Value aggregates — the FOLDS are pure runtime semantics ([Aggregates], IG5→IF10); the strategy only
        // RESOLVES operands (operand-list `map { evalExpr }` vs the starred `starVals`) and gates Unknown
        // ([combine]). The same fold serves both forms (`Min`/`MinValue` share [Aggregates.min]). Min/max over
        // DATE-like operands ride the kernel's DateCombiner semantics ([dateAggregate]): the empty identity is
        // no-value (Unknown), not the number combiner's 0 (DateAggregateDiffTest).
        // operand-list forms: every operand counts (an empty number arrives as the substituted 0; a DATE-like
        // empty operand is SKIPPED — the kernel's `minDatum` combine keeps the date-bearing side)
        FunctionOp.MIN -> combine(minMaxOperandVals(f)) { Aggregates.min(it, minMaxEmpty(f)) }
        FunctionOp.MAX -> combine(minMaxOperandVals(f)) { Aggregates.max(it, minMaxEmpty(f)) }
        // starred aggregates: empties are SKIPPED (Sum substitutes 0 = same result; Max/distinct ignore)
        FunctionOp.SUM -> combine(starVals(f), Aggregates::sum)
        FunctionOp.MAX_VALUE -> combine(starVals(f)) { Aggregates.max(it, minMaxEmpty(f)) }
        FunctionOp.MIN_VALUE -> combine(starVals(f)) { Aggregates.min(it, minMaxEmpty(f)) }
        FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> combine(starVals(f), Aggregates::numberOfDifferentValues)
        // DifferenceInDays(a, b) = b − a in whole days (sign = second − first), as a number. An EMPTY date operand
        // reads as the fillable 0 (either empty → 0, kernel getDatumsDiff) and FIRES; a MALFORMED operand → Unknown.
        FunctionOp.DIFFERENCE_IN_DAYS -> dateDiffEmpty(f.args[0], f.args[1]) ?: run {
            val a = epochOf(f.args[0])
            val b = epochOf(f.args[1])
            if (a == null || b == null) Val.Unknown else Val.Num(dec((b - a).toString()))
        }
        // Count-of-filled functions (used in a comparison, e.g. NumberOfFilledFields(a, b, c) < 2).
        FunctionOp.NUMBER_OF_FILLED_FIELDS -> filledFieldsValue(f.args)
        FunctionOp.NUMBER_OF_FILLED_GROUPS -> Val.Num(dec(filledGroupsCount(f.args)))
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
        FunctionOp.LENGTH -> if (emptyCheckRelevant(f.args[0])) Val.Num(DEC_ZERO)   // empty string → 0 (laenge, grow-only)
            else (evalExpr(f.args[0]) as? Val.Txt)?.let { Val.Num(dec(it.v.length)) } ?: Val.Unknown
        // Rounding — `Round*([expr], n)` to n decimal places; the `*Value(field)` siblings to an integer (0).
        FunctionOp.ROUND_DOWN -> roundExpr(f.args, RoundMode.FLOOR)
        FunctionOp.ROUND_UP -> roundExpr(f.args, RoundMode.CEILING)
        FunctionOp.ROUND_ACCOUNTING -> roundExpr(f.args, RoundMode.HALF_UP)
        FunctionOp.ROUND_DOWN_VALUE -> roundExpr(f.args, RoundMode.FLOOR)
        FunctionOp.ROUND_UP_VALUE -> roundExpr(f.args, RoundMode.CEILING)
        FunctionOp.ROUND_ACCOUNTING_VALUE -> roundExpr(f.args, RoundMode.HALF_UP)
        // FirstFilledValue: the first FILLED value in row order (empties already dropped by `star`). NOT
        // `combine` — a later malformed element is invisible; but if the first filled one is malformed it
        // arrives as Unknown, so the comparison is Unknown and the rule is suppressed. No filled row → Unknown.
        FunctionOp.FIRST_FILLED_VALUE -> firstFilledValue(f)
        // NumberOfValueInFields(value In f1, f2, …): count the FILLED fields equal to the constant.
        FunctionOp.NUMBER_OF_VALUE_IN_FIELDS -> numberOfValueInFields(f.args)
        // RangeAsString/RangeAsNumber(field, s, e): a substring of a STRING field by 1-based inclusive range.
        FunctionOp.RANGE_AS_STRING -> rangeSubstring(f.args)?.let { Val.Txt(it) } ?: Val.Unknown
        FunctionOp.RANGE_AS_NUMBER -> rangeSubstring(f.args)?.let {
            Val.Num(if (DIGITS.matches(it)) dec(it) else DEC_ZERO)   // kernel: only `\d+` parses, else 0
        } ?: Val.Unknown
        // FieldValueAsString(ref): the RAW stored value as a string (empty → ""). Coercion to string; the ref
        // may also be a semantic index (the kernel resolves the one matched cell, then feldWertAlsString).
        FunctionOp.FIELD_VALUE_AS_STRING -> fieldValueAsString(f.args[0])
        // FieldValueAsNumber(string/numeric-enum field): the value parsed to a number; empty → 0 (kernel feldWertAlsZahl).
        FunctionOp.FIELD_VALUE_AS_NUMBER -> fieldValueAsNumber(pathOf(f.args[0]))
        // SumOfProducts(a*, b*): Σ aᵢ·bᵢ paired row-by-row (empty → 0); any malformed element → suppressed.
        FunctionOp.SUM_OF_PRODUCTS -> sumOfProducts(f.args)
        // CurrentRepetition(group): the current row's 1-based index at that group's level (the last reps part).
        // An UNBOUND `$` group (an overlap-predicate Having, no enclosing iteration) → UNKNOWN (KERNEL-FINDINGS §6).
        FunctionOp.CURRENT_REPETITION ->
            if (outerUnknown(f.args[0])) Val.Unknown else Val.Num(dec(ctxOf(f.args[0]).repsOf(pathOf(f.args[0])).last()))
        null -> throw UnsupportedConstructException("unsupported function ${f.name}")
        }
    }

    /** `Round*([expr][, n])` / `Round*Value(field[, n])` — round the numeric first operand with [mode] to the
     *  OPTIONAL decimal-places literal: the kernel grammar makes the digits optional on BOTH forms
     *  (`rundenOperOp`/`rundenOp` — `(',' ZiffernFolge)?`), and an absent digits argument is the integer round
     *  (places 0; kernel-pinned, IF102 — `roundExpr` crashed on the legal 1-arg form and the field form
     *  hardcoded 0 even with a digits argument present). A non-numeric/Unknown value or a non-whole places
     *  operand → Unknown. */
    private fun roundExpr(args: List<Expr>, mode: RoundMode): Val {
        val v = (evalExpr(args[0]) as? Val.Num)?.v ?: return Val.Unknown
        val places = if (args.size < 2) 0
            else (evalExpr(args[1]) as? Val.Num)?.v?.display()?.toIntOrNull() ?: return Val.Unknown
        return Val.Num(v.roundTo(places, mode))
    }

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
                // filled row values (empties dropped) — an optional `Having` filters the rows (kernel-probed: FVNU
                // ACCEPTS + EVALUATES a filter, the overlap-predicate keep-law; unlike SumOfProducts, which the
                // kernel authoring-rejects). A malformed kept cell → non-evaluable (suppressed).
                val rows = filteredStarCells(a)
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
        // O(n) duplicate detection via canonical keys. The starred operand collects ONE value per row, so the
        // old O(n²) pairwise `eq` scan went quadratic on a large repetition list (confirmed super-linear; the
        // kernel memoizes this via its RepetitionNotUniqueErrorCache). The key matches [eq]: numbers by their
        // scale-19 form (`compareScaled19` equality — scale-insensitive, exact past 19 digits), strings/bools by
        // themselves (the prefix prevents cross-type collision); a `Range` never `eq`-matches, so it's skipped.
        val seen = HashSet<String>(values.size * 2)
        for (v in values) {
            val k = when (v) {
                is Val.Num -> "N" + v.v.scale19().display()
                is Val.Txt -> "S" + v.v
                is Val.Bool -> "B" + v.v
                else -> continue   // Range / other: never eq → cannot form a duplicate
            }
            if (!seen.add(k)) return Tri.TRUE
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
        rejectHaving(args, "SumOfProducts")
        if (computeMode) {
            for (a in args) {
                val st = a as Expr.Star
                if (ctx.starScanStates(st.path, st.bindDepth).any { it == CellState.INVALID }) throw PoisonedReadException(st.path)
            }
        }
        // Resolve the two row-aligned star lists here (ctx-coupled); the Σ aᵢ·bᵢ fold is pure ([Aggregates], IF10).
        return Aggregates.sumOfProducts(
            (args[0] as Expr.Star).let { ctx.starAll(it.path, it.bindDepth) },
            (args[1] as Expr.Star).let { ctx.starAll(it.path, it.bindDepth) })
    }

    /**
     * Degrade LOUD (IF61) if any operand is a starred rep-list carrying a `Having` this consumer does not
     * evaluate — the kernel AUTHORING-REJECTS a filter on a `SumOfProducts` operand (`MVK_WILDCARD_AT_LOWEST_
     * LEVEL_REQUIRED`: the lowest repetition must be an unrestricted `*`), so on a valid model this never fires
     * (parity-by-construction); a filter that somehow reached the interpreter must be REPORTED (the rule is
     * reported unsupported), never SILENTLY ignored (which would compute the wrong answer). Contrast
     * `FieldValuesNotUnique`, which the kernel ACCEPTS + EVALUATES — it filters via [filteredStarCells], not here.
     */
    private fun rejectHaving(args: List<Expr>, op: String) {
        if (args.any { it is Expr.Star && it.having != null })
            throw UnsupportedConstructException("$op does not support a Having filter on its operand")
    }

    /**
     * `FieldValueAsString(ref)` — the RAW stored value as a string; an empty/absent value reads as `""` (which
     * a string comparison then suppresses — the kernel's `vergleicheSTRING` empty rule). A plain field reads its
     * display value (formally invalid → Unknown). A SEMANTIC INDEX reads the one matched cell: absent row /
     * empty cell / empty key → `""`; a malformed matched cell → Unknown. A matched DATE-like cell is
     * unsupported — its resolved [Val] is the canonical ISO form, not the raw stored text the kernel returns
     * (loud, not wrong). `SemanticIndexPresenceDiffTest`.
     */
    private fun fieldValueAsString(arg: Expr): Val = when (arg) {
        is Expr.SemanticIndex -> {
            if (dateLike(ctx.declaredKind(arg.path)))
                throw UnsupportedConstructException("FieldValueAsString over a date-valued semantic index is not supported")
            when (val cell = semanticIndexCell(arg)) {
                null -> Val.Txt("")
                is Val.Unknown -> Val.Unknown
                is Val.Num -> Val.Txt(cell.v.display())
                is Val.Txt -> cell
                is Val.Bool -> Val.Txt(cell.v.toString())
                is Val.Range -> throw UnsupportedConstructException("FieldValueAsString over a date-range semantic index is not supported")
            }
        }
        else -> pathOf(arg).let { if (invalid(it)) Val.Unknown else Val.Txt(ctx.displayValue(it) ?: "") }
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

    /**
     * An operand that reads as the kernel's fillable EMPTY value (`nichtAngegeben` & check-relevant) for the
     * value functions that coerce an unspecified operand to **0** and FIRE — `Length` (`BigDecimalUtils.laenge`),
     * the date/time extractors (`getDatumsTeil`) and the date diffs (`getDatumsDiff`), unlike a direct comparison
     * on an empty field (which is suppressed). A plain field ref that is not filled and not formally invalid;
     * non-field operands (constants, constructions) are never empty here. KERNEL-FINDINGS §6, IF73.
     */
    private fun emptyCheckRelevant(e: Expr): Boolean =
        e is Expr.Field && !ctxOf(e).filled(e.path) && !invalid(e.path)

    /** A field operand that is formally invalid (a malformed value, or a required-but-empty field): the empty-as-0
     *  value functions stay NON-evaluable on it (`nichtPruefungsrelevant` → `NICHT_PRUEF_REL_ZAHL`, the comparison
     *  is suppressed) — malformed BEATS empty (checked first in `getDatumsDiff`). */
    private fun malformedField(e: Expr): Boolean = e is Expr.Field && invalid(e.path)

    /** The empty/malformed short-circuit shared by the date diffs (`getDatumsDiff`): EITHER operand malformed →
     *  Unknown (suppress); else EITHER an empty check-relevant operand → the fillable **0** (fires; the fill is
     *  symmetric via `funcFill`'s leaf); null when both are real values (compute the actual difference). */
    private fun dateDiffEmpty(a: Expr, b: Expr): Val? = when {
        malformedField(a) || malformedField(b) -> Val.Unknown
        emptyCheckRelevant(a) || emptyCheckRelevant(b) -> Val.Num(DEC_ZERO)
        else -> null
    }

    /** `DifferenceIn{Months,Years}(a, b)` → a signed whole-period count; an empty operand reads as the fillable 0
     *  (either empty → 0, kernel `getDatumsDiff`); a malformed operand → Unknown (suppressed). */
    private fun dateDiff(args: List<Expr>, diff: (String, String) -> Int?): Val {
        dateDiffEmpty(args[0], args[1])?.let { return it }
        val a = (evalExpr(args[0]) as? Val.Txt)?.v ?: return Val.Unknown
        val b = (evalExpr(args[1]) as? Val.Txt)?.v ?: return Val.Unknown
        return diff(dateOnly(a), dateOnly(b))?.let { Val.Num(dec(it)) } ?: Val.Unknown
    }

    /** A number picked from a date operand's (year, month, day); an EMPTY date → the fillable **0** (kernel
     *  `getDatumsTeil` — `DEFAULT_VALUE`, symmetric fill), a MALFORMED date → Unknown (suppressed). A DATE_TIME
     *  operand extracts from its DATE part (IF95 — the kernel has no datetime format definition: the field rides
     *  `FormatDefinitionDatum` with a time-bearing format, so `feldWertAlsDatum` accepts it). */
    private fun dateComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
        if (emptyCheckRelevant(e)) return Val.Num(DEC_ZERO)
        val parts = (coerceDate(evalExpr(e)) as? Val.Txt)?.let { dateParts(dateOnly(it.v)) } ?: return Val.Unknown
        return Val.Num(dec(pick(parts.first, parts.second, parts.third)))
    }

    /** The DATE part of a canonical datetime (`2024-06-25T05:21:07` → `2024-06-25`); a plain date unchanged.
     *  The kernel's date consumers parse a DATE_TIME value with its full format and use the date part (IF95);
     *  a garbage value stays garbage — the downstream date parse still rejects it. */
    private fun dateOnly(v: String): String = v.substringBefore('T')

    /** A number picked from a TIME operand's (hours, minutes, seconds) `HH:mm:ss`; an EMPTY time → the fillable
     *  **0** (`getDatumsTeil`, symmetric fill), a MALFORMED time → Unknown (suppressed). A DATE_TIME operand
     *  extracts from its TIME part (IF95's time half — the kernel parses the datetime with the field's own
     *  time-bearing format, so `HoursFromTime(<datetime>)` is legal and reads the clock time). */
    private fun timeComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
        if (emptyCheckRelevant(e)) return Val.Num(DEC_ZERO)
        val m = (evalExpr(e) as? Val.Txt)?.let { TIME.matchEntire(it.v.substringAfter('T')) } ?: return Val.Unknown
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

    /** Classify a `Date(...)` construction's operands. Two arities: `Date(day, month, year)` and the 4-arg
     *  century form `Date(day, month, century, year)` whose full year is `century*100 + year` (kernel-probed,
     *  IF31). Precedence: any malformed part → MALFORMED; else any empty field part → HAS_EMPTY; else
     *  REAL/UNREAL_PRESENT by the calendar check. */
    private fun analyzeDate(parts: List<Expr>): DateAnalysis {
        if (parts.size != 3 && parts.size != 4) return DateAnalysis(DateStatus.MALFORMED, null)
        var hasEmpty = false
        val nums = IntArray(parts.size)
        for (i in parts.indices) {
            val e = parts[i]
            if (e is Expr.Field && !ctx.filled(e.path)) { hasEmpty = true; continue }
            nums[i] = valToInt(evalExpr(e)) ?: return DateAnalysis(DateStatus.MALFORMED, null)
        }
        if (hasEmpty) return DateAnalysis(DateStatus.HAS_EMPTY, null)
        val d = nums[0]; val m = nums[1]
        // 4-arg century form splits the year into century + 2-digit year: full year = century*100 + year.
        val y = if (parts.size == 3) nums[2] else nums[2] * 100 + nums[3]
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
     *  truncated toward zero; an EMPTY date-time operand reads as the fillable 0 (either empty → 0, kernel
     *  `getDatumsDiff` via `getCalenderDiff`) and FIRES; a MALFORMED operand → Unknown (suppressed). */
    private fun dateTimeDiff(args: List<Expr>, unitSeconds: Long): Val {
        dateDiffEmpty(args[0], args[1])?.let { return it }
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
    private fun epochOf(e: Expr): Long? = (coerceDate(evalExpr(e)) as? Val.Txt)?.let { epochDay(dateOnly(it.v)) }

    /** `FirstFilled(G* / f)` — the first FILLED cell's value in row order. In PARTIAL validation it is order-aware
     *  (IF42): a NON-relevant cell encountered BEFORE the first filled cell makes the result UNKNOWN (its
     *  filledness is unknown, so we cannot skip past it); the first relevant-FILLED cell's value wins. Full
     *  validation (or a non-star / Having operand): the plain first-filled value. */
    private fun firstFilledValue(f: Expr.Func): Val {
        val star = f.args.singleOrNull() as? Expr.Star
        // NO filled value in the selection → the kernel's per-kind `FirstValueCombiner` returns its OWN kind's
        // "not-given" value ([firstFilledEmpty]): a NUMBER gives the fillable 0 (`NICHT_ANGEGEBENE_ZAHL`, the
        // empty-as-0 its Sum/Min/Max siblings give — IF80), a DATE/DATE_TIME/TIME/DATE_RANGE/STRING gives a
        // NOT-GIVEN value → the comparison does not fire / a computation CLEARS (Val.Unknown; IF83, the IF58
        // sibling — returning Num(0) for a date/string crashed `ValCompare` against the real value). A malformed
        // FIRST value still arrives as Val.Unknown (rule suppressed) via [starVals].
        if (cellRelevant == null || star == null || star.having != null) return starVals(f).firstOrNull() ?: firstFilledEmpty(f)
        for ((reps, value) in ctx.starCells(star.path, star.bindDepth)) {
            if (!cellRelevant.invoke(star.path, reps)) return Val.Unknown   // a NON-relevant cell precedes → Unknown (partial)
            if (value != null) return value
        }
        return firstFilledEmpty(f)   // all cells relevant + empty → the kind's not-given value
    }

    /** The empty-selection identity of a `FirstFilledValue` by its operand kind (the kernel's per-kind
     *  `FirstValueCombiner`): NUMBER → the fillable `0`; every other kind → NOT-GIVEN ([Val.Unknown], the
     *  comparison does not fire / a computation clears). IF83. */
    private fun firstFilledEmpty(f: Expr.Func): Val =
        if (firstFilledKind(f) == Kind.NUMBER) Val.Num(DEC_ZERO) else Val.Unknown

    /** The declared kind of a `FirstFilledValue`'s operand (its star / field), or null when indeterminable. */
    private fun firstFilledKind(f: Expr.Func): Kind? = f.args.firstNotNullOfOrNull { a ->
        when (a) {
            is Expr.Star -> ctxOf(a).declaredKind(a.path)
            is Expr.Field -> ctxOf(a).declaredKind(a.path)
            else -> null
        }
    }

    /** DATE-like kinds ride the kernel's date machinery (`VkDate`) — min/max over them dispatch to the
     *  `DateCombiner` family, not the number combiner ([dateAggregate]). */
    private fun dateLike(k: Kind?): Boolean = k == Kind.DATE || k == Kind.TIME || k == Kind.DATE_TIME

    /**
     * Whether a min/max aggregate's operands are DATE-like — the kernel dispatches these statically to its
     * date combiners (`minWertDatum`/`maxWertDatum` for the star form, `minDatum`/`maxDatum` for the operand
     * list), whose empty identity is a NULL date (no value → [Val.Unknown], the comparison does not fire) —
     * never the number combiner's `0`. Operand types are homogeneous by authoring (the MVK comparison type
     * checks), so any date-like operand decides for the whole aggregate (`DateAggregateDiffTest`).
     */
    private fun dateAggregate(f: Expr.Func): Boolean = f.args.any { a ->
        when (a) {
            is Expr.Field -> dateLike(ctxOf(a).declaredKind(a.path))
            is Expr.Star -> dateLike(ctxOf(a).declaredKind(a.path))
            is Expr.SemanticIndex -> dateLike(ctx.declaredKind(a.path))
            else -> producesDate(a)
        }
    }

    /** The min/max empty-fold identity by operand kind: dates → no value; numbers → `0` (see [dateAggregate]). */
    private fun minMaxEmpty(f: Expr.Func): Val = if (dateAggregate(f)) Val.Unknown else Val.Num(DEC_ZERO)

    /**
     * Operand values for the operand-list `Min`/`Max`. A NUMBER operand always counts — an empty number field
     * arrives as the substituted `0` (the kernel feeds every operand's `feldWertAlsZahl` to the combiner). A
     * DATE-like field operand that is EMPTY (and not formally invalid) is SKIPPED — the kernel's `minDatum`
     * combine keeps the date-bearing side when the other is a valueless `VkDate` — while a malformed or
     * required-but-empty one still resolves [Val.Unknown] and poisons via [combine] (`feldWertAlsDatum` returns
     * nicht-prüfungsrelevant there). `DateAggregateDiffTest`.
     */
    private fun minMaxOperandVals(f: Expr.Func): List<Val> = f.args.mapNotNull { a ->
        val skippableEmptyDate = a is Expr.Field && dateLike(ctxOf(a).declaredKind(a.path))
            && !ctxOf(a).filled(a.path) && !invalid(a.path)
        if (skippableEmptyDate) null else evalExpr(a)
    }

    /**
     * The FILLED values feeding a value aggregate (`Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`),
     * collected across ALL operands — the single-star form AND the kernel's explicit MULTI-FIELD list (IF51).
     * A starred field expands over its repetition rows (a `Having` filters them); a plain field contributes
     * its value only when filled, so an empty operand is DROPPED (as the star drops empty cells) — `MinValue(A, B)`
     * with `B` empty ignores `B`, it never treats it as 0. A filled-but-malformed operand resolves to [Val.Unknown]
     * and [combine] then suppresses the whole aggregate.
     */
    private fun starVals(f: Expr.Func): List<Val> = f.args.flatMap { aggOperandVals(it) }

    private fun aggOperandVals(arg: Expr): List<Val> = when (arg) {
        is Expr.Star -> starOperandVals(arg)
        else -> if (ctx.filled(pathOf(arg))) listOf(evalExpr(arg)) else emptyList()   // plain field: empty dropped
    }

    /** A starred aggregate operand's filled values — its group's row cells (empties dropped, like [EvalContext.star]);
     *  an optional `Having` evaluates the per-row filter in each row context and keeps only the matching rows.
     *  COMPUTE mode: a value aggregate reads ALL its selected cells (the kernel feeds every row to the combiner),
     *  so any formally-INVALID selected cell poisons (IF85) — the filter's own reads poison inside [havingInterp]. */
    private fun starOperandVals(star: Expr.Star): List<Val> {
        // A GROUP star (`Sum(Lines*)` — no field segment) expands to the group's contained FIELDS, recursively
        // (the kernel ExpandService → getAllContainedFields, exactly like the fill quantifiers — IF49/IF51),
        // each field's cells collected with the same star semantics (bindDepth + Having preserved; IF99).
        if (ctxOf(star).declaredKind(star.path) == null) {
            val contained = ctxOf(star).containedFields(star.path)
            if (contained.isNotEmpty()) {
                return contained.flatMap { starOperandVals(Expr.Star(it, star.having, star.outer, star.bindDepth)) }
            }
        }
        if (star.having == null) {
            if (computeMode && ctx.starScanStates(star.path, star.bindDepth).any { it == CellState.INVALID }) {
                throw PoisonedReadException(star.path)
            }
            // An index field's not-check-relevant cell (mandatory-empty / duplicated value) makes the whole
            // aggregate non-evaluable — the kernel's `combineFeldListe` returns NICHT_PRUEF_REL on the first such
            // cell, so the referencing rule is suppressed (IF15/IF60 generalized to aggregates).
            if (indexOperandNonEvaluable(ctx, star.path, star.bindDepth)) return listOf(Val.Unknown)
            return ctx.star(star.path, star.bindDepth)
        }
        val group = star.path.substringBeforeLast('/')
        return ctx.rowContexts(group, star.bindDepth)
            .filter { rc -> havingInterp(rc).fires(star.having) }
            .mapNotNull { rc ->
                if (computeMode && rc.cellState(star.path) == CellState.INVALID) throw PoisonedReadException(star.path)
                if (rc.filled(star.path)) rc.field(star.path) else null
            }
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

    /** The path of a plain field-ref operand. Any other operand shape in a field-ref position is a construct
     *  this evaluator does not model there — reported UNSUPPORTED (the per-rule tolerance catches it), never a
     *  ClassCastException that would sink the whole validation (the per-rule degrade net, IF61). */
    private fun pathOf(e: Expr): String = (e as? Expr.Field)?.path
        ?: throw UnsupportedConstructException("unsupported operand ${e::class.simpleName} in a field-reference position")
    private fun num(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")

    private fun eq(l: Val, r: Val): Boolean = when {
        // NUMBER equality rounds both to scale 19 HALF_UP, like the comparison ops (the kernel's `vergleiche`).
        l is Val.Num && r is Val.Num -> l.v.compareScaled19(r.v) == 0
        l is Val.Txt && r is Val.Txt -> l.v == r.v
        l is Val.Bool && r is Val.Bool -> l.v == r.v
        // A DATE_RANGE pair is equal iff both endpoints match (the implicit computation-validation of a
        // date-range computed field compares two ranges; the kernel uses endpoint equality). IF32.
        l is Val.Range && r is Val.Range -> l.start == r.start && l.end == r.end
        else -> false
    }

    private companion object {
        /** A TIME value `HH:mm:ss` — captures hours, minutes, seconds for the time-of-day extractors. */
        val TIME = Regex("""(\d{2}):(\d{2}):(\d{2})""")

        /** The OMITTED-YEAR date literal shape `dd.MM.` (day.month., trailing dot, no year) — the reference-year
         *  form that gets `baseYear` injected (IF52); the full `dd.MM.yyyy` never matches (it has a trailing year). */
        val OMITTED_YEAR = Regex("""\d{1,2}\.\d{1,2}\.""")

        /** Functions that yield a DATE value — so a counterpart string literal beside one is a date literal
         *  ([producesDate]). The date-time/range constructors return other kinds; the extractors return numbers. */
        val DATE_PRODUCING_FUNCS = setOf(
            FunctionOp.DATE, FunctionOp.DATE_FROM_DATE_TIME,
            FunctionOp.START_OF_DATE_RANGE, FunctionOp.END_OF_DATE_RANGE,
            FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS,
            FunctionOp.VALUE_AS_DATE,
        )

        /** The min/max family — DATE-valued when its operands are dates ([dateAggregate]/[producesDate]). */
        val MIN_MAX_FUNCS = setOf(FunctionOp.MIN, FunctionOp.MAX, FunctionOp.MIN_VALUE, FunctionOp.MAX_VALUE)

        /** `RangeAsNumber` parses its substring ONLY when it is a run of digits (kernel `\d+`); else the value is 0. */
        val DIGITS = Regex("""\d+""")
    }
}
