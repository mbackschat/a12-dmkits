package io.github.mbackschat.a12.dm.adapter.perf

import java.io.File
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * **Every performance number this estate prints is formatted in [Locale.ROOT].**
 *
 * A perf line is read by a machine as often as by a person: `scripts/perf.sh` greps it, the progress log is
 * aggregated with `sort -n`/`awk`, and a maintainer copies a median out of it into the committed anchor. On a JVM
 * whose default locale is German — which is the machine this estate is developed on — a `%.2f` placeholder
 * formatted without a locale renders `12,45`, and `sort -n` reads that as `12`. That has already produced one
 * wrong growth median during a hand aggregation. `PerfAnchors` had reached the right answer for the committed
 * record and documented it there; the other 52 call sites had not, so the same run printed the same quantity two
 * different ways.
 *
 * The guard exists because the omission is invisible at the call site — it is what every neighbouring line used
 * to look like, no test fails, no output is malformed, and the number is simply wrong for whoever parses it next.
 *
 * **Scope: a literal that is actually formatted.** A `%.2f` in prose, in a doc comment, or in an assertion
 * message is not a defect, and a guard that flagged those would need an exemption for its own explanation —
 * which is how a guard starts decaying. So the rule is exactly the one worth stating: *if a literal carrying a
 * floating-point placeholder is passed to `format(`, that call names its locale.*
 */
class PerfNumberLocaleContractTest {

    private val floatLiteral = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*%\\.[0-9]+f(?:[^\"\\\\\\n]|\\\\.)*\"")
    private val formatCall = Regex("^\\s*\\.format\\(")
    private val localeFirst = Regex("^\\s*\\.format\\(\\s*Locale\\.ROOT\\b")

    private fun perfSources(): List<File> {
        var here: File? = File("").absoluteFile
        while (here != null) {
            val dir = File(here, "adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf")
            if (dir.isDirectory) return dir.listFiles { f: File -> f.name.endsWith(".kt") }!!.sortedBy { it.name }
            here = here.parentFile
        }
        error("cannot locate the perf sources from ${File("").absolutePath}")
    }

    /** Each formatted floating-point literal, as `file:line  literal`, paired with whether it names its locale. */
    private fun formattedLiterals(): List<Pair<String, Boolean>> = perfSources().flatMap { file ->
        val text = file.readText()
        floatLiteral.findAll(text).mapNotNull { match ->
            val tail = text.substring(match.range.last + 1, minOf(text.length, match.range.last + 81))
            if (!formatCall.containsMatchIn(tail)) {
                null
            } else {
                val where = "${file.name}:${text.take(match.range.first).count { it == '\n' } + 1}"
                "$where  ${match.value.take(72)}" to localeFirst.containsMatchIn(tail)
            }
        }
    }

    @Test
    fun everyFloatingPointPerfLiteralNamesTheLocaleItFormatsIn() {
        val formatted = formattedLiterals()

        // The denominator first: a scan that stopped matching would report zero offenders and read as a pass.
        assertTrue(formatted.size >= 40,
            "expected at least 40 formatted floating-point literals in the perf package, found ${formatted.size} " +
                "— the pattern has stopped matching, so the check below is vacuous")

        val offenders = formatted.filterNot { (_, namesLocale) -> namesLocale }.map { (where, _) -> where }
        assertEquals(emptyList<String>(), offenders,
            "these lines render a decimal in the JVM's default locale, so on a German-default machine they print " +
                "a comma where the committed record prints a point — and anything that parses the line reads the " +
                "comma as a terminator:\n  " + offenders.joinToString("\n  ") +
                "\n\nPass Locale.ROOT as the format call's first argument.")
    }

    /**
     * The rule it enforces, executed rather than asserted about — the German default is the machine's own.
     *
     * The placeholder is held in a `val` rather than written inline because the scan above is a source-text one:
     * an inline literal here would be a genuine hit on a control that must format in the wrong locale on purpose.
     * That is also the scan's recall limit, stated rather than discovered — a format string reached through a
     * variable is invisible to it. It is not currently used anywhere in this package, and the cost of missing one
     * is a mis-rendered log line, so the cheap textual rule is kept.
     */
    @Test
    fun theDefaultLocaleGenuinelyRendersADifferentNumber() {
        val twoPlaces = "%.2f"
        assertEquals("12.45", twoPlaces.format(Locale.ROOT, 12.45))
        assertEquals("12,45", twoPlaces.format(Locale.GERMANY, 12.45),
            "the failure this guard prevents is real on this project's own development machine")
    }
}
