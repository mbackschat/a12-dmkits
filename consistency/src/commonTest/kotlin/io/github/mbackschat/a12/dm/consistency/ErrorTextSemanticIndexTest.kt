package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the semantic index an error-text parameter may carry.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ErrorTextSemanticIndexLawsTest`. Two rows carry the risk: the index field that
 * RIDES ALONG with a bound key, and its plain sibling that does not — admitting the sibling is the wrong law
 * and refusing the index field is an over-report.
 */
class ErrorTextSemanticIndexTest {

    @Test
    fun whichSemanticIndexAnErrorTextParameterMayCarry() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain parameter the condition reads — the control"] = reporting("\$Reference\$")
        actual["the indexed field with the condition's OWN key"] = reporting("\$Shipments/Weight For \\\"T-1\\\"\$")
        actual["the indexed field with a DIFFERENT literal key"] = reporting("\$Shipments/Weight For \\\"T-9\\\"\$")
        actual["the indexed field with a FIELD key where the condition used a literal"] =
            reporting("\$Shipments/Weight For LookupCode\$")
        actual["the indexed field with NO key at all"] = reporting("\$Shipments/Weight\$")
        actual["a SIBLING field of the indexed group, the condition's key"] =
            reporting("\$Shipments/TrackingNo For \\\"T-1\\\"\$")
        actual["a PLAIN sibling of the indexed group, the condition's key"] =
            reporting("\$Shipments/Carrier For \\\"T-1\\\"\$")
        actual["a field of a group the condition never reads, with a key"] =
            reporting("\$Parcels/Volume For \\\"P-1\\\"\$")
        actual["a key on a field that does not repeat"] = reporting("\$Total For \\\"T-1\\\"\$")

        val refused = listOf("MVK_INDEX_FOR_ERROR_TEXT_INVALID")
        val expected = mutableMapOf<String, List<String>>()
        expected["a plain parameter the condition reads — the control"] = emptyList()
        expected["the indexed field with the condition's OWN key"] = emptyList()
        expected["the indexed field with a DIFFERENT literal key"] = refused
        expected["the indexed field with a FIELD key where the condition used a literal"] = refused
        // A key-less parameter is the MEMBERSHIP family's class (`INVALID_FIELD`), not this one's — naming
        // this code there would be the wrong class on a model A12 correctly refuses.
        expected["the indexed field with NO key at all"] = emptyList()
        // The discriminating pair: only the group's own index field rides along with a bound key.
        expected["a SIBLING field of the indexed group, the condition's key"] = emptyList()
        expected["a PLAIN sibling of the indexed group, the condition's key"] = refused
        expected["a field of a group the condition never reads, with a key"] = refused
        expected["a key on a field that does not repeat"] = refused
        assertEquals(expected, actual, "which semantic index an error-text parameter may carry")
    }

    /** The finding names the PAIRING, since writing the read into the condition is the fix. */
    @Test
    fun theFindingNamesThePairingTheConditionNeverWrote() {
        val finding = check(ModelSource(model("\$Parcels/Volume For \\\"P-1\\\"\$")))
            .findings.single { it.code == "MVK_INDEX_FOR_ERROR_TEXT_INVALID" }
        assertEquals("/Fulfilment/ProbeRule", finding.at)
        assertEquals(true, finding.explain.contains("/Fulfilment/Parcels/Volume"), finding.explain)
        assertEquals(true, finding.explain.contains("P-1"), finding.explain)
    }

    private fun reporting(text: String): List<String> =
        check(ModelSource(model(text))).findings.filter { it.code in OWNED }.map { it.code }

    /** An e-commerce fulfilment desk mirroring the laws test's carrier. */
    private fun model(text: String): String = """
        {"header":{"id":"fulfilment-error-text-index","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"LookupCode","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Total","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Shipments","Group":{"repeatability":10,
              "indexFieldName":"TrackingNo","elements":[
              {"type":"Field","id":"f3","name":"TrackingNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f4","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f5","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Parcels","Group":{"repeatability":10,
              "indexFieldName":"ParcelNo","elements":[
              {"type":"Field","id":"f6","name":"ParcelNo","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f7","name":"Volume","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"ProbeRule","Rule":{
              "errorCondition":"FieldFilled(Reference) And [Shipments/Weight For \"T-1\"] > 0",
              "errorEntityRelPath":"../Reference",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"$text"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        val OWNED = setOf("MVK_INDEX_FOR_ERROR_TEXT_INVALID")
    }
}
