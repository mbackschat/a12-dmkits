package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Differential — **a YEARLESS stored day is bounded by its month's length in the DECLARED Base Year, not by
 * the month's greatest possible day.** Reproduces a12-kernel-lean's `SPEC-2026-09-05-12` on an independently
 * authored carrier and measures it here rather than citing their report: an inbound observation is theirs, and
 * a fact this repository's own artifacts rest on is measured in this repository.
 *
 * **The separator is which of two things the year does.** A `MM-dd` cell holding `02-29` is admissible if the
 * bound is February's greatest possible length (29, in some year) and inadmissible if it is February's length
 * in the year the model actually anchors to. Holding the document and the declaration fixed and moving only
 * the Base Year answers it, and the UNDECLARED configuration is what places the difference in the anchor
 * year's leapness rather than in declaring a Base Year at all: it behaves like the leap case, because the
 * anchor a base-year-free model falls back to is itself a leap year.
 *
 * **The century pair is what separates the rule from a `year % 4` test**, which agrees with it on 2023/2024 and
 * disagrees on 1900 — a year divisible by four and not a leap year. February is the only month whose two
 * candidate bounds differ, so one witness settles the whole rule; `02-28` and an ordinary March day are
 * carried as the crossing controls under every configuration.
 *
 * Own domain (personal-lines insurance renewals).
 */
// One instance per CLASS: five models means five kernel codegen runs and five model loads, and this Gradle task
// also carries clock-reading assertions (CLAUDE.md: a new test must not perturb a timing floor sharing its task).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class YearlessDayBoundBaseYearDiffTest {

    private companion object {
        const val YEARLESS_FORMAT = "MM-dd"
        const val COVER_DAY = "/Policy/CoverDay"

        /** The one witness: February 29 is admissible in a leap anchor year and unreal in any other. */
        const val LEAP_DAY = "02-29"

        /** The crossing controls — real in every year, so they must cross under every configuration. */
        const val ORDINARY_FEBRUARY_DAY = "02-28"
        const val ORDINARY_MARCH_DAY = "03-15"

        const val MALFORMED = "datumFormatFalsch"

        /** [baseYear] null declares none at all, which is its own configuration rather than a missing one. */
        fun model(baseYear: Int?): String {
            val draft = DocumentModelDraft.named("renewal-day-${baseYear ?: "undeclared"}").locale("en_US")
                .rootGroup("Policy") { p -> p.dateField("CoverDay") { it.format(YEARLESS_FORMAT) } }
            return (if (baseYear == null) draft else draft.baseYear(baseYear)).build().toJson()
        }
    }

    private val models = HashMap<Int?, String>()
    private val java = HashMap<String, RuntimeLaws.JavaKernelEngine>()
    private val loaded = HashMap<String, io.github.mbackschat.a12.dm.interpreter.model.ModelDef>()

    private fun modelFor(baseYear: Int?) = models.getOrPut(baseYear) { model(baseYear) }

    /**
     * The declarations every row rests on, read out of the model bytes. A draft that silently dropped the base
     * year, or normalized the yearless format, would make the whole grid a measurement of one configuration.
     */
    @Test
    fun theModelsDifferInTheBaseYearAndNothingElseThatMatters() {
        assertTrue(Regex("\"baseYear\"\\s*:\\s*\"?2023\"?").containsMatchIn(modelFor(2023)),
            "the 2023 model must declare it, or its refusal is not about a base year")
        assertFalse(Regex("\"baseYear\"").containsMatchIn(modelFor(null)),
            "the undeclared model must declare NO base year, or it is not the third configuration")
        for (year in listOf(2023, 2024, null)) {
            assertTrue(Regex("\"$YEARLESS_FORMAT\"").containsMatchIn(modelFor(year)),
                "every model must declare the yearless format, or the day bound is not in question")
        }
    }

    /**
     * **The whole claim as one table.** The identical document under three configurations: the leap day is
     * malformed under a non-leap anchor and admitted under a leap one, and the undeclared configuration agrees
     * with the leap case.
     */
    @Test
    fun theLeapDayIsBoundedByItsMonthLengthInTheDeclaredBaseYear() {
        assertEquals(
            mapOf(
                "base year 2023 (non-leap)" to listOf(MALFORMED),
                "base year 2024 (leap)" to emptyList(),
                "no base year declared" to emptyList(),
            ),
            mapOf(
                "base year 2023 (non-leap)" to triCodes(2023, LEAP_DAY),
                "base year 2024 (leap)" to triCodes(2024, LEAP_DAY),
                "no base year declared" to triCodes(null, LEAP_DAY),
            ),
            "the yearless day bound reads the anchor year's February, not February's greatest length",
        )
    }

    /** The century pair, which a `year % 4` bound gets wrong on 1900 and right on 2000. */
    @Test
    fun theGregorianCenturyRuleAndNotADivisibilityTestDecidesIt() {
        assertEquals(listOf(MALFORMED), triCodes(1900, LEAP_DAY),
            "1900 is divisible by four and is not a leap year")
        assertEquals(emptyList<String>(), triCodes(2000, LEAP_DAY),
            "2000 is, so the bound is the Gregorian rule rather than a divisibility test")
    }

    /**
     * The crossing controls. Without them every empty list above is consistent with "this cell is never
     * checked", and every refusal with "this declaration refuses everything".
     */
    @Test
    fun anOrdinaryDayCrossesUnderEveryConfiguration() {
        for (year in listOf(2023, 2024, null, 1900, 2000)) {
            for (day in listOf(ORDINARY_FEBRUARY_DAY, ORDINARY_MARCH_DAY)) {
                assertEquals(emptyList<String>(), triCodes(year, day),
                    "$day must cross under base year ${year ?: "<undeclared>"}")
            }
        }
        assertEquals(listOf(MALFORMED), triCodes(2024, "02-30"),
            "and a day beyond February's length in a LEAP year is still refused, or the bound is not a bound")
    }

    // ---- the instrument ----

    /** The formal codes for one stored [value], agreed by dynamic Groovy, generated Java and the interpreter. */
    private fun triCodes(baseYear: Int?, value: String): List<String> {
        val model = modelFor(baseYear)
        val groovy = RuntimeLaws.messages(model, kernelDoc(value)).map { it.code() }.sorted()
        val engine = java.getOrPut(model) { RuntimeLaws.kernelJavaEngine(model) }
        assertEquals(groovy, engine.firedCodesSorted(engine.instance(kernelDoc(value))),
            "generated-Java vs normative dynamic-Groovy on '$value' under ${baseYear ?: "<undeclared>"}")
        val def = loaded.getOrPut(model) { ModelLoader.load(model) }
        val doc = def.newDocument().group("/Policy", 1).field(COVER_DAY, value, 1, 1)
        assertEquals(groovy, DmInterpreter(def).validateFull(doc).map { it.code }.sorted(),
            "interpreter vs normative dynamic-Groovy on '$value' under ${baseYear ?: "<undeclared>"}")
        return groovy
    }

    private fun kernelDoc(value: String): RuntimeLaws.Doc =
        RuntimeLaws.doc().group("/Policy", 1).field(COVER_DAY, value, 1, 1)
}
