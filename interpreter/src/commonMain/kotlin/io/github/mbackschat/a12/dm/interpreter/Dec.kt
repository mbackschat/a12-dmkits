package io.github.mbackschat.a12.dm.interpreter

/**
 * The interpreter's decimal — the ONE platform-specific primitive (proposal §5's "thin shim"). All the
 * rest of the engine is common code. The JVM actual wraps java.math.BigDecimal and the JS actual wraps
 * **big.js**, so both carry the same arbitrary-precision arithmetic (`0.1 + 0.2 == 0.3` exactly on each).
 * Any swap is localized to exactly the two actual files.
 */
expect class Dec : Comparable<Dec> {
    operator fun plus(other: Dec): Dec
    override fun compareTo(other: Dec): Int

    /** Canonical numeric rendering, trailing zeros stripped (`49.90` → `49.9`, `0` → `0`) — for computed values. */
    fun display(): String
}

expect fun dec(value: String): Dec
expect fun dec(value: Int): Dec
expect val DEC_ZERO: Dec
