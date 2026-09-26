package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the duplicate and root-group gates on the three value-list quantifiers, whose field
 * list sits before an `In` rather than being the call's own operands.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.EntityListRootGateCarrierLawsTest` (KERNEL-FINDINGS KF388). The rows that carry the
 * risk are the ROOT rows whose root holds a Date or sits beside a text field: the value list's own kind and
 * same-type gates would answer there if they expanded the root, and A12 answers the root class instead.
 */
class ValueListEntityPreGatesTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"courier","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Parcel","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Handling","Group":{"elements":[
                  {"type":"Field","id":"f2","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}},
              {"type":"Group","id":"g2","name":"Depot","Group":{"elements":[
                {"type":"Field","id":"f3","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
              ]}},
              {"type":"Group","id":"g3","name":"Ledger","Group":{"elements":[
                {"type":"Field","id":"f4","name":"Booked","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(call: String): List<String> = codes("FieldFilled(Reference) And $call")

    @Test
    fun theWrittenOutSpellingAsksTheDuplicateAndRootGatesOfItsFieldList() {
        val byList = linkedMapOf(
            "/Parcel In \\\"x\\\"" to listOf("MVK_ROOT_GROUP_REFERENCED"),
            "/Parcel, /Depot/Code In \\\"x\\\"" to listOf("MVK_ROOT_GROUP_REFERENCED"),
            "/Parcel, /Parcel/Label In \\\"x\\\"" to listOf("MVK_DUPLICATE_PARAM2"),
            "/Ledger In \\\"x\\\"" to listOf("MVK_ROOT_GROUP_REFERENCED"),
            "/Ledger/Booked In \\\"x\\\"" to listOf("MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED"),
            "/Parcel/Handling In \\\"x\\\"" to emptyList(),
            "/Parcel/Handling, /Depot/Code In \\\"x\\\"" to emptyList(),
        )
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        QUANTIFIERS.forEach { quantifier ->
            byList.forEach { (list, codes) ->
                val call = "$quantifier($list)"
                actual[call] = subject(call)
                expected[call] = codes
            }
        }
        assertEquals(expected, actual, "the written-out spelling's field-list gates")
    }

    /**
     * A12 answers the root class on either side of a field-read list, and beside the root's own field across
     * the `In` as well. The checker reads the pre-gates on the written-out spelling only, so it is silent
     * here, and deliberately: the value list's own gates must not answer for a root they would expand.
     */
    @Test
    fun aRootInAFieldReadListSilencesTheValueListsOwnGates() {
        val actual = linkedMapOf<String, List<String>>()
        listOf("/Ledger In /Depot/Code", "/Depot/Code In /Ledger", "/Parcel In /Parcel/Label").forEach { list ->
            actual[list] = subject("AtLeastOneFieldValueIncludedInValueList($list)")
        }
        assertEquals(
            mapOf(
                "/Ledger In /Depot/Code" to emptyList(),
                "/Depot/Code In /Ledger" to emptyList(),
                "/Parcel In /Parcel/Label" to emptyList<String>(),
            ),
            actual,
            "a root in a field-read list",
        )
    }

    private companion object {
        val QUANTIFIERS = listOf(
            "AtLeastOneFieldValueIncludedInValueList",
            "NoFieldValueIncludedInValueList",
            "NotAllFieldValuesIncludedInValueList",
        )
    }
}
