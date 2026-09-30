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

    /** Σ of [values] (each a [Val.Num]) as a zero-seeded, encounter-ordered left fold. Each [Dec.plus]
     *  rounds immediately at precision 50 HALF_UP, so reordering can change the result; empty folds to 0. */
    fun sum(values: List<Val>): Val = Val.Num(values.fold(DEC_ZERO) { acc, v -> acc + numOf(v) })

    /** The maximum by the kernel's value order ([ValCompare.ORDER]) of the FILLED values; an empty list folds
     *  to the [empty] identity, which is KIND-dependent because the kernel has two combiner families: the
     *  number combiner seeds `0` (probed: `MaxValue()` over all-empty operands / no rows == 0, the same seed
     *  as `Sum`; an empty operand is IGNORED, not folded, so `MaxValue(-5, empty)` == -5, not 0 —
     *  KERNEL-FINDINGS), while the DATE combiner seeds a NULL date — no value, so a comparison against it
     *  does not fire → [Val.Unknown] (`DateAggregateDiffTest`). The strategy resolves the operand kind and
     *  passes the identity, mirroring the kernel's static `minWert` vs `minWertDatum` dispatch. Distinct from
     *  a malformed operand (→ [Val.Unknown] via the strategy's [Interpreter.combine]). */
    fun max(values: List<Val>, empty: Val = Val.Num(DEC_ZERO)): Val = values.maxWithOrNull(ValCompare.ORDER) ?: empty

    /** The minimum by [ValCompare.ORDER] of the FILLED values; an empty list → the kind's [empty] identity (see [max]). */
    fun min(values: List<Val>, empty: Val = Val.Num(DEC_ZERO)): Val = values.minWithOrNull(ValCompare.ORDER) ?: empty

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
        for (v in values) seen.add(distinctKeyOrNull(v)
            ?: error("NumberOfDifferentValues over a non-comparable value: $v"))
        return Val.Num(dec(seen.size))
    }

    /**
     * The canonical typed distinct-/equality-key of a resolved value, or null for a non-comparable one — NUMBERS
     * by their scale-19 form (scale-insensitive, so `49.90` ≡ `49.9`), STRINGS/BOOLS by themselves, each with a
     * type prefix so no cross-type collision. The SINGLE source shared by [numberOfDifferentValues] and the
     * interpreter's `FieldValuesNotUnique` duplicate detection — both must key identically so distinctness and
     * duplicate-detection agree, and both are kernel-value-equality-consistent (a [Val.Range] never compares
     * equal, so it has no key → null; the count aggregate treats that as a model error, the duplicate scan skips it).
     */
    fun distinctKeyOrNull(v: Val): String? = when (v) {
        is Val.Num -> "N" + v.v.scale19().display()
        is Val.Txt -> "S" + v.v
        is Val.Bool -> "B" + v.v
        else -> null
    }

    /**
     * `SumOfProducts` — Σ aᵢ·bᵢ over two row-aligned value lists (the [Val.Num] products). Any Unknown cell in
     * EITHER list makes the whole sum non-evaluable → [Val.Unknown] (the formal-suppression mechanism). The lists
     * can have different lengths when their semantic keys select different parents. Only paired positions
     * contribute, but an invalid unpaired tail still suppresses the result (KeyedGroupFieldListDiffTest).
     * An empty cell arrives as the substituted 0, so its product is 0.
     */
    fun sumOfProducts(a: List<Val>, b: List<Val>): Val {
        if (a.any { it is Val.Unknown } || b.any { it is Val.Unknown }) return Val.Unknown
        var sum = DEC_ZERO
        for (i in 0 until minOf(a.size, b.size)) sum += numOf(a[i]) * numOf(b[i])
        return Val.Num(sum)
    }

    /** Unwrap a resolved [Val.Num] to its [Dec] — the values reaching a numeric fold are Num by construction
     *  (the Unknown gate ran in the strategy, and a non-numeric operand is a model/type error upstream). */
    private fun numOf(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")
}
