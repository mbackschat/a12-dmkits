package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.ast.Cond
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.ValueListQuantOp
import io.github.mbackschat.a12.dm.interpreter.model.MsgType

// The value-list-quantifier collaborator (shape B, `<fields> AtLeastOne/No/NotAll In <values>`) — truth AND
// polarity, sliced vertically because both halves share the per-cell [vlqSide] expansion and nothing else
// consumes it. An extension family on the shared eval-state seam ([Interpreter], IF117): entry points are
// internal (the truth walk and the typed walks dispatch here), the expansion machinery is file-private.

/**
 * One side of a field-list × value-list quantifier, expanded to per-cell classes: the [present] cells'
 * typed values, whether an empty cell was seen ([anyEmpty] — an empty plain field / an empty star cell /
 * a starred entry's un-instantiated declared tail; the kernel's `einWertDerWerteListeNichtAngegeben` /
 * `isAuslassungsFehler` flag sources), whether a malformed, not-check-relevant cell was seen
 * ([anyUnknown]), and whether any operand carried a `Having` filter ([hasHaving] — the kernel escalates a
 * fired result to TRUE_AF/OMISSION on a filtered operand, IG48). A star expands to one cell per
 * instantiated row.
 *
 * [extentUnknown] is a SEPARATE partial-validation fact from the per-cell classes above: a starred entry's
 * COMPLETE extent is established only by wildcard coverage of its repeatable level(s) — concretely enumerating
 * every current row does NOT (SPEC-2026-07-22-09). It drives the side-specific UNKNOWN table below and is never
 * set in full validation.
 */
private class VlqSide {
    val present = mutableListOf<Val>()
    var anyEmpty = false
    var anyUnknown = false
    var hasHaving = false
    var extentUnknown = false
}

private fun Interpreter.vlqSide(entries: List<Expr>): VlqSide {
    val out = VlqSide()
    for (e in entries) when (e) {
        is Expr.Star -> {
            val c = ctxOf(e)
            // Partial validation: this starred entry's COMPLETE extent is established only by WILDCARD coverage
            // of its repeatable level(s) (or a relevant ancestor) — the [starRelevant] seam shared with all-rows
            // aggregates (IF42); enumerating concrete rows, even all of them, does NOT (SPEC-2026-07-22-09).
            // Null in full validation → never set → the full path stays byte-identical.
            if (starRelevant?.invoke(e.path) == false) out.extentUnknown = true
            // A starred INDEX field's formal errors are field-LOCAL and make the CELL not check-relevant
            // → UNKNOWN: an empty cell is mandatory-empty (the kernel auto-requires an index field per
            // row), and EVERY cell of a duplicated value carries the uniqueIndex error (over ALL rows, not
            // just filter-kept ones). A clean sibling field in the same row reads normally — the row is not
            // collectively compromised. `ValueListQuantifierStarDiffTest.compromisedIndexRows`.
            val dupIndexValues = if (e.path in indexFields) c.indexColumn(e.path, e.bindDepth).duplicated else null
            if (e.having == null) {
                for ((_, v) in c.starCells(e.path, e.bindDepth)) classifyVlqCell(out, v, dupIndexValues)
                if (c.starOmission(e.path, e.bindDepth)) out.anyEmpty = true   // un-instantiated declared tail counts as empty
            } else {
                // A `Having` filter (IG48): classify ONLY rows the filter keeps (keep iff it fires TRUE — the
                // IF72 keep-law; a malformed filter operand → UNKNOWN → drop), the same per-cell classes over
                // the kept subset. A dropped row contributes nothing; the un-instantiated tail is unreachable
                // (no data to filter), and a Having-bearing operand forces OMISSION on fire ([VlqSide.hasHaving]).
                out.hasHaving = true
                val group = e.path.substringBeforeLast('/')
                for (rc in c.rowContexts(group, e.bindDepth)) {
                    if (!havingInterp(rc).fires(e.having)) continue
                    classifyVlqCell(out, if (rc.filled(e.path)) rc.field(e.path) else null, dupIndexValues)
                }
            }
        }
        is Expr.Field, is Expr.SemanticIndex -> when (presenceTri(e)) {
            Tri.TRUE -> out.present += evalExpr(e)
            Tri.FALSE -> out.anyEmpty = true
            Tri.UNKNOWN -> out.anyUnknown = true
        }
        // An enum CATEGORY projection ([field -> Cat]) is a per-cell operand, NOT a literal: an empty enum
        // is an EMPTY cell (drives OMISSION for `No`, not a present Unknown — both kernel strategies), an
        // out-of-domain/malformed enum is UNKNOWN, and a filled enum contributes its (many-to-one) category
        // value. Presence is the UNDERLYING enum field's three-way presence ([filledTri] on [Expr.EnumCategory.path]
        // — [pathOf] rejects a category node). (SPEC-2026-07-22-05; the kernel's `keinFeldWertInWerteListe` empty-cell auslassung.)
        is Expr.EnumCategory -> when {
            outerUnknown(e) -> out.anyUnknown = true
            else -> when (filledTri(ctxOf(e), e.path)) {
                Tri.TRUE -> when (val v = evalExpr(e)) {
                    is Val.Unknown -> out.anyUnknown = true
                    else -> out.present += v
                }
                Tri.FALSE -> out.anyEmpty = true
                Tri.UNKNOWN -> out.anyUnknown = true
            }
        }
        else -> out.present += evalExpr(e)   // a literal member (never empty/unknown)
    }
    return out
}

