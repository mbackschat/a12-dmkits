package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A12's basic sentences: the codeless notifications of `BasicConsistencyCheck`, each published under a code of
 * this checker's own ([KERNEL-FINDINGS KF579], [KF626]).
 *
 * A12 runs that check as its last consistency rule (`BasicConsistencyCheckFromSerializerRule`) over the model as
 * READ, so a declaration its converter leaves out still draws its sentence ([KERNEL-FINDINGS KF652]). Each sentence
 * fires on `StringUtils.isEmpty`, so an empty member draws it and a blank one does not. `isEmpty` is true of a
 * missing member as well, so the members the reader accepts missing draw their sentence absent as well: a rule's
 * condition and an alternative's operation, also when `null` ([KERNEL-FINDINGS KF643]), a type definition's name
 * ([KERNEL-FINDINGS KF644]), and on kernel 31.1.1 an element's name, absent or `null` (KF652). A rule's code is not
 * among them on 31.1.1, where its absence leaves no model (KF654). The others are left to the reader, which refuses
 * them before this check is reached.
 *
 * Two of A12's sentences are not here because no JSON source reaches them: an empty `sortFields[].name` leaves the
 * model unbuilt first, and a localized text of `null` is refused by the reader. An empty `indexFieldName` reaches its
 * sentence on kernel 31.1.1 (KERNEL-FINDINGS KF653).
 */
internal fun TrackedFactBase.basicSentenceFindings(): List<Finding> =
    // A12's walker visits the type definitions before the elements (KERNEL-FINDINGS KF647).
    typeDefinitionSentences() + declarationsAsRead().flatMap { element ->
        val body = element.body
        val shared = listOfNotNull(
            element.id?.takeIf { it.isEmpty() }?.let { sentence(element, RK_ID_EMPTY, "this declaration's `id` is empty") },
            element.name.takeIf { it.isEmpty() }?.let { sentence(element, RK_NAME_EMPTY, "this declaration's `name` is empty") },
        )
        shared + when (element.declaredKind) {
            DeclaredKind.FIELD -> fieldSentences(element, body)
            DeclaredKind.RULE -> ruleSentences(element, body)
            DeclaredKind.COMPUTATION -> computationSentences(element, body)
            DeclaredKind.GROUP -> listOfNotNull(
                body?.emptyMember("indexFieldName")?.let { sentence(element, RK_INDEX_FIELD_EMPTY, "this group's `indexFieldName` is empty") },
            )
            else -> emptyList()
        }
    }

/** The codes [basicSentenceFindings] publishes, each A12's sentence for one member. */
internal val BASIC_SENTENCE_CODES = setOf(
    RK_ID_EMPTY, RK_NAME_EMPTY, RK_ERROR_CODE_EMPTY, RK_ERROR_CONDITION_EMPTY, RK_ERROR_ENTITY_EMPTY,
    RK_COMPUTED_FIELD_EMPTY, RK_COMPUTATION_OPERATION_EMPTY, RK_ENUM_VALUE_DUPLICATE, RK_ENUM_CATEGORY_DUPLICATE,
    RK_TYPE_REFERENCE_EMPTY, RK_INDEX_FIELD_EMPTY,
)

/**
 * An Enumeration's repeated values and category names, one sentence for each, and an absent or empty type reference.
 * A12 collects the repeats into a `HashMap` and states them as it iterates (KERNEL-FINDINGS KF647), so they come in
 * [computeIfAbsentOrder].
 */
private fun fieldSentences(field: TrackedDeclaration, body: JsonObject?): List<Finding> {
    val declared = body?.get("fieldType") as? JsonObject ?: return emptyList()
    val type = declared.namedFieldType() ?: return emptyList()
    val typeBody = type.bodyIn(declared) ?: return emptyList()
    return when (type) {
        FieldType.ENUMERATION ->
            typeBody.repeated("values", "value", absentIsAKey = true).map {
                sentence(field, RK_ENUM_VALUE_DUPLICATE, it?.let { token -> "this enumeration declares the value `$token` more than once" }
                    ?: "this enumeration declares more than one value with no `value` token, which A12 reads as one repeated value")
            } + typeBody.repeated("categories", "name").map {
                sentence(field, RK_ENUM_CATEGORY_DUPLICATE, "this enumeration declares the category `$it` more than once")
            }
        FieldType.TYPE_DEF -> listOfNotNull(
            if (field.typeDefinitionIdIsAbsent() || typeBody.emptyMember("typeDefinitionId") != null) {
                sentence(field, RK_TYPE_REFERENCE_EMPTY, "this field's `typeDefinitionId` is absent or empty")
            } else {
                null
            },
        )
        else -> emptyList()
    }
}

/**
 * A rule's empty condition, error target and code. A rule with no code leaves no model on kernel 31.1.1
 * (KERNEL-FINDINGS KF654), so only the empty one draws its sentence.
 */
