package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of which `SumOfProducts` operands reach the operator's own four gates ([KERNEL-FINDINGS
 * KF470]).
 *
 * Mirrors `rulekit.validate.laws.SumOfProductsOperandReachLawsTest` row for row, on the same sheet. Every row
 * compares the checker's COMPLETE class list against A12's one class, so the operator's bar co-firing beside a
 * read that answers first is as visible as a missing class.
 */
class SumOfProductsOperandReachTest {

    private val every = "/Fulfilment/Shipments*/Parcels*"
    private val outerUnstarred = "/Fulfilment/Shipments/Parcels*"

    @Test
    fun aPairWhoseUnboundLevelsAreStarredReachesTheGatesWhereverItsFieldsLie() = measured(
        linkedMapOf(
            "Ledger | $every/Weight, $every/Quantity" to "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED",
            "Ledger | $every/Weight, $every/Label" to "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED",
            "Ledger | $every/Weight, /Fulfilment/Ledger/Total" to "MVK_DIFFERENT_GROUPS",
            "Ledger | $every/Weight Having [/Fulfilment/Shipments/Parcels/Quantity] > 0, $every/Quantity" to
                "MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED",
            "Summary | $every/Weight, $every/Quantity" to "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED",
            "Shipments | $every/Weight, $every/Quantity" to "MVK_WILDCARD_ONLY_AT_LOWEST_LEVEL_ALLOWED",
        ),
    )

    @Test
    fun aPairThatReadsARowMeetsTheReadFirst() = measured(
        linkedMapOf(
            "Shipments | Parcels*/Weight, Parcels*/Quantity" to "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
            "Ledger | $outerUnstarred/Weight, $outerUnstarred/Quantity" to "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
            "Summary | $outerUnstarred/Weight, $outerUnstarred/Quantity" to "MVK_NO_WILDCARD",
            "Summary | $every/Weight, $outerUnstarred/Quantity" to "MVK_NO_WILDCARD",
            "Summary | /Fulfilment/Shipments*/Parcels/Weight, /Fulfilment/Shipments*/Parcels/Quantity" to
                "MVK_NO_WILDCARD",
            "Shipments | Parcels*/Weight, $every/Quantity" to "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
        ),
    )

    private fun measured(rows: Map<String, String>) {
        val actual = rows.keys.associateWith { row ->
            val (group, pair) = row.split(" | ", limit = 2)
            codes(group, "SumOfProducts($pair) > 0")
        }
        assertEquals(rows.mapValues { (_, code) -> listOf(code) }, actual)
    }

    private fun codes(group: String, read: String): List<String> {
        val rule = """
            ,{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"FieldFilled(/Fulfilment/Summary/Result) And $read",
              "errorEntityRelPath":"../../Summary/Result",
              "errorCode":"PROBE","errorMessage":[{"locale":"en_US","text":"x"}]}}
        """.trimIndent()
        fun into(name: String) = if (name == group) rule else ""
        val source = """
            {"header":{"modelVersion":"29.4.0","id":"fulfilment-products","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Summary","Group":{"elements":[
                  ${number("f0", "Result")}${into("Summary")}]}},
                {"type":"Group","id":"g2","name":"Ledger","Group":{"elements":[
                  ${number("f1", "Total")}${into("Ledger")}]}},
                {"type":"Group","id":"g3","name":"Shipments","Group":{"repeatability":2,"elements":[
                  {"type":"Group","id":"g4","name":"Parcels","Group":{"repeatability":2,"elements":[
                    ${number("f2", "Weight")}, ${number("f3", "Quantity")},
                    {"type":"Field","id":"f4","name":"Label","Field":{"fieldType":{"type":"StringType"}}}
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
}
