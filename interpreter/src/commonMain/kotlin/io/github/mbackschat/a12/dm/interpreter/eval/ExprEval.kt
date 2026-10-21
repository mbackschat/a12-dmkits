package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.asciiIntOrNull
import io.github.mbackschat.a12.dm.interpreter.javaIntOrNull
import io.github.mbackschat.a12.dm.interpreter.DecimalDigits
import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.RoundMode
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.decOrNull
import io.github.mbackschat.a12.dm.interpreter.nearestIndexFieldOf
import io.github.mbackschat.a12.dm.interpreter.pathParts
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.expansionBindDepth
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone

// The value-expression resolution collaborator — [Interpreter.evalExpr] and everything an [Expr] resolves
// through: the value-function catalog (evalFunc + its helpers), arithmetic/concatenation, the constants,
// the semantic-index (`[F For key]`) machinery, and the date/time constructions. An extension family on
// the shared eval-state seam ([Interpreter], IF117): the internal functions are the seam entry points
// (the truth walk, the polarity walk, and the VLQ collaborator dispatch here); the rest is file-private.

/** A TIME value `HH:mm:ss` — captures hours, minutes, seconds for the time-of-day extractors. */
private val TIME = Regex("""(\d{2}):(\d{2}):(\d{2})""")

/** The injected `Now` value. Authored/stored date-times remain whole-second; only the clock may carry the
 * kernel runtime's exact millisecond remainder. */
private val CLOCK_DATE_TIME = Regex("""(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{3}))?""")

/** The OMITTED-YEAR date literal shape `dd.MM.` (day.month., trailing dot, no year) — the reference-year
 *  form that gets `baseYear` injected (IF52); the full `dd.MM.yyyy` never matches (it has a trailing year). */
private val OMITTED_YEAR = Regex("""\d{1,2}\.\d{1,2}\.""")

/** Functions that yield a DATE value — so a counterpart string literal beside one is a date literal
 *  ([producesTemporal]). The date-time/range constructors return other kinds; the extractors return numbers. */
private val DATE_PRODUCING_FUNCS = setOf(
    FunctionOp.DATE, FunctionOp.DATE_FROM_DATE_TIME,
    FunctionOp.START_OF_DATE_RANGE, FunctionOp.END_OF_DATE_RANGE,
    FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS,
    FunctionOp.VALUE_AS_DATE,
)

/** Functions that yield a DATE_TIME value, the date-time twin of [DATE_PRODUCING_FUNCS] ([producesTemporal]). */
private val DATE_TIME_PRODUCING_FUNCS = setOf(
    FunctionOp.DATE_TIME, FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS,
)

/** The min/max family — temporal when its operands are ([dateAggregate]/[producesTemporal]). */
private val MIN_MAX_FUNCS = setOf(FunctionOp.MIN, FunctionOp.MAX, FunctionOp.MIN_VALUE, FunctionOp.MAX_VALUE)

/** The operators whose result is one of their operands' values, so their operands type it ([producesTemporal]). */
private val OPERAND_TYPED_FUNCS = MIN_MAX_FUNCS + FunctionOp.FIRST_FILLED_VALUE

/** Starred readers whose result can be memoized by expression + aggregate-scope prefixes: each reads only the
 *  cells of its star scopes, and a cell's availability is the cell's own, never the evaluating row's. */
private val MEMOIZED_STAR_AGGREGATES = setOf(
    FunctionOp.SUM,
    FunctionOp.MAX_VALUE,
    FunctionOp.MIN_VALUE,
    FunctionOp.NUMBER_OF_DIFFERENT_VALUES,
    FunctionOp.SUM_OF_PRODUCTS,
    FunctionOp.NUMBER_OF_FILLED_FIELDS,
    FunctionOp.NUMBER_OF_FILLED_GROUPS,
)

/** A validation-call cache key for a starred aggregate value and the exact star scopes it ranges over. */
internal data class AggregateMemoKey(
    val expression: Expr.Func,
    val scopePrefixes: List<List<Int>>,
)

/** `RangeAsNumber` parses its substring ONLY when it is a run of digits (kernel `\d+`); else the value is 0. */
private val DIGITS = Regex("""\d+""")

/** Whether a SemanticIndex resolves to a present cell for polarity projection; [semanticIndexPresence] retains
 * the distinct UNKNOWN state of a compromised lookup. */
internal fun Interpreter.semanticIndexFilled(e: Expr.SemanticIndex): Boolean = semanticIndexCell(e) != null

/**
 * Whether the cell at [path] is empty but not check-relevant, so a list reader meets it as unavailable rather than
 * empty: a required-empty field in validation, and in a computation the empty index cell the preliminary index
 * check invalidates, for which the gate throws the poison itself (KERNEL-FINDINGS KF552,
 * `adapter.laws.RequiredEmptyReadDiffTest`).
 */
internal fun Interpreter.unavailableEmpty(c: EvalContext, path: String): Boolean = !c.filled(path) && invalid(c, path)

/** Whether an instantiated row of a starred operand holds an [unavailableEmpty] cell. A12's list loops
 *  (`anzahlAngegebenerFelder`, `combineFeldListe`, `anzahlWertInFeldlisteIntern`) return the not-check-relevant
 *  value on the first such cell, as on a malformed one. [Expr.Star.bindDepth] scopes the rows. */
private fun Interpreter.starHasUnavailableEmpty(c: EvalContext, operand: Expr.Star): Boolean =
    emptyMayBeUnavailable(operand.path) &&
        c.firstFilledRows(operand.path.substringBeforeLast('/'), operand.bindDepth)
            .any { row -> unavailableEmpty(row, operand.path) }

/**
 * Whether a starred INDEX-field operand carries ANY NOT-CHECK-RELEVANT cell — the kernel's field-local index
 * errors (IF15/IF60, the VLQ [classifyVlqCell] mechanism generalized to the aggregate/count path): a
 * mandatory-EMPTY (or absent) cell of the auto-required index field, and EVERY cell of a DUPLICATED value
 * (`uniqueIndex`). A malformed or call-locally unavailable cell is [Val.Unknown]. The kernel's shared aggregate
 * loop `combineFeldListe` (Sum/Min/Max/NumberOfDifferentValues)
 * and `anzahlAngegebenerFelder` (NumberOfFilledFields) return the NOT-CHECK-RELEVANT value on the FIRST such
 * cell, so the whole aggregate is non-evaluable and the referencing rule is suppressed (`IndexAggregateDiffTest`).
 * False for a non-index field or a clean index column.
 */
