package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
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
    private val source = "Runs*/Parcels*/Charge Having (FieldFilled(Runs/Parcels/Permit) Or ([Runs/Parcels/RecordedAt] == \"29.02.1500T10:00:00\"))"
    private val model = ModelDef(
        fields = listOf(FieldDef(permit, Kind.NUMBER, scale = 0), FieldDef(charge, Kind.NUMBER, scale = 0),
            FieldDef(stamp, Kind.DATE_TIME, format = "yyyy-MM-dd'T'HH:mm:ss")) +
            listOf("Total", "Filled", "First", "Minimum", "Maximum", "Distinct").map { FieldDef("/Dispatch/$it", Kind.NUMBER, scale = 0) },
        rules = emptyList(),
        computations = listOf("Total" to "Sum", "Filled" to "NumberOfFilledFields", "First" to "FirstFilledValue",
            "Minimum" to "MinValue", "Maximum" to "MaxValue", "Distinct" to "NumberOfDifferentValues")
            .map { (name, operator) -> ComputationDef("/Dispatch/$name", "/Dispatch", "$operator($source)") },
        groupPaths = setOf("/Dispatch", "/Dispatch/Runs", group),
        declaredReps = mapOf("/Dispatch/Runs" to 2, group to 51),
        timeZone = ModelTimeZone.EUROPE_BERLIN,
    )

    private fun outcomes(width: Int, blank: Boolean = false): Map<String, String> {
        val rows = List(width) { """{"Permit":1}""" }.joinToString(",")
        val doc = model.readDocumentJson("""{"Dispatch":{"Total":99,"Filled":99,"First":99,"Minimum":99,"Maximum":99,"Distinct":99,
            "Runs":[{"Parcels":[$rows]},{"Parcels":[{"Permit":1,"Charge":5}${if (blank) ",{}" else ""}]}]}}""")
        return DmInterpreter(model).compute(doc).associate { it.path.substringAfterLast('/') to when (it.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${it.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        } }
    }

    @Test
    fun actualCandidateWidthControlsSparseTraversalWhileFirstFilledStaysDense() {
        val clear = mapOf("Total" to "CLEARED", "Filled" to "CLEARED", "First" to "CLEARED", "Minimum" to "CLEARED", "Maximum" to "CLEARED", "Distinct" to "CLEARED")
        assertEquals(clear, outcomes(49))
        assertEquals(clear, outcomes(50))
        assertEquals(mapOf("Total" to "VALUE|5", "Filled" to "VALUE|1", "First" to "CLEARED", "Minimum" to "VALUE|5", "Maximum" to "VALUE|5", "Distinct" to "VALUE|1"), outcomes(51))
        assertEquals(clear, outcomes(51, blank = true))
    }
    @Test
    fun uniquenessReadsTheKeptSuccessorButHidesTheNextFilter() {
        val fields = listOf("Charge", "ShippedOn")
        val definition = model.copy(
            fields = model.fields + FieldDef("$group/ShippedOn", Kind.DATE, format = "yyyy-MM-dd", datePrecision = DatePrecision.DAY_OPTIONAL) +
                FieldDef("/Dispatch/Anchor", Kind.NUMBER, scale = 0),
            computations = emptyList(),
            rules = fields.map { name -> RuleDef("/Dispatch/${name}Rule", "/Dispatch",
                "FieldFilled(Anchor) And FieldValuesNotUnique(${source.replace("Charge", name)})", name, "../Anchor") },
        )
        for (failureIndex in listOf(2, 3)) {
            val rows = List(51) { index -> when {
                index == failureIndex -> "{}"
                index < 2 -> """{"Permit":1,"Charge":7,"ShippedOn":"2024-06-00"}"""
                else -> """{"Permit":1}"""
            } }.joinToString(",")
            val doc = definition.readDocumentJson("""{"Dispatch":{"Anchor":1,"Runs":[{"Parcels":[$rows]},{"Parcels":[{"Permit":1}]}]}}""")
            val messages = DmInterpreter(definition).validateFull(doc)
            for (field in fields) assertEquals(listOf(if (failureIndex == 2) MsgType.VALUE else MsgType.OMISSION),
                messages.filter { it.code == field }.map { it.type }, "$failureIndex/$field")
        }
    }

}
