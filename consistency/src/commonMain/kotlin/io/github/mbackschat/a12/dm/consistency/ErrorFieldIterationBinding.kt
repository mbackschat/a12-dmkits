package io.github.mbackschat.a12.dm.consistency

/**
 * That every repetition a rule's condition iterates is also iterated by a reference naming its error field.
 *
 * A rule reading a field per row runs once per row, and A12 has to attach each violation to that row's error
 * field. So once some reference records an ITERATION at a repetition level of the error field's path, one of
 * the references NAMING the error field — the field itself or a group containing it — must record it too, or
 * the rule is refused with `MVK_ERROR_FIELD_AND_ITERATION_INVALID` ([KERNEL-FINDINGS KF417],
 * `rulekit.validate.laws.IterationBindingLawsTest`, `…RootRepetitionIterationAgreementLawsTest`).
 *
 * **A star names the error field without binding it.** A starred level records a wildcard, never an iteration,
 * so `FieldFilled(Claims/Reserve) And Sum(Claims*\/Amount) > 0` reporting on `Claims/Amount` satisfies the
 * referenced-ness gate and is still refused, while the error field's own per-row guard admits it. The plain
 * list admits because nothing iterates, and a computation has no error field to bind.
 *
 * **Only the rule's own ordinary levels iterate**: references outside filters or carrying `$`. A selection
 * replaces its indexed level, retaining ordinary ancestors and the field-valued key's own levels
 * (`SelectedAuthoredScopeDiffTest`; [KERNEL-FINDINGS KF488], [KF205]).
 *
 * **Two arms, and the second is narrower.** When the field or a non-root group names the error field
 * (`checkIteration4NotOnlyVRefs`), every repeatable level on the error field's path is asked. When only its
 * ROOT group names it (`checkVRefs`), A12 first answers a deeper iteration with the classes
 * [errorTargetIterationFindings] owns and asks this one at the root level alone, so this family speaks there
 * only when the root is the one level iterated — the row measured on a repeatable root.
 *
 * **A parallel iteration is bound only in parallel** ([parallelBindingFindings], [KERNEL-FINDINGS KF597]).
 *
 * **Silent wherever another class answers first, or the shape is unmeasured.** An iteration of a level OFF the
 * error field's path that the parallel marking does not reach is the wildcard classes' business; an unresolved
 * operand pre-empts the whole pass; the rule-context keyword names the rule's group without a path; and a GROUP error target is left
 * alone — the one measured row, a starred group beside a per-row read, is admitted, and which of its own levels
 * A12 asks is not known beyond it (`adapter.laws.CheckerIterationBindingAgreementTest`).
 */
internal fun TrackedFactBase.errorFieldIterationBindingFindings(element: TrackedDeclaration): List<Finding> {
    val declared = element.declaredReference()?.takeIf { it.role == ReferenceRole.ERROR_TARGET }
        ?: return emptyList()
    val target = (declared.target as? Reference.Resolved)?.path ?: return emptyList()
    if (declarationAt(target)?.declaredKind != DeclaredKind.FIELD) return emptyList()
    val references = conditionReferencesOf(element)
    if (references.isEmpty() || references.any { it.target !is Reference.Resolved } ||
        namesTheRulesOwnGroup(element)
    ) return emptyList()
    val own = rulesOwnReferences(element)
    val ordinaryLevels = rulesOwnIterationLevels(element) ?: return emptyList()
    val iterating = references.filter { it.written in own }.associateWith { reference ->
        ordinaryLevels[reference.written].orEmpty()
    }
    val levels = iteratedLevels(target)
    val naming = iterating.keys.filter { it.reaches(target) }
    if (naming.isEmpty()) return emptyList()
    if (iterating.values.flatten().any { it !in levels }) {
        return parallelBindingFindings(element, target, iterating, naming, levels) ?: emptyList()
    }
    val asked = if (naming.all { (it.target as Reference.Resolved).path.isRootPath() }) {
        val root = levels.firstOrNull()?.takeIf { it.isRootPath() } ?: return emptyList()
        if (iterating.values.flatten().any { it != root }) return emptyList()
        listOf(root)
    } else {
        levels
    }
    val unbound = asked.firstOrNull { level ->
        iterating.values.any { level in it } && naming.none { level in iterating.getValue(it) }
    } ?: return emptyList()
    val by = iterating.entries.first { unbound in it.value }.key.written
    return listOf(bindingFinding(element, unbound, by, target))
}

/**
 * The parallel arm: a rule iterating a level off its error field's path iterates it in parallel, A12 then
 * marks every keyed level on that path parallel too, and a reference naming the error field has to be among
 * those marked. A star leaves its level a wildcard, which neither pass marks, so `Demand*\/Headcount` beside
 * `Supply/Headcount` names the error field and binds nothing (`IterationChecker.checkIteration4NotOnlyVRefs`,
 * kernel 30.8.1, read 2026-10-22; [KERNEL-FINDINGS KF597], `adapter.laws.CheckerIterationBindingAgreementTest`).
 *
 * Null wherever this reading has no verdict: an off-path level the parallel marking does not reach is another
 * class's business, and so is a rule only a root group names, whose arm asks the parallel question differently.
 */
private fun TrackedFactBase.parallelBindingFindings(
    element: TrackedDeclaration,
    target: String,
    iterating: Map<ConditionReference, Set<String>>,
    naming: List<ConditionReference>,
    levels: List<String>,
): List<Finding>? {
    if (naming.all { (it.target as Reference.Resolved).path.isRootPath() }) return null
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val anchor = anchorOf(target) ?: return null
    val marks = iterating.keys.associateWith { reference ->
        markedLevelsOf(reference.written, declaringGroup, anchor, element) ?: return null
    }
    val joint = iterating.entries.firstOrNull { (reference, iterated) ->
        iterated.any { it !in levels && it in marks.getValue(reference).joint }
    } ?: return null
    if (iterating.any { (reference, iterated) -> iterated.any { it !in levels && it !in marks.getValue(reference).joint } }) {
        return null
    }
    if (naming.any { reference -> iterating.getValue(reference).any { it in marks.getValue(reference).marked } }) {
        return emptyList()
    }
    val level = joint.value.first { it !in levels }
    return listOf(bindingFinding(element, level, joint.key.written, target))
}

private fun bindingFinding(element: TrackedDeclaration, level: String, by: String, target: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_ERROR_FIELD_AND_ITERATION_INVALID",
    "this rule runs once per repetition of `$level` because it reads `$by` per row, but no reference " +
        "naming its error field `$target` reads that repetition per row — a star names the field without " +
        "saying which row; read the error field itself, unstarred, in the condition",
    element.path,
)

/** Whether [this] names a root group: one segment below the model root. */
internal fun String.isRootPath(): Boolean = lastIndexOf('/') == 0

internal fun TrackedFactBase.errorFieldIterationBindingContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = execution?.postParse(PostParseCheck.ERROR_TARGET_ITERATION) {
    errorFieldIterationBindingFindings(element)
}.orEmpty()
