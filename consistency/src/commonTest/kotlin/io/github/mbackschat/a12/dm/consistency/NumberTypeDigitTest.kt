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
    private val fractDigitsMissing = "MVK_FRACT_DIGITS_MISSING"
    private val minAboveMaxValue = "MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE"
    private val maxLengthTooBig = "MVK_MAX_LENGTH_TOO_BIG"
    private val arityTooBig = "MVK_ARITY_TOO_BIG"

    /** A model whose one number field carries [numberType] as its type body. */
    private fun codes(numberType: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"28.4.0","id":"catalog","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
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
     * The two declaration demands, of which only one is reachable.
     *
     * The amount rows are the silence that matters: A12 derives the integer-place count for every amount
     * that declares none, so the law that demands it can never fire and a checker reporting it would refuse
     * a model A12 admits.
     */
    @Test
    fun theDeclarationDemands() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary scale — the control"] = codes(""""maxFractionalDigits":2""")
        actual["BOTH fractional members negative"] =
            codes(""""minFractionalDigits":-1,"maxFractionalDigits":-1""")
        actual["only the MAXIMUM negative"] = codes(""""minFractionalDigits":0,"maxFractionalDigits":-1""")
        actual["an AMOUNT with no integer places"] = codes(""""trait":"amount","maxFractionalDigits":2""")
        actual["the same amount WITH integer places"] =
            codes(""""trait":"amount","maxFractionalDigits":2,"maxIntegerDigits":5""")
        actual["a PERCENT with no integer places"] = codes(""""trait":"percent","maxFractionalDigits":2""")
        actual["an amount whose fractional members are both negative"] =
            codes(""""trait":"amount","maxIntegerDigits":5,"minFractionalDigits":-1,"maxFractionalDigits":-1""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary scale — the control"] = emptyList()
        expected["BOTH fractional members negative"] = listOf(fractDigitsMissing)
        expected["only the MAXIMUM negative"] = emptyList()
        expected["an AMOUNT with no integer places"] = emptyList()
        expected["the same amount WITH integer places"] = emptyList()
        expected["a PERCENT with no integer places"] = emptyList()
        expected["an amount whose fractional members are both negative"] = listOf(fractDigitsMissing)
        assertEquals(expected, actual, "a number type's declaration demands")
    }

    /** The five laws that measure the declaration against A12's budget of 15 digits. */
    @Test
    fun theDigitBudget() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an ordinary length — the control"] = codes(""""maxLength":10""")
        actual["a maximum length of 16"] = codes(""""maxLength":16""")
        actual["a maximum length of 17"] = codes(""""maxLength":17""")
        actual["a minimum length of 17"] = codes(""""minLength":17""")
        actual["a maximum length of 16 on a SIGNLESS number"] =
            codes(""""positivesOnly":true,"maxLength":16""")
        actual["a maximum length of 17 beside a fractional part"] =
            codes(""""maxFractionalDigits":2,"maxLength":17""")
        actual["a maximum length of 18 beside a fractional part"] =
            codes(""""maxFractionalDigits":2,"maxLength":18""")
        actual["an over-long maximum where NEITHER fractional member is declared"] =
            codes(""""minFractionalDigits":-1,"maxFractionalDigits":-1,"maxLength":20""")
        actual["the same undeclared pair at a maximum length of 17"] =
            codes(""""minFractionalDigits":-1,"maxFractionalDigits":-1,"maxLength":17""")
        actual["a maximum length below the fractional part"] =
            codes(""""minFractionalDigits":3,"maxFractionalDigits":3,"maxLength":4""")
        actual["the same fractional part one length up"] =
            codes(""""minFractionalDigits":3,"maxFractionalDigits":3,"maxLength":5""")
        actual["an INVERTED length pair"] = codes(""""minLength":8,"maxLength":4""")
        actual["integer and fractional places past the budget"] =
            codes(""""maxIntegerDigits":14,"minFractionalDigits":2,"maxFractionalDigits":2""")
        actual["the same places within it"] =
            codes(""""maxIntegerDigits":5,"minFractionalDigits":2,"maxFractionalDigits":2""")
        actual["integer places alone at the budget"] = codes(""""maxIntegerDigits":15""")
        actual["integer places one past the budget"] = codes(""""maxIntegerDigits":16""")
        actual["an amount whose maximum scale is suppressed"] =
            codes(""""trait":"amount","maxFractionalDigits":-1""")
        actual["an amount whose minimum scale exceeds its maximum"] =
            codes(""""trait":"amount","minFractionalDigits":5,"maxFractionalDigits":2""")

        val expected = mutableMapOf<String, List<String>>()
        expected["an ordinary length — the control"] = emptyList()
        expected["a maximum length of 16"] = emptyList()
        expected["a maximum length of 17"] = listOf(maxLengthTooBig)
        expected["a minimum length of 17"] = listOf("MVK_MIN_LENGTH_TOO_BIG")
        expected["a maximum length of 16 on a SIGNLESS number"] = listOf(maxLengthTooBig)
        expected["a maximum length of 17 beside a fractional part"] = emptyList()
        expected["a maximum length of 18 beside a fractional part"] = listOf(maxLengthTooBig)
        expected["an over-long maximum where NEITHER fractional member is declared"] =
            listOf(fractDigitsMissing, maxLengthTooBig)
        expected["the same undeclared pair at a maximum length of 17"] =
            listOf(fractDigitsMissing, maxLengthTooBig)
        expected["a maximum length below the fractional part"] = listOf("MVK_MAX_LENGTH_TOO_SMALL")
        expected["the same fractional part one length up"] = emptyList()
        expected["an INVERTED length pair"] = listOf("MVK_MIN_LENGTH_BIGGER_MAX_LENGTH")
        expected["integer and fractional places past the budget"] = listOf(arityTooBig)
        expected["the same places within it"] = emptyList()
        expected["integer places alone at the budget"] = emptyList()
        expected["integer places one past the budget"] = listOf("RK_MEMBER_ABOVE_DIGIT_BUDGET")
        expected["an amount whose maximum scale is suppressed"] = listOf("RK_MEMBER_ABOVE_DIGIT_BUDGET")
        expected["an amount whose minimum scale exceeds its maximum"] =
            listOf(fractDigitsInvalid, arityTooBig)
        assertEquals(expected, actual, "a number type against the model-wide digit budget")
    }

    /**
     * A scale past the cap is a codeless VERDICT, not a build failure: A12 judges the declaration and states the
     * range sentence (KF559). The pair reports it once, and is not also an inversion.
     */
    @Test
    fun aScalePastTheCapIsReportedAsItsRangeSentence() {
        assertEquals(emptyList(), codes(""""maxFractionalDigits":14"""), "14 is the admitted boundary")
        assertEquals(listOf("RK_MEMBER_ABOVE_SCALE_LIMIT"), codes(""""maxFractionalDigits":15"""))
        assertEquals(listOf("RK_MEMBER_ABOVE_SCALE_LIMIT"), codes(""""minFractionalDigits":15,"maxFractionalDigits":15"""))
    }
}
