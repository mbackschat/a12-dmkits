package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A12's answer on the literal, arithmetic-shape, static-scale and raw-String cells a12-kernel-lean named as the
 * acceptance of SPEC-2026-10-26-15, -25 and -26, and the checker's agreement on each ([KERNEL-FINDINGS KF628]).
 * Every row is judged by `rule check` and by the checker; the expected column is A12's, measured 2026-10-27.
 *
 * Own domain (an online order).
 */
class CheckerArithmeticShapeAgreementTest {

    private val order = DocumentModelDraft.named("order-arithmetic-shapes").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Note") { }
            .stringField("Raw") { it.lineBreaks().noValueValidation() }
            .numberField("Qty") { n -> n.fractionDigits(0) }
            .numberField("Exp1") { n -> n.fractionDigits(0) }
            .numberField("Exp2") { n -> n.fractionDigits(0) }
            .numberField("P2") { n -> n.fractionDigits(2, 2) }
            .numberField("P4") { n -> n.fractionDigits(4, 4) }
            .group("Lines") { l -> l.repeatable(3)
                .numberField("LineQty") { n -> n.fractionDigits(0) }
                .numberField("D2") { n -> n.fractionDigits(2, 2) }
                .stringField("LineNote") { }
                .stringField("LineRaw") { it.lineBreaks().noValueValidation() } } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun draft(errorField: String, condition: String) =
        DraftRule("/Order", "ShapeProbe", "../$errorField", condition, "PROBE", "ERROR")

    private fun kernel(errorField: String, condition: String): List<String> =
        validator.check(order, draft(errorField, condition)).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.sorted()
        }

    private fun ours(errorField: String, condition: String): List<String> =
        check(ModelSource(validator.inject(order, draft(errorField, condition)))).findings
            .filter { it.justifiesRejection }.map { it.code }.sorted()

    @Test
    fun theCheckerNamesA12sClassOnEveryAcceptanceCell() {
        val observed = ROWS.mapValues { (_, row) -> kernel(row.first, row.second) to ours(row.first, row.second) }
        val expected = ROWS.mapValues { (id, _) -> A12.getValue(id) to A12.getValue(id) }
        assertEquals(expected, observed, "as (A12's classes, the checker's)")
    }

    private companion object {
        val ROWS: Map<String, Pair<String, String>> = linkedMapOf(
            "a 15-digit literal" to ("Qty" to "[Qty] == 123456789012345"),
            "a 16-digit literal" to ("Qty" to "[Qty] == 1234567890123456"),
            "a 19-digit literal" to ("Qty" to "[Qty] == 1234567890123456789"),
            "an ungrouped power chain" to ("Qty" to "[Qty] ^ [Exp1] ^ [Exp2] > 1"),
            "a grouped power base" to ("Qty" to "{[Qty] ^ [Exp1]} ^ [Exp2] > 1"),
            "a grouped literal power exponent" to ("Qty" to "[Qty] ^ {2 ^ 2} > 1"),
            "a grouped field power exponent" to ("Qty" to "[Qty] ^ {[Exp1] ^ [Exp2]} > 1"),
            "a field exponent, the control" to ("Qty" to "[Qty] ^ [Exp1] > 1"),
            "divisions in separate addends" to ("Qty" to "[Qty] / [Exp1] + [Exp2] / [Qty] > 1"),
            "two divisions in one region" to ("Qty" to "[Qty] / [Exp1] / [Exp2] > 1"),
            "a division times a division" to ("Qty" to "[Qty] / [Exp1] * [Exp2] / [Qty] > 1"),
            "a scale-2 aggregate exponent" to ("Qty" to "[Qty] ^ Sum(Lines*/D2) > 1"),
            "a scale-0 aggregate exponent" to ("Qty" to "[Qty] ^ Sum(Lines*/LineQty) > 1"),
            "a wider field equals a narrower one" to ("P4" to "[P4] == [P2]"),
            "a wider field equals a rounded field" to ("P4" to "[P4] == RoundAccountingValue(P2, 2)"),
            "a wider field equals a rounded expression" to ("P4" to "[P4] == RoundAccounting([P2] * 1, 2)"),
            "an equal scale equals a rounded expression" to ("P2" to "[P2] == RoundAccounting([P4] * 1, 2)"),
            "a wider field equals a constant product" to ("P4" to "[P4] == [P2] * 1"),
            "a wider field equals a decimal constant product" to ("P4" to "[P4] == [P2] * 1.00"),
            "a wider field equals a rounded constant product" to ("P4" to "[P4] == RoundAccounting([P2] * 1.00, 2)"),
            "a raw String under <" to ("Raw" to "FieldFilled(Raw) And [Raw] < \"x\""),
            "a String under <, the control" to ("Note" to "FieldFilled(Note) And [Note] < \"x\""),
            "a raw String under -" to ("Raw" to "FieldFilled(Raw) And {[Raw] - 1} > 0"),
            "a String under -, the control" to ("Note" to "FieldFilled(Note) And {[Note] - 1} > 0"),
            "a raw String under Sum" to ("Raw" to "FieldFilled(Raw) And Sum(Lines*/LineRaw) > 0"),
            "a String under Sum, the control" to ("Note" to "FieldFilled(Note) And Sum(Lines*/LineNote) > 0"),
        )

        /** Measured through `RuleValidator.check`, kernel 30.8.1, 2026-10-27. */
        val A12: Map<String, List<String>> = linkedMapOf(
            "a 15-digit literal" to emptyList(),
            "a 16-digit literal" to emptyList(),
            "a 19-digit literal" to emptyList(),
            "an ungrouped power chain" to listOf("MVK_TOO_MANY_POW_FOR_CALC"),
            "a grouped power base" to emptyList(),
            "a grouped literal power exponent" to emptyList(),
            "a grouped field power exponent" to listOf("MVK_INVALID_NUMBER_FOR_EXP"),
            "a field exponent, the control" to emptyList(),
            "divisions in separate addends" to emptyList(),
            "two divisions in one region" to listOf("MVK_TOO_MANY_DIV_FOR_CALC"),
            "a division times a division" to listOf("MVK_TOO_MANY_DIV_FOR_CALC"),
            "a scale-2 aggregate exponent" to listOf("MVK_INVALID_NUMBER_FOR_EXP"),
            "a scale-0 aggregate exponent" to emptyList(),
            "a wider field equals a narrower one" to listOf("MVK_INVALID_COMPARE_DEC_PLACES"),
            "a wider field equals a rounded field" to listOf("MVK_INVALID_COMPARE_DEC_PLACES"),
            "a wider field equals a rounded expression" to listOf("MVK_INVALID_COMPARE_DEC_PLACES"),
            "an equal scale equals a rounded expression" to emptyList(),
            "a wider field equals a constant product" to emptyList(),
            "a wider field equals a decimal constant product" to emptyList(),
            "a wider field equals a rounded constant product" to listOf("MVK_INVALID_COMPARE_DEC_PLACES"),
            "a raw String under <" to listOf("MVK_INVALID_RAW_TYPE"),
            "a String under <, the control" to listOf("MVK_INVALID_TYPE_FOR_COMPARISON"),
            "a raw String under -" to listOf("MVK_INVALID_RAW_TYPE"),
            "a String under -, the control" to listOf("MVK_NO_NUMBER_FOR_CALC"),
            "a raw String under Sum" to listOf("MVK_NO_NUMBER"),
            "a String under Sum, the control" to listOf("MVK_NO_NUMBER"),
        )
    }
}
