package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldOperand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SPEC-2026-10-03-03: declared selection and deciding-prefix polarity are independent axes. */
class FilteredFillDecisionDiffTest {
    private data class Row(val id: String, val a: String?, val b: String?, val rows: List<String?>)

    @Test
    fun selectedMissingSlotsAndDecidingPrefixesAgreeOnBothValidationArms() {
        val base = DocumentModelDraft.named("filtered-invoice").locale("en_US")
            .rootGroup("Invoice") { root ->
                for (name in listOf("A", "B", "C")) root.numberField(name) { it.fractionDigits(0) }
                root.group("Rows") { it.repeatable(2).numberField("R") { n -> n.fractionDigits(0) } }
            }.build().toJson()
        val group = EditSession.read(base).group("/Invoice")
        val a = group.num("A"); val b = group.num("B"); val c = group.num("C")
        val star = group.flatten("Rows*/R")
        val first = star.having(Dsl.currentRepetition(group.group("Rows")).eq(1))
        val lists: List<List<FieldOperand>> = listOf(listOf(star), listOf(first), listOf(a.field(), first),
            listOf(first, a.field()), listOf(star.having(group.num("Rows/R").gt(0))),
            listOf(star.having(Dsl.currentRepetition(group.group("Rows")).gt(0))), listOf(a.field(), b.field(), first))
        var json = base
        lists.forEachIndexed { f, operands ->
            val conditions = listOf(Dsl.allFieldsFilled(operands), Dsl.noFieldFilled(operands),
                Dsl.atLeastOneFieldFilled(operands), Dsl.moreThanOneFieldFilled(operands),
                Dsl.notAllFieldsFilled(operands), Dsl.notExactlyOneFieldFilled(operands), Dsl.fieldsNotCollectivelyFilled(operands))
            conditions.forEachIndexed { q, condition ->
                val code = "F${f}Q$q"
                val rule = group.rule { it.name(code).code(code).errorField(c).`when`(c.filled().and(condition)).message(Dsl.en("Check invoice.")).build() }
                json = TypedRules.inject(json, rule)
            }
        }
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(json), "every rule is admitted")
        val rows = listOf(Row("zero", null, null, emptyList()), Row("kept-filled", null, null, listOf("1")),
            Row("kept-empty", null, null, listOf(null)), Row("kept-invalid", null, null, listOf("bad")),
            Row("second-filled", null, null, listOf(null, "1")), Row("both-filled", null, null, listOf("1", "1")),
            Row("direct-only", "1", null, emptyList()), Row("both-sources", "1", null, listOf("1")),
            Row("two-direct", "1", "1", listOf("1")))
        val def = ModelLoader.load(json)
        val engine = RuntimeLaws.kernelJavaEngine(json)
        fun signature(messages: List<RuntimeLaws.Msg>) = messages.filter { it.code().startsWith("F") }
            .map { "${it.code()}|${it.field()}|${it.type().name}" }.sorted()
        val retained = mutableListOf<Map<String, Any>>()
        val expected = mutableListOf<Pair<String, List<String>>>()
        val actual = mutableListOf<Pair<String, List<String>>>()
        for (row in rows) {
            val kd = RuntimeLaws.doc().group("/Invoice", 1).field(c.field().path().value(), "1", 1, 1)
            val id = def.newDocument().group("/Invoice", 1).field(c.field().path().value(), "1", 1, 1)
            for ((field, value) in listOf(a to row.a, b to row.b)) if (value != null) {
                kd.field(field.field().path().value(), value, 1, 1); id.field(field.field().path().value(), value, 1, 1)
            }
            row.rows.forEachIndexed { index, value ->
                kd.group("/Invoice/Rows", 1, index + 1); id.group("/Invoice/Rows", 1, index + 1)
                if (value != null) { kd.field(star.field().path().value(), value, 1, index + 1, 1)
                    id.field(star.field().path().value(), value, 1, index + 1, 1) }
            }
            for (partial in listOf(false, true)) {
                val relevant = setOf(RuntimeLaws.Relevant.of("/Invoice", 1))
                val dynamic = signature(if (partial) RuntimeLaws.messagesPart(json, kd, relevant) else RuntimeLaws.messages(json, kd))
                val static = signature(if (partial) engine.messagesPart(kd, relevant) else engine.messagesModern(kd, null))
                assertEquals(dynamic, static, "${row.id}/$partial: kernel strategies")
                if (partial) assertTrue(dynamic.none { !it.startsWith("F0") }, "filtered whole-root partial controls")
                if (!partial) {
                    fun has(code: String, type: String) = "$code|/Invoice[1]/C|${type}_ERROR" in dynamic
                    when (row.id) {
                        "kept-filled" -> { assertTrue(has("F1Q0", "OMISSION")); assertTrue(has("F5Q4", "OMISSION")) }
                        "zero" -> { assertTrue(has("F1Q4", "OMISSION")); assertTrue(has("F4Q0", "OMISSION")) }
                        "both-sources" -> { assertTrue(has("F2Q2", "VALUE")); assertTrue(has("F3Q2", "OMISSION")); assertTrue(has("F6Q3", "OMISSION")) }
                        "two-direct" -> { assertTrue(has("F6Q3", "VALUE")); assertTrue(has("F6Q5", "VALUE")) }
                        "kept-invalid" -> { assertTrue(dynamic.none { it.startsWith("F1Q1|") || it.startsWith("F1Q4|") }); assertTrue(has("F4Q1", "OMISSION")) }
                    }
                }
                val interpreter = DmInterpreter(def)
                val messages = if (partial) interpreter.validatePart(id, setOf(RelevantEntity("/Invoice", listOf(1)))).messages else interpreter.validateFull(id)
                val interpreted = messages.filter { it.code.startsWith("F") }.map {
                    "${it.code}|${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}"
                }.sorted()
                retained += mapOf("id" to "${row.id}/$partial", "document" to RuntimeLaws.canonicalDocumentJson(json, kd),
                    "partial" to partial, "messages" to dynamic)
                expected += "${row.id}/$partial" to dynamic
                actual += "${row.id}/$partial" to interpreted
            }
        }
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val stored = mapper.readTree(checkNotNull(javaClass.getResource("/filtered-fill-decisions.json")))
        assertEquals(stored, mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("model" to json, "rows" to retained)), "retained typed fixture")
        assertEquals(expected, actual, "all 441 outcomes on each validation arm, without collapsing identities")
    }
}
