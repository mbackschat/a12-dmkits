package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EXP-2026-07-30-02 — computation-result actions are classified against a source and may then be applied to
 * another compatible destination. A source-classified CLEARED action is therefore tested against absent
 * destination topology, separately from same-document compute/application.
 *
 * Dynamic Groovy and generated Java use the existing retained-result `applyTo(destination)` route. The
 * interpreter is deliberately absent: its public `applyComputations(document)` computes and applies one
 * document and cannot retain a source result for application to another destination.
 */
class SeparateDestinationClearDiffTest {

    private val model = run {
        val base = DocumentModelDraft.named("order-clear-actions").locale("en_US")
            .rootGroup("Order") { order ->
                order.group("Lines") { lines ->
                    lines.repeatable(5)
                        .numberField("Candidate") { it.fractionDigits(0) }
                        .numberField("Amount") { it.fractionDigits(0).max("9") }
                }
            }
            .build()
            .toJson()
        val bound = BoundModel.ofJson(base)
        val computation = bound.computation(bound.num(AMOUNT)).name("AmountProjection")
            .commonPrecondition(bound.num(CANDIDATE).filled())
            .calc(bound.num(CANDIDATE).value())
            .message(Dsl.en("Project the selected amount."))
            .build()
        TypedRules.inject(base, computation)
    }
    private val java = RuntimeLaws.kernelJavaEngine(model)
    private val probe = listOf(RuntimeLaws.CellProbe(AMOUNT, CELL))

    @Test
    fun aSourceClassifiedClearCreatesPresentEmptyDestinationState() {
        val source = rows(2).field(AMOUNT, "9", *CELL.toIntArray())
        assertTrue(
            RuntimeLaws.resultChannels(model, source).cleared().any { it.contains("Amount") },
            "the filled source target mints the row-2 CLEARED action",
        )
        assertTrue(
            java.resultChannels(java.instance(source)).cleared().any { it.contains("Amount") },
            "generated Java mints the same row-2 CLEARED action",
        )

        assertBoth(
            source,
            rows(2),
            "presentEmpty",
            "row 2 exists but its target is absent",
        )
        assertBoth(
            source,
            rows(0),
            "presentEmpty",
            "the Lines repetition is absent and row 2 must be created for the clear action",
        )
    }

    @Test
    fun sourceAndActionControlsSeparateClearFromSilenceErrorAndValue() {
        val sourceWithoutTarget = rows(2)
        assertFalse(
            RuntimeLaws.resultChannels(model, sourceWithoutTarget).cleared().any { it.contains("Amount") },
            "an absent source target mints no cleared action",
        )
        assertFalse(
            java.resultChannels(java.instance(sourceWithoutTarget)).cleared().any { it.contains("Amount") },
            "generated Java also mints no cleared action",
        )
        assertBoth(sourceWithoutTarget, rows(0), "absent", "no source action leaves the destination absent")

        val erroredSource = rows(2).field(CANDIDATE, "10", *CELL.toIntArray())
        assertTrue(
            RuntimeLaws.resultChannels(model, erroredSource).withErrors().any { it.contains("Amount") },
            "the out-of-range result is source-classified ERRORED",
        )
        assertTrue(
            java.resultChannels(java.instance(erroredSource)).withErrors().any { it.contains("Amount") },
            "generated Java source-classifies the result ERRORED",
        )
        assertBoth(erroredSource, rows(0), "absent", "ERRORED does not create an absent destination target")

        val changedSource = rows(2).field(CANDIDATE, "7", *CELL.toIntArray())
        assertTrue(
            RuntimeLaws.resultChannels(model, changedSource).withChanges().any { it.contains("Amount") },
            "the clean source result is classified as a changed VALUE",
        )
        assertTrue(
            java.resultChannels(java.instance(changedSource)).withChanges().any { it.contains("Amount") },
            "generated Java classifies the clean source result as a changed VALUE",
        )
        assertBoth(changedSource, rows(0), "presentValue|7", "VALUE creates the missing row and target")

        val clearedSource = rows(2).field(AMOUNT, "9", *CELL.toIntArray())
        val filledDestination = rows(2).field(AMOUNT, "8", *CELL.toIntArray())
        assertBoth(clearedSource, filledDestination, "presentEmpty", "CLEARED empties a filled destination target")
    }

    private fun assertBoth(
        source: RuntimeLaws.Doc,
        destination: RuntimeLaws.Doc,
        expectedState: String,
        label: String,
    ) {
        val expected = listOf("$AMOUNT|$expectedState")
        val dynamic = RuntimeLaws.appliedStatesToDestination(model, source, destination, probe)
        val generated = java.appliedStatesToDestination(source, destination, probe)

        assertEquals(expected, dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
    }

    private fun rows(count: Int): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
        for (row in 1..count) {
            doc.group(LINES, 1, row)
        }
    }

    private companion object {
        const val ROOT = "/Order"
        const val LINES = "$ROOT/Lines"
        const val CANDIDATE = "$LINES/Candidate"
        const val AMOUNT = "$LINES/Amount"
        val CELL = listOf(1, 2, 1)
    }
}
