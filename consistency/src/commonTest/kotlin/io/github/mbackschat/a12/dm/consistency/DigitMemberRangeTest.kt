package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of a Number type's digit-count ranges (KERNEL-FINDINGS KF559), on JVM and JS.
 *
 * Every row is one `adapter.laws.CheckerDigitMemberRangeAgreementTest` compares with Kernel 30.8.1: integer places
 * 1 to 15, fractional places 0 to 14 with `-1` for "not declared", each sentence once per field across lengths and
 * digits, and an out-of-range count no bound for a law that reads it.
 */
class DigitMemberRangeTest {

    private val aboveBudget = "RK_MEMBER_ABOVE_DIGIT_BUDGET"
    private val aboveScale = "RK_MEMBER_ABOVE_SCALE_LIMIT"
    private val notPositive = "RK_MEMBER_NOT_POSITIVE"
    private val signed = "RK_MEMBER_SIGNED"

    @Test
    fun eachCountHasItsOwnRange() {
        assertEquals(emptyList(), codes(""""maxIntegerDigits":15,"minFractionalDigits":0,"maxFractionalDigits":0"""))
        assertEquals(listOf(aboveBudget), codes(""""maxIntegerDigits":16"""))
        assertEquals(listOf(notPositive), codes(""""maxIntegerDigits":0"""))
        assertEquals(listOf(signed), codes(""""maxIntegerDigits":-1"""))
        assertEquals(emptyList(), codes(""""minFractionalDigits":14,"maxFractionalDigits":14"""))
        assertEquals(listOf(aboveScale), codes(""""maxFractionalDigits":15"""))
        assertEquals(listOf(signed), codes(""""minFractionalDigits":-2"""))
    }

    @Test
    fun eachSentenceIsReportedOncePerFieldAcrossLengthsAndDigits() {
        assertEquals(listOf(signed), codes(""""maxIntegerDigits":-2,"maxFractionalDigits":-2,"maxLength":-2"""))
        assertEquals(listOf(aboveBudget, aboveScale), codes(""""maxIntegerDigits":16,"maxFractionalDigits":15"""))
    }

    @Test
    fun anOutOfRangeCountIsNoBoundForALawThatReadsIt() {
        assertEquals(listOf(aboveScale), codes(""""minFractionalDigits":15"""))
        assertEquals(listOf(aboveScale), codes(""""maxIntegerDigits":14,"minFractionalDigits":15,"maxFractionalDigits":15"""))
        assertEquals(listOf("MVK_MAX_LENGTH_TOO_SMALL", aboveScale),
            codes(""""minFractionalDigits":2,"maxFractionalDigits":15,"maxLength":2"""))
    }

    /** "Has a fractional part" is an OR over the pair: a known nonzero member decides it, an out-of-range one otherwise leaves it open. */
    @Test
    fun theLengthBudgetAsksAThreeValuedFractionalPart() {
        assertEquals(listOf("MVK_MAX_LENGTH_TOO_BIG", aboveScale),
            codes(""""minFractionalDigits":15,"maxFractionalDigits":2,"maxLength":18"""))
        assertEquals(listOf(signed), codes(""""minFractionalDigits":-2,"maxFractionalDigits":0,"maxLength":17"""))
        assertEquals(listOf(aboveScale), codes(""""maxFractionalDigits":15,"maxLength":18"""))
    }

    @Test
    fun anAmountsDerivedIntegerCountIsJudgedLikeADeclaredOne() {
        assertEquals(listOf(aboveBudget), codes(""""trait":"amount","maxFractionalDigits":-1"""))
        assertEquals(listOf(notPositive, aboveScale).sorted(), codes(""""trait":"amount","maxFractionalDigits":15"""))
        assertEquals(listOf(aboveBudget), codes(""""trait":"percent","maxIntegerDigits":16"""))
    }

    @Test
    fun aFieldDeletedFromTheDefaultVariantIsNotJudged() {
        val deleted = ""","variantFilter":{"filterType":"ONLY","variants":["premium"]}"""
        assertEquals(emptyList(), codes(""""maxIntegerDigits":16""", deleted))
        assertEquals(emptyList(), codes(""""maxFractionalDigits":15""", deleted))
    }

    private fun codes(members: String, fieldExtra: String = ""): List<String> =
        checkRead(ModelSource(model(members, fieldExtra))).findings
            .filter { it.at == "/Ticket/Effort" && it.justifiesRejection }.map { it.code }.sorted()

    private fun model(members: String, fieldExtra: String): String = """
        {"header":{"id":"ticket","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Ticket","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Effort","Field":{"fieldType":{
              "type":"NumberType","NumberType":{$members}}$fieldExtra}}
          ]}}
         ]}}}
    """.trimIndent()
}
