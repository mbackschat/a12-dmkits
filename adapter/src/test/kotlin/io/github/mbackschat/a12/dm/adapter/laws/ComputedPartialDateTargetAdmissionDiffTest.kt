package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Computation
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Terminal authoring correction for SPEC-2026-07-27-01. Kernel 30.8.1 rejects every computation whose target
 * has a partial DatePrecision: computation validation generates `[target] != operation`, and that target read is
 * rejected as `MVK_INVALID_DATE_TYPE`. The only supported suppression is the decimal-place warning, so
 * `MVK_INVALID_DATE_TYPE` cannot be bypassed. A FULL target is the positive control.
 */
class ComputedPartialDateTargetAdmissionDiffTest {

    private val base = DocumentModelDraft.named("date-target-admission").locale("en_US")
        .rootGroup("Calendar") { group ->
            group.dateField("Source") { it.format("dd.MM.yyyy") }
                .dateField("Full") { it.format("dd.MM.yyyy").datePrecision(DatePrecision.FULL) }
                .dateField("DayOptional") { it.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL) }
                .dateField("MonthOptional") { it.format("dd.MM.yyyy").datePrecision(DatePrecision.MONTH_OPTIONAL) }
                .dateField("YearOptional") { it.format("dd.MM.yyyy").datePrecision(DatePrecision.YEAR_OPTIONAL) }
        }
        .build().toJson()
    private val model = BoundModel.ofJson(base)
    private val validator = RuleValidator()

    @Test
    fun fullDateTargetIsAdmitted() {
        val result = validator.check(base, computation(FULL).draft())
        assertTrue(result.ok()) { "FULL target must remain the admitted control: ${result.diagnostics()}" }
    }

    @Test
    fun everyPartialDateTargetIsRejected() {
        for (target in PARTIAL_TARGETS) {
            val result = validator.check(base, computation(target).draft())
            assertFalse(result.ok(), "$target must be rejected")
            assertTrue(result.diagnostics().any { it.code().orElse("") == "MVK_INVALID_DATE_TYPE" }) {
                "$target must carry MVK_INVALID_DATE_TYPE: ${result.diagnostics()}"
            }
        }
    }

    @Test
    fun unsupportedSuppressionCannotDefeatTheGate() {
        val draft = computation(DAY_OPTIONAL).draft()
        val suppressed = DraftComputation(
            draft.groupPath(),
            draft.name(),
            draft.computedFieldRelPath(),
            draft.commonPrecondition(),
            draft.alternatives(),
            listOf("MVK_INVALID_DATE_TYPE"),
        )
        val result = validator.check(base, suppressed)
        assertFalse(result.ok())
        assertTrue(result.diagnostics().any { it.code().orElse("") == "MVK_INVALID_SUPPRESSED_WARNING" }) {
            "the kernel must refuse the attempted bypass: ${result.diagnostics()}"
        }
    }

    private fun computation(target: String): Computation =
        model.computation(model.date(target).field()).name("${target.substringAfterLast('/')}Comp")
            .calc(Dsl.addYears(model.date(SOURCE), 0).expression())
            .message(Dsl.en("Copy the source date."))
            .build()

    private companion object {
        const val SOURCE = "/Calendar/Source"
        const val FULL = "/Calendar/Full"
        const val DAY_OPTIONAL = "/Calendar/DayOptional"
        const val MONTH_OPTIONAL = "/Calendar/MonthOptional"
        const val YEAR_OPTIONAL = "/Calendar/YearOptional"
        val PARTIAL_TARGETS = setOf(DAY_OPTIONAL, MONTH_OPTIONAL, YEAR_OPTIONAL)
    }
}
