package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of what A12's field-value read `[ … ]` demands of the entity it names
 * ([KERNEL-FINDINGS KF473]).
 *
 * Mirrors `rulekit.validate.laws.FieldValueReadOperandLawsTest` row for row, on the same basket and the same
 * fulfilment sheet. Every row compares the checker's COMPLETE class list against A12's one class, so the
 * filter chain's `MVK_NO_ITERATION_FOR_WILDCARD` surfacing beside a group read in a filter's body, or this
 * family speaking beside containment, is as visible as a missing class.
 */
class FieldValueReadOperandTest {

    private val every = "/Fulfilment/Shipments*/Parcels*"

    @Test
    fun aStarredReadIsTheStarsClassWhereverTheBracketStands() {
        val star = listOf("MVK_NO_WILDCARDS_ALLOWED")
        val actual = linkedMapOf<String, List<String>>()
        listOf(
            "[Total] == [Lines*/Qty]", "[Lines*/Qty] == [Total]", "[Total] == {[Lines*/Qty] + 1}",
            "[Total] == Abs([Lines*/Qty])", "[Lines*/Qty] > 0", "[Lines*/Qty] == \\\"x\\\"",
            "[Total] == [Lines*/Packaging/Weight]", "[Total] == [Fees]", "[Total] == [Lines*]", "[Total] == [Lines]",
            "[Total] == Sum(Lines*/Qty)",
        ).forEach { actual[it] = codes(basketModel(inBasket = rule("FieldFilled(Total) And $it", "../Total"))) }
        actual["from Lines: [Qty] == [/Basket/Lines*/Qty]"] =
            codes(basketModel(inLines = rule("FieldFilled(Qty) And [Qty] == [/Basket/Lines*/Qty]", "../Qty")))
        actual["computation: [Lines*/Qty]"] = codes(basketModel(inBasket = computation("[Lines*/Qty]")))

        val expected = linkedMapOf<String, List<String>>()
        actual.keys.forEach { expected[it] = star }
        expected["[Total] == [Fees]"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["[Total] == [Lines*]"] = listOf("MVK_NO_GROUPS_ALLOWED")
        expected["[Total] == [Lines]"] = listOf("MVK_NO_WILDCARD")
        expected["[Total] == Sum(Lines*/Qty)"] = listOf()
        assertEquals<Map<String, List<String>>>(
            expected,
            actual,
            "what a starred field-value read draws on each carrier",
        )
    }

    @Test
    fun theGatesAnswerInTheirOrderAfterTheReadersOwnClasses() {
        val rows = linkedMapOf(
            "Ledger | [$every/Weight] > 0" to "MVK_NO_WILDCARDS_ALLOWED",
            "Ledger | [/Fulfilment/Shipments/Parcels*/Weight] > 0" to "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
            "Shipments | [Parcels*/Weight] > 0" to "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
            "Summary | [$every/Weight] > 0" to "MVK_NO_WILDCARDS_ALLOWED",
            "Summary | [/Fulfilment/Shipments*/Parcels] > 0" to "MVK_NO_WILDCARD",
            "Summary | [$every] > 0" to "MVK_NO_GROUPS_ALLOWED",
            "Summary | [/Fulfilment/Ledger] > 0" to "MVK_NO_GROUPS_ALLOWED",
            "Summary | [/Fulfilment/Ledger/Total] > 0" to null,
            "Summary | [/Fulfilment/Ledger/Note] == \\\"x\\\"" to "MVK_INVALID_RAW_TYPE",
            "Summary | [/Fulfilment/Ledger/Partial] == [/Fulfilment/Ledger/Due]" to "MVK_INVALID_DATE_TYPE",
            "Summary | [/Fulfilment/Ledger/Due] == [/Fulfilment/Ledger/Due]" to null,
            "Summary | [/Fulfilment/Ledger/NoSuch] > 0" to "MVK_INVALID_ENTITY",
            "Summary | [/Fulfilment/Ledger*/Total] > 0" to "MVK_INVALID_WILDCARD",
            "Summary | [/Fulfilment/Ledger/Total*] > 0" to "MVK_INVALID_WILDCARD",
            "Summary | [/Fulfilment/Ledger/Note] == [$every/Label]" to "MVK_INVALID_RAW_TYPE",
            "Summary | [$every/Label] == [/Fulfilment/Ledger/Note]" to "MVK_NO_WILDCARDS_ALLOWED",
            "Summary | [/Fulfilment/Ledger/Due] == [$every/Label]" to "MVK_NO_WILDCARDS_ALLOWED",
            "Summary | Sum($every/Weight Having [/Fulfilment/Shipments/Parcels/Weight] > [$every/Quantity]) > 0" to
                "MVK_NO_WILDCARDS_ALLOWED",
            "Summary | Sum($every/Weight Having [/Fulfilment/Ledger] > 0) > 0" to "MVK_NO_GROUPS_ALLOWED",
        )
        val actual = rows.keys.associateWith { row ->
            val (group, read) = row.split(" | ", limit = 2)
            codes(sheet(group, rule("FieldFilled(/Fulfilment/Summary/Result) And $read", "../../Summary/Result")))
        }
        assertEquals<Map<String, List<String>>>(
            rows.mapValues { (_, code) -> listOfNotNull(code) },
            actual,
            "which class a field-value read draws, and in which order",
        )
    }

    /** Mirrors the laws twin's `theGatesReachEveryCarrierOfTheRead`, one row each. */
    @Test
    fun theGatesReachEveryCarrierOfTheRead() {
        val rows = linkedMapOf(
            "[/Fulfilment] > 0" to "MVK_NO_GROUPS_ALLOWED",
            "Sum($every/Weight Having [/Fulfilment/Shipments/Parcels] > 0) > 0" to "MVK_NO_GROUPS_ALLOWED",
            "Sum($every/Weight Having [/Fulfilment/Returns] > 0) > 0" to "MVK_NO_GROUPS_ALLOWED",
            "[/Fulfilment/Ledger/Partial] < Today" to "MVK_INVALID_DATE_TYPE",
            "[/Fulfilment/Ledger/Due] < Today" to null,
            "FieldValueIncludedInValueList(/Fulfilment/Summary/Memo, [/Fulfilment/Ledger/Partial])" to
                "MVK_INVALID_DATE_TYPE",
        )
        val copies = linkedMapOf(
            "../Settled := [/Fulfilment/Ledger/Partial]" to "MVK_INVALID_DATE_TYPE",
            "../Settled := [/Fulfilment/Ledger/Due]" to null,
            "../Memo := [/Fulfilment/Ledger/Note]" to "MVK_INVALID_RAW_TYPE",
            "../Result := [/Fulfilment/Ledger]" to "MVK_NO_GROUPS_ALLOWED",
            "../Result := [/Fulfilment/Ledger/Total]" to null,
        )
        val actual = rows.keys.associateWith { read ->
            codes(sheet("Summary", rule("FieldFilled(/Fulfilment/Summary/Result) And $read", "../../Summary/Result")))
        } + copies.keys.associateWith { copy ->
            val (target, operation) = copy.split(" := ", limit = 2)
            codes(sheet("Summary", copyInto(target, operation)))
        }
        assertEquals<Map<String, List<String>>>(
            (rows + copies).mapValues { (_, code) -> listOfNotNull(code) },
            actual,
            "the read's class on every carrier",
        )
    }

    private fun copyInto(target: String, operation: String) = """
        ,{"type":"Computation","id":"c0","name":"Copy","Computation":{"computedFieldRelPath":"$target",
          "computationAlternatives":[{"operation":"$operation"}],
          "errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    private fun rule(condition: String, errorField: String) = """
        ,{"type":"Rule","id":"r0","name":"Probe","Rule":{"errorCondition":"$condition",
          "errorEntityRelPath":"$errorField","errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    private fun computation(operation: String) = """
        ,{"type":"Computation","id":"c0","name":"TotalCalc","Computation":{"computedFieldRelPath":"../Total",
          "computationAlternatives":[{"operation":"$operation"}],
          "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    /** The fulfilment sheet the laws twin builds, with [declaration] added to the group named [into]. */
    private fun sheet(into: String, declaration: String): ModelSource {
        fun inside(group: String) = if (group == into) declaration else ""
        fun number(id: String, name: String) = """{"type":"Field","id":"$id","name":"$name","Field":{"fieldType":{
            "type":"NumberType","NumberType":{"maxFractionalDigits":0}}}}"""
        return ModelSource(
            """
            {"header":{"id":"fulfilment-values","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                {"type":"Group","id":"g1","name":"Summary","Group":{"elements":[
                  ${number("f0", "Result")},
                  {"type":"Field","id":"f8","name":"Memo","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f9","name":"Settled","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"yyyy-MM-dd"}}}}${inside("Summary")}]}},
                {"type":"Group","id":"g2","name":"Ledger","Group":{"elements":[
                  ${number("f1", "Total")},
                  {"type":"Field","id":"f5","name":"Note","Field":{"fieldType":{"type":"StringType",
                    "StringType":{"lineBreaksPermitted":true,"noValueValidation":true}}}},
                  {"type":"Field","id":"f6","name":"Partial","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"yyyy-MM-dd","datePrecision":"DAY_OPTIONAL"}}}},
                  {"type":"Field","id":"f7","name":"Due","Field":{"fieldType":{"type":"DateType",
                    "DateType":{"format":"yyyy-MM-dd"}}}}${inside("Ledger")}]}},
                {"type":"Group","id":"g3","name":"Shipments","Group":{"repeatability":2,"elements":[
                  {"type":"Group","id":"g4","name":"Parcels","Group":{"repeatability":2,"elements":[
                    ${number("f2", "Weight")}, ${number("f3", "Quantity")},
                    {"type":"Field","id":"f4","name":"Label","Field":{"fieldType":{"type":"StringType"}}}
                  ]}}${inside("Shipments")}]}},
                {"type":"Group","id":"g5","name":"Returns","Group":{"repeatability":2,"elements":[
                  {"type":"Field","id":"f10","name":"Reason","Field":{"fieldType":{"type":"StringType"}}}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        )
    }

    private fun codes(source: ModelSource): List<String> = checkRead(source)
        .findings.filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.sorted()
}
