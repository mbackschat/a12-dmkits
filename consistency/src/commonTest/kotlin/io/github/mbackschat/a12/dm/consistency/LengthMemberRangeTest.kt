package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of a length member's metamodel range (KERNEL-FINDINGS KF556), on JVM and JS.
 *
 * Every row is one `adapter.laws.CheckerLengthLimitAgreementTest` compares with Kernel 30.8.1: `minLength` and
 * `maxLength` lie between 1 and 99,999 on String, Custom and Number types, each codeless sentence is reported once
 * per field, an out-of-range member judges nothing else, and `-1` is "no bound" on String and Custom only.
 */
class LengthMemberRangeTest {

    private val above = "RK_MEMBER_ABOVE_TECHNICAL_LIMIT"
    private val notPositive = "RK_MEMBER_NOT_POSITIVE"
    private val signed = "RK_MEMBER_SIGNED"

    @Test
    fun theRangeIsOneToTheTechnicalLimit() {
        assertEquals(emptyList(), codes("StringType", """"minLength":1,"maxLength":99999"""))
        assertEquals(listOf(above), codes("StringType", """"maxLength":100000"""))
        assertEquals(listOf(notPositive), codes("CustomFieldType", """"name":"ref","minLength":0"""))
        assertEquals(listOf(signed), codes("NumberType", """"maxLength":-2"""))
    }

    @Test
    fun eachSentenceIsReportedOncePerField() {
        assertEquals(listOf(notPositive), codes("StringType", """"minLength":0,"maxLength":0"""))
        assertEquals(listOf(above, signed), codes("StringType", """"minLength":-2,"maxLength":100000"""))
    }

    @Test
    fun anOutOfRangeMemberJudgesNothingElse() {
        assertEquals(listOf(above), codes("StringType", """"minLength":100000,"maxLength":50"""))
        assertEquals(listOf(notPositive), codes("CustomFieldType", """"name":"ref","minLength":5,"maxLength":0"""))
        assertEquals(listOf(notPositive),
            codes("StringType", """"noValueValidation":true,"lineBreaksPermitted":true,"minLength":0"""))
    }

    @Test
    fun theUndefinedSentinelIsNoBoundOnStringAndCustomOnly() {
        assertEquals(emptyList(), codes("StringType", """"minLength":-1,"maxLength":-1"""))
        assertEquals(emptyList(), codes("CustomFieldType", """"name":"ref","maxLength":-1"""))
        assertEquals(listOf(signed), codes("NumberType", """"maxLength":-1"""))
    }

    private fun codes(type: String, members: String, fieldExtra: String = ""): List<String> =
        checkRead(ModelSource(model(type, members, fieldExtra))).findings
            .filter { it.at == "/Ticket/Entry" && it.justifiesRejection }.map { it.code }.sorted()

    private fun model(type: String, members: String, fieldExtra: String): String {
        val extra = if (type == "NumberType") ""","minFractionalDigits":0,"maxFractionalDigits":0""" else ""
        return """
            {"header":{"modelVersion":"29.4.0","id":"ticket","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Ticket","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Entry","Field":{"fieldType":{
                  "type":"$type","$type":{$members$extra}}$fieldExtra}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
