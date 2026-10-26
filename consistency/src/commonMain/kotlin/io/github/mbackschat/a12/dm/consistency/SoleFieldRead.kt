package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand

/**
 * What the lone operand of a single-value carrier names, judged in the order A12 asks: whether it is a group,
 * then whether a repetition star reads across it, then whether it projects a category.
 *
 * Each carrier words its own finding for each arm, but the order and the arms are one reading, measured on the
 * range and partial-date carriers alike (`rulekit.validate.laws.RangeOperandGateLawsTest`,
 * `rulekit.validate.laws.ValueAsDateOperandGateLawsTest`).
 */
internal sealed interface SoleFieldRead {
    val target: String

    data class Group(override val target: String) : SoleFieldRead

    data class Starred(override val target: String) : SoleFieldRead

    data class Categorized(override val target: String, val category: String) : SoleFieldRead

    data class Field(override val target: String, val declared: TrackedDeclaration) : SoleFieldRead
}

/** [operand] read as a [SoleFieldRead], or null where it is no declared field or group this reader can address. */
internal fun TrackedFactBase.soleFieldReadOf(
    operand: CallOperand,
    declaringGroup: String,
    element: TrackedDeclaration,
    inAFilterBody: Boolean,
): SoleFieldRead? {
    val read = operandSpec(operand, declaringGroup, element, inAFilterBody) ?: return null
    val target = addressedTargetOf(read, declaringGroup, element, inAFilterBody) ?: return null
    val declared = declarationAt(target) ?: return null
    val category = read.category
    return when {
        // A repeatable group, starred or not: one nothing addresses is the reference reader's `MVK_NO_WILDCARD`,
        // which [addressedTargetOf] has already stood down for (KERNEL-FINDINGS KF599).
        declared.declaredKind == DeclaredKind.GROUP -> SoleFieldRead.Group(target)
        declared.declaredKind != DeclaredKind.FIELD -> null
        '*' in read.reference -> SoleFieldRead.Starred(target)
        category != null -> SoleFieldRead.Categorized(target, category)
        else -> SoleFieldRead.Field(target, declared)
    }
}
