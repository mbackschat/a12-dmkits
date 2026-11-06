package io.github.mbackschat.a12.dm.interpreter.eval

/** The kernel's dense-iteration threshold on the product of declared capacities (`MAX_PRUEF_KONTEXT`). */
internal const val DENSE_PRODUCT_LIMIT = 5L

/**
 * Whether [capacities] multiply to at most [DENSE_PRODUCT_LIMIT], read only until the product passes it: at or below
 * it a validation visits every declared slot, above it only the occupied ones ([Document.fullValidationContextsFor]).
 */
internal fun withinDenseLimit(capacities: Sequence<Int>): Boolean {
    var product = 1L
    for (capacity in capacities) {
        product *= capacity
        if (product > DENSE_PRODUCT_LIMIT) return false
    }
    return true
}

/**
 * [indices] as a sorted array, the occupied child repetitions a sparse walk visits. Filled by hand because the
 * stdlib's `toIntArray` meets a Set here and Lists everywhere else, and V8 discards its optimized copy on the second
 * receiver shape: a 2.5 ms bailout inside the browser first answer (`--trace-deopt`, 2026-11-06).
 */
internal fun sortedIndices(indices: Set<Int>): IntArray {
    val sorted = IntArray(indices.size)
    var at = 0
    for (index in indices) sorted[at++] = index
    return sorted.apply { sort() }
}
