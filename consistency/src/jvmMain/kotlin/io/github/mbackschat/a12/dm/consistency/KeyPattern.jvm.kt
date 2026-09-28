package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.regex.JavaPatternToJs
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

internal actual class CompiledKeyPattern(private val pattern: Pattern) {
    actual fun matches(value: String): Boolean = pattern.matcher(value).matches()
}

internal actual fun compileKeyPattern(
    pattern: String,
    translation: JavaPatternToJs.Translation,
): CompiledKeyPattern? = try {
    CompiledKeyPattern(Pattern.compile(pattern))
} catch (_: PatternSyntaxException) {
    null
}
