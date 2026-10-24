package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every operand family that reads a keyed operand, over a selection whose key is a FIELD of another repetition,
 * against the same selection keyed by a constant (KERNEL-FINDINGS KF605).
 *
 * Outside a `Having` body A12 binds the key by the error target's iteration: `Lines/Sku` iterates `Lines`, so
 * `Picks/X For Lines/LineNo` selects one pick per line and each family judges the pick's field exactly as it judges
 * the constant-keyed twin, while a target that does not iterate `Lines` draws `MVK_SEMANTIC_INDEX_INVALID_ITER`
 * ahead of any kind defect. Inside a body A12 asks no iteration question of the key at all: the family's class
 * answers whether the filter, the target, both or neither bind `Lines`, and a sound selection draws
 * `MVK_SEMANTIC_INDEX_NOT_ALLOWED` from the filter walk after it.
 *
 * Own domain: an order's lines and the warehouse picks that serve them, each keyed by its own number.
 */
class CheckerKeyedOperandFamilyAgreementTest {

    private val verdicts by lazy { ROWS.mapValues { (_, row) -> KeyedOrderModel.judge(row.errorField, row.condition, row.computed) } }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, verdicts.mapValues { it.value.first })
    }

    /** The checker names A12's class on every row but the [FILTER_WALK] ones, where it stays silent instead. */
    @Test
    fun theCheckerNamesA12sClassOrLeavesTheFilterWalksRowsToA12() {
        assertEquals(
            verdicts.mapValues { (id, verdict) -> if (id in FILTER_WALK) emptyList() else verdict.first },
            verdicts.mapValues { it.value.second },
        )
    }

    private class Row(val errorField: String, val condition: String, val a12: List<String>, val computed: Boolean = false)

    private companion object {
        const val NO_NUMBER = "MVK_NO_NUMBER"
        const val NOT_ALLOWED = "MVK_SEMANTIC_INDEX_NOT_ALLOWED"

        /**
         * A body whose index has an entity key, which the filter walk leaves to A12 whatever the index's state
         * (CONSISTENCY-GAPS DG155): the sound selection's `NOT_ALLOWED`, and the key-type class the index's own
         * gates raise in a body.
         */
        val FILTER_WALK = setOf("b-sound-field", "b-sound-unbound", "b-sound-filtered", "b-key-type")

        val ROWS: Map<String, Row> = LinkedHashMap<String, Row>().apply {
            fun row(id: String, errorField: String, condition: String, vararg a12: String) =
                put(id, Row(errorField, "FieldFilled($errorField) And $condition", a12.toList()))
            // Each family twice beside a target iterating `Lines`: the field key, then the constant key.
            fun family(id: String, probe: String, vararg a12: String) {
                row(id, "Lines/Sku", probe.replace("KEY", "Lines/LineNo"), *a12)
                row("$id-constant", "Lines/Sku", probe.replace("KEY", "\"a\""), *a12)
            }
            fun body(id: String, errorField: String, filter: String, probe: String, vararg a12: String) =
                row(id, errorField, "AtLeastOneFieldFilled($filter Having $probe)", *a12)

            family("value-in-fields", "NumberOfValueInFields(\"x\" In Picks/Qty For KEY, Lines/Qty) > 0", "MVK_NO_STRING_OR_ENUM")
            family("sum", "Sum(Lines/Qty, Picks/Ref For KEY) > 0", NO_NUMBER)
            family("first-filled", "FirstFilledValue(Lines/Sku, Picks/Qty For KEY) == \"x\"", "MVK_VARYING_TYPES_NOT_ALLOWED")
            family("field-operand", "FieldFilled(Picks For KEY)", "MVK_NO_GROUPS_ALLOWED")
            family("rounding", "RoundUpValue(Picks/Ref For KEY, 0) > 1", NO_NUMBER)
            family("date-argument", "DifferenceInDays(Picks/Qty For KEY, Lines/ShipOn) > 0", "MVK_NO_DATE")
            family("date-arithmetic", "AddDays(Picks/Ref For KEY, 1) > Lines/ShipOn", "MVK_NO_DATE")
            family("scalar-list", "FieldValueIncludedInValueList(Picks/Window For KEY, \"a\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED")
            family("value-list", "AtLeastOneFieldValueIncludedInValueList(Picks/Window For KEY In \"a\")",
                "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED")
            family("group-list", "AllGroupsFilled(Picks For KEY, Picks For KEY)", "MVK_DUPLICATE_PARAM1")
            family("comparison-read", "[Picks/Window For KEY] == \"a\"", "MVK_INVALID_COMPARE_TO_DATE_RANGE")
            family("comparison-order", "[Picks/Ref For KEY] > 5", "MVK_INVALID_TYPE_FOR_COMPARISON")
            // Two families that read a plain path, because a keyed operand meets another gate before either.
            family("repetition-key", "RepetitionNotUnique(Picks/Ref For KEY)", NOT_ALLOWED)
            family("group-wildcard", "GroupFilled(Picks* For KEY)", "MVK_SEMANTIC_INDEX_AND_WILDCARD")
            // The operation walk, from a computation whose target iterates `Lines`.
            put("computed", Row("Lines/Total", "[Picks/Ref For Lines/LineNo] + 1", listOf("MVK_NO_STRING_OR_NUMBER_FOR_ADD"),
                computed = true))

            // A target that does not iterate `Lines`: the key's read iterates, which answers ahead of the kind.
            val sum = "Sum(Total, Picks/Ref For Lines/LineNo) > 0"
            row("o-unbound-kind", "Total", sum, "MVK_SEMANTIC_INDEX_INVALID_ITER")
            row("o-unbound-sound", "Total", "Sum(Total, Picks/Qty For Lines/LineNo) > 0", "MVK_SEMANTIC_INDEX_INVALID_ITER")

            // Inside a body, the four ways the filter and the target can bind `Lines`, and the constant-key control.
            body("b-filter-binds", "Total", "Lines*/Qty", sum, NO_NUMBER)
            body("b-target-binds", "Lines/Sku", "Notes*/Text", sum, NO_NUMBER)
            body("b-neither", "Total", "Notes*/Text", sum, NO_NUMBER)
            body("b-both", "Lines/Sku", "Lines*/Qty", sum, NO_NUMBER)
            body("b-constant", "Total", "Notes*/Text", "Sum(Total, Picks/Ref For \"a\") > 0", NO_NUMBER)
            // Each family once more, in a body nothing binds.
            body("b-date-arithmetic", "Total", "Notes*/Text", "AddDays(Picks/Ref For Lines/LineNo, 1) > Lines/ShipOn",
                "MVK_NO_DATE")
            body("b-scalar-list", "Total", "Notes*/Text",
                "FieldValueIncludedInValueList(Picks/Window For Lines/LineNo, \"a\")", "MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED")
            body("b-value-in-fields", "Total", "Notes*/Text",
                "NumberOfValueInFields(\"x\" In Picks/Qty For Lines/LineNo, Total) > 0", "MVK_NO_STRING_OR_ENUM")
            body("b-rounding", "Total", "Notes*/Text", "RoundUpValue(Picks/Ref For Lines/LineNo, 0) > 1", NO_NUMBER)
            body("b-comparison-order", "Total", "Notes*/Text", "[Picks/Ref For Lines/LineNo] > 5",
                "MVK_INVALID_TYPE_FOR_COMPARISON")
            body("b-field-operand", "Total", "Notes*/Text", "FieldFilled(Picks For Lines/LineNo)", "MVK_NO_GROUPS_ALLOWED")
            // A sound selection, and one whose key the index refuses, which no operand gate judges.
            body("b-sound-field", "Lines/Sku", "Lines*/Qty", "FieldFilled(Picks/Ref For Lines/LineNo)", NOT_ALLOWED)
            body("b-sound-unbound", "Total", "Notes*/Text", "Sum(Total, Picks/Qty For Lines/LineNo) > 0", NOT_ALLOWED)
            body("b-sound-filtered", "Total", "Lines*/Qty", "Sum(Total, Picks/Qty For Lines/LineNo) > 0", NOT_ALLOWED)
            body("b-key-type", "Total", "Notes*/Text", "Sum(Total, Picks/Qty For Lines/Qty) > 0", "MVK_INDEX_TYPE_INCONSISTENT")
        }
    }
}
