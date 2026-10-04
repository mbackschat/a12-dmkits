package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.PredicateOp
import io.github.mbackschat.a12.dm.interpreter.model.ComputationDef
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/** Scheduling excludes protected producers from positive containing-group reads, never from operands. */
internal class ComputationDependencies(
    private val model: ModelDef,
    private val parsed: (ComputationDef) -> ParsedComputation,
) {
    private val profiles: Map<String, Set<String>> by lazy {
        model.computations.groupBy { it.targetPath }.mapValues { (target, computations) ->
            val conditions = computations.flatMap { comp ->
                val body = parsed(comp)
                val implicit = model.repeatable.filter { target.startsWith("$it/") }.maxByOrNull { it.length }
                body.alternatives.mapNotNull { alt ->
                    val present = listOfNotNull(body.commonPrecondition, alt.precondition)
                    if (present.isEmpty() && implicit == null) null
                    else present.flatMap { protectedGroups(it, target) }.toSet() + ancestors(implicit)
                }
            }
            conditions.reduceOrNull { left, right -> left intersect right }.orEmpty()
        }
    }

    fun reads(comp: ComputationDef): List<String> {
        val body = parsed(comp)
        return listOfNotNull(body.commonPrecondition).flatMap { reads(it, comp.targetPath) } +
            body.alternatives.flatMap { alt ->
                alt.precondition?.let { reads(it, comp.targetPath) }.orEmpty() +
                    referencedFieldPaths(alt.operation).flatMap(::expand)
            }
    }

    private fun reads(condition: Cond, target: String): List<String> = when (condition) {
        is Cond.And -> reads(condition.left, target) + reads(condition.right, target)
        is Cond.Or -> reads(condition.left, target) + reads(condition.right, target)
        is Cond.Not -> reads(condition.c, target)
        else -> {
            val group = positiveContainingGroup(condition, target)
            if (group == null) condDepRefs(condition).flatMap(::expand)
            else expand(group).filter { it != target && group !in profiles[it].orEmpty() }
        }
    }

    private fun protectedGroups(condition: Cond, target: String): Set<String> = when (condition) {
        is Cond.And -> protectedGroups(condition.left, target) + protectedGroups(condition.right, target)
        is Cond.Or -> protectedGroups(condition.left, target) intersect protectedGroups(condition.right, target)
        else -> ancestors(positiveContainingGroup(condition, target))
    }

    /**
     * The group a positive presence guard names when it contains [target]: `GroupFilled`/`AllGroupsFilled` over one
     * group, or `AtLeastOneGroupFilled` over one ROOT group, the only list form A12 admits over a group containing the
     * computed field (SPEC-2026-10-03-05, `adapter.laws.RootGroupGuardDependencyDiffTest`).
     */
    private fun positiveContainingGroup(condition: Cond, target: String): String? {
        if (condition !is Cond.Pred) return null
        val group = (condition.args.singleOrNull() as? Expr.Field)?.path ?: return null
        val admitted = when (PredicateOp.byName(condition.name)) {
            PredicateOp.GROUP_FILLED, PredicateOp.ALL_GROUPS_FILLED -> true
            PredicateOp.AT_LEAST_ONE_GROUP_FILLED -> group.trim('/').split('/').size == 1
            else -> false
        }
        return group.takeIf { admitted && it in model.groupPaths && target.startsWith("$it/") }
    }

    private fun ancestors(group: String?): Set<String> = if (group == null) emptySet()
        else model.groupPaths.filterTo(mutableSetOf()) { it == group || group.startsWith("$it/") }

    private fun expand(path: String): List<String> = if (path in model.kinds) listOf(path)
        else model.kinds.keys.filter { it.startsWith("$path/") }
}
