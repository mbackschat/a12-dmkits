package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * A GROUP handed to one of the two field predicates that take an entity rather than a list.
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
 * A repetition read without a star answers `MVK_NO_WILDCARD`, and a star on a level that does not repeat
 * answers `MVK_INVALID_WILDCARD` — in each case naming the kind would send the author to the wrong repair.
 * The test is therefore that the operand's star COUNT matches the number of repeating levels on its path,
 * which is silent on a two-level repetition carrying one star rather than guessing which gate answers.
 *
 * **The root group is a third silence**, `MVK_ROOT_GROUP_REFERENCED` answering ahead of the kind class.
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
                    call.operands.firstNotNullOfOrNull { operand ->
                        groupFinding(operand.path, call.operator, group, element, member)
                    }
                }
        }
    }
}

/** The two scalar field predicates, named by their catalog ids. */
private val ENTITY_FIELD_OPERATORS = setOf("FieldFilled", "FieldNotFilled")

private fun TrackedFactBase.spelledCarriers(): Set<String> =
    ENTITY_FIELD_OPERATORS.mapNotNull { CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(it) }.toSet()

private fun TrackedFactBase.groupFinding(
    written: String?,
    operator: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    val path = written ?: return null
    val resolved = resolveEntity(declaringGroup, path, element.origin) as? Reference.Resolved ?: return null
    val target = declarationAt(resolved.path, DeclaredKind.GROUP)
        ?: return null
    if (target.path.isRootGroup()) {
        return null
    }
    if (path.count { it == '*' } != repeatingLevelsOnThePathTo(target.path)) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_NO_GROUPS_ALLOWED",
        "in `$member`: `$operator` takes one field and this hands it the group `${target.path}` — name a " +
            "field inside it, or use a field-LIST operator, which quantifies over the group's fields",
        element.path,
    )
}
