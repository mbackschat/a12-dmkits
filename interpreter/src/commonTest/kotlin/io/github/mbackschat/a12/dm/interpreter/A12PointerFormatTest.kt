package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Locks A12's canonical `DocumentPointer` string against the grammar recorded in KERNEL-FINDINGS
 * ("The canonical Document JSON and `DocumentPointer` string"), read from `DocumentPointerImpl` /
 * `PathPartImpl` / `DocumentMultiPointer` in kernel 30.8.1. This is the addressing form the whole
 * project consolidates onto; we do not define our own.
 */
class A12PointerFormatTest {

    // ---- format: `[1]` is implicit, so most pointers carry no bracket at all -------------------

    @Test
    fun omitsTheBracketForRepetitionOne() {
        assertEquals("Recipe/Title", A12PointerFormat.format("/Recipe/Title", listOf(1, 1)))
    }

    @Test
    fun writesTheBracketOnlyWhereTheRepetitionIsNotOne() {
        assertEquals(
            "Recipe/Ingredients[3]/Name",
            A12PointerFormat.format("/Recipe/Ingredients/Name", listOf(1, 3, 1)),
        )
    }

    @Test
    fun writesTheWildcardAsBracketZero() {
        assertEquals("Recipe/Ingredients[0]", A12PointerFormat.format("/Recipe/Ingredients", listOf(1, 0)))
    }

    @Test
    fun acceptsAPathWithoutALeadingSlash() {
        assertEquals("Recipe/Title", A12PointerFormat.format("Recipe/Title", listOf(1, 1)))
    }

    // ---- parse: the leading slash is redundant, a trailing slash is an error -------------------

    @Test
    fun parsesAnAbsentBracketAsRepetitionOne() {
        assertEquals(A12Pointer(listOf("Recipe", "Title"), listOf(1, 1)), A12PointerFormat.parse("Recipe/Title"))
    }

    @Test
    fun parsesExplicitRepetitions() {
        assertEquals(
            A12Pointer(listOf("Recipe", "Ingredients", "Name"), listOf(1, 3, 1)),
            A12PointerFormat.parse("Recipe/Ingredients[3]/Name"),
        )
    }

    @Test
    fun stripsTheRedundantLeadingSlash() {
        assertEquals(A12PointerFormat.parse("Recipe/Title"), A12PointerFormat.parse("/Recipe/Title"))
    }

    @Test
    fun parsesTheTrailingWildcard() {
        assertEquals(
            A12Pointer(listOf("Recipe", "Ingredients"), listOf(1, 0)),
            A12PointerFormat.parse("Recipe/Ingredients[0]"),
        )
    }

    @Test
    fun parsesAnEmptyBracketAsRepetitionOne() {
        assertEquals(A12Pointer(listOf("Recipe"), listOf(1)), A12PointerFormat.parse("Recipe[]"))
    }

    @Test
    fun rejectsATrailingSlash() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Recipe/Title/") }
    }

    @Test
    fun rejectsAWildcardThatIsNotTheLastPart() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Recipe/Ingredients[0]/Name") }
    }

    @Test
    fun rejectsAPartThatIsNotAName() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Recipe/Ingredients[3]x") }
    }

    // ---- A12's unknown repetition (IIdentifier.UNKNOWN = -5) ------------------------------------

    /**
     * `-5` is not an index and not a wildcard — it marks a level the engine could not resolve to a row, which a
     * parallel iteration produces whenever one joined group has no row for the current index value. The kernel
     * emits such pointers, so this format must express one rather than force an invention at the boundary.
     */
    @Test
    fun parsesAndRendersTheUnknownRepetitionAtAnyPart() {
        val parsed = A12PointerFormat.parse("Plan/Demand[-5]/Headcount")
        assertEquals(listOf(1, -5, 1), parsed.repetitions)
        assertEquals("Plan/Demand[-5]/Headcount", A12PointerFormat.format("Plan/Demand/Headcount", parsed.repetitions))
    }

    /**
     * The distinction that keeps this from being a loosening: `[0]` stays refused on an earlier part. The two
     * brackets share a syntax and mean unrelated things — the last-part `0` addresses `RepetitionsV2`, `-5` says
     * "no row here" — so admitting one says nothing about the other.
     */
    @Test
    fun butAnEarlierZeroIsStillRejectedAndSoIsAnyOtherNegative() {
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Plan/Demand[0]/Headcount") }
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Plan/Demand[-1]/Headcount") }
        assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse("Plan/Demand[-4]") }
        // …while -5 stays legal wherever it appears, last part included.
        assertEquals(listOf(1, -5), A12PointerFormat.parse("Plan/Demand[-5]").repetitions)
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    fun roundTripsTheCanonicalForm() {
        for (canonical in listOf("Recipe", "Recipe/Title", "Recipe/Ingredients[3]/Name", "Recipe/Ingredients[0]")) {
            val parsed = A12PointerFormat.parse(canonical)
            assertEquals(canonical, A12PointerFormat.format(parsed.names.joinToString("/"), parsed.repetitions))
        }
    }
}
