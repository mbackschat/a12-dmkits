package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.ast.typedValue
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData
import io.github.mbackschat.a12.dm.interpreter.model.DatePrecision
import io.github.mbackschat.a12.dm.interpreter.model.FieldPointer
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ValuedField
import kotlin.jvm.JvmOverloads

/**
 * The repetition index a parallel-iteration pointer carries for a group that has **no row** for the current
 * index value (the outer-join "not specified" side) — the kernel's `IIdentifier.UNKNOWN`, a fixed sentinel, so
 * the rendered pointer (`…/Demand[-5]/Headcount`) matches the kernel bit-for-bit (KERNEL-FINDINGS §9).
 */
internal const val UNKNOWN_REP = -5

/**
 * A general document instance the interpreter walks — NO kernel types. Addressed exactly like the
 * kernel: every placement carries **one 1-based repetition index per path part** (group AND field), so
 * `/Order/Items/Count` row 2 is `field("/Order/Items/Count", "80", 1, 2, 1)`. This subsumes the flat
 * (single-row) and one-level-repetition shapes the spike's [SingleRowDoc]/[RepDoc] handle; it produces
 * one [EvalContext] per instantiated row of a rule's iteration scope via [contextsFor].
 *
 * Field reference resolution within a row context follows the **common-prefix rule**: a referenced
 * path P shares a prefix with the iteration group G, takes the row's index for each shared group part,
 * and index 1 below the divergence — so an outer field, a same-row field, and a nested non-repeatable
 * field all resolve correctly. A starred aggregate collects the field across every row of its deepest
 * repeatable group ancestor.
 *
 * Building a field auto-instantiates its ancestor groups (mirrors the kernel's instance model).
 *
 * **Performance (INTERPRETER-PERFORMANCE.md).** Placement lookups are **indexed**, not linear scans: an
 * immutable [Index] — built once on first query, invalidated on mutation — maps `(path, reps) → value` and
 * `groupPath → rows` so `valueAt`/`rowsOf`/`star` are O(1)/O(rows), not O(P). `contextsFor` computes each
 * row's `evaluated` flag in ONE amortized pass over the valued fields (not an O(P) scan per row), and group
 * auto-instantiation dedupes via a set (not an O(P²) build scan). Net: per-row evaluation is ~O(rows · refs),
 * not O(rows · placements).
 */
