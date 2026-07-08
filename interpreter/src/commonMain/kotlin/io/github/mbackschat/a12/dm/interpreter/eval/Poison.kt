package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * COMPUTE-mode control flow — the interpreter's twin of the kernel's `CalculationFieldException` (IF85):
 * during `compute()` a read of a **formally-invalid** cell (an input field, or a computed dependency that
 * ended INVALID — an errored value or an itself-poisoned instance) aborts the reading computation instance.
 * The catcher (`DmInterpreter.computeInstances`) marks that target instance POISONED (it produces no value,
 * reports CLEARED under the report-all contract, and poisons its own dependents' reads transitively) and
 * skips its remaining alternatives. Never thrown during validation, where formal invalidity stays the
 * three-valued "not check-relevant" exclusion (`isWertAngegeben` → UNKNOWN).
 */
class PoisonedReadException(val path: String) : RuntimeException("compute read of a formally-invalid cell: $path")
