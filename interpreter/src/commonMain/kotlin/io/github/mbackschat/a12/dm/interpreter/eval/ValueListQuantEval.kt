package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

// The value-list-quantifier collaborator (shape B, `<fields> AtLeastOne/No/NotAll In <values>`) — truth AND
// polarity, sliced vertically because both halves share the per-operand [vlqOperands] expansion and nothing
// else consumes it. An extension family on the shared eval-state seam ([Interpreter], IF117): entry points
// are internal (the truth walk and the typed walks dispatch here), the expansion machinery is file-private.
//
// ONE evaluator, THREE pass structures (SPEC-2026-07-23-16 — the kernel's `RuntimeController` gives each
// operator its own traversal over the same two operand lists, so a side is NEVER flattened into one global
// UNKNOWN/filter summary): `AtLeastOne` consumes the complete values side then scans fields to the deciding
// match; `No` materializes values poison-sensitively then runs an ORDERED fields scan a match terminates;
// `NotAll` runs a fields-PRESENCE prepass, consumes values only when a present field exists, then restarts
// the fields scan to the outside witness. Filter (`Having`) polarity is witness-specific per those passes,
// not one side-wide flag.

/**
 * One authored operand of a field-list × value-list quantifier side, expanded to its per-cell classes in
 * encounter order — [cells] holds `null` for an EMPTY cell (an empty plain field / an empty star cell; the
 * kernel's `einWertDerWerteListeNichtAngegeben` / `isAuslassungsFehler` flag sources), [Val.Unknown] for a
 * malformed, not-check-relevant cell, and the typed value for a present cell. A star expands to one cell per
 * instantiated (or filter-kept, IG48/IF74) row; a literal member is a single present cell (never
 * empty/unknown).
 *
 * [filtered] marks a `Having`-bearing operand — its polarity effect is decided by the consuming pass
 * (witness-specific / reached-specific), never side-wide. [omittedTail] is an unfiltered star's
 * un-instantiated declared tail. [extentUnknown] is the SEPARATE partial-validation fact: a starred entry's
 * COMPLETE extent is established only by wildcard coverage of its repeatable level(s) — concretely
 * enumerating every current row does NOT (SPEC-2026-07-22-09); never set in full validation.
 */
private class VlqOperand(val filtered: Boolean) {
    private val collected = mutableListOf<Val?>()
    private var reads: Sequence<Val?>? = null
    var omittedTail = false
    var extentUnknown = false

    /** The cells in encounter order: collected eagerly in validation, a lazy read stream in computation
     *  ([readLazily]), where pulling a cell is the read that can poison. */
    val cells: Sequence<Val?> get() = reads ?: collected.asSequence()

    fun add(cell: Val?) {
        collected += cell
    }

    fun readLazily(cells: Sequence<Val?>): VlqOperand = apply { reads = cells }

    /** Reads every remaining cell now, for a side the operator consumes completely before deciding. */
    fun drain(): VlqOperand = apply {
        reads?.let { collected += it }
        reads = null
    }

    fun anyPresent(): Boolean = cells.any { it != null && it !is Val.Unknown }
}

