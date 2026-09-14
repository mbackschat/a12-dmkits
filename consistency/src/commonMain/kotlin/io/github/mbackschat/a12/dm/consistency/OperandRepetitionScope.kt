package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
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
 * **Silent where `MVK_ERROR_FIELD_NOT_IN_RULEGROUP` answers first.** That gate runs at the top of the same
 * method, on the first iterating level, and fires whenever the target lies outside the declaring group —
 * so a target outside it is never this family's to judge. Our own `Rule.build()` refuses that pair up front
 * and is measured OVER-REJECTING there ([KF212]), which is a second reason not to inherit the shape.
 */
fun PreparedModel.operandRepetitionScopeFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val unjudgeable = setOfNotNull(keywords[HAVING], keywords[SEMANTIC_INDEX])
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
            if (namesKeyword(text, unjudgeable) != null || OUTER_MARKER in text) {
                return@firstNotNullOfOrNull null
            }
            referencesIn(text, conditionLanguage)
                .filterNot { isRuleContext(it, conditionLanguage) }
                .firstNotNullOfOrNull { written ->
                    levelClassOf(written, declaringGroup, targetGroup, element)
                        ?.let { scopeFinding(it, written, target, element, member) }
                }
        }
    }
}

private const val HAVING = "Having"

private const val SEMANTIC_INDEX = "SemanticIndex"

/**
 * The `$` that marks a reference as reaching OUT of a filter into the enclosing rule.
 *
 * A marked reference re-enters the walk with the enclosing rule's error field restored
 * (`CheckEntitySpecImpl.checkCommon`), which this reader cannot reproduce from text, so a text carrying one
 * is left alone rather than judged against the wrong target.
 */
private const val OUTER_MARKER = "$"

/** The group whose repetitions A12 addresses the target by, or null when the target is not a FIELD. */
private fun PreparedModel.groupOfTargetField(target: String): String? = elements
    .firstOrNull { it.path == target && it.declaredKind == DeclaredKind.FIELD }
    ?.let { target.substringBeforeLast('/', missingDelimiterValue = "") }

/**
 * The class ONE reference draws, walking the repeatable levels it crosses from the outermost inwards.
 *
 * @return null wherever the reference is admitted, or is a shape another family owns.
 */
private fun PreparedModel.levelClassOf(
    written: String,
    declaringGroup: String,
    targetGroup: String,
    element: DeclaredElement,
): String? {
    val bare = written.replace("*", "")
    val resolved = resolveEntity(declaringGroup, bare, element.origin) as? Reference.Resolved ?: return null
    // A reference naming a GROUP is judged by the group-condition families, whose operators run their own
    // wildcard gates ahead of this walk; only a FIELD read reaches it with nothing else to say about it.
    if (elements.none { it.path == resolved.path && it.declaredKind == DeclaredKind.FIELD }) {
        return null
    }
    val starred = starredLevelsOf(written, declaringGroup, element) ?: return null
    var wildcardSeen = false
    crossedRepetitions(resolved.path).forEach { level ->
        if (level.path in starred) {
            wildcardSeen = true
            return@forEach
        }
        when {
            wildcardSeen -> return NO_WILDCARD
            level.path.startsWith("$targetGroup/") -> return NO_WILDCARD
            targetGroup == level.path || targetGroup.startsWith("${level.path}/") -> Unit
            else -> parallelClassOf(level, targetGroup)?.let { return it }
        }
    }
    return null
}

private const val NO_WILDCARD = "MVK_NO_WILDCARD"

private const val MULTIPLE_INDEX_FIELDS = "MVK_MULTIPLE_INDEX_FIELDS"

/**
 * The resolved paths this reference stars, or null when a star lands anywhere but on a repeatable group.
 *
 * A star on a non-repeatable level is `MVK_INVALID_WILDCARD` and outruns every slot gate ([KF328]), so such
 * a reference is [repetitionWildcardFindings]' and not judged here at all — including the case where the
 * starred prefix does not resolve, which is the reference family's.
 */
