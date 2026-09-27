package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/** The diagnostic names the refused partner and a corrective; the class sweep cannot assert prose. */
class BooleanComparisonAgreementTest {
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
