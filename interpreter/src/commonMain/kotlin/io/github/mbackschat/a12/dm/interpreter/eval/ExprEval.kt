package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.RoundMode
import io.github.mbackschat.a12.dm.interpreter.UnsupportedConstructException
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.decOrNull
import io.github.mbackschat.a12.dm.interpreter.ast.ArithOp
import io.github.mbackschat.a12.dm.interpreter.ast.Expr
import io.github.mbackschat.a12.dm.interpreter.ast.Tri
import io.github.mbackschat.a12.dm.interpreter.ast.Val
import io.github.mbackschat.a12.dm.interpreter.ast.emptyValue
import io.github.mbackschat.a12.dm.interpreter.model.Kind

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
 *  ([producesDate]). The date-time/range constructors return other kinds; the extractors return numbers. */
private val DATE_PRODUCING_FUNCS = setOf(
    FunctionOp.DATE, FunctionOp.DATE_FROM_DATE_TIME,
    FunctionOp.START_OF_DATE_RANGE, FunctionOp.END_OF_DATE_RANGE,
    FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS,
    FunctionOp.VALUE_AS_DATE,
)

/** The min/max family — DATE-valued when its operands are dates ([dateAggregate]/[producesDate]). */
private val MIN_MAX_FUNCS = setOf(FunctionOp.MIN, FunctionOp.MAX, FunctionOp.MIN_VALUE, FunctionOp.MAX_VALUE)

/** `RangeAsNumber` parses its substring ONLY when it is a run of digits (kernel `\d+`); else the value is 0. */
private val DIGITS = Regex("""\d+""")

/** Whether a SemanticIndex resolves to a present (filled) cell — vs an absent row / empty cell (or an
 *  empty/absent key field for the field-keyed form, which also yields no match). */
internal fun Interpreter.semanticIndexFilled(e: Expr.SemanticIndex): Boolean = semanticIndexCell(e) != null

/**
 * Whether a starred INDEX-field operand carries ANY NOT-CHECK-RELEVANT cell — the kernel's field-local index
 * errors (IF15/IF60, the VLQ [classifyVlqCell] mechanism generalized to the aggregate/count path): a
 * mandatory-EMPTY (or absent) cell of the auto-required index field, and EVERY cell of a DUPLICATED value
 * (`uniqueIndex`). A malformed cell is already not-check-relevant via [resolveValue] → [Val.Unknown], so it is
 * NOT re-counted here. The kernel's shared aggregate loop `combineFeldListe` (Sum/Min/Max/NumberOfDifferentValues)
 * and `anzahlAngegebenerFelder` (NumberOfFilledFields) return the NOT-CHECK-RELEVANT value on the FIRST such
 * cell, so the whole aggregate is non-evaluable and the referencing rule is suppressed (`IndexAggregateDiffTest`).
 * False for a non-index field or a clean index column.
 */
private fun Interpreter.indexOperandNonEvaluable(c: EvalContext, path: String, bindDepth: Int = -1): Boolean =
    path in indexFields && c.indexColumn(path, bindDepth).anyEmptyOrDuplicated

/**
 * `NumberOfFilledGroups(...)`'s count — the STARRED single-repeatable-group form `NumberOfFilledGroups(
 * /Group*)` (the `RootGroupCount` DSL node / `Dsl.numberOfFilledGroups(repeatableGroup)`) counts the group's
 * **instantiated repetitions** (a created-but-empty row counts too — kernel-probed, IF46); the plain
 * multi-group form counts admitted groups only when every operand group is fully relevant and error-free; any
 * unavailable group makes the count Unknown rather than a partial count. Linear: O(rows) for the starred form
 * (one row-context list, already scoped + cached), O(args) for the plain form.
 */
