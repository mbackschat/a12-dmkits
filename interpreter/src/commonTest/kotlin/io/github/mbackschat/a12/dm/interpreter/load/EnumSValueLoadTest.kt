package io.github.mbackschat.a12.dm.interpreter.load

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Multiplatform (JVM + JS) lock for the enum S-value domain guard (IF221, graduated from IG104(b)): a declared
 * `sValue` must be one of the enum's declared values. A foreign raw DM-JSON model whose s-value is out-of-domain
 * is a MODEL-consistency error the kernel refuses to even generate code for (`MVK_S_VALUE_INVALID`), so
 * [io.github.mbackschat.a12.dm.interpreter.load.ModelLoader] rejects it LOUD rather than silently staging the
 * out-of-domain token (which previously fabricated a spurious runtime formal error). The valid twin loads.
 */
class EnumSValueLoadTest {

    private fun model(sValue: String) = """
        {
          "header": { "id": "order" },
          "content": { "modelRoot": { "rootGroups": [ {
            "type": "Group", "id": "/Order", "name": "Order", "Group": { "elements": [
              { "type": "Field", "id": "/Order/ServiceLevel", "name": "ServiceLevel", "Field": { "fieldType": {
                "type": "EnumerationType",
                "EnumerationType": { "values": [ {"value":"STD"}, {"value":"EXPRESS"} ], "sValue": "$sValue" } } } }
            ] } } ] } }
        }
    """.trimIndent()

    @Test fun anInDomainSValueLoads() {
        val def = ModelLoader.load(model("STD"))
        assertEquals("STD", def.fields.single().enumSValue, "the in-domain s-value loads")
    }

    @Test fun anOutOfDomainSValueIsRejectedLoud() {
        val e = assertFailsWith<IllegalArgumentException> { ModelLoader.load(model("OVERNIGHT")) }
        val message = e.message ?: ""
        assertContains(message, "OVERNIGHT")
        assertContains(message, "/Order/ServiceLevel")
        assertContains(message, "MVK_S_VALUE_INVALID")
    }
}