// A GROUP operand is expanded to its contained fields FIRST, in either repetition shape, through the shared
// [expandGroupOperands] owner. Without it neither arm below could read a cell: the `Star` arm calls
// `starCells` at the starred path, of which a group path has none, and the `Field` arm takes `presenceTri`,
// FALSE for a group — so the quantifier ran over an EMPTY selection and still returned a well-formed verdict.
// Measured against both kernel strategies, which ADMIT the shape ([KF187]): on a document holding a listed
// value, `AtLeastOne` returned false and `No` returned true — the opposite-direction signature of an empty
// selection rather than of a wrong verdict ([IF287]'s class; `GroupOperandValueListQuantifierDiffTest`).
private fun Interpreter.vlqOperands(entries: List<Expr>): List<VlqOperand> = expandGroupOperands(entries).map { e ->
    if (e.isIndexedStar()) return@map withIndexedStar(e as Expr.EntitySpec,
        VlqOperand(filtered = false).also { it.add(Val.Unknown) }) {
            vlqOperands(listOf(it)).single()
        }
    when (e) {
        is Expr.Star -> {
            val op = VlqOperand(filtered = e.having != null)
            val c = ctxOf(e)
            // Computation reads the operand as a stream its consuming pass pulls only to the deciding cell, so a
            // malformed cell or filter beyond it is never read and cannot poison (SPEC-2026-10-03-04). The partial
            // gates below are null there, and an index cell the formal pass refuses is an invalid read.
            if (computeMode) {
                if (e.having == null && c.starOmission(e.path, e.bindDepth)) op.omittedTail = true
                val having = e.having
                val keeps = having?.let { filter -> { row: EvalContext -> havingInterp(row).keeps(filter) } }
                return@map op.readLazily(computeStarReads(e, keeps).map { it?.value })
            }
            // Partial validation: this starred entry's COMPLETE extent is established by WILDCARD coverage of
            // its repeatable level(s) (or a relevant ancestor); enumerating concrete rows, even all of them,
            // does NOT (SPEC-2026-07-22-09). It is its OWN seam, not the all-rows aggregates' [starRelevant]:
            // that one is universal over the reduced identifier set, this one is existential, and the two
            // kernel-diverge on a wildcard beside concrete siblings ([IF264]).
            // Null in full validation → never set → the full path stays byte-identical.
            if (starExtentKnown?.invoke(e.path, e.bindDepth, c.repsOf(e.path)) == false) op.extentUnknown = true
            // …and, independently of the extent, a NON-RELEVANT cell is not in the kernel's evaluation cache at
            // all, so it contributes nothing to the scan ([IF267], measured on both strategies: with the only
            // matching cell non-relevant, the kernel's `AtLeastOne` stays silent). The extent gate answers
            // "do I know every row?"; this answers "may I read THIS row?" — a starred entry needs both.
            // A starred INDEX field's formal errors are field-LOCAL and make the CELL not check-relevant
            // → UNKNOWN: an empty cell is mandatory-empty (the kernel auto-requires an index field per
            // row), and EVERY cell of a duplicated value carries the uniqueIndex error (over ALL rows, not
            // just filter-kept ones). A clean sibling field in the same row reads normally — the row is not
            // collectively compromised. `ValueListQuantifierStarDiffTest.compromisedIndexRows`.
            val indexColumn = if (e.path in indexFields) indexColumnOf(c, e.path, e.bindDepth) else null
            val dupIndexValues = indexColumn?.duplicated
            if (e.having == null) {
                val cells = indexColumn?.cells ?: c.starCells(e.path, e.bindDepth)
                for ((reps, v) in cells) {
                    if (cellRelevant?.invoke(e.path, reps) == false) continue   // [IF267]
                    op.classify(v, dupIndexValues)
                }
                if (c.starOmission(e.path, e.bindDepth)) op.omittedTail = true   // un-instantiated declared tail
            } else {
                // A `Having` filter (IG48): classify ONLY rows the filter keeps (keep iff it fires TRUE — the
                // IF72 keep-law; a malformed filter operand → UNKNOWN → drop), the same per-cell classes over
                // the kept subset. A dropped row contributes nothing; the un-instantiated tail is unreachable
                // (no data to filter). The operand's polarity effect is the consuming pass's call ([filtered]).
                val group = e.path.substringBeforeLast('/')
                val indexValuesByRepetitions = indexColumn?.cells?.toMap()
                for (rc in c.rowContexts(group, e.bindDepth)) {
                    val repetitions = rc.repsOf(e.path)
                    if (cellRelevant?.invoke(e.path, repetitions) == false) continue   // [IF267]
                    if (!havingInterp(rc).keeps(e.having)) continue
                    val value = if (indexValuesByRepetitions != null) {
                        indexValuesByRepetitions[repetitions]
                    } else if (rc.filled(e.path)) {
                        rc.field(e.path)
                    } else {
                        null
                    }
                    op.classify(value, dupIndexValues)
                }
            }
            op
        }
        is Expr.Field, is Expr.SemanticIndex, is Expr.EnumCategory -> VlqOperand(filtered = false).also { op ->
            if (computeMode) op.readLazily(sequence { yield(valueListCell(e)) }) else op.add(valueListCell(e))
        }
        else -> VlqOperand(filtered = false).also { it.add(evalExpr(e)) }   // a literal member (never empty/unknown)
    }
}

/**
 * One field-valued value-list operand's cell: null when empty, [Val.Unknown] when present but not check-relevant,
 * otherwise its value; any other operand is a literal member and is its value. In computation the presence read of
 * an invalid cell poisons instead of answering unknown.
 *
 * An enum CATEGORY projection (`[field -> Cat]`) is a per-cell operand, not a literal: an empty enum is an EMPTY cell
 * (it drives OMISSION for `No`, not a present unknown — both kernel strategies), an out-of-domain or malformed enum is
 * unknown, and a filled enum contributes its (many-to-one) category value. Its presence is the underlying enum field's
 * three-way presence ([filledTri] on [Expr.EnumCategory.path], since [pathOf] rejects a category node)
 * (SPEC-2026-07-22-05; the kernel's `keinFeldWertInWerteListe` empty-cell omission).
 */
