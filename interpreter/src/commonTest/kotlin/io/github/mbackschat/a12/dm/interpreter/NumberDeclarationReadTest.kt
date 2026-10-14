package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.FractionalPair
import io.github.mbackschat.a12.dm.interpreter.model.printedNumberBound
import io.github.mbackschat.a12.dm.interpreter.model.resolveFractionalPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The runtime reading of a Number declaration and of a quoted Number, on every platform the interpreter ships
 * (KF564). The Kernel oracle for the same rows is `adapter.laws.NumberDeclarationIngressDiffTest`; this class
 * keeps the JS target honest, whose `Double.toString` and digit tests are separate implementations.
 */
class NumberDeclarationReadTest {

    @Test
    fun theFractionalSentinelResolvesIntoAnOrdinaryPairExceptBesideAZeroMaximum() {
        assertEquals(FractionalPair(0, 2, checked = true), resolveFractionalPair(-1, 2))
        assertEquals(FractionalPair(2, 2, checked = true), resolveFractionalPair(2, -1))
        assertEquals(FractionalPair(null, 0, checked = true), resolveFractionalPair(null, -1))
        assertEquals(FractionalPair(0, 0, checked = false), resolveFractionalPair(-1, 0))
        assertEquals(FractionalPair(1, 3, checked = true), resolveFractionalPair(1, 3))
    }

    @Test
    fun aBoundIsPrintedFromItsNearestDouble() {
        assertEquals("5.56", printedNumberBound("5.555", 2, 2))
        assertEquals("-5.56", printedNumberBound("-5.555", 2, 2))
        assertEquals("0.13", printedNumberBound("0.125", 2, 2))
        assertEquals("13", printedNumberBound("12.5", null, 0))
        assertEquals("1", printedNumberBound("1.0", null, 2))
        assertEquals("1", printedNumberBound("1.00000000000000001", null, 2))
        assertEquals("100000000000000000000", printedNumberBound("1e20", 0, 3))
        assertEquals("5.5", printedNumberBound(" 5.5 ", null, 2))
        // A bound that is no finite number stays as written, and the value check then has none to read.
        assertEquals("abc", printedNumberBound("abc", null, 2))
        assertEquals("1e400", printedNumberBound("1e400", null, 2))
        assertNull(printedNumberBound(null, null, 2))
    }

    @Test
    fun aQuotedNumberConvertsNonAsciiDecimalDigitsButNotSupplementaryOnes() {
        assertEquals("3.5", A12DocumentJson.canonicalNumberOrNull("٣.٥"))
        assertEquals("5", A12DocumentJson.canonicalNumberOrNull("５"))
        assertEquals("3", A12DocumentJson.canonicalNumberOrNull("٠٣"))
        assertNull(A12DocumentJson.canonicalNumberOrNull("𝟏"))
    }
}
