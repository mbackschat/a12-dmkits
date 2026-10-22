package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.REPETITION_NOT_UNIQUE
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.markedReferenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.rulesOwnReadsIn

/**
 * That no `Having` filter hangs off an operand A12 has marked as PARALLEL-iterated.
 *
 * `ParserService.checkNormalRule`'s second gate asks `AnalyseService.hasParallelIteratedFilterConditionId`,
 * which wants ONE entity id carrying both a filter condition and the parallel-iteration marker, and refuses
 * the rule with `MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED`
 * ([KERNEL-FINDINGS KF376], `rulekit.validate.laws.FilteredEntityParallelIterationLawsTest`).
 *
 * **Which levels carry the marker is two passes, not one, and the second is why this family reads the whole
 * condition rather than the filtered operand alone.** `setSeparatePathIndexes` marks the level a reference
 * iterates OFF the target's path — the joint iteration itself — and only if that found one does
 * `setIndexesForParallelIteration` then mark, on EVERY reference, each unstarred repeatable level that is an
 * ancestor-or-equal of the error field and declares an index field. So a filter on the rule's own group is
 * admitted alone and refused once any other operand parallel-iterates, with nothing about the operand
 * changed between the two rows. A family keying on "the filtered operand reads a parallel group" admits
 * that shape; the measurement is what says so.
 *
 * **Silent behind the gate that runs before it.** `checkForParallelIteration` refuses a `RepetitionNotUnique`
 * in the same condition first ([parallelIterationUniquenessFindings] owns that class), and both are
 * post-parse, so neither hides behind a positioned defect and only the order decides — measured on a
 * condition violating both.
 *
 * **Silent on every level the scope chain refuses**, which is most of the grid: an unkeyed parallel level,
 * keys that differ in name or type, a level below a starred one, a level under the target's own group. A12
 * draws those DURING the parse, so naming this class HERE would be a post-parse verdict on a rule that never
 * finished parsing. They are [operandScopeContributions]' classes, and since 2026-09-15 that family
 * reads a filter-carrying text — with the filter BODY removed — so the levels the filtered OPERAND crosses
 * are answered in the report by the family that owns them rather than going unnamed. This family's silence
 * is a partition, not an under-report, for exactly those rows. *(Until then it was both: that family skipped
 * such a text wholesale, and the two silences together left the class unnamed on either side.)*
 *
 * **Still silent on a level only the filter BODY crosses**, which is the genuine remainder: those references
 * are judged against the filtered entity rather than the rule's target, so neither this family nor the scope
 * walk speaks for them — [filterBodyGateFindings] owns what can be said there.
 *
 * **Silent wherever the `$` outer marker survives**, for [operandScopeContributions]' reason: the
 * marker re-binds a filter reference to the enclosing rule's error field, which this reader cannot
 * reproduce, so the levels it would walk are not the ones A12 walks. It binds inside a filter body, so
 * removing the body usually removes it too; what is declined is a marker left in the surrounding text.
 *
 * **Rules only.** The gate is `checkNormalRule`'s; a computation's operations are normalised by the
 * calculation module instead, whose own parallel-iteration treatment is unmeasured here.
 */
internal fun TrackedFactBase.filteredEntityIterationFindings(): List<Finding> =
    declarations().flatMap { filteredEntityIterationFindings(it) }

internal fun TrackedFactBase.filteredEntityContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = execution?.postParse(PostParseCheck.FILTERED_ENTITY) {
    filteredEntityIterationFindings(element)
}.orEmpty()

internal fun TrackedFactBase.filteredEntityIterationFindings(element: TrackedDeclaration): List<Finding> {
    val rule = parallelRuleOf(element) ?: return emptyList()
    return listOfNotNull(conditionTexts(element.kind, rule.body).firstNotNullOfOrNull { (member, text) ->
        markedFilteredOperand(text, member, rule.declaringGroup, rule.targetGroup, element, rule.filterKeyword,
            rule.indexKeyword, rule.uniqueness)?.let { operand ->
            filteredEntityFinding(operand, rule.filterKeyword, rule.target, element, member)
        }
    })
}

/**
 * The filter-BODY twin of the gate above, `checkNormalRule`'s third: a body that parallel-iterates draws
 * `MVK_PARALLEL_ITERATION_NOT_ALLOWED`. An unmarked body read never reaches it, the filter walk refusing a body
 * that iterates during the parse ([KERNEL-FINDINGS KF353]); a `$`-marked read skips that walk and reaches it
 * whenever the rule parallel-iterates and the read sits at a level A12's two passes mark — an off-path joint
 * level, or the rule's own keyed level once something else iterates jointly (KF375, measured by
 * `adapter.laws.CheckerPostParseOrderAgreementTest`). Silent beside `RepetitionNotUnique`, whose gate runs
 * first, and beside any index in a body, which the filter walk or the later index gate owns.
 */
