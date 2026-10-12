package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.readDocumentJson
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

    @Test
    fun directUnboundedDescendantRetainsFinitePrefixStructuralDiagnostics() {
        fun fixture(capacity: Int) = DocumentModelDraft.named("parcel-unbounded-prefix").locale("en_US")
            .rootGroup("Parcel") { parcel ->
                parcel.stringField("Marker") { }.group("Rows") { rows ->
                    rows.repeatable(2).group("Detail") { detail ->
                        detail.repeatable(capacity).numberField("Quantity") { it.fractionDigits(0) }
                    }
                }
            }.build().toJson()
        val json = fixture(2)
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
        val editor = EditSession.read(json).group("/Parcel")
        val marker = editor.str("Marker").field().path().value()
        val detail = editor.group("Rows/Detail").path().value()
        val bounded = io.github.mbackschat.a12.dm.interpreter.load.ModelLoader.load(json)
        org.junit.jupiter.api.Assertions.assertTrue(detail in bounded.repeatableGroups)
        val unbounded = bounded.copy(declaredReps = bounded.declaredReps - detail)
        val dynamic = RuntimeLaws.kernelEngine(json)
        val static = RuntimeLaws.kernelJavaEngine(json)
        fun signature(message: com.mgmtp.a12.kernel.md.rt.api.IMessage) = MessageSignature(
            message.errorCode, message.messageType.name,
            io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey(message.errorFieldPointer.fullName(), message.errorFieldPointer.repetitionIndexes()),
            message.referencedFieldsPointers.map { io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey(it.fullName(), it.repetitionIndexes()) }.sorted(),
            message.refOmissionErrorResponsiblePointers.map { io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey(it.fullName(), it.repetitionIndexes()) }.sorted(),
        )
        fun interpreted(definition: io.github.mbackschat.a12.dm.interpreter.model.ModelDef, document: String,
                        relevant: Set<io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity>?): List<MessageSignature> {
            val interpreter = io.github.mbackschat.a12.dm.interpreter.DmInterpreter(definition)
            val input = definition.readDocumentJson(document)
            val messages = if (relevant == null) interpreter.validateFull(input) else {
                val report = interpreter.validatePart(input, relevant)
                assertEquals(emptyList<Any>(), report.unsupported)
                report.messages
            }
            return messages.map { message ->
                MessageSignature(message.code, "${message.type.name}_ERROR",
                    io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey(message.errorPointer.fullName, message.errorPointer.repetitionIndexes),
                    message.referencedPointers.map { io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey(it.fullName, it.repetitionIndexes) }.sorted(),
                    message.fillToFixPointers.map { io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey(it.fullName, it.repetitionIndexes) }.sorted())
            }.sorted()
        }
        val routes = listOf(
            "full" to null,
            "nonempty-partial" to setOf(io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity(marker, listOf(0, 0))),
            "empty-partial" to emptySet(),
        )
        val checks = mutableListOf<Executable>()
        for (outer in listOf(2, 3)) for (present in listOf(false, true)) {
            val row = if (present) """{"Detail":[{"Quantity":1}]}""" else """{"Detail":[{}]}"""
            val document = """{"Parcel":{"Marker":"ready","Rows":[${(List(outer - 1) { "{}" } + row).joinToString(",")}]}}"""
            for ((route, relevant) in routes) {
                val id = "ancestor-row=$outer/field-present=$present/$route"
                val selection = relevant?.map { RuntimeLaws.Relevant.of(it.path, *it.indices.toIntArray()) }?.toSet()
                val groovy = (if (selection == null) dynamic.validationMessages(dynamic.instance(document), null, Locale.US)
                    else dynamic.partialValidationMessages(dynamic.instance(document), selection, null, Locale.US)).map(::signature).sorted()
                val java = (if (selection == null) static.validationMessages(static.instance(document), null, Locale.US)
                    else static.partialValidationMessages(static.instance(document), selection, null, Locale.US)).map(::signature).sorted()
                val finite = interpreted(bounded, document, relevant)
                val open = interpreted(unbounded, document, relevant)
                println("unbounded-prefix/$id|groovy=$groovy|java=$java|bounded=$finite|unbounded=$open")
                checks += Executable {
                    assertEquals(if (outer == 3 && route != "empty-partial") 2 + (if (present) 1 else 0) else 0,
                        groovy.size, "$id kernel structural occurrence liveness")
                    assertEquals(groovy, java, "$id kernel strategies")
                    assertEquals(groovy, finite, "$id finite ModelDef control")
                    assertEquals(groovy, open, "$id direct unbounded ModelDef finite prefix")
                }
                if (outer == 3 && !present && route == "full") {
                    val wider = fixture(3)
                    assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(wider))
                    val widerDynamic = RuntimeLaws.kernelEngine(wider)
                    val widerStatic = RuntimeLaws.kernelJavaEngine(wider)
                    val widerGroovy = widerDynamic.validationMessages(widerDynamic.instance(document), null, Locale.US).map(::signature).sorted()
                    val widerJava = widerStatic.validationMessages(widerStatic.instance(document), null, Locale.US).map(::signature).sorted()
                    println("unbounded-prefix/$id/descendant-bound=3|groovy=$widerGroovy|java=$widerJava")
                    checks += Executable {
                        assertEquals(groovy, widerGroovy, "$id inactive descendant-bound control")
                        assertEquals(groovy, widerJava, "$id inactive descendant-bound control, Java")
                    }
                }
            }
        }
        assertAll("twelve finite-prefix routes plus one inactive-bound control", checks)
    }

}
