package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
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
fun PreparedModel.operandRepetitionScopeFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val unjudgeable = setOfNotNull(keywords[SEMANTIC_INDEX])
    val filterKeyword = setOfNotNull(keywords[HAVING])
    val targets = declaredReferences().associateBy { it.declaredAt }
    return elements.mapNotNull { element ->
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
            referencesIn(own, conditionLanguage)
                .filterNot { isRuleContext(it, conditionLanguage) }
                .firstNotNullOfOrNull { written ->
                    levelClassOf(written, declaringGroup, targetGroup, element)
                        ?.let { scopeFinding(it, written, target, element, member) }
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

private fun withoutFilterBodies(
    text: String,
    member: String,
    filterKeyword: Set<String>,
    conditionLanguage: ConditionLanguage,
): String = callsIn(text, conditionLanguage, syntaxEntryOf(member))
    .flatMap { call -> call.operands }
    .flatMap { operand -> operand.clauses }
    .filter { it.keyword in filterKeyword }
    .map { it.text }
    .filter { it.isNotEmpty() }
    .fold(text) { stripped, bodyText -> stripped.replace(bodyText, " ") }

private const val HAVING = "Having"

private const val SEMANTIC_INDEX = "SemanticIndex"

/** The group whose repetitions A12 addresses the target by, or null when the target is not a FIELD. */
internal fun PreparedModel.groupOfTargetField(target: String): String? =
    groupOfTargetFieldIn(elements.any { it.path == target && it.declaredKind == DeclaredKind.FIELD }, target)

/** The same question through the read barrier; it reads the set's shape and no body. */
internal fun TrackedFactBase.groupOfTargetField(target: String): String? = groupOfTargetFieldIn(
    declarationAt(target)?.declaredKind == DeclaredKind.FIELD, target)

private fun groupOfTargetFieldIn(namesAField: Boolean, target: String): String? =
    if (namesAField) target.substringBeforeLast('/', missingDelimiterValue = "") else null

/**
 * The class ONE reference draws, walking the repeatable levels it crosses from the outermost inwards.
 *
 * @return null wherever the reference is admitted, or is a shape another family owns.
 */
internal fun PreparedModel.levelClassOf(
    written: String,
    declaringGroup: String,
    targetGroup: String,
    element: DeclaredElement,
): String? = levelClassIn(
    written,
    targetGroup,
    resolve = { bare -> resolveEntity(declaringGroup, bare, element.origin) },
    namesAField = { path -> elements.any { it.path == path && it.declaredKind == DeclaredKind.FIELD } },
    starredLevels = { starredLevelsOf(it, declaringGroup, element) },
    crossedRepetitions = { crossedRepetitions(it) },
    pathOf = { it.path },
    parallelVerdict = { level, group -> parallelVerdictOf(level, group) },
)

/** The same ordered walk through the read barrier, over the tracked arm of every read it makes. */
internal fun TrackedFactBase.levelClassOf(
    written: String,
    declaringGroup: String,
    targetGroup: String,
    element: TrackedDeclaration,
): String? = levelClassIn(
    written,
    targetGroup,
    resolve = { bare -> resolveEntity(declaringGroup, bare, element.origin) },
    namesAField = { path -> declarationAt(path)?.declaredKind == DeclaredKind.FIELD },
    starredLevels = { starredLevelsOf(it, declaringGroup, element) },
    crossedRepetitions = { crossedRepetitions(it) },
    pathOf = { it.path },
    parallelVerdict = { level, group -> parallelVerdictOf(level, group) },
)

/**
 * The walk itself, kept as ONE implementation because its order is the law.
 *
 * Five gates answer in source order and three of them return a different class, so two copies of this — one
 * per carrier — could disagree about which class a level draws while each arm's own tests stayed green. The
 * lambdas are what let the two carriers share it; they also keep the walk LAZY, so the tracked arm charges
 * only the declarations an early return actually reached.
 */
private inline fun <T> levelClassIn(
    written: String,
    targetGroup: String,
    resolve: (String) -> Reference,
    namesAField: (String) -> Boolean,
    starredLevels: (String) -> Set<String>?,
    crossedRepetitions: (String) -> List<T>,
    pathOf: (T) -> String,
    parallelVerdict: (T, String) -> ParallelVerdict,
): String? {
    val resolved = resolve(written.replace("*", "")) as? Reference.Resolved ?: return null
    // A reference naming a GROUP is judged by the group-condition families, whose operators run their own
    // wildcard gates ahead of this walk; only a FIELD read reaches it with nothing else to say about it.
    if (!namesAField(resolved.path)) {
        return null
    }
    val starred = starredLevels(written) ?: return null
    var wildcardSeen = false
    crossedRepetitions(resolved.path).forEach { level ->
        val levelPath = pathOf(level)
        if (levelPath in starred) {
            wildcardSeen = true
            return@forEach
        }
        when {
            wildcardSeen -> return NO_WILDCARD
            levelPath.startsWith("$targetGroup/") -> return NO_WILDCARD
            targetGroup == levelPath || targetGroup.startsWith("$levelPath/") -> Unit
            else -> when (val verdict = parallelVerdict(level, targetGroup)) {
                is ParallelVerdict.Refused -> return verdict.code
                ParallelVerdict.Joint, ParallelVerdict.Unjudged -> Unit
            }
        }
    }
    return null
}

private const val NO_WILDCARD = "MVK_NO_WILDCARD"

private const val MULTIPLE_INDEX_FIELDS = "MVK_MULTIPLE_INDEX_FIELDS"


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
internal fun PreparedModel.parallelVerdictOf(
    level: DeclaredElement,
    targetGroup: String,
): ParallelVerdict = parallelVerdictIn(
    level,
    targetGroup,
    pathOf = { it.path },
    indexNameOf = { indexFieldNameOf(it) },
    keyedGroupsAtOrAbove = { keyedGroupsAtOrAbove(it) },
    indexFieldAt = { group, name -> indexFieldAt(group, name) },
    baseFamilyOf = { baseFamilyOf(it.body) },
)

/**
 * The same verdict through the read barrier, over the tracked arm of every read it makes.
 *
 * Passed as lambdas rather than pre-computed values on purpose: the walk short-circuits at five points, and
 * pre-reading what an early return never reaches would charge a body this verdict did not depend on. Safe —
 * over-charging can only lose reuse — but the whole slice exists to keep the charge tight.
 */
internal fun TrackedFactBase.parallelVerdictOf(
    level: TrackedDeclaration,
    targetGroup: String,
): ParallelVerdict = parallelVerdictIn(
    level,
    targetGroup,
    pathOf = { it.path },
    indexNameOf = { indexFieldNameOf(it) },
    keyedGroupsAtOrAbove = { keyedGroupsAtOrAbove(it) },
    indexFieldAt = { group, name -> indexFieldAt(group, name) },
    baseFamilyOf = { baseFamilyOf(it.body) },
)

private inline fun <T> parallelVerdictIn(
    level: T,
    targetGroup: String,
    pathOf: (T) -> String,
    indexNameOf: (T) -> String?,
    keyedGroupsAtOrAbove: (String) -> List<T>,
    indexFieldAt: (String, String) -> T?,
    baseFamilyOf: (T) -> String?,
): ParallelVerdict {
    val operandIndexName = indexNameOf(level) ?: return ParallelVerdict.Refused(NO_WILDCARD)
    val operandChain = keyedGroupsAtOrAbove(pathOf(level))
    if (operandChain.size > 1) {
        return ParallelVerdict.Refused(MULTIPLE_INDEX_FIELDS)
    }
    // A declared index field that names nothing in its own group is A12's `MVK_INDEX_FIELD_INVALID`, and
    // that model shape is measured NOT BUILDABLE with no code at all (KF295) — so it cannot arrive here,
    // and silence is what a reader of a model that somehow carries it should get.
    val operandIndex = indexFieldAt(pathOf(level), operandIndexName) ?: return ParallelVerdict.Unjudged
    val targetChain = keyedGroupsAtOrAbove(targetGroup)
    if (targetChain.size > 1) {
        return ParallelVerdict.Refused(MULTIPLE_INDEX_FIELDS)
    }
    val keyedForTheTarget = targetChain.firstOrNull()
        ?: return ParallelVerdict.Refused("MVK_ERROR_FIELD_NO_INDEX")
    val targetIndexName = indexNameOf(keyedForTheTarget) ?: return ParallelVerdict.Unjudged
    if (targetIndexName != operandIndexName) {
        return ParallelVerdict.Refused("MVK_ERROR_FIELD_INVALID_INDEX")
    }
    val targetIndex = indexFieldAt(pathOf(keyedForTheTarget), targetIndexName)
        ?: return ParallelVerdict.Unjudged
    val operandFamily = baseFamilyOf(operandIndex) ?: return ParallelVerdict.Unjudged
    val targetFamily = baseFamilyOf(targetIndex) ?: return ParallelVerdict.Unjudged
    return if (operandFamily == targetFamily) {
        ParallelVerdict.Joint
    } else {
        ParallelVerdict.Refused("MVK_ERROR_FIELD_INVALID_INDEX_TYPE")
    }
}

/** Every group at or above [path] that declares an index field — A12's `getIndexGroup` walk. */
private fun PreparedModel.keyedGroupsAtOrAbove(path: String): List<DeclaredElement> = elements
    .filter { it.keysAGroupAtOrAbove(path) && indexFieldNameOf(it) != null }

private fun TrackedFactBase.keyedGroupsAtOrAbove(path: String): List<TrackedDeclaration> = declarations()
    .filter { it.keysAGroupAtOrAbove(path) && indexFieldNameOf(it) != null }

private fun DeclaredElement.keysAGroupAtOrAbove(path: String): Boolean =
    declaredKind == DeclaredKind.GROUP && (path == this.path || path.startsWith("${this.path}/"))

private fun TrackedDeclaration.keysAGroupAtOrAbove(path: String): Boolean =
    declaredKind == DeclaredKind.GROUP && (path == this.path || path.startsWith("${this.path}/"))

private fun PreparedModel.indexFieldAt(group: String, name: String): DeclaredElement? =
    elements.firstOrNull { it.path == "$group/$name" && it.declaredKind == DeclaredKind.FIELD }

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
    element: DeclaredElement,
    member: String,
) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `$member`: `$written` reads a repetition that `$target`'s own coordinates do not address — " +
        "${REMEDIES.getValue(code)}",
    element.path,
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
