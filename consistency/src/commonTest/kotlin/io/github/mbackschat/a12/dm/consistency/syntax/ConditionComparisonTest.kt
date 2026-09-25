package io.github.mbackschat.a12.dm.consistency.syntax

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which comparisons a condition makes, and the characters each one spans.
 *
 * A comparison's own gates are raised once its right side is read (`vergleichsBedingung`'s action), so its
 * extent runs from the first token of its left side to the last of its right, and a text that fails to parse
 * after a comparison still yields it, as `ConditionCallTest` states for calls.
 */
class ConditionComparisonTest {

    private fun comparisons(text: String) = comparisonsIn(text, ConditionLanguage.EN, SyntaxEntry.CONDITION)

    @Test
    fun aComparisonSpansItsLeftSideThroughItsRightSide() {
        val text = "FieldFilled(Tier) And [Premium] + 1 >= Abs([Score])"
        assertEquals(
            listOf("[Premium] + 1 >= Abs([Score])"),
            comparisons(text).map { text.substring(it.start, it.end) },
        )
    }

    @Test
    fun textThatDoesNotParseYieldsTheComparisonsReadToTheirEnd() {
        val actual = mutableMapOf<String, List<String>>()
        actual["a comparison, then a stray connective"] =
            comparisons("[Reference] > 5 And And [Score] > 1").map { "${it.left} ${it.operator} ${it.right}" }
        actual["a comparison cut short"] = comparisons("[Reference] > ").map { it.left }
        val expected = mutableMapOf<String, List<String>>()
        expected["a comparison, then a stray connective"] = listOf("[Reference] > 5")
        expected["a comparison cut short"] = emptyList()
        assertEquals(expected, actual, "the comparisons a failed parse read to their end")
    }
}
