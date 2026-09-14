package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallOperand
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.callsIn
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * What every date operator's DATE-ARGUMENT slot demands, whichever operator is holding it.
 *
 * `CheckDatumAngabeImpl.check` is the shared checker for the grammar's `datumAngabe`, which the difference,
 * shift and extraction operators all hand their operands to. It branches on which of the slot's forms was
 * written, and each form asks a different question ([KERNEL-FINDINGS KF400],
 * `rulekit.validate.laws.DateArgumentSlotLawsTest`):
 *
 * | the form | the gate | class |
 * |---|---|---|
 * | a field spec | it names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | | it carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | | its declaration is not a date | `MVK_NO_DATE` |
 * | a string literal | it is none of the four accepted shapes | `MVK_INVALID_DATE_STRING` |
 * | a nested extraction | the extraction does not itself yield a date | `MVK_INVALID_DATE_FOR_OP` |
 *
 * **The slot's kind gate takes ALL FOUR temporal declarations** — `FieldUtils.isDate` tests `IDateType`,
 * which covers time, stamp and fragment as well as date — so a TIME field passes HERE and is refused one
 * layer up by the carrier's own format gate ([dateArithmeticOperandFindings], `MVK_WRONG_DATE_FORMAT_FOR_OP`).
 * The DateRange is the only temporal declaration the slot itself refuses. Four measured rows separate the
 * two layers, and a family that folded them would name this class on a model A12 refuses with the other.
 *
 * **The slot accepts FOUR constant shapes, not the two its kernel javadoc names.**
 * `DateUtils.isStringAValidDate` switches on the string's LENGTH and then matches one pattern:
 *
 * | length | shape | example |
 * |---|---|---|
 * | 6 | `dd.MM.` — with a TRAILING DOT | `01.02.` |
 * | 8 | `HH:mm:ss` | `10:30:00` |
 * | 10 | `dd.MM.yyyy` | `01.02.2024` |
 * | 19 | `dd.MM.yyyy'T'HH:mm:ss` | `01.02.2024T10:30:00` |
 *
 * Because the LENGTH picks the pattern, a 19-character stamp spelled with a space instead of the `T` reaches
 * the date-time pattern and fails it, and the year-less shape without its trailing dot is six characters
 * short of nothing and is simply refused. The parse behind the pattern is non-lenient, so `32.01.2024` and
 * `29.02.2023` match their pattern and are still refused — this reader reproduces both halves.
 *
 * **The year-less constant is ACCEPTED by the slot and the MIXTURE refused one layer up**, which is the
 * layering again: `"01.02."` against a dated field is `MVK_DATE_WITH_AND_WITHOUT_YEAR` and against another
 * year-less constant is admitted. That class is not this family's, so a year-less constant is never reported
 * here.
 *
 * **Silent on a CATEGORY access**, which A12 refuses first with `MVK_CATEGORY_NOT_ALLOWED` — the shared
 * operand reducer declines a categorized operand, so no call of that shape reaches here, and nothing else
 * reads this carrier either: a disclosed under-report. Silent too on a `TypeDefType` or `UnspecifiedType`
 * declaration, whose effective type this reader does not resolve; on a nested operation that is not one of
 * the extractions typed here; and on a slot whose repetition stars do not match its repeating levels, where
 * a wildcard-validity class answers ahead of everything in this family.
 */
fun PreparedModel.dateArgumentSlotFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = DATE_SLOTS_OF.mapNotNull { (id, slots) -> keywords[id]?.let { it to slots } }.toMap()
    val numericExtractors = NUMERIC_EXTRACTIONS.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        conditionTexts(element.kind, body).flatMap { (member, text) ->
            callsIn(text, conditionLanguage, syntaxEntryOf(member)).flatMap { call ->
                val slots = carriers[call.operator] ?: return@flatMap emptyList()
                slots.mapNotNull { slot ->
                    val operand = call.operands.getOrNull(slot) ?: return@mapNotNull null
                    slotDefect(operand, numericExtractors, group, element)
                        ?.let { finding(it, call, element, member) }
                }
            }
        }
    }
}

/** The extractions that yield a NUMBER, so one standing in a date slot is the slot's own defect. */
private val NUMERIC_EXTRACTIONS = listOf(
    "YearFromDate", "MonthFromDate", "DayFromDate", "QuarterFromDate",
    "HoursFromTime", "MinutesFromTime", "SecondsFromTime",
)

/** The extractions that yield a date, which a date slot therefore takes. */
private val TEMPORAL_EXTRACTIONS = listOf("DateFromDateTime", "TimeFromDateTime")

