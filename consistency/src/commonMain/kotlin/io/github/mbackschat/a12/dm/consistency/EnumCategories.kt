package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
 * (`rulekit.validate.laws.EnumCategoryWireShapeLawsTest`). Kernel 31.1.1's reader passes over a member it does not
 * know ([KERNEL-FINDINGS KF654]), so a misspelled `categories` is no categories and a misspelled `value` is a value
 * entry with no token.
 *
 * **A value entry with no token keeps its position** ([KERNEL-FINDINGS KF655]): a token paired with it
 * classifies nothing and draws `MVK_MISSING_INTERNAL_VALUE`, and the values after it still pair by position. The
 * rival, a pairing over the entries that carry a token, admits a token-less entry between two values under a
 * two-token category, where A12 draws both classes. A token-less entry past the tokens is not judged: no row has
 * measured it.
 *
 * **What it leaves alone**, all of it under-reporting: a body whose `values` or `categories` is not an array
 * of the shape measured here, and a value entry whose `value` is present and not a string — each is a shape
 * nobody has put to A12, and refusing one would rest a rejection on a reading.
 *
 * **An EMPTY `values` is left alone for a different reason**: A12 does not reach the pairing at all, because
 * an enumeration declaring no values makes the model unbuildable with or without categories (measured). This
 * family reported two `MVK_MISSING_INTERNAL_VALUE` there until the row was run — a code A12 never raises on
 * that model, which is the pairing law transferred onto a shape the pairing never sees.
 */
internal fun TrackedFactBase.enumCategoryFindings(): List<Finding> =
    fieldDeclarations().flatMap { field ->
        val body = field.ownEnumerationBody() ?: return@flatMap emptyList()
        val values = body.declaredEnumPositions()?.takeIf { it.isNotEmpty() }?.map { it?.javaTrimmed() }
            ?: return@flatMap emptyList()
        val declared = body["categories"] as? JsonArray ?: return@flatMap emptyList()
        val categories = declared.mapNotNull { it.pairing() }
        categories.flatMap { field.shortfall(values, it) } + categories.flatMap { field.surplus(values, it) } +
            categories.flatMap { field.spoiltPairing(values, it) } + listOfNotNull(field.unnamedCategory(declared))
    }

/**
 * `MVK_NAME_MISSING`, once per field, for a category whose `name` is absent, empty or blank as Java's `isBlank` reads
 * it, the em space included; such a category is paired with nothing, so a token shortfall beside it draws no class of
 * its own, while the field's named categories are still judged. Measured on kernel 31.1.1
 * (`adapter.laws.CheckerEmptyMemberAgreementTest`); KERNEL-FINDINGS KF367 had measured the carrier unreachable on
 * 30.8.1. Two such categories are the build failure [fieldTypeBindingFindings] reports, and an explicit `null` name too.
 */
private fun TrackedDeclaration.unnamedCategory(declared: JsonArray): Finding? =
    declared.filterIsInstance<JsonObject>().firstOrNull { it.namesNoCategory() }?.let {
        Finding(
            FindingCategory.A12_CONSTRAINT,
            FindingSeverity.ERROR,
            "MVK_NAME_MISSING",
            "a category of this enumeration has no `name`, or only a blank one, and A12 requires every category to " +
                "be named — name it, or remove the category",
            path,
        )
    }

/** Whether this category entry's `name` is absent or a blank string — the two shapes A12 reads as no name. */
private fun JsonObject.namesNoCategory(): Boolean = when (val name = this["name"]) {
    null -> true
    is JsonPrimitive -> name.isString && name.content.isA12Blank()
    else -> false
}

/** One category as this family reads it, or null when it is not the measured shape. */
private class Pairing(val name: String, val tokens: JsonArray)

