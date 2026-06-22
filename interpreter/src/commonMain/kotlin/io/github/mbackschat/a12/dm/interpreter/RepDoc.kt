package io.github.mbackschat.a12.dm.interpreter

/**
 * A document with ONE repeatable subgroup — NO kernel types. Outer fields plus a list of
 * item rows. It produces two kinds of [EvalContext]:
 *  - [outerContext] — for an aggregate rule evaluated once in the outer row (Sum/MaxValue over `star`);
 *  - [rowContexts] — one per item row, for a per-row rule that iterates the repetition.
 *
 * Item-field values live under [itemsPath]; outer values under [outerPath]. [declaredReps] is the
 * group's declared max repetition count: the un-instantiated tail below it is a fillable omission, so a
 * starred aggregate stays OMISSION-typed until every declared row is instantiated and filled.
 */
class RepDoc(
    private val outerPath: String,
    private val itemsPath: String,
    private val outer: Map<String, String>,
    private val itemRows: List<Map<String, String>>,
    private val kinds: Map<String, Kind>,
    private val declaredReps: Int = Int.MAX_VALUE,
) {
    private fun kindOf(path: String): Kind =
        kinds[path] ?: error("no kind declared for $path")

    private fun resolve(map: Map<String, String>, path: String): Val =
        map[path]?.let { typedValue(kindOf(path), it) } ?: emptyValue(kindOf(path))

    /** FILLED values of a starred field across every row (empties skipped — the aggregates decide). */
    private fun starOf(path: String): List<Val> =
        itemRows.mapNotNull { row -> row[path]?.let { typedValue(kindOf(path), it) } }

    /** Whether a starred aggregate over [path] could still grow — an empty operand, or an
     *  un-instantiated tail below [declaredReps] — making a fired comparison an OMISSION. */
    private fun starOmissionOf(path: String): Boolean =
        itemRows.size < declaredReps || itemRows.any { it[path] == null }

    private val outerFilled: Boolean = outer.keys.any { it.startsWith("$outerPath/") }

    /** The outer (/Order) row context — aggregates collapse the item rows via [EvalContext.star]. */
    fun outerContext(): EvalContext = object : EvalContext {
        override val evaluated = outerFilled
        override fun field(path: String) = resolve(outer, path)
        override fun filled(path: String) = outer.containsKey(path)
        override fun star(path: String) = starOf(path)
        override fun starOmission(path: String) = starOmissionOf(path)
        override fun displayValue(path: String) = outer[path]
    }

    /** One context per item row — a per-row rule fires independently in each. */
    fun rowContexts(): List<EvalContext> = itemRows.map { row ->
        object : EvalContext {
            // The row is evaluated if it carries any item value (or the outer row is filled).
            override val evaluated = row.keys.any { it.startsWith("$itemsPath/") } || outerFilled

            override fun field(path: String): Val =
                if (path.startsWith("$itemsPath/")) resolve(row, path) else resolve(outer, path)

            override fun filled(path: String): Boolean =
                if (path.startsWith("$itemsPath/")) row.containsKey(path) else outer.containsKey(path)

            override fun star(path: String) = starOf(path)
            override fun starOmission(path: String) = starOmissionOf(path)
            override fun displayValue(path: String) =
                if (path.startsWith("$itemsPath/")) row[path] else outer[path]
        }
    }
}
