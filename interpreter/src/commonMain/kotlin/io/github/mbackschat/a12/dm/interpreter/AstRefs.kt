package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.ast.CmpOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.eval.FunctionOp

// Pure AST reference walks shared by the I2 seam collaborators (IF117) — which field/star paths a
// condition or expression references, in the two views the engine needs: the star-free SCOPE view
// (a starred ref is an aggregate and does not drive iteration) and the full cascade-DEPENDENCY view.

/** Every field/star path an expression reads — for cascade dependency ordering: starred aggregates count,
 *  and a star's `Having` FILTER refs count too (the filter reads the cascade — a `Sum(… Having [flag] ==
 *  True)` over a computed flag must run after the flag's computation, IF98). */
internal fun referencedFieldPaths(expr: Expr): List<String> = when (expr) {
    is Expr.Field -> listOf(expr.path)
    is Expr.Star -> listOf(expr.path) + (expr.having?.let { condDepRefs(it) } ?: emptyList())
    // CurrentRepetition reads a structural row coordinate, not the values contained by its group operand.
    // Treating that group path as a cell dependency makes expandGroupDependency invent edges to every
    // computation below the group and can manufacture cycles between otherwise independent computations.
    is Expr.Func -> if (FunctionOp.byName(expr.name) == FunctionOp.CURRENT_REPETITION) {
        emptyList()
    } else {
        expr.args.flatMap { referencedFieldPaths(it) }
    }
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
    is Cond.Custom -> emptyList()   // the name carries no textual ref; the complete rule must reference its error field elsewhere
}

/** One operand pointer the kernel publishes on a fired message. [Wildcard] retains the binding depth of the
 *  aggregate that owns it: every repeatable level at or below that depth is rendered with A12's wildcard
 *  repetition, while an [Ordinary] reference resolves in the rule's current row. Keeping this separate from
 *  [nonStarredRefs] is load-bearing — that star-free view decides iteration and suppression, not the message's
 *  rich reference channel. */
internal sealed interface MessageReference {
    val path: String

    data class Ordinary(override val path: String) : MessageReference
    data class Wildcard(override val path: String, val bindDepth: Int) : MessageReference
}

/** Message-reference walk in deterministic AST order. A starred operand contributes its `Having` field operands
 *  and the aggregate operand itself. Filter refs are part of that wildcard domain unless marked `$`-outer;
 *  `CurrentRepetition` is structural and contributes no field pointer. The kernel publishes these as a set, so
 *  its incidental `HashSet` iteration order is not reproduced. [excludeRepetitionNotUniqueKeys] preserves RNU's
 *  separately projected duplicate-cluster channel while allowing references from sibling branches through. */
internal fun messageReferences(
    cond: Cond,
    excludeRepetitionNotUniqueKeys: Boolean = false,
): List<MessageReference> = messageReferences(cond, null, excludeRepetitionNotUniqueKeys)

private fun messageReferences(
    cond: Cond,
    wildcardBindDepth: Int?,
    excludeRepetitionNotUniqueKeys: Boolean,
): List<MessageReference> = when (cond) {
    is Cond.Cmp -> messageReferences(cond.left, wildcardBindDepth) + messageReferences(cond.right, wildcardBindDepth)
    is Cond.Tolerance -> messageReferences(cond.left, wildcardBindDepth) + messageReferences(cond.right, wildcardBindDepth)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { messageReferences(it, wildcardBindDepth) }
    is Cond.And -> messageReferences(cond.left, wildcardBindDepth, excludeRepetitionNotUniqueKeys) +
        messageReferences(cond.right, wildcardBindDepth, excludeRepetitionNotUniqueKeys)
    is Cond.Or -> messageReferences(cond.left, wildcardBindDepth, excludeRepetitionNotUniqueKeys) +
        messageReferences(cond.right, wildcardBindDepth, excludeRepetitionNotUniqueKeys)
    is Cond.Not -> messageReferences(cond.c, wildcardBindDepth, excludeRepetitionNotUniqueKeys)
    is Cond.Pred -> if (excludeRepetitionNotUniqueKeys && cond.name == "RepetitionNotUnique") emptyList()
        else cond.args.flatMap { messageReferences(it, wildcardBindDepth) }
    is Cond.Pattern -> messageReferences(cond.value, wildcardBindDepth) + messageReferences(cond.pattern, wildcardBindDepth)
    is Cond.Custom -> emptyList()
}

