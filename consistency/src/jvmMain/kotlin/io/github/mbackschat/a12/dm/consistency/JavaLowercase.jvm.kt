package io.github.mbackschat.a12.dm.consistency

/** Kotlin's JVM `lowercase` is `toLowerCase(Locale.ROOT)` itself. */
internal actual fun String.javaLowercase(): String = lowercase()
