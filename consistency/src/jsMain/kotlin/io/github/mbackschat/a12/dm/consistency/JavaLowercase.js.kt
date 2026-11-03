package io.github.mbackschat.a12.dm.consistency

internal actual fun String.javaLowercase(): String = asDynamic().toLowerCase().unsafeCast<String>()

internal actual fun String.javaEndsWithIgnoringCase(suffix: String): Boolean {
    if (length < suffix.length) return false
    val offset = length - suffix.length
    return suffix.indices.all { i ->
        val a = this[offset + i]
        val b = suffix[i]
        val upperA = a.simpleCase(upper = true)
        val upperB = b.simpleCase(upper = true)
        a == b || upperA == upperB || upperA.simpleCase(upper = false) == upperB.simpleCase(upper = false)
    }
}

/**
 * Java's `Character.toUpperCase`/`toLowerCase`: JavaScript's mapping where it yields one unit, else the char itself, as
 * Java's simple mapping does for `ß`. `İ` is the one char whose simple lower-case (`i`) differs from a one-unit reading
 * of its full mapping (`i̇`, two units), so it is spelled out.
 */
private fun Char.simpleCase(upper: Boolean): Char {
    if (!upper && this == 'İ') return 'i'
    val text = toString().asDynamic()
    val mapped = (if (upper) text.toUpperCase() else text.toLowerCase()).unsafeCast<String>()
    return mapped.singleOrNull() ?: this
}
