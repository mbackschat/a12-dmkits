package io.github.mbackschat.a12.dm.consistency

internal actual fun String.javaLowercase(): String = asDynamic().toLowerCase().unsafeCast<String>()
