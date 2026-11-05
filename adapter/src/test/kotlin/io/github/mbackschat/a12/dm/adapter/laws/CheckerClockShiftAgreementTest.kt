package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `AddMonths` and `AddYears` refuse a timestamp outright (KERNEL-FINDINGS KF392's fourth gate), and on kernel
 * 31.1.1 the clock constant `Now` is one: both draw `MVK_WRONG_DATE_FORMAT_FOR_OP` over it, exactly as over a
 * DateTime field, while `AddDays` over the same two operands and the month and year shifts over a Date are
 * admitted. So a reading that treats `Now` as "not a declared field, nothing to judge" under-reports exactly
 * these two rows.
 *
 * Each row is pinned for A12 and compared against the portable checker. Requested by a12-kernel-lean as part of
 * SPEC-2026-11-05-01 (rows `am-now`, `ay-now` and their controls); own domain (a subscription's renewal dates).
 */
class CheckerClockShiftAgreementTest {

    private val model = DocumentModelDraft.named("subscription-renewal").locale("en_US")
        .rootGroup("Subscription") { s -> s
            .dateField("Renewal") { }
            .dateTimeField("SignedAt") { } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun monthAndYearShiftsRefuseTheClockAsTheyRefuseAStamp() {
        val stamp = setOf("MVK_WRONG_DATE_FORMAT_FOR_OP")
        val rows = linkedMapOf(
            "AddMonths over Now" to ("AddMonths(Now, 1)" to stamp),
            "AddYears over Now" to ("AddYears(Now, 1)" to stamp),
            "AddMonths over a DateTime field" to ("AddMonths(SignedAt, 1)" to stamp),
            "AddDays over Now — the control" to ("AddDays(Now, 1)" to emptySet()),
            "AddMonths over a Date field — the control" to ("AddMonths(Renewal, 1)" to emptySet()),
        )
        val observed = rows.mapValues { (_, row) -> verdicts("DifferenceInDays(${row.first}, Renewal) > 0") }
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second }, observed, "(A12, ours)")
    }

    private fun verdicts(condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Subscription", "RenewalRule", "../Renewal", condition, "RENEWAL", "ERROR")
        val result = validator.check(model, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = rejectingCodes(validator.inject(model, draft)).toSet()
        return a12 to ours
    }
}
