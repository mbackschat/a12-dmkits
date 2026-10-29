package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which `FirstFilledValue` Date computations A12 admits into a Date target of another spelling (KERNEL-FINDINGS
 * KF550, a12-kernel-lean SPEC-2026-10-13-01), with the portable checker required to name the same class. The
 * source list keeps its exact-profile gate, while the target is judged like a temporal comparison: a crossed ISO
 * or dotted spelling and a target that omits components are admitted, a yearless source needs a Base Year, and a
 * DateTime source is refused. The runtime half is `FirstFilledDateTargetRenderingDiffTest`.
 *
 * Own domain (an insurance claim's reported and confirmed dates), declared once with a Base Year and once without.
 */
class CheckerFirstFilledDateTargetAgreementTest {

    private fun claim(baseYear: Int?) = DocumentModelDraft.named("claim-date-targets").locale("en_US")
        .let { if (baseYear == null) it else it.baseYear(baseYear) }
        .rootGroup("Claim") { c -> c
            .dateField("ReportedIso") { it.format("yyyy-MM-dd") }.dateField("ConfirmedIso") { it.format("yyyy-MM-dd") }
            .dateField("ReportedDot") { it.format("dd.MM.yyyy") }.dateField("ConfirmedDot") { it.format("dd.MM.yyyy") }
            .dateField("ReportedMonth") { it.format("yyyy-MM") }.dateField("ConfirmedMonth") { it.format("yyyy-MM") }
            .dateField("ReportedYear") { it.format("yyyy") }.dateField("ConfirmedYear") { it.format("yyyy") }
            .dateField("ReportedDay") { it.format("MM-dd") }.dateField("ConfirmedDay") { it.format("MM-dd") }
            .dateTimeField("ReportedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateTimeField("ConfirmedAt") { it.format("yyyy-MM-dd'T'HH:mm:ss") }
            .dateField("IsoTarget") { it.format("yyyy-MM-dd") }.dateField("DotTarget") { it.format("dd.MM.yyyy") }
            .dateField("YearTarget") { it.format("yyyy") }.dateField("DayTarget") { it.format("MM-dd") } }
        .build().toJson()

    private val withBaseYear = claim(2024)
    private val withoutBaseYear = claim(null)
    private val validator = RuleValidator()

    @Test
    fun theTargetIsJudgedLikeATemporalComparison() {
        assertAgrees(withBaseYear, linkedMapOf(
            "dotted into ISO" to (("IsoTarget" to "FirstFilledValue(ReportedDot, ConfirmedDot)") to ADMITTED),
            "ISO into dotted" to (("DotTarget" to "FirstFilledValue(ReportedIso, ConfirmedIso)") to ADMITTED),
            "a full Date into a year" to (("YearTarget" to "FirstFilledValue(ReportedIso, ConfirmedIso)") to ADMITTED),
            "a year-month into a year" to (("YearTarget" to "FirstFilledValue(ReportedMonth, ConfirmedMonth)") to ADMITTED),
            "a year into a full Date" to (("IsoTarget" to "FirstFilledValue(ReportedYear, ConfirmedYear)") to ADMITTED),
            "yearless into ISO with a Base Year" to (("IsoTarget" to "FirstFilledValue(ReportedDay, ConfirmedDay)") to ADMITTED),
            "a DateTime into a Date" to (("IsoTarget" to "FirstFilledValue(ReportedAt, ConfirmedAt)") to DATE_COMPARE),
            "crossed source spellings" to (("IsoTarget" to "FirstFilledValue(ReportedIso, ConfirmedDot)") to VARYING),
        ))
    }

    /** Without a Base Year, a yearless source compares only with a yearless target. */
    @Test
    fun aYearlessSourceNeedsABaseYearForADatedTarget() {
        assertAgrees(withoutBaseYear, linkedMapOf(
            "yearless into ISO" to (("IsoTarget" to "FirstFilledValue(ReportedDay, ConfirmedDay)") to DATE_COMPARE),
            "yearless into yearless" to (("DayTarget" to "FirstFilledValue(ReportedDay, ConfirmedDay)") to ADMITTED),
            "dotted into ISO" to (("IsoTarget" to "FirstFilledValue(ReportedDot, ConfirmedDot)") to ADMITTED),
            "a direct yearless copy into ISO" to (("IsoTarget" to "[ReportedDay]") to DATE_COMPARE),
            "a direct full-Date copy into yearless" to (("DayTarget" to "[ReportedIso]") to DATE_COMPARE),
        ))
        assertAgrees(withBaseYear, linkedMapOf(
            "a direct yearless copy into ISO with a Base Year" to (("IsoTarget" to "[ReportedDay]") to ADMITTED),
            "a direct full-Date copy into yearless with a Base Year" to (("DayTarget" to "[ReportedIso]") to ADMITTED),
        ))
    }

    private fun assertAgrees(model: String, rows: Map<String, Pair<Pair<String, String>, Set<String>>>) {
        val wrong = rows.mapNotNull { (row, written) ->
            val (computation, a12Class) = written
            val (a12, ours) = compare(model, computation.first, computation.second)
            if (a12 == a12Class && ours == a12Class) null
            else "$row: expected $a12Class, A12 $a12, ours $ours"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(model: String, target: String, operation: String): Pair<Set<String>, Set<String>> {
        val draft = DraftComputation("/Claim", "Select$target", "../$target", null,
            listOf(DraftComputation.Alternative(null, operation)))
        val a12 = validator.check(model, draft).let { result ->
            if (result.ok()) emptySet() else result.diagnostics().map { it.codeOrSummary() }.toSet()
        }
        val ours = rejectingCodes(validator.inject(model, draft)).toSet()
        return a12 to ours
    }

    private companion object {
        val ADMITTED = emptySet<String>()
        val DATE_COMPARE = setOf("MVK_INVALID_COMPARE_TO_DATE")
        val VARYING = setOf("MVK_VARYING_TYPES_NOT_ALLOWED")
    }
}
