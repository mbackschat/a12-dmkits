package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The two A12 addressing domains are separate types, and neither leaks into the other.
 *
 * [A12Pointer]/[A12PointerFormat] model A12's **exact** `DocumentPointer` only: repetitions `>= 1`, with `0`
 * legal on the final part alone (`DocumentPointerImpl`'s constructor, plus `PathPartImpl`'s
 * `repetitionIndex >= 0`). A negative repetition is unrepresentable there. The `[\w_-]+` name grammar belongs
 * to the **string codec**, not to the value — see [A12PointerOwnerEntryPointTest].
 *
 * [A12PartiallyKnownMultiPointer] models `PartiallyKnownDocumentMultiPointer`, which is what `IMessage` really
 * returns from `getErrorFieldPointer`/`getReferencedFieldsPointers`/`getRefOmissionErrorResponsiblePointers`.
 * There `0` is `DocumentMultiPointer.WILDCARD` at **any** part and `-5` is `UNKNOWN`.
 *
 * This file exists because those two domains were once ONE hybrid type that admitted their union: `-5` on an
 * exact pointer, which A12 cannot even parse, while still enforcing the exact type's last-part-only `0`. The
 * union is a shape A12 has nowhere, so the type belonged to neither channel. `A12PointerKernelDomainDiffTest`
 * in `:adapter` measures both accept/reject sets against the real kernel factories.
 */
class A12PointerDomainTest {

    // ---- the exact pointer admits no negative repetition at all --------------------------------

    @Test
    fun theExactPointerRejectsTheUnknownRepetition() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Plan/Demand[-5]/Headcount") }
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.format("Plan/Demand", listOf(1, -5)) }
        assertFailsWith<IllegalArgumentException> { A12PathPart("Demand", -5) }
    }

    // ---- every construction path validates, and none keeps the caller's list -------------------

    @Test
    fun directConstructionRejectsAnIllegalExactValue() {
        assertFailsWith<IllegalArgumentException> {
            A12Pointer.of(listOf(A12PathPart("Order", 0), A12PathPart("Items", 1)))
        }
    }

    @Test
    fun aCallerListMutationCannotChangeAConstructedPointer() {
        val parts = mutableListOf(A12PathPart("Order", 1), A12PathPart("Items", 2))
        val pointer = A12Pointer.of(parts)
        parts[1] = A12PathPart("Hijacked", 9)
        assertEquals("Order/Items[2]", A12PointerFormat.render(pointer))
        assertEquals(listOf(1, 2), pointer.repetitionIndexes)
    }

    /**
     * [A12PathPart] is the one pointer type that may stay a `data class`: it holds no collection, so the
     * generated `copy()` runs the primary constructor and re-validates. [A12Pointer] cannot — it must snapshot,
     * which `init` cannot do to a `val` constructor property — so it has a private constructor and no `copy` at
     * all. That absence is pinned by the committed ABI dumps, which would grow a `copy` entry if it came back.
     */
    @Test
    fun copyingAPathPartCannotBypassItsDomain() {
        assertFailsWith<IllegalArgumentException> { A12PathPart("Items", 2).copy(repetitionIndex = -7) }
        assertFailsWith<IllegalArgumentException> { A12PathPart("Items", 2).copy(name = "") }
        // …while `Items[3]` is a LEGAL part name — only the string codec refuses to write it.
        assertEquals("Items[3]", A12PathPart("Items", 2).copy(name = "Items[3]").name)
    }

    // ---- the message channel is its own type, with PartiallyKnownDocumentMultiPointer's domain --

    /**
     * The owner shape, member for member: `fullName()` (the `/A/B/C` form
     * `PartiallyKnownDocumentMultiPointerImpl` joins) and `repetitionIndexes()` — and **no pointer string**,
     * because A12 defines none for this type.
     */
    @Test
    fun thePartiallyKnownPointerCarriesTheOwnerShapeAndNothingElse() {
        val reported = A12PartiallyKnownMultiPointer.of("/Plan/Demand/Headcount", listOf(1, -5, 1))
        assertEquals("/Plan/Demand/Headcount", reported.fullName)
        assertEquals(listOf(1, -5, 1), reported.repetitionIndexes)
    }

    /** `0` is the wildcard at EVERY part here, and the `RepetitionsV2` slot on the final part only there. */
    @Test
    fun zeroMeansDifferentThingsOnTheTwoTypes() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Plan/Demand[0]/Headcount") }
        assertFailsWith<IllegalArgumentException> {
            A12Pointer.of(listOf(A12PathPart("Plan", 0), A12PathPart("Demand", 2)))
        }
        assertEquals(
            listOf(0, 2),
            A12PartiallyKnownMultiPointer.of("/Plan/Demand", listOf(0, 2)).repetitionIndexes,
        )
    }

    /**
     * `toDocumentPointer()`'s own condition: every non-final index `> WILDCARD`, and the final one `>= WILDCARD`.
     * So a wildcard or unknown anywhere but a trailing `0` makes the value inconvertible — which is the whole
     * reason the two types cannot be one.
     */
    @Test
    fun aPartiallyKnownPointerConvertsToTheExactTypeOnlyWhenItIsOne() {
        assertEquals(
            A12PointerFormat.parse("Order/Items[2]"),
            A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(1, 2)).toExactPointer(),
        )
        assertEquals(
            A12PointerFormat.parse("Order/Items[0]"),
            A12PartiallyKnownMultiPointer.of("/Order/Items", listOf(1, 0)).toExactPointer(),
        )
        assertNull(A12PartiallyKnownMultiPointer.of("/Plan/Demand/Headcount", listOf(1, -5, 1)).toExactPointer())
        assertNull(A12PartiallyKnownMultiPointer.of("/Plan/Demand", listOf(0, 2)).toExactPointer())
    }

    @Test
    fun thePartiallyKnownPointerAlsoSnapshotsAndValidates() {
        val repetitions = mutableListOf(1, -5)
        val pointer = A12PartiallyKnownMultiPointer.of("/Plan/Demand", repetitions)
        repetitions[1] = 99
        assertEquals(listOf(1, -5), pointer.repetitionIndexes)
        // -1 is IIdentifier.ITERATION, a template placeholder; only -5 is a message-pointer sentinel.
        assertFailsWith<IllegalArgumentException> {
            A12PartiallyKnownMultiPointer.of("/Plan/Demand", listOf(1, -1))
        }
        assertFailsWith<IllegalArgumentException> {
            A12PartiallyKnownMultiPointer.of("/Plan/Demand", listOf(1))
        }
    }

    /**
     * The root pointer on both types, including the shape that used to be unreadable: the partially-known
     * `fullName()` for the root is `"/"`, because its owner joins with a `"/"` prefix — and the shared splitter
     * rejected exactly that string, so the type could not read back its own accessor.
     */
    @Test
    fun theRootPointerIsRepresentableOnBothTypes() {
        assertEquals("", A12PointerFormat.parse("").fullName)
        assertEquals("", A12PointerFormat.parse("/").fullName)
        assertEquals(0, A12PointerFormat.parse("").size)

        val root = A12PartiallyKnownMultiPointer.of("", emptyList())
        assertEquals("/", root.fullName)
        assertEquals(emptyList(), root.repetitionIndexes)
        assertEquals(root, A12PartiallyKnownMultiPointer.of(root.fullName, root.repetitionIndexes))
        // …but its CONVERSION raises, exactly as the owner's does — see A12PointerOwnerEntryPointTest.
        assertFailsWith<IllegalArgumentException> { root.toExactPointer() }
    }
}
