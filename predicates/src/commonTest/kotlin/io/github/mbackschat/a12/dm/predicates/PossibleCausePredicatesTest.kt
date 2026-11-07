package io.github.mbackschat.a12.dm.predicates

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A possible-cause entry's spelling against the rows A12 was asked about (KERNEL-FINDINGS KF682,
 * `adapter.laws.CheckerPossibleCauseFilterAgreementTest`), on both runtimes.
 */
class PossibleCausePredicatesTest {

    @Test
    fun aWrittenEntryIsComparedAfterOneSpellingMap() {
        val rows = linkedMapOf(
            "" to "./", "./" to "./", ".." to "../", "Premium/.." to "Premium/../", "Rider/../" to "Rider/../",
            "Premium" to "Premium", "./Premium" to "./Premium", "Premium/" to "Premium/", " Premium" to " Premium",
            "." to ".", "../Premium" to "../Premium",
        )
        assertEquals<Map<String, String>>(rows, rows.keys.associateWith { possibleCauseSpelling(it) })
    }

    @Test
    fun anEntityIsNamedByTheShortestPathFromTheRulesGroup() {
        val rows = linkedMapOf(
            ("/Policy" to "/Policy/Premium") to "Premium",
            ("/Policy" to "/Policy/Lines/Amount") to "Lines/Amount",
            ("/Policy/Lines" to "/Policy/Premium") to "../Premium",
            ("/Policy/Lines" to "/Policy/Lines") to "./",
            ("/Policy/Lines" to "/Policy") to "../",
            ("/Policy/Lines/Items" to "/Policy/Rider/Code") to "../../Rider/Code",
            ("/Policy" to "/Other/Code") to "../Other/Code",
        )
        assertEquals<Map<Pair<String, String>, String>>(rows,
            rows.keys.associateWith { (group, entity) -> possibleCauseEntry(group, entity) })
    }
}
