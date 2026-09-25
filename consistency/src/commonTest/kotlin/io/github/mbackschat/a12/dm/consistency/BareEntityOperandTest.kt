package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of where a bare entity reference may stand ([KERNEL-FINDINGS KF472]).
 *
 * Mirrors `rulekit.validate.laws.BareEntityOperandLawsTest` row for row, on the same basket. Every row compares
 * the checker's COMPLETE class list against A12's one class, so a reference family resolving the name the
 * parser never took into a reference is as visible as a missing class.
 */
class BareEntityOperandTest {

    @Test
    fun aBareNameWhereAnOperationOrAConditionStandsIsATokenTheParserCannotUse() {
        val refused = listOf(
            "Total > 0", "NoSuchField > 0", "[Total] > Total", "[Total] > NoSuchField", "[Total] > {Total}",
            "[Total] > [Total] + Total", "[Total] > /Basket/Total", "[Total] > \$Total", "Lines*/Qty > 0",
            "(Total > 0)", "RoundUpValue(Total, 0) > Total", "Sum(Lines*/Qty Having Lines/Qty > 0) > 0",
            "Foo(Total)", "Foo (Total)", "[Total] == Foo(1)", "RoundUpValue(Total, NoSuchField) > 0",
            "RoundUpValue(Total, Total) > 0",
        )
        val admitted = listOf(
            "RoundUpValue(Total, 0) > 0", "[Total] == Sum(Lines*/Qty)", "Sum(Lines*/Qty Having [Lines/Qty] > 0) > 0",
        )
        val refusedOperations =
            listOf("Fees/Handling", "[Fees/Handling] + Fees/Handling", "NoSuchField", "Foo([Total])")
        val actual = linkedMapOf<String, List<String>>()
        val expected = linkedMapOf<String, List<String>>()
        (refused + admitted).forEach { condition ->
            actual[condition] = codes(rule("FieldFilled(Total) And $condition"))
            expected[condition] = if (condition in admitted) listOf() else listOf("MVK_UNEXPECTED_TOKEN")
        }
        // Divergence locks, DG93: the shape parser takes every argument as an entity slot, so a name in the
        // rounding digit count is resolved — a wrong class where it names nothing, silence where it names a field.
        expected["RoundUpValue(Total, NoSuchField) > 0"] = listOf("MVK_INVALID_ENTITY")
        expected["RoundUpValue(Total, Total) > 0"] = listOf()
        (refusedOperations + "[Fees/Handling]").forEach { operation ->
            actual["computation: $operation"] = codes(computation(operation))
            expected["computation: $operation"] =
                if (operation in refusedOperations) listOf("MVK_UNEXPECTED_TOKEN") else listOf()
        }
        assertEquals<Map<String, List<String>>>(expected, actual, "where a bare entity reference may stand")
    }

    private fun rule(condition: String) = """
        ,{"type":"Rule","id":"r0","name":"Probe","Rule":{"errorCondition":"$condition",
          "errorEntityRelPath":"../Total","errorCode":"C1","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    private fun computation(operation: String) = """
        ,{"type":"Computation","id":"c0","name":"TotalCalc","Computation":{"computedFieldRelPath":"../Total",
          "computationAlternatives":[{"operation":"$operation"}],
          "errorCode":"P","errorMessage":[{"locale":"en_US","text":"x"}]}}
    """.trimIndent()

    private fun codes(declaration: String): List<String> = check(basketModel(inBasket = declaration))
        .findings.filter { it.category == FindingCategory.A12_CONSTRAINT }.map { it.code }.sorted()
}
