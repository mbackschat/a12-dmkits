package io.github.mbackschat.a12.dm.interpreter

/**
 * Closed model-static work classes. The optional observer exists only for deterministic performance guards;
 * it is instance-local and never participates in evaluation semantics.
 */
internal enum class PreparationWork {
    RULE_AST,
    COMPUTATION_AST,
    IMPLICIT_RULE_AST,
    RULE_PLAN,
    COMPUTATION_PLAN,
    ALL_COMPUTATION_ORDER,
    COMPUTATION_CLOSURE,
}

internal data class PreparationEvent(val work: PreparationWork, val key: String)

internal fun interface PreparationObserver {
    fun performed(event: PreparationEvent)
}
