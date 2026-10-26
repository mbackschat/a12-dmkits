package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext

/**
 * A group named in a condition that carries no field anywhere beneath it.
 *
 * **Declaring such a group is legal and NAMING it is not** ([KERNEL-FINDINGS KF215],
 * `rulekit.validate.laws.EmptyGroupOperandLawsTest`). The model stays valid with the group in it, so this
 * family judges the reference rather than the declaration — a checker reporting the declaration would refuse
 * a model A12 admits.
 *
 * **Emptiness is over the SUBTREE, not the direct children**, which is the row that carries the whole
 * family: a group with no direct field but a populated descendant is admitted in every position an empty one
 * is refused, at any depth. Testing direct children would be an over-rejection.
 *
 * **The class is the carrier's**, as it is for the name laws: a root group answers with
 * `MVK_ROOT_GROUP_IS_EMPTY` where any group below it answers with `MVK_GROUP_IS_EMPTY`.
 *
 * **The law reaches past the group-operand position and PRE-EMPTS what else is wrong there.** Measured in a
 * field-list operator and in an index position, where the populated control is refused for its wildcard and
 * the empty one for its emptiness instead. That is what lets this family judge any reference resolving to a
 * group without knowing which operand position it sits in — the one thing a checker reading condition text
 * cannot recover.
 *
 * **`RuleGroup` is the declaring group here too** ([KERNEL-FINDINGS KF611], [KERNEL-FINDINGS KF618]), so a rule
 * declared in an empty group draws the class through the keyword. At a root group the keyword meets the
 * root-reference class first ([KERNEL-FINDINGS KF611]), which this family leaves to that one.
 */
internal fun TrackedFactBase.emptyGroupContributions(element: TrackedDeclaration): List<ConditionFinding> =
    entityContributions(element, EntityCheck.EMPTY_GROUP) { entity, span ->
        val written = entity.writtenPath
        val declaringGroup = element.path.substringBeforeLast('/')
        val path = if (isRuleContext(written, conditionLanguage)) {
            declaringGroup.takeUnless { it.isRootGroup() } ?: return@entityContributions null
        } else {
            (resolveEntity(declaringGroup, written, element.origin) as? Reference.Resolved)?.path
                ?: return@entityContributions null
        }
        val group = declarationAt(path, DeclaredKind.GROUP) ?: return@entityContributions null
        if (carriesAField(group.path)) return@entityContributions null
        Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR,
            if (group.path.isRootGroup()) "MVK_ROOT_GROUP_IS_EMPTY" else "MVK_GROUP_IS_EMPTY",
            "this ${span.member} names `${group.path}`, a group with no field anywhere beneath it — A12 admits " +
                "the declaration and refuses every condition that names it, so either populate the group or " +
                "name one that carries something", element.path, span)
    }

/**
 * Whether any field is declared at or below [path].
 *
 * A prefix test over the flat element list rather than a walk: the preparation already holds every
 * declaration with its canonical path, and the `/` guard keeps `/Store/Empty` from claiming the fields of a
 * sibling named `/Store/EmptyRows`.
 */
private fun TrackedFactBase.carriesAField(path: String): Boolean = allDeclarations().any {
    it.declaredKind == DeclaredKind.FIELD && it.path.startsWith("$path/")
}
