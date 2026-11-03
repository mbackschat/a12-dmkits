package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OUTER_MARKER
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.ReferenceSpan
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.fieldValueReadsIn
import io.github.mbackschat.a12.dm.consistency.syntax.isLexerBlank
import io.github.mbackschat.a12.dm.consistency.syntax.markedReferenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.namesKeyword
import io.github.mbackschat.a12.dm.consistency.syntax.namesRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
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
 * | iteration of a level the FILTERED entity does not sit under | `MVK_INVALID_ITERATION_IN_SET_EXPRESSION` |
 * | iteration of a level below one the same spec stars | `MVK_NO_WILDCARD` |
 * | a SEMANTIC INDEX | `MVK_SEMANTIC_INDEX_NOT_ALLOWED` |
 * | no level is one the operand's star reopens | `MVK_NO_ITERATION_FOR_WILDCARD` |
 * | a nested filter, `RepetitionNotUnique` or `CustomCondition`, once every gate above passes | `MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION` |
 *
 * **The construct gate runs LAST, so it is reachable only from a filter that is otherwise legal** — every
 * class above pre-empts it, measured on both of the shapes that violate two gates at once. That ordering is
 * also what makes it cheap to report here rather than as a family of its own: this walk already decides
 * whether the gates before it pass, and a separate family would have to reproduce all of them to know the
 * gate was ever reached.
 *
 * **All three of A12's forbidden constructs are reported**: a NESTED filter, `CustomCondition` and
 * `RepetitionNotUnique`. A nested filter is walked like the rest of the body first — its operand and its own
 * body are specs the walk reads — so a body that names another repetition inside it is the iteration class,
 * not the construct one ([KERNEL-FINDINGS KF486]). `CustomCondition` needed a parser fix to reach the gate at
 * all: the shape reader spelled the keyword-plus-bare-name predicate only where a condition was expected
 * positionally, never as a CLAUSE operand, so a filter body carrying it drew `MVK_UNEXPECTED_TOKEN` from the
 * syntax family on a text A12 parses.
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
 * **An index with an ENTITY key is walked as a literal-keyed one.** A12 asks such a key no iteration question inside a
 * body, and the index's own spec gates, which run while the spec is parsed, answer a refused key ahead of this walk:
 * a key naming a group is `MVK_SEMANTIC_INDEX_NO_FIELD` and one of the wrong type `MVK_INDEX_TYPE_INCONSISTENT`, while
 * a sound key is this walk's index class ([KERNEL-FINDINGS KF605], `adapter.laws.CheckerKeyedOperandFamilyAgreementTest`).
 *
 * **A filter an entity key owns is walked against the KEY.** A `Having` after an entity key is the key's
 * ([SyntaxSite.FilterEntry.indexKey]), so A12 asks every gate here of the key's spec while the key is parsed, ahead of the
 * index gates the outer spec asks of the key once it is complete: `[Orders/Note]` in the body of a filter the
 * starred key `Parties* / Name` owns is the iteration class, not the key's star class. An index in such a body sits
 * inside the key's own index and is refused as a nested one while the body is parsed; that class is
 * [semanticIndexStructureContributions]', spanned on the inner index, so report assembly places it ahead of this walk's
 * (`adapter.laws.CheckerFilterBodyAgreementTest`, [KERNEL-FINDINGS KF485]).
 *
 * **A level is "ridden" by ending at it as much as by passing through it.** `CurrentRepetition(Legs)`
 * resolves to the group itself and carries the same iteration marker a field beneath `Legs` does, so a walk
 * over ancestors alone would find no level at all on the carrier the catalog line is about.
 *
 * **Silent on the operand shapes whose own gate answers earlier**, each measured rather than assumed: a
 * repetition the OPERAND crosses that neither its own star nor the DECLARATION'S ITERATION addresses draws
 * `MVK_NO_WILDCARD` from the reference reader before any filter question, and a star inside the BODY draws
 * `MVK_NO_WILDCARDS_ALLOWED` from [fieldValueReadOperandFindings]' position. The iteration is set by where the
 * error field sits rather than by where the rule is declared, which are the same thing on every carrier that
 * measured this until 2026-09-15 and are not the same question.
 *
 * **A `$`-marked reference is not walked.** `extractFilterIterationEntityRefs` skips it, and A12 checks it
 * against the rule's target instead, as the same read outside any filter is — [operandScopeContributions]'
 * question ([rulesOwnReadsIn]). So it clears no gate here: a body reading nothing else is the scope class, where
 * the same body unmarked, reading another repetition, is the iteration class. A marked read inside a nested
 * filter reaches the RULE, not the filter it stands in (`adapter.laws.CheckerFilterBodyAgreementTest`,
 * [KERNEL-FINDINGS KF487]). A marked read's semantic index is not the walk's either; A12 refuses it only once
 * the parse is done ([filterBodyPostParseIndexFindings]).
 */
