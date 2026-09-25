package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

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
 * The test is therefore that every repeating level on the operand's path is addressed, by its own star or by
 * the level the rule iterates ([KERNEL-FINDINGS KF477]), which is silent on a two-level repetition carrying
 * one star rather than guessing which gate answers.
 *
 * **The root group is a third silence**, `MVK_ROOT_GROUP_REFERENCED` answering ahead of the kind class, and
 * **an earlier segment's gate is a fourth**: the `.*` suffix and a starred leading `..*` answer ahead of it too
 * ([earlierSegmentGate]).
 *
 * **A FIELD clears the kind gate and meets the star next**: a correctly starred `Claims*` then `Amount` is
 * `MVK_NO_WILDCARDS_ALLOWED`, because these predicates read one addressed value
 * (`rulekit.validate.laws.GroupOperandRepetitionScopeLawsTest`).
 */
internal fun TrackedFactBase.fieldOperandKindFindings(): List<Finding> {
    val carriers = spelledCarriers()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator in carriers }
                .mapNotNull { call ->
                    // A12's arity gate answers ahead of the operand's, so a call with the wrong count is
                    // `EntityListArity`'s alone (`rulekit.validate.laws.EntityListArityLawsTest`).
                    call.operands.singleOrNull()?.let { operand ->
                        operandFinding(operand.path, call, group, element, member)
                    }
                }
        }
    }
}

/** The two scalar field predicates, named by their catalog ids. */
private val ENTITY_FIELD_OPERATORS = setOf("FieldFilled", "FieldNotFilled")

private fun TrackedFactBase.spelledCarriers(): Set<String> =
    ENTITY_FIELD_OPERATORS.mapNotNull { CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(it) }.toSet()

private fun TrackedFactBase.operandFinding(
    written: String?,
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val operator = call.operator
    val span = call.spanIn(member)
    val path = written ?: return null
    val resolved = resolveEntity(declaringGroup, path, element.origin) as? Reference.Resolved ?: return null
    val gate = earlierSegmentGate(
        path,
        resolves = { prefix -> resolveEntity(declaringGroup, prefix, element.origin) },
        isRepeatableGroup = { declarationAt(it)?.isRepeatableGroup == true },
    )
    // No key: this gate's operand is a group, and the shared reader vouches only for a field-valued key.
    val addressed = addressedTargetOf(path, declaringGroup, element, indexSelected = false, call.inAFilterBody)
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
