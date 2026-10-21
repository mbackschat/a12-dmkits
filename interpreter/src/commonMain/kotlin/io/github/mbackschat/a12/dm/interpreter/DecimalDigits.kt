package io.github.mbackschat.a12.dm.interpreter

/**
 * The decimal digits A12's JVM runtime reads, on every platform this engine runs on.
 *
 * A12 converts number text with `java.math.BigDecimal`, which takes any UTF-16 unit `Character.digit` gives a
 * decimal value, so Arabic-Indic `٣` and fullwidth `５` are digits there (KF564). Kotlin's `Char.isDigit` answers
 * from `Character` on the JVM but from Kotlin/JS's own Unicode table in the browser, so a release skew between the
 * two would read one digit differently (IF415). A12's TypeScript runtime refuses these digits before converting
 * (KF575), so the two A12 runtimes disagree here and the dynamic-Groovy JVM reading is the normative one.
 *
 * [ZEROS] is every BMP unit JDK 21's `Character.digit(c, 10)` reads as 0; each is followed by its nine siblings, 370
 * digits in all (derived 2026-10-21 on JDK 21.0.12.1). `DecimalDigitsTest` holds the table to `Character` on the JVM.
 */
internal object DecimalDigits {
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

    fun isDigit(c: Char): Boolean = valueOf(c) >= 0
}