internal fun TrackedFactBase.filterBodyGateFindings(): List<Finding> = declarations().mapNotNull { element ->
    filterBodyContributions(element).firstOrNull()?.finding
}

internal fun TrackedFactBase.filterBodyContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = walkedFilters(element, execution).mapNotNull { walked ->
    walked.verdict?.defect?.let {
        ConditionFinding(element.key, EntityCheckSite(walked.site, EntityCheck.FILTER), filterFinding(it, element, walked.span.member, walked.span))
    }
}

/** KF487: the filter walk skips marked keys; their index is refused only after successful parsing. */
internal fun TrackedFactBase.filterBodyPostParseIndexFindings(): List<Finding> = declarations().flatMap {
    filterBodyIndexContributions(it).map { contribution -> contribution.finding }
}

internal fun TrackedFactBase.filterBodyIndexContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = execution?.postParse(PostParseCheck.FILTER_SEMANTIC_INDEX) {
    val filters = walkedFilters(element, execution)
    if (filters.any { it.verdict == null }) emptyList() else listOfNotNull(filters.firstNotNullOfOrNull { walked ->
        walked.verdict?.markedKeyedRead?.let { read ->
            Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, "MVK_SEMANTIC_INDEX_NOT_ALLOWED",
                "in `${walked.span.member}`: the filter reads `$read` through a semantic index — a filter condition " +
                    "takes no semantic index, `$OUTER_MARKER`-marked or not; select the row outside the filter",
                element.path, walked.span)
        }
    })
}.orEmpty()

private class WalkedFilter(val site: SyntaxSite.Completion, val span: TextSpan, val verdict: FilterVerdict?)

private fun TrackedFactBase.walkedFilters(
    element: TrackedDeclaration,
    execution: ConditionExecution?,
): List<WalkedFilter> {
    if (execution == null) return emptyList()
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return emptyList()
    val callable = keywords.values.toSet()
    val indexKeyword = keywords[OperatorId.SEMANTIC_INDEX]
    val group = element.path.substringBeforeLast('/')
    return execution.recognition.sites.filterIsInstance<SyntaxSite.FilterExit>().mapNotNull { exit ->
        val site = execution.entitiesByStart[exit.entry.start] ?: return@mapNotNull null
        val span = execution.carrier.semanticSpan(site.start, site.end) ?: return@mapNotNull null
        val text = execution.carrier.text.substring(exit.entry.end, exit.end)
        WalkedFilter(site, span, filterVerdictOf(normalizedPath(exit.entry.path), text,
            indexKeyword, callable, keywords, group, element))
    }
}

/** [text] with each of [spans] blanked in place. */
private fun withoutSpans(text: String, spans: List<ReferenceSpan>): String {
    val blanked = text.toCharArray()
    spans.forEach { blanked.fill(' ', it.start, it.end) }
    return blanked.concatToString()
}

/** The four gates of the filter walk, in the order A12 asks them. */
private enum class FilterGate(val code: String) {
    INVALID_ITERATION("MVK_INVALID_ITERATION_IN_SET_EXPRESSION"),
    ITERATION_BELOW_A_STAR("MVK_NO_WILDCARD"),
    INDEX_NOT_ALLOWED("MVK_SEMANTIC_INDEX_NOT_ALLOWED"),
    NO_ITERATION("MVK_NO_ITERATION_FOR_WILDCARD"),
    DISALLOWED_CONSTRUCT("MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION"),
}

