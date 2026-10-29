package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the single-entity value operators' operand gates.
 *
 * Field rows mirror `rulekit.validate.laws.FieldValueOperatorOperandLawsTest`;
 * CurrentRepetition rows mirror the kernel-measured `PathGatePopulation.repetitionRows`.
 *
 * The rows that carry the file are the two that invert a reader's expectation: `FieldValueAsString` demands
 * a NUMBER, and `FieldValueAsNumber` is the one arm that admits a CATEGORY and then reads that category's
 * values rather than the enumeration's.
 */
class FieldValueOperatorOperandsTest {

    @Test
    fun aRepetitionValueRequiresOneGroupAfterItsPathReads() {
        val rows = mapOf(
            "CurrentRepetition(Bundle) > 0" to emptyList(),
            "CurrentRepetition(Bundle/BundleQty) > 0" to listOf("MVK_NO_FIELDS_ALLOWED"),
            "CurrentRepetition(Lines*) > 0" to listOf("MVK_NO_WILDCARDS_ALLOWED"),
            "CurrentRepetition(Lines*/LineQty) > 0" to listOf("MVK_NO_FIELDS_ALLOWED"),
            "Sum(Lines*/LineQty Having CurrentRepetition(Lines*) > 0) > 0" to listOf("MVK_NO_WILDCARDS_ALLOWED"),
        )
        for ((condition, expected) in rows) assertEquals(expected, subject(condition), condition)
    }

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"orders","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Notes","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0}}}},
                {"type":"Field","id":"f3","name":"DigitCode","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"maxLength":5,"pattern":"[0-9]+",
                  "errorMessage":[{"locale":"en_US","text":"digits only"}]}}}},
                {"type":"Field","id":"f4","name":"PlainText","Field":{"fieldType":{"type":"StringType",
                  "StringType":{"maxLength":5}}}},
                {"type":"Field","id":"f5","name":"Serial","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"OrderSerial"}}}},
                {"type":"Field","id":"f6","name":"NumericTier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[{"value":"1"},{"value":"2"}]}}}},
                {"type":"Field","id":"f7","name":"TextTier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[{"value":"A"},{"value":"B"}]}}}},
                {"type":"Field","id":"f8","name":"BandedTier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[{"value":"1"},{"value":"2"}],
                  "categories":[{"name":"Band","values":["3","4"]}]}}}},
                {"type":"Field","id":"f9","name":"TextBandedTier","Field":{"fieldType":{
                  "type":"EnumerationType","EnumerationType":{"values":[{"value":"1"},{"value":"2"}],
                  "categories":[{"name":"Band","values":["X","Y"]}]}}}},
                {"type":"Group","id":"g1","name":"Bundle","Group":{"elements":[
                  {"type":"Field","id":"f10","name":"BundleQty","Field":{"fieldType":{"type":"NumberType",
                    "NumberType":{"maxFractionalDigits":0}}}}
                ]}},
                {"type":"Group","id":"g2","name":"Lines","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f11","name":"LineQty","Field":{"fieldType":{"type":"NumberType",
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

    /**
     * The operator wrapped in a comparison of the class it returns, so no comparison gate answers instead.
     *
     * The text literal is written with its JSON ESCAPE, because this condition is substituted into the model
     * source above: a bare `"` closes `errorCondition` and the model then carries no rule at all, which reads
     * as the family staying silent rather than as a broken fixture.
     */
    private fun call(operator: String, operand: String): String =
        if (operator == "FieldValueAsString") {
            """FieldValueAsString($operand) == \"0\""""
        } else {
            "$operator($operand) > 0"
        }

    private val allFour = listOf("AbsValue", "Length", "FieldValueAsNumber", "FieldValueAsString")

    @Test
    fun theSharedHeadRefusesAGroupAndAStarAtEveryOperator() {
        val actual = mutableMapOf<String, List<String>>()
        val expected = mutableMapOf<String, List<String>>()
        for (operator in allFour) {
            actual["$operator over a group"] = subject(call(operator, "Bundle"))
            actual["$operator over a starred field"] = subject(call(operator, "Lines*/LineQty"))
            expected["$operator over a group"] = listOf("MVK_NO_GROUPS_ALLOWED")
            expected["$operator over a starred field"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        }
        assertEquals(expected, actual, "the head both arms share, before either asks about the kind")
    }

    @Test
    fun theKindEachOperatorDemandsIsNotTheOneItsNameSuggests() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AbsValue over a number"] = subject(call("AbsValue", "Quantity"))
        actual["AbsValue over a string"] = subject(call("AbsValue", "Notes"))
        actual["AbsValue over a custom type"] = subject(call("AbsValue", "Serial"))
        actual["FieldValueAsString over a number"] = subject(call("FieldValueAsString", "Quantity"))
        actual["FieldValueAsString over a string"] = subject(call("FieldValueAsString", "Notes"))
        actual["Length over a string"] = subject(call("Length", "Notes"))
        actual["Length over a custom type"] = subject(call("Length", "Serial"))
        actual["Length over a number"] = subject(call("Length", "Quantity"))
        actual["Length over an enumeration"] = subject(call("Length", "TextTier"))

        val expected = mutableMapOf<String, List<String>>()
        expected["AbsValue over a number"] = emptyList()
        expected["AbsValue over a string"] = listOf("MVK_NO_NUMBER")
        expected["AbsValue over a custom type"] = listOf("MVK_NO_NUMBER")
        expected["FieldValueAsString over a number"] = emptyList()
        // The name invites the opposite reading: it stringifies a NUMBER, so a string operand is refused.
        expected["FieldValueAsString over a string"] = listOf("MVK_NO_NUMBER")
        expected["Length over a string"] = emptyList()
        expected["Length over a custom type"] = emptyList()
        expected["Length over a number"] = listOf("MVK_NO_STRING")
        expected["Length over an enumeration"] = listOf("MVK_NO_STRING")
        assertEquals(expected, actual, "each arm's own kind question, over the declared classes it separates")
    }

    @Test
    fun onlyTheNumberConversionAdmitsACategoryAndItReadsThatCategorysValues() {
        val actual = mutableMapOf<String, List<String>>()
        actual["AbsValue over a category"] = subject(call("AbsValue", "BandedTier -> Band"))
        actual["Length over a category"] = subject(call("Length", "BandedTier -> Band"))
        actual["FieldValueAsString over a category"] = subject(call("FieldValueAsString", "BandedTier -> Band"))
        actual["the conversion over a numeric category"] =
            subject(call("FieldValueAsNumber", "BandedTier -> Band"))
        actual["the conversion over a non-numeric category"] =
            subject(call("FieldValueAsNumber", "TextBandedTier -> Band"))

        val expected = mutableMapOf<String, List<String>>()
        expected["AbsValue over a category"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        expected["Length over a category"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        expected["FieldValueAsString over a category"] = listOf("MVK_CATEGORY_NOT_ALLOWED")
        // Both banded enumerations declare the numeric values `1` and `2`, so only a reader taking the
        // CATEGORY's own tokens can tell these two rows apart.
        expected["the conversion over a numeric category"] = emptyList()
        expected["the conversion over a non-numeric category"] = listOf("MVK_NO_CONVERT_IN_NUMBER")
        assertEquals(expected, actual, "the category gate, and which values the one admitting arm reads")
    }

    @Test
    fun theNumberConversionReadsTheDeclarationAndNeverAStoredValue() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a bounded digit-patterned string"] = subject(call("FieldValueAsNumber", "DigitCode"))
        actual["a bounded string with no pattern"] = subject(call("FieldValueAsNumber", "PlainText"))
        actual["an enumeration whose values parse"] = subject(call("FieldValueAsNumber", "NumericTier"))
        actual["an enumeration whose values do not"] = subject(call("FieldValueAsNumber", "TextTier"))
        actual["a number"] = subject(call("FieldValueAsNumber", "Quantity"))
        actual["a custom type"] = subject(call("FieldValueAsNumber", "Serial"))

        val expected = mutableMapOf<String, List<String>>()
        expected["a bounded digit-patterned string"] = emptyList()
        expected["a bounded string with no pattern"] = listOf("MVK_NO_CONVERT_IN_NUMBER")
        expected["an enumeration whose values parse"] = emptyList()
        expected["an enumeration whose values do not"] = listOf("MVK_NO_CONVERT_IN_NUMBER")
        // The arm branches on string then enumeration and refuses every other declaration outright.
        expected["a number"] = listOf("MVK_NO_CONVERT_IN_NUMBER")
        expected["a custom type"] = listOf("MVK_NO_CONVERT_IN_NUMBER")
        assertEquals(expected, actual, "the declaration is the whole question on the conversion arm")
    }
}
