package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals

/** On the JVM a key has A12's Java type: a `BigDecimal` with its scale, a `Date` (KERNEL-FINDINGS KF683). */
class UnknownIndexKeyJvmTest {

    @Test
    fun aNumberKeyIsABigDecimalKeepingItsScale() {
        val pointer = A12PartiallyKnownMultiPointer.of("/Portfolio/Fees/Rate", listOf(1, A12PartiallyKnownMultiPointer.UNKNOWN, 1))
            .withUnknownIndexKey(UnknownIndexKey.Decimal("8.50"))

        assertEquals(BigDecimal("8.50"), pointer.semanticIndexValueForUnknownIndexGroup)
        assertEquals(2, (pointer.semanticIndexValueForUnknownIndexGroup as BigDecimal).scale())
    }

    /** A date key is a `java.util.Date` at the key's instant, and every read gets its own, since a `Date` is mutable. */
    @Test
    fun aDateKeyIsAFreshDateAtItsInstant() {
        val pointer = A12PartiallyKnownMultiPointer.of("/Portfolio/Shifts/Hours", listOf(1, A12PartiallyKnownMultiPointer.UNKNOWN, 1))
            .withUnknownIndexKey(UnknownIndexKey.Instant(1_767_308_400_000L))
        val first = pointer.semanticIndexValueForUnknownIndexGroup as Date

        assertEquals(1_767_308_400_000L, first.time)
        first.time = 0L
        assertEquals(1_767_308_400_000L, (pointer.semanticIndexValueForUnknownIndexGroup as Date).time)
    }
}
