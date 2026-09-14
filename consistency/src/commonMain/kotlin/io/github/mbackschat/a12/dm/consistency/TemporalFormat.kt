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
 * `CustomFieldType`, `TypeDefType`, `UnspecifiedType` and `DateRangeType` — types whose effective base type this
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

/**
 * The `interpretationOfYear` a `DateRangeType` declares, or null where it declares none.
 *
 * An absent key is A12's `STANDARD`, which is why null and `"STANDARD"` mean the same thing to every caller:
 * the declaration expresses the default by omitting the property.
 */
internal fun declaredRangeYearInterpretationOf(field: DeclaredElement): String? =
    field.declaredFormat(mapOf("DateRangeType" to "interpretationOfYear"))

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

/**
 * Whether [constant] is one of the four shapes `DateUtils.isStringAValidDate` accepts.
 *
 * The LENGTH picks the pattern and the parse behind it is non-lenient, so both halves are reproduced: the
 * shape must match and the value must exist in the calendar. A length the switch does not name is refused
 * without any pattern being tried, which is why `01.02` — the year-less shape minus its trailing dot — is
 * not read as a shorter date.
 */
internal fun isAcceptedDateConstant(constant: String): Boolean = when (constant.length) {
    6 -> constant.matchesShape("dd.mm.") && constant.namesARealDay(day = 0, month = 3, year = null)
    8 -> constant.matchesShape("hh:mm:ss") && constant.namesARealTime()
    10 -> constant.matchesShape("dd.mm.yyyy") && constant.namesARealDay(day = 0, month = 3, year = 6)
    19 -> constant.matchesShape("dd.mm.yyyyThh:mm:ss") &&
        constant.namesARealDay(day = 0, month = 3, year = 6) && constant.substring(11).namesARealTime()
    else -> false
}

/**
 * Whether the string matches [shape], where a letter stands for the digit class the kernel's pattern uses
 * at that position and every other character must appear verbatim.
 *
 * The kernel writes four `Pattern` constants; this is the same four written once, because they differ only
 * in which positions are bounded — the hour's first digit is `[0-2]`, the minute's `[0-5]`, the second's
 * `[0-6]` (leap seconds, per the kernel's own comment), the year's `[1-2]`, and the rest are `[0-9]`.
 */
private fun String.matchesShape(shape: String): Boolean {
    if (length != shape.length) {
        return false
    }
    return shape.withIndex().all { (at, expected) ->
        val actual = this[at]
        when (expected) {
            'd', 'm', 's' -> actual.isDigit()
            'y' -> if (at == shape.indexOf('y')) actual in '1'..'2' else actual.isDigit()
            'h' -> if (at == shape.indexOf('h')) actual in '0'..'2' else actual.isDigit()
            else -> actual == expected
        }
    }
}

/** Whether the day and month at those offsets name a day the calendar has, the year deciding February. */
private fun String.namesARealDay(day: Int, month: Int, year: Int?): Boolean {
    val d = substring(day, day + 2).toIntOrNull() ?: return false
    val m = substring(month, month + 2).toIntOrNull() ?: return false
    if (m !in 1..12 || d < 1) {
        return false
    }
    val y = year?.let { substring(it, it + 4).toIntOrNull() ?: return false } ?: YEARLESS_DEFAULT_YEAR
    return d <= daysIn(m, y)
}

/** Whether the `HH:mm:ss` at the start of this string names a time, on the kernel's own bounds. */
private fun String.namesARealTime(): Boolean {
    val h = substring(0, 2).toIntOrNull() ?: return false
    val min = substring(3, 5).toIntOrNull() ?: return false
    val s = substring(6, 8).toIntOrNull() ?: return false
    return h <= 23 && min <= 59 && s <= 59
}

/**
 * The year a year-less date constant is judged against.
 *
 * `SimpleDateFormat` with the pattern `dd.MM.` leaves the year at its epoch default of 1970, and the parse
 * is non-lenient — so the year-less spelling is NOT year-agnostic and `29.02.` is refused, measured. Reading
 * it as year-agnostic would admit a constant A12 rejects, which is under-reporting rather than the forbidden
 * direction, but it would also have been wrong.
 */
private const val YEARLESS_DEFAULT_YEAR = 1970

private fun daysIn(month: Int, year: Int): Int = when (month) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
}
