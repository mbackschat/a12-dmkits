package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * A repetition wildcard in a GROUP-CONDITION operand, where five of the seven operators refuse one.
 *
 * A12 checks the whole group-condition family in one `switch` and splits it three ways
 * ([KERNEL-FINDINGS KF369], `rulekit.validate.laws.GroupQuantifierWildcardLawsTest`): the two scalar
 * predicates refuse a star with `MVK_NO_WILDCARDS_ALLOWED`, three of the five list predicates refuse it with
 * `MVK_NO_WILDCARDS_G_ALLOWED`, and `AtLeastOneGroupFilled` and `NoGroupFilled` run no wildcard gate at all.
 * The last pair is what makes this a measurement rather than a name-driven rule: they are spelled like the
 * three that refuse and take the same operand list, so a family assembled from what the operators are CALLED
 * would over-reject two of seven — the one direction this project forbids.
 *
 * **The class turns on the SLOT, never on where the star lands**, which is why this is not part of
 * [repetitionWildcardFindings]: that family answers a star on something that cannot repeat and its class is
 * fixed by the landing site. Here the landing site is a perfectly legal repeatable group and the operator
 * alone picks the class — the same star is admitted in a count, in an aggregate and in the two ungated
 * predicates.
 *
 * **A field operand does not escape.** `EntityRef.hasWildCardsInGroup` reads a field's own repetition index
 * as exempt, so a star one level down could plausibly be admitted; it is not, measured, so the test is on
 * the reference and not on the operand's kind.
 *
 * **The gate runs inside a `Having` filter too**, measured on the discriminating class rather than assumed:
 * `MVK_NO_WILDCARDS_G_ALLOWED` is a class no filter gate can produce, so a quantifier nested in a filter is
 * answered by its own gate and this family may report there.
 *
 * **Silent unless every starred segment lands on a group A12 agrees repeats, and the whole reference
 * resolves.** A star on a non-repeatable level is [repetitionWildcardFindings]' class and outruns this one;
 * an unresolvable leaf is unmeasured in this position, so silence is the safe answer.
 */
internal fun TrackedFactBase.groupConditionWildcardFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val gates = WILDCARD_GATES.mapNotNull { (id, arm) -> keywords[id]?.let { it to arm } }.toMap()
    if (gates.isEmpty()) {
        return emptyList()
    }
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member)).mapNotNull { call ->
                val arm = gates[call.operator] ?: return@mapNotNull null
                val rootSilencesIt = arm == GroupConditionArm.LIST &&
                    call.operands.any { namesARootGroup(it.path, group, element) }
                if (rootSilencesIt) {
                    return@mapNotNull null
                }
                val starred = call.operands.firstOrNull { starsARepetition(it.path, group, element) }
                    ?: return@mapNotNull null
                wildcardFinding(arm, call.operator, starred.path.orEmpty(), element, member)
            }
        }
    }
}

/** The two arms of A12's switch that refuse a star, each with the class it draws and why it draws it. */
private enum class GroupConditionArm(val code: String, val because: String) {
    /** One group, one repetition — a star would name the whole list where the slot holds a single row. */
    SCALAR("MVK_NO_WILDCARDS_ALLOWED", "takes one group and reads a single repetition of it"),

    /** Quantification over the OPERAND LIST, which is a different list from a group's repetitions. */
    LIST("MVK_NO_WILDCARDS_G_ALLOWED", "quantifies over the groups you list, never over one group's rows"),
}

/**
 * Which operators sit on which arm, keyed by catalog id.
 *
 * Resolved to the model's own spelling before use, because a German-language condition writes
 * `AlleKontexteAngegeben` and a literal English keyword is a legal FIELD name in such a model.
 * `AtLeastOneGroupFilled`, `NoGroupFilled` and `NumberOfFilledGroups` are deliberately absent: A12 runs no
 * wildcard gate for them and admits the same star.
 */
private val WILDCARD_GATES = mapOf(
    "GroupFilled" to GroupConditionArm.SCALAR,
    "GroupNotFilled" to GroupConditionArm.SCALAR,
    "AllGroupsFilled" to GroupConditionArm.LIST,
    "GroupsNotCollectivelyFilled" to GroupConditionArm.LIST,
    "NotAllGroupsFilled" to GroupConditionArm.LIST,
)

/**
 * Whether [path] names a root group, which silences the LIST arm and only that arm.
 *
 * `checkNotRootGroupReferenced` runs ahead of the wildcard gate for those three operators, so A12 answers a
 * root operand with `MVK_ROOT_GROUP_REFERENCED` however it is starred — and it checks the WHOLE operand
 * list first, so one root operand silences the call rather than only itself. The two scalar predicates have
 * no root gate in an A12 model and do draw the wildcard class on a starred root, measured.
 */
private fun TrackedFactBase.namesARootGroup(
    path: String?,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean {
    val written = path ?: return false
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return false
    return resolved.path.isRootGroup()
}

/** Whether [path] stars at least one level, and every level it stars is one A12 agrees repeats. */
private fun TrackedFactBase.starsARepetition(
    path: String?,
    declaringGroup: String,
    element: TrackedDeclaration,
): Boolean {
    val written = path?.takeIf { it.contains('*') } ?: return false
    if (resolveEntity(declaringGroup, written.replace("*", ""), element.origin) !is Reference.Resolved) {
        return false
    }
    val segments = written.split('/')
    val starredPrefixes = segments.indices
        .filter { segments[it].endsWith("*") }
        .map { segments.take(it + 1).joinToString("/").replace("*", "") }
    return starredPrefixes.isNotEmpty() && starredPrefixes.all { prefix ->
        val resolved = resolveEntity(declaringGroup, prefix, element.origin) as? Reference.Resolved
        resolved != null && repeatableGroups().any { it.path == resolved.path }
    }
}

private fun wildcardFinding(
    arm: GroupConditionArm,
    operator: String,
    written: String,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    arm.code,
    "in `$member`: `$operator` ${arm.because}, and this stars `$written` — drop the `*`, or count the " +
        "filled repetitions with `NumberOfFilledGroups`",
    element.path,
)
