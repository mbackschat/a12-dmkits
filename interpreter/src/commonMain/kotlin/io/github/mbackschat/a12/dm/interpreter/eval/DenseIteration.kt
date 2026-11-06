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
