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
 * **The label law, on kernel 31.1.1, reads a `label` list as A12's converter does** ([KERNEL-FINDINGS KF655]): a value
 * whose list holds ANY entry is labelled, and is then given a text for every DECLARED locale, the declared locale's
 * own text or none. Three classes follow, and none pre-empts another:
 *
 * - values that disagree on being labelled draw `MVK_COUNT_OF_VALUES_INVALID`, an empty `label` list counting as none;
 * - a labelled value with a token and no non-blank text for a declared locale, or with a text and no token, draws
 *   `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` for that locale;
 * - a labelled value with no token draws `MVK_INTERNAL_VALUE_MISSING`.
 *
 * An EMPTY token is a token while the enumeration is unlabelled and none once it is labelled, and labelled with its own
 * empty text A12 cannot build the model at all (KERNEL-FINDINGS KF321). Only the empty string is measured; a token of
 * spaces is left to the classes above.
 *
 * The rows that separate this from kernel 30.8.1's law ("a label is carried only for a declared locale", KF569)
 * are the undeclared ones: a value labelled only for an undeclared locale beside an unlabelled value now draws both
 * the count and the locale class, where 30.8.1 admitted it.
 *
 * **A value entry with no `value` member is a value with no token** (KERNEL-FINDINGS KF654, KF655): when no entry has one, A12 draws
 * `MVK_INTERNAL_VALUES_MISSING`. An entry whose `value` is present and not a string is not judged here.
 */
internal fun TrackedFactBase.enumValueLabelFindings(): List<Finding> =
    fieldDeclarations().flatMap { field ->
        val values = field.ownEnumerationBody()?.get("values") as? JsonArray ?: return@flatMap emptyList()
        val entries = values.map { it as? JsonObject }
        duplicateTokenFindings(field, entries) + labelFindings(field, entries)
    }

private fun duplicateTokenFindings(field: TrackedDeclaration, values: List<JsonObject?>): List<Finding> {
    // A value with a supplementary-plane character is not named; A12 reports it as an unsupported sign (KF551).
    // A12 compares the values trimmed, as Java's `String.trim` trims: `BASIC` and `BASIC\t` repeat (KF418).
    val tokens = values.mapNotNull { it?.string("value")?.javaTrimmed() }.filter { it.namedByA12() }
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
    val labelled = values.filterNotNull().filter { localizedTextEntries(it["label"]).isNotEmpty() }
    val tokenless = values.filterNotNull().filter { "value" !in it }
    val texts = labelled.map { value ->
        localizedTextEntries(value["label"])
            .mapNotNull { entry -> entry.string("locale")?.let { localeKeyOrNull(it) }?.to(entry) }
            .filter { (locale, _) -> locale in declaredLocales }
            // A later entry for one locale replaces an earlier one, as everywhere else A12 collects texts.
            .toMap()
    }
    if (labelled.indices.any { index ->
            labelled[index].string("value") == "" && declaredLocales.any { texts[index][it]?.string("text") == "" }
        }) {
        return listOf(finding(field, CK_MODEL_NOT_BUILDABLE,
            "a labelled value of this enumeration has an empty token and an empty text, so A12 reads the model and " +
                "then fails to build it — no consistency verdict about it exists"))
    }
    val findings = mutableListOf<Finding>()
    if (values.isNotEmpty() && tokenless.size == values.size) {
        findings += finding(field, "MVK_INTERNAL_VALUES_MISSING",
            "no entry of this enumeration's `values` carries a `value` token, so it declares no value A12 can store")
    }
    if (labelled.isNotEmpty() && labelled.size < values.size) {
        findings += finding(field, "MVK_COUNT_OF_VALUES_INVALID",
            "some of this enumeration's values carry a `label` and others do not, which A12 refuses; an empty " +
                "`label` list counts as carrying none")
    }
    val unanswered = declaredLocales.filter { locale ->
        labelled.indices.any { index ->
            val answered = texts[index][locale]?.string("text")?.isA12Blank() == false
            if (labelled[index].carriesToken()) !answered else answered
        }
    }
    findings += unanswered.map { locale ->
        finding(field, "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES",
            "a labelled value of this enumeration lacks either its token or a text for `$locale`; A12 gives a " +
                "labelled value a text for every declared locale and asks both to be filled, and a blank text is no text")
    }
    if (labelled.any { !it.carriesToken() }) {
        findings += finding(field, "MVK_INTERNAL_VALUE_MISSING",
            "a labelled value of this enumeration carries no `value` token, which A12 refuses")
    }
    return findings.ifEmpty { repeatedLabelFindings(field, texts) }
}

/** Whether a labelled value carries a token: an empty one is none ([labelFindings]). */
private fun JsonObject.carriesToken(): Boolean = "value" in this && string("value") != ""

/**
 * The texts A12 asks to be distinct, and the scope of that law is ONE LANGUAGE.
 *
 * A12 writes one per-language block per declared locale and asks each block's `displayValue` list to be
 * unique ([KERNEL-FINDINGS KF368], `rulekit.validate.laws.EnumValueAndLabelLawsTest`), so the same text used
 * for one value in English and another in German is ADMITTED — the row a family keyed on the texts alone
 * would refuse. A locale the model does not declare has no block, so a text repeated only there is never
 * compared ([KERNEL-FINDINGS KF569]); [texts] arrive already narrowed to the declared locales.
 *
 * **It is asked only where the label set is otherwise complete**, which is where it was measured: a set that
 * is short or half-blank draws its own class first, and whether A12 adds this one there is unmeasured.
 * Under-reporting the pair is safe; naming a class on an unmeasured shape is not. A repeated label carrying a
 * supplementary-plane character is not named: A12 answers the unsupported-signs sentence alone (KF551). Labels
 * are compared trimmed, so `Tier` and `Tier ` repeat (KF418).
 */
private fun repeatedLabelFindings(
    field: TrackedDeclaration,
    texts: List<Map<String, JsonObject>>,
): List<Finding> {
    val perLocale = mutableMapOf<String, MutableList<String>>()
    for (value in texts) {
        for ((locale, entry) in value) {
            entry.string("text")?.javaTrimmed()?.takeIf { it.namedByA12() }
                ?.let { perLocale.getOrPut(locale) { mutableListOf() }.add(it) }
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
