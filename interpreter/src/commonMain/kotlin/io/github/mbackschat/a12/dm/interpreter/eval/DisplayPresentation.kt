package io.github.mbackschat.a12.dm.interpreter.eval

import io.github.mbackschat.a12.dm.interpreter.DecimalDigits
import io.github.mbackschat.a12.dm.interpreter.model.Kind

/**
 * How a `$field.value$` message parameter is PRESENTED — the interpreter's clean-room twin of the kernel's
 * per-locale presentation information (KERNEL-FINDINGS [KF199]).
 *
 * A stored value is written in the field's DECLARED format; what a rule message renders is that value
 * re-presented for the MESSAGE LOCALE, and only for the nine locale tags the kernel maps. Any other declared
 * locale (`fr_FR`, for instance — the map keys `fr`, not `fr_FR`) renders the stored text verbatim, which is
 * why this is a lookup that can miss rather than a cascade with a default.
 *
 * Measured on both kernel codegen strategies across all nine tags plus one unmapped control; the table below
 * is that measurement, not a transcription. Three things it deliberately does NOT do, each measured:
 *
 *  * **TIME is never re-presented** — its format is the same in every entry, so a time renders as stored.
 *  * **An unfilled field's DEFAULT is never presented.** A Number's `0.00` stays `0.00` under `de_DE`, whose
 *    filled values use a comma; the default is chosen by the field format, downstream of this.
 *  * **Fractional digits are preserved, never padded.** A stored `1234.5` renders `1,234.5`, not `1,234.50`.
 */
internal data class DisplayPresentation(
    val datePattern: String,
    val decimalSeparator: Char,
    val thousandsSeparator: Char,
) {
    internal companion object {
        private val EN = DisplayPresentation("MM/dd/yyyy", '.', ',')
        private val DE = DisplayPresentation("dd.MM.yyyy", ',', '.')
        private val CH = DisplayPresentation("dd.MM.yyyy", '.', '\'')
        private val FR = DisplayPresentation("dd/MM/yyyy", ',', ' ')
        private val GB = DisplayPresentation("dd/MM/yyyy", '.', ',')
        private val NL = DisplayPresentation("dd-MM-yyyy", ',', '.')

        /** The exact tags the kernel maps. A miss is the contract, not a gap — see the class KDoc. */
        private val BY_LOCALE = mapOf(
            "en" to EN, "en_US" to EN, "us" to EN,
            "de" to DE, "de_DE" to DE,
            "ch" to CH, "fr" to FR, "gb" to GB, "nl" to NL,
        )

        fun of(locale: String): DisplayPresentation? = BY_LOCALE[locale]
    }
}

/**
 * The DATE and DATE_TIME halves join their date and time with a SPACE, and a DATE_RANGE joins its two
 * endpoints with one too — replacing whatever separator the field declares. Both are fixed across every
 * locale entry, so they are constants here rather than presentation slots.
 */
private const val PRESENTED_JOIN = " "

/**
 * [stored] re-presented for [presentation], reading its components out of the field's [declaredFormat].
 * Returns [stored] unchanged whenever the kind is not presented, the value does not match its declared
 * format, or no presentation applies — the kernel's own behaviour on a value it cannot convert.
 */
internal fun presentDisplayValue(
    stored: String,
    kind: Kind,
    declaredFormat: String?,
    rangeSeparator: String?,
    presentation: DisplayPresentation,
): String = when (kind) {
    Kind.DATE ->
        presentDate(stored, declaredFormat, presentation) ?: stored
    Kind.DATE_TIME ->
        presentDateTime(stored, declaredFormat, presentation) ?: stored
    Kind.DATE_RANGE ->
        presentRange(stored, declaredFormat, rangeSeparator, presentation) ?: stored
    Kind.NUMBER -> presentNumber(stored, presentation)
    else -> stored
}

/**
 * A date's components are moved from the declared format's slots into the locale pattern's, **as text**: a
 * partially known value keeps its `00` markers and is never completed, so `2024-06-00` becomes `06/00/2024`
 * and not `06/01/2024`, the earliest date of the interval it denotes. Reading the components as strings
 * rather than as numbers is what makes that automatic.
 */
private fun presentDate(stored: String, declaredFormat: String?, p: DisplayPresentation): String? {
    val components = dateComponentsOf(stored, declaredFormat ?: return null) ?: return null
    return fillDatePattern(p.datePattern, components)
}

