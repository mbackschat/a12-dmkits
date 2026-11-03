package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/** The diagnostic preserves declaration location and both operand spellings beyond class-set agreement. */
class ComparisonOperatorOperandsTest {
    /** The finding names both sides, since the fix is to change one of them or the operator. */
    @Test
    fun theFindingNamesBothSides() {
        val finding = checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"order","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"GiftNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Reference) And [GiftNote] < [Reference]",
                      "errorEntityRelPath":"../Reference",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == "MVK_INVALID_TYPE_FOR_COMPARISON" }
        assertEquals("/Order/Probe", finding.at)
        assertEquals(true, finding.explain.contains("[GiftNote]"), finding.explain)
        assertEquals(true, finding.explain.contains("[Reference]"), finding.explain)
    }
}
