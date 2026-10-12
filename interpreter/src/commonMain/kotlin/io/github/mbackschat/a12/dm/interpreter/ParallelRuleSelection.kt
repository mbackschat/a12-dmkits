package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity

/** Partial parallel dispatch compresses error selectors while operand relevance keeps the caller's set (SPEC10). */
internal class ParallelRuleSelection(private val model: ModelDef, private val relevant: Set<RelevantEntity>, private val paths: ModelPathIndex) {
    private val selectors = HashMap<String, List<Int>?>()

    private fun selector(path: String): List<Int>? = selectors.getOrPut(path) {
        val through = paths.groupsThrough(path)
        val depth = through.size
        if (path in model.globalFields) List(depth) { 0 } else {
            val inputs = relevant.filter { it.path in through }
                .filter { it.indices.size == pathParts(it.path).size }
            if (inputs.isEmpty()) null else List(depth) { level ->
                val values = inputs.map { it.indices.getOrElse(level) { 0 } }.distinct()
                values.singleOrNull() ?: 0
            }
        }
    }

    private fun ordinaryDepth(plan: IterationPlan.Parallel): Int =
        pathParts(plan.deepestFrame ?: plan.commonParent).size

    private fun hasOrdinaryIteration(plan: IterationPlan.Parallel): Boolean =
        plan.deepestFrame != null || (plan.commonParent in model.entityPaths &&
            paths.groupsThrough(plan.commonParent).any { it in model.repeatable })

    fun admits(path: String, plan: IterationPlan.Parallel): Boolean {
        val selected = selector(path) ?: return false
        if (!hasOrdinaryIteration(plan)) return true
        return plan.joined.filter { it in paths.ancestorsOf(path) }.all { selected[pathParts(it).size - 1] == 0 }
    }

    fun includes(path: String, plan: IterationPlan.Parallel, ctx: EvalContext): Boolean {
        val selected = selector(path) ?: return false
        if (!hasOrdinaryIteration(plan)) return true
        val reps = ctx.repsOf(path)
        return (0 until minOf(ordinaryDepth(plan), selected.size)).all { selected[it] == 0 || selected[it] == reps[it] }
    }
}