private fun Interpreter.filledGroupsValue(args: List<Expr>): Val {
    val star = args.singleOrNull() as? Expr.Star
    if (star != null) return Val.Num(dec(ctxOf(star).rowContexts(star.path, star.bindDepth)
        .count { groupPresence(it, star.path).definitelyFilled }))
    val states = args.map { groupPresence(ctxOf(it), pathOf(it)) }
    if (states.any { it.erroneous || it.relevance != GroupRelevance.FULL }) return Val.Unknown
    return Val.Num(dec(states.count { it.definitelyFilled }))
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
 */
private fun Interpreter.filledFieldsValue(args: List<Expr>): Val {
    var count = 0
    for (arg in args) when (arg) {
        is Expr.Star -> {
            if (arg.having != null) {
                // The count HONORS a Having filter (IF94 — the kernel's `anzahlAngegebenerFelder` rides the
                // same filtered iterator as every field-list aggregate; ignoring the filter over-counted).
                // The filter drops rows FIRST — a malformed cell in a dropped row never suppresses (kernel-
                // pinned) — then a KEPT row's malformed cell makes the count non-evaluable (poison in
                // compute). Left index-unaware like the other Having paths (IF86's no-untested-logic call).
                val group = arg.path.substringBeforeLast('/')
                for (rc in ctx.rowContexts(group, arg.bindDepth)) {
                    if (!havingInterp(rc).fires(arg.having)) continue
                    if (!rc.filled(arg.path)) continue
                    if (rc.field(arg.path) is Val.Unknown) {
                        if (computeMode) throw PoisonedReadException(arg.path)
                        return Val.Unknown
                    }
                    count++
                }
            } else {
                val cells = ctx.star(arg.path, arg.bindDepth)   // filled cells in row order (empties dropped)
                if (cells.any { it is Val.Unknown }) {          // a malformed cell → non-evaluable
                    if (computeMode) throw PoisonedReadException(arg.path)   // …and a POISON on the compute path (IF85)
                    return Val.Unknown
                }
                // An index field's not-check-relevant cell (mandatory-empty / duplicated value) makes the count
                // non-evaluable too — `anzahlAngegebenerFelder` returns NICHT_PRUEF_REL on the first UNKNOWN cell.
                if (indexOperandNonEvaluable(ctx, arg.path, arg.bindDepth)) return Val.Unknown
                count += cells.size
            }
        }
        else -> {
            val path = pathOf(arg)
            if (ctx.declaredKind(path) == null) {              // GROUP operand — its descendant fields
                val fs = ctx.fillScope(path, isGroup = true)
                if (fs.hasUnknownInst) {                       // a malformed descendant → non-evaluable
                    if (computeMode) throw PoisonedReadException(path)
                    return Val.Unknown
                }
                count += fs.tInst
            } else {                                           // plain field
                if (ctx.filled(path) && invalid(path)) return Val.Unknown   // malformed → non-evaluable
                if (ctx.filled(path)) count++
            }
        }
    }
    return Val.Num(dec(count))
}

internal fun Interpreter.evalExpr(e: Expr): Val = when (e) {
    is Expr.NumLit -> e.asVal
    is Expr.StrLit -> e.asVal
    is Expr.BoolLit -> e.asVal
    // COMPUTE mode: a value read of a formally-invalid cell poisons (IF85) — row-correct via the resolving
    // context's own cellState (a `Having` filter row reads ITS row's cell, where the parent-row [invalid]
    // gate cannot judge it).
    is Expr.Field -> when {
        outerUnknown(e) -> Val.Unknown
        computeMode && ctxOf(e).cellState(e.path) == CellState.INVALID -> throw PoisonedReadException(e.path)
        invalid(e.path) -> Val.Unknown
        else -> ctxOf(e).field(e.path)
    }
    is Expr.Star -> throw UnsupportedConstructException("a starred path must sit inside an aggregate (Sum/MaxValue/…)")
    is Expr.Func -> evalFunc(e)
    is Expr.Arith -> evalArith(e)
    is Expr.Const -> evalConst(e.name)
    is Expr.SemanticIndex -> evalSemanticIndex(e)
    is Expr.EnumCategory -> if (outerUnknown(e)) Val.Unknown else ctxOf(e).enumCategory(e.path, e.category)
}

/** `[field For "value"]` — the field's value in the row whose index field equals the value; an absent row
 *  or empty cell reads as the kind's EMPTY value (a number → 0, fillable), matching a missing field ref.
 *  Compute-mode reads go through the poison gates of [computeGuardedIndexCell]. */
private fun Interpreter.evalSemanticIndex(e: Expr.SemanticIndex): Val {
    val kind = ctx.declaredKind(e.path) ?: return Val.Unknown
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
 * read), exactly like the validation read. VALIDATION mode differs from the compute whole-column marking
 * (IF115, kernel-pinned): a match on a CLEAN key cell resolves the read no matter what the OTHER cells
 * carry (the matched target's own malformed-ness stays [Val.Unknown] = not-check-relevant); a match on a
 * DUPLICATED or malformed key cell, and a NO-match over a column carrying ANY not-check-relevant cell —
 * malformed (a maybe-match), EMPTY (auto-required), or duplicated — are unresolvable → [Val.Unknown], so
 * a direct comparison is suppressed and the concat gate ([concatOperandInvalid]) suppresses a
 * concatenation (the kernel's `np` flag riding `VkString.add` into `isStringNotRelevant`).
 */
private fun Interpreter.computeGuardedIndexCell(e: Expr.SemanticIndex): Val? {
    if (computeMode) {
        if (indexColumnInvalid(e)) throw PoisonedReadException(e.path)
        val cell = semanticIndexCell(e)
        if (cell is Val.Unknown) throw PoisonedReadException(e.path)
        return cell
    }
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    val key = resolveIndexKey(e) ?: return null                      // empty/absent key → not-given
    val matchedKey = ctx.indexedCell(idx, idx, key)                  // the index-field self-lookup
    if (matchedKey != null) {
        // matched on a malformed (raw-equal) or DUPLICATED key cell → not-check-relevant
        if (matchedKey is Val.Unknown || matchedKey in ctx.indexColumn(idx).duplicated) return Val.Unknown
        return ctx.indexedCell(e.path, idx, key)
    }
    if (indexColumnInvalid(e)) return Val.Unknown                    // unresolvable no-match
    return null                                                      // clean no-match → not-given
}

/** Whether the index COLUMN of `[F For key]` carries ANY invalid cell — malformed, duplicated, or empty
 *  (the IndexFieldCache marking, IF107): every read through the index is then poisoned (compute) or
 *  not-check-relevant (validation, IF115). O(1) per lookup via [EvalContext.indexColumn]. */
private fun Interpreter.indexColumnInvalid(e: Expr.SemanticIndex): Boolean {
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException(
        "SemanticIndex needs an index field on the group of ${e.path}")
    return ctx.indexColumn(idx).anyNotCheckRelevant
}

/** The matched CELL of `[F For key]`, or null when nothing matches — an empty/absent key field, no row
 *  carrying the key, or a matched row whose cell is empty. A matched-but-malformed cell is [Val.Unknown]. */
internal fun Interpreter.semanticIndexCell(e: Expr.SemanticIndex): Val? {
    val idx = indexFieldOf(e.path) ?: throw UnsupportedConstructException("SemanticIndex needs an index field on the group of ${e.path}")
    val key = resolveIndexKey(e) ?: return null
    return ctx.indexedCell(e.path, idx, key)
}

/**
 * The 3VL presence of `[F For key]` — the kernel resolves the indexed row and answers `isWertAngegeben` on
 * the ONE matched cell: matched + check-relevant filled → TRUE; an ABSENT row, an empty matched cell, or an
 * empty/no-match key → FALSE (an absent row is judged like an empty cell, NOT skipped — kernel
 * `ValidationCache.resolveSemanticIndex` → `isEntityFilled`); a malformed matched cell, or a formally
 * invalid KEY field, → UNKNOWN (not check-relevant). `SemanticIndexPresenceDiffTest`.
 */
internal fun Interpreter.semanticIndexPresence(e: Expr.SemanticIndex): Tri {
    e.indexFieldPath?.let { if (invalid(it)) return Tri.UNKNOWN }
    val cell = semanticIndexCell(e) ?: return Tri.FALSE
    return if (cell is Val.Unknown) Tri.UNKNOWN else Tri.TRUE
}

/** The lookup key of a [Expr.SemanticIndex]: the string literal, or the CURRENT value of the key field
 *  ([Expr.SemanticIndex.indexFieldPath]) for the field-keyed `[F For keyField]` form — null when that key
 *  field is empty/absent (→ no row matches → the empty value, same as a non-matching literal). */
private fun Interpreter.resolveIndexKey(e: Expr.SemanticIndex): String? =
    e.indexValue ?: e.indexFieldPath?.let { ctx.displayValue(it)?.takeIf { v -> v.isNotEmpty() } }

/** The index-field path of [fieldPath]'s repeatable group (its sibling index field), or null if none. */
private fun Interpreter.indexFieldOf(fieldPath: String): String? {
    val group = fieldPath.substringBeforeLast('/')
    return indexFields.firstOrNull { it.substringBeforeLast('/') == group }
}

/**
 * A no-operand constant. `Today` resolves to the injected clock's current date and `Now` to its current
 * date-time (ISO text — values comparing chronologically, like any date / date-time field). Without the
 * clock (or, for `Now`, without [EvalClock.now]) the constant cannot be resolved → reported as unsupported
 * (tolerated by `validate`), not crashed.
 */
private fun Interpreter.evalConst(name: String): Val = when (ConstantOp.byName(name)) {
    ConstantOp.TODAY -> Val.Txt(clock?.today
        ?: throw UnsupportedConstructException("Today needs an evaluation clock — construct DmInterpreter with an EvalClock"))
    ConstantOp.NOW -> Val.Txt(nowTemporalValue().text)
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
    self is Expr.StrLit && producesDate(other) -> coerceDate(Val.Txt(self.v))
    else -> evalExpr(self)
}

/** Whether [e] statically yields a DATE value — the signal that a counterpart string LITERAL is a date
 *  literal (German `dd.MM.yyyy`), not a plain string. A DATE field, the date constants (`Today`/`BaseYear`),
 *  a date-producing function, or a min/max whose operands are dates ([dateAggregate] — the kernel types
 *  `Min`/`MinValue` polymorphically by their operands, so a literal beside a date min is a date literal). */
private fun Interpreter.producesDate(e: Expr): Boolean = when (e) {
    is Expr.Field -> ctx.declaredKind(e.path) == Kind.DATE
    is Expr.Const -> ConstantOp.byName(e.name).let { it == ConstantOp.TODAY || it == ConstantOp.BASE_YEAR }
    is Expr.Func -> FunctionOp.byName(e.name).let { op ->
        op in DATE_PRODUCING_FUNCS || (op in MIN_MAX_FUNCS && dateAggregate(e))
    }
    is Expr.SemanticIndex -> ctx.declaredKind(e.path) == Kind.DATE
    else -> false
}

/** Canonicalize a German date literal to ISO `yyyy-MM-dd`; any other value (already-ISO, a non-date string)
 *  is unchanged — so it only ever rewrites a literal that genuinely parses as a date. Two literal shapes: the
 *  full `dd.MM.yyyy`, and the **omitted-year** `dd.MM.` (a reference-year feature — the kernel injects the
 *  model's `baseYear`, so `"13.07."` means `13.07.<baseYear>`; a model with NO base year rejects it at
 *  validation, `MVK_INVALID_COMPARE_TO_DATE`, so we only ever see it when `baseYear` is set — IF52). */
private fun Interpreter.coerceDate(v: Val): Val {
    if (v !is Val.Txt) return v
    parseDateToIso(v.v, "dd.MM.yyyy")?.let { return Val.Txt(it) }
    if (baseYear != null && OMITTED_YEAR.matches(v.v)) {
        parseDateToIso(v.v + baseYear.toString().padStart(4, '0'), "dd.MM.yyyy")?.let { return Val.Txt(it) }
    }
    return v
}

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
    val e = exp.display().toIntOrNull() ?: return Val.Unknown
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
    is Expr.Field -> ctxOf(e).filled(e.path) && invalid(e.path)
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

private fun Interpreter.evalFunc(f: Expr.Func): Val {
    val op = FunctionOp.byName(f.name)
    // Partial validation only: an ALL-ROWS starred aggregate (Sum/Max/Min/distinct/sum-of-products) is
    // UNKNOWN unless the relevant set WILDCARDS its starred level — concrete per-row enumeration, even of all
    // rows, is not enough (kernel-probed, IF42). `FirstFilled` is EXEMPT here (handled order-aware in its
    // branch). Full validation: starRelevant is null → skipped. O(relevant-set), no per-cell scan.
    if (starRelevant != null && op != FunctionOp.FIRST_FILLED_VALUE) {
        f.args.forEach { arg ->
            val sp = (arg as? Expr.Star)?.path
            if (sp != null && !starRelevant.invoke(sp)) return Val.Unknown
        }
    }
    return when (op) {
    // Value aggregates — the FOLDS are pure runtime semantics ([Aggregates], IG5→IF10); the strategy only
    // RESOLVES operands (operand-list `map { evalExpr }` vs the starred `starVals`) and gates Unknown
    // ([combine]). The same fold serves both forms (`Min`/`MinValue` share [Aggregates.min]). Min/max over
    // DATE-like operands ride the kernel's DateCombiner semantics ([dateAggregate]): the empty identity is
    // no-value (Unknown), not the number combiner's 0 (DateAggregateDiffTest).
    // operand-list forms: every operand counts (an empty number arrives as the substituted 0; a DATE-like
    // empty operand is SKIPPED — the kernel's `minDatum` combine keeps the date-bearing side)
    FunctionOp.MIN -> if (dateAggregate(f)) evalTemporal(f).asTemporalVal()
        else combine(minMaxOperandVals(f)) { Aggregates.min(it, minMaxEmpty(f)) }
    FunctionOp.MAX -> if (dateAggregate(f)) evalTemporal(f).asTemporalVal()
        else combine(minMaxOperandVals(f)) { Aggregates.max(it, minMaxEmpty(f)) }
    // starred aggregates: empties are SKIPPED (Sum substitutes 0 = same result; Max/distinct ignore)
    FunctionOp.SUM -> combine(starVals(f), Aggregates::sum)
    FunctionOp.MAX_VALUE -> combine(starVals(f)) { Aggregates.max(it, minMaxEmpty(f)) }
    FunctionOp.MIN_VALUE -> combine(starVals(f)) { Aggregates.min(it, minMaxEmpty(f)) }
    FunctionOp.NUMBER_OF_DIFFERENT_VALUES -> combine(starVals(f), Aggregates::numberOfDifferentValues)
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
    // DateRange(start, end): a date range from two date operands (computation-only — the kernel rejects it in
    // a condition). Rendered to the target field's format at the compute() boundary. Empty/malformed → Unknown.
    FunctionOp.DATE_RANGE -> {
        val s = (evalExpr(f.args[0]) as? Val.Txt)?.v
        val e = (evalExpr(f.args[1]) as? Val.Txt)?.v
        if (s == null || e == null) Val.Unknown else Val.Range(s, e)
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
    FunctionOp.START_OF_DATE_RANGE -> (evalExpr(f.args[0]) as? Val.Range)?.let { Val.Txt(it.start) } ?: Val.Unknown
    FunctionOp.END_OF_DATE_RANGE -> (evalExpr(f.args[0]) as? Val.Range)?.let { Val.Txt(it.end) } ?: Val.Unknown
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
    // An UNBOUND `$` group (an overlap-predicate Having, no enclosing iteration) → UNKNOWN (KERNEL-FINDINGS §6).
    FunctionOp.CURRENT_REPETITION ->
        if (outerUnknown(f.args[0])) Val.Unknown else Val.Num(dec(ctxOf(f.args[0]).repsOf(pathOf(f.args[0])).last()))
    null -> throw UnsupportedConstructException("unsupported function ${f.name}")
    }
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
            placeValue.v.display().toIntOrNull() ?: return Val.Unknown
        }
    return Val.Num(value.v.roundTo(places, mode))
}

/**
 * `NumberOfValueInFields(value In f1, f2, …)` — `args[0]` is the constant, `args[1..]` the bare field refs.
 * The kernel (`RuntimeController.anzahlWertInFeldlisteIntern`) counts only **filled** fields whose value
 * equals the constant: an EMPTY field is skipped (not counted), and a formally-invalid (Unknown) field
 * makes the whole count non-evaluable → the rule is suppressed. Equality uses the same typed `==` semantics.
 */
private fun Interpreter.numberOfValueInFields(args: List<Expr>): Val {
    val target = evalExpr(args[0])
    var count = 0
    for (i in 1 until args.size) {
        val path = pathOf(args[i])
        if (!ctx.filled(path)) continue          // empty → not counted (kernel: b == FALSE)
        val v = evalExpr(args[i])
        if (v is Val.Unknown) return Val.Unknown // a filled-but-malformed operand → non-evaluable
        if (ValCompare.equal(v, target)) count++
    }
    return Val.Num(dec(count))
}

/**
 * `SumOfProducts(a*, b*)` — Σ aᵢ·bᵢ over two parallel field lists of the SAME repeatable group, paired
 * **row-by-row** (the kernel zips the two row iterators). An empty cell reads as `0` (so its product is 0);
 * any malformed cell makes the whole sum non-evaluable → the rule is suppressed (the formal mechanism). Both
 * stars resolve over the same group, so `starAll` yields equal-length, row-aligned lists.
 */
private fun Interpreter.sumOfProducts(args: List<Expr>): Val {
    rejectHaving(args, "SumOfProducts")
    if (computeMode) {
        for (a in args) {
            val st = a as Expr.Star
            if (ctx.starScanStates(st.path, st.bindDepth).any { it == CellState.INVALID }) throw PoisonedReadException(st.path)
        }
    }
    // Resolve the two row-aligned star lists here (ctx-coupled); the Σ aᵢ·bᵢ fold is pure ([Aggregates], IF10).
    return Aggregates.sumOfProducts(
        (args[0] as Expr.Star).let { ctx.starAll(it.path, it.bindDepth) },
        (args[1] as Expr.Star).let { ctx.starAll(it.path, it.bindDepth) })
}

/**
 * Degrade LOUD (IF61) if any operand is a starred rep-list carrying a `Having` this consumer does not
 * evaluate — the kernel AUTHORING-REJECTS a filter on a `SumOfProducts` operand (`MVK_WILDCARD_AT_LOWEST_
 * LEVEL_REQUIRED`: the lowest repetition must be an unrestricted `*`), so on a valid model this never fires
 * (parity-by-construction); a filter that somehow reached the interpreter must be REPORTED (the rule is
 * reported unsupported), never SILENTLY ignored (which would compute the wrong answer). Contrast
 * `FieldValuesNotUnique`, which the kernel ACCEPTS + EVALUATES — it filters via [filteredStarCells], not here.
 */
private fun rejectHaving(args: List<Expr>, op: String) {
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
            computeMode && ctxOf(arg).cellState(path) == CellState.INVALID -> throw PoisonedReadException(path)
            invalid(path) -> Val.Unknown
            else -> ctx.displayValue(path)?.let { Val.Txt(it) } ?: Val.Unknown
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
    if (computeMode && ctxOf(source).cellState(path) == CellState.INVALID) throw PoisonedReadException(path)
    if (invalid(path)) return Val.Unknown
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
            val digit = ch.digitToIntOrNull() ?: return null
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
    if (invalid(path)) return null
    val value = ctx.displayValue(path) ?: ""
    val s = (evalExpr(args[1]) as Val.Num).v.display().toInt()
    val e = (evalExpr(args[2]) as Val.Num).v.display().toInt()
    return if (value.isEmpty() || e > value.length) "" else value.substring(s - 1, e)
}

/**
 * An operand that reads as the kernel's fillable EMPTY value (`nichtAngegeben` & check-relevant) for the
 * value functions that coerce an unspecified operand to **0** and FIRE — `Length` (`BigDecimalUtils.laenge`),
 * the date/time extractors (`getDatumsTeil`) and the date diffs (`getDatumsDiff`), unlike a direct comparison
 * on an empty field (which is suppressed). A plain field ref that is not filled and not formally invalid;
 * non-field operands (constants, constructions) are never empty here. KERNEL-FINDINGS §6, IF73.
 */
private fun Interpreter.emptyCheckRelevant(e: Expr): Boolean =
    e is Expr.Field && !ctxOf(e).filled(e.path) && !invalid(e.path)

/** A field operand that is formally invalid (a malformed value, or a required-but-empty field): the empty-as-0
 *  value functions stay NON-evaluable on it (`nichtPruefungsrelevant` → `NICHT_PRUEF_REL_ZAHL`, the comparison
 *  is suppressed) — malformed BEATS empty (checked first in `getDatumsDiff`). */
private fun Interpreter.malformedField(e: Expr): Boolean = e is Expr.Field && invalid(e.path)

/** `DifferenceIn{Months,Years}(a, b)` → a signed whole-period count; an empty operand reads as the fillable 0
 *  (either empty → 0, kernel `getDatumsDiff`); a malformed operand → Unknown (suppressed). */
private fun Interpreter.dateDiff(args: List<Expr>, op: FunctionOp): Val = temporalDifference(args) { a, b ->
    val legacy = a.basis == DateCalendarBasis.LEGACY_HYBRID || b.basis == DateCalendarBasis.LEGACY_HYBRID
    val result = when (op) {
        FunctionOp.DIFFERENCE_IN_MONTHS ->
            if (legacy) differenceInLegacyCalendarMonths(a.date, b.date) else differenceInMonths(a.date, b.date)
        FunctionOp.DIFFERENCE_IN_YEARS ->
            if (legacy) differenceInLegacyCalendarYears(a.date, b.date) else differenceInYears(a.date, b.date)
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
    val parts = calendarDateParts(value.date, value.basis) ?: return Val.Unknown
    return Val.Num(dec(pick(parts.first, parts.second, parts.third)))
}

/** The DATE part of a canonical datetime (`2024-06-25T05:21:07` → `2024-06-25`); a plain date unchanged.
 *  The kernel's date consumers parse a DATE_TIME value with its full format and use the date part (IF95);
 *  a garbage value stays garbage — the downstream date parse still rejects it. */
private fun dateOnly(v: String): String = v.substringBefore('T')

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

/** The status of a `Date(day, month, year)` construction — the basis for the constructor's value AND the
 *  `Valid`/`Invalid` verdicts (kernel-probed, KERNEL-FINDINGS §6). MALFORMED (a part is a non-evaluable
 *  Unknown) makes the whole non-evaluable; HAS_EMPTY (a part is an empty field) is fillable. */
internal enum class DateStatus { REAL, UNREAL_PRESENT, HAS_EMPTY, MALFORMED }

private data class DateAnalysis(val status: DateStatus, val iso: String?)

/** Classify a `Date(...)` construction's operands. Two arities: `Date(day, month, year)` and the 4-arg
 *  century form `Date(day, month, century, year)` whose full year is `century*100 + year` (kernel-probed,
 *  IF31). Precedence: any malformed part → MALFORMED; else any empty field part → HAS_EMPTY; else
 *  REAL/UNREAL_PRESENT by the calendar check. */
private fun Interpreter.analyzeDate(parts: List<Expr>): DateAnalysis {
    if (parts.size != 3 && parts.size != 4) return DateAnalysis(DateStatus.MALFORMED, null)
    var hasEmpty = false
    val nums = IntArray(parts.size)
    for (i in parts.indices) {
        val e = parts[i]
        if (e is Expr.Field && !ctx.filled(e.path)) { hasEmpty = true; continue }
        nums[i] = valToInt(evalExpr(e)) ?: return DateAnalysis(DateStatus.MALFORMED, null)
    }
    if (hasEmpty) return DateAnalysis(DateStatus.HAS_EMPTY, null)
    val d = nums[0]; val m = nums[1]
    // 4-arg century form splits the year into century + 2-digit year: full year = century*100 + year.
    val y = if (parts.size == 3) nums[2] else nums[2] * 100 + nums[3]
    val componentGate = d != 0 && m != 0 && if (parts.size == 3) y != 0 else nums[2] != 0
    return if (componentGate && isConstructibleDate(y, m, d, timeZone)) DateAnalysis(DateStatus.REAL, isoDate(y, m, d))
    else DateAnalysis(DateStatus.UNREAL_PRESENT, null)
}

/**
 * One evaluated temporal value, carrying the information the kernel's `VkDate` keeps but a plain ISO string
 * loses: calendar provenance, exact instant identity, and why a legal expression has no value.
 */
private sealed interface Evaluated<out T> {
    data class Present<T>(val value: T) : Evaluated<T>
    data class NoValue(val reason: NoValueReason) : Evaluated<Nothing>
}

private enum class NoValueReason {
    /** Empty or present-but-unreal: numeric temporal consumers receive their fillable default `0`. */
    NOT_GIVEN,

    /** Malformed/formally-invalid: the expression is not check-relevant and numeric consumers suppress. */
    NON_RELEVANT,
}

private data class TemporalValue(
    val text: String,
    val basis: DateCalendarBasis,
    val exactInstantMillis: Long? = null,
) {
    val date: String get() = dateOnly(text)
    val isDateTime: Boolean get() = 'T' in text

    /** A pure time-of-day (`HH:mm:ss`, no date) — it has no model-zone instant and folds by its decoded
     *  time coordinate, not a resolved instant (SPEC-2026-07-21-07). */
    val isTimeOnly: Boolean get() = TIME.matchEntire(text) != null

    fun instantMillis(timeZone: io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone): Long =
        exactInstantMillis ?: checkNotNull(calendarOperandInstant(text, timeZone, basis)) {
            "real temporal value has no model-zone instant: $text ($basis, $timeZone)"
        } * 1_000L

    fun instantSeconds(timeZone: io.github.mbackschat.a12.dm.interpreter.model.ModelTimeZone): Long =
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
    if (reason == NoValueReason.NOT_GIVEN) Val.Num(DEC_ZERO) else Val.Unknown

/** NON_RELEVANT dominates NOT_GIVEN, matching the runtime's not-check-relevant precedence. */
private fun dominantNoValue(vararg values: Evaluated<*>): Evaluated.NoValue? {
    val absent = values.filterIsInstance<Evaluated.NoValue>()
    return when {
        absent.any { it.reason == NoValueReason.NON_RELEVANT } ->
            Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        absent.isNotEmpty() -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
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
            FunctionOp.DATE_TIME -> composeDateTime(e.args)
            FunctionOp.DATE_FROM_DATE_TIME -> mapTemporal(e.args.single()) { value ->
                value.copy(text = value.date, exactInstantMillis = null)
            }
            FunctionOp.ADD_DAYS, FunctionOp.ADD_MONTHS, FunctionOp.ADD_YEARS,
            FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS -> shiftTemporal(e)
            FunctionOp.MIN, FunctionOp.MAX -> aggregateTemporal(e)
            else -> temporalLeaf(e)
        }
    }
    return temporalLeaf(e)
}

private fun Interpreter.evaluateDateConstruction(args: List<Expr>): Evaluated<TemporalValue> {
    val analysis = analyzeDate(args)
    return when (analysis.status) {
        DateStatus.REAL -> Evaluated.Present(
            TemporalValue(analysis.iso!!, DateCalendarBasis.LEGACY_HYBRID),
        )
        DateStatus.UNREAL_PRESENT, DateStatus.HAS_EMPTY -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        DateStatus.MALFORMED -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
}

private fun Interpreter.temporalLeaf(e: Expr): Evaluated<TemporalValue> {
    if (e is Expr.Const) {
        when (ConstantOp.byName(e.name)) {
            ConstantOp.NOW -> return Evaluated.Present(nowTemporalValue())
            ConstantOp.BASE_YEAR -> {
                val text = (baseYearDate() as Val.Txt).v
                return temporalText(text, DateCalendarBasis.PROLEPTIC_GREGORIAN)
            }
            else -> Unit
        }
    }
    val evaluated = coerceDate(evalExpr(e))
    if (evaluated is Val.Txt) {
        val basis = DateCalendarBasis.PROLEPTIC_GREGORIAN
        // A pure time-of-day has no date and therefore no model-zone instant; it carries only its decoded
        // time coordinate (the operand-list Min/Max over TIME folds on that, SPEC-2026-07-21-07).
        if (TIME.matchEntire(evaluated.v) != null) return Evaluated.Present(TemporalValue(evaluated.v, basis))
        val instant = calendarOperandInstant(evaluated.v, timeZone, basis)
            ?: return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        return Evaluated.Present(TemporalValue(evaluated.v, basis, instant * 1_000L))
    }
    return when {
        malformedField(e) -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        emptyCheckRelevant(e) -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
}

private fun Interpreter.nowTemporalValue(): TemporalValue {
    val raw = clock?.now
        ?: throw UnsupportedConstructException(
            "Now needs an evaluation clock with a current date-time — construct DmInterpreter with an EvalClock(today, now)",
        )
    val match = CLOCK_DATE_TIME.matchEntire(raw)
        ?: throw UnsupportedConstructException("EvalClock.now must be yyyy-MM-dd'T'HH:mm:ss or yyyy-MM-dd'T'HH:mm:ss.SSS")
    val text = match.groupValues[1]
    val epochSecond = calendarOperandInstant(text, timeZone, DateCalendarBasis.PROLEPTIC_GREGORIAN)
        ?: throw UnsupportedConstructException("EvalClock.now is not a real date-time in the model time zone: $raw")
    val millis = match.groupValues[2].ifEmpty { "0" }.toLong()
    return TemporalValue(text, DateCalendarBasis.PROLEPTIC_GREGORIAN, epochSecond * 1_000L + millis)
}

private fun Interpreter.temporalText(
    text: String,
    basis: DateCalendarBasis,
): Evaluated<TemporalValue> {
    val epochSecond = calendarOperandInstant(text, timeZone, basis)
        ?: return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    return Evaluated.Present(TemporalValue(text, basis, epochSecond * 1_000L))
}

private fun Interpreter.composeDateTime(args: List<Expr>): Evaluated<TemporalValue> {
    if (args.size != 2) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    val date = evalTemporal(args[0])
    val time = evalTime(args[1])
    dominantNoValue(date, time)?.let { return it }
    val dateValue = (date as Evaluated.Present).value
    val timeValue = (time as Evaluated.Present).value
    val text = "${dateValue.date}T$timeValue"
    val instant = calendarOperandInstant(text, timeZone, dateValue.basis)
        ?: return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    return Evaluated.Present(TemporalValue(text, dateValue.basis, instant * 1_000L))
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
    if (args.size != 3) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    var hasEmpty = false
    var hasMalformed = false
    val components = IntArray(3)
    for (i in args.indices) {
        val arg = args[i]
        if (malformedField(arg)) {
            hasMalformed = true
            continue
        }
        if (emptyCheckRelevant(arg)) {
            hasEmpty = true
            continue
        }
        components[i] = valToInt(evalExpr(arg)) ?: run {
            hasMalformed = true
            0
        }
    }
    if (hasMalformed) return Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    if (hasEmpty) return Evaluated.NoValue(NoValueReason.NOT_GIVEN)
    val (hour, minute, second) = Triple(components[0], components[1], components[2])
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) {
        return Evaluated.NoValue(NoValueReason.NOT_GIVEN)
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
    val amount = (amountValue as? Val.Num)?.v?.display()?.toLongOrNull()
    val amountState: Evaluated<Long> = when {
        amount != null -> Evaluated.Present(amount)
        malformedField(e.args[1]) -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
        emptyCheckRelevant(e.args[1]) -> Evaluated.NoValue(NoValueReason.NOT_GIVEN)
        else -> Evaluated.NoValue(NoValueReason.NON_RELEVANT)
    }
    dominantNoValue(source, amountState)?.let { return it }
    val value = (source as Evaluated.Present).value
    val n = (amountState as Evaluated.Present).value
    val shifted = when (FunctionOp.byName(e.name)) {
        FunctionOp.ADD_DAYS -> if (value.isDateTime) {
            val landing = wallDayLandingInstant(instantToLocal(value.instantSeconds(timeZone), timeZone), n.toInt(), timeZone)
            val landingMillis = landing * 1_000L + value.instantMillis(timeZone).mod(1_000L)
            TemporalValue(
                isoFromCalendarInstant(landing, timeZone, value.basis),
                value.basis,
                landingMillis,
            )
        } else {
            val date = if (value.basis == DateCalendarBasis.LEGACY_HYBRID)
                addLegacyCalendarDays(value.date, n.toInt()) else addDays(value.date, n.toInt())
            date?.let { TemporalValue(it, value.basis) }
        }
        FunctionOp.ADD_MONTHS -> {
            val date = if (value.basis == DateCalendarBasis.LEGACY_HYBRID)
                addLegacyCalendarMonths(value.date, n.toInt()) else addMonths(value.date, n.toInt())
            date?.let { TemporalValue(it, value.basis) }
        }
        FunctionOp.ADD_YEARS -> {
            val date = if (value.basis == DateCalendarBasis.LEGACY_HYBRID)
                addLegacyCalendarYears(value.date, n.toInt()) else addYears(value.date, n.toInt())
            date?.let { TemporalValue(it, value.basis) }
        }
        FunctionOp.ADD_HOURS, FunctionOp.ADD_MINUTES, FunctionOp.ADD_SECONDS -> {
            val unit = when (FunctionOp.byName(e.name)) {
                FunctionOp.ADD_HOURS -> 3_600_000L
                FunctionOp.ADD_MINUTES -> 60_000L
                else -> 1_000L
            }
            val instantMillis = value.instantMillis(timeZone) + n * unit
            TemporalValue(
                isoFromCalendarInstant(instantMillis.floorDiv(1_000L), timeZone, value.basis),
                value.basis,
                instantMillis,
            )
        }
        else -> error("not a temporal shift: ${e.name}")
    }
    return shifted?.let { Evaluated.Present(it) } ?: Evaluated.NoValue(NoValueReason.NON_RELEVANT)
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
    is Val.Num -> v.v.display().toIntOrNull()
    is Val.Txt -> v.v.toIntOrNull()
    else -> null
}

private fun pad2(n: Int): String = n.toString().padStart(2, '0')

/** Exact instant identity of a legal date-time expression, carried by [evalTemporal] through composition. */
internal fun Interpreter.dateTimeInstant(e: Expr): Long? =
    (evalTemporal(e) as? Evaluated.Present)?.value?.takeIf { it.isDateTime }?.instantMillis(timeZone)

/** `ValueAsDate(field, FirstDay|LastDay)` — resolve a (DAY_OPTIONAL) date field's raw value to a concrete
 *  date: a specified day → itself, an omitted `00` day → the month's first (`FirstDay`) or last (`LastDay`)
 *  day. Unknown when the field is empty/formally-invalid or the value doesn't parse. */
private fun Interpreter.valueAsDate(args: List<Expr>): Val {
    val path = pathOf(args[0])
    if (invalid(path)) return Val.Unknown
    val raw = ctx.displayValue(path) ?: return Val.Unknown
    val lastDay = (args[1] as? Expr.Const)?.name == "LastDay"
    return resolvePartialDate(raw, ctx.dateFormatOf(path) ?: "yyyy-MM-dd", lastDay)?.let { Val.Txt(it) } ?: Val.Unknown
}

/**
 * The one ordered `FirstFilledValue` result consumed by both value evaluation and polarity. Operands are
 * encountered in authored order. A star's `Having` marker is observed immediately before that slot is
 * scanned; a filled or formally-unavailable value terminates the whole walk, so no later slot, filter, or
 * cell is read (IF184). An empty encountered before termination makes the result displaceable by filling;
 * an encountered filter makes it symmetrically fillable regardless of whether it keeps a row.
 */
internal data class FirstFilledScan(val value: Val, val fill: Fill, val encounteredFilter: Boolean)

internal fun Interpreter.firstFilledScan(f: Expr.Func): FirstFilledScan {
    var fillable = false
    var encounteredFilter = false
    for (operand in f.args) when (operand) {
        is Expr.Star -> {
            if (operand.having != null) {
                encounteredFilter = true
                fillable = true
            }

            // An index column's empty/duplicate status is column-wide in the kernel cache. Keep the existing
            // conservative gate, but reach it only when this operand itself is reached.
            val operandContext = ctxOf(operand)
            if (operand.having == null &&
                indexOperandNonEvaluable(operandContext, operand.path, operand.bindDepth)
            ) {
                return FirstFilledScan(Val.Unknown, Fill.of(fillable, fillable), encounteredFilter)
            }

            val group = operand.path.substringBeforeLast('/')
            val rows = operandContext.rowContexts(group, operand.bindDepth)
            var visitedRow = false
            for (row in rows) {
                if (operand.having != null && !havingInterp(row).fires(operand.having)) continue
                visitedRow = true
                val reps = row.repsOf(operand.path)
                if (cellRelevant != null && !cellRelevant.invoke(operand.path, reps)) {
                    return FirstFilledScan(Val.Unknown, Fill.of(fillable, fillable), encounteredFilter)
                }
                if (computeMode) {
                    val cell = ScanCell(
                        operand.path,
                        reps,
                        row.displayValue(operand.path),
                        row.cellState(operand.path),
                    )
                    if (!filledOrPoison(cell)) {
                        fillable = true
                        continue
                    }
                } else if (!row.filled(operand.path)) {
                    fillable = true
                    continue
                }
                return FirstFilledScan(row.field(operand.path), Fill.of(fillable, fillable), encounteredFilter)
            }
            if (!visitedRow) fillable = true
        }
        is Expr.Field -> {
            val operandContext = ctxOf(operand)
            if (!operandContext.filled(operand.path)) {
                fillable = true
                continue
            }
            return FirstFilledScan(evalExpr(operand), Fill.of(fillable, fillable), encounteredFilter)
        }
        else -> {
            val value = evalExpr(operand)
            return FirstFilledScan(value, Fill.of(fillable, fillable), encounteredFilter)
        }
    }

    // NO terminal value → the per-kind FirstValueCombiner identity: NUMBER gives the fillable 0; the
    // other kinds give NOT-GIVEN. A future value in any exhausted operand can displace that identity.
    return FirstFilledScan(firstFilledEmpty(f), Fill.of(true, true), encounteredFilter)
}

private fun Interpreter.firstFilledValue(f: Expr.Func): Val = firstFilledScan(f).value

/** The empty-selection identity of a `FirstFilledValue` by its operand kind (the kernel's per-kind
 *  `FirstValueCombiner`): NUMBER → the fillable `0`; every other kind → NOT-GIVEN ([Val.Unknown], the
 *  comparison does not fire / a computation clears). IF83. */
private fun Interpreter.firstFilledEmpty(f: Expr.Func): Val =
    if (firstFilledKind(f) == Kind.NUMBER) Val.Num(DEC_ZERO) else Val.Unknown

/** The declared kind of a `FirstFilledValue`'s operand (its star / field), or null when indeterminable. */
private fun Interpreter.firstFilledKind(f: Expr.Func): Kind? = f.args.firstNotNullOfOrNull { a ->
    when (a) {
        is Expr.Star -> ctxOf(a).declaredKind(a.path)
        is Expr.Field -> ctxOf(a).declaredKind(a.path)
        else -> null
    }
}

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
        is Expr.Field -> dateLike(ctxOf(a).declaredKind(a.path))
        is Expr.Star -> dateLike(ctxOf(a).declaredKind(a.path))
        is Expr.SemanticIndex -> dateLike(ctx.declaredKind(a.path))
        else -> producesDate(a)
    }
}

/** The min/max empty-fold identity by operand kind: dates → no value; numbers → `0` (see [dateAggregate]). */
private fun Interpreter.minMaxEmpty(f: Expr.Func): Val = if (dateAggregate(f)) Val.Unknown else Val.Num(DEC_ZERO)

/** Numeric operand-list Min/Max values. Date Min/Max use [aggregateTemporal], which preserves reason and basis. */
private fun Interpreter.minMaxOperandVals(f: Expr.Func): List<Val> = f.args.map(::evalExpr)

/**
 * The FILLED values feeding a value aggregate (`Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`),
 * collected across ALL operands — the single-star form AND the kernel's explicit MULTI-FIELD list (IF51).
 * A starred field expands over its repetition rows (a `Having` filters them); a plain field contributes
 * its value only when filled, so an empty operand is DROPPED (as the star drops empty cells) — `MinValue(A, B)`
 * with `B` empty ignores `B`, it never treats it as 0. A filled-but-malformed operand resolves to [Val.Unknown]
 * and [combine] then suppresses the whole aggregate.
 */
private fun Interpreter.starVals(f: Expr.Func): List<Val> = f.args.flatMap { aggOperandVals(it) }

private fun Interpreter.aggOperandVals(arg: Expr): List<Val> = when (arg) {
    is Expr.Star -> starOperandVals(arg)
    else -> if (ctx.filled(pathOf(arg))) listOf(evalExpr(arg)) else emptyList()   // plain field: empty dropped
}

/** A starred aggregate operand's filled values — its group's row cells (empties dropped, like [EvalContext.star]);
 *  an optional `Having` evaluates the per-row filter in each row context and keeps only the matching rows.
 *  COMPUTE mode: a value aggregate reads ALL its selected cells (the kernel feeds every row to the combiner),
 *  so any formally-INVALID selected cell poisons (IF85) — the filter's own reads poison inside [havingInterp]. */
private fun Interpreter.starOperandVals(star: Expr.Star): List<Val> {
    // A GROUP star (`Sum(Lines*)` — no field segment) expands to the group's contained FIELDS, recursively
    // (the kernel ExpandService → getAllContainedFields, exactly like the fill quantifiers — IF49/IF51),
    // each field's cells collected with the same star semantics (bindDepth + Having preserved; IF99).
    if (ctxOf(star).declaredKind(star.path) == null) {
        val contained = ctxOf(star).containedFields(star.path)
        if (contained.isNotEmpty()) {
            return contained.flatMap { starOperandVals(Expr.Star(it, star.having, star.outer, star.bindDepth)) }
        }
    }
    if (star.having == null) {
        if (computeMode && ctx.starScanStates(star.path, star.bindDepth).any { it == CellState.INVALID }) {
            throw PoisonedReadException(star.path)
        }
        // An index field's not-check-relevant cell (mandatory-empty / duplicated value) makes the whole
        // aggregate non-evaluable — the kernel's `combineFeldListe` returns NICHT_PRUEF_REL on the first such
        // cell, so the referencing rule is suppressed (IF15/IF60 generalized to aggregates).
        if (indexOperandNonEvaluable(ctx, star.path, star.bindDepth)) return listOf(Val.Unknown)
        return ctx.star(star.path, star.bindDepth)
    }
    val group = star.path.substringBeforeLast('/')
    return ctx.rowContexts(group, star.bindDepth)
        .filter { rc -> havingInterp(rc).fires(star.having) }
        .mapNotNull { rc ->
            if (computeMode && rc.cellState(star.path) == CellState.INVALID) throw PoisonedReadException(star.path)
            if (rc.filled(star.path)) rc.field(star.path) else null
        }
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
