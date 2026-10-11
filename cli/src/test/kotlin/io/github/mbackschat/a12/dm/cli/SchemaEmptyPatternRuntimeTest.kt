package io.github.mbackschat.a12.dm.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import com.networknt.schema.resource.DisallowSchemaLoader
import io.github.mbackschat.a12.dm.schemakit.JsonSchemaImporter
import io.github.mbackschat.a12.dm.schemakit.TranscodeReport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SchemaEmptyPatternRuntimeTest {
    private val mapper = ObjectMapper()
    private val schemas = JsonSchemaFactory.builder(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012))
        .schemaLoaders { it.values { loaders -> loaders.clear() }.add(DisallowSchemaLoader.getInstance()) }.build()

    @Test
    fun arrayLowerBoundsAreExactOrExplicitlyOmitted(@TempDir dir: Path) {
        val failures = mutableListOf<String>()
        val rows: List<List<Map<String, String>>?> = listOf(
            emptyList(), listOf(emptyMap()), listOf(mapOf("label" to "")),
            listOf(mapOf("label" to "ready")), listOf(mapOf("kind" to "express")),
            listOf(mapOf("kind" to "ground")),
            listOf(mapOf("kind" to "express"), mapOf("kind" to "express")),
            listOf(emptyMap(), emptyMap(), emptyMap()), null,
        )
        for (property in listOf("label", "phantom")) for (required in listOf(false, true)) {
            for (rootFilled in listOf(false, true)) for (keyword in listOf("contains", "minItems")) {
                val requiredSchema = if (required) "\"required\":[\"legs\"]," else ""
                val constraint = if (keyword == "contains")
                    """"contains":{"properties":{"$property":{"pattern":""}}},"minContains":1,"maxContains":2"""
                else """"minItems":1"""
                val schema = """
                    {"type":"object",${requiredSchema}"properties":{"ref":{"type":"string"},"legs":{"type":"array","maxItems":3,
                     "items":{"type":"object","properties":{"label":{"type":"string"},"kind":{"type":"string"}}},$constraint}}}
                """.trimIndent()
                val imported = JsonSchemaImporter().importSchema(schema)
                val omitted = imported.report().dispositions().any { it.tier() == TranscodeReport.Tier.OMITTED }
                if (omitted == required) failures += "$keyword/required=$required/wrong omission disposition: ${imported.report()}"
                val supportedSchema = mapper.readTree(schema) as ObjectNode
                if (omitted) {
                    val array = supportedSchema.path("properties").path("legs") as ObjectNode
                    if (keyword == "contains") array.put("minContains", 0) else array.remove("minItems")
                }
                val original = schemas.getSchema(schema)
                assertFalse(original.validate(mapper.readTree("""{"ref":"parcel","legs":[]}""")).isEmpty())
                assertEquals(!required, original.validate(mapper.readTree("""{"ref":"parcel"}""")).isEmpty())
                val validator = schemas.getSchema(supportedSchema)
                val model = dir.resolve("$keyword-$property-$required.dm.json")
                Files.writeString(model, imported.dmJson())
                for ((row, values) in rows.withIndex()) {
                    val body = linkedMapOf<String, Any>()
                    if (rootFilled) body["ref"] = "parcel"
                    if (values != null) body["legs"] = values
                    val instance = mapper.valueToTree<JsonNode>(body)
                    val accepted = validator.validate(instance).isEmpty()
                    val document = dir.resolve("document.json")
                    Files.writeString(document, mapper.writeValueAsString(mapOf("Root" to instance)))
                    val args = arrayOf("-m", model.toString(), "model", "eval", "--instance", document.toString())
                    for ((engine, result) in listOf("kernel" to CliTestSupport.runKernel(*args), "interpreter" to CliTestSupport.run(*args))) {
                        assertEquals(0, result.code()) { result.out() + result.err() }
                        val admitted = mapper.readTree(result.out()).path("data").path("messages").isEmpty
                        if (accepted != admitted) failures += "$keyword/$property/required=$required/rootFilled=$rootFilled/row=$row/engine=$engine/accepted=$accepted/${result.out()}"
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun emptyPatternsDoNotEraseSiblingAssertions() {
        val leaf = """{"pattern":"","minLength":2}"""
        val predicates = listOf(
            """{"required":["phantom"],"properties":{"phantom":{"pattern":""}}}""",
            """{"properties":{"label":$leaf}}""",
            """{"properties":{"address":{"required":["code"],"properties":{"code":{"pattern":""}}}}}""",
        )
        for (predicate in predicates) {
            val schema = """
                {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":3,
                 "items":{"type":"object","properties":{"label":{"type":"string"},
                  "address":{"type":"object","properties":{"code":{"type":"string"}}}}},"contains":$predicate}}}
            """.trimIndent()
            val witness = mapper.readTree("""{"legs":[{"label":"x","address":{}}]}""")
            assertFalse(schemas.getSchema(schema).validate(witness).isEmpty(), predicate)
            assertTrue(JsonSchemaImporter().importSchema(schema).report().dispositions().any {
                it.tier() == TranscodeReport.Tier.OMITTED
            }, predicate)
        }
        for (arm in listOf("then", "else")) {
            val schema = """
                {"type":"object","properties":{"mode":{"type":"string"},"code":{"type":"string"}},
                 "if":{"properties":{"mode":{"const":"active"}}},"$arm":{"properties":{"code":$leaf}}}
            """.trimIndent()
            val mode = if (arm == "then") "active" else "idle"
            assertFalse(schemas.getSchema(schema).validate(mapper.readTree("""{"mode":"$mode","code":"x"}""")).isEmpty())
            assertTrue(JsonSchemaImporter().importSchema(schema).report().dispositions().any {
                it.tier() == TranscodeReport.Tier.OMITTED
            }, arm)
        }
    }

    @Test
    fun anUnanchorableUniversalCountIsReported() {
        val imported = JsonSchemaImporter().importSchema("""
            {"type":"object","required":["legs"],"properties":{"legs":{"type":"array","maxItems":3,
             "items":{"type":"object","properties":{}},"contains":{"properties":{"phantom":{"pattern":""}}}}}}
        """.trimIndent())
        assertTrue(imported.report().diagnostics().any { it.contains("scalar field") }, imported.report().toString())
        assertTrue(imported.report().dispositions().any { it.tier() == TranscodeReport.Tier.OMITTED }, imported.report().toString())
    }
}
