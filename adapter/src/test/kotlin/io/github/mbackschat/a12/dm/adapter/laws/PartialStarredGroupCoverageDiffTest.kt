package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.GroupListOperand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class PartialStarredGroupCoverageDiffTest {
    private val session = EditSession.read(DocumentModelDraft.named("shipment-starred-coverage").locale("en_US")
        .rootGroup("Shipment") { s ->
            s.stringField("Reference") { }.stringField("Wanted") { }
                .group("Batches") { b ->
                    b.repeatable(2).indexField("Code").stringField("Code") { }
                        .group("Parcels") { p ->
                            p.repeatable(2).numberField("Quantity") { it.fractionDigits(0) }
                                .group("Details") { d ->
                                    d.numberField("Score") { it.fractionDigits(0) }
                                        .numberField("Allowance") { it.fractionDigits(0) }
                                }.group("Items") { i ->
                                    i.repeatable(2).numberField("Quantity") { it.fractionDigits(0) }
                                }
                        }
                }.group("Approval") { it.stringField("Code") { } }
                .group("Returns") { it.repeatable(2).stringField("Reason") { } }
        }.build().toJson())
    private val shipment = session.group("/Shipment")
    private val reference = shipment.str("Reference")
    private val wanted = shipment.str("Wanted")
    private val parcels = shipment.flattenGroup("Batches*/Parcels*")
    private val selected = shipment.flattenGroup("Batches/Parcels*").forIndex(wanted.field())
    private val details = shipment.flattenGroup("Batches/Parcels*/Details").forIndex(wanted.field())
    private val items = shipment.flattenGroup("Batches/Parcels*/Items*").forIndex(wanted.field())
    private val approval = shipment.flattenGroup("Approval")
    private val returns = shipment.flattenGroup("Returns*")
    private val model = run {
        val operandLists: List<Pair<String, List<GroupListOperand>>> = listOf(
            "ALL" to listOf(parcels), "SELECTED" to listOf(selected),
            "DETAILS" to listOf(details), "ITEMS" to listOf(items),
            "DUPLICATE" to listOf(selected, selected),
            "FIXED_FIRST" to listOf(approval, selected), "FIXED_LAST" to listOf(selected, approval),
            "STAR_FIRST" to listOf(returns, selected), "STAR_LAST" to listOf(selected, returns),
        )
        for ((name, operands) in operandLists) {
            val first = operands.first()
            val rest = operands.drop(1).toTypedArray()
            for ((suffix, condition) in listOf(
                "NONE" to Dsl.noGroupFilled(first, *rest),
                "SOME" to Dsl.atLeastOneGroupFilled(first, *rest),
            )) shipment.addRule {
                val code = "${name}_$suffix"
                it.name(code).code(code).errorField(reference).`when`(reference.filled().and(condition))
                    .message(Dsl.en("Check shipment coverage.")).build()
            }
        }
        session.toJson()
    }

    @Test
    fun missingDeclaredPositionsRemainUnknownUnlessCovered() {
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
        val dynamic = RuntimeLaws.kernelEngine(model)
        val static = RuntimeLaws.kernelJavaEngine(model)
        val prepared = PreparedModel.loadExpandedJson(model)
        val root = shipment.ref().path().value()
        val batches = session.group(shipment.group("Batches").path().value())
        val parcelPath = batches.group("Parcels").path().value()
        val itemPath = batches.group("Parcels/Items").path().value()
        fun all(path: String) = RuntimeLaws.Relevant.of(path, *IntArray(path.count { it == '/' }))
        fun parcel(parent: Int, slot: Int) = RuntimeLaws.Relevant.of(parcelPath, 0, parent, slot)
        fun item(parent: Int, p: Int, i: Int) = RuntimeLaws.Relevant.of(itemPath, 0, parent, p, i)
        val prerequisites = listOf(all(reference.field().path().value()), all(wanted.field().path().value()),
            all(batches.str("Code").field().path().value()), all(approval.fieldPath()))
        val selections = listOf(
            "root" to listOf(all(root)),
            "uncovered" to emptyList(),
            "first" to listOf(parcel(1, 1)),
            "leaf" to listOf(RuntimeLaws.Relevant.of(batches.num("Parcels/Quantity").field().path().value(), 0, 1, 1, 0)),
            "concrete" to listOf(parcel(1, 1), parcel(1, 2)),
            "wildcard" to listOf(parcel(0, 1), parcel(0, 2)),
            "diagonal" to listOf(item(0, 1, 1), item(0, 2, 2)),
            "product" to (1..2).flatMap { p -> (1..2).map { i -> item(0, p, i) } },
        )
        val documents = listOf(
            "absent" to "",
            "empty" to """, "Batches":[{"Code":"A"},{"Code":"B"}]""",
            "empty-row" to """, "Batches":[{"Code":"A","Parcels":[{}]},{"Code":"B"}]""",
            "positive" to """, "Batches":[{"Code":"A","Parcels":[{"Details":{"Score":1}}]},{"Code":"B"}]""",
            "malformed" to """, "Batches":[{"Code":"A","Parcels":[{"Details":{"Score":"bad"}}]},{"Code":"B"}]""",
            "admitted-beside-malformed" to """, "Batches":[{"Code":"A","Parcels":[{"Details":{"Score":"bad","Allowance":1}}]},{"Code":"B"}]""",
            "other-parent" to """, "Batches":[{"Code":"A"},{"Code":"B","Parcels":[{}]}]""",
            "duplicate-key" to """, "Batches":[{"Code":"A"},{"Code":"A","Parcels":[{}]}]""",
            "excess" to """, "Batches":[{"Code":"A","Parcels":[{},{},{}]},{"Code":"B"}]""",
        )
        val failures = mutableListOf<String>()
        for ((content, fields) in documents) for ((selection, selectedEntities) in selections.filter {
            content in setOf("absent", "empty") || it.first in setOf("root", "uncovered", "first", "leaf")
        }) {
            val label = "$content/$selection"
            val json = """{"Shipment":{"Reference":"dispatch","Wanted":"A"$fields}}"""
            val relevant = (prerequisites + selectedEntities).toSet()
            fun signatures(messages: List<com.mgmtp.a12.kernel.md.rt.api.IMessage>) = messages.map {
                "${it.errorCode}|${it.messageType}|${it.severity}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
            }.sorted()
            val kernel = dynamic.partialValidationMessages(dynamic.instance(json), relevant, null, Locale.US)
            val expected = signatures(kernel)
            assertEquals(expected, signatures(static.partialValidationMessages(static.instance(json), relevant, null, Locale.US)), "$label/static")
            val codes = kernel.map { it.errorCode }
            if (content in setOf("absent", "empty")) {
                val complete = selection in setOf("root", "wildcard") || content == "empty" && selection == "concrete"
                assertEquals(complete, "SELECTED_NONE" in codes, "$label/absence control")
                assertEquals(selection in setOf("root", "wildcard", "product") || content == "empty" && selection == "concrete",
                    "ITEMS_NONE" in codes, "$label/product control")
            }
            if (content == "empty-row" && selection in setOf("first", "leaf")) {
                assertEquals(true, "SELECTED_SOME" in codes, "$label/structural witness")
                assertEquals(false, "DETAILS_SOME" in codes, "$label/fixed child")
            }
            val report = prepared.createInterpreter().validatePart(prepared.documents.readDocument(json), relevant.map { pointer ->
                prepared.model.relevantPointer(pointer.path(), pointer.reps().map {
                    if (it == 0) RelevantCoordinate.All else RelevantCoordinate.Exact(it)
                })
            })
            assertEquals(emptyList<Any>(), report.unsupported, label)
            val actual = report.findings.map {
                "${it.code}|${it.type.name}_ERROR|${it.severity}|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
            }.sorted()
            if (actual != expected) failures += "$label expected=$expected actual=$actual"
        }
        assertEquals(emptyList<String>(), failures)
    }
}
