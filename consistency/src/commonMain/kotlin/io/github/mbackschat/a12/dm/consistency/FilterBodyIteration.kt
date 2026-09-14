package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.namesKeyword
import io.github.mbackschat.a12.dm.consistency.syntax.namesRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn

/**
 * Which repeatable levels a `Having` filter's BODY may name, and the one it must.
 *
 * `CheckEntitySpecImpl.checkIndicesInFilterCondition` walks every entity spec in the filter, level by level,
 * and asks two questions of each repeatable level the spec rides unmarked. They are separate gates with
 * separate classes, and a single-column table reads them as one
 * ([KERNEL-FINDINGS KF374], `rulekit.validate.laws.HavingFilterIterationGateLawsTest`,
 * `…HavingFilterGrammarAndScopeLawsTest`).
 *
 * | the level a filter reference rides | class |
 * |---|---|
 * | one the FILTERED entity does not sit under | `MVK_INVALID_ITERATION_IN_FILTER_CONDITION` |
 * | none of them is a level the operand's star reopens | `MVK_NO_ITERATION_FOR_WILDCARD` |
 *
 * **The first gate yields to nothing and the second yields to any conjunct**, which is what makes the
 * distinction load-bearing rather than cosmetic: the scope gate is an OCCURRENCE check over the whole body,
 * so one in-scope reference anywhere clears it, while an out-of-path level is refused however much else the
 * filter names — the enclosing rule's own iteration of that level included.
 *
 * **A level is "ridden" by ending at it as much as by passing through it.** `CurrentRepetition(Legs)`
 * resolves to the group itself and carries the same iteration marker a field beneath `Legs` does, so a walk
 * over ancestors alone would find no level at all on the carrier the catalog line is about.
 *
 * **Silent on the operand shapes whose own gate answers earlier**, each measured rather than assumed: a
 * repetition the OPERAND crosses neither starred nor iterated by the declaring group draws `MVK_NO_WILDCARD`
 * from the reference reader before any filter question, and a star inside the BODY draws
 * `MVK_NO_WILDCARDS_ALLOWED` from [filterWildcardFindings]' position.
 *
 * **Silent wherever the body carries the `$` outer marker.** The marker relocates a reference to the
 * captured outer environment, so A12's own walk skips it — but where that environment has no coordinate for
 * the level the reference reader answers `MVK_NO_WILDCARD` instead, and this reader cannot see the marker at
 * all: [referencesIn] reports a marked reference without it, which is right for binding and wrong for this
 * walk. Judging the text either way would name a class on a shape the marker moves.
 */
fun PreparedModel.filterBodyIterationFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    val callable = keywords.values.toSet()
    val clauseKeywords = setOfNotNull(keywords[HAVING], keywords[FROM], keywords[SEMANTIC_INDEX])
    return elements.mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .flatMap { it.operands }
                .firstNotNullOfOrNull { operand ->
                    filterDefectOf(operand, filterKeyword, clauseKeywords, callable, group, element)
                        ?.let { filterFinding(it, element, member) }
                }
        }
    }
}

private const val HAVING = "Having"

private const val FROM = "From"

private const val SEMANTIC_INDEX = "SemanticIndex"

private data class FilterDefect(val code: String, val wrote: String, val level: String?)

/**
 * The first of the two gates this operand's filter fails, in A12's own order.
 *
 * The walk runs reference by reference and level by level, so an out-of-path level anywhere pre-empts the
 * scope gate — which is checked only once every reference has been read, exactly as the kernel does.
 */
private fun PreparedModel.filterDefectOf(
    operand: CallOperand,
    filterKeyword: String,
    clauseKeywords: Set<String>,
    callable: Set<String>,
    group: String,
    element: DeclaredElement,
): FilterDefect? {
    val filter = operand.clauses.firstOrNull { it.keyword == filterKeyword } ?: return null
    val written = operand.base ?: return null
    // A nested clause, the rule-context keyword, and the outer marker each put a shape in the body that this
    // walk has no measured cell for — the first two because they carry indices A12 reads and no path this
    // reader can resolve, the third because the marker is invisible in a normalized reference.
    if (OUTER_MARKER in filter.text ||
        namesRuleContext(filter.text, conditionLanguage) ||
        namesKeyword(filter.text, clauseKeywords) != null
    ) {
        return null
    }
    val starred = starredLevelsOf(written, group, element)?.takeIf { it.isNotEmpty() } ?: return null
    val filtered = (resolveEntity(group, written.replace("*", ""), element.origin) as? Reference.Resolved)
        ?.path ?: return null
    // The OPERAND is read before its filter, so a repetition it crosses that neither its own star nor the
    // declaring group's iteration addresses is `MVK_NO_WILDCARD` there and the filter is never reached —
    // measured with the unfiltered operand as the control that locates the refusal.
    if (iteratedLevels(filtered).any { it !in starred && !(group == it || group.startsWith("$it/")) }) {
        return null
    }
    var boundSeen = false
    referencesIn(filter.text, conditionLanguage)
        .filterNot { it in callable }
        .forEach { reference ->
            if ('*' in reference) {
                return null
            }
            val resolved = (resolveEntity(group, reference, element.origin) as? Reference.Resolved)
                ?.path ?: return null
            iteratedLevels(resolved).forEach { level ->
                if (!(filtered == level || filtered.startsWith("$level/"))) {
                    return FilterDefect(INVALID_ITERATION, reference, level)
                }
                if (level in starred) {
                    boundSeen = true
                }
            }
        }
    return if (boundSeen) null else FilterDefect(NO_ITERATION, written, null)
}

private const val INVALID_ITERATION = "MVK_INVALID_ITERATION_IN_FILTER_CONDITION"

private const val NO_ITERATION = "MVK_NO_ITERATION_FOR_WILDCARD"

private fun filterFinding(defect: FilterDefect, element: DeclaredElement, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    when (defect.level) {
        null ->
            "in `$member`: the filter on `${defect.wrote}` names no level that the operand's own star " +
                "reopens, so nothing in it is about the repetition being filtered — reference a field or " +
                "group inside a starred level, or drop the filter"
        else ->
            "in `$member`: the filter names `${defect.wrote}`, which iterates `${defect.level}` — a " +
                "repetition the filtered operand does not sit under, so A12 has no row of it to read; the " +
                "`$OUTER_MARKER` marker is the only spelling that reaches one, and only where the " +
                "declaration already iterates that level"
    },
    element.path,
)
