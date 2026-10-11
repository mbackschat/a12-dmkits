package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class PartialParentGroupCoverageDiffTest {
    private fun model(capacity: Int): String {
        val session = EditSession.read(DocumentModelDraft.named("shipment-assessment-coverage").locale("en_US")
            .rootGroup("Shipment") { shipment ->
                shipment.stringField("Reference") { }.group("Assessment") { assessment ->
                    assessment.numberField("Score") { it.fractionDigits(0) }
                        .numberField("Allowance") { it.fractionDigits(0) }.group("Packages") { packages ->
                            packages.repeatable(capacity).group("Items") { items ->
                                items.repeatable(2).numberField("Quantity") { it.fractionDigits(0) }
                            }
                        }
                }.group("Approval") { it.stringField("Code") { } }
            }.build().toJson())
        val shipment = session.group("/Shipment")
        val reference = shipment.str("Reference")
        val assessment = shipment.group("Assessment")
        val approval = shipment.group("Approval")
        for ((code, condition) in listOf(
            "FILLED" to assessment.filled(), "EMPTY" to assessment.notFilled(),
            "ALL" to Dsl.allGroupsFilled(assessment, approval),
            "NONE" to Dsl.noGroupFilled(assessment, approval),
            "SOME" to Dsl.atLeastOneGroupFilled(assessment, approval),
            "NOT_ALL" to Dsl.notAllGroupsFilled(assessment, approval),
            "NOT_COLLECTIVE" to Dsl.groupsNotCollectivelyFilled(assessment, approval),
            "COUNT0" to Dsl.numberOfFilledGroups(assessment, approval).eq(0L),
            "COUNT1" to Dsl.numberOfFilledGroups(assessment, approval).eq(1L),
        )) shipment.addRule {
            it.name(code).code(code).errorField(reference).`when`(reference.filled().and(condition))
                .message(Dsl.en("Check shipment assessment.")).build()
        }
        return session.toJson()
    }

    @Test
    fun descendantCoverageUsesTheWholeDeclaredProduct() {
        val mismatches = mutableListOf<String>()
        for (capacity in listOf(2, 3)) {
            val model = model(capacity)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            val dynamic = RuntimeLaws.kernelEngine(model)
            val static = RuntimeLaws.kernelJavaEngine(model)
            val prepared = PreparedModel.loadExpandedJson(model)
            val editor = EditSession.read(model).group("/Shipment/Assessment")
            val score = editor.num("Score").field().path().value()
            val allowance = editor.num("Allowance").field().path().value()
            val items = editor.group("Packages/Items").path().value()
            val quantity = editor.num("Packages/Items/Quantity").field().path().value()
            val direct = listOf(RuntimeLaws.Relevant.of(score, 0, 0, 0), RuntimeLaws.Relevant.of(allowance, 0, 0, 0))
            fun group(p: Int, i: Int) = RuntimeLaws.Relevant.of(items, 0, 0, p, i)
            val product = (1..2).flatMap { p -> (1..2).map { i -> group(p, i) } }
            val selections = listOf(
                "whole" to listOf(RuntimeLaws.Relevant.of(editor.ref().path().value(), 0, 0)),
                "groups" to direct + product,
                "fields" to direct + (1..2).flatMap { p -> (1..2).map { i -> RuntimeLaws.Relevant.of(quantity, 0, 0, p, i, 0) } },
                "first" to direct + group(1, 1),
                "duplicate" to direct + listOf(group(1, 1), group(1, 1)),
                "out-of-capacity" to direct + product.dropLast(1) + group(2, 3),
                "missing-field" to direct.take(1) + product,
                "diagonal" to direct + listOf(group(1, 1), group(2, 2)),
            )
            val documents = listOf(
                "absent" to "{}",
                "empty-row" to """{"Packages":[{"Items":[{}]}]}""",
                "later-content" to """{"Packages":[{},{"Items":[{},{"Quantity":1}]}]}""",
                "both-filled" to """{"Score":1}""",
                "malformed-only" to """{"Score":"bad"}""",
                "admitted-beside-malformed" to """{"Score":"bad","Allowance":1}""",
            )
            for ((content, assessment) in documents) for ((selection, selected) in selections.filter {
                content != "both-filled" || it.first == "whole" || it.first == "first"
            }) {
                val case = "capacity=$capacity/content=$content/selection=$selection"
                val json = if (content == "absent") """{"Shipment":{"Reference":"dispatch"}}"""
                    else """{"Shipment":{"Reference":"dispatch","Assessment":$assessment${if (content == "both-filled") ",\"Approval\":{\"Code\":\"approved\"}" else ""}}}"""
                val relevant = (selected + listOf(RuntimeLaws.Relevant.of("/Shipment/Reference", 0, 0),
                    RuntimeLaws.Relevant.of("/Shipment/Approval", 0, 0))).toSet()
                val kernel = dynamic.partialValidationMessages(dynamic.instance(json), relevant, null, Locale.US)
                val expected = kernel.map {
                    "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted()
                assertEquals(expected, static.partialValidationMessages(static.instance(json), relevant, null, Locale.US).map {
                    "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted(), "$case: static")
                if (content == "absent") {
                    val complete = selection == "whole" || capacity == 2 && selection in setOf("groups", "fields")
                    assertEquals(complete, kernel.any { it.errorCode == "EMPTY" }, "$case: absence control")
                    assertEquals(complete, kernel.any { it.errorCode == "COUNT0" }, "$case: fixed-count control")
                }
                if (content == "both-filled") assertEquals(listOf("ALL", "FILLED", "SOME"), kernel.map { it.errorCode }.sorted(), "$case: positive controls")
                val report = prepared.createInterpreter().validatePart(prepared.documents.readDocument(json), relevant.map { pointer ->
                    prepared.model.relevantPointer(pointer.path(), pointer.reps().map {
                        if (it == 0) RelevantCoordinate.All else RelevantCoordinate.Exact(it)
                    })
                })
                assertEquals(emptyList<Any>(), report.unsupported, case)
                val actual = report.findings.map {
                    "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                }.sorted()
                if (actual != expected) mismatches += "$case: expected=$expected actual=$actual"
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