/** Classify one star cell of a VLQ side by its resolved value (null = empty, [Val.Unknown] = malformed, an
 *  index-field duplicated value = field-local UNKNOWN) into [out] — shared by the unfiltered + filtered paths. */
private fun classifyVlqCell(out: VlqSide, v: Val?, dupIndexValues: Set<Val>?) {
    when {
        v == null -> if (dupIndexValues != null) out.anyUnknown = true else out.anyEmpty = true
        v is Val.Unknown -> out.anyUnknown = true
        dupIndexValues != null && v in dupIndexValues -> out.anyUnknown = true
        else -> out.present += v
    }
}

/**
 * The field-list value-list quantifiers (shape B) — kernel-exact per the 30.8.1 runtime
 * (`mindestensEinFeldWertInWerteListe` / `keinFeldWertInWerteListe` / `nichtAlleFeldWerteInWerteListe`,
 * shared by both strategies; `ValueListQuantifierStarDiffTest`). Both sides expand per-cell ([vlqSide]) —
 * this includes the ENTITY value list (`f In` the flatten of `/Group*` then `x`, IF50) and STARRED fields
 * entries; equality is the
 * typed [ValCompare.equal] (numbers scale-19-insensitively — the kernel's `VkBigDecimal.equals` rescales too). The
 * malformed (UNKNOWN) handling is OP-ASYMMETRIC, never a blanket suppression:
 *
 *  - `AtLeastOne`: empty/malformed cells and members are SKIPPED outright; an empty value SET → no fire;
 *    fires iff some present cell ∈ values.
 *  - `No`: a malformed FIELDS cell or VALUES member POISONS (→ Unknown, no fire); fires iff no present
 *    cell ∈ values — including when nothing is filled at all.
 *  - `NotAll`: no filled FIELDS cell → no fire (`noEntityFilled`); a malformed VALUES member poisons, a
 *    malformed FIELDS cell is skipped; fires iff some present cell ∉ values.
 *
 * Partial validation adds a side-specific EXTENT gate ([VlqSide.extentUnknown], SPEC-2026-07-22-09): a starred
 * entry known only by concrete-row enumeration (not wildcard coverage) suppresses `No` on EITHER side and
 * `NotAll` on the VALUES side to UNKNOWN, while `AtLeastOne` (either side) and `NotAll` on the fields side
 * ignore it.
 */
internal fun Interpreter.evalValueListQuant(c: Cond.ValueListQuant): Tri = when (c.op) {
    ValueListQuantOp.AT_LEAST_ONE -> {
        val values = vlqSide(c.values)
        if (values.present.isEmpty()) Tri.FALSE
        else Tri.of(vlqSide(c.fields).present.any { p -> values.present.any { ValCompare.equal(p, it) } })
    }
    ValueListQuantOp.NO -> {
        val values = vlqSide(c.values)
        // No is suppressed to UNKNOWN by an extent-unknown star on EITHER side (SPEC-2026-07-22-09).
        if (values.anyUnknown || values.extentUnknown) Tri.UNKNOWN
        else {
            val fields = vlqSide(c.fields)
            if (fields.anyUnknown || fields.extentUnknown) Tri.UNKNOWN
            else Tri.of(fields.present.none { p -> values.present.any { ValCompare.equal(p, it) } })
        }
    }
    ValueListQuantOp.NOT_ALL -> {
        val fields = vlqSide(c.fields)
        if (fields.present.isEmpty()) Tri.FALSE
        else {
            val values = vlqSide(c.values)
            // NotAll is suppressed by an extent-unknown star on the VALUES side only; it IGNORES the fields
            // side's extent (SPEC-2026-07-22-09) — a concretely-enumerated fields star still fires here.
            if (values.anyUnknown || values.extentUnknown) Tri.UNKNOWN
            else Tri.of(fields.present.any { p -> values.present.none { ValCompare.equal(p, it) } })
        }
    }
}

/** Fired value-list-quantifier polarity (kernel TRUE_AF vs TRUE_WF): a `Having` filter on ANY operand
 *  escalates a fired result to OMISSION (TRUE_AF), unconditionally (IG48) — else `AtLeastOne` fires on a
 *  PRESENT matching value → VALUE (empties are irrelevant to it); a fired `No` is OMISSION on any empty
 *  FIELDS cell / un-instantiated tail OR an empty VALUES member; a fired `NotAll` is OMISSION only on the
 *  VALUES side's account — it has no fields-tail flag (the No-vs-NotAll asymmetry,
 *  `ValueListQuantifierStarDiffTest` / `ValueListQuantifierHavingDiffTest`). */
internal fun Interpreter.valueListQuantType(c: Cond.ValueListQuant): MsgType {
    val fields = vlqSide(c.fields)
    val values = vlqSide(c.values)
    if (fields.hasHaving || values.hasHaving) return MsgType.OMISSION
    return when (c.op) {
        ValueListQuantOp.AT_LEAST_ONE -> MsgType.VALUE
        ValueListQuantOp.NO -> if (fields.anyEmpty || values.anyEmpty) MsgType.OMISSION else MsgType.VALUE
        ValueListQuantOp.NOT_ALL -> if (values.anyEmpty) MsgType.OMISSION else MsgType.VALUE
    }
}
