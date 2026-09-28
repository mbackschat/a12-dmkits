package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.regex.JavaPatternToJs
import io.github.mbackschat.a12.dm.predicates.kernelPatternBlacklist

/** A whole-value Java pattern whose translation is represented on both checker targets. */
internal class KeyPattern private constructor(private val compiled: CompiledKeyPattern) {
    fun matches(value: String): Boolean = compiled.matches(value)

    companion object {
        fun parse(pattern: String): KeyPattern? = try {
            if (kernelPatternBlacklist(pattern) != null) return null
            val translation = JavaPatternToJs.compile(pattern)
            if (translation.hasUnverifiedMapping) return null
            compileKeyPattern(pattern, translation)?.let(::KeyPattern)
        } catch (_: JavaPatternToJs.UnsupportedJavaPattern) {
            null
        }
    }
}

internal expect class CompiledKeyPattern {
    fun matches(value: String): Boolean
}

internal expect fun compileKeyPattern(
    pattern: String,
    translation: JavaPatternToJs.Translation,
): CompiledKeyPattern?
