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

    fun isRelevant(path: String, reps: List<Int>): Boolean = isRelevantWithin(path, reps, reps.size)

    /**
     * Concrete relevance with every level from [bound] on treated as unbound. With `bound == reps.size`, this is
     * exactly [isRelevant].
     */
    fun isRelevantWithin(path: String, reps: List<Int>, bound: Int): Boolean {
        if (path in globalFields) return true
        val parts = pathParts(path)
        var depth = 1
        while (depth <= parts.size && depth <= reps.size) {
            val prefix = "/" + parts.take(depth).joinToString("/")
            if (depth <= bound) {
                val bucket = tuples[prefix]
                if (bucket != null) {
                    val base = reps.take(depth)
                    for (pattern in patterns.getValue(prefix)) {
                        val key = base.mapIndexed { i, rep -> if (pattern[i]) RelevantEntity.ALL else rep }
                        if (key in bucket) return true
                    }
                }
            } else {
                val projection = projection(prefix, bound)
                if (projection != null) {
                    val base = reps.take(bound)
                    for (pattern in projection.second) {
                        val key = base.mapIndexed { i, rep -> if (pattern[i]) RelevantEntity.ALL else rep }
                        if (key in projection.first) return true
                    }
                }
            }
            depth++
        }
        return false
    }

    private fun projection(prefix: String, levels: Int): Pair<Set<List<Int>>, Set<List<Boolean>>>? {
        val bucket = tuples[prefix] ?: return null
        return projected.getOrPut(prefix to levels) {
            bucket.mapTo(HashSet()) { it.take(levels) } to
                patterns.getValue(prefix).mapTo(LinkedHashSet()) { it.take(levels) }
        }
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
