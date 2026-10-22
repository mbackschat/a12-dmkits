package io.github.mbackschat.a12.dm.consistency.syntax

/** The two connectives a condition joins its parts with. */
internal enum class ConnectiveKind { AND, OR }

/**
 * A recognized condition's connective structure, kept as written: a bracket nests and never flattens, and a
 * filter body stays with the entity it filters. That matches the tree A12's iteration-guard verdict walks, which
 * never enters a filter body (`IEntityId.traverse` descends into a semantic index only; kernel 30.8.1, read
 * 2026-10-22). Other checks in the same pass do read filter bodies, so they take those references elsewhere.
 *
 * Leaves point at the shapes the recognizer already records, so a check reads an operand from the same tokens
 * every other family reads.
 */
internal sealed interface ConditionNode {

    /**
     * Parts joined by connectives, `connectives[i]` sitting between `parts[i]` and `parts[i + 1]`. A mix of the
     * two inside one bracket is kept rather than regrouped, since A12 refuses it as `MVK_BRACKET_MISSING` before
     * any check that would read the grouping.
     */
    data class Junction(val parts: List<ConditionNode>, val connectives: List<ConnectiveKind>) : ConditionNode

    data class Call(val shape: CallShape) : ConditionNode

    data class Comparison(val shape: ComparisonShape) : ConditionNode

    /** A `CustomCondition`, whose meaning the model does not carry. */
    data object Custom : ConditionNode
}

/**
 * Whether [node] proves the positive presence [proves] asks about: one `And` part suffices and every `Or` part
 * must (SPEC-2026-10-11-01). A mix folds left to right as written, the reading this proof had before the tree.
 */
internal fun provesPresence(node: ConditionNode, proves: (CallShape) -> Boolean): Boolean = when (node) {
    is ConditionNode.Call -> proves(node.shape)
    is ConditionNode.Comparison, ConditionNode.Custom -> false
    is ConditionNode.Junction -> node.parts.drop(1).foldIndexed(provesPresence(node.parts.first(), proves)) { i, proof, part ->
        val next = provesPresence(part, proves)
        when (node.connectives[i]) {
            ConnectiveKind.AND -> proof || next
            ConnectiveKind.OR -> proof && next
        }
    }
}
