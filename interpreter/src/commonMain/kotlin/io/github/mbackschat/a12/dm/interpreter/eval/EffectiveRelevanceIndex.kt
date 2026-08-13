package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.pathParts
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity

/**
 * The production partial-validation relevance index. This lives in the engine package so in-repo coverage
 * instruments can ask the exact query the evaluator asks without publishing a supported user-facing API. Engine
 * packages are intentionally excluded from the frozen interpreter ABI contract.
 *
 * One index owns both queries: [isRelevant] for a concrete cell and [isRelevantWithin] for a rule-run gate whose
 * iteration left deeper repetition levels unbound. Construction ignores malformed entities whose coordinate arity
 * cannot match their path. Global fields are relevant for every repetition.
 *
 * The index replaces a per-cell linear scan of the caller's relevant set. It probes only wildcard patterns the
 * caller supplied, rather than enumerating `2^depth` combinations. The ordinary full-bound query deliberately
 * avoids the projected-key cache and its pair allocation because it runs once per field instance.
 */
class EffectiveRelevanceIndex private constructor(
    private val globalFields: Set<String>,
    private val tuples: Map<String, HashSet<List<Int>>>,
    private val patterns: Map<String, Set<List<Boolean>>>,
) {
    /** Projected tuple/pattern buckets, built once per `(path, bound)` used by a rule-run gate. */
    private val projected = HashMap<Pair<String, Int>, Pair<Set<List<Int>>, Set<List<Boolean>>>>()

    /** One ancestor level of a queried path that the caller's relevant set actually names. */
    private class Level(
        val prefix: String,
        val tuples: Set<List<Int>>,
        val patterns: Set<List<Boolean>>,
    )

    /**
     * Per queried path, one entry per ancestor level — the level's bucket, or `null` where the relevant set names
     * nothing at that depth. **Model-static, so it is memoized by path**, which is what makes the query cheap: it
     * replaces, per relevance question and per depth, a `pathParts` split, a `take`, a `joinToString` that builds
     * the prefix string, and two `HashMap` probes to resolve that string back to its buckets.
     *
     * That string building, not the probe key [IG128] named, was the dominant cost: `StringBuilder.append` was
     * **16.95 %** of a partial-validation profile and the `joinToString` family ~10 % more, against ~2.8 % for the
     * list-key mechanics. Keying model-static data by path is not the defect IF284 removed — that one put a
     * repetition LIST in a key, so hashing it walked the list on every lookup. This key is the path `String`, whose
     * hash the platform has already cached, and it is the same shape as `Document.partsByPath`.
     */
    private val plans = HashMap<String, List<Level?>>()

    fun isRelevant(path: String, reps: List<Int>): Boolean = isRelevantWithin(path, reps, reps.size)

    /**
     * Concrete relevance with every level from [bound] on treated as unbound. With `bound == reps.size`, this is
     * exactly [isRelevant].
     */
    fun isRelevantWithin(path: String, reps: List<Int>, bound: Int): Boolean {
        if (path in globalFields) return true
        val plan = plans.getOrPut(path) { planFor(path) }
        val deepest = if (plan.size < reps.size) plan.size else reps.size
        for (depth in 1..deepest) {
            val level = plan[depth - 1] ?: continue
            if (depth <= bound) {
                for (pattern in level.patterns) {
                    if (projectedKey(reps, pattern, depth) in level.tuples) return true
                }
            } else {
                val (projectedTuples, projectedPatterns) = projection(level, bound)
                for (pattern in projectedPatterns) {
                    if (projectedKey(reps, pattern, bound) in projectedTuples) return true
                }
            }
        }
        return false
    }

    /**
     * The one allocation left per `(depth, pattern)` probe: the key its bucket is hashed on. Built in a single pass
     * over [reps] — it used to be a `take` followed by a `mapIndexed`, which allocated one list to produce another.
     */
    private fun projectedKey(reps: List<Int>, pattern: List<Boolean>, levels: Int): List<Int> =
        List(levels) { i -> if (pattern[i]) RelevantEntity.ALL else reps[i] }

    private fun planFor(path: String): List<Level?> {
        val prefix = StringBuilder()
        return pathParts(path).map { part ->
            prefix.append('/').append(part)
            val at = prefix.toString()
            tuples[at]?.let { Level(at, it, patterns.getValue(at)) }
        }
    }

    private fun projection(level: Level, levels: Int): Pair<Set<List<Int>>, Set<List<Boolean>>> =
        projected.getOrPut(level.prefix to levels) {
            level.tuples.mapTo(HashSet()) { it.take(levels) } to
                level.patterns.mapTo(LinkedHashSet()) { it.take(levels) }
        }

    companion object {
        fun build(globalFields: Set<String>, relevant: Set<RelevantEntity>): EffectiveRelevanceIndex {
            val tuples = HashMap<String, HashSet<List<Int>>>()
            val patterns = HashMap<String, MutableSet<List<Boolean>>>()
            for (entity in relevant) {
                if (entity.indices.size != pathParts(entity.path).size) continue
                tuples.getOrPut(entity.path) { HashSet() } += entity.indices
                patterns.getOrPut(entity.path) { LinkedHashSet() } +=
                    entity.indices.map { it == RelevantEntity.ALL }
            }
            return EffectiveRelevanceIndex(globalFields, tuples, patterns)
        }
    }
}
