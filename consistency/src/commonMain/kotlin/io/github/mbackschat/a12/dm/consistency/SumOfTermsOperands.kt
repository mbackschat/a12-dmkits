package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.RecognizedEntity
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.soleFieldValueReadIn

/**
 * What `SumOfTerms(<Group*>, <term>)` asks of its two arguments once both are read: its iteration entity first,
 * then its term (KERNEL-FINDINGS KF656, KF670; `rulekit.validate.laws.SumOfTermsIterationEntityLawsTest`,
 * `rulekit.validate.laws.SumOfTermsTermLawsTest`).
 *
 * A12 runs these questions as the operation closes (`CheckSetOperOpImpl.check`, source-read at eec9134), so a
 * class the arguments' own specs draw while they are read answers first — an unstarred group the rule does not
 * iterate is the iteration walk's `MVK_NO_WILDCARD`, a starred fixed group is `MVK_INVALID_WILDCARD` — and every
 * post-parse class, the error field's reference among them, answers after. In order, each stopping the chain:
 *
 * 1. the iteration entity is a group (`MVK_NO_FIELDS_ALLOWED`), repeatable (`MVK_GROUP_NOT_REPEATABLE`), and
 *    starred on that level (`MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED`);
 * 2. the term holds no filter and no nested term sum (`MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION`) — the
 *    application condition and `RepetitionNotUnique` the same check names reach a term only inside a filter;
 * 3. each spec the term writes, in the order it is written, iterates only levels on the iteration entity's path
 *    (`MVK_INVALID_ITERATION_IN_SET_EXPRESSION`), none of them below a star it writes itself (`MVK_NO_WILDCARD`),
 *    and selects no row by a semantic index (`MVK_SEMANTIC_INDEX_NOT_ALLOWED`); and some spec iterates the
 *    entity's own level (`MVK_NO_ITERATION_FOR_LOWEST_WILDCARD`);
 * 4. the term is a number (`MVK_NO_NUMBER`), judged here only for a sole read of a string field, the one kind
 *    measured.
 *
 * **Silent wherever it cannot read what A12 reads**: an iteration entity it cannot reduce to one declared
 * element, a spec it cannot resolve, a level above the written path that repeats. A class this family withholds
 * is under-reported; a class it guesses could be the wrong one.
 */
internal fun TrackedFactBase.sumOfTermsOperandFindings(): List<Finding> = declarations().flatMap { element ->
    sumOfTermsContributions(element).map { it.finding }
}

internal fun TrackedFactBase.sumOfTermsContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    if (execution == null) return emptyList()
    val operator = OPERATOR_SPELLINGS[conditionLanguage]?.get(OperatorId.SUM_OF_TERMS) ?: return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    return callContributions(element, execution) { member, call ->
        if (call.operator != operator || call.operands.size != 2) return@callContributions null
        val (iteration, term) = call.operands
        sumOfTermsCode(iteration, term, call, execution, group, element)
            ?.let { finding(it, element, member, call) }
    }
}

private fun TrackedFactBase.sumOfTermsCode(
    iteration: CallOperand,
    term: CallOperand,
    call: ConditionCall,
    execution: ConditionExecution,
    group: String,
    element: TrackedDeclaration,
): String? {
    val written = iteration.base ?: return null
    if (written.startsWith("$")) return null
    val entityPath = resolvedPathOf(written, group, element) ?: return null
    val entity = declarationAt(entityPath) ?: return null
    when {
        entity.declaredKind == DeclaredKind.FIELD -> return "MVK_NO_FIELDS_ALLOWED"
        entity.declaredKind != DeclaredKind.GROUP -> return null
        !entity.isRepeatableGroup -> return "MVK_GROUP_NOT_REPEATABLE"
        !written.trimEnd('/').endsWith("*") -> return "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED"
    }
    val inTerm = { start: Int -> start >= term.at && start < term.end }
    val sites = execution.recognition.sites
    if (sites.any { it is SyntaxSite.FilterEntry && inTerm(it.start) } ||
        execution.recognition.calls.any { it.operator == call.operator && it.at != call.at && inTerm(it.at) }
    ) return "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION"
    val specs = execution.recognition.entities
        .filter { inTerm(it.site.start) && !it.outerMarked }.sortedBy { it.site.start }
    val entityLevel = depthOf(entityPath)
    var lowestIterated = false
    for (spec in specs) {
        val levels = levelsOf(spec, group, element) ?: return null
        var starred = false
        for ((depth, level) in levels.withIndex()) when (level.kind) {
            LevelKind.SEMANTIC_INDEX -> return "MVK_SEMANTIC_INDEX_NOT_ALLOWED"
            LevelKind.WILDCARD -> starred = true
            LevelKind.ITERATION -> {
                if (!entityPath.isAtOrBelow(level.path)) return "MVK_INVALID_ITERATION_IN_SET_EXPRESSION"
                if (depth == entityLevel) lowestIterated = true
                if (starred) return "MVK_NO_WILDCARD"
            }
            LevelKind.FIXED -> Unit
        }
    }
    if (!lowestIterated) return "MVK_NO_ITERATION_FOR_LOWEST_WILDCARD"
    val read = soleFieldValueReadIn(execution.carrier.text.substring(term.at, term.end), conditionLanguage)
    val readType = read?.path?.let { resolvedPathOf(it, group, element) }
        ?.let { declarationAt(it, DeclaredKind.FIELD) }?.declaredFieldType()
    return if (readType == FieldType.STRING) "MVK_NO_NUMBER" else null
}

