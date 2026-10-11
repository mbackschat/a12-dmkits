package io.github.mbackschat.a12.dm.schemakit

import io.github.mbackschat.a12.dm.modelkit.ModelDocument
import io.github.mbackschat.a12.dm.modelkit.Requiredness
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Optional

class ArrayMinimumApplicabilityTest {
    private val array = """
        {"type":"array","minItems":1,"maxItems":3,"items":{"type":"object",
         "properties":{"label":{"type":"string"}}}}
    """.trimIndent()

    @Test
    fun rootAllOfRequirednessIsUnconditionalButNestedRequirednessIsNot() {
        val root = """
            {"type":"object","allOf":[{"properties":{"legs":$array}},{"required":["legs"]}]}
        """.trimIndent()
        assertMinimumOmitted(JsonSchemaImporter().importSchema(root), false)
        for (requiredParent in listOf(false, true)) {
            val parentRequired = if (requiredParent) "\"required\":[\"parcel\"]," else ""
            val nested = """
                {"type":"object",${parentRequired}"properties":{"parcel":{"type":"object","required":["legs"],
                 "properties":{"legs":$array}}}}
            """.trimIndent()
            assertMinimumOmitted(JsonSchemaImporter().importSchema(nested), true)
        }
    }

    @Test
    fun selectedOpenApiComponentSuppliesRootApplicability() {
        for (union in listOf(false, true)) {
            val parcel = """{"type":"object","required":["legs"],"properties":{"legs":$array}}"""
            val component = if (union) """{"oneOf":[$parcel,{"type":"object","properties":{"note":{"type":"string"}}}]}""" else parcel
            val api = """{"openapi":"3.1.0","components":{"schemas":{"Parcel":$component}}}"""
            assertMinimumOmitted(JsonSchemaImporter().importSchema(api, MappingProfile.defaults(), Dialect.AUTO, "Parcel"), union)
        }
    }

    @Test
    fun standaloneRequiredSubschemasAlsoReachScalarFields() {
        for (clause in listOf("""{"required":["label"]}""", """{"${'$'}ref":"#/${'$'}defs/RequiredLabel"}""")) {
            val imported = JsonSchemaImporter().importSchema("""
                {"type":"object","${'$'}defs":{"RequiredLabel":{"required":["label"]}},
                 "properties":{"label":{"type":"string"}},"allOf":[$clause]}
            """.trimIndent())
            assertEquals(Optional.of(Requiredness.REQUIRED), ModelDocument.read(imported.dmJson()).requiredness("/Root/label"))
        }
        val noFields = JsonSchemaImporter().importSchema("""
            {"type":"object","properties":{"parcel":{"allOf":[{"required":["label"]}]}}}
        """.trimIndent())
        assertEquals(true, noFields.report().dispositions().any {
            it.source() == "/Root/parcel" && it.tier() == TranscodeReport.Tier.OMITTED
        })
    }

    private fun assertMinimumOmitted(result: JsonSchemaImporter.ImportResult, omitted: Boolean) {
        assertEquals(omitted, result.report().dispositions().any {
            it.source().endsWith("/minItems") && it.tier() == TranscodeReport.Tier.OMITTED
        }, result.report().toString())
        assertEquals(!omitted, result.report().dispositions().any {
            it.source().endsWith("/legs") && it.target().startsWith("rule minItems(") && it.tier() == TranscodeReport.Tier.TIER_A_PLUS
        }, result.report().toString())
    }
}
