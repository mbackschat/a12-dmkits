package io.github.mbackschat.a12.dm.interpreter.regex

import java.util.regex.Pattern

/** JVM actual — `java.util.regex.Pattern` on the ORIGINAL Java regex (the dynamic-Groovy/kernel-Java engine), a
 *  full-region `matches()`. No translation: the JVM already carries the normative semantics. */
actual class CompiledPattern(private val pattern: Pattern) {
    actual fun matches(text: String): Boolean = pattern.matcher(text).matches()
}

actual fun compilePattern(pattern: String): CompiledPattern = CompiledPattern(Pattern.compile(pattern))
