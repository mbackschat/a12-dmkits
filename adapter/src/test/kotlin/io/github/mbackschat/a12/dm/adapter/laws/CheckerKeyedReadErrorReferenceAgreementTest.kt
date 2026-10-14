package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A keyed read of another repeatable group does not iterate it, so it cannot stand in for an unreferenced error
 * field (a12-kernel-lean SPEC-2026-10-14-06, row `u03`). An unstarred read of that group iterates it in
 * parallel, and A12 answers with the parallel class instead, the two groups' index fields differing in name; the
 * key selects the row, so A12 asks only whether the condition names the target. Each row is pinned for A12 and
 * compared against the portable checker.
 *
 * Own domain (a parcel depot: parcels against a rate card).
 */
class CheckerKeyedReadErrorReferenceAgreementTest {

    private val depot = DocumentModelDraft.named("parcel-depot").locale("en_US")
        .rootGroup("Depot") { d -> d
            .group("Parcels") { p -> p.repeatable(5).indexField("Tag")
                .stringField("Tag") { }
                .numberField("Weight") { it.fractionDigits(0) }
                .stringField("Tariff") { } }
            .group("Rates") { r -> r.repeatable(5).indexField("Code")
                .stringField("Code") { }
                .numberField("Amount") { it.fractionDigits(0) } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun aKeyedReadOfAnotherGroupLeavesTheErrorFieldUnreferenced() {
        val notReferenced = setOf("MVK_ERROR_FIELD_NOT_REFERENCED")
        val rows = linkedMapOf(
            "a literal key, the target unread" to
                ("FieldFilled(Parcels/Tariff) And [Rates/Amount For \"STD\"] > 0" to notReferenced),
            "a field key, the target unread" to
                ("FieldFilled(Parcels/Tariff) And [Rates/Amount For Parcels/Tariff] > 0" to notReferenced),
            "an unkeyed read, the target unread — the iteration class answers" to
                ("FieldFilled(Parcels/Tariff) And [Rates/Amount] > 0" to setOf("MVK_ERROR_FIELD_INVALID_INDEX")),
            "a literal key, the target read — the control" to
                ("FieldFilled(Parcels/Weight) And [Rates/Amount For \"STD\"] > 0" to emptySet()),
        )
        val observed = rows.mapValues { (_, row) -> verdicts(row.first) }
        assertEquals(rows.mapValues { (_, row) -> row.second to row.second }, observed, "(A12, ours)")
    }

    private fun verdicts(condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Depot", "ProbeRule", "../Parcels/Weight", condition, "PROBE", "ERROR")
        val result = validator.check(depot, draft)
        val a12 = if (result.ok()) emptySet() else result.diagnostics().map { it.code().orElse("?") }.toSet()
        val ours = check(ModelSource(validator.inject(depot, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }
}