internal fun TrackedFactBase.filterBodyParallelContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> = execution?.postParse(PostParseCheck.FILTER_PARALLEL_ITERATION) {
    val rule = parallelRuleOf(element) ?: return@postParse emptyList()
    listOfNotNull(conditionTexts(element.kind, rule.body).firstNotNullOfOrNull { (member, text) ->
        markedBodyRead(text, member, rule, element)?.let { read ->
            Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, "MVK_PARALLEL_ITERATION_NOT_ALLOWED",
                "in `$member`: a `${rule.filterKeyword}` body reads `$OUTER_MARKER$read`, which iterates in " +
                    "parallel with `${rule.target}`'s own repetition — a filter body may not parallel-iterate; " +
                    "read the filtered row without the marker, or move the read out of the filter",
                element.path)
        }
    })
}.orEmpty()

/** What both parallel-iteration gates over a filter need to know about one rule. */
private class ParallelRule(
    val body: kotlinx.serialization.json.JsonObject,
    val target: String,
    val targetGroup: String,
    val declaringGroup: String,
    val filterKeyword: String,
    val indexKeyword: String?,
    val uniqueness: String?,
)

private fun TrackedFactBase.parallelRuleOf(element: TrackedDeclaration): ParallelRule? {
    if (element.kind != "Rule") return null
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return null
    val filterKeyword = keywords[HAVING] ?: return null
    val body = element.body ?: return null
    val target = (element.declaredReference()?.target as? Reference.Resolved)?.path ?: return null
    val targetGroup = groupOfTargetField(target) ?: return null
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    // KF372: an error field outside its declaring group is refused during the parse.
    if (!(target == declaringGroup || target.startsWith("$declaringGroup/"))) return null
    return ParallelRule(body, target, targetGroup, declaringGroup, filterKeyword, keywords[SEMANTIC_INDEX],
        keywords[REPETITION_NOT_UNIQUE])
}

/** The first `$`-marked body read at a level A12 marks as parallel-iterated, or null. */
private fun TrackedFactBase.markedBodyRead(
    text: String,
    member: String,
    rule: ParallelRule,
    element: TrackedDeclaration,
): String? {
    val calls = callsIn(text, conditionLanguage, syntaxEntryOf(member))
    if (rule.uniqueness != null && calls.any { it.operator == rule.uniqueness }) return null
    val bodies = calls.flatMap { it.operands }.flatMap { operand ->
        operand.clauses.filter { it.keyword == rule.filterKeyword }
    }
    if (bodies.isEmpty() || rule.indexKeyword != null && bodies.any { rule.indexKeyword in it.text }) return null
    val parallel = referencesIn(rulesOwnReadsIn(text, conditionLanguage), conditionLanguage)
        .filterNot { isRuleContext(it, conditionLanguage) }
        .any { markedLevelsOf(it, rule.declaringGroup, rule.targetGroup, element)?.joint?.isNotEmpty() == true }
    if (!parallel) return null
    return bodies.flatMap { markedReferenceSpansIn(it.text) }.map { it.written }.firstOrNull { read ->
        markedLevelsOf(read, rule.declaringGroup, rule.targetGroup, element)?.marked?.isNotEmpty() == true
    }
}

