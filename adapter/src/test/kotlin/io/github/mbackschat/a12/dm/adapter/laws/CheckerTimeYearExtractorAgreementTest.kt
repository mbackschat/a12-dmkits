package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Whether the checker admits a year extracted from a TIME exactly where A12 does (a12-kernel-lean
 * SPEC-2026-10-08-01, [KF261]): a clock field, a built time and a time recovered from a date-time each have their
 * year admitted under a declared Base Year and refused without one. The same draft is built twice, differing in
 * the Base Year alone.
 *
 * Own domain (an insurance cover term).
 */
class CheckerTimeYearExtractorAgreementTest {

    private fun term(baseYear: Int?): String = DocumentModelDraft.named("cover-term").locale("en_US")
        .let { if (baseYear == null) it else it.baseYear(baseYear) }
        .rootGroup("Term") { t -> t
            .stringField("Holder") { }
            .timeField("RenewalClock") { c -> c.format("HH:mm:ss") }
            .numberField("RenewalHour") { n -> n.fractionDigits(0).min("0").max("23") }
            .dateField("StartOn") { d -> d.format("yyyy-MM-dd") } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aTimesYearIsAdmittedExactlyUnderABaseYear() {
        val shapes = linkedMapOf(
            "a clock field" to "YearFromDate(RenewalClock) == 1970",
            "a built time" to "YearFromDate(Time(RenewalHour)) == 1970",
            "a time recovered from a date-time" to
                "YearFromDate(TimeFromDateTime(DateTime(StartOn, Time(RenewalHour)))) == 1970",
            "an ordinary date — armed control" to "YearFromDate(StartOn) == 2024",
        )
        val wrong = mutableListOf<String>()
        for ((label, condition) in shapes) {
            for ((axis, model) in listOf("no base year" to term(null), "base year 2024" to term(2024))) {
                val draft = DraftRule("/Term", "ProbeRule", "../Holder", "FieldFilled(Holder) And $condition",
                    "PROBE", "ERROR")
                val kernel = validator.check(model, draft).let { r ->
                    if (r.ok()) emptySet() else r.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
                }
                val ours = check(ModelSource(validator.inject(model, draft))).findings
                    .filter { it.justifiesRejection }.map { it.code }.toSet()
                val expected = if (axis == "no base year" && label != "an ordinary date — armed control") {
                    setOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
                } else {
                    emptySet()
                }
                val oursExpected = if (expected.isNotEmpty() && label in UNREAD_BY_THE_CHECKER) emptySet() else expected
                if (kernel != expected || ours != oursExpected) {
                    wrong += "$label, $axis: expected (A12, ours) ${expected to oursExpected}, observed ${kernel to ours}"
                }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private companion object {
        /** The refusals the checker does not yet name, because it has no extractor format gate (CONSISTENCY-GAPS DG126). */
        val UNREAD_BY_THE_CHECKER = setOf("a clock field", "a built time", "a time recovered from a date-time")
    }
}
