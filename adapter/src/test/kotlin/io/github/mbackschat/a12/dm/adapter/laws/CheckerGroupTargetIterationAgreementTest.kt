package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The root-only error-target iteration class on a GROUP error target (KERNEL-FINDINGS KF348). A group target is
 * judged by the group that holds it, as a field target is, whether the target itself repeats or not: a per-row
 * read in the holding group draws the own-group class under both.
 *
 * [ROWS] pins A12's answer on every row, and the checker must name exactly the same classes. Own domain: a
 * shipment's received lines, each with a fixed check and repeatable parts.
 */
class CheckerGroupTargetIterationAgreementTest {

    private val shipment = DocumentModelDraft.named("shipment-group-target").locale("en_US")
        .rootGroup("Shipment") { root -> root.repeatable(5)
            .group("Received") { rows -> rows.repeatable(10)
                .numberField("Quantity") { }
                .stringField("Note") { }
                .group("Check") { c -> c.stringField("Mark") { } }
                .group("Parts") { p -> p.repeatable(4).numberField("Cost") { } } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun a12AnswersEveryRowAsPinned() {
        assertEquals(ROWS.mapValues { it.value.second }, ROWS.mapValues { judge(it.value.first).first })
    }

    @Test
    fun theCheckerNamesWhatA12Names() {
        assertEquals(ROWS.mapValues { judge(it.value.first).first }, ROWS.mapValues { judge(it.value.first).second })
    }

    private fun judge(probe: Pair<String, String>): Pair<List<String>, List<String>> {
        val (target, condition) = probe
        val draft = DraftRule("/Shipment/Received", "TargetProbe", "../$target", condition, "PROBE", "ERROR")
        val result = validator.check(shipment, draft)
        val a12 = if (result.ok()) emptyList() else result.diagnostics().map { it.code().orElse("(no code)") }
        val ours = rejectingCodes(validator.inject(shipment, draft))
        return a12.sorted() to ours.sorted()
    }

    private companion object {
        const val OWN_GROUP = "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"
        const val ROOT_ONLY_READ = "GroupFilled(/Shipment) And [Quantity] > 0"

        val ROWS = linkedMapOf(
            "a field target, the control" to (("Note" to ROOT_ONLY_READ) to listOf(OWN_GROUP)),
            "a fixed group target" to (("Check" to ROOT_ONLY_READ) to listOf(OWN_GROUP)),
            "a repeatable group target" to (("Parts" to ROOT_ONLY_READ) to listOf(OWN_GROUP)),
            "a repeatable group target, no per-row read" to (("Parts" to "GroupFilled(/Shipment)") to emptyList()),
        )
    }
}