private fun messageReferences(expr: Expr, wildcardBindDepth: Int?): List<MessageReference> = when (expr) {
    is Expr.Field -> listOf(messageReference(expr.path, expr.outer, wildcardBindDepth))
    is Expr.Star -> (expr.having?.let {
        messageReferences(it, expr.bindDepth, excludeRepetitionNotUniqueKeys = false)
    } ?: emptyList()) + MessageReference.Wildcard(expr.path, expr.bindDepth)
    is Expr.Func -> if (FunctionOp.byName(expr.name) == FunctionOp.CURRENT_REPETITION) emptyList()
        else expr.args.flatMap { messageReferences(it, wildcardBindDepth) }
    is Expr.Arith -> messageReferences(expr.left, wildcardBindDepth) + messageReferences(expr.right, wildcardBindDepth)
    is Expr.EnumCategory -> listOf(messageReference(expr.path, expr.outer, wildcardBindDepth))
    // Preserve the existing message contract: the field-valued key is the per-row operand; the semantic lookup
    // target is an absolute lookup and does not itself appear in the message reference list.
    is Expr.SemanticIndex -> expr.indexFieldPath?.let {
        listOf(messageReference(it, expr.indexFieldOuter, wildcardBindDepth))
    } ?: emptyList()
    is Expr.EntitySpec -> when (val index = expr.semanticIndex) {
        null -> messageReferences(expr.source, wildcardBindDepth)
        is Expr.EntityIndex.Literal -> emptyList()
        is Expr.EntityIndex.Field -> listOf(messageReference(index.path, index.outer, wildcardBindDepth))
    }
    else -> emptyList()
}

private fun messageReference(path: String, outer: Boolean, wildcardBindDepth: Int?): MessageReference =
    if (wildcardBindDepth != null && !outer) MessageReference.Wildcard(path, wildcardBindDepth)
    else MessageReference.Ordinary(path)

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

/** Every semantic-index lookup target in an expression, including lookups nested in a `Having` filter. The
 * target's index field is an eager computation operand even when the lookup key is a literal, so this walk is
 * kept separate from [referencedFieldPaths], whose ordinary dependency view needs only the value path and an
 * optional field-valued key. */
internal fun semanticIndexTargets(expr: Expr): List<String> = when (expr) {
    is Expr.SemanticIndex -> listOf(expr.path)
    is Expr.Star -> expr.having?.let(::semanticIndexTargets).orEmpty()
    is Expr.Func -> expr.args.flatMap(::semanticIndexTargets)
    is Expr.Arith -> semanticIndexTargets(expr.left) + semanticIndexTargets(expr.right)
    else -> emptyList()
}

