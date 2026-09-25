package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.isLexerBlank
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A computation's `computationAlternatives` — the list that carries its work, and the guards A12 demands of
 * it once there is more than one.
 *
 * Like the declaration slots, nothing here needs a condition parser: the rows read structure, and the one
 * place a condition's TEXT would decide the answer is deliberately left to the syntax family (see below).
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
    declarations().filter { it.declaredKind == DeclaredKind.COMPUTATION }.flatMap { alternativeFindings(it) }

/**
 * A computation is a whole element A12 refuses or judges as one, so an unreadable shape stops the read here
 * rather than contributing an alternative nothing can be said about.
 */
private fun alternativeFindings(element: TrackedDeclaration): List<Finding> {
    // An envelope A12 cannot bind is a tier ABOVE any verdict about this member, and reading it as an
    // absent member would report two codes about a model A12 never examined.
    val body = element.body ?: return listOf(unreadableEnvelope(element))
    val member = body["computationAlternatives"]
    return when {
        member == null -> noWorkFindings(element)
        member is JsonArray && member.isEmpty() -> noWorkFindings(element)
        // Every other shape of the member itself — an explicit `null`, an object, a scalar — refuses A12's
        // own reader. The object case is the one worth naming: `errorMessage` ADMITS an object, because its
        // deserializer iterates the node's elements and an object's elements are its values, and inferring
        // one list-shaped member's reader from the other's would have analysed a model A12 never loads.
        member !is JsonArray -> listOf(unreadable(element, "`computationAlternatives` is not a JSON array"))
        else -> alternativeFindings(element, member)
    }
}

/**
 * A computation with no alternative at all carries TWO verdicts, and the second is the surprising one: with
 * no alternative there is no precondition slot either, so A12 reports the missing condition beside the
 * incompleteness. An alternative that merely lacks its operation draws the incompleteness alone, which is
 * what separates "nothing to compute" from "an alternative with no work in it".
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

private fun alternativeFindings(element: TrackedDeclaration, alternatives: JsonArray): List<Finding> {
    val declared = alternatives.map {
        it as? JsonObject
            ?: return listOf(unreadable(element, "a `computationAlternatives` entry is not a JSON object"))
    }
    val missingWork = declared.mapIndexedNotNull { index, alternative ->
        if (alternative["operation"].isPresentText()) {
            null
        } else {
            Finding(
                FindingCategory.A12_CONSTRAINT,
                FindingSeverity.ERROR,
                "MVK_INCOMPLETE_INPUT",
                "alternative ${index + 1} of this computation carries no operation — A12 refuses it, and a " +
                    "blank or `null` `operation` counts as none at all",
                element.path,
                endOfBlankText(
                    "computationAlternatives[$index].operation",
                    (alternative["operation"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                ),
            )
        }
    }
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
    // where they help an author without being claimed as A12's own report.
    return missingWork + Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_ERROR_EMPTY_PRECONDITION",
        "this computation has ${declared.size} alternatives and " +
            (if (unguarded.size == 1) "alternative ${unguarded.single()} declares" else
                "alternatives ${unguarded.joinToString(", ")} declare") +
            " no precondition — every one of several owes its own, and a `commonPrecondition` does not " +
            "stand in for it",
        element.path,
    )
}

private fun unreadable(element: TrackedDeclaration, explain: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "$explain, which A12's own reader refuses — the source never loads, and no consistency verdict about " +
        "this model exists",
    element.path,
)

/**
 * Whether a member carries text A12 reads as present — absent, `null` and blank all count as none.
 *
 * "Blank" is the lexer's: an operation has no presence test of its own, so text of any other space reaches the
 * lexer and draws its error rather than this family's `MVK_INCOMPLETE_INPUT` ([KERNEL-FINDINGS KF310]).
 *
 * A non-string primitive answers TRUE and draws no finding: A12 coerces a scalar to the String member, as it
 * does for a rule's `errorCode`, and a container in this slot is unmeasured. Under-reporting is the safe
 * direction; inventing a verdict for either would be a refusal with no Kernel basis.
 */
private fun JsonElement?.isPresentText(): Boolean = when (this) {
    null, is JsonNull -> false
    is JsonPrimitive -> !isString || !content.isLexerBlank()
    else -> true
}
