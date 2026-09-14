package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what equality demands of two numbers' scales.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.NumberComparisonScaleGateLawsTest`. The two rows that carry the risk are the
 * asymmetric literal pair — a scale-2 declaration ADMITS `1` while a scale-0 one refuses `1.00` — and the
 * suppressed computation, where reporting would name a class A12's own pragma has switched off.
 */
class NumberComparisonScaleGateTest {

    private fun model(rulesAndComputations: String): String =
        """
        {"header":{"id":"order","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":0}}}},
            {"type":"Field","id":"f2","name":"Backordered","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":0}}}},
            {"type":"Field","id":"f3","name":"UnitPrice","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f4","name":"Discount","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f5","name":"Untyped","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{}}}},
            {"type":"Field","id":"f6","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
            RULES_HERE
          ]}}
         ]}}}
        """.trimIndent().replace("RULES_HERE", rulesAndComputations)

    private fun comparison(text: String): List<String> = check(
        ModelSource(
            model(
                """
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And $text","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                """.trimIndent(),
            ),
        ),
    ).findings.map { it.code }

    private val scale = listOf("MVK_INVALID_COMPARE_DEC_PLACES")

    @Test
    fun whichScalesEqualityAdmits() {
        val actual = mutableMapOf<String, List<String>>()
        actual["two declarations of the same scale"] = comparison("[Quantity] == [Backordered]")
        actual["scale 0 against scale 2"] = comparison("[Quantity] == [UnitPrice]")
        actual["scale 2 against scale 0"] = comparison("[UnitPrice] == [Quantity]")
        actual["the same two under inequality"] = comparison("[Quantity] != [UnitPrice]")
        actual["the same two under ORDERING"] = comparison("[Quantity] < [UnitPrice]")
        actual["scale 2 against a whole written-out number"] = comparison("[UnitPrice] == 1")
        actual["scale 0 against a two-place written-out number"] = comparison("[Quantity] == 1.00")
        actual["a two-place written-out number on the LEFT of scale 0"] = comparison("1.00 == [Quantity]")
        actual["scale 2 against a two-place written-out number"] = comparison("[UnitPrice] == 1.00")
        actual["an OMITTED fractional-digit member against scale 2"] = comparison("[Untyped] == [UnitPrice]")
        actual["an omitted member against a whole written-out number"] = comparison("[Untyped] == 1")

        val expected = mutableMapOf<String, List<String>>()
        expected["two declarations of the same scale"] = emptyList()
        expected["scale 0 against scale 2"] = scale
        expected["scale 2 against scale 0"] = scale
        expected["the same two under inequality"] = scale
        expected["the same two under ORDERING"] = emptyList()
        expected["scale 2 against a whole written-out number"] = emptyList()
        expected["scale 0 against a two-place written-out number"] = scale
        expected["a two-place written-out number on the LEFT of scale 0"] = scale
        expected["scale 2 against a two-place written-out number"] = emptyList()
        expected["an OMITTED fractional-digit member against scale 2"] = scale
        expected["an omitted member against a whole written-out number"] = emptyList()
        assertEquals(expected, actual, "which scales equality admits")
    }

    @Test
    fun whatANumberMayStandAgainst() {
        val actual = mutableMapOf<String, List<String>>()
        actual["against True"] = comparison("[Quantity] == True")
        actual["against False on the LEFT"] = comparison("False == [Quantity]")
        actual["against a BOOLEAN declaration"] = comparison("[Quantity] == [Expedited]")

        val expected = mutableMapOf<String, List<String>>()
        expected["against True"] = listOf("MVK_INCONSISTENT_TYPES_COMPARED")
        expected["against False on the LEFT"] = listOf("MVK_INCONSISTENT_TYPES_COMPARED")
        expected["against a BOOLEAN declaration"] = listOf("MVK_INVALID_COMPARE_TO_YESNO")
        assertEquals(expected, actual, "what a number may stand against")
    }

    /** The one class a computation may switch off for itself, and the control that locates it. */
    @Test
    fun aComputationMaySuppressTheScaleGate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a mismatched-scale precondition"] = computationCodes("[]")
        actual["the same precondition, suppressed"] = computationCodes("""["MVK_INVALID_COMPARE_DEC_PLACES"]""")

        val expected = mutableMapOf<String, List<String>>()
        expected["a mismatched-scale precondition"] = scale
        expected["the same precondition, suppressed"] = emptyList()
        assertEquals(expected, actual, "a computation may suppress the scale gate")
    }

    private fun computationCodes(suppressed: String): List<String> = check(
        ModelSource(
            model(
                """
                {"type":"Computation","id":"c0","name":"ScaleProbe","Computation":{
                  "computedFieldRelPath":"../Discount","errorCodesToSuppress":$suppressed,
                  "computationAlternatives":[{"precondition":"[Quantity] == [UnitPrice]",
                    "operation":"1.00"}],
                  "errorMessage":[{"locale":"en_US","text":"x"}]}}
                """.trimIndent(),
            ),
        ),
    ).findings.map { it.code }
}
