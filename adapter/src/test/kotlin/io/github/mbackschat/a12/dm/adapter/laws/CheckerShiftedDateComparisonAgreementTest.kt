package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A shifted date compared with a constant: each `Add*` operator over a date in either declared spelling, or a
 * stamp, against a text, a date and a number constant (KERNEL-FINDINGS KF606).
 *
 * Own domain: an order item's ship date, in two spellings, and its dispatch stamp.
 */
class CheckerShiftedDateComparisonAgreementTest {

    private val shop = DocumentModelDraft.named("shop-shifted-dates").locale("en_US")
        .rootGroup("Customer") { c -> c
            .group("Items") { i -> i.repeatable(20)
                .numberField("Count") { n -> n.fractionDigits(0) }
                .dateField("ShipOn") { d -> d.format("yyyy-MM-dd") }
                .dateField("ShipDe") { d -> d.format("dd.MM.yyyy") }
                .dateTimeField("Stamp") { t -> t.format("yyyy-MM-dd'T'HH:mm:ss") } } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun judge(condition: String): Pair<List<String>, List<String>> {
        val draft = DraftRule("/Customer", "ShiftProbe", "../Items/Count", condition, "PROBE", "ERROR")
        val result = validator.check(shop, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = check(ModelSource(validator.inject(shop, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }
        return a12.sorted() to ours.sorted()
    }

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS, ROWS.mapValues { judge(it.key).first })
    }

    @Test
    fun theCheckerNamesA12sClassOnEveryRow() {
        val verdicts = ROWS.mapValues { judge(it.key) }
        assertEquals(verdicts.mapValues { it.value.first }, verdicts.mapValues { it.value.second })
    }

    private companion object {
        const val ORDERING = "MVK_INVALID_TYPE_FOR_COMPARISON"
        const val DATE = "MVK_INVALID_COMPARE_TO_DATE"

        /** Condition → A12's classes. */
        val ROWS: Map<String, List<String>> = LinkedHashMap<String, List<String>>().apply {
            for (op in listOf("AddDays", "AddMonths", "AddYears")) for (field in listOf("ShipOn", "ShipDe")) {
                val shifted = "$op(Items/$field, 1)"
                put("FieldFilled(Items/Count) And $shifted > \"2020-01-01\"", listOf(ORDERING))
                put("FieldFilled(Items/Count) And $shifted == \"2020-01-01\"", listOf(DATE))
                put("FieldFilled(Items/Count) And $shifted > \"01.01.2020\"", listOf())
                put("FieldFilled(Items/Count) And $shifted == \"01.01.2020\"", listOf())
                put("FieldFilled(Items/Count) And $shifted > 5", listOf(DATE))
                put("FieldFilled(Items/Count) And $shifted == 5", listOf(DATE))
            }
            for (op in listOf("AddDays", "AddHours", "AddMinutes", "AddSeconds")) {
                val shifted = "$op(Items/Stamp, 1)"
                put("FieldFilled(Items/Count) And $shifted > \"2020-01-01\"", listOf(ORDERING))
                put("FieldFilled(Items/Count) And $shifted > \"01.01.2020T10:00:00\"", listOf())
                put("FieldFilled(Items/Count) And $shifted > \"01.01.2020\"", listOf())
                put("FieldFilled(Items/Count) And $shifted == \"01.01.2020\"", listOf(DATE))
                put("FieldFilled(Items/Count) And $shifted > 5", listOf(DATE))
            }
            put("FieldFilled(Items/Count) And AddDays(Items/ShipOn, 1) > [Items/ShipDe]", listOf())
            put("FieldFilled(Items/Count) And AtLeastOneFieldFilled(Items*/Count Having [Items/Count] > 0 And " +
                "AddDays(\$Items/ShipOn, 1) > \"2020-01-01\")", listOf(ORDERING))
        }
    }
}
