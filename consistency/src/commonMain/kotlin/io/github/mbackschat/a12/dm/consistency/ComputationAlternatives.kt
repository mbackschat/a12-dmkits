package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.isLexerBlank
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * A computation's `computationAlternatives` — the list that carries its work, and the guards A12 demands of
 * it once there is more than one.
 *
 * Structural absence belongs to the declaration. Blank operation text retains this family's ownership
 * but uses the joined recognizer's actual failure site, so an earlier action can stop it (R5).
 *
 * **A single alternative may be unguarded; from two on, EVERY one owes its own precondition.** The first
 * half is why the law needs stating: a lone unguarded alternative is admitted, so "an alternative may be
 * unguarded" looks settled, and a second one refuses the pair with `MVK_ERROR_EMPTY_PRECONDITION` whichever
 * of them lacks the guard — first, last or both. The rival reading, that the last alternative is a fallback
 * owing nothing, fits the single-alternative observation exactly and disagrees on every real model
 * (`ComputationAlternativeSlotLawsTest`, kernel 30.8.1, 2026-09-12).
 *
 * **A `commonPrecondition` does not pay that debt**, which is measured rather than assumed: it is a
 * different slot, and declaring one leaves both the admitted single and the refused pair exactly as they
 * were.
 *
 * **A BLANK precondition is not an empty one, and that is the row that breaks the sibling families' law.**
 * A blank `errorCode` and a blank message `text` each count as absent ([KERNEL-FINDINGS KF290], [KF291]), so
 * carrying that across looks obvious — but a precondition that is PRESENT reaches A12's condition parser
 * whatever it spells, and `""` fails there as `MVK_UNEXPECTED_TOKEN`. Reporting an empty precondition for it
 * would name the wrong defect in a family that cannot fix it, so a blank one is left to the syntax layer.
 *
 * **An explicit `null` answers differently per binding site, inside this one member.** `computationAlternatives:
 * null` refuses A12's reader outright, as a rule's own members do; `operation: null` and `precondition: null`
 * one level down are ordinary verdicts, identical to the omitted member. All three are measured, because the
 * one that was inferred from its neighbour was the one that turned out wrong.
 */
internal fun TrackedFactBase.computationAlternativeFindings(): List<Finding> =
    declarations().flatMap { element ->
        computationAlternativeContributions(element).filterNot { it is ConditionFinding }.map { it.finding } +
            blankOperationFindings(element)
    }

internal fun TrackedFactBase.computationAlternativeContributions(element: TrackedDeclaration): List<OwnedFinding> {
    if (element.declaredKind != DeclaredKind.COMPUTATION) return emptyList()
    // An envelope A12 cannot bind is a tier ABOVE any verdict about this member, and reading it as an
    // absent member would report two codes about a model A12 never examined.
    val body = element.body ?: return listOf(DeclarationFinding(element.key, DeclarationStage.TARGET, unreadableEnvelope(element)))
    val member = body["computationAlternatives"]
    return when {
        member == null || member is JsonArray && member.isEmpty() -> noWorkFindings(element)
            .map { DeclarationFinding(element.key, DeclarationStage.CONDITION, it) }
        // Every other shape of the member itself — an explicit `null`, an object, a scalar — refuses A12's
        // own reader. The object case is the one worth naming: `errorMessage` ADMITS an object, because its
        // deserializer iterates the node's elements and an object's elements are its values, and inferring
        // one list-shaped member's reader from the other's would have analysed a model A12 never loads.
        member !is JsonArray -> listOf(DeclarationFinding(element.key, DeclarationStage.TARGET,
            unreadable(element, "`computationAlternatives` is not a JSON array")))
        else -> alternativeFindings(element, member)
    }
}

/**
 * A computation with no alternative at all carries TWO verdicts, and the second is the surprising one: with
 * no alternative there is no precondition slot either, so A12 reports the missing condition beside the
 * incompleteness. An alternative that merely lacks its operation draws one class alone — the incompleteness
 * while it is the only alternative, the token class beside a sibling — which is what separates "nothing to
 * compute" from "an alternative with no work in it".
 */
