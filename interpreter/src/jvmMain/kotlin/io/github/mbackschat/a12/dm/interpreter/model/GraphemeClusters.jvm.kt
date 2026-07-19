package io.github.mbackschat.a12.dm.interpreter.model

import java.util.regex.Pattern

private val EXTENDED_GRAPHEME = Pattern.compile("\\X")

internal actual fun graphemeClustersOf(value: String): List<String> {
    val matcher = EXTENDED_GRAPHEME.matcher(value)
    return buildList {
        while (matcher.find()) add(matcher.group())
    }
}
