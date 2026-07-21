package io.github.mbackschat.a12.dm.interpreter.eval

/**
 * Validation-stage presence of one group instance. Formal conversion and error marking are independent kernel
 * stages. Relevance is independently [GroupRelevance.PARTIAL] or [GroupRelevance.FULL]: positive presence is
 * knowable from any relevant descendant, while absence is knowable only when the complete group is relevant.
 */
data class GroupPresenceState(
    val filled: Boolean,
    val erroneous: Boolean,
    val relevance: GroupRelevance = GroupRelevance.FULL,
) {
    val definitelyFilled: Boolean get() = relevance != GroupRelevance.NONE && filled
    val definitelyEmpty: Boolean get() = relevance == GroupRelevance.FULL && !filled && !erroneous
}

/** How much of a group is validation-relevant for this concrete row. */
enum class GroupRelevance { NONE, PARTIAL, FULL }
