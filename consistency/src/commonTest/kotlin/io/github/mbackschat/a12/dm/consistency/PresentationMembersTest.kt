package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A rule's `guiPatterns`, held to the grid A12 was measured on.
 *
 * The rows mirror `rulekit.validate.laws.RuleGuiPatternsLawsTest` one for one, with `ADMITTED` there reading as
 * no finding here. **The rows that matter most are the admissions**: `guiPatterns` documents a closed vocabulary
 * in its own javadoc that A12 enforces nowhere, so an undocumented key and an undocumented value must both pass —
 * translating that javadoc would refuse models the Kernel accepts.
 */
class PresentationMembersTest {

    private val textMap = """[{"locale":"en_US","text":"premium wording"}]"""

    private fun source(members: String) = ModelSource(
        """
        {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Rule","id":"r0","name":"PriceRule","Rule":{
              "errorCondition":"FieldFilled(Price)","errorEntityRelPath":"../Price",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]$members}}
          ]}}
         ]}}}
        """.trimIndent(),
    )

    /** The codes reported for a rule carrying [member] set to [rawJson], or nothing when null. */
    private fun codes(member: String, rawJson: String?): List<String> {
        val members = rawJson?.let { ""","$member":$it""" } ?: ""
        val prepared = prepare(assertIs<Admission.Admitted>(admit(source(members))))
        return prepared.tracked().presentationMemberFindings().map { it.code }
    }

    @Test
    fun aGuiPatternsMapIsCheckedForBindingAndNeverForItsVOCABULARY() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codes("guiPatterns", null)
        actual["an empty map"] = codes("guiPatterns", "{}")
        actual["the one documented pattern"] = codes("guiPatterns", """{"dynamicEnumeration":"true"}""")
        actual["a value outside the documented two"] = codes("guiPatterns", """{"dynamicEnumeration":"maybe"}""")
        actual["a key outside the documented one"] = codes("guiPatterns", """{"notAPattern":"true"}""")
        actual["a JSON boolean value"] = codes("guiPatterns", """{"dynamicEnumeration":true}""")
        actual["a null value"] = codes("guiPatterns", """{"dynamicEnumeration":null}""")
        actual["an explicit null"] = codes("guiPatterns", "null")
        actual["a list instead of a map"] = codes("guiPatterns", "[]")
        actual["a bare string instead of a map"] = codes("guiPatterns", "\"dynamicEnumeration\"")
        actual["an object value"] = codes("guiPatterns", """{"dynamicEnumeration":{"a":"b"}}""")
        actual["a list value"] = codes("guiPatterns", """{"dynamicEnumeration":[1]}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["absent — the control"] = emptyList()
        expected["an empty map"] = emptyList()
        expected["the one documented pattern"] = emptyList()
        // The two rows this family exists to get right: A12 admits both, so reporting either would refuse a
        // model the Kernel accepts.
        expected["a value outside the documented two"] = emptyList()
        expected["a key outside the documented one"] = emptyList()
        // A scalar of any type binds, because it is coerced into the String value.
        expected["a JSON boolean value"] = emptyList()
        expected["a null value"] = emptyList()
        expected["an explicit null"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a list instead of a map"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a bare string instead of a map"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["an object value"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a list value"] = listOf(CK_MODEL_NOT_BUILDABLE)
        assertEquals(expected, actual, "what the checker reports for a rule's guiPatterns")
    }

    /** The member does not exist on a computation, so it is not read there however it is spelled. */
    @Test
    fun theMemberIsNotReadOnAComputation() {
        val source = ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                  "computationAlternatives":[{"operation":"42.00"}],
                  "guiPatterns":[]}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
        val prepared = prepare(assertIs<Admission.Admitted>(admit(source)))
        assertEquals(emptyList(), prepared.tracked().presentationMemberFindings().map { it.code })
    }
}
