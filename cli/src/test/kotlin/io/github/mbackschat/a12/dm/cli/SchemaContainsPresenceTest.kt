package io.github.mbackschat.a12.dm.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.resource.DisallowSchemaLoader
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter
import io.github.mbackschat.a12.dm.schemakit.TranscodeReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SchemaContainsPresenceTest {
    private val mapper = ObjectMapper()
    private val schemas = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
        .schemaLoaders { it.values { loaders -> loaders.clear() }.add(DisallowSchemaLoader.getInstance()) }.build()

    @Test
    fun emptyRejectingObjectPredicatesReportTheErasedPresenceDistinction(@TempDir dir: Path) {
        for (clause in listOf("""{"const":"express"}""", """{"enum":["express","priority"]}""", """{"pattern":"^express$"}""")) {
            for (minimum in listOf(false, true)) {
                val bound = if (minimum) "\"minContains\":1" else "\"minContains\":0,\"maxContains\":0"
                val source = schema("""{"properties":{"kind":$clause}}""", """"kind":{"type":"string"}""", bound)
                val imported = JsonSchemaImporter().importSchema(source)
                val validator = schemas.getSchema(source)
                val actual = mutableListOf<Boolean>()
                for ((i, value) in listOf(null, "", "express", "ground").withIndex()) {
                    val row = linkedMapOf("marker" to "kept")
                    if (value != null) row["kind"] = value
                    val instance = mapper.valueToTree<JsonNode>(mapOf("legs" to listOf(row)))
                    val matches = i == 0 || i == 2
                    assertEquals(if (minimum) matches else !matches, validator.validate(instance).isEmpty())
                    actual += evaluate(imported.dmJson(), instance, dir)
                }
                val label = "$clause/minimum=$minimum"
                assertEquals(listOf(true, true, true, true), actual, "$label must omit the whole count")
                assertEquals(0, imported.report().dispositions().count { it.tier() == TranscodeReport.Tier.TIER_A_PLUS }, label)
                assertTrue(imported.report().dispositions().any { it.tier() == TranscodeReport.Tier.OMITTED && it.source() == "/Root/legs" }, label)
                assertTrue(imported.report().diagnostics().any { it.contains("absent/empty") && it.contains("retain source-schema validation") }, label)
            }
        }
    }

    @Test
    fun emptyAcceptingObjectPredicatesCountMissingAndEmptyValues(@TempDir dir: Path) {
        val failures = mutableListOf<String>()
        for ((clause, count) in listOf("""{"const":""}""" to 2, """{"enum":["","express"]}""" to 3)) {
          for (property in listOf("kind", "value")) {
            val rows = listOf(mapOf("marker" to "absent"), mapOf("marker" to "empty", property to ""),
                mapOf("marker" to "matched", property to "express"), mapOf("marker" to "other", property to "ground"))
            val instance = mapper.valueToTree<JsonNode>(mapOf("legs" to rows))
            for (bound in listOf(count - 1, count, count + 1)) {
                val source = schema("""{"properties":{"$property":$clause}}""", """"$property":{"type":"string"}""",
                    """"minContains":$bound,"maxContains":$bound""")
                val imported = JsonSchemaImporter().importSchema(source)
                assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED })
                val accepted = schemas.getSchema(source).validate(instance).isEmpty()
                assertEquals(bound == count, accepted)
                if (accepted != evaluate(imported.dmJson(), instance, dir)) failures += "$property/$clause/bound=$bound"
            }
          }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun enumObjectPropertiesRetainMissingMatches(@TempDir dir: Path) {
        val instance = mapper.readTree("""{"legs":[{"marker":"absent"},{"kind":"express"},{"kind":"ground"}]}""")
        for (bound in listOf(1, 2, 3)) {
            val source = schema("""{"properties":{"kind":{"const":"express"}}}""",
                """"kind":{"type":"string","enum":["express","ground"]}""",
                """"minContains":$bound,"maxContains":$bound""")
            val imported = JsonSchemaImporter().importSchema(source)
            assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED })
            assertEquals(bound == 2, schemas.getSchema(source).validate(instance).isEmpty())
            assertEquals(bound == 2, evaluate(imported.dmJson(), instance, dir))
        }
    }

    @Test
    fun nestedObjectAbsenceRemainsAMatchingRow(@TempDir dir: Path) {
        val instance = mapper.readTree("""{"legs":[
            {"marker":"absent"},{"marker":"nested","address":{}},{"marker":"blank","address":{"code":""}},
            {"marker":"matched","address":{"code":"express"}},{"marker":"other","address":{"code":"ground"}}
        ]}""")
        for (bound in listOf(3, 4, 5)) {
            val source = schema("""{"properties":{"address":{"properties":{"code":{"enum":["","express"]}}}}}""",
                """"address":{"type":"object","properties":{"code":{"type":"string"}}}""",
                """"minContains":$bound,"maxContains":$bound""")
            assertEquals(bound == 4, schemas.getSchema(source).validate(instance).isEmpty())
            assertEquals(bound == 4, evaluate(JsonSchemaImporter().importSchema(source).dmJson(), instance, dir))
        }
    }

    @Test
    fun numericObjectCountsSubtractEachFailingRowOnce(@TempDir dir: Path) {
        val rows = listOf(
            mapOf("marker" to "both", "amount" to 5, "units" to 10),
            mapOf("marker" to "absent"),
            mapOf("marker" to "amount", "amount" to 4, "units" to 10),
            mapOf("marker" to "units", "amount" to 5, "units" to 11),
            mapOf("marker" to "double", "amount" to 4, "units" to 11),
        )
        val instance = mapper.valueToTree<JsonNode>(mapOf("legs" to rows))
        val failures = mutableListOf<String>()
        for (reversed in listOf(false, true)) for (bound in listOf(1, 2, 3)) {
            val clauses = if (reversed) """"units":{"maximum":10},"amount":{"minimum":5}"""
                else """"amount":{"minimum":5},"units":{"maximum":10}"""
            val source = schema("""{"properties":{$clauses}}""",
                """"amount":{"type":"integer"},"units":{"type":"integer"}""",
                """"minContains":$bound,"maxContains":$bound""")
            val imported = JsonSchemaImporter().importSchema(source)
            assertTrue(imported.report().dispositions().none { it.tier() == TranscodeReport.Tier.OMITTED })
            val accepted = schemas.getSchema(source).validate(instance).isEmpty()
            assertEquals(bound == 2, accepted)
            val actual = evaluate(imported.dmJson(), instance, dir)
            if (accepted != actual) failures += "reversed=$reversed/bound=$bound/accepted=$accepted/actual=$actual"
        }
        assertEquals(emptyList<String>(), failures)
    }

    private fun schema(predicate: String, properties: String, bound: String): String = """
        {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":8,
         "items":{"type":"object","properties":{"marker":{"type":"string"},$properties}},
         "contains":$predicate,$bound}}}
    """.trimIndent()

    private fun evaluate(dm: String, instance: JsonNode, dir: Path): Boolean {
        val model = dir.resolve("parcel.dm.json")
        val document = dir.resolve("parcel.document.json")
        Files.writeString(model, dm)
        Files.writeString(document, mapper.writeValueAsString(mapOf("Root" to instance)))
        val args = arrayOf("-m", model.toString(), "model", "eval", "--instance", document.toString())
        val actual = listOf(CliTestSupport.runKernel(*args), CliTestSupport.run(*args)).map { result ->
            assertEquals(0, result.code()) { result.out() + result.err() }
            val messages = mapper.readTree(result.out()).path("data").path("messages")
            assertTrue(messages.isArray, result.out())
            messages.isEmpty
        }
        assertEquals(actual[0], actual[1], "kernel/interpreter parity")
        return actual[0]
    }
}