/** The gate this filter fails, what the author wrote there, and the level it iterates where the gate has one. */
private data class FilterDefect(val gate: FilterGate, val wrote: String, val level: String? = null)

/**
 * What the walk decides about one filter: the gate it fails, or null where it fails none, and the first
 * `$`-marked keyed read the walk skipped, which A12 refuses after the parse.
 */
private data class FilterVerdict(val defect: FilterDefect?, val markedKeyedRead: String?)

/**
 * What the walk decides about this operand's filter, or null where it leaves the filter unjudged: the first
 * gate the filter fails, in A12's own order, and a marked keyed read it skipped.
 *
 * The walk runs reference by reference and level by level, so an out-of-path level anywhere pre-empts the
 * scope gate — which is checked only once every reference has been read, exactly as the kernel does.
 */
private fun TrackedFactBase.filterVerdictOf(
    written: String,
    text: String,
    indexKeyword: String?,
    callable: Set<String>,
    keywords: Map<OperatorId, String>,
    group: String,
    element: TrackedDeclaration,
): FilterVerdict? {
    // A filter written after an entity key filters the KEY ([SyntaxSite.FilterEntry.indexKey]): A12 asks every gate
    // below of the key's spec, while the key is parsed, so the key is the filtered entity here.
    // The rule-context keyword puts a shape in the body that this walk has no measured cell for, carrying indices
    // A12 reads and no path this reader can resolve. A starred bracket read is the field-value read's star gate,
    // which A12 raises as the bracket is read. A `@From` scope needs no such exit: `RepetitionNotUnique`'s own
    // gates answer ahead of this walk in a body (KERNEL-FINDINGS KF355).
    val marked = markedReferenceSpansIn(text)
    if (namesRuleContext(text, conditionLanguage) ||
        fieldValueReadsIn(text, conditionLanguage, syntaxEntryOf("errorCondition")).any { it.path?.contains('*') == true }
    ) {
        return null
    }
    val starred = starredLevelsOf(written, group, element)?.takeIf { it.isNotEmpty() } ?: return null
    val filtered = resolvedPathOf(written, group, element) ?: return null
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
    // A marked read's index is not the walk's: the walk skips the marked spec, and A12 refuses the index only
    // after the parse ([filterBodyPostParseIndexFindings]).
    val (markedUses, uses) = indexKeyword
        ?.let { semanticIndexUsesIn(text, conditionLanguage, it) }
        .orEmpty()
        .partition { use -> marked.any { it.end <= use.keywordAt && text.substring(it.end, use.keywordAt).isLexerBlank() } }
    // An ENTITY key is read like a literal one: the index's own spec gates answer a refused key ahead of this walk,
    // while the key is parsed, and a sound one is this walk's index class (KERNEL-FINDINGS KF605,
    // `adapter.laws.CheckerKeyedOperandFamilyAgreementTest`). On a marked read the same key is unmeasured.
    if (markedUses.any { it.keyLiteral == null } || (uses + markedUses).any { it.keyCategory != null || it.nested }) {
        return null
    }
    val indexed = uses.mapNotNullTo(mutableSetOf()) { it.valuePath }
    val markedKeyedRead = markedUses.firstOrNull()?.let { text.substring(it.start, it.end).javaTrimmed() }
    fun verdict(defect: FilterDefect?) = FilterVerdict(defect, markedKeyedRead)
    var boundSeen = false
    // Every spec in the body is walked, a nested aggregate's operand and a nested filter's body included
    // (`extractFilterIterationEntityRefs` visits the whole parse tree), each level by level: a starred level
    // is a WILDCARD, which asks nothing but marks the levels after it, and an unstarred repeatable one is an
    // ITERATION, asked the path question first and the below-star one second (source-read; measured,
    // `adapter.laws.CheckerFilterBodyAgreementTest`).
    referencesIn(withoutSpans(text, marked), conditionLanguage)
        .filterNot { it in callable }
        .forEach { reference ->
            val wildcards = starredLevelsOf(reference, group, element) ?: return null
            val resolved = resolvedPathOf(reference, group, element) ?: return null
            // The index replaces ITERATION at the level it addresses, so this reference is never asked the
            // scope question — and the walk reads references in authored order, which is what decides
            // between the two classes when the body carries one of each.
            if (reference in indexed) {
                return verdict(FilterDefect(FilterGate.INDEX_NOT_ALLOWED, reference))
            }
            var belowAStar = false
            iteratedLevels(resolved).forEach { level ->
                if (level in wildcards) {
                    belowAStar = true
                    return@forEach
                }
                if (!(filtered == level || filtered.startsWith("$level/"))) {
                    return verdict(FilterDefect(FilterGate.INVALID_ITERATION, reference, level))
                }
                if (belowAStar) {
                    return verdict(FilterDefect(FilterGate.ITERATION_BELOW_A_STAR, reference, level))
                }
                if (level in starred) {
                    boundSeen = true
                }
            }
        }
    if (!boundSeen) {
        return verdict(FilterDefect(FilterGate.NO_ITERATION, written))
    }
    // A12's LAST gate over the body, and it runs only here: `checkNoDisallowedConstructsInFilterCondition`
    // sits after the index walk in `checkFilterCondition`, so every class above pre-empts it and it is
    // reachable only from a filter that is otherwise legal.
    return verdict(disallowedConstructIn(text, keywords)?.let { FilterDefect(FilterGate.DISALLOWED_CONSTRUCT, it) })
}

