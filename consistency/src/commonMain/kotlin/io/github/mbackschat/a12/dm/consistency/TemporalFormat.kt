package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The six datetime components A12's `DateFormat` sorts a format string into. */
internal enum class Component { YEAR, MONTH, DAY, HOUR, MINUTE, SECOND }

/** A12's `DateFormat` reads its components by plain containment of these six letters, and so does this. */
private val COMPONENT_LETTERS = listOf(
    'y' to Component.YEAR,
    'M' to Component.MONTH,
    'd' to Component.DAY,
    'H' to Component.HOUR,
    'm' to Component.MINUTE,
    's' to Component.SECOND,
)

internal fun componentsOf(format: String): Set<Component> =
    COMPONENT_LETTERS.filter { format.contains(it.first) }.mapTo(mutableSetOf()) { it.second }

/** A12's `isFormatWithDateSpecification` — any of the three calendar components. */
internal fun namesADate(components: Set<Component>): Boolean =
    Component.YEAR in components || Component.MONTH in components || Component.DAY in components

/** A12's `isFormatWithTimeSpecification` — any of the three clock components. */
internal fun namesATime(components: Set<Component>): Boolean =
    Component.HOUR in components || Component.MINUTE in components || Component.SECOND in components

/** A model-level base year supplements every format's year, which is what `hasBaseYear` means to A12. */
internal fun supplemented(components: Set<Component>, hasBaseYear: Boolean): Set<Component> =
    if (hasBaseYear) components + Component.YEAR else components

/**
 * The four carriers whose declared format A12 reads as a DATETIME operand type.
 *
 * `DateRangeType` is deliberately absent: it is its own operand base type, with its own comparison arm and
 * its own refusal class, so a family that folded it in here would name the date arm's class on it.
 */
private val TEMPORAL_FORMAT_MEMBERS = mapOf(
    "DateType" to "format",
    "TimeType" to "format",
    "DateTimeType" to "format",
    "DateFragmentType" to "formatOfFragment",
)

/**
 * The declared types A12 reads as the DATETIME operand base type — `IDateType` and the three kinds that
 * carry it — for a family that needs the membership rather than the format.
 */
internal val TEMPORAL_DECLARED_TYPES: Set<String> = TEMPORAL_FORMAT_MEMBERS.keys

/**
 * The format this field declares as a temporal one, or null when it declares none.
 *
 * Null leaves a reader unjudged rather than defaulting, because it means the declaration is not one A12
 * typed as temporal at all — a format A12 does not recognise is a separate answer, handled where a caller
 * needs it.
 */
internal fun declaredTemporalFormatOf(field: DeclaredElement): String? =
    field.declaredFormat(TEMPORAL_FORMAT_MEMBERS)

/**
 * The declared field types this reader is CERTAIN A12 gives a non-DATETIME base type.
 *
 * An allow-list rather than the complement of the temporal one, because the complement would include
 * `CustomType`, `TypeDefType`, `UnspecifiedType` and `DateRangeType` — types whose effective base type this
 * reader does not resolve — and refusing on those would be an over-rejection on a model A12 may admit.
 */
internal val PLAINLY_NON_TEMPORAL_TYPES = setOf(
    "StringType",
    "NumberType",
    "BooleanType",
    "ConfirmType",
    "EnumerationType",
)

/** The format a `DateRangeType` declares, or null when this field is not one. */
internal fun declaredDateRangeFormatOf(field: DeclaredElement): String? =
    field.declaredFormat(mapOf("DateRangeType" to "format"))

private fun DeclaredElement.declaredFormat(members: Map<String, String>): String? {
    val declared = body?.get("fieldType") as? JsonObject ?: return null
    val type = (declared["type"] as? JsonPrimitive)?.contentOrNull ?: return null
    val member = members[type] ?: return null
    val carrier = declared[type] as? JsonObject ?: return null
    return (carrier[member] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * Whether this field declares a PARTIALLY KNOWN date — A12's `FieldUtils.isDatePartialKnown`.
 *
 * Only a `DateType` carries the member, so every other declaration answers false rather than unknown: the
 * precision is a `DateType` body key and its absence there is A12's own default.
 */
internal fun declaresAPartialPrecision(field: DeclaredElement): Boolean {
    val declared = field.body?.get("fieldType") as? JsonObject ?: return false
    val body = declared["DateType"] as? JsonObject ?: return false
    return (body["datePrecision"] as? JsonPrimitive)?.contentOrNull in PARTIAL_PRECISIONS
}
