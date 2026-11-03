package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class PartialDeclaredListExtentDiffTest {
    private fun model(capacity: Int): String {
        val draft = DocumentModelDraft.named("delivery-list-coverage").locale("en_US")
            .rootGroup("Delivery") { delivery ->
                delivery.stringField("Reference") { }.stringField("WantedCode") { }
                    .numberField("WantedQuantity") { it.fractionDigits(0) }
                    .group("Packages") { packages ->
                        packages.repeatable(capacity).numberField("Quantity") { it.fractionDigits(0) }
                            .numberField("Reserve") { it.fractionDigits(0) }
                    }.group("Labels") { labels ->
                        labels.repeatable(capacity).stringField("Code") { }.stringField("Description") { }
                    }
            }.build().toJson()
        val session = EditSession.read(draft)
        val delivery = session.group("/Delivery")
        val reference = delivery.str("Reference")
        fun rule(code: String, condition: Condition) {
            delivery.addRule {
                it.name(code).code(code).errorField(reference).`when`(reference.filled().and(condition))
                    .message(Dsl.en("Check the delivery selection.")).build()
            }
        }
        val quantities = delivery.flatten("Packages*/Quantity")
        val packages = delivery.flattenGroup("Packages*")
        val wantedQuantity = delivery.num("WantedQuantity").field()
        val wantedCode = delivery.str("WantedCode").field()
        for ((name, pair) in listOf(
            "NF" to (quantities to wantedQuantity),
            "NG" to (packages to wantedQuantity),
            "SF" to (delivery.flatten("Labels*/Code") to wantedCode),
            "SG" to (delivery.flattenGroup("Labels*") to wantedCode),
        )) {
            val (list, wanted) = pair
            for ((side, operands) in listOf("LEFT" to (list to wanted), "RIGHT" to (wanted to list))) {
                val (fields, values) = operands
                val valueList = listOf<ValueListOperand>(values)
                rule("${name}_${side}_ONE", Dsl.atLeastOneFieldValueIncludedInValueList(valueList, fields))
                rule("${name}_${side}_NO", Dsl.noFieldValueIncludedInValueList(valueList, fields))
                rule("${name}_${side}_NOTALL", Dsl.notAllFieldValuesIncludedInValueList(valueList, fields))
            }
        }
        for (n in 0L..1L) rule("VALUE$n", Dsl.numberOfValueInFields(10L, quantities).eq(n))
        rule("FILLED", Dsl.numberOfFilledFields(quantities).gt(0))
        rule("GROUPS", Dsl.numberOfFilledGroups(packages).gt(0))
        rule("SUM", Dsl.sum(quantities).gt(0))
        rule("DISTINCT", Dsl.numberOfDifferentValues(quantities).gt(0))
        rule("MIN", Dsl.minValue(quantities).gt(0))
        rule("MAX", Dsl.maxValue(quantities).gt(0))
        return session.toJson()
    }

    @Test
    fun completeDeclaredSlotsEstablishListExtentWithoutChangingAggregateGates() {
        val mismatches = mutableListOf<String>()
        for (capacity in listOf(2, 3)) {
            val jsonModel = model(capacity)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(jsonModel))
            val dynamic = RuntimeLaws.kernelEngine(jsonModel)
            val static = RuntimeLaws.kernelJavaEngine(jsonModel)
            val prepared = PreparedModel.loadExpandedJson(jsonModel)
            for (matchingRow in 0..2) for (emptyThird in if (capacity == 3) listOf(false, true) else listOf(false)) {
                val packages = (1..2).joinToString(",") {
                    """{"Quantity":${if (it == matchingRow) 10 else 6 + it},"Reserve":${20 + it}}"""
                } + if (emptyThird) ",{}" else ""
                val labels = (1..2).joinToString(",") {
                    """{"Code":"${if (it == matchingRow) "urgent" else "route-$it"}","Description":"label-$it"}"""
                } + if (emptyThird) ",{}" else ""
                val json = """{"Delivery":{"Reference":"dispatch","WantedQuantity":10,"WantedCode":"urgent","Packages":[$packages],"Labels":[$labels]}}"""
                for (rows in listOf(listOf(0), listOf(1, 2), listOf(1), listOf(2))) {
                    val relevant = setOf(
                        RuntimeLaws.Relevant.of("/Delivery/Reference", 0, 0),
                        RuntimeLaws.Relevant.of("/Delivery/WantedQuantity", 0, 0),
                        RuntimeLaws.Relevant.of("/Delivery/WantedCode", 0, 0),
                    ) + rows.flatMap { row -> listOf(
                        RuntimeLaws.Relevant.of("/Delivery/Packages", 0, row),
                        RuntimeLaws.Relevant.of("/Delivery/Labels", 0, row),
                    ) }
                    val case = "capacity=$capacity/match=$matchingRow/emptyThird=$emptyThird/rows=$rows"
                    val kernel = dynamic.partialValidationMessages(dynamic.instance(json), relevant, null, Locale.US)
                    val expected = kernel.map {
                        "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                    }.sorted()
                    assertEquals(expected, static.partialValidationMessages(static.instance(json), relevant, null, Locale.US).map {
                        "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                    }.sorted(), "$case: kernel strategies")
                    if (matchingRow == 0) {
                        val complete = rows == listOf(0) || (capacity == 2 && rows == listOf(1, 2))
                        val expectedControls = buildList {
                            if (complete) addAll(listOf("NF_LEFT_NO", "NF_RIGHT_NO", "NF_RIGHT_NOTALL", "VALUE0"))
                            add("NF_LEFT_NOTALL")
                            if (rows == listOf(0)) addAll(listOf("FILLED", "SUM", "DISTINCT", "MIN", "MAX"))
                            // The group count joins the declared-slot union on kernel 31.1.1: wholly relevant rows
                            // covering every slot fold into the wildcard (KERNEL-FINDINGS KF667).
                            if (complete) add("GROUPS")
                        }.sorted()
                        val controls = setOf("NF_LEFT_NO", "NF_RIGHT_NO", "NF_RIGHT_NOTALL", "NF_LEFT_NOTALL", "VALUE0",
                            "FILLED", "SUM", "DISTINCT", "MIN", "MAX", "GROUPS")
                        assertEquals(expectedControls, kernel.map { it.errorCode }.filter { it in controls }.sorted(), "$case: controls")
                    }
                    val report = prepared.createInterpreter().validatePart(prepared.documents.readDocument(json), relevant.map { pointer ->
                        prepared.model.relevantPointer(pointer.path(), pointer.reps().map {
                            if (it == 0) RelevantCoordinate.All else RelevantCoordinate.Exact(it)
                        })
                    })
                    assertEquals(emptyList<Any>(), report.unsupported, case)
                    val actual = report.findings.map {
                        "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                    }.sorted()
                    if (expected != actual) mismatches += "$case: expected=$expected actual=$actual"
                }
                val full = dynamic.validationMessages(dynamic.instance(json), null, Locale.US).map { it.errorCode to it.messageType.toString() }.sortedBy { it.first }
                assertEquals(full, static.validationMessages(static.instance(json), null, Locale.US).map { it.errorCode to it.messageType.toString() }.sortedBy { it.first })
                val fullReport = prepared.createInterpreter().validateFull(prepared.documents.readDocument(json))
                assertEquals(full, fullReport.findings.map { it.code to "${it.type.name}_ERROR" }.sortedBy { it.first })
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }
}
