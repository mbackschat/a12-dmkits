package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

/** Portable controls for the owned SparseFilteredAggregateDiffTest kernel grid (SPEC-2026-10-10-02). */
class SparseFilteredAggregateTest {
    private val group = "/Dispatch/Runs/Parcels"
    private val permit = "$group/Permit"
    private val charge = "$group/Charge"
    private val stamp = "$group/RecordedAt"
    private val source = "Runs*/Parcels*/Charge Having (FieldFilled(Runs/Parcels/Permit) Or ([Runs/Parcels/RecordedAt] == \"31.03.2024T02:30:00\"))"
    private val model = ModelDef(
        fields = listOf(FieldDef(permit, Kind.NUMBER, scale = 0), FieldDef(charge, Kind.NUMBER, scale = 0),
            FieldDef(stamp, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss")) +
            listOf("Total", "Filled", "First").map { FieldDef("/Dispatch/$it", Kind.NUMBER, scale = 0) },
        rules = emptyList(),
        computations = listOf("Total" to "Sum", "Filled" to "NumberOfFilledFields", "First" to "FirstFilledValue")
            .map { (name, operator) -> ComputationDef("/Dispatch/$name", "/Dispatch", "$operator($source)") },
        groupPaths = setOf("/Dispatch", "/Dispatch/Runs", group),
        declaredReps = mapOf("/Dispatch/Runs" to 2, group to 51),
        timeZone = ModelTimeZone.EUROPE_BERLIN,
    )

    private fun outcomes(width: Int, blank: Boolean = false): Map<String, String> {
        val rows = List(width) { """{"Permit":1}""" }.joinToString(",")
        val doc = model.readDocumentJson("""{"Dispatch":{"Total":99,"Filled":99,"First":99,
            "Runs":[{"Parcels":[$rows]},{"Parcels":[{"Permit":1,"Charge":5}${if (blank) ",{}" else ""}]}]}}""")
        return DmInterpreter(model).compute(doc).associate { it.path.substringAfterLast('/') to when (it.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${it.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        } }
    }

    @Test
    fun actualCandidateWidthControlsSparseTraversalWhileFirstFilledStaysDense() {
        val clear = mapOf("Total" to "CLEARED", "Filled" to "CLEARED", "First" to "CLEARED")
        assertEquals(clear, outcomes(49))
        assertEquals(clear, outcomes(50))
        assertEquals(mapOf("Total" to "VALUE|5", "Filled" to "VALUE|1", "First" to "CLEARED"), outcomes(51))
        assertEquals(clear, outcomes(51, blank = true))
    }
}
