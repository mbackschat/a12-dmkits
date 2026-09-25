package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "an operator's gate is a parse-time class and orders like the other operand classes".
 *
 * Mirrors `rulekit.validate.laws.ConditionClassWithholdingLawsTest.theOperatorGatesOrderLikeTheOtherOperandClasses`
 * row for row, one gate per operator family. The gates publish no span, so the row with the bad name AFTER
 * the gate is a disclosed substitution rather than a match: A12 names the gate, and this checker withholds a
 * span-less class behind any bad name in the text and names the bad name (`DEV-API-GAPS` DG84). Every other
 * row matches, the unreferenced-target ones included, which is what listing the codes as positioned bought.
 */
class OperatorGateOrderTest {

    @Test
    fun theOperatorGatesOrderLikeTheOtherOperandClasses() {
        val gates = linkedMapOf(
            "text in a rounding slot" to ("0 < RoundUpValue(Reference, 2)" to "MVK_NO_NUMBER"),
            "an argument too many" to ("FieldFilled(Reference, Score)" to "MVK_PARAMSIZE_INVALID1"),
            "a filtered group in a count" to
                ("NumberOfFilledGroups(Claims Having [Claims/Amount] > 0) > 0" to "MVK_NO_GROUPS_ALLOWED"),
            "text in a date slot" to ("DifferenceInDays(Reference, Reference) > 0" to "MVK_NO_DATE"),
            "text ordered against a number" to ("[Reference] > 5" to "MVK_INVALID_TYPE_FOR_COMPARISON"),
        )
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        gates.forEach { (row, gate) ->
            val (condition, alone) = gate
            actual[row] = codes(condition)
            expected[row] = listOf(alone)
            actual["$row, bad name after"] = codes("$condition And FieldFilled(NoSuchField)")
            expected["$row, bad name after"] = listOf("MVK_INVALID_ENTITY")
            actual["$row, bad name BEFORE"] = codes("FieldFilled(NoSuchField) And $condition")
            expected["$row, bad name BEFORE"] = listOf("MVK_INVALID_ENTITY")
        }
        actual["a sound condition, error field unreferenced"] = codes("FieldFilled(Reference)", UNNAMED)
        expected["a sound condition, error field unreferenced"] = listOf("MVK_ERROR_FIELD_NOT_REFERENCED")
        gates.values.filter { it.second != "MVK_NO_GROUPS_ALLOWED" }.forEach { (condition, alone) ->
            actual["$condition, error field unreferenced"] = codes(condition, UNNAMED)
            expected["$condition, error field unreferenced"] = listOf(alone)
        }
        assertEquals(expected, actual, "the operator gates against a bad name and an unreferenced target")
    }

    private fun codes(condition: String, errorTarget: String = "../Reference"): List<String> =
        check(ModelSource(model(condition, errorTarget))).findings
            .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Policy/Probe" }
            .map { it.code }.distinct().sorted()

    /** The claims policy of the laws test, reduced to the elements its gate rows read. */
    private fun model(condition: String, errorTarget: String): String = """
        {"header":{"id":"claims-policy","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Score","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Assessment","Group":{"elements":[
              {"type":"Field","id":"f2","name":"Notes","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g4","name":"Claims","Group":{"repeatability":5,"elements":[
              {"type":"Field","id":"f4","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorTarget",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        /** No gate text names `Assessment/Notes`, so the class this target draws is live on every row. */
        const val UNNAMED = "../Assessment/Notes"
    }
}
