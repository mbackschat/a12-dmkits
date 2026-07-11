package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform (JVM + JS) lock for the FRAGMENT-format DATE_RANGE EVAL read (IF118) — the PURE interpreter
 * (parser + eval), so the year-range endpoint completion runs on Kotlin/JS too. Before IF118 a `yyyy` range
 * resolved `Val.Unknown` (the range side-parse required all of y/M/d) and any rule reading it was suppressed.
 * The values are the ones the kernel differential pins in `adapter.laws.FragmentRangeEvalDiffTest`: a `yyyy`
 * range's start completes to the interval's earliest date (01-01) and its end to the latest (12-31). Own
 * domain (travel shop) `Trip/Season` (`yyyy`/`/`) + a full-format `Trip/Window` (`dd.MM.yyyy`/`-`).
 */
class FragmentRangeEvalTest {

    private val SEASON = "/Trip/Season"
    private val OTHER = "/Trip/OtherSeason"
    private val WINDOW = "/Trip/Window"

    private fun model(cond: String) = ModelDef(
        fields = listOf(
            FieldDef(SEASON, Kind.DATE_RANGE, format = "yyyy", rangeSeparator = "/"),
            FieldDef(OTHER, Kind.DATE_RANGE, format = "yyyy", rangeSeparator = "/"),
            FieldDef(WINDOW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
        ),
        rules = listOf(RuleDef("/Trip/RR", "/Trip", cond, "RR", "../Season")),
    )

    private fun fires(cond: String, vararg cells: Pair<String, String>): Boolean {
        val m = model(cond)
        val doc: Document = m.newDocument().group("/Trip", 1)
        cells.forEach { (path, v) -> doc.field(path, v, 1, 1) }
        return DmInterpreter(m).validateFull(doc).any { it.code == "RR" }
    }

    // per-side year: start = first year, end = second year
    @Test fun startYearIsFirst() =
        assertEquals(true, fires("YearFromDate(StartOfDateRange(/Trip/Season)) == 2024", SEASON to "2024/2025"))

    @Test fun endYearIsSecond() =
        assertEquals(true, fires("YearFromDate(EndOfDateRange(/Trip/Season)) == 2025", SEASON to "2024/2025"))

    @Test fun endYearIsNotFirst() =
        assertEquals(false, fires("YearFromDate(EndOfDateRange(/Trip/Season)) == 2024", SEASON to "2024/2025"))

    // sub-year completion, observed through overlap with a full-format window at the year's far ends
    @Test fun endReachesLateDecember() =
        assertEquals(true, fires("DateRangesOverlap(/Trip/Season, /Trip/Window)",
            SEASON to "2024/2024", WINDOW to "20.12.2024-25.12.2024"))

    @Test fun startReachesEarlyJanuary() =
        assertEquals(true, fires("DateRangesOverlap(/Trip/Season, /Trip/Window)",
            SEASON to "2024/2024", WINDOW to "01.01.2024-10.01.2024"))

    @Test fun doesNotReachAnotherYear() =
        assertEquals(false, fires("DateRangesOverlap(/Trip/Season, /Trip/Window)",
            SEASON to "2024/2024", WINDOW to "05.01.2025-10.01.2025"))

    // overlap over two year-ranges, both polarities
    @Test fun overlapFiresOnSharedYear() =
        assertEquals(true, fires("DateRangesOverlap(/Trip/Season, /Trip/OtherSeason)",
            SEASON to "2024/2025", OTHER to "2025/2026"))

    @Test fun overlapSilentOnDisjointYears() =
        assertEquals(false, fires("DateRangesOverlap(/Trip/Season, /Trip/OtherSeason)",
            SEASON to "2024/2024", OTHER to "2026/2026"))
}
