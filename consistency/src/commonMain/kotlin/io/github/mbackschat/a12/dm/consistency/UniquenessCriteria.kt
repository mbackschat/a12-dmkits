package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.messageParametersAreUnterminated
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What A12 asks of each document uniqueness criterion of the entry model ([KERNEL-FINDINGS KF671]).
 *
 * A12's `DocumentUniquenessCriteriaRule` runs after the id rule and before code generation (source-read at
 * `eec9134`), over the model as read, so every class is stated whatever else the model carries. It first states a
 * name shared by two criteria, once per criterion of that name, in `HashMap` order of the names
 * ([computeIfAbsentOrder]). Then, per criterion: an empty name, an empty field list, and for each field in turn one
 * that is missing (or a group, or written relatively), else not always required, repeatable and transient, each
 * asked. A field named twice is judged once, since the fields are a set. Then a missing or empty text for every
 * DECLARED locale, and finally each text through the error-text grammar the rule messages share: a character gate
 * alone, else an unterminated parameter, else one sentence per parameter that names no field of the criterion with
 * or without `.value`. A blank text is a text. Locked by `validate.laws.DocumentUniquenessCriterionLawsTest`,
 * `…ReachLawsTest`, and, for the order and every gate of [characterRefusal], by
 * `adapter.laws.CheckerUniquenessCriterionAgreementTest`.
 *
 * Only the entry's criteria are judged: expansion drops an included model's own, measured admitted even over a
 * missing field. A member this checker cannot read as A12 binds it (a criterion that is no object, a name, field or
 * text that is no string) is left to A12 as a [CoverageGap] rather than judged.
 */
internal fun TrackedFactBase.uniquenessCriterionFindings(): List<Finding> {
    val criteria = uniquenessCriteria?.readCriteria() ?: return emptyList()
    val names = criteria.map { it.name }
    val shared = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val duplicates = computeIfAbsentOrder(names).filter { it in shared }.flatMap { name ->
        criteria.filter { it.name == name }.map { criterionFinding(RK_CRITERION_NAME_NOT_UNIQUE, it, "shares its name with another criterion") }
    }
    val asRead = declarationsAsRead()
    val fields = LinkedHashMap<String, TrackedDeclaration>().also { byPath ->
        asRead.forEach { byPath.getOrPut(it.path) { it } }
    }.filterValues { it.declaredKind == DeclaredKind.FIELD }
    val repeatable = asRead.filter { it.isRepeatableGroup }.map { it.path }
    return duplicates + criteria.flatMap { criterionFindings(it, fields, repeatable) }
}

/** A criteria member this checker cannot read as A12 binds it, left to A12 rather than judged. */
internal fun TrackedFactBase.uniquenessCriterionGaps(): List<CoverageGap> {
    val member = uniquenessCriteria ?: return emptyList()
    return if (member.readCriteria() == null) {
        listOf(CoverageGap(null, "a document uniqueness criterion carries a member that is not as A12 binds it, " +
            "so what A12 makes of the criteria was left to A12"))
    } else {
        emptyList()
    }
}

/** The codes [uniquenessCriterionFindings] publishes beside A12's keyed error-text ones. */
internal val UNIQUENESS_CRITERION_CODES = setOf(
    RK_CRITERION_NAME_NOT_UNIQUE, RK_CRITERION_NAME_EMPTY, RK_CRITERION_FIELDS_EMPTY, RK_CRITERION_FIELD_NOT_FOUND,
    RK_CRITERION_FIELD_NOT_REQUIRED, RK_CRITERION_FIELD_REPEATABLE, RK_CRITERION_FIELD_TRANSIENT,
    RK_CRITERION_ERROR_TEXT_MISSING, RK_CRITERION_ERROR_TEXT_PARAMETER,
)

private class Criterion(val name: String, val fields: List<String>, val texts: Map<String, String>)

/** Each criterion as A12 binds it, or null when one member is not a shape this checker reads. */
private fun JsonArray.readCriteria(): List<Criterion>? = map { entry ->
    val criterion = entry as? JsonObject ?: return null
    val name = criterion.text("name") ?: return null
    val fields = criterion.objects("fields")?.map { it.text("fullName") ?: return null } ?: return null
    val texts = criterion.objects("errorMessage")?.associate { message ->
        (message.text("locale") ?: return null) to (message.text("text") ?: return null)
    } ?: return null
    Criterion(name, fields.distinct(), texts)
}

/** A member as A12's reader binds a `String`: absent is A12's empty default, a JSON string is its text. */
private fun JsonObject.text(member: String): String? = when (val value: JsonElement? = this[member]) {
    null -> ""
    is JsonPrimitive -> value.takeIf { it.isString }?.content
    else -> null
}