class Document @JvmOverloads constructor(
    private val kinds: Map<String, Kind>,
    private val declaredReps: Map<String, Int> = emptyMap(),
    /** NUMBER fields that are *unsigned* (the kernel's `hatVorzeichen == false` — a non-negative `minValue`);
     *  drives the directional empty-operand polarity ([EvalContext.signed]). Default: all fields signed. */
    private val unsignedFields: Set<String> = emptySet(),
    /** Each [Kind.DATE_RANGE] field's `(format, rangeSeparator)` — lets [resolveValue] canonicalize a rendered
     *  range value (`01.06.2021-30.06.2021`) to a [Val.Range] of ISO dates, format-aware (locale variants). */
    private val rangeConfig: Map<String, Pair<String, String>> = emptyMap(),
    /** Each [Kind.DATE] field's rendering format (the kernel's `DateType.format`) — lets [resolveValue]
     *  canonicalize a rendered date (`15.06.2024` for `dd.MM.yyyy`) to its ISO form, format-aware. Missing/ISO
     *  fields stay ISO. */
    private val dateFormats: Map<String, String> = emptyMap(),
    /** Each NUMBER field's scale (`maxFractionalDigits`) — lets [resolveValue] formal-check a value so a
     *  formally-invalid one (signed-zero, too-many-decimals, …) resolves to [Val.Unknown] even when it parses. */
    private val scales: Map<String, Int?> = emptyMap(),
    /** Each [Kind.DATE_TIME] field's rendering format (the kernel's `DateTimeType.format`) — lets [resolveValue]
     *  canonicalize a rendered date-time to the ISO `yyyy-MM-dd'T'HH:mm:ss` form, format-aware. ISO default when
     *  unset. (Appended after [scales] to keep the positional constructor compatible with existing callers.) */
    private val dateTimeFormats: Map<String, String> = emptyMap(),
    /** Each [Kind.DATE] field's [DatePrecision] — a `DAY_OPTIONAL` field accepts a day-omitted `00.MM.yyyy`
     *  value (well-formed, but resolved to [Val.Unknown] directly; `ValueAsDate` resolves it). Default FULL.
     *  (Appended for positional-constructor compatibility.) */
    private val datePrecisions: Map<String, DatePrecision> = emptyMap(),
    /** Each enumeration field's XML value list (declaration order) — the index domain for `EnumCategory`
     *  (`[Field -> Category]`) and the membership check. (Appended for positional-constructor compatibility.) */
    private val enumValues: Map<String, List<String>> = emptyMap(),
    /** Each enumeration field's category map (name → positional value list) — the `EnumCategory` mapping:
     *  the stored value's index in [enumValues] selects the category's value. (Appended for positional compat.) */
    private val enumCategories: Map<String, Map<String, List<String>>> = emptyMap(),
    /** Each [Kind.TIME] field's rendering format (the kernel's `TimeType.format`, e.g. `HH:mm:ss`) — lets
     *  [resolveValue] formal-check a TIME so a malformed value resolves to [Val.Unknown] (a malformed time was
     *  previously unchecked → it escaped suppression; KERNEL-FINDINGS §6). (Appended for positional compat.) */
    private val timeFormats: Map<String, String> = emptyMap(),
) {
    private data class Placement(val path: String, val reps: List<Int>, val value: String?, val isGroup: Boolean)

    private val placements = mutableListOf<Placement>()
    /** Group `(path, reps)` already instantiated — O(1) dedup for [autoGroup] (avoids an O(P²) build scan). */
    private val instantiatedGroups = HashSet<Pair<String, List<Int>>>()

    /** A field with a value at the given 1-based indices (one per path part, the field included). */
    fun field(path: String, value: String, vararg reps: Int): Document = add(path, reps, value, false)

    /** A present-but-valueless (unfilled) field. */
    fun emptyField(path: String, vararg reps: Int): Document = add(path, reps, null, false)

    /** A group row at the given 1-based indices (one per path part). */
    fun group(path: String, vararg reps: Int): Document = add(path, reps, null, true)

    private fun add(path: String, reps: IntArray, value: String?, isGroup: Boolean): Document {
        val r = reps.toList()
        if (isGroup) {
            if (instantiatedGroups.add(path to r)) placements += Placement(path, r, value, true)
        } else {
            ancestorGroups(path).forEachIndexed { i, g -> autoGroup(g, r.take(i + 1)) }
            placements += Placement(path, r, value, false)
        }
        cachedIndex = null   // invalidate the lazily-built lookup index
        return this
    }

    private fun autoGroup(path: String, reps: List<Int>) {
        if (instantiatedGroups.add(path to reps)) placements += Placement(path, reps, null, true)
    }

    // ---- lookup index (built once on first query, invalidated on mutation) ----------------------------

    private class Index(
        // Non-group placements, FIRST per key wins. Split by path (path → reps → placement) rather than a flat
        // `Map<Pair<String, List<Int>>, _>`: `valueAt` is on the hottest path, and the nested form drops the
        // per-lookup `Pair` allocation + `Pair.equals`/`hashCode` (the JFR profile's largest residual cluster) —
        // the outer `String` lookup is cheap; only the reps are hashed inside.
        val valueByKey: Map<String, Map<List<Int>, Placement>>,
        val rowsByGroup: Map<String, List<List<Int>>>,             // group path → distinct reps, document order
        val valued: List<Placement>,                               // valued (value != null) non-group placements
        val partsByPath: Map<String, List<String>>,                // memoized path split
    ) {
        // Per-group memos, deterministic for this (immutable) index, rebuilt with it on mutation. They turn the
        // O(valued) attach-depth scan + row mapping — which [contextsFor]/[rowContextsScoped] otherwise redo on
        // EVERY rule (and every nested row) — into once-per-distinct-group work. This is the lever that keeps the
        // interpreter on-par with the kernel at high rule counts (many rules share a handful of iteration scopes;
        // the kernel computes the row structure once and reuses it across rules — this is the direct analog).
        val attachByGroup: MutableMap<String, Map<Int, Set<List<Int>>>> = HashMap()
        val contextsByGroup: MutableMap<String, List<EvalContext>> = HashMap()
        // Per-group rows indexed by EVERY prefix of their rep tuple (built once on first use): `prefix → rows
        // whose reps start with it`. This replaces [starRows]'s O(all-rows) `.filter` per call with an O(1)
        // lookup — the lever that kills the **O(rows²)** in semantic-index lookups (`[F For "v"]`) and
        // cross-level star aggregates evaluated per row (the kernel's IndexFieldCache / row-index analog).
        // Space is O(rows × depth) (depth ≤ ~4), built only for the groups actually star-resolved.
        val prefixRowsByGroup: MutableMap<String, Map<List<Int>, List<List<Int>>>> = HashMap()
        // `GroupFilled(nonRepeatableGroup)` support: per (groupPath, iteration-group), the attach-depth →
        // prefix-set of valued DESCENDANTS of groupPath — the GroupFillCache analog for the non-repeatable
        // branch. Mirrors [attachByGroup] but filtered to groupPath's subtree, so `groupFilled` is O(1) per
        // call after a once-per-(groupPath,group) O(valued) build, not an O(valued) scan every row.
        val nonRepFilledByGroup: MutableMap<Pair<String, String>, Map<Int, Set<List<Int>>>> = HashMap()
    }

    private var cachedIndex: Index? = null

    private fun index(): Index = cachedIndex ?: build().also { cachedIndex = it }

    private fun build(): Index {
        val byKey = HashMap<String, HashMap<List<Int>, Placement>>()
        val rows = LinkedHashMap<String, MutableList<List<Int>>>()
        val valued = ArrayList<Placement>()
        val partsMemo = HashMap<String, List<String>>()
        for (p in placements) {
            partsMemo.getOrPut(p.path) { parts(p.path) }
            if (p.isGroup) {
                // No `!in` dedup needed: every group placement is already unique by (path, reps) — both
                // group() and autoGroup() gate on the `instantiatedGroups` set before adding. The old
                // membership check was an O(rows) List scan per group → an O(N²) index build that the WARM
                // perf harness never saw (build runs once, before timing) but every COLD first validateFull
                // pays — the startup / CLI-one-shot path, and the reason a single JS pass blew up at scale.
                rows.getOrPut(p.path) { ArrayList() }.add(p.reps)
            } else {
                byKey.getOrPut(p.path) { HashMap() }.getOrPut(p.reps) { p }   // FIRST placement per (path,reps) wins
                if (p.value != null) valued.add(p)
            }
        }
        return Index(byKey, rows, valued, partsMemo)
    }

    private fun partsOf(path: String): List<String> = index().partsByPath[path] ?: parts(path)

    /**
     * Every present (valued) field instance — its path, 1-based repetition indices, and raw value — in
     * document order. The document-wide formal-error check ([DmInterpreter.validate]) walks these: the
     * kernel formal-checks *all* filled fields, not only the rule-referenced ones. Unfilled (empty) fields
     * and groups are excluded — emptiness is a requiredness concern, not a format one.
     */
    fun valuedFields(): List<ValuedField> =
        index().valued.map { ValuedField(it.path, it.reps, it.value!!) }

    /**
     * A copy of this document with computed values applied — the interpreter's twin of the kernel's
     * `IDocumentComputationResult.applyTo` (KERNEL-FINDINGS "validateFull does NOT run computations"): each
     * [overrides] entry's value REPLACES the cell at `(path, reps)`; a `null` value CLEARS it. (The kernel
     * applies the `WithChanges` values and clears both the `WithErrors` and the `Cleared` instances, so an
     * errored/cleared computation maps to a `null` override here.) The original document is unchanged.
     *
     * Used by [DmInterpreter.applyComputations] to materialize the compute-applied document for the
     * form-engine validation flow (compute → apply → validate); re-adding through the public builders keeps the
     * indices/auto-grouping invariants and document order intact.
     */
    internal fun withComputed(overrides: Map<Pair<String, List<Int>>, String?>): Document {
        val copy = emptyCopy()
        val applied = HashSet<Pair<String, List<Int>>>()
        for (p in placements) {
            if (p.isGroup) {
                copy.group(p.path, *p.reps.toIntArray())
                continue
            }
            val key = p.path to p.reps
            when {
                key in overrides -> {
                    applied += key
                    overrides[key]?.let { copy.field(p.path, it, *p.reps.toIntArray()) }
                        ?: copy.emptyField(p.path, *p.reps.toIntArray())
                }
                p.value != null -> copy.field(p.path, p.value, *p.reps.toIntArray())
                else -> copy.emptyField(p.path, *p.reps.toIntArray())
            }
        }
        // A value computed into a cell with no prior placement (an empty computed field never present as a
        // placement): add it. A null override (clear) for an absent cell is a no-op.
        for ((key, value) in overrides) {
            if (key !in applied && value != null) copy.field(key.first, value, *key.second.toIntArray())
        }
        return copy
    }

    /** An empty document carrying this one's model configuration (kinds/reps/formats/…) but no placements —
     *  the single place the config param list is threaded, so [withComputed] stays in lockstep with the
     *  constructor. */
    private fun emptyCopy(): Document = Document(kinds, declaredReps, unsignedFields, rangeConfig, dateFormats,
        scales, dateTimeFormats, datePrecisions, enumValues, enumCategories, timeFormats)

    /**
     * One [EvalContext] per instantiated row of [groupPath]; empty if the group has no rows. Each row's
     * `evaluated` flag is computed here in a single amortized pass over the valued fields: a row is
     * evaluated iff some valued field is in its scope, i.e. its index-prefix matches the field at the depth
     * where the field's path diverges from the group (`common`). Recording each valued field's prefix at its
     * attach-depth turns the per-row "is anything in scope?" check into a set lookup.
     */
    fun contextsFor(groupPath: String): List<EvalContext> =
        index().contextsByGroup.getOrPut(groupPath) { computeContextsFor(groupPath) }

    private fun computeContextsFor(groupPath: String): List<EvalContext> {
        val rows = rowsOf(groupPath)
        if (rows.isEmpty()) return emptyList()
        val attach = attachByDepth(groupPath)
        return rows.map { reps ->
            RowContext(groupPath, reps, attach.any { (depth, prefixes) -> reps.take(depth) in prefixes })
        }
    }

    /**
     * **Parallel iteration** — joint iteration of sibling repeatable [groups] (neither nested in the other),
     * keyed by their shared **index field** [indexFieldName], within each row of their deepest common ancestor
     * [commonParent]. The kernel evaluates this as an *outer join over the index VALUES* (KERNEL-SEMANTICS §9 /
     * KERNEL-FINDINGS §9 — its `IndexFieldCache`): for each value present in ANY group, each group resolves to
     * the row whose index cell equals that value, or counts as **not specified** when it has no such row. One
     * [EvalContext] per (common-parent row × index value) is produced; a group with no matching row resolves its
     * fields empty (and its pointer level to [UNKNOWN_REP], -5).
     *
     * The iteration value set is the **union of the groups' index-field values** within each common-parent row
     * (the kernel's `ValidationCache.getIterationValuesForGroups` — the per-group value→repetition map keys,
     * last write winning on a duplicate, empty cells excluded). Each value resolves each group to the row that
     * carries it, or **not specified** when the group has no row for it.
     *
     * **Invalid index → 3VL (KERNEL-FINDINGS §9):** a group whose index is *invalid* here (a duplicate value or
     * an empty cell — `invalidIndexGroups`) reads its **unmatched** references as 3-valued **UNKNOWN**, not
     * "empty" — matching the kernel's behavior, which suppresses a negative operator like
     * `FieldsNotCollectivelyFilled` on the compromised side. A *valid* group's unmatched reference is definite
     * "empty". A *matched* reference always resolves to its row (definite), regardless of group validity. The
     * duplicate/empty rows additionally carry the auto `uniqueIndex`/`mandatoryField` errors (emitted
     * independently). Iteration order is irrelevant (the message multiset is compared).
     *
     * **Frame (a nested repeatable above one joined group):** when one joined group [frameGroup] sits under a
     * repeatable non-indexed ancestor [deepestFrame] (between [commonParent] and it), the kernel iterates that
     * frame normally and does the join INSIDE each frame row (KERNEL-FINDINGS §9 — the `/Plan/X/A ⋈ B` case):
     * the framed group's rows + index values are scoped to the current frame row, while the other (clean) joined
     * groups stay shared under [commonParent]. With no frame ([deepestFrame] null) this reduces to one join per
     * common-parent row.
     */
    fun parallelContexts(groups: List<String>, indexFieldName: String, commonParent: String,
            frameGroup: String? = null, deepestFrame: String? = null): List<EvalContext> {
        val out = ArrayList<EvalContext>()
        for (cReps in parentRowsOf(commonParent)) {
            // The NON-framed (clean) groups are shared across every frame row — build their value maps + invalid
            // flags ONCE per common-parent row, NOT per frame row (else a clean group of M rows is rescanned per
            // frame row → O(frames × M)). Only the framed group's map depends on the current frame row.
            val cleanInvalid = HashSet<String>()
            val cleanMaps = groups.filter { it != frameGroup }.associateWith { indexMap(it, indexFieldName, cReps, cleanInvalid) }
            val frameRows = if (deepestFrame != null) rowsWithPrefix(deepestFrame, cReps) else listOf(cReps)
            for (fr in frameRows) {
                // each group's parent context: the framed group is scoped to the current frame row, others to cReps
                val parentReps = groups.associateWith { g -> if (g == frameGroup) fr else cReps }
                val invalid = HashSet(cleanInvalid)
                val perGroup = if (frameGroup == null) cleanMaps
                    else cleanMaps + (frameGroup to indexMap(frameGroup, indexFieldName, fr, invalid))
                val values = LinkedHashSet<String>().apply { groups.forEach { addAll(perGroup.getValue(it).keys) } }
                for (v in values) {
                    out += ParallelRowContext(commonParent, cReps, groups, parentReps,
                        groups.associateWith { perGroup.getValue(it)[v] }, invalid)
                }
            }
        }
        return out
    }

    /** Common-parent context rows for a parallel iteration: the parent group's instantiated rows, or the all-1s
     *  prefix when a non-repeatable parent is not separately instantiated. */
    private fun parentRowsOf(commonParent: String): List<List<Int>> =
        rowsOf(commonParent).ifEmpty {
            if (commonParent in declaredReps) emptyList() else listOf(List(parts(commonParent).size) { 1 })
        }

    /** [group]'s `indexValue → matching-row reps` map under parent context [ctxReps] (last write wins on a
     *  duplicate); adds [group] to [invalidOut] if its index has a duplicate value or an empty cell here. */
    private fun indexMap(group: String, indexFieldName: String, ctxReps: List<Int>, invalidOut: MutableSet<String>): Map<String, List<Int>> {
        val idxPath = "$group/$indexFieldName"
        val byValue = LinkedHashMap<String, List<Int>>()
        for (row in rowsWithPrefix(group, ctxReps)) {
            val v = valueAt(idxPath, row + 1)?.value
            if (v.isNullOrEmpty()) invalidOut.add(group)                    // empty index cell → group invalid here
            else if (byValue.put(v, row) != null) invalidOut.add(group)     // duplicate value → group invalid here
        }
        return byValue
    }

    /**
     * The per-group attach-depth index: `attach-depth → the set of row index-prefixes that have a valued field
     * in scope at that depth`. The O(valued) scan that builds it is **memoized per group** (rebuilt with the
     * [Index] on mutation), so many rules sharing one iteration scope — and nested per-row [rowContextsScoped]
     * lookups — pay it once, not per rule/row. A row is `evaluated` iff some prefix matches.
     */
    private fun attachByDepth(groupPath: String): Map<Int, Set<List<Int>>> =
        index().attachByGroup.getOrPut(groupPath) {
            val gParts = partsOf(groupPath)
            val m = HashMap<Int, HashSet<List<Int>>>()
            for (p in index().valued) {
                val common = commonPrefixLen(partsOf(p.path), gParts)
                m.getOrPut(common) { HashSet() }.add(p.reps.take(common))
            }
            m
        }

    /**
     * For `groupFilled(nonRepeatableGroupPath)` evaluated from iteration group [group]: `attach-depth →
     * prefix-set` over the valued **descendants of [groupPath]** (paths under `groupPath/`). Memoized per
     * (groupPath, group) in the [Index]. A row's instance of [groupPath] is filled iff `rowReps.take(d)` is in
     * the set at some depth `d` — which mirrors the per-descendant `commonPrefixLen(p.path, group)` shared-index
     * scope check exactly, only grouped so it is computed once per (groupPath, group) instead of scanned per row.
     */
    private fun nonRepGroupFilledDepths(groupPath: String, group: String): Map<Int, Set<List<Int>>> =
        index().nonRepFilledByGroup.getOrPut(groupPath to group) {
            val gParts = partsOf(group)
            val prefix = "$groupPath/"
            val m = HashMap<Int, HashSet<List<Int>>>()
            for (p in index().valued) {
                if (!p.path.startsWith(prefix)) continue
                val common = commonPrefixLen(partsOf(p.path), gParts)
                m.getOrPut(common) { HashSet() }.add(p.reps.take(common))
            }
            m
        }

    /** The distinct 1-based index tuples at which [groupPath] is instantiated, in document order. */
    private fun rowsOf(groupPath: String): List<List<Int>> = index().rowsByGroup[groupPath] ?: emptyList()

    /** [EvalContext]s for the rows of [groupPath] whose reps extend [scopeReps] (the rows under a given parent
     *  row) — the scoped form of [contextsFor], for a `Having` per-row filter. Uses the **prefix index**
     *  ([rowsWithPrefix], O(in-scope rows)) rather than scanning ALL rows of [groupPath] and prefix-filtering:
     *  the `Having` clause asks for this per parent scope, so the old O(all-rows) scan was O(scopes·rows) =
     *  quadratic on a deep nested doc (the `deepRichScaling` cliff — JFR-confirmed). The prefix map is built
     *  once per group; document order is preserved (the same rows the filter returned). */
    private fun rowContextsScoped(groupPath: String, scopeReps: List<Int>): List<EvalContext> {
        val attach = attachByDepth(groupPath)   // memoized per group — no O(valued) rescan per nested row
        return rowsWithPrefix(groupPath, scopeReps)
            .map { reps -> RowContext(groupPath, reps, attach.any { (d, pfx) -> reps.take(d) in pfx }) }
    }

    /** Indices for a referenced path P under a row of group G: shared-prefix indices from the row, else 1. */
    private fun repsFor(refPath: String, group: String, rowReps: List<Int>): List<Int> {
        val refParts = partsOf(refPath)
        val common = commonPrefixLen(refParts, partsOf(group))
        return refParts.indices.map { i -> if (i < common) rowReps[i] else 1 }
    }

    private fun valueAt(path: String, reps: List<Int>): Placement? = index().valueByKey[path]?.get(reps)

    /**
     * Whether the field instance at [path]/[reps] carries a value — the requiredness check for an
     * **unconditionally** required field ([DmInterpreter]), which the kernel flags at the canonical all-1s
     * instance even on an otherwise un-instantiated document (so it can't go through [contextsFor]).
     */
    fun filledAt(path: String, reps: List<Int>): Boolean = valueAt(path, reps)?.value != null

    private inner class RowContext(
        private val group: String,
        private val rowReps: List<Int>,
        override val evaluated: Boolean,
    ) : EvalContext {

        /**
         * Resolve a referenced field to its placement (or null) ONCE per path, cached for this row context —
         * the kernel's single-resolution `vergleiche`/`feldWertAlsZahl` analog. `field`, `filled`, and
         * `displayValue` all resolved the SAME `(path, reps)` independently (each `repsFor` allocating a reps
         * list + a `valueAt` map lookup), so a comparison's field operand was resolved ~2× per row — and the
         * row contexts are reused across every rule iterating this scope, so caching pays across all of them.
         * The document is immutable during a validation, so the placement is stable (correct for full + partial).
         */
        // The reps (1-based repetition indices) of a referenced path in THIS row context — `repsFor` is
        // deterministic in (path, group, rowReps), and group/rowReps are fixed here, so memoize by path. The
        // profile (#64) put `repsFor` + its `commonPrefixLen` string-compares + the `List<Int>` alloc among the
        // top costs: `repsOf` is called many times per row (the error path, each referenced field, the
        // FormalCache key, the suppressor scan) and recomputed every time. Caching also returns the SAME list
        // instance, so the FormalCache `Pair` key stops re-allocating it. (Stable: the document is immutable.)
        private val repsCache = HashMap<String, List<Int>>()
        private fun reps(path: String): List<Int> = repsCache.getOrPut(path) { repsFor(path, group, rowReps) }

        private val placeCache = HashMap<String, Placement?>()
        private fun placeOf(path: String): Placement? {
            placeCache[path]?.let { return it }
            if (placeCache.containsKey(path)) return null
            val p = valueAt(path, reps(path))
            placeCache[path] = p
            return p
        }

        // The resolved [Val] per path, cached for this row context — `field` is called once per operand per
        // RULE, so many rules referencing the same field on the same row re-resolved (`resolveValue`, a `Val`
        // allocation) independently. The value is stable (immutable document), so cache it once per row.
        private val valCache = HashMap<String, Val>()
        override fun field(path: String): Val = valCache.getOrPut(path) {
            val v = placeOf(path)?.value
            if (v != null) resolveValue(path, v) else emptyValue(kindOf(path))
        }

        override fun filled(path: String): Boolean = placeOf(path)?.value != null

        override fun signed(path: String): Boolean = path !in unsignedFields

        /**
         * Whether [groupPath] is filled in this row — matching the kernel's `GroupFillCache` exactly, which
         * splits on repeatability:
         * - a **repeatable** group is filled once its row is **instantiated** (creating the row is itself
         *   content — the row index is meaningful); a created-but-empty repeat row counts. A filled descendant
         *   also instantiates the row (via `autoGroup`), so "the row exists" subsumes "a descendant is filled".
         * - a **non-repeatable** group is filled only when **at least one descendant field is filled** — its
         *   mere structural presence is not content.
         *
         * (KERNEL-FINDINGS §4/§9; this is the resolution of the "GroupFilled on an empty created repeat row"
         * edge. Requiredness rides on the same notion: `GroupFilled(relativeTo)`.)
         */
        override fun groupFilled(groupPath: String): Boolean =
            if (groupPath in declaredReps)
                // A repeatable group is "filled" iff its instance at this row's reps is instantiated — an O(1)
                // membership via the row-prefix index (was an O(rows) `in` on the row List, i.e. O(rows²) when
                // hit per row by requiredness over a sparse-required document).
                rowsWithPrefix(groupPath, reps(groupPath)).isNotEmpty()
            else
                // A NON-repeatable group is "filled" iff some valued DESCENDANT is in this row's scope.
                // O(1) per call via the per-(groupPath, group) attach-depth index (the GroupFillCache analog) —
                // exactly mirrors the per-descendant scope check (group descendants by their common-with-`group`
                // depth, match the row's prefix at that depth), but built once per (groupPath, group), not per row.
                nonRepGroupFilledDepths(groupPath, group).any { (depth, prefixes) -> rowReps.take(depth) in prefixes }

        override fun star(path: String): List<Val> =
            starRows(path).mapNotNull { reps -> valueAt(path, reps)?.value?.let { resolveValue(path, it) } }

        // Row-aligned: one entry per group row — empty → the kind's empty value (number → 0), malformed → Unknown.
        override fun starAll(path: String): List<Val> =
            starRows(path).map { reps -> valueAt(path, reps)?.value?.let { resolveValue(path, it) } ?: emptyValue(kindOf(path)) }

        // Reps-aware (for the cascade overlay): one entry per group row, null = empty cell, malformed = Unknown.
        override fun starCells(path: String): List<Pair<List<Int>, Val?>> =
            starRows(path).map { reps -> reps to valueAt(path, reps)?.value?.let { resolveValue(path, it) } }

        override fun starOmission(path: String): Boolean {
            val repGroup = parentGroup(path)
            val rows = starRows(path)
            val declared = declaredReps[repGroup] ?: Int.MAX_VALUE
            return rows.size < declared || rows.any { reps -> valueAt(path, reps)?.value == null }
        }

        // `FirstFilledValue` is displaceable iff an instantiated empty row precedes the first filled one
        // (starRows is in repetition order, so the first filled is at indexOfFirst; > 0 means earlier rows
        // were empty). A first-row-filled or no-filled-row case is fixed → VALUE.
        override fun firstStarRowEmpty(path: String): Boolean =
            starRows(path).indexOfFirst { reps -> valueAt(path, reps)?.value != null } > 0

        override fun displayValue(path: String): String? = placeOf(path)?.value

        override fun declaredKind(path: String): Kind? = kinds[path]

        override fun dateFormatOf(path: String): String? = dateFormats[path]

        /**
         * SemanticIndex `[F For "v"]`: the value of [targetPath] in the in-scope row whose sibling [indexField]
         * cell equals [indexValue]. The kernel's `IndexFieldCache` analog — a per-context `indexValue → row`
         * memo built ONCE per index field (first match wins, matching a scan) and reused across every reference
         * and every rule sharing this row context, instead of an O(rows) scan **per reference** (latent
         * O(refs·rows)). Rows of the index field's group == `starRows(targetPath)` (same group, sibling leaves),
         * and the row reps address the leaf for any sibling, so the map is shared across sibling targets.
         */
        private val indexRowCache = HashMap<String, Map<String, List<Int>>>()
        override fun indexedCell(targetPath: String, indexField: String, indexValue: String): Val? {
            val byValue = indexRowCache.getOrPut(indexField) {
                val m = HashMap<String, List<Int>>()
                for (reps in starRows(targetPath)) valueAt(indexField, reps)?.value?.let { if (it !in m) m[it] = reps }
                m
            }
            val reps = byValue[indexValue] ?: return null
            val raw = valueAt(targetPath, reps)?.value
            return if (!raw.isNullOrEmpty()) resolveValue(targetPath, raw) else null
        }

        // Rows of groupPath in this row's SCOPE (the Having filter's per-row contexts). An aggregate/quantifier
        // over groupPath spans all rows under groupPath's PARENT (the common ancestor) — so scope to the
        // parent-rep prefix, NOT the full rowReps. This matters when THIS context is itself a row of groupPath
        // (a rule iterating the same group the aggregate ranges over — the `$` outer-correlation case): scoping
        // to the full rowReps would wrongly return only this one row. A DEEPER (nested) aggregate keeps the full
        // prefix (take(n) with n ≥ this depth ≡ rowReps), so it stays scoped to this row — unchanged.
        override fun rowContexts(groupPath: String): List<EvalContext> =
            rowContextsScoped(groupPath, rowReps.take(partsOf(groupPath).size - 1))

        // The data view a CustomCondition reads (the kernel's IData): document-wide raw-value lookup by
        // (path, indices) and the set of present field instances — a custom condition's IData spans the whole
        // document, the current row is carried by the error-field pointer (not row-scoped here).
        override fun customConditionData(): CustomConditionData = object : CustomConditionData {
            override fun getValue(field: FieldPointer): String? = valueAt(field.path, field.indices)?.value
            override fun fields(): List<FieldPointer> = index().valued.map { FieldPointer(it.path, it.reps) }
        }

        // EnumCategory `[field -> Category]`: map the enum field's stored value to its category value by its
        // POSITION in the enum's value list. An empty enum or an out-of-enum value yields no comparison value
        // (Unknown → the rule does not fire / is suppressed, matching the kernel's empty-string compare /
        // formal-error-suppression on a value not in the enumeration).
        override fun enumCategory(path: String, category: String): Val {
            val raw = valueAt(path, reps(path))?.value
            if (raw.isNullOrEmpty()) return Val.Unknown
            val idx = (enumValues[path] ?: return Val.Unknown).indexOf(raw)
            if (idx < 0) return Val.Unknown
            return enumCategories[path]?.get(category)?.getOrNull(idx)?.let { Val.Txt(it) } ?: Val.Unknown
        }

        override fun repsOf(path: String): List<Int> = reps(path)

        /** Index tuples of [starPath] across every row of its deepest repeatable group ancestor in scope. The
         *  in-scope rows are fetched by prefix in O(1) (via the [Index] prefix index), not by an O(all-rows)
         *  filter — so a semantic-index / cross-level star resolved per row stays linear, not O(rows²). */
        private fun starRows(starPath: String): List<List<Int>> {
            val repGroup = parentGroup(starPath)
            val common = commonPrefixLen(partsOf(repGroup), partsOf(group))
            return rowsWithPrefix(repGroup, rowReps.take(common))
                .map { it + 1 }   // append the field's own index (1) to address the leaf
        }
    }

    /**
     * One iteration of a [parallelContexts] outer join: the common-parent row [cReps] and, per parallel group,
     * the matched row reps in [matches] (a `null` entry = no row for this index value → the group counts as
     * "not specified"). A field reference resolves through whichever parallel group it belongs to — empty when
     * that group has no matching row, with the group's pointer level set to [UNKNOWN_REP]; a reference outside
     * the parallel groups resolves relative to [commonParent], the common-prefix way ([repsFor]).
     */
    private inner class ParallelRowContext(
        private val commonParent: String,
        private val cReps: List<Int>,
        private val groups: List<String>,
        private val parentReps: Map<String, List<Int>>,
        private val matches: Map<String, List<Int>?>,
        private val invalidIndexGroups: Set<String>,
    ) : EvalContext {

        // A row over a present index value is always evaluated (the value exists in at least one group).
        override val evaluated: Boolean = true

        /** The parallel group [path] belongs to (itself or a descendant), or null when [path] is outside them. */
        private fun groupOf(path: String): String? = groups.firstOrNull { path == it || path.startsWith("$it/") }

        /** Whether [path]'s group has no row for the current index value (the outer-join "not specified" side). */
        private fun unmatched(path: String): Boolean = groupOf(path)?.let { matches[it] == null } ?: false

        override fun repsOf(path: String): List<Int> {
            val g = groupOf(path) ?: return repsFor(path, commonParent, cReps)
            val gDepth = parts(g).size
            val parent = parentReps.getValue(g)   // the group's join parent: a frame row, else the common parent
            val matched = matches[g]
            val segs = parts(path)
            return List(segs.size) { i ->
                when {
                    i < parent.size -> parent[i]            // the group's parent prefix (frame / common-parent — real)
                    i >= gDepth -> 1                        // the field / deeper level
                    matched != null -> matched[i]           // a matched row carries the real index at every level
                    // Unmatched (no row for this value): a REPEATABLE level is UNKNOWN (-5); a non-repeatable
                    // intermediate wrapper is always 1 (the kernel renders `…/W1[1]/A[-5]/…`, not `W1[-5]`).
                    "/" + segs.take(i + 1).joinToString("/") in declaredReps -> UNKNOWN_REP
                    else -> 1
                }
            }
        }

        /** The raw value of [path] in this iteration, or null when its group has no matching row (or it is empty). */
        private fun resolved(path: String): String? {
            val reps = repsOf(path)
            return if (reps.any { it == UNKNOWN_REP }) null else valueAt(path, reps)?.value
        }

        // An UNMATCHED reference into an INVALID-index group reads as 3VL UNKNOWN (not empty) — the kernel's
        // invalid-index behavior (KERNEL-FINDINGS §9), routed through [presenceUnknown] for presence predicates
        // and Val.Unknown here for value comparisons. A valid group's unmatched ref is the kind's empty value.
        override fun field(path: String): Val = when {
            unmatched(path) -> if (groupOf(path) in invalidIndexGroups) Val.Unknown else emptyValue(kindOf(path))
            else -> resolved(path)?.let { resolveValue(path, it) } ?: emptyValue(kindOf(path))
        }

        override fun filled(path: String): Boolean = resolved(path) != null

        override fun presenceUnknown(path: String): Boolean = unmatched(path) && groupOf(path) in invalidIndexGroups

        override fun displayValue(path: String): String? = resolved(path)

        override fun signed(path: String): Boolean = path !in unsignedFields

        override fun declaredKind(path: String): Kind? = kinds[path]

        override fun dateFormatOf(path: String): String? = dateFormats[path]

        // A parallel group is "filled" for this iteration iff it has a matching row.
        override fun groupFilled(groupPath: String): Boolean =
            if (groupPath in groups) matches[groupPath] != null
            else throw UnsupportedConstructException(
                "group-filled on a non-parallel group ($groupPath) inside a parallel iteration is not modeled")

        override fun enumCategory(path: String, category: String): Val {
            val raw = resolved(path)
            if (raw.isNullOrEmpty()) return Val.Unknown
            val idx = (enumValues[path] ?: return Val.Unknown).indexOf(raw)
            if (idx < 0) return Val.Unknown
            return enumCategories[path]?.get(category)?.getOrNull(idx)?.let { Val.Txt(it) } ?: Val.Unknown
        }

        // Aggregates / filters / semantic index / custom conditions are NOT valid inside a parallel iteration
        // (KERNEL-SEMANTICS §9: a filter condition may not contain a parallel iteration, and vice versa) — fail
        // loud rather than risk a silently-wrong answer.
        override fun star(path: String): List<Val> = unsupportedInParallel()
        override fun starOmission(path: String): Boolean = unsupportedInParallel()
        private fun unsupportedInParallel(): Nothing =
            throw UnsupportedConstructException("an aggregate/filter inside a parallel iteration is not modeled")
    }

    /**
     * Rows of [repGroup] whose rep tuple starts with [prefix] — O(1) via the per-group all-prefixes index
     * (built once, dropped on mutation with the [Index]). Replaces the O(all-rows) scan that made per-row
     * star/semantic-index resolution O(rows²). Document order is preserved (the index is built by iterating
     * [rowsOf] in order).
     */
    private fun rowsWithPrefix(repGroup: String, prefix: List<Int>): List<List<Int>> {
        val byPrefix = index().prefixRowsByGroup.getOrPut(repGroup) {
            val m = HashMap<List<Int>, MutableList<List<Int>>>()
            for (row in rowsOf(repGroup)) for (k in 0..row.size) m.getOrPut(row.take(k)) { ArrayList() }.add(row)
            m
        }
        return byPrefix[prefix] ?: emptyList()
    }

    private fun kindOf(path: String): Kind = kinds[path] ?: error("no kind declared for $path")

    /**
     * A present raw value → typed [Val], format-aware. A [Kind.DATE_RANGE] value is parsed to a [Val.Range] of
     * ISO dates via its field's `(format, separator)` ([rangeConfig]); a [Kind.DATE] value is canonicalized to
     * ISO via its field's `format` ([dateFormats], ISO default) — so a non-ISO `dd.MM.yyyy` field's `15.06.2024`
     * becomes `2024-06-15` and the extractors/comparisons read it like any date. A [Kind.DATE_TIME] value is
     * likewise canonicalized to ISO `yyyy-MM-dd'T'HH:mm:ss` via its field's `format` ([dateTimeFormats]) so it
     * compares chronologically against `Now`. All are locale-variant by
     * construction (the format is read, not hardcoded); a malformed value → [Val.Unknown] (the referencing rule
     * is suppressed, and the document-wide formal check reports it). Other kinds go through the plain [typedValue].
     */
    private fun resolveValue(path: String, raw: String): Val {
        val kind = kindOf(path)
        // A formally-invalid value is "unknown" EVERYWHERE — a direct ref, an aggregate element, a computation
        // operand — matching the kernel (a formal error makes the value non-evaluable). This catches values
        // that parse but are still invalid: signed-zero `-0`, leading-plus `+5`, too-many-decimals. Without
        // this, an aggregate would silently fold a `-0` as 0 and fire where the kernel suppresses.
        val precision = datePrecisions[path] ?: DatePrecision.FULL
        // The field's date-ish format for the formal check — DATE/TIME/DATE_TIME/DATE_RANGE each from their own
        // map (so a malformed TIME/DATE_TIME/range also resolves to Unknown, not just DATE; KERNEL-FINDINGS §6).
        val dateLikeFormat = when (kind) {
            Kind.DATE -> dateFormats[path]
            Kind.TIME -> timeFormats[path]
            Kind.DATE_TIME -> dateTimeFormats[path]
            Kind.DATE_RANGE -> rangeConfig[path]?.first
            else -> null
        }
        if (formalError(kind, raw, scales[path], dateLikeFormat, precision, rangeConfig[path]?.second) != null) return Val.Unknown
        return when (kind) {
            Kind.DATE_RANGE -> rangeConfig[path]?.let { (format, sep) ->
                parseRangeToIso(raw, format, sep)?.let { Val.Range(it.first, it.second) }
            } ?: Val.Unknown
            // A DAY_OPTIONAL field's day-omitted value (`00.MM.yyyy`) is well-formed but NOT a single concrete
            // date → Unknown for a direct reference (only `ValueAsDate` resolves it); a full value canonicalizes.
            Kind.DATE -> {
                val fmt = dateFormats[path] ?: "yyyy-MM-dd"
                if (precision == DatePrecision.DAY_OPTIONAL && dayOptionalParts(raw, fmt)?.third == 0) Val.Unknown
                else parseDateToIso(raw, fmt)?.let { Val.Txt(it) } ?: Val.Unknown
            }
            Kind.DATE_TIME -> parseDateTimeToIso(raw, dateTimeFormats[path] ?: "yyyy-MM-dd'T'HH:mm:ss")
                ?.let { Val.Txt(it) } ?: Val.Unknown
            else -> typedValue(kind, raw)
        }
    }

    private companion object {
        fun parts(path: String): List<String> = path.trim('/').split('/')

        fun commonPrefixLen(a: List<String>, b: List<String>): Int {
            var n = 0
            while (n < a.size && n < b.size && a[n] == b[n]) n++
            return n
        }

        /** `/A/B/F` → `[/A, /A/B]`. */
        fun ancestorGroups(fieldPath: String): List<String> {
            val p = parts(fieldPath)
            return (1 until p.size).map { "/" + p.take(it).joinToString("/") }
        }

        /** The immediate parent group path of a field, e.g. `/Order/Items/Count` → `/Order/Items`. */
        fun parentGroup(fieldPath: String): String {
            val p = parts(fieldPath)
            return "/" + p.take(p.size - 1).joinToString("/")
        }
    }
}
