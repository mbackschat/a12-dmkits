package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the filter walk's THIRD branch — a semantic index in the body.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.SemanticIndexInFilterLawsTest`, on the same policy shape. The two ordered rows are
 * what the branch costs: an index and an off-path read in one body are answered by whichever the text
 * writes first, so a reader that asked the two questions in a fixed order would be wrong in one order.
 */
class SemanticIndexInFilterTest {

    private val notAllowed = "MVK_SEMANTIC_INDEX_NOT_ALLOWED"

    /** Coverages and Endorsements key alike, which is the only pairing here that parallel-iterates. */
    private fun codes(errorField: String, condition: String): List<String> = checkRead(
        ModelSource(
            """
            {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                {"type":"Field","id":"f0","name":"PolicyRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Coverages","Group":{"repeatability":20,
                  "indexFieldName":"CoverageRef","elements":[
                  {"type":"Field","id":"f2","name":"CoverageRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f3","name":"Limit","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f4","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
                ]}},
                {"type":"Group","id":"g2","name":"Endorsements","Group":{"repeatability":20,
                  "indexFieldName":"CoverageRef","elements":[
                  {"type":"Field","id":"f5","name":"CoverageRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"EndorsementAmount",
                    "Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g3","name":"Riders","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f7","name":"RiderNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f8","name":"RiderAmount",
                      "Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}},
                {"type":"Group","id":"g4","name":"Claims","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f9","name":"ClaimRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"fa","name":"ClaimAmount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"${condition.replace("\"", "\\\"")}",
                  "errorEntityRelPath":"$errorField","errorCode":"C1",
                  "errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private fun onPremium(condition: String) =
        codes("../Premium", "FieldFilled(Premium) And $condition")

    private fun onACoverage(condition: String) =
        codes("../Coverages/Limit", "FieldFilled(Coverages/Limit) And $condition")

    @Test
    fun whereASemanticIndexIsRefusedForSittingInAFilter() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an index over the FILTERED group, literal key"] =
            onPremium("Sum(Coverages*/Limit Having [Coverages/Note For \"C1\"] == \"x\") > 0")
        actual["an index over ANOTHER group, literal key"] =
            onPremium("Sum(Claims*/ClaimAmount Having [Coverages/Limit For \"C1\"] > 0) > 0")
        actual["an index whose key is READ from the filtered group"] =
            onPremium("Sum(Claims*/ClaimAmount Having [Coverages/Limit For Claims/ClaimRef] > 0) > 0")
        actual["the same index OUTSIDE any filter — the control"] =
            onPremium("[Coverages/Limit For \"C1\"] > 0")
        actual["an off-path iterating reference BEFORE the indexed one"] =
            onPremium(
                "Sum(Coverages*/Limit Having [Claims/ClaimRef] == \"y\" " +
                    "And [Coverages/Note For \"C1\"] == \"x\") > 0",
            )
        actual["the same two, the indexed reference FIRST"] =
            onPremium(
                "Sum(Coverages*/Limit Having [Coverages/Note For \"C1\"] == \"x\" " +
                    "And [Claims/ClaimRef] == \"y\") > 0",
            )
        actual["an index in the filter whose key names a GROUP"] =
            onPremium("Sum(Coverages*/Limit Having [Coverages/Note For Claims] == \"x\") > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["an index over the FILTERED group, literal key"] = listOf(notAllowed)
        // The group the index addresses is not a variable: A12 asks only whether some level of some
        // reference in the body carries an index at all.
        expected["an index over ANOTHER group, literal key"] = listOf(notAllowed)
        // An ENTITY key is asked no iteration question in a body, so a sound one is the walk's class too.
        expected["an index whose key is READ from the filtered group"] = listOf(notAllowed)
        // The control that makes the POSITION load-bearing rather than the construct.
        expected["the same index OUTSIDE any filter — the control"] = emptyList()
        expected["an off-path iterating reference BEFORE the indexed one"] =
            listOf("MVK_INVALID_ITERATION_IN_SET_EXPRESSION")
        expected["the same two, the indexed reference FIRST"] = listOf(notAllowed)
        // A12 answers this from the index's OWN spec gates, which run while the spec is parsed and ahead of the
        // walk: the key-kind class, with no iteration class although the group repeats.
        expected["an index in the filter whose key names a GROUP"] = listOf("MVK_SEMANTIC_INDEX_NO_FIELD")
        assertEquals(expected, actual, "where a semantic index is refused for sitting in a filter")
    }

    /** The parse-time throw erases the whole-rule gate that would otherwise answer this shape. */
    @Test
    fun theFilterWalkThrowsBeforeTheWholeRuleGatesRun() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an indexed filter on an operand under a keyed PARALLEL level"] =
            onACoverage(
                "Sum(Endorsements/Riders*/RiderAmount " +
                    "Having [Coverages/Note For \"C1\"] == \"x\") > 0",
            )
        actual["the same operand with a filter carrying NO index"] =
            onACoverage(
                "Sum(Endorsements/Riders*/RiderAmount " +
                    "Having [Endorsements/Riders/RiderNote] == \"x\") > 0",
            )

        val expected = mutableMapOf<String, List<String>>()
        // Under-reported: the operand crosses a repetition the filter walk's own reader refuses to judge,
        // so the index branch never runs — and [filteredEntityIterationFindings] must stay silent here,
        // because A12's verdict is the parse-time class rather than its post-parse one.
        expected["an indexed filter on an operand under a keyed PARALLEL level"] = emptyList()
        expected["the same operand with a filter carrying NO index"] =
            listOf("MVK_PARALLEL_ITERATION_FOR_PATH_WITH_ASTERISKS_NOT_ALLOWED")
        assertEquals(expected, actual, "the filter walk throws before the whole-rule gates run")
    }
}