/** The date half is presented and rejoined to the untouched time half with a space, replacing any `T`. */
private fun presentDateTime(stored: String, declaredFormat: String?, p: DisplayPresentation): String? {
    val format = declaredFormat ?: return null
    val split = splitDateTime(stored, format) ?: return null
    val date = presentDate(split.first, dateHalfOf(format), p) ?: return null
    return date + PRESENTED_JOIN + split.second
}

/** Both endpoints are presented and rejoined with a space — the declared separator does not survive. */
private fun presentRange(
    stored: String,
    declaredFormat: String?,
    rangeSeparator: String?,
    p: DisplayPresentation,
): String? {
    val separator = rangeSeparator?.takeIf { it.isNotEmpty() } ?: return null
    val at = stored.indexOf(separator)
    if (at < 0) return null
    val from = presentDate(stored.substring(0, at), declaredFormat, p) ?: return null
    val to = presentDate(stored.substring(at + separator.length), declaredFormat, p) ?: return null
    return from + PRESENTED_JOIN + to
}

/**
 * Only the two separators change: the integer part is grouped in threes and the fraction is kept exactly as
 * stored. A value that is not plain decimal text is returned unchanged.
 */
private fun presentNumber(stored: String, p: DisplayPresentation): String {
    val negative = stored.startsWith("-")
    val body = if (negative || stored.startsWith("+")) stored.substring(1) else stored
    val dot = body.indexOf('.')
    val integer = if (dot < 0) body else body.substring(0, dot)
    val fraction = if (dot < 0) null else body.substring(dot + 1)
    if (integer.isEmpty() || !integer.all(DecimalDigits::isDigit) || fraction?.all(DecimalDigits::isDigit) == false) {
        return stored
    }
    val grouped = StringBuilder()
    for ((seen, digit) in integer.reversed().withIndex()) {
        if (seen > 0 && seen % 3 == 0) grouped.append(p.thousandsSeparator)
        grouped.append(digit)
    }
    val sign = if (negative) "-" else ""
    val tail = if (fraction == null) "" else p.decimalSeparator + fraction
    return sign + grouped.reverse().toString() + tail
}

/** The `y`/`M`/`d` runs of [format], each paired with the exact text [stored] carries in that slot. */
private fun dateComponentsOf(stored: String, format: String): Map<Char, String>? {
    val components = mutableMapOf<Char, String>()
    var atFormat = 0
    var atValue = 0
    while (atFormat < format.length) {
        val c = format[atFormat]
        if (c == 'y' || c == 'M' || c == 'd') {
            var end = atFormat
            while (end < format.length && format[end] == c) end++
            val width = end - atFormat
            if (atValue + width > stored.length) return null
            val text = stored.substring(atValue, atValue + width)
            if (!text.all(DecimalDigits::isDigit)) return null
            components[c] = text
            atFormat = end
            atValue += width
        } else {
            if (atValue >= stored.length || stored[atValue] != c) return null
            atFormat++
            atValue++
        }
    }
    return if (atValue == stored.length && components.isNotEmpty()) components else null
}

/** [pattern]'s runs filled from [components], each padded or trimmed to the run's width. */
private fun fillDatePattern(pattern: String, components: Map<Char, String>): String {
    val out = StringBuilder()
    var at = 0
    while (at < pattern.length) {
        val c = pattern[at]
        if (c == 'y' || c == 'M' || c == 'd') {
            var end = at
            while (end < pattern.length && pattern[end] == c) end++
            val width = end - at
            val text = components[c] ?: "0".repeat(width)
            out.append(if (text.length >= width) text.takeLast(width) else text.padStart(width, '0'))
            at = end
        } else {
            out.append(c)
            at++
        }
    }
    return out.toString()
}

/** The date-bearing prefix of a DATE_TIME format, i.e. everything before its first time component. */
private fun dateHalfOf(format: String): String? {
    val at = format.indexOfFirst { it == 'H' || it == 'm' || it == 's' }
    if (at <= 0) return null
    return format.substring(0, at).trimEnd('\'', 'T', ' ')
}

/**
 * [stored] split into its date and time halves. Every `y`/`M`/`d` run and literal is fixed-width, so the date
 * half occupies exactly as many characters as the date format is long, and the single character after it is
 * the boundary the format spells (`'T'` or a space) whichever one this value carries.
 */
private fun splitDateTime(stored: String, format: String): Pair<String, String>? {
    val width = (dateHalfOf(format) ?: return null).length
    if (width + 1 >= stored.length) return null
    return stored.substring(0, width) to stored.substring(width + 1)
}
