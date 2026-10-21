package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity

/**
 * Relevant entities indexed by their coordinates, so the entities addressing one row are found along that row's own
 * branch and the wildcard's, instead of by a scan of every entity. A relevant set that names every row grows with the
 * document, and a scan per row then costs O(rows²) (INTERPRETER-FINDINGS IF412, `GrowingRelevancePartialPerfTest`).
 *
 * An entity addresses a row's bound coordinates when every index it carries there is [RelevantEntity.ALL] or the
 * row's own; a level the entity does not carry agrees with any row. [addressing] keeps the entities' given order.
 */
internal class RowAddressIndex(private val entities: List<RelevantEntity>) {
    private class Node {
        val children = HashMap<Int, Node>()
        val ending = ArrayList<Int>()
        val through = ArrayList<Int>()
    }

    private val root = Node()

    init {
        entities.forEachIndexed { ordinal, entity ->
            var node = root
            node.through += ordinal
            for (index in entity.indices) {
                node = node.children.getOrPut(index) { Node() }
                node.through += ordinal
            }
            node.ending += ordinal
        }
    }

    fun addressing(bound: List<Int>): List<RelevantEntity> {
        val found = ArrayList<Int>()
        fun walk(node: Node, level: Int) {
            if (level == bound.size) {
                found += node.through
                return
            }
            found += node.ending
            node.children[bound[level]]?.let { walk(it, level + 1) }
            if (bound[level] != RelevantEntity.ALL) node.children[RelevantEntity.ALL]?.let { walk(it, level + 1) }
        }
        walk(root, 0)
        found.sort()
        return found.map(entities::get)
    }
}

/** Relevant entities' index lists as a trie, so a repetition asks only its own branch and the wildcard's. */
internal class IndexTrie {
    private val children = HashMap<Int, IndexTrie>()
    private var ends = false

    fun add(indices: List<Int>) {
        var node = this
        for (index in indices) node = node.children.getOrPut(index) { IndexTrie() }
        node.ends = true
    }

    /** Whether some list agrees with [reps] on every level both carry, each entry [RelevantEntity.ALL] or equal. */
    fun agreesWithin(reps: List<Int>, level: Int = 0): Boolean = when {
        ends -> true
        level >= reps.size -> children.isNotEmpty()
        else -> children[reps[level]]?.agreesWithin(reps, level + 1) == true ||
            (reps[level] != RelevantEntity.ALL && children[RelevantEntity.ALL]?.agreesWithin(reps, level + 1) == true)
    }

    /** Whether some list of exactly [depth] entries agrees with the first [depth] of [reps]. */
    fun selects(reps: List<Int>, depth: Int, level: Int = 0): Boolean = when {
        level == depth -> ends
        else -> children[reps[level]]?.selects(reps, depth, level + 1) == true ||
            (reps[level] != RelevantEntity.ALL && children[RelevantEntity.ALL]?.selects(reps, depth, level + 1) == true)
    }
}
