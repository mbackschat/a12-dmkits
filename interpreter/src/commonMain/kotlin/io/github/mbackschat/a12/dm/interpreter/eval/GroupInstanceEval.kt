package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.pathParts
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val

// The value operators that iterate GROUP INSTANCES rather than cells: `NumberOfFilledGroups` counts them and
// `SumOfTerms` adds a term over them. Both consult the partial-validation GroupCountGate before reading a row,
// because the gate asks about declared instances the document lacks (KF464, KF670).

/**
 * `NumberOfFilledGroups(...)`'s count over its group list, whatever mix of shapes it holds. Every operand
 * expands to the group instances it names — a fixed group its own, a starred scope one per environment
 * [Interpreter.groupFillStates] also selects, so a repeatable terminal counts each instantiated row (a
 * created-but-empty one too, IF46) and a fixed group below starred levels counts by its content — and each
 * instance contributes its [GroupPresenceState.fillProduct]. Under partial validation an operand is counted
 * only once its [GroupCountGate] admits it, which asks about the declared instances the document lacks and
 * so cannot be read off the held ones (KF464). One undecidable operand or instance makes the whole count
 * Unknown rather than a partial count (KF463). Linear in the instances.
 */
internal fun Interpreter.filledGroupsValue(args: List<Expr>): Val {
    rejectHaving(args, "NumberOfFilledGroups")
    val gate = groupCountGate
    var count = 0
    for (arg in args) {
        val c = ctxOf(arg)
        if (arg is Expr.Star) {
            val rows = c.groupListEnvironments(arg.path, arg.bindDepth)
            if (gate != null &&
                !gate.admits(arg.path, arg.bindDepth, c.repsOf(arg.path), rows.mapTo(HashSet()) { it.repsOf(arg.path) })
            ) return Val.Unknown
            for (row in rows) count += countedGroup(groupPresence(row, arg.path)) ?: return Val.Unknown
        } else {
            val path = pathOf(arg)
            // Every level of a fixed operand is bound, so the gate asks about this one instance alone.
            if (gate != null && !gate.admits(path, pathParts(path).size, c.repsOf(path), emptySet())) return Val.Unknown
            count += countedGroup(groupPresence(c, path)) ?: return Val.Unknown
        }
    }
    return Val.Num(dec(count))
}

/**
 * `SumOfTerms` (KERNEL-FINDINGS KF670): the term over every row the iteration star keeps, each read in its own row.
 * A row holding no value adds nothing, a row whose term fails its domain (a division by zero or by an empty field)
 * adds nothing either, and a row whose presence or term is unknown leaves the sum unknown. Partial validation asks the
 * group-count gate first, because A12 iterates the group itself here as it does for `NumberOfFilledGroups`.
 */
internal fun Interpreter.termSum(e: Expr.TermSum): Val {
    var sum = DEC_ZERO
    for (row in termSumRows(e) ?: return Val.Unknown) {
        when (groupPresence(row, e.iteration.path).fillProduct) {
            Tri.FALSE -> continue
            Tri.UNKNOWN -> return Val.Unknown
            Tri.TRUE -> when (val term = termRowInterp(row).evalExpr(e.term)) {
                is Val.Num -> sum += term.v
                Val.DomainFailure -> continue
                else -> return Val.Unknown
            }
        }
    }
    return Val.Num(sum)
}

/** The rows a term sum visits — the instances its iteration star spans, narrowed by its filter — or null where the
 *  partial-validation gate leaves the group uncountable. */
internal fun Interpreter.termSumRows(e: Expr.TermSum): List<EvalContext>? {
    val star = e.iteration
    val c = ctxOf(star)
    val rows = c.groupListEnvironments(star.path, star.bindDepth)
    val gate = groupCountGate
    if (gate != null && !gate.admits(star.path, star.bindDepth, c.repsOf(star.path), rows.mapTo(HashSet()) { it.repsOf(star.path) })) {
        return null
    }
    val having = star.having ?: return rows
    return keptRowsWithSuccessorLookahead(rows, having).toList()
}

/** One counted instance's addition to the count, or null when it leaves the count undecidable. */
private fun countedGroup(state: GroupPresenceState): Int? = when (state.fillProduct) {
    Tri.TRUE -> 1
    Tri.FALSE -> 0
    Tri.UNKNOWN -> null
}

/** A sub-interpreter rooted at one row [rc] of a `SumOfTerms` iteration, where the term is read. It shares the
 *  enclosing scope memo with every row: each entry is keyed by the row prefix of the scope it ranges over
 *  ([AggregateMemoKey], `FillScopeMemoKey`), so an aggregate the term reads, such as a `Sum` over every part
 *  of the row's claim, still gets one entry per row (`adapter.laws.SumOfTermsShapesRuntimeLawsTest`). */
internal fun Interpreter.termRowInterp(rc: EvalContext): Interpreter =
    selectedRowInterp(rc).also { row -> scopeMemo?.let(row::sharingScopeMemo) }
