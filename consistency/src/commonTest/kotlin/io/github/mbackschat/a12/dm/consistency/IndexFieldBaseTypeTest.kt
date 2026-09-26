package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "which index-field types iterate jointly".
 *
 * Mirrors `rulekit.validate.laws.IndexFieldBaseTypeLawsTest` row for row, on the same one-model fixture: every
 * group is keyed by a `ShipmentKey`, and the groups differ only in that key's declared type.
 */
class IndexFieldBaseTypeTest {

    @Test
    fun aStringKeyPairsOnlyWithATextKey() {
        val others = listOf(
            "ByText", "ByCustom", "ByNumber", "ByCode", "ByFlag", "ByConfirm", "ByDate", "ByMoment", "ByTime",
            "ByFragment", "ByRange",
        )
        val actual = others.associateWith { reading("Invoices", it) }
        val expected = others.associateWith { if (it == "ByText" || it == "ByCustom") ADMITTED else INVALID_TYPE }
        assertEquals(expected, actual, "a String key beside each other kind of key")
    }

    @Test
    fun theOtherKindsPairByTheirOwnBaseType() {
        val actual = linkedMapOf(
            "Number beside Number of another scale" to reading("ByNumber", "ByNumberAlt"),
            "Enumeration beside Enumeration of other values" to reading("ByCode", "ByCodeAlt"),
            "Date beside DateTime" to reading("ByDate", "ByMoment"),
            "Date beside Time" to reading("ByDate", "ByTime"),
            "Date beside a date fragment" to reading("ByDate", "ByFragment"),
            "DateTime beside Time" to reading("ByMoment", "ByTime"),
            "Date beside a date range" to reading("ByDate", "ByRange"),
            "Boolean beside Confirm" to reading("ByFlag", "ByConfirm"),
            "Number beside Enumeration" to reading("ByNumber", "ByCode"),
            "Number beside Date" to reading("ByNumber", "ByDate"),
            "Enumeration beside Boolean" to reading("ByCode", "ByFlag"),
        )
        val expected = linkedMapOf(
            "Number beside Number of another scale" to ADMITTED,
            "Enumeration beside Enumeration of other values" to ADMITTED,
            "Date beside DateTime" to ADMITTED,
            "Date beside Time" to ADMITTED,
            "Date beside a date fragment" to ADMITTED,
            "DateTime beside Time" to ADMITTED,
            "Date beside a date range" to INVALID_TYPE,
            "Boolean beside Confirm" to INVALID_TYPE,
            "Number beside Enumeration" to INVALID_TYPE,
            "Number beside Date" to INVALID_TYPE,
            "Enumeration beside Boolean" to INVALID_TYPE,
        )
        assertEquals(expected, actual, "two keys of which neither is a String")
    }

    @Test
    fun aMarkedReferenceWithNoEnclosingIterationDrawsTheWalksClass() {
        val returned = "[/Fulfilment/Returns/Weight]"
        val actual = linkedMapOf(
            "marked, into the keyed Invoices" to summing("[$/Fulfilment/Invoices/Weight] == $returned"),
            "marked, into the unkeyed filtered group" to summing("[$/Fulfilment/Returns/Weight] == $returned"),
            "unmarked, into the keyed Invoices" to summing("[/Fulfilment/Invoices/Weight] == $returned"),
            "marked, into the fixed Summary" to summing("[$/Fulfilment/Summary/Total] == $returned"),
            "a filter on its own group — control" to summing("$returned > 0"),
            "the keyed read, outside any filter" to
                codes("Summary", "FieldFilled(Remark) And [/Fulfilment/Invoices/Weight] > 0", "../Remark"),
            "the unkeyed read, outside any filter" to
                codes("Summary", "FieldFilled(Remark) And $returned > 0", "../Remark"),
        )
        val expected = linkedMapOf(
            // A marked read in a body is checked as the rule's own read, so it draws the class the same read draws
            // outside any filter (KERNEL-FINDINGS KF353, KF487).
            "marked, into the keyed Invoices" to listOf("MVK_ERROR_FIELD_NO_INDEX"),
            "marked, into the unkeyed filtered group" to listOf("MVK_NO_WILDCARD"),
            "unmarked, into the keyed Invoices" to listOf("MVK_INVALID_ITERATION_IN_FILTER_CONDITION"),
            "marked, into the fixed Summary" to ADMITTED,
            "a filter on its own group — control" to ADMITTED,
            "the keyed read, outside any filter" to listOf("MVK_ERROR_FIELD_NO_INDEX"),
            "the unkeyed read, outside any filter" to listOf("MVK_NO_WILDCARD"),
        )
        assertEquals(expected, actual, "a marked reference no iteration encloses")
    }

