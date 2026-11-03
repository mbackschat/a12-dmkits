package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "a text ending on an operator runs out only when nothing stops the parser earlier".
 *
 * Every row mirrors one of `rulekit.validate.laws.DanglingOperatorPairLawsTest`. The completeness family used to
 * decide from the LAST token alone, so every pair below drew `MVK_INCOMPLETE_INPUT`; the minus row is the one
 * where the parser does run out and still draws another class, because a sign waits for one number.
 */
class DanglingOperatorPairTest {

    private fun codes(condition: String, target: String): List<String> =
        checkRead(ModelSource(model(condition, target))).findings
            .filter { it.at == "/Policy/Probe" }.map { it.code }

    private fun onReference(condition: String) = codes(condition, "../Reference")

    private fun onPremium(condition: String) = codes(condition, "../Premium")

    @Test
    fun aSecondOperatorBeforeTheEndIsTheTokenTheParserStopsAt() {
        val actual = linkedMapOf(
            "one trailing connective, the control" to onReference("FieldFilled(Reference) And"),
            "And, then Or" to onReference("FieldFilled(Reference) And Or"),
            "Or, then And" to onReference("FieldFilled(Reference) Or And"),
            "three connectives" to onReference("FieldFilled(Reference) And And And"),
            "a comment after the pair" to onReference("FieldFilled(Reference) And And ;; later"),
            "the lower-case pair" to onReference("FieldFilled(Reference) and and"),
            "one trailing comparison, the control" to onPremium("[Premium] >"),
            "a comparison, then a connective" to onPremium("[Premium] > And"),
            "a connective, then a comparison" to onPremium("[Premium] > 1 And >"),
            "a comparison, then a multiplication" to onPremium("[Premium] > *"),
            "a comparison, then a minus" to onPremium("[Premium] > -"),
        )
        val unexpected = listOf("MVK_UNEXPECTED_TOKEN")
        val expected = linkedMapOf(
            "one trailing connective, the control" to listOf("MVK_INCOMPLETE_INPUT"),
            "And, then Or" to unexpected,
            "Or, then And" to unexpected,
            "three connectives" to unexpected,
            "a comment after the pair" to unexpected,
            "the lower-case pair" to unexpected,
            "one trailing comparison, the control" to listOf("MVK_INCOMPLETE_INPUT"),
            "a comparison, then a connective" to unexpected,
            "a connective, then a comparison" to unexpected,
            "a comparison, then a multiplication" to unexpected,
            "a comparison, then a minus" to listOf("MVK_EXPECTED_TOKEN_NOT_FOUND"),
        )
        assertEquals(expected, actual, "which class a text ending on two operators draws")
    }

    private fun model(condition: String, target: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"claims-policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$target",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
