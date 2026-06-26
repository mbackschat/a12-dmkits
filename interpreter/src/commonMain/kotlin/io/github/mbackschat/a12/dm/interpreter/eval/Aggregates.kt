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

    /** The maximum by the kernel's value order ([ValCompare.ORDER]); empty list → [Val.Unknown]. */
    fun max(values: List<Val>): Val = values.maxWithOrNull(ValCompare.ORDER) ?: Val.Unknown

    /** The minimum by [ValCompare.ORDER]; empty list → [Val.Unknown]. */
    fun min(values: List<Val>): Val = values.minWithOrNull(ValCompare.ORDER) ?: Val.Unknown

    /**
     * Count of DISTINCT values by **numeric** equality (scale-insensitive `compareTo`, so `49.90` ≡ `49.9`).
     * O(n log n) via sort — equal values group adjacently, so a run boundary (`compareTo != prev`) marks a new
     * distinct value. (The prior "linear scan of the distinct-so-far per element" was O(n²) — a latent cliff on
     * a large value list, IF; the sort fix keeps it sub-quadratic.)
     */
    fun numberOfDifferentValues(values: List<Val>): Val {
        val nums = values.map { numOf(it) }.sortedWith { a, b -> a.compareTo(b) }
        var count = 0
        for (i in nums.indices) if (i == 0 || nums[i].compareTo(nums[i - 1]) != 0) count++
        return Val.Num(dec(count))
    }

    /** Unwrap a resolved [Val.Num] to its [Dec] — the values reaching a numeric fold are Num by construction
     *  (the Unknown gate ran in the strategy, and a non-numeric operand is a model/type error upstream). */
    private fun numOf(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")
}
