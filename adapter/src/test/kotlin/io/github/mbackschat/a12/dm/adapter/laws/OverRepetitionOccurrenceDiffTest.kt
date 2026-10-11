package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.Test
import java.util.Locale

class OverRepetitionOccurrenceDiffTest {
    private enum class Cell(val json: String?) { ABSENT(null), NULL("null"), EMPTY("\"\""), VALID("1"), INVALID("\"bad\"") }

    private val model = DocumentModelDraft.named("parcel-structural-occurrences").locale("en_US")
        .rootGroup("Parcel") { root ->
            root.numberField("Total") { it.fractionDigits(0) }.stringField("Marker") { }
                .group("Rows") { row ->
                    row.repeatable(2).numberField("Quantity") { it.fractionDigits(0) }.stringField("Unread") { }
                        .group("Detail") { it.numberField("Quantity") { field -> field.fractionDigits(0) } }
                }.group("Notes") { it.repeatable(2).stringField("Memo") { } }
        }.build().toJson()

    @Test
    fun structuralDiagnosticsRetainEmptyFieldOccurrencesOutsidePartialSelection() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val prepared = PreparedModel.loadExpandedJson(model)
        val selections = listOf(
            emptySet(),
            setOf(RuntimeLaws.Relevant.of("/Parcel", 0)),
            setOf(RuntimeLaws.Relevant.of("/Parcel/Marker", 0, 0)),
            setOf(RuntimeLaws.Relevant.of("/Parcel/Rows", 0, 1)),
            setOf(RuntimeLaws.Relevant.of("/Parcel/Rows/Quantity", 0, 3, 0)),
        )
        val checks = mutableListOf<Executable>()
        for (nested in listOf(false, true)) for (cell in Cell.entries) {
            val content = cell.json?.let { "\"Quantity\":$it" }.orEmpty()
            val row = if (nested) "\"Detail\":{$content}" else content
            val json = """{"Parcel":{"Marker":"ready","Rows":[{},{},{$row}]}}"""
            val full = dynamic.validationMessages(dynamic.instance(json), null, Locale.US).map {
                "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
            }.sorted()
            assertEquals(1 + (if (nested) 1 else 0) + (if (cell == Cell.ABSENT) 0 else 1), full.size, "$nested/$cell full occurrence count")
            checks += Executable {
                val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(json))
                assertEquals(emptyList<Any>(), report.unsupported)
                assertEquals(full, report.findings.map {
                    "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                }.sorted(), "$nested/$cell: full interpreter")
            }
            for (selected in selections) checks += Executable {
                val label = "$nested/$cell/$selected"
                val oracle = dynamic.partialValidationMessages(dynamic.instance(json), selected, null, Locale.US).map {
                    "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted()
                assertEquals(if (selected.isEmpty()) emptyList<String>() else full, oracle, "$label: structural findings outside nonempty partial selection")
                assertEquals(oracle, static.partialValidationMessages(static.instance(json), selected, null, Locale.US).map {
                    "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted(), "$label: static")
                val relevant = selected.map { pointer -> prepared.model.relevantPointer(pointer.path(), pointer.reps().map {
                    if (it == 0) RelevantCoordinate.All else RelevantCoordinate.Exact(it)
                }) }
                val report = prepared.createInterpreter().validatePart(prepared.documents.readDocument(json), relevant)
                assertEquals(emptyList<Any>(), report.unsupported, label)
                assertEquals(oracle, report.findings.map {
                    "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                }.sorted(), "$label: interpreter")
            }
        }
        assertAll(checks)
    }


    @Test
    fun computationReportsPresentOperandOccurrencesButExcludesUnreadFieldsAndSubtrees() {
        val session = EditSession.read(model)
        val parcel = session.group("/Parcel")
        val computationModel = TypedRules.inject(session.toJson(), parcel.computation(parcel.num("Total")) {
            it.name("SumQuantity").calc(Dsl.sum(parcel.flatten("Rows*/Quantity")))
                .message(Dsl.en("Check the total quantity.")).build()
        })
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(computationModel))
        val dynamic = RuntimeLaws.kernelEngine(computationModel)
        val static = RuntimeLaws.kernelJavaEngine(computationModel)
        val prepared = PreparedModel.loadExpandedJson(computationModel)
        val checks = mutableListOf<Executable>()
        for (cell in Cell.entries) for (unread in listOf(false, true)) checks += Executable {
            val quantity = cell.json?.let { "\"Quantity\":$it" }.orEmpty()
            val siblings = if (unread) listOf("\"Unread\":\"\"", "\"Detail\":{\"Quantity\":null}") else emptyList()
            val row = (listOf(quantity).filter { it.isNotEmpty() } + siblings).joinToString(",")
            val notes = if (unread) ",\"Notes\":[{},{},{\"Memo\":null}]" else ""
            val json = """{"Parcel":{"Rows":[{"Quantity":5},{},{$row}]$notes}}"""
            val label = "$cell/unread=$unread"
            val expected = listOf("zuGrosseZeile|VALUE_ERROR|/Parcel/Rows|[1, 3]") +
                if (cell == Cell.ABSENT) emptyList() else
                    listOf("zuGrosseKontextnummer|VALUE_ERROR|/Parcel/Rows/Quantity|[1, 3, 1]")
            val oracle = dynamic.computation(dynamic.instance(json), null, Locale.US)
            assertEquals(expected.sorted(), oracle.formalErrorsInOperands().map {
                "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
            }.sorted(), "$label: dynamic")
            val java = static.computation(static.instance(json), null, Locale.US)
            assertEquals(expected.sorted(), java.formalErrorsInOperands().map {
                "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
            }.sorted(), "$label: static")
            assertEquals("5", oracle.outcomes().single().value().orElseThrow(), "$label: dynamic value")
            assertEquals("5", java.outcomes().single().value().orElseThrow(), "$label: static value")
            val report = prepared.createInterpreter().compute(prepared.documents.readDocument(json))
            assertEquals(emptyList<Any>(), report.unsupported, label)
            assertEquals("5", report.results.single().value, "$label: interpreter value")
            assertEquals(expected.sorted(), report.formalErrorsInOperands.map {
                "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
            }.sorted(), "$label: interpreter")
        }
        assertAll(checks)
    }
}
