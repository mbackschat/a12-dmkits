package io.github.mbackschat.a12.dm.interpreter.regex

import io.github.mbackschat.a12.dm.regex.JavaPatternToJs
import kotlin.js.RegExp

/** JS actual. The post-match guards restore Java's “unmatched backreference fails” rule. */
actual class CompiledPattern(
    private val re: RegExp,
    private val guards: List<Pair<Int, Int>>,
) {
    actual fun matches(text: String): Boolean {
        val match = re.asDynamic().exec(text) ?: return false
        return guards.all { guard ->
            match[guard.second] == null || match[guard.first] != null
        }
    }
}

actual fun compilePattern(pattern: String): CompiledPattern {
    val translated = JavaPatternToJs.compile(pattern)
    return CompiledPattern(
        RegExp("^(?:" + translated.source + ")$", translated.flags),
        translated.backReferenceGuards.map { it.capture to it.marker },
    )
}