/** A list member of objects; absent is A12's empty default. */
private fun JsonObject.objects(member: String): List<JsonObject>? = when (val value = this[member]) {
    null -> emptyList()
    is JsonArray -> value.map { it as? JsonObject ?: return null }
    else -> null
}

private fun TrackedFactBase.criterionFindings(
    criterion: Criterion,
    fields: Map<String, TrackedDeclaration>,
    repeatable: List<String>,
): List<Finding> {
    val found = mutableListOf<Finding>()
    if (criterion.name.isEmpty()) found += criterionFinding(RK_CRITERION_NAME_EMPTY, criterion, "has no name")
    if (criterion.fields.isEmpty()) found += criterionFinding(RK_CRITERION_FIELDS_EMPTY, criterion, "names no field")
    for (path in criterion.fields) {
        val field = fields[path]
        if (field == null) {
            found += criterionFinding(RK_CRITERION_FIELD_NOT_FOUND, criterion,
                "names `$path`, which is no field of the model — a group, a relative path and a `.value` suffix name none")
            continue
        }
        val body = field.body
        val mode = ((body?.get("requirednessConfig") as? JsonObject)?.get("mode") as? JsonPrimitive)?.content
        if (mode != ALWAYS_REQUIRED) {
            found += criterionFinding(RK_CRITERION_FIELD_NOT_REQUIRED, criterion,
                "names `$path`, which is not always required — required only with its parent does not count")
        }
        if (repeatable.any { path.startsWith("$it/") }) {
            found += criterionFinding(RK_CRITERION_FIELD_REPEATABLE, criterion, "names `$path`, which repeats")
        }
        if (configFlag(body?.get("transient")) == ConfigFlag.ON) {
            found += criterionFinding(RK_CRITERION_FIELD_TRANSIENT, criterion, "names `$path`, which is transient")
        }
    }
    for (locale in declaredLocales) {
        if (criterion.texts[locale].isNullOrEmpty()) {
            found += criterionFinding(RK_CRITERION_ERROR_TEXT_MISSING, criterion, "has no error text for `$locale`")
        }
    }
    val allowed = criterion.fields.flatMap { listOf(it, "$it.value") }.toSet()
    for ((locale, text) in criterion.texts) {
        if (text.isEmpty()) continue
        val refusal = text.characterRefusal()
        when {
            refusal != null -> found += criterionFinding(refusal.code, criterion,
                "has an error text for `$locale` carrying ${refusal.describe()}")
            messageParametersAreUnterminated(text) -> found += criterionFinding("WRONG_NUMBER_OF_DOLLARS", criterion,
                "has an error text for `$locale` that opens a parameter it never closes")
            else -> messageParameterBodies(text).filterNot { it in allowed }.forEach { parameter ->
                found += criterionFinding(RK_CRITERION_ERROR_TEXT_PARAMETER, criterion,
                    "has an error text for `$locale` whose parameter `$parameter` names no field of the criterion by its full path")
            }
        }
    }
    return found
}

/** A12 reads only this mode as a guarantee that the field is filled. */
private const val ALWAYS_REQUIRED = "absoluteOrRelativeToNextRepAncestor"

private fun criterionFinding(code: String, criterion: Criterion, what: String) = Finding(
    FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code,
    "the document uniqueness criterion `${criterion.name}` $what, which A12 states in a sentence naming the criterion",
    null,
)

internal const val RK_CRITERION_NAME_NOT_UNIQUE = "RK_CRITERION_NAME_NOT_UNIQUE"
internal const val RK_CRITERION_NAME_EMPTY = "RK_CRITERION_NAME_EMPTY"
internal const val RK_CRITERION_FIELDS_EMPTY = "RK_CRITERION_FIELDS_EMPTY"
internal const val RK_CRITERION_FIELD_NOT_FOUND = "RK_CRITERION_FIELD_NOT_FOUND"
internal const val RK_CRITERION_FIELD_NOT_REQUIRED = "RK_CRITERION_FIELD_NOT_REQUIRED"
internal const val RK_CRITERION_FIELD_REPEATABLE = "RK_CRITERION_FIELD_REPEATABLE"
internal const val RK_CRITERION_FIELD_TRANSIENT = "RK_CRITERION_FIELD_TRANSIENT"
internal const val RK_CRITERION_ERROR_TEXT_MISSING = "RK_CRITERION_ERROR_TEXT_MISSING"
internal const val RK_CRITERION_ERROR_TEXT_PARAMETER = "RK_CRITERION_ERROR_TEXT_PARAMETER"
