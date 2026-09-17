package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn

/**
 * A repetition wildcard inside a `Having` filter, which reads ONE row and never the list.
 *
 * The filter decides whether the current repetition belongs in the aggregate, so a starred reference in it
 * has no row to be about and A12 refuses it with `MVK_NO_WILDCARDS_ALLOWED`
 * ([KERNEL-FINDINGS KF97], `rulekit.validate.laws.AggregateSlotLawsTest.whichFilterGateReports`).
 *
 * **Its own family rather than part of [repetitionWildcardFindings], because the filter is a FOURTH position
 * and position is what picks the class there.** The same star on the same repeatable group is admitted in an
 * aggregate's operand, `MVK_NO_WILDCARDS_ALLOWED` in a scalar slot and `MVK_NO_WILDCARDS_G_ALLOWED` in a
 * quantifier's operand list — so nothing about this position could be inherited, and the reference-level
 * family deliberately judges only stars that land on something non-repeatable.
 *
 * **Which is also the one shape this family must leave alone.** A star on a NON-repeatable group inside a
 * filter draws the landing-site class `MVK_INVALID_WILDCARD` instead, measured, and that class outruns every
 * slot gate ([KF328]) — so the two families partition the filter position between them rather than both
 * reporting on it.
 *
 * **The gate belongs to the entity-list aggregate family, not to one operator**, measured on a second
 * aggregate rather than transferred; and the `$` outer marker does not move it, measured rather than assumed
 * — a marked star is refused the same way, so the reader stripping the marker costs nothing here.
 *
 * **Silent when the filter carries a nested CALL.** Whether a star inside an aggregate nested in a filter is
 * refused is unmeasured, and a filter is the one place this reader hands back authored text rather than
 * structure, so a call in it is a shape no row covers.
 */
internal fun TrackedFactBase.filterWildcardFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    val callable = keywords.values.toSet()
    return declarations().flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .flatMap { call -> call.operands }
                .flatMap { operand -> operand.clauses.filter { it.keyword == filterKeyword } }
                .flatMap { filter -> starredGroupsIn(filter.text, group, element, callable) }
                .distinct()
                .map { starred -> wildcardFinding(starred, element, member) }
        }
    }
}

private const val HAVING = "Having"

/**
 * The repeatable groups a filter body stars, or none when the body is a shape no row covers.
 *
 * A star is reported only where it lands on a group A12 agrees repeats, because the other landing sites are
 * [repetitionWildcardFindings]' and carry a different class — and only where the WHOLE reference resolves,
 * which is measured rather than inherited: the wildcard class outruns the entity gate at the non-repeatable
 * landing site and does NOT outrun it here.
 */
private fun TrackedFactBase.starredGroupsIn(
    filter: String,
    group: String,
    element: TrackedDeclaration,
    callable: Set<String>,
): List<String> {
    if (callsIn(filter, conditionLanguage, syntaxEntryOf("errorCondition")).isNotEmpty() ||
        referencesIn(filter, conditionLanguage).any { it in callable }
    ) {
        return emptyList()
    }
    return referencesIn(filter, conditionLanguage)
        .filter { it.contains('*') }
        .mapNotNull { reference ->
            // The WHOLE reference has to resolve, not just the starred prefix. A12 answers an unresolvable
            // leaf with `MVK_INVALID_ENTITY` even though the star's own group is legal and repeatable —
            // measured, after the opposite was predicted from the non-repeatable landing site, where the
            // wildcard class does outrun the entity gate. The two positions do not share that ordering.
            if (resolveEntity(group, reference.replace("*", ""), element.origin) !is Reference.Resolved) {
                return@mapNotNull null
            }
            val starred = reference.substringBefore('*')
            val resolved = resolveEntity(group, starred, element.origin) as? Reference.Resolved
                ?: return@mapNotNull null
            declarations().firstOrNull {
                it.path == resolved.path && it.isRepeatableGroup
            }?.path
        }
}

private fun wildcardFinding(starred: String, element: TrackedDeclaration, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_NO_WILDCARDS_ALLOWED",
    "this $member stars `$starred` inside a `$HAVING` filter — the filter decides one repetition at a " +
        "time, so it reads the current row and never the whole list; drop the `*`",
    element.path,
)
