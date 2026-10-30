package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.parentGroup
import io.github.mbackschat.a12.dm.interpreter.pathParts
import io.github.mbackschat.a12.dm.interpreter.ast.Val

/**
 * The group a keyed star selects: the one directly above its first star, where that group lies at or below the
 * index group. The key picks the index group's row and every level between keeps the firing row's coordinate, so a
 * row the selected group lacks reads as a clean no-match (KERNEL-FINDINGS KF475, `adapter.laws.KeyedLowerRowDiffTest`).
 * A star on the index group itself, or a leaf group's star, selects the index group.
 */
internal fun keyedStarSelectionGroup(star: Expr.Star, indexField: String): String {
    val indexGroup = parentGroup(indexField)
    val aboveStar = star.bindDepth.takeIf { it >= 0 }?.let { depth -> "/" + pathParts(star.path).take(depth).joinToString("/") }
    return aboveStar?.takeIf { it == indexGroup || it.startsWith("$indexGroup/") } ?: indexGroup
}

/**
 * The cells a keyed operand contributes to `FieldValuesNotUnique`, or null when [a] is not keyed. A keyed star is
 * read by [cells] on the interpreter of the row its key selects, never the caller's; a keyed field compares the one cell its key selects, so a row the
 * selected group lacks compares nothing (KERNEL-FINDINGS KF475, `adapter.laws.KeyedLowerRowDiffTest`).
 */
internal fun Interpreter.keyedOperandCells(a: Expr, cells: Interpreter.(Expr) -> Sequence<Val>): Sequence<Val>? = when {
    a.isIndexedStar() -> withIndexedStar<Sequence<Val>>(a as Expr.EntitySpec, sequenceOf(Val.Unknown), cells)
    a is Expr.SemanticIndex && !isKeyedGroup(a) -> listOfNotNull(semanticIndexCell(a)).asSequence()
    else -> null
}
