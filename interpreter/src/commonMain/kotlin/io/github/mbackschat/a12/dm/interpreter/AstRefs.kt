package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr

// Pure AST reference walks shared by the I2 seam collaborators (IF117) — which field/star paths a
// condition or expression references, in the two views the engine needs: the star-free SCOPE view
// (a starred ref is an aggregate and does not drive iteration) and the full cascade-DEPENDENCY view.

/** Every field/star path an expression reads — for cascade dependency ordering: starred aggregates count,
 *  and a star's `Having` FILTER refs count too (the filter reads the cascade — a `Sum(… Having [flag] ==
 *  True)` over a computed flag must run after the flag's computation, IF98). */
internal fun referencedFieldPaths(expr: Expr): List<String> = when (expr) {
    is Expr.Field -> listOf(expr.path)
    is Expr.Star -> listOf(expr.path) + (expr.having?.let { condDepRefs(it) } ?: emptyList())
    is Expr.Func -> expr.args.flatMap { referencedFieldPaths(it) }
    is Expr.Arith -> referencedFieldPaths(expr.left) + referencedFieldPaths(expr.right)
    is Expr.EnumCategory -> listOf(expr.path)
    is Expr.SemanticIndex -> listOfNotNull(expr.path, expr.indexFieldPath)
    else -> emptyList()
}

/** ALL field/star refs of a condition — the cascade-DEPENDENCY view of a precondition (its quantifier/
 *  aggregate stars and their `Having` refs count, unlike the star-free SCOPE view [nonStarredRefs]). */
internal fun condDepRefs(cond: Cond): List<String> = when (cond) {
    is Cond.Cmp -> referencedFieldPaths(cond.left) + referencedFieldPaths(cond.right)
    is Cond.Tolerance -> referencedFieldPaths(cond.left) + referencedFieldPaths(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { referencedFieldPaths(it) }
    is Cond.And -> condDepRefs(cond.left) + condDepRefs(cond.right)
    is Cond.Or -> condDepRefs(cond.left) + condDepRefs(cond.right)
    is Cond.Not -> condDepRefs(cond.c)
    is Cond.Pred -> cond.args.flatMap { referencedFieldPaths(it) }
    is Cond.Pattern -> referencedFieldPaths(cond.value) + referencedFieldPaths(cond.pattern)
    is Cond.Custom -> emptyList()
}

/** Every starred operand of the condition — the star side of the SCOPE view (IF127): a star's BINDING
 *  levels (the segments STRICTLY ABOVE its first `*`-marked group) carry the kernel's per-row ITERATION
 *  marker and drive iteration exactly like a non-starred ref's repeatable ancestors, while the starred
 *  level and everything below are a WILDCARD collapse and never do. The walk does NOT descend into
 *  [Expr.Star.having] — the kernel excludes filter-condition refs from iteration analysis (a `$`-outer
 *  ref correlates to the very outer row being determined). */
internal fun starRefs(cond: Cond): List<Expr.Star> = when (cond) {
    is Cond.Cmp -> exprStars(cond.left) + exprStars(cond.right)
    is Cond.Tolerance -> exprStars(cond.left) + exprStars(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { exprStars(it) }
    is Cond.And -> starRefs(cond.left) + starRefs(cond.right)
    is Cond.Or -> starRefs(cond.left) + starRefs(cond.right)
    is Cond.Not -> starRefs(cond.c)
    is Cond.Pred -> cond.args.flatMap { exprStars(it) }
    is Cond.Pattern -> exprStars(cond.value) + exprStars(cond.pattern)
    is Cond.Custom -> emptyList()
}

internal fun exprStars(expr: Expr): List<Expr.Star> = when (expr) {
    is Expr.Star -> listOf(expr)
    is Expr.Func -> expr.args.flatMap { exprStars(it) }
    is Expr.Arith -> exprStars(expr.left) + exprStars(expr.right)
    else -> emptyList()
}

/** Every non-starred field-reference path in the condition (Star refs are aggregates, excluded). */
internal fun nonStarredRefs(cond: Cond): List<String> = when (cond) {
    is Cond.Cmp -> exprRefs(cond.left) + exprRefs(cond.right)
    is Cond.Tolerance -> exprRefs(cond.left) + exprRefs(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { exprRefs(it) }
    is Cond.And -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
    is Cond.Or -> nonStarredRefs(cond.left) + nonStarredRefs(cond.right)
    is Cond.Not -> nonStarredRefs(cond.c)
    is Cond.Pred -> cond.args.flatMap { exprRefs(it) }
    is Cond.Pattern -> exprRefs(cond.value) + exprRefs(cond.pattern)
    is Cond.Custom -> emptyList()   // the name references no field in the text — the rule is guarded by a field ref
}

/**
 * Every ordinary message reference, excluding the key arguments of a `RepetitionNotUnique` leaf. RNU projects
 * those keys from its duplicate-cluster cache instead of resolving only the current row; the same path used by
 * another branch still appears through that branch's own walk.
 */
internal fun nonRnuMessageRefs(cond: Cond): List<String> = when (cond) {
    is Cond.Cmp -> exprRefs(cond.left) + exprRefs(cond.right)
    is Cond.Tolerance -> exprRefs(cond.left) + exprRefs(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { exprRefs(it) }
    is Cond.And -> nonRnuMessageRefs(cond.left) + nonRnuMessageRefs(cond.right)
    is Cond.Or -> nonRnuMessageRefs(cond.left) + nonRnuMessageRefs(cond.right)
    is Cond.Not -> nonRnuMessageRefs(cond.c)
    is Cond.Pred -> if (cond.name == "RepetitionNotUnique") emptyList() else cond.args.flatMap { exprRefs(it) }
    is Cond.Pattern -> exprRefs(cond.value) + exprRefs(cond.pattern)
    is Cond.Custom -> emptyList()
}

internal fun exprRefs(expr: Expr): List<String> = when (expr) {
    is Expr.Field -> listOf(expr.path)
    is Expr.Func -> expr.args.flatMap { exprRefs(it) }   // Star args excluded → drop through
    is Expr.Arith -> exprRefs(expr.left) + exprRefs(expr.right)
    is Expr.EnumCategory -> listOf(expr.path)            // `[field -> cat]` references the enum field
    // `[F For keyField]`: the KEY field is a per-row ref (drives iteration + is a referenced field); the
    // target `F` is an absolute semantic lookup (a specific repetition), not a per-row ref. A literal key
    // (`[F For "v"]`) references no field.
    is Expr.SemanticIndex -> expr.indexFieldPath?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
}

internal fun condRefsOrEmpty(cond: Cond?): List<String> = cond?.let { nonStarredRefs(it) } ?: emptyList()
