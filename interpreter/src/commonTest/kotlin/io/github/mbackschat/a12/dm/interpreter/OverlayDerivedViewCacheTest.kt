package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.ComputationAlternative
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) correctness lock for the compute-overlay derived-view cache (IF143): under an
 * ACTIVE cascade overlay, the interpreter shares an index-column / `$`-correlation view across a computation
 * step's outer rows instead of rebuilding it per row (O(rows²)→O(rows)). The cache MUST stay correct — it
 * reads the CASCADE values (not the stale stored cell) and never leaks one outer row's view to a row of a
 * different scope. The scaling half is the adapter perf gate (`PerfGateTest.computeOverlay*StaysSubQuadratic`);
 * the against-kernel outcome parity is `PerfGateTest.computeOverlayAgreesWithKernel`. Here we pin the OUTCOMES
 * on the pure engine so JS is covered too, and so a stale/mis-scoped cache is caught by a wrong value.
 */
class OverlayDerivedViewCacheTest {

    private val KEY = "/Ovl/Row/Key"
    private val SRC = "/Ovl/Row/Src"
    private val A = "/Ovl/Row/A"
    private val C = "/Ovl/Row/C"
    private val IDX = "/Ovl/Row/Idx"

    private fun outcomes(def: ModelDef, build: (Document) -> Unit): Map<String, String> {
        val doc = def.newDocument().group("/Ovl", 1)
        build(doc)
        return DmInterpreter(def).compute(doc).associate {
            "${it.path}@${it.indices.joinToString(",")}" to when (it.outcome) {
                ComputeOutcome.VALUE -> "VALUE|${it.value}"
                ComputeOutcome.CLEARED -> "CLEARED"
                ComputeOutcome.ERRORED -> "ERRORED"
            }
        }
    }

    private fun row(doc: Document, i: Int, key: String, src: String) {
        doc.group("/Ovl/Row", 1, i).field(KEY, key, 1, i, 1).field(SRC, src, 1, i, 1)
    }

    /**
     * A per-Row `$`-equality correlation whose join/star column `A` is itself COMPUTED (a prior cascade step),
     * so the correlation view must read A's OVERLAID values, and must read the SAME view for every outer Row of
     * the step. `A := Src + 100` lands first (C reads A → dependency order); then `C := [A]` fires only where
     * MORE THAN ONE Row shares this Row's computed A. A cache that read the stale STORED A (empty) would see an
     * empty join key on every Row → the quantifier never fires → every C would CLEAR; a cache that leaked one
     * Row's buckets to another would mis-tally. The exact per-Row outcomes pin both.
     */
    @Test fun correlationOverAComputedColumnReadsTheCascadeAcrossRows() {
        val def = ModelDef(
            fields = listOf(
                FieldDef(KEY, Kind.STRING), FieldDef(SRC, Kind.NUMBER, scale = 0),
                FieldDef(A, Kind.NUMBER, scale = 0), FieldDef(C, Kind.NUMBER, scale = 0),
            ),
            rules = emptyList(),
            computations = listOf(
                // C declared FIRST on purpose — only the cascade's dependency order runs A before C.
                ComputationDef(C, "/Ovl/Row", listOf(ComputationAlternative(
                    "MoreThanOneFieldFilled(/Ovl/Row*/A Having [$A] == [\$$A])", "[$A]"))),
                ComputationDef(A, "/Ovl/Row", listOf(ComputationAlternative(null, "[$SRC] + 100"))),
            ),
            repeatableGroups = setOf("/Ovl/Row"), indexFields = setOf(KEY),
        )
        val out = outcomes(def) { doc ->
            row(doc, 1, "ka", "10"); row(doc, 2, "kb", "10"); row(doc, 3, "kc", "20")
        }
        // A = Src + 100 (the overlaid column the correlation reads).
        assertEquals("VALUE|110", out["$A@1,1,1"]); assertEquals("VALUE|110", out["$A@1,2,1"])
        assertEquals("VALUE|120", out["$A@1,3,1"])
        // C fires where >1 Row shares this Row's computed A: rows 1 & 2 share A=110; row 3 (A=120) is alone.
        // Reading the cascade (not the stale empty A) is what makes rows 1 & 2 fire at all.
        assertEquals("VALUE|110", out["$C@1,1,1"], "row 1 sees row 2's computed A=110 → fires")
        assertEquals("VALUE|110", out["$C@1,2,1"], "the shared correlation view is consistent across the step's rows")
        assertEquals("CLEARED", out["$C@1,3,1"], "row 3's A=120 is unique → the quantifier does not fire")
    }

    /**
     * A per-Row `[Src For "kb"]` semantic-index read UNDER an active overlay: `Act := [Src]` activates the
     * cascade so the reader runs through the [OverlayContext], whose `indexColumn(Key)` whole-column validity
     * view is shared across the step's Rows. The lookup resolves the SAME Row (`Key == "kb"`) for every outer
     * Row, so `Idx` is the same value everywhere; a mis-scoped or corrupted cache would resolve some Rows wrong.
     */
    @Test fun semanticIndexUnderActiveOverlayIsConsistentAcrossRows() {
        val def = ModelDef(
            fields = listOf(
                FieldDef(KEY, Kind.STRING), FieldDef(SRC, Kind.NUMBER, scale = 0),
                FieldDef(A, Kind.NUMBER, scale = 0), FieldDef(IDX, Kind.NUMBER, scale = 0),
            ),
            rules = emptyList(),
            computations = listOf(
                ComputationDef(A, "/Ovl/Row", listOf(ComputationAlternative(null, "[$SRC]"))),          // activator
                ComputationDef(IDX, "/Ovl/Row", listOf(ComputationAlternative(null, "[$SRC For \"kb\"]"))),
            ),
            repeatableGroups = setOf("/Ovl/Row"), indexFields = setOf(KEY),
        )
        val out = outcomes(def) { doc ->
            row(doc, 1, "ka", "10"); row(doc, 2, "kb", "20"); row(doc, 3, "kc", "30")
        }
        assertEquals("VALUE|10", out["$A@1,1,1"]); assertEquals("VALUE|30", out["$A@1,3,1"])   // the overlay activator
        // [Src For "kb"] resolves the Key=="kb" Row (Src=20) — the SAME for every outer Row (index is scope-global).
        for (i in 1..3) assertEquals("VALUE|20", out["$IDX@1,$i,1"], "the shared index-column view resolves Row kb for every Row")
    }
}
