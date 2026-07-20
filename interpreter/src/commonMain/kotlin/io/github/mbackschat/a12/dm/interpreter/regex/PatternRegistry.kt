package io.github.mbackschat.a12.dm.interpreter.regex

/**
 * One interpreter owner's distinct-pattern cache. Every model-static pattern locus resolves through this
 * registry, so equal sources compile once regardless of how many rules, fields, or predefined types use them.
 */
internal class PatternRegistry {
    private val compiled = mutableMapOf<String, CompiledPattern>()

    fun pattern(source: String): CompiledPattern =
        compiled.getOrPut(source) { compilePattern(source) }

    fun matches(source: String, value: String): Boolean =
        pattern(source).matches(value)

    val compilationCount: Int get() = compiled.size
}
