package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock — the computation-phase filtered traversal's ONE-KEPT-SUCCESSOR LOOKAHEAD
 * consumed by `FirstFilledValue` (SPEC-2026-07-23-10, the kernel `EbenenIterator.InnerIterator` prefetch): a
 * filter poison met while searching for the next kept candidate precedes the pending filled current target;
 * once one successor is kept, filters beyond it stay unread when the current target terminates the prefix
 * scan; an earlier terminal authored operand leaves a later filtered operand wholly unread. Kernel-verified
 * by the tri-check differential `adapter.laws.FirstFilledValueKeptSuccessorDiffTest`.
 */
class FirstFilledValueKeptSuccessorTest {

    private val PAYOUT = "/Policy/Payout"
    private val ANCHOR = "/Policy/Anchor"
    private val AMOUNT = "/Policy/Claims/Amount"
    private val RANK = "/Policy/Claims/Rank"

    private fun def(expr: String) = ModelDef(
        fields = listOf(
            FieldDef(PAYOUT, Kind.NUMBER, scale = 0),
            FieldDef(ANCHOR, Kind.NUMBER, scale = 0),
            FieldDef(AMOUNT, Kind.NUMBER, scale = 0),
            FieldDef(RANK, Kind.NUMBER, scale = 0),
        ),
        rules = emptyList(),
        computations = listOf(ComputationDef(PAYOUT, "/Policy", expr)),
        repeatableGroups = setOf("/Policy/Claims"),
    )

    /** [rows]: per row `(Amount, Rank)`, null = empty cell. Returns the `Payout` outcome. */
    private fun outcome(expr: String, rows: List<Pair<String?, String?>>, anchor: String? = null): String {
        val model = def(expr)
        val doc = model.newDocument().group("/Policy", 1).field(PAYOUT, "999", 1, 1)
        anchor?.let { doc.field(ANCHOR, it, 1, 1) }
        rows.forEachIndexed { i, (amount, rank) ->
            doc.group("/Policy/Claims", 1, i + 1)
            amount?.let { doc.field(AMOUNT, it, 1, i + 1, 1) }
            rank?.let { doc.field(RANK, it, 1, i + 1, 1) }
        }
        val c = DmInterpreter(model).compute(doc).first { it.path == PAYOUT }
        return when (c.outcome) {
            ComputeOutcome.VALUE -> "VALUE|${c.value}"
            ComputeOutcome.CLEARED -> "CLEARED"
            ComputeOutcome.ERRORED -> "ERRORED"
        }
    }

    private val filtered = "FirstFilledValue(Claims*/Amount Having ([Claims/Rank] > 0))"

    @Test fun aSuccessorFilterPoisonPrecedesTheFilledCurrentTarget() =
        assertEquals("CLEARED", outcome(filtered, listOf("5" to "1", "3" to "07")))

    @Test fun aFilterBeyondThePrefetchedSuccessorStaysUnread() =
        assertEquals("VALUE|5", outcome(filtered, listOf("5" to "1", "3" to "1", "4" to "07")))

    @Test fun aPoisonMetWhileSearchingThroughAFalseCandidatePrecedesTheCurrentTarget() =
        assertEquals("CLEARED", outcome(filtered, listOf("5" to "1", "3" to "0", "4" to "07")))

    @Test fun anEarlierTerminalDirectOperandLeavesTheFilteredOperandUnread() =
        assertEquals("VALUE|7", outcome(
            "FirstFilledValue(Anchor, Claims*/Amount Having ([Claims/Rank] > 0))",
            listOf("5" to "07"), anchor = "7",
        ))

    @Test fun anEmptyKeptTargetContinuesToTheNextKeptRow() =
        assertEquals("VALUE|3", outcome(filtered, listOf(null to "1", "3" to "1")))
}
