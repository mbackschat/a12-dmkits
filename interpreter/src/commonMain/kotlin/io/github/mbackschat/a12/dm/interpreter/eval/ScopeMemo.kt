package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/** One validation call's results that depend on a star's scope alone, so every row of that scope shares them. */
internal class ScopeMemo {
    val aggregates = HashMap<AggregateMemoKey, Val>()
    val fillScopes = HashMap<FillScopeMemoKey, FillScope>()

    /** A correlated scope's rows holding an unavailable empty cell. They are rows of this call's document, judged
     *  by its relevance and formal results, so they live here and never on the reusable [EvalConfig]. */
    val unavailableRows = HashMap<List<Any>, UnavailableRows>()
}

/** A validation-call cache key for an unfiltered starred fill-quantifier operand and the scope it ranges over. */
internal data class FillScopeMemoKey(
    val path: String,
    val bindDepth: Int,
    val scopePrefix: List<Int>,
)
