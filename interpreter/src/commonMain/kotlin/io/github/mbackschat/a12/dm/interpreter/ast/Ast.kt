package io.github.mbackschat.a12.dm.interpreter.ast

import io.github.mbackschat.a12.dm.interpreter.Dec
import io.github.mbackschat.a12.dm.interpreter.dec
import io.github.mbackschat.a12.dm.interpreter.decOrNull
import io.github.mbackschat.a12.dm.interpreter.DEC_ZERO
import io.github.mbackschat.a12.dm.interpreter.model.Kind

/*
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

/** A resolved, typed value, [Unknown] when the operand is not evaluated, or [DomainFailure] when an
 *  expression WAS evaluated but has no value in its domain. Two sources are measured: a numeric expression
 *  outside its domain (division by zero, an out-of-range power), and a DATE_TIME literal naming a wall time
 *  the model's declared zone has no instant for (IF326) — both reach the store with nothing to store. */
sealed interface Val {
    data object Unknown : Val
    data object DomainFailure : Val
    data class Num(val v: Dec) : Val
    data class Txt(val v: String) : Val
    data class Bool(val v: Boolean) : Val

    /** A date range — its [start]/[end] as canonical ISO `yyyy-MM-dd` dates. Produced by a [Kind.DATE_RANGE]
     *  field (format-parsed in the general `Document`); consumed by the date-range operators (`StartOfDateRange`
     *  /`EndOfDateRange`/…). Equality/inequality are legal; ordering is not. */
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
        private val BY_NAME: Map<String, ValueListQuantOp> = entries.associateBy { it.kernelName }

        /** O(1) by name — see `FunctionOp.byName` for why a linear scan was wrong here. */
        fun byName(name: String): ValueListQuantOp? = BY_NAME[name]
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
    data class Pattern(val value: Expr, val pattern: Expr, val expectMatch: Boolean) : Cond

    /**
     * The infix tolerance comparison `left DiffersWithToleranceRangeN right` — fires iff
     * `|R19(left) − R19(right)| > [tolerance]` (a fixed band of 1/2/5/10; the bound is STRICT, so a difference
     * exactly equal to the band does NOT fire). Numbers only; an empty numeric operand reads as 0. A firing
     * uses directional numeric-`!=` polarity.
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
     * Once reached, fires iff the registered condition's `check` returns true; the callback receives relevance,
     * formal-invalid addresses, and the exact error pointer and owns its hidden dependency decision. An
     * unregistered name makes the rule unsupported. The complete rule must reference its error field somewhere
     * (a bare custom condition is a kernel `MVK_ERROR_FIELD_NOT_REFERENCED`), but that reference need not guard
     * callback invocation.
     */
    data class Custom(val name: String) : Cond
}

/** Binary arithmetic operators (language-independent symbols). All five are evaluated and differential-locked
 *  by `DivPowDiffTest` and `DivisionDomainFailureComputationDiffTest`: divide-by-zero is a domain failure,
 *  projected to UNKNOWN by comparisons but retained as invalidity by numeric computations. */
enum class ArithOp { ADD, SUB, MUL, DIV, POW }

/** Expression AST. */
sealed interface Expr {
    /**
     * An operand that NAMES a model path — the two shapes an entity slot accepts, [Field] and [Star].
     *
     * [path] and [outer] are declared here rather than on each shape because every site that resolves an
     * operand needs them without caring which shape it got: whether the path names a group, what a group
     * expands to, which context a `$`-marked operand resolves against. Those sites used to hand-destructure
     * (`arg as? Star`, then `?: (arg as Field)`), which is a cast the compiler cannot check and a site can
     * silently get wrong — `pathOf` rejects a [Star] outright, so reaching for it on an operand slot throws
     * rather than answering. Hoisting the two members makes the shared shape a TYPE fact: a new
     * `EntitySource` cannot be added without supplying them, and no caller has to remember the pair exists.
     */
    sealed interface EntitySource : Expr {
        /** The model path this operand names. It may name a FIELD or a GROUP; only the model decides which. */
        val path: String

        /** The `$` correlation marker — resolve against the enclosing repetition environment, not the inner row. */
        val outer: Boolean
    }

    /** The key of an owner semantic-index suffix. Keeping the two grammar alternatives explicit avoids the
     * nullable-pair invariant of [SemanticIndex] in the compositional `entitySpec` representation. */
    sealed interface EntityIndex {
        data class Literal(val value: String) : EntityIndex
        data class Field(val path: String, val outer: Boolean = false) : EntityIndex
    }

    /** A field/value reference. [outer] marks the `$` correlation: inside a `Having` filter it resolves
     *  its own named path against the complete ENCLOSING repetition environment, not the inner filtered
     *  row. Different paths can select different captured repetition coordinates. Default `false`. */
    data class Field(override val path: String, override val outer: Boolean = false) : EntitySource

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
    data class Star(override val path: String, val having: Cond? = null, override val outer: Boolean = false,
                    val bindDepth: Int = -1) : EntitySource

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
    data class SemanticIndex(
        val path: String,
        val indexValue: String? = null,
        val indexFieldPath: String? = null,
        val outer: Boolean = false,
        val indexFieldOuter: Boolean = false,
    ) : Expr

    /** An enum-category access `[<field> -> <category>]` — maps the enumeration [path]'s stored value to its
     *  value in the named [category] (the model pairs category values positionally with the enum values), then
     *  compares against the category's value domain. An empty enum or an out-of-enum value yields no comparison
     *  value (→ the rule does not fire / is suppressed). */
    data class EnumCategory(val path: String, val category: String, val outer: Boolean = false) : Expr

    /** The owner grammar's compositional `entitySpec`: source, optional enum-category projection, then optional
     * semantic index. The legacy single-modifier nodes above remain for the common shapes; this node represents
     * the combinations whose modifiers must not erase one another. */
    data class EntitySpec(
        val source: EntitySource,
        val category: String? = null,
        val semanticIndex: EntityIndex? = null,
    ) : Expr
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
/**
 * The bind depth to give a field an operand EXPANDS to.
 *
 * `bindDepth >= 0` is an absolute count of bound group levels, so it survives being copied onto a deeper path
 * unchanged. **`-1` does not** — it is path-RELATIVE ("the leaf group's parent"), so re-attaching it to a field
 * below the operand makes the same stored value denote a different level, and the resolver then caps it against
 * whatever group the rule happens to iterate. That is not a hypothesis: it is what produced IF287's fourth
 * defect, where an expanded field took the `-1` default and an anchored intermediate level pinned the compared
 * set to one row while the operand's own reference channel spanned all of them.
 *
 * So expansion resolves the sentinel to the operand's own absolute depth exactly once, at the point where the
 * path changes. Where the two agree this is a no-op; where they diverge, the operand's depth is the one that
 * matches the kernel.
 */
internal fun expansionBindDepth(operandPath: String, bindDepth: Int): Int =
if (bindDepth >= 0) bindDepth else operandPath.trim('/').split('/').size
