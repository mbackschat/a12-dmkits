package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.regex.JavaPatternToJs
import kotlin.js.RegExp

internal actual class CompiledKeyPattern(
    private val regex: RegExp,
    private val guards: List<Pair<Int, Int>>,
) {
    actual fun matches(value: String): Boolean {
        val match = regex.asDynamic().exec(value) ?: return false
        return guards.all { (capture, marker) -> match[marker] == null || match[capture] != null }
    }
}

internal actual fun compileKeyPattern(
    pattern: String,
    translation: JavaPatternToJs.Translation,
): CompiledKeyPattern? = try {
    CompiledKeyPattern(
        RegExp("^(?:" + translation.source + ")$", translation.flags),
        translation.backReferenceGuards.map { it.capture to it.marker },
    )
} catch (_: Throwable) {
    null
}
