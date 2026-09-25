package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionComparison
import io.github.mbackschat.a12.dm.consistency.syntax.comparisonsIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Whether two declarations may be compared once their LABELS are taken into account.
 *
 * This is the display half of `CheckVergleichsBedingungImpl`'s text-or-enumeration walk, whose kind and
 * domain halves [stringEnumComparisonAgreementFindings] owns. A12 asks it of two FIELDS, never of a written
 * value, so the three halves partition the comparison rather than competing
 * ([KERNEL-FINDINGS KF407], `rulekit.validate.laws.EnumDisplayComparabilityLawsTest`):
 *
 * | the pair | class |
 * |---|---|
 * | one side labels its values, the other does not | `MVK_INVALID_COMPARE_ENUM_TO_STRING` |
 * | both label, and a shared STORED token carries two texts | `MVK_INVALID_COMPARE_ENUM_TO_ENUM` |
 * | both label, and a shared DISPLAY text stands for two tokens | `MVK_INVALID_COMPARE_ENUM_TO_ENUM` |
 *
 * **"Labels its values" is not "carries a label".** `IEnumerationType.hasDisplayValues` looks for a value
 * whose text DIFFERS from its stored token, so an enumeration that labels `BASIC` as `BASIC` is textless and
 * compares happily with one carrying no labels at all. A reader testing for the presence of labels refuses
 * that pair, which A12 admits.
 *
 * **A label written for a locale the model does not DECLARE is invisible here**, measured: such an
 * enumeration is textless whatever its texts say. Together with the value law — once any value answers a
 * declared locale, every value owes every declared locale ([KERNEL-FINDINGS KF321]) — that makes A12's
 * "shared display locales" intersection always the declared set in a buildable model, so this reader walks
 * the declared locales rather than reconstructing the intersection.
 *
 * **Both directions of the map are checked, and neither implies the other.** One stored token carrying two
 * texts and one text standing for two tokens are different defects with one class, and an enumeration pair
 * can fail the second while agreeing on every shared token.
 *
 * **Silent on a CATEGORY access**, which returns before this gate on either side — measured on a pair that
 * conflicts, admitted through the projection and refused without it. Silent too wherever the shared side
 * reader will not reduce an operand to one declared field, which is the same boundary A12 draws with
 * `isFeldWertOp`: a labelled enumeration meets a string-valued FUNCTION of a field happily and refuses the
 * same field read bare.
 *
 * **Silent on a TABLE-backed enumeration**, whose values live outside the model.
 */
internal fun TrackedFactBase.enumDisplayComparabilityFindings(): List<Finding> = declarations().mapNotNull { element ->
    val body = element.body ?: return@mapNotNull null
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
        comparisonsIn(text, conditionLanguage, syntaxEntryOf(member))
            .firstNotNullOfOrNull { comparison -> displayFinding(comparison, group, element, member) }
    }
}

private const val MIXED_DISPLAY = "MVK_INVALID_COMPARE_ENUM_TO_STRING"

private const val CLASHING_DISPLAY = "MVK_INVALID_COMPARE_ENUM_TO_ENUM"

/** What one side is to THIS gate: an enumeration with its labels, a plain text field, or nothing it judges. */
private sealed interface DisplaySide {
    data object TextField : DisplaySide
    data class Enumeration(val path: String, val labels: Map<String, Map<String, String>>) : DisplaySide
}

private val DisplaySide.Enumeration.bearsDisplayValues: Boolean
    get() = labels.values.any { byToken -> byToken.any { (token, text) -> text != token } }

private fun TrackedFactBase.displayFinding(
    comparison: ConditionComparison,
    declaringGroup: String,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    // Only equality reaches this walk: a text or enumeration side cannot survive the ordering gate ahead of
    // it, so the two equality spellings are the whole reachable population.
    if (comparison.operator != "==" && comparison.operator != "!=") {
        return null
    }
    val left = displaySideOf(comparison.left, declaringGroup, element) ?: return null
    val right = displaySideOf(comparison.right, declaringGroup, element) ?: return null
    val defect = when {
        left is DisplaySide.Enumeration && right is DisplaySide.Enumeration -> enumPairDefect(left, right)
        left is DisplaySide.Enumeration -> textPartnerDefect(left)
        right is DisplaySide.Enumeration -> textPartnerDefect(right)
        else -> null
    } ?: return null
    return Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, defect.code,
        "in `$member`: ${defect.because}", element.path, comparison.spanIn(member))
}

private class DisplayDefect(val code: String, val because: String)

