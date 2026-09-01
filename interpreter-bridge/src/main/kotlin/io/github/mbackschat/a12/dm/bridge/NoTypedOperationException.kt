package io.github.mbackschat.a12.dm.bridge

/**
 * rulekit publishes no operation for this combination — so the rule is refused, but by the shape of the API
 * rather than by a check that runs.
 *
 * A great deal of rulekit's kernel knowledge is stated as a type split rather than as a runtime guard: a date
 * range offers `eq` and `ne` and no ordering, because ordering one is illegal; the extraction operators take a
 * `DateOperand` and a string ref cannot be passed. When a human authors through the API the compiler enforces
 * all of it. At an untyped AST boundary none of it is reachable — there is simply no method to call, and the
 * bridge learns the rule by failing to find one.
 *
 * Its own type because it is neither of the neighbouring answers. Counting it as a rulekit verdict would
 * credit a check that never ran, and counting it as an unmapped construct would hide that rulekit HAS the
 * rule and the case is one it would catch. Kept apart, it measures exactly how much of the kernel's rule set
 * is stated in a form a programmatic consumer cannot reach.
 */
internal open class NoTypedOperationException(message: String) : UnmappedConstructException(message) {

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