internal fun Interpreter.valueListCell(e: Expr): Val? = when (e) {
    is Expr.Field, is Expr.SemanticIndex -> when (presenceTri(e)) {
        Tri.TRUE -> evalExpr(e)
        Tri.FALSE -> null
        Tri.UNKNOWN -> Val.Unknown
    }
    is Expr.EnumCategory -> if (outerUnknown(e)) Val.Unknown else when (filledTri(ctxOf(e), e.path)) {
        Tri.TRUE -> evalExpr(e)
        Tri.FALSE -> null
        Tri.UNKNOWN -> Val.Unknown
    }
    else -> evalExpr(e)
}

/** Classify one star cell by its resolved value (null = empty, [Val.Unknown] = malformed, an index-field
 *  duplicated value = field-local UNKNOWN, an empty INDEX cell = mandatory-empty UNKNOWN) into the operand's
 *  encounter-ordered [VlqOperand.cells] — shared by the unfiltered + filtered paths. */
private fun VlqOperand.classify(v: Val?, dupIndexValues: Set<Val>?) {
    add(when {
        v == null -> if (dupIndexValues != null) Val.Unknown else null
        v is Val.Unknown -> Val.Unknown
        dupIndexValues != null && v in dupIndexValues -> Val.Unknown
        else -> v
    })
}

/** The eagerly-materialized values side of `No`/`NotAll` (the kernel's `fuegeFeldWerteZuWerteListe`):
 *  the present [members] plus the sticky [omission] potential — an empty member, an omitted declared tail,
 *  or a REACHED values-side filter (regardless of what it kept, the S5 asymmetry to `AtLeastOne`'s
 *  present-selection rule). Null = poisoned: an UNKNOWN member or an extent-unknown star suppresses. */
private class VlqValues(val members: List<Val>, val omission: Boolean)

private fun vlqValuesOf(operands: List<VlqOperand>): VlqValues? {
    var omission = false
    val members = mutableListOf<Val>()
    for (op in operands) {
        if (op.filtered) omission = true
        if (op.extentUnknown) return null
        if (op.omittedTail) omission = true
        for (cell in op.cells) when {
            cell == null -> omission = true
            cell is Val.Unknown -> return null
            else -> members += cell
        }
    }
    return VlqValues(members, omission)
}

/**
 * The field-list value-list quantifiers (shape B) — kernel-exact per the 30.8.1 runtime
 * (`mindestensEinFeldWertInWerteListe` / `keinFeldWertInWerteListe` / `nichtAlleFeldWerteInWerteListe`,
 * shared by both strategies; `ValueListQuantifierStarDiffTest`, `ValueListQuantifierOrderDiffTest`). Both
 * sides expand per OPERAND, per cell, in encounter order ([vlqOperands]) — including the ENTITY value list
 * (`f In` the flatten of `/Group*` then `x`, IF50) and STARRED fields entries; equality is the typed
 * [ValCompare.equal] (numbers scale-19-insensitively — the kernel's `VkBigDecimal.equals` rescales too).
 * The malformed (UNKNOWN) handling is OP-ASYMMETRIC, never a blanket suppression, and each operator owns
 * its pass structure (SPEC-2026-07-23-16):
 *
 *  - `AtLeastOne`: the COMPLETE values side first (empty/malformed member cells SKIPPED outright; an empty
 *    value SET → no fire), then fields in authored order to the first present match. A fire is OMISSION iff
 *    the DECIDING fields operand is filtered or a filtered values operand selected a PRESENT member —
 *    an earlier filtered nonwitness or a filtered empty selection never escalates.
 *  - `No`: values materialized poison-sensitively (an UNKNOWN member → Unknown, no fire; empty member /
 *    omitted tail / reached filter → sticky omission), then an ORDERED fields scan: a MATCH terminates
 *    non-firing before every later cell (a later UNKNOWN/filter/tail is never reached), a reached UNKNOWN
 *    before the match suppresses. Fires — including when nothing is filled at all — as OMISSION iff any
 *    reached empty field / omitted tail / filter or the values side's omission potential occurred.
 *  - `NotAll`: a fields-PRESENCE prepass first (no present cell → no fire WITHOUT consuming the values
 *    side — an UNKNOWN values member cannot poison it), then values (No-style), then a RESTARTED fields
 *    scan to the first present cell outside the set (malformed fields cells skipped). The fire is OMISSION
 *    iff the outside-WITNESS operand is filtered or the values side carries omission potential — it has NO
 *    fields-side empty/tail account (the `No`-vs-`NotAll` asymmetry).
 *
 * Partial validation adds a side-specific EXTENT gate ([VlqOperand.extentUnknown], SPEC-2026-07-22-09): a
 * starred entry known only by concrete-row enumeration (not wildcard coverage) suppresses `No` (either
 * side, at its position in the scan) and `NotAll` on the VALUES side to UNKNOWN, while `AtLeastOne` (either
 * side) and `NotAll` on the fields side ignore it.
 *
 * Validation expands each side eagerly, since its reads cannot poison; only the VERDICT is the operator's ordered
 * pass. Computation instead reads each operand as a stream that pass pulls, so its read set is the pass's own
 * (SPEC-2026-10-03-04): every operator consumes the values side completely, then scans the fields only to the
 * deciding cell, and a filtered list reads one kept successor ahead. A malformed cell the pass reaches poisons; one
 * beyond the decision is never read. The side-consumption laziness is the kernel's: `AtLeastOne`/`No` never touch
 * fields when the values side already decided, `NotAll` never touches values when its presence prepass decided.
 */
