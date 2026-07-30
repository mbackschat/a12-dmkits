package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.CacheObserver
import io.github.mbackschat.a12.dm.interpreter.CacheOwnerKind
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
    // The overlay's per-computation-step derived-view cache (IF143) + the step's OWN target column. The cache is
    // shared across the step's outer rows so an index-column / correlation-bucket view over a STABLE column is built
    // once, not rebuilt per row (O(rows²)→O(rows)); a view reading [selfTarget] (the column this step writes) is
    // NEVER cached — the overlay for that column changes as the cascade lands, so it is rebuilt fresh every read.
    private val views: OverlayViews,
    private val selfTarget: String,
) : EvalContext by d, GroupListEnvironmentProvider {
    private fun overlaid(path: String): Val? = overlay[path to d.repsOf(path)]
    private fun isCleared(path: String): Boolean = path to d.repsOf(path) in cleared
    // A POISONED cascade target (an errored value / an itself-poisoned instance) poisons EVERY read — value,
    // presence, or display (IF85; the kernel's CalculationCache.getWertXML/isEntityFilled throw for an
    // invalid-marked field). The set carries cell identity only: producer cause/payload/message is deliberately
    // unavailable to the dependent (SPEC-2026-07-26-04). A CLEARED target instead reads as EMPTY (IF77).
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
    // NOT the raw context's document-level memo (`by d` would delegate there and read stale/raw cells): the overlay's
    // column view changes as the cascade lands. But WITHIN a computation step only [selfTarget] changes, so a view
    // over any OTHER column is stable and is cached once per (column, scope) — shared across the step's outer rows
    // (IF143). Only the step's own write column rebuilds from the wrapped cells per read (never a stale cache).
    override fun indexColumn(path: String, bindDepth: Int): IndexColumn =
        if (path == selfTarget) IndexColumn(starCells(path, bindDepth))
        else views.indexColumn(path, d.scopePrefixOf(parentGroupOf(path), bindDepth)) { IndexColumn(starCells(path, bindDepth)) }
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
    override fun starScanCells(path: String, bindDepth: Int, declaredRange: Boolean): Sequence<ScanCell> =
        d.starScanCells(path, bindDepth, declaredRange).map(::overlaidScanCell)

    override fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> =
        d.containedFieldCells(groupPath, declaredRange).map(::overlaidScanCell)

    private fun overlaidScanCell(cell: ScanCell): ScanCell {
        val key = cell.path to cell.reps
        return when {
            key in poisoned -> cell.copy(raw = null, baseState = CellState.INVALID)
            key in cleared -> cell.copy(raw = null, baseState = CellState.EMPTY)
            key in overlay -> cell.copy(raw = renderOverlayValue(cell.path, overlay.getValue(key)), baseState = CellState.FILLED)
            else -> cell
        }
    }

    private fun renderOverlayValue(path: String, value: Val): String? = when (value) {
        is Val.Num -> value.v.display(minFracOf(path))
        is Val.Txt -> value.v
        is Val.Bool -> value.v.toString()
        is Val.Range -> "${value.start}/${value.end}"
        is Val.Unknown -> null
        is Val.DomainFailure -> null
    }

    // A `Having` filter / group-star iteration reads the cascade too: every row context is overlay-WRAPPED,
    // so the filter's reads (and the kept-row reads riding it — starOperandVals, filledFieldsValue, the fill
    // quantifiers) see an overlaid value, a cleared target as EMPTY (IF77), and a poisoned one as a
    // poisoning read (IF85) — the raw delegation read the STALE stored cell (IF98).
    override fun rowContexts(groupPath: String, bindDepth: Int): List<EvalContext> =
        d.rowContexts(groupPath, bindDepth).map { OverlayContext(it, overlay, cleared, poisoned, emptyOf, minFracOf, views, selfTarget) }

    override fun groupListEnvironments(groupPath: String, bindDepth: Int): List<EvalContext> =
        d.groupListEnvironments(groupPath, bindDepth)
            .map { OverlayContext(it, overlay, cleared, poisoned, emptyOf, minFracOf, views, selfTarget) }

    // The `$`-join buckets must read joined values through the overlay — rebuilt from the wrapped rows (the
    // Document-side cache is scope-keyed on RAW reads; a cascade overlay is per-compute-pass). But within a step
    // that rebuild is stable unless a join/star field IS the step's write column, so it is cached once per (group,
    // scope, join, star) and shared across the step's outer rows (IF143). A self-write join/target rebuilds fresh.
    override fun correlationBuckets(groupPath: String, joinFields: List<String>, starPath: String, bindDepth: Int): Map<List<Val>, IntArray> =
        if (starPath == selfTarget || selfTarget in joinFields)
            correlationBucketsOf(rowContexts(groupPath, bindDepth), joinFields, starPath)
        else views.correlation(groupPath, d.scopePrefixOf(groupPath, bindDepth), joinFields, starPath) {
            correlationBucketsOf(rowContexts(groupPath, bindDepth), joinFields, starPath)
        }

    override fun orderedCorrelation(
        groupPath: String,
        innerField: String,
        starPath: String,
        bindDepth: Int,
    ): OrderedCorrelation =
        if (starPath == selfTarget || innerField == selfTarget)
            OrderedCorrelation.build(rowContexts(groupPath, bindDepth), innerField, starPath)
        else views.orderedCorrelation(groupPath, d.scopePrefixOf(groupPath, bindDepth), innerField, starPath) {
            OrderedCorrelation.build(rowContexts(groupPath, bindDepth), innerField, starPath)
        }

    /** The immediate parent group of a field path (`/Ovl/Row/Src` → `/Ovl/Row`) — matches [Document]'s
     *  `parentGroup`, so the index-column scope key aligns with the raw context's `starScopePrefix`. */
    private fun parentGroupOf(fieldPath: String): String {
        val i = fieldPath.lastIndexOf('/')
        return if (i <= 0) "/" else fieldPath.substring(0, i)
    }
}

