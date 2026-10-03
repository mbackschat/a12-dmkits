package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.expansionBindDepth
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

// The polarity-walk collaborator — the typed truth+polarity walks ([Interpreter.firedType]'s
// [evalTypedLazy] and the eager [evalTyped] it must agree with) plus the FILL resolution machinery
// (the kernel's `VkBigDecimal`/combiner fill propagation, resolved against the row context) and the
// comparison/tolerance/predicate polarity rules. ONE kernel subsystem serving several constructs, so it
// is sliced horizontally; a construct whose polarity is computed IN its truth scan keeps it vertical
// (the overlap predicates in the strategy, VLQ in its own file). An extension family on the shared
// eval-state seam ([Interpreter], IF117); the pure per-operator polarity RULES live in [Polarity] (IF10).

/** The count ops whose filtered fill follows the kernel's `containsFilteredValue` count rule ([countFill],
 *  IF114 + SPEC-2026-07-23-12), not the value-combiner blanket: `NumberOfFilledFields`/`NumberOfFilledGroups`
 *  (`anzahlAngegebenerEntities`) and `NumberOfValueInFields` (`anzahlWertInFeldlisteIntern`). */
private val FILTER_SENSITIVE_COUNTS = setOf(
    FunctionOp.NUMBER_OF_FILLED_FIELDS, FunctionOp.NUMBER_OF_FILLED_GROUPS, FunctionOp.NUMBER_OF_VALUE_IN_FIELDS)

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
    is Cond.ValueListQuant -> vlqRes(c)   // truth + witness-specific polarity in ONE pass (SPEC-2026-07-23-16)
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
internal fun Interpreter.evalTypedLazy(c: Cond): Res {
    // Validation walks HERE, not through the plain condition dispatch: `firedType` needs a node's polarity, so
    // a rule evaluated by `validate` never reaches `evalCond`. Counting only there reported a 200-message
    // validation as doing zero condition work (measured 2026-09-19, which is how this site was found).
    work?.conditionNode()
    return evalTypedLazyNode(c)
}

private fun Interpreter.evalTypedLazyNode(c: Cond): Res = when (c) {
    is Cond.Cmp -> evalCmp(c).let { Res.of(it, if (it == Tri.TRUE) leafType(c.op, c.left, c.right) else MsgType.VALUE) }
    is Cond.Tolerance -> evalTolerance(c).let { Res.of(it, if (it == Tri.TRUE) toleranceType(c) else MsgType.VALUE) }
    is Cond.ValueListQuant -> vlqRes(c)   // single pass; a non-firing result already carries the VALUE placeholder
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
 *  `kannGroesserWerden`/`kannKleinerWerden = hatVorzeichen`); a filled field or literal can't move. Numeric
 *  functions and arithmetic propagate those directions. In particular, value aggregates summarize every
 *  missing declaration (including fields expanded from a GROUP star): an all-empty aggregate's `0` identity
 *  moves both ways, while a present-value `Sum` shrinks iff a specifically missing declaration is signed. */
private fun Interpreter.fill(e: Expr): Fill {
    // FirstFilledValue is positionally short-circuiting: only a filter on a REACHED operand escalates
    // polarity. Its shared ordered scan handles that before the general value-combiner rule below (IF184).
    if (e is Expr.Func && FunctionOp.byName(e.name) == FunctionOp.FIRST_FILLED_VALUE) return fillOf(e)

    // The kernel's ordinary VALUE combiners mark ANY `Having`-filtered result fillable in BOTH directions
    // (`getValue(containsFilter)` → kannGroesser = kannKleiner = true — NumberCombiner and its family), so
    // a fired comparison consuming a filtered star is TRUE_AF → OMISSION in both directions — even when
    // the filter KEPT a filled row (kernel-pinned, IF104; the comparison twin of IF101's quantifier
    // escalation). The ENTITY COUNTS do NOT ride that combiner rule — `anzahlAngegebenerEntities` has its
    // own filter flags ([countFill], IF114/SPEC-2026-07-23-12) — so they compute their own fill.
    if (hasHavingStar(e) && !isFilterSensitiveCount(e)) return Fill.of(true, true)
    return fillOf(e)
}

