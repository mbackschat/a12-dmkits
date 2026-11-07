package io.github.mbackschat.a12.dm.predicates

/**
 * The spelling A12 compares and reports a rule's possible-cause entry in (KERNEL-FINDINGS KF682): an empty path is
 * `./`, and a path ending in `..` gains a trailing `/`, a name ending in `..` included (`Premium..` is `Premium../`,
 * `ElementPathUtils.mapDmRelPathToDorRelPath`, source-read). Nothing else is normalized, so `./Premium`, `Premium/`
 * and ` Premium` stay distinct from `Premium`, and A12 admits none of them.
 */
fun possibleCauseSpelling(written: String): String = when {
    written.isEmpty() -> "./"
    written.endsWith("..") -> "$written/"
    else -> written
}

/**
 * The one entry A12 admits for [entity] in the possible-cause filter of a rule in [group], both absolute paths: the
 * shortest relative path from the group, up to the shared ancestor and down, in [possibleCauseSpelling].
 */
fun possibleCauseEntry(group: String, entity: String): String {
    val from = group.split('/').filter { it.isNotEmpty() }
    val to = entity.split('/').filter { it.isNotEmpty() }
    val shared = from.zip(to).takeWhile { (a, b) -> a == b }.size
    return possibleCauseSpelling((List(from.size - shared) { ".." } + to.drop(shared)).joinToString("/"))
}
