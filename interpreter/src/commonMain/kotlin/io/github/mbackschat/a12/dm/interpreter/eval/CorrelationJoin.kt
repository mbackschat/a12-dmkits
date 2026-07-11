package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr

/** A recognized `$` equality-correlation `Having`: aligned inner/outer field paths of its equality conjuncts
 *  (`[innerFields[i]] == [$outerFields[i]]`) + whether a `CurrentRepetition(G) != CurrentRepetition($G)`
 *  self-exclusion is present (which drops the outer row from its own bucket). */
internal class JoinSpec(val innerFields: List<String>, val outerFields: List<String>, val selfExcluded: Boolean)

/**
 * Pure recognition of a starred filter quantifier's `Having` as a `$`-equality self-correlation — the AST-only
 * half of the interpreter's hash-join fast path. Strategy-agnostic runtime semantics: it operates only on the
 * parsed [Cond]/[Expr], with NO `EvalContext` / document walk (the IF10 runtime-vs-strategy split, sibling of
 * [Aggregates] / [ValCompare] / [CorrelationJoin]'s consumer `Interpreter.hashJoinTally`). The resolution-coupled
 * tally that *uses* the recognized [JoinSpec] stays in the strategy; a future generator recognizes the join the
 * same way.
 */
internal object CorrelationJoin {

    /** Recognize a `Having` as a PURE equality self-correlation → the hash-join fast path; null otherwise (→ the
     *  O(N²) fallback). Must be a conjunction of: 0-or-1 self-exclusion `CurrentRepetition(G) !=
     *  CurrentRepetition($G)` and ≥1 equality `[A] == [$B]` (a plain field == its `$` outer counterpart), and
     *  NOTHING else — so its exact semantics reduce to a value join (any other shape keeps the general scan). */
    fun parse(having: Cond, group: String): JoinSpec? {
        val inner = mutableListOf<String>()
        val outer = mutableListOf<String>()
        var selfExcluded = false
        for (c in flattenAnd(having)) {
            if (isSelfExclusion(c, group)) { selfExcluded = true; continue }
            val pair = asEqualityJoin(c) ?: return null
            inner.add(pair.first); outer.add(pair.second)
        }
        return if (inner.isEmpty()) null else JoinSpec(inner, outer, selfExcluded)
    }

    private fun flattenAnd(c: Cond): List<Cond> =
        if (c is Cond.And) flattenAnd(c.left) + flattenAnd(c.right) else listOf(c)

    /** `(groupPath, isOuter)` if [e] is `CurrentRepetition(<groupRef>)`, else null. */
    private fun currentRepOf(e: Expr): Pair<String, Boolean>? {
        if (e !is Expr.Func || FunctionOp.byName(e.name) != FunctionOp.CURRENT_REPETITION) return null
        val g = e.args.singleOrNull() as? Expr.Field ?: return null
        return g.path to g.outer
    }

    /** Whether [c] is `CurrentRepetition(G) != CurrentRepetition($G)` over the star's [group] (either order). */
    private fun isSelfExclusion(c: Cond, group: String): Boolean {
        if (c !is Cond.Cmp || c.op != CmpOp.NE) return false
        val l = currentRepOf(c.left) ?: return false
        val r = currentRepOf(c.right) ?: return false
        return l.first == group && r.first == group && l.second != r.second
    }

    /** `(innerFieldPath, outerFieldPath)` if [c] is `[A] == [$B]` — one plain field vs one `$` outer field
     *  (either operand order); null otherwise. */
    private fun asEqualityJoin(c: Cond): Pair<String, String>? {
        if (c !is Cond.Cmp || c.op != CmpOp.EQ) return null
        val l = c.left as? Expr.Field ?: return null
        val r = c.right as? Expr.Field ?: return null
        return when {
            !l.outer && r.outer -> l.path to r.path
            l.outer && !r.outer -> r.path to l.path
            else -> null
        }
    }
}
