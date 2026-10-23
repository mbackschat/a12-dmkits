package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A list family's kind gates over an operand that selects one row by its semantic index, with a constant or a
 * field key (KERNEL-FINDINGS KF605).
 *
 * Own domain: an order's lines and the warehouse picks that serve them, each keyed by its own number.
 */
class CheckerKeyedListOperandAgreementTest {

    private val order = DocumentModelDraft.named("order-keyed-gates").locale("en_US")
        .rootGroup("Order") { o -> o
            .numberField("Total") { n -> n.fractionDigits(0) }
            .group("Lines") { l -> l.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .stringField("Sku") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") } }
            .group("Picks") { p -> p.repeatable(10).indexField("Ref")
                .stringField("Ref") { }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .numberField("Price") { n -> n.fractionDigits(2) }
                .dateField("ShipOn") { d -> d.format("dd.MM.yyyy") }
                .dateField("ShipIso") { d -> d.format("yyyy-MM-dd") }
                .dateRangeField("Window") { it.format("yyyy-MM-dd").separator("/") }
                .stringField("RawNote") { s -> s.noValueValidation().lineBreaks() } } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(row: Row): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Order", "KeyedProbe", "../${row.errorField}", row.condition, "PROBE", "ERROR")
        val result = validator.check(order, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.a12 }, ROWS.mapValues { judge(it.value).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { judge(it.value) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private class Row(val errorField: String, val condition: String, val a12: List<String>)

    private companion object {
        const val VARYING = "MVK_VARYING_TYPES_NOT_ALLOWED"
        const val KIND = "MVK_ONLY_STRING_ENUM_NUMBER_DATE_ALLOWED"
        const val LOWEST = "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"

        val ROWS: Map<String, Row> = LinkedHashMap<String, Row>().apply {
            fun row(id: String, errorField: String, condition: String, vararg a12: String) =
                put(id, Row(errorField, condition, a12.toList()))

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
