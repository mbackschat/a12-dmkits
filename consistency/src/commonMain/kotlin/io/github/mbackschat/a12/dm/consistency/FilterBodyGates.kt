package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CUSTOM_CONDITION
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.namesKeyword
import io.github.mbackschat.a12.dm.consistency.syntax.namesRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn

/**
 * What a `Having` filter's BODY may name — which repeatable levels, the one it must, and the constructs it
 * may not use at all.
 *
 * `CheckEntitySpecImpl.checkIndicesInFilterCondition` walks every entity spec in the filter, level by level,
 * and asks two questions of each repeatable level the spec rides unmarked; `checkFilterCondition` then runs
 * `checkNoDisallowedConstructsInFilterCondition` over the whole body. They are separate gates with separate
 * classes, and a single-column table reads them as one
 * ([KERNEL-FINDINGS KF374], `rulekit.validate.laws.HavingFilterIterationGateLawsTest`,
 * `…HavingFilterGrammarAndScopeLawsTest`, `…FilteredOperandGateOrderLawsTest`).
 *
 * | what the body carries | class |
 * |---|---|
 * | iteration of a level the FILTERED entity does not sit under | `MVK_INVALID_ITERATION_IN_FILTER_CONDITION` |
 * | a SEMANTIC INDEX | `MVK_SEMANTIC_INDEX_NOT_ALLOWED` |
 * | no level is one the operand's star reopens | `MVK_NO_ITERATION_FOR_WILDCARD` |
 * | `RepetitionNotUnique` or `CustomCondition`, once every gate above passes | `MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION` |
 *
 * **The construct gate runs LAST, so it is reachable only from a filter that is otherwise legal** — every
 * class above pre-empts it, measured on both of the shapes that violate two gates at once. That ordering is
 * also what makes it cheap to report here rather than as a family of its own: this walk already decides
 * whether the gates before it pass, and a separate family would have to reproduce all of them to know the
 * gate was ever reached.
 *
 * **One of A12's three forbidden constructs is not reported**: a NESTED filter leaves this walk before the
 * gate, being one of the shapes [filterDefectOf] declines outright. `CustomCondition` needed a parser fix to
 * reach it at all — the shape reader spelled the keyword-plus-bare-name predicate only where a condition was
 * expected positionally, never as a CLAUSE operand, so a filter body carrying it drew `MVK_UNEXPECTED_TOKEN`
 * from the syntax family on a text A12 parses.
 *
 * **The first two gates yield to nothing and the third yields to any conjunct**, which is what makes the
 * distinction load-bearing rather than cosmetic: the scope gate is an OCCURRENCE check over the whole body,
 * so one in-scope reference anywhere clears it, while an out-of-path level is refused however much else the
 * filter names — the enclosing rule's own iteration of that level included.
 *
 * **The index gate is REFERENCE-positional against the iteration gate, and is measured in both text orders**
 * ([KERNEL-FINDINGS KF377], `rulekit.validate.laws.SemanticIndexInFilterLawsTest`): an index replaces
 * ITERATION at the level it addresses, so a reference carrying one is never asked the scope question, and
 * whichever of the two references the body writes first is the one A12 answers about. That is also why the
 * class belongs to this walk rather than to the semantic-index families: `ParserService.checkNormalRule`
 * carries a `MVK_SEMANTIC_INDEX_NOT_ALLOWED` branch of its own and never reaches it, the filter walk having
 * thrown during the parse — measured against the whole-rule gate that would otherwise have answered.
 *
 * **A filter whose index has an ENTITY key is left alone entirely**, which is an under-report and a
 * deliberate one: A12 answers those from the index's own spec gates, which run while the spec is parsed and
 * so ahead of this walk, and their order among themselves inside a filter is measured on two shapes that
 * disagree with the order [semanticIndexKeyFindings] applies outside one. Judging such a body here would
 * name a third class beside a pair that is already one too many.
 *
 * **A level is "ridden" by ending at it as much as by passing through it.** `CurrentRepetition(Legs)`
 * resolves to the group itself and carries the same iteration marker a field beneath `Legs` does, so a walk
 * over ancestors alone would find no level at all on the carrier the catalog line is about.
 *
 * **Silent on the operand shapes whose own gate answers earlier**, each measured rather than assumed: a
 * repetition the OPERAND crosses that neither its own star nor the DECLARATION'S ITERATION addresses draws
 * `MVK_NO_WILDCARD` from the reference reader before any filter question, and a star inside the BODY draws
 * `MVK_NO_WILDCARDS_ALLOWED` from [filterWildcardFindings]' position. The iteration is set by where the
 * error field sits rather than by where the rule is declared, which are the same thing on every carrier that
 * measured this until 2026-09-15 and are not the same question.
 *
 * **Silent wherever the body carries the `$` outer marker.** The marker relocates a reference to the
 * captured outer environment, so A12's own walk skips it — but where that environment has no coordinate for
 * the level the reference reader answers `MVK_NO_WILDCARD` instead, and this reader cannot see the marker at
 * all: [referencesIn] reports a marked reference without it, which is right for binding and wrong for this
 * walk. Judging the text either way would name a class on a shape the marker moves.
 */
