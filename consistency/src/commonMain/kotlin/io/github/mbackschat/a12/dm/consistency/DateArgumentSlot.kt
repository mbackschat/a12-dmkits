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
internal fun TrackedFactBase.dateArgumentSlotFindings(): List<Finding> {
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val carriers = DATE_SLOTS_OF.mapNotNull { (id, slots) -> keywords[id]?.let { it to slots } }.toMap()
    val numericExtractors = NUMERIC_EXTRACTIONS.mapNotNull { keywords[it] }.toSet()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    return declarations().flatMap { element ->
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

private class DateArgumentDefect(val code: String, val said: String)

/** The gate this slot fails, or null where it fails none and where this reader may not judge. */
private fun TrackedFactBase.slotDefect(
    operand: CallOperand,
    numericExtractors: Set<String>,
    declaringGroup: String,
    element: TrackedDeclaration,
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

/** The declaration one written operand names, whatever kind it is, or null where nothing resolves it. */
private fun TrackedFactBase.declaredTargetOf(
    operand: String,
    declaringGroup: String,
    element: TrackedDeclaration,
): TrackedDeclaration? {
    val reference = referencesIn(operand, conditionLanguage).singleOrNull() ?: return null
    val resolved = resolveEntity(declaringGroup, reference.replace("*", ""), element.origin)
    val target = (resolved as? Reference.Resolved)?.path ?: return null
    return allDeclarations().firstOrNull { it.path == target }
}

private fun finding(
    defect: DateArgumentDefect,
    call: ConditionCall,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${call.operator}`'s date argument ${defect.said}",
    element.path,
)
