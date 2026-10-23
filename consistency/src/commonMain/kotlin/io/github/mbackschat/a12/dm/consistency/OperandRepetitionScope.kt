package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.SemanticIndexUse
import io.github.mbackschat.a12.dm.consistency.syntax.isLexerBlank
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import io.github.mbackschat.a12.dm.consistency.syntax.referenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.rulesOwnReadsIn
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn

/**
 * Which repetitions an operand may cross unstarred, given where the rule's error field — or the
 * computation's computed field — sits.
 *
 * A12 walks every LEVEL of every reference in the text and asks, for each one it iterates, whether the
 * target's coordinates address that repetition. The walk is `CheckEntityUtils.checkIndicesForA12Model` and
 * it is one construct with one gate chain, so the five classes below are one family rather than five
 * ([KERNEL-FINDINGS KF371], `rulekit.validate.laws.ParallelIterationRouteLawsTest` and
 * `…RuleOperandScopeLawsTest`).
 *
 * The chain, in source order, for each level the reference iterates:
 *
 * | the level | class |
 * |---|---|
 * | sits below a level the same reference STARS | `MVK_NO_WILDCARD` |
 * | sits strictly below the target's group | `MVK_NO_WILDCARD` |
 * | is the target's group or an ancestor of it | admitted — the target's coordinates address it |
 * | is PARALLEL and declares no index field | `MVK_NO_WILDCARD` |
 * | is PARALLEL, and two groups on its own chain declare one | `MVK_MULTIPLE_INDEX_FIELDS` |
 * | is PARALLEL, and nothing on the TARGET's chain declares one | `MVK_ERROR_FIELD_NO_INDEX` |
 * | is PARALLEL, and the two index fields differ in NAME | `MVK_ERROR_FIELD_INVALID_INDEX` |
 * | is PARALLEL, same name, different base TYPE | `MVK_ERROR_FIELD_INVALID_INDEX_TYPE` |
 * | is PARALLEL, same name and type | admitted — a legal parallel iteration |
 *
 * **The per-level walk outranks the gate order inside one level**, which is the row a reading of the chain
 * alone gets wrong: the same doubly-keyed operand draws `MVK_MULTIPLE_INDEX_FIELDS` into a keyed target and
 * `MVK_ERROR_FIELD_NO_INDEX` into an unkeyed one, because the OUTER level is refused before the inner one
 * is ever asked. So this reports the first level in path order, not the first gate in source order.
 *
 * **The same chain runs for a rule and for a computation**, measured cell by cell on both carriers rather
 * than transferred ([KF212], `ComputationDeclaringGroupLawsTest`) — the computed field takes the error
 * field's role, and the declaring group is not a variable in it.
 *
 * **On a text carrying a `Having` filter this reads what A12 checks against the rule's target** ([rulesOwnReadsIn]):
 * the operand BASES, and a `$`-marked read in a body, which is judged exactly as the same read outside any
 * filter ([KERNEL-FINDINGS KF487]). Every other body reference is judged against the FILTERED entity rather than
 * the rule's target — that walk is [filterBodyGateFindings]' — and the flat reference list cannot tell the two
 * apart, so they are set aside by position — found by delimiters rather than by a parse, because a text that
 * fails to parse later still has its earlier reads judged. A
 * filtered base answers once its whole spec is read, so a defect inside the body comes first (both measured,
 * `rulekit.validate.laws.ConditionClassWithholdingLawsTest.aFilteredOperandsDefectsAnswerInTheOrderA12ReadsThem`).
 * Until 2026-09-15 the whole text was skipped, which threw the base away with the body and
 * left an unkeyed parallel level under a filtered operand unnamed (measured 2026-09-14,
 * `rulekit.validate.laws.FilteredEntityParallelIterationLawsTest`; the reach is
 * `rulekit.validate.laws.AggregateSlotLawsTest.whichClassAnUnstarredAggregateOperandDrawsOnEachIterationCarrier`).
 *
 * **A keyed value is walked with the level its key addresses, and its key is not walked at all.** A key the
 * index chain admits addresses the level of the group declaring the index and no other, so the walk treats
 * that one level as the target's coordinates would and asks every other level as usual: `Cover` then `Items`
 * then `Amount`, keyed on an index the root declares, is `MVK_NO_WILDCARD` ([KERNEL-FINDINGS KF478]). A key
 * the chain refuses answers with the chain's class first — the same path keyed where nothing declares an
 * index is `MVK_NO_INDEX_FIELD` — so its value is silent here, and a key's own reference is the chain's too.
 * Every other reference in the text is walked, because A12 raises an earlier unaddressed read ahead of a
 * later keyed one: `[Riders/Memo] == "a" And Sum(Claims/Amount For "X") > 0` is `MVK_NO_WILDCARD`
 * (`rulekit.validate.laws.SemanticIndexPinnedLevelLawsTest`).
 *
 * **Silent where `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` answers first.** That gate runs at the top of the same
 * method, on the first iterating level, and fires whenever the target lies outside the declaring group —
 * so a target outside it is never this family's to judge. Our own `Rule.build()` refuses that pair up front
 * and is measured OVER-REJECTING there ([KF212]), which is a second reason not to inherit the shape.
 */
