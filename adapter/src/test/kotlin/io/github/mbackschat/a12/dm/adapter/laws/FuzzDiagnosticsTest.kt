package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.perf.PerfScenario
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises [FuzzDiagnostics] (harness B) on a DELIBERATELY divergent scenario — the kernel carries a rule the
 * interpreter model lacks, so a seed diverges by construction (everything real is exact-matched). Proves
 * [FuzzDiagnostics.localize] names the diverging (code @ pointer) and [FuzzDiagnostics.shrink] reduces to the
 * minimal failing document. Anti-rot for the harness the fuzz reaches for only when something breaks.
 */
class FuzzDiagnosticsTest {

    private val fuzz = RuntimeLaws.model("models/fuzz.dm.json")
    private val fields = listOf(FieldDef(ITEM, Kind.STRING), FieldDef(QTY, Kind.NUMBER, scale = 0))

    /** Kernel fires `DV` (Qty > 50); the interpreter model has NO rules → guaranteed divergence on a Qty-99 row. */
    private fun divergentScenario(rows: Int): PerfScenario {
        val kModel = RuntimeLaws.withRule(fuzz, "/Cart/Lines", "DV", "../Qty", "DV", "FieldFilled(Qty) And [Qty] > 50")
        val iModel = ModelDef(fields, emptyList(), repeatableGroups = setOf("/Cart/Lines"),
            declaredReps = mapOf("/Cart/Lines" to 1000), indexFields = setOf(ITEM))
        return PerfScenario("divergent", kModel, iModel) { sink, _, _ ->
            sink.group("/Cart", 1)
            for (r in 1..rows) {
                sink.group("/Cart/Lines", 1, r)
                sink.field(ITEM, "K$r", 1, r, 1)
                sink.field(QTY, if (r == rows) "99" else "10", 1, r, 1)   // only the LAST row trips Qty > 50
            }
        }
    }

    @Test
    fun localizeNamesTheDivergingCodeAndRow() {
        val loc = FuzzDiagnostics.localize(divergentScenario(rows = 3), seed = 0)
        // The kernel fires DV on the last Line; the interpreter (no rules) does not → kernel-only names it.
        assertTrue(loc.contains("kernel-only"), "shows the kernel-only side: $loc")
        assertTrue(loc.contains("DV@"), "names the diverging code with its row pointer: $loc")
    }

    @Test
    fun shrinkReducesToTheMinimalFailingRows() {
        val shr = FuzzDiagnostics.shrink(divergentScenario(rows = 5), seed = 0)
        // Only the Qty-99 row matters; the four Qty-10 rows are dropped, leaving the root + one Line.
        assertTrue(shr.contains("/Cart/Lines"), "dumps the surviving rows: $shr")
        assertTrue(shr.contains("99"), "keeps the row that trips the divergence: $shr")
        assertFalse(shr.contains("[1, 5, 1] 10"), "drops a row irrelevant to the divergence: $shr")
    }

    private companion object {
        const val ITEM = "/Cart/Lines/ItemId"
        const val QTY = "/Cart/Lines/Qty"
    }
}
