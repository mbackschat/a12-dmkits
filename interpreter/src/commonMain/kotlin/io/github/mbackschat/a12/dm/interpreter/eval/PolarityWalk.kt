package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

// The polarity-walk collaborator — the typed truth+polarity walks ([Interpreter.firedType]'s
// [evalTypedLazy] and the eager [evalTyped] it must agree with) plus the FILL resolution machinery
// (the kernel's `VkBigDecimal`/combiner fill propagation, resolved against the row context) and the
// comparison/tolerance/predicate polarity rules. ONE kernel subsystem serving several constructs, so it
// is sliced horizontally; a construct whose polarity is computed IN its truth scan keeps it vertical
// (the overlap predicates in the strategy, VLQ in its own file). An extension family on the shared
// eval-state seam ([Interpreter], IF117); the pure per-operator polarity RULES live in [Polarity] (IF10).

/** The entity-count ops whose filtered fill follows the kernel count rule (IF114), not the
 *  value-combiner blanket. */
private val ENTITY_COUNTS = setOf(FunctionOp.NUMBER_OF_FILLED_FIELDS, FunctionOp.NUMBER_OF_FILLED_GROUPS)

/** A node's (truth, polarity). **Interned** (3 × 2 = 6 instances) — the kernel returns an interned
 *  `ValidierungsErgebnis` enum (0 alloc); a node result is allocated per-node-per-row in the hot walk, so
 *  routing construction through [of] removes that allocation. Use `Res.of(...)`, never the constructor. */
internal data class Res(val truth: Tri, val type: MsgType) {
    companion object {
        private val interned = Array(Tri.entries.size) { t -> Array(MsgType.entries.size) { m -> Res(Tri.entries[t], MsgType.entries[m]) } }
        fun of(truth: Tri, type: MsgType): Res = interned[truth.ordinal][type.ordinal]
    }
}

// The typing rides alongside the truth value (kernel: ValidierungsErgebnis.combineUND/combineODER):
//   And — omission wins (a fillable branch makes the whole error fillable);
//   Or  — value wins  (a satisfied value branch convicts regardless of filling).
internal fun Interpreter.evalTyped(c: Cond): Res = when (c) {
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
        Res.of(Kleene.and(a.truth, b.truth), andType(a, b))
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
internal fun Interpreter.evalTypedLazy(c: Cond): Res = when (c) {
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
        // type read only when truth==TRUE ⟹ both a,b TRUE ⟹ both types real (not the placeholder) — the
        // SAME [andType] combine the eager walk uses, gated by the lazy skip (like the Or branch below).
        Res.of(truth, if (truth == Tri.TRUE) andType(a, b) else MsgType.VALUE)
    }
    is Cond.Or -> {
        val a = evalTypedLazy(c.left)
        val b = evalTypedLazy(c.right)
        val truth = Kleene.or(a.truth, b.truth)
        Res.of(truth, if (truth == Tri.TRUE) orType(a, b) else MsgType.VALUE)
    }
    is Cond.Not -> evalTyped(c.c).let { Res.of(Kleene.not(it.truth), it.type) }   // dead path: full typing keeps Not sound
}

/** And: a fillable (OMISSION) branch makes the whole error fillable — OMISSION if EITHER operand is
 *  OMISSION, else VALUE (kernel: `ValidierungsErgebnis.combineUND`). Shared by [evalTyped] (unconditional)
 *  and [evalTypedLazy] (read only on a TRUE And, which forces both operands TRUE — their types real). */
private fun Interpreter.andType(a: Res, b: Res): MsgType =
    if (a.type == MsgType.OMISSION || b.type == MsgType.OMISSION) MsgType.OMISSION else MsgType.VALUE

