package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A12's basic sentences: the codeless notifications of `BasicConsistencyCheck`, each published under a code of
 * this checker's own ([KERNEL-FINDINGS KF579], [KF626]).
 *
 * A12 runs that check as its last consistency rule (`BasicConsistencyCheckFromSerializerRule`) over the model as
 * READ, before any variant filter applies, so a declaration an `ONLY` filter deletes or suppresses still draws
 * its sentence — and on such a declaration the sentence is the model's only refusal. Each sentence fires on
 * `StringUtils.isEmpty`, so an empty member draws it and a blank one does not: a name, condition or error target
 * of one space is measured admitted under `ONLY`. `isEmpty` is true of a missing member as well, so the members
 * the reader accepts missing draw their sentence absent as well: a rule's condition and an alternative's
 * operation, also when `null` ([KERNEL-FINDINGS KF643]), and a rule's code. The others are left to the
 * reader, which refuses them before this check is reached.
 *
 * Two of A12's sentences are not here because no JSON source reaches them: an empty `indexFieldName` or
 * `sortFields[].name` leaves the model unbuilt first, and a localized text of `null` is refused by the reader.
 */
internal fun TrackedFactBase.basicSentenceFindings(): List<Finding> =
    declarations().flatMap { element ->
        val body = element.body
        val shared = listOfNotNull(
            element.id?.takeIf { it.isEmpty() }?.let { sentence(element, RK_ID_EMPTY, "this declaration's `id` is empty") },
            element.name.takeIf { it.isEmpty() }?.let { sentence(element, RK_NAME_EMPTY, "this declaration's `name` is empty") },
        )
        shared + when (element.declaredKind) {
            DeclaredKind.FIELD -> fieldSentences(element, body)
            DeclaredKind.RULE -> ruleSentences(element, body)
            DeclaredKind.COMPUTATION -> computationSentences(element, body)
            else -> emptyList()
        }
    } + typeDefinitionSentences()

/** The codes [basicSentenceFindings] publishes, each A12's sentence for one member ([VARIANT_BLIND_CODES]). */
internal val BASIC_SENTENCE_CODES = setOf(
    RK_ID_EMPTY, RK_NAME_EMPTY, RK_ERROR_CODE_EMPTY, RK_ERROR_CONDITION_EMPTY, RK_ERROR_ENTITY_EMPTY,
    RK_COMPUTED_FIELD_EMPTY, RK_COMPUTATION_OPERATION_EMPTY, RK_ENUM_VALUE_DUPLICATE, RK_ENUM_CATEGORY_DUPLICATE,
    RK_TYPE_REFERENCE_EMPTY,
)

/** An Enumeration's repeated values and category names, one sentence for each, and an absent or empty type reference. */
private fun fieldSentences(field: TrackedDeclaration, body: JsonObject?): List<Finding> {
    val declared = body?.get("fieldType") as? JsonObject ?: return emptyList()
    val type = declared.namedFieldType() ?: return emptyList()
    val typeBody = type.bodyIn(declared) ?: return emptyList()
    return when (type) {
        FieldType.ENUMERATION ->
            typeBody.repeated("values", "value").map {
                sentence(field, RK_ENUM_VALUE_DUPLICATE, "this enumeration declares the value `$it` more than once")
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
 * A rule's empty condition and error target, and its absent or empty code — which alone among the members is
 * judged absent as well, because A12 reads a rule with no code and states both its class and this sentence.
 */
private fun ruleSentences(rule: TrackedDeclaration, body: JsonObject?): List<Finding> {
    // No body is an envelope A12's reader refuses, and that refusal is the declaration's one finding.
    body ?: return emptyList()
    val code = body["errorCode"]
    return listOfNotNull(
        if (body.isEmptyOrMissing("errorCondition")) {
            sentence(rule, RK_ERROR_CONDITION_EMPTY, "this rule's `errorCondition` is absent or empty")
        } else {
            null
        },
        body.emptyMember("errorEntityRelPath")?.let { sentence(rule, RK_ERROR_ENTITY_EMPTY, "this rule's `errorEntityRelPath` is empty") },
        if (code == null || code is JsonPrimitive && code.isString && code.content.isEmpty()) {
            sentence(rule, RK_ERROR_CODE_EMPTY, "this rule's `errorCode` is absent or empty")
        } else {
            null
        },
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
 * A type definition's empty id and empty name, which carry no element path. Two definitions with an empty id
 * draw the sentence once here where A12 states it twice; the verdict is the same.
 */
private fun TrackedFactBase.typeDefinitionSentences(): List<Finding> {
    val ids = declaredTypeDefinitionIds
    val emptyId = if ("" in ids) listOf(sentence(null, RK_ID_EMPTY, "a `content.typeDefinitions` entry has an empty `id`")) else emptyList()
    val emptyName = ids.firstOrNull { typeDefinitionBody(it)?.emptyMember("name") != null }?.let {
        sentence(null, RK_NAME_EMPTY, "the `content.typeDefinitions` entry `$it` has an empty `name`")
    }
    return emptyId + listOfNotNull(emptyName)
}

/** Whether this member is absent, `null` or the empty string — what `StringUtils.isEmpty` reads after the reader. */
private fun JsonObject.isEmptyOrMissing(member: String): Boolean =
    this[member].let { it == null || it is JsonNull } || emptyMember(member) != null

/** This member when it is present as the empty string, which is exactly what `StringUtils.isEmpty` adds to absence. */
private fun JsonObject.emptyMember(member: String): String? =
    (this[member] as? JsonPrimitive)?.takeIf { it.isString && it.content.isEmpty() }?.content

/** The values of [key] that two or more entries of the [list] share, each once. */
private fun JsonObject.repeated(list: String, key: String): List<String> =
    (this[list] as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { value -> value.isString }?.content }
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys.toList()

private fun sentence(element: TrackedDeclaration?, code: String, explain: String) = Finding(
    FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code,
    "$explain, which A12 states in a sentence with no class, before it applies any variant filter", element?.path,
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
