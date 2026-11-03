package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Val

/** One validation call's results that depend on a star's scope alone, so every row of that scope shares them. */
internal class ScopeMemo {
    val aggregates = HashMap<AggregateMemoKey, Val>()
    val fillScopes = HashMap<FillScopeMemoKey, FillScope>()
    val termSumFills = HashMap<AggregateMemoKey, Fill>()

    /** A correlated scope's rows holding an unavailable empty cell. They are rows of this call's document, judged
     *  by its relevance and formal results, so they live here and never on the reusable [EvalConfig]. */
    val unavailableRows = HashMap<List<Any>, UnavailableRows>()
}

/** A validation-call cache key for a starred aggregate value, a `Func` or a `TermSum`, and the exact star scopes it
 *  ranges over. */
internal data class AggregateMemoKey(
    val expression: Expr,
    val scopePrefixes: List<List<Int>>,
)

/**
 * The memo key of a starred reader whose value depends on its [stars]' scopes alone, or null where it may not be
 * shared across the rows of one scope: in a computation, whose overlay changes between reads; under a filter or a
 * `$` star, which may read the evaluating row; and, for a reader that [asksGroupCountGate], under partial
 * validation, where the gate decides inside the fold by its own bound prefix, which is not assumed to be the key's.
 * `NumberOfFilledGroups` and `SumOfTerms` are the readers that ask it.
 */
internal fun Interpreter.scopeInvariantMemoKey(
    expression: Expr,
    stars: List<Expr.Star>,
    asksGroupCountGate: Boolean,
): AggregateMemoKey? {
    if (computeMode || asksGroupCountGate && groupCountGate != null) return null
    if (stars.any { it.having != null || it.outer }) return null
    return AggregateMemoKey(expression, stars.map { star -> starScopePrefix(ctxOf(star), star) })
}

/** A validation-call cache key for an unfiltered starred fill-quantifier operand and the scope it ranges over. */
internal data class FillScopeMemoKey(
    val path: String,
    val bindDepth: Int,
    val scopePrefix: List<Int>,
)
