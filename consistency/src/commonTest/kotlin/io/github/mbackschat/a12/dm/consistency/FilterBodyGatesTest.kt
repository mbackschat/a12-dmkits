package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the two gates a `Having` filter's body answers to.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.HavingFilterIterationGateLawsTest`, on the same shipment shape. The conjunct column
 * is what separates the gates: the scope gate yields to any in-scope reference and the iteration gate yields
 * to nothing, so a table carrying only sole-reference rows reads the two codes as one spelling difference.
 */
class FilterBodyGatesTest {

    private val noIteration = "MVK_NO_ITERATION_FOR_WILDCARD"
    private val invalidIteration = "MVK_INVALID_ITERATION_IN_FILTER_CONDITION"
    private val noWildcard = "MVK_NO_WILDCARD"

    /**
     * Two SIBLING repeatable groups, one with a repeatable child, plus a non-repeatable one — so a filter can
     * name a level that is repeatable and off the operand's path, repeatable and an unstarred ancestor on it,
     * or not repeatable at all.
     */
    private fun codes(group: String, target: String, operand: String, filter: String?): List<String> {
        val filtered = filter?.let { " Having $it" } ?: ""
        val condition = "FieldFilled($target) And NumberOfFilledFields($operand$filtered) > 0"
        return check(
            ModelSource(
                """
            {"header":{"id":"shipment","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Shipment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Carrier","Group":{"elements":[
                  {"type":"Field","id":"f1","name":"Scac","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Parcels","Group":{"repeatability":50,"elements":[
                  {"type":"Field","id":"f2","name":"Tracking","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g3","name":"Boxes","Group":{"repeatability":50,"elements":[
                    {"type":"Field","id":"f4","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f5","name":"Volume","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                  ${probe(group == PARCELS, target, condition)}
                ]}},
                {"type":"Group","id":"g4","name":"Claims","Group":{"repeatability":50,"elements":[
                  {"type":"Field","id":"f6","name":"ClaimNo","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f7","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                  ${probe(group == CLAIMS, target, condition)}
                ]}}
                ${probe(group == SHIPMENT, target, condition)}
              ]}}
             ]}}}
                """.trimIndent(),
            ),
        ).findings.map { it.code }
    }

