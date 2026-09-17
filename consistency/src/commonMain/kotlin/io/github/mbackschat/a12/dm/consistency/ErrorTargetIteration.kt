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
 * and it is not unreported: [errorTargetContainmentFindings] owns it, which is why this family's silence
 * there costs an author nothing.
 *
 * **Silent wherever a reading could become a refusal.** A condition naming the rule-context keyword is left
 * alone: the keyword carries no path, so whether it pins here is unmeasured, and `MVK_ERROR_FIELD_NOT_REFERENCED`
 * already treats it as reaching. An unresolvable operand pre-empts A12's own gate, so reporting would name a
 * second fix for one typo. A computation carries no error field and is not judged at all.
 */
internal fun TrackedFactBase.errorTargetIterationFindings(): List<Finding> {
    val named = conditionReferences().groupBy { it.declaredAt }
    return declaredReferences()
        .filter { it.role == ReferenceRole.ERROR_TARGET }
        .mapNotNull { declared ->
            val target = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
            val references = named[declared.declaredAt].orEmpty()
            if (references.isEmpty() ||
                references.any { it.target !is Reference.Resolved } ||
                namesTheRulesOwnGroup(declared)
            ) {
                return@mapNotNull null
            }
            val ruleGroup = declared.declaredAt.substringBeforeLast('/', missingDelimiterValue = "")
            // A target OUTSIDE the rule's group is `MVK_ERROR_FIELD_NOT_IN_RULEGROUP`, drawn at the top of
            // `checkIndicesForA12Model` on the first iterating level — where these two classes come from
            // `IterationChecker`, which runs after the parse. Every row of both grids that measured them
            // held the target inside the declaring group, so the containment demand was a constant of the
            // set rather than something any row tested; measured 2026-09-14 (KERNEL-FINDINGS KF372), and
            // the reason this shape is silent HERE — `ERROR_TARGET_CONTAINMENT` reports it. It reaches a
            // checker only through stored DM-JSON, `Rule.build()` refusing the pair up front.
            if (!(target == ruleGroup || target.startsWith("$ruleGroup/"))) {
                return@mapNotNull null
            }
            // REACHED-but-not-pinned is the whole shape. A condition that reaches the target from at or
            // below the rule's group has pinned the repetition; one that does not reach it AT ALL is
            // `MVK_ERROR_FIELD_NOT_REFERENCED`'s, and A12 draws that class alone there. Every row of the
            // first grid carried a reaching reference, so the two silences looked like one until the
            // reference family's own mirror went red on a condition naming a sibling field.
            if (references.none { it.reaches(target) } ||
                references.any { it.reaches(target) && it.sitsWithin(ruleGroup) }
            ) {
                return@mapNotNull null
            }
            val read = references.mapNotNull { (it.target as? Reference.Resolved)?.path }
            val errorFieldGroup = target.substringBeforeLast('/', missingDelimiterValue = "")
            // There has to BE a repetition for A12 to be unable to choose one. Measured on a flat model:
            // a rule reaching its error field from above while reading a sibling is ADMITTED when nothing
            // repeats. The error field's group repeats on every row of the two grids above, so this was a
            // constant rather than a condition — and the family refused an ordinary two-field rule in the
            // variant-filter family's own fixture before the cell existed.
            if (!repeats(errorFieldGroup)) {
                return@mapNotNull null
            }
            iterationFinding(read, target, errorFieldGroup, declared.declaredAt)
        }
}

/**
 * The one class this rule draws, parallel first because that is the order A12 reports in.
 *
 * @param read every path the condition resolves to, the reaching reference included — it is harmless here,
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
    allDeclarations().any { candidate ->
        candidate.isRepeatableGroup &&
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
    allDeclarations().any { it.path == path && it.isRepeatableGroup }
