package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of where the `$` outer marker may stand.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.OuterMarkerPositionLawsTest`, on the same claims shape. The risk sits in the two
 * silent rows: a marked path that resolves to nothing is `MVK_INVALID_ENTITY` in A12 and must not draw this
 * class, and a marker INSIDE a filter is legal, so reporting either would be an over-rejection.
 */
class OuterMarkerPositionTest {

    private val outer = "MVK_INVALID_OUTER_ITERATION"

    @Test
    fun whereTheOuterMarkerMayStand() {
        val actual = mutableMapOf<String, List<String>>()
        actual["no marker anywhere — the control"] = reporting("FieldFilled(Reference)")
        actual["a marker OUTSIDE any filter, on a path that resolves"] =
            reporting("FieldFilled(Reference) And [\$/Claim/ExcessDue] > 0")
        actual["a marker INSIDE a filter, beside an in-scope reference"] =
            reporting(
                "FieldFilled(Reference) And Sum(Assessments*/Amount Having " +
                    "([Assessments/Code] == \\\"X\\\" And [\$/Claim/ExcessDue] > 0)) > 0",
            )
        actual["a marker outside a filter, on a path that RESOLVES TO NOTHING"] =
            reporting("FieldFilled(Reference) And [\$/Claim/Nowhere] > 0")
        actual["a marker outside a filter, AHEAD of an unrelated defect"] =
            reporting("[\$/Claim/ExcessDue] > 0 And FieldFilled(Nowhere)")

        val expected = mutableMapOf<String, List<String>>()
        expected["no marker anywhere — the control"] = emptyList()
        expected["a marker OUTSIDE any filter, on a path that resolves"] = listOf(outer)
        // Legal, and the family stays out of the class the body's own gate would draw: a marked reference is
        // skipped by the filter's index walk, so the in-scope conjunct is what clears the scope gate.
        expected["a marker INSIDE a filter, beside an in-scope reference"] = emptyList()
        // RESOLUTION ANSWERS FIRST. A12 draws `MVK_INVALID_ENTITY` here, from a gate that runs before the
        // marker's — so naming this class would be the wrong class on a model A12 refuses for its address.
        expected["a marker outside a filter, on a path that RESOLVES TO NOTHING"] = emptyList()
        // A12 DOES report the marker here, the marked reference standing first in the text — but the second
        // defect makes the declaration's DSL unsound, and every family in this checker withholds its verdict
        // on such a declaration rather than judging a text whose membership set is short. A disclosed
        // under-report shared with the estate, not a stance this family takes alone.
        expected["a marker outside a filter, AHEAD of an unrelated defect"] = emptyList()
        assertEquals(expected, actual, "where the outer marker may stand")
    }

    /**
     * A conjunct OUTSIDE the filter that spells its body verbatim, marker first, is refused: A12 reads the
     * position, so a reader cutting the body out by its TEXT would set the conjunct aside too. Mirrors the
     * laws test's `aMarkerOutsideIsRefusedEvenWhereItsConjunctSpellsTheFilterBody`, every class included, so
     * a co-firing one shows.
     */
    @Test
    fun aMarkerOutsideIsRefusedEvenWhereItsConjunctSpellsTheFilterBody() {
        val body = "([\$/Claim/ExcessDue] > 0 And [Assessments/Code] == \\\"X\\\")"
        val filtered = "FieldFilled(Reference) And Sum(Assessments*/Amount Having $body) > 0"
        fun codes(condition: String) = check(ModelSource(model(condition))).findings
            .filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.distinct()

        assertEquals(emptyList(), codes(filtered), "the filter alone — the control")
        assertEquals(listOf(outer), codes("$filtered And $body"), "the filter, then its body's text outside it")
    }

    /** The finding names the marked path, since dropping the marker is the repair. */
    @Test
    fun theFindingNamesTheMarkedPath() {
        val finding = check(ModelSource(model("FieldFilled(Reference) And [\$/Claim/ExcessDue] > 0")))
            .findings.single { it.code == outer }
        assertEquals("/Claim/Probe", finding.at)
        assertEquals(true, finding.explain.contains("/Claim/ExcessDue"), finding.explain)
    }

    private fun reporting(condition: String): List<String> =
        check(ModelSource(model(condition))).findings.filter { it.code == outer }.map { it.code }

    /** An insurance claims desk mirroring the laws test's carrier. */
    private fun model(condition: String): String = """
        {"header":{"id":"claim-outer-marker","locales":[{"code":"en_US"}]},
         "content":{"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Claim","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"ExcessDue","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Assessments","Group":{"repeatability":20,"elements":[
              {"type":"Field","id":"f2","name":"Code","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"$condition","errorEntityRelPath":"../Reference",
              "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()
}
