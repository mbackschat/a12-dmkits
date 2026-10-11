package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaContainsCountDiffTest {
    @Test
    fun activeStringPredicatesConflateAbsentAndEmptyWithPopulatedControls() {
        val base = DocumentModelDraft.named("parcel-presence").locale("en_US")
            .rootGroup("Parcel") { root -> root.group("Legs") { legs ->
                legs.repeatable(4).stringField("Marker") { }.stringField("Kind") { }
            } }.build().toJson()
        val model = BoundModel.ofJson(base)
        val legs = model.group("/Parcel/Legs")
        val marker = model.str("/Parcel/Legs/Marker")
        val kind = model.str("/Parcel/Legs/Kind")
        var json = base
        for ((code, condition) in listOf("FILLED" to kind.filled(), "EMPTY" to kind.notFilled(),
            "EXPRESS" to kind.eq("express"), "OTHER" to kind.ne("express"))) {
            json = TypedRules.inject(json, model.rule(legs).name("Check$code").errorField(marker.field())
                .`when`(marker.filled().and(condition)).code(code).message(Dsl.en("Review this delivery leg.")).build())
        }
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(json))
        val observations = listOf(null, "", "express", "ground").map { value ->
            val row = linkedMapOf("Marker" to "kept")
            if (value != null) row["Kind"] = value
            val document = ObjectMapper().writeValueAsString(mapOf("Parcel" to mapOf("Legs" to listOf(row))))
            val dynamic = DocumentDifferential.kernel(json, document, dynamic = true)
            assertEquals(dynamic, DocumentDifferential.kernel(json, document, dynamic = false), "$value static")
            assertEquals(dynamic, DocumentDifferential.interpreter(json, document), "$value interpreter")
            dynamic
        }
        assertEquals(observations[0], observations[1], "active predicates retain the same absent/empty observation")
        assertEquals(listOf("EXPRESS", "FILLED"), observations[2].messages.map { it.code }.sorted())
        assertEquals(listOf("FILLED", "OTHER"), observations[3].messages.map { it.code }.sorted())
        assertTrue(observations[0].messages.any { it.code == "EMPTY" && it.type == "OMISSION_ERROR" })
    }

    @Test
    fun importedMatchingCountsAgreeAcrossBothKernelStrategies() {
        val numeric = """
            {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":8,
             "items":{"type":"object","properties":{"marker":{"type":"string"},"amount":{"type":"integer"},"units":{"type":"integer"}}},
             "contains":{"properties":{"amount":{"minimum":5},"units":{"maximum":10}}},"minContains":2,"maxContains":2}}}
        """.trimIndent()
        val strings = """
            {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":8,
             "items":{"type":"object","properties":{"marker":{"type":"string"},"kind":{"type":"string"}}},
             "contains":{"properties":{"kind":{"enum":["","express"]}}},"minContains":2,"maxContains":2}}}
        """.trimIndent()
        val cases = listOf(
            Triple(numeric, """[{"marker":"absent"},{"amount":5,"units":10},{"amount":4,"units":11},{"amount":5,"units":11}]""", true),
            Triple(numeric, """[{"marker":"absent"},{"amount":4,"units":11}]""", false),
            Triple(strings, """[{"marker":"absent"},{"kind":""},{"kind":"ground"}]""", true),
            Triple(strings, """[{"marker":"absent"},{"kind":""},{"kind":"express"}]""", false),
        )
        for ((i, case) in cases.withIndex()) {
            val (schema, rows, accepted) = case
            val model = JsonSchemaImporter().importSchema(schema).dmJson()
            val document = """{"Root":{"legs":$rows}}"""
            val dynamic = DocumentDifferential.kernel(model, document, dynamic = true)
            assertEquals(accepted, dynamic.messages.isEmpty(), "case $i")
            assertEquals(dynamic, DocumentDifferential.kernel(model, document, dynamic = false), "case $i static")
            assertEquals(dynamic, DocumentDifferential.interpreter(model, document), "case $i interpreter")
        }
    }
}
