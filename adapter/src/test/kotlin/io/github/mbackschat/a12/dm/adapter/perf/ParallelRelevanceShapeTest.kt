package io.github.mbackschat.a12.dm.adapter.perf

import io.github.mbackschat.a12.dm.adapter.laws.FuzzDiagnostics
import io.github.mbackschat.a12.dm.adapter.perf.SiblingGroupFuzz.ParallelRelevanceShape
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The contract of [SiblingGroupFuzz.parallelRelevanceShapes] — the coverage meter that
 * [SiblingGroupFuzzDiffTest.theParallelSeedMatrixReachesEveryDiscriminatingRelevanceShape] asserts against, and
 * therefore the thing standing between "the partial parallel fuzz covered every shape" and a vacuous claim.
 *
 * Both readings pinned here were WRONG before 2026-08-09 and neither could be caught through the generator: the
 * shipped strategy places every field of every row and gives both sibling groups the same key domain, so the
 * missing-index row and the disjoint-key join simply never occur in the fuzzed documents. A meter is only as
 * honest as the inputs it has been shown.
 */
class ParallelRelevanceShapeTest {

    @Test
    fun aRowWhoseIndexCellWasNeverPlacedStillCounts() {
        val view = view(
            cells = listOf(group(A, 1, 1), field("$A/Va", 1, 1, 1, value = "7")),
            relevant = setOf(RelevantEntity("$A/Va", listOf(1, 1, 1))),
        )
        assertEquals(
            setOf(ParallelRelevanceShape.PAYLOAD_WITHOUT_ITS_INDEX),
            SiblingGroupFuzz.parallelRelevanceShapes(view),
            "a row with a payload and NO index placement is the sharpest form of the shape; keying the scan off " +
                "the index CELL made exactly this row invisible and the meter reported nothing at all",
        )
    }

    @Test
    fun twoCompleteSidesAreAJoinOnlyWhenTheyShareAKey() {
        val readings = listOf(
            "the two sides carry the SAME key" to sides(aKey = "D1", bKey = "D1"),
            "the two sides carry DIFFERENT keys" to sides(aKey = "D1", bKey = "D9"),
            "the near side's index cell is EMPTY" to sides(aKey = null, bKey = "D1"),
        ).map { (label, shapes) -> "$label -> ${shapes.map { it.name }.sorted()}" }

        assertEquals(
            listOf(
                "the two sides carry the SAME key -> [BOTH_SIDES]",
                // Complete relevant rows on both sides, and yet nothing ever joins. "Each side has some complete
                // row" reported BOTH_SIDES here, which is the reading that let a disjoint-key document pass as
                // full coverage of the matching case.
                "the two sides carry DIFFERENT keys -> [ONE_SIDE_ONLY]",
                // An empty index cell keys nothing at all, so it cannot make a match either.
                "the near side's index cell is EMPTY -> [ONE_SIDE_ONLY]",
            ),
            readings,
            "BOTH_SIDES means the join MATCHES — some index value has a complete relevant row on both sides",
        )
    }

    /** One complete relevant row per side, carrying [aKey] / [bKey] (null = an empty index cell). */
    private fun sides(aKey: String?, bKey: String?): Set<ParallelRelevanceShape> = SiblingGroupFuzz
        .parallelRelevanceShapes(
            view(
                cells = listOf(
                    group(A, 1, 1), field("$A/Dept", 1, 1, 1, value = aKey), field("$A/Va", 1, 1, 1, value = "7"),
                    group(B, 1, 1), field("$B/Dept", 1, 1, 1, value = bKey), field("$B/Vb", 1, 1, 1, value = "8"),
                ),
                relevant = setOf(RelevantEntity(A, listOf(1, 1)), RelevantEntity(B, listOf(1, 1))),
            ),
        )

    private fun view(cells: List<FuzzDiagnostics.Cell>, relevant: Set<RelevantEntity>) =
        FuzzDiagnostics.RelevantView(cells, relevant, emptySet())

    private fun group(path: String, vararg reps: Int) =
        FuzzDiagnostics.Cell(path, reps.toList(), isGroup = true, value = null)

    private fun field(path: String, vararg reps: Int, value: String?) =
        FuzzDiagnostics.Cell(path, reps.toList(), isGroup = false, value = value)

    private companion object {
        const val A = "/Plan/A"
        const val B = "/Plan/B"
    }
}
