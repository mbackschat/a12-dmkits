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
 * **The multiplicity is part of the law, and so is A12's collapse.** A12 emits one notification per unpaired
 * element, so two values under an empty token list draw two findings, but it keeps one of IDENTICAL
 * notifications: a value repeated past the tokens, or a repeated surplus token, is reported once per category.
 * The findings come in A12's blocks, every category's unclassified values first, then every surplus token,
 * then the spoilt pairings (KERNEL-FINDINGS KF551, `adapter.laws.CheckerEnumDeclarationStreamAgreementTest`).
 *
 * **A value or token carrying a supplementary-plane character is never named**, measured: it is reported
 * through the codeless "unsupported signs" notification instead, which this checker publishes as
 * `RK_UNSUPPORTED_CHARACTER`, and the entry still occupies its position in the pairing. Why is a hypothesis
 * read from source, not measured: A12 reads the declaration as a document of its metamodel, whose formal check
 * refuses any UTF-16 surrogate (KERNEL-FINDINGS KF551).
 *
 * **Values and tokens are identified TRIMMED, as Java's `String.trim` trims**: `BASIC` and `BASIC ` are one value
 * to the pairing, so they draw `MVK_CATEGORY_VALUE_DUPLICATE` and an unclassified pair is named once, and two
 * surplus tokens differing in a trailing space are one surplus token (KERNEL-FINDINGS KF418).
 *
 * **The member names are measured, not read off the Java fields**
 * (`rulekit.validate.laws.EnumCategoryWireShapeLawsTest`), and that grid also settled why a checker cannot
 * be lax about them: A12's reader REFUSES a member it does not know rather than ignoring it, so a body this
 * family gets to judge is one whose member names A12 has already accepted.
 *
 * **What it leaves alone**, all of it under-reporting: a body whose `values` or `categories` is not an array
 * of the shape measured here, and a value entry that does not spell a string `value` — each is a shape
 * nobody has put to A12, and refusing one would rest a rejection on a reading.
 *
 * **An EMPTY `values` is left alone for a different reason**: A12 does not reach the pairing at all, because
 * an enumeration declaring no values makes the model unbuildable with or without categories (measured). This
 * family reported two `MVK_MISSING_INTERNAL_VALUE` there until the row was run — a code A12 never raises on
 * that model, which is the pairing law transferred onto a shape the pairing never sees.
 */
internal fun TrackedFactBase.enumCategoryFindings(): List<Finding> =
    defaultVariantFields().flatMap { field ->
        val body = field.enumerationBody() ?: return@flatMap emptyList()
        val values = body.declaredEnumValues()?.takeIf { it.isNotEmpty() }?.map { it.javaTrimmed() }
            ?: return@flatMap emptyList()
        val categories = (body["categories"] as? JsonArray ?: return@flatMap emptyList()).mapNotNull { it.pairing() }
        categories.flatMap { field.shortfall(values, it) } + categories.flatMap { field.surplus(values, it) } +
            categories.flatMap { field.spoiltPairing(values, it) }
    }

/** One category as this family reads it, or null when it is not the measured shape. */
private class Pairing(val name: String, val tokens: JsonArray)

