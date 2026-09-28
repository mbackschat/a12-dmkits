package io.github.mbackschat.a12.dm.consistency

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Whether A12's value validation of this index field admits the non-empty [constant], or null where this reader
 * cannot say.
 *
 * A12 runs the index field type's `isValueApplicable` over a constant key (`CheckSemanticIndexImpl`). A
 * Boolean's custom values do not count here: a Boolean declaring `J`/`N` still admits exactly `true` and
 * `false` (measured), consistent with `TypeConverter` reading every `notInDCustom*` member only under its one
 * `considerNotInD` flag (source-read). A custom date format rides the same flag and is unmeasured. Every kind's
 * test is a SHAPE test, measured row by row against the Kernel in
 * `adapter.laws.CheckerIndexKeyConstantAgreementTest` ([KERNEL-FINDINGS KF481]).
 *
 * Unjudged, and therefore safe to under-report: a constant meeting its length bounds beside a declared String
 * pattern outside [KeyPattern]'s represented Java-to-JS domain; a table-backed enumeration, whose values a model does
 * not carry; a non-String or ambiguous type definition, an unspecified field, and a constant
 * holding a CR on another type. String keys normalize CRLF to LF before both length and pattern checks
 * (DG102-E2 kernel population); a lone CR is preserved.
 */
internal fun TrackedFactBase.admitsIndexConstant(field: TrackedDeclaration, constant: String): Boolean? {
    val fieldType = field.declaredFieldType() ?: return null
    val declared = if (fieldType == FieldType.TYPE_DEF) {
        val id = field.boundTypeDefinitionId() ?: return null
        typeDefinitionBody(id)?.get("fieldType") as? JsonObject ?: return null
    } else {
        field.body?.get("fieldType") as? JsonObject
    }
    val type = (if (fieldType == FieldType.TYPE_DEF) declared?.namedFieldType() else fieldType) ?: return null
    if (fieldType == FieldType.TYPE_DEF && type != FieldType.STRING) return null
    val typeBody = type.bodyIn(declared)
    if (type == FieldType.STRING && typeBody.constrainsNoString()) {
        return true
    }
    if ('\r' in constant && type != FieldType.STRING) {
        return null
    }
    return when (type) {
        FieldType.STRING -> typeBody?.stringAdmits(constant.replace("\r\n", "\n"))
        FieldType.NUMBER -> numberShapeAdmits(
            minFraction = maxOf(typeBody?.declaredInt("minFractionalDigits") ?: 0, 0),
            maxFraction = typeBody?.declaredInt("maxFractionalDigits") ?: 0,
            constant = constant,
        )
        FieldType.BOOLEAN -> constant == TRUE || constant == FALSE
        FieldType.CONFIRM -> constant == TRUE
        FieldType.ENUMERATION -> field.enumerationAdmits(constant)
        FieldType.DATE, FieldType.DATE_TIME, FieldType.TIME, FieldType.DATE_FRAGMENT ->
            declaredTemporalFormatOf(field)?.let { temporalShapeAdmits(it, constant) }
        FieldType.DATE_RANGE -> {
            val format = declaredDateRangeFormatOf(field)
            val separator = typeBody?.declaredText("rangeSeparator")
            if (format == null || separator == null) null else dateRangeShapeAdmits(format, separator, constant)
        }
        FieldType.CUSTOM_FIELD -> typeBody?.stringAdmits(constant)
        FieldType.TYPE_DEF, FieldType.UNSPECIFIED -> null
    }
}

/** The stored values A12 builds a Boolean and a Confirm from on this path. */
private const val TRUE = "true"
private const val FALSE = "false"

/** A String body declaring no length and no pattern, or none at all, admits every non-empty constant. */
private fun JsonObject?.constrainsNoString(): Boolean {
    val string = this ?: return true
    return LENGTH_MEMBERS.none { (string.declaredInt(it) ?: 0) > 0 } && string.declaredText("pattern").isNullOrEmpty()
}

private val LENGTH_MEMBERS = listOf("minLength", "maxLength")

/**
 * A length bound is asked only where it is positive, then the pattern, as `IStringType` asks them. A key must
 * meet both, so a pattern outside [KeyPattern]'s represented domain leaves unjudged only a constant whose length is met. The length counts UTF-16 units, as `String.length()` does, while the pattern uses Java's whole-region matcher.
 */
private fun JsonObject.stringAdmits(constant: String): Boolean? {
    val max = declaredInt("maxLength")?.takeIf { it > 0 }
    val min = declaredInt("minLength")?.takeIf { it > 0 }
    if ((max != null && constant.length > max) || (min != null && constant.length < min)) {
        return false
    }
    val declared = declaredText("pattern")?.takeIf { it.isNotEmpty() } ?: return true
    return KeyPattern.parse(declared)?.matches(constant)
}

