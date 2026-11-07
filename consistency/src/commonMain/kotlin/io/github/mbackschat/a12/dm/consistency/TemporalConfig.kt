package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A date field's declared CONFIG against its declared FORMAT — A12's date-config gate.
 *
 * It is a consistency verdict rather than a reader refusal: A12 reads and builds the model and then reports the
 * conflict, so this family reproduces a code A12 itself gives ([KERNEL-FINDINGS KF314]).
 *
 * | the declaration says | and the format is | A12 says |
 * |---|---|---|
 * | `datePrecision` is a PARTIAL one | not a complete pure date | `MVK_OPTIONAL_DATE_TYPE_INVALID` |
 *
 * `datePrecision` is declared by `DateType` alone. The second gate this family carried through kernel 31.1.1,
 * `youngerThan1900Check` on a year-free format, went with the attribute's evaluation in 31.2 ([KERNEL-FINDINGS
 * KF677]): A12 now admits the check on every format and every carrier, so reading the member at all would refuse
 * models A12 takes.
 *
 * **The gate fires only over the format vocabulary A12 admits, and that restriction is the safety.** The
 * admitted set is derived from the kernel and published in [TEMPORAL-FORMAT-VOCABULARY], but it is explicitly
 * NOT claimed closed — a format outside the probed universe is undiscovered rather than refused, which
 * [KERNEL-FINDINGS KF313] demonstrated by finding a thirteenth. So an unrecognised format leaves this family
 * silent: reporting one would rest the refusal on the limit of a probe.
 *
 * **An absent `format` is the declared DEFAULT rather than nothing**, as measured: the builder fills
 * `yyyy-MM-dd`, which is complete, so the row is ADMITTED — which is why
 * [declaredFormat] can treat the absent member as nothing to judge and still give A12's answer.
 *
 * The format itself is gated first, and only over the derived Refused table ([refusedFormatFinding]).
 */
internal fun TrackedFactBase.temporalConfigFindings(field: TrackedDeclaration): List<Finding> {
    if (field.declaredKind != DeclaredKind.FIELD) {
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
            "with no class — declare one of the formats it admits, such as `yyyy-MM-dd` or `HH:mm:ss`",
        field.path,
    )
}

private fun configFindings(field: TrackedDeclaration): List<Finding> {
    val declared = field.body?.get("fieldType") as? JsonObject ?: return emptyList()
    val type = declared.namedFieldType() ?: return emptyList()
    val body = type.bodyIn(declared) ?: return emptyList()
    return when (type) {
        FieldType.DATE -> listOfNotNull(partialPrecisionFinding(field, body, body.declaredFormat("format")))
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
 * `yyyy-MM-dd`, which is a complete pure date, so A12 admits it (measured:
 * [KERNEL-FINDINGS KF314]). Reading the absent member AS that default was written first and produced the
 * identical verdict on every input — a verdict-equivalent branch, removed rather than kept. A gate that
 * refused the default would have to read it, and that is the trigger for putting it back.
 */
private fun JsonObject.declaredFormat(member: String): String? {
    val text = (this[member] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return text.takeIf { it in ADMITTED_FORMATS }
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

/** The thirteen formats the kernel admits, the denominator this family judges over. */
// VOCABULARY-GRID-START — `rulekit.validate.laws.TemporalFormatVocabularyGuardTest` reads only this
// delimited region and requires every admitted format to appear in it, so a member the kernel gains cannot
// silently go missing from the set. A format named elsewhere in this file does not count as carried.
internal val ADMITTED_FORMATS = setOf(
    "HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.", "dd.MM.yyyy", "ddMM",
    "yyyy", "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd",
)
// VOCABULARY-GRID-END

internal const val RK_DATE_FORMAT_INVALID = "RK_DATE_FORMAT_INVALID"

/**
 * The formats a PARTIAL precision admits, measured by `rulekit.validate.laws.TemporalDeclarationConfigAdmissionLawsTest`
 * — not "carries all three components": `yyyy-MM-dd'T'HH:mm:ss` carries them and is refused.
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
