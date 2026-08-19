package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — a **WRAPPING yearless** date range, the one shape whose two endpoints do not share a year.
 *
 * A `DateRangeType` may declare `interpretationOfYear`, and the accepted values are `FROM` and `TO` (measured
 * 2026-08-19 through `model check`; the tool API's `STANDARD`/`PREVIOUS`/`SUBSEQUENT` spellings are all
 * refused, and STANDARD is expressed by omitting the property). `DateUtil.getSupplementaryYears` then breaks
 * the base-year default for a range that wraps: `FROM` moves the START back a year, `TO` moves the END forward
 * one, and only when `isYearNotBaseYear` says the value actually wraps.
 *
 * Without that, `01.11-31.01` reads as start 11-01 and end 01-31 of the SAME year — start after end — so the
 * value is reported invalid where the kernel accepts a perfectly ordinary winter season.
 */
class WrappingYearlessRangeDiffTest {

    /** The order fixture with its coverage window redeclared as the given range profile. */
    private fun model(format: String, separator: String, interpretation: String?): String {
        val declaration = buildString {
            append("\"format\": \"$format\",\n                      \"rangeSeparator\": \"$separator\"")
            if (interpretation != null) append(",\n                      \"interpretationOfYear\": \"$interpretation\"")
        }
        return RuntimeLaws.model("models/order.dm.json")
            .replace("\"format\": \"dd.MM.yyyy\",\n                      \"rangeSeparator\": \"-\"", declaration)
            .replace("\"format\": \"dd.MM.yyyy\",\n                            \"rangeSeparator\": \"-\"",
                declaration.replace("\n                      ", "\n                            "))
    }

    private fun agree(interpretation: String?, value: String,
                      format: String = "dd.MM", separator: String = "-") {
        val json = model(format, separator, interpretation)
        val kernelDocument = RuntimeLaws.doc().group("/Order", 1).field(WINDOW, value, 1, 1)
        val kernel = RuntimeLaws.messages(json, kernelDocument).map { it.code() }.sorted()

        val definition = ModelLoader.load(json)
        val document = definition.newDocument().group("/Order", 1).field(WINDOW, value, 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(document).map { it.code }.sorted()

        assertEquals(kernel, interpreted,
            "$format[$separator] interpretationOfYear=$interpretation value=\"$value\" " +
                "(kernel=$kernel interp=$interpreted)")
    }

    @Test fun aWrappingRangeIsValidUnderTo() = agree("TO", "01.11-31.01")

    @Test fun aWrappingRangeIsValidUnderFrom() = agree("FROM", "01.11-31.01")

    @Test fun anOrderedRangeIsUnaffectedByTo() = agree("TO", "01.03-30.06")

    @Test fun anOrderedRangeIsUnaffectedByFrom() = agree("FROM", "01.03-30.06")

    @Test fun aWrappingRangeWithoutAnInterpretationStaysInvalid() = agree(null, "01.11-31.01")

    @Test fun anOrderedRangeWithoutAnInterpretationIsValid() = agree(null, "01.03-30.06")

    // ── the other yearless profiles that can wrap ────────────────────────────────────────────────────────

    @Test fun aWrappingMonthRangeIsValidUnderTo() = agree("TO", "11/01", format = "MM", separator = "/")

    @Test fun aWrappingMonthRangeIsValidUnderFrom() = agree("FROM", "11/01", format = "MM", separator = "/")

    @Test fun aWrappingMonthRangeWithoutAnInterpretationStaysInvalid() =
        agree(null, "11/01", format = "MM", separator = "/")

    @Test fun aWrappingMonthDayRangeIsValidUnderTo() =
        agree("TO", "11-01/01-31", format = "MM-dd", separator = "/")

    @Test fun aWrappingMonthDayRangeWithoutAnInterpretationStaysInvalid() =
        agree(null, "11-01/01-31", format = "MM-dd", separator = "/")

    /** Does the shift rescue an INVERTED range whose years are written out? The kernel decides, not us. */
    @Test fun anInvertedExactRangeUnderTo() =
        agree("TO", "2024-11-01/2024-01-31", format = "yyyy-MM-dd", separator = "/")

    @Test fun anOrderedExactRangeUnderTo() =
        agree("TO", "2024-01-01/2024-12-31", format = "yyyy-MM-dd", separator = "/")

    /** A leap day is why the kernel probes the wrap with the fixed leap year 2000 rather than the base year. */
    @Test fun aWrappingRangeAcrossFebruary29UnderTo() = agree("TO", "01.11-29.02")

    /**
     * WHICH endpoint moves — the half a formal-error row cannot see. `FROM` says the base year labels the
     * range's from-year, so the END rolls forward; `TO` says it labels the to-year, so the START rolls back.
     * The order fixture's base year is 2020, and both engines must agree on all four endpoint years.
     */
    private fun agreeOnEndpointYear(interpretation: String, bound: String, year: Int, expected: Boolean) {
        val json = model("dd.MM", "-", interpretation)
        val condition = "YearFromDate($bound(CoverageWindow)) == $year"
        val ruleModel = RuntimeLaws.withRule(json, "/Order", "EY", "../CoverageWindow", "EY", condition)
        val kernelDocument = RuntimeLaws.doc().group("/Order", 1).field(WINDOW, WRAPPING, 1, 1)
        val kernel = RuntimeLaws.fires(ruleModel, "EY", kernelDocument)

        val definition = ModelLoader.load(ruleModel)
        val document = definition.newDocument().group("/Order", 1).field(WINDOW, WRAPPING, 1, 1)
        val interpreted = DmInterpreter(definition).validateFull(document).any { it.code == "EY" }

        assertEquals(expected, kernel, "KERNEL: $interpretation $bound == $year")
        assertEquals(expected, interpreted, "INTERPRETER: $interpretation $bound == $year")
    }

    @Test fun fromKeepsTheStartAtTheBaseYear() = agreeOnEndpointYear("FROM", "StartOfDateRange", 2020, true)

    @Test fun fromRollsTheEndForwardOneYear() = agreeOnEndpointYear("FROM", "EndOfDateRange", 2021, true)

    @Test fun fromDoesNotRollTheStartBack() = agreeOnEndpointYear("FROM", "StartOfDateRange", 2019, false)

    @Test fun toRollsTheStartBackOneYear() = agreeOnEndpointYear("TO", "StartOfDateRange", 2019, true)

    @Test fun toKeepsTheEndAtTheBaseYear() = agreeOnEndpointYear("TO", "EndOfDateRange", 2020, true)

    @Test fun toDoesNotRollTheEndForward() = agreeOnEndpointYear("TO", "EndOfDateRange", 2021, false)

    private companion object {
        const val WINDOW = "/Order/CoverageWindow"
        const val WRAPPING = "01.11-31.01"
    }
}
