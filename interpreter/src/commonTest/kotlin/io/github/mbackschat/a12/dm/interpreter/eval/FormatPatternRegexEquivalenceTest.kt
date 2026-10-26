package io.github.mbackschat.a12.dm.interpreter.eval

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks [FormatPattern] to the regex translation it replaced: one `(\d{n})` group per component run and every
 * other character escaped, matched whole. That translation is kept below as the oracle, unchanged, and both run
 * under the host's own regex engine — Java's on the JVM, the browser's on JS — so a divergence in what `\d` or
 * an escaped literal admits on either carrier fails here.
 */
class FormatPatternRegexEquivalenceTest {
    private val formats = listOf(
        "yyyy-MM-dd", "dd.MM.yyyy", "MM/dd/yyyy", "yyyy", "MM-dd", "yyyyMM", "dd.MM", "y-M-d",
        "yyyy-MM-dd'T'HH:mm:ss", "HH:mm:ss", "dd.MM.yyyy HH:mm", "yy''MM", "'at' HH", "'unclosed HH",
        "a+b(yyyy)[MM]*", "\\yyyy$", "yyyy1MM", "no component",
    )

    private val separators = listOf("-", " - ", "|", ".", "")

    @Test
    fun aDateFormatCapturesWhatItsRegexCaptured() {
        for (format in formats) {
            val pattern = dateFragment(format)
            val oracle = regexTranslation(format, withTime = false)
            assertEquals(oracle?.second, pattern?.components, format)
            for (raw in candidates(format)) {
                assertEquals(oracle?.let { matched(it.first, raw) }, pattern?.capture(raw), "$format ← '$raw'")
            }
        }
    }

    @Test
    fun aDateTimeFormatCapturesWhatItsRegexCaptured() {
        for (format in formats) {
            val pattern = dateTimeFragment(format)
            val oracle = regexTranslation(format, withTime = true)
            assertEquals(oracle?.second, pattern?.components, format)
            for (raw in candidates(format)) {
                assertEquals(oracle?.let { matched(it.first, raw) }, pattern?.capture(raw), "$format ← '$raw'")
            }
        }
    }

    @Test
    fun aFormatShapeReadsTheComponentsItsPatternCarries() {
        for (format in formats + listOf("'yyyy' HH", "HH 'mm'", "''", "'", "yyyy-MM-dd''HH")) {
            val comps = dateTimeFragment(format)?.components.orEmpty()
            val date = comps.any { it in "yMd" }
            val time = comps.any { it in "Hms" }
            val expected = when {
                date && time -> TemporalFormatShape.DATE_TIME
                time -> TemporalFormatShape.TIME
                else -> TemporalFormatShape.DATE
            }
            assertEquals(expected, temporalFormatShape(format), format)
        }
    }

    @Test
    fun aRangeCapturesWhatItsRegexCaptured() {
        for (format in formats) {
            val pattern = dateFragment(format) ?: continue
            val fragment = regexTranslation(format, withTime = false)!!.first
            for (separator in separators) {
                val ranged = pattern.around(separator)
                val oracle = "$fragment${Regex.escape(separator)}$fragment"
                for (one in candidates(format)) {
                    for (raw in listOf(one + separator + one, one + separator, one + one, separator + one)) {
                        assertEquals(matched(oracle, raw), ranged.capture(raw), "$format/$separator ← '$raw'")
                    }
                }
            }
        }
    }

    private fun matched(fragment: String, raw: String): List<String>? =
        Regex("^$fragment$").matchEntire(raw)?.groupValues?.drop(1)

    /** A value shaped like [format], then each mutation the walk must refuse or accept exactly as the regex did. */
    private fun candidates(format: String): List<String> {
        val shaped = format.replace("'", "").map { if (it in "yMdHms") '7' else it }.joinToString("")
        val mutations = listOf(
            shaped, "", shaped + "\n", shaped + "\r\n", " $shaped", shaped.dropLast(1), shaped + "7",
            shaped.replaceFirst('7', '٣'), shaped.replaceFirst('7', '７'), shaped.replaceFirst('7', 'x'),
            shaped.uppercase(), shaped.lowercase(), format,
        )
        val literalSwapped = shaped.indexOfFirst { it != '7' }.takeIf { it >= 0 }
            ?.let { shaped.substring(0, it) + '#' + shaped.substring(it + 1) }
        return (mutations + listOfNotNull(literalSwapped)).distinct()
    }

    /** The translation [FormatPattern] replaced, verbatim in behaviour: the reference, never production code. */
    private fun regexTranslation(format: String, withTime: Boolean): Pair<String, List<Char>>? {
        val components = if (withTime) "yMdHms" else "yMd"
        val sb = StringBuilder()
        val comps = mutableListOf<Char>()
        var i = 0
        while (i < format.length) {
            val c = format[i]
            when {
                c in components -> {
                    var j = i
                    while (j < format.length && format[j] == c) j++
                    sb.append("(\\d{").append(j - i).append("})")
                    comps.add(c)
                    i = j
                }
                withTime && c == '\'' -> {
                    i++
                    while (i < format.length && format[i] != '\'') { sb.append(Regex.escape(format[i].toString())); i++ }
                    if (i < format.length) i++
                }
                else -> { sb.append(Regex.escape(c.toString())); i++ }
            }
        }
        return if (comps.isEmpty()) null else sb.toString() to comps
    }
}
