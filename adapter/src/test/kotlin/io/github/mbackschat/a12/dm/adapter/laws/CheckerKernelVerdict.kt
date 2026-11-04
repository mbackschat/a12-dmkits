package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ModelLoader
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic

/**
 * How the checker's classes on one input stand against A12's, in the words the disagreement ledgers use; null
 * where the two agree.
 *
 * Shared by every sweep that files checker-against-Kernel disagreements into a ledger, so a tier means the same
 * in each ledger. Each sweep collects the two class sets its own way (the near-miss sweep keeps A12's `ERROR`
 * findings only), so one input may still land in different tiers under two sweeps. The words are the tiers of
 * [CONFORMANCE-PLAN](../../../../../../../../../../../docs/CONFORMANCE-PLAN.md): `OVER-REJECT` and `WRONG-CLASS`
 * are correctness, `SILENT` is coverage, and `EXTRA` is order, the classes A12's early stop never reached.
 *
 * Agreement requires equal class sets. A diagnostic surviving a hypothetical repair is still EXTRA when
 * the measured kernel did not report it (CONFORMANCE-PLAN, G-POINT-ORACLE).
 */
internal fun checkerVerdict(a12: Set<String>, ours: Set<String>): String? = when {
    a12 == ours -> null
    a12.isEmpty() -> "OVER-REJECT"
    ours.isEmpty() -> "SILENT"
    ours.containsAll(a12) -> "EXTRA"
    else -> "WRONG-CLASS"
}

/** The class a ledger records for one A12 diagnostic: its key, or for a codeless one [codelessClass]. */
internal fun Diagnostic.ledgerClass(): String = code().orElse(codelessClass(summary()))

/** A rule check's class for one diagnostic: its key, or its summary marked as codeless so a diff shows the words. */
internal fun Diagnostic.codeOrSummary(): String = code().orElse("(no code: ${summary()})")

/** A12's class for one trimmed notification message: its `[KEY]` suffix, or [codelessClass] of its words. */
internal fun notificationClass(message: String): String = KEYED_SUFFIX.find(message)?.groupValues?.get(1) ?: codelessClass(message)

/** [notificationClass], an unnamed codeless sentence spelled `codeless:<message>` so a disagreement shows its words. */
internal fun legibleNotificationClass(message: String): String =
    notificationClass(message).let { if (it == UNKEYED_A12_CLASS) "codeless:$message" else it }

/** The checker's codes that justify rejecting [json], in the order its report states them. */
internal fun rejectingCodes(json: String): List<String> =
    check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }

private val KEYED_SUFFIX = Regex("\\[(\\w+)]$")

/**
 * The checker's name for a codeless A12 sentence, or [UNKEYED_A12_CLASS].
 *
 * These sentences have names. The charset sentence is the checker's `RK_UNSUPPORTED_CHARACTER`
 * ([KF489](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf489)), the five a metamodel member outside its range draws are the
 * `RK_MEMBER_*` classes ([KF556](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf556), [KF559](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf559)), and a `sortFields` entry beyond the list's capacity is
 * `RK_SORT_LIST_ABOVE_CAPACITY` ([KF345](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf345)), and the two a Number bound's printed form fails are the `RK_BOUND_*` classes ([KF569](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf569)), and an executable's name and error-code alphabets are `RK_RULE_NAME_ALPHABET` and `RK_ERROR_CODE_ALPHABET` ([KF578](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf578)), and a rule's absent or empty code is `RK_ERROR_CODE_EMPTY` ([KF579](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf579)), and the four an element path's names draw are `RK_GROUP_PATH_INVALID`, `RK_ROOT_GROUP_NAME_ALPHABET`, `RK_FIELD_NAME_ALPHABET` and `RK_MEMBER_ABOVE_60_CHARACTERS` ([KF580](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf580)), and a date range's two are `RK_DATE_RANGE_FORMAT_INVALID` and `RK_DATE_RANGE_SEPARATOR_INVALID` ([KF581](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf581)), and a refused temporal format's is `RK_DATE_FORMAT_INVALID` ([KF648](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf648)), and A12's ten basic sentences are the checker's `RK_ID_EMPTY` to `RK_INDEX_FIELD_EMPTY` ([KF626](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf626)), and a model version's two are `RK_MODEL_VERSION_PATTERN` and `RK_MODEL_VERSION_INCOMPATIBLE` ([KF650](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf650)), and a shared element id's is `RK_ELEMENT_ID_NOT_UNIQUE` ([KF661](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf661)), and a document uniqueness criterion's codeless sentences are the `RK_CRITERION_*` classes ([KF671](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf671)). A member class names the sentence, not the member,
 * because A12 answers several members with the same words and names only the field, while each maximum is a sentence of
 * its own. That keys the two digit maxima faithfully, because among the numeric members A12's collector writes only the
 * integer places can draw the 15 sentence and only the fractional pair the 14 one: group repetitions, the other
 * candidate, draw neither (`CheckerDigitMemberRangeAgreementTest.aRepetitionCountDrawsNoDigitSentence`). Any other codeless
 * diagnostic stays unkeyed, so it shows as a disagreement rather than matching by accident.
 */
