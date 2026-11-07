package io.github.mbackschat.a12.dm.interpreter

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** On the JVM a number key is the `BigDecimal` A12 keeps, scale included (KERNEL-FINDINGS KF683). */
class UnknownIndexKeyJvmTest {

    @Test
    fun aNumberKeyIsABigDecimalKeepingItsScale() {
        val pointer = A12PartiallyKnownMultiPointer.of("/Portfolio/Fees/Rate", listOf(1, A12PartiallyKnownMultiPointer.UNKNOWN, 1))
            .withUnknownIndexKey(UnknownIndexKey.Decimal("8.50"))

        assertEquals(BigDecimal("8.50"), pointer.semanticIndexValueForUnknownIndexGroup)
        assertEquals(2, (pointer.semanticIndexValueForUnknownIndexGroup as BigDecimal).scale())
    }
}