/** Whether [e] is directly a filter-sensitive count call ([FILTER_SENSITIVE_COUNTS] — the filled-entity counts
 *  and `NumberOfValueInFields`) whose filtered fill follows the kernel's `containsFilteredValue` count rule
 *  ([countFill]), not the value-combiner blanket. (A count NESTED in arithmetic with a filtered star stays
 *  blanketed — unpinned, no corpus instance.) */
private fun Interpreter.isFilterSensitiveCount(e: Expr): Boolean = e is Expr.Func &&
    FunctionOp.byName(e.name) in FILTER_SENSITIVE_COUNTS

/** Whether [e]'s tree consumes a `Having`-FILTERED star (the combiner escalation trigger, IF104). */
private fun Interpreter.hasHavingStar(e: Expr): Boolean = when (e) {
    is Expr.Star -> e.having != null
    is Expr.Func -> if (FunctionOp.byName(e.name) == FunctionOp.FIRST_FILLED_VALUE)
        firstFilledScan(e).encounteredFilter
    else e.args.any { hasHavingStar(it) }
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

/** The resolved value SIGN (−1 / 0 / +1) of an operand. A [Val.DomainFailure] is distinct from
 *  [Val.Unknown], but neither has a comparison value, so polarity projects both to the fixed/non-firing
 *  shape. */
private fun Interpreter.signOf(e: Expr): Int = when (val value = evalExpr(e)) {
    is Val.Num -> value.v.compareTo(DEC_ZERO)
    Val.DomainFailure -> 0
    else -> 0
}

/** The resolved numeric value of an operand. [Val.DomainFailure] and [Val.Unknown] remain distinct in the
 *  value algebra even though both project to null at this comparison-polarity boundary. */
private fun Interpreter.numValOrNull(e: Expr): Dec? = evalExpr(e).numericOrNull()

/** Numeric comparison projection. [Val.DomainFailure] and [Val.Unknown] deliberately share the no-value
 *  result here, but remain explicit cases in the exhaustive value-algebra dispatch. */
private fun Val.numericOrNull(): Dec? = when (this) {
    is Val.Num -> v
    is Val.Txt, is Val.Bool, is Val.Range, Val.Unknown, Val.DomainFailure -> null
}

/**
 * Directional fillability of a value FUNCTION. Numeric value aggregates carry the kernel's declaration-aware
 * `NumberCombiner` directions. With at least one present value and at least one missing contributor, `Sum`
 * grows and shrinks iff any missing declaration is signed, `MaxValue` only grows, and `MinValue` only shrinks.
 * The all-empty `0` identity is the exception: its first value can move either way for every operator and
 * signedness. Group stars expand to their contained declarations before this summary. Date aggregates retain
 * the symmetric DateCombiner rule; the other function families use their rules below.
 */
private fun Interpreter.funcFill(f: Expr.Func): Fill = when (FunctionOp.byName(f.name)) {
    FunctionOp.SUM -> numericAggregateFill(f) { signed -> Fill.of(canGrow = true, canShrink = signed) }
    FunctionOp.MAX_VALUE -> numericAggregateFill(f) { Fill.of(canGrow = true, canShrink = false) }
    FunctionOp.MIN_VALUE -> numericAggregateFill(f) { Fill.of(canGrow = false, canShrink = true) }
    // CurrentRepetition is the structural ROW INDEX — no field-fill can change it, so a fired comparison
    // against it is always a VALUE error (kernel-probed). The generic fallback would wrongly read its group
    // argument as an "unfilled field" and type it OMISSION.
    FunctionOp.CURRENT_REPETITION -> Fill.of(false, false)
    // The value walk and polarity walk share the same positional stop: only empty/filter markers reached
    // before the terminal value can make it fillable; later operands are invisible (IF184).
    FunctionOp.FIRST_FILLED_VALUE -> firstFilledScan(f).fill
    // The COUNT aggregates are monotonic NON-DECREASING under filling — filling a field / row / distinct value
    // can only ADD to the count, never remove one. So they grow like an UNSIGNED `Sum`: `canGrow` while a
    // fillable operand remains, `canShrink` NEVER. Directionally this makes a fired `count >= n` / `count > n`
    // a VALUE error (filling can't clear it) while `count < n` / `count == n` stays OMISSION (filling still
    // MultiFieldAggregateDiffTest.firedPolarityMatchesKernel, NumberOfFilledDiffTest). The filter-sensitive
    // counts additionally carry the kernel's own filter flags ([countFill], IF114/SPEC-2026-07-23-12).
    // The two split on WHAT they count, so they cannot share one operand read. `NumberOfFilledFields` counts
    // CELLS, so a group operand contributes its expansion — the same read its value arm already does through
    // `fillScope(isGroup = true)`. `NumberOfFilledGroups` counts GROUPS, so its group operand IS the counted
    // entity and expanding it would be wrong in kind; it gets its own entity tally.
    FunctionOp.NUMBER_OF_FILLED_FIELDS -> countFill(f, expandGroupOperands(f.args))
    FunctionOp.NUMBER_OF_FILLED_GROUPS -> filledGroupCountFill(f)
    // NumberOfValueInFields rides the same count rule, but a filtered cell only makes the count SHRINKABLE
    // when its value MATCHED the counted constant (the `containsFilteredValue` value gate) — so a filtered
    // selected non-match is a grow-only zero, not both-directional (SPEC-2026-07-23-12).
    FunctionOp.NUMBER_OF_VALUE_IN_FIELDS -> evalExpr(f.args[0]).let { target ->
        // The counted constant rides along unexpanded (it is no entity); the operand tail's GROUPS expand to the
        // same declarations the value walk counts — the shared owner, so the two walks cannot disagree.
        countFill(f, expandGroupOperands(f.args)) { rc, path -> ValCompare.equal(rc.field(path), target) }
    }
    // NumberOfDifferentValues follows the value-combiner rule for a filter (blanketed both-directional by the
    // [fill] escalation above); unfiltered it is grow-only like the other counts.
    FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> Fill.of(canGrow = leafOmission(f), canShrink = false)
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
 * The kernel's filter-sensitive COUNT fill flags (`RuntimeController.anzahlAngegebenerEntities`, IF114;
 * `anzahlWertInFeldlisteIntern`, SPEC-2026-07-23-12). A `Having`-FILTERED operand that actually COUNTED a
 * qualifying cell escalates BOTH directions (`containsFilteredValue` — "in presence of a filter the count can
 * become bigger or smaller"); otherwise the count is GROW-ONLY: it can grow while any operand cell remains
 * uncounted — an empty cell or the un-instantiated declared tail ([leafOmission]), or a FILTERED-OUT row's
 * cell (the kernel's `anzAllerFelder > anzAngFelder` — filled cells the filter dropped still make the count
 * growable). It never shrinks. So a fired mismatch over a kept-nothing filtered count is DIRECTIONAL (stored
 * below → VALUE, stored above → OMISSION) where the value-combiner blanket said OMISSION for both.
 *
 * [qualifies] is the per-cell "this filled cell is COUNTED" gate: always for the filled-entity counts, and
 * "the value equals the constant" for `NumberOfValueInFields` — so a filtered selected NON-match leaves a
 * grow-only zero (the filter is present ⇒ growable, but no match came through it ⇒ not shrinkable), while a
 * filtered current MATCH is both-directional.
 *
 * [operands] is the list this fill READS, stated by each caller rather than defaulted, because a GROUP operand
 * means different things to the three counts. `NumberOfValueInFields` counts CELLS, so its group operand is
 * expanded to the declarations below it — unexpanded, no arm sees a `Star`, and `leafOmission(Expr.Field(<group
 * path>))` is `!filled(<group path>)`, which is TRUE for every group because a group holds no value of its own.
 * That answered a permanently growable count regardless of contents: measured, a fired comparison over a FULL
 * fixed group was typed OMISSION where both kernel strategies answer VALUE ([IF287]'s class in the fill walk,
 * `FixedGroupValueInFieldsPolarityDiffTest`). `NumberOfFilledGroups` counts GROUPS, so its group operand is the
 * counted entity and expanding it would be wrong in kind; it keeps the authored list and reads its entities
 * through [filledGroupCountFill] instead.
 */
private fun Interpreter.countFill(
    f: Expr.Func,
    operands: List<Expr> = f.args,
    qualifies: (EvalContext, String) -> Boolean = { _, _ -> true },
): Fill {
    var counted = 0
    var total = 0
    var anyFilteredCounted = false
    for (a in operands) {
        val st = a as? Expr.Star ?: continue
        val having = st.having ?: continue
        val group = st.path.substringBeforeLast('/')
        for (rc in ctxOf(st).rowContexts(group, st.bindDepth)) {
            total++
            if (havingInterp(rc).fires(having) && rc.filled(st.path) && qualifies(rc, st.path)) {
                counted++; anyFilteredCounted = true
            }
        }
    }
    if (anyFilteredCounted) return Fill.of(true, true)
    return Fill.of(canGrow = total > counted || operands.any { leafOmission(it) }, canShrink = false)
}

/**
 * The GROUP-counting fill — `NumberOfFilledGroups`. The kernel routes `anzahlAngegebenerKontexte` through the
 * very same `anzahlAngegebenerEntities` as the field count, so there is ONE rule — `anzAllerEntities >
 * anzAngEntities`, never shrinkable — and only the counted ENTITY differs: a group, not a cell. That is exactly
 * why this count cannot share the cell expansion its two siblings use; expanding would report a group that is
 * already counted as still growable.
 *
 * `given` is the number of entities whose group product state is FILLED, read through [Interpreter.groupFillStates] —
 * the same stream the group-fill quantifiers tally, so group presence is decided in one place. `total` is the
 * DECLARED extent, the total the kernel's entity iterator declares before it trims to the filled rows: one entity
 * per unstarred operand, and for a starred one [declaredGroupListExtent], the product of the declared capacities
 * of the repeatable levels it ranges over (KF463). Reading the
 * declared extent rather than the instantiated rows is what makes an EMPTY repeatable group growable — with no
 * rows at all there is nothing for [Interpreter.groupFillStates] to yield, and an instantiated-row tally would
 * answer `0 > 0` and type a fired comparison VALUE. An unbounded repeatable operand never closes its tail.
 *
 * Before this, every shape here fell through to `leafOmission(Expr.Field(<group path>))`, whose `!filled(<group
 * path>)` is TRUE for any group because a group holds no value of its own — so the count read as permanently
 * growable regardless of content, except at zero rows where the starred shape read as permanently fixed. Both
 * measured against both kernel strategies in `FixedGroupValueInFieldsPolarityDiffTest` /
 * `StarredGroupCountPolarityDiffTest` ([IF312], [IF287]'s class in the fill walk).
 */
private fun Interpreter.filledGroupCountFill(f: Expr.Func): Fill {
    // A `Having` on a group-counting operand is not authorable through the typed surface and has no measured
    // basis here, so it degrades LOUD rather than borrowing either branch's answer (IF61, as `rejectHaving` does).
    rejectHaving(f.args, "NumberOfFilledGroups")
    var given = 0
    for (state in groupFillStates(f.args)) if (state == Tri.TRUE) given++
    var total = 0
    for (arg in f.args) {
        if (arg !is Expr.Star) { total++; continue }
        val declared = ctxOf(arg).declaredGroupListExtent(arg.path, arg.bindDepth)
            ?: return Fill.of(canGrow = true, canShrink = false)   // unbounded: the tail never closes
        total += declared
    }
    return Fill.of(canGrow = total > given, canShrink = false)
}

/** Directional fill of an operand-list `Min`/`Max` — [ArithFill.min]/[ArithFill.max] left-folded over the
 *  operands' resolved [fill]s, selecting by value ORDER (the smaller wins a `Min`'s grow direction). Numeric
 *  operands only; a DATE-like or non-numeric operand falls back to the symmetric leaf (the kernel's date
 *  min/max carries only the symmetric `nichtAngegeben`, matching [leafOmission]). */
private fun Interpreter.minMaxFill(f: Expr.Func): Fill {
    val isMin = FunctionOp.byName(f.name) == FunctionOp.MIN
    val terms = f.args.map { it to evalExpr(it).numericOrNull() }
    // A non-numeric / DATE-like / Unknown / domain-failed operand → the symmetric leaf (kernel date min/max is symmetric).
    if (terms.any { it.second == null }) return leafOmission(f).let { Fill.of(it, it) }
    return terms.drop(1).fold(fill(terms[0].first) to terms[0].second!!) { (accFill, accVal), (e, v) ->
        val less = accVal.compareTo(v!!)                 // running selection vs the next operand
        val combined = if (isMin) ArithFill.min(accFill, fill(e), less) else ArithFill.max(accFill, fill(e), less)
        combined to (if (isMin == (less <= 0)) accVal else v)   // the new running min / max VALUE
    }.first
}

/** The declaration-aware fill summary of a numeric value aggregate. A missing declaration contributes its
 *  own signedness even when another declaration supplies the current value; a GROUP star expands to the same
 *  field declarations the value walk consumes. At the all-empty `0` identity the first future value can move
 *  the result either way regardless of field signedness or aggregate operator. Once a value is present,
 *  [build] applies the operator direction and a `Sum` can shrink iff a specifically missing declaration is
 *  signed. No missing declaration means the result is fixed.
 *
 *  Non-numeric/date/unsupported operand shapes retain [legacyStarFill], preserving the DateCombiner's
 *  symmetric fill, the single-star exhausted behavior, and the former safe fallback. `Having` is handled
 *  before this function by [fill], which preserves its unconditional both-direction escalation. */
private fun Interpreter.numericAggregateFill(f: Expr.Func, build: (signedMissing: Boolean) -> Fill): Fill {
    val state = f.args.fold(AggregateFillState.EMPTY) { acc, arg ->
        val next = aggregateFillState(arg) ?: return legacyStarFill(f, build)
        acc + next
    }
    return when {
        !state.hasMissing -> Fill.of(false, false)
        !state.hasPresent -> Fill.of(true, true)
        else -> build(state.hasSignedMissing)
    }
}

private data class AggregateFillState(
    val hasPresent: Boolean,
    val hasMissing: Boolean,
    val hasSignedMissing: Boolean,
) {
    operator fun plus(other: AggregateFillState): AggregateFillState = AggregateFillState(
        hasPresent || other.hasPresent,
        hasMissing || other.hasMissing,
        hasSignedMissing || other.hasSignedMissing,
    )

    companion object {
        val EMPTY = AggregateFillState(hasPresent = false, hasMissing = false, hasSignedMissing = false)
    }
}

/** Resolve one legal numeric aggregate operand to the presence/missingness of its declared contributors. */
private fun Interpreter.aggregateFillState(arg: Expr): AggregateFillState? = when (arg) {
    is Expr.EntitySpec -> withIndexedStar(arg,
        AggregateFillState(false, true, false), AggregateFillState.EMPTY) { aggregateFillState(it) }
    // A keyed GROUP's contributors are the selected row's, read as the bare group arm below reads an instance's
    // (IF340); an absent row's are all missing. An undecided key leaves the aggregate non-evaluable, so no
    // message carries this operand's polarity. A keyed FIELD is its one cell.
    is Expr.SemanticIndex -> if (isKeyedGroup(arg)) {
        when (val cells = keyedGroupCells(arg)) {
            is KeyedGroupCells.Selected -> cells.interpreter.aggregateFillState(Expr.Field(arg.path))
            KeyedGroupCells.Empty -> ctxOf(arg).let { c ->
                val fields = c.containedFields(arg.path)
                if (fields.isEmpty() || fields.any { c.declaredKind(it) != Kind.NUMBER }) null
                else AggregateFillState(hasPresent = false, hasMissing = true, fields.any { c.signed(it) })
            }
            KeyedGroupCells.Undecided -> AggregateFillState.EMPTY
        }
    } else {
        val c = ctxOf(arg)
        if (c.declaredKind(arg.path) != Kind.NUMBER) null
        else if (semanticIndexFilled(arg)) AggregateFillState(true, false, false)
        else AggregateFillState(false, true, c.signed(arg.path))
    }
    is Expr.Field -> {
        val c = ctxOf(arg)
        when {
            c.declaredKind(arg.path) == Kind.NUMBER ->
                if (c.filled(arg.path)) AggregateFillState(true, false, false)
                else AggregateFillState(false, true, c.signed(arg.path))
            // A GROUP operand contributes its contained fields, exactly as the `Expr.Star` arm below already
            // does. Returning `null` here sent it to `legacyStarFill`, whose `singleOrNull() as? Expr.Star` is
            // null for a Field, so the polarity came from `leafOmission(Expr.Field(groupPath))` — and
            // `!ctx.filled(groupPath)` is TRUE for every group, since a group holds no value of its own. The
            // result was a symmetric fill state and an OMISSION where the kernel answers VALUE. Measured:
            // `Sum(/Order/Billing) > 5` over 3 + 4 gives `VALUE_ERROR` on both kernel strategies and gave
            // `OMISSION_ERROR` here. The VALUE channel's fix made this reachable — before it the operand was
            // dropped, the sum folded to 0 and the rule never fired, so the mistyping was unobservable (IF287).
            c.declaredKind(arg.path) == null -> {
                val fields = c.containedFields(arg.path)
                if (fields.isEmpty() || fields.any { c.declaredKind(it) != Kind.NUMBER }) null
                else fields.fold(AggregateFillState.EMPTY) { acc, path ->
                    acc + (if (c.filled(path)) AggregateFillState(true, false, false)
                    else AggregateFillState(false, true, c.signed(path)))
                }
            }
            else -> null
        }
    }
    is Expr.Star -> {
        val c = ctxOf(arg)
        when (c.declaredKind(arg.path)) {
            Kind.NUMBER -> c.numericStarFillState(arg)
            null -> {
                val fields = c.containedFields(arg.path)
                if (fields.isEmpty() || fields.any { c.declaredKind(it) != Kind.NUMBER }) null
                else fields.fold(AggregateFillState.EMPTY) { acc, path ->
                    acc + c.numericStarFillState(Expr.Star(path, arg.having, arg.outer, expansionBindDepth(arg.path, arg.bindDepth)))
                }
            }
            else -> null
        }
    }
    else -> null
}

private fun EvalContext.numericStarFillState(star: Expr.Star): AggregateFillState {
    val missing = starOmission(star.path, star.bindDepth)
    return AggregateFillState(
        hasPresent = star(star.path, star.bindDepth).isNotEmpty(),
        hasMissing = missing,
        hasSignedMissing = missing && signed(star.path),
    )
}

/**
 * The fill of a min/max over a **group of DATE fields** — `null` when the operand is not that shape.
 *
 * **Why it cannot be left to the reads below.** A group path has no declared field, so every one of them resolves
 * where nothing is stored: `declaredKind` is null, which makes the DATE branch's `dateLike` test FALSE and sends a
 * date group down the *numeric* branches to read `starOmission`/`star`/`signed` at that path; and an UNSTARRED
 * group is not an `Expr.Star` at all, so `singleOrNull() as? Expr.Star` is null and the polarity came from
 * `leafOmission`, whose `!filled(groupPath)` is TRUE for every group. Both shapes therefore answered a symmetric
 * fill — an **OMISSION** where both kernel strategies answer **VALUE**, measured on a fully-filled document
 * (`DateGroupExtremaDiffTest`; [IF287]'s class).
 *
 * The kind is resolved the way its `ExprEval` sibling resolves it ([firstFilledEntityKind]) and the omission is
 * OR-ed across the expansion, which is exactly the shape the date arm below already has: a DATE aggregate's fill is
 * symmetric, so there is no direction for a per-field disagreement to disturb.
 *
 * **Scope, and every boundary of it is measured rather than assumed.** The numeric group operand is handled by
 * [aggregateFillState]'s own group arms. `Sum` cannot reach here over a date group at all (`MVK_NO_NUMBER`) and a
 * MIXED-kind group cannot reach min/max either (`MVK_NOT_SORTABLE`), so the order-dependent
 * [firstFilledEntityKind] read a review flagged for a mixed group is unreachable — both refusals measured in
 * [KF187]. A HOMOGENEOUS non-numeric, non-date group is refused too, and that is a **separate** measurement
 * rather than a consequence of the mixed one: the mixed group is refused for being heterogeneous, which is
 * silent about a group uniformly of one unsortable kind. Measured 2026-08-15 in both repetition shapes, with
 * the equivalent explicit field list drawing the same code — so the gate is about the KIND, not about
 * groupness, and this function cannot receive that shape either. What IS reachable and was missed is the MULTI-operand form: this function once took
 * `f.args.singleOrNull()`, so `MinValue(<date group>, <date field>)` fell through to `leafOmission` and answered
 * OMISSION where the kernel answers VALUE. It now walks every operand and ORs their missingness, which is the
 * same shape the single-operand case always had.
 */
private fun Interpreter.dateGroupFill(f: Expr.Func): Fill? {
    if (f.args.none { namesADateGroup(it) }) return null
    var om = false
    for (arg in f.args) {
        val c = ctxOf(arg)
        val path = when (arg) {
            is Expr.Star -> arg.path
            is Expr.Field -> arg.path
            else -> return null                              // an operand shape this fill cannot account for
        }
        om = om || if (c.declaredKind(path) == null) {
            val fields = c.containedFields(path)
            if (fields.isEmpty()) return null
            val star = arg as? Expr.Star
            val depth = expansionBindDepth(path, star?.bindDepth ?: -1)
            star?.having != null || fields.any { c.starOmission(it, depth) }
        } else {
            !c.filled(path)                                  // a plain date operand is missing iff empty
        }
    }
    return Fill.of(om, om)
}

/** Whether [arg] names a GROUP whose expansion is DATE-like — the operand shape [dateGroupFill] accounts for. */
private fun Interpreter.namesADateGroup(arg: Expr): Boolean {
    val path = when (arg) {
        is Expr.Star -> arg.path
        is Expr.Field -> arg.path
        else -> return false
    }
    val c = ctxOf(arg)
    return c.declaredKind(path) == null && dateLike(firstFilledEntityKind(c, path)) &&
        c.containedFields(path).isNotEmpty()
}


/** The pre-declaration-summary behavior retained for non-numeric/date/unsupported aggregate shapes. A
 *  DATE-like star has symmetric fill; a numeric single star keeps its former exhausted and directional cases. */
private fun Interpreter.legacyStarFill(f: Expr.Func, build: (signed: Boolean) -> Fill): Fill {
    dateGroupFill(f)?.let { return it }
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

/** Tolerance reuses numeric `!=` polarity: only movement of the smaller side upward or the larger side
 * downward can close a firing gap. Operand ordering uses the same scale-19 normalization as truth. */
private fun Interpreter.toleranceType(c: Cond.Tolerance): MsgType {
    val left = evalCmpOperand(c.left, c.right).numericOrNull() ?: return MsgType.VALUE
    val right = evalCmpOperand(c.right, c.left).numericOrNull() ?: return MsgType.VALUE
    return if (ToleranceOps.fillable(left, right, fill(c.left), fill(c.right))) {
        MsgType.OMISSION
    } else {
        MsgType.VALUE
    }
}

/**
 * The value-vs-omission polarity of a fired predicate. Non-quantifier predicates carry a static
 * [PredicateOp.polarity]; a field-list quantifier's polarity is **data-dependent** and computed the way
 * the kernel does — OMISSION iff filling some currently-empty operand could clear the firing (so the
 * error is fixable by filling), else VALUE. (E.g. `NotExactlyOneFieldFilled` is OMISSION in its
 * zero-filled/no-unavailable firing region but VALUE at two known-filled cells — KERNEL-FINDINGS §1.)
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
    // FieldValuesNotUnique escalates to OMISSION only when a filtered operand contributed a compared value BEFORE
    // the duplicate the scan returned at — POSITIONAL, so it cannot be read off the operand list and is decided in
    // the same ordered scan as the truth (SPEC-2026-08-05-01; `FieldValuesNotUniqueDiffTest`).
    if (op == PredicateOp.FIELD_VALUES_NOT_UNIQUE) return fieldValuesNotUnique(p.args).type
    op.polarity?.let { return it }
    // SPEC-2026-10-03-03: positive thresholds stop their polarity scan at the deciding witness.
    if (!op.countsGroups && p.args.any { it is Expr.Star && it.having != null }) return filteredFillType(op, p.args)
    if (!op.countsGroups && hasFillScopeOperand(p.args)) return fillScopeType(op, fillScopeInfo(p.args))
    val (t, f, n) = quantCounts(op, p.args)
    // OMISSION iff filling more content could clear the firing. For a STARRED group operand the fix is ADDING
    // a row — (t, f, n) → (t+1, f, n+1), n is not fixed — so `NoGroupFilled(G*)` at zero rows is OMISSION
    // (add a row) while `AtLeastOneGroupFilled(G*)` is VALUE (adding never clears it) (IF68). For a fixed-arity
    // quantifier each fill moves a FALSE operand to TRUE: (t, f) → (t+j, f-j). (UNKNOWN operands aren't
    // "fillable" — they need correcting, not filling — so they don't enter this.)
    //
    // The two moves are available INDEPENDENTLY, so a list that mixes them offers both: the add-a-row move
    // comes from ANY starred operand, not only a sole one. That mattered once the kernel's duplicate gate was
    // found to skip wildcarded operands (SPEC-2026-07-23-09), which makes `NoGroupFilled(G*, G*)` legal: at
    // zero rows every operand contributes nothing, so `f` is 0, the fixed-arity range `1..f` is empty, and
    // requiring a SOLE star typed the firing VALUE where the kernel says OMISSION.
    val fillable = if (op.countsGroups && p.args.any { it is Expr.Star || it.isIndexedStar() })
        !quantifierFires(op, t + 1, f, n + 1) || (1..f).any { j -> !quantifierFires(op, t + j, f - j, n) }
    else (1..f).any { j -> !quantifierFires(op, t + j, f - j, n) }
    return if (fillable) MsgType.OMISSION else MsgType.VALUE
}

private fun Interpreter.leafOmission(e: Expr): Boolean = when (e) {
    // Through `ctxOf`, like its `EnumCategory` sibling on the next line — which is how this one was found. A
    // `$`-marked node resolves in the OUTER context, so the ambient `ctx` answers about a different row
    // ([IF287]'s fourteenth and twenty-third instances, the same two-line pattern in two other functions).
    is Expr.Field -> !ctxOf(e).filled(e.path)
    is Expr.EnumCategory -> !ctxOf(e).filled(e.path)
    // A reached `Having`-FILTERED star escalates a fired consumer to TRUE_AF unconditionally — the kernel's
    // filtered-iterator escalation (IF101/IF104). FirstFilledValue delegates to its ordered scan so a filter
    // on an unvisited later slot is excluded (IF184). An ordinary unfiltered star keeps [EvalContext.starOmission].
    is Expr.Star -> e.having != null || ctx.starOmission(e.path, e.bindDepth)
    is Expr.EntitySpec -> if (e.isIndexedStar()) withIndexedStar(e, true, false) { leafOmission(it) } else false
    is Expr.Func -> if (FunctionOp.byName(e.name) == FunctionOp.FIRST_FILLED_VALUE)
        firstFilledScan(e).fill.let { it.canGrow || it.canShrink }
    else e.args.any { leafOmission(it) }
    is Expr.SemanticIndex -> if (isKeyedGroup(e)) keyedGroupOmission(e) else !semanticIndexFilled(e)
    else -> false
}

/** Whether filling can still add to a keyed GROUP operand of a count: the selected row's expansion read as the
 *  bare group's is (IF340), every field of an absent row, and nothing of an undecided one, which needs
 *  correcting rather than filling. */
private fun Interpreter.keyedGroupOmission(e: Expr.SemanticIndex): Boolean = when (val cells = keyedGroupCells(e)) {
    is KeyedGroupCells.Selected -> with(cells.interpreter) {
        expandGroupOperands(listOf(Expr.Field(e.path))).any { leafOmission(it) }
    }
    KeyedGroupCells.Empty -> true
    KeyedGroupCells.Undecided -> false
}

/** The message polarity of a fired group/starred-scope fill quantifier — the kernel's fixed WF/AF per op
 *  (`NoField`/`NotAll`/`FieldsNotCollectively` are omission errors; `NotExactlyOne` is omission in its
 *  zero-filled/no-unavailable region and value at ≥2 known-filled; the rest are value errors). IF49. */
private fun Interpreter.fillScopeType(op: PredicateOp, fs: FillScope): MsgType = when (op) {
    PredicateOp.NO_FIELD_FILLED, PredicateOp.NOT_ALL_FIELDS_FILLED,
    PredicateOp.FIELDS_NOT_COLLECTIVELY_FILLED -> MsgType.OMISSION
    PredicateOp.NOT_EXACTLY_ONE_FIELD_FILLED -> if (fs.tInst == 0) MsgType.OMISSION else MsgType.VALUE
    else -> MsgType.VALUE
}
