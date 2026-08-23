package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DateFieldBuilder
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * A component-omitting `DateType` reports **ONE** formal cause for every spelling failure, and the
 * position-in-time cause never arises (a12-kernel-lean `SPEC-2026-08-23-15`, [KF196]).
 *
 * **Why one cause, structurally.** A complete date needs two causes because a well-spelled text can still fall
 * below the Gregorian floor — `datumFormatFalsch` for the spelling, `datumFalsch` for the position in time.
 * These formats carry no complete date, so nothing can fall below the floor: a wrong component width, a text
 * carrying components the format omits, an out-of-range month, and an unreal month-day all report the FORMAT
 * finding. A consumer that carried the two-cause split over emits the wrong code on every one of them.
 *
 * **The negative existential needs its positive control**, and [aCompleteFormatStillReachesThePositionCause]
 * is it: the same model's complete-format sibling DOES draw `datumFalsch` on a below-floor date, so the second
 * cause exists here and is simply unreachable for the five omitting formats. Without that row, "one cause"
 * would be equally satisfied by a fixture in which the second cause fires nowhere at all.
 *
 * **The separator is exact in BOTH directions**, which is what makes `yyyy-MM` and `yyyyMM` two formats rather
 * than one lenient parser tolerating an optional dash: each rejects the other's canonical text, and each is
 * measured against its own accepted text on the same row set.
 *
 * **A yearless day is leap-capable and bounded by the month's GREATEST possible day.** `02-29` is accepted and
 * `02-30` refused, so the day is not validated against any particular year's February — the same yearless
 * bound the DateRange overlap rule uses, measured here on its own carrier rather than transferred. `04-31` is
 * refused against `04-30` and `01-31` accepted, so the bound is per month rather than a flat 31.
 */
class IncompleteDateFormatCauseDiffTest {

    /** Every canonical text at once — the vacuity control: this fixture CAN be clean. */
    @Test
    fun everyCanonicalTextIsAccepted() {
        assertCodes(
            mapOf(
                YEAR to "2020", YEAR_MONTH to "2020-06", YEAR_MONTH_TIGHT to "202006",
                MONTH to "06", MONTH_DAY to "06-15", COMPLETE to "2020-06-15",
            ),
            expected = emptyList(),
        )
    }

    /** Empty stored text is present and value-free on every one of these formats. */
    @Test
    fun emptyTextIsValueFreeOnEveryOmittingFormat() {
        assertCodes(
            mapOf(YEAR to "", YEAR_MONTH to "", YEAR_MONTH_TIGHT to "", MONTH to "", MONTH_DAY to ""),
            expected = emptyList(),
        )
    }

    @Test
    fun aWrongComponentWidthIsTheFormatCause() {
        assertCodes(mapOf(YEAR to "20"), expected = listOf("$FORMAT@Year"))
    }

    @Test
    fun aTextCarryingComponentsTheFormatOmitsIsTheFormatCause() {
        assertCodes(mapOf(MONTH to "06-15"), expected = listOf("$FORMAT@Month"))
    }

    @Test
    fun anOutOfRangeMonthIsTheFormatCause() {
        assertCodes(mapOf(YEAR_MONTH to "2020-13"), expected = listOf("$FORMAT@YearMonth"))
    }

    @Test
    fun anUnrealMonthDayIsTheFormatCause() {
        assertCodes(mapOf(MONTH_DAY to "02-30"), expected = listOf("$FORMAT@MonthDay"))
    }

    // ── the separator is exact in both directions ───────────────────────────────────────────────────

    @Test
    fun theDashedYearMonthRejectsTheTightSpelling() {
        assertCodes(mapOf(YEAR_MONTH to "202006"), expected = listOf("$FORMAT@YearMonth"))
    }

    @Test
    fun theTightYearMonthRejectsTheDashedSpelling() {
        assertCodes(mapOf(YEAR_MONTH_TIGHT to "2020-06"), expected = listOf("$FORMAT@YearMonthTight"))
    }

    // ── a yearless day is leap-capable, and bounded by the month's greatest possible day ────────────

    @Test
    fun aYearlessFebruaryAdmitsTheTwentyNinth() {
        assertCodes(mapOf(MONTH_DAY to "02-29"), expected = emptyList())
    }

    @Test
    fun aYearlessFebruaryRefusesTheThirtieth() {
        assertCodes(mapOf(MONTH_DAY to "02-30"), expected = listOf("$FORMAT@MonthDay"))
    }

    @Test
    fun theYearlessDayBoundIsPerMonthRatherThanAFlatThirtyOne() {
        assertCodes(mapOf(MONTH_DAY to "04-30"), expected = emptyList())
        assertCodes(mapOf(MONTH_DAY to "04-31"), expected = listOf("$FORMAT@MonthDay"))
        assertCodes(mapOf(MONTH_DAY to "01-31"), expected = emptyList())
    }

    // ── the control that makes "one cause" a measurement rather than a fixture property ─────────────

    /** The complete-format sibling in the SAME model reaches the position-in-time cause on a below-floor date. */
    @Test
    fun aCompleteFormatStillReachesThePositionCause() {
        assertCodes(mapOf(COMPLETE to "1583-10-15"), expected = listOf("$POSITION@Complete"))
        assertCodes(mapOf(COMPLETE to "1583-10-16"), expected = emptyList())
    }

    private fun assertCodes(cells: Map<String, String>, expected: List<String>) {
        val definition = ModelLoader.load(MODEL)
        val kernelDoc = RuntimeLaws.doc().group(ROOT, 1)
        val interpretedDoc = definition.newDocument().group(ROOT, 1)
        for ((path, value) in cells) {
            kernelDoc.field(path, value, 1, 1)
            interpretedDoc.field(path, value, 1, 1)
        }
        fun short(field: String) = field.substringAfterLast('/')
        val dynamic = RuntimeLaws.messages(MODEL, kernelDoc).map { "${it.code()}@${short(it.field())}" }.sorted()
        val interpreted = DmInterpreter(definition).validateFull(interpretedDoc)
            .map { "${it.code}@${short(it.errorPath)}" }.sorted()
        assertEquals(expected.sorted(), dynamic, "kernel — $cells")
        assertEquals(dynamic, interpreted, "interpreter — $cells")
    }

    private companion object {
        const val ROOT = "/Policy"
        const val YEAR = "$ROOT/Year"
        const val YEAR_MONTH = "$ROOT/YearMonth"
        const val YEAR_MONTH_TIGHT = "$ROOT/YearMonthTight"
        const val MONTH = "$ROOT/Month"
        const val MONTH_DAY = "$ROOT/MonthDay"
        const val COMPLETE = "$ROOT/Complete"
        const val FORMAT = "datumFormatFalsch"
        const val POSITION = "datumFalsch"

        /** All five component-omitting formats beside a complete one, so every row shares one model. */
        val MODEL: String = DocumentModelDraft.named("policy").locale("en_US")
            .rootGroup("Policy", Consumer { g ->
                g.dateField("Year", Consumer<DateFieldBuilder> { it.format("yyyy") })
                g.dateField("YearMonth", Consumer<DateFieldBuilder> { it.format("yyyy-MM") })
                g.dateField("YearMonthTight", Consumer<DateFieldBuilder> { it.format("yyyyMM") })
                g.dateField("Month", Consumer<DateFieldBuilder> { it.format("MM") })
                g.dateField("MonthDay", Consumer<DateFieldBuilder> { it.format("MM-dd") })
                g.dateField("Complete", Consumer<DateFieldBuilder> { it.format("yyyy-MM-dd") })
            }).build().toJson()
    }
}
