package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Whether the cell at [path] is empty but not check-relevant, so a list reader meets it as unavailable rather than
 * empty: a required-empty field in validation, and in a computation the empty index cell the preliminary index
 * check invalidates, for which the gate throws the poison itself (KERNEL-FINDINGS KF552,
 * `adapter.laws.RequiredEmptyReadDiffTest`).
 */
internal fun Interpreter.unavailableEmpty(c: EvalContext, path: String): Boolean = !c.filled(path) && emptyUnavailable(c, path)

/** [unavailableEmpty] for a cell already known to be empty. The call's [EvalConfig.emptyMayBeUnavailable] answers
 *  for the whole path first, so an ordinary empty cell never reaches the per-cell formal cache. */
internal fun Interpreter.emptyUnavailable(c: EvalContext, path: String): Boolean =
    emptyMayBeUnavailable(path) && invalid(c, path)
