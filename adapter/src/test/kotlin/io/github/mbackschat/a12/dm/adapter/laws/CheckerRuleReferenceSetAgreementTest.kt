package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which references the post-parse gates over a rule's error field count, with A12's class written out on every
 * row and the portable checker required to name the same one.
 *
 * `IterationChecker.checkIteration` keeps a reference only when it was created with the filter flag off or
 * carries the `$` marker, and its iteration test asks for an ITERATION index, which a star is not (source-read).
 * Both `MVK_ERROR_FIELD_NOT_REFERENCED` and the `MVK_ERROR_FIELDGROUP_ITERATION{4,5}_INVALID` pair are decided over
 * that set. The iteration pair's grids ([KERNEL-FINDINGS KF348]) held every read unstarred and outside any filter,
 * so neither variable had a row.
 *
 * Own domain (goods receiving): the rule is declared in `Received` and reports on its `Note`, as KF348's are.
 */
class CheckerRuleReferenceSetAgreementTest {

    private val shipment = DocumentModelDraft.named("shipment-reference-set").locale("en_US")
        .rootGroup("Shipment") { s -> s
            .group("Received") { r -> r.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .numberField("Quantity") { n -> n.fractionDigits(0) }
                .stringField("Note") { } }
            .group("Expected") { e -> e.repeatable(10).indexField("LineNo")
                .stringField("LineNo") { }
                .numberField("Quantity") { n -> n.fractionDigits(0) } }
            .group("Returns") { r -> r.repeatable(5)
                .numberField("Weight") { n -> n.fractionDigits(0) } } }
        .build().toJson()

    private val validator = RuleValidator()

    @Test
    fun theIterationPairCountsOnlyTheRulesOwnIteratingReads() {
        // Each condition reaches `Note` only through the root, which pins no repetition.
        val predicted = linkedMapOf(
            "no read beyond the root — control" to ("GroupFilled(/Shipment)" to ADMITTED),
            "a read of the error field's own group — control" to ("GroupFilled(/Shipment) And [Quantity] > 0" to OWN),
            "a read of a parallel keyed group — control" to
                ("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity] > 0" to PARALLEL),
            // A star is a WILDCARD, never an ITERATION.
            "a starred read of the error field's own group" to
                ("GroupFilled(/Shipment) And Sum(/Shipment/Received*/Quantity) > 0" to ADMITTED),
            "a starred read of a parallel keyed group" to
                ("GroupFilled(/Shipment) And Sum(/Shipment/Expected*/Quantity) > 0" to ADMITTED),
            "a starred read of an unkeyed repetition" to
                ("GroupFilled(/Shipment) And Sum(/Shipment/Returns*/Weight) > 0" to ADMITTED),
            // A keyed read puts an index at the level it keys, never an ITERATION.
            "a keyed read of the error field's own group" to
                ("GroupFilled(/Shipment) And [/Shipment/Received/Quantity For \"L1\"] > 0" to ADMITTED),
            "a keyed read of a parallel keyed group" to
                ("GroupFilled(/Shipment) And [/Shipment/Expected/Quantity For \"L1\"] > 0" to ADMITTED),
            // A body read created with the flag on is not the rule's; a marked one is.
            "a filter-body read of the error field's own group" to
                ("GroupFilled(/Shipment) And Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Quantity] > 1) > 0" to
                    ADMITTED),
            "a marked filter-body read of the error field's own group" to
                ("GroupFilled(/Shipment) And Sum(/Shipment/Returns*/Weight Having [/Shipment/Returns/Weight] > [\$Quantity]) > 0" to
                    OWN),
        )
        assertAgreement(predicted)
    }

    @Test
    fun theErrorFieldIsReferencedOnlyByTheRulesOwnReads() {
        val predicted = linkedMapOf(
            "the error field read only in a filter body" to
                ("Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Note] == \"x\") > 0" to NOT_REFERENCED),
            "the error field read marked in a filter body" to
                ("Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Quantity] > 1 And [\$Note] == \"x\") > 0" to
                    ADMITTED),
            // The root reaches `Note` only from a body, so nothing the rule reads reaches it, and the iterating
            // read beside it cannot make the iteration pair's class.
            "the root reached only from a filter body, beside an iterating read" to
                ("[Quantity] > 0 And Sum(/Shipment/Received*/Quantity Having GroupFilled(/Shipment) And " +
                    "[/Shipment/Received/Quantity] > 1) > 0" to NOT_REFERENCED),
            "the error field read outside the filter — control" to
                ("FieldFilled(Note) And Sum(/Shipment/Received*/Quantity Having [/Shipment/Received/Note] == \"x\") > 0" to
                    ADMITTED),
        )
        assertAgreement(predicted)
    }

    private fun assertAgreement(predicted: Map<String, Pair<String, Set<String>>>) {
        val wrong = predicted.mapNotNull { (row, cell) ->
            val (condition, a12) = cell
            val observed = compare(condition)
            val expected = a12 to (OURS[row] ?: a12)
            if (observed == expected) null else "$row: expected (A12, ours) $expected, observed $observed"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n", "rows that disagree:\n"))
    }

    private fun compare(condition: String): Pair<Set<String>, Set<String>> {
        val draft = DraftRule("/Shipment/Received", "ProbeRule", "../Note", condition, "PROBE", "ERROR")
        val result = validator.check(shipment, draft)
        val a12 = if (result.ok()) emptySet() else
            result.diagnostics().map { it.code().orElse("(no code: ${it.summary()})") }.toSet()
        val ours = check(ModelSource(validator.inject(shipment, draft))).findings
            .filter { it.justifiesRejection }.map { it.code }.toSet()
        return a12 to ours
    }

    private companion object {
        val OWN = setOf("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID")
        val PARALLEL = setOf("MVK_ERROR_FIELDGROUP_ITERATION5_INVALID")
        val NOT_REFERENCED = setOf("MVK_ERROR_FIELD_NOT_REFERENCED")
        val ADMITTED = emptySet<String>()

        /** The rows where the checker answers otherwise, with what it answers. */
        val OURS: Map<String, Set<String>> = mapOf()
    }
}
