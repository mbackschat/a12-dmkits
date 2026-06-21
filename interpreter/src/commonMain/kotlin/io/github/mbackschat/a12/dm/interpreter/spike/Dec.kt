package io.github.mbackschat.a12.dm.interpreter.spike

/**
 * The interpreter's decimal — the ONE platform-specific primitive (proposal §5's "thin shim"). All the
 * rest of the engine is common code. The JVM actual wraps java.math.BigDecimal (full precision — what
 * the kernel-differential tests ride); the JS actual wraps Double for this POC (exact for the
 * integer/simple-decimal fixtures). Production swaps the JS actual for big.js or a multiplatform bignum
 * — and that swap is localized to exactly this one file.
 */
expect class Dec : Comparable<Dec> {
    operator fun plus(other: Dec): Dec
    override fun compareTo(other: Dec): Int
}

expect fun dec(value: String): Dec
expect fun dec(value: Int): Dec
expect val DEC_ZERO: Dec
