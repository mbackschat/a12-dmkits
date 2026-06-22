package io.github.mbackschat.a12.dm.interpreter

/**
 * The interpreter's condition/value AST, in commonMain so it compiles to BOTH JVM and JS.
 * PURE static code over plain data: imports ZERO kernel (`com.mgmtp.*`) and ZERO groovy types (enforced
 * by the `verifyInterpreterPurity` lint), and the only platform-specific primitive is [Dec]. Only the
 * differential TESTS (in `:adapter`, JVM) touch the kernel.
 */

/** Field kinds the empty-value asymmetry depends on — the miniature "metadata bridge" (proposal §10). */
enum class Kind { NUMBER, STRING, BOOLEAN, CONFIRM, DATE }

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
}

enum class CmpOp { LT, LE, GT, GE, EQ, NE }

/** Value-vs-omission polarity of a fired message (kernel `IMessage.MessageType`). */
enum class MsgType { VALUE, OMISSION }

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
}

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
}

/** Parse a raw field value into a typed [Val] by kind. */
fun typedValue(kind: Kind, raw: String): Val = when (kind) {
    Kind.NUMBER -> Val.Num(dec(raw))
    Kind.STRING, Kind.DATE -> Val.Txt(raw)   // ISO dates as text compare chronologically
    Kind.BOOLEAN, Kind.CONFIRM -> Val.Bool(raw.equals("true", ignoreCase = true))
}

/** The value of an EMPTY field, by kind — the TYPE-DRIVEN asymmetry (within a filled row). */
fun emptyValue(kind: Kind): Val = when (kind) {
    Kind.NUMBER -> Val.Num(DEC_ZERO)   // empty number → 0
    Kind.CONFIRM -> Val.Bool(false)     // empty confirm → False
    Kind.BOOLEAN, Kind.STRING, Kind.DATE -> Val.Unknown  // not evaluated
}
