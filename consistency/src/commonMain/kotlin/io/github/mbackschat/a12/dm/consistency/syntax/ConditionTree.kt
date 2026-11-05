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

/**
 * How a presence call proves a row from its operands: EVERY operand must lie in the row for the list quantifiers,
 * ANY one suffices for the all-filled pair. One law for an unkeyed row token over a joined level and a keyed row
 * token over a key (KERNEL-FINDINGS KF675, KF546), so it is stated once. `FieldFilled` and `GroupFilled` take one
 * operand, where the two readings agree.
 */
private enum class OperandProof { ANY, EVERY }

private val PRESENCE_PROOF_BY_SPELLING: Map<ConditionLanguage, Map<String, OperandProof>> =
    OPERATOR_SPELLINGS.mapValues { (_, spellings) ->
        mapOf(
            OperatorId.FIELD_FILLED to OperandProof.EVERY,
            OperatorId.GROUP_FILLED to OperandProof.EVERY,
            OperatorId.AT_LEAST_ONE_FIELD_FILLED to OperandProof.EVERY,
            OperatorId.MORE_THAN_ONE_FIELD_FILLED to OperandProof.EVERY,
            OperatorId.AT_LEAST_ONE_GROUP_FILLED to OperandProof.EVERY,
            OperatorId.ALL_FIELDS_FILLED to OperandProof.ANY,
            OperatorId.ALL_GROUPS_FILLED to OperandProof.ANY,
        ).mapNotNull { (id, proof) -> spellings[id]?.let { it to proof } }.toMap()
    }

/**
 * Whether [node]'s presence calls prove a row, [inRow] judging one operand: `true` in the row, `false` outside it,
 * `null` where it cannot tell. Returns `true` when proved, `null` when not proved but some call went undecided, and
 * `false` otherwise. A call with no operand is undecided, since the arity family answers it.
 */
internal fun provesRow(node: ConditionNode, language: ConditionLanguage, inRow: (CallShape, List<Token>) -> Boolean?): Boolean? {
    val proofs = PRESENCE_PROOF_BY_SPELLING[language].orEmpty()
    var undecided = false
    val proven = provesPresence(node) { call ->
        val proof = proofs[call.operator] ?: return@provesPresence false
        val verdicts = call.operands.map { inRow(call, it) }
        when {
            verdicts.isEmpty() || null in verdicts -> { undecided = true; false }
            proof == OperandProof.EVERY -> verdicts.all { it == true }
            else -> verdicts.any { it == true }
        }
    }
    return if (proven) true else if (undecided) null else false
}
