package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the **date-range endpoint extractors** `StartOfDateRange` / `EndOfDateRange` over a
 * `DateRangeType` field (`CoverageWindow`, format `dd.MM.yyyy`, separator `-`). The interpreter must parse
 * the rendered range value (`01.06.2021-30.06.2021`) into its two ISO endpoints (format-aware, so the date
 * extractors read them like any date) and agree with the kernel on firing. (`DateRangesOverlap` and the
 * starred/`IN` forms are a follow-up; the format foundation lands here.)
 */
class DateRangeDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")
    private val fields = listOf(
        FieldDef(OD, Kind.DATE),
        FieldDef(CW, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-"),
    )

    /** `<cond>` over OrderDate filled + CoverageWindow = `coverage` — kernel and interpreter must agree on firing. */
    private fun agree(cond: String, coverage: String, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "RR", "../OrderDate", "RR", cond)
        val def = ModelDef(fields, listOf(RuleDef("/Order/RR", "/Order", cond, "RR", "../OrderDate")))

        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(CW, coverage, 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig)
            .field(OD, "2024-06-01", 1, 1).field(CW, coverage, 1, 1)
        val kernel = RuntimeLaws.fires(kModel, "RR", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "RR" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    private val cov = "01.06.2021-30.06.2021"   // 1 Jun 2021 .. 30 Jun 2021

    @Test fun startYearFires() = agree("FieldFilled(OrderDate) And YearFromDate(StartOfDateRange(CoverageWindow)) > 2020", cov, "start year 2021 > 2020")
    @Test fun startYearDoesNotFire() = agree("FieldFilled(OrderDate) And YearFromDate(StartOfDateRange(CoverageWindow)) > 2021", cov, "start year 2021 not > 2021")
    @Test fun startMonth() = agree("FieldFilled(OrderDate) And MonthFromDate(StartOfDateRange(CoverageWindow)) == 6", cov, "start month is 6")
    @Test fun endDayFires() = agree("FieldFilled(OrderDate) And DayFromDate(EndOfDateRange(CoverageWindow)) > 28", cov, "end day 30 > 28")
    @Test fun endDayDoesNotFire() = agree("FieldFilled(OrderDate) And DayFromDate(EndOfDateRange(CoverageWindow)) > 30", cov, "end day 30 not > 30")

    // ---- DateRangesOverlap(scalar, starred) — closed intervals, any-row overlap (KERNEL-FINDINGS §6) -----

    private val overlapFields = fields + FieldDef(IC, Kind.DATE_RANGE, format = "dd.MM.yyyy", rangeSeparator = "-")
    private val overlapCond = "FieldFilled(OrderDate) And DateRangesOverlap(CoverageWindow, Items*/ItemCoverage)"

    /** CoverageWindow vs N ItemCoverage rows — kernel and interpreter must agree on the overlap firing. */
    private fun overlapAgree(window: String, itemCoverages: List<String>, msg: String) {
        val kModel = RuntimeLaws.withRule(order, "/Order", "CC", "../OrderDate", "CC", overlapCond)
        val def = ModelDef(overlapFields, listOf(RuleDef("/Order/CC", "/Order", overlapCond, "CC", "../OrderDate")),
            repeatableGroups = setOf("/Order/Items"), declaredReps = mapOf("/Order/Items" to 50))
        val kDoc = RuntimeLaws.doc().group("/Order", 1).field(OD, "2024-06-01", 1, 1).field(CW, window, 1, 1)
        val iDoc = Document(def.kinds, def.declaredReps, def.unsignedFields, def.rangeConfig)
            .field(OD, "2024-06-01", 1, 1).field(CW, window, 1, 1)
        itemCoverages.forEachIndexed { i, ic ->
            kDoc.group("/Order/Items", 1, i + 1).field(IC, ic, 1, i + 1, 1)
            iDoc.group("/Order/Items", 1, i + 1).field(IC, ic, 1, i + 1, 1)
        }
        val kernel = RuntimeLaws.fires(kModel, "CC", kDoc)
        val interp = DmInterpreter(def).validateFull(iDoc).any { it.code == "CC" }
        assertEquals(kernel, interp, "$msg  (kernel=$kernel interp=$interp)")
    }

    @Test fun touchingRangesOverlap() = overlapAgree("01.01.2024-31.01.2024", listOf("31.01.2024-28.02.2024"), "touching ranges overlap (closed)")
    @Test fun backToBackDoNot() = overlapAgree("01.01.2024-31.01.2024", listOf("01.02.2024-28.02.2024"), "adjacent-day ranges do not overlap")
    @Test fun containedOverlaps() = overlapAgree("01.01.2024-31.12.2024", listOf("01.06.2024-30.06.2024"), "a contained range overlaps")
    @Test fun anyRowOverlaps() = overlapAgree("01.06.2024-30.06.2024", listOf("01.01.2025-31.01.2025", "15.06.2024-15.07.2024"), "overlaps any row (2nd)")
    @Test fun noRowOverlaps() = overlapAgree("01.06.2024-30.06.2024", listOf("01.01.2025-31.01.2025", "01.02.2025-28.02.2025"), "no row overlaps")

    private companion object {
        const val OD = "/Order/OrderDate"
        const val CW = "/Order/CoverageWindow"
        const val IC = "/Order/Items/ItemCoverage"
    }
}
