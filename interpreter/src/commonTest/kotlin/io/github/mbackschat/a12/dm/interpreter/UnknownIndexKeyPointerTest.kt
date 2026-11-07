package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * A missed key is part of a message pointer's identity, as `PartiallyKnownDocumentMultiPointerImpl` hashes and
 * compares it: two keys missing one row are two entities, and a number key is told apart by its text, so `7` and
 * `7.0` are two (KERNEL-FINDINGS KF683, measured on both kernel strategies by `adapter.laws.UnknownIndexKeyLawsTest`).
 */
class UnknownIndexKeyPointerTest {

    private val missed = A12PartiallyKnownMultiPointer.of("/Portfolio/Claims/Cost", listOf(1, A12PartiallyKnownMultiPointer.UNKNOWN, 1))

    @Test
    fun eachMissedKeyIsItsOwnEntity() {
        val a = missed.withUnknownIndexKey(UnknownIndexKey.Text("A"))
        val keys = listOf(
            missed,
            a,
            missed.withUnknownIndexKey(UnknownIndexKey.Text("B")),
            missed.withUnknownIndexKey(UnknownIndexKey.Decimal("7")),
            missed.withUnknownIndexKey(UnknownIndexKey.Decimal("7.0")),
            missed.withUnknownIndexKey(UnknownIndexKey.Instant(1_767_308_400_000L)),
            missed.withUnknownIndexKey(UnknownIndexKey.Instant(1_767_312_000_000L)),
        )

        assertEquals(keys.size, keys.toSet().size, "every pointer is distinct: $keys")
        val again = missed.withUnknownIndexKey(UnknownIndexKey.Text("A"))
        assertEquals(a, again)
        assertEquals(a.hashCode(), again.hashCode())
        assertNotEquals(missed, a)
    }

    /** The debug form names the key's value, so two unequal pointers never print alike. */
    @Test
    fun theDebugFormNamesTheKeyValue() {
        assertEquals(
            listOf("\"A\"", "7.0", "Date(1767308400000)"),
            listOf(UnknownIndexKey.Text("A"), UnknownIndexKey.Decimal("7.0"), UnknownIndexKey.Instant(1_767_308_400_000L))
                .map { missed.withUnknownIndexKey(it).toString().substringAfterLast(", ").removeSuffix("]") },
        )
    }

    @Test
    fun aTextKeyIsPublishedAsItsTextAndAnUnkeyedPointerPublishesNone() {
        assertEquals("A", missed.withUnknownIndexKey(UnknownIndexKey.Text("A")).semanticIndexValueForUnknownIndexGroup)
        assertNull(missed.semanticIndexValueForUnknownIndexGroup)
    }

    @Test
    fun onlyAPointerToTheUnknownRowCarriesAKey() {
        assertFailsWith<IllegalArgumentException> {
            A12PartiallyKnownMultiPointer.of("/Portfolio/Claims/Cost", listOf(1, 2, 1))
                .withUnknownIndexKey(UnknownIndexKey.Text("A"))
        }
    }
}
