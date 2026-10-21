package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
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

/**
 * The checker's name for a codeless A12 sentence, or [UNKEYED_A12_CLASS].
 *
 * Twelve sentences have names. The charset sentence is the checker's `RK_UNSUPPORTED_CHARACTER`
 * ([KF489](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf489)), the five a metamodel member outside its range draws are the
 * `RK_MEMBER_*` classes ([KF556](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf556), [KF559](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf559)), and a `sortFields` entry beyond the list's capacity is
 * `RK_SORT_LIST_ABOVE_CAPACITY` ([KF345](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf345)), and the two a Number bound's printed form fails are the `RK_BOUND_*` classes ([KF569](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf569)), and an executable's name and error-code alphabets are `RK_RULE_NAME_ALPHABET` and `RK_ERROR_CODE_ALPHABET` ([KF578](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf578)), and a rule's absent or empty code is `RK_ERROR_CODE_EMPTY` ([KF579](../../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf579)). A member class names the sentence, not the member,
 * because A12 answers several members with the same words, while each maximum is a sentence of its own. Any other codeless
 * diagnostic stays unkeyed, so it shows as a disagreement rather than matching by accident.
 */
internal fun codelessClass(message: String): String = when {
    A12_REPERTOIRE_REFUSAL in message -> RK_UNSUPPORTED_CHARACTER
    SORT_CAPACITY_REFUSAL.containsMatchIn(message) -> "RK_SORT_LIST_ABOVE_CAPACITY"
    else -> (MEMBER_RANGE_SENTENCES + BOUND_SENTENCES + DECLARATION_ALPHABET_SENTENCES).entries.firstOrNull { message.trim().endsWith(it.key) }?.value
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

private val BOUND_SENTENCES = mapOf(
    "The value '0' as well as positive values must be provided without a sign." to "RK_BOUND_SIGNED_ZERO",
    "Only values with up to 15 digits are allowed." to "RK_BOUND_TOO_MANY_DIGITS",
)

/** Whole-model admission, also covering the original partial fixtures P4 preserves before hosted completion. */
internal fun kernelModelClasses(json: String): Set<String> = try {
    BoundModel.ofJson(json)
    emptySet()
} catch (unreadable: ModelInputException) {
    setOf("CK_MODEL_NOT_BUILDABLE")
} catch (rejected: ModelConsistencyException) {
    val messages = rejected.notifications().map { it.message().trim() }
    check(messages.isNotEmpty()) { "A12 refused the model without a notification" }
    messages.map { message ->
        Regex("\\[(\\w+)]$").find(message)?.groupValues?.get(1) ?: when {
            message.contains("the kernel could not build this model:") -> "CK_MODEL_NOT_BUILDABLE"
            else -> codelessClass(message)
        }
    }.toSet()
}

/** One fixed whole-model input with a predicted first class for trace and admission oracles. */
internal data class ConditionCase(val id: String, val model: String, val expected: String?)
