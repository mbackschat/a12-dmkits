package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A bracketed field read where `Length` or a date difference names an entity: A12 refuses it as a token, never
 * reading it as the field's value, while the bare path beside it is admitted (KERNEL-FINDINGS KF600,
 * a12-kernel-lean SPEC-2026-10-22-01). A12 is pinned on every row and the checker must name the same classes.
 *
 * Own domain: an order with a note, a quantity and two dates.
 */
class CheckerBracketedEntitySlotAgreementTest {

    private val order = DocumentModelDraft.named("order-slots").locale("en_US")
        .rootGroup("Order") { o -> o
            .stringField("Note") { }
            .numberField("Qty") { n -> n.fractionDigits(0) }
            .dateField("OrderedOn") { d -> d.format("yyyy-MM-dd") }
            .dateField("ShippedOn") { d -> d.format("yyyy-MM-dd") } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(errorField: String, condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Order", "SlotProbe", "../$errorField", condition, "PROBE", "ERROR")
        val a12 = validator.check(order, draft).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.code().orElse("(no code)") }
        }
        val ours = check(ModelSource(validator.inject(order, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.third }, ROWS.mapValues { (_, row) -> judge(row.first, row.second).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { (_, row) -> judge(row.first, row.second) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        const val TOKEN = "MVK_UNEXPECTED_TOKEN"
        const val DATES = "AllFieldsFilled(OrderedOn, ShippedOn) And "

        fun row(errorField: String, condition: String, vararg a12: String) = Triple(errorField, condition, a12.toList())

        val ROWS: Map<String, Triple<String, String, List<String>>> = linkedMapOf(
            "Length of a bare string field" to row("Note", "FieldFilled(Note) And Length(/Order/Note) > 5"),
            "Length of a bracketed string field" to row("Note", "FieldFilled(Note) And Length([/Order/Note]) > 5", TOKEN),
            "Length of a bracketed number field" to row("Qty", "FieldFilled(Qty) And Length([/Order/Qty]) > 5", TOKEN),
            "Length of a bracketed relative field" to row("Note", "FieldFilled(Note) And Length([Note]) > 5", TOKEN),
            "DifferenceInDays of two bare fields" to row("OrderedOn", DATES + "DifferenceInDays(OrderedOn, ShippedOn) > 1"),
            "DifferenceInDays, first slot bracketed" to row("OrderedOn",
                DATES + "DifferenceInDays([/Order/OrderedOn], ShippedOn) > 1", TOKEN),
            "DifferenceInDays, second slot bracketed" to row("OrderedOn",
                DATES + "DifferenceInDays(OrderedOn, [/Order/ShippedOn]) > 1", TOKEN),
        )
    }
}
