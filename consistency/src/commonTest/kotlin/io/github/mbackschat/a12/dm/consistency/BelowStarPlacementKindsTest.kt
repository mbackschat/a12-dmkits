package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the below-star placement on every other operand kind ([KERNEL-FINDINGS KF466]).
 *
 * Mirrors `rulekit.validate.laws.BelowStarPlacementKindsLawsTest` row for row, on the same fulfilment sheet and
 * the same spellings. Every row compares the checker's COMPLETE class list against A12's one class, so a
 * carrier's own bar co-firing beside the placement is as visible as a missing class.
 */
class BelowStarPlacementKindsTest {

    private val notInRuleGroup = "MVK_ERROR_FIELD_NOT_IN_RULEGROUP"
    private val noWildcard = "MVK_NO_WILDCARD"

    @Test
    fun everyOtherKindPlacesALevelUnstarredBelowTheFirstStar() {
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        CARRIERS.forEach { (carrier, control) ->
            val below = carrier.replace("%s", "/Fulfilment/Shipments*/Parcels")
            val every = carrier.replace("%s", "/Fulfilment/Shipments*/Parcels*")
            for (group in listOf("Shipments", "Ledger")) {
                actual["$group | $below"] = codes(group, below)
                expected["$group | $below"] = listOf(notInRuleGroup)
                actual["$group | $every — control"] = codes(group, every)
                expected["$group | $every — control"] = listOfNotNull(control)
            }
            actual["Summary | $below"] = codes("Summary", below)
            expected["Summary | $below"] = listOf(noWildcard)
        }
        assertEquals(expected, actual, "the placed class on every other operand kind")
    }

    /** A rule declared in [group] reporting on `Summary/Result`, whose condition reads [read]. */
    private fun codes(group: String, read: String): List<String> {
        val rule = """
            ,{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(/Fulfilment/Summary/Result) And $read",
              "errorEntityRelPath":"../../Summary/Result",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent()
        fun into(name: String) = if (name == group) rule else ""
        val source = """
            {"header":{"modelVersion":"29.4.0","id":"fulfilment-kinds","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Summary","Group":{"elements":[
                  ${number("f0", "Result")}${into("Summary")}]}},
                {"type":"Group","id":"g2","name":"Ledger","Group":{"elements":[
                  ${number("f1", "Total")}, ${string("f2", "LedgerCode")}, ${service("f3", "LedgerService")},
                  ${date("f4", "LedgerDate")}, ${range("f5", "Window", "dd.MM.yyyy")},
                  ${range("f6", "Season", "dd.MM")}${into("Ledger")}]}},
                {"type":"Group","id":"g3","name":"Shipments","Group":{"repeatability":2,"elements":[
                  {"type":"Group","id":"g4","name":"Parcels","Group":{"repeatability":2,"elements":[
                    ${number("f7", "Weight")}, ${number("f8", "Quantity")}, ${string("f9", "Label")},
                    ${date("f10", "ShippedOn")}, ${range("f11", "Cover", "dd.MM.yyyy")},
                    ${range("f12", "Peak", "dd.MM")},
                    {"type":"Field","id":"f13","name":"Fragile","Field":{"fieldType":{"type":"BooleanType"}}},
                    ${service("f14", "Service")},
                    {"type":"Group","id":"g5","name":"Seal","Group":{"elements":[${string("f15", "SealCode")}]}}
                  ]}}${into("Shipments")}]}}
              ]}}
             ]}}}
        """.trimIndent()
        return checkRead(ModelSource(source)).findings
            .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.distinct().sorted()
    }

    private fun number(id: String, name: String) =
        """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"NumberType",
            "NumberType":{"maxFractionalDigits":0}}}}"""

    private fun string(id: String, name: String) =
        """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"StringType"}}}"""

    private fun service(id: String, name: String) =
        """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"EnumerationType",
            "EnumerationType":{"values":[{"value":"STANDARD"},{"value":"EXPRESS"}]}}}}"""

    private fun date(id: String, name: String) =
        """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"DateType",
            "DateType":{"format":"dd.MM.yyyy"}}}}"""

    private fun range(id: String, name: String, format: String) =
        """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{"type":"DateRangeType",
            "DateRangeType":{"format":"$format","rangeSeparator":"-"}}}}"""

    private companion object {
        /** Each carrier with `%s` for the level list, and the class its fully starred control draws. */
        val CARRIERS = listOf(
            "FirstFilledValue(%s/Label) == \\\"x\\\"" to null,
            "FieldValuesNotUnique(%s/ShippedOn)" to null,
            "NumberOfDifferentValues(%s/ShippedOn) < 2" to null,
            "MaxValue(%s/ShippedOn) == [/Fulfilment/Ledger/LedgerDate]" to null,
            "MinValue(%s/ShippedOn) == [/Fulfilment/Ledger/LedgerDate]" to null,
            "DateRangesOverlap(/Fulfilment/Ledger/Window, %s/Cover)" to null,
            "AtLeastOneDateRangeOverlaps(/Fulfilment/Ledger/Window In %s/Cover)" to null,
            "DateRangesOverlap(/Fulfilment/Ledger/Season, %s/Peak)" to null,
            "AtLeastOneDateRangeOverlaps(/Fulfilment/Ledger/Season In %s/Peak)" to null,
            "NumberOfValueInFields(True In %s/Fragile) > 0" to null,
            "NumberOfValueInFields(\\\"x\\\" In %s/Label) > 0" to null,
            "AtLeastOneFieldValueIncludedInValueList(%s/Service In \\\"STANDARD\\\")" to null,
            "AtLeastOneFieldValueIncludedInValueList(%s/Label, /Fulfilment/Ledger/LedgerCode In \\\"x\\\")" to null,
            "AtLeastOneFieldValueIncludedInValueList(%s/Label In /Fulfilment/Ledger/LedgerCode)" to null,
            "AtLeastOneFieldValueIncludedInValueList(%s/Weight In /Fulfilment/Ledger/Total)" to null,
            "AtLeastOneFieldValueIncludedInValueList(/Fulfilment/Ledger/LedgerCode In %s/Label)" to null,
            "AtLeastOneFieldValueIncludedInValueList(/Fulfilment/Ledger/LedgerService In %s/Service)" to null,
            "NumberOfFilledFields(%s/Seal) > 0" to null,
            "AtLeastOneFieldFilled(%s/Seal)" to null,
            "Sum(%s/Weight Having ([/Fulfilment/Shipments/Parcels/Quantity] == " +
                "[/Fulfilment/Shipments/Parcels/Weight])) > 0" to null,
            "NumberOfFilledFields(%s/Weight Having ([/Fulfilment/Shipments/Parcels/Quantity] == " +
                "[/Fulfilment/Shipments/Parcels/Weight])) > 0" to null,
            // Its own bar refuses a star above the lowest level, so the fully starred control is not admitted.
            "SumOfProducts(%s/Weight, %s/Quantity) > 0" to "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED",
        )
    }
}
