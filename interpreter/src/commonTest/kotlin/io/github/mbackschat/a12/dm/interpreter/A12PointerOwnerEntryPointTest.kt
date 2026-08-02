package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * **Each A12 entry point has its OWN domain, and A12 demonstrably does not give them one shared name grammar.**
 *
 * This is the correction to a wrong premise. A previous revision put `[\w_-]+` behind every entry point of both
 * pointer types on the theory that one type has one domain. Measured against kernel 30.8.1's factories, that is
 * false in both directions:
 *
 *  - `PathPartImpl` enforces a **non-empty** name and `repetitionIndex >= 0` — nothing else. `PathPart.of("a/b", 1)`
 *    is accepted and survives into `DocumentPointer.fullName()` as `/a/b`.
 *  - `DocumentPointerImpl`'s **string parser** owns `([\w_-]+)(\[(\d+)?\])?`, so `DocumentPointer.of("Order$")`
 *    is refused while `DocumentPointer.of(List.of(PathPart.of("Order$", 1)))` is accepted.
 *  - `PartiallyKnownDocumentMultiPointer.of(fullName, repetitions)` validates neither: it splits and normalizes,
 *    so `"/Order//Items"` with three repetitions is a legal value whose middle name is the empty string.
 *
 * So the grammar is the **string codec's** precondition, not the value's. `adapter … laws/A12PointerKernelDomainDiffTest`
 * decides each of these against its own owner entry point; this file is the interpreter-side statement of the
 * same split.
 */
class A12PointerOwnerEntryPointTest {

    // ---- A12PathPart == PathPartImpl: non-empty name, index >= 0, and nothing else ---------------

    @Test
    fun aPathPartAcceptsEveryNameTheKernelsPartAccepts() {
        for (name in listOf("Order\$", "Order Item", "Order[2]", "a/b", "Ümlaut", "x")) {
            assertEquals(name, A12PathPart(name, 1).name, "PathPartImpl accepts '$name'")
        }
    }

    @Test
    fun aPathPartStillRefusesWhatPathPartImplRefuses() {
        assertFailsWith<IllegalArgumentException> { A12PathPart("", 1) }
        assertFailsWith<IllegalArgumentException> { A12PathPart("Order", -1) }
        assertFailsWith<IllegalArgumentException> { A12PathPart("Order", -5) }
    }

    // ---- A12Pointer.of == DocumentPointer.of(List<PathPart>): the POSITION rule only -------------

    @Test
    fun theExactPointerFactoryDoesNotImposeTheStringParsersGrammar() {
        val pointer = A12Pointer.of(listOf(A12PathPart("a/b", 1)))
        assertEquals("/a/b", pointer.fullName)
        assertEquals(listOf("Order\$", "Items"), A12Pointer.of(
            listOf(A12PathPart("Order\$", 2), A12PathPart("Items", 1)),
        ).pathParts.map(A12PathPart::name))
    }

    @Test
    fun theExactPointerFactoryStillEnforcesThePositionRule() {
        assertFailsWith<IllegalArgumentException> {
            A12Pointer.of(listOf(A12PathPart("Order", 0), A12PathPart("Items", 1)))
        }
        assertEquals(0, A12Pointer.of(listOf(A12PathPart("Order", 1), A12PathPart("Items", 0)))
            .repetitionIndexes.last())
    }

    // ---- the STRING codec keeps the parser grammar, because its output must be parseable ---------

    @Test
    fun theStringCodecKeepsItsOwnGrammarOnBothDirections() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Order\$") }
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.format("Order\$", listOf(1)) }
        assertEquals("Order/Items[2]", A12PointerFormat.format("Order/Items", listOf(1, 2)))
    }

    // ---- the partially-known owner factory validates splitting, not names ------------------------

    @Test
    fun thePartiallyKnownFactoryAcceptsEveryFullNameItsOwnerAccepts() {
        for (fullName in listOf("/Order\$", "/Order Item", "/Order[2]")) {
            assertEquals(fullName, A12PartiallyKnownMultiPointer.of(fullName, listOf(1)).fullName)
        }
        val interior = A12PartiallyKnownMultiPointer.of("/Order//Items", listOf(1, 1, 1))
        assertEquals("/Order//Items", interior.fullName)
        assertEquals(listOf(1, 1, 1), interior.repetitionIndexes)
    }

    @Test
    fun thePartiallyKnownFactoryKeepsItsTwoDocumentedStrictnesses() {
        assertFailsWith<IllegalArgumentException> {
            A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(1))
        }
        assertFailsWith<IllegalArgumentException> {
            A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(1, -1))
        }
        assertEquals(listOf(1, -5), A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(1, -5))
            .repetitionIndexes)
    }

    // ---- the root conversion throws, as the owner's does -----------------------------------------

    /**
     * `toDocumentPointer()` evaluates `repetitions.subList(0, repetitions.size() - 1)` in its `if` condition,
     * outside its own `try`, so an empty index list raises rather than returning `Optional`. Measured, not read:
     * `IllegalArgumentException: fromIndex(0) > toIndex(-1)` on kernel 30.8.1. Silently repairing that edge would
     * make this method something other than the counterpart it is documented as.
     */
    @Test
    fun theRootConversionRaisesJustAsTheOwnersDoes() {
        val root = A12PartiallyKnownMultiPointer.of("", emptyList())
        assertEquals("/", root.fullName)
        assertFailsWith<IllegalArgumentException> { root.toExactPointer() }
    }

    @Test
    fun aNonRootConversionStillAnswers() {
        assertEquals(
            listOf(1, 2),
            A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(1, 2)).toExactPointer()
                ?.repetitionIndexes,
        )
        assertEquals(null, A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(0, 2)).toExactPointer())
    }
}
