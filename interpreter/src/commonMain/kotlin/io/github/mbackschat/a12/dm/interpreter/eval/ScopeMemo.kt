package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Val

/** One validation call's results that depend on a star's scope alone, so every row of that scope shares them. */
internal class ScopeMemo {
    val aggregates = HashMap<AggregateMemoKey, Val>()
    val fillScopes = HashMap<FillScopeMemoKey, FillScope>()
}

/** A validation-call cache key for an unfiltered starred fill-quantifier operand and the scope it ranges over. */
internal data class FillScopeMemoKey(
    val path: String,
    val bindDepth: Int,
    val scopePrefix: List<Int>,
)
