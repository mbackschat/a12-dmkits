package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A rule's `guiPatterns` and `variantErrorMessages`, held to the grid A12 was measured on.
 *
 * The rows mirror `rulekit.validate.laws.RuleVariantPresentationLawsTest` one for one, with `ADMITTED` there
 * reading as no finding here. **The rows that matter most are the admissions**: `guiPatterns` documents a
 * closed vocabulary in its own javadoc that A12 enforces nowhere, so an undocumented key and an undocumented
 * value must both pass — translating that javadoc would refuse models the Kernel accepts.
 *
 * **The two members' NULL-value rows are deliberately both here**, because they are the pair that does not
 * transfer: `Map<String, String>` takes a null value and `Map<String, ILocalizedTextMap>` does not. A checker
 * sharing one predicate between the members would be wrong on exactly that row, in whichever direction it
 * picked.
 */
class PresentationMembersTest {

    private val textMap = """[{"locale":"en_US","text":"premium wording"}]"""

    private fun source(members: String) = ModelSource(
        """
        {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
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

    @Test
    fun aVariantErrorMessagesMapIsCheckedForBindingAndNeverForItsVARIANTNAMES() {
        val actual = mutableMapOf<String, List<String>>()
        actual["absent — the control"] = codes("variantErrorMessages", null)
        actual["an empty map"] = codes("variantErrorMessages", "{}")
        actual["a variant no filter names"] = codes("variantErrorMessages", """{"premium":$textMap}""")
        actual["the default variant, named by empty string"] = codes("variantErrorMessages", """{"":$textMap}""")
        actual["an empty text map"] = codes("variantErrorMessages", """{"premium":[]}""")
        actual["a plain string where a text map belongs"] =
            codes("variantErrorMessages", """{"premium":"premium wording"}""")
        actual["a number where a text map belongs"] = codes("variantErrorMessages", """{"premium":7}""")
        actual["a null value"] = codes("variantErrorMessages", """{"premium":null}""")
        actual["an explicit null"] = codes("variantErrorMessages", "null")
        actual["a list instead of a map"] = codes("variantErrorMessages", "[]")
        actual["one entry object rather than a list of them"] =
            codes("variantErrorMessages", """{"premium":{"locale":"en_US","text":"x"}}""")
        actual["an entry with no locale"] = codes("variantErrorMessages", """{"premium":[{"text":"x"}]}""")
        actual["an entry with no text"] = codes("variantErrorMessages", """{"premium":[{"locale":"en_US"}]}""")

        val expected = mutableMapOf<String, List<String>>()
        expected["absent — the control"] = emptyList()
        expected["an empty map"] = emptyList()
        // Any variant name is admitted, including one nothing declares and the default's empty string, so
        // this family never reconciles the key against a `variantFilter`.
        expected["a variant no filter names"] = emptyList()
        expected["the default variant, named by empty string"] = emptyList()
        expected["an empty text map"] = emptyList()
        // A scalar in the text-map slot binds, the same leniency the sibling member shows one level up.
        expected["a plain string where a text map belongs"] = emptyList()
        expected["a number where a text map belongs"] = emptyList()
        // The row that does NOT match the sibling member, where the identical shape is admitted.
        expected["a null value"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["an explicit null"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["a list instead of a map"] = listOf(CK_MODEL_NOT_BUILDABLE)
        // The list is required as the container even to hold a single well-formed entry.
        expected["one entry object rather than a list of them"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["an entry with no locale"] = listOf(CK_MODEL_NOT_BUILDABLE)
        expected["an entry with no text"] = listOf(CK_MODEL_NOT_BUILDABLE)
        assertEquals(expected, actual, "what the checker reports for a rule's variantErrorMessages")
    }

    /** Neither member exists on a computation, so neither is read there however it is spelled. */
    @Test
    fun neitherMemberIsReadOnAComputation() {
        val source = ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Price","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Computation","id":"c0","name":"PriceCalc","Computation":{
                  "computationAlternatives":[{"operation":"42.00"}],
                  "guiPatterns":[],"variantErrorMessages":null}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
        val prepared = prepare(assertIs<Admission.Admitted>(admit(source)))
        assertEquals(emptyList(), prepared.tracked().presentationMemberFindings().map { it.code })
    }
}
