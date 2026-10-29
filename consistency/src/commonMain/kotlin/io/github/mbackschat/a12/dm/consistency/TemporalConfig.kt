package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 *
 * The format itself is gated first, and only over the derived Refused table ([refusedFormatFinding]).
 */
internal fun TrackedFactBase.temporalConfigFindings(field: TrackedDeclaration): List<Finding> {
    if (field.declaredKind != DeclaredKind.FIELD || field.body.deletesAtDefaultVariant()) {
        return emptyList()
    }
    return listOfNotNull(refusedFormatFinding(field)) + configFindings(field)
}

/**
 * A format A12 refuses with its codeless "The date format is invalid." ([KERNEL-FINDINGS KF648]).
 *
 * Read through the field's resolved type, because A12 judges a type definition's format on the field that uses it
 * and never on the definition alone. The set is the probed universe's Refused table, generated from the kernel
 * into [REFUSED_TEMPORAL_FORMATS], so a format outside it stays silent ([KERNEL-FINDINGS KF313]).
 */
private fun TrackedFactBase.refusedFormatFinding(field: TrackedDeclaration): Finding? {
    val format = temporalFormatOf(field)?.takeIf { it in REFUSED_TEMPORAL_FORMATS } ?: return null
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        RK_DATE_FORMAT_INVALID,
        "the format `$format` is one A12 refuses on every date, time, datetime and fragment field, in a sentence " +
            "with no class — declare one of the formats it admits, listed in TEMPORAL-FORMAT-VOCABULARY",
        field.path,
    )
}

private fun configFindings(field: TrackedDeclaration): List<Finding> {
    val declared = field.body?.get("fieldType") as? JsonObject ?: return emptyList()
    val type = declared.namedFieldType() ?: return emptyList()
    val body = type.bodyIn(declared) ?: return emptyList()
    return when (type) {
        FieldType.DATE -> listOfNotNull(
            pre1900Finding(field, body, body.declaredFormat("format")),
            partialPrecisionFinding(field, body, body.declaredFormat("format")),
        )
        // The fragment carrier spells its format under its own member and takes only the first gate,
        // having no precision of its own.
        FieldType.DATE_FRAGMENT -> listOfNotNull(
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

private fun pre1900Finding(field: TrackedDeclaration, body: JsonObject, format: String?): Finding? {
    // The gate is on the VALUE and not on the member's presence — `false` is admitted on a year-free format,
    // so a checker keyed on presence would report a declaration A12 takes. The value is bound as A12 binds it,
    // so `1` and `" TRUE"` set it too (`adapter.laws.CheckerDeclarationAdmissionAgreementTest`).
    if (body.flag("youngerThan1900Check") != true) {
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

private fun partialPrecisionFinding(field: TrackedDeclaration, body: JsonObject, format: String?): Finding? {
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

internal val ADMITTED_FORMATS = YEAR_BEARING_FORMATS + YEAR_FREE_FORMATS

internal const val RK_DATE_FORMAT_INVALID = "RK_DATE_FORMAT_INVALID"

/**
 * The formats a PARTIAL precision admits — a strict subset of the year-bearing set, and not "carries all
 * three components": `yyyy-MM-dd'T'HH:mm:ss` carries them and is refused.
 */
private val COMPLETE_DATE_FORMATS = setOf("dd.MM.yyyy", "yyyy-MM-dd", "yyyyMMdd")

/**
 * `DatePrecision` minus its `FULL` default, which is the one value that carries no format requirement, each with
 * the calendar components it leaves open. One table, so the set of partial precisions and what each leaves open
 * cannot name different precisions.
 */
internal val OPEN_UNDER_PRECISION: Map<String, Set<Component>> = mapOf(
    "DAY_OPTIONAL" to setOf(Component.DAY),
    "MONTH_OPTIONAL" to setOf(Component.MONTH, Component.DAY),
    "YEAR_OPTIONAL" to setOf(Component.YEAR, Component.MONTH, Component.DAY),
)

internal val PARTIAL_PRECISIONS: Set<String> = OPEN_UNDER_PRECISION.keys