/** Condition twin of [semanticIndexTargets]. */
internal fun semanticIndexTargets(cond: Cond): List<String> = when (cond) {
    is Cond.Cmp -> semanticIndexTargets(cond.left) + semanticIndexTargets(cond.right)
    is Cond.Tolerance -> semanticIndexTargets(cond.left) + semanticIndexTargets(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap(::semanticIndexTargets)
    is Cond.And -> semanticIndexTargets(cond.left) + semanticIndexTargets(cond.right)
    is Cond.Or -> semanticIndexTargets(cond.left) + semanticIndexTargets(cond.right)
    is Cond.Not -> semanticIndexTargets(cond.c)
    is Cond.Pred -> cond.args.flatMap(::semanticIndexTargets)
    is Cond.Pattern -> semanticIndexTargets(cond.value) + semanticIndexTargets(cond.pattern)
    is Cond.Custom -> emptyList()
}

/** Every concrete semantic-index node in evaluation order. Unlike [semanticIndexTargets], this retains the key
 *  specification because a rule whose repeated error field is semantically addressed inherits the selected
 *  repetition as its evaluation/error context. */
internal fun semanticIndexes(cond: Cond): List<Expr.SemanticIndex> = when (cond) {
    is Cond.Cmp -> semanticIndexes(cond.left) + semanticIndexes(cond.right)
    is Cond.Tolerance -> semanticIndexes(cond.left) + semanticIndexes(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap(::semanticIndexes)
    is Cond.And -> semanticIndexes(cond.left) + semanticIndexes(cond.right)
    is Cond.Or -> semanticIndexes(cond.left) + semanticIndexes(cond.right)
    is Cond.Not -> semanticIndexes(cond.c)
    is Cond.Pred -> cond.args.flatMap(::semanticIndexes)
    is Cond.Pattern -> semanticIndexes(cond.value) + semanticIndexes(cond.pattern)
    is Cond.Custom -> emptyList()
}

private fun semanticIndexes(expr: Expr): List<Expr.SemanticIndex> = when (expr) {
    is Expr.SemanticIndex -> listOf(expr)
    is Expr.Star -> expr.having?.let(::semanticIndexes).orEmpty()
    is Expr.Func -> expr.args.flatMap(::semanticIndexes)
    is Expr.Arith -> semanticIndexes(expr.left) + semanticIndexes(expr.right)
    is Expr.EntitySpec -> {
        val source = expr.source as? Expr.Field
        val indexed = source?.let { field ->
            when (val index = expr.semanticIndex) {
                null -> null
                is Expr.EntityIndex.Literal -> Expr.SemanticIndex(
                    field.path,
                    indexValue = index.value,
                    outer = field.outer,
                )
                is Expr.EntityIndex.Field -> Expr.SemanticIndex(
                    field.path,
                    indexFieldPath = index.path,
                    outer = field.outer,
                    indexFieldOuter = index.outer,
                )
            }
        }
        listOfNotNull(indexed) + semanticIndexes(expr.source)
    }
    is Expr.Field, is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit, is Expr.Const, is Expr.EnumCategory ->
        emptyList()
}

internal fun condRefsOrEmpty(cond: Cond?): List<String> = cond?.let { nonStarredRefs(it) } ?: emptyList()

// ---------------------------------------------------------------------------------------------------------
// The DECLARATION view — every entity path the text NAMES, with no purpose-specific exclusion (IF248).
// ---------------------------------------------------------------------------------------------------------

/**
 * Every entity path [cond] NAMES, whether field or group, starred or not, in any operand position — the view a
 * declaration check needs. Deliberately **not** one of the walks above: each of those omits paths on purpose
 * (the scope view drops stars, the dependency view drops `CurrentRepetition`'s group and `EntitySpec`
 * altogether, the message view drops a semantic-index target), and every such omission would become a
 * reference this walk fails to check. Both `when`s are exhaustive over the sealed AST so a new node cannot be
 * added without deciding what it names.
 *
 * A starred operand contributes its `*`-free path, which is what the parser already stores ([Expr.Star.path]) —
 * the wildcard markers never reach the AST, so a legal `Items* / Count` is checked as `…/Items/Count`.
 */
internal fun namedEntityPaths(cond: Cond): List<String> = when (cond) {
    is Cond.Cmp -> namedEntityPaths(cond.left) + namedEntityPaths(cond.right)
    is Cond.Tolerance -> namedEntityPaths(cond.left) + namedEntityPaths(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap { namedEntityPaths(it) }
    is Cond.And -> namedEntityPaths(cond.left) + namedEntityPaths(cond.right)
    is Cond.Or -> namedEntityPaths(cond.left) + namedEntityPaths(cond.right)
    is Cond.Not -> namedEntityPaths(cond.c)
    is Cond.Pred -> cond.args.flatMap { namedEntityPaths(it) }
    is Cond.Pattern -> namedEntityPaths(cond.value) + namedEntityPaths(cond.pattern)
    // A custom condition names no entity — the delegate receives the document and decides for itself.
    is Cond.Custom -> emptyList()
}

/** Expression twin of [namedEntityPaths]; a literal or a no-operand constant names nothing. */
internal fun namedEntityPaths(expr: Expr): List<String> = when (expr) {
    is Expr.Field -> listOf(expr.path)
    is Expr.Star -> listOf(expr.path) + expr.having?.let { namedEntityPaths(it) }.orEmpty()
    is Expr.Func -> expr.args.flatMap { namedEntityPaths(it) }
    is Expr.Arith -> namedEntityPaths(expr.left) + namedEntityPaths(expr.right)
    is Expr.EnumCategory -> listOf(expr.path)
    is Expr.SemanticIndex -> listOfNotNull(expr.path, expr.indexFieldPath)
    is Expr.EntitySpec -> namedEntityPaths(expr.source) + when (val index = expr.semanticIndex) {
        null, is Expr.EntityIndex.Literal -> emptyList()
        is Expr.EntityIndex.Field -> listOf(index.path)
    }
    is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit, is Expr.Const -> emptyList()
}

/**
 * Every model path the condition names, in any role and at any depth: values, stars and their `Having` filters,
 * group operands, semantic-index targets and keys, category projections, and `CurrentRepetition`'s group.
 * This is the "which root groups does the rule touch" view, so unlike every view above nothing is excluded.
 */
internal fun entityPaths(cond: Cond): List<String> = when (cond) {
    is Cond.Cmp -> entityPaths(cond.left) + entityPaths(cond.right)
    is Cond.Tolerance -> entityPaths(cond.left) + entityPaths(cond.right)
    is Cond.ValueListQuant -> (cond.fields + cond.values).flatMap(::entityPaths)
    is Cond.And -> entityPaths(cond.left) + entityPaths(cond.right)
    is Cond.Or -> entityPaths(cond.left) + entityPaths(cond.right)
    is Cond.Not -> entityPaths(cond.c)
    is Cond.Pred -> cond.args.flatMap(::entityPaths)
    is Cond.Pattern -> entityPaths(cond.value) + entityPaths(cond.pattern)
    is Cond.Custom -> emptyList()
}

private fun entityPaths(expr: Expr): List<String> = when (expr) {
    is Expr.Field -> listOf(expr.path)
    is Expr.Star -> listOf(expr.path) + expr.having?.let(::entityPaths).orEmpty()
    is Expr.Func -> expr.args.flatMap(::entityPaths)
    is Expr.Arith -> entityPaths(expr.left) + entityPaths(expr.right)
    is Expr.EnumCategory -> listOf(expr.path)
    is Expr.SemanticIndex -> listOfNotNull(expr.path, expr.indexFieldPath)
    is Expr.EntitySpec -> entityPaths(expr.source) + when (val index = expr.semanticIndex) {
        null, is Expr.EntityIndex.Literal -> emptyList()
        is Expr.EntityIndex.Field -> listOf(index.path)
    }
    is Expr.NumLit, is Expr.StrLit, is Expr.BoolLit, is Expr.Const -> emptyList()
}

/**
 * Whether some comparison holds a filled-field, filled-group or distinct-value count on the side where a smaller
 * count makes it true (`count < x`, `x > count`, the `<=`/`>=` twins), or on either side of an (in)equality.
 * A12 files such a rule as always evaluated, because an empty instance is exactly where the count is small
 * (KERNEL-FINDINGS KF507). A tolerance band is read as an (in)equality, a choice no measured row separates
 * (INTERPRETER-FINDINGS IF351). A `Having` filter's own comparisons are not asked, matching the walk that stops
 * at a comparison's operands.
 */
internal fun comparesACountOnItsSmallSide(cond: Cond): Boolean = when (cond) {
    is Cond.Cmp -> when (cond.op) {
        CmpOp.LT, CmpOp.LE -> containsCount(cond.left)
        CmpOp.GT, CmpOp.GE -> containsCount(cond.right)
        CmpOp.EQ, CmpOp.NE -> containsCount(cond.left) || containsCount(cond.right)
    }
    is Cond.Tolerance -> containsCount(cond.left) || containsCount(cond.right)
    is Cond.And -> comparesACountOnItsSmallSide(cond.left) || comparesACountOnItsSmallSide(cond.right)
    is Cond.Or -> comparesACountOnItsSmallSide(cond.left) || comparesACountOnItsSmallSide(cond.right)
    is Cond.Not -> comparesACountOnItsSmallSide(cond.c)
    is Cond.ValueListQuant, is Cond.Pred, is Cond.Pattern, is Cond.Custom -> false
}

private val SMALL_SIDE_COUNTS = setOf(
    FunctionOp.NUMBER_OF_FILLED_FIELDS, FunctionOp.NUMBER_OF_FILLED_GROUPS, FunctionOp.NUMBER_OF_DIFFERENT_VALUES,
)

private fun containsCount(expr: Expr): Boolean = when (expr) {
    is Expr.Func -> FunctionOp.byName(expr.name) in SMALL_SIDE_COUNTS || expr.args.any(::containsCount)
    is Expr.Arith -> containsCount(expr.left) || containsCount(expr.right)
    else -> false
}
