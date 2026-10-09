package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.CacheObserver
import io.github.mbackschat.a12.dm.interpreter.CacheOwnerKind
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * Decorates an [EvalContext] so reads of a field that a PRIOR computation produced return its computed
 * value (the cascade overlay) — keyed by `(path, reps)`, no document mutation. Direct `[A]` /
 * `FieldFilled(A)` reads are overlaid, AND a STARRED read (`Sum(Group* then A)`) applies the overlay per row
 * (via [EvalContext.starCells]) — so an aggregate over a computed field sums the COMPUTED values, not the
 * stored ones (KERNEL-FINDINGS §11). Every other read of cell CONTENT goes through the cascade too — group
 * presence, the group-scoped fill tally, an enumeration's category — because a content read left to the wrapped
 * context answers from the document as if nothing had been computed. The members still delegated are structural
 * (model metadata, repetition coordinates) or unreachable on the compute path. Used only by
 * `DmInterpreter.computeInstances`, so the polarity/omission reads (irrelevant to a computed value) are left
 * to the real context.
 */
internal class OverlayContext(
    private val d: EvalContext,
    private val cascade: CascadeOverlay,
    private val emptyOf: (String) -> Val,
    private val minFracOf: (String) -> Int,
    // The overlay's per-computation-step derived-view cache (IF143) + the step's OWN target column. The cache is
    // shared across the step's outer rows so an index-column / correlation-bucket view over a STABLE column is built
    // once, not rebuilt per row (O(rows²)→O(rows)); a view reading [selfTarget] (the column this step writes) is
    // NEVER cached — the overlay for that column changes as the cascade lands, so it is rebuilt fresh every read.
    private val views: OverlayViews,
    private val selfTarget: String,
) : EvalContext by d, GroupListEnvironmentProvider, DeclaredStarRowProvider {
    /**
     * What the cascade says about ONE cell, resolved with a single lookup and no allocation.
     *
     * Two earlier rounds of this: the first asked three separate questions through three helpers (three
     * `repsOf(path)` calls, three `Pair` allocations, three hashes), the second built one key and still probed
     * three collections with it — so the repetition list was still walked three times per logical read, which is
     * what the [IF285] sweep measured. [CascadeOverlay] holds one entry per cell, nested `path → reps`, so a path
     * the cascade never touched exits on a cached `String` hash without ever hashing a list or calling `repsOf`.
     *
     * A `null` entry means ABSENT — delegate to the wrapped context. The precedence that used to live in this
     * function's `when` order now lives on [CascadeOverlay]'s write side, which is where it is enforceable.
     */
    private fun entryAt(path: String): CascadeOverlay.Entry? =
        cascade.rowsAt(path)?.get(d.repsOf(path))

    // A POISONED cascade target (an errored value / an itself-poisoned instance) poisons EVERY read — value,
    // presence, or display (IF85; the kernel's CalculationCache.getWertXML/isEntityFilled throw for an
    // invalid-marked field). The entry carries cell identity only: producer cause/payload/message is deliberately
    // unavailable to the dependent (SPEC-2026-07-26-04). A CLEARED target instead reads as EMPTY (IF77).
    override fun field(path: String): Val = when (val entry = entryAt(path)) {
        null -> d.field(path)
        CascadeOverlay.Entry.Poisoned -> throw PoisonedReadException(path)
        CascadeOverlay.Entry.Cleared -> emptyOf(path)
        is CascadeOverlay.Entry.Overlaid -> entry.value
    }
    override fun filled(path: String): Boolean = when (val entry = entryAt(path)) {
        null -> d.filled(path)
        CascadeOverlay.Entry.Poisoned -> throw PoisonedReadException(path)
        CascadeOverlay.Entry.Cleared -> false
        is CascadeOverlay.Entry.Overlaid -> entry.value !is Val.Unknown
    }
    override fun displayValue(path: String): String? = when (val entry = entryAt(path)) {
        null -> d.displayValue(path)
        CascadeOverlay.Entry.Poisoned -> throw PoisonedReadException(path)
        CascadeOverlay.Entry.Cleared -> null
        // The STORED form of a computed value, so a downstream read or poison gate sees what the kernel stored:
        // a number padded to the target's minFractionalDigits (IF82/IF90), and a date rendered into its declared
        // format — a `yyyy` target holds `2024`, which its year-component and formal reads expect, not the
        // evaluation's `2024-01-01` (SPEC-2026-10-04-01, `adapter.laws.CalendarTargetCompletionDiffTest`).
        is CascadeOverlay.Entry.Overlaid -> when (val value = entry.value) {
            is Val.Num -> value.v.display(minFracOf(path))
            is Val.Txt -> if (d.declaredKind(path) == Kind.DATE)
                d.dateFormatOf(path)?.let { renderDateToFormat(value.v, it) } ?: value.v else value.v
            is Val.Bool -> value.v.toString()
            else -> d.displayValue(path)
        }
    }
    override fun cellState(path: String): CellState = when (entryAt(path)) {
        null -> d.cellState(path)
        CascadeOverlay.Entry.Poisoned -> CellState.INVALID
        CascadeOverlay.Entry.Cleared -> CellState.EMPTY
        is CascadeOverlay.Entry.Overlaid -> CellState.FILLED
    }

    // Each row's value is the overlaid (computed) value if present, a CLEARED cascade cell is empty, a
    // POISONED cell is Val.Unknown (the compute-mode readers treat it as a poisoning read), else the stored
    // cell — so an aggregate over a computed field reads the cascade results. star = filled values;
    // starAll = row-aligned with empty/cleared rows as the kind-empty value.
    override fun starCells(path: String, bindDepth: Int): List<Pair<List<Int>, Val?>> {
        // Resolved ONCE for the column, not per row: the flat form built and hashed three keys for every row of
        // every star read, which is the same cost as [entryAt]'s multiplied by the row count.
        val rows = cascade.rowsAt(path) ?: return d.starCells(path, bindDepth)
        return d.starCells(path, bindDepth).map { (reps, v) -> reps to overlaidValue(rows[reps], v) }
    }

    /** One cell's value as the tallying readers see it: a POISONED cell reads [Val.Unknown], which every one of
     *  them treats as a poisoning read, and a CLEARED one reads empty. */
    private fun overlaidValue(entry: CascadeOverlay.Entry?, stored: Val?): Val? = when (entry) {
        null -> stored
        CascadeOverlay.Entry.Poisoned -> Val.Unknown
        CascadeOverlay.Entry.Cleared -> null
        is CascadeOverlay.Entry.Overlaid -> entry.value
    }

    // The group-scoped fill tally, read through the cascade by the wrapped context's own scope rule, so a group
    // count over a computed field counts what was computed (the kernel's `isEntityFilled` answers from its
    // calculated values first).
    override fun fillScope(scopePath: String, isGroup: Boolean, bindDepth: Int): FillScope =
        d.fillScope(scopePath, isGroup, bindDepth) { path, reps, stored -> overlaidValue(cascade.rowsAt(path)?.get(reps), stored) }

    override fun fillScope(scopePath: String, isGroup: Boolean, bindDepth: Int, cellValue: CellValueSource): FillScope =
        d.fillScope(scopePath, isGroup, bindDepth) { path, reps, stored ->
            cellValue.valueOf(path, reps, overlaidValue(cascade.rowsAt(path)?.get(reps), stored))
        }

    // A group's presence also reads the cascade: a value computed so far fills the non-repeatable groups above it.
    // The document half holds no computed field's stored value (ComputationEngine strips them before the cascade),
    // so a cleared or poisoned cell fills nothing and a group read never poisons (CascadeGroupPresenceDiffTest).
    override fun groupFilled(groupPath: String): Boolean =
        d.groupFilled(groupPath) || cascade.fillsGroup(groupPath) { path -> d.repsOf(path).take(d.boundLevelsOf(path)) }

    override fun enumCategory(path: String, category: String): Val = when (val entry = entryAt(path)) {
        null -> d.enumCategory(path, category)
        CascadeOverlay.Entry.Poisoned -> throw PoisonedReadException(path)
        CascadeOverlay.Entry.Cleared -> Val.Unknown   // an empty field has no category (EvalContext.enumCategory)
        is CascadeOverlay.Entry.Overlaid -> d.enumCategoryValue(path, category, entry.value)
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
        // Same column-once resolution as [starCells], and the same reason: this ran three key hashes per row.
        val entries = cascade.rowsAt(path) ?: return states
        val rows = d.starCells(path, bindDepth)
        return states.mapIndexed { i, state ->
            when (entries[rows[i].first]) {
                null -> state
                CascadeOverlay.Entry.Poisoned -> CellState.INVALID
                CascadeOverlay.Entry.Cleared -> CellState.EMPTY
                is CascadeOverlay.Entry.Overlaid -> CellState.FILLED
            }
        }
    }
    override fun starScanCells(path: String, bindDepth: Int, declaredRange: Boolean): Sequence<ScanCell> =
        d.starScanCells(path, bindDepth, declaredRange).map(::overlaidScanCell)

    override fun containedFieldCells(groupPath: String, declaredRange: Boolean): Sequence<ScanCell> =
        d.containedFieldCells(groupPath, declaredRange).map(::overlaidScanCell)
    override fun indexedGroupRow(
        groupPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): EvalContext? = d.indexedGroupRow(groupPath, indexField, indexValue, indexIdentityAdmitted)
        ?.let { OverlayContext(it, cascade, emptyOf, minFracOf, views, selfTarget) }

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

    override fun indexPointerRepetitions(
        targetPath: String,
        indexField: String,
        indexValue: String,
        indexIdentityAdmitted: ((String, List<Int>, String) -> Boolean)?,
    ): List<Int>? = d.indexPointerRepetitions(targetPath, indexField, indexValue, indexIdentityAdmitted)

    override fun indexedValueAt(targetPath: String, repetitions: List<Int>): Val? =
        when (val entry = cascade.rowsAt(targetPath)?.get(repetitions)) {
            null -> d.indexedValueAt(targetPath, repetitions)
            CascadeOverlay.Entry.Poisoned -> throw PoisonedReadException(targetPath)
            CascadeOverlay.Entry.Cleared -> null
            is CascadeOverlay.Entry.Overlaid -> entry.value
        }

    private fun overlaidScanCell(cell: ScanCell): ScanCell =
        when (val entry = cascade.rowsAt(cell.path)?.get(cell.reps)) {
            null -> cell
            CascadeOverlay.Entry.Poisoned -> cell.copy(raw = null, baseState = CellState.INVALID, value = Val.Unknown)
            CascadeOverlay.Entry.Cleared -> cell.copy(raw = null, baseState = CellState.EMPTY, value = null)
            is CascadeOverlay.Entry.Overlaid -> cell.copy(
                raw = renderOverlayValue(cell.path, entry.value),
                baseState = CellState.FILLED,
                value = entry.value,
            )
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
        // what is cached here is the WRAPPERS, not values. An `OverlayContext` holds a reference to the live
        // cascade overlay and consults it on every read, so a wrapper handed out early in a
        // step returns the cascade's latest answer just as a freshly built one would. That is why this needs no
        // `selfTarget` bypass, which every value-caching view above does need.
        //
        // Keyed on the scope prefix rather than the raw bind depth for the same reason the correlation cache is:
        // different outer rows resolve the same `bindDepth` to different row sets, so the depth alone would hand
        // one row's contexts to another. Worth doing because `scanFilled` asks for these per outer row, so the
        // wrapper allocation was O(rows) per row — 13.3 % of samples on the compute-overlay correlation profile.
        views.rowContexts(groupPath, d.scopePrefixOf(groupPath, bindDepth)) {
            d.rowContexts(groupPath, bindDepth).map {
                OverlayContext(it, cascade, emptyOf, minFracOf, views, selfTarget)
            }
        }

    // A filtered declared-range fill scan selects among missing declared rows too (SPEC-2026-10-04-05); they are
    // wrapped like stored ones so a filter reads the cascade there as well.
    override fun declaredStarRows(fieldPath: String, bindDepth: Int): Sequence<EvalContext> =
        d.declaredStarRows(fieldPath, bindDepth).map { OverlayContext(it, cascade, emptyOf, minFracOf, views, selfTarget) }

    override fun groupListEnvironments(groupPath: String, bindDepth: Int): List<EvalContext> =
        d.groupListEnvironments(groupPath, bindDepth)
            .map { OverlayContext(it, cascade, emptyOf, minFracOf, views, selfTarget) }

    // Not covered by `by d`, which forwards EvalContext alone: without this the provider's null default answers.
    override fun declaredGroupListExtent(groupPath: String, bindDepth: Int): Int? =
        d.declaredGroupListExtent(groupPath, bindDepth)

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
     * value: a wrapper reads the live cascade overlay on every access, so sharing one across a step's outer
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
