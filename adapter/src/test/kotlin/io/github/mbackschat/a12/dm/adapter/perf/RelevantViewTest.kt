package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity.Companion.ALL
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The contract of [FuzzDiagnostics.RelevantView] — the EFFECTIVE-relevance query a coverage meter asks.
 *
 * This exists because a meter's claims are only as good as its predicate. A meter that tested raw set membership
 * would report "the payload is relevant but its index cell is not" for a row whose whole GROUP was selected —
 * a shape the runtime never produces — and the fuzz would then look like it covered a cell it never reached.
 * So the two ways relevance arrives INDIRECTLY (an ancestor group, an [ALL] wildcard) are pinned here, together
 * with the arity admission that makes an ill-formed entity inert rather than throwing.
 */
class RelevantViewTest {

    @Test
    fun relevanceArrivesDirectlyThroughAnAncestorGroupAndThroughWildcards() {
        val readings = listOf(
            "the cell's own exact coordinates" to
                view(RelevantEntity(SKU, listOf(1, 2, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "a DIFFERENT row's exact coordinates" to
                view(RelevantEntity(SKU, listOf(1, 3, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "the row's whole GROUP" to
                view(RelevantEntity(LINES, listOf(1, 2))).isRelevant(SKU, listOf(1, 2, 1)),
            "ANOTHER row's whole group" to
                view(RelevantEntity(LINES, listOf(1, 3))).isRelevant(SKU, listOf(1, 2, 1)),
            "the root group, which covers everything beneath it" to
                view(RelevantEntity(ORDER, listOf(1))).isRelevant(SKU, listOf(1, 2, 1)),
            "a fully wildcarded pointer at the cell's own path" to
                view(RelevantEntity(SKU, listOf(ALL, ALL, ALL))).isRelevant(SKU, listOf(1, 2, 1)),
            "a wildcard on the REPETITION level only" to
                view(RelevantEntity(SKU, listOf(1, ALL, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "a wildcard on the repetition level, wrong root coordinate" to
                view(RelevantEntity(SKU, listOf(2, ALL, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "a wildcarded GROUP entity" to
                view(RelevantEntity(LINES, listOf(1, ALL))).isRelevant(SKU, listOf(1, 2, 1)),
            "two patterns at one path, the second matching" to
                view(RelevantEntity(SKU, listOf(1, 9, 1)), RelevantEntity(SKU, listOf(ALL, 2, ALL)))
                    .isRelevant(SKU, listOf(1, 2, 1)),
            "a SIBLING field of the same row" to
                view(RelevantEntity(QTY, listOf(1, 2, 1))).isRelevant(SKU, listOf(1, 2, 1)),
            "an entity carrying too few coordinates for its path" to
                view(RelevantEntity(SKU, listOf(1, 2))).isRelevant(SKU, listOf(1, 2, 1)),
            "nothing relevant at all" to view().isRelevant(SKU, listOf(1, 2, 1)),
        ).map { (label, relevant) -> "$label -> $relevant" }

        assertEquals(
            listOf(
                "the cell's own exact coordinates -> true",
                "a DIFFERENT row's exact coordinates -> false",
                "the row's whole GROUP -> true",
                "ANOTHER row's whole group -> false",
                "the root group, which covers everything beneath it -> true",
                "a fully wildcarded pointer at the cell's own path -> true",
                "a wildcard on the REPETITION level only -> true",
                "a wildcard on the repetition level, wrong root coordinate -> false",
                "a wildcarded GROUP entity -> true",
                "two patterns at one path, the second matching -> true",
                "a SIBLING field of the same row -> false",
                "an entity carrying too few coordinates for its path -> false",
                "nothing relevant at all -> false",
            ),
            readings,
            "effective relevance reaches a cell through its own pointer, through ANY ancestor prefix, and " +
                "through a wildcard at any level — a meter that asked only for the cell's own path in the set " +
                "would misreport every row selected by its group",
        )
    }

    @Test
    fun aGlobalFieldIsRelevantWhateverTheSetSays() {
        val global = FuzzDiagnostics.RelevantView(emptyList(), emptySet(), setOf(SKU))
        assertEquals(
            listOf(true, false),
            listOf(global.isRelevant(SKU, listOf(1, 2, 1)), global.isRelevant(QTY, listOf(1, 2, 1))),
            "a global field is unconditionally relevant; its sibling is not",
        )
    }

    private fun view(vararg entities: RelevantEntity) =
        FuzzDiagnostics.RelevantView(emptyList(), entities.toSet(), emptySet())

    private companion object {
        const val ORDER = "/Order"
        const val LINES = "/Order/Lines"
        const val SKU = "/Order/Lines/Sku"
        const val QTY = "/Order/Lines/Qty"
    }
}
