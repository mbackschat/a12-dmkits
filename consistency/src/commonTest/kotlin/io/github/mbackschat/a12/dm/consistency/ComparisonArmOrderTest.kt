package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the order in which a comparison's agreement arms answer.
 *
 * Every row mirrors one measured against the real kernel by `rulekit.validate.laws.ComparisonArmOrderLawsTest`
 * and asserts the COMPLETE class set, because the defect this locks is a second class beside the right one,
 * which a mirror filtered to one family's codes cannot see (KERNEL-FINDINGS KF469).
 */
class ComparisonArmOrderTest {

    private fun codes(condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"id":"parcel-arm-order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Weight","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f3","name":"Priority","Field":{"fieldType":{"type":"EnumerationType",
                  "EnumerationType":{"values":[{"value":"EXPRESS"},{"value":"STANDARD"}]}}}},
                {"type":"Field","id":"f4","name":"ShipDate","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f5","name":"DeliveryWindow","Field":{"fieldType":{
                  "type":"DateRangeType","DateRangeType":{"format":"dd.MM.yyyy","rangeSeparator":"-"}}}},
                {"type":"Field","id":"f6","name":"Insured","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f7","name":"Signed","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }.sorted()

    @Test
    fun theArmAskedFirstIsTheOneThatAnswers() {
        val rows = linkedMapOf(
            "[Signed] == [ShipDate]" to "MVK_INVALID_COMPARE_TO_YES",
            "[Insured] == [ShipDate]" to "MVK_INVALID_COMPARE_TO_YESNO",
            "[Signed] == [DeliveryWindow]" to "MVK_INVALID_COMPARE_TO_YES",
            "[Insured] == [Carrier]" to "MVK_INVALID_COMPARE_TO_YESNO",
            "[Signed] == [Priority]" to "MVK_INVALID_COMPARE_TO_YES",
            "[Insured] == [Weight]" to "MVK_INVALID_COMPARE_TO_YESNO",
            "[ShipDate] == [DeliveryWindow]" to "MVK_INVALID_COMPARE_TO_DATE",
            "[ShipDate] == [Priority]" to "MVK_INVALID_COMPARE_TO_DATE",
            "[ShipDate] == [Weight]" to "MVK_INVALID_COMPARE_TO_DATE",
            "[DeliveryWindow] == [Carrier]" to "MVK_INVALID_COMPARE_TO_DATE_RANGE",
            "[DeliveryWindow] == [Priority]" to "MVK_INVALID_COMPARE_TO_DATE_RANGE",
            "[DeliveryWindow] == [Weight]" to "MVK_INVALID_COMPARE_TO_DATE_RANGE",
        )
        assertEquals(rows.mapValues { (_, code) -> listOf(code) }, rows.keys.associateWith { codes(it) })
    }
}