    /** The probe rule, emitted into whichever group hosts it — the rows' one placement variable. */
    private fun probe(here: Boolean, target: String, condition: String): String = if (!here) {
        ""
    } else {
        """,{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"../$target","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
    }

    /** Rule at the NON-repeatable root, so only the operand's own star can reopen a level. */
    private fun fromRoot(filter: String?) =
        codes(SHIPMENT, "Reference", "Parcels*/Tracking", filter)

    /** Rule hosted at the repeatable `Claims` — a level the operand's path does not contain. */
    private fun fromClaims(filter: String?) =
        codes(CLAIMS, "ClaimNo", "/Shipment/Parcels*/Tracking", filter)

    /** Rule at the root over a `Boxes*` operand, leaving `Parcels` bound by nothing. */
    private fun fromRootOverBoxes(filter: String?) =
        codes(SHIPMENT, "Reference", "Parcels/Boxes*/Code", filter)

    /**
     * A12's LAST gate over the body: the constructs it may not name, and what pre-empts each.
     *
     * The construct gate is reachable only from a filter that is otherwise legal, so the in-scope conjunct in
     * the reporting row is load-bearing — measured both ways in
     * `rulekit.validate.laws.FilteredOperandGateOrderLawsTest`, with and without it, because the conjunct
     * changes what the gate BEFORE this one sees. Two of A12's three forbidden constructs are reported; a
     * NESTED filter is not, being one of the shapes this walk declines before the gate.
     */
    @Test
    fun theConstructsAFilterBodyMayNotName() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an in-scope reference alone — the control"] = fromRoot("Parcels/Weight > 0")
        actual["a CustomCondition beside an in-scope reference"] =
            fromRoot("Parcels/Weight > 0 And CustomCondition SomeCheck")
        actual["a RepetitionNotUnique beside an in-scope reference"] =
            fromRoot("Parcels/Weight > 0 And RepetitionNotUnique(Parcels/Tracking)")
        actual["a CustomCondition ALONE"] = fromRoot("CustomCondition SomeCheck")
        actual["a CustomCondition beside a STARRED reference"] =
            fromRoot("Parcels*/Weight > 0 And CustomCondition SomeCheck")

        val refused = listOf("MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION")
        val expected = mutableMapOf<String, List<String>>()
        expected["an in-scope reference alone — the control"] = emptyList()
        expected["a RepetitionNotUnique beside an in-scope reference"] = refused
        expected["a CustomCondition beside an in-scope reference"] = refused
        // The three CustomCondition rows land on A12's own three classes, which is the point of carrying
        // them together: the construct gate runs LAST, so moving the body one step in either direction
        // hands the verdict to a different gate. Each is the class
        // `rulekit.validate.laws.AggregateSlotLawsTest` measured on the same three shapes.
        expected["a CustomCondition ALONE"] = listOf(noIteration)
        expected["a CustomCondition beside a STARRED reference"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        assertEquals(expected, actual, "the constructs a filter body may not name")
    }

    /** The grid the kernel-side laws test calls the gate separator. */
    @Test
    fun anInScopeReferenceRescuesTheScopeGateAndNotTheIterationGate() {
        val actual = mutableMapOf<String, List<String>>()
        actual["the level the operand's own star reopens — sole"] =
            fromRoot("CurrentRepetition(Parcels) > 0")
        actual["a repeatable sibling off the operand's path — sole"] =
            fromRoot("CurrentRepetition(Claims) > 0")
        actual["a repeatable sibling off the operand's path — beside an in-scope reference"] =
            fromRoot("CurrentRepetition(Claims) > 0 And Parcels/Weight > 0")
        actual["a NON-repeatable group — sole"] = fromRoot("CurrentRepetition(Carrier) > 0")
        actual["a NON-repeatable group — beside an in-scope reference"] =
            fromRoot("CurrentRepetition(Carrier) > 0 And Parcels/Weight > 0")
        actual["that same sibling while the RULE iterates it — sole"] =
            fromClaims("CurrentRepetition(/Shipment/Claims) > 0")
        actual["that same sibling while the RULE iterates it — beside an in-scope reference"] =
            fromClaims("CurrentRepetition(/Shipment/Claims) > 0 And /Shipment/Parcels/Weight > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["the level the operand's own star reopens — sole"] = emptyList()
        expected["a repeatable sibling off the operand's path — sole"] = listOf(invalidIteration)
        expected["a repeatable sibling off the operand's path — beside an in-scope reference"] =
            listOf(invalidIteration)
        expected["a NON-repeatable group — sole"] = listOf(noIteration)
        expected["a NON-repeatable group — beside an in-scope reference"] = emptyList()
        // The enclosing rule's own iteration of the level does NOT rescue it, which is the row that surprises.
        expected["that same sibling while the RULE iterates it — sole"] = listOf(invalidIteration)
        expected["that same sibling while the RULE iterates it — beside an in-scope reference"] =
            listOf(invalidIteration)
        assertEquals(expected, actual, "the scope gate yields to a reference; the iteration gate to nothing")
    }

    /**
     * A level is ridden by ENDING at it as much as by passing through it, and an unstarred ancestor on the
     * operand's own path clears the iteration gate while failing the scope one.
     */
    @Test
    fun whichLevelsAReferenceRides() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an unstarred ancestor ON the operand's path, as a group — sole"] =
            codes(PARCELS, "Tracking", "Boxes*/Code", "CurrentRepetition(/Shipment/Parcels) > 0")
        actual["an unstarred ancestor ON the operand's path, as a field — sole"] =
            codes(PARCELS, "Tracking", "Boxes*/Code", "Weight > 0")
        actual["the same beside a reference into the reopened level"] =
            codes(PARCELS, "Tracking", "Boxes*/Code", "Weight > 0 And Boxes/Volume > 0")
        actual["a field UNDER the reopened level, which rides both"] =
            codes(PARCELS, "Tracking", "Boxes*/Code", "Boxes/Volume > 0")
        actual["a repeatable level off the path, as a field — sole"] =
            fromClaims("/Shipment/Claims/Amount > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["an unstarred ancestor ON the operand's path, as a group — sole"] = listOf(noIteration)
        expected["an unstarred ancestor ON the operand's path, as a field — sole"] = listOf(noIteration)
        expected["the same beside a reference into the reopened level"] = emptyList()
        expected["a field UNDER the reopened level, which rides both"] = emptyList()
        expected["a repeatable level off the path, as a field — sole"] = listOf(invalidIteration)
        assertEquals(expected, actual, "which levels a filter reference rides")
    }

    /**
     * The shapes it leaves alone, each because another gate answers before the filter is reached.
     *
     * The unfiltered control is what locates the first refusal: the operand alone already draws it, so no
     * filter row on that topology says anything. The last row is the positive control — without one, three
     * silent rows would pass on a model that never parsed.
     */
    @Test
    fun theShapesItLeavesAlone() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an operand crossing a repetition nothing addresses — unfiltered control"] =
            fromRootOverBoxes(null)
        actual["the same operand with a filter"] =
            fromRootOverBoxes("CurrentRepetition(/Shipment/Parcels) > 0")
        actual["a star inside the filter body"] =
            fromRoot("Parcels*/Weight > 0")
        actual["the `$` outer marker in the filter body"] =
            fromClaims("/Shipment/Claims/Amount > 0 And \$/Shipment/Claims/Amount > 0")
        actual["an out-of-path filter on the same model — the positive control"] =
            fromRoot("CurrentRepetition(Claims) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["an operand crossing a repetition nothing addresses — unfiltered control"] = listOf(noWildcard)
        // Reported since 2026-09-15. The sibling family used to skip a filtered text wholesale, so this row
        // recorded the under-report its own comment had already measured; it now reads the condition with
        // the filter BODY removed, and the operand outside it is judged like any other.
        expected["the same operand with a filter"] = listOf(noWildcard)
        expected["a star inside the filter body"] = listOf("MVK_NO_WILDCARDS_ALLOWED")
        expected["the `$` outer marker in the filter body"] = emptyList()
        expected["an out-of-path filter on the same model — the positive control"] = listOf(invalidIteration)
        assertEquals(expected, actual, "the shapes it leaves alone")
    }

    private companion object {
        const val SHIPMENT = "/Shipment"
        const val PARCELS = "/Shipment/Parcels"
        const val CLAIMS = "/Shipment/Claims"
    }
}
