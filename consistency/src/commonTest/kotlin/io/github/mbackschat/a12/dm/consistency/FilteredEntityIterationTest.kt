package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of the filtered-entity gate.
 *
 * Every row mirrors one measured against the real kernel by
 * `rulekit.validate.laws.FilteredEntityParallelIterationLawsTest`, on the same fulfilment shape. The row
 * that carries the risk is the one where nothing about the filtered operand changes: a filter on the rule's
 * own group is admitted alone and refused once another operand parallel-iterates.
 */
class FilteredEntityIterationTest {

    private val filtered = "MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED"
    private val notAllowed = "MVK_PARALLEL_ITERATION_NOT_ALLOWED"

    /**
     * A fulfilment model whose `Lines` and `Picks` key on `LineRef`, with `Audits` repeating unkeyed.
     *
     * [group] picks where the probe rule sits: inside `Lines`, whose own repetitions the error field is
     * addressed by, or at the root, which is the only anchoring `RepetitionNotUnique` is reachable from.
     */
    private fun codes(group: String, errorField: String, condition: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"fulfilment","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                {"type":"Field","id":"f0","name":"OrderRef","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":50,
                  "indexFieldName":"LineRef","elements":[
                  {"type":"Field","id":"f1","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"OrderedQty","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g2","name":"Details","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f3","name":"DetailNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f4","name":"DetailQty","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                  ${probe(group == LINES, errorField, condition)}
                ]}},
                {"type":"Group","id":"g3","name":"Picks","Group":{"repeatability":50,
                  "indexFieldName":"LineRef","elements":[
                  {"type":"Field","id":"f5","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f6","name":"PickedQty","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Group","id":"g4","name":"Checks","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f7","name":"CheckNote","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"f8","name":"CheckQty","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}},
                {"type":"Group","id":"g5","name":"Audits","Group":{"repeatability":20,"elements":[
                  {"type":"Field","id":"f9","name":"AuditNote","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Group","id":"g6","name":"Notes","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"fa","name":"NoteText","Field":{"fieldType":{"type":"StringType"}}},
                    {"type":"Field","id":"fb","name":"NoteQty","Field":{"fieldType":{"type":"NumberType"}}}
                  ]}}
                ]}}
                ${probe(group == FULFILMENT, errorField, condition)}
              ]}}
             ]}}}
            """.trimIndent(),
        ),
    ).findings.map { it.code }

    private fun probe(here: Boolean, errorField: String, condition: String): String = if (!here) {
        ""
    } else {
        """,{"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":"${condition.replace("\"", "\\\"")}",
              "errorEntityRelPath":"$errorField","errorCode":"C1",
              "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
    }

    private fun inLines(condition: String): List<String> =
        codes(LINES, "../OrderedQty", "FieldFilled(OrderedQty) And $condition")

    private fun inFulfilment(condition: String): List<String> =
        codes(FULFILMENT, "../Lines/LineRef", condition)

    @Test
    fun whatMakesAFilteredOperandParallelIterated() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a filtered operand under a keyed PARALLEL level"] =
            inLines(
                "Sum(/Fulfilment/Picks/Checks*/CheckQty " +
                    "Having [/Fulfilment/Picks/Checks/CheckNote] == \"x\") > 0",
            )
        actual["the same operand with NO filter"] = inLines("Sum(/Fulfilment/Picks/Checks*/CheckQty) > 0")
        actual["a filtered operand on the TARGET's own chain"] =
            inLines("Sum(Details*/DetailQty Having [Details/DetailNote] == \"x\") > 0")
        actual["a filtered operand on the target's chain, the condition parallel-iterating ELSEWHERE"] =
            inLines(
                "Sum(Details*/DetailQty Having [Details/DetailNote] == \"x\") > 0 " +
                    "And FieldFilled(/Fulfilment/Picks/PickedQty)",
            )
        actual["the parallel level declares NO index field"] =
            inLines(
                "Sum(/Fulfilment/Audits/Notes*/NoteQty " +
                    "Having [/Fulfilment/Audits/Notes/NoteText] == \"x\") > 0",
            )

        val expected = mutableMapOf<String, List<String>>()
        expected["a filtered operand under a keyed PARALLEL level"] = listOf(filtered)
        expected["the same operand with NO filter"] = emptyList()
        expected["a filtered operand on the TARGET's own chain"] = emptyList()
        // The pair that makes the second marking pass observable: the operand is byte-identical to the row
        // above and the rule is refused, because the read of `Picks` turned the rule's own `Lines` level
        // into a parallel-iterated one.
        expected["a filtered operand on the target's chain, the condition parallel-iterating ELSEWHERE"] =
            listOf(filtered)
        // A12 refuses an unkeyed parallel level during the parse with `MVK_NO_WILDCARD`, and nothing in the
        // report names it: [operandRepetitionScopeFindings] owns that class and does not judge a
        // filter-carrying text. A disclosed under-report, and the reason this family stays silent here
        // rather than naming a post-parse class on a rule that never parsed.
        expected["the parallel level declares NO index field"] = emptyList()
        assertEquals(expected, actual, "what makes a filtered operand parallel-iterated")
    }

    /** The order of `checkNormalRule`'s first two gates, which nothing but a measurement settles. */
    @Test
    fun whichPostParseGateAnswersFirst() {
        val filter = "Sum(/Fulfilment/Picks/Checks*/CheckQty " +
            "Having [/Fulfilment/Picks/Checks/CheckNote] == \"x\") > 0"

        val actual = mutableMapOf<String, List<String>>()
        actual["a filtered parallel operand, rule at the root"] = inFulfilment(filter)
        actual["the same beside a RepetitionNotUnique"] =
            inFulfilment("RepetitionNotUnique(Lines/LineRef) And $filter")
        actual["an UNFILTERED read starred below the parallel level, beside a RepetitionNotUnique"] =
            inFulfilment("RepetitionNotUnique(Lines/LineRef) And Sum(/Fulfilment/Picks/Checks*/CheckQty) > 0")

        val expected = mutableMapOf<String, List<String>>()
        expected["a filtered parallel operand, rule at the root"] = listOf(filtered)
        expected["the same beside a RepetitionNotUnique"] = listOf(notAllowed)
        expected["an UNFILTERED read starred below the parallel level, beside a RepetitionNotUnique"] =
            listOf(notAllowed)
        assertEquals(expected, actual, "which post-parse gate answers first")
    }

    /** The finding names the filtered operand and the target, since either end is where the fix goes. */
    @Test
    fun theFindingNamesTheFilteredOperandAndTheTarget() {
        val finding = check(
            ModelSource(
                """
                {"header":{"id":"fulfilment","locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Fulfilment","Group":{"elements":[
                    {"type":"Group","id":"g1","name":"Lines","Group":{"repeatability":50,
                      "indexFieldName":"LineRef","elements":[
                      {"type":"Field","id":"f1","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Field","id":"f2","name":"OrderedQty",
                        "Field":{"fieldType":{"type":"NumberType"}}}
                    ]}},
                    {"type":"Group","id":"g3","name":"Picks","Group":{"repeatability":50,
                      "indexFieldName":"LineRef","elements":[
                      {"type":"Field","id":"f5","name":"LineRef","Field":{"fieldType":{"type":"StringType"}}},
                      {"type":"Group","id":"g4","name":"Checks","Group":{"repeatability":5,"elements":[
                        {"type":"Field","id":"f7","name":"CheckNote",
                          "Field":{"fieldType":{"type":"StringType"}}},
                        {"type":"Field","id":"f8","name":"CheckQty",
                          "Field":{"fieldType":{"type":"NumberType"}}}
                      ]}}
                    ]}},
                    {"type":"Rule","id":"r0","name":"Probe","Rule":{
                      "errorCondition":"FieldFilled(Lines/OrderedQty) And Sum(Picks/Checks*/CheckQty Having [Picks/Checks/CheckNote] == \"x\") > 0",
                      "errorEntityRelPath":"../Lines/OrderedQty",
                      "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
                  ]}}
                 ]}}}
                """.trimIndent(),
            ),
        ).findings.single { it.code == filtered }
        assertEquals("/Fulfilment/Probe", finding.at)
        assertEquals(true, finding.explain.contains("Picks/Checks*/CheckQty"), finding.explain)
        assertEquals(true, finding.explain.contains("/Fulfilment/Lines/OrderedQty"), finding.explain)
    }

    private companion object {
        const val FULFILMENT = "/Fulfilment"
        const val LINES = "/Fulfilment/Lines"
    }
}
