package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the three questions a comparison operator asks of its sides.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ComparisonOperatorOperandLawsTest`. The ordering row on two constants carries the
 * risk: the constant gate is asked first, so naming an ordering class there would be the wrong class on a
 * correct rejection.
 */
class ComparisonOperatorOperandsTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f3","name":"Backordered","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f4","name":"PlacedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f5","name":"ShippedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f6","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
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
    fun theGateThatRefusesTwoConstants() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a field against a constant"] = comparison("[Quantity] == 1")
        actual["two number constants"] = comparison("1 == 2")
        actual["two number constants under an ORDERING operator"] = comparison("1 > 2")
        actual["two string constants"] = comparison("\\\"a\\\" == \\\"b\\\"")

        val expected = mutableMapOf<String, List<String>>()
        expected["a field against a constant"] = emptyList()
        expected["two number constants"] = listOf("MVK_INVALID_COMPARE_TO_CONST")
        expected["two number constants under an ORDERING operator"] =
            listOf("MVK_INVALID_COMPARE_TO_CONST")
        expected["two string constants"] = listOf("MVK_INVALID_COMPARE_TO_CONST")
        assertEquals(expected, actual, "the gate that refuses two constants")
    }

    @Test
    fun whatAToleranceOperatorDemands() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two number fields"] = comparison("[Quantity] DiffersWithToleranceRange1 [Backordered]")
        actual["a number field against a number constant"] =
            comparison("[Quantity] DiffersWithToleranceRange1 5")
        actual["a text field on the right"] =
            comparison("[Quantity] DiffersWithToleranceRange1 [GiftNote]")
        actual["a text field on the left"] =
            comparison("[GiftNote] DiffersWithToleranceRange1 [Quantity]")
        actual["a date field on the right"] =
            comparison("[Quantity] DiffersWithToleranceRange10 [PlacedOn]")

        val expected = mutableMapOf<String, List<String>>()
        expected["two number fields"] = emptyList()
        expected["a number field against a number constant"] = emptyList()
        expected["a text field on the right"] = listOf("MVK_INVALID_TYPE_FOR_TOLERANCE_OP")
        expected["a text field on the left"] = listOf("MVK_INVALID_TYPE_FOR_TOLERANCE_OP")
        expected["a date field on the right"] = listOf("MVK_INVALID_TYPE_FOR_TOLERANCE_OP")
        assertEquals(expected, actual, "what a tolerance operator demands")
    }

    @Test
    fun whatAnOrderingOperatorDemands() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two number fields"] = comparison("[Quantity] < [Backordered]")
        actual["two date fields"] = comparison("[PlacedOn] <= [ShippedOn]")
        actual["two text fields"] = comparison("[GiftNote] < [Reference]")
        actual["a text field against a number one"] = comparison("[GiftNote] < [Quantity]")
        actual["a Boolean field against a number one"] = comparison("[Expedited] < [Quantity]")
        actual["two text fields under EQUALITY"] = comparison("[GiftNote] == [Reference]")

        val expected = mutableMapOf<String, List<String>>()
        expected["two number fields"] = emptyList()
        expected["two date fields"] = emptyList()
        expected["two text fields"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        expected["a text field against a number one"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        expected["a Boolean field against a number one"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        expected["two text fields under EQUALITY"] = emptyList()
        assertEquals(expected, actual, "what an ordering operator demands")
    }

    /** The sides it will not type, each because A12 computes a result type this reader does not. */
    @Test
    fun theSidesItDeliberatelyDoesNotType() {
        val actual = mutableMapOf<String, List<String>>()
        actual["arithmetic on one side of an ordering comparison"] =
            comparison("[GiftNote] < {[Quantity] + 1}")
        actual["an aggregate on one side of an ordering comparison"] =
            comparison("[GiftNote] < Sum(Quantity, Backordered)")

        val expected = mutableMapOf<String, List<String>>()
        expected["arithmetic on one side of an ordering comparison"] = emptyList()
        expected["an aggregate on one side of an ordering comparison"] = emptyList()
        assertEquals(expected, actual, "the sides it deliberately does not type")
    }

    /** The finding names both sides, since the fix is to change one of them or the operator. */
    @Test
    fun theFindingNamesBothSides() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And [GiftNote] < [Reference]",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_INVALID_TYPE_FOR_COMPARISON" }
        assertEquals("/Order/Probe", finding.at)
        assertEquals(true, finding.explain.contains("[GiftNote]"), finding.explain)
        assertEquals(true, finding.explain.contains("[Reference]"), finding.explain)
    }
}