private fun Interpreter.indexOperandNonEvaluable(c: EvalContext, path: String, bindDepth: Int = -1): Boolean =
    path in indexFields && indexColumnOf(c, path, bindDepth).anyEmptyOrDuplicated

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
private fun Interpreter.filledGroupsValue(args: List<Expr>): Val {
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

/** One counted instance's addition to the count, or null when it leaves the count undecidable. */
private fun countedGroup(state: GroupPresenceState): Int? = when (state.fillProduct) {
    Tri.TRUE -> 1
    Tri.FALSE -> 0
    Tri.UNKNOWN -> null
}

/** Whether [arg] names a GROUP rather than a declared field — a group holds no value of its own, so an operand
 *  that names one has to be expanded to the fields below it before anything can read a cell. */
internal fun Interpreter.namesAGroup(arg: Expr): Boolean =
    arg is Expr.EntitySource && ctxOf(arg).declaredKind(arg.path) == null

/**
 * **The one place that knows HOW a group operand expands** — recursively to every declared field below it (the
 * kernel's `ExpandService` → `getAllContainedFields`), each field carrying the authored operand's `Having`,
 * `$`-outer marking, and a bind depth resolved to the operand's OWN level so every repeatable level strictly
 * below it stays free ([expansionBindDepth]; [IF287]'s fourth instance is what a raw `-1` cost).
 *
 * Both shapes expand to a **starred** field, including the unstarred one: the star form iterates every repeatable
 * level below the evaluating row, while a field sitting directly in a non-repeatable group degenerates to the
 * single row a plain reference would have read. Choosing the node shape from the *operand's* shape instead was
 * [IF287]'s third defect.
 *
 * A group with no declared field below it yields the operand unchanged rather than nothing, so an empty group
 * cannot silently erase an operand.
 */
internal fun Interpreter.expandOneGroupOperand(arg: Expr): List<Expr> {
    val source = arg as? Expr.EntitySource ?: return listOf(arg)
    val having = (source as? Expr.Star)?.having
    val depth = expansionBindDepth(source.path, (source as? Expr.Star)?.bindDepth ?: -1)
    return ctxOf(arg).containedFields(source.path)
        .map { Expr.Star(it, having, source.outer, depth) }
        .ifEmpty { listOf(arg) }
}

/** Expand STARRED group operands only — the shape a repeatable group operand must use (`MVK_NO_WILDCARD`
 *  refuses it unstarred). Kept distinct from [expandGroupOperands] because its callers each have their own
 *  measured handling of the unstarred shape, and widening a shared path past its measured basis is the mirror
 *  of the defect class this function exists to close. */
internal fun Interpreter.expandGroupStars(args: List<Expr>): List<Expr> {
    if (args.none { it is Expr.Star && namesAGroup(it) }) return args
    return args.flatMap { arg -> if (arg is Expr.Star && namesAGroup(arg)) expandOneGroupOperand(arg) else listOf(arg) }
}

/** Expand a group operand in **either** repetition shape — the starred one and the unstarred one a
 *  NON-repeatable group must use. For a carrier that admits both and treats them alike. */
internal fun Interpreter.expandGroupOperands(args: List<Expr>): List<Expr> {
    if (args.none { namesAGroup(it) }) return args
    return args.flatMap { arg -> if (namesAGroup(arg)) expandOneGroupOperand(arg) else listOf(arg) }
}

/**
 * `NumberOfFilledFields(...)`'s count across its operands — a plain field counts 1 when filled; a STARRED
 * repetition list (`Items* then Count`) counts its FILLED cells across all rows (IF51, the `pathOf(Star)`
 * crash); a GROUP operand counts the FILLED fields INSIDE it recursively (IF51, the kernel `ExpandService` →
 * `getAllContainedFields`, via [EvalContext.fillScope]). A present-but-**malformed** cell is a formal error
 * that makes the count **non-evaluable** → the whole function is [Val.Unknown] and the referencing rule is
 * suppressed (the kernel's formal-suppression, exactly as [combine] does for the value aggregates — the fuzz
 * surfaced that "count the valid ones" was WRONG: a malformed cell among valid ones suppresses, not skips;
 * IF51). Empties are simply not counted. Linear: O(rows) per star, O(instantiated cells) per group.
 *
 * A **starred GROUP** operand expands to its contained fields, each starred the same way.
 *
 * The un-starred group arm below already expands (via [EvalContext.fillScope] with `isGroup = true`); the starred
 * arm did not, and for a *repeatable* group the star is the only legal spelling — the un-starred form is rejected
 * `MVK_NO_WILDCARD` — so the branch that could not expand was the only one a real author can reach. Unexpanded,
 * `ctx.star` resolves through the group's PARENT rows and then reads a value at the group path, where no field is
 * declared: the count came back **0**, a well-formed answer that simply never fires. Measured against both kernel
 * strategies, `NumberOfFilledFields(/Order/Lines*) >= 3` over two fully-filled rows fired on the kernel and not
 * here (IF287's class, fifth instance).
 *
 * The `Having`, `outer` and `bindDepth` of the authored operand ride each expanded field, exactly as
 * [starOperandVals] preserves them — the filter belongs to the operand, not to one of its expanded cells.
 */
private fun Interpreter.filledFieldsValue(args: List<Expr>): Val = filledFieldsCount(args)?.let { Val.Num(dec(it)) } ?: Val.Unknown

/** [filledFieldsValue]'s count, or null where the count is non-evaluable. */
private fun Interpreter.filledFieldsCount(args: List<Expr>): Int? {
    var count = 0
    for (arg in expandGroupStars(args)) when (arg) {
        is Expr.EntitySpec -> count += withIndexedStar(arg, null) {
            filledFieldsCount(listOf(it))
        } ?: return null
        is Expr.Star -> {
            if (arg.having != null) {
                // The count HONORS a Having filter (IF94 — the kernel's `anzahlAngegebenerFelder` rides the
                // same filtered iterator as every field-list aggregate; ignoring the filter over-counted).
                // The filter drops rows FIRST — a malformed cell in a dropped row never suppresses (kernel-
                // pinned) — then a KEPT row's malformed cell makes the count non-evaluable (poison in
                // compute). Left index-unaware like the other Having paths (IF86's no-untested-logic call).
                // Kept rows come through the SHARED one-kept-successor traversal — the sole filtered
                // field-list owner (SPEC-2026-07-23-12 follow-up); the kernel's count constructs the same
                // EntityIterator (`anzahlAngegebenerFelder`, IF94).
                for (rc in filteredRows(ctx, arg, FilterTraversal.SPARSE)) {
                    if (unavailableEmpty(rc, arg.path)) return null
                    if (!rc.filled(arg.path)) continue
                    if (rc.field(arg.path) is Val.Unknown) {
                        if (computeMode) throw PoisonedReadException(arg.path)
                        return null
                    }
                    count++
                }
            } else {
                val cells = ctx.star(arg.path, arg.bindDepth)   // filled cells in row order (empties dropped)
                if (cells.any { it is Val.Unknown }) {          // a malformed cell → non-evaluable
                    if (computeMode) throw PoisonedReadException(arg.path)   // …and a POISON on the compute path (IF85)
                    return null
                }
                // An index field's not-check-relevant cell (mandatory-empty / duplicated value) makes the count
                // non-evaluable too — `anzahlAngegebenerFelder` returns NICHT_PRUEF_REL on the first UNKNOWN cell.
                if (indexOperandNonEvaluable(ctx, arg.path, arg.bindDepth)) return null
                if (starHasUnavailableEmpty(ctx, arg)) return null
                count += cells.size
            }
        }
        // A keyed GROUP counts the selected row as the bare group arm below counts its instance (IF340); a keyed
        // FIELD counts its one cell, and an undecided one makes the count non-evaluable like a malformed cell.
        is Expr.SemanticIndex -> if (isKeyedGroup(arg)) {
            when (val cells = keyedGroupCells(arg)) {
                is KeyedGroupCells.Selected ->
                    count += cells.interpreter.filledFieldsCount(listOf(Expr.Field(arg.path))) ?: return null
                KeyedGroupCells.Empty -> {}
                KeyedGroupCells.Undecided -> return null
            }
        } else {
            when (presenceTri(arg)) {
                Tri.TRUE -> count++
                Tri.FALSE -> {}
                Tri.UNKNOWN -> return null
            }
        }
        else -> {
            val path = pathOf(arg)
            if (ctx.declaredKind(path) == null) {              // GROUP operand — its descendant fields
                val fs = ctx.fillScope(path, isGroup = true)
                if (fs.hasUnknownInst) {                       // a malformed descendant → non-evaluable
                    if (computeMode) throw PoisonedReadException(path)
                    return null
                }
                count += fs.tInst
            } else {                                           // plain field
                // `ctxOf`, not the ambient `ctx`: a `$`-marked operand resolves in the OUTER context, and asking
                // the wrong one counts a different row's cell ([IF287]'s fourteenth/twenty-third instances).
                when (filledTri(ctxOf(arg), path)) {
                    Tri.TRUE -> count++
                    Tri.FALSE -> {}
                    Tri.UNKNOWN -> return null                 // malformed or required-empty → non-evaluable
                }
            }
        }
    }
    return count
}

internal fun Interpreter.evalExpr(e: Expr): Val {
    // Every value node, including those a sub-interpreter resolves, passes here — the compute path's whole
    // evaluation volume, which the condition dispatch never sees (WorkLedger).
    work?.expressionNode()
    return evalExprNode(e)
}

private fun Interpreter.evalExprNode(e: Expr): Val = when (e) {
    is Expr.NumLit -> e.asVal
    is Expr.StrLit -> e.asVal
    is Expr.BoolLit -> e.asVal
    // A value read of a cell that is not check-relevant is unavailable in validation, and in a computation the
    // gate throws, which IS the poisoning read (IF85). It is judged in the reading context, so a `Having` filter
    // row judges ITS row's cell (IF319).
    is Expr.Field -> when {
        invalid(ctxOf(e), e.path) -> Val.Unknown
        else -> ctxOf(e).field(e.path)
    }
    is Expr.Star -> throw UnsupportedConstructException("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
    is Expr.Func -> evalFunc(e)
    is Expr.Arith -> evalArith(e)
    is Expr.Const -> evalConst(e.name)
    is Expr.SemanticIndex -> evalSemanticIndex(e)
    // A category read `[Plan -> Tier]` reads the same cell, so it takes the value read's gate: a duplicated or
    // missing index key and a row above capacity make it unavailable, not only a malformed value (IF338, IF341).
    is Expr.EnumCategory -> when {
        invalid(ctxOf(e), e.path) -> Val.Unknown
        else -> ctxOf(e).enumCategory(e.path, e.category)
    }
    is Expr.EntitySpec -> evalEntitySpec(e)
}

private fun Interpreter.evalEntitySpec(e: Expr.EntitySpec): Val {
    val source = e.source as? Expr.Field
        ?: throw UnsupportedConstructException("a starred entitySpec must sit inside an aggregate")
    val selected = e.semanticIndex?.let { evalSemanticIndex(source.semanticIndex(it)) } ?: evalExpr(source)
    return e.category?.let { ctxOf(source).enumCategoryValue(source.path, it, selected) } ?: selected
}

private fun Expr.Field.semanticIndex(index: Expr.EntityIndex): Expr.SemanticIndex = when (index) {
    is Expr.EntityIndex.Literal -> Expr.SemanticIndex(path, indexValue = index.value, outer = outer)
    is Expr.EntityIndex.Field -> Expr.SemanticIndex(
        path,
        indexFieldPath = index.path,
        outer = outer,
        indexFieldOuter = index.outer,
    )
}

/**
 * Select the indexed parent once, then let the existing star reader expand only that parent's children.
 *
 * A key no row carries is read the same way, inside the empty parent row at the unbound coordinate, so the
 * consumer's own coverage gate decides there exactly as it decides over a present row: a wildcarded list suffices
 * for an aggregate, and a complete enumeration of the declared lower repetitions under a parent wildcard suffices
 * for `SumOfProducts`, the ordered first-filled scan and the fill quantifiers. Asking the keyed GROUP's presence
 * instead, as [keyedGroupCells] does for a group operand, demanded every field of the parent row and left such a
 * list undecided wherever only the listed field was selected (SPEC-2026-10-06-03,
 * `adapter.laws.PartialProductCapacityDiffTest`).
 */
internal fun <T> Interpreter.withIndexedStar(
    operand: Expr.EntitySpec,
    unavailable: T,
    read: Interpreter.(Expr.Star) -> T,
): T {
    val source = operand.source as? Expr.Star
        ?: throw UnsupportedConstructException("this field-list operand requires a starred source")
    val indexField = indexFieldOf(source.path)
        ?: throw UnsupportedConstructException("SemanticIndex needs an index field on ${source.path}")
    val parent = Expr.Field(indexField.substringBeforeLast('/'), source.outer)
    val row = when (val selection = keyedGroupSelection(parent.semanticIndex(checkNotNull(operand.semanticIndex)))) {
        is KeyedGroupSelection.Row -> selection.ctx
        is KeyedGroupSelection.Absent -> ctxOf(parent).absentGroupRow(parent.path, selection.repetitions)
        KeyedGroupSelection.Unavailable -> return unavailable
    }
    return selectedRowInterp(row).read(source.copy(outer = false))
}

internal fun Expr.isIndexedStar(): Boolean =
    this is Expr.EntitySpec && source is Expr.Star && semanticIndex != null

/** `[field For "value"]` — the field's value in the row whose index field equals the value; an absent row
 *  or empty cell reads as the kind's EMPTY value (a number → 0, fillable), matching a missing field ref.
 *  Compute-mode reads go through the poison gates of [computeGuardedIndexCell]. */
private fun Interpreter.evalSemanticIndex(e: Expr.SemanticIndex): Val {
    val kind = ctxOf(e).declaredKind(e.path) ?: return Val.Unknown
    return computeGuardedIndexCell(e) ?: emptyValue(kind)
}

/**
 * The matched cell of `[F For key]` through the COMPUTE-mode poison gates — the ONE read path shared by the
 * direct operand ([evalSemanticIndex]) and the `FieldValueAsString(ref For key)` coercion
 * ([fieldValueAsString]), so the two read sites cannot diverge. Two kernel-pinned gates, in order:
 *
 *  1. an INVALID index COLUMN — any empty, duplicated, or malformed key cell — poisons the reading
 *     computation even when a clean row matches (IF107; the IndexFieldCache marking, the semantic-index
 *     analog of IF96's parallel-iteration rule);
 *  2. a MATCHED cell that is itself FORMALLY INVALID poisons too (IF108; the indexed analog of the
 *     direct-field read rule IF85) — without it a concat swallowed the invalid cell as `""`.
 *
 * A clean column's no-match and a matched-but-EMPTY cell return null (the caller's empty-as-0 / not-given
 * read), exactly like the validation read. Partial validation first asks the coverage at the coordinate the key
 * resolves to — the selected row, or the unbound row for a no-match ([unmatchedCoordinate]) — and a cell
 * outside it is [Val.Unknown] (KF461). VALIDATION mode differs from the compute whole-column marking
 * (IF115, kernel-pinned): a match on a CLEAN key cell resolves the read no matter what the OTHER cells
 * carry (the matched target's own malformed-ness stays [Val.Unknown] = not-check-relevant); a match on a
 * DUPLICATED or malformed key cell, and a NO-match over a column carrying ANY not-check-relevant cell —
 * malformed (a maybe-match), EMPTY (auto-required), or duplicated — are unresolvable → [Val.Unknown], so
 * a direct comparison is suppressed and the concat gate ([concatOperandInvalid]) suppresses a
 * concatenation (the kernel's `np` flag riding `VkString.add` into `isStringNotRelevant`).
 *
 * A [selection] reads a well-formed partial Date in the selected row as a boundary rather than as unknown
 * ([EvalContext.partialDateBoundaryAt]): presence and `FirstFilledValue` read its earliest day, `ValueAsDate` the
 * day it names.
 */
private fun Interpreter.computeGuardedIndexCell(e: Expr.SemanticIndex, selection: DateBoundary? = null): Val? {
    val c = ctxOf(e)
    if (computeMode) {
        if (indexColumnInvalid(e)) throw PoisonedReadException(e.path)
        val cell = semanticIndexCell(e)
        if (cell is Val.Unknown) {
            return selection?.let { boundary ->
                semanticIndexRepetitions(e)?.let { c.partialDateBoundaryAt(e.path, it, boundary == DateBoundary.LAST_DAY) }
            }
                ?: throw PoisonedReadException(e.path)
        }
        return cell
    }
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    val key = when (val read = resolveIndexKey(e)) {
        is KeyRead.Of -> read.value
        KeyRead.None -> return unmatchedKeyedCell(e)
        KeyRead.Unusable -> return Val.Unknown
    }
    val matchedKey = indexedCell(c, idx, idx, key)                  // the index-field self-lookup
    if (matchedKey != null) {
        // matched on a malformed (raw-equal) or DUPLICATED key cell → not-check-relevant
        if (matchedKey is Val.Unknown || matchedKey in indexColumnOf(c, idx).duplicated) return Val.Unknown
        val selected = c.indexedCellRepetitions(e.path, idx, key, indexIdentityAdmitted())
        if (selected != null && outsideCoverage(e.path, selected)) return Val.Unknown
        val cell = indexedCell(c, e.path, idx, key)
        if (selection != null && cell is Val.Unknown && selected != null) {
            return c.partialDateBoundaryAt(e.path, selected, selection == DateBoundary.LAST_DAY) ?: cell
        }
        return cell
    }
    return unmatchedKeyedCell(e)
}

/** A keyed cell whose key selects no row: the column's classification first, then, over a clean column, the
 *  coverage at the unbound row — an empty cell the coverage does not reach is undecided, not empty. */
private fun Interpreter.unmatchedKeyedCell(e: Expr.SemanticIndex): Val? =
    unmatchedSemanticIndexValue(e) ?: Val.Unknown.takeIf { outsideCoverage(e.path, unmatchedCoordinate(e)) }

/**
 * The coordinate a key no row carries resolves to: the reading context's coordinate for [Expr.SemanticIndex.path]
 * with the keyed level set to [UNKNOWN_REP]. The kernel's lookup answers `IIdentifier.UNKNOWN` for a no-match
 * (`IndexFieldCache.lookup`, source-read) and then asks that identifier like any other, so a relevant entity
 * reaches it only by wildcarding the keyed level: listing every existing row concretely does not
 * (`adapter.laws.PartialKeyedSelectionRelevanceDiffTest`, both strategies, 2026-09-23).
 */
private fun Interpreter.unmatchedCoordinate(e: Expr.SemanticIndex): List<Int> {
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    val keyedLevel = idx.trim('/').split('/').size - 2
    return ctxOf(e).repsOf(e.path).toMutableList().also { it[keyedLevel] = UNKNOWN_REP }
}

/** Partial validation only: whether the cell at [path]/[repetitions] lies outside the caller's coverage, which
 *  makes any read of it undecided. Always false in full validation and computation, which carry no coverage. */
private fun Interpreter.outsideCoverage(path: String, repetitions: List<Int>): Boolean =
    cellRelevant?.invoke(path, repetitions) == false

/** One classification for every unmatched semantic consumer: a clean column gives the fillable no-value result,
 * while any compromised index cell makes the lookup unavailable. Computation treats that unavailability as poison. */
private fun Interpreter.unmatchedSemanticIndexValue(e: Expr.SemanticIndex): Val? {
    if (!indexColumnInvalid(e)) return null
    if (computeMode) throw PoisonedReadException(e.path)
    return Val.Unknown
}

/** What a keyed GROUP operand (`Group For key`) selects: one row, no row, or no decidable row. */
internal sealed interface KeyedGroupSelection {
    /** The one admitted row, as a context rooted at the selected group instance. */
    class Row(val ctx: EvalContext) : KeyedGroupSelection

    /** A clean no-match: an empty key, or a key no row carries over a clean column. The group reads absent, at
     *  the unbound coordinate [repetitions] ([unmatchedCoordinate]) the coverage is asked about. */
    class Absent(val repetitions: List<Int>) : KeyedGroupSelection

    /** A match on a duplicated or malformed key cell, or a no-match over a compromised column. */
    data object Unavailable : KeyedGroupSelection
}

/**
 * The row a keyed GROUP operand selects — the single owner every group-valued consumer reads through, so the
 * value scan of `FirstFilledValue` and the presence product of the group-list quantifiers cannot disagree on
 * which row a key names. The key itself is read by [resolveIndexKey], the step a keyed cell shares, so a malformed
 * field-valued key is unusable on both (IF333). The classification follows [computeGuardedIndexCell]'s for a
 * keyed cell: validation resolves a clean-key match whatever the rest of the column holds and reads a
 * compromised no-match as [KeyedGroupSelection.Unavailable]; computation poisons on any invalid key cell
 * (IF107/IF115). Locked by `adapter.laws.FirstFilledValueEntitySpecDiffTest`,
 * `adapter.laws.IndexedGroupOperandDiffTest`, `adapter.laws.KeyedGroupFieldListDiffTest` and
 * `adapter.laws.MalformedIndexKeyDiffTest`.
 */
internal fun Interpreter.keyedGroupSelection(e: Expr.SemanticIndex): KeyedGroupSelection {
    val c = ctxOf(e)
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    val unmatched = {
        if (unmatchedSemanticIndexValue(e) == null) KeyedGroupSelection.Absent(unmatchedCoordinate(e))
        else KeyedGroupSelection.Unavailable
    }
    val key = when (val read = resolveIndexKey(e)) {
        is KeyRead.Of -> read.value
        KeyRead.None -> return unmatched()
        KeyRead.Unusable -> return KeyedGroupSelection.Unavailable
    }
    val matchedKey = indexedCell(c, idx, idx, key)
    if (computeMode && indexColumnInvalid(e)) throw PoisonedReadException(e.path)
    return when {
        matchedKey is Val.Unknown || matchedKey in indexColumnOf(c, idx).duplicated ->
            if (computeMode) throw PoisonedReadException(e.path) else KeyedGroupSelection.Unavailable
        matchedKey == null -> unmatched()
        else -> KeyedGroupSelection.Row(checkNotNull(c.indexedGroupRow(e.path, idx, key, indexIdentityAdmitted())) {
            "the key ${e.path} For \"$key\" matched an index cell but selected no row"
        })
    }
}

/** Whether [e] is a keyed GROUP operand (`Group For key`), which reads a row, rather than a keyed field. */
internal fun Interpreter.isKeyedGroup(e: Expr): Boolean =
    e is Expr.SemanticIndex && ctxOf(e).declaredKind(e.path) == null

/**
 * What a keyed GROUP operand's CELLS read as, for every consumer that expands a group into the fields below it:
 * the field-fill quantifiers, `NumberOfFilledFields`, the value aggregates, and their polarity (IF340).
 *
 * A12 expands the operand into its contained fields, each carrying the group's key (`ExpandService`, source-read),
 * and measured on both strategies that reads the selected row's cells with every repeatable level below the key
 * free and its declared tail counted — which is the bare group read inside that row, so [Selected] asks the bare
 * group's own arm of an interpreter rooted there. Resolved through [keyedGroupSelection], so these consumers and
 * the presence ones cannot disagree on which row a key names.
 */
internal sealed interface KeyedGroupCells {
    /** The selected row: ask the bare group's arm of [interpreter], which is rooted at that row. */
    class Selected(val interpreter: Interpreter) : KeyedGroupCells

    /** A key no row carries, with its unbound row inside the coverage: every contained field is empty. */
    data object Empty : KeyedGroupCells

    /** An undecidable key, or an unbound row outside the coverage: no cell is decided. */
    data object Undecided : KeyedGroupCells
}

internal fun Interpreter.keyedGroupCells(e: Expr.SemanticIndex): KeyedGroupCells =
    when (val selection = keyedGroupSelection(e)) {
        is KeyedGroupSelection.Row -> KeyedGroupCells.Selected(selectedRowInterp(selection.ctx))
        is KeyedGroupSelection.Absent ->
            if (absentGroupPresence(e.path, selection.repetitions).relevance == GroupRelevance.FULL) KeyedGroupCells.Empty
            else KeyedGroupCells.Undecided
        KeyedGroupSelection.Unavailable -> KeyedGroupCells.Undecided
    }

/** Whether the index COLUMN of `[F For key]` carries ANY invalid cell — malformed, duplicated, or empty
 *  (the IndexFieldCache marking, IF107). Computation poisons every read; validation still resolves a clean
 *  match and uses this only to classify an unmatched lookup as not-check-relevant (IF115). O(1) per lookup
 *  via [EvalContext.indexColumn]. */
private fun Interpreter.indexColumnInvalid(e: Expr.SemanticIndex): Boolean {
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    return indexColumnOf(ctxOf(e), idx).anyNotCheckRelevant
}

/** The matched CELL of `[F For key]`, or null when nothing matches — an empty/absent key field, no row
 *  carrying the key, or a matched row whose cell is empty. A matched-but-malformed cell, and a lookup whose key
 *  field is not check-relevant, are [Val.Unknown]. */
internal fun Interpreter.semanticIndexCell(e: Expr.SemanticIndex): Val? {
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException("SemanticIndex needs an index field on the group of ${e.path}")
    return when (val read = resolveIndexKey(e)) {
        is KeyRead.Of -> indexedCell(ctxOf(e), e.path, idx, read.value)
        KeyRead.None -> null
        KeyRead.Unusable -> Val.Unknown
    }
}

/** The concrete admitted row selected by [e], for the kernel's semantic error-context re-anchor. */
internal fun Interpreter.semanticIndexRepetitions(e: Expr.SemanticIndex): List<Int>? {
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    val key = (resolveIndexKey(e) as? KeyRead.Of)?.value ?: return null
    return ctxOf(e).indexedCellRepetitions(e.path, idx, key, indexIdentityAdmitted())
}

/**
 * The 3VL presence of `[F For key]` — the kernel resolves the indexed row and answers `isWertAngegeben` on
 * the ONE matched cell: matched + check-relevant filled → TRUE; an ABSENT row, an empty matched cell, or a
 * clean-column empty/no-match key → FALSE (an absent row is judged like an empty cell, NOT skipped — kernel
 * `ValidationCache.resolveSemanticIndex` → `isEntityFilled`); a malformed matched cell, or a formally
 * invalid KEY field, or an empty query over a compromised column → UNKNOWN (not check-relevant). Under partial
 * validation a target the coverage does not reach — at the selected row, or at the unbound row of a no-match —
 * is UNKNOWN too ([computeGuardedIndexCell]).
 * `SemanticIndexPresenceDiffTest` / `NumericIndexKeyDiffTest`.
 */
internal fun Interpreter.semanticIndexPresence(e: Expr.SemanticIndex): Tri {
    // Presence and value reads share the admitted-key lookup, the key field's own gate included ([resolveIndexKey]). In particular, a malformed stored key contributes
    // no identity, yet keeps the column invalid: no admitted equal key means UNKNOWN, while a later admitted equal
    // key remains selectable regardless of the malformed row elsewhere in the column (IF115/IF272).
    val cell = computeGuardedIndexCell(e, selection = DateBoundary.FIRST_DAY) ?: return Tri.FALSE
    return if (cell is Val.Unknown) Tri.UNKNOWN else Tri.TRUE
}

/** What the key of a [Expr.SemanticIndex] reads as, before any row is looked up. */
private sealed interface KeyRead {
    data class Of(val value: String) : KeyRead

    /** An empty or absent key field: nothing matches, exactly as for a literal no row carries. */
    data object None : KeyRead

    /** A key field that is not check-relevant, such as a malformed value: validation cannot use the lookup. */
    data object Unusable : KeyRead
}

/**
 * The lookup key of a [Expr.SemanticIndex]: the string literal, or the CURRENT value of the key field
 * ([Expr.SemanticIndex.indexFieldPath]) for the field-keyed `[F For keyField]` form.
 *
 * The key field is an operand like any other, so it is read through the same gate as a plain field value
 * ([evalExprNode]'s field arm): validation answers [KeyRead.Unusable] for a cell that is not check-relevant, and
 * in computation that gate throws, which poisons. This is the only place a key field is read, so every keyed consumer — a
 * keyed field's value and presence, a keyed group's selection, `FirstFilledValue` — inherits the same answer.
 * Measured on both kernel strategies: over a malformed key, validation is silent on every consumer and
 * computation poisons every one (`adapter.laws.MalformedIndexKeyDiffTest`, 2026-09-23).
 */
private fun Interpreter.resolveIndexKey(e: Expr.SemanticIndex): KeyRead {
    e.indexValue?.let { return KeyRead.Of(it) }
    val keyField = e.indexFieldPath ?: return KeyRead.None
    val c = indexKeyCtx(e)
    return when {
        invalid(c, keyField) -> KeyRead.Unusable
        else -> c.displayValue(keyField)?.takeIf { it.isNotEmpty() }?.let(KeyRead::Of) ?: KeyRead.None
    }
}

/** The index field of [fieldPath]'s nearest indexed ancestor group, or null if none. The target may sit below
 * nonrepeatable or repeatable descendants; semantic selection binds the indexed ancestor and preserves lower
 * coordinates from the current iteration. */
private fun Interpreter.indexFieldOf(fieldPath: String): String? = nearestIndexFieldOf(indexFields, fieldPath)

/**
 * A no-operand constant. `Today` resolves to the injected clock's current date and `Now` to its current
 * date-time (ISO text — values comparing chronologically, like any date / date-time field). Without the
 * clock (or, for `Now`, without [EvalClock.now]) the constant cannot be resolved → reported as unsupported
 * (tolerated by `validate`), not crashed.
 */
private fun Interpreter.evalConst(name: String): Val = when (ConstantOp.byName(name)) {
    ConstantOp.TODAY -> Val.Txt(clock?.today
        ?: throw UnsupportedConstructException("Today needs an evaluation clock — construct DmInterpreter with an EvalClock"))
    ConstantOp.NOW -> Val.Txt(clockNow().first)
    // The kernel parse tree defaults BaseYear to NUMBER; date-only positions convert it explicitly. Comparisons
    // resolve it from the other operand in [evalCmpOperand], while [evalTemporal] supplies the date form.
    ConstantOp.BASE_YEAR -> baseYearNumber()
    null -> throw UnsupportedConstructException("unsupported constant $name")
}

/** Evaluate a comparison operand, applying the two context-typed resolutions: (a) a polymorphic `BaseYear`
 *  by the OTHER operand's type (year NUMBER vs numeric, else the base-year-START date); (b) a quoted
 *  German-format date LITERAL canonicalized to ISO when its counterpart yields a date (else it stays a
 *  plain string — the kernel types a literal by the other operand). A plain operand evaluates normally. */
internal fun Interpreter.evalCmpOperand(self: Expr, other: Expr): Val = when {
    self is Expr.Const && ConstantOp.byName(self.name) == ConstantOp.BASE_YEAR ->
        if (evalExpr(other) is Val.Num) baseYearNumber() else baseYearDate()
    // The counterpart's own declared format types the literal, exactly as an assignment target's does — see
    // [coerceTemporalLiteral]. Without it a year-omitting literal compared against a year-FREE date field reached
    // the comparison as raw German text, and the kernel admits that pair whenever neither side carries a year
    // (IF321; `adapter.laws.YearlessConstantComparisonDiffTest`). A DateTime counterpart types a DateTime literal
    // the same way; before SPEC-2026-10-03-06 it compared the German text lexically against the ISO value.
    self is Expr.StrLit && producesTemporal(other) ->
        coerceTemporalLiteral(Val.Txt(self.v), declaredDateFormat(other))
    else -> evalExpr(self)
}

/** The declared date format of a plain field operand, for typing a literal against it; null for anything else.
 *  A TIME field declared `dd.MM.yyyy` is a date operand too, so the format is read whatever the kind (KF211). */
private fun Interpreter.declaredDateFormat(e: Expr): String? =
    if (e is Expr.Field && ctxOf(e).temporalClassOf(e.path) == Kind.DATE) ctxOf(e).declaredTemporalFormatOf(e.path)
        ?: ctxOf(e).dateFormatOf(e.path)
    else null

/** The class a field's declared FORMAT selects, never its kind alone ([temporalClassifier], KF211). */
private fun EvalContext.temporalClassOf(path: String): Kind? =
    declaredKind(path)?.let { temporalClassifier(it, declaredTemporalFormatOf(path)) }

/**
 * Evaluate an expression being ASSIGNED to a typed target. An assignment adds exactly one resolution over
 * [evalExpr]: a bare quoted temporal LITERAL is typed by the target's declared kind, the way a comparison types
 * one by its counterpart operand ([evalCmpOperand]). Without it the German literal text reaches the store
 * uncanonicalized, so a target declaring any other format cannot render it and the cell is flagged errored
 * instead of stored — measured against both kernel strategies, which store `31.12.1899` into a `yyyy-MM-dd`
 * DATE target as `1899-12-31` and `05.03.2024T12:30:00` into an ISO DATE_TIME target as
 * `2024-03-05T12:30:00` (`adapter.laws.DateConstantTargetPolicyDiffTest`,
 * `adapter.laws.DateTimeConstantZoneSplitDiffTest`; the store rule itself is KERNEL-FINDINGS `KF222`/`KF223`).
 *
 * Two kinds are resolved because two kinds were measured. [Kind.TIME] needs none — its literal is already the
 * ISO `HH:mm:ss` shape — and [Kind.DATE_RANGE] has no witnessed literal at all (`KF223`), so neither is
 * guessed at here.
 *
 * [targetDateFormat] is the DATE target's declared format, needed for the year-omitting literal alone; see
 * [coerceTemporalLiteral].
 */
internal fun Interpreter.evalAssignedExpr(expr: Expr, targetKind: Kind?, targetDateFormat: String? = null): Val {
    if (expr !is Expr.StrLit) return evalExpr(expr)
    return when (targetKind) {
        Kind.DATE -> coerceTemporalLiteral(Val.Txt(expr.v), targetDateFormat)
        Kind.DATE_TIME -> dateTimeLiteral(expr.v)
        else -> evalExpr(expr)
    }
}

/**
 * A DATE_TIME literal canonicalized to ISO — or a [TemporalLabelFailure] THROWN when the label it names has
 * **no instant** in the model's declared zone.
 *
 * A wall time inside a spring-forward gap is not a time, so there is nothing for the kernel's conversion to
 * produce, and the conversion runs where the label is evaluated. It is thrown rather than returned because the
 * kernel's failure is an exception, not an operand state: it was first modelled as [Val.DomainFailure] on the
 * assignment carrier alone (IF326), which agreed there (no value, `berechnungsWertFehler` per in-capacity row,
 * KF231) and was wrong everywhere else — a comparison, a shift source, an extractor and `Min`/`Max` all read the
 * label as an absorbable unknown (SPEC-2026-10-03-06).
 *
 * **Deliberately narrow to the unresolvable-instant condition.** A temporal constant that fails its target's
 * *value policy* keeps store-and-flag: the same KF231 measurement has a pre-1900 Date constant ERRORED
 * retaining its rendered `1899-12-31`, so "a temporal constant whose target check fails has no value" is
 * false. Only a label with no instant loses its value here.
 */
private fun Interpreter.dateTimeLiteral(text: String): Val {
    val iso = parseDateTimeToIso(text, DATE_TIME_LITERAL_FORMAT) ?: return Val.Txt(text)
    if (isGapLocal(iso, timeZone)) throw TemporalLabelFailure(text)
    return Val.Txt(iso)
}

/** The kernel's date-time constant spelling: the German date, a literal `T`, and the clock (`KF223`). */
private const val DATE_TIME_LITERAL_FORMAT = "dd.MM.yyyy'T'HH:mm:ss"

/** Whether [e] statically yields a DATE or DATE_TIME value — the signal that a counterpart string LITERAL is a
 *  temporal literal ([coerceTemporalLiteral]), not a plain string. A field whose declared format is a date or a
 *  date-time, whatever its kind (KF211; `adapter.laws.DateDeclaredTimeComparisonDiffTest`), the temporal
 *  constants (`Today`/`BaseYear`/`Now`), a temporal-producing function, or an operator returning one of its
 *  operands whose operands are temporal ([dateAggregate] — the kernel types `Min`/`MinValue` and
 *  `FirstFilledValue` by their operands, so a literal beside a temporal one is a temporal literal; KF568,
 *  `adapter.laws.FirstFilledDateLiteralComparisonDiffTest`). */
private fun Interpreter.producesTemporal(e: Expr): Boolean = when (e) {
    is Expr.Field -> ctx.temporalClassOf(e.path).let { it == Kind.DATE || it == Kind.DATE_TIME }
    is Expr.Const -> ConstantOp.byName(e.name).let {
        it == ConstantOp.TODAY || it == ConstantOp.BASE_YEAR || it == ConstantOp.NOW
    }
    is Expr.Func -> FunctionOp.byName(e.name).let { op ->
        op in DATE_PRODUCING_FUNCS || op in DATE_TIME_PRODUCING_FUNCS || (op in OPERAND_TYPED_FUNCS && dateAggregate(e))
    }
    is Expr.SemanticIndex -> ctx.temporalClassOf(e.path).let { it == Kind.DATE || it == Kind.DATE_TIME }
    else -> false
}

/**
 * Canonicalize a German temporal literal to ISO — the date-time spelling `dd.MM.yyyy'T'HH:mm:ss` through
 * [dateTimeLiteral] (which throws for a gap label), a date to `yyyy-MM-dd`; any other value (already-ISO, a
 * non-date string) is unchanged — so it only ever rewrites a literal that genuinely parses as one. A date has
 * two literal shapes: the full `dd.MM.yyyy`, and the **omitted-year** `dd.MM.`, which needs a year from somewhere.
 *
 * There are two sources, and the second was missing until 2026-09-03. The first is the model's `baseYear`, which
 * the kernel injects, so `"13.07."` means `13.07.<baseYear>` (IF52). The second is that the year is simply not
 * needed: when the literal is being ASSIGNED to a DATE target whose own declared format names no year, the store
 * renders only the components that format carries, so no reference year reaches the document. The kernel admits
 * exactly that pair — a yearless literal into a yearless target with no Base Year declared — and stores the month
 * alone.
 *
 * **[YEARLESS_ANCHOR_YEAR] is reached only when NO base year is declared, and on one carrier it IS observable.**
 * The first half of the qualification stands: the kernel's year-agreement gate returns true unconditionally once
 * a Base Year is declared, so "a year-rendering target disagrees with a yearless literal" is the rule for a
 * base-year-FREE model only, and in a base-year model the `baseYear ?:` above takes the declared year. The
 * second half was wrong until 2026-09-03, and it was wrong because reachability was reasoned rather than run.
 * On the ASSIGNMENT and COMPARISON carriers the anchor is indeed unobservable — the shapes that would expose it
 * are refused at admission under `MVK_INVALID_COMPARE_TO_DATE` (KF239, measured): the year disagreement, and a
 * day that needs a real year to exist (`29.02.`; `31.04.` is refused on the same ground and `30.04.` admitted,
 * so the gate validates the day rather than special-casing February). But a temporal OPERATION admits a
 * year-free literal, and `DifferenceInDays("28.02.", "01.03.")` then answers 1 or 2 according to the anchor's
 * leap-ness. **Measured on one model varying only its Base Year: 2024 answers 2, 2023 answers 1, an unconfigured
 * model answers 2.** So the anchor is the declared Base Year — which the `baseYear ?:` below already prefers —
 * and [YEARLESS_ANCHOR_YEAR] constrains the UNCONFIGURED fallback only
 * (`adapter.laws.YearlessConstantOperationDiffTest`). A first version of this comment said "the anchor must be a
 * leap year" without that qualifier; every row behind it declared no Base Year, so it could not tell the two
 * accounts apart (a12-kernel-lean `SPEC-2026-09-04-01` supplied the axis).
 *
 * **[declaredFormat] is deliberately NOT defaulted.** Its absence means "nothing in scope declares a year", which
 * is a reason to anchor rather than a reason to abstain — the opposite of what a defaulted `null` produced. Two
 * defects came from this parameter being easy to omit: the assignment carrier was missing the branch entirely
 * (IF320), and the fix for that added the parameter WITH a default, so the comparison and operation carriers
 * silently kept the old behaviour (IF321, IF322). Forcing every caller to name its format is what makes a fourth
 * carrier a compile error instead of a silent divergence.
 *
 * This branch used to be absent, and its old comment asserted that a model with no base year rejects the literal
 * at validation. That is true for a year-carrying target and false for a yearless one, so a legal computation
 * reached the store as raw German text and the cell was flagged errored where both kernel strategies store the
 * month alone (IF320; `adapter.laws.YearlessConstantMonthStoreDiffTest`,
 * `rulekit.validate.laws.TemporalConstantAdmissionGateLawsTest`).
 */
private fun Interpreter.coerceTemporalLiteral(v: Val, declaredFormat: String?): Val {
    if (v !is Val.Txt) return v
    if (parseDateTimeToIso(v.v, DATE_TIME_LITERAL_FORMAT) != null) return dateTimeLiteral(v.v)
    parseDateToIso(v.v, "dd.MM.yyyy")?.let { return Val.Txt(it) }
    if (!OMITTED_YEAR.matches(v.v)) return v
    val year = baseYear ?: YEARLESS_ANCHOR_YEAR.takeIf { declaredFormat?.contains('y') != true } ?: return v
    return parseDateToIso(v.v + year.toString().padStart(4, '0'), "dd.MM.yyyy")?.let { Val.Txt(it) } ?: v
}

/**
 * The year a yearless literal borrows when no base year is declared; see [coerceTemporalLiteral].
 *
 * **It must be a leap year**, and it binds only this unconfigured case — a model that declares a Base Year
 * anchors to that instead, and [coerceTemporalLiteral] prefers it. On the temporal-operation carrier the choice is
 * kernel-observable: with no Base Year declared both kernel strategies count 2 days from `28.02.` to `01.03.`
 * (`adapter.laws.YearlessConstantOperationDiffTest`). A common year here would answer 1 and diverge. Which leap
 * year is not pinned — reading the borrowed year back is refused `MVK_NO_CONST`.
 */
private const val YEARLESS_ANCHOR_YEAR = 2000

/** `BaseYear` as the base-year-START date (ISO `yyyy-01-01`). */
private fun Interpreter.baseYearDate(): Val = Val.Txt("${baseYearOrThrow().toString().padStart(4, '0')}-01-01")

/** `BaseYear` as the year NUMBER. */
private fun Interpreter.baseYearNumber(): Val = Val.Num(dec(baseYearOrThrow()))

private fun Interpreter.baseYearOrThrow(): Int = baseYear
    ?: throw UnsupportedConstructException("BaseYear needs the model's base year (modelInfo.baseYear)")

/** Binary arithmetic; an empty numeric operand already arrives as 0, Unknown propagates. A numeric
 *  [Val.DomainFailure] propagates after both operands have been evaluated, preserving the kernel's
 *  left-to-right poisoning reads. `+` is overloaded: STRING CONCATENATION when an operand is textual,
 *  else numeric addition (IF48). */
private fun Interpreter.evalArith(a: Expr.Arith): Val {
    val l = evalExpr(a.left)
    val r = evalExpr(a.right)
    // `+` = string concatenation when an operand is a string VALUE, or STATICALLY a string-typed operand (so
    // two EMPTY strings still concat to "", where the runtime `Val.Txt` check alone would see only Unknowns).
    // An empty/absent string operand concatenates as "" (kernel-probed: `"" + " " + "Smith"` = " Smith"), NOT
    // as non-evaluable. (The kernel rejects a mixed number+string `+` at compile, so a concat is all-strings.)
    if (a.op == ArithOp.ADD && (l is Val.Txt || r is Val.Txt || producesString(a.left) || producesString(a.right))) {
        // A FILLED-but-formally-invalid concat operand makes the WHOLE concatenation non-evaluable → the
        // computed field CLEARS (IF81, was IG51 — differential-confirmed: `[S] + "x"` over a too-long S
        // yields CLEARED). Distinct from an EMPTY operand, which concatenates as "" — even a REQUIRED-empty
        // one (kernel-probed: required-empty stays "", not cleared). `invalid` is the operand-invalidity gate.
        if (concatOperandInvalid(a.left) || concatOperandInvalid(a.right)) return Val.Unknown
        return Val.Txt(concatText(l) + concatText(r))
    }
    if (l is Val.DomainFailure || r is Val.DomainFailure) return Val.DomainFailure
    if (l is Val.Unknown || r is Val.Unknown) return Val.Unknown
    val x = num(l)
    val y = num(r)
    return when (a.op) {
        ArithOp.ADD -> Val.Num(x + y)
        ArithOp.SUB -> Val.Num(x - y)
        ArithOp.MUL -> Val.Num(x * y)
        // A rule comparison projects this domain failure to UNKNOWN (does not fire). Computation keeps
        // a no-value/CLEARED public delta but marks the target invalid for dependent reads.
        ArithOp.DIV -> if (y.compareTo(DEC_ZERO) == 0) Val.DomainFailure else Val.Num(x.div(y))
        ArithOp.POW -> power(x, y)
    }
}

/**
 * `base ^ exp` — the two runtime-invalid integral regions are numeric DOMAIN FAILURES (the kernel's shared
 * `INVALID_NUMBER` sentinel, exactly like divide-by-zero — IF16/SPEC-2026-07-21-05): an integral exponent
 * outside `[-1000, 1000]`, or `0` to a negative integral power. A rule comparison projects that failure to
 * UNKNOWN (does not fire), while a numeric computation preserves it through the numeric wrappers and marks
 * the Number target invalid (`berechnungsWertFehler`), poisoning a dependent read. A non-whole exponent is a
 * STATIC authoring error (`MVK_INVALID_NUMBER_FOR_EXP`), never a legal runtime value, so it stays a defensive
 * [Val.Unknown] here rather than a domain failure. Otherwise `MathContext(50)` arithmetic via [Dec.pow].
 * (operators.json Arithmetic.)
 */
private fun power(base: Dec, exp: Dec): Val {
    val e = exp.display().asciiIntOrNull() ?: return Val.Unknown
    if (e !in -1000..1000) return Val.DomainFailure
    if (base.compareTo(DEC_ZERO) == 0 && e < 0) return Val.DomainFailure
    return Val.Num(base.pow(e))
}

/** A concat operand as text: a string value as-is; an empty/absent/non-evaluable string operand as "" (the
 *  kernel treats an empty string field in a concat as ""); a number defensively via its display form. */
private fun concatText(v: Val): String = when (v) {
    is Val.Txt -> v.v
    is Val.Num -> v.v.display()
    else -> ""
}

/** Whether a concat operand (possibly a nested `+` concatenation) contains a NOT-CHECK-RELEVANT read —
 *  the kernel's `np` flag ORs through `VkString.add`, making the whole concatenation non-evaluable: the
 *  computed field CLEARS / the referencing rule is suppressed (IF81/IF115). Distinct from an EMPTY
 *  operand, which concatenates as "" (even required-empty — kernel-probed). The np reads: a
 *  FILLED-but-formally-invalid field (`filled &&` excludes required-empty), an indexed read over an
 *  invalid column or a matched-malformed cell ([computeGuardedIndexCell] — which POISONS in compute
 *  mode, IF108), and a `FieldValueAsString` over either. A constant/other-function operand is never an
 *  np site here. */
private fun Interpreter.concatOperandInvalid(e: Expr): Boolean = when (e) {
    is Expr.Field -> ctxOf(e).filled(e.path) && invalid(ctxOf(e), e.path)
    is Expr.SemanticIndex -> computeGuardedIndexCell(e) is Val.Unknown
    is Expr.Func -> FunctionOp.byName(e.name) == FunctionOp.FIELD_VALUE_AS_STRING && concatOperandInvalid(e.args[0])
    is Expr.Arith -> e.op == ArithOp.ADD && (concatOperandInvalid(e.left) || concatOperandInvalid(e.right))
    else -> false
}

/** Whether [e] statically yields a STRING — the signal that a `+` is concatenation (so two EMPTY string
 *  operands still concat to "", where the runtime `Val.Txt` check alone would see only Unknowns). */
internal fun Interpreter.producesString(e: Expr): Boolean = when (e) {
    is Expr.StrLit -> true
    is Expr.Field -> ctx.declaredKind(e.path) == Kind.STRING
    is Expr.Arith -> e.op == ArithOp.ADD && (producesString(e.left) || producesString(e.right))
    else -> false
}

/** Whether the partial extent [gate] leaves [star]'s extent unestablished in this row. A starred GROUP is asked
 *  field by field: a field-expanding aggregate expands it into its contained fields before any extent gate reads
 *  it, so field coverage settles an extent no identifier names on the group path (KF464). */
private fun Interpreter.extentUnknown(star: Expr.Star, gate: (String, Int, List<Int>) -> Boolean): Boolean {
    val gated = if (namesAGroup(star)) expandOneGroupOperand(star) else listOf(star)
    return gated.filterIsInstance<Expr.Star>().any { !gate.invoke(it.path, it.bindDepth, ctx.repsOf(it.path)) }
}

private fun Interpreter.evalFunc(f: Expr.Func): Val {
    val op = FunctionOp.byName(f.name)
    // Partial validation chooses the extent predicate by operator family. Most direct starred functions use
    // the reduced-universal all-rows gate. `NumberOfValueInFields` instead shares the declared-coverage value-list
    // gate, `SumOfProducts` accepts a complete concrete enumeration, `FirstFilledValue` is order-aware via
    // per-cell relevance, and `NumberOfFilledGroups` asks its own [GroupCountGate] in [filledGroupsValue],
    // because A12 iterates the counted group rather than its fields. These are five measured contracts, not
    // interchangeable completeness spellings.
    if (op != FunctionOp.FIRST_FILLED_VALUE && op != FunctionOp.NUMBER_OF_FILLED_GROUPS) {
        // Completeness is asked PER ROW: with two nested repeatable levels a relevant set can complete
        // one outer row and not the next, and the kernel evaluates the complete one. A path-keyed
        // question cannot express that (PartialNestedAggregateDiffTest). `bindDepth` rides along because
        // it is what says WHICH levels the row binds — a multi-star operand spans every row of its
        // outer levels (IF94), so a gate deriving that from the path alone binds a level the star
        // iterates and reads a pinned outer row as wildcarded (PartialMultiStarScopeDiffTest).
        val gate = when (op) {
            FunctionOp.SUM_OF_PRODUCTS -> starFullyEnumerated
            FunctionOp.NUMBER_OF_VALUE_IN_FIELDS -> starExtentKnown
            else -> starRelevant
        }
        // A keyed list is asked in the row its key selects, or in the empty row a key no row carries names, so
        // the same gate decides its extent; an undecidable key leaves the read itself unknown
        // (SPEC-2026-10-06-03, `adapter.laws.PartialProductCapacityDiffTest`).
        if (gate != null && f.args.any { arg ->
                when {
                    arg is Expr.Star -> extentUnknown(arg, gate)
                    arg.isIndexedStar() -> withIndexedStar(arg as Expr.EntitySpec, false) { extentUnknown(it, gate) }
                    else -> false
                }
            }) return Val.Unknown
    }
    val memo = scopeMemo?.aggregates
    val memoKey = if (memo == null) null else invariantAggregateMemoKey(f, op)
    if (memo != null && memoKey != null) memo[memoKey]?.let { return it }
    val result = when (op) {
    // Value aggregates — the FOLDS are pure runtime semantics ([Aggregates], IG5→IF10); the strategy only
    // RESOLVES operands (operand-list `map { evalExpr }` vs the starred `starVals`) and gates Unknown
    // ([combine]). The same fold serves both forms (`Min`/`MinValue` share [Aggregates.min]). Min/max over
    // DATE-like operands ride the kernel's DateCombiner semantics ([dateAggregate]): the empty identity is
    // no-value (Unknown), not the number combiner's 0 (DateAggregateDiffTest).
    // operand-list forms: every operand counts (an empty number arrives as the substituted 0; a DATE-like
    // empty operand is SKIPPED — the kernel's `minDatum` combine keeps the date-bearing side)
    FunctionOp.MIN -> {
        refuseGroupOperandUnderMinMax(f)
        if (dateAggregate(f)) evalTemporal(f).asTemporalVal()
        else combine(minMaxOperandVals(f)) { Aggregates.min(it, minMaxEmpty(f)) }
    }
    FunctionOp.MAX -> {
        refuseGroupOperandUnderMinMax(f)
        if (dateAggregate(f)) evalTemporal(f).asTemporalVal()
        else combine(minMaxOperandVals(f)) { Aggregates.max(it, minMaxEmpty(f)) }
    }
    // starred aggregates: empties are SKIPPED (Sum substitutes 0 = same result; Max/distinct ignore)
    FunctionOp.SUM -> combine(starVals(f, FilterTraversal.SPARSE), Aggregates::sum)
    FunctionOp.MAX_VALUE -> combine(starVals(f, FilterTraversal.SPARSE)) { Aggregates.max(it, minMaxEmpty(f)) }
    FunctionOp.MIN_VALUE -> combine(starVals(f, FilterTraversal.SPARSE)) { Aggregates.min(it, minMaxEmpty(f)) }
    FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> combine(starVals(f, FilterTraversal.SPARSE), Aggregates::numberOfDifferentValues)
    // DifferenceInDays(a, b) = b − a as signed MODEL-ZONE CALENDAR STEPS. The earlier legacy-calendar
    // operand advances by whole days until its next landing would pass the later operand, so time-of-day still
    // matters (23:00 → 01:00 next day is 0), while a Berlin special-hour landing can differ from naive
    // wall-seconds / 86 400. A DATE operand reads as midnight; mixed DATE/DATE_TIME pairs are legal.
    // An EMPTY operand reads as the fillable 0 (either empty → 0, kernel getDatumsDiff); MALFORMED → Unknown.
    FunctionOp.DIFFERENCE_IN_DAYS -> temporalDifference(f.args) { a, b ->
        Val.Num(dec(differenceInCalendarDays(a.instantSeconds(timeZone), b.instantSeconds(timeZone), timeZone)))
    }
    // Count-of-filled functions (used in a comparison, e.g. NumberOfFilledFields(a, b, c) < 2).
    FunctionOp.NUMBER_OF_FILLED_FIELDS -> filledFieldsValue(f.args)
    FunctionOp.NUMBER_OF_FILLED_GROUPS -> filledGroupsValue(f.args)
    // Date-component extractors — a number from the date's parts; an empty/absent date is Unknown.
    FunctionOp.YEAR_FROM_DATE -> dateComponent(f.args[0]) { y, _, _ -> y }
    FunctionOp.MONTH_FROM_DATE -> dateComponent(f.args[0]) { _, m, _ -> m }
    FunctionOp.DAY_FROM_DATE -> dateComponent(f.args[0]) { _, _, d -> d }
    FunctionOp.QUARTER_FROM_DATE -> dateComponent(f.args[0]) { _, m, _ -> (m - 1) / 3 + 1 }
    // Time-of-day extractors — a number from a TIME value's `HH:mm:ss` parts; empty/absent → Unknown.
    FunctionOp.HOURS_FROM_TIME -> timeComponent(f.args[0]) { h, _, _ -> h }
    FunctionOp.MINUTES_FROM_TIME -> timeComponent(f.args[0]) { _, m, _ -> m }
    FunctionOp.SECONDS_FROM_TIME -> timeComponent(f.args[0]) { _, _, s -> s }

    // DateFromDateTime/TimeFromDateTime: split the canonical ISO date-time `yyyy-MM-dd'T'HH:mm:ss` at the
    // `T` into its DATE part (a `yyyy-MM-dd` the date extractors read) and its TIME part (an `HH:mm:ss` the
    // time extractors read). Empty/malformed operand (no `T`) → Unknown.
    FunctionOp.DATE_FROM_DATE_TIME -> evalTemporal(f).asTemporalVal()
    FunctionOp.TIME_FROM_DATE_TIME -> evalTime(f).asTimeVal()
    // DateTime(date, time): compose a DATE (`yyyy-MM-dd`) and a TIME (`HH:mm:ss`) into the canonical
    // date-time `yyyy-MM-dd'T'HH:mm:ss`. Either operand empty/malformed → Unknown.
    FunctionOp.DATE_TIME -> evalTemporal(f).asTemporalVal()
    // Date(day, month, year): the constructed calendar date as ISO text — Unknown when the date is not real
    // or a part is empty/malformed (kernel-probed: the extractors see no value). Valid/Invalid inspect the
    // construction directly (they distinguish unreal from empty from malformed; this value path does not).
    FunctionOp.DATE -> evalTemporal(f).asTemporalVal()
    // Time(hours, minutes, seconds): the constructed time as `HH:mm:ss`. Components are in range by model
    // validity (an out-of-range constant is MVK_WRONG_DATE_FORMAT_FOR_OP at codegen); empty/malformed → Unknown.
    FunctionOp.TIME -> evalTime(f).asTimeVal()
    // DateRange(start, finish): a date range from two owner entity references whose DECLARED FORMAT carries
    // date components and no time component — the kind takes no part, so a TIME- or DATE_TIME-declared
    // `yyyy-MM-dd` field is an admitted endpoint (KF271) — optionally semantic-index selected. The value is equality-comparable with another range and renders to
    // the target field's format at the compute() boundary. Empty/malformed → Unknown.
    //
    // The two endpoints complete ASYMMETRICALLY, exactly as a STORED fragment range's do: the kernel's
    // `constructDateRange` pairs each raw operand with that field's declared format and hands the pair to the
    // same `datesAndFormatsToDateRange` the stored path uses — INTERVAL_START for the start, INTERVAL_END for
    // the finish ("we have to use the last day possible to correspond with feldWertAlsDateRange", its own
    // comment). Reading both operands as ordinary dates is INTERVAL_START twice, so a construction over a
    // day-omitting profile (`MM`, `yyyy`, `yyyy-MM`) never equalled the stored range it was built to match
    // (SPEC-2026-08-19-02, `ConstructedRangeFragmentDiffTest`). A profile that carries month and day —
    // `MM-dd`, a full `dd.MM.yyyy` — has nothing to widen and is returned unchanged.
    FunctionOp.DATE_RANGE -> {
        val s = (evalExpr(f.args[0]) as? Val.Txt)?.v
        val e = (evalExpr(f.args[1]) as? Val.Txt)?.v
        if (s == null || e == null) Val.Unknown else Val.Range(s, intervalEndOf(e, f.args[1]))
    }
    // ValueAsDate(field, FirstDay|LastDay): resolve a partial (DAY_OPTIONAL) date field's RAW value to a
    // concrete date — FirstDay → day 01, LastDay → the month's last day; a full value → itself. Reads the
    // raw value + the field's format (the canonicalized Val is Unknown for a `00`-day value).
    FunctionOp.VALUE_AS_DATE -> valueAsDate(f.args)
    // Date-time arithmetic — uses epoch milliseconds, so a day boundary and Now's remainder carry automatically.
    FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS -> evalTemporal(f).asTemporalVal()
    // DifferenceInUnit(a, b) = (epochMillis(b) − epochMillis(a)) / unitMillis, TRUNCATED toward zero (kernel-probed:
    // a reverse difference of −5.5 units yields −5, not −6 — so plain integer division, not floor).
    FunctionOp.DIFFERENCE_IN_HOURS -> temporalDifference(f.args) { a, b ->
        Val.Num(dec(((b.instantMillis(timeZone) - a.instantMillis(timeZone)) / 3_600_000L).toString()))
    }
    FunctionOp.DIFFERENCE_IN_MINUTES -> temporalDifference(f.args) { a, b ->
        Val.Num(dec(((b.instantMillis(timeZone) - a.instantMillis(timeZone)) / 60_000L).toString()))
    }
    FunctionOp.DIFFERENCE_IN_SECONDS -> temporalDifference(f.args) { a, b ->
        Val.Num(dec(((b.instantMillis(timeZone) - a.instantMillis(timeZone)) / 1_000L).toString()))
    }
    // Date arithmetic — date + n days / months / years → a date (ISO text). Empty/absent date → Unknown.
    // AddDays ALSO accepts a DATE_TIME operand (kernel-probed; AddMonths/AddYears reject one at MVK).
    FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS -> evalTemporal(f).asTemporalVal()
    FunctionOp.DIFFERENCE_IN_MONTHS -> dateDiff(f.args, FunctionOp.DIFFERENCE_IN_MONTHS)
    FunctionOp.DIFFERENCE_IN_YEARS -> dateDiff(f.args, FunctionOp.DIFFERENCE_IN_YEARS)
    // Date-range endpoint extractors — a DATE_RANGE operand → its start / end as an ISO date (so the
    // *FromDate extractors and date comparisons read it like any date). Empty/absent range → Unknown.
    FunctionOp.START_OF_DATE_RANGE -> dateRangeBound(f.args[0], start = true)
    FunctionOp.END_OF_DATE_RANGE -> dateRangeBound(f.args[0], start = false)
    // |x| — Abs over an expression, AbsValue over a field. Kernel VkBigDecimal.abs preserves its invalid
    // numeric sentinel, so an arithmetic domain failure must survive this legal wrapper; Unknown remains Unknown.
    FunctionOp.ABS, FunctionOp.ABS_VALUE -> mapNumber(evalExpr(f.args[0])) { Val.Num(it.abs()) }
    // The UTF-16 code-unit count of a string field's value. A CHECK-RELEVANT empty/absent operand → 0 (the
    // kernel's `laenge` fills an unspecified string as "", grow-only — IF73); a malformed, wrong-kind, or
    // otherwise non-evaluable operand → Unknown (suppressed). An operator-specific exception to the direct
    // String comparison (which an empty operand suppresses).
    FunctionOp.LENGTH -> if (emptyCheckRelevant(f.args[0])) Val.Num(DEC_ZERO)
        else (evalExpr(f.args[0]) as? Val.Txt)?.let { Val.Num(dec(it.v.length)) } ?: Val.Unknown
    // Rounding — both `Round*(expr[, n])` and `Round*Value(field[, n])`; omission means 0 places.
    FunctionOp.ROUND_DOWN -> roundExpr(f.args, RoundMode.FLOOR)
    FunctionOp.ROUND_UP -> roundExpr(f.args, RoundMode.CEILING)
    FunctionOp.ROUND_ACCOUNTING -> roundExpr(f.args, RoundMode.HALF_UP)
    FunctionOp.ROUND_DOWN_VALUE -> roundExpr(f.args, RoundMode.FLOOR)
    FunctionOp.ROUND_UP_VALUE -> roundExpr(f.args, RoundMode.CEILING)
    FunctionOp.ROUND_ACCOUNTING_VALUE -> roundExpr(f.args, RoundMode.HALF_UP)
    // FirstFilledValue: the first terminal value across operands/rows in authored order. NOT `combine` —
    // a later cell/slot is invisible; a first malformed value is Unknown. An empty selection uses the
    // operand kind's identity (number 0, otherwise Unknown).
    FunctionOp.FIRST_FILLED_VALUE -> firstFilledValue(f)
    // NumberOfValueInFields(value In f1, f2, …): count the FILLED fields equal to the constant.
    FunctionOp.NUMBER_OF_VALUE_IN_FIELDS -> numberOfValueInFields(f.args)
    // RangeAsString/RangeAsNumber(field, s, e): a substring of a STRING field by 1-based inclusive range.
    FunctionOp.RANGE_AS_STRING -> rangeSubstring(f.args)?.let { Val.Txt(it) } ?: Val.Unknown
    FunctionOp.RANGE_AS_NUMBER -> rangeSubstring(f.args)?.let {
        Val.Num(if (DIGITS.matches(it)) dec(it) else DEC_ZERO)   // kernel: only `\d+` parses, else 0
    } ?: Val.Unknown
    // FieldValueAsString(ref): the RAW stored value as a string (empty → ""). Coercion to string; the ref
    // may also be a semantic index (the kernel resolves the one matched cell, then feldWertAlsString).
    FunctionOp.FIELD_VALUE_AS_STRING -> fieldValueAsString(f.args[0])
    // FieldValueAsNumber(string/numeric-enum field or selected enum category): exact selected token → number;
    // empty → 0 (kernel feldWertAlsZahl).
    FunctionOp.FIELD_VALUE_AS_NUMBER -> fieldValueAsNumber(f.args[0])
    // SumOfProducts(a*, b*): Σ aᵢ·bᵢ paired row-by-row (empty → 0); any malformed element → suppressed.
    FunctionOp.SUM_OF_PRODUCTS -> sumOfProducts(f.args)
    // CurrentRepetition(group): the current row's 1-based index at that group's level (the last reps part).
    FunctionOp.CURRENT_REPETITION ->
        Val.Num(dec(ctxOf(f.args[0]).repsOf(pathOf(f.args[0])).last()))
    null -> throw UnsupportedConstructException("unsupported function ${f.name}")
    }
    if (memo != null && memoKey != null) memo[memoKey] = result
    return result
}

/**
 * An unfiltered, uncorrelated starred value aggregate depends on its star domains, not on the individual row
 * inside one of those domains. The expression plus every scope prefix is therefore the complete value identity:
 * sibling parent rows remain separate, while their child rows share one fold. A top-level aggregate may create a
 * one-off entry; keeping the eligibility rule purely semantic is cheaper and safer than trying to predict call
 * multiplicity from A12 repetition-list lengths (which contain one index for every path segment).
 */
private fun Interpreter.invariantAggregateMemoKey(f: Expr.Func, op: FunctionOp?): AggregateMemoKey? {
    if (computeMode) return null
    if (op !in MEMOIZED_STAR_AGGREGATES) return null
    // Under partial validation the count asks its [GroupCountGate] inside the fold, keyed by the gate's own
    // bound prefix; the memo key derives its prefix separately, so the two are not assumed to coincide there.
    if (op == FunctionOp.NUMBER_OF_FILLED_GROUPS && groupCountGate != null) return null
    val stars = f.args.map { it as? Expr.Star ?: return null }
    if (stars.any { it.having != null || it.outer }) return null
    val scopes = stars.map { star ->
        val context = ctxOf(star)
        val groupPath = if (context.declaredKind(star.path) == null) star.path else star.path.substringBeforeLast('/')
        context.scopePrefixOf(groupPath, star.bindDepth).toList()
    }
    return AggregateMemoKey(f, scopes)
}

/**
 * The interval-END form of a `DateRange(start, finish)` construction's already-evaluated finish [value], read
 * through the finish [operand]'s own declared date format ([intervalEndOfDeclaredFormat]). An operand shape
 * that names no single field, or a field with no declared date format, keeps the evaluated value — the
 * asymmetry is a property of the DECLARATION, so there is nothing to widen without one.
 */
private fun Interpreter.intervalEndOf(value: String, operand: Expr): String {
    val path = when (operand) {
        is Expr.Field -> operand.path
        is Expr.SemanticIndex -> operand.path
        is Expr.EntitySpec -> operand.source.path
        else -> return value
    }
    val format = ctx.dateFormatOf(path) ?: return value
    return intervalEndOfDeclaredFormat(value, format) ?: value
}

/** A date-range endpoint. `BaseYear` is the one point-in-time operand the kernel admits in this slot and
 * denotes the complete reference year; ordinary operands must resolve to a real range. */
private fun Interpreter.dateRangeBound(operand: Expr, start: Boolean): Val {
    if (operand is Expr.Const && ConstantOp.byName(operand.name) == ConstantOp.BASE_YEAR) {
        val year = baseYearOrThrow().toString().padStart(4, '0')
        return Val.Txt(if (start) "$year-01-01" else "$year-12-31")
    }
    val range = evalExpr(operand) as? Val.Range ?: return Val.Unknown
    return Val.Txt(if (start) range.start else range.end)
}

/** `Round*([expr][, n])` / `Round*Value(field[, n])` — round the numeric first operand with [mode] to the
 *  OPTIONAL decimal-places literal: the kernel grammar makes the digits optional on BOTH forms
 *  (`rundenOperOp`/`rundenOp` — `(',' ZiffernFolge)?`), and an absent digits argument is the integer round
 *  (places 0; kernel-pinned, IF102 — `roundExpr` crashed on the legal 1-arg form and the field form
 *  hardcoded 0 even with a digits argument present). A non-numeric/Unknown value or a non-whole places
 *  operand → Unknown. */
private fun Interpreter.roundExpr(args: List<Expr>, mode: RoundMode): Val {
    val value = evalExpr(args[0])
    if (value !is Val.Num) return value.numericNonValue()
    val places = if (args.size < 2) 0
        else {
            val placeValue = evalExpr(args[1])
            if (placeValue !is Val.Num) return placeValue.numericNonValue()
            placeValue.v.display().asciiIntOrNull() ?: return Val.Unknown
        }
    return Val.Num(value.v.roundTo(places, mode))
}

/**
 * `NumberOfValueInFields(value In f1, f2, …)` — `args[0]` is the constant, `args[1..]` the field-list operands
 * (a plain field ref, or a `Group* / Field` repetition list, optionally `Having`-filtered). The kernel
 * (`RuntimeController.anzahlWertInFeldlisteIntern`) counts only **filled** fields whose value equals the
 * constant: an EMPTY cell is skipped (not counted — kernel `b == FALSE`, so an empty Number never matches `0`),
 * and a formally-invalid (Unknown) cell makes the whole count non-evaluable → the rule is suppressed. Equality
 * uses the same typed `==` semantics (`ValCompare` — scale-19 Number, exact textual projection). A STARRED
 * operand rides the SAME filtered iterator as [filledFieldsValue] (SPEC-2026-07-23-12): a `Having` filter drops
 * a row FIRST (a dropped row's malformed cell never suppresses), then a kept row is counted iff it is filled and
 * matches; on the compute path a kept malformed cell POISONs. The filter-sensitive result POLARITY rides
 * [countFill] with the value-match gate (containsFilteredValue), NOT the value-combiner blanket.
 */
private fun Interpreter.numberOfValueInFields(args: List<Expr>): Val {
    val target = evalExpr(args[0])
    return countValueInFields(target, args.subList(1, args.size))?.let { Val.Num(dec(it)) } ?: Val.Unknown
}

private fun Interpreter.countValueInFields(target: Val, operands: List<Expr>): Int? {
    var count = 0
    // The counted value is already resolved; only entity operands are expanded. BOTH repetition
    // shapes of a GROUP operand go through the shared owner: unexpanded, the `Expr.Star` arm below took the
    // group's PARENT rows and read a value at the group path, where no field is declared, while the plain arm
    // asked `filled` of that same valueless path — so the count was 0 on every document either way. Measured
    // on both: the kernel ADMITS the starred operand and counts one match per row across two rows (IF287's
    // class, eighth instance), and it ADMITS the UNSTARRED one over a non-repeatable group and expands its
    // direct declarations, where the interpreter still answered 0 (a12-kernel-lean SPEC-2026-08-24-05, IF311).
    for (arg in expandGroupOperands(operands)) {
        if (isKeyedGroup(arg)) {
            val keyed = arg as Expr.SemanticIndex
            val value = when (val cells = keyedGroupCells(keyed)) {
                is KeyedGroupCells.Selected -> cells.interpreter.countValueInFields(target, listOf(Expr.Field(keyed.path)))
                KeyedGroupCells.Empty -> 0
                KeyedGroupCells.Undecided -> null
            }
            count += value ?: return null
            continue
        }
        if (arg.isIndexedStar()) {
            val value = withIndexedStar(arg as Expr.EntitySpec, null) {
                countValueInFields(target, listOf(it))
            }
            count += value ?: return null
            continue
        }
        when (arg) {
            is Expr.Star -> {                    // a `Group* / Field` list (kernel: a group/wildcard expansion)
                val group = arg.path.substringBeforeLast('/')
                val having = arg.having
                val rows = if (having == null) ctxOf(arg).rowContexts(group, arg.bindDepth)
                    else filterCandidates(ctxOf(arg), group, arg.bindDepth)
                // A filtered list rides the SHARED one-kept-successor traversal — the sole filtered
                // field-list owner (SPEC-2026-07-23-12 follow-up); the filter still drops the row first.
                val kept = if (having == null) rows.asSequence() else keptRowsWithSuccessorLookahead(rows, having)
                for (rc in kept) {
                    if (unavailableEmpty(rc, arg.path)) return null
                    if (!rc.filled(arg.path)) continue                                        // empty → not counted
                    val v = rc.field(arg.path)
                    if (v is Val.Unknown) {                                                    // kept malformed → suppress
                        if (computeMode) throw PoisonedReadException(arg.path)
                        return null
                    }
                    if (ValCompare.equal(v, target)) count++
                }
            }
            else -> {
                val path = pathOf(arg)
                // The fill test and the value read must ask ONE context — `evalExpr` goes through `ctxOf`, which
                // is the OUTER one for a `$`-marked node ([IF287]'s fourteenth/twenty-third instances).
                if (unavailableEmpty(ctxOf(arg), path)) return null
                if (!ctxOf(arg).filled(path)) continue  // empty → not counted (kernel: b == FALSE)
                val v = evalExpr(arg)
                if (v is Val.Unknown) return null // a filled-but-malformed operand → non-evaluable
                if (ValCompare.equal(v, target)) count++
            }
        }
    }
    return count
}

/**
 * `SumOfProducts(a*, b*)` — Σ aᵢ·bᵢ over two parallel field lists of the SAME repeatable group, paired
 * **row-by-row** (the kernel zips the two row iterators). An empty cell reads as `0` (so its product is 0);
 * any malformed cell makes the whole sum non-evaluable → the rule is suppressed (the formal mechanism). Both
 * independently keyed parents can have different row counts (KeyedGroupFieldListDiffTest).
 */
private fun Interpreter.sumOfProducts(args: List<Expr>): Val {
    rejectHaving(args, "SumOfProducts")
    // Resolve the two row-aligned star lists here (ctx-coupled); the Σ aᵢ·bᵢ fold is pure ([Aggregates], IF10).
    return Aggregates.sumOfProducts(productOperandValues(args[0]), productOperandValues(args[1]))
}

private fun Interpreter.productOperandValues(operand: Expr): List<Val> = when (operand) {
    // The extent gate was asked by [evalFunc], in the row the key selects.
    is Expr.EntitySpec -> withIndexedStar(operand, listOf(Val.Unknown)) { productOperandValues(it) }
    is Expr.Star -> {
        val context = ctxOf(operand)
        if (computeMode && context.starScanStates(operand.path, operand.bindDepth).any { it == CellState.INVALID })
            throw PoisonedReadException(operand.path)
        if (starHasUnavailableEmpty(context, operand)) listOf(Val.Unknown)
        else context.starAll(operand.path, operand.bindDepth)
    }
    else -> throw UnsupportedConstructException("SumOfProducts requires starred field lists")
}

/**
 * Degrade LOUD (IF61) if any operand is a starred rep-list carrying a `Having` this consumer does not
 * evaluate — the kernel AUTHORING-REJECTS a filter on a `SumOfProducts` operand (`MVK_WILDCARD_AT_LOWEST_
 * LEVEL_REQUIRED`: the lowest repetition must be an unrestricted `*`), so on a valid model this never fires
 * (parity-by-construction); a filter that somehow reached the interpreter must be REPORTED (the rule is
 * reported unsupported), never SILENTLY ignored (which would compute the wrong answer). Contrast
 * `FieldValuesNotUnique`, which the kernel ACCEPTS + EVALUATES — it filters via [filteredStarCells], not here.
 */
internal fun rejectHaving(args: List<Expr>, op: String) {
    if (args.any { it is Expr.Star && it.having != null })
        throw UnsupportedConstructException("$op does not support a Having filter on its operand")
}

/**
 * `FieldValueAsString(ref)` — the RAW stored value as a string. A NOT-GIVEN read (empty/absent value,
 * absent indexed row, empty key) is [Val.Unknown] — the not-given string, NOT `Txt("")`: a standalone
 * computation then CLEARS (the string-copy store law, KERNEL-FINDINGS §11), a concat maps it to `""`
 * (IF81's empty rule), and a comparison is suppressed (`vergleicheSTRING`) — each context resolves it
 * exactly as the kernel does. COMPUTE-mode reads of a formally-INVALID cell POISON (IF85/IF108) — the
 * indexed read through [computeGuardedIndexCell] (shared with the direct operand), the plain read via the
 * same cellState gate as `evalExpr(Field)`; in validation both stay Unknown. A matched DATE-like indexed
 * cell is unsupported — its resolved [Val] is the canonical ISO form, not the raw stored text the kernel
 * returns (loud, not wrong). `SemanticIndexPresenceDiffTest`, `SemanticIndexMatchedInvalidCellDiffTest`.
 */
private fun Interpreter.fieldValueAsString(arg: Expr): Val = when (arg) {
    is Expr.SemanticIndex -> {
        if (dateLike(ctx.declaredKind(arg.path)))
            throw UnsupportedConstructException("FieldValueAsString over a date-valued semantic index is not supported")
        when (val cell = computeGuardedIndexCell(arg)) {
            null -> Val.Unknown
            is Val.Unknown -> Val.Unknown
            is Val.DomainFailure -> Val.DomainFailure
            is Val.Num -> Val.Txt(cell.v.display())
            is Val.Txt -> cell
            is Val.Bool -> Val.Txt(cell.v.toString())
            is Val.Range -> throw UnsupportedConstructException("FieldValueAsString over a date-range semantic index is not supported")
        }
    }
    else -> {
        val path = pathOf(arg)
        when {
            invalid(ctxOf(arg), path) -> Val.Unknown
            // `ctxOf`, not the ambient `ctx`: a `$`-marked operand resolves in the OUTER context, and the
            // gate on the line above already does — the sibling [fieldValueAsNumber] pairs them correctly.
            else -> ctxOf(arg).displayValue(path)?.let { Val.Txt(it) } ?: Val.Unknown
        }
    }
}

/** `FieldValueAsNumber(field | field -> category)` — the exact selected stored/category token parsed as a
 *  decimal; an EMPTY field reads as `0` (kernel `feldWertAlsZahl`), and a formally-invalid read is unavailable
 *  (or poisons a computation). Admission guarantees the selected domain obeys [fieldValueDecimalOrNull]. */
private fun Interpreter.fieldValueAsNumber(source: Expr): Val {
    val path = when (source) {
        is Expr.Field -> source.path
        is Expr.EnumCategory -> source.path
        else -> throw UnsupportedConstructException(
            "unsupported operand ${source::class.simpleName} in FieldValueAsNumber",
        )
    }
    if (invalid(ctxOf(source), path)) return Val.Unknown
    if (!ctxOf(source).filled(path)) return Val.Num(DEC_ZERO)
    val selected = when (source) {
        is Expr.Field -> ctxOf(source).displayValue(path)
        is Expr.EnumCategory -> (evalExpr(source) as? Val.Txt)?.v
        else -> null
    }
    return selected?.let(::fieldValueDecimalOrNull)?.let { Val.Num(it) } ?: Val.Unknown
}

/** Kernel FieldValueAsNumber decimal grammar, normalized for cross-host parsing. Decimal digits are Unicode
 *  decimal digits (the host Character.isDigit domain), while sign and point stay ASCII and positional. */
private fun fieldValueDecimalOrNull(raw: String): Dec? {
    if (raw.isEmpty()) return null
    val normalized = StringBuilder(raw.length)
    var at = 0
    if (raw[0] == '-') {
        normalized.append('-')
        at++
        if (at == raw.length) return null
    }
    var pointSeen = false
    var digitCount = 0
    while (at < raw.length) {
        val ch = raw[at]
        if (ch == '.') {
            if (pointSeen || at == raw.lastIndex) return null
            pointSeen = true
            normalized.append(ch)
        } else {
            val digit = DecimalDigits.valueOf(ch).takeIf { it >= 0 } ?: return null
            normalized.append(('0'.code + digit).toChar())
            digitCount++
        }
        at++
    }
    if (digitCount == 0) return null
    return decOrNull(normalized.toString())
}

/**
 * The substring of a STRING field's value by the 1-based inclusive `(field, start, end)` [args] — the kernel's
 * `getSubstring`: an EMPTY value OR `end > length` → `""` (NOT a partial substring); else `value[start-1, end)`.
 * Returns null only when the field ref is formally invalid (the caller yields Unknown; STRING fields have no
 * formal error, so this is defensive). The bounds are integer literals, validated `1 <= start <= end` at compile.
 */
private fun Interpreter.rangeSubstring(args: List<Expr>): String? {
    val path = pathOf(args[0])
    if (invalid(ctxOf(args[0]), path)) return null
    val value = ctxOf(args[0]).displayValue(path) ?: ""
    val s = (evalExpr(args[1]) as Val.Num).v.display().toInt()
    val e = (evalExpr(args[2]) as Val.Num).v.display().toInt()
    return if (value.isEmpty() || e > value.length) "" else value.substring(s - 1, e)
}

/**
 * An operand that reads as the kernel's fillable EMPTY value (`nichtAngegeben` & check-relevant) for the
 * value functions that coerce an unspecified operand to **0** and FIRE — `Length` (`BigDecimalUtils.laenge`),
 * the date/time extractors (`getDatumsTeil`) and the date diffs (`getDatumsDiff`), unlike a direct comparison
 * on an empty field (which is suppressed). A plain field ref that is not filled and not formally invalid, or a
 * SELECTED read that resolves to no cell — a clean no-match or a matched empty cell, judged like an empty field
 * (SPEC-2026-10-06-04, `adapter.laws.DateComponentRelevanceDiffTest`); constants and constructions are never
 * empty here. KERNEL-FINDINGS §6, IF73.
 */
private fun Interpreter.emptyCheckRelevant(e: Expr): Boolean = when (e) {
    is Expr.Field -> !ctxOf(e).filled(e.path) && !invalid(ctxOf(e), e.path)
    is Expr.SemanticIndex -> computeGuardedIndexCell(e) == null
    else -> false
}

/** A field operand that is formally invalid (a malformed value, or a required-but-empty field), or a selected read
 *  the index cannot resolve (a duplicated or malformed key, a malformed matched cell): the empty-as-0 value
 *  functions stay NON-evaluable on it (`nichtPruefungsrelevant` → `NICHT_PRUEF_REL_ZAHL`, the comparison is
 *  suppressed) — malformed BEATS empty (checked first in `getDatumsDiff`). */
private fun Interpreter.malformedField(e: Expr): Boolean = when (e) {
    is Expr.Field -> invalid(ctxOf(e), e.path)
    is Expr.SemanticIndex -> computeGuardedIndexCell(e) is Val.Unknown
    else -> false
}

/** `DifferenceIn{Months,Years}(a, b)` → a signed whole-period count; an empty operand reads as the fillable 0
 *  (either empty → 0, kernel `getDatumsDiff`); a malformed operand → Unknown (suppressed). */
private fun Interpreter.dateDiff(args: List<Expr>, op: FunctionOp): Val = temporalDifference(args) { a, b ->
    val result = when (op) {
        FunctionOp.DIFFERENCE_IN_MONTHS -> differenceInLegacyCalendarMonths(a.date, b.date)
        FunctionOp.DIFFERENCE_IN_YEARS -> differenceInLegacyCalendarYears(a.date, b.date)
        else -> error("not a month/year date difference: $op")
    }
    result?.let { Val.Num(dec(it)) } ?: Val.Unknown
}

/** A number picked from a date operand's (year, month, day); an EMPTY date → the fillable **0** (kernel
 *  `getDatumsTeil` — `DEFAULT_VALUE`, symmetric fill), a MALFORMED date → Unknown (suppressed). A DATE_TIME
 *  operand extracts from its DATE part (IF95 — the kernel has no datetime format definition: the field rides
 *  `FormatDefinitionDatum` with a time-bearing format, so `feldWertAlsDatum` accepts it). */
private fun Interpreter.dateComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
    val value = when (val evaluated = evalTemporal(e)) {
        is Evaluated.Present -> evaluated.value
        is Evaluated.NoValue -> return evaluated.numericDefault()
    }
    val parts = calendarDateParts(value.date) ?: return Val.Unknown
    return Val.Num(dec(pick(parts.first, parts.second, parts.third)))
}

/** The DATE part of a canonical datetime (`2024-06-25T05:21:07` → `2024-06-25`); a plain date unchanged.
 *  The kernel's date consumers parse a DATE_TIME value with its full format and use the date part (IF95);
 *  a garbage value stays garbage — the downstream date parse still rejects it. */
private fun dateOnly(v: String): String = v.substringBefore('T')

/** The date a time-of-day is anchored on ([TemporalValue.date]). */
private const val TIME_ANCHOR_DATE = "1970-01-01"

/** A number picked from a TIME operand's (hours, minutes, seconds) `HH:mm:ss`; an EMPTY time → the fillable
 *  **0** (`getDatumsTeil`, symmetric fill), a MALFORMED time → Unknown (suppressed). A DATE_TIME operand
 *  extracts from its TIME part (IF95's time half — the kernel parses the datetime with the field's own
 *  time-bearing format, so `HoursFromTime(<datetime>)` is legal and reads the clock time). */
private fun Interpreter.timeComponent(e: Expr, pick: (Int, Int, Int) -> Int): Val {
    val value = when (val evaluated = evalTime(e)) {
        is Evaluated.Present -> evaluated.value
        is Evaluated.NoValue -> return evaluated.numericDefault()
    }
    val m = TIME.matchEntire(value) ?: return Val.Unknown
    return Val.Num(dec(pick(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())))
}

/**
 * Whether a temporal constructor stops reading its arguments at the first formally invalid one. A computation does:
 * the invalid read poisons it and nothing after it runs. Validation reads every argument, so a later one that fails
 * at runtime still fails the rule (SPEC-2026-10-05-01, `adapter.laws.ConstructionArgumentPhaseDiffTest`).
 */
private val Interpreter.stopsAtFormalArgument: Boolean get() = computeMode

/** The status of a `Date(day, month, year)` construction — the basis for the constructor's value AND the
 *  `Valid`/`Invalid` verdicts (kernel-probed, KERNEL-FINDINGS §6). MALFORMED (a part is a formally invalid read)
 *  makes the whole non-evaluable; NON_RELEVANT (a part is cause-free non-relevance, such as an unknown year) does
 *  too, but clears cleanly; HAS_EMPTY (a part is an empty field) is fillable. */
internal enum class DateStatus { REAL, UNREAL_PRESENT, HAS_EMPTY, NON_RELEVANT, MALFORMED }

private data class DateAnalysis(val status: DateStatus, val iso: String?)

/** Classify a `Date(...)` construction's operands. Two arities: `Date(day, month, year)` and the 4-arg
 *  century form `Date(day, month, century, year)` whose full year is `century*100 + year` (kernel-probed,
 *  IF31). Precedence: a formally invalid part → MALFORMED; else a cause-free non-relevant part → NON_RELEVANT,
 *  which never stops the walk, so a later formally invalid part is still reached and wins (SPEC-2026-10-06-04,
 *  `adapter.laws.DateComponentRelevanceDiffTest`); else any empty field part → HAS_EMPTY; else REAL/UNREAL_PRESENT
 *  by the calendar check. A formally invalid part stops the walk only where [stopsAtFormalArgument] says so. */
private fun Interpreter.analyzeDate(parts: List<Expr>): DateAnalysis {
    if (parts.size !in 2..4) return DateAnalysis(DateStatus.MALFORMED, null)
    var hasEmpty = false
    var nonRelevant = false
    var malformed = false
    val nums = IntArray(parts.size)
    for (i in parts.indices) {
        when (val component = temporalConstructionComponentInt(parts[i])) {
            is Evaluated.Present -> nums[i] = component.value
            is Evaluated.NoValue -> when (component.reason) {
                NoValueReason.NOT_GIVEN -> hasEmpty = true
                NoValueReason.PRESENT_NO_VALUE -> nums[i] = 0
                NoValueReason.NON_RELEVANT -> when {
                    !component.formal -> nonRelevant = true
                    stopsAtFormalArgument -> return DateAnalysis(DateStatus.MALFORMED, null)
                    else -> malformed = true
                }
            }
        }
    }
    if (malformed) return DateAnalysis(DateStatus.MALFORMED, null)
    if (nonRelevant) return DateAnalysis(DateStatus.NON_RELEVANT, null)
    if (hasEmpty) return DateAnalysis(DateStatus.HAS_EMPTY, null)
    val d = nums[0]; val m = nums[1]
    // The 2-arg form takes the model's Base Year, which the kernel requires it to declare (MVK_MISSING_YEAR,
    // KF403); the 4-arg century form splits the year into century + 2-digit year: century*100 + year.
    val y = when (parts.size) {
        2 -> baseYear ?: return DateAnalysis(DateStatus.MALFORMED, null)
        3 -> nums[2]
        else -> nums[2] * 100 + nums[3]
    }
    val componentGate = d != 0 && m != 0 && if (parts.size == 4) nums[2] != 0 else y != 0
    return if (componentGate && isConstructibleDate(y, m, d, timeZone)) DateAnalysis(DateStatus.REAL, isoDate(y, m, d))
    else DateAnalysis(DateStatus.UNREAL_PRESENT, null)
}

/**
 * Numeric conversion for one statically admitted Date/Time component, preserving the source evaluation reason.
 * Direct Number/String fields and constants already evaluate to Number/text. Matching extractors need the
 * stronger path: an empty nested temporal source is still a missing component, a formal source is unavailable,
 * and a reached temporal expression with no value projects to fixed zero. The one Date field exception is exact
 * `yyyy` at complete Year: ordinary Date evaluation cannot represent a year-only value, so its stored display
 * text is read only at this constructor boundary where the kernel retags it as Number.
 */
private fun Interpreter.temporalConstructionComponentInt(e: Expr): Evaluated<Int> {
    if (malformedField(e)) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    if (emptyCheckRelevant(e)) return Evaluated.NoValue(NoValueReason.NOT_GIVEN)
    if (e is Expr.Field && ctxOf(e).declaredKind(e.path) == Kind.DATE && ctxOf(e).dateFormatOf(e.path) == "yyyy") {
        return ctxOf(e).displayValue(e.path)?.javaIntOrNull()?.let { Evaluated.Present(it) }
            ?: Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
    if (e is Expr.Func) {
        return when (FunctionOp.byName(e.name)) {
            FunctionOp.YEAR_FROM_DATE -> extractedDateComponent(e) { year, _, _ -> year }
            FunctionOp.MONTH_FROM_DATE -> extractedDateComponent(e) { _, month, _ -> month }
            FunctionOp.DAY_FROM_DATE -> extractedDateComponent(e) { _, _, day -> day }
            FunctionOp.HOURS_FROM_TIME -> extractedTimeComponent(e) { hour, _, _ -> hour }
            FunctionOp.MINUTES_FROM_TIME -> extractedTimeComponent(e) { _, minute, _ -> minute }
            FunctionOp.SECONDS_FROM_TIME -> extractedTimeComponent(e) { _, _, second -> second }
            else -> evaluatedComponentInt(evalExpr(e))
        }
    }
    return evaluatedComponentInt(evalExpr(e))
}

private fun Interpreter.extractedDateComponent(
    extraction: Expr.Func,
    pick: (Int, Int, Int) -> Int,
): Evaluated<Int> = when (val source = evalTemporal(extraction.args.single())) {
    is Evaluated.Present -> if (source.value.missing) Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else calendarDateParts(source.value.date)
        ?.let { Evaluated.Present(pick(it.first, it.second, it.third)) }
        ?: Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    is Evaluated.NoValue -> source.asConstructionComponent()
}

private fun Interpreter.extractedTimeComponent(
    extraction: Expr.Func,
    pick: (Int, Int, Int) -> Int,
): Evaluated<Int> = when (val source = evalTimeKeepingProvenance(extraction.args.single())) {
    is Evaluated.Present -> TIME.matchEntire(source.value)
        ?.let { match ->
            Evaluated.Present(
                pick(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                ),
            )
        }
        ?: Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    is Evaluated.NoValue -> source.asConstructionComponent()
}

private fun Evaluated.NoValue.asConstructionComponent(): Evaluated<Int> =
    if (reason == NoValueReason.PRESENT_NO_VALUE) Evaluated.Present(0) else this

private fun evaluatedComponentInt(value: Val): Evaluated<Int> = when (value) {
    is Val.DomainFailure -> Evaluated.Present(0)
    else -> valToInt(value)?.let { Evaluated.Present(it) }
        ?: Evaluated.NoValue(NoValueReason.NON_RELEVANT)
}

/**
 * One evaluated temporal value, carrying the information the kernel's `VkDate` keeps but a plain ISO string
 * loses: calendar provenance, exact instant identity, and why a legal expression has no value.
 */
private sealed interface Evaluated<out T> {
    data class Present<T>(val value: T) : Evaluated<T>
    /**
     * [formal] separates a no-value caused by a formally invalid read from the cause-free non-relevance of a
     * partial date with no resolvable value: only the former stops `DateTime(date, time)` before its Time argument
     * (SPEC-2026-10-04-02, `adapter.laws.ConstructionProvenanceDiffTest`).
     */
    data class NoValue(val reason: NoValueReason, val formal: Boolean = true) : Evaluated<Nothing>
}

private enum class NoValueReason {
    /** An empty source: numeric temporal consumers receive `0`, retaining fillable/missing provenance. */
    NOT_GIVEN,

    /** A reached temporal expression with no value (unreal date or domain failure): its numeric projection is fixed `0`. */
    PRESENT_NO_VALUE,

    /** Malformed/formally-invalid: the expression is not check-relevant and numeric consumers suppress. */
    NON_RELEVANT,
}

private data class TemporalValue(
    val text: String,
    val exactInstantMillis: Long? = null,
    /**
     * A payload that carries missing provenance: a shift whose extracted amount came from an empty source keeps
     * its source's value, yet a construction component read from it is empty (SPEC-2026-10-04-06,
     * `adapter.laws.ShiftAmountPhaseDiffTest`).
     */
    val missing: Boolean = false,
) {
    /**
     * The calendar date this value carries. A pure time-of-day has none of its own, but A12 anchors a time on
     * 1 January 1970, so a year a Base Year admits on it is 1970 — on a clock field, a built time and a time
     * recovered from a date-time alike, never the declared Base Year (SPEC-2026-10-08-01,
     * `adapter.laws.BaseYearExtractorAdmissionDiffTest`).
     */
    val date: String get() = if (isTimeOnly) TIME_ANCHOR_DATE else dateOnly(text)
    val isDateTime: Boolean get() = 'T' in text

    /** A pure time-of-day (`HH:mm:ss`, no date) — it has no model-zone instant and folds by its decoded
     *  time coordinate, not a resolved instant (SPEC-2026-07-21-07). */
    val isTimeOnly: Boolean get() = TIME.matchEntire(text) != null

    fun instantMillis(timeZone: ModelTimeZone): Long =
        exactInstantMillis ?: checkNotNull(calendarOperandInstant(text, timeZone)) {
            "real temporal value has no model-zone instant: $text ($timeZone)"
        } * 1_000L

    fun instantSeconds(timeZone: ModelTimeZone): Long =
        instantMillis(timeZone).floorDiv(1_000L)
}

/** The decoded seconds-of-day of a `HH:mm:ss` time-of-day (its zone-independent fold coordinate). */
private fun timeOfDaySeconds(text: String): Long {
    val (h, m, s) = TIME.matchEntire(text)!!.destructured
    return h.toLong() * 3_600 + m.toLong() * 60 + s.toLong()
}

private fun Evaluated<TemporalValue>.asTemporalVal(): Val = when (this) {
    is Evaluated.Present -> Val.Txt(value.text)
    is Evaluated.NoValue -> Val.Unknown
}

private fun Evaluated<String>.asTimeVal(): Val = when (this) {
    is Evaluated.Present -> Val.Txt(value)
    is Evaluated.NoValue -> Val.Unknown
}

private fun Evaluated.NoValue.numericDefault(): Val =
    if (reason == NoValueReason.NON_RELEVANT) Val.Unknown else Val.Num(DEC_ZERO)

/** NON_RELEVANT dominates NOT_GIVEN, which dominates a reached-but-valueless result. A NON_RELEVANT result is
 *  formal when any of its non-relevant inputs is, so cause-free non-relevance survives a shift or a composition. */
private fun dominantNoValue(vararg values: Evaluated<*>): Evaluated.NoValue? {
    val absent = values.filterIsInstance<Evaluated.NoValue>()
    val nonRelevant = absent.filter { it.reason == NoValueReason.NON_RELEVANT }
    return when {
        nonRelevant.isNotEmpty() ->
            Evaluated.NoValue(NoValueReason.NON_RELEVANT, formal = nonRelevant.any { it.formal })
        absent.any { it.reason == NoValueReason.NOT_GIVEN } ->
            Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        absent.isNotEmpty() -> Evaluated.NoValue(NoValueReason.PRESENT_NO_VALUE)
        else -> null
    }
}

/**
 * Evaluate a date/date-time expression once as a reason-bearing value. Every legal temporal producer maps
 * here, so composition retains both no-value reason and legacy-calendar/instant identity instead of
 * re-discovering them from selected AST shapes at each consumer.
 */
private fun Interpreter.evalTemporal(e: Expr): Evaluated<TemporalValue> {
    if (e is Expr.Func) {
        return when (FunctionOp.byName(e.name)) {
            FunctionOp.DATE -> evaluateDateConstruction(e.args)
            FunctionOp.TIME -> when (val time = evaluateTimeConstruction(e.args)) {
                is Evaluated.Present -> Evaluated.Present(TemporalValue(time.value))
                is Evaluated.NoValue -> time
            }
            FunctionOp.VALUE_AS_DATE -> partialDateValue(e.args)
            FunctionOp.DATE_TIME -> composeDateTime(e.args)
            FunctionOp.DATE_FROM_DATE_TIME -> mapTemporal(e.args.single()) { value ->
                value.copy(text = value.date, exactInstantMillis = null)
            }
            FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS,
            FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS -> shiftTemporal(e)
            FunctionOp.MIN, FunctionOp.MAX -> aggregateTemporal(e)
            FunctionOp.START_OF_DATE_RANGE, FunctionOp.END_OF_DATE_RANGE -> rangeBoundTemporal(e)
            else -> temporalLeaf(e)
        }
    }
    return temporalLeaf(e)
}

/**
 * A range endpoint as a temporal operand. Without a value it carries its RANGE's reason, so an empty range gives an
 * empty endpoint — which an extractor reads as the fillable 0, exactly as for an empty date — and a malformed one a
 * non-relevant endpoint (KERNEL-FINDINGS KF540's empty rows, both strategies).
 */
private fun Interpreter.rangeBoundTemporal(e: Expr.Func): Evaluated<TemporalValue> =
    when (val bound = temporalLeaf(e)) {
        is Evaluated.Present -> bound
        is Evaluated.NoValue -> temporalLeaf(e.args.single()) as? Evaluated.NoValue ?: bound
    }

private fun Interpreter.evaluateDateConstruction(args: List<Expr>): Evaluated<TemporalValue> {
    val analysis = analyzeDate(args)
    return when (analysis.status) {
        // A constructed Date retains the instant its FRESH resolution selected (SPEC-2026-07-29-01): over an
        // ambiguous midnight — Berlin's only one is 1916-10-01 — that is the LATER copy, which a comparison
        // against a shifted Date must be able to tell apart from the shift's own landing.
        DateStatus.REAL -> Evaluated.Present(
            TemporalValue(
                analysis.iso!!,
                freshDateInstant(analysis.iso, timeZone)?.times(1_000L),
            ),
        )
        DateStatus.UNREAL_PRESENT -> Evaluated.NoValue(NoValueReason.PRESENT_NO_VALUE)
        DateStatus.HAS_EMPTY -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        DateStatus.NON_RELEVANT -> Evaluated.NoValue(NoValueReason.NON_RELEVANT, formal = false)
        DateStatus.MALFORMED -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
}

private fun Interpreter.temporalLeaf(e: Expr): Evaluated<TemporalValue> {
    if (e is Expr.Const) {
        when (ConstantOp.byName(e.name)) {
            ConstantOp.NOW -> return Evaluated.Present(nowTemporalValue())
            ConstantOp.TODAY -> {
                val today = clock?.today
                    ?: throw UnsupportedConstructException("Today needs an evaluation clock — construct DmInterpreter with an EvalClock")
                return clockReading(today, 0L)?.let { Evaluated.Present(it) }
                    ?: throw UnsupportedConstructException("EvalClock.today is not a real date in the model time zone: $today")
            }
            ConstantOp.BASE_YEAR -> return temporalText((baseYearDate() as Val.Txt).v)
            else -> Unit
        }
    }
    // No target or counterpart constrains a literal here, so nothing declares a year and the anchor applies.
    val evaluated = coerceTemporalLiteral(evalExpr(e), declaredFormat = null)
    if (evaluated is Val.Txt) {
        // A pure time-of-day has no date and therefore no model-zone instant; it carries only its decoded
        // time coordinate (the operand-list Min/Max over TIME folds on that, SPEC-2026-07-21-07).
        if (TIME.matchEntire(evaluated.v) != null) return Evaluated.Present(TemporalValue(evaluated.v))
        return temporalText(evaluated.v)
    }
    return when {
        malformedField(e) -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        emptyCheckRelevant(e) -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
}

/** [EvalClock.now]'s proleptic date-time and its millisecond remainder. */
private fun Interpreter.clockNow(): Pair<String, Long> {
    val raw = clock?.now
        ?: throw UnsupportedConstructException(
            "Now needs an evaluation clock with a current date-time — construct DmInterpreter with an EvalClock(today, now)",
        )
    val match = CLOCK_DATE_TIME.matchEntire(raw)
        ?: throw UnsupportedConstructException("EvalClock.now must be yyyy-MM-dd'T'HH:mm:ss or yyyy-MM-dd'T'HH:mm:ss.SSS")
    return match.groupValues[1] to match.groupValues[2].ifEmpty { "0" }.toLong()
}

private fun Interpreter.nowTemporalValue(): TemporalValue {
    val (text, millis) = clockNow()
    return clockReading(text, millis)
        ?: throw UnsupportedConstructException("EvalClock.now is not a real date-time in the model time zone: ${clock?.now}")
}

/**
 * A clock reading as A12 holds it. A12 sets a `GregorianCalendar` in the model's zone to its clock instant, and that
 * calendar is Julian before 1582-10-15, so `DayFromDate(Today)` at 1500-03-10T12:00Z is 29 (of February) on both
 * strategies (KF576). [EvalClock] carries proleptic labels, which a stored value compares against as text, so the
 * temporal reading re-labels the same instant under the hybrid calendar; after the cutover the two labels agree.
 */
private fun Interpreter.clockReading(prolepticText: String, millis: Long): TemporalValue? {
    val local = if ('T' in prolepticText) epochSecond(prolepticText) else epochDay(prolepticText)?.times(86_400L)
    val instant = local?.let { localToInstant(it, timeZone) } ?: return null
    val hybrid = isoFromCalendarInstant(instant, timeZone)
    val text = if ('T' in prolepticText) hybrid else hybrid.substringBefore('T')
    return TemporalValue(text, instant * 1_000L + millis)
}

/**
 * A date or date-time label as A12 holds it: its instant read on the hybrid calendar, so a value below the cutover
 * is Julian in every shift, difference and comparison, whichever operator produced it (KF588).
 */
private fun Interpreter.temporalText(text: String): Evaluated<TemporalValue> {
    val epochSecond = calendarOperandInstant(text, timeZone)
        ?: return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    return Evaluated.Present(TemporalValue(text, epochSecond * 1_000L))
}

private fun Interpreter.composeDateTime(args: List<Expr>): Evaluated<TemporalValue> {
    if (args.size != 2) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    val date = evalTemporal(args[0])
    // The generated constructor evaluates Date first. In a computation a formally unavailable Date stops before the
    // Time arm, while validation reads on ([stopsAtFormalArgument]); a partial date with no value is not formally
    // unavailable, so the Time arm runs in both phases (SPEC-2026-10-04-02).
    if (date is Evaluated.NoValue && date.reason == NoValueReason.NON_RELEVANT && date.formal && stopsAtFormalArgument) {
        return date
    }
    val time = evalTime(args[1])
    dominantNoValue(date, time)?.let { return it }
    val dateValue = (date as Evaluated.Present).value
    val timeValue = (time as Evaluated.Present).value
    val text = "${dateValue.date}T$timeValue"
    // A constructed wall time inside a zone gap is a fixed outcome that clears cleanly, unlike a gap literal,
    // which is a runtime failure (KF513).
    val instant = calendarOperandInstant(text, timeZone)
        ?: return Evaluated.NoValue(NoValueReason.PRESENT_NO_VALUE)
    return Evaluated.Present(TemporalValue(text, instant * 1_000L))
}

private fun Interpreter.evalTime(e: Expr): Evaluated<String> {
    if (e is Expr.Func) {
        return when (FunctionOp.byName(e.name)) {
            FunctionOp.TIME -> evaluateTimeConstruction(e.args)
            FunctionOp.TIME_FROM_DATE_TIME -> timePart(evalTemporal(e.args.single()))
            FunctionOp.DATE_TIME, FunctionOp.ADD_DAYS, FunctionOp.ADD_HOURS,
            FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS -> timePart(evalTemporal(e))
            else -> timeLeaf(e)
        }
    }
    return timeLeaf(e)
}

private fun Interpreter.timeLeaf(e: Expr): Evaluated<String> {
    val evaluated = evalExpr(e)
    if (evaluated is Val.Txt) {
        val time = evaluated.v.substringAfter('T')
        if (TIME.matches(time)) return Evaluated.Present(time)
    }
    return when {
        malformedField(e) -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        emptyCheckRelevant(e) -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
}

private fun timePart(value: Evaluated<TemporalValue>): Evaluated<String> = when (value) {
    is Evaluated.NoValue -> value
    is Evaluated.Present -> {
        val time = value.value.text.substringAfter('T', missingDelimiterValue = "")
        if (TIME.matches(time)) Evaluated.Present(time)
        else Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
}

private fun Interpreter.evaluateTimeConstruction(args: List<Expr>): Evaluated<String> {
    if (args.size > 3) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    var hasEmpty = false
    var hasMalformed = false
    val components = IntArray(3)
    for (i in args.indices) {
        when (val component = temporalConstructionComponentInt(args[i])) {
            is Evaluated.Present -> components[i] = component.value
            is Evaluated.NoValue -> when (component.reason) {
                NoValueReason.NOT_GIVEN -> hasEmpty = true
                NoValueReason.PRESENT_NO_VALUE -> components[i] = 0
                NoValueReason.NON_RELEVANT -> {
                    if (component.formal && stopsAtFormalArgument) return component
                    hasMalformed = true
                }
            }
        }
    }
    if (hasMalformed) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    if (hasEmpty) return Evaluated.NoValue(NoValueReason.NOT_GIVEN)
    val (hour, minute, second) = Triple(components[0], components[1], components[2])
    // An unreal clock is a fixed outcome, not a missing one: an extremum over it fires VALUE (SPEC-2026-10-04-02).
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return Evaluated.NoValue(NoValueReason.PRESENT_NO_VALUE)
    }
    return Evaluated.Present("${pad2(hour)}:${pad2(minute)}:${pad2(second)}")
}

private fun Interpreter.mapTemporal(
    source: Expr,
    transform: (TemporalValue) -> TemporalValue,
): Evaluated<TemporalValue> = when (val value = evalTemporal(source)) {
    is Evaluated.NoValue -> value
    is Evaluated.Present -> Evaluated.Present(transform(value.value))
}

private fun Interpreter.shiftTemporal(e: Expr.Func): Evaluated<TemporalValue> {
    val source = evalTemporal(e.args[0])
    val amountValue = evalExpr(e.args[1])
    // Java `BigDecimal.intValue()`: truncate toward zero, then keep the low signed 32 bits — the kernel's
    // `VkBigDecimal.intValue` (SPEC-2026-07-27-04). So `1.9` shifts by 1 and an out-of-range amount WRAPS;
    // neither is rejected, and neither is saturated. A domain failure supplies no amount at all.
    val amount = (amountValue as? Val.Num)?.v?.display()?.let(::plainDecimalAsJavaInt)
    val amountState: Evaluated<Int> = when {
        amount != null -> Evaluated.Present(amount)
        amountValue is Val.DomainFailure -> Evaluated.NoValue(NoValueReason.PRESENT_NO_VALUE)
        malformedField(e.args[1]) -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        emptyCheckRelevant(e.args[1]) -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
    dominantNoValue(source, amountState)?.let { return it }
    val value = (source as Evaluated.Present).value
    val n = (amountState as Evaluated.Present).value
    // An amount extracted from an empty source evaluates as zero, but the shift keeps that missing provenance.
    val missingAmount = e.args[1] is Expr.Func && FunctionOp.byName((e.args[1] as Expr.Func).name) in TEMPORAL_EXTRACTORS &&
        (temporalConstructionComponentInt(e.args[1]) as? Evaluated.NoValue)?.reason == NoValueReason.NOT_GIVEN
    val shifted = when (FunctionOp.byName(e.name)) {
        FunctionOp.ADD_DAYS -> if (value.isDateTime) {
            val landing = wallDayLandingInstant(value.instantSeconds(timeZone), n, timeZone)
            val landingMillis = landing * 1_000L + value.instantMillis(timeZone).mod(1_000L)
            TemporalValue(isoFromCalendarInstant(landing, timeZone), landingMillis)
        } else {
            val date = addLegacyCalendarDays(value.date, n)
            // The civil label stays label arithmetic (it owns the legacy-hybrid rendering); the retained
            // instant lands from the SOURCE instant on the expected civil date, re-fitting the offset when the
            // shift crosses a transition (IF348, `adapter.laws.DayStepLandingDiffTest`).
            date?.let {
                TemporalValue(it, wallDayLandingInstant(value.instantSeconds(timeZone), n, timeZone) * 1_000L)
            }
        }
        // Month and year mutate CIVIL FIELDS, so their landing is resolved at compute time from the resulting
        // label — sign-independently, taking the later copy of an overlap (SPEC-2026-07-29-01). That is a
        // different mechanism from the day arm above, which carries the source instant.
        FunctionOp.ADD_MONTHS -> {
            addLegacyCalendarMonths(value.date, n)
                ?.let { TemporalValue(it, fieldMutationInstantOfDate(it, timeZone)?.times(1_000L)) }
        }
        FunctionOp.ADD_YEARS -> {
            addLegacyCalendarYears(value.date, n)
                ?.let { TemporalValue(it, fieldMutationInstantOfDate(it, timeZone)?.times(1_000L)) }
        }
        FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS -> {
            val unit = when (FunctionOp.byName(e.name)) {
                FunctionOp.ADD_HOURS -> 3_600_000L
                FunctionOp.ADD_MINUTES -> 60_000L
                else -> 1_000L
            }
            val instantMillis = value.instantMillis(timeZone) + n.toLong() * unit
            TemporalValue(isoFromCalendarInstant(instantMillis.floorDiv(1_000L), timeZone), instantMillis)
        }
        else -> error("not a temporal shift: ${e.name}")
    }
    return shifted?.let { Evaluated.Present(it.copy(missing = value.missing || missingAmount)) }
        ?: Evaluated.NoValue(NoValueReason.NON_RELEVANT)
}

private val TEMPORAL_EXTRACTORS = setOf(
    FunctionOp.YEAR_FROM_DATE, FunctionOp.MONTH_FROM_DATE, FunctionOp.DAY_FROM_DATE,
    FunctionOp.HOURS_FROM_TIME, FunctionOp.MINUTES_FROM_TIME, FunctionOp.SECONDS_FROM_TIME,
)

/** [evalTime] for a construction component, where a payload carrying missing provenance reads as empty. */
private fun Interpreter.evalTimeKeepingProvenance(e: Expr): Evaluated<String> {
    val shifted = e is Expr.Func && FunctionOp.byName(e.name) in setOf(FunctionOp.DATE_TIME, FunctionOp.ADD_DAYS,
        FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS)
    if (!shifted) return evalTime(e)
    val value = evalTemporal(e)
    return if (value is Evaluated.Present && value.value.missing) Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else timePart(value)
}

private fun Interpreter.aggregateTemporal(e: Expr.Func): Evaluated<TemporalValue> {
    val values = e.args.map(::evalTemporal)
    if (values.filterIsInstance<Evaluated.NoValue>()
            .any { it.reason == NoValueReason.NON_RELEVANT }) {
        return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
    val present = values.filterIsInstance<Evaluated.Present<TemporalValue>>().map { it.value }
    if (present.isEmpty()) return Evaluated.NoValue(NoValueReason.NOT_GIVEN)
    // Fold coordinate: a pure time-of-day by its decoded seconds, any dated value by its resolved instant
    // (SPEC-2026-07-21-07). Authored operands are homogeneous, so one key kind decides the whole fold.
    fun key(v: TemporalValue): Long = if (v.isTimeOnly) timeOfDaySeconds(v.text) * 1_000L else v.instantMillis(timeZone)
    val selected = when (FunctionOp.byName(e.name)) {
        FunctionOp.MIN -> present.minBy(::key)
        FunctionOp.MAX -> present.maxBy(::key)
        else -> error("not a temporal aggregate: ${e.name}")
    }
    return Evaluated.Present(selected)
}

private fun Interpreter.temporalDifference(
    args: List<Expr>,
    difference: (TemporalValue, TemporalValue) -> Val,
): Val {
    val left = evalTemporal(args[0])
    val right = evalTemporal(args[1])
    dominantNoValue(left, right)?.let { return it.numericDefault() }
    return difference(
        (left as Evaluated.Present).value,
        (right as Evaluated.Present).value,
    )
}

/** The [DateStatus] of a `Valid`/`Invalid` predicate's single operand — its `Date(...)` construction;
 *  a non-`Date(...)` operand is non-evaluable here → MALFORMED (the predicate goes quiet, not wrong). */
internal fun Interpreter.dateStatusOf(arg: Expr): DateStatus {
    val parts = (arg as? Expr.Func)?.takeIf { FunctionOp.byName(it.name) == FunctionOp.DATE }?.args
        ?: return DateStatus.MALFORMED
    return analyzeDate(parts).status
}

/** An integer from a number value or a digit-string constant (the `Date`/`Time` components); null otherwise. */
private fun valToInt(v: Val): Int? = when (v) {
    is Val.Num -> v.v.display().asciiIntOrNull()
    // A12 reads a text component's digits in any script: "\u0661\u0660" is 10 (DateComponentConstantDiffTest).
    is Val.Txt -> v.v.javaIntOrNull()
    else -> null
}

private fun pad2(n: Int): String = n.toString().padStart(2, '0')

/** Exact instant identity of a legal date-time expression, carried by [evalTemporal] through composition. */
internal fun Interpreter.dateTimeInstant(e: Expr): Long? =
    (evalTemporal(e) as? Evaluated.Present)?.value?.takeIf { it.isDateTime }?.instantMillis(timeZone)

/**
 * Exact instant identity of a legal DATE expression — the date twin of [dateTimeInstant], read structurally so
 * a constructed or shifted Date keeps the instant its own resolution selected instead of having its rendered
 * label re-resolved (SPEC-2026-07-29-01). A plain field read has no retained instant and derives one by the
 * ordinary parse resolution, which is exactly what the kernel's `VkDate` carries for it.
 */
internal fun Interpreter.dateInstant(e: Expr): Long? =
    (evalTemporal(e) as? Evaluated.Present)?.value
        ?.takeIf { !it.isDateTime && !it.isTimeOnly }
        ?.instantMillis(timeZone)

/**
 * `ValueAsDate` as a reason-bearing temporal value: an empty partial date is a missing outcome, and one with no
 * resolvable value (malformed, or an unknown year) is cause-free non-relevance, which [composeDateTime] does not
 * treat as a formally invalid read (SPEC-2026-10-04-02).
 */
private fun Interpreter.partialDateValue(args: List<Expr>): Evaluated<TemporalValue> =
    when (val value = partialDateSelection(args)) {
        null -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        is Val.Txt -> temporalText(value.v)
        else -> Evaluated.NoValue(NoValueReason.NON_RELEVANT, formal = false)
    }

/** `ValueAsDate(field, FirstDay|LastDay)` — select a partial DateType value's concrete boundary. Unknown
 *  when the field is empty/formally-invalid, its year is unknown, or the value doesn't parse. */
private fun Interpreter.valueAsDate(args: List<Expr>): Val = partialDateSelection(args) ?: Val.Unknown

/** Which concrete day a partially known Date is read as. */
private enum class DateBoundary { FIRST_DAY, LAST_DAY }

/**
 * The boundary `ValueAsDate` selects, or null where nothing is given. A key-selected cell is read through the same
 * guarded keyed read as a direct keyed operand, so its key, its column and the selected cell's own validity gate it
 * as they gate any keyed read; A12 admits the keyed operand (`validate.laws.KeyedTemporalOperandLawsTest`) and
 * resolves it as the row it selects (`adapter.laws.KeyedTemporalOperandDiffTest`).
 */
private fun Interpreter.partialDateSelection(args: List<Expr>): Val? {
    val boundary = if ((args[1] as? Expr.Const)?.name == "LastDay") DateBoundary.LAST_DAY else DateBoundary.FIRST_DAY
    val operand = args[0]
    if (operand is Expr.SemanticIndex) return computeGuardedIndexCell(operand, selection = boundary)
    val source = ctxOf(operand)
    val path = pathOf(operand)
    if (invalid(source, path)) return Val.Unknown
    val raw = source.displayValue(path) ?: return null
    return resolvePartialDate(
        raw,
        ctx.dateFormatOf(path) ?: "yyyy-MM-dd",
        ctx.datePrecisionOf(path),
        boundary == DateBoundary.LAST_DAY,
    )?.let { Val.Txt(it) } ?: Val.Unknown
}

/**
 * The one ordered `FirstFilledValue` result consumed by both value evaluation and polarity. Operands are
 * encountered in authored order. A star's `Having` marker is observed immediately before that slot is
 * scanned; a filled or formally-unavailable value terminates the whole walk, so no later slot, filter, or
 * cell is read (IF184) — with one filtered-star refinement (SPEC-2026-07-23-10): the traversal prefetches
 * ONE kept successor before the current kept target is consumed ([keptRowsWithSuccessorLookahead]), so a
 * filter poison met during that successor search precedes a filled pending target, while filters beyond the
 * prefetched successor stay unread. An empty encountered before termination makes the result displaceable by
 * filling; an encountered filter makes it symmetrically fillable regardless of whether it keeps a row.
 */
internal data class FirstFilledScan(val value: Val, val fill: Fill, val encounteredFilter: Boolean)

private data class FirstFilledWalk(var fillable: Boolean = false, var encounteredFilter: Boolean = false)

private sealed interface FirstFilledStep {
    data object Continue : FirstFilledStep
    data class Stop(val value: Val) : FirstFilledStep
}

/**
 * The filtered entity traversal shared by every filtered field-list consumer — the kernel's
 * `EbenenIterator.InnerIterator` ONE-KEPT-SUCCESSOR LOOKAHEAD (SPEC-2026-07-23-10/-11): after the current
 * candidate is kept, the successor search evaluates later filters (skipping false candidates) until another
 * candidate is kept or the stream ends, BEFORE the consumer reads the current candidate's target. So a filter
 * poison met during that search precedes the pending current target, while filters beyond one prefetched
 * successor stay unread when the current target terminates the consumer's pull ([firstFilledScan]'s prefix
 * stop). Filtering never becomes globally eager; a draining consumer ([starOperandVals]) still visits every
 * kept row. Validation drops unknown filter results, but a reached temporal-literal failure still
 * matters; its deciding-prefix consumers therefore use this lazy traversal too (SPEC-2026-10-10-02).
 */
internal fun Interpreter.keptRowsWithSuccessorLookahead(
    rows: List<EvalContext>,
    having: Cond,
    keeps: ((EvalContext) -> Boolean)? = null,
    eligible: (EvalContext?, EvalContext) -> Boolean = { _, _ -> true },
    resume: ((from: Int, keptIndex: Int) -> Int)? = null,
): Sequence<EvalContext> = sequence {
    var pending: EvalContext? = null
    var keptIndex = -1
    var index = 0
    while (true) {
        // [resume] jumps past candidates [eligible] would refuse anyway, once a row is kept.
        if (resume != null && keptIndex >= 0) index = resume(index, keptIndex)
        if (index >= rows.size) break
        val row = rows[index]
        if (eligible(pending, row) && (keeps?.invoke(row) ?: havingInterp(row).keeps(having))) {
            pending?.let { yield(it) }
            pending = row
            keptIndex = index
        }
        index++
    }
    pending?.let { yield(it) }
}

/** A filtered list's candidate rows as A12's filter walk visits them, missing coordinates included
 *  ([FilterWalkRowProvider]); in a computation only the groups the whole programme names extend the walk. */
internal fun Interpreter.filterCandidates(c: EvalContext, groupPath: String, bindDepth: Int): List<EvalContext> =
    c.filterWalkRows(groupPath, bindDepth, filterWalkGroups)

internal enum class FilterTraversal { DENSE, SPARSE }

/** Sparse consumers test occupancy at the first coordinate differing from the last kept row. A rejected
 * candidate never advances that cursor, so initial/reset searches retain their dense prefix (SPEC-2026-10-10-02,
 * SparseFilteredAggregateDiffTest). The shared scanner still fetches a kept successor before consuming payload. */
internal fun Interpreter.filteredRows(
    context: EvalContext,
    star: Expr.Star,
    traversal: FilterTraversal,
    keeps: ((EvalContext) -> Boolean)? = null,
): Sequence<EvalContext> {
    val group = star.path.substringBeforeLast('/')
    val rows = filterCandidates(context, group, star.bindDepth)
    val occupied = when (traversal) {
        FilterTraversal.DENSE -> null
        FilterTraversal.SPARSE -> (context as? FilterWalkRowProvider)?.sparseFilterPrefixes(group, star.bindDepth, filterWalkGroups)
    }
    val having = requireNotNull(star.having)
    if (occupied == null) return keptRowsWithSuccessorLookahead(rows, having, keeps)
    // A walk widened by one wide sibling row then costs its occupied rows rather than its whole grid (IF413).
    if (rows is FilterWalkView && occupied is OccupiedPrefixes) return keptRowsWithSuccessorLookahead(rows, having, keeps,
        resume = { from, kept -> rows.nextUnskipped(from, rows.coordinatesAt(kept), occupied) })
    return keptRowsWithSuccessorLookahead(rows, having, keeps, eligible = { previous, row ->
        if (previous == null) true else {
            val coordinates = row.repsOf(group)
            val last = previous.repsOf(group)
            val changed = coordinates.indices.firstOrNull { coordinates[it] != last[it] } ?: -1
            changed < 0 || (if (changed == coordinates.lastIndex) coordinates else coordinates.take(changed + 1)) in occupied
        }
    })
}

/**
 * A starred operand's cells as a COMPUTATION reads them, for a consumer that decides on the stream and stops at its
 * deciding cell: rows in order, a filtered operand through the one-kept-successor lookahead with [keeps] as its filter,
 * and every cell pulled is a read that poisons when invalid ([filledOrPoison]). Yields the read cell, or null for an
 * empty one. Validation keeps each consumer's own expansion, whose malformed cells are unknown rather than poison.
 */
internal fun Interpreter.computeStarReads(
    star: Expr.Star,
    keeps: ((EvalContext) -> Boolean)?,
    traversal: FilterTraversal = FilterTraversal.DENSE,
): Sequence<ScanCell?> {
    val context = ctxOf(star)
    val cells = if (keeps == null) context.starScanCells(star.path, star.bindDepth)
    else filteredRows(context, star, traversal, keeps)
        .map { scanCell(star.path, it) }
    return cells.map { cell -> cell.takeIf { filledOrPoison(it) } }
}

internal fun Interpreter.firstFilledScan(f: Expr.Func): FirstFilledScan {
    val walk = FirstFilledWalk()
    for (operand in f.args) {
        when (val step = firstFilledOperand(operand, walk)) {
            FirstFilledStep.Continue -> Unit
            is FirstFilledStep.Stop -> return FirstFilledScan(
                step.value,
                Fill.of(walk.fillable, walk.fillable),
                walk.encounteredFilter,
            )
        }
    }

    // NO terminal value → the per-kind FirstValueCombiner identity: NUMBER gives the fillable 0; the
    // other kinds give NOT-GIVEN. A future value in any exhausted operand can displace that identity.
    return FirstFilledScan(firstFilledEmpty(f), Fill.of(true, true), walk.encounteredFilter)
}

/** Scan one A12 `entitySpec`. Groups expand to descendant fields without changing their authored slot;
 * semantic indexes and category projections distinguish an absent value (continue) from an unavailable value
 * (terminal Unknown). Under partial validation every cell the walk visits is asked of the coverage first, empty
 * or not, so a cell outside it before the first filled one leaves the result undecided, on every arm as on the
 * starred one (KF57's order-aware gate). This is an original owner-behaviour
 * reconstruction, differential-locked by `FirstFilledValueEntitySpecDiffTest` and
 * `PartialKeyedSelectionRelevanceDiffTest`. */
private fun Interpreter.firstFilledOperand(operand: Expr, walk: FirstFilledWalk): FirstFilledStep = when (operand) {
    is Expr.Field -> {
        val c = ctxOf(operand)
        if (c.declaredKind(operand.path) == null) {
            firstFilledContainedCells(c, if (cellRelevant == null) c.firstFilledContainedCells(operand.path)
                else c.containedFieldCells(operand.path, declaredRange = false), walk)
        } else if (outsideCoverage(operand.path, c.repsOf(operand.path))) {
            FirstFilledStep.Stop(Val.Unknown)
        } else if (!c.filled(operand.path)) {
            firstFilledEmpty(c, operand.path, walk)
        } else {
            FirstFilledStep.Stop(firstFilledSelection(c, operand.path, c.repsOf(operand.path), evalExpr(operand)))
        }
    }
    is Expr.Star -> {
        val c = ctxOf(operand)
        if (c.declaredKind(operand.path) == null) {
            firstFilledExpandedGroup(c.containedFields(operand.path), walk) { path ->
                Expr.Star(path, operand.having, operand.outer, expansionBindDepth(operand.path, operand.bindDepth))
            }
        } else {
            firstFilledStar(operand, walk)
        }
    }
    is Expr.EnumCategory -> {
        val c = ctxOf(operand)
        if (outsideCoverage(operand.path, c.repsOf(operand.path))) {
            FirstFilledStep.Stop(Val.Unknown)
        } else if (!c.filled(operand.path)) {
            firstFilledEmpty(c, operand.path, walk)
        } else {
            FirstFilledStep.Stop(evalExpr(operand))
        }
    }
    is Expr.SemanticIndex -> {
        val c = ctxOf(operand)
        if (c.declaredKind(operand.path) == null) {
            when (val selection = keyedGroupSelection(operand)) {
                is KeyedGroupSelection.Row -> firstFilledContainedCells(
                    selection.ctx,
                    if (cellRelevant == null) selection.ctx.firstFilledContainedCells(operand.path)
                    else selection.ctx.containedFieldCells(operand.path, declaredRange = false),
                    walk,
                )
                // every cell of a row no key selects is empty, so the walk passes it only if the coverage
                // reaches all of them
                is KeyedGroupSelection.Absent ->
                    if (absentGroupPresence(operand.path, selection.repetitions).relevance != GroupRelevance.FULL) {
                        FirstFilledStep.Stop(Val.Unknown)
                    } else {
                        walk.fillable = true
                        FirstFilledStep.Continue
                    }
                KeyedGroupSelection.Unavailable -> FirstFilledStep.Stop(Val.Unknown)
            }
        } else {
            val cell = computeGuardedIndexCell(operand, selection = DateBoundary.FIRST_DAY)
            if (cell == null) {
                walk.fillable = true
                FirstFilledStep.Continue
            } else {
                FirstFilledStep.Stop(cell)
            }
        }
    }
    is Expr.EntitySpec -> firstFilledEntitySpec(operand, walk)
    else -> FirstFilledStep.Stop(evalExpr(operand))
}

private fun Interpreter.firstFilledEntitySpec(
    operand: Expr.EntitySpec,
    walk: FirstFilledWalk,
): FirstFilledStep {
    val category = operand.category
    val step = when (val source = operand.source) {
        is Expr.Field -> operand.semanticIndex?.let { firstFilledOperand(source.semanticIndex(it), walk) }
            ?: firstFilledOperand(source, walk)
        is Expr.Star -> {
            if (operand.semanticIndex != null) {
                val step = withIndexedStar(operand, FirstFilledStep.Stop(Val.Unknown)) {
                    if (category == null) firstFilledOperand(it, walk) else firstFilledStar(it, walk, category)
                }
                if (step == FirstFilledStep.Continue) walk.fillable = true
                return step
            }
            return firstFilledStar(source, walk, category)
        }
    }
    if (step !is FirstFilledStep.Stop || category == null) return step
    val source = operand.source
    return FirstFilledStep.Stop(ctxOf(source).enumCategoryValue(source.path, category, step.value))
}

/** Consume the identity-preserving descendant-cell stream supplied by [EvalContext]. This is the shared
 * implicit-wildcard mechanism used by every bare-group scan: repeatable levels below the authored group are
 * enumerated by the document context, rather than reconstructed as scalar AST paths. */
private fun Interpreter.firstFilledContainedCells(
    source: EvalContext,
    cells: Sequence<ScanCell>,
    walk: FirstFilledWalk,
): FirstFilledStep {
    var visited = false
    for (cell in cells) {
        visited = true
        if (outsideCoverage(cell.path, cell.reps)) return FirstFilledStep.Stop(Val.Unknown)
        if (computeMode) {
            if (!filledOrPoison(cell)) {
                walk.fillable = true
                continue
            }
        } else {
            when (cell.baseState) {
                CellState.EMPTY -> {
                    walk.fillable = true
                    continue
                }
                CellState.INVALID -> return FirstFilledStep.Stop(Val.Unknown)
                CellState.FILLED -> Unit
            }
        }
        return FirstFilledStep.Stop(firstFilledSelection(source, cell.path, cell.reps, cell.value ?: Val.Unknown))
    }
    if (!visited) walk.fillable = true
    return FirstFilledStep.Continue
}

private inline fun Interpreter.firstFilledExpandedGroup(
    fields: List<String>,
    walk: FirstFilledWalk,
    operand: (String) -> Expr,
): FirstFilledStep {
    if (fields.isEmpty()) walk.fillable = true
    for (field in fields) {
        val step = firstFilledOperand(operand(field), walk)
        if (step is FirstFilledStep.Stop) return step
    }
    return FirstFilledStep.Continue
}

private fun Interpreter.firstFilledStar(
    operand: Expr.Star,
    walk: FirstFilledWalk,
    category: String? = null,
): FirstFilledStep {
    val having = operand.having
    if (having != null) {
        walk.encounteredFilter = true
        walk.fillable = true
    }

    // An index column's empty/duplicate status is column-wide in the kernel cache. Keep the existing
    // conservative gate, but reach it only when this operand itself is reached.
    val operandContext = ctxOf(operand)
    if (having == null && indexOperandNonEvaluable(operandContext, operand.path, operand.bindDepth)) {
        return FirstFilledStep.Stop(Val.Unknown)
    }

    val group = operand.path.substringBeforeLast('/')
    // Partial validation visits every DECLARED slot in order, a missing one reading empty, so a slot outside the
    // relevant set stops the walk even where no row is stored (SPEC-2026-10-06-03,
    // `adapter.laws.PartialFillWalkRelevanceDiffTest`). Whether a filtered walk reaches missing slots is IG165.
    val kept = when {
        having != null -> keptRowsWithSuccessorLookahead(filterCandidates(operandContext, group, operand.bindDepth), having)
        cellRelevant != null -> operandContext.declaredStarRows(operand.path, operand.bindDepth)
        else -> operandContext.firstFilledRows(group, operand.bindDepth)
    }
    var visitedRow = false
    for (row in kept) {
        visitedRow = true
        val reps = row.repsOf(operand.path)
        if (cellRelevant?.invoke(operand.path, reps) == false) {
            return FirstFilledStep.Stop(Val.Unknown)
        }
        if (computeMode) {
            if (!filledOrPoison(scanCell(operand.path, row))) {
                walk.fillable = true
                continue
            }
        } else if (!row.filled(operand.path)) {
            val step = firstFilledEmpty(row, operand.path, walk)
            if (step is FirstFilledStep.Stop) return step
            continue
        }
        val selected = firstFilledSelection(row, operand.path, reps, row.field(operand.path))
        return FirstFilledStep.Stop(
            category?.let { row.enumCategoryValue(operand.path, it, selected) } ?: selected,
        )
    }
    if (!visitedRow) walk.fillable = true
    return FirstFilledStep.Continue
}

/** An empty cell the walk passes over, unless it is not check-relevant (a required-empty field): A12 decides the
 *  walk there as unavailable rather than reading past it (KERNEL-FINDINGS KF552). */
private fun Interpreter.firstFilledEmpty(c: EvalContext, path: String, walk: FirstFilledWalk): FirstFilledStep =
    if (invalid(c, path)) FirstFilledStep.Stop(Val.Unknown)
    else {
        walk.fillable = true
        FirstFilledStep.Continue
    }

private fun Interpreter.firstFilledValue(f: Expr.Func): Val = firstFilledScan(f).value

/** The value `FirstFilledValue` takes from the filled cell it stops at: a partial Date a value read leaves
 *  unknown is selected as its earliest day ([EvalContext.partialDateBoundaryAt]). */
private fun firstFilledSelection(c: EvalContext, path: String, repetitions: List<Int>, resolved: Val): Val =
    if (resolved is Val.Unknown) c.partialDateBoundaryAt(path, repetitions) ?: resolved else resolved

/** The empty-selection identity of a `FirstFilledValue` by its operand kind (the kernel's per-kind
 *  `FirstValueCombiner`): NUMBER → the fillable `0`; every other kind → NOT-GIVEN ([Val.Unknown], the
 *  comparison does not fire / a computation clears). IF83. */
private fun Interpreter.firstFilledEmpty(f: Expr.Func): Val =
    if (firstFilledKind(f) == Kind.NUMBER) Val.Num(DEC_ZERO) else Val.Unknown

/** The declared kind of a `FirstFilledValue` entity, including expanded groups and projections. */
private fun Interpreter.firstFilledKind(f: Expr.Func): Kind? = f.args.firstNotNullOfOrNull { a ->
    when (a) {
        is Expr.Star -> firstFilledEntityKind(ctxOf(a), a.path)
        is Expr.Field -> firstFilledEntityKind(ctxOf(a), a.path)
        is Expr.SemanticIndex -> firstFilledEntityKind(ctxOf(a), a.path)
        is Expr.EnumCategory -> Kind.STRING
        is Expr.EntitySpec -> if (a.category != null) Kind.STRING
            else firstFilledEntityKind(ctxOf(a.source), a.source.path)
        else -> null
    }
}

internal fun firstFilledEntityKind(c: EvalContext, path: String): Kind? =
    c.declaredKind(path) ?: c.containedFields(path).firstNotNullOfOrNull(c::declaredKind)

/** DATE-like kinds ride the kernel's date machinery (`VkDate`) — min/max over them dispatch to the
 *  `DateCombiner` family, not the number combiner ([dateAggregate]). */
internal fun dateLike(k: Kind?): Boolean = k == Kind.DATE || k == Kind.TIME || k == Kind.DATE_TIME

/**
 * Whether a min/max aggregate's operands are DATE-like — the kernel dispatches these statically to its
 * date combiners (`minWertDatum`/`maxWertDatum` for the star form, `minDatum`/`maxDatum` for the operand
 * list), whose empty identity is a NULL date (no value → [Val.Unknown], the comparison does not fire) —
 * never the number combiner's `0`. Operand types are homogeneous by authoring (the MVK comparison type
 * checks), so any date-like operand decides for the whole aggregate (`DateAggregateDiffTest`).
 */
private fun Interpreter.dateAggregate(f: Expr.Func): Boolean = f.args.any { a ->
    when (a) {
        // A GROUP operand holds no kind of its own, so it resolves through the same fallback its file-mate
        // [firstFilledEntityKind] already uses: the kind of the first declared field below it (the operands are
        // homogeneous by authoring, so one field decides). Without it a group of DATE fields answered `false`
        // here and took the NUMERIC branch, which cost two things at once — [minMaxEmpty] handed back `0` where
        // the date branch hands back "no value", and the comparison against a real date then THREW
        // `incomparable values Num(0) vs Txt(…)` rather than answering wrongly; and [producesTemporal] routes
        // through this same predicate, so a counterpart string literal stopped parsing as a German date
        // (`MinValue(<date group>) < "01.07.2026"` fired on both kernel strategies and returned false here).
        // Measured against both kernel strategies in `DateGroupExtremaDiffTest`; the kernel admits the shape
        // ([KF187]). IF287's class, and the fifth time the site disagreed with a sibling that was already right.
        is Expr.Field -> dateLike(firstFilledEntityKind(ctxOf(a), a.path))
        is Expr.Star -> dateLike(firstFilledEntityKind(ctxOf(a), a.path))
        is Expr.SemanticIndex -> dateLike(ctx.declaredKind(a.path))
        else -> producesTemporal(a)
    }
}

/** The min/max empty-fold identity by operand kind: dates → no value; numbers → `0` (see [dateAggregate]). */
private fun Interpreter.minMaxEmpty(f: Expr.Func): Val = if (dateAggregate(f)) Val.Unknown else Val.Num(DEC_ZERO)

/**
 * Numeric operand-list Min/Max values. Date Min/Max use [aggregateTemporal], which preserves reason and basis.
 *
 * **A GROUP operand is REFUSED here rather than expanded, and the refusal has a measured kernel basis:** the
 * operand-list `Min`/`Max` draw `MVK_NO_GROUPS_ALLOWED` for a group operand in both repetition shapes, while their
 * `MinValue`/`MaxValue` siblings accept one ([KF187]). So there is no correct expansion semantics to implement —
 * the shape has no meaning to match — and widening this site "on symmetry" with `aggOperandVals` would invent one.
 *
 * It is refused **as a DEGRADE** rather than left to fail on its own, because a kernel refusal only bounds what a
 * *stored, validated* model can contain: `Expr` is public API, this module's parser accepts the shape in both the
 * bracketed and the bare spelling, and `dmtool rule eval --condition` evaluates an arbitrary condition with no MVK
 * pre-flight at all (the native binary has no kernel to pre-flight with). Measured before this guard existed, both
 * spellings escaped as `IllegalStateException: no kind declared`, which the degrade net does NOT catch — so one
 * refused shape typed into `rule eval` took the WHOLE evaluation down instead of failing its own rule.
 * `UnsupportedConstructException` is the one type that net catches. (`KernelRefusedShapeReachabilityTest`.)
 */
private fun Interpreter.minMaxOperandVals(f: Expr.Func): List<Val> = f.args.map(::evalExpr)

/**
 * The operand-list `Min`/`Max` REFUSE a group operand, and the refusal sits at the DISPATCH rather than inside the
 * numeric arm — which is the correction a cold review forced.
 *
 * **The measured basis** is that these two carriers draw `MVK_NO_GROUPS_ALLOWED` for a group operand in both
 * repetition shapes, while their `MinValue`/`MaxValue` siblings accept one ([KF187]). So there is no correct
 * expansion semantics to implement here — the shape has no meaning to match — and widening the site "on symmetry"
 * with `aggOperandVals` would invent one. It is refused as a DEGRADE rather than left to fail on its own because a
 * kernel refusal only bounds what a *stored, validated* model can contain: `Expr` is public API, this module's
 * parser accepts the shape in both the bracketed and the bare spelling, and `dmtool rule eval --condition`
 * evaluates an arbitrary condition with no MVK pre-flight at all.
 *
 * **Why the position matters, and it is the slice's own convergence lesson.** The guard first lived inside
 * [minMaxOperandVals], which the dispatch reaches only on its NUMERIC branch. The same session's fix that made
 * [dateAggregate] resolve a GROUP's kind through its contained fields therefore routed a group of DATE fields past
 * the guard into [evalTemporal], where reading a value at the group path threw `no kind declared` — the very crash
 * class this refusal exists to close, re-opened by a sibling fix in the same slice, and invisible to the original
 * lock because its fixture group held two NUMBER fields. (`KernelRefusedShapeReachabilityTest` now carries both
 * kinds in both spellings.)
 */
private fun Interpreter.refuseGroupOperandUnderMinMax(f: Expr.Func) {
    // The path comes from `EntitySource`, NOT from `pathOf`: `pathOf` rejects an `Expr.Star`, so the STARRED
    // spelling used to throw "unsupported operand Star in a field-reference position" over this corrective —
    // still an `UnsupportedConstructException`, so it degraded and the type-only reachability lock stayed green,
    // but the author was told about an internal operand position instead of which operator to reach for. The
    // kernel refuses both spellings identically (`MVK_NO_GROUPS_ALLOWED`, [KF187]), so both are equally
    // reachable through the channels that carry no MVK pre-flight.
    // (`MinMaxGroupRefusalDiagnosticTest`, whose unstarred row is the control that this was starred-only.)
    val group = f.args.firstOrNull { namesAGroup(it) } as? Expr.EntitySource ?: return
    throw UnsupportedConstructException(
        "${f.name} does not accept a group operand (${group.path}) — the kernel refuses it with " +
            "MVK_NO_GROUPS_ALLOWED; use MinValue/MaxValue for a group scope, or name the fields explicitly",
    )
}

/**
 * The FILLED values feeding a value aggregate (`Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`),
 * collected across ALL operands — the single-star form AND the kernel's explicit MULTI-FIELD list (IF51).
 * A starred field expands over its repetition rows (a `Having` filters them); a plain field contributes
 * its value only when filled, so an empty operand is DROPPED (as the star drops empty cells) — `MinValue(A, B)`
 * with `B` empty ignores `B`, it never treats it as 0. A filled-but-malformed operand resolves to [Val.Unknown]
 * and [combine] then suppresses the whole aggregate.
 */
private fun Interpreter.starVals(f: Expr.Func, traversal: FilterTraversal = FilterTraversal.DENSE): List<Val> =
    f.args.flatMap { aggOperandVals(it, traversal) }

private fun Interpreter.aggOperandVals(arg: Expr, traversal: FilterTraversal = FilterTraversal.DENSE): List<Val> = when (arg) {
    is Expr.EntitySpec -> withIndexedStar(arg, listOf(Val.Unknown)) { starOperandVals(it, traversal) }
    is Expr.Star -> starOperandVals(arg, traversal)
    // A keyed GROUP contributes the selected row's cells as the bare group arm below contributes its instance's
    // (IF340); a keyed FIELD its one cell, dropped when empty like a plain field's.
    is Expr.SemanticIndex -> if (isKeyedGroup(arg)) {
        when (val cells = keyedGroupCells(arg)) {
            is KeyedGroupCells.Selected -> cells.interpreter.aggOperandVals(Expr.Field(arg.path), traversal)
            KeyedGroupCells.Empty -> emptyList()
            KeyedGroupCells.Undecided -> listOf(Val.Unknown)
        }
    } else {
        listOfNotNull(computeGuardedIndexCell(arg))
    }
    else -> {
        val path = pathOf(arg)
        // A GROUP operand contributes its contained fields, exactly as [starOperandVals] does for the starred
        // form. Without this the `filled` test below decides it: a group holds no value of its own, so
        // `ctx.filled` is false for every group path, the operand was DROPPED, and the aggregate folded to its
        // empty identity — a silent `Sum` of 0 that fires nothing. Measured against both kernel strategies:
        // `Sum(/Order/Billing) > 5` over 3 + 4 fires on the kernel and returned false here (IF287's class, sixth
        // instance; the kernel's admission of the shape is asserted in the lock so a rejection cannot pass as
        // agreement). The unstarred spelling is the only legal one for a non-repeatable group.
        if (ctxOf(arg).declaredKind(path) == null) {
            // Through the shared owner, so the depth formula is not re-derived here. It used to be inlined, which
            // is how the same quantity came to be spelled three different ways across five resolvers (IF287).
            expandOneGroupOperand(arg).flatMap { aggOperandVals(it, traversal) }
        } else if (ctxOf(arg).filled(path)) listOf(evalExpr(arg)) else emptyList()   // plain field: empty dropped
    }
}

/** A starred aggregate operand's filled values — its group's row cells (empties dropped, like [EvalContext.star]);
 *  an optional `Having` keeps only the matching rows through the shared one-kept-successor lookahead
 *  ([keptRowsWithSuccessorLookahead], SPEC-2026-07-23-11) — the aggregate still consumes EVERY clean kept
 *  target (no first-value stop). COMPUTE mode: a value aggregate reads ALL its selected cells (the kernel
 *  feeds every row to the combiner), so any formally-INVALID selected cell poisons (IF85) — the filter's own
 *  reads poison inside [havingInterp], and a filter poison met while locating the next kept candidate
 *  precedes the pending current target. */
private fun Interpreter.starOperandVals(star: Expr.Star, traversal: FilterTraversal = FilterTraversal.DENSE): List<Val> {
    // A GROUP star (`Sum(Lines*)` — no field segment) expands to the group's contained FIELDS, recursively
    // (the kernel ExpandService → getAllContainedFields, exactly like the fill quantifiers — IF49/IF51),
    // each field's cells collected with the same star semantics (bindDepth + Having preserved; IF99).
    if (ctxOf(star).declaredKind(star.path) == null) {
        val contained = ctxOf(star).containedFields(star.path)
        if (contained.isNotEmpty()) {
            val depth = expansionBindDepth(star.path, star.bindDepth)
            return contained.flatMap { starOperandVals(Expr.Star(it, star.having, star.outer, depth), traversal) }
        }
    }
    val having = star.having
    if (having == null) {
        if (computeMode && ctx.starScanStates(star.path, star.bindDepth).any { it == CellState.INVALID }) {
            throw PoisonedReadException(star.path)
        }
        // An index field's not-check-relevant cell (mandatory-empty / duplicated value) makes the whole
        // aggregate non-evaluable — the kernel's `combineFeldListe` returns NICHT_PRUEF_REL on the first such
        // cell, so the referencing rule is suppressed (IF15/IF60 generalized to aggregates).
        if (indexOperandNonEvaluable(ctx, star.path, star.bindDepth)) return listOf(Val.Unknown)
        if (starHasUnavailableEmpty(ctx, star)) return listOf(Val.Unknown)
        return ctx.star(star.path, star.bindDepth)
    }
    val vals = mutableListOf<Val>()
    for (rc in filteredRows(ctx, star, traversal)) {
        if (computeMode && rc.cellState(star.path) == CellState.INVALID) throw PoisonedReadException(star.path)
        if (rc.filled(star.path)) vals += rc.field(star.path)
        else if (unavailableEmpty(rc, star.path)) return listOf(Val.Unknown)
    }
    return vals
}

/**
 * Apply a numeric combiner ([Min]/[Max]/[Sum]/[MaxValue]/[MinValue]/[NumberOfDifferentValues]) to its
 * value list. A [Val.DomainFailure] wins and propagates: the kernel's `VkBigDecimal.min`/`max` preserve
 * `INVALID_NUMBER`, independent of operand order. Otherwise, if ANY element is malformed (resolved to
 * [Val.Unknown]), the whole combiner is non-evaluable and yields [Val.Unknown]. Empties never reach a
 * starred aggregate (the star drops absent/empty cells); only a filled-but-malformed value is [Val.Unknown].
 */
private inline fun combine(vals: List<Val>, op: (List<Val>) -> Val): Val =
    when {
        vals.any { it is Val.DomainFailure } -> Val.DomainFailure
        vals.any { it is Val.Unknown } -> Val.Unknown
        else -> op(vals)
    }

/** Apply a unary numeric function while preserving the kernel's invalid-number sentinel. */
private inline fun mapNumber(value: Val, op: (Dec) -> Val): Val = when (value) {
    is Val.Num -> op(value.v)
    else -> value.numericNonValue()
}

/** A numeric consumer's non-value projection: preserve domain failure; all other wrong/non-values are Unknown. */
private fun Val.numericNonValue(): Val = if (this is Val.DomainFailure) this else Val.Unknown

private fun num(v: Val): Dec = (v as? Val.Num)?.v ?: error("expected a number, got $v")
