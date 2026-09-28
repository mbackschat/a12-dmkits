package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.regex.JavaPatternToJs
import io.github.mbackschat.a12.dm.predicates.kernelPatternBlacklist

/** A whole-value Java pattern whose translation is represented on both checker targets. */
internal class KeyPattern private constructor(
    private val compiled: CompiledKeyPattern,
    private val valueDomain: JavaPatternToJs.ValueDomain,
) {
    fun matches(value: String): Boolean? {
        val withinDomain = when (valueDomain) {
            JavaPatternToJs.ValueDomain.ANY -> true
            JavaPatternToJs.ValueDomain.LATIN1 -> value.all { it.code <= 0xff }
            JavaPatternToJs.ValueDomain.ASCII -> value.all { it.code <= 0x7f }
            JavaPatternToJs.ValueDomain.PRINTABLE_ASCII -> value.all { it.code in 0x20..0x7e }
        }
        return if (withinDomain) compiled.matches(value) else null
    }

    companion object {
        fun parse(pattern: String): KeyPattern? = try {
            if (kernelPatternBlacklist(pattern) != null) return null
            val translation = JavaPatternToJs.compileForConsistency(pattern)
            if (translation.hasUnverifiedMapping) return null
            // DG102-D excludes stateful captures: a JS post-match guard cannot retry Java's other alternatives.
            if (translation.backReferenceGuards.isNotEmpty()) return null
            val checkerTranslation = if (translation.globalUnicodeCase) translation.copy(flags = "iu") else translation
            compileKeyPattern(pattern, checkerTranslation)?.let { KeyPattern(it, translation.certifiedValueDomain) }
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
