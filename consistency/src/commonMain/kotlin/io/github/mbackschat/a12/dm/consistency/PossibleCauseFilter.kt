package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.predicates.possibleCauseEntry
import io.github.mbackschat.a12.dm.predicates.possibleCauseSpelling
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A rule's `filterForPossiblyCausingEntities` (kernel 31.3.1, [KERNEL-FINDINGS KF682]).
 *
 * Three verdicts, each with its own place in A12's report. A shape its reader refuses leaves no model. A repeated
 * entry is a metamodel sentence, stated whatever else the rule gets wrong. An entry that is not the relative path of
 * an entity the condition references is `NoMetaModelChecks`' last check: it runs only when the checks before it
 * passed ([withholdsPossibleCauseReferences]) and only for a rule whose condition reads without a problem, which
 * [selectDeclaration] decides from the rule's own findings.
 */
internal fun TrackedFactBase.possibleCauseFilterContributions(element: TrackedDeclaration): List<OwnedFinding> {
    if (element.declaredKind != DeclaredKind.RULE) return emptyList()
    val filter = element.body?.get(FILTER_MEMBER) ?: return emptyList()
    if (filter is JsonNull) return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    val lists = (filter as? JsonObject)?.let { boundLists(it, group) }
        ?: return listOf(unbuildable(element, "`$FILTER_MEMBER` is not an object"))
    lists.firstNotNullOfOrNull { (list, entries) -> entries.exceptionOrNull()?.let { list to it.message } }
        ?.let { (list, complaint) -> return listOf(unbuildable(element, "`${list.member}` $complaint")) }
    val written = lists.associate { (list, entries) -> list to entries.getOrThrow().map(::possibleCauseSpelling) }
    val candidates by lazy { referencedRelativePaths(element, group) }
    return PossibleCauseList.entries.flatMap { list ->
        val entries = written.getValue(list)
        val duplicates = entries.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { entry ->
            DeclarationFinding(element.key, DeclarationStage.INDEPENDENT, Finding(
                FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, list.duplicateCode,
                "`${list.member}` names `$entry` more than once; A12 allows each entity once per list", element.path))
        }
        val unreferenced = entries.filterNot { it in candidates }
        duplicates + listOfNotNull(unreferenced.takeIf { it.isNotEmpty() }?.let {
            DeclarationFinding(element.key, DeclarationStage.POSSIBLE_CAUSES, Finding(
                FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, list.notReferencedCode,
                "`${list.member}` names ${it.joinToString(", ") { entry -> "`$entry`" }}, which is not the " +
                    "relative path of an entity the condition references, spelled as A12 spells it from `$group`",
                element.path))
        })
    }
}

/** The two lists, each as A12 binds its entries' text, or the shape its reader refuses. */
private fun boundLists(filter: JsonObject, group: String): List<Pair<PossibleCauseList, Result<List<String>>>> =
    PossibleCauseList.entries.map { list -> list to boundEntries(filter[list.member], group) }

private fun boundEntries(member: JsonElement?, group: String): Result<List<String>> = runCatching {
    when (member) {
        null -> emptyList()
        is JsonArray -> member.map { entry ->
            val path = (entry as? JsonObject)?.get(RELATIVE_PATH)
            // A12 coerces a scalar into the text it binds: `5` is the entry `5`.
            val text = (path as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
                ?: error("holds an entry without a relative path")
            check(!text.startsWith("/")) { "holds `$text`, which A12 refuses as an illegal relative path" }
            check(climbsWithin(text, group)) { "holds `$text`, which climbs above the model's technical root" }
            text
        }
        else -> error("is not a list")
    }
}

/** Whether the leading `..` run of [written] stays at or below the technical root above [group]. */
private fun climbsWithin(written: String, group: String): Boolean =
    written.split('/').takeWhile { it == ".." }.size <= group.split('/').count { it.isNotEmpty() }

/**
 * Every spelling A12 accepts for this rule: the canonical relative path from [group] to each entity the condition
 * references, and to the index field of each group a semantic index keys. A starred level is not itself
 * referenced, and the rule's own group is `./`.
 */
private fun TrackedFactBase.referencedRelativePaths(element: TrackedDeclaration, group: String): Set<String> {
    val referenced = conditionReferencesOf(element).mapNotNull { resolvedPathOf(it.written, group, element) }
    val keyword = OPERATOR_SPELLINGS[conditionLanguage]?.get(OperatorId.SEMANTIC_INDEX)
    val indexFields = if (keyword == null) emptyList() else element.body?.let { conditionTexts(element.kind, it) }.orEmpty()
        .flatMap { (_, text) -> semanticIndexUsesIn(text, conditionLanguage, keyword) }
        .mapNotNull { use -> use.valuePath?.let { resolvedPathOf(it, group, element) } }
        .mapNotNull { value -> indexGroupOf(value)?.let { indexed -> declarationAt(indexed) } }
        .mapNotNull { indexed -> indexFieldNameOf(indexed)?.let { "${indexed.path}/$it" } }
    return (referenced + indexFields).mapTo(HashSet()) { possibleCauseEntry(group, it) }
}

private fun unbuildable(element: TrackedDeclaration, complaint: String) = DeclarationFinding(
    element.key,
    DeclarationStage.INDEPENDENT,
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, CK_MODEL_NOT_BUILDABLE,
        "$complaint — A12's reader refuses it, so no consistency verdict about this model exists", element.path),
)

/**
 * Whether A12 never reaches its reference check for these filters: `NoMetaModelChecks` runs it last, after its entity
 * walk and its index and sort gates all passed (source-read `NoMetaModelChecks.check`, measured on a time zone and an
 * unresolved sort field, [KERNEL-FINDINGS KF682]).
 */
internal fun TrackedFactBase.withholdsPossibleCauseReferences(reported: List<Finding>): Boolean =
    reported.any { refusesTheEntityWalk(it) || it.justifiesRejection && it.code in ORDERING_GATE_CODES }

internal val POSSIBLE_CAUSE_REFERENCE_CODES: Set<String> =
    PossibleCauseList.entries.mapTo(HashSet()) { it.notReferencedCode }

private const val FILTER_MEMBER = "filterForPossiblyCausingEntities"
private const val RELATIVE_PATH = "relativePath"

private enum class PossibleCauseList(val member: String, val notReferencedCode: String, val duplicateCode: String) {
    IF_FILLED(
        "entitiesEnabledAsPossibleCauseIfFilled",
        "MVK_REF_ERROR_ENTITY_IF_FILLED_NOT_REFERENCED",
        "CUSTOM_REF_ERROR_ENTITY_IF_FILLED_DUPLICATE",
    ),
    IF_NOT_FILLED(
        "entitiesEnabledAsPossibleCauseIfNotFilled",
        "MVK_REF_ERROR_ENTITY_IF_NOT_FILLED_NOT_REFERENCED",
        "CUSTOM_REF_ERROR_ENTITY_IF_NOT_FILLED_DUPLICATE",
    ),
}
