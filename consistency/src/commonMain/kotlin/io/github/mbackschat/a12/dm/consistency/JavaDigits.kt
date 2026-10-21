package io.github.mbackschat.a12.dm.consistency

/**
 * The decimal digits Java's `Character.digit(c, 10)` reads, for the members A12 binds through `Integer.parseInt` or
 * `Long.parseLong`: `"\u0663"` is 3 there, and Kotlin's own parse reads digits from a different table in Node.
 *
 * The interpreter carries the same table as `DecimalDigits`; its dependency gate admits no project edge, so the two
 * cannot share one source. `JavaDigitsJvmTest` holds this copy to the JDK over the whole BMP.
 */
internal object JavaDigits {
    private val ZEROS = charArrayOf(
        '0', '٠', '۰', '߀', '०', '০', '੦', '૦', '୦', '௦',
        '౦', '೦', '൦', '෦', '๐', '໐', '༠', '၀', '႐', '០',
        '᠐', '᥆', '᧐', '᪀', '᪐', '᭐', '᮰', '᱀', '᱐', '꘠',
        '꣐', '꤀', '꧐', '꧰', '꩐', '꯰', '０',
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
