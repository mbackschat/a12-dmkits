package io.github.mbackschat.a12.dm.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A number literal's exponent at the Document boundary (KERNEL-FINDINGS KF564). A12's converter is
 * `new BigDecimal(String)`, which accepts a literal exactly when its scale (fraction length minus exponent) fits
 * 32 bits; A12's reader parses every UNQUOTED token the same way before it looks at the model, so one without such
 * a scale makes the whole Document unreadable wherever it stands. The kernel rows are
 * `adapter … NumberExponentEdgeDiffTest`; this file locks the reader on every platform.
 *
 * Own domain (a sales order).
 */
class NumberExponentIngressTest {

    private val documents = PreparedModel.loadExpandedJson(MODEL).documents

    private fun stored(json: String, limits: DocumentDecodeLimits = DocumentDecodeLimits.defaults()) =
        documents.encodeDocument(documents.readDocument(json, limits))

    @Test
    fun aZeroAtEveryThirtyTwoBitScaleIsStoredAsZero() {
        for (literal in listOf("\"0e2147483648\"", "\"-0e2147483648\"", "\"0.0e2147483649\"", "0e99999999", "0e2147483648")) {
            assertEquals("""{"Order":{"Items":[{"Qty":0}]}}""", stored("""{"Order":{"Items":{"Qty":$literal}}}"""), literal)
        }
    }

    @Test
    fun aQuotedLiteralWithoutAThirtyTwoBitScaleStaysVerbatim() {
        for (literal in listOf("0e2147483649", "0.00e2147483651", "1.5e-2147483647", "0e99999999999999999999")) {
            assertEquals("""{"Order":{"Items":[{"Qty":"$literal"}]}}""", stored("""{"Order":{"Items":{"Qty":"$literal"}}}"""))
        }
    }

    @Test
    fun anUnquotedTokenWithoutAThirtyTwoBitScaleMakesTheDocumentUnreadableWhereverItStands() {
        val positions = linkedMapOf(
            "a number field" to """{"Order":{"Items":{"Qty":0e2147483649}}}""",
            "a string field" to """{"Order":{"Ref":0e2147483649}}""",
            "an unknown key" to """{"Order":{"Ref":"a","Discount":0e2147483649}}""",
            "inside an unknown subtree" to """{"Order":{"Discount":{"Tiers":[1,{"Rate":-1.5e-2147483647}]}}}""",
            "a group position" to """{"Order":{"Shipping":0e2147483649}}""",
            "a repetition that is no object" to """{"Order":{"Items":[{"Qty":1},0e2147483649]}}""",
            "inside an object at a field position" to """{"Order":{"Ref":{"Amount":0e2147483649}}}""",
            "the root id" to """{"id":0e2147483649,"Order":{}}""",
            "inside a container at the root id" to """{"id":[0e2147483649],"Order":{}}""",
        )
        for ((position, json) in positions) {
            val error = assertFailsWith<DocumentInputError>(position) { documents.readDocument(json) }
            assertEquals(DocumentInputReasonCode.MALFORMED_JSON, error.diagnostics.single().reasonCode, position)
        }
    }

    @Test
    fun aNumberIsChargedAtTheLengthItIsStoredAt() {
        // `1e999999` is stored as a million code units, the default per-field ceiling, and one more digit is over it.
        stored("""{"Order":{"Items":{"Qty":"1e999999"}}}""")
        for (literal in listOf("\"1e1000000\"", "1e1000000", "\"1e2147483648\"", "\"0e-1000000\"")) {
            val error = assertFailsWith<DocumentInputError>(literal) {
                documents.readDocument("""{"Order":{"Items":{"Qty":$literal}}}""")
            }
            val diagnostic = error.diagnostics.single()
            assertEquals(DocumentInputReasonCode.RESOURCE_LIMIT_EXCEEDED, diagnostic.reasonCode, literal)
            assertEquals(DocumentInputResource.RAW_CODE_UNITS_PER_FIELD, diagnostic.resource, literal)
            assertEquals("Order/Items/Qty", diagnostic.pointer, literal)
        }
        // Two `1e9` are 3 code units each as written and 10 as stored: 20 in total, over a ceiling of 15.
        val total = DocumentDecodeLimits.builder().maxTotalRawCodeUnits(15).build()
        val error = assertFailsWith<DocumentInputError> {
            documents.readDocument("""{"Order":{"Items":[{"Qty":"1e9"},{"Qty":"1e9"}]}}""", total)
        }
        assertEquals(DocumentInputResource.TOTAL_RAW_CODE_UNITS, error.diagnostics.single().resource)
        assertEquals(20L, error.diagnostics.single().actual)
    }

    @Test
    fun aLiteralKeptAsTextIsChargedAsWritten() {
        // A leading-zero field keeps `0001e9` as its text, never expanded, and A12's converter cannot read `1e99x`.
        val perField = DocumentDecodeLimits.builder().maxRawCodeUnitsPerField(6).build()
        assertEquals("""{"Order":{"Code":"0001e9"}}""", stored("""{"Order":{"Code":"0001e9"}}""", perField))
        assertEquals("""{"Order":{"Items":[{"Qty":"1e99x"}]}}""", stored("""{"Order":{"Items":{"Qty":"1e99x"}}}""", perField))
    }

    private companion object {
        val MODEL = """
            {
              "header":{"id":"sales-orders"},
              "content":{
                "modelRoot":{"rootGroups":[{
                  "type":"Group","id":"/Order","name":"Order",
                  "Group":{"elements":[
                    {"type":"Field","id":"/Order/Ref","name":"Ref","Field":{"fieldType":{"type":"StringType"}}},
                    {
                      "type":"Field","id":"/Order/Code","name":"Code",
                      "Field":{"fieldType":{"type":"NumberType","NumberType":{"leadingZerosAllowed":true}}}
                    },
                    {
                      "type":"Group","id":"/Order/Items","name":"Items",
                      "Group":{"repeatability":3,"elements":[
                        {"type":"Field","id":"/Order/Items/Qty","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}}
                      ]}
                    },
                    {
                      "type":"Group","id":"/Order/Shipping","name":"Shipping",
                      "Group":{"elements":[
                        {"type":"Field","id":"/Order/Shipping/Mode","name":"Mode","Field":{"fieldType":{"type":"StringType"}}}
                      ]}
                    }
                  ]}
                }]}
              }
            }
        """.trimIndent()
    }
}