private fun ruleSentences(rule: TrackedDeclaration, body: JsonObject?): List<Finding> {
    // No body is an envelope A12's reader refuses, and that refusal is the declaration's one finding.
    body ?: return emptyList()
    return listOfNotNull(
        // An absent or `null` condition leaves no model on kernel 31.1.1, and the build failure is its one class
        // (`adapter.laws.CheckerEmptyMemberAgreementTest`).
        body.emptyMember("errorCondition")?.let { sentence(rule, RK_ERROR_CONDITION_EMPTY, "this rule's `errorCondition` is empty") },
        body.emptyMember("errorEntityRelPath")?.let { sentence(rule, RK_ERROR_ENTITY_EMPTY, "this rule's `errorEntityRelPath` is empty") },
        body.emptyMember("errorCode")?.let { sentence(rule, RK_ERROR_CODE_EMPTY, "this rule's `errorCode` is empty") },
    )
}

/** A computation's empty target, and one sentence however many of its alternatives carry an empty operation. */
private fun computationSentences(computation: TrackedDeclaration, body: JsonObject?): List<Finding> = listOfNotNull(
    body?.emptyMember("computedFieldRelPath")?.let {
        sentence(computation, RK_COMPUTED_FIELD_EMPTY, "this computation's `computedFieldRelPath` is empty")
    },
    (body?.get("computationAlternatives") as? JsonArray).orEmpty()
        .firstOrNull { (it as? JsonObject)?.isEmptyOrMissing("operation") == true }
        ?.let { sentence(computation, RK_COMPUTATION_OPERATION_EMPTY, "an alternative of this computation has an absent or empty `operation`") },
)

/**
 * Each type definition's empty id and empty name, one sentence per defect, in the order A12 walks them, which
 * [declaredTypeDefinitionIds] keeps: by id when A12 expands the model, as declared when it does not (KERNEL-FINDINGS
 * KF647). They carry no element path. Two definitions sharing an id, the empty one included, leave no model this
 * check runs on, so one sentence per id is A12's count.
 */
private fun TrackedFactBase.typeDefinitionSentences(): List<Finding> = declaredTypeDefinitionIds.flatMap { id ->
    listOfNotNull(
        sentence(null, RK_ID_EMPTY, "a `content.typeDefinitions` entry has an empty `id`").takeIf { id.isEmpty() },
        // An absent name reads as empty too (KF644); `null`, an object or an array is the reader's refusal instead.
        typeDefinitionBody(id)?.takeIf { "name" !in it || it.emptyMember("name") != null }?.let {
            sentence(null, RK_NAME_EMPTY, "the `content.typeDefinitions` entry `$id` has an absent or empty `name`")
        },
    )
}

/** Whether this member is absent, `null` or the empty string — what `StringUtils.isEmpty` reads after the reader. */
private fun JsonObject.isEmptyOrMissing(member: String): Boolean =
    this[member].let { it == null || it is JsonNull } || emptyMember(member) != null

/** This member when it is present as the empty string, which is exactly what `StringUtils.isEmpty` adds to absence. */
private fun JsonObject.emptyMember(member: String): String? = string(member)?.takeIf { it.isEmpty() }

/**
 * The keys repeated in [list], in A12's order. With [absentIsAKey], an entry without [key] is the null key, as A12 reads
 * a value entry with no token (KERNEL-FINDINGS KF655); an entry whose [key] is not a string is not judged.
 */
private fun JsonObject.repeated(list: String, key: String, absentIsAKey: Boolean = false): List<String?> {
    val seen = mutableSetOf<String?>()
    // A12 calls `computeIfAbsent` at every occurrence after the first, so the third one can still resize its map.
    val keys: List<String?> = (this[list] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { entry ->
        when {
            key !in entry -> if (absentIsAKey) listOf(null) else emptyList()
            else -> listOfNotNull(entry.string(key))
        }
    }
    val repeats = keys.filterNot { seen.add(it) }
    return computeIfAbsentOrder(repeats)
}

private fun sentence(element: TrackedDeclaration?, code: String, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code,
    "$explain, which A12 states in a sentence with no class over the model as read", element?.path,
)

internal const val RK_ID_EMPTY = "RK_ID_EMPTY"
internal const val RK_NAME_EMPTY = "RK_NAME_EMPTY"
internal const val RK_ERROR_CODE_EMPTY = "RK_ERROR_CODE_EMPTY"
internal const val RK_ERROR_CONDITION_EMPTY = "RK_ERROR_CONDITION_EMPTY"
internal const val RK_ERROR_ENTITY_EMPTY = "RK_ERROR_ENTITY_EMPTY"
internal const val RK_COMPUTED_FIELD_EMPTY = "RK_COMPUTED_FIELD_EMPTY"
internal const val RK_COMPUTATION_OPERATION_EMPTY = "RK_COMPUTATION_OPERATION_EMPTY"
internal const val RK_ENUM_VALUE_DUPLICATE = "RK_ENUM_VALUE_DUPLICATE"
internal const val RK_ENUM_CATEGORY_DUPLICATE = "RK_ENUM_CATEGORY_DUPLICATE"
internal const val RK_TYPE_REFERENCE_EMPTY = "RK_TYPE_REFERENCE_EMPTY"
internal const val RK_INDEX_FIELD_EMPTY = "RK_INDEX_FIELD_EMPTY"