private fun noWorkFindings(element: TrackedDeclaration): List<Finding> = listOf(
    Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_INCOMPLETE_INPUT",
        "this computation declares no alternative, so it has nothing to compute — A12 refuses it, and an " +
            "empty `computationAlternatives` counts as none at all",
        element.path,
        // No alternative, so no text of this computation's to place it in.
        span = null,
    ),
    Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_CONDITION_MISSING",
        "with no alternative this computation has no precondition slot either, so A12 reports the missing " +
            "condition beside the missing work",
        element.path,
    ),
)

private fun TrackedFactBase.alternativeFindings(element: TrackedDeclaration, alternatives: JsonArray): List<OwnedFinding> {
    val declared = alternatives.map {
        it as? JsonObject
            ?: return listOf(DeclarationFinding(element.key, DeclarationStage.TARGET,
                unreadable(element, "a `computationAlternatives` entry is not a JSON object")))
    }
    val reached = conditionExecution(element)?.syntaxContribution(CheckFamily.COMPUTATION_ALTERNATIVES)
    val missingWork = listOfNotNull(reached?.let { contribution ->
        blankOperationFindings(element).firstOrNull { it.span?.member == contribution.finding.span?.member }
            ?.let { contribution.copy(finding = it) }
    })
    // A lone alternative owes no guard, so the demand exists only from two on — and then it is owed by each
    // of them, not by all but the last.
    if (declared.size < 2) {
        return missingWork
    }
    // Only an ABSENT or `null` member is an empty precondition. A present one is a condition, and whether it
    // parses is the syntax family's question.
    val unguarded = declared.withIndex()
        .filter { (_, alternative) -> alternative["precondition"].let { it == null || it is JsonNull } }
        .map { (index, _) -> index + 1 }
    if (unguarded.isEmpty()) {
        return missingWork
    }
    // ONE finding for the computation, however many alternatives are unguarded: A12 raises a single
    // notification naming the declaration and carrying no alternative index at all (measured —
    // `ComputationAlternativeSlotLawsTest.oneUnguardedNotificationCoversTheWholeComputation`). Emitting one
    // per alternative would tell a consumer A12 said twice what it said once; the numbers go in the text,
    // where they help an author without being claimed as A12's own report. It is raised where the walk meets
    // the first unguarded alternative, between the target reads of the alternatives around it
    // (`adapter.laws.UnguardedAlternativeSelfReadAgreementTest`).
    return missingWork + DeclarationFinding(element.key, DeclarationStage.CALCULATION_ALTERNATIVES, Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_ERROR_EMPTY_PRECONDITION",
        "this computation has ${declared.size} alternatives and " +
            (if (unguarded.size == 1) "alternative ${unguarded.single()} declares" else
                "alternatives ${unguarded.joinToString(", ")} declare") +
            " no precondition — every one of several owes its own, and a `commonPrecondition` does not " +
            "stand in for it",
        element.path,
    ), walkPosition = alternativeWalkPosition(unguarded.first() - 1, AlternativeStep.GATE))
}

private fun unreadable(element: TrackedDeclaration, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain, which A12's own reader refuses — the source never loads, and no consistency verdict about " +
        "this model exists",
    element.path,
)


/** Local slot diagnostics remain available on partial models; publication requires a reached failure (R5). */
private fun blankOperationFindings(element: TrackedDeclaration): List<Finding> {
    if (element.declaredKind != DeclaredKind.COMPUTATION) return emptyList()
    val body = element.body ?: return emptyList()
    val alternatives = body["computationAlternatives"] as? JsonArray ?: return emptyList()
    if (alternatives.any { it !is JsonObject }) return emptyList()
    return alternatives.mapIndexedNotNull { index, entry ->
        val operation = (entry as JsonObject)["operation"]
        val text = when (operation) {
            null, is JsonNull -> ""
            is JsonPrimitive -> if (operation.isString) operation.content else return@mapIndexedNotNull null
            else -> return@mapIndexedNotNull null
        }
        if (!text.isLexerBlank()) return@mapIndexedNotNull null
        val member = "computationAlternatives[$index].operation"
        Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR,
            if (followedInGeneratedText(element.kind, body, member)) "MVK_UNEXPECTED_TOKEN" else "MVK_INCOMPLETE_INPUT",
            "alternative ${index + 1} of this computation carries no operation — A12 refuses it, and a " +
                "blank or `null` `operation` counts as none at all",
            element.path, endOfBlankText(member, text))
    }
}