/** How A12 indexes one group level of a spec: starred, iterated (an unstarred repeatable level), keyed, or fixed. */
private enum class LevelKind { WILDCARD, ITERATION, SEMANTIC_INDEX, FIXED }

private class SpecLevel(val path: String, val kind: LevelKind)

/**
 * The group levels [spec] crosses, root first, each with the index A12 gives it; null where this reader cannot
 * tell. The written segments name the tail of the resolved path, so a star sits on the level its segment names;
 * a repeatable level above the written segments takes an index from the rule's own scope, which is unread here.
 * A keyed spec's index sits on the group that declares the index field, the nearest one up its path.
 */
private fun TrackedFactBase.levelsOf(spec: RecognizedEntity, group: String, element: TrackedDeclaration): List<SpecLevel>? {
    val written = spec.writtenPath
    val resolved = resolvedPathOf(written, group, element) ?: return null
    val segments = resolved.trimStart('/').split('/')
    val writtenSegments = written.trimStart('/').split('/').filter { it.isNotEmpty() && it != ".." && it != "." }
    if (writtenSegments.size > segments.size) return null
    val firstWritten = segments.size - writtenSegments.size
    val groups = (1..segments.size).map { "/" + segments.take(it).joinToString("/") }
        .mapNotNull { path -> declarationAt(path)?.takeIf { it.declaredKind == DeclaredKind.GROUP } }
    val keyed = if (spec.indexed) groups.lastOrNull { indexFieldNameOf(it) != null }?.path ?: return null else null
    return groups.map { level ->
        val depth = depthOf(level.path)
        val star = depth >= firstWritten && writtenSegments[depth - firstWritten].endsWith("*")
        if (depth < firstWritten && level.isRepeatableGroup) return null
        SpecLevel(level.path, when {
            level.path == keyed -> LevelKind.SEMANTIC_INDEX
            star -> LevelKind.WILDCARD
            level.isRepeatableGroup -> LevelKind.ITERATION
            else -> LevelKind.FIXED
        })
    }
}

/** The zero-based level of [path] below the root, so `/Policy` is 0 and `/Policy/Claims` is 1. */
private fun depthOf(path: String): Int = path.trimStart('/').count { it == '/' }

private fun finding(code: String, element: TrackedDeclaration, member: String, call: ConditionCall): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: `${OperatorId.SUM_OF_TERMS.catalogId}` adds its term once per row of a starred repeatable group, " +
        "and the term reads that group's fields unstarred — " + ADVICE.getValue(code),
    element.path,
    call.spanIn(member),
)

private val ADVICE = mapOf(
    "MVK_NO_FIELDS_ALLOWED" to "name the group to iterate, not one of its fields",
    "MVK_GROUP_NOT_REPEATABLE" to "name a repeatable group as the iteration entity",
    "MVK_WILDCARD_AT_LOWEST_LEVEL_REQUIRED" to "star the iteration entity's own level",
    "MVK_DISALLOWED_CONSTRUCT_IN_SET_EXPRESSION" to "move the filter or the nested term sum out of the term",
    "MVK_INVALID_ITERATION_IN_SET_EXPRESSION" to "read only the iteration entity's path in the term, or aggregate the other group",
    "MVK_NO_WILDCARD" to "leave unstarred every level of the term at or above the iteration entity",
    "MVK_SEMANTIC_INDEX_NOT_ALLOWED" to "read the term's fields without a semantic index",
    "MVK_NO_ITERATION_FOR_LOWEST_WILDCARD" to "read at least one field of the iteration entity in the term",
    "MVK_NO_NUMBER" to "make the term a number",
)
