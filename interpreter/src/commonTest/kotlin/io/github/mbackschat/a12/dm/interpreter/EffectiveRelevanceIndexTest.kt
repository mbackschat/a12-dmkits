package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EffectiveRelevanceIndex
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity.Companion.ALL
import kotlin.test.Test
import kotlin.test.assertEquals

/** JVM + JS law for the production relevance query shared by partial validation and structural fuzz meters. */
class EffectiveRelevanceIndexTest {

    @Test
    fun effectiveCellRelevanceIncludesAncestorsWildcardsAndGlobals() {
        val readings = listOf(
            "exact" to index(RelevantEntity(SKU, listOf(1, 2, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "different row" to index(RelevantEntity(SKU, listOf(1, 3, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "ancestor group" to index(RelevantEntity(LINES, listOf(1, 2))).isRelevant(SKU, listOf(1, 2, 1)),
            "different ancestor row" to index(RelevantEntity(LINES, listOf(1, 3))).isRelevant(SKU, listOf(1, 2, 1)),
            "root ancestor" to index(RelevantEntity(ORDER, listOf(1))).isRelevant(SKU, listOf(1, 2, 1)),
            "wildcard" to index(RelevantEntity(SKU, listOf(1, ALL, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "wrong wildcard root" to index(RelevantEntity(SKU, listOf(2, ALL, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "wildcard ancestor group" to
                index(RelevantEntity(LINES, listOf(1, ALL))).isRelevant(SKU, listOf(1, 2, 1)),
            "second pattern matches" to index(
                RelevantEntity(SKU, listOf(1, 9, 1)),
                RelevantEntity(SKU, listOf(ALL, 2, ALL)),
            ).isRelevant(SKU, listOf(1, 2, 1)),
            "sibling" to index(RelevantEntity(QTY, listOf(1, 2, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "wrong arity" to index(RelevantEntity(SKU, listOf(1, 2))).isRelevant(SKU, listOf(1, 2, 1)),
            "global" to index(globalFields = setOf(SKU)).isRelevant(SKU, listOf(9, 9, 1)),
            "empty" to index().isRelevant(SKU, listOf(1, 2, 1)),
        ).map { (label, result) -> "$label=$result" }

        assertEquals(
            listOf(
                "exact=true",
                "different row=false",
                "ancestor group=true",
                "different ancestor row=false",
                "root ancestor=true",
                "wildcard=true",
                "wrong wildcard root=false",
                "wildcard ancestor group=true",
                "second pattern matches=true",
                "sibling=false",
                "wrong arity=false",
                "global=true",
                "empty=false",
            ),
            readings,
        )
    }

    @Test
    fun aRuleGateTreatsEveryUnboundDeeperLevelAsWildcard() {
        val relevance = index(RelevantEntity(TAG, listOf(1, 2, 3)))
        assertEquals(
            listOf(false, true, false),
            listOf(
                relevance.isRelevant(TAG, listOf(1, 2, 1)),
                relevance.isRelevantWithin(TAG, listOf(1, 2, 1), bound = 2),
                relevance.isRelevantWithin(TAG, listOf(1, 4, 1), bound = 2),
            ),
            "the full cell query stays exact; the rule-run query projects only levels the iteration bound",
        )
    }

    private fun index(
        vararg entities: RelevantEntity,
        globalFields: Set<String> = emptySet(),
    ) = EffectiveRelevanceIndex.build(globalFields, entities.toSet())

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val SKU = "/Order/Lines/Sku"
        const val QTY = "/Order/Lines/Qty"
        const val TAG = "/Order/Lines/Tag"
    }
}
