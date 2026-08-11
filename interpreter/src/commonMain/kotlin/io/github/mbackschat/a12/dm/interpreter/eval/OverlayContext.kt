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
    /**
     * What the cascade says about ONE cell, resolved with a single key.
     *
     * Every public read below needs all three answers — poisoned, cleared, overlaid — and each used to ask its
     * own question through its own helper: three `repsOf(path)` calls, three `Pair` allocations and three hashes
     * of a `Pair<String, List<Int>>` for one logical read. The overlay is on the hot path of every compute
     * cascade, and JFR duly found `overlaid` at 5.6 % of samples with `Pair.hashCode` and `ArrayList.hashCode`
     * under it. One key, asked once, is the same answer at a third of the cost.
     *
     * The order is load-bearing and matches what it replaced: poisoned beats cleared beats overlaid.
     */
    private enum class CascadeState { POISONED, CLEARED, OVERLAID, ABSENT }

    private fun stateOf(path: String): Pair<CascadeState, Val?> {
        // `repsOf` is itself a map lookup, so it is asked once and the key built once.
        val key = path to d.repsOf(path)
        return when {
            key in poisoned -> CascadeState.POISONED to null
            key in cleared -> CascadeState.CLEARED to null
            else -> overlay[key]?.let { CascadeState.OVERLAID to it } ?: (CascadeState.ABSENT to null)
        }
    }

    // A POISONED cascade target (an errored value / an itself-poisoned instance) poisons EVERY read — value,
    // presence, or display (IF85; the kernel's CalculationCache.getWertXML/isEntityFilled throw for an
    // invalid-marked field). The set carries cell identity only: producer cause/payload/message is deliberately
    // unavailable to the dependent (SPEC-2026-07-26-04). A CLEARED target instead reads as EMPTY (IF77).
    override fun field(path: String): Val {
        val (state, value) = stateOf(path)
        return when (state) {
            CascadeState.POISONED -> throw PoisonedReadException(path)
            CascadeState.CLEARED -> emptyOf(path)
            CascadeState.OVERLAID -> value!!
            CascadeState.ABSENT -> d.field(path)
        }
    }
    override fun filled(path: String): Boolean {
        val (state, value) = stateOf(path)
        return when (state) {
            CascadeState.POISONED -> throw PoisonedReadException(path)
            CascadeState.CLEARED -> false
            CascadeState.OVERLAID -> value !is Val.Unknown
            CascadeState.ABSENT -> d.filled(path)
        }
    }
    override fun displayValue(path: String): String? {
        val (state, value) = stateOf(path)
        return when (state) {
            CascadeState.POISONED -> throw PoisonedReadException(path)
            CascadeState.CLEARED -> null
            // The STORED form of a computed number (padded to the target's minFractionalDigits, IF82/IF90) —
            // so a downstream poison gate sees the same value the kernel stored, not an unpadded `10`.
            CascadeState.OVERLAID -> when (value) {
                is Val.Num -> value.v.display(minFracOf(path))
                is Val.Txt -> value.v
                is Val.Bool -> value.v.toString()
                else -> d.displayValue(path)
            }
            CascadeState.ABSENT -> d.displayValue(path)
        }
    }
    override fun cellState(path: String): CellState = when (stateOf(path).first) {
        CascadeState.POISONED -> CellState.INVALID
        CascadeState.CLEARED -> CellState.EMPTY
        CascadeState.OVERLAID -> CellState.FILLED
        CascadeState.ABSENT -> d.cellState(path)
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
    override fun indexColumnCacheable(path: String): Boolean = path != selfTarget
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
    override fun indexedContainedFieldCells(
        groupPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): Sequence<ScanCell> =
        d.indexedContainedFieldCells(groupPath, indexField, indexValue, indexIdentityAdmitted).map(::overlaidScanCell)

    override fun indexedCell(
        targetPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): Val? {
        val repetitions = indexedCellRepetitions(targetPath, indexField, indexValue, indexIdentityAdmitted)
            ?: return null
        return indexedValueAt(targetPath, repetitions)
    }

    override fun indexedCellRepetitions(
        targetPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): List<Int>? = d.indexedCellRepetitions(targetPath, indexField, indexValue, indexIdentityAdmitted)

    override fun indexedValueAt(targetPath: String, repetitions: List<Int>): Val? {
        val key = targetPath to repetitions
        if (key in poisoned) throw PoisonedReadException(targetPath)
        if (key in cleared) return null
        return overlay[key] ?: d.indexedValueAt(targetPath, repetitions)
    }

    private fun overlaidScanCell(cell: ScanCell): ScanCell {
        val key = cell.path to cell.reps
        return when {
            key in poisoned -> cell.copy(raw = null, baseState = CellState.INVALID, value = Val.Unknown)
            key in cleared -> cell.copy(raw = null, baseState = CellState.EMPTY, value = null)
            key in overlay -> cell.copy(
                raw = renderOverlayValue(cell.path, overlay.getValue(key)),
                baseState = CellState.FILLED,
                value = overlay.getValue(key),
            )
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
        // Shared across the step's outer rows, like the derived views below — and SAFER than any of them, because
        // what is cached here is the WRAPPERS, not values. An `OverlayContext` holds references to the live
        // overlay/cleared/poisoned collections and consults them on every read, so a wrapper handed out early in a
        // step returns the cascade's latest answer just as a freshly built one would. That is why this needs no
        // `selfTarget` bypass, which every value-caching view above does need.
        //
        // Keyed on the scope prefix rather than the raw bind depth for the same reason the correlation cache is:
        // different outer rows resolve the same `bindDepth` to different row sets, so the depth alone would hand
        // one row's contexts to another. Worth doing because `scanFilled` asks for these per outer row, so the
        // wrapper allocation was O(rows) per row — 13.3 % of samples on the compute-overlay correlation profile.
        views.rowContexts(groupPath, d.scopePrefixOf(groupPath, bindDepth)) {
            d.rowContexts(groupPath, bindDepth).map {
                OverlayContext(it, overlay, cleared, poisoned, emptyOf, minFracOf, views, selfTarget)
            }
        }

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
    private val rowContexts = HashMap<Pair<String, List<Int>>, List<EvalContext>>()

    /**
     * The overlay-WRAPPED row contexts of one group in one scope. Unlike the derived views below this caches no
     * value: a wrapper reads the live cascade collections on every access, so sharing one across a step's outer
     * rows cannot serve a stale answer — it only avoids rebuilding the wrappers.
     */
    fun rowContexts(groupPath: String, scope: List<Int>, build: () -> List<EvalContext>): List<EvalContext> =
        rowContexts.getOrPut(groupPath to scope, build).also { observe() }

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
        rowContexts.clear()
        observe()
    }

    private fun observe() {
        val active = observer ?: return
        active.cacheEntries(this, CacheOwnerKind.OVERLAY_VIEWS,
            indexColumns.size + correlations.size + orderedCorrelations.size + rowContexts.size)
    }
}
