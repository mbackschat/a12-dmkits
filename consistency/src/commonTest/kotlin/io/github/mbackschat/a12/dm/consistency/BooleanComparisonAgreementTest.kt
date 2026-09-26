package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what a confirm or boolean declaration may be compared to.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.BooleanComparisonAgreementLawsTest`. Two rows carry the risk: two confirms under
 * `!=` are ADMITTED where the same pair under `==` is not, and an ordering comparison is the operator gates'
 * to answer — naming this family's class on either would be the wrong class on a correct rejection.
 */
class BooleanComparisonAgreementTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f3","name":"Expedited","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f4","name":"GiftWrapped","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f5","name":"TermsAccepted","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Field","id":"f6","name":"NewsletterOptIn","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }

    private fun comparison(text: String): List<String> = codes("FieldFilled(Reference) And $text")

    private val yes = listOf("MVK_INVALID_COMPARE_TO_YES")

    private val yesNo = listOf("MVK_INVALID_COMPARE_TO_YESNO")

    @Test
    fun whatAConfirmDeclarationMayBeComparedTo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["against True"] = comparison("[TermsAccepted] == True")
        actual["against False"] = comparison("[TermsAccepted] == False")
        actual["against another confirm, for inequality"] =
            comparison("[TermsAccepted] != [NewsletterOptIn]")
        actual["against another confirm, for equality"] =
            comparison("[TermsAccepted] == [NewsletterOptIn]")
        actual["against ITSELF, for inequality"] = comparison("[TermsAccepted] != [TermsAccepted]")
        actual["against a boolean"] = comparison("[TermsAccepted] == [Expedited]")
        actual["against a text constant"] = comparison("[TermsAccepted] == \\\"y\\\"")
        actual["against a text declaration"] = comparison("[TermsAccepted] == [GiftNote]")

        val expected = mutableMapOf<String, List<String>>()
        expected["against True"] = emptyList()
        expected["against False"] = yes
        expected["against another confirm, for inequality"] = emptyList()
        expected["against another confirm, for equality"] = yes
        expected["against ITSELF, for inequality"] = emptyList()
        expected["against a boolean"] = yes
        expected["against a text constant"] = yes
        expected["against a text declaration"] = yes
        assertEquals(expected, actual, "what a confirm declaration may be compared to")
    }

    @Test
    fun whatABooleanDeclarationMayBeComparedTo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["against True"] = comparison("[Expedited] == True")
        actual["against False"] = comparison("[Expedited] == False")
        actual["against another boolean"] = comparison("[Expedited] == [GiftWrapped]")
        actual["against a text constant"] = comparison("[Expedited] == \\\"y\\\"")
        actual["against a number declaration"] = comparison("[Expedited] == [Quantity]")

        val expected = mutableMapOf<String, List<String>>()
        expected["against True"] = emptyList()
        expected["against False"] = emptyList()
        expected["against another boolean"] = emptyList()
        expected["against a text constant"] = yesNo
        expected["against a number declaration"] = yesNo
        assertEquals(expected, actual, "what a boolean declaration may be compared to")
    }

    /** The sides swapped, and the two gates that answer ahead of this family. */
    @Test
    fun theGatesThisFamilyYieldsTo() {
        val actual = mutableMapOf<String, List<String>>()
        actual["True on the LEFT of a confirm"] = comparison("True == [TermsAccepted]")
        actual["a text constant on the LEFT of a confirm"] = comparison("\\\"y\\\" == [TermsAccepted]")
        actual["a boolean on the LEFT of a confirm"] = comparison("[Expedited] == [TermsAccepted]")
        actual["a confirm under an ORDERING operator"] = comparison("[TermsAccepted] < [Expedited]")
        actual["two written-out constants"] = comparison("True == False")

        val expected = mutableMapOf<String, List<String>>()
        expected["True on the LEFT of a confirm"] = emptyList()
        expected["a text constant on the LEFT of a confirm"] = yes
        expected["a boolean on the LEFT of a confirm"] = yes
        expected["a confirm under an ORDERING operator"] = listOf("MVK_INVALID_TYPE_FOR_COMPARISON")
        expected["two written-out constants"] = listOf("MVK_INVALID_COMPARE_TO_CONST")
        assertEquals(expected, actual, "the gates this family yields to")
    }

    /** The message names the partner and the corrective, which is what an author acts on. */
    @Test
    fun theMessageNamesThePartnerAndTheCorrective() {
        val findings = checkRead(
            ModelSource(
                """
                {"header":{"id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f5","name":"TermsAccepted","Field":{
                      "fieldType":{"type":"ConfirmType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"[TermsAccepted] == False","errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.filter { it.code == "MVK_INVALID_COMPARE_TO_YES" }
        assertEquals(1, findings.size, "the confirm arm reports once")
        val message = findings.single().explain
        assertEquals(true, "False" in message, "the message names the partner: $message")
        assertEquals(true, "!= True" in message, "the message names the corrective: $message")
    }
}
