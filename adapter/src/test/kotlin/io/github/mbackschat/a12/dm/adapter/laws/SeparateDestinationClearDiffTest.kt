package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EXP-2026-07-30-02 — computation-result actions are classified against a source and may then be applied to
 * another compatible destination. A source-classified CLEARED action is therefore tested against absent
 * destination topology, separately from same-document compute/application.
 *
 * Dynamic Groovy, generated Java, and the standalone interpreter use their retained-result
 * `compute(source).applyTo(destination)` routes.
 */
class SeparateDestinationClearDiffTest {

    private val model = RetainedComputationFixture.modelJson()
    private val java = RuntimeLaws.kernelJavaEngine(model)
    private val prepared = PreparedModel.loadExpandedJson(model)
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
        CrossRouteSourceGate.requireRouteAgnostic(model, prepared, source)
        val expected = listOf("$AMOUNT|$expectedState")
        val dynamic = RuntimeLaws.appliedStatesToDestination(model, source, destination, probe)
        val generated = java.appliedStatesToDestination(source, destination, probe)
        val standalone = interpreterState(source, destination)

        assertEquals(expected, dynamic, "$label — dynamic Groovy")
        assertEquals(dynamic, generated, "$label — generated Java")
        assertEquals(dynamic, standalone, "$label — standalone interpreter")
    }

    private fun interpreterState(source: RuntimeLaws.Doc, destination: RuntimeLaws.Doc): List<String> {
        val sourceSnapshot = prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, source))
        val destinationSnapshot = prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, destination))
        val report = prepared.createInterpreter().compute(sourceSnapshot)
        val applied = report.applyTo(destinationSnapshot)
        val encoded = prepared.documents.encodeDocument(applied)
        val state = when {
            "\"Amount\":null" in encoded -> "presentEmpty"
            "\"Amount\":7" in encoded -> "presentValue|7"
            else -> "absent"
        }
        return listOf("$AMOUNT|$state")
    }

    private fun rows(count: Int): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
        for (row in 1..count) {
            doc.group(LINES, 1, row)
        }
    }

    private companion object {
        const val ROOT = RetainedComputationFixture.ROOT
        const val LINES = RetainedComputationFixture.LINES
        const val CANDIDATE = RetainedComputationFixture.CANDIDATE
        const val AMOUNT = RetainedComputationFixture.AMOUNT
        val CELL = RetainedComputationFixture.CELL
    }
}