private fun PreparedModel.starredLevelsOf(
    written: String,
    declaringGroup: String,
    element: DeclaredElement,
): Set<String>? {
    val segments = written.split('/')
    val starred = mutableSetOf<String>()
    segments.indices.filter { segments[it].endsWith("*") }.forEach { at ->
        val prefix = segments.take(at + 1).joinToString("/").replace("*", "")
        val resolved = resolveEntity(declaringGroup, prefix, element.origin) as? Reference.Resolved
            ?: return null
        val group = elements.firstOrNull {
            it.path == resolved.path && it.declaredKind == DeclaredKind.GROUP && it.isRepeatable
        } ?: return null
        starred += group.path
    }
    return starred
}

/** The repeatable groups [path] lies inside, outermost first — the levels A12 iterates for it. */
private fun PreparedModel.crossedRepetitions(path: String): List<DeclaredElement> = elements
    .filter { it.declaredKind == DeclaredKind.GROUP && it.isRepeatable && path.startsWith("${it.path}/") }
    .sortedBy { it.path.length }

/**
 * The class a PARALLEL level draws — one the target's coordinates neither contain nor lie under.
 *
 * A12's route here is `checkParallelIteration`, which asks whether the two branches iterate JOINTLY: each
 * end must resolve to exactly one index field, and the two must agree. The gates are transcribed in source
 * order, and the `getIndexGroup` walk that resolves each end is what can answer with the multiple-index
 * class before either error-field comparison happens.
 */
private fun PreparedModel.parallelClassOf(level: DeclaredElement, targetGroup: String): String? {
    val operandIndexName = indexFieldNameOf(level) ?: return NO_WILDCARD
    val operandChain = keyedGroupsAtOrAbove(level.path)
    if (operandChain.size > 1) {
        return MULTIPLE_INDEX_FIELDS
    }
    // A declared index field that names nothing in its own group is A12's `MVK_INDEX_FIELD_INVALID`, and
    // that model shape is measured NOT BUILDABLE with no code at all (KF295) — so it cannot arrive here,
    // and silence is what a reader of a model that somehow carries it should get.
    val operandIndex = indexFieldAt(level.path, operandIndexName) ?: return null
    val targetChain = keyedGroupsAtOrAbove(targetGroup)
    if (targetChain.size > 1) {
        return MULTIPLE_INDEX_FIELDS
    }
    val keyedForTheTarget = targetChain.firstOrNull() ?: return "MVK_ERROR_FIELD_NO_INDEX"
    val targetIndexName = indexFieldNameOf(keyedForTheTarget) ?: return null
    if (targetIndexName != operandIndexName) {
        return "MVK_ERROR_FIELD_INVALID_INDEX"
    }
    val targetIndex = indexFieldAt(keyedForTheTarget.path, targetIndexName) ?: return null
    val operandFamily = baseFamilyOf(operandIndex) ?: return null
    val targetFamily = baseFamilyOf(targetIndex) ?: return null
    return if (operandFamily == targetFamily) null else "MVK_ERROR_FIELD_INVALID_INDEX_TYPE"
}

/** Every group at or above [path] that declares an index field — A12's `getIndexGroup` walk. */
private fun PreparedModel.keyedGroupsAtOrAbove(path: String): List<DeclaredElement> = elements
    .filter {
        it.declaredKind == DeclaredKind.GROUP &&
            (path == it.path || path.startsWith("${it.path}/")) &&
            indexFieldNameOf(it) != null
    }

private fun indexFieldNameOf(group: DeclaredElement): String? =
    (group.body?.get("indexFieldName") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }

private fun PreparedModel.indexFieldAt(group: String, name: String): DeclaredElement? =
    elements.firstOrNull { it.path == "$group/$name" && it.declaredKind == DeclaredKind.FIELD }

/**
 * The base type A12 compares two index fields by, or null for every declared type this has not read.
 *
 * `IFieldType.matchBaseType` is implemented per type interface, and the four below are the ones whose
 * implementation was read: each admits only its own interface, so a difference between two of them is a
 * mismatch on any reading. Every other declared type answers null and the type class is withheld — a
 * conservative silence, because `IStringType` also admits `IPredefinedType` and a base type shared across
 * two declared types would otherwise become an invented rejection.
 */
private fun baseFamilyOf(field: DeclaredElement): String? {
    val declared = (field.body?.get("fieldType") as? JsonObject)?.get("type") as? JsonPrimitive
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
