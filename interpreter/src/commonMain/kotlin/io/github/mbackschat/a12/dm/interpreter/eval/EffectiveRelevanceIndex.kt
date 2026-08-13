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
 * caller supplied, rather than enumerating `2^depth` combinations.
 *
 * **One instance serves one call** (`DmInterpreter.relevanceQueriesOf` builds it per `validatePart`), which is what
 * lets it carry mutable state: the per-path level plan, the projection memo, and the reusable probe key below.
 */
class EffectiveRelevanceIndex private constructor(
    private val globalFields: Set<String>,
    private val tuples: Map<String, HashSet<RepKey>>,
    private val patterns: Map<String, Set<List<Boolean>>>,
) {
    /**
     * A repetition tuple as a hash key over an `IntArray`.
     *
     * **Why not `List<Int>`**, which is what this was: every probe had to BUILD one, so the query allocated a list
     * per `(depth, pattern)` and then hashed it by walking it — IF289's first half left that as the top frame at 8.23 % with
     * the `ArrayList` hash/equals mechanics ~7 % behind it. An `IntArray` hashes without boxing, and one instance
     * can be REUSED across probes because a lookup never retains its key.
     *
     * [length] is carried separately from `values.size` so the reusable probe can hold an oversized buffer and
     * still compare as a shorter tuple. Stored keys are sized exactly and are never mutated; that discipline is
     * local, because this class is private to the index and [reset] is called on the probe alone.
     */
    internal class RepKey(var values: IntArray, var length: Int) {
        fun reset(reps: List<Int>, pattern: List<Boolean>, levels: Int): RepKey {
            if (values.size < levels) values = IntArray(levels)
            for (i in 0 until levels) values[i] = if (pattern[i]) RelevantEntity.ALL else reps[i]
            length = levels
            return this
        }

        fun truncatedTo(levels: Int) = RepKey(values.copyOf(levels), levels)

        override fun hashCode(): Int {
            var hash = 1
            for (i in 0 until length) hash = 31 * hash + values[i]
            return hash
        }

        override fun equals(other: Any?): Boolean {
            if (other !is RepKey || other.length != length) return false
            for (i in 0 until length) if (values[i] != other.values[i]) return false
            return true
        }
    }

    /** Projected tuple/pattern buckets, built once per `(path, bound)` used by a rule-run gate. */
    private val projected = HashMap<Pair<String, Int>, Pair<Set<RepKey>, Set<List<Boolean>>>>()

    /** One ancestor level of a queried path that the caller's relevant set actually names. */
    private class Level(
        val prefix: String,
        val tuples: Set<RepKey>,
        val patterns: Set<List<Boolean>>,
    )

    /**
     * Per queried path, one entry per ancestor level — the level's bucket, or `null` where the relevant set names
     * nothing at that depth. **Model-static, so it is memoized by path**, which is what makes the query cheap: it
     * replaces, per relevance question and per depth, a `pathParts` split, a `take`, a `joinToString` that builds
     * the prefix string, and two `HashMap` probes to resolve that string back to its buckets.
     *
     * That string building, not the probe key IG128 first named, was the dominant cost: `StringBuilder.append`
     * was **16.95 %** of a partial-validation profile and the `joinToString` family ~10 % more, against ~2.8 % for
     * the list-key mechanics. Keying model-static data by path is not the defect IF284 removed — that one put a
     * repetition LIST in a key, so hashing it walked the list on every lookup. This key is the path `String`, whose
     * hash the platform has already cached, and it is the same shape as `Document.partsByPath`.
     */
    private val plans = HashMap<String, List<Level?>>()

    /** The reusable probe key. Safe because [Set.contains] never retains what it is given, and one index instance
     *  belongs to one call. */
    private val probe = RepKey(IntArray(INITIAL_PROBE_LEVELS), 0)

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
                    if (probe.reset(reps, pattern, depth) in level.tuples) return true
                }
            } else {
                val (projectedTuples, projectedPatterns) = projection(level, bound)
                for (pattern in projectedPatterns) {
                    if (probe.reset(reps, pattern, bound) in projectedTuples) return true
                }
            }
        }
        return false
    }

    private fun planFor(path: String): List<Level?> {
        val prefix = StringBuilder()
        return pathParts(path).map { part ->
            prefix.append('/').append(part)
            val at = prefix.toString()
            tuples[at]?.let { Level(at, it, patterns.getValue(at)) }
        }
    }

    private fun projection(level: Level, levels: Int): Pair<Set<RepKey>, Set<List<Boolean>>> =
        projected.getOrPut(level.prefix to levels) {
            level.tuples.mapTo(HashSet()) { it.truncatedTo(levels) } to
                level.patterns.mapTo(LinkedHashSet()) { it.take(levels) }
        }

    companion object {
        /** Deep enough for any A12 path in practice; [RepKey.reset] grows the buffer if one is deeper. */
        private const val INITIAL_PROBE_LEVELS = 8

        fun build(globalFields: Set<String>, relevant: Set<RelevantEntity>): EffectiveRelevanceIndex {
            val tuples = HashMap<String, HashSet<RepKey>>()
            val patterns = HashMap<String, MutableSet<List<Boolean>>>()
            for (entity in relevant) {
                if (entity.indices.size != pathParts(entity.path).size) continue
                tuples.getOrPut(entity.path) { HashSet() } +=
                    RepKey(entity.indices.toIntArray(), entity.indices.size)
                patterns.getOrPut(entity.path) { LinkedHashSet() } +=
                    entity.indices.map { it == RelevantEntity.ALL }
            }
            return EffectiveRelevanceIndex(globalFields, tuples, patterns)
        }
    }
}