/** A labelled enumeration cannot meet a plain text field: A12 compares the label, which a text has none of. */
private fun textPartnerDefect(enumeration: DisplaySide.Enumeration): DisplayDefect? =
    if (enumeration.bearsDisplayValues) {
        DisplayDefect(
            MIXED_DISPLAY,
            "`${enumeration.path}` labels its values, and the other side is a plain text read — A12 " +
                "compares the LABELS of a labelled enumeration, and a text field carries none; compare it " +
                "against an enumeration labelled the same way, or drop the labels",
        )
    } else {
        null
    }

/**
 * The class two enumerations draw, in A12's order: the display/textless mismatch, then the map comparison.
 *
 * The second is reached only when both label their values, so the two never contend — and the first is a
 * mismatch rather than a property of either side, which is why it is asked of the PAIR.
 */
private fun TrackedFactBase.enumPairDefect(
    left: DisplaySide.Enumeration,
    right: DisplaySide.Enumeration,
): DisplayDefect? {
    if (left.bearsDisplayValues != right.bearsDisplayValues) {
        val bearing = if (left.bearsDisplayValues) left else right
        val other = if (left === bearing) right else left
        return DisplayDefect(
            MIXED_DISPLAY,
            "`${bearing.path}` labels its values and `${other.path}` does not — A12 compares two " +
                "enumerations by their labels, so either both carry them or neither does",
        )
    }
    if (!left.bearsDisplayValues) {
        return null
    }
    return declaredLocales.firstNotNullOfOrNull { locale ->
        val here = left.labels[locale] ?: return@firstNotNullOfOrNull null
        val there = right.labels[locale] ?: return@firstNotNullOfOrNull null
        clashingToken(left, right, here, there, locale) ?: clashingText(left, right, here, there, locale)
    }
}

/** A stored token both declare, labelled differently — the forward direction of A12's two map comparisons. */
private fun clashingToken(
    left: DisplaySide.Enumeration,
    right: DisplaySide.Enumeration,
    here: Map<String, String>,
    there: Map<String, String>,
    locale: String,
): DisplayDefect? = here.entries.firstOrNull { (token, text) -> there[token]?.equals(text) == false }
    ?.let { (token, text) ->
        DisplayDefect(
            CLASHING_DISPLAY,
            "`${left.path}` and `${right.path}` both declare `$token` and label it `$text` and " +
                "`${there[token]}` in `$locale` — a comparison matches the two by their labels, so a shared " +
                "value has to be labelled the same in every declared locale",
        )
    }

/** A display text both declare, standing for different tokens — the reverse direction of the same check. */
private fun clashingText(
    left: DisplaySide.Enumeration,
    right: DisplaySide.Enumeration,
    here: Map<String, String>,
    there: Map<String, String>,
    locale: String,
): DisplayDefect? {
    val theirTokens = there.entries.associate { (token, text) -> text to token }
    return here.entries.firstOrNull { (token, text) -> theirTokens[text]?.equals(token) == false }
        ?.let { (token, text) ->
            DisplayDefect(
                CLASHING_DISPLAY,
                "`${left.path}` and `${right.path}` both show `$text` in `$locale`, for `$token` and " +
                    "`${theirTokens[text]}` — a comparison matches the two by their labels, so one label " +
                    "cannot stand for two different values",
            )
        }
}

/** What this side is to this gate, or null wherever the shared reader will not reduce it to one field. */
private fun TrackedFactBase.displaySideOf(
    side: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): DisplaySide? {
    val declared = bareReferenceField(side, declaringGroup, element) ?: return null
    return when (declared.declaredFieldType()) {
        FieldType.STRING -> DisplaySide.TextField
        FieldType.ENUMERATION -> declaredLabels(declared)?.let { DisplaySide.Enumeration(declared.path, it) }
        else -> null
    }
}

/**
 * The declared locale to token-to-label map this enumeration carries, or null where it cannot be read.
 *
 * Only the DECLARED locales are collected, because a label written for any other does not make the
 * enumeration display-bearing to A12 — measured. A value carrying no label for a locale is entered under its
 * own token, which is what makes "labelled as itself" indistinguishable from unlabelled, as A12's own
 * `hasDisplayValues` has it.
 */
private fun TrackedFactBase.declaredLabels(field: TrackedDeclaration): Map<String, Map<String, String>>? {
    val body = field.enumerationBody() ?: return null
    if (body["tableName"] != null) {
        return null
    }
    val values = body["values"] as? JsonArray ?: return null
    val entries = values.map { it as? JsonObject ?: return null }
    return declaredLocales.associateWith { locale ->
        entries.associate { entry ->
            val token = entry.string("value") ?: return null
            token to (labelFor(entry, locale) ?: token)
        }
    }
}

private fun labelFor(value: JsonObject, locale: String): String? =
    (value["label"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        .lastOrNull { entry -> entry.string("locale")?.let { localeKeyOrNull(it) } == locale }
        ?.string("text")
