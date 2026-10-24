package io.github.mbackschat.a12.dm.adapter.laws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A list family's kind gates over an operand that selects one row by its semantic index, with a constant or a
 * field key (KERNEL-FINDINGS KF605).
 *
 * Own domain: an order's lines and the warehouse picks that serve them, each keyed by its own number.
 */
class CheckerKeyedListOperandAgreementTest {

    private fun judge(row: Row) = KeyedOrderModel.judge(row.errorField, row.condition, row.computed)

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { judge(it.value) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private class Row(val errorField: String, val condition: String, val a12: List<String>, val computed: Boolean = false)

    private companion object {
        const val VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED"
        const val KIND = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED"
        const val LOWEST = "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"

        val ROWS: Map<String, Row> = LinkedHashMap<String, Row>().apply {
            fun row(id: String, errorField: String, condition: String, vararg a12: String) =
                put(id, Row(errorField, condition, a12.toList()))
            fun computation(id: String, computed: String, operation: String, vararg a12: String) =
                put(id, Row(computed, operation, a12.toList(), computed = true))

            row("k01", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks/Ref For Lines/LineNo)")
            row("k02", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks/Qty For Lines/LineNo)", VARYING)
            row("k03", "Lines/Qty", "FieldValuesNotUnique(Lines/Qty, Picks/Ref For Lines/LineNo)", VARYING)
            row("k04", "Lines/Qty", "FieldValuesNotUnique(Lines/Qty, Picks/Qty For Lines/LineNo)")
            row("k05", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks/Qty For \"a\")", VARYING)
            row("k06", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks/Window For \"a\")", KIND)
            row("k07", "Lines/ShipOn", "FieldValuesNotUnique(Lines/ShipOn, Picks/ShipOn For \"a\")", KIND)
            row("k08", "Lines/ShipOn", "FieldValuesNotUnique(Lines/ShipOn, Picks/ShipIso For \"a\")")
            row("k09", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks/RawNote For \"a\")", "MVK_INVALID_RAW_TYPE")
            // The selected group expands to its fields, the date range among them.
            row("k10", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks For \"a\")", KIND)
            row("k11", "Lines/Sku", "FieldValuesNotUnique(Lines/Sku, Picks/Qty For Lines/Qty)", "MVK_INDEX_TYPE_INCONSISTENT")
            // The same keys from a rule whose error field does not iterate `Lines`: the key's read iterates.
            row("i1", "Total", "FieldFilled(Total) And FieldFilled(Picks/Qty For Lines/LineNo)", "MVK_SEMANTIC_INDEX_INVALID_ITER")
            row("i2", "Total", "FieldFilled(Total) And FieldFilled(Picks/Qty For Lines/Qty)", "MVK_SEMANTIC_INDEX_INVALID_ITER")
            row("i3", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Picks/Qty For Lines/Qty)", "MVK_INDEX_TYPE_INCONSISTENT")
            // The index chain's other gates behind a key the error field's iteration reads.
            row("c1", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Lines/Qty For Lines/LineNo)",
                "MVK_SEMANTIC_INDEX_CONTAINED_IN_INDEX")
            row("c2", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Picks/Qty For Lines/RawRef)", "MVK_INVALID_RAW_TYPE")
            row("c3", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Notes/Text For Lines/LineNo)", "MVK_NO_INDEX_FIELD")
            row("c4", "Lines/Sku", "FieldFilled(Lines/Sku) And AtLeastOneFieldFilled(Picks*/Qty For Lines/LineNo)",
                "MVK_SEMANTIC_INDEX_AND_WILDCARD")
            // A keyed operand read where an earlier gate of the list answers: arity, then the duplicate.
            row("p1", "Lines/Sku", "FieldValuesNotUnique(Picks/Window For Lines/LineNo)", "MVK_PARAMSIZE_INVALIDN")
            row("p1c", "Picks/Ref", "FieldValuesNotUnique(Picks/Window)", "MVK_PARAMSIZE_INVALIDN")
            row("p2", "Lines/Sku", "FieldValuesNotUnique(Picks/Qty For Lines/LineNo, Picks/Qty For Lines/LineNo, " +
                "Picks/Ref For \"a\")", "MVK_DUPLICATE_PARAM1")
            row("p3", "Lines/Sku", "FieldValuesNotUnique(Picks/Qty For \"a\", Picks/Qty For \"a\", Picks/Ref For \"a\")",
                "MVK_DUPLICATE_PARAM1")
            row("p4", "Lines/Sku", "AtLeastOneFieldFilled(Picks/Qty For Lines/LineNo, Picks/Qty For Lines/LineNo)",
                "MVK_DUPLICATE_PARAM1")
            // The key's reach beyond its own group's error field: a nested error field, a filtered key, a parallel key.
            row("n1", "Lines/Boxes/Tag", "FieldFilled(Lines/Boxes/Tag) And FieldFilled(Picks/Qty For Lines/Qty)",
                "MVK_INDEX_TYPE_INCONSISTENT")
            row("n2", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Picks/Qty For Lines/Qty Having [Lines/Qty] > 0)",
                "MVK_WILDCARDS_REQUIRED")
            row("n3", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Notes/Text For Picks/Ref)",
                "MVK_ERROR_FIELD_INVALID_INDEX")
            row("n4", "Lines/Sku", "FieldFilled(Lines/Sku) And FieldFilled(Lines/Qty For Picks/Qty)",
                "MVK_ERROR_FIELD_INVALID_INDEX")
            computation("n5", "Lines/Total", "[Picks/Qty For Lines/Qty]", "MVK_INDEX_TYPE_INCONSISTENT")
            computation("n6", "Lines/Total", "[Picks/Qty For Lines/LineNo]")
            row("L1", "Total", "FieldFilled(Total) And Length(Picks/RawNote For \"a\") > 1", "MVK_INVALID_LENGTH_OF_RAW_TYPE")
            row("L2", "Lines/Sku", "FieldFilled(Lines/Sku) And Length(Picks/RawNote For Lines/LineNo) > 1",
                "MVK_INVALID_LENGTH_OF_RAW_TYPE")
            row("S1", "Total", "FieldFilled(Total) And SumOfProducts(Picks/Qty For \"a\", Picks/Price For \"a\") > 0", LOWEST)
            row("S2", "Lines/Sku", "FieldFilled(Lines/Sku) And " +
                "SumOfProducts(Picks/Qty For Lines/LineNo, Picks/Price For Lines/LineNo) > 0", LOWEST)
            row("S3", "Total", "FieldFilled(Total) And SumOfProducts(Picks/Qty For \"a\", Picks*/Price) > 0", LOWEST)
            row("S4", "Total", "FieldFilled(Total) And SumOfProducts(Picks*/Qty, Picks/Price For \"a\") > 0", LOWEST)
            row("S5", "Total", "FieldFilled(Total) And SumOfProducts(Picks*/Qty, Picks*/Price) > 0")
            row("S6", "Total", "FieldFilled(Total) And SumOfProducts(Picks/Qty For \"a\", Lines*/Qty) > 0",
                "MVK_DIFFERENT_GROUPS")
        }
    }
}