internal fun TrackedFactBase.operandScopeContributions(element: TrackedDeclaration): List<ConditionFinding> {
    val target = (element.declaredReference()?.target as? Reference.Resolved)?.path ?: return emptyList()
    val targetGroup = groupOfTargetField(target) ?: return emptyList()
    val group = element.path.substringBeforeLast('/')
    if (!(target == group || target.startsWith("$group/"))) return emptyList()
    return entityContributions(element, EntityCheck.ADDRESS) { entity, span ->
        if (entity.indexKey || entity.inFilter && entity.path.first().text != "$") return@entityContributions null
        val written = normalizedPath(entity.path.dropWhile { it.text == "$" })
        if (isRuleContext(written, conditionLanguage)) return@entityContributions null
        levelClassOf(written, group, targetGroup, element, entity.filtered, entity.indexed)
            ?.let { scopeFinding(it, written, target, element, span) }
    }
}

/** Whether the reference ending at [end] is the value this index keys, written right before its keyword. */
private fun SemanticIndexUse.indexes(end: Int, text: String): Boolean =
    end <= keywordAt && text.substring(end, keywordAt).isLexerBlank()

/**
 * The references [element] reads as the rule's own — the ones A12 checks against the rule's target
 * ([rulesOwnReadsIn]), which is where a per-row read draws its iteration classes.
 */
internal fun TrackedFactBase.rulesOwnReferences(element: TrackedDeclaration): Set<String> {
    val declared = element.body ?: return emptySet()
    return conditionTexts(element.kind, declared)
        .flatMap { (_, text) -> referencesIn(rulesOwnReadsIn(text, conditionLanguage), conditionLanguage) }
        .toSet()
}

/**
 * Ordinary iteration levels of the rule's own authored references. Selection replaces only the indexed
 * payload level; its ancestors and the field-valued key retain their own scope (SPEC-2026-10-10-03,
 * `SelectedAuthoredScopeDiffTest`). Repeated spellings union their levels so an ordinary error-field
 * guard binds the selected level regardless of which occurrence appears first.
 */
internal fun TrackedFactBase.rulesOwnIterationLevels(element: TrackedDeclaration): Map<String, Set<String>>? {
    val declared = element.body ?: return emptyMap()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX)
    val levelsByReference = mutableMapOf<String, MutableSet<String>>()
    for ((_, text) in conditionTexts(element.kind, declared)) {
        val indexes = keyword?.let { semanticIndexUsesIn(text, conditionLanguage, it) }.orEmpty()
        for (read in referenceSpansIn(rulesOwnReadsIn(text, conditionLanguage), conditionLanguage)) {
            val path = (resolveEntity(group, read.written.replace("*", ""), element.origin) as? Reference.Resolved)
                ?.path ?: return null
            val levels = ordinaryRepeatableLevels(read.written, path, group, element,
                indexed = indexes.any { it.indexes(read.end, text) }) ?: return null
            levelsByReference.getOrPut(read.written) { mutableSetOf() }.addAll(levels)
        }
    }
    return levelsByReference
}

