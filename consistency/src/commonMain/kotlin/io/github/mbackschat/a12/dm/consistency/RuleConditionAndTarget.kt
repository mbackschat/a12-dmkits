package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The two members a stored RULE cannot work without — `errorCondition` and `errorEntityRelPath`.
 *
 * This is the rule twin of the computation's alternative list, and it reads structure only: whether the
 * member is there and whether it carries text, never what the text means.
 *
 * **A BLANK member draws a verdict and an ABSENT one draws none at all — which inverts what a checker would
 * naturally report.** Three sibling slots read a blank string as absent (`errorCode` and a message `text`),
 * so "missing" looks like one shape; here the two sit in different tiers. A blank `errorCondition` is A12's
 * verdict (`MVK_CONDITION_MISSING` beside `MVK_INCOMPLETE_INPUT`) and a blank `errorEntityRelPath` likewise
 * (`MVK_ERROR_FIELD_MISSING` beside `MVK_MISSING_ERROR_FIELD` — two codes whose names nearly mirror each
 * other and which fire together), while an ABSENT `errorCondition` stops A12 from building the model and an
 * absent `errorEntityRelPath` refuses its reader outright. Reporting a missing-condition verdict for the
 * absent member would present this checker's reading as A12's about a model A12 never judged
 * (`RuleConditionAndTargetSlotLawsTest`, kernel 30.8.1, 2026-09-12).
 *
 * **An explicit `null` rides with the absent member here**, as it does for every rule member measured
 * ([KERNEL-FINDINGS KF291]) — and unlike inside a computation alternative, where a `null` operation and a
 * `null` precondition are ordinary verdicts.
 */
fun PreparedModel.ruleConditionAndTargetFindings(): List<Finding> =
    elements.filter { it.kind == RULE_KIND }.flatMap { element ->
        val body = element.declaration[RULE_KIND] as? JsonObject
        requiredTextFindings(element, body?.get("errorCondition"), CONDITION) +
            requiredTextFindings(element, body?.get("errorEntityRelPath"), ERROR_TARGET)
    }

private const val RULE_KIND = "Rule"

/**
 * One required member's two outcomes, named so each row states which tier it belongs to.
 *
 * @param member the member's own name, for the explanation.
 * @param blankCodes what A12 reports when the member is present and blank — every one of them, because both
 *     members draw a PAIR and naming one would under-report what A12 said.
 * @param absentExplain what A12 does when it is absent, in A12's own terms rather than in a verdict's.
 */
private class RequiredMember(
    val member: String,
    val blankCodes: List<String>,
    val absentExplain: String,
)

private val CONDITION = RequiredMember(
    "errorCondition",
    listOf("MVK_CONDITION_MISSING", "MVK_INCOMPLETE_INPUT"),
    "A12 cannot build the model from it",
)

private val ERROR_TARGET = RequiredMember(
    "errorEntityRelPath",
    listOf("MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD"),
    "A12's own reader refuses the source",
)

private fun requiredTextFindings(
    element: DeclaredElement,
    declared: JsonElement?,
    required: RequiredMember,
): List<Finding> = when {
    declared == null || declared is JsonNull -> listOf(
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            CK_MODEL_NOT_BUILDABLE,
            "this rule declares no `${required.member}`, and ${required.absentExplain} — so no consistency " +
                "verdict about this model exists, and a BLANK one would have drawn a verdict instead",
            element.path,
        ),
    )
    declared is JsonPrimitive && declared.isString && declared.content.isBlank() -> required.blankCodes.map {
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            it,
            "this rule's `${required.member}` is blank, which A12 judges and refuses — unlike an OMITTED " +
                "member, which it never gets far enough to judge",
            element.path,
        )
    }
    // A non-string primitive is COERCED to the String member, as a rule's `errorCode` is, and a container is
    // unmeasured. Under-reporting is the safe direction; inventing a verdict would be a refusal with no
    // Kernel basis.
    else -> emptyList()
}
