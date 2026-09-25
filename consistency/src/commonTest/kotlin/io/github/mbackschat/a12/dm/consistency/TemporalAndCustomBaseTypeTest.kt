package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the base-type class of a TIME, DATE_TIME, date-fragment and CUSTOM declaration.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.TemporalAndCustomBaseTypeLawsTest`, on the same fields kind for kind. A12 builds the
 * four temporal kinds as one date type and gives a custom declaration the string's base type, so the checker
 * reads each by that class wherever a gate compares kinds (KERNEL-FINDINGS KF468).
 */
class TemporalAndCustomBaseTypeTest {

    private fun codes(condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"parcel-shipment","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Carrier","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f2","name":"Service","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f3","name":"Weight","Field":{"fieldType":{"type":"NumberType",
                  "NumberType":{"maxFractionalDigits":2}}}},
                {"type":"Field","id":"f4","name":"ShipDate","Field":{"fieldType":{"type":"DateType",
                  "DateType":{"format":"dd.MM.yyyy"}}}},
                {"type":"Field","id":"f5","name":"DispatchMonth","Field":{"fieldType":{
                  "type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"yyyy-MM"}}}},
                {"type":"Field","id":"f6","name":"ReturnMonth","Field":{"fieldType":{
                  "type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"yyyy-MM"}}}},
                {"type":"Field","id":"f7","name":"PickupTime","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f8","name":"DropoffTime","Field":{"fieldType":{"type":"TimeType",
                  "TimeType":{"format":"HH:mm:ss"}}}},
                {"type":"Field","id":"f9","name":"ShippedAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f10","name":"DeliveredAt","Field":{"fieldType":{"type":"DateTimeType",
                  "DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}}},
                {"type":"Field","id":"f11","name":"Tracking","Field":{"fieldType":{"type":"CustomFieldType",
                  "CustomFieldType":{"name":"TrackingCode"}}}},
                {"type":"Field","id":"f12","name":"Insured","Field":{"fieldType":{"type":"BooleanType"}}},
                {"type":"Field","id":"f13","name":"Signed","Field":{"fieldType":{"type":"ConfirmType"}}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Reference) And CONDITION_HERE","errorEntityRelPath":"../Reference",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CONDITION_HERE", condition),
        ),
    ).findings.map { it.code }.sorted()

    private fun measured(rows: Map<String, String?>) {
        val actual = rows.keys.associateWith { codes(it) }
        val expected = rows.mapValues { (_, code) -> listOfNotNull(code) }
        assertEquals(expected, actual)
    }

    @Test
    fun whichBaseTypeClassEachTemporalAndCustomKindFallsInto() = measured(
        linkedMapOf(
            "[DispatchMonth] < [ReturnMonth]" to null,
            "[PickupTime] < [DropoffTime]" to null,
            "[ShippedAt] < [DeliveredAt]" to null,
            "[DispatchMonth] < [Carrier]" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[PickupTime] < [Carrier]" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[ShippedAt] DiffersWithToleranceRange1 [Weight]" to "MVK_INVALID_TYPE_FOR_TOLERANCE_OP",
            "[Tracking] < 1" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[Signed] == [DispatchMonth]" to "MVK_INVALID_COMPARE_TO_YES",
            "[Insured] == [PickupTime]" to "MVK_INVALID_COMPARE_TO_YESNO",
            "AtLeastOneFieldValueIncludedInValueList(ShipDate, DispatchMonth In \\\"x\\\")" to
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED",
            "AtLeastOneFieldValueIncludedInValueList(PickupTime, ShippedAt In \\\"x\\\")" to
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED",
            "AtLeastOneFieldValueIncludedInValueList(Carrier, Tracking In \\\"x\\\")" to null,
            "AtLeastOneFieldValueIncludedInValueList(Carrier In Service, PickupTime)" to
                "MVK_VARYING_TYPES_NOT_ALLOWED",
            "FieldValueIncludedInValueList(Carrier, [PickupTime])" to "MVK_VARYING_TYPES_NOT_ALLOWED",
        ),
    )

    @Test
    fun eachArmAReclassifiedKindReachesIsMeasuredOnTheKindItself() = measured(
        linkedMapOf(
            "[ShipDate] < \\\"x\\\"" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[PickupTime] < \\\"x\\\"" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[DispatchMonth] < \\\"x\\\"" to "MVK_INVALID_TYPE_FOR_COMPARISON",
            "[Tracking] DiffersWithToleranceRange1 1" to "MVK_INVALID_TYPE_FOR_TOLERANCE_OP",
            "[Signed] == [Tracking]" to "MVK_INVALID_COMPARE_TO_YES",
            "[Insured] == [Tracking]" to "MVK_INVALID_COMPARE_TO_YESNO",
            "[Signed] == [PickupTime]" to "MVK_INVALID_COMPARE_TO_YES",
        ),
    )
}