/** Selection replaces one indexed level, while stars replace only their own levels (KF488). Sharing this
 * calculation keeps parse-time containment and post-parse binding on the same authored scope. */
internal fun TrackedFactBase.ordinaryRepeatableLevels(
    written: String,
    path: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    indexed: Boolean,
): List<String>? {
    val levels = unstarredRepeatableLevels(written, path, declaringGroup, element) ?: return null
    val selected = if (indexed) indexGroupOf(path) else null
    return levels.filter { it != selected }
}

/**
 * Whether A12 refuses one of [call]'s operands while READING it, so the operator's own gates never run.
 *
 * A12 checks an operand's entity spec as it parses that operand, and the operator's own check runs on the
 * finished call: `CheckKontextListenBedingungImpl.check` is handed an operand list whose members have already
 * been read. So a class the read draws answers for the whole call. There are two such classes here:
 * containment, for a per-row read into a target outside the declaring group, and this family's walk, for a
 * read into a target inside it. Both were measured ahead of the star bars on both group-condition arms, the
 * group-list duplicate, overlap and root gates, and the field-kind gate
 * (`rulekit.validate.laws.PartialStarContainmentLawsTest.anOperatorsOwnGatesWaitForItsOperandsRead`).
 *
 * **Not [addressedTargetOf]'s question.** That one also yields on a legal PARALLEL walk, which A12 admits
 * before asking the operator's gates, so answering it here would silence a class A12 draws. A keyed operand
 * is walked as this family walks it, with its key's level addressed, and one whose key the chain refuses
 * reads as not refused, the chain's class being another family's.
 */
internal fun TrackedFactBase.anOperandIsRefusedOnRead(call: ConditionCall, element: TrackedDeclaration): Boolean {
    val target = declaredReferences().firstOrNull {
        it.declaredAt == element.path &&
            (it.role == ReferenceRole.ERROR_TARGET || it.role == ReferenceRole.COMPUTED_TARGET)
    }?.target as? Reference.Resolved ?: return false
    val targetGroup = groupOfTargetField(target.path) ?: return false
    val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val contained = target.path == declaringGroup || target.path.startsWith("$declaringGroup/")
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]
    val keyed = setOfNotNull(keywords?.get(SEMANTIC_INDEX))
    val filter = setOfNotNull(keywords?.get(HAVING))
    return call.operands.any { operand ->
        val base = operand.base ?: return@any false
        val isKeyed = operand.clauses.any { it.keyword in keyed }
        if (isKeyed && operandReferenceIn(operand.text, declaringGroup, element) { null }?.indexSelected != true) {
            return@any false
        }
        if (contained) {
            val filtered = operand.clauses.any { it.keyword in filter }
            return@any levelClassOf(base, declaringGroup, targetGroup, element, filtered, isKeyed) != null
        }
        if (isKeyed) {
            return@any false
        }
        val resolved = resolveEntity(declaringGroup, base.replace("*", ""), element.origin)
        val path = (resolved as? Reference.Resolved)?.path ?: return@any false
        unstarredRepeatableLevels(base, path, declaringGroup, element)?.isNotEmpty() == true
    }
}

/** The group whose repetitions A12 addresses the target by, or null when the target is not a FIELD. */
internal fun TrackedFactBase.groupOfTargetField(target: String): String? =
    if (declarationAt(target)?.declaredKind == DeclaredKind.FIELD) {
        target.substringBeforeLast('/', missingDelimiterValue = "")
    } else {
        null
    }

