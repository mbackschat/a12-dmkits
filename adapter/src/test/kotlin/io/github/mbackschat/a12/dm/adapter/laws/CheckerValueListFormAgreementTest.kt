package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The two value-list forms a12-kernel-lean SPEC-2026-10-26-16 left unmeasured, and the checker's agreement on
 * each ([KERNEL-FINDINGS KF628]): a singular value-list operator takes its members after the subject's comma and
 * refuses an `In`, and a quantifier's `In` list takes no parentheses. The expected column is A12's, measured
 * through `rule check` on 2026-10-27.
 *
 * Own domain (an online order).
 */
class CheckerValueListFormAgreementTest {

    private val order = DocumentModelDraft.named("order-value-list-forms").locale("en_US")
        .rootGroup("Order") { o -> o.stringField("Note") { }.stringField("Memo") { } }
        .build().toJson()

    private val validator = RuleValidator()

    private fun draft(condition: String) = DraftRule("/Order", "ListProbe", "../Note", condition, "PROBE", "ERROR")

    private fun kernel(condition: String): List<String> =
        validator.check(order, draft(condition)).let { r ->
            if (r.ok()) emptyList() else r.diagnostics().map { it.codeOrSummary() }.sorted()
        }

    private fun ours(condition: String): List<String> =
        rejectingCodes(validator.inject(order, draft(condition))).sorted()

    @Test
    fun theCheckerNamesA12sClassOnEachForm() {
        val rows = linkedMapOf(
            "FieldValueIncludedInValueList(Note In \"A\", \"B\")" to listOf("MVK_UNEXPECTED_TOKEN"),
            "FieldValueIncludedInValueList(Note, \"A\", \"B\")" to emptyList(),
            "AtLeastOneFieldValueIncludedInValueList(Note, Memo In (\"A\", \"B\"))" to listOf("MVK_UNEXPECTED_TOKEN"),
            "AtLeastOneFieldValueIncludedInValueList(Note, Memo In \"A\", \"B\")" to emptyList(),
        )
        val observed = rows.keys.associateWith { kernel(it) to ours(it) }
        assertEquals(rows.mapValues { (_, a12) -> a12 to a12 }, observed, "as (A12's classes, the checker's)")
    }
}
