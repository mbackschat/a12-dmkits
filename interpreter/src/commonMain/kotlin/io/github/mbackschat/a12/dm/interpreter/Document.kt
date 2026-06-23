package io.github.mbackschat.a12.dm.interpreter

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
 */
class Document(
    private val kinds: Map<String, Kind>,
    private val declaredReps: Map<String, Int> = emptyMap(),
) {
    private data class Placement(val path: String, val reps: List<Int>, val value: String?, val isGroup: Boolean)

    private val placements = mutableListOf<Placement>()

    /** A field with a value at the given 1-based indices (one per path part, the field included). */
    fun field(path: String, value: String, vararg reps: Int): Document = add(path, reps, value, false)

    /** A present-but-valueless (unfilled) field. */
    fun emptyField(path: String, vararg reps: Int): Document = add(path, reps, null, false)

    /** A group row at the given 1-based indices (one per path part). */
    fun group(path: String, vararg reps: Int): Document = add(path, reps, null, true)

    private fun add(path: String, reps: IntArray, value: String?, isGroup: Boolean): Document {
        val r = reps.toList()
        if (!isGroup) ancestorGroups(path).forEach { g -> autoGroup(g, r.take(parts(g).size)) }
        placements += Placement(path, r, value, isGroup)
        return this
    }

    private fun autoGroup(path: String, reps: List<Int>) {
        if (placements.none { it.isGroup && it.path == path && it.reps == reps }) {
            placements += Placement(path, reps, null, true)
        }
    }

    /**
     * Every present (valued) field instance — its path, 1-based repetition indices, and raw value — in
     * document order. The document-wide formal-error check ([DmInterpreter.validate]) walks these: the
     * kernel formal-checks *all* filled fields, not only the rule-referenced ones. Unfilled (empty) fields
     * and groups are excluded — emptiness is a requiredness concern, not a format one.
     */
    fun valuedFields(): List<ValuedField> =
        placements.filter { !it.isGroup && it.value != null }.map { ValuedField(it.path, it.reps, it.value!!) }

    /** One [EvalContext] per instantiated row of [groupPath]; empty if the group has no rows. */
    fun contextsFor(groupPath: String): List<EvalContext> =
        rowsOf(groupPath).map { reps -> RowContext(groupPath, reps) }

    /** The distinct 1-based index tuples at which [groupPath] is instantiated, in document order. */
    private fun rowsOf(groupPath: String): List<List<Int>> =
        placements.filter { it.isGroup && it.path == groupPath }.map { it.reps }.distinct()

    /** Indices for a referenced path P under a row of group G: shared-prefix indices from the row, else 1. */
    private fun repsFor(refPath: String, group: String, rowReps: List<Int>): List<Int> {
        val refParts = parts(refPath)
        val common = commonPrefixLen(refParts, parts(group))
        return refParts.indices.map { i -> if (i < common) rowReps[i] else 1 }
    }

    private fun valueAt(path: String, reps: List<Int>): Placement? =
        placements.firstOrNull { !it.isGroup && it.path == path && it.reps == reps }

    private inner class RowContext(private val group: String, private val rowReps: List<Int>) : EvalContext {

        override val evaluated: Boolean
            get() = placements.any { p -> !p.isGroup && p.value != null && inScope(p) }

        /** A value placement is in this row's scope if its shared-prefix indices match the row. */
        private fun inScope(p: Placement): Boolean {
            val common = commonPrefixLen(parts(p.path), parts(group))
            return (0 until common).all { i -> p.reps[i] == rowReps[i] }
        }

        override fun field(path: String): Val {
            val v = valueAt(path, repsFor(path, group, rowReps))?.value
            return if (v != null) typedValue(kindOf(path), v) else emptyValue(kindOf(path))
        }

        override fun filled(path: String): Boolean =
            valueAt(path, repsFor(path, group, rowReps))?.value != null

        override fun star(path: String): List<Val> =
            starRows(path).mapNotNull { reps -> valueAt(path, reps)?.value?.let { typedValue(kindOf(path), it) } }

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
            val common = commonPrefixLen(parts(repGroup), parts(group))
            return placements
                .filter { it.isGroup && it.path == repGroup && (0 until common).all { i -> it.reps[i] == rowReps[i] } }
                .map { it.reps + 1 }   // append the field's own index (1) to address the leaf
                .distinct()
        }
    }

    private fun kindOf(path: String): Kind = kinds[path] ?: error("no kind declared for $path")

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
