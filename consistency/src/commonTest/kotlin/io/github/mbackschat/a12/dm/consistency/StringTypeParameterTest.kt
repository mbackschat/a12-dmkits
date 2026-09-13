package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the string type's parameter grid.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.StringTypeParameterLawsTest`. All five codes REFUSE models, so the controls carry
 * the risk here: `maxLength` beside `noValueValidation`, an empty hint list, and every sentinel row are
 * models A12 ADMITS, and reporting one of them is the direction this checker may never take.
 */
class StringTypeParameterTest {

    private val paramsInconsistent = "MVK_STRING_TYPE_PARAMS_INCONSISTENT"
    private val noValueNeedsLineBreaks = "MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK"
    private val noValueAndHints = "MVK_NO_VAL_AND_HINT_LISTS"
    private val lineBreaksAndLengthOne = "MVK_LINE_BREAK_AND_MAX_LENGTH_1_INVALID"
    private val minAboveMax = "MVK_MIN_LENGTH_BIGGER_MAX_LENGTH"

    private val pattern =
        """"pattern":"[A-Z]+","errorMessage":[{"locale":"en_US","text":"letters only"}]"""
    private val hintList = """"hintList":[{"locale":"en_US","values":["AB"]}]"""

    /** A model whose one string field carries [stringType] as its type body. */
    private fun codes(stringType: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType",
                  "StringType":{$stringType}}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    @Test
    fun whatAStringTypeMayDeclareAlongsideNoValueValidation() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary string type — the control"] = codes(""""maxLength":40""")
        actual["no-value validation alone"] = codes(""""noValueValidation":true""")
        actual["no-value validation with line breaks"] =
            codes(""""noValueValidation":true,"lineBreaksPermitted":true""")
        actual["…and a pattern"] = codes(""""noValueValidation":true,"lineBreaksPermitted":true,$pattern""")
        actual["…and a minimum length"] =
            codes(""""noValueValidation":true,"lineBreaksPermitted":true,"minLength":2""")
        actual["…and a maximum length"] =
            codes(""""noValueValidation":true,"lineBreaksPermitted":true,"maxLength":40""")
        actual["…and a hint list"] =
            codes(""""noValueValidation":true,"lineBreaksPermitted":true,$hintList""")

        // Each excluded member on its own, so a row above is attributed to the pair rather than the member.
        actual["a pattern on its own"] = codes(pattern)
        actual["a minimum length on its own"] = codes(""""minLength":2""")
        actual["a hint list on its own"] = codes(hintList)

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary string type — the control"] = emptyList()
        expected["no-value validation alone"] = listOf(noValueNeedsLineBreaks)
        expected["no-value validation with line breaks"] = emptyList()
        expected["…and a pattern"] = listOf(paramsInconsistent)
        expected["…and a minimum length"] = listOf(paramsInconsistent)
        expected["…and a maximum length"] = emptyList()
        expected["…and a hint list"] = listOf(noValueAndHints)
        expected["a pattern on its own"] = emptyList()
        expected["a minimum length on its own"] = emptyList()
        expected["a hint list on its own"] = emptyList()
        assertEquals(expected, actual, "what a string type may declare alongside no-value validation")
    }

    @Test
    fun theTwoLengthLawsAndTheLineBreakOneBesideThem() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a minimum above the maximum"] = codes(""""minLength":5,"maxLength":3""")
        actual["a minimum EQUAL to the maximum — the boundary"] = codes(""""minLength":3,"maxLength":3""")
        actual["line breaks with a maximum of one"] = codes(""""lineBreaksPermitted":true,"maxLength":1""")
        actual["a maximum of one on its own"] = codes(""""maxLength":1""")
        actual["line breaks with a maximum of two"] = codes(""""lineBreaksPermitted":true,"maxLength":2""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a minimum above the maximum"] = listOf(minAboveMax)
        expected["a minimum EQUAL to the maximum — the boundary"] = emptyList()
        expected["line breaks with a maximum of one"] = listOf(lineBreaksAndLengthOne)
        expected["a maximum of one on its own"] = emptyList()
        expected["line breaks with a maximum of two"] = emptyList()
        assertEquals(expected, actual, "the two length laws and the line-break one beside them")
    }

    /**
     * A12's `-1` is NO bound, and reading it as a number would report three models A12 admits.
     *
     * This is the row the Java kit got wrong for months, on the read path, so it is locked on this carrier
     * too rather than left to the shared reasoning.
     */
    @Test
    fun theUndefinedSentinelIsNoBoundRatherThanANegativeOne() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a real minimum with an UNDEFINED maximum"] = codes(""""minLength":2,"maxLength":-1""")
        actual["an UNDEFINED minimum"] = codes(""""minLength":-1,"maxLength":3""")
        actual["line breaks with an UNDEFINED maximum"] =
            codes(""""lineBreaksPermitted":true,"maxLength":-1""")
        actual["no-value validation with line breaks and an UNDEFINED minimum"] =
            codes(""""noValueValidation":true,"lineBreaksPermitted":true,"minLength":-1""")
        actual["no-value validation with line breaks and an EMPTY hint list"] =
            codes(""""noValueValidation":true,"lineBreaksPermitted":true,"hintList":[]""")

        assertEquals(
            actual.keys.associateWith { emptyList<String>() },
            actual,
            "every one of these is a model A12 admits",
        )
    }

    /** Two independent laws on one type surface as two findings, because A12 reports both. */
    @Test
    fun twoParameterConflictsOnOneTypeAreBothReported() {
        assertEquals(
            listOf(paramsInconsistent, noValueNeedsLineBreaks),
            codes(""""noValueValidation":true,$pattern"""),
        )
    }
}