internal fun codelessClass(message: String): String = when {
    A12_REPERTOIRE_REFUSAL in message -> RK_UNSUPPORTED_CHARACTER
    SORT_CAPACITY_REFUSAL.containsMatchIn(message) -> "RK_SORT_LIST_ABOVE_CAPACITY"
    REPEATED_ENUM_VALUE.containsMatchIn(message) -> "RK_ENUM_VALUE_DUPLICATE"
    REPEATED_ENUM_CATEGORY.containsMatchIn(message) -> "RK_ENUM_CATEGORY_DUPLICATE"
    VERSION_PATTERN_REFUSAL.containsMatchIn(message) -> "RK_MODEL_VERSION_PATTERN"
    VERSION_MISMATCH.containsMatchIn(message) -> "RK_MODEL_VERSION_INCOMPATIBLE"
    COMPUTED_FIELD_NOT_FOUND.containsMatchIn(message) -> "RK_COMPUTED_FIELD_NOT_FOUND"
    TYPE_DEFINITION_NOT_FOUND.containsMatchIn(message) -> "RK_TYPE_DEFINITION_NOT_FOUND"
    CHILD_NAME_DUPLICATE.containsMatchIn(message) -> "RK_CHILD_NAME_DUPLICATE"
    ELEMENT_ID_NOT_UNIQUE.containsMatchIn(message) -> "RK_ELEMENT_ID_NOT_UNIQUE"
    else -> (MEMBER_RANGE_SENTENCES + BOUND_SENTENCES + DECLARATION_ALPHABET_SENTENCES + PATH_NAME_SENTENCES +
        TEMPORAL_FORMAT_SENTENCES + BASIC_SENTENCES + CONVERTER_SENTENCES).entries.firstOrNull { message.trim().endsWith(it.key) }?.value
        ?: CRITERION_SENTENCES.entries.firstOrNull { it.key.containsMatchIn(message.trim()) }?.value
        ?: UNKEYED_A12_CLASS
}

/** How a ledger row spells a diagnostic A12 raises without a class key. */
internal const val UNKEYED_A12_CLASS = "(no code)"

private const val A12_REPERTOIRE_REFUSAL = "The field contains one or several unsupported signs."
private const val RK_UNSUPPORTED_CHARACTER = "RK_UNSUPPORTED_CHARACTER"

private val SORT_CAPACITY_REFUSAL =
    Regex("""The index for group '/MVK/Model/Groups/SortInfo/SortFields' was specified with \d+, but at most 3 is allowed\.$""")

private val MEMBER_RANGE_SENTENCES = mapOf(
    "Only values without a sign are allowed." to "RK_MEMBER_SIGNED",
    "Only values greater than 0 are allowed." to "RK_MEMBER_NOT_POSITIVE",
    "Only values smaller than or equal to 99999 are allowed." to "RK_MEMBER_ABOVE_TECHNICAL_LIMIT",
    "Only values smaller than or equal to 15 are allowed." to "RK_MEMBER_ABOVE_DIGIT_BUDGET",
    "Only values smaller than or equal to 14 are allowed." to "RK_MEMBER_ABOVE_SCALE_LIMIT",
)

private val DECLARATION_ALPHABET_SENTENCES = mapOf(
    "A rule name must start with a letter followed by letters, digits and underscores." to "RK_RULE_NAME_ALPHABET",
    "The error code is only allowed to contain letters, digits, blanks and underscores." to "RK_ERROR_CODE_ALPHABET",
    "The error code is empty." to "RK_ERROR_CODE_EMPTY",
)

private val PATH_NAME_SENTENCES = mapOf(
    "A group name may only contain letters, numbers or underscores. Group names must begin with a letter or an " +
        "underscore. Name parts may not be longer than 60 characters and are separated by slashes. An absolute group " +
        "name must begin with a slash." to "RK_GROUP_PATH_INVALID",
    "A root group name may only contain letters, numbers and underscores." to "RK_ROOT_GROUP_NAME_ALPHABET",
    "A field name may only contain letters, digits or underscores. Field names must not begin with a digit." to
        "RK_FIELD_NAME_ALPHABET",
    "Only values with up to 60 characters are allowed." to "RK_MEMBER_ABOVE_60_CHARACTERS",
)

/** A12's basic sentences ([KF626](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf626)); the id and name ones end alike on an element and a type definition. */
private val BASIC_SENTENCES = mapOf(
    "The id is empty." to "RK_ID_EMPTY",
    "The name is empty." to "RK_NAME_EMPTY",
    "The error condition is empty." to "RK_ERROR_CONDITION_EMPTY",
    "The error entity is not set properly." to "RK_ERROR_ENTITY_EMPTY",
    "The computed field is not set properly." to "RK_COMPUTED_FIELD_EMPTY",
    "One of the computation operations is empty." to "RK_COMPUTATION_OPERATION_EMPTY",
    "The id of the referenced type definition is not set properly." to "RK_TYPE_REFERENCE_EMPTY",
    "The name of the index field is not set properly." to "RK_INDEX_FIELD_EMPTY",
)

