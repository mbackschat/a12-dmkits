package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — the computation-phase filtered
 * entity iterator's ONE-KEPT-SUCCESSOR LOOKAHEAD consumed by `FirstFilledValue` (SPEC-2026-07-23-10; kernel
 * `EbenenIterator.InnerIterator.next` → `toNext`): after the current candidate is kept, the iterator searches
 * through later FALSE candidates until another candidate is kept or the stream ends, BEFORE the consumer reads
 * the current candidate's target. So a filter poison met during that search precedes a filled pending target
 * (S1, S3), while a filter beyond one prefetched successor stays unread when the current target terminates the
 * prefix scan (S2), and an earlier terminal authored operand leaves a later filtered operand wholly unread (S4).
 *
 * Own domain (insurance policy): `Payout` = the first filled of `Claims* / Amount` kept by `[Claims/Rank] > 0`;
 * the invalid trigger a leading-zero number (`07`) on a scale-0 field.
 */
class FirstFilledValueKeptSuccessorDiffTest {

    private companion object {
        const val PAYOUT = "/Policy/Payout"
        const val ANCHOR = "/Policy/Anchor"
        const val CLAIMS = "/Policy/Claims"
        const val AMOUNT = "/Policy/Claims/Amount"
        const val RANK = "/Policy/Claims/Rank"
    }

    private val base = DocumentModelDraft.named("claimfirst").locale("en_US")
        .rootGroup("Policy") { p ->
            p.numberField("Payout") { it.fractionDigits(0, 0) }
            p.numberField("Anchor") { it.fractionDigits(0, 0) }
            p.group("Claims") { c ->
                c.repeatable(9)
                    .numberField("Amount") { it.fractionDigits(0, 0) }
                    .numberField("Rank") { it.fractionDigits(0, 0) }
            }
        }.build().toJson()

    /** [rows]: per row `(Amount, Rank)`, null = empty cell. Tri-checks and returns the `Payout` outcome. */
    private fun triOutcome(expr: String, rows: List<Pair<String?, String?>>, anchor: String? = null): String {
        val kModel = RuntimeLaws.withComputation(base, "/Policy", "PayoutComp", "../Payout", expr)
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Policy", 1).field(PAYOUT, "999", 1, 1)
            i.group("/Policy", 1).field(PAYOUT, "999", 1, 1)
            anchor?.let { k.field(ANCHOR, it, 1, 1); i.field(ANCHOR, it, 1, 1) }
            rows.forEachIndexed { idx, (amount, rank) ->
                val r = idx + 1
                k.group(CLAIMS, 1, r); i.group(CLAIMS, 1, r)
                amount?.let { k.field(AMOUNT, it, 1, r, 1); i.field(AMOUNT, it, 1, r, 1) }
                rank?.let { k.field(RANK, it, 1, r, 1); i.field(RANK, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(kModel, kDoc).firstOrNull { it.path() == PAYOUT }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$PAYOUT|") }
            ?.substringAfter("$PAYOUT|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == PAYOUT }
            .let { c -> when (c?.outcome) {
                null -> "NONE"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
                ComputeOutcome.VALUE -> "VALUE|${c.value}"
            } }
        assertEquals(groovy, javaStatic, "rows=$rows — groovy vs java-static (tri-check)")
        assertEquals(groovy, interp, "rows=$rows — kernel vs interpreter")
        return groovy
    }

    private val filtered = "FirstFilledValue(Claims*/Amount Having ([Claims/Rank] > 0))"

    // ── S1 (lead): the successor search runs BEFORE the current kept target is read ───────────────────────
    @Test fun aSuccessorFilterPoisonPrecedesTheFilledCurrentTarget() =
        assertEquals("CLEARED", triOutcome(filtered, listOf("5" to "1", "3" to "07")),
            "row 1 is kept and its target filled, but the prefetch reads row 2's invalid filter first → poison")

    // ── S2: once one successor is kept, filters beyond it stay unread when the target terminates ──────────
    @Test fun aFilterBeyondThePrefetchedSuccessorStaysUnread() =
        assertEquals("VALUE|5", triOutcome(filtered, listOf("5" to "1", "3" to "1", "4" to "07")),
            "row 2 is the prefetched kept successor — the filled row-1 target stops the scan, row 3's filter is unread")

    // ── S3: false candidates remain part of the successor search ──────────────────────────────────────────
    @Test fun aPoisonMetWhileSearchingThroughAFalseCandidatePrecedesTheCurrentTarget() =
        assertEquals("CLEARED", triOutcome(filtered, listOf("5" to "1", "3" to "0", "4" to "07")),
            "row 2 is false, so the successor search continues to row 3's invalid filter → poison before target 1")

    // ── S4: an earlier terminal authored operand hides a later filtered operand completely ────────────────
    @Test fun anEarlierTerminalDirectOperandLeavesTheFilteredOperandUnread() =
        assertEquals("VALUE|7", triOutcome(
            "FirstFilledValue(Anchor, Claims*/Amount Having ([Claims/Rank] > 0))",
            listOf("5" to "07"), anchor = "7",
        ), "the filled direct Anchor terminates the scan — the filtered slot and its filters are never read")

    // ── control: an empty kept target continues the prefix scan to the next kept row ──────────────────────
    @Test fun anEmptyKeptTargetContinuesToTheNextKeptRow() =
        assertEquals("VALUE|3", triOutcome(filtered, listOf(null to "1", "3" to "1")))
}
