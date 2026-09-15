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
 * **A STAR aggregate reads no row, so it derives no iteration.** Without that the family would refuse every
 * aggregate rule reporting on a field outside its group — the discriminator between "a repeatable group
 * appears in the path" and "the construct reads one row at a time", and only the second is A12's.
 *
 * **This gate answers FIRST**, which is why it carries none of the preconditions its neighbours do. It
 * pre-empts `MVK_ERROR_FIELDGROUP_ITERATION{4,5}_INVALID` ([KERNEL-FINDINGS KF372]), the wildcard classes
 * that answer the same read where the written field IS contained, and `MVK_ERROR_FIELD_NOT_REFERENCED` — so
 * unlike [errorTargetIterationFindings] it needs no reaching reference, and unlike the operand families it
 * does not care whether the extent that is read declares an index field.
 *
 * **Silent on an unresolvable reference.** A12's verdict there is unmeasured, and every gate past the parse
 * depends on resolution, so the reference family's own class is what an author sees. Under-reporting is the
 * safe direction; naming a second fix for one typo is not.
 *
 * The shape reaches a checker only as stored DM-JSON: `Rule.Builder.build()` refuses an uncontained error
 * field outright, on a check measured OVER-REJECTING and kept deliberately (MVK-LEDGER, FEATURE-GAPS §4).
 * That refusal is geometry alone, so it is NOT the predicate here — copying it would refuse the models A12
 * admits, which is exactly what it does on the authoring path.
 */
fun PreparedModel.errorTargetContainmentFindings(): List<Finding> {
    val named = conditionReferences().groupBy { it.declaredAt }
    return declaredReferences()
        .filter { it.role == ReferenceRole.ERROR_TARGET || it.role == ReferenceRole.COMPUTED_TARGET }
        .mapNotNull { declared ->
            val written = (declared.target as? Reference.Resolved)?.path ?: return@mapNotNull null
            val group = declared.declaredAt.substringBeforeLast('/', missingDelimiterValue = "")
            if (written == group || written.startsWith("$group/")) {
                return@mapNotNull null
            }
            val texts = named[declared.declaredAt].orEmpty()
            if (texts.any { it.target !is Reference.Resolved }) {
                return@mapNotNull null
            }
            val perRowRead = texts.firstOrNull { readsOneRowAtATime(it) }
            val writtenIterates =
                declared.role == ReferenceRole.COMPUTED_TARGET && repeatingLevelsOnThePathTo(written) > 0
            if (perRowRead == null && !writtenIterates) {
                return@mapNotNull null
            }
            containmentFinding(declared, written, group, perRowRead?.written)
        }
}

/**
 * Whether [reference] reads ONE ROW — the only kind of read that derives iteration.
 *
 * A star makes the reference an aggregate over the extent rather than a read within it, and the authored
 * spelling is where that shows: the resolved path carries no star, so a family reading only the path cannot
 * tell the two apart and would refuse every aggregate.
 */
private fun PreparedModel.readsOneRowAtATime(reference: ConditionReference): Boolean {
    val path = (reference.target as? Reference.Resolved)?.path ?: return false
    return '*' !in reference.written && repeatingLevelsOnThePathTo(path) > 0
}

/**
 * @param read the authored spelling of the per-row reference, absent when the computed field's own scope is
 *     what iterates — the two have different repairs, so the explain names which one A12 is answering.
 */
private fun containmentFinding(
    declared: DeclaredReference,
    written: String,
    group: String,
    read: String?,
): Finding {
    val because = read?.let { "its condition reads `$it` one row at a time" }
        ?: "`$written` itself repeats, so writing it means writing one row"
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_ERROR_FIELD_NOT_IN_RULEGROUP",
        "this declaration writes `$written`, which lies outside its own group `$group`, while $because — " +
            "A12 cannot then say which repetition the result belongs to. Declare it in a group that " +
            "CONTAINS `$written` (an ancestor will do), or write into a field under `$group`",
        declared.declaredAt,
    )
}
