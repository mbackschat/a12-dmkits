package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.regex.JavaPatternToJs
import kotlin.js.RegExp

internal actual class CompiledKeyPattern(private val regex: RegExp) {
    actual fun matches(value: String): Boolean = regex.test(value)
}

internal actual fun compileKeyPattern(
    pattern: String,
    translation: JavaPatternToJs.Translation,
): CompiledKeyPattern? = try {
    CompiledKeyPattern(
        RegExp("^(?:" + translation.source + ")$", translation.flags),
    )
} catch (_: Throwable) {
    null
}
