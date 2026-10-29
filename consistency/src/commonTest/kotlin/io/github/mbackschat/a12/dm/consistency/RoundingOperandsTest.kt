package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the rounding operand and digits gates.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.RoundingOperandLawsTest`.
 *
 * The rows that carry the file are the two doubly-wrong calls: the digits gate is asked LAST on both
 * constructs, so a reader reporting whichever defect it noticed first would name the wrong class on a model
 * A12 refuses for the operand.
 */
class RoundingOperandsTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"prices","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"PriceList","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"UnitPrice","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":4}}}},
                {"type":"Field","id":"f3","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0}}}},
                {"type":"Group","id":"g1","name":"Bundle","Group":{"elements":[
                  {"type":"Field","id":"f4","name":"BundleQty","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":0}}}}
                ]}},
                {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f5","name":"LineQty","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":0}}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Sku",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(condition: String): List<String> = codes("FieldFilled(Sku) And $condition")

    @Test
    fun theValueSpellingWalksItsOwnGatesAheadOfTheDigitsOne() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a number field, two places"] = subject("RoundUpValue(UnitPrice, 2) > 0")
        actual["a number field, fourteen places"] = subject("RoundDownValue(UnitPrice, 14) > 0")
        actual["a number field, fifteen places"] = subject("RoundAccountingValue(UnitPrice, 15) > 0")
        actual["a text field"] = subject("RoundUpValue(Label, 2) > 0")
        actual["a group"] = subject("RoundUpValue(Bundle, 2) > 0")
        actual["a starred field"] = subject("RoundUpValue(Lines*/LineQty, 2) > 0")
        actual["a text field and too many places"] = subject("RoundUpValue(Label, 99) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a number field, two places"] = emptyList()
        expected["a number field, fourteen places"] = emptyList()
        expected["a number field, fifteen places"] = listOf("MVK_INVALID_DIGIT_FOR_ROUNDING")
        expected["a text field"] = listOf("MVK_NO_NUMBER")
        expected["a group"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["a starred field"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        // The operand gate is asked first, so its class is what a doubly-wrong call draws.
        expected["a text field and too many places"] = listOf("MVK_NO_NUMBER")
        assertEquals(expected, actual, "the field spelling's four gates run before the shared digits one")
    }

    @Test
    fun theBareSpellingRefusesAConstantAndLeavesAnExpressionAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a field value"] = subject("RoundUp([UnitPrice], 2) > 0")
        actual["an arithmetic expression"] = subject("RoundUp([UnitPrice] * [Quantity], 2) > 0")
        actual["a numeric constant"] = subject("RoundUp(5, 2) > 0")
        actual["a text value"] = subject("RoundUp([Label], 2) > 0")
        actual["a constant and too many places"] = subject("RoundUp(5, 99) > 0")
        actual["an expression and too many places"] = subject("RoundUp([UnitPrice] * [Quantity], 99) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a field value"] = emptyList()
        expected["an arithmetic expression"] = emptyList()
        expected["a numeric constant"] = listOf("MVK_NO_CONST")
        expected["a text value"] = listOf("MVK_NO_NUMBER")
        expected["a constant and too many places"] = listOf("MVK_NO_CONST")
        // An expression this reader does not type still reaches the digits gate, which needs no type at all.
        expected["an expression and too many places"] = listOf("MVK_INVALID_DIGIT_FOR_ROUNDING")
        assertEquals(expected, actual, "the constant gate opens the operation spelling's walk")
    }
}
