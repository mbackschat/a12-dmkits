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
 * Differential + tri-check (groovy-dynamic == java-static == interpreter) — `FirstFilledValue` over the
 * starred `Items* / Qty` as a COMPUTATION whose star carries a formally-INVALID cell (the IG56 E4 hostile VCV
 * minimal: kernel VALUE, interp over-suppressed to CLEARED).
 *
 * The kernel's `FirstValueCombiner.addValue` IGNORES every cell after the first FILLED value is captured — an
 * invalid cell poisons (`nonRelevantFieldBeforeFirstGiven` → the non-relevant result → the computation clears)
 * only when it sits BEFORE the first given cell. So the generic "any invalid selected cell poisons" star rule
 * (right for Sum/Min/Max, whose combiners consume every row) does NOT apply to `FirstFilledValue`: an invalid
 * TAIL cell is invisible. Own domain (e-commerce order): `Result` = the first filled of `Items* / Qty`, the
 * invalid trigger a leading-zero quantity (`07`).
 */
class FirstFilledValueInvalidTailDiffTest {

    private companion object {
        const val RESULT = "/Order/Result"
        const val QTY = "/Order/Items/Qty"
        const val ITEMS = "/Order/Items"
    }

    private val base = DocumentModelDraft.named("orderfirst").locale("en_US")
        .rootGroup("Order") { o ->
            o.numberField("Result") { it.fractionDigits(0, 0) }
            o.group("Items") { i -> i.repeatable(10).numberField("Qty") { it.fractionDigits(0, 0) } }
        }.build().toJson()

    private val kModel = RuntimeLaws.withComputation(base, "/Order", "FirstComp", "../Result",
        "FirstFilledValue(Items*/Qty)")

    /** [rows]: Qty per row (null = instantiated row, empty cell). Returns the tri-checked Result outcome. */
    private fun triOutcome(rows: List<String?>): String {
        val def = ModelLoader.load(kModel)
        fun build(k: RuntimeLaws.Doc, i: Document) {
            k.group("/Order", 1).field(RESULT, "999", 1, 1)
            i.group("/Order", 1).field(RESULT, "999", 1, 1)
            rows.forEachIndexed { idx, qty ->
                val r = idx + 1
                k.group(ITEMS, 1, r); i.group(ITEMS, 1, r)
                qty?.let { k.field(QTY, it, 1, r, 1); i.field(QTY, it, 1, r, 1) }
            }
        }
        val kDoc = RuntimeLaws.doc(); val iDoc = def.newDocument()
        build(kDoc, iDoc)
        val groovy = RuntimeLaws.compute(kModel, kDoc).firstOrNull { it.path() == RESULT }
            .let { c -> if (c == null) "NONE" else if (c.cleared()) "CLEARED" else if (c.errored()) "ERRORED" else "VALUE|${c.value().orElse("")}" }
        val engine = RuntimeLaws.kernelJavaEngine(kModel)
        val kDoc2 = RuntimeLaws.doc(); val iDoc2 = def.newDocument()
        build(kDoc2, iDoc2)
        val javaStatic = engine.computeOutcomesSorted(engine.instance(kDoc2)).firstOrNull { it.startsWith("$RESULT|") }
            ?.substringAfter("$RESULT|")?.let { if (it == "ERR") "ERRORED" else if (it == "CLEARED") "CLEARED" else "VALUE|$it" } ?: "NONE"
        val interp = DmInterpreter(def).compute(iDoc).firstOrNull { it.path == RESULT }
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

    // ── the corpus instance: an invalid cell AFTER the first filled one is never consumed → VALUE ─────────
    @Test fun anInvalidTailCellIsInvisible() =
        assertEquals("VALUE|5", triOutcome(listOf("5", "07")),
            "07 (leading zero) is formally invalid but sits after the first filled cell — the combiner ignores it")

    // ── an invalid cell BEFORE the first filled one poisons (nonRelevantFieldBeforeFirstGiven) ────────────
    @Test fun anInvalidCellBeforeTheFirstFilledPoisons() =
        assertEquals("CLEARED", triOutcome(listOf("07", "5")))

    // ── an EMPTY row before the first filled one does not poison (notGiven, not nonRelevant) ──────────────
    @Test fun anEmptyRowBeforeTheFirstFilledComputes() =
        assertEquals("VALUE|5", triOutcome(listOf(null, "5")))

    // ── empty then invalid then filled: the invalid still precedes the first GIVEN cell → poison ──────────
    @Test fun anInvalidCellAfterAnEmptyRowStillPoisons() =
        assertEquals("CLEARED", triOutcome(listOf(null, "07", "5")))

    // ── controls ───────────────────────────────────────────────────────────────────────────────────────────
    @Test fun allValidComputesTheFirst() =
        assertEquals("VALUE|5", triOutcome(listOf("5", "9")))

    @Test fun allEmptySelection() =
        assertEquals("VALUE|0", triOutcome(listOf(null, null)),
            "the NUMBER FirstValueCombiner's empty selection is the fillable 0 (IF80)")
}