internal fun Interpreter.evalValueListQuant(c: Cond.ValueListQuant): Tri = vlqRes(c).truth

/** Truth + fired polarity in ONE pass — [Res.type] is meaningful only when [Res.truth] is TRUE (the typed
 *  walks read it only then; a non-firing result carries the VALUE placeholder). */
internal fun Interpreter.vlqRes(c: Cond.ValueListQuant): Res = when (c.op) {
    ValueListQuantOp.AT_LEAST_ONE -> atLeastOneRes(c)
    ValueListQuantOp.NO -> noRes(c)
    ValueListQuantOp.NOT_ALL -> notAllRes(c)
}

private fun Interpreter.atLeastOneRes(c: Cond.ValueListQuant): Res {
    val values = vlqOperands(c.values).onEach { it.drain() }
    val members = values.flatMap { op -> op.cells.mapNotNull { cell -> cell.takeUnless { it is Val.Unknown } } }
    if (members.isEmpty()) return Res.of(Tri.FALSE, MsgType.VALUE)
    // A values-side filter keeps its polarity only when that operand SELECTED a present member
    // (the kernel's `fuegePruefungsrelevanteFeldWerteZuWerteListe` filter flag rides the present add).
    val filteredSelection = values.any { it.filtered && it.anyPresent() }
    for (op in vlqOperands(c.fields)) {
        for (cell in op.cells) {
            if (cell == null || cell is Val.Unknown) continue   // empty/malformed fields cells are skipped
            if (members.any { ValCompare.equal(cell, it) }) {
                // The DECIDING operand's filter — not an earlier filtered nonwitness — sets the polarity.
                return Res.of(Tri.TRUE, if (op.filtered || filteredSelection) MsgType.OMISSION else MsgType.VALUE)
            }
        }
    }
    return Res.of(Tri.FALSE, MsgType.VALUE)
}

private fun Interpreter.noRes(c: Cond.ValueListQuant): Res {
    val values = vlqValuesOf(vlqOperands(c.values)) ?: return Res.of(Tri.UNKNOWN, MsgType.VALUE)
    var omission = values.omission
    for (op in vlqOperands(c.fields)) {
        // The fields-side extent gate sits AT the operand's scan position: an earlier match already
        // decided non-firing before an extent-unknown (or malformed) later operand is reached.
        if (op.extentUnknown) return Res.of(Tri.UNKNOWN, MsgType.VALUE)
        if (op.omittedTail || op.filtered) omission = true   // reached tail/filter → sticky omission
        for (cell in op.cells) when {
            cell == null -> omission = true                  // reached empty cell → sticky omission
            cell is Val.Unknown -> return Res.of(Tri.UNKNOWN, MsgType.VALUE)
            values.members.any { ValCompare.equal(cell, it) } ->
                return Res.of(Tri.FALSE, MsgType.VALUE)      // a match decides non-firing, the scan ends
        }
    }
    return Res.of(Tri.TRUE, if (omission) MsgType.OMISSION else MsgType.VALUE)
}

private fun Interpreter.notAllRes(c: Cond.ValueListQuant): Res {
    val fields = vlqOperands(c.fields)
    // The presence prepass (`noEntityFilled`): nothing present → no fire, values NEVER consumed.
    if (fields.none { it.anyPresent() }) return Res.of(Tri.FALSE, MsgType.VALUE)
    val values = vlqValuesOf(vlqOperands(c.values)) ?: return Res.of(Tri.UNKNOWN, MsgType.VALUE)
    // The RESTARTED fields scan — no extent gate, no empty/tail account on this side
    // (SPEC-2026-07-22-09 / the No-vs-NotAll asymmetry): the first present cell outside the set wins.
    for (op in fields) {
        for (cell in op.cells) {
            if (cell == null || cell is Val.Unknown) continue
            if (values.members.none { ValCompare.equal(cell, it) }) {
                // The outside-WITNESS operand's filter — or the values side — sets the polarity.
                return Res.of(Tri.TRUE, if (op.filtered || values.omission) MsgType.OMISSION else MsgType.VALUE)
            }
        }
    }
    return Res.of(Tri.FALSE, MsgType.VALUE)
}
