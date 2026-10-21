package io.github.mbackschat.a12.dm.consistency

/**
 * The decimal digits Java's `Character.digit(c, 10)` reads, for the members A12 binds through `Integer.parseInt`:
 * `"\u0663"` is 3 there, and Kotlin's own parse reads digits from a different table in Node.
 *
 * The interpreter carries the same table as `DecimalDigits`; its dependency gate admits no project edge, so the two
 * cannot share one source. `JavaDigitsJvmTest` holds this copy to the JDK over the whole BMP.
 */
internal object JavaDigits {
    private val ZEROS = charArrayOf(
        '0', '\u0660', '\u06F0', '\u07C0', '\u0966', '\u09E6', '\u0A66', '\u0AE6', '\u0B66', '\u0BE6',
        '\u0C66', '\u0CE6', '\u0D66', '\u0DE6', '\u0E50', '\u0ED0', '\u0F20', '\u1040', '\u1090', '\u17E0',
        '\u1810', '\u1946', '\u19D0', '\u1A80', '\u1A90', '\u1B50', '\u1BB0', '\u1C40', '\u1C50', '\uA620',
        '\uA8D0', '\uA900', '\uA9D0', '\uA9F0', '\uAA50', '\uABF0', '\uFF10',
    )

    /** [c]'s decimal value, or -1 when A12's runtime reads no digit there. ASCII answers before any table read. */
    fun valueOf(c: Char): Int {
        if (c in '0'..'9') return c - '0'
        if (c < ZEROS[1]) return -1
        var low = 1
        var high = ZEROS.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val zero = ZEROS[mid]
            when {
                c < zero -> high = mid - 1
                c > zero + 9 -> low = mid + 1
                else -> return c - zero
            }
        }
        return -1
    }
}

/**
 * Java's `Long.parseLong`: an optional `+` or `-`, then at least one digit as [JavaDigits] reads it, within `Long`'s
 * range; null wherever Java throws.
 */
internal fun String.javaLongOrNull(): Long? {
    val negative = startsWith('-')
    val start = if (negative || startsWith('+')) 1 else 0
    if (start == length) return null
    var value = 0L
    for (i in start until length) {
        val digit = JavaDigits.valueOf(this[i])
        if (digit < 0) return null
        // Accumulate negatively, so Long.MIN_VALUE's magnitude fits.
        if (value < (Long.MIN_VALUE + digit) / 10) return null
        value = value * 10 - digit
    }
    return if (negative) value else if (value == Long.MIN_VALUE) null else -value
}

/** Java's `Integer.parseInt`, which reads every digit [JavaDigits] does. */
internal fun String.javaIntOrNull(): Int? = javaLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