/** Every operator holding a date-argument slot, with the operand positions that are one. */
private val DATE_SLOTS_OF: Map<String, List<Int>> = buildMap {
    listOf("Days", "Months", "Years", "Hours", "Minutes", "Seconds").forEach { unit ->
        put("DifferenceIn$unit", listOf(0, 1))
        put("Add$unit", listOf(0))
    }
    (NUMERIC_EXTRACTIONS + TEMPORAL_EXTRACTIONS).forEach { put(it, listOf(0)) }
}

/**
 * The declarations this reader is CERTAIN the slot's kind gate refuses, every member measured.
 *
 * An allow-list rather than the complement of the four temporal kinds, so a `TypeDefType` or
 * `UnspecifiedType` — whose effective type this checker does not resolve — is left unjudged rather than
 * refused, which would be the one direction this checker may not take.
 */
private val PLAINLY_NON_DATE_TYPES = setOf(
    "StringType", "NumberType", "BooleanType", "ConfirmType", "EnumerationType",
    "CustomFieldType", "DateRangeType",
)

private class DateArgumentDefect(val code: String, val said: String)

/** The gate this slot fails, or null where it fails none and where this reader may not judge. */
private fun PreparedModel.slotDefect(
    operand: CallOperand,
    numericExtractors: Set<String>,
    declaringGroup: String,
    element: DeclaredElement,
): DateArgumentDefect? {
    val leadingName = tokenize(operand.text).firstOrNull()?.takeIf { it.kind == TokenKind.NAME }?.text
    if (leadingName != null && operand.path == null) {
        // A nested OPERATION rather than a reference. Only the extractions are typed here; every other
        // operation is left alone, because typing it would be a claim with nothing behind it.
        return if (leadingName in numericExtractors) {
            DateArgumentDefect(
                "MVK_INVALID_DATE_FOR_OP",
                "is handed `$leadingName(…)`, which yields a NUMBER — a date slot takes a date-valued " +
                    "extraction (`DateFromDateTime`, `TimeFromDateTime`), a date field, or a date constant",
            )
        } else {
            null
        }
    }
    operand.literal?.let { constant ->
        return if (isAcceptedDateConstant(constant)) {
            null
        } else {
            DateArgumentDefect(
                "MVK_INVALID_DATE_STRING",
                "is handed the constant `\"$constant\"`, which is none of the four shapes a date slot " +
                    "spells — `dd.MM.` (the trailing dot is part of it), `HH:mm:ss`, `dd.MM.yyyy`, or " +
                    "`dd.MM.yyyy'T'HH:mm:ss`",
            )
        }
    }
    val written = operand.path ?: return null
    val target = declaredTargetOf(written, declaringGroup, element) ?: return null
    if (written.count { it == '*' } != repeatingLevelsOnThePathTo(target.path)) {
        return null
    }
    if (target.declaredKind == DeclaredKind.GROUP) {
        return DateArgumentDefect(
            "MVK_NO_GROUPS_ALLOWED",
            "is handed the group `${target.path}` — a date slot takes one field, so name a date inside it",
        )
    }
    if (target.declaredKind != DeclaredKind.FIELD) {
        return null
    }
    if ('*' in written) {
        return DateArgumentDefect(
            "MVK_NO_WILDCARDS_ALLOWED",
            "reads `${target.path}` across a repetition — a date slot takes one value, so drop the star",
        )
    }
    return if (target.declaredFieldType() in PLAINLY_NON_DATE_TYPES) {
        DateArgumentDefect(
            "MVK_NO_DATE",
            "reads `${target.path}`, declared `${target.declaredFieldType()}` — a date slot takes a date, " +
                "time, date-time or date-fragment field",
        )
    } else {
        null
    }
}

/**
 * Whether [constant] is one of the four shapes `DateUtils.isStringAValidDate` accepts.
 *
 * The LENGTH picks the pattern and the parse behind it is non-lenient, so both halves are reproduced: the
 * shape must match and the value must exist in the calendar. A length the switch does not name is refused
 * without any pattern being tried, which is why `01.02` — the year-less shape minus its trailing dot — is
 * not read as a shorter date.
 */
private fun isAcceptedDateConstant(constant: String): Boolean = when (constant.length) {
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

/** The declaration one written operand names, whatever kind it is, or null where nothing resolves it. */
private fun PreparedModel.declaredTargetOf(
    operand: String,
    declaringGroup: String,
    element: DeclaredElement,
): DeclaredElement? {
    val reference = referencesIn(operand, conditionLanguage).singleOrNull() ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    return elements.firstOrNull { it.path == target }
}

private fun finding(
    defect: DateArgumentDefect,
    call: ConditionCall,
    element: DeclaredElement,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}`'s date argument ${defect.said}",
    element.path,
)
