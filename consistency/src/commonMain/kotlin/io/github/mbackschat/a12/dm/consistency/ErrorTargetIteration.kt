package io.github.mbackschat.a12.dm.consistency

/**
 * A rule whose condition reaches its error field only from ABOVE, while also reading a repetition.
 *
 * A12 has to decide WHICH repetition of the error field a violation attaches to. A condition that reaches the
 * field through the rule's own group has already said; one that reaches it only through a group ABOVE that
 * has not, and once such a condition also reads a repeating field the rule is refused
 * ([KERNEL-FINDINGS KF348], `rulekit.validate.laws.RootOnlyErrorTargetIterationLawsTest`).
 *
 * **Two classes, and which one fires is the read's CARRIER rather than anything about the error field.** A
 * read in the error field's OWN group draws `MVK_ERROR_FIELDGROUP_ITERATION4_INVALID`; a read under a
 * repeatable group PARALLEL to it draws `MVK_ERROR_FIELDGROUP_ITERATION5_INVALID`. With both present the
 * parallel class wins — measured in BOTH text orders, because one order cannot tell that from "the later
 * read wins", which is how a mechanism shipped wrong two days earlier ([KF340]).
 *
 * **What PINS the repetition is a reference that reaches the target AND sits at or below the rule's group.**
 * That pairing is the whole law and neither half alone gives it: the root group reaches the target on every
 * measured row and pins nothing, while a sibling field sits below the rule's group and reaches nothing. Both
 * the rule's own group and the error field itself pin, and either one admits the rule however much else the
 * condition reads.
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
 * a star is not, nor is a semantic index (source-read). So a starred or keyed read, of the error field's group
 * or a parallel one, and an unmarked filter-body read are admitted, while a marked body read of the error
 * field's group is the own-group class
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
    if (rulesOwn.none { it.reaches(target) } ||
        rulesOwn.any { it.reaches(target) && it.sitsWithin(ruleGroup) }
    ) return emptyList()
    val unkeyed = rulesOwnUnkeyedReferences(element)
    val read = rulesOwn.filter { it.written in unkeyed }.mapNotNull { reference ->
        (reference.target as? Reference.Resolved)?.path?.takeIf { path ->
            unstarredRepeatableLevels(reference.written, path, ruleGroup, element)?.isNotEmpty() == true
        }
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
    read: List<String>,
    target: String,
    errorFieldGroup: String,
    at: String,
): Finding? {
    val parallel = read.firstOrNull { parallelToTheErrorField(it, errorFieldGroup) }
    if (parallel != null) {
        return finding(
            "MVK_ERROR_FIELDGROUP_ITERATION5_INVALID",
            "this rule reports on `$target` but its condition reaches that field only from ABOVE the rule's " +
                "own group, while reading `$parallel` — which repeats independently of the error field, so " +
                "A12 cannot say which repetition the error belongs to",
            at,
        )
    }
    val own = read.firstOrNull { it.startsWith("$errorFieldGroup/") && it != target }
        ?: return null
    return finding(
        "MVK_ERROR_FIELDGROUP_ITERATION4_INVALID",
        "this rule reports on `$target` but its condition reaches that field only from ABOVE the rule's own " +
            "group, while reading `$own` from the repeating group the error field itself sits in — naming " +
            "the rule's own group, or the error field, tells A12 which repetition is meant",
        at,
    )
}

/**
 * Whether [path] repeats independently of the error field — under a repeatable group that does not contain it.
 *
 * The exclusion is what keeps the error field's OWN repetitions, and every level above them, out of this arm:
 * a repeatable ancestor of the error field's group repeats WITH it, so a read beneath one is the other class
 * or no class at all.
 */
private fun TrackedFactBase.parallelToTheErrorField(path: String, errorFieldGroup: String): Boolean =
    repeatableGroups().any { candidate ->
        (path == candidate.path || path.startsWith("${candidate.path}/")) &&
            !(errorFieldGroup == candidate.path || errorFieldGroup.startsWith("${candidate.path}/"))
    }

/** Whether this reference resolves at or below [group] — the half of pinning that [reaches] does not carry. */
private fun ConditionReference.sitsWithin(group: String): Boolean {
    val resolved = (target as? Reference.Resolved)?.path ?: return false
    return resolved == group || resolved.startsWith("$group/")
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
