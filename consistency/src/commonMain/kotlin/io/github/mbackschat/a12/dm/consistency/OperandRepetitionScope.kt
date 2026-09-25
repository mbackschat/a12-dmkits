package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.HAVING
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.firstReferenceSpansIn
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.namesKeyword
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * **On a text carrying a `Having` filter this reads the operand BASES only.** A filter body's references are
 * judged against the FILTERED entity rather than the rule's target — that walk is [filterBodyGateFindings]' —
 * and the flat reference list cannot tell the two apart, so a filtered text is read through its calls
 * instead, where the base is exactly the operand without its clauses and is judged by this walk like any
 * other reference. Until 2026-09-15 the whole text was skipped, which threw the base away with the body and
 * left an unkeyed parallel level under a filtered operand unnamed (measured 2026-09-14,
 * `rulekit.validate.laws.FilteredEntityParallelIterationLawsTest`; the reach is
 * `rulekit.validate.laws.AggregateSlotLawsTest.whichClassAnUnstarredAggregateOperandDrawsOnEachIterationCarrier`).
 *
 * **Still silent on a semantic index**, where the spelling consumes the star and an indexed reference names
 * one value rather than a repetition — a shape this walk has not been measured on.
 *
 * **Silent where `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` answers first.** That gate runs at the top of the same
 * method, on the first iterating level, and fires whenever the target lies outside the declaring group —
 * so a target outside it is never this family's to judge. Our own `Rule.build()` refuses that pair up front
 * and is measured OVER-REJECTING there ([KF212]), which is a second reason not to inherit the shape.
 */
internal fun TrackedFactBase.operandRepetitionScopeFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val unjudgeable = setOfNotNull(keywords[SEMANTIC_INDEX])
    val filterKeyword = setOfNotNull(keywords[HAVING])
    val targets = declaredReferences().associateBy { it.declaredAt }
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        val target = (targets[element.path]?.target as? Reference.Resolved)?.path ?: return@mapNotNull null
        val targetGroup = groupOfTargetField(target) ?: return@mapNotNull null
        val declaringGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        if (!(target == declaringGroup || target.startsWith("$declaringGroup/"))) {
            return@mapNotNull null
        }
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            if (namesKeyword(text, unjudgeable) != null) {
                return@firstNotNullOfOrNull null
            }
            val own = withoutFilterBodies(text, member, filterKeyword, conditionLanguage)
            if (OUTER_MARKER in own) {
                return@firstNotNullOfOrNull null
            }
            val filtered = filteredBases(text, member, filterKeyword, conditionLanguage)
            firstReferenceSpansIn(own, conditionLanguage)
                .filterNot { isRuleContext(it.written, conditionLanguage) }
                .firstNotNullOfOrNull { occurrence ->
                    val written = occurrence.written
                    val span = TextSpan(member, occurrence.start, occurrence.end)
                    levelClassOf(written, declaringGroup, targetGroup, element, filtered = written in filtered)
                        ?.let { scopeFinding(it, written, target, element, span) }
                }
        }
    }
}

internal fun TrackedFactBase.referencesOutsideFilterBodies(element: TrackedDeclaration): Set<String> {
    val language = conditionLanguage
    val keywords = CONDITION_OPERATOR_KEYWORDS[language] ?: return emptySet()
    val filterKeyword = setOfNotNull(keywords[HAVING])
    val declared = element.body ?: return emptySet()
    return conditionTexts(element.kind, declared)
        .flatMap { (member, text) ->
            referencesIn(withoutFilterBodies(text, member, filterKeyword, language), language)
        }
        .toSet()
}

/**
 * [text] with every `Having` filter body blanked IN PLACE, one space per character.
 *
 * In place so that an offset into the result is an offset into [text], which is what lets a finding read off
 * it point at the author's characters. And by POSITION rather than by matching the body's text, which would
 * also blank a conjunct that happens to spell it — a read A12 still judges
 * (`rulekit.validate.laws.FilterBodyTextRepeatedOutsideLawsTest`).
 */
private fun withoutFilterBodies(
    text: String,
    member: String,
    filterKeyword: Set<String>,
    conditionLanguage: ConditionLanguage,
): String {
    val blanked = text.toCharArray()
    callsIn(text, conditionLanguage, syntaxEntryOf(member))
        .flatMap { call -> call.operands }
        .flatMap { operand -> operand.clauses }
        .filter { it.keyword in filterKeyword }
        .forEach { body -> blanked.fill(' ', body.at, body.at + body.text.length) }
    return blanked.concatToString()
}

/** The operands [text] hands to a `Having` filter, by their base. */
private fun filteredBases(
    text: String,
    member: String,
    filterKeyword: Set<String>,
    conditionLanguage: ConditionLanguage,
): Set<String> = callsIn(text, conditionLanguage, syntaxEntryOf(member))
    .flatMap { call -> call.operands }
    .filter { operand -> operand.clauses.any { it.keyword in filterKeyword } }
    .mapNotNull { it.base }
    .toSet()

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
 * reads as not refused, because the walk is not judged on one and silence here would be a guess.
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
        if (operand.clauses.any { it.keyword in keyed }) {
            return@any false
        }
        if (contained) {
            val filtered = operand.clauses.any { it.keyword in filter }
            return@any levelClassOf(base, declaringGroup, targetGroup, element, filtered) != null
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
 * @return null wherever the reference is admitted, or is a shape another family owns.
 */
internal fun TrackedFactBase.levelClassOf(
    written: String,
    declaringGroup: String,
    targetGroup: String,
    element: TrackedDeclaration,
    filtered: Boolean = false,
): String? {
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    val declared = declarationAt(resolved.path) ?: return null
    val isGroup = declared.declaredKind == DeclaredKind.GROUP
    if (!isGroup && declared.declaredKind != DeclaredKind.FIELD || isGroup && filtered) {
        return null
    }
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    val levels = crossedRepetitions(resolved.path) +
        listOfNotNull(declared.takeIf { isGroup && it.isRepeatableGroup })
    var wildcardSeen = false
    levels.forEach { level ->
        if (level.path in starred) {
            wildcardSeen = true
            return@forEach
        }
        when {
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
    val operandFamily = baseFamilyOf(operandIndex.body) ?: return ParallelVerdict.Unjudged
    val targetFamily = baseFamilyOf(targetIndex.body) ?: return ParallelVerdict.Unjudged
    return if (operandFamily == targetFamily) {
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

/**
 * The base type A12 compares two index fields by, or null for every declared type this has not read.
 *
 * `IFieldType.matchBaseType` is implemented per type interface, and the four below are the ones whose
 * implementation was read: each admits only its own interface, so a difference between two of them is a
 * mismatch on any reading. Every other declared type answers null and the type class is withheld — a
 * conservative silence, because `IStringType` also admits `IPredefinedType` and a base type shared across
 * two declared types would otherwise become an invented rejection.
 */
private fun baseFamilyOf(body: JsonObject?): String? {
    val declared = (body?.get("fieldType") as? JsonObject)?.get("type") as? JsonPrimitive
    return BASE_FAMILIES[declared?.contentOrNull]
}

private val BASE_FAMILIES = mapOf(
    "StringType" to "string",
    "NumberType" to "number",
    "DateType" to "date",
    "EnumerationType" to "enumeration",
)

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
