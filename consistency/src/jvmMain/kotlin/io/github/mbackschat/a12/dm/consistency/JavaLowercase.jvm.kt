package io.github.mbackschat.a12.dm.consistency

/** Kotlin's JVM `lowercase` is `toLowerCase(Locale.ROOT)` itself. */
internal actual fun String.javaLowercase(): String = lowercase()

/** Kotlin's JVM `regionMatches` with `ignoreCase` is `java.lang.String.regionMatches(true, …)` itself. */
internal actual fun String.javaEndsWithIgnoringCase(suffix: String): Boolean =
    length >= suffix.length && regionMatches(length - suffix.length, suffix, 0, suffix.length, ignoreCase = true)
