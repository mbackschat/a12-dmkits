package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr

/** Immutable recognition only; field kinds and time zone are qualified by the current interpreter. */
internal data class PreparedCorrelationShape(val plan: CorrelationPlan?, val multiStar: Boolean)

/** A recognized, indexable `$` self-correlation shape. */
internal sealed interface CorrelationPlan {
    val selfExcluded: Boolean
}

/**
 * A conjunction of equality joins (`[innerFields[i]] == [$outerFields[i]]`) served by keyed hash tallies.
 */
internal data class KeyedCorrelationPlan(
    val innerFields: List<String>,
    val outerFields: List<String>,
    override val selfExcluded: Boolean,
) : CorrelationPlan

/**
 * One normalized value comparison (`[innerField] [op] [$outerField]`) served by an ordered tally. [op] always
 * reads inner-to-outer even when the source condition wrote the operands in the opposite order.
 */
internal data class OrderedCorrelationPlan(
    val innerField: String,
    val outerField: String,
    val op: CmpOp,
    override val selfExcluded: Boolean,
) : CorrelationPlan

/**
 * Pure recognition of the indexable `$` self-correlation boundary. A `Having` is indexable only when it is a
 * conjunction of an optional `CurrentRepetition(G) != CurrentRepetition($G)` self-exclusion and either:
 *
 * - one or more plain-field/outer-field equality terms (a keyed join), or
 * - exactly one plain-field/outer-field `<`, `<=`, `>`, `>=`, or `!=` term (an ordered tally).
 *
 * Any extra predicate, expression operand, or multi-comparator range returns null and stays on the documented
 * general nested fallback. This AST-only classifier contains no document access; its consumer additionally checks
 * that an ordered field kind has the same comparison semantics as [ValCompare.language].
 */
internal object CorrelationJoin {

    fun parse(having: Cond, group: String): CorrelationPlan? {
        val comparisons = mutableListOf<Cond.Cmp>()
        var selfExcluded = false
        for (condition in flattenAnd(having)) {
            if (isSelfExclusion(condition, group)) {
                selfExcluded = true
                continue
            }
            comparisons += condition as? Cond.Cmp ?: return null
        }
        if (comparisons.isEmpty()) return null

        val equalities = comparisons.map { asEqualityJoin(it) }
        if (equalities.all { it != null }) {
            val joins = equalities.filterNotNull()
            return KeyedCorrelationPlan(
                joins.map { it.first },
                joins.map { it.second },
                selfExcluded,
            )
        }

        if (comparisons.size != 1) return null
        val comparison = asOrderedComparison(comparisons.single()) ?: return null
        return OrderedCorrelationPlan(
            comparison.innerField,
            comparison.outerField,
            comparison.op,
            selfExcluded,
        )
    }

    private fun flattenAnd(condition: Cond): List<Cond> =
        if (condition is Cond.And) flattenAnd(condition.left) + flattenAnd(condition.right) else listOf(condition)

    /** `(groupPath, isOuter)` if [expression] is `CurrentRepetition(<groupRef>)`, else null. */
    private fun currentRepOf(expression: Expr): Pair<String, Boolean>? {
        if (expression !is Expr.Func || FunctionOp.byName(expression.name) != FunctionOp.CURRENT_REPETITION) return null
        val group = expression.args.singleOrNull() as? Expr.Field ?: return null
        return group.path to group.outer
    }

    /** Whether [condition] is `CurrentRepetition(G) != CurrentRepetition($G)` over [group] (either order). */
    private fun isSelfExclusion(condition: Cond, group: String): Boolean {
        if (condition !is Cond.Cmp || condition.op != CmpOp.NE) return false
        val left = currentRepOf(condition.left) ?: return false
        val right = currentRepOf(condition.right) ?: return false
        return left.first == group && right.first == group && left.second != right.second
    }

    /** `(innerFieldPath, outerFieldPath)` for `[A] == [$B]`, in either operand order. */
    private fun asEqualityJoin(condition: Cond.Cmp): Pair<String, String>? {
        if (condition.op != CmpOp.EQ) return null
        val fields = asInnerOuterFields(condition.left, condition.right)
            ?: asInnerOuterFields(condition.right, condition.left)
            ?: return null
        return fields.inner to fields.outer
    }

    /** Normalize one field comparison so [NormalizedComparison.op] always compares inner to outer. */
    private fun asOrderedComparison(condition: Cond.Cmp): NormalizedComparison? {
        if (condition.op == CmpOp.EQ) return null
        val direct = asInnerOuterFields(condition.left, condition.right)
        if (direct != null) return NormalizedComparison(direct.inner, direct.outer, condition.op)
        val reversed = asInnerOuterFields(condition.right, condition.left) ?: return null
        return NormalizedComparison(reversed.inner, reversed.outer, reverse(condition.op))
    }

    private fun asInnerOuterFields(left: Expr, right: Expr): InnerOuterFields? {
        val inner = left as? Expr.Field ?: return null
        val outer = right as? Expr.Field ?: return null
        return if (!inner.outer && outer.outer) InnerOuterFields(inner.path, outer.path) else null
    }

    private fun reverse(op: CmpOp): CmpOp = when (op) {
        CmpOp.LT -> CmpOp.GT
        CmpOp.LE -> CmpOp.GE
        CmpOp.GT -> CmpOp.LT
        CmpOp.GE -> CmpOp.LE
        CmpOp.EQ -> CmpOp.EQ
        CmpOp.NE -> CmpOp.NE
    }

    private data class InnerOuterFields(val inner: String, val outer: String)
    private data class NormalizedComparison(val innerField: String, val outerField: String, val op: CmpOp)
}
