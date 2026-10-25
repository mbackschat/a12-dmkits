package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity

/**
 * Which rows a partial call runs a rule for. A12 merges every relevant pointer of the rule's error field, its
 * ancestor groups included, into ONE selector, and a coordinate the pointers disagree on becomes a wildcard
 * (KF621, KF544). Dispatch follows that selector, so it can run rows no pointer names; operand reads still use the
 * caller's set, and a non-relevant cell on such a row reads as unknown. This class owns dispatch only.
 */
internal class PartialRuleDispatch(private val model: ModelDef, private val relevant: Set<RelevantEntity>, private val paths: ModelPathIndex) {
    private val selectors = HashMap<String, List<Int>?>()

    private fun selector(path: String): List<Int>? = selectors.getOrPut(path) {
        // The path's own prefixes, not the prepared index: a low-level model need not declare every group.
        val parts = pathParts(path)
        val through = parts.indices.mapTo(HashSet()) { "/" + parts.subList(0, it + 1).joinToString("/") }
        val depth = parts.size
        if (path in model.globalFields) List(depth) { 0 } else {
            val inputs = relevant.filter { it.path in through }
                .filter { it.indices.size == pathParts(it.path).size }
            if (inputs.isEmpty()) null else List(depth) { level ->
                val values = inputs.map { it.indices.getOrElse(level) { 0 } }.distinct()
                values.singleOrNull() ?: 0
            }
        }
    }

    private fun matches(selected: List<Int>, reps: List<Int>, levels: Int): Boolean =
        (0 until minOf(levels, selected.size, reps.size)).all { selected[it] == 0 || selected[it] == reps[it] }

    /** An ordinary rule runs at [ctx] when the selector admits it at every level the iteration BOUND; a level
     *  the iteration never bound counts as a wildcard ([IF268]). */
    fun runsForRow(path: String, ctx: EvalContext): Boolean {
        val selected = selector(path) ?: return false
        return matches(selected, ctx.repsOf(path), ctx.boundLevelsOf(path))
    }

    /** The deepest level a parallel rule iterates as an ordinary repetition: a level below the joined group
     *  ([IterationPlan.Parallel.nested], KF603), else its frame or common parent. */
    private fun ordinaryDepth(plan: IterationPlan.Parallel): Int =
        pathParts(plan.nested ?: plan.deepestFrame ?: plan.commonParent).size

    private fun hasOrdinaryIteration(plan: IterationPlan.Parallel): Boolean =
        plan.nested != null || plan.deepestFrame != null || (plan.commonParent in model.entityPaths &&
            paths.groupsThrough(plan.commonParent).any { it in model.repeatable })

    /** A parallel rule with ordinary iteration runs only when the selector wildcards each joined level; one
     *  without it runs whenever its error field has a selector at all, whatever the coordinates (KF621). */
    fun admits(path: String, plan: IterationPlan.Parallel): Boolean {
        val selected = selector(path) ?: return false
        if (!hasOrdinaryIteration(plan)) return true
        return plan.joined.filter { it in paths.ancestorsOf(path) }.all { selected[pathParts(it).size - 1] == 0 }
    }

    fun includes(path: String, plan: IterationPlan.Parallel, ctx: EvalContext): Boolean {
        val selected = selector(path) ?: return false
        if (!hasOrdinaryIteration(plan)) return true
        return matches(selected, ctx.repsOf(path), ordinaryDepth(plan))
    }
}