/** The first filtered operand A12 would find marked, or null when the condition draws no such class. */
private fun TrackedFactBase.markedFilteredOperand(
    text: String,
    member: String,
    declaringGroup: String,
    targetGroup: String,
    element: TrackedDeclaration,
    filterKeyword: String,
    indexKeyword: String?,
    uniqueness: String?,
): String? {
    // A marked read in a body joins the parallel-iteration pass this gate follows
    // (`ParserService.setSeparatePathIndexes` keeps an outer reference, source-read), and no row measures it.
    if (OUTER_MARKER in text) {
        return null
    }
    val calls = callsIn(text, conditionLanguage, syntaxEntryOf(member))
    if (uniqueness != null && calls.any { it.operator == uniqueness }) {
        return null
    }
    val filtered = calls.flatMap { it.operands }
        .filter { operand -> operand.clauses.any { it.keyword == filterKeyword } }
    if (filtered.isEmpty()) {
        return null
    }
    // A semantic index anywhere in a filter body is refused by `checkIndicesInFilterCondition` during the
    // PARSE, so this whole-rule gate never runs on such a rule — measured on the shape that violates both
    // ([KERNEL-FINDINGS KF377]), where A12 names the index class and this one would be the wrong verdict.
    if (indexKeyword != null &&
        filtered.any { operand ->
            operand.clauses.any { it.keyword == filterKeyword && indexKeyword in it.text }
        }
    ) {
        return null
    }
    if (!parallelIterates(text, filtered, filterKeyword, declaringGroup, targetGroup, element)) {
        return null
    }
    // The filtered ENTITY is the operand only where the filter is its own: one written after an index key
    // filters the key ([OperandClause.filtersKey]), which this reading of the operand's levels does not judge.
    return filtered.firstOrNull { operand ->
        val written = operand.base ?: return@firstOrNull false
        operand.clauses.any { it.keyword == filterKeyword && it.filtersKey == null } &&
            markedLevelsOf(written, declaringGroup, targetGroup, element)?.marked?.isNotEmpty() == true
    }?.base
}

/**
 * Whether some reference makes the CONDITION parallel-iterate — A12's `setSeparatePathIndexes` pass.
 *
 * A reference inside a filter body is excluded, as A12 excludes it: the pass skips a filter reference that
 * does not carry the outer marker, and a text carrying one is not judged at all. Excluding it is also the
 * safe direction, since a filter body that iterates a level of its own is
 * `MVK_INVALID_ITERATION_IN_FILTER_CONDITION` during the parse.
 */
private fun TrackedFactBase.parallelIterates(
    text: String,
    filtered: List<CallOperand>,
    filterKeyword: String,
    declaringGroup: String,
    targetGroup: String,
    element: TrackedDeclaration,
): Boolean {
    val insideFilters = filtered
        .flatMap { operand -> operand.clauses.filter { it.keyword == filterKeyword } }
        .flatMap { referencesIn(it.text, conditionLanguage) }
        .toSet()
    return referencesIn(text, conditionLanguage)
        .filterNot { it in insideFilters || isRuleContext(it, conditionLanguage) }
        .any { markedLevelsOf(it, declaringGroup, targetGroup, element)?.joint?.isNotEmpty() == true }
}

/**
 * The levels one reference iterates jointly, and every level A12's two passes would mark on it.
 *
 * @param joint the levels the FIRST pass marks — off the target's path and iterated together with it. At
 *     most one is reachable: a second unstarred repetition on the same off-path chain is refused during the
 *     parse either for declaring no index field of its own or for putting two on one chain.
 * @param marked those plus the levels the SECOND pass adds, which only runs once some reference contributed
 *     a joint one.
 */
internal data class MarkedLevels(val joint: List<String>, val marked: List<String>)

/** Null wherever the scope chain refuses the reference outright, since A12 answers it during the parse. */
internal fun TrackedFactBase.markedLevelsOf(
    written: String,
    declaringGroup: String,
    targetGroup: String,
    element: TrackedDeclaration,
): MarkedLevels? {
    val bare = written.replace("*", "")
    val resolved = resolveEntity(declaringGroup, bare, element.origin) as? Reference.Resolved ?: return null
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    val joint = mutableListOf<String>()
    val onTargetPath = mutableListOf<String>()
    var wildcardSeen = false
    crossedRepetitions(resolved.path).forEach { level ->
        if (level.path in starred) {
            wildcardSeen = true
            return@forEach
        }
        when {
            wildcardSeen -> return null
            level.path.startsWith("$targetGroup/") -> return null
            targetGroup == level.path || targetGroup.startsWith("${level.path}/") ->
                indexFieldNameOf(level)?.let { onTargetPath += level.path }
            parallelVerdictOf(level, targetGroup) == ParallelVerdict.Joint -> joint += level.path
            else -> return null
        }
    }
    return MarkedLevels(joint, joint + onTargetPath)
}

private fun filteredEntityFinding(
    operand: String,
    filterKeyword: String,
    target: String,
    element: TrackedDeclaration,
    member: String,
) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_PARALLEL_ITERATION_FOR_FILTERED_ENTITY_NOT_ALLOWED",
    "in `$member`: `$operand` carries a `$filterKeyword` filter while this condition iterates a " +
        "repetition in parallel with `$target`'s own — A12 cannot say which repetitions the filter runs " +
        "over, so the filter has to go, or the parallel read has to be starred or moved to its own rule",
    element.path,
)