internal fun TrackedFactBase.filterBodyGateFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val filterKeyword = keywords[HAVING] ?: return emptyList()
    val callable = keywords.values.toSet()
    // A nested filter or a scope in the body is a shape with no measured cell; a semantic index no longer
    // is one — it has its own branch of the same walk.
    val clauseKeywords = setOfNotNull(keywords[HAVING], keywords[FROM])
    val indexKeyword = keywords[SEMANTIC_INDEX]
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member))
                .flatMap { it.operands }
                .firstNotNullOfOrNull { operand ->
                    filterDefectOf(operand, filterKeyword, indexKeyword, clauseKeywords, callable,
                        keywords, group, element)
                        ?.let { filterFinding(it, element, member) }
                }
        }
    }
}

private const val FROM = "From"

private data class FilterDefect(val code: String, val wrote: String, val level: String?)

/**
 * The first of the two gates this operand's filter fails, in A12's own order.
 *
 * The walk runs reference by reference and level by level, so an out-of-path level anywhere pre-empts the
 * scope gate — which is checked only once every reference has been read, exactly as the kernel does.
 */
private fun TrackedFactBase.filterDefectOf(
    operand: CallOperand,
    filterKeyword: String,
    indexKeyword: String?,
    clauseKeywords: Set<String>,
    callable: Set<String>,
    keywords: Map<String, String>,
    group: String,
    element: TrackedDeclaration,
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
    // DECLARATION'S ITERATION addresses is `MVK_NO_WILDCARD` there and the filter is never reached —
    // measured with the unfiltered operand as the control that locates the refusal.
    //
    // The iteration is the TARGET's group, not the declaring one: a rule declared at the root but reporting
    // inside a repetition iterates it, and the identical operand is then admitted and its filter reached
    // (`rulekit.validate.laws.HavingFilterIterationGateLawsTest
    // .whetherTheFilterIterationGateIsKeyedOnTheDeclaringGroupOrTheTarget`). Every other carrier in that
    // grid declares the rule in its error field's own group, which makes the two questions one sentence —
    // this was the third reader in the module keyed on the wrong one.
    val iterated = iterationGroupOf(element) ?: group
    if (iteratedLevels(filtered).any { it !in starred && !(iterated == it || iterated.startsWith("$it/")) }) {
        return null
    }
    val uses = indexKeyword
        ?.let { semanticIndexUsesIn(filter.text, conditionLanguage, it) }
        .orEmpty()
    // An ENTITY key leaves the whole operand unjudged. A12 answers such a body from the index's own spec
    // gates, which run while the spec is parsed and so ahead of this walk — measured on two shapes, one
    // answered by the key-kind gate and one by the filter branch, neither by anything this walk asks.
    if (uses.any { it.keyLiteral == null || it.keyCategory != null || it.nested }) {
        return null
    }
    val indexed = uses.mapNotNullTo(mutableSetOf()) { it.valuePath }
    var boundSeen = false
    referencesIn(filter.text, conditionLanguage)
        .filterNot { it in callable }
        .forEach { reference ->
            if ('*' in reference) {
                return null
            }
            val resolved = (resolveEntity(group, reference, element.origin) as? Reference.Resolved)
                ?.path ?: return null
            // The index replaces ITERATION at the level it addresses, so this reference is never asked the
            // scope question — and the walk reads references in authored order, which is what decides
            // between the two classes when the body carries one of each.
            if (reference in indexed) {
                return FilterDefect(INDEX_NOT_ALLOWED, reference, null)
            }
            iteratedLevels(resolved).forEach { level ->
                if (!(filtered == level || filtered.startsWith("$level/"))) {
                    return FilterDefect(INVALID_ITERATION, reference, level)
                }
                if (level in starred) {
                    boundSeen = true
                }
            }
        }
    if (!boundSeen) {
        return FilterDefect(NO_ITERATION, written, null)
    }
    // A12's LAST gate over the body, and it runs only here: `checkNoDisallowedConstructsInFilterCondition`
    // sits after the index walk in `checkFilterCondition`, so every class above pre-empts it and it is
    // reachable only from a filter that is otherwise legal.
    return disallowedConstructIn(filter.text, keywords)?.let { FilterDefect(DISALLOWED_CONSTRUCT, it, null) }
}

/**
 * The forbidden construct this filter body names, or null where it names none this reader may judge.
 *
 * A12's listener refuses exactly three, and each by its own terminal: a NESTED filter, `CustomCondition` and
 * `RepetitionNotUnique`. The last two are reported; the nested filter leaves this walk before the gate is
 * reached — the body is one of the shapes [filterDefectOf] declines outright — so naming it here would claim
 * a gate that never ran.
 */
private fun disallowedConstructIn(text: String, keywords: Map<String, String>): String? =
    listOfNotNull(keywords[CUSTOM_CONDITION], keywords[REPETITION_NOT_UNIQUE])
        .firstOrNull { namesKeyword(text, setOf(it)) != null }

private const val INVALID_ITERATION = "MVK_INVALID_ITERATION_IN_FILTER_CONDITION"

private const val NO_ITERATION = "MVK_NO_ITERATION_FOR_WILDCARD"

private const val INDEX_NOT_ALLOWED = "MVK_SEMANTIC_INDEX_NOT_ALLOWED"

private const val DISALLOWED_CONSTRUCT = "MVK_DISALLOWED_CONSTRUCT_IN_FILTER_CONDITION"

private fun filterFinding(defect: FilterDefect, element: TrackedDeclaration, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    when {
        defect.code == INDEX_NOT_ALLOWED ->
            "in `$member`: the filter reads `${defect.wrote}` through a semantic index, which picks ONE " +
                "repetition where the filter decides whether the current one belongs — drop the index and " +
                "read the field the filter is about, or move the comparison out of the filter"
        defect.level == null ->
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
