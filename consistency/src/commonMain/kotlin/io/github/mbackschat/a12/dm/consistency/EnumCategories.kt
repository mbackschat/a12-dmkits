package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * An Enumeration's categories against its values — a POSITIONAL pairing, so equal cardinality is the law.
 *
 * A category carries a token list that pairs with the enum's values **by position, left to right**, not by
 * name: same-length disjoint tokens are ADMITTED, and over three values a single token leaves A12 naming the
 * SECOND and third as unclassified ([KERNEL-FINDINGS KF216],
 * `rulekit.validate.laws.EnumCategoryAlignmentLawsTest`). So the check is a cardinality comparison and not a
 * set membership one, and A12 reports the two directions separately: a value with no token is
 * `MVK_MISSING_CATEGORY_VALUE`, a token with no value is `MVK_MISSING_INTERNAL_VALUE`.
 *
 * **The multiplicity is part of the law.** A12 emits one notification per unpaired element, so two values
 * under an empty token list draw two findings. A family reporting the misalignment once would be reporting a
 * different fact, and the oracle keeps its multiplicity for exactly that reason.
 *
 * **The member names are measured, not read off the Java fields**
 * (`rulekit.validate.laws.EnumCategoryWireShapeLawsTest`), and that grid also settled why a checker cannot
 * be lax about them: A12's reader REFUSES a member it does not know rather than ignoring it, so a body this
 * family gets to judge is one whose member names A12 has already accepted.
 *
 * **What it leaves alone**, all of it under-reporting: a body whose `values` or `categories` is not an array
 * of the shape measured here, and a value entry that does not spell a string `value` — each is a shape
 * nobody has put to A12, and refusing one would rest a rejection on a reading.
 */
fun PreparedModel.enumCategoryFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val declared = field.body?.get("fieldType") as? JsonObject ?: return@flatMap emptyList()
        if ((declared["type"] as? JsonPrimitive)?.content != "EnumerationType") {
            return@flatMap emptyList()
        }
        val body = declared["EnumerationType"] as? JsonObject ?: return@flatMap emptyList()
        val values = body.declaredValues() ?: return@flatMap emptyList()
        val categories = body["categories"] as? JsonArray ?: return@flatMap emptyList()
        categories.flatMap { category -> field.misalignment(values, category) }
    }

/** The enum's own value spellings, in declaration order, or null when the list is not the measured shape. */
private fun JsonObject.declaredValues(): List<String>? {
    val declared = this["values"] as? JsonArray ?: return null
    return declared.map { entry ->
        val value = ((entry as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }
        value?.content ?: return null
    }
}

/** One category's unpaired elements, in the order A12 reports them: its own values first, then its tokens. */
private fun DeclaredElement.misalignment(values: List<String>, category: JsonElement): List<Finding> {
    val declared = category as? JsonObject ?: return emptyList()
    val tokens = declared["values"] as? JsonArray ?: return emptyList()
    val named = (declared["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return emptyList()
    return when {
        tokens.size < values.size -> values.drop(tokens.size).map { unclassified(named, it, tokens.size) }
        tokens.size > values.size -> tokens.drop(values.size).map { unmatched(named, values.size) }
        else -> emptyList()
    }
}

private fun DeclaredElement.unclassified(category: String, value: String, tokens: Int) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_MISSING_CATEGORY_VALUE",
    "the value `$value` has no token in the category `$category`, which declares $tokens of them — a " +
        "category's tokens pair with the enum's values BY POSITION, so the two lists must be the same " +
        "length and adding a token for this value is the fix",
    path,
)

private fun DeclaredElement.unmatched(category: String, values: Int) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_MISSING_INTERNAL_VALUE",
    "the category `$category` declares more tokens than the enum has values ($values) — the pairing is " +
        "positional, so a trailing token classifies nothing and A12 refuses it",
    path,
)
