package io.github.mbackschat.a12.dm.interpreter.ast

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.decOrNull
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * The interpreter's condition/value AST, in commonMain so it compiles to BOTH JVM and JS.
 * PURE static code over plain data: imports ZERO kernel (`com.mgmtp.*`) and ZERO groovy types (enforced
 * by the `verifyInterpreterPurity` lint), and the only platform-specific primitive is [Dec]. Only the
 * differential TESTS (in `:adapter`, JVM) touch the kernel.
 */

/** Three-valued logic. UNKNOWN models the kernel's "not evaluated" — a rule does NOT fire on it. */
enum class Tri {
    TRUE, FALSE, UNKNOWN;

    companion object {
        fun of(b: Boolean): Tri = if (b) TRUE else FALSE
    }
}

/** A resolved, typed value — or [Unknown] when the operand is "not evaluated". */
sealed interface Val {
    data object Unknown : Val
    data class Num(val v: Dec) : Val
    data class Txt(val v: String) : Val
    data class Bool(val v: Boolean) : Val

    /** A date range — its [start]/[end] as canonical ISO `yyyy-MM-dd` dates. Produced by a [Kind.DATE_RANGE]
     *  field (format-parsed in the general `Document`); consumed by the date-range operators (`StartOfDateRange`
     *  /`EndOfDateRange`/…). Not directly comparable as a scalar. */
    data class Range(val start: String, val end: String) : Val
}

enum class CmpOp { LT, LE, GT, GE, EQ, NE }

/** Condition AST — built from condition text by [parseCondition]. */
sealed interface Cond {
    data class Cmp(val op: CmpOp, val left: Expr, val right: Expr) : Cond
    data class And(val left: Cond, val right: Cond) : Cond
    data class Or(val left: Cond, val right: Cond) : Cond
    data class Not(val c: Cond) : Cond

    /** A boolean-valued predicate function, e.g. FieldFilled(path), AllFieldsFilled(a, b). */
    data class Pred(val name: String, val args: List<Expr>) : Cond

    /**
     * An infix pattern test: `value PatternMatched "regex"` / `value PatternViolated "regex"`. The regex
     * is anchored over the WHOLE value (kernel semantics). [expectMatch] = PatternMatched (fires when the
     * value matches); PatternViolated fires when a *present* value does NOT match (empty → not evaluated).
     */
    data class Pattern(val value: Expr, val pattern: Expr, val expectMatch: Boolean) : Cond

    /**
     * The infix tolerance comparison `left DiffersWithToleranceRangeN right` — fires iff `|left − right| >
     * [tolerance]` (a fixed band of 1/2/5/10; the bound is STRICT, so a difference exactly equal to the band
     * does NOT fire). Numbers only; an empty numeric operand reads as 0. Its polarity is EQ-like (filling an
     * empty operand toward the other can clear it → OMISSION; both present → VALUE).
     */
    data class Tolerance(val left: Expr, val right: Expr, val tolerance: Dec) : Cond
}

/** Binary arithmetic operators (language-independent symbols). Division/power are not yet modeled. */
enum class ArithOp { ADD, SUB, MUL, DIV, POW }

/** Expression AST. */
sealed interface Expr {
    data class Field(val path: String) : Expr
    data class NumLit(val v: Dec) : Expr
    data class StrLit(val v: String) : Expr
    data class BoolLit(val v: Boolean) : Expr

    /** A starred path, e.g. `Items* / Count` — the values across repetition rows; only inside an aggregate. */
    data class Star(val path: String) : Expr

    /** A value-valued function, e.g. Min(a, b) (operand-list) or Sum(Star) (aggregate). */
    data class Func(val name: String, val args: List<Expr>) : Expr

    /** A no-operand constant resolved at evaluation time, e.g. `Today` (the current date from the clock). */
    data class Const(val name: String) : Expr

    /** A binary arithmetic expression, e.g. `[BackorderQuantity] + 2`. Grouped with curly braces `{ }`. */
    data class Arith(val op: ArithOp, val left: Expr, val right: Expr) : Expr
}

/** Parse a raw field value into a typed [Val] by kind. A malformed NUMBER resolves to [Val.Unknown] (NOT a
 *  crash) — its formal error is detected separately, but a value reached via an aggregate/computation is
 *  parsed without the up-front malformed-operand guard, so the parse itself must be total. */
fun typedValue(kind: Kind, raw: String): Val = when (kind) {
    Kind.NUMBER -> decOrNull(raw)?.let { Val.Num(it) } ?: Val.Unknown
    Kind.STRING, Kind.DATE, Kind.TIME -> Val.Txt(raw)   // ISO dates/times as text compare chronologically
    Kind.BOOLEAN, Kind.CONFIRM -> Val.Bool(raw.equals("true", ignoreCase = true))
    // A DATE_RANGE needs its field's format + separator to parse — the general Document resolves it
    // format-aware; this meta-less path (the flat spike contexts) does not model ranges.
    Kind.DATE_RANGE -> Val.Unknown
}

/** The value of an EMPTY field, by kind — the TYPE-DRIVEN asymmetry (within a filled row). */
fun emptyValue(kind: Kind): Val = when (kind) {
    Kind.NUMBER -> Val.Num(DEC_ZERO)   // empty number → 0
    Kind.CONFIRM -> Val.Bool(false)     // empty confirm → False
    Kind.BOOLEAN, Kind.STRING, Kind.DATE, Kind.TIME, Kind.DATE_RANGE -> Val.Unknown  // not evaluated
}
