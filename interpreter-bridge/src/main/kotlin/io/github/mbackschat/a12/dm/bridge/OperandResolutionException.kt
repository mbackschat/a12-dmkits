package io.github.mbackschat.a12.dm.bridge

/**
 * An operand naming something the model does not have — as opposed to rulekit refusing an operand it
 * understood.
 *
 * Both arrive as an [IllegalArgumentException], and telling them apart is what makes a coverage measurement
 * mean anything: a rulekit refusal is a finding about the rule, while this is either a defect in the bridge or
 * a rule referencing an entity that is not there. Classifying by message text does not work — rulekit types
 * many of its entry points per kind, so a real refusal often reads as plain English with no `MVK_*` code, and
 * a harness keying on that prefix scores it as nothing.
 *
 * Extends [IllegalArgumentException] rather than replacing it, so a caller that only cares that the operand
 * was refused is unaffected; a caller that must distinguish the two checks the type.
 */
internal class OperandResolutionException(message: String?, cause: Throwable?) :
        IllegalArgumentException(message, cause) {

    private companion object {
        private const val serialVersionUID: Long = 1L
    }
}
