package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock — the filtered computation VALUE AGGREGATES (`Sum`/`MinValue`/`MaxValue`/
 * `NumberOfDifferentValues`) ride the SAME one-kept-successor lookahead as `FirstFilledValue`
 * (SPEC-2026-07-23-11) while still accumulating EVERY clean kept target: a reached filter or kept-target
 * poison clears the computation regardless of which cause is met first (the poison report is cause-blind),
 * an invalid direct earlier operand hides a later filtered slot, and a clean filtered fold consumes every
 * kept row. Kernel-verified by the tri-check differential `adapter.laws.AggregateKeptSuccessorDiffTest`.
 */
class AggregateKeptSuccessorTest {

    private val TOTAL = "/Shipment/Total"
    private val BASE = "/Shipment/Base"
    private val WEIGHT = "/Shipment/Parcels/Weight"
    private val UNITS = "/Shipment/Parcels/Units"

    private fun def(expr: String) = ModelDef(
        fields = listOf(
            FieldDef(TOTAL, Kind.NUMBER, scale = 0),
            FieldDef(BASE, Kind.NUMBER, scale = 0),
            FieldDef(WEIGHT, Kind.NUMBER, scale = 0),
            FieldDef(UNITS, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(TOTAL, "/Shipment", expr)),
        repeatableGroups = setOf("/Shipment/Parcels"),
    )

    /** [rows]: per row `(Weight, Units)`, null = empty cell. Returns the `Total` outcome. */
    private fun outcome(expr: String, rows: List<Pair<String?, String?>>, base: String? = null): String {
        val model = def(expr)
        val doc = model.newDocument().group("/Shipment", 1).field(TOTAL, "999", 1, 1)
        base?.let { doc.field(BASE, it, 1, 1) }
        rows.forEachIndexed { i, (weight, units) ->
            doc.group("/Shipment/Parcels", 1, i + 1)
            weight?.let { doc.field(WEIGHT, it, 1, i + 1, 1) }
            units?.let { doc.field(UNITS, it, 1, i + 1, 1) }
        }
        val c = DmInterpreter(model).compute(doc).first { it.path == TOTAL }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    private val filteredSum = "Sum(Parcels*/Weight Having ([Parcels/Units] > 0))"

    @Test fun aSuccessorFilterPoisonPrecedesTheKeptRowsInvalidTarget() =
        assertEquals("CLEARED", outcome(filteredSum, listOf("07" to "1", "3" to "07")))

    @Test fun aPoisonedCurrentTargetAbortsBeforeTheThirdFilter() =
        assertEquals("CLEARED", outcome(filteredSum, listOf("07" to "1", "3" to "1", "4" to "07")))

    @Test fun theSuccessorSearchReadsTheThirdFilterThroughAFalseCandidate() =
        assertEquals("CLEARED", outcome(filteredSum, listOf("5" to "1", "3" to "0", "4" to "07")))

    @Test fun anInvalidDirectEarlierOperandHidesTheFilteredSlot() =
        assertEquals("CLEARED", outcome(
            "Sum(Base, Parcels*/Weight Having ([Parcels/Units] > 0))",
            listOf("5" to "1"), base = "07",
        ))

    @Test fun aCleanFilteredSumAccumulatesEveryKeptTarget() =
        assertEquals("VALUE|6", outcome(filteredSum, listOf("2" to "1", "3" to "0", "4" to "1")))

    @Test fun aMinValueSuccessorFilterPoisonRidesTheSameTraversal() =
        assertEquals("CLEARED", outcome(
            "MinValue(Parcels*/Weight Having ([Parcels/Units] > 0))",
            listOf("5" to "1", "3" to "07"),
        ))

    @Test fun aNumberOfDifferentValuesStillConsumesEveryKeptRow() =
        assertEquals("VALUE|2", outcome(
            "NumberOfDifferentValues(Parcels*/Weight Having ([Parcels/Units] > 0))",
            listOf("2" to "1", "2" to "1", "4" to "1"),
        ))
}
