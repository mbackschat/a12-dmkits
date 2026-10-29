package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which slot of a constructed Date satisfies a validity condition's entity demand when the slots read a SELECTED
 * row (a12-kernel-lean SPEC-2026-10-06-05). A12 asks each slot whether it IS an entity, without descending into an
 * extractor ([KF402]); a selection is an entity, and an extractor over one, or over a shift of one, is not. The
 * slot gates read the same distinction, so an extractor over a selection meets them as an extractor ([KF373]).
 *
 * Own domain (an insurance policy whose events are keyed by code).
 */
class CheckerSelectedDateValidityAgreementTest {

    private val policy = DocumentModelDraft.named("policy-selected-validity").locale("en_US")
        .rootGroup("Policy") { p -> p
            .stringField("Key") { }
            .dateField("Noted") { it.format("yyyy-MM-dd").datePrecision(DatePrecision.YEAR_OPTIONAL) }
            .group("Events") { g -> g.repeatable(3).indexField("Code")
                .stringField("Code") { }
                .numberField("Day") { n -> n.fractionDigits(0).min("0").max("31") }
                .numberField("Month") { n -> n.fractionDigits(0).min("0").max("12") }
                .numberField("Year") { n -> n.fractionDigits(0).min("0").max("9999") }
                .dateField("Occurred") { it.format("yyyy-MM-dd") }
                .timeField("At") { it.format("HH:mm:ss") } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aSelectedSlotIsAnEntityAndAnExtractorOverOneIsNot() {
        val occurred = "Events/Occurred For Key"
        val shifted = "AddDays($occurred, 1)"
        assertAgreement(linkedMapOf(
            "a selected immediate month" to
                ("Valid(Date(DayFromDate($occurred), Events/Month For Key, YearFromDate($occurred)))" to ADMITTED),
            "a selected immediate month, as Invalid" to
                ("Invalid(Date(DayFromDate($occurred), Events/Month For Key, YearFromDate($occurred)))" to ADMITTED),
            "three selected numbers" to
                ("Valid(Date(Events/Day For Key, Events/Month For Key, Events/Year For Key))" to ADMITTED),
            "three extractors over a selection" to
                ("Valid(Date(DayFromDate($occurred), MonthFromDate($occurred), YearFromDate($occurred)))" to CONSTANTS),
            "three extractors over a shifted selection" to
                ("Valid(Date(DayFromDate($shifted), MonthFromDate($shifted), YearFromDate($shifted)))" to CONSTANTS),
            "three extractors over a shifted selection, as Invalid" to
                ("Invalid(Date(DayFromDate($shifted), MonthFromDate($shifted), YearFromDate($shifted)))" to CONSTANTS),
            "an extractor over a shifted partial date beside constants" to
                ("Valid(Date(DayFromDate(AddDays(ValueAsDate(Noted, FirstDay), 1)), \"2\", \"2024\"))" to CONSTANTS),
            "constants alone" to ("Valid(Date(\"1\", \"2\", \"2024\"))" to CONSTANTS),
        ))
    }

    /**
     * The same misreading, met by the slot walk instead: an extract operator over a selection is still an extract
     * operator, so the slot gates judge it as one.
     */
    @Test
    fun anExtractorOverASelectionMeetsTheSlotGates() {
        val occurred = "Events/Occurred For Key"
        assertAgreement(linkedMapOf(
            "the right extractor in each slot — control" to
                ("Date(DayFromDate($occurred), MonthFromDate($occurred), YearFromDate($occurred)) == \"01.02.2024\"" to ADMITTED),
            "a month extractor in the day slot" to
                ("Date(MonthFromDate($occurred), \"2\", \"2024\") == \"01.02.2024\"" to WRONG_SLOT),
            "a quarter extractor in the month slot" to
                ("Date(\"1\", QuarterFromDate($occurred), \"2024\") == \"01.02.2024\"" to NOT_ALLOWED),
            "a minute extractor in a time's hour slot" to
                ("Time(MinutesFromTime(Events/At For Key), \"0\") == \"10:00:00\"" to WRONG_SLOT),
        ))
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, probe) ->
            val (condition, a12) = probe
            val draft = DraftRule("/Policy", "ProbeRule", "../Key", "FieldFilled(Key) And $condition", "PROBE", "ERROR")
            val kernel = validator.check(policy, draft).let { r ->
                if (r.ok()) emptySet() else r.diagnostics().map { it.codeOrSummary() }.toSet()
            }
            val ours = rejectingCodes(validator.inject(policy, draft)).toSet()
            val observed = kernel to ours
            if (observed == (a12 to a12)) null else "$row: expected (A12, ours) ${a12 to a12}, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val CONSTANTS = setOf("MVK_ONLY_CONSTANTS_USED")
        val WRONG_SLOT = setOf("MVK_INVALID_DATE_OR_TIME_EXTRACT_OP")
        val NOT_ALLOWED = setOf("MVK_DATE_OR_TIME_EXTRACT_OP_NOT_ALLOWED")
    }
}
