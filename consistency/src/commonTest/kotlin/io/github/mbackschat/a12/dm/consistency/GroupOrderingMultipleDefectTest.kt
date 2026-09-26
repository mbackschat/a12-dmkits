package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a group reports a defect on BOTH ordering members".
 *
 * Mirrors `rulekit.validate.laws.GroupOrderingMultipleDefectLawsTest`. Nothing is withheld between the two
 * members, so the rows here are about the class the pairing pass turned up: `MVK_SORT_FIELD_DUPLICATE`, a
 * verdict on the LIST rather than on an entry, which the checker did not emit at all.
 *
 * **One divergence is deliberate and measured.** A12 draws ONE `MVK_SORT_FIELD_NO_VALUE_VALIDATION` however
 * many sort entries are opaque; the checker draws one per entry, which is the count divergence
 * [KERNEL-FINDINGS KF340] already decided to keep — a per-entry finding names the field to fix, and it says
 * nothing A12 did not.
 */
class GroupOrderingMultipleDefectTest {

    private val indexType = "MVK_INDEX_FIELD_NO_VALUE_VALIDATION"
    private val sortType = "MVK_SORT_FIELD_NO_VALUE_VALIDATION"
    private val sortDuplicate = "MVK_SORT_FIELD_DUPLICATE"

    @Test
    fun bothOrderingMembersReportAndARepeatedSortFieldIsItsOwnVerdict() {
        val actual = mutableMapOf<String, List<String>>()
        actual["plain field, both members — the control"] = codes(false, "Sku", listOf("Sku"))
        actual["an opaque INDEX field alone"] = codes(true, "Sku", emptyList())
        actual["an opaque SORT field alone"] = codes(true, null, listOf("Sku"))
        actual["both members on the opaque field"] = codes(true, "Sku", listOf("Sku"))
        actual["a field sorted twice, plain"] = codes(false, null, listOf("Sku", "Sku"))
        actual["a field sorted THREE times"] = codes(false, null, listOf("Sku", "Sku", "Sku"))
        actual["TWO fields each sorted twice"] = codes(false, null, listOf("Sku", "Sku", "Qty", "Qty"))

        val expected = mutableMapOf<String, List<String>>()
        expected["plain field, both members — the control"] = emptyList()
        expected["an opaque INDEX field alone"] = listOf(indexType)
        expected["an opaque SORT field alone"] = listOf(sortType)
        // Neither member withholds the other's class — the row GroupOrderingFieldTypeLawsTest's fixture
        // deletes `indexFieldName` to avoid.
        expected["both members on the opaque field"] = listOf(indexType, sortType)
        // ONE finding whatever repeats and however often: a verdict on the list, not on an entry.
        expected["a field sorted twice, plain"] = listOf(sortDuplicate)
        expected["a field sorted THREE times"] = listOf(sortDuplicate)
        expected["TWO fields each sorted twice"] = listOf(sortDuplicate)
        assertEquals(expected, actual, "both ordering members report and a repeated sort field is its own")
    }

    /** …and the duplicate rides beside the type class rather than replacing it. */
    @Test
    fun aRepeatedSortFieldAndAnOpaqueOneAreBothReported() {
        assertEquals(
            listOf(sortDuplicate, sortType, sortType),
            codes(true, null, listOf("Sku", "Sku")),
            "the same opaque field sorted twice",
        )
    }

    /**
     * The ordering verdicts about `/Order/Items` and the fields under it.
     *
     * The type classes are anchored at the FIELD the member landed on rather than at the group, which is why
     * the filter is a prefix. `MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK` is dropped by name: A12 draws it on
     * the same fixture and so does the checker, but it belongs to the string-type family and rides along only
     * because an opaque field is what this one needs to be defective.
     */
    private fun codes(opaque: Boolean, index: String?, sortFields: List<String>): List<String> =
        checkRead(ModelSource(model(opaque, index, sortFields))).findings
            .filter { it.at.orEmpty().startsWith("/Order/Items") }
            .map { it.code }
            .filterNot { it == "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK" }

    private fun model(opaque: Boolean, index: String?, sortFields: List<String>): String {
        val skuType = if (opaque) {
            """{"type":"StringType","StringType":{"noValueValidation":true,"noLineBreak":true}}"""
        } else {
            """{"type":"StringType"}"""
        }
        val indexMember = index?.let { ""","indexFieldName":"$it"""" } ?: ""
        val sortMember = if (sortFields.isEmpty()) {
            ""
        } else {
            ""","sortFields":[${sortFields.joinToString(",") { """{"name":"$it"}""" }}]"""
        }
        return """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Items","Group":{"repeatability":50$indexMember$sortMember,
                  "elements":[
                    {"type":"Field","id":"f0","name":"Sku","Field":{"fieldType":$skuType}},
                    {"type":"Field","id":"f1","name":"Qty","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
