package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Whether A12 can BIND every member it declares as text — one law, stated once, for all three carriers.
 *
 * A member holding a JSON container where A12 declares a string refuses A12's own reader outright: no
 * diagnostic, no code, the source simply does not load. Measured across every such member on every carrier
 * that owns one — a rule's `errorCondition` and `errorEntityRelPath`, a computation alternative's `operation`
 * and `precondition`, a group's `indexFieldName` — with four container shapes each, and the answer is the
 * same twenty times over ([KERNEL-FINDINGS KF304], `rulekit.validate.laws.StringSlotContainerLawsTest`).
 *
 * **Uniformity is the reason this is one family rather than a branch in each owning family, and it was not
 * the safe guess.** The adjacent presentation members are the standing warning: `guiPatterns` and
 * `variantErrorMessages` sit on one element and answer the identical shape in different tiers, and one of
 * them coerces a bare scalar into a slot typed for a list while refusing an object into that same slot. So
 * the uniformity here is a measurement, not an inference from them — and having measured it, restating the
 * shape test in three families would be three chances to disagree about one law.
 *
 * **What this family deliberately does NOT judge.** A non-string SCALAR is coerced into A12's string slot, so
 * a numeric `errorCondition` passes here; refusing it would reject a model A12 loads. Blank and absent
 * members belong to the families that own their verdicts — `ruleConditionAndTargetFindings` reports a blank
 * `errorCondition` as A12's own `MVK_CONDITION_MISSING` and an absent one as unbuildable — and duplicating
 * either here would hand a consumer two findings for one defect, the mistake `UnreadableEnvelopeTest` counts.
 * This family reports only the shape those families leave unjudged.
 *
 * The finding is [CK_MODEL_NOT_BUILDABLE] rather than any `MVK_*`, because A12 never reaches the tier that
 * issues codes.
 */
fun PreparedModel.stringSlotFindings(): List<Finding> = elements.flatMap { element ->
    val body = element.body ?: return@flatMap emptyList()
    when (element.declaredKind) {
        DeclaredKind.RULE -> RULE_TEXT_MEMBERS.mapNotNull { unbindableText(element, body, it) }
        DeclaredKind.COMPUTATION -> alternativeTextFindings(element, body)
        DeclaredKind.GROUP -> listOfNotNull(unbindableText(element, body, "indexFieldName")) +
            listOfNotNull(unbindableSortFields(element, body))
        else -> emptyList()
    }
}

private val RULE_TEXT_MEMBERS = listOf("errorCondition", "errorEntityRelPath")

private val ALTERNATIVE_TEXT_MEMBERS = listOf("operation", "precondition")

/**
 * The alternative carrier, reached through its own list.
 *
 * A `computationAlternatives` that is not a list, or an entry that is not an object, is the alternative
 * family's finding and not this one's — this family would otherwise report the same unusable model twice.
 */
private fun alternativeTextFindings(element: DeclaredElement, body: JsonObject): List<Finding> {
    val alternatives = body["computationAlternatives"] as? JsonArray ?: return emptyList()
    return alternatives.flatMap { entry ->
        val alternative = entry as? JsonObject ?: return@flatMap emptyList()
        ALTERNATIVE_TEXT_MEMBERS.mapNotNull { unbindableText(element, alternative, it) }
    }
}

/** One member of one carrier: a finding when it holds a container, and nothing for every other shape. */
private fun unbindableText(element: DeclaredElement, carrier: JsonObject, member: String): Finding? {
    val declared = carrier[member] ?: return null
    if (declared !is JsonObject && declared !is JsonArray) {
        return null
    }
    return unbindableSlot(element, member, "holds ${shapeOfSlot(declared)} where A12 binds text")
}

/**
 * `sortFields` is the mirror question, and it belongs here for the same reason the others do.
 *
 * It is declared as a LIST, so the unbindable shapes are the non-list ones rather than the container ones —
 * an object, a string and a number were each measured and each refuses the reader. Its ENTRIES' `name`
 * members are the ordering family's business, which reads them once they bind.
 */
private fun unbindableSortFields(element: DeclaredElement, body: JsonObject): Finding? {
    val declared = body["sortFields"] ?: return null
    if (declared is JsonArray) {
        return null
    }
    return unbindableSlot(element, "sortFields", "holds ${shapeOfSlot(declared)} where A12 binds a list")
}

private fun unbindableSlot(element: DeclaredElement, member: String, complaint: String) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    CK_MODEL_NOT_BUILDABLE,
    "`$member` $complaint — A12's own reader refuses the source outright, so the model never loads and no " +
        "consistency verdict about it exists. A non-text scalar would have been coerced and accepted; it is " +
        "the container that cannot bind.",
    element.path,
)

/** How to name the offending shape, in the reader's terms rather than the serializer's. */
private fun shapeOfSlot(element: JsonElement): String = when (element) {
    is JsonArray -> "a list"
    is JsonObject -> "an object"
    else -> "text"
}
