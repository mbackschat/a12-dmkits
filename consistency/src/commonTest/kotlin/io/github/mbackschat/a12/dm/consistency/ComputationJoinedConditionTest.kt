package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the one condition A12 joins a computation into: its members read in the joined order,
 * and each alternative ending in the comparison of its target with its operation.
 *
 * Every row mirrors one measured against the real kernel by
 * `adapter.laws.CheckerComputationJoinedConditionAgreementTest`, and runs here on every target the checker
 * ships to.
 */
class ComputationJoinedConditionTest {

    @Test
    fun aComputationIsJudgedAsTheComparisonItIsLoweredTo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a String from a Number"] = codes("../Label", alternative("[Total]"))
        actual["an Enumeration from a Number"] = codes("../Tier", alternative("[Total]"))
        actual["a Number from a Number of a larger scale"] = codes("../Total", alternative("[Amount]"))
        actual["a Date from a Number"] = codes("../Shipped", alternative("[Total]"))
        actual["a String from a String under a tolerance band"] =
            codes("../Label", """{"operation":"[Note]","toleranceRangeOp":"DiffersWithToleranceRange1"}""")
        actual["a partially known Date target"] = codes("../Promised", alternative("[Shipped]"))
        actual["a raw String target"] = codes("../Remark", alternative("[Note]"))
        actual["a Number from a partially known Date"] = codes("../Total", alternative("[Promised]"))
        actual["a String from a String, the control"] = codes("../Label", alternative("[Note]"))

        val expected = mutableMapOf<String, List<String>>()
        expected["a String from a Number"] = listOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")
        expected["an Enumeration from a Number"] = listOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")
        expected["a Number from a Number of a larger scale"] = listOf("MVK_INVALID_COMPARE_DEC_PLACES")
        expected["a Date from a Number"] = listOf("MVK_INVALID_COMPARE_TO_DATE")
        expected["a String from a String under a tolerance band"] = listOf("MVK_INVALID_TYPE_FOR_TOLERANCE_OP")
        // The target's own read comes first, and neither of these two is a comparison class.
        expected["a partially known Date target"] = listOf("MVK_INVALID_DATE_TYPE")
        expected["a raw String target"] = listOf("MVK_INVALID_RAW_TYPE")
        // The one read the comparison encloses entirely: A12 raises it first, and nothing after it.
        expected["a Number from a partially known Date"] = listOf("MVK_INVALID_DATE_TYPE")
        expected["a String from a String, the control"] = emptyList()
        assertEquals(expected, actual, "what a computation's lowered comparison draws")
    }

    @Test
    fun aComputationsMembersAnswerInTheOrderA12JoinsThem() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a precondition, then its operation"] =
            codes("../Label", alternative("[Total]", precondition = "[Shipped] > [Amount]"))
        actual["an operation, then the next alternative's precondition"] = codes(
            "../Label",
            alternative("[Total]", precondition = "FieldFilled(Note)"),
            alternative("[Note]", precondition = "[Shipped] > [Amount]"),
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["a precondition, then its operation"] = listOf("MVK_INVALID_COMPARE_TO_DATE")
        expected["an operation, then the next alternative's precondition"] = listOf("MVK_INVALID_COMPARE_TO_ENUM_OR_STRING")
        assertEquals(expected, actual, "which member's class A12 names")
    }

    private fun alternative(operation: String, precondition: String? = null): String =
        if (precondition == null) """{"operation":"$operation"}"""
        else """{"precondition":"$precondition","operation":"$operation"}"""

    private fun codes(target: String, vararg alternatives: String): List<String> =
        check(ModelSource(model(target, alternatives.joinToString(",")))).findings
            .filter { it.justifiesRejection }
            .map { it.code }

    /** An order desk: one field of each kind the rows copy between, and the computation under test. */
    private fun model(target: String, alternatives: String): String = """
        {"header":{"id":"order-joined-condition","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Label","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Note","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f2","name":"Remark","Field":{"fieldType":{"type":"StringType",
              "StringType":{"noValueValidation":true,"lineBreaksPermitted":true}}}},
            {"type":"Field","id":"f3","name":"Total","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":0}}}},
            {"type":"Field","id":"f4","name":"Amount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f5","name":"Shipped","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd"}}}},
            {"type":"Field","id":"f6","name":"Promised","Field":{"fieldType":{"type":"DateType",
              "DateType":{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}}}},
            {"type":"Field","id":"f7","name":"Tier","Field":{"fieldType":{"type":"EnumerationType",
              "EnumerationType":{"values":[{"value":"GOLD"},{"value":"SILVER"}]}}}},
            {"type":"Computation","id":"c0","name":"Derived","Computation":{
              "computedFieldRelPath":"$target",
              "computationAlternatives":[$alternatives],
              "errorMessage":[{"locale":"en_US","text":"the derived value"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
