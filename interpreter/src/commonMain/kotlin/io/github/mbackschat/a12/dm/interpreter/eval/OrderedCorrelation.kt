package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * Ordered, prefix-summed tallies for one correlation field inside one repetition scope. Construction is
 * O(rows log rows), once per immutable document/scope; each outer-row `<`/`<=`/`>`/`>=`/`==`/`!=` query is
 * O(log distinct-values). Unknown comparison values and all-empty, non-evaluated rows are absent, exactly as a
 * `Having` comparison that evaluates to UNKNOWN or is row-gated would be.
 */
class OrderedCorrelation private constructor(
    private val values: List<Val>,
    private val truePrefix: IntArray,
    private val falsePrefix: IntArray,
    private val rowPrefix: IntArray,
) {

    internal fun tally(op: CmpOp, outer: Val): Triple<Int, Int, Int> {
        val lower = lowerBound(outer)
        val upper = upperBound(outer)
        val end = values.size
        return when (op) {
            CmpOp.LT -> between(0, lower)
            CmpOp.LE -> between(0, upper)
            CmpOp.GT -> between(upper, end)
            CmpOp.GE -> between(lower, end)
            CmpOp.EQ -> between(lower, upper)
            CmpOp.NE -> add(between(0, lower), between(upper, end))
        }
    }

    private fun lowerBound(target: Val): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ValCompare.language(values[mid], target) < 0) low = mid + 1 else high = mid
        }
        return low
    }

    private fun upperBound(target: Val): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (ValCompare.language(values[mid], target) <= 0) low = mid + 1 else high = mid
        }
        return low
    }

    private fun between(from: Int, to: Int): Triple<Int, Int, Int> = Triple(
        truePrefix[to] - truePrefix[from],
        falsePrefix[to] - falsePrefix[from],
        rowPrefix[to] - rowPrefix[from],
    )

    companion object {
        internal fun build(rows: List<EvalContext>, innerField: String, starPath: String): OrderedCorrelation {
            val entries = rows.mapNotNull { row ->
                if (!row.evaluated) return@mapNotNull null
                val value = row.field(innerField)
                if (value is Val.Unknown) return@mapNotNull null
                Entry(value, cellTally(row, starPath))
            }.sortedWith { left, right -> ValCompare.language(left.value, right.value) }

            val values = ArrayList<Val>(entries.size)
            val tallies = ArrayList<Triple<Int, Int, Int>>(entries.size)
            entries.forEach { entry ->
                val last = values.lastIndex
                if (last >= 0 && ValCompare.language(values[last], entry.value) == 0) {
                    tallies[last] = add(tallies[last], entry.tally)
                } else {
                    values += entry.value
                    tallies += entry.tally
                }
            }

            val truePrefix = IntArray(values.size + 1)
            val falsePrefix = IntArray(values.size + 1)
            val rowPrefix = IntArray(values.size + 1)
            tallies.forEachIndexed { index, tally ->
                truePrefix[index + 1] = truePrefix[index] + tally.first
                falsePrefix[index + 1] = falsePrefix[index] + tally.second
                rowPrefix[index + 1] = rowPrefix[index] + tally.third
            }
            return OrderedCorrelation(values, truePrefix, falsePrefix, rowPrefix)
        }

        private fun cellTally(row: EvalContext, starPath: String): Triple<Int, Int, Int> = when {
            row.presenceUnknown(starPath) -> Triple(0, 0, 1)
            !row.filled(starPath) -> Triple(0, 1, 1)
            row.field(starPath) is Val.Unknown -> Triple(0, 0, 1)
            else -> Triple(1, 0, 1)
        }

        private fun add(left: Triple<Int, Int, Int>, right: Triple<Int, Int, Int>): Triple<Int, Int, Int> =
            Triple(left.first + right.first, left.second + right.second, left.third + right.third)
    }

    private data class Entry(val value: Val, val tally: Triple<Int, Int, Int>)
}

/** The normalized inner-to-outer comparison used for self-exclusion subtraction. */
internal fun orderedComparisonMatches(op: CmpOp, inner: Val, outer: Val): Boolean = when (op) {
    CmpOp.LT -> ValCompare.language(inner, outer) < 0
    CmpOp.LE -> ValCompare.language(inner, outer) <= 0
    CmpOp.GT -> ValCompare.language(inner, outer) > 0
    CmpOp.GE -> ValCompare.language(inner, outer) >= 0
    CmpOp.EQ -> ValCompare.equal(inner, outer)
    CmpOp.NE -> !ValCompare.equal(inner, outer)
}
