package io.github.mbackschat.a12.dm.predicates

import io.github.mbackschat.a12.dm.predicates.A12SortOrderBinding.ASC
import io.github.mbackschat.a12.dm.predicates.A12SortOrderBinding.DESC
import io.github.mbackschat.a12.dm.predicates.A12SortOrderBinding.UNREADABLE
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The binding against every order spelling A12 was asked about (`rulekit.validate.laws.GroupIndexAndSortReferenceLawsTest`
 * for read or refuse, `adapter.SortOrderBindingTest` for which constant), on both runtimes. The padded rows separate
 * Java's trim from an exact match and from Kotlin's `trim()`.
 */
class SortOrderBindingPredicatesTest {

    @Test
    fun aStringBindsByNameOrCanonicalOrdinalAfterJavasTrim() {
        val rows = linkedMapOf(
            "ASC" to ASC, "DESC" to DESC, " ASC" to ASC, "\tDESC" to DESC, "DESC\n" to DESC,
            "0" to ASC, "1" to DESC, " 1" to DESC,
            "asc" to UNREADABLE, "Desc" to UNREADABLE, "UP" to UNREADABLE, "" to UNREADABLE, " " to UNREADABLE,
            "ASC\u2003" to UNREADABLE, "\u00A0ASC" to UNREADABLE, "01" to UNREADABLE, "2" to UNREADABLE,
            "-1" to UNREADABLE, "1.0" to UNREADABLE, "null" to UNREADABLE,
        )
        assertEquals<Map<String, A12SortOrderBinding>>(rows, rows.keys.associateWith { a12SortOrderOfString(it) })
    }

    @Test
    fun aNumberBindsOnlyAsAnOrdinalOfTheTwoConstants() {
        val rows = linkedMapOf(
            "0" to ASC, "-0" to ASC, "1" to DESC,
            "2" to UNREADABLE, "-1" to UNREADABLE, "1.0" to UNREADABLE, "1e0" to UNREADABLE, "9".repeat(26) to UNREADABLE,
        )
        assertEquals<Map<String, A12SortOrderBinding>>(rows, rows.keys.associateWith { a12SortOrderOfNumber(it) })
    }
}
