package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Java `BigDecimal.intValue()` semantics, computed **lexically** from a plain decimal rendering — the
 * conversion every Date/DateTime `Add*` amount goes through in the kernel runtime
 * (`BedingungsOperatorHelper.addTime` → `VkBigDecimal.intValue`, which delegates straight to
 * `java.math.BigDecimal.intValue()`).
 *
 * Two stages, in this order (SPEC-2026-07-27-04):
 *  1. **truncate toward zero** — the fractional digits are discarded, never rounded (`1.9` → `1`,
 *     `-1.9` → `-1`), exactly `BigDecimal.toBigInteger()`;
 *  2. **retain the low signed 32 bits** — an out-of-range amount is neither rejected nor saturated
 *     (`2147483648` → `-2147483648`, `-2147483649` → `2147483647`), exactly `BigInteger.intValue()`.
 *
 * The implementation is deliberately **lexical**, not numeric: it must be one platform-independent
 * conversion shared by JVM and Kotlin/JS rather than each host's own decimal-to-int narrowing (big.js has
 * no two's-complement narrowing at all, and a `Long` round-trip would lose the arbitrarily large amounts
 * the kernel still narrows). `A12ShiftAmountNarrowingTest` oracles it against `java.math.BigDecimal.intValue`
 * itself, so the claim is measured rather than derived from prose.
 *
 * [plain] is a plain (never scientific) decimal rendering — what `Dec.display()` produces. A value that is
 * not one returns null; the caller keeps its own no-amount handling for that.
 */
internal fun plainDecimalAsJavaInt(plain: String): Int? {
    if (plain.isEmpty()) return null
    val negative = plain[0] == '-'
    val digitsFrom = if (negative || plain[0] == '+') 1 else 0
    if (digitsFrom == plain.length) return null
    // Low 32 bits of the integer part's MAGNITUDE. Masking each step keeps the accumulator bounded, so an
    // amount of any length narrows without an intermediate overflow — the kernel narrows those too.
    var low32 = 0L
    var sawDigit = false
    for (i in digitsFrom until plain.length) {
        val c = plain[i]
        if (c == '.') break                                   // truncation toward zero: stop at the point
        if (c !in '0'..'9') return null
        sawDigit = true
        low32 = (low32 * 10L + (c - '0')) and 0xFFFF_FFFFL
    }
    if (!sawDigit) return null
    // `Long.toInt()` keeps the low 32 bits, which IS the two's-complement narrowing for either sign.
    return (if (negative) -low32 else low32).toInt()
}