/**
 * The class ONE reference draws, walking the repeatable levels it crosses from the outermost inwards.
 *
 * **A GROUP is walked like a field, its own repetition included**: an unstarred repeatable group read from
 * outside it is `MVK_NO_WILDCARD`, or `MVK_ERROR_FIELD_NO_INDEX` from a parallel level that declares an index
 * field, on all twenty-two carriers measured, the group quantifiers and the aggregates among them, so no
 * carrier's own gate answers first (`rulekit.validate.laws
 * .GroupOperandRepetitionScopeLawsTest`), and a `@From` scope is walked the same way
 * (`rulekit.validate.laws.RepetitionNotUniqueFromScopeLawsTest`). A repeatable ROOT group is no exception:
 * its root classes answer only once the walk passes (`rulekit.validate.laws.GroupListRootOperandLawsTest`).
 * The one exception is a group handed to a `Having` filter, which is refused as a group ahead of the walk
 * ([filtered]). On a group the index classes are measured on those carriers, for the group's own level and for
 * one above a starred group: the missing index (`rulekit.validate.laws.GroupOperandRepetitionScopeLawsTest`,
 * `…ComputationStarBindingLawsTest`), and the index of another name or type beside a keyed error field
 * (`…KeyedParallelGroupOperandLawsTest`). `MVK_MULTIPLE_INDEX_FIELDS` is not measured on a group, so it is
 * left unjudged there rather than given a field's.
 *
 * @param filtered whether [written] is the base of a `Having` filter.
 * @param keyed whether [written] is the value of a key the index chain admits.
 * @return null wherever the reference is admitted, or is a shape another family owns.
 */
internal fun TrackedFactBase.levelClassOf(
    written: String,
    declaringGroup: String,
    targetGroup: String,
    element: TrackedDeclaration,
    filtered: Boolean = false,
    keyed: Boolean = false,
): String? {
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    val declared = declarationAt(resolved.path) ?: return null
    // The one level an admitted key addresses ([KERNEL-FINDINGS KF478]).
    val pinned = if (keyed) indexGroupOf(resolved.path) else null
    val isGroup = declared.declaredKind == DeclaredKind.GROUP
    if (!isGroup && declared.declaredKind != DeclaredKind.FIELD || isGroup && filtered) {
        return null
    }
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    val levels = repetitionsReachedBy(resolved.path)
    var wildcardSeen = false
    levels.forEach { level ->
        if (level.path in starred) {
            wildcardSeen = true
            return@forEach
        }
        when {
            // A keyed level below a starred one was never measured, so the walk says nothing past it.
            level.path == pinned -> if (wildcardSeen) return null
            wildcardSeen -> return NO_WILDCARD
            level.path.startsWith("$targetGroup/") -> return NO_WILDCARD
            targetGroup == level.path || targetGroup.startsWith("${level.path}/") -> Unit
            else -> when (val verdict = parallelVerdictOf(level, targetGroup)) {
                is ParallelVerdict.Refused -> return verdict.code.takeUnless { isGroup && it !in GROUP_PARALLEL_CLASSES }
                ParallelVerdict.Joint, ParallelVerdict.Unjudged -> Unit
            }
        }
    }
    return null
}

private const val NO_WILDCARD = "MVK_NO_WILDCARD"

private const val MULTIPLE_INDEX_FIELDS = "MVK_MULTIPLE_INDEX_FIELDS"

/** The parallel-level classes measured on a GROUP operand; [levelClassOf] withholds the others there. */
private val GROUP_PARALLEL_CLASSES = setOf(
    NO_WILDCARD,
    "MVK_ERROR_FIELD_NO_INDEX",
    "MVK_ERROR_FIELD_INVALID_INDEX",
    "MVK_ERROR_FIELD_INVALID_INDEX_TYPE",
)

/**
 * What A12 answers about a PARALLEL level — one the target's coordinates neither contain nor lie under.
 *
 * Three outcomes rather than a nullable class, because the two silences are different claims and a second
 * family now depends on which one it got: [Joint] says A12 iterates the two branches TOGETHER, which is the
 * precondition every whole-rule gate in `ParserService.checkNormalRule` runs behind, where [Unjudged] says
 * this reader declined to decide. Only [Joint] may be read as an admission.
 */
internal sealed interface ParallelVerdict {

    /** A12 iterates the level jointly with the target's own chain. */
    data object Joint : ParallelVerdict

    /** A12 refuses the level with [code], during the parse. */
    data class Refused(val code: String) : ParallelVerdict

    /** A shape this reader declines to judge, so neither half may be claimed of it. */
    data object Unjudged : ParallelVerdict
}

