package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * COMPUTE-mode control flow — the interpreter's twin of the kernel's `CalculationFieldException` (IF85):
 * during `compute()` a read of a **formally-invalid** cell (an input field, or a computed dependency that
 * ended INVALID — an errored value or an itself-poisoned instance) aborts the reading computation instance.
 * The catcher (`DmInterpreter.computeInstances`) marks that target instance POISONED (it produces no value,
 * reports CLEARED under the report-all contract, and poisons its own dependents' reads transitively) and
 * skips its remaining alternatives. The exception's immediate [path] supports expression-order tests only;
 * the catcher deliberately erases it, retaining neither source path nor cause/payload/message across the
 * computed-target dependency edge (SPEC-2026-07-26-04). Never thrown during validation, where formal
 * invalidity stays the three-valued "not check-relevant" exclusion (`isWertAngegeben` → UNKNOWN).
 */
class PoisonedReadException(val path: String) : RuntimeException("compute read of a formally-invalid cell: $path")

/**
 * A reached runtime failure, shared by an empty authored pattern and a DateTime wall label with no instant.
 * The failure belongs to execution rather than an operand state.
 * The kernel raises it only at execution, so the failure is not an operand
 * state the three-valued algebra can absorb: an earlier operand that already decided the expression (a malformed
 * read that poisons first, a false `And` prefix) prevents it, and nothing after it can (SPEC-2026-10-03-06).
 *
 * Two catchers own it. A computation instance ends with no value, a `berechnungsWertFehler` on its target and a
 * poisoned target for its readers — the numeric domain failure's outcome, reached by a different path. A
 * validation rule ends its row loop and reports its own code once, at its FIRST iteration address
 * (`DmInterpreter.rowOutcomes`). Never caught anywhere else.
 */
sealed class RuntimeEvaluationFailure(message: String) : RuntimeException(message)

/** An empty authored pattern is rejected on execution, after its operand read (SPEC-2026-10-10-13). */
class EmptyPatternFailure : RuntimeEvaluationFailure("an authored pattern must not be empty")

class TemporalLabelFailure(val label: String) : RuntimeEvaluationFailure("the wall label $label has no instant in the model zone")

/**
 * A custom condition reached in a rule whose error entity is a group. A12 converts the rule's error field into the
 * condition's argument before it looks the condition up, and its generated code names no field for a group, so
 * the call fails before any condition runs (KERNEL-FINDINGS KF615).
 */
class GroupErrorEntityCustomFailure(name: String) :
    RuntimeEvaluationFailure("custom condition \"$name\" cannot receive a group as its error field")

/** A message token reading a value through a key that selects no row (KERNEL-FINDINGS KF561). */
class UnmatchedMessageKeyFailure(token: String) : RuntimeEvaluationFailure("the message parameter $token selects no row")