/**
 * The compute cascade's per-computation-STEP cache of the overlay's derived views — index columns
 * ([EvalContext.indexColumn]), `$` equality buckets ([EvalContext.correlationBuckets]), and `$` comparator
 * ordered tallies ([EvalContext.orderedCorrelation]). An [OverlayContext] would otherwise rebuild one of these
 * views from the wrapped cells on EVERY outer row. A computation step writes only its OWN target column, and a
 * legal computation never reads its own target — the caller passes that target as `selfTarget` and bypasses this
 * cache for it — so within one step every OTHER column is stable and a view is built once and shared across outer
 * rows. Keys mirror the raw document-level caches' column(s) + scope prefix. The cache is allocated **fresh per
 * step**, bounded by its distinct views, and never spans a step; a later step cannot read a view built against a
 * superseded overlay.
 */
internal class OverlayViews(private val observer: CacheObserver? = null) {
    private val indexColumns = HashMap<Pair<String, List<Int>>, IndexColumn>()
    private val correlations = HashMap<List<Any?>, Map<List<Val>, IntArray>>()
    private val orderedCorrelations = HashMap<List<Any?>, OrderedCorrelation>()

    fun indexColumn(path: String, scope: List<Int>, build: () -> IndexColumn): IndexColumn =
        indexColumns.getOrPut(path to scope, build).also { observe() }

    fun correlation(groupPath: String, scope: List<Int>, joinFields: List<String>, starPath: String,
                    build: () -> Map<List<Val>, IntArray>): Map<List<Val>, IntArray> =
        correlations.getOrPut(listOf(groupPath, scope, joinFields, starPath), build).also { observe() }

    fun orderedCorrelation(
        groupPath: String,
        scope: List<Int>,
        innerField: String,
        starPath: String,
        build: () -> OrderedCorrelation,
    ): OrderedCorrelation =
        orderedCorrelations.getOrPut(listOf(groupPath, scope, innerField, starPath), build).also { observe() }

    /** End the computation-step lifetime explicitly, including exceptional/early-exit paths. */
    fun release() {
        if (observer == null) return
        indexColumns.clear()
        correlations.clear()
        orderedCorrelations.clear()
        observe()
    }

    private fun observe() {
        val active = observer ?: return
        active.cacheEntries(this, CacheOwnerKind.OVERLAY_VIEWS,
            indexColumns.size + correlations.size + orderedCorrelations.size)
    }
}