private fun JsonElement.pairing(): Pairing? {
    val declared = this as? JsonObject ?: return null
    // An absent token list is the empty one, measured: each value then draws `MVK_MISSING_CATEGORY_VALUE`
    // (`adapter.laws.CheckerEmptyMemberAgreementTest`).
    val tokens = declared["values"]?.let { it as? JsonArray ?: return null } ?: JsonArray(emptyList())
    val named = (declared["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return Pairing(named.takeUnless { it.isA12Blank() } ?: return null, tokens)
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
    fieldDeclarations().mapNotNull { field ->
        val unread = field.ownEnumerationBody()?.unreadableHalf() ?: return@mapNotNull null
        CoverageGap(
            field.path,
            "this Enumeration's categories were not paired with its values, so A12 alone judges them: $unread",
        )
    }

/**
 * The `EnumerationType` body this field's OWN declaration carries, or null when it declares another kind or none.
 *
 * For a family that judges the declaration itself. A rule or computation reading the field reads
 * [enumerationBodyOf] instead, which A12 resolves through a type definition ([KERNEL-FINDINGS KF602]).
 */
internal fun TrackedDeclaration.ownEnumerationBody(): JsonObject? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    if (declared.namedFieldType() != FieldType.ENUMERATION) {
        return null
    }
    return FieldType.ENUMERATION.bodyIn(declared)
}

/** The `EnumerationType` body A12 reads this field by, through its type definition where it has one. */
internal fun TrackedFactBase.enumerationBodyOf(field: TrackedDeclaration): JsonObject? = typeBodyOf(field, FieldType.ENUMERATION)

/**
 * Which half of the body this family could not read, or null when it read both.
 *
 * An ABSENT `categories` is not one of them: a declaration with no categories has no pairing to judge, and
 * A12 has nothing to say about it either, so reporting a gap there would describe every ordinary enum.
 */
private fun JsonObject.unreadableHalf(): String? {
    val values = declaredEnumPositions()
        ?: return "its `values` carries an entry this checker cannot read as a string `value` or as one with no token, and " +
            "refusing a shape nobody has put to A12 would rest a rejection on a reading"
    if (values.isEmpty()) {
        return "it declares NO values, which leaves the model unbuilt with or without categories, so the " +
            "pairing is never reached"
    }
    val categories = this["categories"] ?: return null
    val entries = categories as? JsonArray ?: return "its `categories` is present and is not an array"
    return entries.firstNotNullOfOrNull { entry ->
        val declared = entry as? JsonObject ?: return@firstNotNullOfOrNull "a `categories` entry is not an object"
        when {
            declared["values"] != null && declared["values"] !is JsonArray -> "a category's own `values` is not an array"
            declared["name"] != null && (declared["name"] as? JsonPrimitive)?.takeIf { it.isString } == null ->
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
internal fun JsonObject.declaredEnumValues(): List<String>? = declaredEnumPositions()?.takeIf { null !in it }?.filterNotNull()

/**
 * The enum's value positions, in declaration order, an entry with no `value` member as null, or null when an entry is
 * not an object or spells a `value` that is not a string.
 */
private fun JsonObject.declaredEnumPositions(): List<String?>? {
    val declared = this["values"] as? JsonArray ?: return null
    return declared.map { entry ->
        val value = entry as? JsonObject ?: return null
        if ("value" !in value) return@map null
        ((value["value"] as? JsonPrimitive)?.takeIf { it.isString } ?: return null).content
    }
}

/**
 * The values this enumeration is looked up in — its own, or the named category's INSTEAD of them — or null when
 * either list is not the measured shape.
 *
 * An own value written `""` is not in it: kernel 31.1.1's expand copy reads the empty token as an absent one
 * ([KERNEL-FINDINGS KF658]), so the entry keeps its place in the list but no condition literal finds it, and a
 * comparison or value-list membership against `""` draws the constant class (`adapter.laws.EmptyEnumerationTokenDiffTest`).
 */
internal fun JsonObject.lookupDomain(category: String?): List<String>? {
    if (category == null) {
        return declaredEnumValues()?.filter { it.isNotEmpty() }
    }
    val entries = this["categories"] as? JsonArray ?: return null
    val named = entries.filterIsInstance<JsonObject>()
        .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == category } ?: return null
    val values = named["values"] as? JsonArray ?: return null
    return values.map { (it as? JsonPrimitive)?.takeIf { token -> token.isString }?.content ?: return null }
}

/**
 * The values this category leaves unclassified, in position order and each named once: those past its last token, and
 * those whose token is `null` or blank as Java's `isBlank` reads it, which A12 reads as no token at all (measured on
 * kernel 31.1.1, `adapter.laws.CheckerCategoryTokenAgreementTest`). A token-less value is not judged.
 */
private fun TrackedDeclaration.shortfall(values: List<String?>, category: Pairing): List<Finding> =
    values.filterIndexed { position, _ -> category.tokens.getOrNull(position).classifiesNothing() }
        .filterNotNull().filter { it.namedByA12() }.distinct()
        .map { unclassified(category.name, it, category.tokens.size) }

/**
 * The tokens past this enum's last value or paired with a token-less one, each named once. A `null` or blank token
 * there is no token, so it draws nothing (measured, the same test).
 */
private fun TrackedDeclaration.surplus(values: List<String?>, category: Pairing): List<Finding> =
    category.tokens.filterIndexed { position, token -> values.getOrNull(position) == null && !token.classifiesNothing() }
        .map { it.tokenText().javaTrimmed() }.filter { it.namedByA12() }.distinct()
        .map { unmatched(category.name, it, values.size) }

/** Whether this token position carries no token as A12 reads it: absent, `null`, or a blank string. */
private fun JsonElement?.classifiesNothing(): Boolean = when (this) {
    null, is JsonNull -> true
    is JsonPrimitive -> isString && content.isA12Blank()
    else -> false
}

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
private fun TrackedDeclaration.spoiltPairing(values: List<String?>, category: Pairing): List<Finding> {
    val named = category.name
    return values.filterNotNull().filter { it.namedByA12() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.map { token ->
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
        "category's tokens pair with the enum's values BY POSITION, and a blank or `null` token is no token, so " +
        "giving this value a token at its position is the fix",
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
    "the token `$token` in the category `$category` classifies nothing: the pairing with the enum's $values " +
        "value entries is positional, and the entry at its position is missing or carries no `value` token, so " +
        "A12 refuses it",
    path,
)

/** A token as declared — non-string entries are rendered rather than refused, since A12 reads the list. */
private fun JsonElement.tokenText(): String = (this as? JsonPrimitive)?.content ?: toString()
