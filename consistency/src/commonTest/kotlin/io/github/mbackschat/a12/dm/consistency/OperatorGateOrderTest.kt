package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "an operator's gate is a parse-time class and orders like the other operand classes".
 *
 * Mirrors `rulekit.validate.laws.ConditionClassWithholdingLawsTest` row for row: the operator gates against a
 * bad name and an unreferenced target, against a later grammar error, and where each gate's own production
 * ends. Each gate carries the span of the production A12 raises it from, which is what places it against the
 * rest of the text.
 */
class OperatorGateOrderTest {

    @Test
    fun theOperatorGatesOrderLikeTheOtherOperandClasses() {
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        GATES.forEach { (row, gate) ->
            val (condition, alone) = gate
            actual[row] = codes(condition)
            expected[row] = listOf(alone)
            actual["$row, bad name after"] = codes("$condition And FieldFilled(NoSuchField)")
            expected["$row, bad name after"] = listOf(alone)
            actual["$row, bad name BEFORE"] = codes("FieldFilled(NoSuchField) And $condition")
            expected["$row, bad name BEFORE"] = listOf("MVK_INVALID_ENTITY")
        }
        actual["a sound condition, error field unreferenced"] = codes("FieldFilled(Reference)", UNNAMED)
        expected["a sound condition, error field unreferenced"] = listOf("MVK_ERROR_FIELD_NOT_REFERENCED")
        GATES.values.filter { it.second != "MVK_NO_GROUPS_ALLOWED" }.forEach { (condition, alone) ->
            actual["$condition, error field unreferenced"] = codes(condition, UNNAMED)
            expected["$condition, error field unreferenced"] = listOf(alone)
        }
        assertEquals(expected, actual, "the operator gates against a bad name and an unreferenced target")
    }

