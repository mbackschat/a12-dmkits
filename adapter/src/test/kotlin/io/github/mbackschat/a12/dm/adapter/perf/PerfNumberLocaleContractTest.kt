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

    /**
     * Every locale-sensitive conversion, not just the `%.2f` shape.
     *
     * The narrow version of this pattern missed `%8.2f` and `%7.2f` — and missed them in a file the sweep had
     * otherwise visited, because the sweep was written from the same shape. A guard derived from the same reading
     * as the change it checks cannot catch that change's blind spot, so the pattern is written from the format
     * grammar instead: flags, then an optional width, then an optional precision, then a conversion whose
     * rendering depends on the locale. The space flag is deliberately excluded — it would make prose like
     * `100% faster` a match.
     */
    private val conversion = "%[-+0,(#]*[0-9]*(?:\\.[0-9]+)?[fFeEgG]|%[-+0(#]*,[0-9]*[dD]"
    private val floatLiteral = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*(?:$conversion)(?:[^\"\\\\\\n]|\\\\.)*\"")
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
        // Held close to the real count (55 at the time of writing) rather than at a round number well below it:
        // a loose floor tolerates a PARTIAL decay, which is exactly how the `%8.2f` shape stayed invisible.
        assertTrue(formatted.size >= 52,
            "expected at least 52 formatted locale-sensitive literals in the perf package, found ${formatted.size} " +
                "— the pattern has stopped matching some of them, so the check below is partly vacuous. If they " +
                "were genuinely deleted, lower this floor in the same commit")

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
     *
     * **The scan's recall limits, stated rather than discovered — the list is the point.** It sees only a literal
     * *immediately followed by* `.format(`, so it misses (a) a format string reached through a variable, as here;
     * (b) a literal passed as an ARGUMENT — `String.format(fmt, …)`, `"…".formatted(…)`, or a literal stored and
     * formatted elsewhere. Both are currently absent from this package (`grep` for `String.format`, `.formatted(`
     * → zero hits), and the cost of missing one is a mis-rendered log line, so the cheap textual rule is kept
     * rather than replaced by a parser. A third limit — conversions carrying a width, `%8.2f` — was **not** on
     * this list until it was found in the source, which is why the pattern is now written from the format grammar
     * instead of from the shapes the author happened to have in mind.
     */
    @Test
    fun theDefaultLocaleGenuinelyRendersADifferentNumber() {
        val twoPlaces = "%.2f"
        assertEquals("12.45", twoPlaces.format(Locale.ROOT, 12.45))
        assertEquals("12,45", twoPlaces.format(Locale.GERMANY, 12.45),
            "the failure this guard prevents is real on this project's own development machine")
    }
}
