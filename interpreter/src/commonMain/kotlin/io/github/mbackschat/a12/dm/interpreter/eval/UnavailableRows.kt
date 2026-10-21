package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * A correlated scope's rows holding an unavailable empty cell in the starred column or a join field, with their own
 * index, built the way the scope's index is, so a tally can take exactly these rows out of it (INTERPRETER-FINDINGS
 * IF423). One instance serves one plan of one scope, so each index is built once.
 */
internal class UnavailableRows(val rows: List<EvalContext>) {
    private var buckets: Map<List<Val>, IntArray>? = null
    private var ordered: OrderedCorrelation? = null

    fun buckets(innerFields: List<String>, starPath: String): Map<List<Val>, IntArray> =
        buckets ?: correlationBucketsOf(rows, innerFields, starPath).also { buckets = it }

    fun ordered(innerField: String, starPath: String): OrderedCorrelation =
        ordered ?: OrderedCorrelation.build(rows, innerField, starPath).also { ordered = it }

    companion object {
        val NONE = UnavailableRows(emptyList())
    }
}

/** A bucket's `(t, f, n)`, the empty tally when no row carries the key. */
internal fun IntArray?.toTally(): Triple<Int, Int, Int> = if (this == null) Triple(0, 0, 0) else Triple(this[0], this[1], this[2])

internal operator fun Triple<Int, Int, Int>.plus(other: Triple<Int, Int, Int>) =
    Triple(first + other.first, second + other.second, third + other.third)

internal operator fun Triple<Int, Int, Int>.minus(other: Triple<Int, Int, Int>) =
    Triple(first - other.first, second - other.second, third - other.third)
