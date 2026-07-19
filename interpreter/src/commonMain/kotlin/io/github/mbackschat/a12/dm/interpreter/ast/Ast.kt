package io.github.mbackschat.a12.dm.interpreter.ast

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.decOrNull
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.regex.CompiledPattern
import io.github.mbackschat.a12.dm.interpreter.regex.compilePattern

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

/**
 * The field-list value-list quantifiers (shape B: `op(f1, f2 In v1, v2)`) — classify each field as IN (its
 * present value is in the list), OUT (present, not in the list), or EMPTY, then fire by the count: AtLeastOne
 * iff some field is IN, No iff none is IN, NotAll iff some field is OUT. (Distinct from the scalar
 * single-field `FieldValueIncludedInValueList`, which is a [Cond.Pred].)
 */
enum class ValueListQuantOp(val kernelName: String) {
    AT_LEAST_ONE("AtLeastOneFieldValueIncludedInValueList"),
    NO("NoFieldValueIncludedInValueList"),
    NOT_ALL("NotAllFieldValuesIncludedInValueList"),
    ;

    companion object {
        fun byName(name: String): ValueListQuantOp? = entries.firstOrNull { it.kernelName == name }
    }
}

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
    data class Pattern(val value: Expr, val pattern: Expr, val expectMatch: Boolean) : Cond {
        /** The regex literal compiled ONCE (with Java-Pattern semantics on both hosts, IG90) and reused across
         *  every evaluated row — the kernel caches globally; this caches per AST node. Not part of data-class
         *  identity. [by lazy] is thread-safe; the pattern is always a [Expr.StrLit] on a valid model. */
        val compiled: CompiledPattern by lazy { compilePattern((pattern as Expr.StrLit).v) }
    }

    /**
     * The infix tolerance comparison `left DiffersWithToleranceRangeN right` — fires iff `|left − right| >
     * [tolerance]` (a fixed band of 1/2/5/10; the bound is STRICT, so a difference exactly equal to the band
     * does NOT fire). Numbers only; an empty numeric operand reads as 0. Its polarity is EQ-like (filling an
     * empty operand toward the other can clear it → OMISSION; both present → VALUE).
     */
    data class Tolerance(val left: Expr, val right: Expr, val tolerance: Dec) : Cond

    /**
     * A field-list value-list quantifier (shape B: `op([fields] In [values])`) — see [ValueListQuantOp]. The
     * [fields] are bare field references; the [values] are the value list (literals and/or field references).
     */
    data class ValueListQuant(val op: ValueListQuantOp, val fields: List<Expr>, val values: List<Expr>) : Cond

    /**
     * A `CustomCondition <name>` — the rule delegates its fire/don't-fire decision to a consumer-supplied
     * custom condition resolved by [name] (the kernel's `ICustomCondition` client SPI, cross-platform JVM+JS).
     * Fires iff the registered condition's `check` returns true on an evaluated, formally-correct row; an
     * unregistered name makes the rule unsupported. (Always guarded by a field reference — a bare custom
     * condition is a kernel `MVK_ERROR_FIELD_NOT_REFERENCED`.)
     */
    data class Custom(val name: String) : Cond
}

/** Binary arithmetic operators (language-independent symbols). All five are evaluated and differential-locked
 *  by `DivPowDiffTest` (incl. divide-by-zero → Unknown and the `^` > `*`/`/` > `+`/`-` precedence). */
enum class ArithOp { ADD, SUB, MUL, DIV, POW }

/** Expression AST. */
sealed interface Expr {
    /** A field/value reference. [outer] marks the `$` outer-iteration correlation (KERNEL-FINDINGS §"$
     *  correlation"): inside a `Having` filter it resolves against the ENCLOSING iteration row, not the inner
     *  filtered row. Default `false` (an ordinary reference). */
    data class Field(val path: String, val outer: Boolean = false) : Expr

    /** A numeric literal. [asVal] is the typed value, built ONCE at parse (the AST is cached per rule, #31)
     *  so evaluating the literal costs zero allocation per (rule, row) — the kernel's constant is likewise
     *  a field of the generated code, built once at compile. (Not a constructor param → out of equals/copy.) */
    data class NumLit(val v: Dec) : Expr {
        val asVal: Val.Num = Val.Num(v)
    }

