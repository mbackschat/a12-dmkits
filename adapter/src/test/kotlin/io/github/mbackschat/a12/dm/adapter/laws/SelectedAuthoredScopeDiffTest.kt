package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.eval.pointerComparisonKey
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SelectedAuthoredScopeDiffTest {
    private enum class Carrier { Field, Group, Star, StarGroup }
    private val base = DocumentModelDraft.named("depot-selected-scope").locale("en_US")
        .rootGroup("Store") { store -> store.stringField("Reference") { }
            .group("Depots") { depot -> depot.repeatable(2)
                .stringField("Wanted") { }.numberField("Marker") { }.numberField("Result") { }
                .group("Bins") { bin -> bin.repeatable(3).indexField("Code")
                    .stringField("Code") { }.numberField("Stock") { }.numberField("Reserve") { }.numberField("Result") { }
                    .group("Shelf") { it.numberField("Aisle") { }.numberField("Level") { } }
                    .group("Slots") { it.repeatable(2).numberField("Qty") { } } } }
            .group("Catalog") { bin -> bin.repeatable(3).indexField("Code")
                .stringField("Code") { }.numberField("Stock") { }.numberField("Reserve") { }
                .group("Shelf") { it.numberField("Aisle") { }.numberField("Level") { } }
                .group("Slots") { it.repeatable(2).numberField("Qty") { } } } }
        .build().toJson()

    @Test
    fun selectedPayloadKeepsAncestorsButDoesNotBindItsSelectedLevel() {
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val depot = session.group("/Store/Depots")
        val bin = session.group("/Store/Depots/Bins")
        val wanted = depot.field("Wanted")
        val stock = bin.num("Stock")
        val shelf = depot.flattenGroup("Bins/Shelf")
        val wrong = mutableListOf<String>()
        for (group in listOf(false, true)) for (fieldKey in listOf(false, true)) {
            val selected = if (group) Dsl.sum(if (fieldKey) shelf.forIndex(wanted) else shelf.forIndex("b")).gt(0)
                else (if (fieldKey) stock.forIndex(wanted) else stock.forIndex("b")).gt(0)
            val error = if (group) bin.num("Shelf/Aisle") else stock
            val sibling = if (group) bin.num("Shelf/Level") else bin.num("Reserve")
            val variants = listOf(
                Triple("plain", selected, emptySet()),
                Triple("ancestor", depot.num("Marker").filled().and(selected), emptySet()),
                Triple("sibling", sibling.filled().and(selected), setOf("MVK_ERROR_FIELD_AND_ITERATION_INVALID")),
                Triple("own-before", sibling.filled().and(error.filled()).and(selected), emptySet()),
                Triple("own-after", sibling.filled().and(selected).and(error.filled()), emptySet()),
            )
            for ((label, condition, expected) in variants) {
                val rule = store.rule { it.name("Selected").code("Selected").errorField(error)
                    .`when`(condition).message(Dsl.en("Check selected stock.")).build() }
                val result = RuleValidator().check(base, rule.draft())
                val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
                val portable = rejectingCodes(TypedRules.inject(base, rule)).toSet()
                if (kernel != expected || portable != expected)
                    wrong += "group=$group fieldKey=$fieldKey $label: expected=$expected kernel=$kernel portable=$portable"
                val computation = store.computation(bin.num("Result")) { it.name("SelectedCalc")
                    .`when`(condition).calc(NumberLiteral.of("1"))
                    .message(Dsl.en("Check computed stock.")).build() }
                val computedModel = TypedRules.inject(base, computation)
                assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(computedModel), "$group $fieldKey $label computation")
                assertEquals(emptySet<String>(), rejectingCodes(computedModel).toSet(), "$group $fieldKey $label computation")
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun implicitLookupDoesNotNameTheIndexField() {
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val depot = session.group("/Store/Depots")
        val bin = session.group("/Store/Depots/Bins")
        val wanted = depot.field("Wanted")
        val code = bin.str("Code")
        for (fieldKey in listOf(false, true)) {
            val stock = bin.num("Stock")
            val payload = (if (fieldKey) stock.forIndex(wanted) else stock.forIndex("b")).gt(0)
            val index = if (fieldKey) code.forIndex(wanted) else code.forIndex("b")
            for ((label, condition, expected) in listOf(
                Triple("implicit", payload, setOf("MVK_ERROR_FIELD_NOT_REFERENCED")),
                Triple("selected-index", payload.and(index.eq("b")), emptySet()),
                Triple("guarded-index", code.filled().and(payload), emptySet()),
            )) {
                val rule = store.rule { it.name("Selected").code("Selected").errorField(code)
                    .`when`(condition).message(Dsl.en("Check selected code.")).build() }
                val result = RuleValidator().check(base, rule.draft())
                val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
                assertEquals(expected, kernel, "$fieldKey $label kernel")
                assertEquals(expected, rejectingCodes(TypedRules.inject(base, rule)).toSet(), "$fieldKey $label portable")
            }
        }
    }

    @Test
    fun aFieldKeyKeepsItsOwnBindingWhenThePayloadHasNoRepeatingAncestor() {
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val depot = session.group("/Store/Depots")
        val wanted = depot.str("Wanted")
        val marker = depot.num("Marker")
        val payloads = listOf(
            store.num("Catalog/Stock").forIndex(wanted.field()).gt(0),
            Dsl.sum(store.flattenGroup("Catalog/Shelf").forIndex(wanted.field())).gt(0),
            Dsl.sum(store.flatten("Catalog/Slots*/Qty").forIndex(wanted.field())).gt(0),
        )
        for (selected in payloads) for (guarded in listOf(false, true)) {
            val rule = store.rule { it.name("Selected").code("Selected").errorField(wanted)
                .`when`(if (guarded) marker.filled().and(selected) else selected)
                .message(Dsl.en("Check selected stock.")).build() }
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(TypedRules.inject(base, rule)))
            assertEquals(emptySet<String>(), rejectingCodes(TypedRules.inject(base, rule)).toSet(), rule.render())
        }
    }

    @Test
    fun rootOnlyErrorReferencesAreJudgedAtTheSurvivingIterationLevels() {
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val depot = session.group("/Store/Depots")
        val bin = session.group("/Store/Depots/Bins")
        val selected = bin.num("Stock").forIndex("b").gt(0)
        val rows = listOf(
            Triple(depot.num("Marker"), selected, "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
            Triple(bin.num("Reserve"), selected, "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
        )
        for ((error, condition, expected) in rows) {
            val rule = store.rule { it.name("Selected").code("Selected").errorField(error)
                .`when`(Dsl.groupFilled(store.ref()).and(condition))
                .message(Dsl.en("Check selected stock.")).build() }
            val result = RuleValidator().check(base, rule.draft())
            val kernel = result.diagnostics().filter { !result.ok() }.map { it.code().orElse("uncoded") }.toSet()
            val portable = rejectingCodes(TypedRules.inject(base, rule)).toSet()
            assertEquals(setOf(expected), kernel, rule.render())
            assertEquals(kernel, portable, rule.render())
        }
    }
    @Test
    fun selectedPayloadAndIndependentKeyEachRetainTheirOrdinaryAncestor() {
        val session = EditSession.read(base)
        val store = session.group("/Store")
        val depot = session.group("/Store/Depots")
        val wanted = depot.str("Wanted")
        for (fieldKey in listOf(false, true)) for (carrier in Carrier.entries) {
            val owner = if (fieldKey) store else depot
            val path = if (fieldKey) "Catalog" else "Bins"
            val condition: Condition = when (carrier) {
                Carrier.Field -> owner.num("$path/Stock").let { if (fieldKey) it.forIndex(wanted.field()) else it.forIndex("b") }.gt(0)
                Carrier.Group -> owner.flattenGroup("$path/Shelf").let { Dsl.sum(if (fieldKey) it.forIndex(wanted.field()) else it.forIndex("b")) }.gt(0)
                Carrier.StarGroup -> owner.flattenGroup("$path/Slots*").let { Dsl.sum(if (fieldKey) it.forIndex(wanted.field()) else it.forIndex("b")) }.gt(0)
                Carrier.Star -> owner.flatten("$path/Slots*/Qty").let { Dsl.sum(if (fieldKey) it.forIndex(wanted.field()) else it.forIndex("b")) }.gt(0)
            }
            val error = if (fieldKey) wanted.field() else when (carrier) {
                Carrier.Field -> owner.field("$path/Stock")
                Carrier.Group -> owner.field("$path/Shelf/Aisle")
                Carrier.Star, Carrier.StarGroup -> owner.field("$path/Slots/Qty")
            }
            val name = "${if (fieldKey) "Key" else "Payload"}$carrier"
            store.addRule { it.name(name).code(name).errorField(error).`when`(condition)
                .message(Dsl.en("Check selected stock.")).build() }
        }
        val model = session.toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
        val documents = listOf(0, 1).associate { active ->
            val rows = (0..1).joinToString(",") { row ->
                val value = if (row == active) 1 else 0
                """{"Wanted":"${if (row == active) "b" else "a"}","Marker":1,"Bins":[{"Code":"a","Stock":0},{"Code":"b","Stock":$value,"Shelf":{"Aisle":$value,"Level":0},"Slots":[{"Qty":$value},{"Qty":0}]}]}"""
            }
            "active depot ${active + 1}" to """{"Store":{"Reference":"S","Depots":[$rows],"Catalog":[{"Code":"a","Stock":0,"Shelf":{"Aisle":0,"Level":0},"Slots":[{"Qty":0},{"Qty":0}]},{"Code":"b","Stock":1,"Shelf":{"Aisle":1,"Level":0},"Slots":[{"Qty":1},{"Qty":0}]}]}}"""
        }
        val prepared = PreparedModel.loadExpandedJson(model)
        for ((label, document) in documents) {
            val row = label.substringAfterLast(' ')
            val expected = listOf("Field", "Group", "Star", "StarGroup").map { "Key$it|VALUE_ERROR|/Store[1]/Depots[$row]/Wanted" } +
                listOf("Field" to "Stock", "Group" to "Shelf[1]/Aisle", "Star" to "Slots[1]/Qty", "StarGroup" to "Slots[1]/Qty").map { (carrier, field) ->
                    "Payload$carrier|VALUE_ERROR|/Store[1]/Depots[$row]/Bins[2]/$field"
                }
            for (dynamic in listOf(true, false))
                assertEquals(expected.sorted(), DocumentDifferential.kernel(model, document, dynamic).messages.map { it.brief }, label)
            val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(document))
            assertEquals(emptyList<Any>(), report.unsupported)
            val messages = report.findings.map {
                MessageSignature(it.code, "${it.type.name}_ERROR", pointerComparisonKey(it.errorPath.fullName, it.errorPath.repetitionIndexes),
                    it.referenced.map { p -> pointerComparisonKey(p.fullName, p.repetitionIndexes) }.sorted(),
                    it.fillToFix.map { p -> pointerComparisonKey(p.fullName, p.repetitionIndexes) }.sorted())
            }.sorted()
            assertEquals(DocumentDifferential.kernel(model, document, true).messages, messages, "$label public interpreter")
        }
        DocumentDifferential.assertAgree(model, documents)
    }
}
