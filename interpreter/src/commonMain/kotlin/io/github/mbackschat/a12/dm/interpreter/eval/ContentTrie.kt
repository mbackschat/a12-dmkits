package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * A document's content keyed by the model path it lies under, so that a question about one group reads only the
 * nodes along that group's path instead of every placement.
 *
 * Each node at depth `d` answers, for every index prefix of length `d` its subtree holds, which branch holds it:
 * the content placed at the node itself, one child, or more than one. That is exactly what the attach gate needs,
 * because a placement whose path leaves the group's path after `d` shared parts makes the group's rows with the
 * same first `d` indices evaluated, and a placement that follows the path further is counted deeper instead.
 *
 * A node builds that answer only when a gate first asks for it, from its own subtree. Building it for every node
 * up front made each call on a fresh document pay for nodes no gate reads: `testRule` and `applyComputations`,
 * which index a new document per call, ran 5–10 % slower than the per-group scan this replaced (measured
 * 2026-10-21, interleaved against `58201827b`).
 */
internal class ContentTrie<T : Any>(private val repsOf: (T) -> List<Int>) {
    private val root = Node<T>(depth = 0)
    private val nodeByPath = HashMap<String, Node<T>>()

    fun add(path: String, parts: List<String>, item: T) {
        nodeByPath.getOrPut(path) {
            parts.fold(root) { node, part -> node.children.getOrPut(part) { Node(node.depth + 1) } }
        }.here += item
    }

    /** The gate for the group whose path is [groupParts]: the nodes on its path, each with the child it continues into. */
    fun gateFor(groupParts: List<String>): AttachGate {
        val levels = ArrayList<AttachGate.Level>()
        var node = root
        for (depth in 1..groupParts.size) {
            node = node.children[groupParts[depth - 1]] ?: break
            levels += AttachGate.Level(depth, branchesOf(node), if (depth < groupParts.size) groupParts[depth] else null)
        }
        return AttachGate(levels)
    }

    /** Every item placed strictly below the node at [parts], in no particular order. */
    fun forEachBelow(parts: List<String>, action: (T) -> Unit) {
        var node = root
        for (part in parts) node = node.children[part] ?: return
        for (child in node.children.values) child.forEachInSubtree(action)
    }

    fun nodeCount(): Int {
        var count = 0
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            count += 1 + (next.branches?.size ?: 0)
            pending.addAll(next.children.values)
        }
        return count
    }

    private fun branchesOf(node: Node<T>): Map<List<Int>, String> = node.branches ?: HashMap<List<Int>, String>().also { held ->
        record(held, node.depth, SELF) { node.here.forEach(it) }
        for ((name, child) in node.children) record(held, node.depth, name) { child.forEachInSubtree(it) }
        node.branches = held
    }

    private inline fun record(held: HashMap<List<Int>, String>, depth: Int, branch: String, items: ((T) -> Unit) -> Unit) {
        var previous: List<Int>? = null
        items { item ->
            val reps = repsOf(item)
            // One branch's placements arrive path by path, so a run of them often shares this depth's prefix: the
            // same key with the same branch, already recorded.
            if (previous?.let { sharesPrefix(it, reps, depth) } != true) {
                // A view, not a copy: every placement's indices live as long as the document does.
                val key = reps.subList(0, depth)
                val was = held[key]
                if (was == null) held[key] = branch else if (was != branch) held[key] = MANY
                previous = reps
            }
        }
    }

    private fun sharesPrefix(a: List<Int>, b: List<Int>, depth: Int): Boolean {
        for (i in 0 until depth) if (a[i] != b[i]) return false
        return true
    }

    private class Node<T>(val depth: Int) {
        val children = HashMap<String, Node<T>>()
        val here = ArrayList<T>()
        var branches: Map<List<Int>, String>? = null

        fun forEachInSubtree(action: (T) -> Unit) {
            val pending = ArrayDeque(listOf(this))
            while (pending.isNotEmpty()) {
                val next = pending.removeLast()
                next.here.forEach(action)
                pending.addAll(next.children.values)
            }
        }
    }

    internal companion object {
        // Neither can be a path part: parts are the non-empty, slash-free segments of a model path.
        const val SELF = ""
        const val MANY = "/"
    }
}

/** Whether a row of one group is evaluated: some content shares its indices up to where that content leaves the group's path. */
internal class AttachGate(private val levels: List<Level>) {
    class Level(val depth: Int, val branches: Map<List<Int>, String>, val continuesInto: String?)

    fun admits(reps: List<Int>): Boolean = levels.any { level ->
        val held = branches(level, reps) ?: return@any false
        // At the group's own node every branch is in scope; above it, only content that leaves the path there.
        // MANY always includes such content, and differs from every part as SELF does.
        level.continuesInto == null || held != level.continuesInto
    }

    private fun branches(level: Level, reps: List<Int>): String? =
        if (reps.size < level.depth) null else level.branches[reps.subList(0, level.depth)]
}
