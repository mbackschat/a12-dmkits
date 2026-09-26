package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what a pattern comparison demands of its sides.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.PatternComparisonOperandLawsTest`. The rows that carry the risk are the two the
 * gate ORDER decides — a written-out value on the left is the shape class, not the type one, and a text
 * declaration on the right is the constant class, not the type one — and the empty pattern, which the
 * admission gate takes.
 */
class PatternComparisonOperandsTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0}}}},
                {"type":"Field","id":"f3","name":"Channel","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"WEB"},{"value":"STORE"}]}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun comparison(text: String): List<String> = codes("FieldFilled(Reference) And $text")

    @Test
    fun theFourQuestionsInOrder() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a text declaration against a written-out pattern"] =
            comparison("[GiftNote] PatternMatched \\\"a+\\\"")
        actual["the negated operator"] = comparison("[GiftNote] PatternViolated \\\"a+\\\"")
        actual["a CALL on the left"] = comparison("Length(GiftNote) PatternMatched \\\"a+\\\"")
        actual["a written-out value on the left"] = comparison("\\\"x\\\" PatternMatched [GiftNote]")
        actual["a number declaration on the left"] = comparison("[Quantity] PatternMatched \\\"a+\\\"")
        actual["an enumeration on the left"] = comparison("[Channel] PatternMatched \\\"a+\\\"")
        actual["a number declaration on the right"] = comparison("[GiftNote] PatternMatched [Quantity]")
        actual["a written-out number on the right"] = comparison("[GiftNote] PatternMatched 1")
        actual["a TEXT DECLARATION on the right"] = comparison("[GiftNote] PatternMatched [Reference]")

        val expected = mutableMapOf<String, List<String>>()
        expected["a text declaration against a written-out pattern"] = emptyList()
        expected["the negated operator"] = emptyList()
        expected["a CALL on the left"] = listOf("MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON")
        expected["a written-out value on the left"] = listOf("MVK_INVALID_PARAMETER_FOR_PATTERN_COMPARISON")
        expected["a number declaration on the left"] = listOf("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON")
        expected["an enumeration on the left"] = listOf("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON")
        expected["a number declaration on the right"] = listOf("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON")
        expected["a written-out number on the right"] = listOf("MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON")
        expected["a TEXT DECLARATION on the right"] = listOf("MVK_MISSING_CONST_IN_PATTERN_COMP")
        assertEquals(expected, actual, "the four questions in order")
    }

    @Test
    fun whichWrittenOutPatternsTheGateAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain quantifier"] = comparison("[GiftNote] PatternMatched \\\"a+\\\"")
        actual["an ESCAPED star before a quantifier"] =
            comparison("[GiftNote] PatternMatched \\\"a\\\\\\\\*+\\\"")
        actual["a possessive quantifier"] = comparison("[GiftNote] PatternMatched \\\"a++\\\"")
        actual["an empty pattern"] = comparison("[GiftNote] PatternMatched \\\"\\\"")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain quantifier"] = emptyList()
        expected["an ESCAPED star before a quantifier"] = listOf("MVK_INVALID_PATTERN")
        expected["a possessive quantifier"] = listOf("MVK_INVALID_PATTERN")
        expected["an empty pattern"] = emptyList()
        assertEquals(expected, actual, "which written-out patterns the gate admits")
    }

    /** The gate that answers ahead of the whole arm. */
    @Test
    fun theArmYieldsToTheConstantGate() {
        assertEquals(
            listOf("MVK_INVALID_COMPARE_TO_CONST"),
            comparison("\\\"x\\\" PatternMatched \\\"a+\\\""),
            "two written-out values are the constant gate's",
        )
    }
}
