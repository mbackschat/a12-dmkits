package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the operand-scope walk.
 *
 * Every reporting row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.ParallelIterationRouteLawsTest`, on the same fulfilment shape. The SILENT rows are
 * where the risk sits: each is a model A12 ADMITS, or one it refuses with a class another family owns.
 */
class OperandRepetitionScopeTest {

    private val noWildcard = "MVK_NO_WILDCARD"

    /**
     * A fulfilment model with five repeatable branches under one root, differing only in how they key.
     *
     * `Shipments` is keyed and carries an unkeyed `Parcels`; `Invoices` repeats its key name; `Returns`
     * declares no index; `Credits` is keyed and carries a keyed `Lines`; `Refunds` keys under another name
     * and `Adjustments` under the same name but another type. The rule is placed in [ruleGroup], so the
     * declaring group never becomes an uncontrolled second variable.
     */
    private fun codes(ruleGroup: String, errorRel: String, condition: String): List<String> {
        fun ruleIn(group: String) = if (group == ruleGroup) {
            """,{"type":"Rule","id":"r0","name":"Probe","Rule":{"errorCondition":"$condition",
             "errorEntityRelPath":"$errorRel","errorCode":"C1",
             "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        } else {
            ""
        }
        return checkRead(
            ModelSource(
                """
                {"header":{"modelVersion":"29.4.0","id":"fulfilment","locales":[{"code":"en_US"}]},
                 "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"OrderRef","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f1","name":"OrderTotal","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Group","id":"g1","name":"Shipments","Group":{"repeatability":20,
                      "indexFieldName":"ShipmentKey","elements":[
                      {"type":"Field","id":"f2","name":"ShipmentKey","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f3","name":"ShipmentCost","Field":{"fieldType":{"type":"NumberType"}}},
                      {"type":"Group","id":"g2","name":"Parcels","Group":{"repeatability":20,"elements":[
                        {"type":"Field","id":"f4","name":"ParcelWeight","Field":{"fieldType":{"type":"NumberType"}}}
                      ]}}${ruleIn("Shipments")}
                    ]}},
                    {"type":"Group","id":"g3","name":"Invoices","Group":{"repeatability":20,
                      "indexFieldName":"ShipmentKey","elements":[
                      {"type":"Field","id":"f5","name":"ShipmentKey","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f6","name":"InvoiceAmount","Field":{"fieldType":{"type":"NumberType"}}}
                      ${ruleIn("Invoices")}
                    ]}},
                    {"type":"Group","id":"g4","name":"Returns","Group":{"repeatability":20,"elements":[
                      {"type":"Field","id":"f7","name":"ShipmentKey","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f8","name":"ReturnAmount","Field":{"fieldType":{"type":"NumberType"}}}
                      ${ruleIn("Returns")}
                    ]}},
                    {"type":"Group","id":"g5","name":"Credits","Group":{"repeatability":20,
                      "indexFieldName":"ShipmentKey","elements":[
                      {"type":"Field","id":"f9","name":"ShipmentKey","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"fa","name":"CreditAmount","Field":{"fieldType":{"type":"NumberType"}}},
                      {"type":"Group","id":"g6","name":"Lines","Group":{"repeatability":20,
                        "indexFieldName":"LineKey","elements":[
                        {"type":"Field","id":"fb","name":"LineKey","Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Field","id":"fc","name":"LineAmount","Field":{"fieldType":{"type":"NumberType"}}}
                      ]}}
                    ]}},
                    {"type":"Group","id":"g7","name":"Refunds","Group":{"repeatability":20,
                      "indexFieldName":"RefundKey","elements":[
                      {"type":"Field","id":"fd","name":"RefundKey","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"fe","name":"RefundAmount","Field":{"fieldType":{"type":"NumberType"}}}
                    ]}},
                    {"type":"Group","id":"g8","name":"Adjustments","Group":{"repeatability":20,
                      "indexFieldName":"ShipmentKey","elements":[
                      {"type":"Field","id":"ff","name":"ShipmentKey","Field":{"fieldType":{"type":"NumberType"}}},
                      {"type":"Field","id":"fg","name":"AdjustmentAmount","Field":{"fieldType":{"type":"NumberType"}}}
                    ]}}${ruleIn("Fulfilment")}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
    }

    /** A rule in [group] reporting on [field] of that group, reading it and one probe operand. */
    private fun probe(group: String, field: String, operand: String): List<String> =
        codes(group, "../$field", "FieldFilled($field) And [$operand] > 0")

    @Test
    fun whichLevelsAnOperandMayCrossUnstarred() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a form-level operand — the control"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/OrderTotal")
        actual["a sibling keyed under the SAME name — the admitted parallel iteration"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Shipments/ShipmentCost")
        actual["a level strictly BELOW the target's own"] =
            probe("Shipments", "ShipmentCost", "/Fulfilment/Shipments/Parcels/ParcelWeight")
        actual["a sibling with NO index field"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Returns/ReturnAmount")
        actual["an unkeyed level beneath an admitted keyed one"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Shipments/Parcels/ParcelWeight")

        val expected = mutableMapOf<String, List<String>>()
        expected["a form-level operand — the control"] = emptyList()
        expected["a sibling keyed under the SAME name — the admitted parallel iteration"] = emptyList()
        expected["a level strictly BELOW the target's own"] = listOf(noWildcard)
        expected["a sibling with NO index field"] = listOf(noWildcard)
        expected["an unkeyed level beneath an admitted keyed one"] = listOf(noWildcard)
        assertEquals(expected, actual, "which levels an operand may cross unstarred")
    }

    @Test
    fun whichClassAParallelLevelDraws() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the target's path declares no index at all"] =
            probe("Returns", "ReturnAmount", "/Fulfilment/Shipments/ShipmentCost")
        actual["the two index fields differ in NAME"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Refunds/RefundAmount")
        actual["the two index fields share a name and differ in TYPE"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Adjustments/AdjustmentAmount")
        actual["two keyed groups on the operand's own chain"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Credits/Lines/LineAmount")

        val expected = mutableMapOf<String, List<String>>()
        expected["the target's path declares no index at all"] = listOf("MVK_ERROR_FIELD_NO_INDEX")
        expected["the two index fields differ in NAME"] = listOf("MVK_ERROR_FIELD_INVALID_INDEX")
        expected["the two index fields share a name and differ in TYPE"] =
            listOf("MVK_ERROR_FIELD_INVALID_INDEX_TYPE")
        expected["two keyed groups on the operand's own chain"] = listOf("MVK_MULTIPLE_INDEX_FIELDS")
        assertEquals(expected, actual, "which class a parallel level draws")
    }

    /**
     * That the walk stops at the OUTERMOST offending level rather than at the first gate of the chain.
     *
     * The same doubly-keyed operand draws the multiple-index class into a keyed target and the no-index class
     * into an unkeyed one — measured, and the row a reading of the gate chain alone gets wrong, since that
     * chain resolves the operand's own index before the target's.
     */
    @Test
    fun theOutermostCrossedLevelAnswers() {
        val actual = mutableMapOf<String, List<String>>()
        actual["doubly-keyed operand into a KEYED target"] =
            probe("Invoices", "InvoiceAmount", "/Fulfilment/Credits/Lines/LineAmount")
        actual["the same operand into an UNKEYED target"] =
            probe("Returns", "ReturnAmount", "/Fulfilment/Credits/Lines/LineAmount")

        val expected = mutableMapOf<String, List<String>>()
        expected["doubly-keyed operand into a KEYED target"] = listOf("MVK_MULTIPLE_INDEX_FIELDS")
        expected["the same operand into an UNKEYED target"] = listOf("MVK_ERROR_FIELD_NO_INDEX")
        assertEquals(expected, actual, "the outermost crossed level answers")
    }

    @Test
    fun aWildcardCollapsesItsOwnLevelAndNothingBelowIt() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an unstarred level beneath a starred one"] = codes(
            "Fulfilment", "../OrderTotal",
            "FieldFilled(OrderTotal) And Sum(Shipments*/Parcels/ParcelWeight) > 0",
        )
        actual["both levels starred — the control"] = codes(
            "Fulfilment", "../OrderTotal",
            "FieldFilled(OrderTotal) And Sum(Shipments*/Parcels*/ParcelWeight) > 0",
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["an unstarred level beneath a starred one"] = listOf(noWildcard)
        expected["both levels starred — the control"] = emptyList()
        assertEquals(expected, actual, "a wildcard collapses its own level and nothing below it")
    }

    /**
     * A GROUP operand on the sibling cells draws the field's classes, its own level being the one crossed —
     * `ParallelIterationRouteLawsTest.theSiblingCellsTakeAGroupOperandAlike`, the field read beside it the
     * within-model twin.
     */
    @Test
    fun aGroupOperandOnTheSiblingCellsDrawsTheFieldsClasses() {
        fun group(path: String) =
            codes("Invoices", "../InvoiceAmount", "FieldFilled(InvoiceAmount) And GroupFilled($path)")
        val actual = linkedMapOf(
            "a group keyed under the same name — the control" to group("/Fulfilment/Shipments"),
            "a group keyed under ANOTHER name" to group("/Fulfilment/Refunds"),
            "the same operand as a FIELD read" to probe("Invoices", "InvoiceAmount", "/Fulfilment/Refunds/RefundAmount"),
            "a group keyed under the same name, of another TYPE" to group("/Fulfilment/Adjustments"),
            "a group with NO index field" to group("/Fulfilment/Returns"),
        )
        val expected = linkedMapOf(
            "a group keyed under the same name — the control" to emptyList(),
            "a group keyed under ANOTHER name" to listOf("MVK_ERROR_FIELD_INVALID_INDEX"),
            "the same operand as a FIELD read" to listOf("MVK_ERROR_FIELD_INVALID_INDEX"),
            "a group keyed under the same name, of another TYPE" to listOf("MVK_ERROR_FIELD_INVALID_INDEX_TYPE"),
            "a group with NO index field" to listOf(noWildcard),
        )
        assertEquals(expected, actual, "the sibling cells, over a group")
    }

    /**
     * The shape it deliberately leaves alone, because A12 answers it with a class another family names: a
     * target OUTSIDE the declaring group is `MVK_ERROR_FIELD_NOT_IN_RULEGROUP`, drawn at the top of the same
     * method and ahead of every gate here.
     */
    @Test
    fun theShapeItLeavesAlone() {
        // Not silence, and no longer this family's business either: `ERROR_TARGET_CONTAINMENT` names the
        // class the KDoc above predicted, so the row asserts the prediction instead of only recording it.
        assertEquals(
            listOf("MVK_ERROR_FIELD_NOT_IN_RULEGROUP"),
            codes(
                "Shipments", "../../OrderTotal",
                "FieldFilled(/Fulfilment/OrderTotal) And [/Fulfilment/Refunds/RefundAmount] > 0",
            ),
            "the target lies outside the declaring group",
        )
    }
}