/** Or: among the branches that are TRUE, a VALUE branch convicts → VALUE; else OMISSION. */
private fun Interpreter.orType(a: Res, b: Res): MsgType {
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
private fun Interpreter.fill(e: Expr): Fill {
    // The kernel's VALUE combiners mark ANY `Having`-filtered result fillable in BOTH directions
    // (`getValue(containsFilter)` → kannGroesser = kannKleiner = true — NumberCombiner and its family), so
    // a fired comparison consuming a filtered star is TRUE_AF → OMISSION in both directions — even when
    // the filter KEPT a filled row (kernel-pinned, IF104; the comparison twin of IF101's quantifier
    // escalation). The ENTITY COUNTS do NOT ride that combiner rule — `anzahlAngegebenerEntities` has its
    // own filter flags ([countFill], IF114) — so they compute their own fill.
    if (hasHavingStar(e) && !isEntityCount(e)) return Fill.of(true, true)
    return fillOf(e)
}

/** Whether [e] is directly an entity-count call (`NumberOfFilledFields`/`NumberOfFilledGroups`) — the two
 *  ops whose filtered fill follows the kernel count rule ([countFill], IF114), not the value-combiner
 *  blanket. (A count NESTED in arithmetic with a filtered star stays blanketed — unpinned, no corpus
 *  instance.) */
private fun Interpreter.isEntityCount(e: Expr): Boolean = e is Expr.Func &&
    FunctionOp.byName(e.name) in ENTITY_COUNTS

/** Whether [e]'s tree consumes a `Having`-FILTERED star (the combiner escalation trigger, IF104). */
private fun Interpreter.hasHavingStar(e: Expr): Boolean = when (e) {
    is Expr.Star -> e.having != null
    is Expr.Func -> e.args.any { hasHavingStar(it) }
    is Expr.Arith -> hasHavingStar(e.left) || hasHavingStar(e.right)
    else -> false
}

private fun Interpreter.fillOf(e: Expr): Fill = when (e) {
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
 * CONCATENATION, not numeric — it carries the kernel's NOT-GIVEN flag instead (`VkString.add` ORs
 * `nichtAngegeben` across the parts, and `RuntimeController.vergleicheSTRING` types a fired comparison
 * TRUE_AF → OMISSION iff an operand carries it — IF113): fillable symmetric when ANY part is a not-given
 * read, else the fixed leaf (a fired fully-given string comparison is VALUE). A malformed / non-numeric
 * operand makes the whole term non-evaluable (the comparison is UNKNOWN and does not fire, so its type is
 * discarded) → the fixed leaf, matching `INVALID_NUMBER`.
 */
private fun Interpreter.arithFill(a: Expr.Arith): Fill {
    if (a.op == ArithOp.ADD && (producesString(a.left) || producesString(a.right))) {
        val na = concatNotGiven(a.left) || concatNotGiven(a.right)
        return Fill.of(na, na)
    }
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

/** Whether a string-concat PART resolves to the kernel's NOT-GIVEN string (`VkString.nichtAngegeben`,
 *  IF113): an EMPTY check-relevant field read, a NO-MATCH/empty-cell indexed read, a `FieldValueAsString`
 *  over either, or a nested concat carrying one. A literal or another function value is given; a MALFORMED
 *  part makes the comparison UNKNOWN (suppressed), so its answer here is discarded. (`Substring`/
 *  `RangeAsString` parts preserve the flag in the kernel (`getSubstring`) but are unpinned by any
 *  differential and absent from the corpora — extend when an instance is pinned.) */
private fun Interpreter.concatNotGiven(e: Expr): Boolean = when (e) {
    is Expr.Field -> !ctxOf(e).filled(e.path)
    is Expr.SemanticIndex -> semanticIndexCell(e) == null
    is Expr.Func -> FunctionOp.byName(e.name) == FunctionOp.FIELD_VALUE_AS_STRING && concatNotGiven(e.args[0])
    is Expr.Arith -> e.op == ArithOp.ADD && (concatNotGiven(e.left) || concatNotGiven(e.right))
    else -> false
}

/** The resolved value SIGN (−1 / 0 / +1) of an operand — 0 for empty (empty-as-0) or a non-numeric/Unknown
 *  value (then the whole term is Unknown and its polarity is discarded). */
private fun Interpreter.signOf(e: Expr): Int = (evalExpr(e) as? Val.Num)?.v?.compareTo(DEC_ZERO) ?: 0

/** The resolved numeric value of an operand, or null when non-numeric/Unknown (a division-by-zero, a
 *  malformed cell) — the caller then reports the term fixed (it never fires). */
private fun Interpreter.numValOrNull(e: Expr): Dec? = (evalExpr(e) as? Val.Num)?.v

/**
 * Directional fillability of a value FUNCTION. The starred aggregates carry the kernel's `NumberCombiner`
 * directions while their tail can still grow (an empty operand or an un-instantiated tail): a `Sum` can
 * grow, and shrink only if the summed field is SIGNED; `MaxValue` can only grow; `MinValue` can only
 * shrink. For a SIGNED field a `Sum`'s fill is `(true, true)` — identical to the old symmetric notion, so
 * signed aggregates are unchanged; only unsigned ones gain the directional VALUE typing. Every other
 * function (the count aggregates — a fillable operand is unconditionally OMISSION; operand-list `Min`/`Max`;
 * date functions) keeps the symmetric notion.
 */
private fun Interpreter.funcFill(f: Expr.Func): Fill = when (FunctionOp.byName(f.name)) {
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
    // MultiFieldAggregateDiffTest.firedPolarityMatchesKernel, NumberOfFilledDiffTest). The ENTITY counts
    // additionally carry the kernel's own filter flags ([countFill], IF114).
    FunctionOp.NUMBER_OF_FILLED_FIELDS, FunctionOp.NUMBER_OF_FILLED_GROUPS -> countFill(f)
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

/**
 * The kernel's ENTITY-COUNT fill flags (`RuntimeController.anzahlAngegebenerEntities`, IF114). A
 * `Having`-FILTERED operand that actually COUNTED a value escalates BOTH directions
 * (`containsFilteredValue` — "in presence of a filter the count can become bigger or smaller"); otherwise
 * the count is GROW-ONLY: it can grow while any operand cell remains uncounted — an empty cell or the
 * un-instantiated declared tail ([leafOmission]), or a FILTERED-OUT row's cell (the kernel's
 * `anzAllerFelder > anzAngFelder` — filled cells the filter dropped still make the count growable). It
 * never shrinks. So a fired mismatch over a kept-nothing filtered count is DIRECTIONAL (stored below →
 * VALUE, stored above → OMISSION) where the value-combiner blanket said OMISSION for both.
 */
private fun Interpreter.countFill(f: Expr.Func): Fill {
    var counted = 0
    var total = 0
    var anyFilteredCounted = false
    for (a in f.args) {
        val st = a as? Expr.Star ?: continue
        val having = st.having ?: continue
        val group = st.path.substringBeforeLast('/')
        for (rc in ctxOf(st).rowContexts(group, st.bindDepth)) {
            total++
            if (havingInterp(rc).fires(having) && rc.filled(st.path)) { counted++; anyFilteredCounted = true }
        }
    }
    if (anyFilteredCounted) return Fill.of(true, true)
    return Fill.of(canGrow = total > counted || leafOmission(f), canShrink = false)
}

/** Directional fill of an operand-list `Min`/`Max` — [ArithFill.min]/[ArithFill.max] left-folded over the
 *  operands' resolved [fill]s, selecting by value ORDER (the smaller wins a `Min`'s grow direction). Numeric
 *  operands only; a DATE-like or non-numeric operand falls back to the symmetric leaf (the kernel's date
 *  min/max carries only the symmetric `nichtAngegeben`, matching [leafOmission]). */
private fun Interpreter.minMaxFill(f: Expr.Func): Fill {
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
private fun Interpreter.starFill(f: Expr.Func, build: (signed: Boolean) -> Fill): Fill {
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
private fun Interpreter.leafType(op: CmpOp, left: Expr, right: Expr): MsgType {
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
private fun Interpreter.neFillable(left: Expr, right: Expr, l: Fill, r: Fill): Boolean {
    val lv = evalExpr(left)
    val rv = evalExpr(right)
    if (!orderable(lv) || !orderable(rv)) return Polarity.anyMove(l, r)
    return Polarity.neFillable(ValCompare.language(lv, rv) < 0, l, r)
}

private fun orderable(v: Val): Boolean = v is Val.Num || v is Val.Txt

/** Tolerance polarity is EQ-like (kernel-probed): OMISSION when filling an empty operand toward the other
 *  could close the gap and clear the firing, else VALUE — so it is fillable iff either operand can move. */
private fun Interpreter.toleranceType(c: Cond.Tolerance): MsgType =
    if (Polarity.anyMove(fill(c.left), fill(c.right))) MsgType.OMISSION else MsgType.VALUE

/**
 * The value-vs-omission polarity of a fired predicate. Non-quantifier predicates carry a static
 * [PredicateOp.polarity]; a field-list quantifier's polarity is **data-dependent** and computed the way
 * the kernel does — OMISSION iff filling some currently-empty operand could clear the firing (so the
 * error is fixable by filling), else VALUE. (E.g. `NotExactlyOneFieldFilled` is OMISSION at k=0 but
 * VALUE at k=2 — KERNEL-FINDINGS §1.)
 */
private fun Interpreter.predType(p: Cond.Pred): MsgType {
    val op = PredicateOp.byName(p.name) ?: return MsgType.VALUE
    // RepetitionNotUnique's polarity is the per-row cache outcome (IF149): VALUE when every key component is
    // present, OMISSION when a fillable component is empty. Read only on a firing (TRUE) row, so the outcome is
    // non-null; the fallback keeps the lazy-walk placeholder sound.
    if (op == PredicateOp.REPETITION_NOT_UNIQUE) return rnuOutcomeAt() ?: MsgType.VALUE
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

private fun Interpreter.leafOmission(e: Expr): Boolean = when (e) {
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

/** The message polarity of a fired group/starred-scope fill quantifier — the kernel's fixed WF/AF per op
 *  (`NoField`/`NotAll`/`FieldsNotCollectively` are omission errors; `NotExactlyOne` is omission at 0 filled,
 *  value at ≥2; the rest are value errors). IF49. */
private fun Interpreter.fillScopeType(op: PredicateOp, fs: FillScope): MsgType = when (op) {
    PredicateOp.NO_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
    PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> MsgType.OMISSION
    PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> if (fs.tInst == 0) MsgType.OMISSION else MsgType.VALUE
    else -> MsgType.VALUE
}
