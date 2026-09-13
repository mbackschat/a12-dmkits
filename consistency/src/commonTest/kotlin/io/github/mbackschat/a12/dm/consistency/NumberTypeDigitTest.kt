package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the number type's digit and range grid.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.NumberTypeDigitLawsTest`. The digit rows are the ones that bite: a checker reading
 * the members as declared disagrees with A12 in BOTH directions — it admits `minFractionalDigits: 2` with no
 * maximum, which A12 refuses, and refuses a positive minimum beside a negative maximum, which A12 admits.
 */
class NumberTypeDigitTest {

    private val fractDigitsInvalid = "MVK_FRACT_DIGITS_INVALID"
    private val minAboveMaxValue = "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE"

    /** A model whose one number field carries [numberType] as its type body. */
    private fun codes(numberType: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{$numberType}}}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    @Test
    fun theFractionalDigitPair() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary scale — the control"] = codes(""""maxFractionalDigits":2""")
        actual["a minimum above the maximum"] = codes(""""minFractionalDigits":3,"maxFractionalDigits":2""")
        actual["a minimum EQUAL to the maximum — the boundary"] =
            codes(""""minFractionalDigits":2,"maxFractionalDigits":2""")
        actual["a minimum with NO declared maximum"] = codes(""""minFractionalDigits":2""")
        actual["neither member"] = codes(""""minValue":0""")
        actual["a NEGATIVE maximum"] = codes(""""maxFractionalDigits":-1""")
        actual["a NEGATIVE minimum"] = codes(""""minFractionalDigits":-1,"maxFractionalDigits":2""")
        actual["a ZERO minimum with a negative maximum"] =
            codes(""""minFractionalDigits":0,"maxFractionalDigits":-1""")
        actual["a ONE minimum with a negative maximum"] =
            codes(""""minFractionalDigits":1,"maxFractionalDigits":-1""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary scale — the control"] = emptyList()
        expected["a minimum above the maximum"] = listOf(fractDigitsInvalid)
        expected["a minimum EQUAL to the maximum — the boundary"] = emptyList()
        expected["a minimum with NO declared maximum"] = listOf(fractDigitsInvalid)
        expected["neither member"] = emptyList()
        expected["a NEGATIVE maximum"] = emptyList()
        expected["a NEGATIVE minimum"] = emptyList()
        expected["a ZERO minimum with a negative maximum"] = emptyList()
        expected["a ONE minimum with a negative maximum"] = emptyList()
        assertEquals(expected, actual, "a number type's fractional-digit pair")
    }

    @Test
    fun theValueRange() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary range — the control"] = codes(""""minValue":0,"maxValue":100""")
        actual["a minimum above the maximum"] = codes(""""minValue":10,"maxValue":5""")
        actual["a minimum EQUAL to the maximum — the boundary"] = codes(""""minValue":5,"maxValue":5""")
        actual["a minimum with no maximum"] = codes(""""minValue":10""")
        actual["a maximum with no minimum"] = codes(""""maxValue":10""")
        actual["a NEGATIVE range"] = codes(""""minValue":-10,"maxValue":-5""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary range — the control"] = emptyList()
        expected["a minimum above the maximum"] = listOf(minAboveMaxValue)
        expected["a minimum EQUAL to the maximum — the boundary"] = emptyList()
        expected["a minimum with no maximum"] = emptyList()
        expected["a maximum with no minimum"] = emptyList()
        expected["a NEGATIVE range"] = emptyList()
        assertEquals(expected, actual, "a number type's value range")
    }

    /**
     * The scale cap is the BUILD tier: A12 reads the model and then cannot build it, with no code of its own.
     *
     * Silence there would present an unusable model as clean, which is exactly what that tier exists to stop.
     */
    @Test
    fun aScalePastTheCapIsReportedAsTheBuildTierRatherThanAsAVerdict() {
        assertEquals(emptyList(), codes(""""maxFractionalDigits":14"""), "14 is the admitted boundary")
        assertEquals(listOf(CK_MODEL_NOT_BUILDABLE), codes(""""maxFractionalDigits":15"""))
        assertEquals(
            listOf(CK_MODEL_NOT_BUILDABLE),
            codes(""""minFractionalDigits":15,"maxFractionalDigits":15""").distinct(),
            "both members carry the cap, and the pair is not also an inversion",
        )
    }
}
