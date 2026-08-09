package io.github.mbackschat.a12.dm.interpreter

/**
 * One row presented to the index-identity stage after the caller has classified admission, relevance, and cache
 * membership. A non-null [key] joins the equality relation; [compromises] means the column cannot answer an
 * unmatched read as definitely empty. A row can have neither, for example an over-limit row that never entered
 * the kernel's evaluation cache.
 */
internal data class IndexIdentityCandidate<K : Any>(
    val repetitions: List<Int>,
    val key: K?,
    val compromises: Boolean,
)

/** The complete collision result shared by index consumers. Duplicated keys are absent from [uniqueRowsByKey]. */
internal data class IndexIdentityResolution<K : Any>(
    val uniqueRowsByKey: Map<K, List<Int>>,
    val duplicatedKeys: Set<K>,
    val duplicateRows: Set<List<Int>>,
    val compromised: Boolean,
)

/**
 * Resolve one index column after per-cell classification. This is deliberately free of Document/formal/relevance
 * policy: callers own which rows reach the stage, but no caller may implement key collision or ambiguity again.
 */
internal fun <K : Any> resolveIndexIdentity(
    candidates: Iterable<IndexIdentityCandidate<K>>,
): IndexIdentityResolution<K> {
    val rowsByKey = LinkedHashMap<K, MutableList<List<Int>>>()
    var compromised = false
    for (candidate in candidates) {
        if (candidate.compromises) compromised = true
        val key = candidate.key ?: continue
        rowsByKey.getOrPut(key) { ArrayList(1) } += candidate.repetitions
    }

    val unique = LinkedHashMap<K, List<Int>>()
    val duplicated = LinkedHashSet<K>()
    val duplicateRows = LinkedHashSet<List<Int>>()
    for ((key, rows) in rowsByKey) {
        if (rows.size == 1) {
            unique[key] = rows.single()
        } else {
            compromised = true
            duplicated += key
            duplicateRows += rows
        }
    }
    return IndexIdentityResolution(unique, duplicated, duplicateRows, compromised)
}