/**
 * An enumeration without a table admits exactly the tokens its values declare, in their case and never a label
 * ([KERNEL-FINDINGS KF479], `IEnumerationType.isValueApplicable`).
 */
private fun TrackedDeclaration.enumerationAdmits(constant: String): Boolean? {
    val enumeration = enumerationBody()?.takeIf { it["tableName"] == null } ?: return null
    return enumeration.declaredEnumValues()?.takeIf { it.isNotEmpty() }?.let { constant in it }
}

/**
 * Whether [constant] has the shape a Number with these fractional counts admits: an optional minus, one or more
 * ASCII digits, and a fraction after `.` or `,` that is absent when [maxFraction] is not positive, optional when
 * [minFraction] is zero, and otherwise between the two counts long. Null where the counts are inverted, which
 * leaves A12 no pattern to build and the model refused on its own digit gate.
 *
 * Nothing else the field declares is asked: `12345` is admitted on a field allowing two integer digits, and
 * `05` on one that forbids a leading zero.
 */
internal fun numberShapeAdmits(minFraction: Int, maxFraction: Int, constant: String): Boolean? {
    if (maxFraction > 0 && minFraction > maxFraction) {
        return null
    }
    val unsigned = constant.removePrefix("-")
    val whole = unsigned.takeWhile { it in '0'..'9' }
    if (whole.isEmpty()) {
        return false
    }
    val rest = unsigned.substring(whole.length)
    if (maxFraction <= 0 || rest.isEmpty()) {
        return rest.isEmpty() && (maxFraction <= 0 || minFraction == 0)
    }
    val fraction = rest.substring(1)
    return rest[0] in DECIMAL_SEPARATORS && fraction.all { it in '0'..'9' } &&
        fraction.length in maxOf(minFraction, 1)..maxFraction
}

private val DECIMAL_SEPARATORS = setOf('.', ',')

/**
 * Whether [constant] has the shape of the temporal [format]: each of the six component letters stands for one
 * ASCII digit, a quoted run stands for its own text, and every other character for itself. Null where the
 * format carries a character A12's pattern would read as regex syntax, because then the shape is not this one.
 *
 * It is a shape and nothing more — `2020-13-45` is admitted on `yyyy-MM-dd` — since A12 matches the format with
 * its letters replaced rather than parsing a date.
 */
internal fun temporalShapeAdmits(format: String, constant: String): Boolean? =
    slotsOf(format, quotesAreLiteral = false)?.admits(constant)

/**
 * Whether [constant] is two dates of [format] around [separator], neither of them unquoting a literal, since
 * A12 builds a range's pattern without that step. Null where the format or the separator would be regex syntax.
 */
internal fun dateRangeShapeAdmits(format: String, separator: String, constant: String): Boolean? {
    val date = slotsOf(format, quotesAreLiteral = true) ?: return null
    if (separator.any { it in REGEX_SYNTAX }) {
        return null
    }
    return (date + separator.map { ShapeSlot.Literal(it) } + date).admits(constant)
}

/** One position of a temporal shape. */
private sealed interface ShapeSlot {
    data object Digit : ShapeSlot
    data class Literal(val char: Char) : ShapeSlot
}

private fun List<ShapeSlot>.admits(constant: String): Boolean =
    constant.length == size && zip(constant.toList()).all { (slot, char) ->
        when (slot) {
            ShapeSlot.Digit -> char in '0'..'9'
            is ShapeSlot.Literal -> char == slot.char
        }
    }

/**
 * The positions [format] asks for, or null where it holds regex syntax. A12 replaces the component letters
 * everywhere, a quoted run included, then escapes the dot, and for a single date then drops each quote pair.
 */
private fun slotsOf(format: String, quotesAreLiteral: Boolean): List<ShapeSlot>? {
    val slots = mutableListOf<ShapeSlot>()
    var index = 0
    while (index < format.length) {
        val char = format[index]
        val closing = if (char == '\'' && !quotesAreLiteral) format.indexOf('\'', index + 1) else -1
        if (closing > index) {
            format.substring(index + 1, closing).forEach { slots += slotOf(it) ?: return null }
            index = closing + 1
        } else {
            slots += slotOf(char) ?: return null
            index++
        }
    }
    return slots
}

private fun slotOf(char: Char): ShapeSlot? = when {
    char in COMPONENT_CHARS -> ShapeSlot.Digit
    char == '.' -> ShapeSlot.Literal(char)
    char in REGEX_SYNTAX -> null
    else -> ShapeSlot.Literal(char)
}

private const val COMPONENT_CHARS = "dMyHms"

/** The characters a Java pattern reads as syntax; the dot is absent because A12 escapes it. */
private const val REGEX_SYNTAX = "\\^$|?*+()[]{}"

private fun JsonObject.declaredInt(member: String): Int? = (this[member] as? JsonPrimitive)?.intOrNull

private fun JsonObject.declaredText(member: String): String? =
    (this[member] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
