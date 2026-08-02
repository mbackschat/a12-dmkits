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
        assertEquals(
            A12Pointer.of(listOf(A12PathPart("Recipe", 1), A12PathPart("Title", 1))),
            A12PointerFormat.parse("Recipe/Title"),
        )
    }

    @Test
    fun parsesExplicitRepetitions() {
        assertEquals(
            A12Pointer.of(
                listOf(A12PathPart("Recipe", 1), A12PathPart("Ingredients", 3), A12PathPart("Name", 1)),
            ),
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
            A12Pointer.of(listOf(A12PathPart("Recipe", 1), A12PathPart("Ingredients", 0))),
            A12PointerFormat.parse("Recipe/Ingredients[0]"),
        )
    }

    @Test
    fun parsesAnEmptyBracketAsRepetitionOne() {
        assertEquals(A12Pointer.of(listOf(A12PathPart("Recipe", 1))), A12PointerFormat.parse("Recipe[]"))
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

    // ---- what an exact pointer may NOT carry ---------------------------------------------------

    /**
     * A12's unknown repetition (`-5`) is not an exact-pointer value at all: `DocumentPointerImpl`'s part pattern
     * is unsigned and `PathPartImpl` refuses a negative index. It belongs to the message channel, so it is
     * `A12PartiallyKnownMultiPointer`'s to carry — see `A12PointerDomainTest`.
     */
    @Test
    fun rejectsEveryNegativeRepetition() {
        for (pointer in listOf("Plan/Demand[-1]/Headcount", "Plan/Demand[-4]", "Plan/Demand[-5]/Headcount")) {
            assertFailsWith<IllegalArgumentException> { A12PointerFormat.parse(pointer) }
        }
    }

    // ---- round trip ---------------------------------------------------------------------------

    @Test
    fun roundTripsTheCanonicalForm() {
        for (canonical in listOf("Recipe", "Recipe/Title", "Recipe/Ingredients[3]/Name", "Recipe/Ingredients[0]")) {
            val parsed = A12PointerFormat.parse(canonical)
            assertEquals(canonical, A12PointerFormat.render(parsed))
            assertEquals(canonical, A12PointerFormat.format(parsed.fullName, parsed.repetitionIndexes))
        }
    }
}
