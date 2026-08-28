package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.FORMAL_RULE_PATH
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for KF211 — **a temporal cell's formal classifier is selected by its DECLARED
 * FORMAT, never by the field's kind.** The kernel's format vocabulary is kind-independent (KF206: a TIME field
 * is refused `HH:mm` and admitted `yyyy-MM-dd`), and the value tier follows the same rule: measured over the
 * complete 8-format × 3-kind grid, every one of the 24 cells is admitted at the model level and every format
 * behaves identically on all three date-bearing kinds.
 *
 * The two sharpest cells have their own named arms below, because they are the ones the kind-keyed reading gets
 * backwards in opposite directions: a TIME field carrying the Gregorian floor, and a DATE field admitting a
 * clock. Both were wrong here until 2026-08-28 (IG144, and its unreported mirror).
 *
 * Kernel oracle for every expectation: `adapter.laws.TemporalDeclaredFormatGateDiffTest`, on both codegen
 * strategies. The kind still owns the DEFAULT format for a field that declares none.
 */
class TemporalDeclaredFormatFormalTest {

    private companion object {
        const val FMT = "datumFormatFalsch"
        const val DATE = "datumFalsch"
        val DATE_BEARING_KINDS = listOf(Kind.DATE, Kind.TIME, Kind.DATE_TIME)
    }

    private fun code(kind: Kind, format: String, value: String): String? {
        val field = FieldDef("/G/F", kind, format = format)
        val m = ModelDef(listOf(field), emptyList())
        val doc = m.newDocument().group("/G", 1).field(field.path, value, 1, 1)
        return DmInterpreter(m).validateFull(doc).firstOrNull { it.rulePath == FORMAL_RULE_PATH }?.code
    }

    /** One stored value and the cause its format assigns it, whatever kind declares that format. */
    private class Cell(val value: String, val expected: String?)

    private val grid: List<Pair<String, List<Cell>>> = listOf(
        "yyyy-MM-dd" to listOf(
            Cell("2024-06-15", null), Cell("1583-10-16", null), Cell("1583-10-15", DATE),
            Cell("2024-02-31", FMT), Cell("10:30:00", FMT),
        ),
        "dd.MM.yyyy" to listOf(
            Cell("15.06.2024", null), Cell("16.10.1583", null), Cell("15.10.1583", DATE),
            Cell("31.02.2024", FMT), Cell("10:30:00", FMT),
        ),
        "HH:mm:ss" to listOf(
            Cell("10:30:00", null), Cell("24:00:00", FMT), Cell("2024-06-15", FMT), Cell("1583-10-15", FMT),
        ),
        "yyyy-MM-dd'T'HH:mm:ss" to listOf(
            Cell("2024-06-15T10:30:00", null), Cell("1583-10-15T00:00:00", DATE),
            Cell("2024-06-15", FMT), Cell("10:30:00", FMT),
        ),
        "MM" to listOf(Cell("06", null), Cell("13", FMT), Cell("00", FMT), Cell("2024-06-15", FMT)),
        "yyyy" to listOf(
            Cell("2024", null), Cell("1583", DATE), Cell("1582", DATE), Cell("0000", FMT), Cell("10:30:00", FMT),
        ),
        "yyyy-MM" to listOf(
            Cell("2024-06", null), Cell("1583-10", DATE), Cell("202406", FMT), Cell("2024-13", FMT),
        ),
        "MM-dd" to listOf(Cell("06-15", null), Cell("02-30", FMT), Cell("04-31", FMT), Cell("02-29", null)),
    )

    @Test fun everyDateBearingKindReadsItsDeclaredFormat() {
        for ((format, cells) in grid) {
            for (kind in DATE_BEARING_KINDS) {
                for (cell in cells) {
                    assertEquals(cell.expected, code(kind, format, cell.value),
                        "$kind declared $format storing '${cell.value}'")
                }
            }
        }
    }

    @Test fun aTimeFieldDeclaredWithADateFormatCarriesTheGregorianFloor() {
        assertEquals(DATE, code(Kind.TIME, "yyyy-MM-dd", "1583-10-15"), "one day below the floor")
        assertEquals(null, code(Kind.TIME, "yyyy-MM-dd", "1583-10-16"), "the floor itself is admitted")
        assertEquals(null, code(Kind.TIME, "yyyy-MM-dd", "2024-06-15"), "an ordinary date is admitted")
        assertEquals(FMT, code(Kind.TIME, "yyyy-MM-dd", "10:30:00"), "its own kind's spelling is the format error")
    }

    /**
     * Year ZERO is an unreal component rather than a date below the floor, so it takes the FORMAT cause while
     * year one takes the date cause. Measured uniform across every date-bearing format and all three kinds; we
     * reported the floor for it, which is the right polarity under the wrong code, and the grid above is what
     * turned it up.
     */
    @Test fun yearZeroIsTheFormatCauseAndYearOneIsTheDateCause() {
        for (kind in DATE_BEARING_KINDS) {
            assertEquals(FMT, code(kind, "yyyy", "0000"), "$kind: a year-only zero")
            assertEquals(DATE, code(kind, "yyyy", "0001"), "$kind: year one is real and below the floor")
            assertEquals(FMT, code(kind, "yyyy-MM", "0000-06"), "$kind: zero year in a year-month")
            assertEquals(FMT, code(kind, "yyyy-MM-dd", "0000-06-15"), "$kind: zero year in a complete date")
            assertEquals(DATE, code(kind, "yyyy-MM-dd", "0001-06-15"), "$kind: year one, complete")
            assertEquals(FMT, code(kind, "dd.MM.yyyy", "15.06.0000"), "$kind: zero year, day-first")
            assertEquals(FMT, code(kind, "yyyy-MM-dd'T'HH:mm:ss", "0000-06-15T10:30:00"), "$kind: zero year, instant")
            assertEquals(DATE, code(kind, "yyyy-MM-dd'T'HH:mm:ss", "0001-06-15T10:30:00"), "$kind: year one, instant")
        }
    }

    @Test fun aDateFieldDeclaredWithTheClockFormatAdmitsAClockAndNeverTheDateError() {
        assertEquals(null, code(Kind.DATE, "HH:mm:ss", "10:30:00"), "the mirror case: a DATE declaring a clock")
        assertEquals(FMT, code(Kind.DATE, "HH:mm:ss", "2024-06-15"), "a date is the format error there")
        assertEquals(FMT, code(Kind.DATE, "HH:mm:ss", "1583-10-15"), "no position in time, so never the date error")
    }
}
