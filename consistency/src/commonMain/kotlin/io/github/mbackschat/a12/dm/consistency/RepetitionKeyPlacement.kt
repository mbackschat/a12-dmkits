package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE

/**
 * Where a `RepetitionNotUnique` KEY has to sit relative to the rule that names it.
 *
 * The operator asks whether a repeating element takes the same key twice, so A12 demands a repetition to ask
 * it about: every key must have a repeatable group strictly BETWEEN the rule's own group and itself, and
 * `MVK_REPEATABLE_GROUP_MISSING` is what A12 draws when one does not ([KERNEL-FINDINGS KF347],
 * `rulekit.validate.laws.RepetitionNotUniqueKeyPathLawsTest`).
 *
 * **The class COLLAPSES shapes that read as different defects**, which is why the family keys on the
 * relation and not on any of them: a sole key in a non-repeatable group, a second key in one, and the rule
 * group sitting AT the repeated group rather than above it all draw it, the last in both the absolute and
 * the relative key spelling.
 *
 * **An OUTSIDE key pre-empts it, and that is the half a reading gets wrong.** With the rule group at
 * `/Basket/Lines`, the key `Sku` has no repetition between it and the rule group and draws the class — but
 * add a second key outside that group and A12 reports the outside key instead, never this class. Which class
 * it reports then depends on the outsider's own carrier, measured at both cells: a parallel REPEATABLE group
 * draws `MVK_NO_WILDCARD`, a non-repeatable one draws `MVK_NOT_IN_RULEGROUP`. Two readings fitted every row
 * that existed before those cells — "a second key of any carrier pre-empts" and "only an outside one does" —
 * and they disagree exactly here, so the grid was built to separate them rather than to add rows to the
 * account it already had. This family therefore says nothing at all once any key lies outside the rule
 * group: both classes belong to families that do not exist yet, and guessing between them would attach one
 * fix to a defect that has another.
 *
 * **A repeatable ROOT is the one rule group that needs no repetition below it**, and only for keys that sit
 * directly in it: `RepetitionNotUnique(Plate, Vin)` at the repeatable root `/Fleet` is admitted, while a key in a
 * fixed subgroup of it draws the class, alone or beside a direct key
 * (`rulekit.validate.laws.RepetitionNotUniquePairedGateLawsTest`).
 *
 * **DEPTH does not matter, measured rather than assumed.** A repeatable group two levels down suppresses the
 * class as a direct child does, and a non-repeatable group two levels down draws it just the same — so the
 * test is "is there a repeatable group on the path between", never "is the key's own parent repeatable".
 *
 * **The multiplicity is ONE finding per call, whatever the keys do.** Two keys each lacking a repetition draw
 * a single class from A12, so this is a verdict on the call rather than on a key, and the message names every
 * key that fails rather than being emitted once per key.
 *
 * **Rules only, and not as a scope limit.** A12's calculation module refuses `RepetitionNotUnique` in a
 * computation's precondition outright ([KERNEL-FINDINGS KF333], reported by [computationPreconditionFeatureFindings]
 * as `MVK_ERROR_INVALID_LANGUAGE_FEATURE`), so a computation carrying one has a different defect and this
 * family would be a second opinion about a condition A12 never reaches.
 */
internal fun TrackedFactBase.repetitionKeyPlacementFindings(): List<Finding> =
    declarations().filter { it.kind == "Rule" }.flatMap { repetitionKeyPlacementContributions(it).map { contribution -> contribution.finding } }

internal fun TrackedFactBase.repetitionKeyPlacementContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (element.kind != "Rule") return emptyList()
    val spelling = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(REPETITION_NOT_UNIQUE) ?: return emptyList()
    val group = element.path.substringBeforeLast('/')
    return callContributions(element, execution) { member, call ->
        if (call.operator == spelling) placementFinding(call, group, element, member) else null
    }
}

/**
 * The one finding this call draws, or null for every shape the grid left to A12.
 *
 * Silent until the key list passes its own gates ([repetitionKeyListIsSound]) — a key that does not read, a
 * duplicate, a category and the four key-list classes all answer ahead of this branch
 * ([KERNEL-FINDINGS KF355]) — and on a SCOPED list, whose last key carries the `@From` clause and so reduces
 * to no bare path: the scope selects the other branch.
 */
private fun TrackedFactBase.placementFinding(
    call: ConditionCall,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    if (!repetitionKeyListIsSound(call, declaringGroup, element)) {
        return null
    }
    val keys = call.operands.map { operand ->
        (resolveEntity(declaringGroup, operand.path ?: return null, element.origin) as? Reference.Resolved)?.path
            ?: return null
    }
    // MEASURED: a key outside the rule group hands the verdict to the outsider's own class, never this one.
    if (keys.any { !it.startsWith("$declaringGroup/") }) {
        return null
    }
    // MEASURED: at a repeatable root, keys that all sit directly in it need no repetition between, since the
    // root's own rows are the ones compared; one key in a subgroup brings the gate back for the whole call.
    if (isRepeatableRoot(declaringGroup) && keys.all { it.lastIndexOf('/') == declaringGroup.length }) {
        return null
    }
    val unrepeated = keys.filterNot { key -> repeatableLiesBetween(declaringGroup, key) }
    if (unrepeated.isEmpty()) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_REPEATABLE_GROUP_MISSING",
        "this $member asks `${call.operator}` about ${unrepeated.joinToString(", ") { "`$it`" }}, which " +
            "no repeatable group separates from `$declaringGroup` — the operator compares one repetition " +
            "against another, so each key needs a repeating group between the rule's own group and itself",
        element.path,
        TextSpan(member, call.at, call.end),
    )
}

/** Whether [group] is a root group that repeats. */
private fun TrackedFactBase.isRepeatableRoot(group: String): Boolean =
    group.lastIndexOf('/') == 0 && repeatableGroups().any { it.path == group }

/**
 * Whether a repeatable group sits strictly between [declaringGroup] and [key].
 *
 * Strictly on both ends: the declaring group itself does not count however repeatable it is — that is the
 * measured "rule group is the repeated group" row — and neither does anything at or below the key.
 */
private fun TrackedFactBase.repeatableLiesBetween(declaringGroup: String, key: String): Boolean =
    repeatableGroups().any { candidate ->
        candidate.path.startsWith("$declaringGroup/") && key.startsWith("${candidate.path}/")
    }
