package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * Decorates an [EvalContext] so reads of a field that a PRIOR computation produced return its computed
 * value (the cascade overlay) — keyed by `(path, reps)`, no document mutation. Direct `[A]` /
 * `FieldFilled(A)` reads are overlaid, AND a STARRED read (`Sum(Group* then A)`) applies the overlay per row
 * (via [EvalContext.starCells]) — so an aggregate over a computed field sums the COMPUTED values, not the
 * stored ones (KERNEL-FINDINGS §11). All other methods delegate. Used only by
 * `DmInterpreter.computeInstances`, so the polarity/omission reads (irrelevant to a computed value) are left
 * to the real context.
 */
internal class OverlayContext(
    private val d: EvalContext,
    private val overlay: Map<Pair<String, List<Int>>, Val>,
    private val cleared: Set<Pair<String, List<Int>>>,
    private val poisoned: Set<Pair<String, List<Int>>>,
    private val emptyOf: (String) -> Val,
    private val minFracOf: (String) -> Int,
) : EvalContext by d {
    private fun overlaid(path: String): Val? = overlay[path to d.repsOf(path)]
    private fun isCleared(path: String): Boolean = path to d.repsOf(path) in cleared
    // A POISONED cascade target (an errored value / an itself-poisoned instance) poisons EVERY read — value,
    // presence, or display (IF85; the kernel's CalculationCache.getWertXML/isEntityFilled throw for an
    // invalid-marked field). A CLEARED target instead reads as EMPTY (IF77) — the two clear-causes differ.
    private fun poisonIfPoisoned(path: String) {
        if (path to d.repsOf(path) in poisoned) throw PoisonedReadException(path)
    }
    override fun field(path: String): Val {
        poisonIfPoisoned(path)
        return if (isCleared(path)) emptyOf(path) else overlaid(path) ?: d.field(path)
    }
    override fun filled(path: String): Boolean {
        poisonIfPoisoned(path)
        return if (isCleared(path)) false else overlaid(path)?.let { it !is Val.Unknown } ?: d.filled(path)
    }
    override fun displayValue(path: String): String? {
        poisonIfPoisoned(path)
        return if (isCleared(path)) null else when (val v = overlaid(path)) {
            // The STORED form of a computed number (padded to the target's minFractionalDigits, IF82/IF90) —
            // so a downstream poison gate sees the same value the kernel stored, not an unpadded `10`.
            is Val.Num -> v.v.display(minFracOf(path))
            is Val.Txt -> v.v
            is Val.Bool -> v.v.toString()
            else -> d.displayValue(path)
        }
    }
    override fun cellState(path: String): CellState = when {
        path to d.repsOf(path) in poisoned -> CellState.INVALID
        isCleared(path) -> CellState.EMPTY
        overlaid(path) != null -> CellState.FILLED
        else -> d.cellState(path)
    }

    // Each row's value is the overlaid (computed) value if present, a CLEARED cascade cell is empty, a
    // POISONED cell is Val.Unknown (the compute-mode readers treat it as a poisoning read), else the stored
    // cell — so an aggregate over a computed field reads the cascade results. star = filled values;
    // starAll = row-aligned with empty/cleared rows as the kind-empty value.
    override fun starCells(path: String, bindDepth: Int): List<Pair<List<Int>, Val?>> =
        d.starCells(path, bindDepth).map { (reps, v) ->
            reps to when {
                path to reps in poisoned -> Val.Unknown
                path to reps in cleared -> null
                else -> overlay[path to reps] ?: v
            }
        }
    override fun star(path: String, bindDepth: Int): List<Val> = starCells(path, bindDepth).mapNotNull { it.second }
    override fun starAll(path: String, bindDepth: Int): List<Val> = starCells(path, bindDepth).map { (_, v) -> v ?: emptyOf(path) }
    // NOT the raw context's document-level memo (`by d` would delegate there and read stale/raw cells):
    // the overlay's column view changes as the cascade lands, so rebuild from THIS context's cells per read.
    override fun indexColumn(path: String, bindDepth: Int): IndexColumn = IndexColumn(starCells(path, bindDepth))
    override fun starScanStates(path: String, bindDepth: Int): List<CellState> {
        val states = d.starScanStates(path, bindDepth)
        val rows = d.starCells(path, bindDepth)
        return states.mapIndexed { i, s ->
            val key = path to rows[i].first
            when {
                key in poisoned -> CellState.INVALID
                key in cleared -> CellState.EMPTY
                key in overlay -> CellState.FILLED
                else -> s
            }
        }
    }

    // A `Having` filter / group-star iteration reads the cascade too: every row context is overlay-WRAPPED,
    // so the filter's reads (and the kept-row reads riding it — starOperandVals, filledFieldsValue, the fill
    // quantifiers) see an overlaid value, a cleared target as EMPTY (IF77), and a poisoned one as a
    // poisoning read (IF85) — the raw delegation read the STALE stored cell (IF98).
    override fun rowContexts(groupPath: String, bindDepth: Int): List<EvalContext> =
        d.rowContexts(groupPath, bindDepth).map { OverlayContext(it, overlay, cleared, poisoned, emptyOf, minFracOf) }

    // The `$`-join buckets must read joined values through the overlay as well — rebuilt from the wrapped
    // rows (the Document-side cache is scope-keyed on RAW reads; a cascade overlay is per-compute-pass).
    override fun correlationBuckets(groupPath: String, joinFields: List<String>, starPath: String): Map<List<Val>, IntArray> =
        correlationBucketsOf(rowContexts(groupPath), joinFields, starPath)
}
