package io.github.mbackschat.a12.dm.interpreter.spike

/**
 * What the interpreter needs from "the document, in the current evaluation context" — the seam that
 * lets the SAME evaluator serve a single row (SpikeDoc) and per-row / outer contexts of a repeatable
 * group (RepDoc), without the operator logic knowing which it is.
 */
interface EvalContext {
    /** Whether this row is evaluated at all (the kernel's "an entirely empty row is not evaluated"). */
    val evaluated: Boolean

    /** Resolve a scalar field reference (empty handled by type). */
    fun field(path: String): Val

    /** Whether a field has a value (for FieldFilled / AllFieldsFilled). */
    fun filled(path: String): Boolean

    /** The FILLED values of a starred field across the repeatable group's rows (for aggregates). */
    fun star(path: String): List<Val>
}