/**
 * Whether A12 iterates [level] jointly with [targetGroup]'s chain, and with what class if not.
 *
 * A12's route here is `checkParallelIteration`, which asks whether the two branches iterate JOINTLY: each
 * end must resolve to exactly one index field, and the two must agree. The gates are transcribed in source
 * order, and the `getIndexGroup` walk that resolves each end is what can answer with the multiple-index
 * class before either error-field comparison happens.
 */
internal fun TrackedFactBase.parallelVerdictOf(
    level: TrackedDeclaration,
    targetGroup: String,
): ParallelVerdict {
    val operandIndexName = indexFieldNameOf(level) ?: return ParallelVerdict.Refused(NO_WILDCARD)
    val operandChain = keyedGroupsAtOrAbove(level.path)
    if (operandChain.size > 1) {
        return ParallelVerdict.Refused(MULTIPLE_INDEX_FIELDS)
    }
    // A declared index field that names nothing in its own group is A12's `MVK_INDEX_FIELD_INVALID`, and
    // that model shape is measured NOT BUILDABLE with no code at all (KF295) — so it cannot arrive here,
    // and silence is what a reader of a model that somehow carries it should get.
    val operandIndex = indexFieldAt(level.path, operandIndexName) ?: return ParallelVerdict.Unjudged
    val targetChain = keyedGroupsAtOrAbove(targetGroup)
    if (targetChain.size > 1) {
        return ParallelVerdict.Refused(MULTIPLE_INDEX_FIELDS)
    }
    val keyedForTheTarget = targetChain.firstOrNull()
        ?: return ParallelVerdict.Refused("MVK_ERROR_FIELD_NO_INDEX")
    val targetIndexName = indexFieldNameOf(keyedForTheTarget) ?: return ParallelVerdict.Unjudged
    if (targetIndexName != operandIndexName) {
        return ParallelVerdict.Refused("MVK_ERROR_FIELD_INVALID_INDEX")
    }
    val targetIndex = indexFieldAt(keyedForTheTarget.path, targetIndexName)
        ?: return ParallelVerdict.Unjudged
    val operandClass = operandIndex.declaredFieldType()?.baseTypeClass ?: return ParallelVerdict.Unjudged
    val targetClass = targetIndex.declaredFieldType()?.baseTypeClass ?: return ParallelVerdict.Unjudged
    return if (operandClass == targetClass) {
        ParallelVerdict.Joint
    } else {
        ParallelVerdict.Refused("MVK_ERROR_FIELD_INVALID_INDEX_TYPE")
    }
}

/** Every group at or above [path] that declares an index field — A12's `getIndexGroup` walk. */
private fun TrackedFactBase.keyedGroupsAtOrAbove(path: String): List<TrackedDeclaration> = allDeclarations()
    .filter { it.keysAGroupAtOrAbove(path) && indexFieldNameOf(it) != null }

private fun TrackedDeclaration.keysAGroupAtOrAbove(path: String): Boolean =
    declaredKind == DeclaredKind.GROUP && (path == this.path || path.startsWith("${this.path}/"))

private fun TrackedFactBase.indexFieldAt(group: String, name: String): TrackedDeclaration? =
    declarationAt("$group/$name")?.takeIf { it.declaredKind == DeclaredKind.FIELD }

private fun scopeFinding(
    code: String,
    written: String,
    target: String,
    element: TrackedDeclaration,
    span: TextSpan,
) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `${span.member}`: `$written` reads a repetition that `$target`'s own coordinates do not address — " +
        "${REMEDIES.getValue(code)}",
    element.path,
    span,
)

private val REMEDIES = mapOf(
    NO_WILDCARD to "star the level to read the whole list at once, or report on a field inside it",
    MULTIPLE_INDEX_FIELDS to "A12 resolves ONE index field per path and two groups on this one declare " +
        "theirs, so it cannot say which repetitions are matched",
    "MVK_ERROR_FIELD_NO_INDEX" to "the two branches can only iterate together on a shared index field, " +
        "and no group above the target declares one",
    "MVK_ERROR_FIELD_INVALID_INDEX" to "the two branches iterate together only on an index field of the " +
        "SAME name, and these two differ",
    "MVK_ERROR_FIELD_INVALID_INDEX_TYPE" to "the two index fields share a name but not a type, and A12 " +
        "matches repetitions by value",
)
