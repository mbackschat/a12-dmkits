package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "which class an unstarred aggregate operand draws", on both iteration carriers.
 *
 * Mirrors `rulekit.validate.laws.AggregateSlotLawsTest.whichClassAnUnstarredAggregateOperandDrawsOnEachIterationCarrier`.
 * The pair that carries the family is the ITERATING column: the same operand text that is a reference defect
 * off an iteration is a legal per-row read on a rule that iterates, so the class moves — and a family keyed
 * on the star alone would name the wrong one on every iterating model.
 */
class AggregateOperandIterationTest {

    @Test
    fun whichClassAnUnstarredAggregateOperandDrawsOnEachIterationCarrier() {
        val actual = mutableMapOf<String, List<String>>()
        actual["starred operand, filter, rule does not iterate — the control"] =
            codes("../Quantity", """Sum(Items*/Count Having [Items/Sku] == \"X\")""")
        actual["unstarred operand, filter, rule does not iterate"] =
            codes("../Quantity", """Sum(Items/Count Having [Items/Sku] == \"X\")""")
        actual["unstarred operand, NO filter, rule does not iterate"] =
            codes("../Quantity", "Sum(Items/Count)")
        actual["unstarred operand, filter, rule ITERATES"] =
            codes("../Items/Count", """Sum(Items/Count Having [Items/Sku] == \"X\")""")
        actual["unstarred operand, NO filter, rule ITERATES"] =
            codes("../Items/Count", "Sum(Items/Count)")
        actual["starred operand, filter, rule ITERATES — the control"] =
            codes("../Items/Count", """Sum(Items*/Count Having [Items/Sku] == \"X\")""")
        actual["unstarred operand, filter, rule ITERATES, another aggregate of the family"] =
            codes("../Items/Count", """MaxValue(Items/Count Having [Items/Sku] == \"X\")""")
        actual["a NON-repeating operand with a filter, rule does not iterate"] =
            codes("../Quantity", """Sum(Quantity Having [Items/Sku] == \"X\")""")
        actual["an unresolvable operand under a filter — the fixture's liveness control"] =
            codes("../Quantity", """Sum(Items*/Nowhere Having [Items/Sku] == \"X\")""")

        val expected = mutableMapOf<String, List<String>>()
        expected["starred operand, filter, rule does not iterate — the control"] = emptyList()
        expected["unstarred operand, filter, rule does not iterate"] = listOf("MVK_NO_WILDCARD")
        expected["unstarred operand, NO filter, rule does not iterate"] = listOf("MVK_NO_WILDCARD")
        expected["unstarred operand, filter, rule ITERATES"] = listOf("MVK_WILDCARDS_REQUIRED")
        // DISCLOSED SILENCE, not an admission. A12 reaches the arity gate here because the read is a legal
        // per-row one, and `addressedTargetOf` yields on any reference crossing a repetition the DECLARING
        // group does not sit in — which is the wrong question, the rule's iteration being set by its error
        // field. Closing it moves a helper several families share and is its own slice.
        expected["unstarred operand, NO filter, rule ITERATES"] = emptyList()
        expected["starred operand, filter, rule ITERATES — the control"] = emptyList()
        expected["unstarred operand, filter, rule ITERATES, another aggregate of the family"] =
            listOf("MVK_WILDCARDS_REQUIRED")
        expected["a NON-repeating operand with a filter, rule does not iterate"] =
            listOf("MVK_WILDCARDS_REQUIRED")
        // Every row above carries a filter, and a filter carries a quoted A12 literal; an unescaped quote
        // ends the JSON member and empties the model, which reads as this whole family being silent. This
        // row draws a class no part of the family under test can produce, so it fails when that happens.
        expected["an unresolvable operand under a filter — the fixture's liveness control"] =
            listOf("MVK_INVALID_ENTITY")
        assertEquals(expected, actual, "which class an unstarred aggregate operand draws")
    }

    private fun codes(errorField: String, call: String): List<String> = check(
        ModelSource(
            """
            {"header":{"id":"order","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Order","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Items","Group":{"repeatability":50,"elements":[
                  {"type":"Field","id":"f1","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                  {"type":"Field","id":"f2","name":"Count","Field":{"fieldType":{"type":"NumberType"}}}
                ]}},
                {"type":"Rule","id":"r0","name":"Probe","Rule":{
                  "errorCondition":"FieldFilled(Quantity) And CALL > 0","errorEntityRelPath":"ERRFIELD",
                  "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
              ]}}
             ]}}}
            """.trimIndent().replace("CALL", call).replace("ERRFIELD", errorField),
        ),
    ).findings.filter { it.justifiesRejection && it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }
}
