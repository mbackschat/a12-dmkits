package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `SumOfProducts` operand that leaves its lowest repeatable level unstarred, because the rule binds that level,
 * draws `MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED` (a12-kernel-lean SPEC-2026-10-14-08, re-measured here). The rule
 * binds the level by iterating it, through its error field, or by being declared inside it; one unstarred operand
 * or both draw the class, while `Sum` and `MaxValue` admit the same pair, and the owning-group gate still answers
 * ahead of it. The class is `MVK_REPEATABLE_LEVEL_REQUIRED`
 * only where the operands' path has no repeatable level at all, and where the rule does not bind the level the
 * reference reader answers first, with `MVK_NO_WILDCARD`. Each row is pinned for A12 and compared against the
 * portable checker, which reported the declaring-group pair under the wrong class.
 *
 * Own domain (the order fixture's repeatable `Items`, with `Count` and `UnitWeight`).
 */
class CheckerSumOfProductsBoundOperandAgreementTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val validator = RuleValidator()

    @Test
    fun anOperandLeftUnstarredOnABoundLevelDrawsTheLowestLevelClass() {
        val lowest = setOf("MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED")
        val rows = linkedMapOf(
            "iterated, the left operand unstarred" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And SumOfProducts(Items/Count, Items*/UnitWeight) > 5", lowest),
            "iterated, the right operand unstarred" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And SumOfProducts(Items*/UnitWeight, Items/Count) > 5", lowest),
            "iterated, both unstarred" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And SumOfProducts(Items/Count, Items/UnitWeight) > 5", lowest),
            "declared in the group, one unstarred" to
                Row(ITEMS, ITEM_COUNT, "FieldFilled(Count) And SumOfProducts(Count, /Order/Items*/UnitWeight) > 5", lowest),
            "declared in the group, both unstarred" to
                Row(ITEMS, ITEM_COUNT, "FieldFilled(Count) And SumOfProducts(Count, UnitWeight) > 5", lowest),
            "iterated, the owning groups differ" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And SumOfProducts(Items/Count, Quantity) > 5",
                    setOf("MVK_DIFFERENT_GROUPS")),
            "not bound — the reader answers" to
                Row(ROOT, "/Order/Quantity", "FieldFilled(Quantity) And SumOfProducts(Items/Count, Items*/UnitWeight) > 5",
                    setOf("MVK_NO_WILDCARD")),
            "no repeatable level" to
                Row(ROOT, "/Order/Quantity", "FieldFilled(Quantity) And SumOfProducts(Quantity, UnitPrice) > 5",
                    setOf("MVK_REPEATABLE_LEVEL_REQUIRED")),
            "both starred — the control" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And SumOfProducts(Items*/Count, Items*/UnitWeight) > 5", emptySet()),
            "both starred inside the group" to
                Row(ITEMS, ITEM_COUNT, "FieldFilled(Count) And SumOfProducts(/Order/Items*/Count, /Order/Items*/UnitWeight) > 5",
                    emptySet()),
            "Sum admits the pair" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And Sum(Items/Count, Items*/UnitWeight) > 5", emptySet()),
            "MaxValue admits the pair" to
                Row(ROOT, ITEM_COUNT, "FieldFilled(Items/Count) And MaxValue(Items/Count, Items*/UnitWeight) > 5", emptySet()),
        )
        val observed = rows.mapValues { (_, row) -> verdicts(row) }
        assertEquals(rows.mapValues { (_, row) -> row.codes to row.codes }, observed, "(A12, ours)")
    }

    /**
     * The same pairs in a computation, whose target binds its group as a rule's error field does (cold review
     * 2026-10-21): the checker's binding gate reaches computations too, which the rows above never measured.
     */
    @Test
    fun aComputationTargetBindsTheLevelAsAnErrorFieldDoes() {
        val rows = linkedMapOf(
            "target in the group, declared at the root" to Comp(ROOT, "Items/StockQty", "SumOfProducts(Items/Count, Items*/UnitWeight)"),
            "target in the group, declared in it" to Comp(ITEMS, "StockQty", "SumOfProducts(Count, /Order/Items*/UnitWeight)"),
            "target outside the group" to Comp(ROOT, "LineTotal", "SumOfProducts(Items/Count, Items*/UnitWeight)"),
            "both starred, target outside — the control" to Comp(ROOT, "LineTotal", "SumOfProducts(Items*/Count, Items*/UnitWeight)"),
        )
        val observed = rows.mapValues { (_, row) ->
            val json = RuntimeLaws.withTieredComputation(order, row.group, "ProbeComputation", "../" + row.target, null,
                listOf(RuntimeLaws.CompAlt(null, row.operation)))
            val a12 = KernelConsistencyOracle().checkConsistency(json, ReferenceResolvers.selfContained())
                .filter { it.severity() == Severity.ERROR }
                .mapNotNull { Regex("\\[([A-Z][A-Z0-9_]+)]").find(it.message())?.groupValues?.get(1) }.toSet()
            val ours = rejectingCodes(json).toSet()
            a12 to ours
        }
        val lowest = setOf("MVK_WILDCARD_AT_LOWEST_REPEATABLE_LEVEL_REQUIRED")
        assertEquals(linkedMapOf(
            "target in the group, declared at the root" to (lowest to lowest),
            "target in the group, declared in it" to (lowest to lowest),
            "target outside the group" to (setOf("MVK_NO_WILDCARD") to setOf("MVK_NO_WILDCARD")),
            "both starred, target outside — the control" to (emptySet<String>() to emptySet()),
        ), observed, "(A12, ours)")
    }

    private class Comp(val group: String, val target: String, val operation: String)

    private class Row(val group: String, val errorField: String, val condition: String, val codes: Set<String>)

    private fun verdicts(row: Row): Pair<Set<String>, Set<String>> {
        val errorRel = "../" + row.errorField.removePrefix("${row.group}/")
        val draft = DraftRule(row.group, "ProbeRule", errorRel, row.condition, "PROBE", "ERROR")
        val result = validator.check(order, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = rejectingCodes(validator.inject(order, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        const val ROOT = "/Order"
        const val ITEMS = "/Order/Items"
        const val ITEM_COUNT = "/Order/Items/Count"
    }
}
