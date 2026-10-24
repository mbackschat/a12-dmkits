package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.isLexerBlank
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
internal fun TrackedFactBase.ruleConditionAndTargetFindings(element: TrackedDeclaration): List<Finding> =
    ruleMemberContributions(element).map { it.finding }

internal fun TrackedFactBase.ruleMemberContributions(element: TrackedDeclaration): List<DeclarationFinding> {
    if (element.declaredKind != DeclaredKind.RULE) return emptyList()
    val body = element.body ?: return listOf(DeclarationFinding(element.key, DeclarationStage.TARGET, unreadableEnvelope(element)))
    return requiredTextFindings(element, body["errorEntityRelPath"], ERROR_TARGET).map {
        DeclarationFinding(element.key, DeclarationStage.TARGET, it)
    } + requiredTextFindings(element, body["errorCondition"], CONDITION).map {
        DeclarationFinding(element.key, DeclarationStage.CONDITION, it)
    }
}

/**
 * One required member's two outcomes, named so each row states which tier it belongs to.
 *
 * @param member the member's own name, for the explanation.
 * @param blankCodes what A12 reports for the member's present text as a blank one — every code, because both
 *     members draw a PAIR on an ASCII blank and naming one would under-report what A12 said — and nothing when
 *     the text is not blank in the sense that member's verdicts use.
 * @param absentExplain what A12 does when it is absent, in A12's own terms rather than in a verdict's.
 */
private class RequiredMember(
    val member: String,
    val blankCodes: (String) -> List<String>,
    val absentExplain: String,
)

/**
 * The condition's pair comes from two different tests. `MVK_CONDITION_MISSING` is A12's member presence test,
 * which counts Java's whitespace; `MVK_INCOMPLETE_INPUT` is the parser meeting no token, which only the lexer's
 * five whitespace characters give. So a condition of vertical tabs draws the first and the lexer error instead
 * of the second, and one of no-break spaces draws neither — measured on six spaces (KERNEL-FINDINGS KF310).
 */
private val CONDITION = RequiredMember(
    "errorCondition",
    { text ->
        listOfNotNull(
            "MVK_CONDITION_MISSING".takeIf { text.isA12Blank() },
            "MVK_INCOMPLETE_INPUT".takeIf { text.isLexerBlank() },
        )
    },
    "A12 cannot build the model from it",
)

/**
 * The target has no blank class of its own: A12 answers every text that does not resolve with this pair, an
 * unresolvable path and a padded valid one included, so any text of spaces alone draws it whichever spaces
 * they are.
 */
private val ERROR_TARGET = RequiredMember(
    "errorEntityRelPath",
    { text -> if (text.isUnicodeBlank()) listOf(ERROR_FIELD_MISSING, MISSING_ERROR_FIELD) else emptyList() },
    "A12's own reader refuses the source",
)

private fun requiredTextFindings(
    element: TrackedDeclaration,
    declared: JsonElement?,
    required: RequiredMember,
): List<Finding> {
    val text = (declared as? JsonPrimitive)?.takeIf { it.isString }?.content
    val blank = text?.let(required.blankCodes)
    return when {
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
        !blank.isNullOrEmpty() -> blank.map {
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                it,
                "this rule's `${required.member}` is blank, which A12 judges and refuses — unlike an OMITTED " +
                    "member, which it never gets far enough to judge",
                element.path,
                endOfBlankText(required.member, text.orEmpty()),
            )
        }
        // A non-string primitive is COERCED to the String member, as a rule's `errorCode` is, and a container
        // is unmeasured. Under-reporting is the safe direction; inventing a verdict would be a refusal with no
        // Kernel basis.
        else -> emptyList()
    }
}
