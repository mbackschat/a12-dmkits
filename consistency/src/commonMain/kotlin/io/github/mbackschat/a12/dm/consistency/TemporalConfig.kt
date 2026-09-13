package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A date field's declared CONFIG against its declared FORMAT — A12's two date-config gates.
 *
 * Both are consistency verdicts rather than reader refusals: A12 reads and builds the model and then reports
 * the conflict, so this family reproduces codes A12 itself gives ([KERNEL-FINDINGS KF314]).
 *
 * | the declaration says | and the format is | A12 says |
 * |---|---|---|
 * | `youngerThan1900Check` is true | year-free | `MVK_ADDITIONAL_CHECK_INVALID` |
 * | `datePrecision` is a PARTIAL one | not a complete pure date | `MVK_OPTIONAL_DATE_TYPE_INVALID` |
 *
 * **The carrier denominator is DERIVED and it is three, not "the date family".** `youngerThan1900Check` is
 * declared by exactly `DateType`, `DateFragmentType` and `DateRangeType` (source-read of kernel `30.8.1`'s
 * `fieldtypes/` package, 2026-09-13), and the third is measured EXEMPT — every admitted range pair takes the
 * check, the year-free ones included. So a family that read the member wherever it found it would refuse a
 * model A12 admits, which is the one direction this checker may not take. `datePrecision` is declared by
 * `DateType` alone.
 *
 * **Both gates fire only over the format vocabulary A12 admits, and that restriction is the safety.** The
 * admitted set is derived from the kernel and published in [TEMPORAL-FORMAT-VOCABULARY], but it is explicitly
 * NOT claimed closed — a format outside the probed universe is undiscovered rather than refused, which
 * [KERNEL-FINDINGS KF313] demonstrated by finding a thirteenth. So an unrecognised format leaves this family
 * silent: reporting one would rest the refusal on the limit of a probe.
 *
 * **An absent `format` is the declared DEFAULT rather than nothing**, measured on both gates: the builder
 * fills `yyyy-MM-dd`, which is year-bearing and complete, so both rows are ADMITTED — which is why
 * [declaredFormat] can treat the absent member as nothing to judge and still give A12's answer.
 */
fun PreparedModel.temporalConfigFindings(): List<Finding> =
    elements.filter { it.declaredKind == DeclaredKind.FIELD }.flatMap { field ->
        val declared = field.body?.get("fieldType") as? JsonObject ?: return@flatMap emptyList()
        val type = (declared["type"] as? JsonPrimitive)?.content ?: return@flatMap emptyList()
        val body = declared[type] as? JsonObject ?: return@flatMap emptyList()
        when (type) {
            "DateType" -> listOfNotNull(
                pre1900Finding(field, body, body.declaredFormat("format")),
                partialPrecisionFinding(field, body, body.declaredFormat("format")),
            )
            // The fragment carrier spells its format under its own member and takes only the first gate,
            // having no precision of its own.
            "DateFragmentType" -> listOfNotNull(
                pre1900Finding(field, body, body.declaredFormat("formatOfFragment")),
            )
            else -> emptyList()
        }
    }

/**
 * The format this body declares and this family may judge — null when there is nothing to judge.
 *
 * Three shapes give null and they are deliberately one answer: the member is absent, it holds something other
 * than a string, or it holds a format the derived vocabulary does not carry.
 *
 * **The absent case is silence rather than ignorance.** A body with no format gets the builder's
 * `yyyy-MM-dd`, which is year-bearing AND a complete pure date, so A12 admits it on both gates (measured:
 * [KERNEL-FINDINGS KF314]). Reading the absent member AS that default was written first and produced the
 * identical verdict on every input — a verdict-equivalent branch, removed rather than kept. A gate that
 * refused the default would have to read it, and that is the trigger for putting it back.
 */
private fun JsonObject.declaredFormat(member: String): String? {
    val text = (this[member] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return text.takeIf { it in ADMITTED_FORMATS }
}

private fun pre1900Finding(field: DeclaredElement, body: JsonObject, format: String?): Finding? {
    // The gate is on the VALUE and not on the member's presence — `false` is admitted on a year-free format,
    // so a checker keyed on presence would report a declaration A12 takes.
    if ((body["youngerThan1900Check"] as? JsonPrimitive)?.booleanOrNull != true) {
        return null
    }
    if (format == null || format !in YEAR_FREE_FORMATS) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_ADDITIONAL_CHECK_INVALID",
        "the pre-1900 check is declared on the format `$format`, which carries no YEAR — A12 gates the check " +
            "on the format's year component, so either declare a year-bearing format or drop the check; it " +
            "is not admitted-and-inert",
        field.path,
    )
}

private fun partialPrecisionFinding(field: DeclaredElement, body: JsonObject, format: String?): Finding? {
    val precision = (body["datePrecision"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    if (precision !in PARTIAL_PRECISIONS) {
        return null
    }
    if (format == null || format in COMPLETE_DATE_FORMATS) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_OPTIONAL_DATE_TYPE_INVALID",
        "the precision `$precision` is declared on the format `$format`, and A12 requires a COMPLETE pure " +
            "date there — ${COMPLETE_DATE_FORMATS.joinToString(", ") { "`$it`" }} and nothing else, the " +
            "instant format included. The precision lets the stored VALUE omit parts, so the format must " +
            "still be able to spell all of them",
        field.path,
    )
}

/**
 * The thirteen formats the kernel admits, split by whether they carry a year.
 *
 * Both halves are the measured outcome of one grid over the derived vocabulary
 * (`rulekit.validate.laws.TemporalDeclarationConfigAdmissionLawsTest`, whose own doc says a client-side
 * refusal may key on nothing wider), not a component predicate read off the strings — deriving them from a
 * predicate makes the checker agree with its own reading instead of with A12.
 */
// VOCABULARY-GRID-START — `rulekit.validate.laws.TemporalFormatVocabularyGuardTest` reads only this
// delimited region and requires every admitted format to appear in it, so a member the kernel gains cannot
// silently go missing from the partition. A format named elsewhere in this file does not count as carried.
private val YEAR_BEARING_FORMATS = setOf(
    "dd.MM.yyyy", "yyyy", "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd",
)

private val YEAR_FREE_FORMATS = setOf("HH:mm:ss", "MM", "MM-dd", "MMdd", "ddMM", "dd.MM.")
// VOCABULARY-GRID-END

private val ADMITTED_FORMATS = YEAR_BEARING_FORMATS + YEAR_FREE_FORMATS

/**
 * The formats a PARTIAL precision admits — a strict subset of the year-bearing set, and not "carries all
 * three components": `yyyy-MM-dd'T'HH:mm:ss` carries them and is refused.
 */
private val COMPLETE_DATE_FORMATS = setOf("dd.MM.yyyy", "yyyy-MM-dd", "yyyyMMdd")

/** `DatePrecision` minus its `FULL` default, which is the one value that carries no format requirement. */
private val PARTIAL_PRECISIONS = setOf("DAY_OPTIONAL", "MONTH_OPTIONAL", "YEAR_OPTIONAL")
