package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.opentest4j.AssertionFailedError

/**
 * Harness self-test — [SeparateDestinationEngines.appliedExtent] compares three engines that are **not fed
 * through the same A12 ingress route**, and this proves its precondition FIRES rather than merely existing.
 *
 * The kernel arms build from `RuntimeLaws.Doc`, A12's V1 String factory; the standalone arm reads canonical
 * Document JSON, the typed route. `applyTo` applies only CHANGED actions, and the routes disagree about a
 * source-identical cell for every non-STRING kind (KERNEL-FINDINGS §11,
 * [SourceIdentityIngressRouteDiffTest]). Nineteen `appliedExtent` call sites are safe today only because no
 * fixture seeds a computed cell with its own computed value — a latent trap, since such a fixture would
 * diverge with a message pointing at topology, which is the wrong place to look.
 *
 * Two arms, because a gate that cannot be shown to fire is indistinguishable from an absent one, and a gate
 * that fires on everything is worse than none.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeparateDestinationRoutePreconditionTest {

    private val model = RetainedComputationFixture.modelJson()
    private val engines = SeparateDestinationEngines(model, CHAIN, CELLS)

    private fun lines(count: Int): RuntimeLaws.Doc = RuntimeLaws.doc().group(ROOT, 1).also { doc ->
        for (row in 1..count) doc.group(LINES, 1, row)
    }

    @Test
    fun aSourceIdenticalNumberCellIsRefusedWithTheRouteAsTheNamedCause() {
        // Row 1 already holds 7 for a computation that produces 7 — changed on V1, unchanged on typed.
        val source = lines(1).field(CANDIDATE, "7", 1, 1, 1).field(AMOUNT, "7", 1, 1, 1)

        val failure = assertThrows(AssertionFailedError::class.java) {
            engines.appliedExtent(source, lines(0))
        }
        assertTrue(
            failure.message!!.contains("not route-agnostic"),
            "the refusal must name the ROUTE as the cause, not report a topology mismatch: ${failure.message}",
        )
        assertTrue(
            failure.message!!.contains("seeded with its own computed value"),
            "and it must name the fixture repair: ${failure.message}",
        )
    }

    /** The polarity control: an ordinary non-identical source must still pass straight through the gate. */
    @Test
    fun anOrdinarySourcePassesTheGate() {
        val source = lines(1).field(CANDIDATE, "7", 1, 1, 1)
        assertEquals(
            "Order=1 | Lines=1 | Lines[1] Candidate=- Amount=7",
            engines.appliedExtent(source, lines(0)),
            "a source whose computed cell differs from its result is route-agnostic and must not be refused",
        )
    }

    /**
     * The measurement the precondition rests on, asserted here so the gate's premise cannot rot silently: the
     * two routes really do classify that same source differently.
     */
    @Test
    fun theRoutesDisagreeAboutThatExactSource() {
        val source = lines(1).field(CANDIDATE, "7", 1, 1, 1).field(AMOUNT, "7", 1, 1, 1)
        val prepared = PreparedModel.loadExpandedJson(model)

        assertEquals(
            listOf("/Order[1]/Lines[1]/Amount"),
            RuntimeLaws.resultChannels(model, source).withChanges().sorted(),
            "A12's V1 String ingress leaves the Number cell holding unconverted text, so it is changed",
        )
        assertEquals(
            emptyList<String>(),
            prepared.createInterpreter()
                .compute(prepared.documents.readDocument(RuntimeLaws.canonicalDocumentJson(model, source)))
                .actions.map { it.pointer.path },
            "and the typed ingress compares the converted value, so nothing is retained",
        )
    }

    private companion object {
        const val ROOT = RetainedComputationFixture.ROOT
        const val LINES = RetainedComputationFixture.LINES
        const val CANDIDATE = RetainedComputationFixture.CANDIDATE
        const val AMOUNT = RetainedComputationFixture.AMOUNT
        val CHAIN = listOf("Order", "Lines")
        val CELLS = listOf("Candidate", "Amount")
    }
}
