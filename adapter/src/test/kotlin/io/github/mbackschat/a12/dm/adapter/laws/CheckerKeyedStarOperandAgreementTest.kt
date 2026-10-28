package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The checker against A12: a semantic-index key does not hide a repetition star BELOW the index group from any
 * operand gate that refuses one (KERNEL-FINDINGS KF476, a12-kernel-lean SPEC-2026-10-28-07's `q4`).
 *
 * The key selects one store and the rule binds its shelf, so `Items*` is the only star on each operand; the
 * semantic-index chain admits it, and each single-value operand then meets its own star gate exactly as the
 * unkeyed operand does: `MVK_NO_WILDCARDS_ALLOWED`, or `MVK_NO_WILDCARDS_G_ALLOWED` on the two group-list
 * operators that run that gate. The checker reduced a keyed operand through a reader that refuses a star, so
 * every one of these gates stood down. The `C` rows are the controls: a bracketed read and a value-list entry,
 * which already agreed; the ungated `AtLeastOneGroupFilled` and the aggregate, which admit the star; the star on
 * the index group itself, which the chain answers first; and the unstarred lower group, bound and unbound.
 *
 * Own domain (an e-commerce shop's stores, shelves and items; the rule reports on the shelf, so it iterates it).
 */
class CheckerKeyedStarOperandAgreementTest {

    private val shop = DocumentModelDraft.named("shop-keyed-star").locale("en_US")
        .rootGroup("Shop") { s -> s
            .group("Stores") { st -> st.repeatable(5).indexField("StoreCode")
                .stringField("StoreCode") { }
                .group("Shelves") { sh -> sh.repeatable(5)
                    .stringField("ShelfNote") { }
                    .numberField("Capacity") { it.fractionDigits(0) }
                    .dateField("Restocked") { }
                    .group("Items") { i -> i.repeatable(9)
                        .numberField("Qty") { it.fractionDigits(0) }
                        .stringField("Label") { }
                        .dateField("Shipped") { } } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aStarBelowTheKeyedGroupMeetsEveryOperandGate() {
        val actual = ROWS.mapValues { (_, condition) -> judged(condition) }
        assertEquals(ROWS.mapValues { (row, _) -> A12.getValue(row).let { it to it } }, actual, "as (A12, ours)")
    }

    private fun rejections(json: String) =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.mapTo(HashSet()) { it.code }

    private fun judged(clause: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Shop/Stores/Shelves", "KeyedStarProbe", "../ShelfNote",
            "FieldFilled(ShelfNote) And $clause", "PROBE", "ERROR")
        val a12 = validator.check(shop, draft)
            .let { r -> if (r.ok()) emptySet() else r.diagnostics().mapTo(HashSet()) { it.ledgerClass() } }
        return a12 to rejections(validator.inject(shop, draft))
    }

    private companion object {
        private const val ITEMS = "/Shop/Stores/Shelves/Items*"
        private const val KEY = "For \"s1\""

        /** The clause after `FieldFilled(ShelfNote) And`. */
        val ROWS = linkedMapOf(
            "K1 GroupFilled" to "GroupFilled($ITEMS $KEY)",
            "K2 GroupNotFilled" to "GroupNotFilled($ITEMS $KEY)",
            "K3 AllGroupsFilled" to "AllGroupsFilled($ITEMS $KEY, $ITEMS $KEY)",
            "K4 NotAllGroupsFilled" to "NotAllGroupsFilled($ITEMS $KEY)",
            "K5 FieldFilled" to "FieldFilled($ITEMS/Qty $KEY)",
            "K6 FieldNotFilled" to "FieldNotFilled($ITEMS/Qty $KEY)",
            "K7 Valid" to "Valid($ITEMS/Label $KEY, \"T\")",
            "K8 RangeAsString" to "RangeAsString($ITEMS/Label $KEY, 1, 2) == \"ab\"",
            "K9 RoundAccountingValue" to "RoundAccountingValue($ITEMS/Qty $KEY, 0) > 0",
            "K10 AbsValue" to "AbsValue($ITEMS/Qty $KEY) > 0",
            "K11 Length" to "Length($ITEMS/Label $KEY) > 0",
            "K12 DayFromDate" to "DayFromDate($ITEMS/Shipped $KEY) > 0",
            "K13 a value-list subject" to "FieldValueIncludedInValueList($ITEMS/Label $KEY, \"x\")",
            "K14 a RepetitionNotUnique key" to "RepetitionNotUnique($ITEMS/Qty $KEY)",
            "K15 a Date construction slot" to "Date(\"01\", \"02\", YearFromDate($ITEMS/Shipped $KEY)) > [Restocked]",
            "C1 a bracketed read" to "[$ITEMS/Qty $KEY] > 0",
            "C2 a value-list entry" to "FieldValueIncludedInValueList(ShelfNote, [$ITEMS/Label $KEY])",
            "C3 the ungated AtLeastOneGroupFilled" to "AtLeastOneGroupFilled($ITEMS $KEY)",
            "C4 an aggregate" to "Sum($ITEMS/Qty $KEY) > 0",
            "C5 the star on the index group" to "GroupFilled(/Shop/Stores* $KEY)",
            "C6 the bound lower group, unstarred" to "GroupFilled(/Shop/Stores/Shelves $KEY)",
            "C7 the unbound lower group, unstarred" to "GroupFilled(/Shop/Stores/Shelves/Items $KEY)",
            "V1 Valid over a bound keyed text" to "Valid(/Shop/Stores/Shelves/ShelfNote $KEY, \"T\")",
            "V2 Valid over a bound keyed number" to "Valid(/Shop/Stores/Shelves/Capacity $KEY, \"T\")",
        )

        private const val ONE = "MVK_NO_WILDCARDS_ALLOWED"
        private const val LIST = "MVK_NO_WILDCARDS_G_ALLOWED"

        /** Measured through `RuleValidator`, kernel 30.8.1, 2026-10-28. */
        val A12 = mapOf(
            "K1 GroupFilled" to setOf(ONE),
            "K2 GroupNotFilled" to setOf(ONE),
            "K3 AllGroupsFilled" to setOf(LIST),
            "K4 NotAllGroupsFilled" to setOf(LIST),
            "K5 FieldFilled" to setOf(ONE),
            "K6 FieldNotFilled" to setOf(ONE),
            "K7 Valid" to setOf(ONE),
            "K8 RangeAsString" to setOf(ONE),
            "K9 RoundAccountingValue" to setOf(ONE),
            "K10 AbsValue" to setOf(ONE),
            "K11 Length" to setOf(ONE),
            "K12 DayFromDate" to setOf(ONE),
            "K13 a value-list subject" to setOf(ONE),
            "K14 a RepetitionNotUnique key" to setOf(ONE),
            "K15 a Date construction slot" to setOf(ONE),
            "C1 a bracketed read" to setOf(ONE),
            "C2 a value-list entry" to setOf(ONE),
            "C3 the ungated AtLeastOneGroupFilled" to emptySet(),
            "C4 an aggregate" to emptySet(),
            "C5 the star on the index group" to setOf("MVK_SEMANTIC_INDEX_AND_WILDCARD"),
            "C6 the bound lower group, unstarred" to emptySet(),
            "C7 the unbound lower group, unstarred" to setOf("MVK_NO_WILDCARD"),
            "V1 Valid over a bound keyed text" to emptySet(),
            "V2 Valid over a bound keyed number" to setOf("MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM"),
        )
    }
}
