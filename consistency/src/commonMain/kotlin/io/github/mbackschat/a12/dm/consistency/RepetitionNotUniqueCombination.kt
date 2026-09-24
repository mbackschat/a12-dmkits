package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn

/**
 * That a condition calls `RepetitionNotUnique` at most ONCE.
 *
 * A12 refuses a second call with `MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE`
 * ([KERNEL-FINDINGS KF352], `rulekit.validate.laws.RepetitionNotUniqueCombinationLawsTest`), and the fix is
 * to merge the keys into one call's composite key rather than to drop one — which is why the finding names
 * the count instead of a particular call.
 *
 * **It counts CALLS, not keys, and the difference is measured rather than assumed.** One call taking a
 * composite key is admitted, so a family counting key operands would refuse the shape A12 offers as the fix.
 *
 * **A whole-RULE gate, which is what makes it independent of where the calls sit.** A12 runs it over the
 * finished parse tree after the condition parses, so two calls in opposite arms of an `Or` are counted
 * together even though no evaluation reaches both — measured, because the plausible reading is per branch.
 *
 * **It reports even where A12 would name a construct's own class instead**, which is deliberate and is this
 * checker's standing difference from A12: a construct gate reports ahead of this one, so a condition with
 * two calls and a bad `@From` scope draws `MVK_GROUP_NOT_REPEATABLE` from A12 alone. Both defects are real
 * and A12 simply stops at the first; suppressing the second would hide a defect the author still has to fix.
 *
 * **A call inside a `Having` filter is NOT counted**, and that is a correction rather than a simplification:
 * the call scanner does descend into a filter body, so counting naively named this class on a condition A12
 * answers with `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` — the construct is refused in that position
 * outright, so the second call is not a second defect at all and removing the filter's call leaves one legal
 * call behind.
 *
 * **Rules only.** A12's calculation module refuses `RepetitionNotUnique` in a computation outright
 * ([KERNEL-FINDINGS KF333]), so a computation carrying two has a different defect entirely.
 */
internal fun TrackedFactBase.repetitionNotUniqueCombinationFindings(
    element: TrackedDeclaration,
): List<Finding> {
    if (element.kind != "Rule") {
        return emptyList()
    }
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val spelling = keywords[REPETITION_NOT_UNIQUE] ?: return emptyList()
    val body = element.body ?: return emptyList()
    val filterKeyword = keywords[HAVING]
    return conditionTexts(element.kind, body).mapNotNull { (member, text) ->
        val entry = syntaxEntryOf(member)
        val found = callsIn(text, conditionLanguage, entry)
        val inFilters = found.flatMap { it.operands }.flatMap { it.clauses }
            .filter { it.keyword == filterKeyword }
            .sumOf { filter ->
                callsIn(filter.text, conditionLanguage, entry).count { it.operator == spelling }
            }
        val calls = found.count { it.operator == spelling } - inFilters
        if (calls < 2) {
            return@mapNotNull null
        }
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE",
            "this $member calls `$spelling` $calls times — A12 admits one per condition, counted over " +
                "the whole text rather than per branch; give the single call a composite key instead",
            element.path,
        )
    }
}
