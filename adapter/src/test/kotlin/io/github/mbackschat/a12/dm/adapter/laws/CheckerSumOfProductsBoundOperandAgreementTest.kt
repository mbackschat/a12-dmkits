package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `SumOfProducts` operand that leaves its lowest repeatable level unstarred, because the rule binds that level,
 * draws `MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED` (a12-kernel-lean SPEC-2026-10-14-08, re-measured here). The rule
 * binds the level by iterating it, through its error field, or by being declared inside it; one unstarred operand
 * or both draw the class, while `Sum` and `MaxValue` admit the same pair. The class is `MVK_REPEATABLE_LEVEL_REQUIRED`
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
        val lowest = setOf("MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED")
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

    private class Row(val group: String, val errorField: String, val condition: String, val codes: Set<String>)

    private fun verdicts(row: Row): Pair<Set<String>, Set<String>> {
        val errorRel = "../" + row.errorField.removePrefix("${row.group}/")
        val draft = DraftRule(row.group, "ProbeRule", errorRel, row.condition, "PROBE", "ERROR")
        val result = validator.check(order, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        const val ROOT = "/Order"
        const val ITEMS = "/Order/Items"
        const val ITEM_COUNT = "/Order/Items/Count"
    }
}
