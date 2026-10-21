package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * A document's content keyed by the model path it lies under, so that a question about one group reads only the
 * nodes along that group's path instead of every placement.
 *
 * Each node at depth `d` records, for every index prefix of length `d` its subtree holds, which branch holds it:
 * the content placed at the node itself, one child, or more than one. That is exactly what the attach gate needs,
 * because a placement whose path leaves the group's path after `d` shared parts makes the group's rows with the
 * same first `d` indices evaluated, and a placement that follows the path further is counted deeper instead.
 */
internal class ContentTrie<T : Any> {
    private val root = Node<T>()

    fun add(parts: List<String>, reps: List<Int>, item: T) {
        var node = root
        for (depth in 1..parts.size) {
            node = node.children.getOrPut(parts[depth - 1]) { Node() }
            val branch = if (depth == parts.size) SELF else parts[depth]
            // A view, not a copy: every placement's indices live as long as the document does.
            val key = reps.subList(0, depth)
            val held = node.branches[key]
            if (held == null) node.branches[key] = branch else if (held != branch) node.branches[key] = MANY
        }
        node.here += item
    }

    /** The gate for the group whose path is [groupParts]: the nodes on its path, each with the child it continues into. */
    fun gateFor(groupParts: List<String>): AttachGate {
        val levels = ArrayList<AttachGate.Level>()
        var node = root
        for (depth in 1..groupParts.size) {
            node = node.children[groupParts[depth - 1]] ?: break
            levels += AttachGate.Level(depth, node.branches, if (depth < groupParts.size) groupParts[depth] else null)
        }
        return AttachGate(levels)
    }

    /** Every item placed strictly below the node at [parts], in no particular order. */
    fun forEachBelow(parts: List<String>, action: (T) -> Unit) {
        var node = root
        for (part in parts) node = node.children[part] ?: return
        val pending = ArrayDeque(node.children.values)
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            next.here.forEach(action)
            pending.addAll(next.children.values)
        }
    }

    fun nodeCount(): Int {
        var count = 0
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            count += 1 + next.branches.size
            pending.addAll(next.children.values)
        }
        return count
    }

    private class Node<T> {
        val children = HashMap<String, Node<T>>()
        val branches = HashMap<List<Int>, String>()
        val here = ArrayList<T>()
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