    data class StrLit(val v: String) : Expr {
        val asVal: Val.Txt = Val.Txt(v)
    }

    data class BoolLit(val v: Boolean) : Expr {
        val asVal: Val.Bool = Val.Bool(v)
    }

    /** A starred path, e.g. `Items* / Count` — the values across repetition rows; only inside an aggregate.
     *  [having] is an optional per-row FILTER (a `… Having <cond>` clause on the starred operand): the
     *  aggregate consumes only the rows for which the condition holds. [bindDepth] is the number of path
     *  segments STRICTLY ABOVE the first `*`-marked group (IF93/IF94): an iterating row binds only those
     *  levels — the first starred level and everything below iterate fully (a multi-star `A* / B* / F` binds
     *  above `A`). −1 (a hand-built node) means "the leaf group's parent" — the single-star default, where
     *  the two coincide. */
    data class Star(val path: String, val having: Cond? = null, val outer: Boolean = false,
                    val bindDepth: Int = -1) : Expr

    /** A value-valued function, e.g. Min(a, b) (operand-list) or Sum(Star) (aggregate). */
    data class Func(val name: String, val args: List<Expr>) : Expr

    /** A no-operand constant resolved at evaluation time, e.g. `Today` (the current date from the clock). */
    data class Const(val name: String) : Expr

    /** A binary arithmetic expression, e.g. `[BackorderQuantity] + 2`. Grouped with curly braces `{ }`. */
    data class Arith(val op: ArithOp, val left: Expr, val right: Expr) : Expr

    /**
     * A semantic-index lookup — the value of [path] in the repetition whose INDEX field equals the lookup key;
     * an absent row / empty cell reads as the kind's empty value. The key is **either** a string literal
     * ([indexValue], the `[<field> For "<lit>"]` form) **or** another field whose current value is the key
     * ([indexFieldPath], the `[<field> For <keyField>]` form — the kernel's field-keyed semantic index).
     * Exactly one of [indexValue]/[indexFieldPath] is non-null.
     */
    data class SemanticIndex(val path: String, val indexValue: String? = null, val indexFieldPath: String? = null) : Expr

    /** An enum-category access `[<field> -> <category>]` — maps the enumeration [path]'s stored value to its
     *  value in the named [category] (the model pairs category values positionally with the enum values), then
     *  compares against the category's value domain. An empty enum or an out-of-enum value yields no comparison
     *  value (→ the rule does not fire / is suppressed). */
    data class EnumCategory(val path: String, val category: String, val outer: Boolean = false) : Expr
}

/** Parse a raw field value into a typed [Val] by kind. A malformed NUMBER resolves to [Val.Unknown] (NOT a
 *  crash) — its formal error is detected separately, but a value reached via an aggregate/computation is
 *  parsed without the up-front malformed-operand guard, so the parse itself must be total. */
fun typedValue(kind: Kind, raw: String): Val = when (kind) {
    Kind.NUMBER -> decOrNull(raw)?.let { Val.Num(it) } ?: Val.Unknown
    Kind.STRING, Kind.DATE, Kind.TIME, Kind.DATE_TIME -> Val.Txt(raw)   // ISO dates/times as text compare chronologically
    Kind.BOOLEAN, Kind.CONFIRM -> Val.Bool(raw.equals("true", ignoreCase = true))
    // A DATE_RANGE needs its field's format + separator to parse — the general Document resolves it
    // format-aware; this meta-less path (the flat spike contexts) does not model ranges.
    Kind.DATE_RANGE -> Val.Unknown
}

/** The value of an EMPTY field, by kind — the TYPE-DRIVEN asymmetry (within a filled row). */
fun emptyValue(kind: Kind): Val = when (kind) {
    Kind.NUMBER -> Val.Num(DEC_ZERO)   // empty number → 0
    Kind.CONFIRM -> Val.Bool(false)     // empty confirm → False
    Kind.BOOLEAN, Kind.STRING, Kind.DATE, Kind.TIME, Kind.DATE_RANGE, Kind.DATE_TIME -> Val.Unknown  // not evaluated
}
