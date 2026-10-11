package io.github.mbackschat.a12.dm.consistency

/**
 * A rule whose condition names its error field only through a ROOT group, while also reading a repetition.
 *
 * A12 has to decide WHICH repetition of the error field a violation attaches to. A condition naming the field
 * itself, or a group containing it below the root, is asked level by level whether it reads that repetition
 * ([errorFieldIterationBindingFindings]); one naming it only through the root has not said, and once such a
 * condition also reads a repeating field the rule is refused
 * ([KERNEL-FINDINGS KF348], `rulekit.validate.laws.RootOnlyErrorTargetIterationLawsTest`).
 *
 * **Two classes, and which one fires is the read's CARRIER rather than anything about the error field.** A
 * read in the error field's OWN group draws `MVK_ERROR_FIELDGROUP_ITERATION4_INVALID`; a read under a
 * repeatable group PARALLEL to it draws `MVK_ERROR_FIELDGROUP_ITERATION5_INVALID`. With both present the
 * parallel class wins — measured in BOTH text orders, because one order cannot tell that from "the later
 * read wins", which is how a mechanism shipped wrong two days earlier ([KF340]).
 *
 * **These classes need ROOT-group references alone to name the target.** `IterationChecker` takes this arm only
 * when no direct reference and no non-root containing group names the error field; any such reference sends the
 * rule to the per-level binding check instead, which [errorFieldIterationBindingFindings] owns. So the rule's own
 * group "pins" only because, in the first grids, it was the repeatable group itself: named at the ROOT it pins
 * nothing and these classes answer, while a non-root group above the repetition draws the binding class
 * (`rulekit.validate.laws.RootOnlyErrorTargetIterationLawsTest.onlyARootGroupReferenceLeavesTheErrorFieldUnpinned`).
 *
 * **The root's own repeatability is NOT the variable**, which had to be measured because it was constant
 * across the whole first grid: with a non-repeatable root the two classes fire exactly as before, so "the
 * reaching reference is the ROOT" and "the reaching reference is itself an unpinned repetition" — the same
 * thing on nine rows — are separated, and this family keys on the former.
 *
 * **And the target has to lie INSIDE the rule's own group**, which was the fourth constant of the grid rather
 * than one of its variables: A12's containment gate throws during the parse, where both these classes come
 * from a pass over the finished reference set, so neither is reachable with the target outside
 * ([KERNEL-FINDINGS KF372]). The shape arrives only as stored DM-JSON, our own builder refusing the pair —
 * and it is not unreported: [errorContainmentContributions] owns it, which is why this family's silence
 * there costs an author nothing.
 *
 * **Only the rule's own ITERATING reads count**, which is what `IterationChecker` decides over: it keeps a
 * reference created with the filter flag off or carrying the `$` marker, and asks for an ITERATION index, which
 * a star is not, nor is the level replaced by a semantic index. Ordinary ancestors of a selected payload
 * and a field-valued key still count (`SelectedAuthoredScopeDiffTest`). A read with no remaining ordinary
 * level and an unmarked filter-body read are admitted; a marked body read of the error field's group is
 * the own-group class
 * (`adapter.laws.CheckerRuleReferenceSetAgreementTest`, [KERNEL-FINDINGS KF488]). The same set decides what
 * reaches and pins the target.
 *
 * **Silent wherever a reading could become a refusal.** A condition naming the rule-context keyword is left
 * alone: the keyword carries no path, so whether it pins here is unmeasured, and `MVK_ERROR_FIELD_NOT_REFERENCED`
 * already treats it as reaching. An unresolvable operand pre-empts A12's own gate, so reporting would name a
 * second fix for one typo. A computation carries no error field and is not judged at all.
 */
internal fun TrackedFactBase.errorTargetIterationFindings(): List<Finding> =
    declarations().flatMap { errorTargetIterationFindings(it) }

private fun TrackedFactBase.errorTargetIterationFindings(element: TrackedDeclaration): List<Finding> {
    val declared = element.declaredReference()?.takeIf { it.role == ReferenceRole.ERROR_TARGET }
        ?: return emptyList()
    val target = (declared.target as? Reference.Resolved)?.path ?: return emptyList()
    val references = conditionReferencesOf(element)
    if (references.isEmpty() || references.any { it.target !is Reference.Resolved } ||
        namesTheRulesOwnGroup(element)
    ) return emptyList()
    val rulesOwn = rulesOwnReferences(element).let { own -> references.filter { it.written in own } }
    val ruleGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    // KF372: a target outside the declaring group is refused before the iteration pass.
    if (!(target == ruleGroup || target.startsWith("$ruleGroup/"))) return emptyList()
    val reaching = rulesOwn.filter { it.reaches(target) }
    if (reaching.isEmpty() || reaching.any { !(it.target as Reference.Resolved).path.isRootPath() }) return emptyList()
    val ordinaryLevels = rulesOwnIterationLevels(element) ?: return emptyList()
    val read = rulesOwn.mapNotNull { reference ->
        val levels = ordinaryLevels[reference.written].orEmpty().filterNot { it.isRootPath() }.toSet()
        (reference.target as? Reference.Resolved)?.path?.takeIf { levels.isNotEmpty() }?.let { it to levels }
    }
    val errorFieldGroup = target.substringBeforeLast('/', missingDelimiterValue = "")
    if (!repeats(errorFieldGroup)) return emptyList()
    return listOfNotNull(iterationFinding(read, target, errorFieldGroup, element.path))
}

/**
 * The one class this rule draws, parallel first because that is the order A12 reports in.
 *
 * @param read every path the rule's own reads iterate, the reaching reference included — it is harmless here,
 *     since a reference that reaches the target is excluded from the own-group arm by [reaches] and cannot
 *     be parallel to the group it contains.
 */
private fun TrackedFactBase.iterationFinding(
    read: List<Pair<String, Set<String>>>,
    target: String,
    errorFieldGroup: String,
    at: String,
): Finding? {
    val parallel = read.firstOrNull { (_, levels) ->
        levels.any { level -> errorFieldGroup != level && !errorFieldGroup.startsWith("$level/") }
    }?.first
    if (parallel != null) {
        return finding(
            "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID",
            "this rule reports on `$target` but its condition names that field only through its root group, " +
                "while reading `$parallel` — which repeats independently of the error field, so A12 cannot " +
                "say which repetition the error belongs to",
            at,
        )
    }
    val own = read.firstOrNull { (path, _) -> path.startsWith("$errorFieldGroup/") && path != target }?.first
        ?: return null
    return finding(
        "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID",
        "this rule reports on `$target` but its condition names that field only through its root group, " +
            "while reading `$own` from the repeating group the error field itself sits in — reading the " +
            "error field, or its repeating group, unstarred tells A12 which repetition is meant",
        at,
    )
}

private fun finding(code: String, explain: String, at: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at)

/** Whether [path] names a group A12 gives repetitions — the ambiguity this family reports needs one. */
private fun TrackedFactBase.repeats(path: String): Boolean =
    repeatableGroups().any { it.path == path }

internal fun TrackedFactBase.errorTargetIterationContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = execution?.postParse(PostParseCheck.ERROR_TARGET_ITERATION) {
    errorTargetIterationFindings(element)
}.orEmpty()
