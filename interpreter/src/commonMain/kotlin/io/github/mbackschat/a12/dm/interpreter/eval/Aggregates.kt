package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.dec

/**
 * Pure aggregate **folds** over already-resolved [Val]s — the runtime-semantics layer for the kernel's value
 * aggregates (`Sum` / `MaxValue` / `MinValue` / `NumberOfDifferentValues`, and the operand-list `Min`/`Max`).
 * Strategy-agnostic: no AST, no `EvalContext`, no document walk — just `List<Val> -> Val`. A future GENERATOR
 * emits calls to these exactly as the interpreter's tree-walk does (the IF10 *runtime-semantics vs strategy*
 * split, the sibling of [ValCompare]/[DateMath]/the op registries).
 *
 * The split: operand RESOLUTION (the star walk, parallel pairing) and the **Unknown gate** (any operand
 * Unknown ⇒ Unknown) stay in the strategy (`Interpreter.starVals` / `combine`); these functions take the
 * resolved, **non-Unknown** value list and produce the aggregate value. The same fold serves both the starred
 * aggregate and its operand-list twin — only resolution differs (`Min` and `MinValue` share [min]).
 */
object Aggregates {

    /** Σ of [values] (each a [Val.Num]); the empty list folds to 0 (the kernel's empty-Sum identity). */
    fun sum(values: List<Val>): Val = Val.Num(values.fold(DEC_ZERO) { acc, v -> acc + numOf(v) })

    /** The maximum by the kernel's value order ([ValCompare.ORDER]) of the FILLED values; an empty list → `0`
     *  (the kernel's empty-aggregate identity — probed: `MaxValue()` over all-empty operands / no rows == 0, the
     *  same seed as `Sum`; an empty operand is IGNORED, not folded, so `MaxValue(-5, empty)` == -5, not 0 —
     *  KERNEL-FINDINGS). Distinct from a malformed operand (→ [Val.Unknown] via the strategy's [Interpreter.combine]). */
    fun max(values: List<Val>): Val = values.maxWithOrNull(ValCompare.ORDER) ?: Val.Num(DEC_ZERO)

    /** The minimum by [ValCompare.ORDER] of the FILLED values; an empty list → `0` (see [max]). */
    fun min(values: List<Val>): Val = values.minWithOrNull(ValCompare.ORDER) ?: Val.Num(DEC_ZERO)

    /**
     * Count of DISTINCT values by the kernel's typed value equality — NUMBERS scale-insensitively (`49.90` ≡
     * `49.9`), STRINGS/BOOLS by themselves. The kernel explicitly permits non-numeric operands here
     * (`CountAggregate.checkValueComparable`), so this counts by a canonical TYPED key (not `numOf`, which
     * crashed on a STRING/ENUM value — IF51). O(n) via a HashSet of the keys; the key mirrors the
     * interpreter's `fieldValuesNotUnique` keying (`"N"+scale19` / `"S"+str` / `"B"+bool`) so distinctness and
     * duplicate-detection agree, and the type prefix prevents cross-type collision.
     */
    fun numberOfDifferentValues(values: List<Val>): Val {
        val seen = HashSet<String>(values.size * 2)
        for (v in values) seen.add(distinctKey(v))
        return Val.Num(dec(seen.size))
    }

    /** The canonical distinct-key of a resolved value (see [numberOfDifferentValues]). */
    private fun distinctKey(v: Val): String = when (v) {
        is Val.Num -> "N" + v.v.scale19().display()
        is Val.Txt -> "S" + v.v
        is Val.Bool -> "B" + v.v
        else -> error("NumberOfDifferentValues over a non-comparable value: $v")
    }

    /**
     * `SumOfProducts` — Σ aᵢ·bᵢ over two row-aligned value lists (the [Val.Num] products). Any Unknown cell in
     * EITHER list makes the whole sum non-evaluable → [Val.Unknown] (the formal-suppression mechanism). The lists
     * are equal-length + row-aligned by construction (both stars resolve over the SAME repeatable group); the
     * strategy does that resolution and passes the two lists. An empty cell arrives as the substituted 0, so its
     * product is 0.
     */
    fun sumOfProducts(a: List<Val>, b: List<Val>): Val {
        if (a.any { it is Val.Unknown } || b.any { it is Val.Unknown }) return Val.Unknown
        var sum = DEC_ZERO
        for (i in a.indices) sum += numOf(a[i]) * numOf(b[i])
        return Val.Num(sum)
    }

    /** Unwrap a resolved [Val.Num] to its [Dec] — the values reaching a numeric fold are Num by construction
     *  (the Unknown gate ran in the strategy, and a non-numeric operand is a model/type error upstream). */
    private fun numOf(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")
}
