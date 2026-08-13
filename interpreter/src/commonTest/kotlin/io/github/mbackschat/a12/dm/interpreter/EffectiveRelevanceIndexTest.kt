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

    /**
     * Two levels of ONE path carrying buckets at once — the case the per-path level plan aligns by depth, and the
     * one a plan built with an off-by-one would answer from the wrong ancestor's tuples. Each reading is decided by
     * a different level, so a plan that collapsed or mis-indexed them cannot produce this pair.
     */
    @Test
    fun eachAncestorLevelIsMatchedAgainstItsOwnTuples() {
        val relevance = index(
            RelevantEntity(LINES, listOf(1, 3)),          // the GROUP level names row 3
            RelevantEntity(SKU, listOf(1, 2, 1)),         // the FIELD level names row 2
        )
        assertEquals(
            listOf(true, true, false),
            listOf(
                relevance.isRelevant(SKU, listOf(1, 2, 1)),   // decided at the field level
                relevance.isRelevant(SKU, listOf(1, 3, 1)),   // decided at the group level
                relevance.isRelevant(SKU, listOf(1, 4, 1)),   // named by neither
            ),
            "a level's coordinates must be probed against that level's bucket, not another's",
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