private fun JsonElement.pairing(): Pairing? {
    val declared = this as? JsonObject ?: return null
    val tokens = declared["values"] as? JsonArray ?: return null
    val named = (declared["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return Pairing(named, tokens)
}

/** Whether A12 names this entry in a coded notification: one carrying a UTF-16 surrogate it does not. */
internal fun String.namedByA12(): Boolean = none { it.isSurrogate() }

/**
 * The declarations this family stayed silent about, so silence is never read as agreement.
 *
 * Every arm above that returns early is under-reporting and therefore safe — but `checksCompleted` names
 * this family on the same report, and a consumer reading both would take "ran, found nothing" for a
 * declaration the family never paired. The gap is per DECLARATION rather than per model because that is
 * what the consumer needs: which enumeration was left to A12, not that some were.
 */
internal fun TrackedFactBase.enumCategoryGaps(): List<CoverageGap> =
    defaultVariantFields().mapNotNull { field ->
        val unread = field.enumerationBody()?.unreadableHalf() ?: return@mapNotNull null
        CoverageGap(
            field.path,
            "this Enumeration's categories were not paired with its values, so A12 alone judges them: $unread",
        )
    }

/** The `EnumerationType` body this field declares, or null when it declares another kind or none at all. */
internal fun TrackedDeclaration.enumerationBody(): JsonObject? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if (declared.namedFieldType() != FieldType.ENUMERATION) {
        return null
    }
    return FieldType.ENUMERATION.bodyIn(declared)
}

/**
 * Which half of the body this family could not read, or null when it read both.
 *
 * An ABSENT `categories` is not one of them: a declaration with no categories has no pairing to judge, and
 * A12 has nothing to say about it either, so reporting a gap there would describe every ordinary enum.
 */
private fun JsonObject.unreadableHalf(): String? {
    val values = declaredEnumValues()
        ?: return "its `values` carries an entry this checker cannot read as a string `value`, and " +
            "refusing a shape nobody has put to A12 would rest a rejection on a reading"
    if (values.isEmpty()) {
        return "it declares NO values, which A12 refuses to build the model from with or without " +
            "categories, so the pairing is never reached — the refusal itself is left to A12 because a " +
            "dynamic enumeration takes its values from a rule, and whether that makes an empty list legal " +
            "is unmeasured"
    }
    val categories = this["categories"] ?: return null
    val entries = categories as? JsonArray ?: return "its `categories` is present and is not an array"
    return entries.firstNotNullOfOrNull { entry ->
        val declared = entry as? JsonObject ?: return@firstNotNullOfOrNull "a `categories` entry is not an object"
        when {
            declared["values"] !is JsonArray -> "a category's own `values` is missing or is not an array"
            (declared["name"] as? JsonPrimitive)?.takeIf { it.isString } == null ->
                "a category spells no string `name`, and A12 names the category in both of its codes"
            else -> null
        }
    }
}

/**
 * The enum's own value spellings, in declaration order, or null when the list is not the measured shape.
 *
 * Null rather than a short list is what three families depend on: a shortened domain turns a neighbouring
 * `values` defect into a wrong constant class on every literal compared against it.
 */
internal fun JsonObject.declaredEnumValues(): List<String>? {
    val declared = this["values"] as? JsonArray ?: return null
    return declared.map { entry ->
        val value = ((entry as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }
        value?.content ?: return null
    }
}

/** The values past this category's last token, each named once. */
private fun TrackedDeclaration.shortfall(values: List<String>, category: Pairing): List<Finding> =
    values.drop(category.tokens.size).filter { it.namedByA12() }.distinct()
        .map { unclassified(category.name, it, category.tokens.size) }

/** The tokens past this enum's last value, each named once. */
private fun TrackedDeclaration.surplus(values: List<String>, category: Pairing): List<Finding> =
    category.tokens.drop(values.size).map { it.tokenText().javaTrimmed() }.filter { it.namedByA12() }.distinct()
        .map { unmatched(category.name, it, values.size) }

/**
 * The pairings a value declared twice makes ambiguous — one finding per duplicated value, per category.
 *
 * **`MVK_CATEGORY_VALUE_DUPLICATE` is not what its name suggests.** A category whose own tokens repeat is
 * ADMITTED; the class is drawn when an INTERNAL value repeats, and only while a category exists to be paired
 * with it — the same enum with no `categories` draws the value class alone ([KERNEL-FINDINGS KF344],
 * `rulekit.validate.laws.EnumValueMultipleDefectLawsTest`). So the duplicate is reported twice over, once
 * against the value list's own identity rule and once for every pairing it spoils.
 *
 * **The multiplicity is the cross product, measured at the one cell that settles it.** Two duplicated values
 * under two categories draw FOUR; 1×2 and 2×1 each draw two, which a maximum and a sum predict as well. And
 * it counts duplicated VALUES rather than extra occurrences: a token declared three times draws one.
 */
private fun TrackedDeclaration.spoiltPairing(values: List<String>, category: Pairing): List<Finding> {
    val named = category.name
    return values.filter { it.namedByA12() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { token ->
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_CATEGORY_VALUE_DUPLICATE",
            "the value `$token` is declared more than once, so the category `$named` cannot say which " +
                "token classifies it — the tokens pair with the values BY POSITION, and a repeated value " +
                "leaves that pairing ambiguous wherever a category exists",
            path,
        )
    }
}

private fun TrackedDeclaration.unclassified(category: String, value: String, tokens: Int) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_MISSING_CATEGORY_VALUE",
    "the value `$value` has no token in the category `$category`, which declares $tokens of them — a " +
        "category's tokens pair with the enum's values BY POSITION, so the two lists must be the same " +
        "length and adding a token for this value is the fix",
    path,
)

/**
 * The surplus token NAMES itself, because A12 raises one diagnostic per DISTINCT surplus token (two distinct
 * ones draw two, a repeated one draws one). Naming it keeps the two distinct findings distinct for a consumer
 * deduplicating on code, path and text, which would otherwise fold the multiplicity this family reproduces.
 */
private fun TrackedDeclaration.unmatched(category: String, token: String, values: Int) = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_MISSING_INTERNAL_VALUE",
    "the token `$token` in the category `$category` classifies nothing: the category declares more tokens " +
        "than the enum has values ($values), and the pairing is positional, so a trailing token has no " +
        "value to pair with and A12 refuses it",
    path,
)

/** A token as declared — non-string entries are rendered rather than refused, since A12 reads the list. */
private fun JsonElement.tokenText(): String = (this as? JsonPrimitive)?.content ?: toString()
