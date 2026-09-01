package io.github.mbackschat.a12.dm.bridge

/**
 * The interpreter's parser refused the condition TEXT — so rulekit never saw the rule at all.
 *
 * The third thing that is not a rulekit verdict, beside [OperandResolutionException] and
 * [UnmappedConstructException]. The parser raises a bare [IllegalArgumentException] from Kotlin's `require`,
 * with no type of its own; the only other way to recognise one is to match the `"parser: "` prefix its
 * messages happen to carry, which is a string dependency on a neighbouring module's wording. Catching at the
 * one call site that runs the parser makes it structural instead.
 *
 * Extends [IllegalArgumentException] so a caller that only cares the condition was refused is unaffected; a
 * caller that must know WHO refused it checks the type.
 */
internal class ConditionParseException(message: String?, cause: Throwable?) :
        IllegalArgumentException(message, cause) {

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
