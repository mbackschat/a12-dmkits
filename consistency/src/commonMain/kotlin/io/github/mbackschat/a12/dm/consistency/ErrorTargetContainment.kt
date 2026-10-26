package io.github.mbackschat.a12.dm.consistency


/**
 * A rule or computation that ITERATES must write into a field at or below its own declaring group.
 *
 * A12 derives a construct's iteration from what it reads, and a construct that iterates has to say which
 * repetition its result belongs to. Containment is how it says so, and it is containment rather than
 * parenthood — an ancestor of the written field satisfies it, and crossing a ROOT boundary is not itself a
 * refusal ([KERNEL-FINDINGS KF212], `rulekit.validate.laws.NonIteratingComputationFreedomLawsTest`).
 *
 * **The two constructs are NOT alike on what derives the iteration, and that is the family's whole risk.**
 * A computation's per-row COMPUTED FIELD derives it on its own, with a bare constant for an operation; a
 * rule's per-row ERROR FIELD derives none. Both halves were measured across the same 2×2 — construct against
 * the declaring group's own repeatability — because the two published sets that disagreed about it had moved
 * both variables at once and neither could say which carried the difference
 * (`rulekit.validate.laws.ErrorTargetContainmentLawsTest`, 2026-09-15).
 *
 * **A star aggregate reads no row only when it stars EVERY repeatable level it crosses.** Without that the
 * family would refuse every aggregate rule reporting on a field outside its group — the discriminator between
 * "a repeatable group appears in the path" and "the construct reads one row at a time", and only the second
 * is A12's. A level the star leaves unstarred is read one row at a time, on either side of the first star,
 * whether or not the declaring group binds it, and on both constructs
 * (`rulekit.validate.laws.PartialStarContainmentLawsTest`, [KERNEL-FINDINGS KF466]).
 *
 * **This gate answers FIRST**, which is why it carries none of the preconditions its neighbours do. It
 * pre-empts `MVK_ERROR_FIELDGROUP_ITERATION{4,5}_INVALID` ([KERNEL-FINDINGS KF372]), the wildcard classes
 * that answer the same read where the written field IS contained, and `MVK_ERROR_FIELD_NOT_REFERENCED` — so
 * unlike [errorTargetIterationFindings] it needs no reaching reference, and unlike the operand families it
 * does not care whether the extent that is read declares an index field.
 *
 * **Beside an unresolvable name, placed rather than silent — where it has a read to be placed at.** A12
 * raises the class at the per-row read, so an unresolvable name after that read loses to it and one before it
 * wins, measured both ways (`rulekit.validate.laws.ConditionClassWithholdingLawsTest
 * .aGateIsRaisedWhereItsOwnProductionEnds`); the finding carries the read's span and report assembly does the
 * ordering. With no read — a computed field that iterates on its own — there is no place in a text to raise it
 * from and A12's verdict beside a bad name is unmeasured, so that arm stays silent: under-reporting is the
 * safe direction, naming a second fix for one typo is not.
 *
 * The shape reaches a checker only as stored DM-JSON: `Rule.Builder.build()` refuses an uncontained error
 * field outright, on a check measured OVER-REJECTING and kept deliberately (MVK-LEDGER, FEATURE-GAPS §4).
 * That refusal is geometry alone, so it is NOT the predicate here — copying it would refuse the models A12
 * admits, which is exactly what it does on the authoring path.
 */
internal fun TrackedFactBase.errorContainmentContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val declared = element.declaredReference() ?: return emptyList()
    val target = (declared.target as? Reference.Resolved)?.path ?: return emptyList()
    val group = element.path.substringBeforeLast('/')
    if (target == group || target.startsWith("$group/")) return emptyList()
    return entityContributions(element, EntityCheck.ADDRESS) { entity, span ->
        if (entity.inFilter && !entity.outerMarked)
            return@entityContributions null
        val written = entity.writtenPath
        val reference = ConditionReference(element.path, span.member, written,
            resolveEntity(group, written, element.origin), span)
        if (!readsOneRowAtATime(reference, group, element, entity.indexed)) return@entityContributions null
        containmentFinding(declared, target, group, reference)
    }
}

/**
 * Whether [reference] reads ONE ROW of some repeatable level — the only kind of read that derives iteration.
 *
 * A star makes its level an aggregate over the extent rather than a read within it, and the authored
 * spelling is where that shows: the resolved path carries no star, so a family reading only the path cannot
 * tell the two apart and would refuse every aggregate. Selection likewise replaces only its indexed level
 * (SelectedContainmentAgreementTest, KF488); a read is per-row when any ordinary level remains. A star that lands on a level that does not repeat is `MVK_INVALID_WILDCARD`, which
 * A12 answers alone (`rulekit.validate.laws.PartialStarContainmentLawsTest`), so such a reference is the
 * wildcard family's and reads no row here.
 */
private fun TrackedFactBase.readsOneRowAtATime(
    reference: ConditionReference,
    declaringGroup: String,
    element: TrackedDeclaration,
    indexed: Boolean,
): Boolean {
    val path = (reference.target as? Reference.Resolved)?.path ?: return false
    return ordinaryRepeatableLevels(reference.written, path, declaringGroup, element, indexed)?.isNotEmpty() == true
}

/**
 * @param read the per-row reference, absent when the computed field's own scope is what iterates — the two
 *     have different repairs, so the explain names which one A12 is answering. A12 raises the class while it
 *     checks that reference's indices, so the finding takes its span; with no such read there is no place in
 *     a text to give it.
 */
private fun containmentFinding(
    declared: DeclaredReference,
    written: String,
    group: String,
    read: ConditionReference?,
): Finding {
    val because = read?.let { "its condition reads `${it.written}` one row at a time" }
        ?: "`$written` itself repeats, so writing it means writing one row"
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
        "this declaration writes `$written`, which lies outside its own group `$group`, while $because — " +
            "A12 cannot then say which repetition the result belongs to. Declare it in a group that " +
            "CONTAINS `$written` (an ancestor will do), or write into a field under `$group`",
        declared.declaredAt,
        span = read?.span,
    )
}