/** A12's criterion sentences name the criterion, and most the field, so they are matched as patterns. */
private val CRITERION_SENTENCES = "Document Uniqueness Criterion '.*': ".let { criterion ->
    mapOf(
        Regex("""^A Document Uniqueness Criterion must have a name\.$""") to "RK_CRITERION_NAME_EMPTY",
        Regex("""^${criterion}Name is not unique in the Document Model\.$""") to "RK_CRITERION_NAME_NOT_UNIQUE",
        Regex("""^${criterion}Must specify at least one field\.$""") to "RK_CRITERION_FIELDS_EMPTY",
        Regex("""^${criterion}The field '.*' does not exist in the Document Model\.$""") to "RK_CRITERION_FIELD_NOT_FOUND",
        Regex("""^${criterion}The field '.*' is not set as required\.$""") to "RK_CRITERION_FIELD_NOT_REQUIRED",
        Regex("""^${criterion}The field '.*' is repeatable\.$""") to "RK_CRITERION_FIELD_REPEATABLE",
        Regex("""^${criterion}The field '.*' is transient\.$""") to "RK_CRITERION_FIELD_TRANSIENT",
        Regex("""^${criterion}No error text was specified for locale '[^']*'\.$""") to "RK_CRITERION_ERROR_TEXT_MISSING",
        Regex("""^${criterion}Invalid error text parameter for locale '[^']*'\. Only fields referenced in the Criterion are allowed\.$""")
            to "RK_CRITERION_ERROR_TEXT_PARAMETER",
    )
}

/**
 * The sentences A12's validation-model converter reports where 30.8.1 threw ([KF652](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf652)):
 * each drops the declaration it names from the converted model, and the check goes on without it.
 */
private val CONVERTER_SENTENCES = mapOf(
    "Element must have a name." to "RK_ELEMENT_NAME_MISSING",
    "Root group must have a name." to "RK_ROOT_GROUP_NAME_MISSING",
    "The computed field was not specified." to "RK_COMPUTED_FIELD_NOT_SPECIFIED",
)
private val COMPUTED_FIELD_NOT_FOUND = Regex("""The computed field "[^"]*" was not found\.$""")
private val TYPE_DEFINITION_NOT_FOUND = Regex("""Typedefinition with id '.*' could not be found\.$""")
private val CHILD_NAME_DUPLICATE = Regex("""Cannot have two child elements with the same name \[.*] in group '[^']*'\.$""")

private val ELEMENT_ID_NOT_UNIQUE = Regex("""The id \[.*] of element on path '.*' is not unique: Used by \[.*]\.$""")

private val VERSION_PATTERN_REFUSAL =
    Regex("""Document model version .* does not match proper version schema\. Expected version is [^ ]*\.$""")
private val VERSION_MISMATCH = Regex("""Version mismatch: Document model version is .* and application version is [^ ]*\.$""")

private val REPEATED_ENUM_VALUE = Regex("""The enumeration contains the value '[^']*' multiple times\.$""")
private val REPEATED_ENUM_CATEGORY = Regex("""The enumeration contains the category '[^']*' multiple times\.$""")

private val TEMPORAL_FORMAT_SENTENCES = mapOf(
    "The date range format is invalid." to "RK_DATE_RANGE_FORMAT_INVALID",
    "The date format is invalid." to "RK_DATE_FORMAT_INVALID",
    "Only minus (-) or slash (/) may be used for date range types." to "RK_DATE_RANGE_SEPARATOR_INVALID",
)

private val BOUND_SENTENCES = mapOf(
    "The value '0' as well as positive values must be provided without a sign." to "RK_BOUND_SIGNED_ZERO",
    "Only values with up to 15 digits are allowed." to "RK_BOUND_TOO_MANY_DIGITS",
)

/**
 * Whole-model admission, also covering the original partial fixtures P4 preserves before hosted completion.
 *
 * Asked of the Kernel gate alone. `BoundModel.ofJson` runs our own `ModelIndex` after the gate, and a throw from
 * that reader is ours, not A12's: read as a refusal, it once put a header column of A12 "refusals" into a
 * finding that A12 builds (KERNEL-FINDINGS KF649, found by a12-kernel-lean's LF224).
 */
internal fun kernelModelClasses(json: String): Set<String> = try {
    ModelLoader.loadSelfContained(json)
    emptySet()
} catch (unreadable: ModelInputException) {
    setOf("CK_MODEL_NOT_BUILDABLE")
} catch (rejected: ModelConsistencyException) {
    val messages = rejected.notifications().map { it.message().trim() }
    check(messages.isNotEmpty()) { "A12 refused the model without a notification" }
    messages.map { message ->
        KEYED_SUFFIX.find(message)?.groupValues?.get(1) ?: when {
            message.contains("the kernel could not build this model:") -> "CK_MODEL_NOT_BUILDABLE"
            else -> codelessClass(message)
        }
    }.toSet()
}

/** One fixed whole-model input with a predicted first class for trace and admission oracles. */
internal data class ConditionCase(val id: String, val model: String, val expected: String?)
