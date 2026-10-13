package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A declared enumeration's VALUE tokens and the per-locale labels beside them.
 *
 * [enumCategoryFindings] pairs the categories with these values and [supportedCharacterFindings] judges the
 * tokens' alphabet; this family is about the values' own identity and their labels
 * ([KERNEL-FINDINGS KF321], `rulekit.validate.laws.EnumValueAndLabelLawsTest`).
 *
 * **Almost nothing about the TOKEN is checked, and that is a measured result rather than a gap.** A repeated
 * token draws `MVK_INTERNAL_VALUES_DUPLICATE`; an empty token, a token with a space and a 300-character
 * token are all ADMITTED. The four `MVK_VALUE_*` codes that read as if they governed this member fire for no
 * shape reachable here, so reproducing any of them would refuse a model A12 takes.
 *
 * **The label law is "started and not finished", and it is NOT "every value has a label".** Once any value
 * carries an entry for any DECLARED locale, every value must carry a non-blank text for every declared
 * locale. Two rows pin it and neither follows from the other: labels written only for an UNDECLARED locale
 * are admitted, because no declared locale has an entry at all — while labels present for a declared locale
 * with every text BLANK are refused, because the entries are there. A checker asking "is this locale
 * answered" gets the first row wrong; one asking "is any label present" gets the second wrong.
 *
 * **The two codes are separated by WHOSE disagreement it is.** Values that disagree on carrying a label at
 * all — an empty `label` array counts as carrying none — draw `MVK_COUNT_OF_VALUES_INVALID`, and A12 reports
 * that INSTEAD of the locale code, so this family does the same.
 */
internal fun TrackedFactBase.enumValueLabelFindings(): List<Finding> =
    declarations().filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val values = field.enumerationBody()?.get("values") as? JsonArray ?: return@flatMap emptyList()
        val entries = values.map { it as? JsonObject }
        duplicateTokenFindings(field, entries) + labelFindings(field, entries)
    }

private fun duplicateTokenFindings(field: TrackedDeclaration, values: List<JsonObject?>): List<Finding> {
    // A value with a supplementary-plane character is not named; A12 reports it as an unsupported sign (KF551).
    val tokens = values.mapNotNull { it?.string("value") }.filter { it.namedByA12() }
    val repeated = tokens.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    return repeated.map { token ->
        finding(
            field,
            "MVK_INTERNAL_VALUES_DUPLICATE",
            "this enumeration declares the value `$token` more than once, which A12 refuses — the tokens " +
                "are its identities, and nothing else about their spelling is checked here",
        )
    }
}

private fun TrackedFactBase.labelFindings(
    field: TrackedDeclaration,
    values: List<JsonObject?>,
): List<Finding> {
    val labelled = values.map { (it?.get("label") as? JsonArray)?.takeIf { list -> list.isNotEmpty() } }
    // A value with an EMPTY label array is one that carries none, which is the row that separates the two
    // codes: A12 answers the values' own disagreement and says nothing about the locales.
    if (labelled.any { it != null } && labelled.any { it == null }) {
        return listOf(
            finding(
                field,
                "MVK_COUNT_OF_VALUES_INVALID",
                "some of this enumeration's values carry a `label` and others do not, which A12 refuses; " +
                    "an empty `label` list counts as carrying none",
            ),
        )
    }
    val texts = labelled.map { entries ->
        entries.orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { entry -> entry.string("locale")?.let { localeKeyOrNull(it) }?.to(entry) }
            // A later entry for one locale replaces an earlier one, as everywhere else A12 collects texts.
            .toMap()
    }
    if (declaredLocales.none { locale -> texts.any { it.containsKey(locale) } }) {
        return emptyList()
    }
    val unanswered = declaredLocales.filter { locale ->
        texts.any { value -> value[locale]?.string("text").isNullOrBlank() }
    }
    if (unanswered.isNotEmpty()) {
        return unanswered.map { locale ->
            finding(
                field,
                "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES",
                "this enumeration labels some of its values for a declared locale and leaves `$locale` " +
                    "without a text on at least one of them; A12 refuses a label set that is started and " +
                    "not finished, and a blank text is no text",
            )
        }
    }
    return repeatedLabelFindings(field, texts)
}

/**
 * The texts A12 asks to be distinct, and the scope of that law is ONE LANGUAGE.
 *
 * A12 writes one per-language block per declared locale and asks each block's `displayValue` list to be
 * unique ([KERNEL-FINDINGS KF368], `rulekit.validate.laws.EnumValueAndLabelLawsTest`), so the same text used
 * for one value in English and another in German is ADMITTED — the row a family keyed on the texts alone
 * would refuse.
 *
 * **It is asked only where the label set is otherwise complete**, which is where it was measured: a set that
 * is short or half-blank draws its own class first, and whether A12 adds this one there is unmeasured.
 * Under-reporting the pair is safe; naming a class on an unmeasured shape is not.
 */
private fun repeatedLabelFindings(
    field: TrackedDeclaration,
    texts: List<Map<String, JsonObject>>,
): List<Finding> {
    val perLocale = mutableMapOf<String, MutableList<String>>()
    for (value in texts) {
        for ((locale, entry) in value) {
            entry.string("text")?.let { perLocale.getOrPut(locale) { mutableListOf() }.add(it) }
        }
    }
    // Sorted rather than in encounter order, because a report's finding order is part of its contract and
    // `toSortedMap` is JVM-only — this file is common code.
    return perLocale.entries.sortedBy { it.key }.flatMap { (locale, written) ->
        written.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted().map { text ->
            finding(
                field,
                "MVK_DISPLAY_VALUES_DUPLICATE",
                "two of this enumeration's values are both labelled `$text` in `$locale`, which A12 " +
                    "refuses — a label is what a user picks by, so it has to tell the values apart. The " +
                    "same text in two different languages is fine",
            )
        }
    }
}

private fun finding(field: TrackedDeclaration, code: String, explain: String) =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, field.path)