    @Test
    fun aSemanticIndexKeyMeetsTheSameTable() {
        val actual = linkedMapOf(
            "a date index, a date key — control" to keyed("ByDate", "ShipDate"),
            "a date index, a date-time key" to keyed("ByDate", "ShipMoment"),
            "a date index, a date-range key" to keyed("ByDate", "ShipWindow"),
            "a date-range index, a date-range key" to keyed("ByRange", "ShipWindow"),
            "a date-range index, a date key" to keyed("ByRange", "ShipDate"),
        )
        val inconsistent = listOf("MVK_INDEX_TYPE_INCONSISTENT")
        val expected = linkedMapOf(
            "a date index, a date key — control" to ADMITTED,
            "a date index, a date-time key" to ADMITTED,
            "a date index, a date-range key" to inconsistent,
            "a date-range index, a date-range key" to ADMITTED,
            "a date-range index, a date key" to inconsistent,
        )
        assertEquals(expected, actual, "a semantic index's key beside the index field")
    }

    /** The rule declared in the fixed `Summary`, on its `Remark`, reading [group] by the key field [key]. */
    private fun keyed(group: String, key: String): List<String> =
        codes("Summary", "FieldFilled(Remark) And [/Fulfilment/$group/Weight For $key] > 0", "../Remark")

    /** The rule declared in [from], on its own `Note`, reading [other]'s `Weight`. */
    private fun reading(from: String, other: String): List<String> =
        codes(from, "FieldFilled(Note) And [/Fulfilment/$other/Weight] > 0", "../Note")

    /** The rule declared in the fixed `Summary`, on its `Remark`, summing `Returns*` under [filter]. */
    private fun summing(filter: String): List<String> =
        codes("Summary", "FieldFilled(Remark) And Sum(/Fulfilment/Returns*/Weight Having $filter) > 0", "../Remark")

    private fun codes(group: String, condition: String, errorTarget: String): List<String> {
        val rule = """{"type":"Rule","id":"r0","name":"ProbeRule","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"$errorTarget",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"see it"}]}}"""
        return checkRead(ModelSource(model(group, rule))).findings
            .filter { it.category == FindingCategory.A12_CONSTRAINT && it.at == "/Fulfilment/$group/ProbeRule" }
            .map { it.code }.distinct().sorted()
    }

    /** The fixture of the laws test, with [rule] placed in [ruleGroup]. */
    private fun model(ruleGroup: String, rule: String): String {
        fun field(id: String, name: String, type: String) =
            """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":$type}}"""
        fun number(id: String, name: String) = field(id, name, """{"type":"NumberType"}""")
        fun group(name: String, body: List<String>, extra: String = ""): String {
            val members = if (name == ruleGroup) body + rule else body
            return """{"type":"Group","id":"g$name","name":"$name","Group":{$extra"elements":[
                ${members.joinToString(",")}]}}"""
        }
        fun keyed(name: String, keyType: String) = group(
            name,
            listOf(
                field("k$name", "ShipmentKey", keyType),
                field("n$name", "Note", TEXT),
                number("w$name", "Weight"),
            ),
            """"repeatability":5,"indexFieldName":"ShipmentKey",""",
        )
        val groups = listOf(
            group(
                "Summary",
                listOf(
                    field("fRemark", "Remark", TEXT), number("fTotal", "Total"), field("fShipDate", "ShipDate", DATE),
                    field("fShipMoment", "ShipMoment", MOMENT), field("fShipWindow", "ShipWindow", RANGE),
                ),
            ),
            group("Returns", listOf(number("fReturned", "Weight")), """"repeatability":5,"""),
            keyed("Invoices", TEXT),
            keyed("ByText", TEXT),
            keyed("ByCustom", """{"type":"CustomFieldType","CustomFieldType":{"name":"CarrierCode"}}"""),
            keyed("ByNumber", """{"type":"NumberType"}"""),
            keyed("ByNumberAlt", """{"type":"NumberType","NumberType":{"maxFractionalDigits":2}}"""),
            keyed("ByCode", enumeration("A", "B")),
            keyed("ByCodeAlt", enumeration("X", "Y")),
            keyed("ByFlag", """{"type":"BooleanType"}"""),
            keyed("ByConfirm", """{"type":"ConfirmType"}"""),
            keyed("ByDate", DATE),
            keyed("ByMoment", MOMENT),
            keyed("ByTime", """{"type":"TimeType","TimeType":{"format":"HH:mm:ss"}}"""),
            keyed("ByFragment", """{"type":"DateFragmentType","DateFragmentType":{"formatOfFragment":"MM"}}"""),
            keyed("ByRange", RANGE),
        )
        return """
            {"header":{"id":"keyed-shipments","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[${groups.joinToString(",")}]}}
             ]}}}
        """.trimIndent()
    }

    private fun enumeration(vararg values: String): String {
        val declared = values.joinToString(",") { """{"value":"$it"}""" }
        return """{"type":"EnumerationType","EnumerationType":{"values":[$declared]}}"""
    }

    private companion object {
        const val TEXT = """{"type":"StringType"}"""
        const val DATE = """{"type":"DateType","DateType":{"format":"yyyy-MM-dd"}}"""
        const val MOMENT = """{"type":"DateTimeType","DateTimeType":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}"""
        const val RANGE = """{"type":"DateRangeType","DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}"""

        val ADMITTED = listOf<String>()
        val INVALID_TYPE = listOf("MVK_ERROR_FIELD_INVALID_INDEX_TYPE")
    }
}
