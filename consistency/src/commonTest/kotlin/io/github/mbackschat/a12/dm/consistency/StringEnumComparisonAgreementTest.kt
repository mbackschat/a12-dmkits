package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what a text or enumeration side of a comparison needs of its partner.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.StringEnumComparisonAgreementLawsTest`. The display-bearing rows carry the risk:
 * A12 answers them with the display-value classes, which `ENUM_DISPLAY_COMPARABILITY` owns — so these rows
 * state that the two halves of one kernel walk partition the comparison, each naming its own class where the
 * other says nothing.
 */
class StringEnumComparisonAgreementTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f3","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}]}}}},
                {"type":"Field","id":"f4","name":"Carrier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"ROAD"},{"value":"AIR"}]}}}},
                {"type":"Field","id":"f5","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[
                    {"value":"BASIC","label":[{"locale":"en_US","text":"Basic plan"}]},
                    {"value":"PLUS","label":[{"locale":"en_US","text":"Plus plan"}]}]}}}},
                {"type":"Field","id":"f6","name":"PlacedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f7","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun comparison(text: String): List<String> = codes("FieldFilled(Reference) And $text")

    private val wrongKind = listOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")

    private val outsideDomain = listOf("MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON")

    @Test
    fun whatATextOrEnumerationSideNeedsOfItsPartner() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two text declarations"] = comparison("[GiftNote] == [Reference]")
        actual["a text declaration against a number one"] = comparison("[GiftNote] == [Quantity]")
        actual["a number declaration against a text one"] = comparison("[Quantity] == [GiftNote]")
        actual["a text declaration against a written-out number"] = comparison("[GiftNote] == 1")
        actual["an enumeration against a number declaration"] = comparison("[Channel] == [Quantity]")
        actual["an enumeration against a written-out number"] = comparison("[Channel] == 1")
        actual["a number declaration against a written-out text"] = comparison("[Quantity] == \\\"a\\\"")
        actual["a text declaration against a textless enumeration"] = comparison("[GiftNote] == [Channel]")
        actual["two textless enumerations"] = comparison("[Channel] == [Carrier]")

        val expected = mutableMapOf<String, List<String>>()
        expected["two text declarations"] = emptyList()
        expected["a text declaration against a number one"] = wrongKind
        expected["a number declaration against a text one"] = wrongKind
        expected["a text declaration against a written-out number"] = wrongKind
        expected["an enumeration against a number declaration"] = wrongKind
        expected["an enumeration against a written-out number"] = wrongKind
        expected["a number declaration against a written-out text"] = wrongKind
        expected["a text declaration against a textless enumeration"] = emptyList()
        expected["two textless enumerations"] = emptyList()
        assertEquals(expected, actual, "what a text or enumeration side needs of its partner")
    }

    @Test
    fun whichWrittenOutValuesAnEnumerationAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a declared value"] = comparison("[Channel] == \\\"WEB\\\"")
        actual["an undeclared value"] = comparison("[Channel] == \\\"OUTLET\\\"")
        actual["an undeclared value on the LEFT"] = comparison("\\\"OUTLET\\\" == [Channel]")
        actual["an undeclared value under inequality"] = comparison("[Channel] != \\\"OUTLET\\\"")
        actual["a declared value of a display-bearing enumeration"] = comparison("[Tier] == \\\"BASIC\\\"")
        actual["its DISPLAY value rather than its stored one"] = comparison("[Tier] == \\\"Basic plan\\\"")

        val expected = mutableMapOf<String, List<String>>()
        expected["a declared value"] = emptyList()
        expected["an undeclared value"] = outsideDomain
        expected["an undeclared value on the LEFT"] = outsideDomain
        expected["an undeclared value under inequality"] = outsideDomain
        expected["a declared value of a display-bearing enumeration"] = emptyList()
        expected["its DISPLAY value rather than its stored one"] = outsideDomain
        assertEquals(expected, actual, "which written-out values an enumeration admits")
    }

    /** The arms that answer first, and the display-value neighbour that owns its own rows. */
    @Test
    fun theArmsThisFamilyYieldsTo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a text declaration against a date"] = comparison("[GiftNote] == [PlacedOn]")
        actual["an enumeration against a boolean"] = comparison("[Channel] == [Expedited]")
        actual["a text declaration ordered against a number"] = comparison("[GiftNote] < [Quantity]")
        actual["two written-out values"] = comparison("\\\"a\\\" == 1")
        actual["a display-bearing enumeration against a text declaration"] = comparison("[Tier] == [GiftNote]")
        actual["a display-bearing enumeration against a textless one"] = comparison("[Tier] == [Channel]")

        val expected = mutableMapOf<String, List<String>>()
        // Reported by the DATE arm, which claims every partner of another kind — this family stays silent.
        expected["a text declaration against a date"] = listOf("MVK_INVALID_COMPARE_TO_DATE")
        expected["an enumeration against a boolean"] = listOf("MVK_INVALID_COMPARE_TO_YESNO")
        expected["a text declaration ordered against a number"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        expected["two written-out values"] = listOf("MVK_INVALID_COMPARE_TO_CONST")
        // Both were this family's documented silence until 2026-09-14, when the DISPLAY half of the same
        // kernel walk got its own family — so the rows now measure that the two halves partition the
        // comparison rather than contend, which is what the empty cells could never state.
        expected["a display-bearing enumeration against a text declaration"] =
            listOf("MVK_INVALID_COMPARE_ENUM_TO_STRING")
        expected["a display-bearing enumeration against a textless one"] =
            listOf("MVK_INVALID_COMPARE_ENUM_TO_STRING")
        assertEquals(expected, actual, "the arms this family yields to")
    }
}
