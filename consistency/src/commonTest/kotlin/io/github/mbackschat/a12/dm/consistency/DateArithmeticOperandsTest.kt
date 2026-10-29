package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the date-arithmetic operand gates.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.DateArithmeticOperandLawsTest`.
 *
 * The rows that carry the file are the two that separate gates sitting one behind the other: a pure clock
 * under `AddHours` is the only route to `MVK_INVALID_TIME`, and a stamp under `AddMonths` is refused although
 * its format spells exactly what the operator adds.
 */
class DateArithmeticOperandsTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"claim","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Days","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":0}}}},
                {"type":"Field","id":"f2","name":"FiledOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f3","name":"ClosedOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd"}}}},
                {"type":"Field","id":"f4","name":"CoverMonth","Field":{"fieldType":{"type":"DateFragmentType",
                  "DateFragmentType":{"formatOfFragment":"yyyy-MM"}}}},
                {"type":"Field","id":"f5","name":"CallStart","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f6","name":"CallEnd","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f7","name":"LoggedAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f8","name":"ClearedAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f9","name":"OnsetOn","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"yyyy-MM-dd","datePrecision":"MONTH_OPTIONAL"}}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun subject(condition: String): List<String> = codes("FieldFilled(Reference) And $condition")

    @Test
    fun theComponentGateAnswersAheadOfTheTimeOneAndTheStampIsItsOwnRefusal() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a plain date shifted by days"] = subject("[ClosedOn] > AddDays(FiledOn, 1)")
        actual["a year-month shifted by days"] = subject("[CoverMonth] > AddDays(CoverMonth, 1)")
        actual["a pure clock shifted by hours"] = subject("[CallEnd] > AddHours(CallStart, 1)")
        actual["a stamp shifted by hours"] = subject("[ClearedAt] > AddHours(LoggedAt, 1)")
        actual["a stamp shifted by months"] = subject("[ClearedAt] > AddMonths(LoggedAt, 1)")
        actual["a stamp shifted by days"] = subject("[ClearedAt] > AddDays(LoggedAt, 1)")

        val expected = mutableMapOf<String, List<String>>()
        expected["a plain date shifted by days"] = emptyList()
        expected["a year-month shifted by days"] = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        // The component gate ahead of this one passes on a clock under a clock operator, so this is the only
        // shape that reaches the date-specification gate at all.
        expected["a pure clock shifted by hours"] = listOf("MVK_INVALID_TIME")
        expected["a stamp shifted by hours"] = emptyList()
        // `A12K-620`: the format spells the month, and A12 refuses it anyway.
        expected["a stamp shifted by months"] = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        expected["a stamp shifted by days"] = emptyList()
        assertEquals(expected, actual, "the operator's component decides first; the stamp rule is separate")
    }

    @Test
    fun aPartialPrecisionAndTheAmountSlotBookendTheWalk() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a month-optional date"] = subject("[ClosedOn] > AddDays(OnsetOn, 1)")
        actual["a text amount"] = subject("[ClosedOn] > AddDays(FiledOn, [Reference])")
        actual["a number-field amount"] = subject("[ClosedOn] > AddDays(FiledOn, [Days])")
        actual["a computed amount this reader does not type"] =
            subject("[ClosedOn] > AddDays(FiledOn, {[Days] + 1})")
        actual["both operands wrong, and the date one answers"] =
            subject("[CoverMonth] > AddDays(CoverMonth, [Reference])")

        val expected = mutableMapOf<String, List<String>>()
        expected["a month-optional date"] = listOf("MVK_INVALID_DATE_TYPE")
        expected["a text amount"] = listOf("MVK_NON_NUMERIC")
        expected["a number-field amount"] = emptyList()
        // A12 types the amount as a whole operation, which this reader does not compute.
        expected["a computed amount this reader does not type"] = emptyList()
        expected["both operands wrong, and the date one answers"] = listOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        assertEquals(expected, actual, "the precision opens the walk and the amount closes it")
    }
}
