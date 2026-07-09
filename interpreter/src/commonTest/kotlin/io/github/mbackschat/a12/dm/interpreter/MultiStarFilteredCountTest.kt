package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for IF94 — (1) `NumberOfFilledFields` HONORS a `Having` filter on its starred
 * operand (the count consumes only filter-kept rows; a kept row's malformed cell makes it non-evaluable, a
 * dropped row's does not), and (2) a MULTI-star operand binds at the FIRST `*`-marked level — a per-row read
 * of `Sections* / Items* / X` spans ALL sections' items, not the current section's. Kernel-verified by
 * `adapter.laws.MultiStarFilteredCountDiffTest` (groovy-dynamic == java-static == interpreter).
 */
class MultiStarFilteredCountTest {

    private val CODE = "/Shop/Catalog/Sections/Items/ItemCode"
    private val GRADE = "/Shop/Catalog/Sections/Items/ItemGrade"
    private val QTY = "/Shop/Catalog/Sections/Items/Qty"
    private val USAGE = "/Shop/Catalog/Sections/Items/UsageCount"
    private val TOTAL = "/Shop/Catalog/Sections/Items/QtyTotal"
    private val ITEMS = "/Shop/Catalog/Sections/Items"

    private val DUP_COUNT =
        "NumberOfFilledFields(../../Sections*/Items*/ItemCode Having ([ItemCode] == [\$ItemCode] And [ItemGrade] == [\$ItemGrade]))"

    private fun model(vararg comps: ComputationDef) = ModelDef(
        fields = listOf(
            FieldDef(CODE, Kind.STRING),
            FieldDef(GRADE, Kind.STRING),
            FieldDef(QTY, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(TOTAL, Kind.NUMBER, scale = 2, minFractionalDigits = 2),
            FieldDef(USAGE, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = comps.toList(),
    )

    private val dupCount = ComputationDef(USAGE, ITEMS,
        listOf(ComputationAlternative("$DUP_COUNT > 1", "$DUP_COUNT - 1")))

    private fun doc(m: ModelDef, vararg items: Triple<List<Int>, Pair<String, String>, String?>) =
        m.newDocument().group("/Shop", 1).group("/Shop/Catalog", 1, 1).also { d ->
            for ((si, cg, qty) in items) {
                val (s, i) = si
                d.group("/Shop/Catalog/Sections", 1, 1, s).group(ITEMS, 1, 1, s, i)
                    .field(CODE, cg.first, 1, 1, s, i, 1).field(GRADE, cg.second, 1, 1, s, i, 1)
                    .field(USAGE, "7", 1, 1, s, i, 1)
                qty?.let { d.field(QTY, it, 1, 1, s, i, 1) }
            }
        }

    private fun outcomes(m: ModelDef, d: io.github.mbackschat.a12.dm.interpreter.eval.Document, path: String) =
        DmInterpreter(m).compute(d).filter { it.path == path }
            .associate { it.indices.joinToString(",") to (it.outcome to it.value) }

    @Test fun crossSectionDuplicatesCountAcrossSections() {
        val m = model(dupCount)
        val d = doc(m,
            Triple(listOf(1, 1), "A" to "X", null),
            Triple(listOf(2, 1), "B" to "Y", null),
            Triple(listOf(2, 2), "A" to "X", null))
        val out = outcomes(m, d, USAGE)
        assertEquals(ComputeOutcome.VALUE to "1", out["1,1,1,1,1"], "S1/I1 has a cross-section duplicate → count 2 → 1")
        assertEquals(ComputeOutcome.CLEARED, out["1,1,2,1,1"]?.first, "S2/I1 is unique → count 1 → precondition false")
        assertEquals(ComputeOutcome.VALUE to "1", out["1,1,2,2,1"], "S2/I2 duplicates S1/I1 → count 2 → 1")
    }

    @Test fun allDistinctPairsClearEverywhere() {
        val m = model(dupCount)
        val d = doc(m,
            Triple(listOf(1, 1), "A" to "X", null),
            Triple(listOf(2, 1), "B" to "Y", null),
            Triple(listOf(2, 2), "C" to "X", null))
        val out = outcomes(m, d, USAGE)
        assertEquals(setOf(ComputeOutcome.CLEARED), out.values.map { it.first }.toSet(),
            "the Having filter is honored — every count is 1, nothing computes")
    }

    @Test fun unfilteredDoubleStarSumSpansAllSections() {
        val m = model(ComputationDef(TOTAL, ITEMS, "Sum(../../Sections*/Items*/Qty)"))
        val d = doc(m,
            Triple(listOf(1, 1), "A" to "X", "1.00"),
            Triple(listOf(2, 1), "B" to "Y", "2.00"),
            Triple(listOf(2, 2), "C" to "Z", "3.00"))
        val out = outcomes(m, d, TOTAL)
        assertEquals(setOf(ComputeOutcome.VALUE to "6.00"), out.values.toSet(),
            "every row's Sum spans ALL sections' items (6.00), not its own section's")
    }

    @Test fun malformedCellInAFilteredOutRowDoesNotPoisonTheCount() {
        val m = model(ComputationDef(USAGE, ITEMS, listOf(ComputationAlternative(
            "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\") > 0",
            "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\")"))))
        val d = doc(m,
            Triple(listOf(1, 1), "A" to "X", "1.00"),
            Triple(listOf(2, 1), "B" to "Y", "5.5"),
            Triple(listOf(2, 2), "C" to "X", "3.00"))
        assertEquals(setOf(ComputeOutcome.VALUE to "2"), outcomes(m, d, USAGE).values.toSet(),
            "the malformed Qty is in a filtered-OUT row — the count over kept rows computes 2")
    }

    @Test fun malformedCellInAKeptRowPoisonsTheCount() {
        val m = model(ComputationDef(USAGE, ITEMS, listOf(ComputationAlternative(
            "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\") > 0",
            "NumberOfFilledFields(../../Sections*/Items*/Qty Having [ItemGrade] == \"X\")"))))
        val d = doc(m,
            Triple(listOf(1, 1), "A" to "X", "1.00"),
            Triple(listOf(2, 1), "B" to "Y", "2.00"),
            Triple(listOf(2, 2), "C" to "X", "5.5"))
        assertEquals(setOf<ComputeOutcome?>(ComputeOutcome.CLEARED), outcomes(m, d, USAGE).values.map { it.first }.toSet(),
            "the malformed Qty is in a KEPT row — the count is non-evaluable, every row poisons")
    }
}
