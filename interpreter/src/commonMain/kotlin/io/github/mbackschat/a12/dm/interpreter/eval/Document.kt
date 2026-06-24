package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.ast.typedValue
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ValuedField
import kotlin.jvm.JvmOverloads

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
        val valueByKey: Map<Pair<String, List<Int>>, Placement>,   // non-group placements, FIRST per key wins
        val rowsByGroup: Map<String, List<List<Int>>>,             // group path → distinct reps, document order
        val valued: List<Placement>,                               // valued (value != null) non-group placements
        val partsByPath: Map<String, List<String>>,                // memoized path split
    )

    private var cachedIndex: Index? = null

    private fun index(): Index = cachedIndex ?: build().also { cachedIndex = it }

    private fun build(): Index {
        val byKey = HashMap<Pair<String, List<Int>>, Placement>()
        val rows = LinkedHashMap<String, MutableList<List<Int>>>()
        val valued = ArrayList<Placement>()
        val partsMemo = HashMap<String, List<String>>()
        for (p in placements) {
            partsMemo.getOrPut(p.path) { parts(p.path) }
            if (p.isGroup) {
                rows.getOrPut(p.path) { ArrayList() }.let { if (p.reps !in it) it.add(p.reps) }
            } else {
                byKey.getOrPut(p.path to p.reps) { p }   // FIRST placement per key wins (matches firstOrNull)
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
     * One [EvalContext] per instantiated row of [groupPath]; empty if the group has no rows. Each row's
     * `evaluated` flag is computed here in a single amortized pass over the valued fields: a row is
     * evaluated iff some valued field is in its scope, i.e. its index-prefix matches the field at the depth
     * where the field's path diverges from the group (`common`). Recording each valued field's prefix at its
     * attach-depth turns the per-row "is anything in scope?" check into a set lookup.
     */
    fun contextsFor(groupPath: String): List<EvalContext> {
        val rows = rowsOf(groupPath)
        if (rows.isEmpty()) return emptyList()
        val gParts = partsOf(groupPath)
        val attachByDepth = HashMap<Int, HashSet<List<Int>>>()
        for (p in index().valued) {
            val common = commonPrefixLen(partsOf(p.path), gParts)
            attachByDepth.getOrPut(common) { HashSet() }.add(p.reps.take(common))
        }
        return rows.map { reps ->
            val evaluated = attachByDepth.any { (depth, prefixes) -> reps.take(depth) in prefixes }
            RowContext(groupPath, reps, evaluated)
        }
    }

    /** The distinct 1-based index tuples at which [groupPath] is instantiated, in document order. */
    private fun rowsOf(groupPath: String): List<List<Int>> = index().rowsByGroup[groupPath] ?: emptyList()

    /** Indices for a referenced path P under a row of group G: shared-prefix indices from the row, else 1. */
    private fun repsFor(refPath: String, group: String, rowReps: List<Int>): List<Int> {
        val refParts = partsOf(refPath)
        val common = commonPrefixLen(refParts, partsOf(group))
        return refParts.indices.map { i -> if (i < common) rowReps[i] else 1 }
    }

    private fun valueAt(path: String, reps: List<Int>): Placement? = index().valueByKey[path to reps]

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

        /** A value placement is in this row's scope if its shared-prefix indices match the row. */
        private fun inScope(p: Placement): Boolean {
            val common = commonPrefixLen(partsOf(p.path), partsOf(group))
            return (0 until common).all { i -> p.reps[i] == rowReps[i] }
        }

        override fun field(path: String): Val {
            val v = valueAt(path, repsFor(path, group, rowReps))?.value
            return if (v != null) resolveValue(path, v) else emptyValue(kindOf(path))
        }

        override fun filled(path: String): Boolean =
            valueAt(path, repsFor(path, group, rowReps))?.value != null

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
            if (groupPath in declaredReps) repsFor(groupPath, group, rowReps) in rowsOf(groupPath)
            else index().valued.any { p -> p.path.startsWith("$groupPath/") && inScope(p) }

        override fun star(path: String): List<Val> =
            starRows(path).mapNotNull { reps -> valueAt(path, reps)?.value?.let { resolveValue(path, it) } }

        // Row-aligned: one entry per group row — empty → the kind's empty value (number → 0), malformed → Unknown.
        override fun starAll(path: String): List<Val> =
            starRows(path).map { reps -> valueAt(path, reps)?.value?.let { resolveValue(path, it) } ?: emptyValue(kindOf(path)) }

        override fun starOmission(path: String): Boolean {
            val repGroup = parentGroup(path)
            val rows = starRows(path)
            val declared = declaredReps[repGroup] ?: Int.MAX_VALUE
            return rows.size < declared || rows.any { reps -> valueAt(path, reps)?.value == null }
        }

        override fun displayValue(path: String): String? =
            valueAt(path, repsFor(path, group, rowReps))?.value

        override fun repsOf(path: String): List<Int> = repsFor(path, group, rowReps)

        /** Index tuples of [starPath] across every row of its deepest repeatable group ancestor in scope. */
        private fun starRows(starPath: String): List<List<Int>> {
            val repGroup = parentGroup(starPath)
            val common = commonPrefixLen(partsOf(repGroup), partsOf(group))
            return rowsOf(repGroup)
                .filter { reps -> (0 until common).all { i -> reps[i] == rowReps[i] } }
                .map { it + 1 }   // append the field's own index (1) to address the leaf
        }
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
        if (formalError(kind, raw, scales[path], dateFormats[path]) != null) return Val.Unknown
        return when (kind) {
            Kind.DATE_RANGE -> rangeConfig[path]?.let { (format, sep) ->
                parseRangeToIso(raw, format, sep)?.let { Val.Range(it.first, it.second) }
            } ?: Val.Unknown
            Kind.DATE -> parseDateToIso(raw, dateFormats[path] ?: "yyyy-MM-dd")?.let { Val.Txt(it) } ?: Val.Unknown
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
