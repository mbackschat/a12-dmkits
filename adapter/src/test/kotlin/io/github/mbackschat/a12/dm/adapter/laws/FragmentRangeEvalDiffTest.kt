package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the EVAL read of a FRAGMENT-format DATE_RANGE (`yyyy` year-range like `2024/2025`), the twin
 * of [FragmentRangeFormalDiffTest]'s FORMAL side (IF112). The kernel completes the two endpoints ASYMMETRICALLY
 * (`RuntimeController.datesAndFormatsToDateRange`: `INTERVAL_START` for the start, `INTERVAL_END` for the end —
 * KERNEL-FINDINGS §6): a `yyyy` range's start fills to 01-01 and its end to the year's LAST day (12-31). Before
 * IF118 the interpreter's range side-parse required all of y/M/d, so such a value resolved `Val.Unknown` and a
 * rule reading it was silently SUPPRESSED where the kernel evaluates.
 *
 * Sub-year extraction (`MonthFromDate`/`DayFromDate`) over a `yyyy`-granularity range is AUTHORING-rejected by
 * the kernel (the fragment granularity gate — only `YearFromDate` is legal there), so the per-side YEAR is
 * pinned by `YearFromDate`, and the sub-year 01-01 / 12-31 completion is pinned OBSERVABLY by overlap against a
 * full-format `dd.MM.yyyy` window that falls at the far ends of the year. Own domain (travel shop). Conditions
 * are authored with the rulekit typed DSL and rendered to the string fed to BOTH engines.
 */
class FragmentRangeEvalDiffTest {

    private val SEASON = "/Trip/Season"
    private val OTHER = "/Trip/OtherSeason"
    private val WINDOW = "/Trip/Window"

    private val base = DocumentModelDraft.named("tripseasons").locale("en_US")
        .rootGroup("Trip") { t ->
            t.dateRangeField("Season") { it.format("yyyy").separator("/") }
            t.dateRangeField("OtherSeason") { it.format("yyyy").separator("/") }
            t.dateRangeField("Window") { it.format("dd.MM.yyyy").separator("-") }
        }.build().toJson()

    private val fields = listOf(
        FieldDef(SEASON, Kind.DATE_RANGE, format = "yyyy", rangeSeparator = "/"),
        FieldDef(OTHER, Kind.DATE_RANGE, format = "yyyy", rangeSeparator = "/"),
        FieldDef(WINDOW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
    )

    private val m = BoundModel.ofJson(base)
    private fun render(c: Condition): String = DslRenderer().render(c)

    /** [cond] (error code `RR`, entity `../Season`) over the given filled range cells: the kernel AND the
     *  interpreter must BOTH fire iff [expected] — pinning the completion, not just parity. */
    private fun agree(cond: String, expected: Boolean, msg: String, vararg cells: Pair<String, String>) {
        val kModel = RuntimeLaws.withRule(base, "/Trip", "RR", "../Season", "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Trip/RR", "/Trip", cond, "RR", "../Season")))
        val kDoc = RuntimeLaws.doc().group("/Trip", 1)
        val iDoc = def.newDocument().group("/Trip", 1)
        cells.forEach { (path, v) -> kDoc.field(path, v, 1, 1); iDoc.field(path, v, 1, 1) }
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(expected, kernel, "KERNEL: $msg  (got kernel=$kernel)")
        assertEquals(expected, interp, "INTERP: $msg  (got interp=$interp)")
    }

    // ---- per-side YEAR resolves: start = first year, end = second year -------------------------------------

    @Test fun startYearIsTheFirstYear() =
        agree(render(Dsl.yearFromDate(Dsl.startOfDateRange(m.dateRange(SEASON))).eq(2024)), true,
            "start year of 2024/2025 is 2024", SEASON to "2024/2025")

    @Test fun endYearIsTheSecondYear() =
        agree(render(Dsl.yearFromDate(Dsl.endOfDateRange(m.dateRange(SEASON))).eq(2025)), true,
            "end year of 2024/2025 is 2025", SEASON to "2024/2025")

    @Test fun endYearIsNotTheFirstYear() =
        agree(render(Dsl.yearFromDate(Dsl.endOfDateRange(m.dateRange(SEASON))).eq(2024)), false,
            "end year of 2024/2025 is not 2024", SEASON to "2024/2025")

    // ---- the END completes to the year's LAST day (12-31), the START to its FIRST (01-01) ------------------
    // Observable via overlap with a full-format window at the far ends of the year: a `2024/2024` season must
    // span all of 2024 (2024-01-01 .. 2024-12-31), so a late-December window overlaps it (end → 12-31) and an
    // early-January one overlaps it (start → 01-01). A 01-01 end completion would miss the December window.

    @Test fun endReachesLateDecember() =
        agree(render(Dsl.dateRangesOverlap(m.field(SEASON), m.field(WINDOW))), true,
            "2024/2024 (season) covers a 20-25 Dec 2024 window ⇒ end completes to 12-31",
            SEASON to "2024/2024", WINDOW to "20.12.2024-25.12.2024")

    @Test fun startReachesEarlyJanuary() =
        agree(render(Dsl.dateRangesOverlap(m.field(SEASON), m.field(WINDOW))), true,
            "2024/2024 (season) covers a 01-10 Jan 2024 window ⇒ start completes to 01-01",
            SEASON to "2024/2024", WINDOW to "01.01.2024-10.01.2024")

    @Test fun seasonDoesNotReachIntoAnotherYear() =
        agree(render(Dsl.dateRangesOverlap(m.field(SEASON), m.field(WINDOW))), false,
            "2024/2024 (season) does not cover a Jan 2025 window (its end is 2024-12-31, not beyond)",
            SEASON to "2024/2024", WINDOW to "05.01.2025-10.01.2025")

    // ---- overlap over two year-ranges: both polarities -----------------------------------------------------

    @Test fun overlapFiresOnAnOverlappingYearRange() =
        agree(render(Dsl.dateRangesOverlap(m.field(SEASON), m.field(OTHER))), true,
            "2024/2025 overlaps 2025/2026 (they share year 2025)",
            SEASON to "2024/2025", OTHER to "2025/2026")

    @Test fun overlapSilentOnDisjointYearRanges() =
        agree(render(Dsl.dateRangesOverlap(m.field(SEASON), m.field(OTHER))), false,
            "2024/2024 does not overlap 2026/2026 (disjoint years)",
            SEASON to "2024/2024", OTHER to "2026/2026")
}