/**
 * The forbidden construct this filter body names, or null where it names none.
 *
 * A12's listener refuses exactly three, and each by its own terminal: a NESTED filter, `CustomCondition` and
 * `RepetitionNotUnique`. All three carry one class, so which one the text names first matters only to the
 * message.
 */
private fun disallowedConstructIn(text: String, keywords: Map<OperatorId, String>): String? =
    listOf(OperatorId.HAVING, OperatorId.CUSTOM_CONDITION, OperatorId.REPETITION_NOT_UNIQUE).mapNotNull { keywords[it] }
        .firstOrNull { namesKeyword(text, setOf(it)) != null }

private fun filterFinding(defect: FilterDefect, element: TrackedDeclaration, member: String, span: TextSpan) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.gate.code,
    when (defect.gate) {
        FilterGate.INDEX_NOT_ALLOWED ->
            "in `$member`: the filter reads `${defect.wrote}` through a semantic index, which picks ONE " +
                "repetition where the filter decides whether the current one belongs — drop the index and " +
                "read the field the filter is about, or move the comparison out of the filter"
        FilterGate.NO_ITERATION ->
            "in `$member`: the filter on `${defect.wrote}` names no level that the operand's own star " +
                "reopens, so nothing in it is about the repetition being filtered — reference a field or " +
                "group inside a starred level, or drop the filter"
        FilterGate.INVALID_ITERATION ->
            "in `$member`: the filter names `${defect.wrote}`, which iterates `${defect.level}` — a " +
                "repetition the filtered operand does not sit under, so A12 has no row of it to read; the " +
                "`$OUTER_MARKER` marker is the only spelling that reaches one, and only where the " +
                "declaration already iterates that level"
        FilterGate.ITERATION_BELOW_A_STAR ->
            "in `$member`: the filter names `${defect.wrote}`, which stars a level above `${defect.level}` " +
                "and then reads one row of `${defect.level}` — a row of every repetition above it has no " +
                "single one below it; star `${defect.level}` too"
        FilterGate.DISALLOWED_CONSTRUCT ->
            "in `$member`: the filter's body uses `${defect.wrote}`, which A12 refuses inside a filter, " +
                "as it does a nested filter, `CustomCondition` and `RepetitionNotUnique` — move it out of " +
                "the filter"
    },
    element.path,
    span,
)