    @Test
    fun aGateAnswersAheadOfALaterGrammarError() {
        val stray = " And And FieldFilled(Reference)"
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        GATES.values.forEach { (gate, alone) ->
            actual["$gate, then a stray connective"] = codes(gate + stray)
            expected["$gate, then a stray connective"] = listOf(alone)
        }
        actual["a gate, then a dangling connective"] = codes("0 < RoundUpValue(Reference, 2) And")
        expected["a gate, then a dangling connective"] = listOf("MVK_NO_NUMBER")
        actual["a gate, then a comparison as a field operand"] = codes("[Reference] > 5 And FieldFilled(Score > 3)")
        expected["a gate, then a comparison as a field operand"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        actual["a gate, then a signed reference"] = codes("0 < RoundUpValue(Reference, 2) And [Score] > -[Score]")
        expected["a gate, then a signed reference"] = listOf("MVK_NO_NUMBER")
        actual["a gate, then a parenthesised operation"] =
            codes("0 < RoundUpValue(Reference, 2) And ([Score] + 1) > 0")
        expected["a gate, then a parenthesised operation"] = listOf("MVK_NO_NUMBER")
        actual["a gate, a bad name, then a stray connective"] =
            codes("[Reference] > 5 And FieldFilled(NoSuchField)$stray")
        expected["a gate, a bad name, then a stray connective"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        actual["a stray connective, then a gate — the reverse"] =
            codes("FieldFilled(Reference)$stray And [Reference] > 5")
        expected["a stray connective, then a gate — the reverse"] = listOf("MVK_UNEXPECTED_TOKEN")
        actual["a gated call that cannot become a comparison"] =
            codes("RoundUpValue(Reference, 2) And FieldFilled(Reference)")
        expected["a gated call that cannot become a comparison"] = listOf("MVK_NO_NUMBER")
        actual["a gated call before a comparison operator, a defect after it"] =
            codes("RoundUpValue(Reference, 2) > -[Score]")
        expected["a gated call before a comparison operator, a defect after it"] = listOf("MVK_NO_NUMBER")
        assertEquals(expected, actual, "a gate against a later grammar error")
    }

    @Test
    fun aGateIsRaisedWhereItsOwnProductionEnds() {
        val actual = linkedMapOf<String, List<String>>()
        actual["a date-argument gate, then a bad name in the next argument"] =
            codes("DifferenceInDays(Reference, NoSuchField) > 0")
        actual["a call's arity gate, with a bad name in its extra argument"] =
            codes("FieldFilled(Reference, NoSuchField)")
        actual["a comparison's gate, with a bad name on its right side"] = codes("[Reference] > [NoSuchField]")
        actual["a gated call inside a call that then fails to parse"] =
            codes("Abs(RoundUpValue(Reference, 2) > 1) > 0")
        val mixed = "FieldFilled(Reference) And FieldFilled(Score) Or "
        actual["a mixed connective, with a bad name in the part after it"] = codes(mixed + "FieldFilled(NoSuchField)")
        actual["a mixed connective, with a bad name in a later part"] =
            codes(mixed + "FieldFilled(Premium) And FieldFilled(NoSuchField)")
        actual["an operand gate of arithmetic, with a bad name later in its chain"] =
            codes("[Reference] * [NoSuchField] > 0")
        actual["an operand gate of arithmetic, with a bad name past its chain"] =
            codes("[Reference] * [Score] > [NoSuchField]")
        actual["a star in an aggregate's filter body"] = codes("Sum(Claims*/Amount Having [Claims*/Amount] > 1) > 0")
        actual["a star in an aggregate's filter body, with a bad name after it in the body"] =
            codes("Sum(Claims*/Amount Having [Claims*/Amount] > [NoSuchField]) > 0")
        actual["a per-row read beside an uncontained error field"] =
            codes("FieldFilled(Claims/Amount)", "../../Registry/Code")
        actual["a per-row read beside an uncontained error field, with a bad name after it"] =
            codes("FieldFilled(Claims/Amount) And FieldFilled(NoSuchField)", "../../Registry/Code")
        actual["a per-row read beside an uncontained error field, with a bad name BEFORE it"] =
            codes("FieldFilled(NoSuchField) And FieldFilled(Claims/Amount)", "../../Registry/Code")
        val yearless = "YearFromDate(Date(\"01\", \"02\")) > 2020"
        val baseYear = "YearFromDate(BaseYear) > 2020"
        actual["a year-less construction, then the base-year constant"] = codes("$yearless And $baseYear")
        actual["the base-year constant, then a year-less construction"] = codes("$baseYear And $yearless")
        val expected = linkedMapOf<String, List<String>>()
        expected["a date-argument gate, then a bad name in the next argument"] = listOf("MVK_NO_DATE")
        expected["a call's arity gate, with a bad name in its extra argument"] = listOf("MVK_INVALID_ENTITY")
        expected["a comparison's gate, with a bad name on its right side"] = listOf("MVK_INVALID_ENTITY")
        expected["a gated call inside a call that then fails to parse"] = listOf("MVK_NO_NUMBER")
        expected["a mixed connective, with a bad name in the part after it"] = listOf("MVK_INVALID_ENTITY")
        expected["a mixed connective, with a bad name in a later part"] = listOf("MVK_BRACKET_MISSING")
        expected["an operand gate of arithmetic, with a bad name later in its chain"] = listOf("MVK_INVALID_ENTITY")
        expected["an operand gate of arithmetic, with a bad name past its chain"] = listOf("MVK_NO_NUMBER_FOR_CALC")
        expected["a star in an aggregate's filter body"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a star in an aggregate's filter body, with a bad name after it in the body"] =
            listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["a per-row read beside an uncontained error field"] = listOf("MVK_ERROR_FIELD_NOT_IN_RULEGROUP")
        expected["a per-row read beside an uncontained error field, with a bad name after it"] =
            listOf("MVK_ERROR_FIELD_NOT_IN_RULEGROUP")
        expected["a per-row read beside an uncontained error field, with a bad name BEFORE it"] =
            listOf("MVK_INVALID_ENTITY")
        expected["a year-less construction, then the base-year constant"] = listOf("MVK_MISSING_YEAR")
        expected["the base-year constant, then a year-less construction"] = listOf("MVK_NO_BASE_YEAR")
        assertEquals(expected, actual, "where each gate's own production ends")
    }

    private fun codes(condition: String, errorTarget: String = "../Reference"): List<String> =
        checkRead(ModelSource(model(condition, errorTarget))).findings
            .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Policy/Probe" }
            .map { it.code }.distinct().sorted()

    /** The claims policy of the laws test, reduced to the elements its gate rows read. */
    private fun model(condition: String, errorTarget: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"claims-policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Score","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Field","id":"f5","name":"Premium","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Group","id":"g1","name":"Assessment","Group":{"elements":[
              {"type":"Field","id":"f2","name":"Notes","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Claims","Group":{"repeatability":5,"elements":[
              {"type":"Field","id":"f4","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}","errorEntityRelPath":"$errorTarget",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}},
          {"type":"Group","id":"g9","name":"Registry","Group":{"elements":[
            {"type":"Field","id":"f9","name":"Code","Field":{"fieldType":{"type":"StringType"}}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        /** No gate text names `Assessment/Notes`, so the class this target draws is live on every row. */
        const val UNNAMED = "../Assessment/Notes"

        /** One gate per operator family and the comparison's scale gate: each text, and the class it draws alone. */
        val GATES = linkedMapOf(
            "text in a rounding slot" to ("0 < RoundUpValue(Reference, 2)" to "MVK_NO_NUMBER"),
            "an argument too many" to ("FieldFilled(Reference, Score)" to "MVK_PARAMSIZE_INVALID1"),
            "a filtered group in a count" to
                ("NumberOfFilledGroups(Claims* Having [Claims/Amount] > 0) > 0" to "MVK_NO_GROUPS_ALLOWED"),
            "text in a date slot" to ("DifferenceInDays(Reference, Reference) > 0" to "MVK_NO_DATE"),
            "text ordered against a number" to ("[Reference] > 5" to "MVK_INVALID_TYPE_FOR_COMPARISON"),
            "two scales held equal" to ("[Premium] == [Score]" to "MVK_INVALID_COMPARE_DEC_PLACES"),
        )
    }
}
