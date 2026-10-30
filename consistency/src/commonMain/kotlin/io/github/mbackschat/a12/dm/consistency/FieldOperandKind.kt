package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId

/**
 * A GROUP, or a starred field, handed to one of the two field predicates that take an entity rather than a list.
 *
 * A12's `CheckEntityUtils` carries the pair `checkIsField` / `checkIsGroup`, and only the second half had
 * ever been measured in this estate ([KERNEL-FINDINGS KF327], the `MVK_NO_FIELDS_ALLOWED` direction). This
 * is the first half: `MVK_NO_GROUPS_ALLOWED` ([KERNEL-FINDINGS KF357],
 * `rulekit.validate.laws.FieldOperandKindLawsTest`).
 *
 * **The carrier set is two, and that is the whole law.** `FieldFilled` and `FieldNotFilled` refuse a group;
 * all EIGHT list carriers — `AllFieldsFilled`, `NoFieldFilled`, `AtLeastOneFieldFilled`, `NotAllFieldsFilled`,
 * `FieldsNotCollectivelyFilled`, `NotExactlyOneFieldFilled`, `MoreThanOneFieldFilled` and
 * `NumberOfFilledFields` — expand the group to its fields and quantify over them, so the operand is legal
 * there. A family assembled from what the operators are CALLED would refuse those eight; the mirror family
 * records the same trap in the other direction, where the name would have refused five.
 *
 * **Both wildcard gates outrun the kind gate, so this reports only where the starring is already right.**
 * A repetition nothing addresses answers `MVK_NO_WILDCARD`, and a star on a level that does not repeat
 * answers `MVK_INVALID_WILDCARD` — in each case naming the kind would send the author to the wrong repair.
 * The test is therefore that every repeating level on the operand's path is addressed, by its own star, by
 * the level the rule iterates ([KERNEL-FINDINGS KF477]) or by a key the index chain admits ([KF476]), which
 * is silent on a two-level repetition carrying one star rather than guessing which gate answers.
 *
 * **The root group is a third silence**, `MVK_ROOT_GROUP_REFERENCED` answering ahead of the kind class, and
 * **an earlier segment's gate is a fourth**: the `.*` suffix and a starred leading `..*` answer ahead of it too
 * ([earlierSegmentGate]).
 *
 * **A FIELD clears the kind gate and meets the star next**: a correctly starred `Claims*` then `Amount` is
 * `MVK_NO_WILDCARDS_ALLOWED`, because these predicates read one addressed value
 * (`rulekit.validate.laws.GroupOperandRepetitionScopeLawsTest`).
 */
internal fun TrackedFactBase.fieldOperandKindFindings(): List<Finding> = declarations().flatMap { element ->
    fieldOperandKindContributions(element).map { it.finding }
}

internal fun TrackedFactBase.fieldOperandKindContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val carriers = spelledCarriers()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator !in carriers) return@callContributions null
        call.operands.singleOrNull()?.let { operandFinding(it, call, group, element, member) }
    }
}

/** The two scalar field predicates, named by their catalog ids. */
private val ENTITY_FIELD_OPERATORS = setOf(OperatorId.FIELD_FILLED, OperatorId.FIELD_NOT_FILLED)

private fun TrackedFactBase.spelledCarriers(): Set<String> =
    ENTITY_FIELD_OPERATORS.mapNotNull { OPERATOR_SPELLINGS[conditionLanguage]?.get(it) }.toSet()

private fun TrackedFactBase.operandFinding(
    operand: CallOperand,
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val operator = call.operator
    val span = call.spanIn(member)
    val read = operandReferenceIn(operand.text, declaringGroup, element, call.inAFilterBody) { operand.path?.let(::OperandReference) }
        ?: return null
    val path = read.reference
    val resolved = resolveEntity(declaringGroup, path, element.origin) as? Reference.Resolved ?: return null
    val gate = earlierSegmentGate(
        path,
        resolves = { prefix -> resolveEntity(declaringGroup, prefix, element.origin) },
        isRepeatableGroup = { declarationAt(it)?.isRepeatableGroup == true },
    )
    val addressed = addressedTargetOf(read, declaringGroup, element, call.inAFilterBody)
    if (gate != null || addressed == null) {
        return null
    }
    val target = declarationAt(resolved.path, DeclaredKind.GROUP)
        ?: return starredFieldFinding(path, resolved.path, operator, element, member, span)
    if (target.path.isRootGroup()) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_NO_GROUPS_ALLOWED",
        "in `$member`: `$operator` takes one field and this hands it the group `${target.path}` — name a " +
            "field inside it, or use a field-LIST operator, which quantifies over the group's fields",
        element.path,
        span,
    )
}

/** The star gate a FIELD meets after the kind gate, or null where the operand carries no star. */
private fun TrackedFactBase.starredFieldFinding(
    written: String,
    field: String,
    operator: String,
    element: TrackedDeclaration,
    member: String,
    span: TextSpan,
): Finding? {
    if ('*' !in written || declarationAt(field, DeclaredKind.FIELD) == null) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_NO_WILDCARDS_ALLOWED",
        "in `$member`: `$operator` reads one addressed value and `$written` reaches `$field` through a " +
            "repetition star — drop the star to read the current repetition, or use a field-LIST operator",
        element.path,
        span,
    )
}
